# LanDrop - 局域网设备发现与文件传输

极简的局域网对传工具，**手机 + Windows 电脑**共用同一套 TCP 协议。
固定端口扫描发现设备 → 配对（申请+同意）→ 定向传文件。

## 特点

- **兼容安卓 4.4**（minSdk 19），老设备友好
- **接收即落盘**：收到的文件直接保存到公共下载目录 `Download/landrop/`，文件管理器和下载应用里可直接看到
- **零第三方依赖**：Android 纯 Java 标准库；PC 用 Go 标准库，编译成单个 exe
- **APK / exe 极小**
- **配对机制**：只有相互同意的设备才能传文件，同一局域网其他设备看不到传输内容
- **双向**：既能接收（对方推送），也能主动拉取（浏览对方文件并下载）
- **无需输入 IP**：自动扫描局域网发现设备

## 使用流程

1. 两台设备（手机/电脑）连接**同一 Wi-Fi**
2. 都启动 LanDrop（手机点「启动服务」，PC 直接运行）
3. 任一方点「扫描设备」/ 输入 `s`，看到对方
4. 发起连接申请（手机点设备→「请求配对」/ PC 输入 `c 1`）
5. 对方确认后建立配对
6. 配对后可：**浏览对方文件并下载**、**发送文件给对方**

未参与的设备（如第三台 c）既看不到设备间的连接，也拿不到任何传输内容。

## 通信协议（TCP 8765，文本行）

```
DISCOVER <name>     探测设备        ->  HELLO <name>
CONNECT <name>      请求配对        ->  ACCEPT / REJECT
LIST                请求文件列表    ->  FILES <count> + count 行 "<enc(name)> <size>"
GET <enc(name)> [<offset>]          ->  FILE <enc(name)> <size> [<offset>] + 数据
SEND <name> <enc(filename)> <size>  ->  READY [<offset>] / NO + 数据 + OK
BYE                 断开
```

- 文件名 URL 编码（支持空格/中文）
- 只有已配对 IP 才能 LIST/GET/SEND
- 每个操作独立一条 TCP 连接
- **断点续传**：GET 可带 offset 从指定位置续传；SEND 时接收方若已有同名 `.part`，会在 READY 里回复已收字节数，发送方据此跳过已传部分。不带 offset 时为旧格式，向后兼容
- **传输进度**：两端各自按本地读/写的字节数计算，无需额外协议消息

## 目录结构

```
LanDrop/
├── app/                          Android APP（Java）
│   └── src/main/java/com/example/landrop/
│       ├── Protocol.java         协议定义
│       ├── Device.java           设备模型
│       ├── DeviceScanner.java    TCP 端口扫描发现
│       ├── TransferServer.java   TCP 服务端
│       ├── TransferClient.java   TCP 客户端操作
│       └── MainActivity.java     界面
├── pc/                           Windows 端（Go）
│   ├── main.go
│   └── go.mod
└── .github/workflows/
    ├── build.yml                 Android 构建
    └── build-pc.yml              Windows exe 构建
```

## 技术参数

| 项目 | Android | PC |
|------|---------|-----|
| 语言 | Java | Go |
| 最低系统 | Android 4.4 (API 19) | Windows 7+ |
| 依赖 | 无 | 无 |
| 端口 | 8765 | 8765 |

## 构建

**Android**：GitHub Actions 自动构建 → 下载 `LanDrop-release` artifact（APK）

**PC**：GitHub Actions 自动构建 → 下载 `LanDrop-pc` artifact（`LanDrop.exe`）

PC 本地构建：
```bash
cd pc
GOOS=windows GOARCH=amd64 go build -ldflags "-s -w" -o LanDrop.exe .
```

## PC 端命令

```
s                     扫描设备
d                     显示设备列表
c <编号|IP>           连接（配对）设备
l <编号|IP>           列出对方文件
g <编号|IP> <文件名>   从对方下载文件
p <编号|IP> <路径>     发送本地文件给对方
y / n                 同意 / 拒绝待处理的请求
q                     退出
```

## 文件存储

- Android：公共下载目录 `Download/landrop/`（Android 6.0+ 首次启动需允许存储权限；外部存储不可用时回退到应用私有目录 `Android/data/com.example.landrop/files/landrop/`）
- PC：exe 同目录下的 `landrop/`

## 安全提醒

- 仅限私人局域网使用，不要暴露到公共网络
- 传输使用明文 TCP，不加密内容
- 配对机制可防止随意访问，但不是强加密认证