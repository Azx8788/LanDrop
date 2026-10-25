package com.example.landrop;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

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
 * 配对状态持久化到 SharedPreferences，重启后保留。
 *
 * 断点续传：
 *   - GET：请求可带 offset，从指定位置发送剩余字节
 *   - SEND：接收方发现已有 <name>.part，在 READY 里回复已收字节数，发送方据此续传
 */
public class TransferServer {

    private static final String PREFS = "landrop_pairs";
    private static final String KEY_PAIRS = "paired_ips";
    private static final int SO_TIMEOUT = 60000;
    private static final long PROGRESS_STEP = 32768;

    private final Context context;
    private final ServerCallback callback;
    private final SharedPreferences prefs;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService workers;
    private Thread acceptThread;
    private String myName;

    /** 已配对设备的 IP 集合（持久化，重启后保留）。 */
    private final Set<String> pairedIps =
            Collections.synchronizedSet(new HashSet<String>());

    /** 需要与 UI 线程交互的回调（弹窗确认、进度）。 */
    public interface ServerCallback {
        /** 收到配对申请，返回是否同意（阻塞直到用户选择）。 */
        boolean onConnectRequest(String remoteName, String ip);
        /** 收到文件推送请求，返回是否接收。 */
        boolean onReceiveRequest(String remoteName, String filename, long size);
        /** 文件接收完成。 */
        void onFileReceived(String remoteName, String filename);
        /** 接收进度（后台线程调用）。 */
        void onProgress(String remoteName, String filename, long done, long total);
    }

    public TransferServer(Context context, ServerCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // 恢复上次保存的配对设备
        Set<String> saved = prefs.getStringSet(KEY_PAIRS, Collections.<String>emptySet());
        if (saved != null) pairedIps.addAll(saved);
    }

    /* ========== 生命周期 ========== */

    public synchronized void start(String name) throws IOException {
        if (running) return;
        this.myName = name;

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(Protocol.PORT));

        workers = Executors.newFixedThreadPool(16);
        running = true;

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Socket s = serverSocket.accept();
                        s.setSoTimeout(SO_TIMEOUT);
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

    public void addPaired(String ip) {
        pairedIps.add(ip);
        persistPairs();
    }

    public void removePaired(String ip) {
        pairedIps.remove(ip);
        persistPairs();
    }

    public Set<String> getPairedIps() {
        synchronized (pairedIps) { return new HashSet<String>(pairedIps); }
    }

    private void persistPairs() {
        prefs.edit().putStringSet(KEY_PAIRS, getPairedIps()).apply();
    }

    /* ========== 接收目录 ========== */

    /**
     * 解析文件保存目录。
     *
     * 优先使用外部存储的公共下载目录 Download/landrop，这样接收到的文件
     * 在系统文件管理器、下载应用里都能直接看到，卸载应用也不会被删。
     * 外部存储不可用或目录无法创建时，回退到应用私有目录。
     */
    public static File resolveSaveDir(Context context) throws IOException {
        Context app = context.getApplicationContext();
        if (app == null) app = context;

        File dir = null;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null) {
                File publicDir = new File(downloads, "landrop");
                if (publicDir.isDirectory()) {
                    // 目录已存在时确认可写（存储权限被回收时 canWrite 为 false）
                    if (publicDir.canWrite()) dir = publicDir;
                } else if (publicDir.mkdirs()) {
                    dir = publicDir;
                }
            }
        }

        if (dir == null) {
            dir = app.getExternalFilesDir("landrop");
            if (dir == null) dir = new File(app.getFilesDir(), "landrop");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建目录");
        }
        return dir.getCanonicalFile();
    }

    private File getDir() throws IOException {
        return resolveSaveDir(context);
    }

    /**
     * 在指定目录内解析文件名，阻止路径穿越。
     * 文件名为空、包含路径分隔符或解析后逃出目录时返回 null。
     */
    public static File resolveFileIn(File dir, String name) throws IOException {
        if (name == null || name.length() == 0
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0) return null;
        File canonicalDir = dir.getCanonicalFile();
        File f = new File(canonicalDir, name).getCanonicalFile();
        if (!canonicalDir.equals(f.getParentFile())) return null;
        return f;
    }

    /** 阻止路径穿越。 */
    private File safeFile(String name) throws IOException {
        return resolveFileIn(getDir(), name);
    }

    /** 在 dir 内为 name 找一个不冲突的文件名。 */
    private static File uniqueIn(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
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
                        addPaired(remoteIp);
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
                    // GET <enc(name)> [<offset>]
                    if (!pairedIps.contains(remoteIp)) {
                        Protocol.writeLine(out, Protocol.NO + " 未配对");
                        return;
                    }
                    String name = p.length > 1 ? Protocol.dec(p[1]) : "";
                    long offset = 0;
                    boolean withOffset = false;
                    if (p.length > 2) {
                        try {
                            offset = Long.parseLong(p[2].trim());
                            withOffset = true;
                        } catch (NumberFormatException ignored) {}
                    }
                    // 不暴露 .part 临时文件
                    if (name.endsWith(".part")) {
                        Protocol.writeLine(out, Protocol.NO + " 文件不存在");
                        return;
                    }
                    File file = safeFile(name);
                    if (file == null || !file.isFile()) {
                        Protocol.writeLine(out, Protocol.NO + " 文件不存在");
                        return;
                    }
                    long total = file.length();
                    if (offset < 0) offset = 0;
                    if (offset > total) {
                        Protocol.writeLine(out, Protocol.NO + " 续传位置无效");
                        return;
                    }
                    if (withOffset) {
                        Protocol.writeLine(out, Protocol.FILE + " "
                                + Protocol.enc(file.getName()) + " " + total + " " + offset);
                    } else {
                        Protocol.writeLine(out, Protocol.FILE + " "
                                + Protocol.enc(file.getName()) + " " + total);
                    }
                    RandomAccessFile raf = new RandomAccessFile(file, "r");
                    try {
                        raf.seek(offset);
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = raf.read(buf)) != -1) out.write(buf, 0, n);
                        out.flush();
                    } finally {
                        raf.close();
                    }

                } else if (Protocol.SEND.equals(cmd)) {
                    // SEND <senderName> <enc(filename)> <size>
                    if (!pairedIps.contains(remoteIp)) {
                        Protocol.writeLine(out, Protocol.NO + " 未配对");
                        return;
                    }
                    String senderName = p.length > 1 ? p[1] : remoteIp;
                    String filename = p.length > 2 ? Protocol.dec(p[2]) : "file";
                    long size = 0;
                    try {
                        if (p.length > 3) size = Long.parseLong(p[3].trim());
                    } catch (NumberFormatException ignored) {}

                    File dir = getDir();
                    File checked = resolveFileIn(dir, filename);
                    String safeName = (checked != null) ? filename
                            : ("received_" + System.currentTimeMillis());

                    boolean ok = callback.onReceiveRequest(senderName, safeName, size);
                    if (!ok) {
                        Protocol.writeLine(out, Protocol.NO + " 对方拒绝接收");
                        return;
                    }

                    // 断点续传：已有 <name>.part 时从其长度续传
                    File part = new File(dir, safeName + ".part");
                    long offset = part.isFile() ? part.length() : 0;
                    if (offset > size) {
                        part.delete();
                        offset = 0;
                    }
                    if (offset > 0) {
                        Protocol.writeLine(out, Protocol.READY + " " + offset);
                    } else {
                        Protocol.writeLine(out, Protocol.READY);
                    }

                    boolean failed = false;
                    FileOutputStream fout = new FileOutputStream(part, offset > 0);
                    try {
                        byte[] buf = new byte[32768];
                        long done = offset;
                        long remaining = size - offset;
                        long lastPost = done;
                        callback.onProgress(senderName, safeName, done, size);
                        while (remaining > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                            if (n < 0) {
                                failed = true;
                                break;
                            }
                            fout.write(buf, 0, n);
                            remaining -= n;
                            done += n;
                            if (done - lastPost >= PROGRESS_STEP) {
                                callback.onProgress(senderName, safeName, done, size);
                                lastPost = done;
                            }
                        }
                        fout.getFD().sync();
                    } finally {
                        fout.close();
                    }

                    if (failed) {
                        // 保留 .part，下次对方重发同名文件可续传
                        Protocol.writeLine(out, Protocol.NO + " 传输中断（已保留 .part，可重试续传）");
                        return;
                    }
                    callback.onProgress(senderName, safeName, size, size);

                    File dest = uniqueIn(dir, safeName);
                    if (!part.renameTo(dest)) {
                        Protocol.writeLine(out, Protocol.NO + " 保存失败");
                        part.delete();
                        return;
                    }
                    Protocol.writeLine(out, Protocol.OK + " 已接收 " + dest.getName());
                    callback.onFileReceived(senderName, dest.getName());

                } else if (Protocol.BYE.equals(cmd)) {
                    removePaired(remoteIp);
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
