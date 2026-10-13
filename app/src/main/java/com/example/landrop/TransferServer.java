package com.example.landrop;

import android.content.Context;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * LanDrop TCP 服务端。
 *
 * 监听固定端口 8765，处理所有入站请求：
 *   发现 / 配对 / 文件列表 / 下载 / 接收推送
 *
 * 配对机制：只有已配对的 IP 才能请求列表、下载、推送文件。
 * 未配对设备（如 c）看不到、也拿不到 a/b 之间传输的任何内容。
 */
public class TransferServer {

    private final Context context;
    private final ServerCallback callback;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService workers;
    private Thread acceptThread;
    private String myName;

    /** 已配对设备的 IP 集合（内存态，重启即清空）。 */
    private final Set<String> pairedIps =
            Collections.synchronizedSet(new HashSet<String>());

    /** 需要与 UI 线程交互的回调（弹窗确认）。 */
    public interface ServerCallback {
        /** 收到配对申请，返回是否同意（阻塞直到用户选择）。 */
        boolean onConnectRequest(String remoteName, String ip);
        /** 收到文件推送请求，返回是否接收。 */
        boolean onReceiveRequest(String remoteName, String filename, long size);
        /** 文件接收完成。 */
        void onFileReceived(String remoteName, String filename);
    }

    public TransferServer(Context context, ServerCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    /* ========== 生命周期 ========== */

    public synchronized void start(String name) throws IOException {
        if (running) return;
        this.myName = name;

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(Protocol.PORT));

        workers = Executors.newFixedThreadPool(4);
        running = true;

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Socket s = serverSocket.accept();
                        s.setSoTimeout(30000);
                        workers.execute(new Handler(s));
                    } catch (IOException e) {
                        if (running) e.printStackTrace();
                    }
                }
            }
        }, "LanDrop-Server");
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (workers != null) workers.shutdownNow();
    }

    public boolean isRunning() { return running; }
    public String getMyName() { return myName; }
    public boolean isPaired(String ip) { return pairedIps.contains(ip); }
    public void addPaired(String ip) { pairedIps.add(ip); }
    public void removePaired(String ip) { pairedIps.remove(ip); }

    public Set<String> getPairedIps() {
        synchronized (pairedIps) { return new HashSet<String>(pairedIps); }
    }

    /* ========== 接收目录 ========== */

    private File getDir() throws IOException {
        File d = context.getExternalFilesDir("landrop");
        if (d == null) d = new File(context.getFilesDir(), "landrop");
        if (!d.exists() && !d.mkdirs()) throw new IOException("无法创建目录");
        return d.getCanonicalFile();
    }

    /** 阻止路径穿越。 */
    private File safeFile(String name) throws IOException {
        if (name == null || name.length() == 0
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0) return null;
        File dir = getDir();
        File f = new File(dir, name).getCanonicalFile();
        if (!f.getParentFile().equals(dir)) return null;
        return f;
    }

    /* ========== 请求处理 ========== */

    private class Handler implements Runnable {
        private final Socket socket;
        Handler(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            String remoteIp = socket.getInetAddress().getHostAddress();
            try {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());

                String line = Protocol.readLine(in);
                if (line == null) return;

                String[] p = line.split(" ", 4);
                String cmd = p[0];

                if (Protocol.DISCOVER.equals(cmd)) {
                    // DISCOVER <name> -> HELLO <name>
                    Protocol.writeLine(out, Protocol.HELLO + " " + myName);

                } else if (Protocol.CONNECT.equals(cmd)) {
                    // CONNECT <name>
                    String remoteName = p.length > 1 ? p[1] : remoteIp;
                    boolean ok = callback.onConnectRequest(remoteName, remoteIp);
                    if (ok) {
                        pairedIps.add(remoteIp);
                        Protocol.writeLine(out, Protocol.ACCEPT);
                    } else {
                        Protocol.writeLine(out, Protocol.REJECT);
                    }

                } else if (Protocol.LIST.equals(cmd)) {
                    if (!pairedIps.contains(remoteIp)) {
                        Protocol.writeLine(out, Protocol.NO + " 未配对");
                        return;
                    }
                    File[] files = getDir().listFiles();
                    List<File> valid = new ArrayList<File>();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile() && !f.getName().endsWith(".part")) valid.add(f);
                        }
                    }
                    Protocol.writeLine(out, Protocol.FILES + " " + valid.size());
                    for (File f : valid) {
                        Protocol.writeLine(out,
                                Protocol.enc(f.getName()) + " " + f.length());
                    }

                } else if (Protocol.GET.equals(cmd)) {
                    if (!pairedIps.contains(remoteIp)) {
                        Protocol.writeLine(out, Protocol.NO + " 未配对");
                        return;
                    }
                    String name = p.length > 1 ? Protocol.dec(p[1]) : "";
                    File file = safeFile(name);
                    if (file == null || !file.isFile()) {
                        Protocol.writeLine(out, Protocol.NO + " 文件不存在");
                        return;
                    }
                    Protocol.writeLine(out, Protocol.FILE + " "
                            + Protocol.enc(file.getName()) + " " + file.length());
                    FileInputStream fin = new FileInputStream(file);
                    try {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = fin.read(buf)) != -1) out.write(buf, 0, n);
                        out.flush();
                    } finally {
                        fin.close();
                    }

                } else if (Protocol.SEND.equals(cmd)) {
                    // SEND <senderName> <enc(filename)> <size>
                    if (!pairedIps.contains(remoteIp)) {
                        Protocol.writeLine(out, Protocol.NO + " 未配对");
                        return;
                    }
                    String senderName = p.length > 1 ? p[1] : remoteIp;
                    String filename = p.length > 2 ? Protocol.dec(p[2]) : "file";
                    long size = p.length > 3 ? Long.parseLong(p[3]) : 0;

                    boolean ok = callback.onReceiveRequest(senderName, filename, size);
                    if (!ok) {
                        Protocol.writeLine(out, Protocol.NO + " 对方拒绝接收");
                        return;
                    }
                    Protocol.writeLine(out, Protocol.READY);

                    // 接收文件数据到临时文件
                    File temp = new File(getDir(), System.currentTimeMillis() + ".part");
                    FileOutputStream fout = new FileOutputStream(temp);
                    try {
                        byte[] buf = new byte[32768];
                        long remaining = size;
                        while (remaining > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                            if (n < 0) throw new EOFException("传输中断");
                            fout.write(buf, 0, n);
                            remaining -= n;
                        }
                        fout.getFD().sync();
                    } finally {
                        fout.close();
                    }

                    // 重名则追加时间戳
                    File dest = safeFile(filename);
                    if (dest == null) dest = new File(getDir(), "received_" + System.currentTimeMillis());
                    if (dest.exists()) {
                        int dot = filename.lastIndexOf('.');
                        if (dot > 0) {
                            dest = new File(getDir(), filename.substring(0, dot)
                                    + "_" + System.currentTimeMillis() + filename.substring(dot));
                        } else {
                            dest = new File(getDir(), filename + "_" + System.currentTimeMillis());
                        }
                    }
                    if (!temp.renameTo(dest)) {
                        Protocol.writeLine(out, Protocol.NO + " 保存失败");
                        temp.delete();
                        return;
                    }
                    Protocol.writeLine(out, Protocol.OK + " 已接收 " + dest.getName());
                    callback.onFileReceived(senderName, dest.getName());

                } else if (Protocol.BYE.equals(cmd)) {
                    pairedIps.remove(remoteIp);
                    Protocol.writeLine(out, Protocol.OK + " 已断开");
                } else {
                    Protocol.writeLine(out, Protocol.NO + " 未知命令");
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}