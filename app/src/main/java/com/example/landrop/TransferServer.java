package com.example.landrop;

import android.content.Context;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 HTTP 文件传输服务。
 *
 * 接口：
 *   GET  /              返回浏览器操作页面（需 pin）
 *   GET  /files?pin=X   列出接收目录文件（JSON）
 *   GET  /download?name=文件名&pin=X   下载文件
 *   POST /upload?name=文件名&pin=X     上传原始二进制数据
 *
 * 安全：启动时生成 4 位配对码，所有请求必须携带正确 pin。
 * 限制：不支持分块传输；单文件上限 4 GiB；固定 4 线程池。
 */
public class TransferServer {

    private final Context context;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService workers;
    private Thread acceptThread;
    private String pin;

    private static final int PORT = 8765;
    private static final int BUFFER_SIZE = 32768;

    public TransferServer(Context context) {
        this.context = context.getApplicationContext();
    }

    /* ========== 生命周期 ========== */

    public synchronized void start() throws IOException {
        if (running) return;

        // 生成 4 位随机配对码
        pin = String.format(Locale.US, "%04d", new Random().nextInt(10000));

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(PORT));

        workers = Executors.newFixedThreadPool(4);
        running = true;

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        final Socket socket = serverSocket.accept();
                        socket.setSoTimeout(30000);
                        workers.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(socket);
                            }
                        });
                    } catch (IOException e) {
                        if (running) e.printStackTrace();
                    }
                }
            }
        }, "LanDrop-Accept");

        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        if (workers != null) workers.shutdownNow();
    }

    public boolean isRunning() {
        return running;
    }

    public String getPin() {
        return pin;
    }

    /* ========== 文件目录 ========== */

    private File getRoot() throws IOException {
        File root = context.getExternalFilesDir("received");
        if (root == null) {
            root = new File(context.getFilesDir(), "received");
        }
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("无法创建接收目录");
        }
        return root.getCanonicalFile();
    }

    /**
     * 安全文件名检查：阻止路径穿越攻击。
     */
    private File safeFile(String name) throws IOException {
        if (name == null || name.length() == 0
                || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0) {
            throw new IOException("非法文件名");
        }

        File root = getRoot();
        File file = new File(root, name).getCanonicalFile();

        if (!file.getParentFile().equals(root)) {
            throw new IOException("非法路径");
        }
        return file;
    }

    /* ========== 请求处理 ========== */

    private void handle(Socket socket) {
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            String requestLine = readLine(in);
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            if (parts.length != 3) {
                sendText(out, 400, "Bad Request", "请求格式错误");
                return;
            }

            String method = parts[0];
            URI uri = new URI(parts[1]);

            int contentLength = -1;
            String line;

            while ((line = readLine(in)) != null && line.length() > 0) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;

                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();

                if ("Content-Length".equalsIgnoreCase(key)) {
                    contentLength = Integer.parseInt(value);
                }

                if ("Transfer-Encoding".equalsIgnoreCase(key)
                        && value.toLowerCase(Locale.US).contains("chunked")) {
                    sendText(out, 501, "Not Implemented", "暂不支持分块传输");
                    return;
                }
            }

            String path = uri.getPath();
            String query = uri.getRawQuery();

            // —— 验证配对码 ——
            String pinParam = queryValue(query, "pin");
            if (pin == null || !pin.equals(pinParam)) {
                sendText(out, 403, "Forbidden",
                    "配对码错误。请在手机屏幕上查看完整地址。");
                return;
            }

            // —— 路由 ——

            if ("GET".equals(method) && "/".equals(path)) {
                sendText(out, 200, "OK", htmlPage(pin));
                return;
            }

            if ("GET".equals(method) && "/files".equals(path)) {
                sendText(out, 200, "OK", listFiles());
                return;
            }

            if ("GET".equals(method) && "/download".equals(path)) {
                String name = queryValue(query, "name");
                File file = safeFile(name);
                if (!file.isFile()) {
                    sendText(out, 404, "Not Found", "文件不存在");
                    return;
                }
                sendFile(out, file, name);
                return;
            }

            if ("POST".equals(method) && "/upload".equals(path)) {
                handleUpload(in, out, contentLength, query);
                return;
            }

            sendText(out, 404, "Not Found", "接口不存在");

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void handleUpload(InputStream in, OutputStream out,
                              int contentLength, String query) throws IOException {
        if (contentLength < 0) {
            sendText(out, 411, "Length Required", "需要 Content-Length");
            return;
        }

        if (contentLength > 4L * 1024 * 1024 * 1024) {
            sendText(out, 413, "Payload Too Large", "文件超过 4 GiB 限制");
            return;
        }

        String name = queryValue(query, "name");
        File target = safeFile(name);
        File temp = new File(getRoot(), UUID.randomUUID().toString() + ".part");

        boolean success = false;
        try {
            FileOutputStream fileOut = new FileOutputStream(temp);
            try {
                byte[] buffer = new byte[BUFFER_SIZE];
                int remaining = contentLength;

                while (remaining > 0) {
                    int n = in.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (n < 0) throw new EOFException("上传中断");
                    fileOut.write(buffer, 0, n);
                    remaining -= n;
                }
                fileOut.getFD().sync();
            } finally {
                fileOut.close();
            }

            if (target.exists()) {
                throw new IOException("目标文件已存在，拒绝覆盖");
            }
            if (!temp.renameTo(target)) {
                throw new IOException("无法保存文件");
            }
            success = true;
        } catch (Exception e) {
            sendText(out, 500, "Internal Error", "上传失败：" + e.getMessage());
            return;
        } finally {
            if (!success) temp.delete();
        }

        sendText(out, 200, "OK", "上传成功：" + target.getName());
    }

    /* ========== 响应工具 ========== */

    private String listFiles() throws IOException {
        File[] files = getRoot().listFiles();
        StringBuilder json = new StringBuilder("[");
        boolean first = true;

        if (files != null) {
            for (File file : files) {
                if (!file.isFile() || file.getName().endsWith(".part")) continue;
                if (!first) json.append(',');
                first = false;
                json.append("{\"name\":\"")
                    .append(jsonEscape(file.getName()))
                    .append("\",\"size\":")
                    .append(file.length())
                    .append('}');
            }
        }
        return json.append(']').toString();
    }

    private void sendFile(OutputStream out, File file, String name) throws IOException {
        // RFC 5987 编码：filename* 支持中文，filename 做 ASCII 回退
        String safeAscii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "");
        String encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        String disposition = "attachment; filename=\"" + safeAscii + "\"; "
            + "filename*=UTF-8''" + encoded;

        writeHeaders(out, 200, "OK", "application/octet-stream",
            file.length(), disposition);

        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            out.flush();
        } finally {
            in.close();
        }
    }

    private void sendText(OutputStream out, int code, String reason, String body) throws IOException {
        byte[] data = body.getBytes(Charset.forName("UTF-8"));
        writeHeaders(out, code, reason, "text/html; charset=UTF-8", data.length, null);
        out.write(data);
        out.flush();
    }

    private void writeHeaders(OutputStream out, int code, String reason,
                              String type, long length, String disposition) throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
         .append("Content-Type: ").append(type).append("\r\n")
         .append("Content-Length: ").append(length).append("\r\n")
         .append("Connection: close\r\n")
         .append("X-Content-Type-Options: nosniff\r\n");
        if (disposition != null) {
            h.append("Content-Disposition: ").append(disposition).append("\r\n");
        }
        h.append("\r\n");
        out.write(h.toString().getBytes(Charset.forName("US-ASCII")));
    }

    /* ========== 解析工具 ========== */

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > 8192) throw new IOException("HTTP 请求头过长");
        }
        if (c == -1 && line.size() == 0) return null;
        return new String(line.toByteArray(), Charset.forName("UTF-8"));
    }

    private String queryValue(String query, String key) throws IOException {
        if (query == null) return null;
        for (String item : query.split("&")) {
            String[] kv = item.split("=", 2);
            if (kv.length == 2
                    && key.equals(URLDecoder.decode(kv[0], "UTF-8"))) {
                return URLDecoder.decode(kv[1], "UTF-8");
            }
        }
        return null;
    }

    private String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /* ========== 网页前端 ========== */

    private String htmlPage(String pin) {
        return "<!doctype html><html><head>"
            + "<meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>LanDrop</title><style>"
            + "body{font-family:sans-serif;max-width:640px;margin:20px auto;padding:0 12px;color:#333}"
            + "h2{margin:0 0 12px}"
            + ".btn{padding:8px 18px;margin:4px 4px 4px 0;cursor:pointer;font-size:15px}"
            + "#list{list-style:none;padding:0;margin:8px 0}"
            + "#list li{padding:8px 0;border-bottom:1px solid #eee}"
            + "a{color:#06c;text-decoration:none}"
            + "a:hover{text-decoration:underline}"
            + "#status{color:#666;min-height:20px;margin:8px 0}"
            + "input[type=file]{margin:8px 0}"
            + "</style></head><body>"
            + "<h2>LanDrop 文件传输</h2>"
            + "<h3>上传到手机</h3>"
            + "<input id='f' type='file' multiple>"
            + "<button class='btn' onclick='upload()'>上传</button>"
            + "<p id='status'></p>"
            + "<h3>手机上的文件</h3>"
            + "<button class='btn' onclick='refresh()'>刷新列表</button>"
            + "<ul id='list'></ul>"
            + "<script>"
            + "var PIN='" + pin + "';"
            + "async function upload(){"
            + "var fs=document.getElementById('f').files;"
            + "var st=document.getElementById('status');"
            + "for(var i=0;i<fs.length;i++){"
            + "var f=fs[i];"
            + "st.textContent='正在上传 '+f.name+'...';"
            + "try{"
            + "var r=await fetch('/upload?name='+encodeURIComponent(f.name)+'&pin='+PIN,{method:'POST',body:f});"
            + "st.textContent=f.name+'：'+await r.text();"
            + "}catch(e){st.textContent=f.name+'：上传失败';}"
            + "}refresh();}"
            + "async function refresh(){"
            + "try{"
            + "var r=await fetch('/files?pin='+PIN);"
            + "var a=await r.json();"
            + "var l=document.getElementById('list');l.textContent='';"
            + "for(var i=0;i<a.length;i++){"
            + "var f=a[i];var li=document.createElement('li');"
            + "var x=document.createElement('a');"
            + "x.href='/download?name='+encodeURIComponent(f.name)+'&pin='+PIN;"
            + "x.textContent=f.name+' ('+sz(f.size)+')';"
            + "li.appendChild(x);l.appendChild(li);}"
            + "}catch(e){document.getElementById('status').textContent='刷新失败';}"
            + "}"
            + "function sz(b){"
            + "if(b<1024)return b+' B';"
            + "if(b<1048576)return (b/1024).toFixed(1)+' KB';"
            + "if(b<1073741824)return (b/1048576).toFixed(1)+' MB';"
            + "return (b/1073741824).toFixed(1)+' GB';}"
            + "refresh();"
            + "</script></body></html>";
    }
}
