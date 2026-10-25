package com.example.landrop;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * LanDrop TCP 客户端操作。
 * 每个操作独立建立一条 TCP 连接，完成后关闭（协议无状态）。
 *
 * 断点续传：
 *   - 拉取（GET）：本地保留 <name>.part，请求时带上已有字节数作为 offset
 *   - 推送（SEND）：由接收方在 READY 里回复已接收的字节数作为 offset
 *
 * 进度：两端都在本地按自己读/写的字节数计算进度，无需额外的线缆消息
 * （数据流是单向的，双方各自精确知道自己搬了多少字节）。
 */
public class TransferClient {

    private static final int CONNECT_TIMEOUT = 3000;
    private static final int SO_TIMEOUT = 60000;
    private static final int BUF_SIZE = 32768;
    /** 每传多少字节回调一次进度。 */
    private static final long PROGRESS_STEP = 32768;

    /** 进度回调（在后台线程调用，更新 UI 请自行切线程）。 */
    public interface Progress {
        /**
         * @param done  已传输字节数（含续传起点）
         * @param total 总字节数；未知时为 -1
         */
        void onProgress(long done, long total);
    }

    /** 文件条目（对方文件列表）。 */
    public static class FileEntry {
        public final String name;
        public final long size;
        public FileEntry(String name, long size) { this.name = name; this.size = size; }
    }

    /** 发起配对申请。返回 true=对方同意。 */
    public static boolean connect(String ip, String myName) throws IOException {
        Socket s = open(ip);
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            Protocol.writeLine(out, Protocol.CONNECT + " " + myName);
            String resp = Protocol.readLine(in);
            return Protocol.ACCEPT.equals(resp);
        } finally {
            close(s);
        }
    }

    /** 请求对方文件列表（需已配对）。 */
    public static List<FileEntry> listFiles(String ip) throws IOException {
        Socket s = open(ip);
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            Protocol.writeLine(out, Protocol.LIST);

            String header = Protocol.readLine(in);
            if (header == null) throw new IOException("无响应");
            if (header.startsWith(Protocol.NO)) throw new IOException(header.substring(2).trim());

            String[] hp = header.split(" ");
            if (hp.length < 2 || !Protocol.FILES.equals(hp[0]))
                throw new IOException("协议错误：" + header);
            int count = Integer.parseInt(hp[1]);

            List<FileEntry> list = new ArrayList<FileEntry>();
            for (int i = 0; i < count; i++) {
                String line = Protocol.readLine(in);
                if (line == null) break;
                int sp = line.lastIndexOf(' ');
                if (sp <= 0) continue;
                String name = Protocol.dec(line.substring(0, sp));
                long size = Long.parseLong(line.substring(sp + 1).trim());
                list.add(new FileEntry(name, size));
            }
            return list;
        } finally {
            close(s);
        }
    }

    /* ---------- 下载（拉取） ---------- */

    /**
     * 从对方下载文件到 dir，支持断点续传。
     *
     * 先写 <name>.part，传完再改名为正式文件（重名自动加时间戳）。
     * 中途失败时 .part 保留，下次下载同名文件会自动从断点续传。
     *
     * @param cb 进度回调，可为 null
     * @return 下载完成后的正式文件
     */
    public static File download(String ip, String name, File dir, Progress cb) throws IOException {
        if (dir == null) throw new IOException("保存目录不可用");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建保存目录");
        File part = new File(dir, name + ".part");

        // 最多试两次：第一次带断点，若对方表示续传位置无效（文件变小/换了），
        // 删掉残片从 0 重来。
        for (int attempt = 0; attempt < 2; attempt++) {
            File exist = new File(dir, name);
            long offset = part.isFile() ? part.length() : 0;
            // 正式文件已在且没有残片，说明早就下完了，直接复用
            if (!part.exists() && exist.isFile() && exist.length() > 0) {
                if (cb != null) cb.onProgress(exist.length(), exist.length());
                return exist;
            }
            try {
                return downloadOnce(ip, name, dir, part, offset, cb);
            } catch (IOException e) {
                if (attempt == 0 && part.isFile() && part.length() > 0) {
                    part.delete();
                    continue;
                }
                throw e;
            }
        }
        throw new IOException("下载失败");
    }

    private static File downloadOnce(String ip, String name, File dir, File part,
                                     long offset, Progress cb) throws IOException {
        Socket s = open(ip);
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = new BufferedInputStream(s.getInputStream());
            Protocol.writeLine(out, Protocol.GET + " " + Protocol.enc(name) + " " + offset);

            String header = Protocol.readLine(in);
            if (header == null) throw new IOException("无响应");
            if (header.startsWith(Protocol.NO)) throw new IOException(header.substring(2).trim());

            // FILE <enc(name)> <size> [<offset>]
            String[] hp = header.split(" ");
            if (hp.length < 3 || !Protocol.FILE.equals(hp[0])) {
                throw new IOException("协议错误：" + header);
            }
            long total = Long.parseLong(hp[2].trim());
            long start = hp.length >= 4 ? Long.parseLong(hp[3].trim()) : 0;
            if (start < 0) start = 0;
            if (start > total) throw new IOException("续传位置无效");
            if (start != offset) {
                // 对方给的位置与本地残片不一致，以对方为准
                if (start == 0) {
                    part.delete();
                } else if (part.isFile() && part.length() > start) {
                    truncateTo(part, start);
                }
            } else if (start == 0) {
                part.delete();
            }

            FileOutputStream fout = new FileOutputStream(part, start > 0);
            long done = start;
            try {
                if (cb != null) cb.onProgress(done, total);
                byte[] buf = new byte[BUF_SIZE];
                long remaining = total - start;
                long lastPost = done;
                while (remaining > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) throw new EOFException("传输中断（已保留 .part，可重试续传）");
                    fout.write(buf, 0, n);
                    remaining -= n;
                    done += n;
                    if (cb != null && done - lastPost >= PROGRESS_STEP) {
                        cb.onProgress(done, total);
                        lastPost = done;
                    }
                }
                fout.getFD().sync();
            } finally {
                fout.close();
            }
            if (cb != null) cb.onProgress(total, total);

            File dest = uniqueIn(dir, name);
            if (!part.renameTo(dest)) {
                copyFile(part, dest);
                part.delete();
            }
            return dest;
        } finally {
            close(s);
        }
    }

    /* ---------- 推送剪贴板文本 ---------- */

    /**
     * 把本机剪贴板文本推送到对方剪贴板。
     * 协议：CLIP <myName> <size> -> READY -> size 字节 UTF-8 文本 -> OK
     * @return true=对方已复制到剪贴板
     */
    public static boolean sendClipboard(String ip, String myName, String text) throws IOException {
        byte[] data = text.getBytes("UTF-8");
        Socket s = open(ip);
        try {
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            InputStream in = new BufferedInputStream(s.getInputStream());
            Protocol.writeLine(out, Protocol.CLIP + " " + myName + " " + data.length);

            String resp = Protocol.readLine(in);
            if (resp == null || !Protocol.READY.equals(resp)) return false;

            out.write(data);
            out.flush();

            String ok = Protocol.readLine(in);
            return ok != null && ok.startsWith(Protocol.OK);
        } finally {
            close(s);
        }
    }

    /** 通知对方断开配对（发 BYE，失败忽略）。 */
    public static void bye(String ip) {
        Socket s = null;
        try {
            s = open(ip);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            Protocol.writeLine(out, Protocol.BYE);
            Protocol.readLine(in);
        } catch (IOException ignored) {
        } finally {
            if (s != null) close(s);
        }
    }

    /* ---------- 推送（发送） ---------- */

    /**
     * 向对方推送文件，支持断点续传。
     *
     * 对方若已有同名 .part，会在 READY 里回复已收到的字节数，
     * 这里据此跳过本地已传部分，只发剩下的。
     *
     * @param cb 进度回调，可为 null
     * @return true=对方确认接收完成
     */
    public static boolean send(String ip, String myName, String filename,
                               long size, InputStream data, Progress cb) throws IOException {
        Socket s = open(ip);
        try {
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            InputStream in = new BufferedInputStream(s.getInputStream());
            Protocol.writeLine(out, Protocol.SEND + " " + myName
                    + " " + Protocol.enc(filename) + " " + size);

            String resp = Protocol.readLine(in);
            if (resp == null) return false;
            long offset = 0;
            if (resp.startsWith(Protocol.READY)) {
                // READY [<已接收字节数>]
                String[] rp = resp.split(" ");
                if (rp.length > 1) {
                    try { offset = Long.parseLong(rp[1].trim()); } catch (NumberFormatException ignored) {}
                }
            } else {
                return false;   // NO <原因>
            }
            if (offset < 0 || offset > size) offset = 0;
            skipFully(data, offset);

            long done = offset;
            byte[] buf = new byte[BUF_SIZE];
            long remaining = size - offset;
            long lastPost = done;
            if (cb != null) cb.onProgress(done, size);
            while (remaining > 0) {
                int n = data.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new EOFException("读取源文件中断");
                out.write(buf, 0, n);
                remaining -= n;
                done += n;
                if (cb != null && done - lastPost >= PROGRESS_STEP) {
                    cb.onProgress(done, size);
                    lastPost = done;
                }
            }
            out.flush();
            if (cb != null) cb.onProgress(size, size);

            String ok = Protocol.readLine(in);
            return ok != null && ok.startsWith(Protocol.OK);
        } finally {
            close(s);
        }
    }

    /* ---------- 工具 ---------- */

    private static Socket open(String ip) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(ip, Protocol.PORT), CONNECT_TIMEOUT);
        s.setSoTimeout(SO_TIMEOUT);
        return s;
    }

    private static void close(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }

    /** 跳过输入流的前 n 个字节。 */
    private static void skipFully(InputStream in, long n) throws IOException {
        byte[] skip = new byte[8192];
        long left = n;
        while (left > 0) {
            long k = in.skip(left);
            if (k <= 0) {
                int r = in.read(skip, 0, (int) Math.min(skip.length, left));
                if (r < 0) throw new EOFException("源文件比预期短");
                left -= r;
            } else {
                left -= k;
            }
        }
    }

    /** 把文件截断到指定长度。 */
    private static void truncateTo(File f, long len) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        try { raf.setLength(len); } finally { raf.close(); }
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

    private static void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        try {
            FileOutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[BUF_SIZE];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.getFD().sync();
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}
