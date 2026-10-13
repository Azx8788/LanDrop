# LanDrop - 局域网文件传输

极简 Android 应用，手机运行 HTTP 服务，电脑浏览器直接上传/下载文件。

## 特点

- **兼容安卓 4.4**（minSdk 19），老设备友好
- **零第三方依赖**，纯 Java 标准库 + Android 原生 API
- **APK 极小**，开启 R8 压缩 + 资源压缩
- **4 位配对码认证**，防止局域网内其他设备随意访问
- **浏览器操作**，电脑端无需安装任何软件

## 使用方法

1. 手机连接 Wi-Fi，打开 LanDrop
2. 点击「启动传输服务」
3. 在电脑浏览器输入手机显示的地址（含配对码）
4. 网页上上传/下载文件

## 技术参数

| 项目 | 值 |
|------|-----|
| minSdk | 19 (Android 4.4) |
| targetSdk | 28 |
| compileSdk | 34 |
| AGP | 8.2.2 |
| Gradle | 8.2 |
| JDK | 17 |
| 依赖 | 无 |

## 构建

```bash
gradle assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

或通过 GitHub Actions 自动构建。

## 安全提醒

- 仅限私人局域网使用，不要暴露到公共网络
- 使用明文 HTTP，不加密传输内容
- 配对码仅防止随意访问，不是强认证
