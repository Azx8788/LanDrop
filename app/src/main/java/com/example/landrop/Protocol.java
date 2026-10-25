package com.example.landrop;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;

/**
 * LanDrop 通信协议（手机与电脑共用同一套 TCP 文本协议）。
 *
 * 所有消息为 ASCII 文本行，以 \n 结束，空格分隔字段。
 * 文件名含空格时用 URL 编码（%20），传输完文件数据后接一个 \n。
 *
 * 消息类型：
 *   DISCOVER <name>        探测设备，对方应回 HELLO
 *   HELLO <name>           响应探测
 *   CONNECT <name>         请求配对
 *   ACCEPT                 同意配对
 *   REJECT                 拒绝配对
 *   LIST                   请求文件列表（需已配对）
 *   FILES <count>          响应：后面 count 行 "<name> <size>"
 *   GET <name> [<offset>]  请求下载某文件，可带 offset 续传
 *   FILE <name> <size> [<offset>]  响应：后面紧跟 size-offset 字节文件数据
 *   SEND <name> <name> <size>      通知对方要推送文件
 *   READY [<offset>]       准备好接收文件数据，可带 offset 让发送方续传
 *   NO <reason>            拒绝
 *   OK <msg>               成功
 *   BYE                    断开
 */
public class Protocol {

    public static final int PORT = 8765;

    public static final String DISCOVER = "DISCOVER";
    public static final String HELLO    = "HELLO";
    public static final String CONNECT  = "CONNECT";
    public static final String ACCEPT   = "ACCEPT";
    public static final String REJECT   = "REJECT";
    public static final String LIST     = "LIST";
    public static final String FILES    = "FILES";
    public static final String GET      = "GET";
    public static final String FILE     = "FILE";
    public static final String SEND     = "SEND";
    public static final String READY    = "READY";
    public static final String NO       = "NO";
    public static final String OK       = "OK";
    public static final String BYE      = "BYE";

    /** 读一行（到 \n），返回去掉 \r\n 的内容；流结束返回 null。 */
    public static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 8192) throw new IOException("行过长");
        }
        if (c == -1 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), Charset.forName("UTF-8"));
    }

    /** 写一行（自动加 \n）。 */
    public static void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(Charset.forName("UTF-8")));
        out.flush();
    }

    /**
     * 根据本机 IP 生成设备名。
     * Android 端：Android-<末段>
     */
    public static String androidName(String ip) {
        return "Android-" + lastOctet(ip);
    }

    /** PC 端：PC-<末段> */
    public static String pcName(String ip) {
        return "PC-" + lastOctet(ip);
    }

    private static String lastOctet(String ip) {
        if (ip == null) return "0";
        int i = ip.lastIndexOf('.');
        return i >= 0 ? ip.substring(i + 1) : ip;
    }

    /** 文件名 URL 编码（协议中文件名可能含空格/中文，需编码）。 */
    public static String enc(String name) {
        try {
            return URLEncoder.encode(name, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return name;
        }
    }

    /** 文件名 URL 解码。 */
    public static String dec(String name) {
        try {
            return URLDecoder.decode(name, "UTF-8");
        } catch (Exception e) {
            return name;
        }
    }

    /** 从本机 IP 推算子网前缀，如 192.168.1.100 -> "192.168.1." */
    public static String subnetPrefix(String ip) {
        if (ip == null) return null;
        int i = ip.lastIndexOf('.');
        return i >= 0 ? ip.substring(0, i + 1) : null;
    }
}
