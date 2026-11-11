package main

import (
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"hongguo/login/internal/auth"
)

func withStdin(t *testing.T, text string, fn func()) {
	t.Helper()
	original := os.Stdin
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := w.WriteString(text); err != nil {
		t.Fatal(err)
	}
	w.Close()
	os.Stdin = r
	defer func() { os.Stdin = original; r.Close() }()
	fn()
}

func captureStdout(t *testing.T, fn func()) string {
	t.Helper()
	original := os.Stdout
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	os.Stdout = w
	fn()
	w.Close()
	os.Stdout = original
	out, err := io.ReadAll(r)
	r.Close()
	if err != nil {
		t.Fatal(err)
	}
	return string(out)
}

func resetServerEnv(t *testing.T) {
	t.Helper()
	t.Setenv("DB_PATH", filepath.Join(t.TempDir(), "auth.sqlite"))
	t.Setenv("HOST", "127.0.0.1")
	t.Setenv("PORT", "8787")
	t.Setenv("TRUST_PROXY", "false")
	t.Setenv("ALLOW_INSECURE_HTTP", "false")
	t.Setenv("SESSION_TTL_SECONDS", "3600")
}

func TestHelpVersionAndArgumentValidation(t *testing.T) {
	for _, argument := range []string{"help", "--help", "-h", "version", "--version"} {
		got := captureStdout(t, func() {
			if err := run([]string{argument}); err != nil {
				t.Fatal(err)
			}
		})
		if !strings.Contains(got, version) {
			t.Errorf("%s did not report version: %q", argument, got)
		}
	}
	for _, args := range [][]string{{"unknown"}, {"serve", "unexpected"}, {"create-user"}, {"reset-password"}, {"disable-user"}, {"list-users", "unexpected"}, {"create-user", "alice", "command-line-password"}} {
		if err := run(args); err == nil {
			t.Errorf("invalid args accepted: %v", args)
		}
	}
}

func TestAdministrativeCommands(t *testing.T) {
	resetServerEnv(t)
	password := "a-test-password-123"
	command := func(args []string, secret string, wantError bool) string {
		t.Helper()
		return captureStdout(t, func() {
			withStdin(t, secret+"\n", func() {
				err := run(args)
				if (err != nil) != wantError {
					t.Fatalf("args=%v err=%v wantError=%v", args, err, wantError)
				}
				if err != nil && strings.Contains(err.Error(), secret) && secret != "" {
					t.Fatal("error leaked password")
				}
			})
		})
	}
	output := command([]string{"create-user", "Alice"}, password, false)
	if !strings.Contains(output, "alice") || strings.Contains(output, password) {
		t.Fatalf("create output=%q", output)
	}
	command([]string{"create-user", "alice"}, password, true)
	command([]string{"create-user", "bad user"}, password, true)
	command([]string{"create-user", "bob"}, "short", true)
	output = command([]string{"list-users"}, "", false)
	if !strings.Contains(output, "alice\tenabled") {
		t.Fatalf("list output=%q", output)
	}
	command([]string{"reset-password", "alice"}, "replacement-password-456", false)
	command([]string{"reset-password", "missing"}, password, true)
	command([]string{"disable-user", "alice"}, "", false)
	command([]string{"disable-user", "missing"}, "", true)
	output = command([]string{"list-users"}, "", false)
	if !strings.Contains(output, "alice\tdisabled") {
		t.Fatalf("disabled list output=%q", output)
	}
	command([]string{"create-user", "carol"}, strings.Repeat("x", 1100), true)
	t.Setenv("DB_PATH", t.TempDir())
	command([]string{"list-users"}, "", true)
}

func TestReadPasswordUsesStdinAndBounds(t *testing.T) {
	for _, tc := range []struct {
		input, want string
		wantError   bool
	}{
		{"first-password\nignored-second-line\n", "first-password", false},
		{" a-password-with-spaces \r\n", " a-password-with-spaces ", false},
		{"without-newline", "without-newline", false},
		{"", "", false},
		{strings.Repeat("x", 1025), "", true},
	} {
		withStdin(t, tc.input, func() {
			got, err := readPassword()
			if (err != nil) != tc.wantError || got != tc.want {
				t.Fatalf("readPassword got=%q err=%v want=%q error=%v", got, err, tc.want, tc.wantError)
			}
		})
	}
	original := os.Stdin
	closed, err := os.Open(filepath.Join(t.TempDir(), "missing"))
	if err == nil {
		closed.Close()
		t.Fatal("unexpected existing file")
	}
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	r.Close()
	w.Close()
	os.Stdin = r
	_, err = readPassword()
	os.Stdin = original
	if err == nil {
		t.Fatal("closed stdin silently accepted")
	}
}

func TestEnvironmentAndErrorMessages(t *testing.T) {
	if got := env("HONGGUO_UNSET_TEST_ENV", "fallback"); got != "fallback" {
		t.Fatalf("fallback=%q", got)
	}
	t.Setenv("HONGGUO_TEST_ENV", "")
	if got := env("HONGGUO_TEST_ENV", "fallback"); got != "" {
		t.Fatalf("empty explicit env=%q", got)
	}
	for _, input := range []string{"true", "false", "invalid"} {
		t.Setenv("HONGGUO_TEST_BOOL", input)
		got, err := envBool("HONGGUO_TEST_BOOL")
		if input == "invalid" {
			if err == nil {
				t.Fatal("invalid bool accepted")
			}
		} else if err != nil || got != (input == "true") {
			t.Fatalf("bool %q=%v err=%v", input, got, err)
		}
	}
	for _, err := range []error{auth.ErrInvalidInput, auth.ErrUserExists, auth.ErrUserNotFound, errors.New("raw database secret error")} {
		got := adminError(err)
		if got == nil || strings.Contains(got.Error(), "raw database secret") {
			t.Fatalf("unsafe admin error %v", got)
		}
	}
}

func TestServeConfigurationErrors(t *testing.T) {
	cases := []struct{ key, value string }{
		{"TRUST_PROXY", "wrong"}, {"ALLOW_INSECURE_HTTP", "wrong"},
		{"SESSION_TTL_SECONDS", "wrong"}, {"SESSION_TTL_SECONDS", "0"}, {"SESSION_TTL_SECONDS", "31536001"},
		{"PORT", "wrong"}, {"PORT", "0"}, {"PORT", "65536"},
		{"DB_PATH", ":memory:"}, {"HOST", "256.256.256.256"},
	}
	for _, tc := range cases {
		t.Run(tc.key+"/"+tc.value, func(t *testing.T) {
			resetServerEnv(t)
			t.Setenv(tc.key, tc.value)
			if err := serve(); err == nil {
				t.Fatal("invalid configuration started service")
			}
		})
	}
	resetServerEnv(t)
	t.Setenv("TRUST_PROXY", "wrong")
	if err := run(nil); err == nil {
		t.Fatal("default serve ignored invalid env")
	}
}

func TestServeStartsAndStopsGracefully(t *testing.T) {
	resetServerEnv(t)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	t.Setenv("PORT", strconv.Itoa(port))
	t.Setenv("ALLOW_INSECURE_HTTP", "true")
	// Keep SIGINT handled by this test process even if an early serve failure occurs.
	backupSignals := make(chan os.Signal, 1)
	signal.Notify(backupSignals, os.Interrupt)
	defer signal.Stop(backupSignals)
	done := make(chan error, 1)
	go func() { done <- run([]string{"serve"}) }()
	client := &http.Client{Timeout: time.Second}
	deadline := time.Now().Add(8 * time.Second)
	ready := false
	for time.Now().Before(deadline) {
		response, err := client.Get("http://127.0.0.1:" + strconv.Itoa(port) + "/healthz")
		if err == nil {
			response.Body.Close()
			if response.StatusCode == 200 {
				ready = true
				break
			}
		}
		select {
		case err := <-done:
			t.Fatalf("server exited before readiness: %v", err)
		default:
		}
		time.Sleep(20 * time.Millisecond)
	}
	process, err := os.FindProcess(os.Getpid())
	if err != nil {
		t.Fatal(err)
	}
	if err := process.Signal(os.Interrupt); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(8 * time.Second):
		t.Fatal("graceful shutdown timed out")
	}
	if !ready {
		t.Fatal("service never became ready")
	}
}
