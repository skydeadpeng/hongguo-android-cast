package auth

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const testPassword = "a-strong-test-password-123"

type apiResult struct {
	status int
	header http.Header
	body   []byte
	object map[string]any
}

func newTestServer(t *testing.T, configure func(*Config)) (*Server, *httptest.Server) {
	t.Helper()
	cfg := Config{DBPath: filepath.Join(t.TempDir(), "auth.sqlite"), AllowInsecureHTTP: true}
	if configure != nil {
		configure(&cfg)
	}
	service, err := NewServer(cfg)
	if err != nil {
		t.Fatal(err)
	}
	httpServer := httptest.NewServer(service.Handler())
	t.Cleanup(func() {
		httpServer.Close()
		if err := service.Close(); err != nil {
			t.Error(err)
		}
	})
	return service, httpServer
}

func apiRequest(t *testing.T, base, method, path, token string, payload any) apiResult {
	t.Helper()
	var body io.Reader
	if payload != nil {
		b, err := json.Marshal(payload)
		if err != nil {
			t.Fatal(err)
		}
		body = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, base+path, body)
	if err != nil {
		t.Fatal(err)
	}
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	b, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	out := apiResult{status: response.StatusCode, header: response.Header, body: b}
	if len(b) > 0 {
		_ = json.Unmarshal(b, &out.object)
	}
	return out
}

func expectStatus(t *testing.T, got apiResult, want int) {
	t.Helper()
	if got.status != want {
		t.Fatalf("HTTP status=%d, want=%d; body=%s", got.status, want, got.body)
	}
}

func createTestUser(t *testing.T, service *Server, username string) User {
	t.Helper()
	user, err := service.Store().CreateUser(username, testPassword)
	if err != nil {
		t.Fatal(err)
	}
	return user
}

func login(t *testing.T, base, username, device string) apiResult {
	t.Helper()
	return apiRequest(t, base, http.MethodPost, "/v1/auth/login", "", map[string]any{
		"username": username, "password": testPassword, "deviceName": device,
	})
}

func tokenOf(t *testing.T, result apiResult) string {
	t.Helper()
	expectStatus(t, result, http.StatusOK)
	token, ok := result.object["token"].(string)
	if !ok || len(token) < 32 {
		t.Fatalf("missing or unexpectedly short token: %s", result.body)
	}
	return token
}

func nestedString(t *testing.T, object map[string]any, field, key string) string {
	t.Helper()
	value, ok := object[field].(map[string]any)
	if !ok {
		t.Fatalf("missing object %s: %#v", field, object)
	}
	out, ok := value[key].(string)
	if !ok || out == "" {
		t.Fatalf("missing string %s.%s: %#v", field, key, object)
	}
	return out
}

func TestLoginMeAndLogoutOverHTTP(t *testing.T) {
	service, httpServer := newTestServer(t, nil)
	createTestUser(t, service, "Alice")
	loggedIn := login(t, httpServer.URL, " ALICE ", "Android 手机")
	token := tokenOf(t, loggedIn)
	if got := nestedString(t, loggedIn.object, "user", "username"); got != "alice" {
		t.Fatalf("username=%q", got)
	}
	if _, ok := loggedIn.object["expiresAt"]; !ok {
		t.Fatal("expiry missing")
	}
	if _, ok := loggedIn.object["session"]; !ok {
		t.Fatal("session missing")
	}
	me := apiRequest(t, httpServer.URL, http.MethodGet, "/v1/auth/me", token, nil)
	expectStatus(t, me, http.StatusOK)
	if !bytes.Contains(me.body, []byte(`"alice"`)) {
		t.Fatalf("unexpected me response: %s", me.body)
	}
	if bytes.Contains(me.body, []byte(testPassword)) || bytes.Contains(me.body, []byte(token)) {
		t.Fatal("me leaked credentials")
	}
	for _, got := range []apiResult{loggedIn, me} {
		if !strings.Contains(got.header.Get("Cache-Control"), "no-store") {
			t.Fatalf("missing no-store: %v", got.header)
		}
		if got.header.Get("X-Content-Type-Options") != "nosniff" {
			t.Fatal("missing nosniff")
		}
	}
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodPost, "/v1/auth/logout", token, nil), http.StatusNoContent)
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodGet, "/v1/auth/me", token, nil), http.StatusUnauthorized)
}

func TestInvalidCredentialsUniformAndDisabledUser(t *testing.T) {
	service, httpServer := newTestServer(t, nil)
	createTestUser(t, service, "alice")
	var reference []byte
	for _, username := range []string{"alice", "unknown"} {
		got := apiRequest(t, httpServer.URL, http.MethodPost, "/v1/auth/login", "", map[string]any{"username": username, "password": "incorrect-password-123"})
		expectStatus(t, got, http.StatusUnauthorized)
		if reference == nil {
			reference = got.body
		} else if !bytes.Equal(reference, got.body) {
			t.Fatalf("user enumeration via response: %s != %s", reference, got.body)
		}
		if !strings.Contains(got.header.Get("Cache-Control"), "no-store") {
			t.Fatal("auth failure may be cached")
		}
	}
	token := tokenOf(t, login(t, httpServer.URL, "alice", "phone"))
	if err := service.Store().DisableUser("alice"); err != nil {
		t.Fatal(err)
	}
	got := login(t, httpServer.URL, "alice", "phone")
	expectStatus(t, got, http.StatusUnauthorized)
	if !bytes.Equal(got.body, reference) {
		t.Fatalf("disabled-user response differs: %s", got.body)
	}
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodGet, "/v1/auth/me", token, nil), http.StatusUnauthorized)
}

func TestSessionOwnershipRevokeAndLogoutAll(t *testing.T) {
	service, httpServer := newTestServer(t, nil)
	createTestUser(t, service, "alice")
	createTestUser(t, service, "bob")
	first := login(t, httpServer.URL, "alice", "phone")
	second := login(t, httpServer.URL, "alice", "TV")
	bob := login(t, httpServer.URL, "bob", "phone")
	firstToken, secondToken, bobToken := tokenOf(t, first), tokenOf(t, second), tokenOf(t, bob)
	firstID := nestedString(t, first.object, "session", "id")
	secondID := nestedString(t, second.object, "session", "id")
	sessions := apiRequest(t, httpServer.URL, http.MethodGet, "/v1/auth/sessions", firstToken, nil)
	expectStatus(t, sessions, http.StatusOK)
	if !bytes.Contains(sessions.body, []byte(firstID)) || !bytes.Contains(sessions.body, []byte(secondID)) {
		t.Fatalf("own sessions missing: %s", sessions.body)
	}
	if bytes.Contains(sessions.body, []byte(bobToken)) || bytes.Contains(sessions.body, []byte(firstToken)) || bytes.Contains(sessions.body, []byte(secondToken)) {
		t.Fatal("session list leaked token")
	}
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodDelete, "/v1/auth/sessions/"+firstID, bobToken, nil), http.StatusNotFound)
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodGet, "/v1/auth/me", firstToken, nil), http.StatusOK)
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodDelete, "/v1/auth/sessions/"+secondID, firstToken, nil), http.StatusNoContent)
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodGet, "/v1/auth/me", secondToken, nil), http.StatusUnauthorized)
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodPost, "/v1/auth/logout-all", firstToken, nil), http.StatusNoContent)
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodGet, "/v1/auth/me", firstToken, nil), http.StatusUnauthorized)
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodGet, "/v1/auth/me", bobToken, nil), http.StatusOK)
}

func TestPasswordResetRevokesSessions(t *testing.T) {
	service, httpServer := newTestServer(t, nil)
	createTestUser(t, service, "alice")
	token := tokenOf(t, login(t, httpServer.URL, "alice", "phone"))
	replacement := "a-replacement-password-456"
	if err := service.Store().ResetPassword(" ALICE ", replacement); err != nil {
		t.Fatal(err)
	}
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodGet, "/v1/auth/me", token, nil), http.StatusUnauthorized)
	expectStatus(t, login(t, httpServer.URL, "alice", "phone"), http.StatusUnauthorized)
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodPost, "/v1/auth/login", "", map[string]any{"username": "alice", "password": replacement}), http.StatusOK)
}

func TestExpiryWithInjectedClock(t *testing.T) {
	var seconds atomic.Int64
	seconds.Store(1_800_000_000)
	service, httpServer := newTestServer(t, func(cfg *Config) {
		cfg.Now = func() time.Time { return time.Unix(seconds.Load(), 0) }
		cfg.SessionTTL = time.Hour
	})
	createTestUser(t, service, "alice")
	token := tokenOf(t, login(t, httpServer.URL, "alice", "phone"))
	seconds.Add(3599)
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodGet, "/v1/auth/me", token, nil), http.StatusOK)
	seconds.Add(1)
	expectStatus(t, apiRequest(t, httpServer.URL, http.MethodGet, "/v1/auth/me", token, nil), http.StatusUnauthorized)
}

func TestPersistentSessionsAcrossRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "auth.sqlite")
	cfg := Config{DBPath: path, AllowInsecureHTTP: true}
	first, err := NewServer(cfg)
	if err != nil {
		t.Fatal(err)
	}
	firstHTTP := httptest.NewServer(first.Handler())
	createTestUser(t, first, "alice")
	token := tokenOf(t, login(t, firstHTTP.URL, "alice", "phone"))
	firstHTTP.Close()
	if err := first.Close(); err != nil {
		t.Fatal(err)
	}
	second, err := NewServer(cfg)
	if err != nil {
		t.Fatal(err)
	}
	secondHTTP := httptest.NewServer(second.Handler())
	t.Cleanup(func() { secondHTTP.Close(); _ = second.Close() })
	expectStatus(t, apiRequest(t, secondHTTP.URL, http.MethodGet, "/v1/auth/me", token, nil), http.StatusOK)
}

func TestHTTPTransportSecurityAndAuthentication(t *testing.T) {
	service, plainHTTP := newTestServer(t, func(cfg *Config) { cfg.AllowInsecureHTTP = false })
	createTestUser(t, service, "alice")
	expectStatus(t, login(t, plainHTTP.URL, "alice", "phone"), http.StatusForbidden)
	req, _ := http.NewRequest(http.MethodPost, plainHTTP.URL+"/v1/auth/login", strings.NewReader(`{"username":"alice","password":"`+testPassword+`"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Forwarded-Proto", "https")
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusForbidden {
		t.Fatal("untrusted forwarded proto bypasses HTTPS")
	}
	tlsServer := httptest.NewTLSServer(service.Handler())
	defer tlsServer.Close()
	req, _ = http.NewRequest(http.MethodPost, tlsServer.URL+"/v1/auth/login", strings.NewReader(`{"username":"alice","password":"`+testPassword+`"}`))
	req.Header.Set("Content-Type", "application/json")
	response, err = tlsServer.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("TLS login status=%d", response.StatusCode)
	}
}

func TestMalformedAndOversizedBodies(t *testing.T) {
	_, httpServer := newTestServer(t, nil)
	cases := []struct {
		name, contentType, body string
		status                  int
	}{
		{"wrong content type", "text/plain", `{}`, http.StatusUnsupportedMediaType},
		{"missing content type", "", `{}`, http.StatusUnsupportedMediaType},
		{"bad JSON", "application/json", `{`, http.StatusBadRequest},
		{"empty JSON", "application/json", ``, http.StatusBadRequest},
		{"wrong type", "application/json", `{"username":12,"password":true}`, http.StatusBadRequest},
		{"two JSON values", "application/json", `{} {}`, http.StatusBadRequest},
		{"oversized", "application/json", `{"password":"` + strings.Repeat("x", 64*1024) + `"}`, http.StatusRequestEntityTooLarge},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req, _ := http.NewRequest(http.MethodPost, httpServer.URL+"/v1/auth/login", strings.NewReader(tc.body))
			if tc.contentType != "" {
				req.Header.Set("Content-Type", tc.contentType)
			}
			response, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatal(err)
			}
			defer response.Body.Close()
			b, _ := io.ReadAll(response.Body)
			if response.StatusCode != tc.status {
				t.Fatalf("HTTP %d, want %d: %s", response.StatusCode, tc.status, b)
			}
		})
	}
}

func TestInvalidBearerTokens(t *testing.T) {
	_, httpServer := newTestServer(t, nil)
	for _, token := range []string{"", "abc", strings.Repeat("x", 1024), "a.b.c", "invalid=="} {
		expectStatus(t, apiRequest(t, httpServer.URL, http.MethodGet, "/v1/auth/me", token, nil), http.StatusUnauthorized)
	}
	for _, authorization := range []string{"Basic YTpi", "Bearer", "Bearer one two"} {
		req, _ := http.NewRequest(http.MethodGet, httpServer.URL+"/v1/auth/me", nil)
		req.Header.Set("Authorization", authorization)
		response, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != http.StatusUnauthorized {
			t.Fatalf("header %q returned %d", authorization, response.StatusCode)
		}
	}
}

func TestIPRateLimitCannotBeBypassedWithForwardedFor(t *testing.T) {
	_, httpServer := newTestServer(t, func(cfg *Config) { cfg.RateLimit = RateLimitConfig{Window: time.Minute, IPMax: 2, AccountMax: 100} })
	for i := 0; i < 3; i++ {
		req, _ := http.NewRequest(http.MethodPost, httpServer.URL+"/v1/auth/login", strings.NewReader(fmt.Sprintf(`{"username":"user%d","password":"wrong-password-123"}`, i)))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Forwarded-For", fmt.Sprintf("192.0.2.%d", i+1))
		response, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		expected := http.StatusUnauthorized
		if i == 2 {
			expected = http.StatusTooManyRequests
		}
		if response.StatusCode != expected {
			t.Fatalf("request %d status=%d want=%d", i, response.StatusCode, expected)
		}
		if i == 2 && response.Header.Get("Retry-After") == "" {
			t.Fatal("missing retry guidance")
		}
	}
}

func TestAccountRateLimitAndWindowReset(t *testing.T) {
	var seconds atomic.Int64
	seconds.Store(1_800_000_000)
	_, httpServer := newTestServer(t, func(cfg *Config) {
		cfg.Now = func() time.Time { return time.Unix(seconds.Load(), 0) }
		cfg.RateLimit = RateLimitConfig{Window: time.Minute, IPMax: 100, AccountMax: 2}
	})
	for i := 0; i < 2; i++ {
		expectStatus(t, login(t, httpServer.URL, " Alice ", "phone"), http.StatusUnauthorized)
	}
	expectStatus(t, login(t, httpServer.URL, "alice", "phone"), http.StatusTooManyRequests)
	expectStatus(t, login(t, httpServer.URL, "bob", "phone"), http.StatusUnauthorized)
	seconds.Add(61)
	expectStatus(t, login(t, httpServer.URL, "alice", "phone"), http.StatusUnauthorized)
}

func TestConcurrentAuthenticatedRequests(t *testing.T) {
	service, httpServer := newTestServer(t, nil)
	createTestUser(t, service, "alice")
	token := tokenOf(t, login(t, httpServer.URL, "alice", "phone"))
	var wg sync.WaitGroup
	failures := make(chan string, 24)
	for i := 0; i < 24; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			req, _ := http.NewRequest(http.MethodGet, httpServer.URL+"/v1/auth/me", nil)
			req.Header.Set("Authorization", "Bearer "+token)
			response, err := http.DefaultClient.Do(req)
			if err != nil {
				failures <- err.Error()
				return
			}
			defer response.Body.Close()
			if response.StatusCode != http.StatusOK {
				failures <- fmt.Sprintf("HTTP %d", response.StatusCode)
			}
		}()
	}
	wg.Wait()
	close(failures)
	for failure := range failures {
		t.Error(failure)
	}
}

func TestUserStoreValidationAndPersistence(t *testing.T) {
	path := filepath.Join(t.TempDir(), "nested", "auth.sqlite")
	store, err := OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	for _, username := range []string{"", "ab", strings.Repeat("a", 33), "a b", "中文用户", "alice@host"} {
		if _, err := store.CreateUser(username, testPassword); err == nil {
			t.Errorf("accepted invalid username %q", username)
		}
	}
	for _, password := range []string{"", "short", strings.Repeat("x", 73)} {
		if _, err := store.CreateUser("alice", password); err == nil {
			t.Errorf("accepted invalid password length=%d", len(password))
		}
	}
	if _, err := store.CreateUser(" Alice ", testPassword); err != nil {
		t.Fatal(err)
	}
	if _, err := store.CreateUser("ALICE", testPassword); err == nil {
		t.Fatal("accepted case-insensitive duplicate")
	}
	if err := store.ResetPassword("missing", testPassword); err == nil {
		t.Error("reset missing user succeeded")
	}
	if err := store.DisableUser("missing"); err == nil {
		t.Error("disable missing user succeeded")
	}
	if err := store.ResetPassword("alice", "weak"); err == nil {
		t.Error("accepted weak reset password")
	}
	users, err := store.ListUsers()
	if err != nil || len(users) != 1 {
		t.Fatalf("users=%#v err=%v", users, err)
	}
	b, _ := json.Marshal(users)
	if bytes.Contains(b, []byte(testPassword)) || bytes.Contains(b, []byte("$2")) {
		t.Fatal("list users leaks password hash")
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	store, err = OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	users, err = store.ListUsers()
	if err != nil || len(users) != 1 {
		t.Fatalf("users after restart=%#v err=%v", users, err)
	}
	if err := store.DisableUser("ALICE"); err != nil {
		t.Fatal(err)
	}
}

func TestTrustedProxyHTTPSAndHostBoundaries(t *testing.T) {
	service, httpServer := newTestServer(t, func(cfg *Config) {
		cfg.AllowInsecureHTTP = false
		cfg.TrustProxy = true
		cfg.TrustedProxies = []string{"127.0.0.0/8", "::1/128"}
	})
	createTestUser(t, service, "alice")
	for _, proto := range []string{"https", "http", "https,http", "http,https", ""} {
		req, _ := http.NewRequest(http.MethodPost, httpServer.URL+"/v1/auth/login", strings.NewReader(`{"username":"alice","password":"`+testPassword+`"}`))
		req.Header.Set("Content-Type", "application/json; charset=utf-8")
		req.Header.Set("X-Forwarded-Proto", proto)
		response, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		want := http.StatusForbidden
		if proto == "https" {
			want = http.StatusOK
		}
		if response.StatusCode != want {
			t.Fatalf("proxy proto %q status=%d want=%d", proto, response.StatusCode, want)
		}
	}
	request := httptest.NewRequest(http.MethodPost, "http://example.test/v1/auth/login", strings.NewReader(`{"username":"alice","password":"`+testPassword+`"}`))
	request.RemoteAddr = "192.0.2.80:1234"
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("X-Forwarded-Proto", "https")
	recorder := httptest.NewRecorder()
	service.Handler().ServeHTTP(recorder, request)
	if recorder.Code != http.StatusForbidden {
		t.Fatalf("non-proxy peer can spoof HTTPS: %d", recorder.Code)
	}
}

func TestHealthAndNoPublicRegistrationOrRedirect(t *testing.T) {
	_, httpServer := newTestServer(t, func(cfg *Config) { cfg.AllowInsecureHTTP = false })
	health := apiRequest(t, httpServer.URL, http.MethodGet, "/healthz", "", nil)
	expectStatus(t, health, http.StatusOK)
	if health.object["status"] != "ok" {
		t.Fatalf("unexpected health response %s", health.body)
	}
	client := &http.Client{CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }}
	for _, path := range []string{"/v1/auth/register", "/v1/auth/login/", "/V1/AUTH/LOGIN", "/.env", "/auth.sqlite"} {
		req, _ := http.NewRequest(http.MethodGet, httpServer.URL+path, nil)
		response, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode >= 200 && response.StatusCode < 400 {
			t.Errorf("unexpected accessible or redirected path %q HTTP=%d", path, response.StatusCode)
		}
	}
}

func TestUnknownJSONFieldsRejected(t *testing.T) {
	_, httpServer := newTestServer(t, nil)
	got := apiRequest(t, httpServer.URL, http.MethodPost, "/v1/auth/login", "", map[string]any{"username": "alice", "password": testPassword, "admin": true})
	expectStatus(t, got, http.StatusBadRequest)
}

func TestTokenIsAcceptedOnlyInAuthorizationHeader(t *testing.T) {
	service, httpServer := newTestServer(t, nil)
	createTestUser(t, service, "alice")
	token := tokenOf(t, login(t, httpServer.URL, "alice", "phone"))
	for _, parameter := range []string{"token", "access_token", "authorization"} {
		expectStatus(t, apiRequest(t, httpServer.URL, http.MethodGet, "/v1/auth/me?"+parameter+"="+token, "", nil), http.StatusUnauthorized)
	}
	req, _ := http.NewRequest(http.MethodGet, httpServer.URL+"/v1/auth/me", nil)
	req.Header.Set("Cookie", "token="+token)
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unexpected cookie-based auth HTTP=%d", response.StatusCode)
	}
}
