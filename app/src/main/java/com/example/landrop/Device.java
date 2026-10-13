package com.example.landrop;

/**
 * 发现到的设备。
 */
public class Device {
    public final String name;   // 如 Android-100 / PC-50
    public final String ip;     // 如 192.168.1.100
    public boolean paired;      // 是否已配对

    public Device(String name, String ip) {
        this.name = name;
        this.ip = ip;
    }

    public boolean isPC() {
        return name != null && name.startsWith("PC-");
    }

    @Override
    public String toString() {
        return name + " (" + ip + ")";
    }
}
