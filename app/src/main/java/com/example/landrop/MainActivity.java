package com.example.landrop;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;

/**
 * LanDrop 主界面。
 *
 * 功能：显示 Wi-Fi IP 地址、启动/停止 HTTP 传输服务、显示配对码。
 *
 * 注意：服务由 Activity 直接持有，关闭页面会停止服务。
 * 正式版应改为前台服务（Foreground Service）。
 */
public class MainActivity extends Activity {

    private TransferServer server;
    private TextView tvStatus;
    private TextView tvAddress;
    private TextView tvPin;
    private Button btnStart;
    private Button btnStop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        server = new TransferServer(this);

        tvStatus  = (TextView) findViewById(R.id.tvStatus);
        tvAddress = (TextView) findViewById(R.id.tvAddress);
        tvPin     = (TextView) findViewById(R.id.tvPin);
        btnStart  = (Button) findViewById(R.id.btnStart);
        btnStop   = (Button) findViewById(R.id.btnStop);

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startServer();
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopServer();
            }
        });

        refreshAddress();
    }

    private void startServer() {
        try {
            server.start();
            String ip = getIpAddress();
            if ("0.0.0.0".equals(ip)) {
                tvStatus.setText("启动成功，但未检测到 Wi-Fi IP");
                tvAddress.setText("请检查 Wi-Fi 连接后重试");
            } else {
                tvStatus.setText("服务运行中，在电脑浏览器打开：");
                tvAddress.setText("http://" + ip + ":8765/?pin=" + server.getPin());
                tvPin.setText("配对码：" + server.getPin());
            }
        } catch (IOException e) {
            tvStatus.setText("启动失败：" + e.getMessage());
            tvPin.setText("");
        }
    }

    private void stopServer() {
        server.stop();
        tvStatus.setText("服务已停止");
        tvAddress.setText("Wi-Fi IP：" + getIpAddress());
        tvPin.setText("");
    }

    /**
     * 从 WifiManager 获取 IPv4 地址。
     * 不使用已废弃的 Formatter.formatIpAddress()，手动拆字节。
     */
    private String getIpAddress() {
        try {
            WifiManager wifi = (WifiManager)
                getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wifi.getConnectionInfo().getIpAddress();
            if (ip == 0) return "0.0.0.0";
            return String.format("%d.%d.%d.%d",
                ip & 0xff,
                (ip >> 8) & 0xff,
                (ip >> 16) & 0xff,
                (ip >> 24) & 0xff);
        } catch (Exception e) {
            return "0.0.0.0";
        }
    }

    private void refreshAddress() {
        String ip = getIpAddress();
        if ("0.0.0.0".equals(ip)) {
            tvAddress.setText("未获取到 Wi-Fi IP，请连接 Wi-Fi");
        } else {
            tvAddress.setText("Wi-Fi IP：" + ip);
        }
    }

    @Override
    protected void onDestroy() {
        // Activity 销毁时停止服务，避免留下失控的监听端口
        if (server != null) server.stop();
        super.onDestroy();
    }
}
