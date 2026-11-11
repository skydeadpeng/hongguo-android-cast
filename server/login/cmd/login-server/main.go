package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"github.com/gin-gonic/gin"
	"golang.org/x/term"
	"hongguo/login/internal/auth"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"
)

var version = "0.2.0"

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "错误：", err)
		os.Exit(1)
	}
}
func run(args []string) error {
	if len(args) == 0 {
		args = []string{"serve"}
	}
	switch args[0] {
	case "help", "--help", "-h":
		fmt.Println("hongguo-login " + version + "\n用法: hongguo-login serve|create-user USER|reset-password USER|disable-user USER|list-users|version\n密码从终端隐藏输入或标准输入读取，绝不使用命令行参数。\nDB_PATH 默认为 data/login.sqlite；监听 127.0.0.1:8787；请通过 HTTPS 反向代理访问。")
		return nil
	case "version", "--version":
		fmt.Println("hongguo-login " + version)
		return nil
	case "serve":
		if len(args) != 1 {
			return errors.New("serve 不接受额外参数")
		}
		return serve()
	case "create-user", "reset-password", "disable-user":
		if len(args) != 2 {
			return errors.New("需要且仅需要一个用户名参数")
		}
	case "list-users":
		if len(args) != 1 {
			return errors.New("list-users 不接受额外参数")
		}
	default:
		return errors.New("未知命令，请运行 --help")
	}
	store, err := auth.OpenStore(env("DB_PATH", "data/login.sqlite"))
	if err != nil {
		return errors.New("打开用户数据库失败，请检查 DB_PATH 与目录权限")
	}
	defer store.Close()
	switch args[0] {
	case "create-user", "reset-password":
		password, err := readPassword()
		if err != nil {
			return err
		}
		if args[0] == "create-user" {
			u, err := store.CreateUser(args[1], password)
			if err != nil {
				return adminError(err)
			}
			fmt.Println("用户已创建：", u.Username)
		} else {
			if err = store.ResetPassword(args[1], password); err != nil {
				return adminError(err)
			}
			fmt.Println("密码已重置，所有旧会话已撤销")
		}
	case "disable-user":
		if err = store.DisableUser(args[1]); err != nil {
			return adminError(err)
		}
		fmt.Println("用户已禁用，所有会话已撤销")
	case "list-users":
		users, err := store.ListUsers()
		if err != nil {
			return errors.New("读取用户列表失败")
		}
		for _, u := range users {
			state := "enabled"
			if u.Disabled {
				state = "disabled"
			}
			fmt.Printf("%s\t%s\t%s\n", u.Username, state, u.CreatedAt)
		}
	}
	return nil
}
func adminError(err error) error {
	switch {
	case errors.Is(err, auth.ErrInvalidInput):
		return errors.New("用户名需为 3–32 位字母、数字、_、.、-；密码至少 12 个字符且最多 72 字节")
	case errors.Is(err, auth.ErrUserExists):
		return errors.New("用户名已存在")
	case errors.Is(err, auth.ErrUserNotFound):
		return errors.New("用户不存在")
	default:
		return errors.New("用户操作失败")
	}
}
func readPassword() (string, error) {
	if term.IsTerminal(int(os.Stdin.Fd())) {
		fmt.Fprint(os.Stderr, "密码（至少 12 个字符，最多 72 字节）：")
		b, err := term.ReadPassword(int(os.Stdin.Fd()))
		fmt.Fprintln(os.Stderr)
		if err != nil {
			return "", errors.New("读取密码失败")
		}
		return string(b), nil
	}
	reader := bufio.NewReader(io.LimitReader(os.Stdin, 1025))
	line, err := reader.ReadString('\n')
	if err != nil && err != io.EOF {
		return "", errors.New("读取密码失败")
	}
	if len(line) > 1024 {
		return "", errors.New("密码输入过长")
	}
	return strings.TrimSuffix(strings.TrimSuffix(line, "\n"), "\r"), nil
}
func env(name, fallback string) string {
	if value, ok := os.LookupEnv(name); ok {
		return value
	}
	return fallback
}
func envBool(name string) (bool, error) {
	value := env(name, "false")
	result, err := strconv.ParseBool(value)
	if err != nil {
		return false, fmt.Errorf("%s 必须为 true 或 false", name)
	}
	return result, nil
}
func serve() error {
	gin.SetMode(gin.ReleaseMode)
	trust, err := envBool("TRUST_PROXY")
	if err != nil {
		return err
	}
	insecure, err := envBool("ALLOW_INSECURE_HTTP")
	if err != nil {
		return err
	}
	ttl, err := strconv.Atoi(env("SESSION_TTL_SECONDS", "2592000"))
	if err != nil || ttl < 1 || ttl > 31536000 {
		return errors.New("SESSION_TTL_SECONDS 须为 1–31536000")
	}
	port, err := strconv.Atoi(env("PORT", "8787"))
	if err != nil || port < 1 || port > 65535 {
		return errors.New("PORT 须为 1–65535")
	}
	server, err := auth.NewServer(auth.Config{DBPath: env("DB_PATH", "data/login.sqlite"), SessionTTL: time.Duration(ttl) * time.Second, TrustProxy: trust, TrustedProxies: strings.Split(env("TRUSTED_PROXIES", "127.0.0.1/32,::1/128"), ","), AllowInsecureHTTP: insecure})
	if err != nil {
		return errors.New("初始化登录服务失败，请检查数据库路径和配置")
	}
	defer server.Close()
	httpServer := server.HTTPServer(net.JoinHostPort(env("HOST", "127.0.0.1"), strconv.Itoa(port)))
	listener, err := net.Listen("tcp", httpServer.Addr)
	if err != nil {
		return errors.New("监听失败，请检查地址、端口与权限")
	}
	signals, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	done := make(chan error, 1)
	go func() { done <- httpServer.Serve(listener) }()
	fmt.Println("hongguo-login " + version + " 已启动，监听 " + listener.Addr().String())
	select {
	case err = <-done:
		if !errors.Is(err, http.ErrServerClosed) {
			return errors.New("HTTP 服务意外停止")
		}
	case <-signals.Done():
		ctx, cancel := context.WithTimeout(context.Background(), 35*time.Second)
		defer cancel()
		if err = httpServer.Shutdown(ctx); err != nil {
			httpServer.Close()
			return errors.New("服务关闭超时")
		}
		<-done
	}
	return nil
}
