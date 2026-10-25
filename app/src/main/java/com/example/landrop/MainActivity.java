package com.example.landrop;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * LanDrop 主界面。
 *
 * 单页面：显示本机信息 + 设备列表。
 * 点击设备可配对连接、浏览对方文件、发送文件。
 *
 * 传输过程中显示进度条；断开配对会发 BYE 让双方状态同步。
 */
public class MainActivity extends Activity implements TransferServer.ServerCallback {

    private static final int REQ_PICK_FILE = 100;
    private static final int REQ_STORAGE = 101;

    private TextView tvInfo;
    private TextView tvHint;
    private Button btnServer;
    private Button btnScan;
    private ListView listDevices;
    private ArrayAdapter<Device> adapter;

    private TransferServer server;
    private final List<Device> devices = new ArrayList<Device>();

    private String myIp;
    private String myName;
    private String pendingSendIp;   // 选文件前记住目标设备

    private final Handler ui = new Handler(Looper.getMainLooper());

    // 进度对话框
    private ProgressDialog progress;
    private long lastProgressPost;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvInfo = (TextView) findViewById(R.id.tvInfo);
        tvHint = (TextView) findViewById(R.id.tvHint);
        btnServer = (Button) findViewById(R.id.btnServer);
        btnScan = (Button) findViewById(R.id.btnScan);
        listDevices = (ListView) findViewById(R.id.listDevices);

        adapter = new ArrayAdapter<Device>(this,
                android.R.layout.simple_list_item_1, devices);
        listDevices.setAdapter(adapter);

        myIp = getIpAddress();
        myName = Protocol.androidName(myIp);

        server = new TransferServer(this, this);
        ensureStoragePermission();

        btnServer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleServer(); }
        });
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doScan(); }
        });
        listDevices.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int pos, long id) {
                showDeviceMenu(devices.get(pos));
            }
        });

        // 自动启动服务
        startServer();
        refreshInfo();
    }

    /* ========== 服务开关 ========== */

    private void toggleServer() {
        if (server.isRunning()) {
            stopServer();
        } else {
            startServer();
        }
    }

    private void startServer() {
        try {
            server.start(myName);
            btnServer.setText("停止服务");
            // 启动前台保活服务，防止后台被回收导致监听中断
            Intent keep = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(keep);
            } else {
                startService(keep);
            }
            refreshInfo();
        } catch (IOException e) {
            toast("启动失败：" + e.getMessage());
        }
    }

    private void stopServer() {
        server.stop();
        stopService(new Intent(this, KeepAliveService.class));
        btnServer.setText("启动服务");
        toast("服务已停止");
        refreshInfo();
    }

    private void refreshInfo() {
        String state = server.isRunning() ? "服务运行中" : "服务未启动";
        tvInfo.setText("LanDrop  " + myName + "\nIP: " + myIp
                + "  端口: " + Protocol.PORT + "\n" + state
                + "\n保存到: " + saveDirText());
    }

    private String saveDirText() {
        try {
            return TransferServer.resolveSaveDir(this).getAbsolutePath();
        } catch (IOException e) {
            return "不可用";
        }
    }

    /* ========== 存储权限 ========== */

    /** Android 6.0+ 申请存储权限；未授予时服务仍可启动，保存目录回退到应用私有目录。 */
    private void ensureStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
        }, REQ_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) return;
        boolean granted = false;
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])
                    && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                granted = true;
            }
        }
        if (granted) {
            toast("已获得存储权限，文件将保存到 Download/landrop");
        } else {
            toast("未获得存储权限，文件将保存到应用私有目录");
        }
        refreshInfo();
    }

    /* ========== 扫描设备（增量显示） ========== */

    private void doScan() {
        if ("0.0.0.0".equals(myIp)) {
            toast("未获取到局域网 IP，请检查 Wi-Fi");
            return;
        }
        tvHint.setText("正在扫描局域网…");
        btnScan.setEnabled(false);
        devices.clear();
        adapter.notifyDataSetChanged();

        DeviceScanner.scanAsync(myIp, new DeviceScanner.Callback() {
            @Override
            public void onDeviceFound(final Device d) {
                final boolean paired = server.isPaired(d.ip);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        d.paired = paired;
                        devices.add(d);
                        adapter.notifyDataSetChanged();
                    }
                });
            }

            @Override
            public void onScanFinished(final int total) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        btnScan.setEnabled(true);
                        tvHint.setText("发现 " + devices.size() + " 台设备"
                                + (devices.isEmpty() ? "（确认对方已启动服务）" : "（点击设备操作）"));
                    }
                });
            }
        });
    }

    /* ========== 设备菜单 ========== */

    private void showDeviceMenu(final Device d) {
        final List<String> items = new ArrayList<String>();
        items.add(d.paired ? "✓ 已配对" : "请求配对连接");
        if (d.paired) {
            items.add("浏览对方文件 / 下载");
            items.add("发送文件给对方");
            items.add("发送剪贴板给对方");
            items.add("断开配对");
        }
        final String[] arr = items.toArray(new String[0]);

        new AlertDialog.Builder(this)
            .setTitle(d.name + "  (" + d.ip + ")")
            .setItems(arr, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String choice = arr[which];
                    if (choice.startsWith("请求配对")) {
                        doConnect(d);
                    } else if (choice.startsWith("浏览")) {
                        browseFiles(d);
                    } else if (choice.startsWith("发送文件")) {
                        pickAndSend(d);
                    } else if (choice.startsWith("发送剪贴板")) {
                        sendClipboardTo(d);
                    } else if (choice.startsWith("断开")) {
                        doDisconnect(d);
                    }
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void doConnect(final Device d) {
        tvHint.setText("正在连接 " + d.name + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String err = null;
                try {
                    ok = TransferClient.connect(d.ip, myName);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final boolean okFinal = ok;
                final String errFinal = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (okFinal) {
                            d.paired = true;
                            server.addPaired(d.ip);
                            adapter.notifyDataSetChanged();
                            toast("已与 " + d.name + " 配对");
                            tvHint.setText("已配对，点击设备可浏览/发送文件");
                        } else {
                            toast("配对失败" + (errFinal != null ? "：" + errFinal : "（对方拒绝或超时）"));
                            tvHint.setText("配对失败，请重试");
                        }
                    }
                });
            }
        }, "LanDrop-Connect").start();
    }

    private void doDisconnect(final Device d) {
        // 通知对方断开，同时清理本地配对状态
        new Thread(new Runnable() {
            @Override
            public void run() {
                TransferClient.bye(d.ip);
            }
        }, "LanDrop-Bye").start();
        server.removePaired(d.ip);
        d.paired = false;
        adapter.notifyDataSetChanged();
        toast("已断开与 " + d.name + " 的配对");
    }

    /* ========== 浏览对方文件 ========== */

    private void browseFiles(final Device d) {
        tvHint.setText("正在读取 " + d.name + " 的文件列表…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<TransferClient.FileEntry> files = null;
                String err = null;
                try {
                    files = TransferClient.listFiles(d.ip);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final List<TransferClient.FileEntry> filesFinal = files;
                final String errFinal = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (filesFinal == null) {
                            toast("读取失败：" + errFinal);
                            tvHint.setText("读取失败");
                            return;
                        }
                        if (filesFinal.isEmpty()) {
                            toast("对方没有可下载的文件");
                            tvHint.setText("对方文件列表为空");
                            return;
                        }
                        showFileListDialog(d, filesFinal);
                    }
                });
            }
        }, "LanDrop-List").start();
    }

    private void showFileListDialog(final Device d, final List<TransferClient.FileEntry> files) {
        final String[] names = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            TransferClient.FileEntry f = files.get(i);
            names[i] = f.name + "   (" + humanSize(f.size) + ")";
        }
        new AlertDialog.Builder(this)
            .setTitle(d.name + " 的文件（点击下载）")
            .setItems(names, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    downloadFile(d, files.get(which));
                }
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    private void downloadFile(final Device d, final TransferClient.FileEntry f) {
        final File dir;
        try {
            dir = TransferServer.resolveSaveDir(this);
        } catch (IOException e) {
            toast("保存目录不可用：" + e.getMessage());
            return;
        }
        ensureStoragePermission();
        showProgress("正在下载 " + f.name);
        tvHint.setText("正在下载 " + f.name + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                File dest = null;
                try {
                    dest = TransferClient.download(d.ip, f.name, dir,
                            new TransferClient.Progress() {
                                @Override
                                public void onProgress(long done, long total) {
                                    postProgress(done, total);
                                }
                            });
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final String errFinal = err;
                final File destFinal = dest;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        hideProgress();
                        if (errFinal == null) {
                            String path = destFinal.getAbsolutePath();
                            toast("已下载：\n" + path);
                            tvHint.setText("已下载：" + path);
                        } else {
                            toast("下载失败：" + errFinal);
                            tvHint.setText("下载失败：" + errFinal);
                        }
                    }
                });
            }
        }, "LanDrop-Download").start();
    }

    /* ========== 发送文件 ========== */

    private void pickAndSend(Device d) {
        pendingSendIp = d.ip;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择要发送的文件"), REQ_PICK_FILE);
        } catch (Exception e) {
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILE || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;

        final Uri uri = data.getData();
        final String ip = pendingSendIp;
        if (ip == null) return;

        // 查文件名和大小
        String name = "file";
        long size = -1;
        Cursor c = getContentResolver().query(uri, null, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                }
            } finally {
                c.close();
            }
        }

        final String fname = name;
        final long fsize = size;
        tvHint.setText("正在发送 " + fname + "…");
        showProgress("正在发送 " + fname);

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String err = null;
                InputStream in = null;
                try {
                    in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("无法读取文件");
                    long useSize = fsize;
                    if (useSize < 0) {
                        // 大小未知：先数一遍，再重新打开流
                        useSize = countStream(in);
                        in.close();
                        in = getContentResolver().openInputStream(uri);
                        if (in == null || useSize < 0) throw new IOException("无法确定文件大小");
                    }
                    final long finalSize = useSize;
                    ok = TransferClient.send(ip, myName, fname, useSize, in,
                            new TransferClient.Progress() {
                                @Override
                                public void onProgress(long done, long total) {
                                    postProgress(done, total);
                                }
                            });
                } catch (IOException e) {
                    err = e.getMessage();
                } finally {
                    if (in != null) try { in.close(); } catch (IOException ignored) {}
                }
                final boolean okFinal = ok;
                final String errFinal = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        hideProgress();
                        if (okFinal) {
                            toast("发送成功：" + fname);
                            tvHint.setText("发送完成");
                        } else {
                            toast("发送失败" + (errFinal != null ? "：" + errFinal : "（对方拒绝）"));
                            tvHint.setText("发送失败");
                        }
                    }
                });
            }
        }, "LanDrop-Send").start();
    }

    /** 数输入流的字节数（会消费流，需外部重新打开）。 */
    private long countStream(InputStream in) {
        try {
            byte[] buf = new byte[32768];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) total += n;
            return total;
        } catch (IOException e) {
            return -1;
        }
    }

    /* ========== 发送剪贴板 ========== */

    private void sendClipboardTo(final Device d) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            toast("剪贴板为空");
            return;
        }
        CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
        if (cs == null || cs.length() == 0) {
            toast("剪贴板不是文本");
            return;
        }
        final String text = cs.toString();
        tvHint.setText("正在发送剪贴板到 " + d.name + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String err = null;
                try {
                    ok = TransferClient.sendClipboard(d.ip, myName, text);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final boolean okFinal = ok;
                final String errFinal = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (okFinal) {
                            toast("剪贴板已发送到 " + d.name);
                            tvHint.setText("剪贴板发送完成");
                        } else {
                            toast("发送失败" + (errFinal != null ? "：" + errFinal : "（对方拒绝）"));
                            tvHint.setText("剪贴板发送失败");
                        }
                    }
                });
            }
        }, "LanDrop-Clip").start();
    }

    /* ========== 服务端回调（从后台线程 -> UI） ========== */

    @Override
    public boolean onConnectRequest(final String remoteName, final String ip) {
        final boolean[] result = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        ui.post(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("配对请求")
                    .setMessage(remoteName + " (" + ip + ") 想要连接，同意吗？")
                    .setCancelable(false)
                    .setPositiveButton("同意", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            result[0] = true; latch.countDown();
                        }
                    })
                    .setNegativeButton("拒绝", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            result[0] = false; latch.countDown();
                        }
                    })
                    .show();
            }
        });
        try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return result[0];
    }

    @Override
    public boolean onReceiveRequest(final String remoteName, final String filename, final long size) {
        final boolean[] result = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        ui.post(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("接收文件")
                    .setMessage(remoteName + " 要发送文件：\n" + filename
                            + "\n大小：" + humanSize(size) + "\n\n接收？")
                    .setCancelable(false)
                    .setPositiveButton("接收", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            result[0] = true; latch.countDown();
                        }
                    })
                    .setNegativeButton("拒绝", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            result[0] = false; latch.countDown();
                        }
                    })
                    .show();
            }
        });
        try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return result[0];
    }

    @Override
    public void onProgress(final String remoteName, final String filename,
                           final long done, final long total) {
        long now = System.currentTimeMillis();
        if (done < total && now - lastProgressPost < 120) return;
        lastProgressPost = now;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (progress == null) {
                    showProgress("正在接收 " + filename);
                }
                updateProgress(done, total);
            }
        });
    }

    @Override
    public void onFileReceived(final String remoteName, final String filename) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                hideProgress();
                String path;
                try {
                    path = TransferServer.resolveSaveDir(MainActivity.this)
                            + "/" + filename;
                } catch (IOException e) {
                    path = filename;
                }
                toast("已接收 " + remoteName + " 的文件：\n" + path);
                tvHint.setText("已接收：" + path);
            }
        });
    }

    @Override
    public boolean onClipboardRequest(final String remoteName, final long size) {
        final boolean[] result = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        ui.post(new Runnable() {
            @Override
            public void run() {
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("接收剪贴板")
                    .setMessage(remoteName + " 要发送剪贴板内容（" + humanSize(size) + "）\n"
                            + "\n接收后会直接复制到你的剪贴板，会覆盖现有内容。")
                    .setCancelable(false)
                    .setPositiveButton("接收", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            result[0] = true; latch.countDown();
                        }
                    })
                    .setNegativeButton("拒绝", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            result[0] = false; latch.countDown();
                        }
                    })
                    .show();
            }
        });
        try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return result[0];
    }

    @Override
    public void onClipboardReceived(final String remoteName, final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm == null) return;
                cm.setPrimaryClip(ClipData.newPlainText("LanDrop", text));
                String preview = text.length() > 50 ? text.substring(0, 50) + "…" : text;
                toast("已复制 " + remoteName + " 的剪贴板：\n" + preview);
                tvHint.setText("已复制剪贴板：" + preview);
            }
        });
    }

    /* ========== 进度对话框 ========== */

    private void showProgress(String title) {
        hideProgress();
        progress = new ProgressDialog(this);
        progress.setTitle(title);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(1000);
        progress.setProgress(0);
        progress.setMessage("准备中…");
        progress.setCancelable(false);
        progress.setIndeterminate(false);
        progress.show();
    }

    private void updateProgress(long done, long total) {
        if (progress == null || !progress.isShowing()) return;
        if (total > 0) {
            progress.setIndeterminate(false);
            progress.setMax(1000);
            int pct = (int) (done * 1000 / total);
            progress.setProgress(pct);
            progress.setMessage(humanSize(done) + " / " + humanSize(total)
                    + "  (" + (done * 100 / total) + "%)");
        } else {
            progress.setIndeterminate(true);
            progress.setMessage(humanSize(done));
        }
    }

    private void hideProgress() {
        if (progress != null) {
            try { progress.dismiss(); } catch (Exception ignored) {}
            progress = null;
        }
    }

    /** 把进度事件节流后切到主线程更新。 */
    private void postProgress(final long done, final long total) {
        long now = System.currentTimeMillis();
        if (done < total && now - lastProgressPost < 120) return;
        lastProgressPost = now;
        ui.post(new Runnable() {
            @Override
            public void run() {
                updateProgress(done, total);
            }
        });
    }

    /* ========== 工具 ========== */

    private String getIpAddress() {
        // 先试 Wi-Fi（最常用场景）
        try {
            WifiManager wifi = (WifiManager)
                    getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wifi.getConnectionInfo().getIpAddress();
            if (ip != 0) {
                return String.format("%d.%d.%d.%d",
                        ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
            }
        } catch (Exception ignored) {}
        // 兜底：枚举网卡，找第一个非回环 IPv4（热点、以太网等）
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }

    private String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format("%.1f KB", b / 1024.0);
        if (b < 1073741824L) return String.format("%.1f MB", b / 1048576.0);
        return String.format("%.2f GB", b / 1073741824.0);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        hideProgress();
        if (server != null) {
            server.stop();
            stopService(new Intent(this, KeepAliveService.class));
        }
        super.onDestroy();
    }
}
