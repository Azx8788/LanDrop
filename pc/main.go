package main

// LanDrop PC 端 —— 与 Android 端共用同一套 TCP 协议。
//
// 编译：go build -o LanDrop.exe .
// 运行后：自动监听 8765 端口、扫描局域网设备，命令行交互。
//
// 命令：
//   s                     扫描设备
//   d                     显示设备列表
//   c <编号|IP>           连接（配对）设备
//   l <编号|IP>           列出对方文件
//   g <编号|IP> <文件名>   从对方下载文件
//   p <编号|IP> <本地路径> 发送本地文件给对方
//   y / n                 同意 / 拒绝待处理的请求
//   q                     退出

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	port        = 8765
	dialTimeout = 3 * time.Second
	ioTimeout   = 30 * time.Second
)

// ---------- 设备 ----------

type Device struct {
	Name   string
	IP     string
	Paired bool
}

var (
	mu       sync.Mutex
	devices  []Device
	pairedIP = map[string]bool{}
	dataDir  string
	myName   string

	confirmMu sync.Mutex
	pendingCh chan bool // 等待用户 y/n 的请求
)

// ---------- 主程序 ----------

func main() {
	ip := localIP()
	myName = "PC-" + lastOctet(ip)

	exe, err := os.Executable()
	if err != nil {
		exe, _ = filepath.Abs(os.Args[0])
	}
	dataDir = filepath.Join(filepath.Dir(exe), "landrop")
	os.MkdirAll(dataDir, 0755)

	fmt.Println("========================================")
	fmt.Println("  LanDrop  " + myName)
	fmt.Println("  IP: " + ip + "   端口: " + strconv.Itoa(port))
	fmt.Println("  接收目录: " + dataDir)
	fmt.Println("========================================")
	printHelp()

	// 启动服务端
	go startServer()

	// stdin 读取
	lines := make(chan string)
	go func() {
		sc := bufio.NewScanner(os.Stdin)
		for sc.Scan() {
			lines <- sc.Text()
		}
	}()

	// 首次扫描（异步，避免阻塞命令循环导致配对确认无法响应）
	go scanDevices(ip)

	// 命令循环
	for {
		line := <-lines
		handleCommand(strings.TrimSpace(line), ip)
	}
}

func printHelp() {
	fmt.Println("命令: s=扫描  d=列表  c=连接  l=列文件  g=下载  p=发送  y/n=同意/拒绝  q=退出")
}

func handleCommand(cmd string, myIp string) {
	if cmd == "" {
		return
	}
	parts := strings.Fields(cmd)
	c := strings.ToLower(parts[0])

	switch c {
	case "s":
		go scanDevices(myIp)
	case "d":
		printDevices()
	case "c":
		if len(parts) < 2 {
			fmt.Println("用法: c <编号|IP>")
			return
		}
		d := resolveDevice(parts[1])
		if d != nil {
			doConnect(*d)
		}
	case "l":
		if len(parts) < 2 {
			fmt.Println("用法: l <编号|IP>")
			return
		}
		d := resolveDevice(parts[1])
		if d != nil {
			doList(*d)
		}
	case "g":
		if len(parts) < 3 {
			fmt.Println("用法: g <编号|IP> <文件名>")
			return
		}
		d := resolveDevice(parts[1])
		if d != nil {
			doGet(*d, strings.Join(parts[2:], " "))
		}
	case "p":
		if len(parts) < 3 {
			fmt.Println("用法: p <编号|IP> <本地文件路径>")
			return
		}
		d := resolveDevice(parts[1])
		if d != nil {
			doSend(*d, strings.Join(parts[2:], " "))
		}
	case "y":
		answerConfirm(true)
	case "n":
		answerConfirm(false)
	case "q", "quit", "exit":
		fmt.Println("再见")
		os.Exit(0)
	case "h", "help", "?":
		printHelp()
	default:
		fmt.Println("未知命令，输入 h 查看帮助")
	}
}

// ---------- 服务端 ----------

func startServer() {
	ln, err := net.Listen("tcp", ":"+strconv.Itoa(port))
	if err != nil {
		fmt.Println("!! 无法监听端口 " + strconv.Itoa(port) + "：" + err.Error())
		return
	}
	for {
		conn, err := ln.Accept()
		if err != nil {
			continue
		}
		go handleConn(conn)
	}
}

func handleConn(conn net.Conn) {
	defer conn.Close()
	remoteIP := hostOf(conn.RemoteAddr())
	reader := bufio.NewReader(conn)

	line, err := readLine(reader)
	if err != nil || line == "" {
		return
	}
	parts := strings.SplitN(line, " ", 4)
	cmd := parts[0]

	switch cmd {
	case "DISCOVER":
		writeLine(conn, "HELLO "+myName)

	case "CONNECT":
		name := arg(parts, 1)
		if name == "" {
			name = remoteIP
		}
		if askConfirm("配对请求: " + name + " (" + remoteIP + ")") {
			mu.Lock()
			pairedIP[remoteIP] = true
			mu.Unlock()
			writeLine(conn, "ACCEPT")
			fmt.Println("[已配对] " + name + " (" + remoteIP + ")")
		} else {
			writeLine(conn, "REJECT")
		}

	case "LIST":
		if !isPaired(remoteIP) {
			writeLine(conn, "NO 未配对")
			return
		}
		files := listLocalFiles()
		writeLine(conn, "FILES "+strconv.Itoa(len(files)))
		for _, f := range files {
			writeLine(conn, enc(f.Name)+" "+strconv.FormatInt(f.Size, 10))
		}

	case "GET":
		if !isPaired(remoteIP) {
			writeLine(conn, "NO 未配对")
			return
		}
		name := dec(arg(parts, 1))
		path := filepath.Join(dataDir, filepath.Base(name))
		fi, err := os.Stat(path)
		if err != nil || fi.IsDir() {
			writeLine(conn, "NO 文件不存在")
			return
		}
		writeLine(conn, "FILE "+enc(filepath.Base(path))+" "+strconv.FormatInt(fi.Size(), 10))
		f, err := os.Open(path)
		if err != nil {
			return
		}
		defer f.Close()
		io.Copy(conn, f)

	case "SEND":
		if !isPaired(remoteIP) {
			writeLine(conn, "NO 未配对")
			return
		}
		sender := arg(parts, 1)
		fname := dec(arg(parts, 2))
		size, _ := strconv.ParseInt(arg(parts, 3), 10, 64)
		if !askConfirm("接收文件: " + sender + " 发送 " + fname + " (" + humanSize(size) + ")") {
			writeLine(conn, "NO 对方拒绝接收")
			return
		}
		writeLine(conn, "READY")
		dest := filepath.Join(dataDir, filepath.Base(fname))
		dest = uniqueName(dest)
		f, err := os.Create(dest)
		if err != nil {
			writeLine(conn, "NO 保存失败")
			return
		}
		_, err = io.CopyN(f, reader, size)
		f.Close()
		if err != nil {
			writeLine(conn, "NO 传输中断")
			fmt.Println("!! 接收失败：" + err.Error())
			return
		}
		writeLine(conn, "OK 已接收 "+filepath.Base(dest))
		fmt.Println("[已接收] " + sender + " -> " + dest)

	case "BYE":
		mu.Lock()
		delete(pairedIP, remoteIP)
		mu.Unlock()
		writeLine(conn, "OK 已断开")

	default:
		writeLine(conn, "NO 未知命令")
	}
}

// ---------- 客户端操作 ----------

func doConnect(d Device) {
	conn, err := dial(d.IP)
	if err != nil {
		fmt.Println("连接失败：" + err.Error())
		return
	}
	defer conn.Close()
	writeLine(conn, "CONNECT "+myName)
	resp, _ := readLine(bufio.NewReader(conn))
	if resp == "ACCEPT" {
		mu.Lock()
		pairedIP[d.IP] = true
		setPaired(d.IP, true)
		mu.Unlock()
		fmt.Println("✓ 已与 " + d.Name + " 配对")
	} else {
		fmt.Println("✗ 对方拒绝或超时")
	}
}

func doList(d Device) {
	conn, err := dial(d.IP)
	if err != nil {
		fmt.Println("连接失败：" + err.Error())
		return
	}
	defer conn.Close()
	reader := bufio.NewReader(conn)
	writeLine(conn, "LIST")
	header, err := readLine(reader)
	if err != nil {
		fmt.Println("无响应")
		return
	}
	if strings.HasPrefix(header, "NO") {
		fmt.Println("对方拒绝：" + strings.TrimPrefix(header, "NO"))
		return
	}
	hp := strings.Fields(header)
	if len(hp) < 2 || hp[0] != "FILES" {
		fmt.Println("协议错误：" + header)
		return
	}
	count, _ := strconv.Atoi(hp[1])
	if count == 0 {
		fmt.Println("对方没有可下载的文件")
		return
	}
	fmt.Println(d.Name + " 的文件：")
	for i := 0; i < count; i++ {
		line, err := readLine(reader)
		if err != nil {
			break
		}
		sp := strings.LastIndex(line, " ")
		if sp <= 0 {
			continue
		}
		name := dec(line[:sp])
		size, _ := strconv.ParseInt(strings.TrimSpace(line[sp+1:]), 10, 64)
		fmt.Printf("  %-40s %s\n", name, humanSize(size))
	}
}

func doGet(d Device, name string) {
	conn, err := dial(d.IP)
	if err != nil {
		fmt.Println("连接失败：" + err.Error())
		return
	}
	defer conn.Close()
	reader := bufio.NewReader(conn)
	writeLine(conn, "GET "+enc(name))
	header, err := readLine(reader)
	if err != nil {
		fmt.Println("无响应")
		return
	}
	if strings.HasPrefix(header, "NO") {
		fmt.Println("下载失败：" + strings.TrimPrefix(header, "NO"))
		return
	}
	if !strings.HasPrefix(header, "FILE ") {
		fmt.Println("协议错误：" + header)
		return
	}
	sp := strings.LastIndex(header, " ")
	if sp <= len("FILE") {
		fmt.Println("协议错误")
		return
	}
	realName := dec(header[len("FILE "):sp])
	size, _ := strconv.ParseInt(strings.TrimSpace(header[sp+1:]), 10, 64)

	dest := uniqueName(filepath.Join(dataDir, filepath.Base(realName)))
	f, err := os.Create(dest)
	if err != nil {
		fmt.Println("无法创建文件：" + err.Error())
		return
	}
	defer f.Close()
	n, err := io.CopyN(f, reader, size)
	if err != nil {
		fmt.Println("下载中断：" + err.Error())
		return
	}
	fmt.Printf("✓ 已下载 %s (%s) -> %s\n", realName, humanSize(n), dest)
}

func doSend(d Device, localPath string) {
	fi, err := os.Stat(localPath)
	if err != nil || fi.IsDir() {
		fmt.Println("文件不存在或不是文件：" + localPath)
		return
	}
	f, err := os.Open(localPath)
	if err != nil {
		fmt.Println("无法读取文件：" + err.Error())
		return
	}
	defer f.Close()

	conn, err := dial(d.IP)
	if err != nil {
		fmt.Println("连接失败：" + err.Error())
		return
	}
	defer conn.Close()
	reader := bufio.NewReader(conn)
	writeLine(conn, "SEND "+myName+" "+enc(filepath.Base(localPath))+" "+strconv.FormatInt(fi.Size(), 10))

	resp, _ := readLine(reader)
	if resp != "READY" {
		fmt.Println("对方拒绝接收")
		return
	}
	n, err := io.CopyN(conn, f, fi.Size())
	if err != nil {
		fmt.Println("发送中断：" + err.Error())
		return
	}
	done, _ := readLine(reader)
	if strings.HasPrefix(done, "OK") {
		fmt.Printf("✓ 已发送 %s (%s)\n", filepath.Base(localPath), humanSize(n))
	} else {
		fmt.Println("对方未确认：" + done)
	}
}

// ---------- 设备扫描 ----------

func scanDevices(myIp string) {
	prefix := subnetPrefix(myIp)
	if prefix == "" {
		fmt.Println("无法确定子网，请检查网络")
		return
	}
	fmt.Println("正在扫描局域网…")

	var wg sync.WaitGroup
	sem := make(chan struct{}, 32)
	found := make(chan Device, 256)

	for i := 1; i <= 254; i++ {
		ip := prefix + strconv.Itoa(i)
		if ip == myIp {
			continue
		}
		wg.Add(1)
		go func(ip string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			if d := probe(ip); d != nil {
				found <- *d
			}
		}(ip)
	}
	go func() { wg.Wait(); close(found) }()

	var list []Device
	for d := range found {
		mu.Lock()
		d.Paired = pairedIP[d.IP]
		mu.Unlock()
		list = append(list, d)
	}
	sort.Slice(list, func(i, j int) bool { return list[i].IP < list[j].IP })

	mu.Lock()
	devices = list
	mu.Unlock()

	fmt.Printf("发现 %d 台设备\n", len(list))
	printDevices()
}

func probe(ip string) *Device {
	conn, err := net.DialTimeout("tcp", ip+":"+strconv.Itoa(port), 400*time.Millisecond)
	if err != nil {
		return nil
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(800 * time.Millisecond))
	writeLine(conn, "DISCOVER probe")
	resp, err := readLine(bufio.NewReader(conn))
	if err != nil {
		return nil
	}
	if strings.HasPrefix(resp, "HELLO ") {
		return &Device{Name: strings.TrimSpace(strings.TrimPrefix(resp, "HELLO")), IP: ip}
	}
	return nil
}

func printDevices() {
	mu.Lock()
	defer mu.Unlock()
	if len(devices) == 0 {
		fmt.Println("（无设备，输入 s 扫描）")
		return
	}
	fmt.Println("设备列表：")
	for i, d := range devices {
		mark := " "
		if d.Paired {
			mark = "✓"
		}
		fmt.Printf("  [%d] %s %-16s %s\n", i+1, mark, d.Name, d.IP)
	}
}

func resolveDevice(ref string) *Device {
	mu.Lock()
	defer mu.Unlock()
	// 按编号
	if n, err := strconv.Atoi(ref); err == nil {
		if n >= 1 && n <= len(devices) {
			return &devices[n-1]
		}
	}
	// 按 IP 或名字
	for i := range devices {
		if devices[i].IP == ref || devices[i].Name == ref {
			return &devices[i]
		}
	}
	fmt.Println("找不到设备：" + ref + "（先 s 扫描）")
	return nil
}

// ---------- 配对确认 ----------

func askConfirm(prompt string) bool {
	ch := make(chan bool, 1)
	confirmMu.Lock()
	pendingCh = ch
	confirmMu.Unlock()

	fmt.Println("\n*** " + prompt)
	fmt.Println("    >>> 输入 y 同意 / n 拒绝 <<<")

	select {
	case v := <-ch:
		confirmMu.Lock()
		pendingCh = nil
		confirmMu.Unlock()
		return v
	case <-time.After(60 * time.Second):
		confirmMu.Lock()
		pendingCh = nil
		confirmMu.Unlock()
		fmt.Println("（超时，默认拒绝）")
		return false
	}
}

func answerConfirm(ok bool) {
	confirmMu.Lock()
	ch := pendingCh
	confirmMu.Unlock()
	if ch == nil {
		fmt.Println("（当前没有待处理的请求）")
		return
	}
	select {
	case ch <- ok:
	default:
	}
}

// ---------- 工具 ----------

func localIP() string {
	conn, err := net.Dial("udp", "8.8.8.8:80")
	if err == nil {
		defer conn.Close()
		return hostOf(conn.LocalAddr())
	}
	// 退化：取第一个非回环 IPv4
	addrs, _ := net.InterfaceAddrs()
	for _, a := range addrs {
		if ipnet, ok := a.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
			if ip4 := ipnet.IP.To4(); ip4 != nil {
				return ip4.String()
			}
		}
	}
	return "127.0.0.1"
}

func hostOf(addr net.Addr) string {
	host, _, err := net.SplitHostPort(addr.String())
	if err != nil {
		return addr.String()
	}
	return host
}

func lastOctet(ip string) string {
	i := strings.LastIndex(ip, ".")
	if i < 0 {
		return ip
	}
	return ip[i+1:]
}

func subnetPrefix(ip string) string {
	i := strings.LastIndex(ip, ".")
	if i < 0 {
		return ""
	}
	return ip[:i+1]
}

func readLine(r *bufio.Reader) (string, error) {
	line, err := r.ReadString('\n')
	if err != nil && line == "" {
		return "", err
	}
	return strings.TrimRight(line, "\r\n"), nil
}

func writeLine(w io.Writer, line string) {
	io.WriteString(w, line+"\n")
}

func arg(parts []string, i int) string {
	if i >= 0 && i < len(parts) {
		return parts[i]
	}
	return ""
}

func enc(s string) string {
	// 与 Android 端 URLEncoder 兼容：空格 -> %20（简化处理）
	var b strings.Builder
	for _, c := range []byte(s) {
		if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
			(c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~' {
			b.WriteByte(c)
		} else {
			fmt.Fprintf(&b, "%%%02X", c)
		}
	}
	return b.String()
}

func dec(s string) string {
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		if s[i] == '%' && i+2 < len(s) {
			v, err := strconv.ParseUint(s[i+1:i+3], 16, 8)
			if err == nil {
				b.WriteByte(byte(v))
				i += 2
				continue
			}
		}
		if s[i] == '+' {
			b.WriteByte(' ')
		} else {
			b.WriteByte(s[i])
		}
	}
	return b.String()
}

func isPaired(ip string) bool {
	mu.Lock()
	defer mu.Unlock()
	return pairedIP[ip]
}

func setPaired(ip string, v bool) {
	for i := range devices {
		if devices[i].IP == ip {
			devices[i].Paired = v
		}
	}
}

func dial(ip string) (net.Conn, error) {
	conn, err := net.DialTimeout("tcp", ip+":"+strconv.Itoa(port), dialTimeout)
	if err != nil {
		return nil, err
	}
	conn.SetDeadline(time.Now().Add(ioTimeout))
	return conn, nil
}

type FileInfo struct {
	Name string
	Size int64
}

func listLocalFiles() []FileInfo {
	var out []FileInfo
	entries, err := os.ReadDir(dataDir)
	if err != nil {
		return out
	}
	for _, e := range entries {
		if e.IsDir() || strings.HasSuffix(e.Name(), ".part") {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, FileInfo{Name: e.Name(), Size: info.Size()})
	}
	return out
}

func uniqueName(path string) string {
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return path
	}
	ext := filepath.Ext(path)
	base := strings.TrimSuffix(path, ext)
	return base + "_" + strconv.FormatInt(time.Now().UnixNano(), 10) + ext
}

func humanSize(b int64) string {
	switch {
	case b < 1024:
		return strconv.FormatInt(b, 10) + " B"
	case b < 1048576:
		return fmt.Sprintf("%.1f KB", float64(b)/1024)
	case b < 1073741824:
		return fmt.Sprintf("%.1f MB", float64(b)/1048576)
	default:
		return fmt.Sprintf("%.2f GB", float64(b)/1073741824)
	}
}
