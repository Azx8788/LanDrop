package com.example.landrop;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 局域网设备发现：TCP 端口扫描。
 *
 * 原理：获取本机 IP -> 推算 /24 子网 -> 并发连接子网内每个 IP 的
 * 8765 端口（短超时）-> 连上的发 DISCOVER，等 HELLO 回复得到设备名。
 *
 * 不依赖 UDP 广播，无需额外权限，老设备兼容好。
 */
public class DeviceScanner {

    private static final int TIMEOUT_MS = 300;
    private static final int CONCURRENCY = 24;

    public interface Callback {
        /** 每发现一个设备回调一次（在后台线程）。 */
        void onDeviceFound(Device device);
        /** 扫描结束。 */
        void onScanFinished(int total);
    }

    /** 同步扫描整个子网，返回发现的设备列表。 */
    public static List<Device> scan(String myIp) {
        final List<Device> result = Collections.synchronizedList(new ArrayList<Device>());
        final Set<String> seen = Collections.synchronizedSet(new HashSet<String>());

        String prefix = Protocol.subnetPrefix(myIp);
        if (prefix == null) return result;

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        final String myIpFinal = myIp;

        for (int i = 1; i <= 254; i++) {
            final String ip = prefix + i;
            if (ip.equals(myIpFinal)) continue;
            futures.add(pool.submit(new Runnable() {
                @Override
                public void run() {
                    Device d = probe(ip);
                    if (d != null && seen.add(d.ip)) {
                        result.add(d);
                    }
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        pool.shutdownNow();

        // 按 IP 末段排序
        Collections.sort(result, new Comparator<Device>() {
            @Override
            public int compare(Device a, Device b) {
                return a.ip.compareTo(b.ip);
            }
        });
        return result;
    }

    /**
     * 异步扫描（边发现边回调，UI 可实时显示新设备）。
     * onDeviceFound 在后台线程调用，回调里更新 UI 请自行切到主线程。
     */
    public static void scanAsync(final String myIp, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String prefix = Protocol.subnetPrefix(myIp);
                if (prefix == null) {
                    cb.onScanFinished(0);
                    return;
                }

                ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
                List<Future<?>> futures = new ArrayList<Future<?>>();
                final Set<String> seen = Collections.synchronizedSet(new HashSet<String>());
                final int[] count = new int[]{0};

                for (int i = 1; i <= 254; i++) {
                    final String ip = prefix + i;
                    if (ip.equals(myIp)) continue;
                    futures.add(pool.submit(new Runnable() {
                        @Override
                        public void run() {
                            Device d = probe(ip);
                            if (d != null && seen.add(d.ip)) {
                                cb.onDeviceFound(d);
                                synchronized (count) { count[0]++; }
                            }
                        }
                    }));
                }

                for (Future<?> f : futures) {
                    try { f.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {}
                }
                pool.shutdownNow();
                cb.onScanFinished(count[0]);
            }
        }, "LanDrop-Scan").start();
    }

    /** 探测单个 IP：连上就发 DISCOVER，读 HELLO 得设备名。 */
    private static Device probe(String ip) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, Protocol.PORT), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);

            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            Protocol.writeLine(out, Protocol.DISCOVER + " probe");

            String resp = Protocol.readLine(in);
            if (resp != null && resp.startsWith(Protocol.HELLO + " ")) {
                String name = resp.substring(Protocol.HELLO.length() + 1).trim();
                return new Device(name, ip);
            }
        } catch (Exception ignored) {
            // 连不上就是没有设备，正常
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
        return null;
    }
}
