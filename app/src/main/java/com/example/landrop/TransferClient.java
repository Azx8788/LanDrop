package com.example.landrop;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * LanDrop TCP 客户端操作。
 * 每个操作独立建立一条 TCP 连接，完成后关闭（协议无状态）。
 */
public class TransferClient {

    private static final int CONNECT_TIMEOUT = 3000;
    private static final int SO_TIMEOUT = 30000;

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

    /** 从对方下载文件，写入 target。返回实际字节数。 */
    public static long download(String ip, String name, File target) throws IOException {
        Socket s = open(ip);
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            Protocol.writeLine(out, Protocol.GET + " " + Protocol.enc(name));

            String header = Protocol.readLine(in);
            if (header == null) throw new IOException("无响应");
            if (header.startsWith(Protocol.NO)) throw new IOException(header.substring(2).trim());

            // FILE <enc(name)> <size>
            int lastSp = header.lastIndexOf(' ');
            if (lastSp <= 0) throw new IOException("协议错误：" + header);
            long size = Long.parseLong(header.substring(lastSp + 1).trim());

            FileOutputStream fout = new FileOutputStream(target);
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
            return size;
        } finally {
            close(s);
        }
    }

    /** 向对方推送文件。返回 true=成功。 */
    public static boolean send(String ip, String myName, String filename,
                               long size, InputStream data) throws IOException {
        Socket s = open(ip);
        try {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            Protocol.writeLine(out, Protocol.SEND + " " + myName
                    + " " + Protocol.enc(filename) + " " + size);

            String resp = Protocol.readLine(in);
            if (resp == null || !Protocol.READY.equals(resp)) return false;

            byte[] buf = new byte[32768];
            long remaining = size;
            while (remaining > 0) {
                int n = data.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) throw new EOFException("读取源文件中断");
                out.write(buf, 0, n);
                remaining -= n;
            }
            out.flush();

            String done = Protocol.readLine(in);
            return done != null && done.startsWith(Protocol.OK);
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
}