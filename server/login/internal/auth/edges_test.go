package auth

import (
	"context"
	"errors"
	"github.com/gin-gonic/gin"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestConfigurationValidation(t *testing.T) {
	for _, change := range []func(*Config){
		func(c *Config) { c.SessionTTL = -time.Second }, func(c *Config) { c.SessionTTL = 366 * 24 * time.Hour },
		func(c *Config) { c.RateLimit.Window = -time.Second }, func(c *Config) { c.RateLimit.IPMax = -1 },
		func(c *Config) { c.RateLimit.AccountMax = -1 }, func(c *Config) { c.RateLimit.MaxEntries = 1 },
		func(c *Config) { c.TrustProxy = true; c.TrustedProxies = []string{"bad-IP"} }, func(c *Config) { c.DBPath = ":memory:" },
	} {
		cfg := Config{DBPath: filepath.Join(t.TempDir(), "auth.sqlite")}
		change(&cfg)
		s, err := NewServer(cfg)
		if err == nil {
			s.Close()
			t.Error("invalid config accepted")
		}
	}
	for _, proxies := range [][]string{nil, {"127.0.0.1", "::1"}} {
		s, _ := newTestServer(t, func(c *Config) { c.TrustProxy = true; c.TrustedProxies = proxies })
		r := httptest.NewRequest("GET", "/", nil)
		r.RemoteAddr = "127.0.0.1:1234"
		if !s.trustedPeer(r) {
			t.Errorf("valid proxy config rejected: %v", proxies)
		}
	}
}
func TestProxyIPParsingAndSpoofBoundaries(t *testing.T) {
	s, _ := newTestServer(t, func(c *Config) { c.TrustProxy = true })
	for _, tc := range []struct {
		peer, forwarded, expected string
		trusted                   bool
	}{
		{"127.0.0.1:123", "203.0.113.2, 192.0.2.1", "192.0.2.1", true},
		{"127.0.0.1", "invalid", "127.0.0.1", true}, {"[::1]:123", "2001:db8::1", "2001:db8::1", true},
		{"192.0.2.5:123", "203.0.113.99", "192.0.2.5", false}, {"192.0.2.5", "", "192.0.2.5", false}, {"bad-peer", "203.0.113.99", "unknown", false},
	} {
		r := httptest.NewRequest("GET", "/", nil)
		r.RemoteAddr = tc.peer
		r.Header.Set("X-Forwarded-For", tc.forwarded)
		if got := s.clientIP(r); got != tc.expected {
			t.Errorf("peer=%q got=%q want=%q", tc.peer, got, tc.expected)
		}
		if s.trustedPeer(r) != tc.trusted {
			t.Errorf("peer trust wrong: %q", tc.peer)
		}
	}
	h := s.HTTPServer("127.0.0.1:8787")
	if h.Handler != s.Handler() || h.ReadHeaderTimeout <= 0 || h.ReadTimeout <= 0 || h.WriteTimeout <= 0 || h.IdleTimeout <= 0 || h.MaxHeaderBytes > 16*1024 {
		t.Fatalf("missing HTTP limits: %#v", h)
	}
}
func TestLoginInputValidationAndBusyProtection(t *testing.T) {
	s, h := newTestServer(t, nil)
	for _, body := range []map[string]any{
		{"username": "ab", "password": testPassword}, {"username": "alice", "password": "weak"},
		{"username": "alice", "password": testPassword, "deviceName": strings.Repeat("x", 81)},
		{"username": "alice", "password": testPassword, "deviceName": strings.Repeat("中", 90)},
		{"username": "alice", "password": testPassword, "deviceName": "phone\nheader"},
	} {
		expectStatus(t, apiRequest(t, h.URL, "POST", "/v1/auth/login", "", body), 400)
	}
	r := httptest.NewRequest("POST", "/v1/auth/login", strings.NewReader("null"))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != 400 {
		t.Fatalf("null login status=%d", w.Code)
	}
	for i := 0; i < cap(s.hashSlots); i++ {
		s.hashSlots <- struct{}{}
	}
	busy := login(t, h.URL, "alice", "phone")
	expectStatus(t, busy, 503)
	if busy.header.Get("Retry-After") == "" {
		t.Fatal("missing busy Retry-After")
	}
	for i := 0; i < cap(s.hashSlots); i++ {
		<-s.hashSlots
	}
	expectStatus(t, apiRequest(t, h.URL, "GET", "/v1/auth/me", strings.Repeat("z", 64), nil), 401)
}
func TestDatabaseFailuresAreSanitizedAndNotSuccessful(t *testing.T) {
	s, h := newTestServer(t, nil)
	createTestUser(t, s, "alice")
	token := tokenOf(t, login(t, h.URL, "alice", "phone"))
	if _, err := s.Store().db.Exec(`CREATE TRIGGER reject_session_delete BEFORE DELETE ON sessions BEGIN SELECT RAISE(ABORT,'secret database detail'); END`); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"/v1/auth/logout", "/v1/auth/logout-all"} {
		got := apiRequest(t, h.URL, "POST", path, token, nil)
		expectStatus(t, got, 500)
		if strings.Contains(string(got.body), "secret database detail") {
			t.Fatal("database details leaked")
		}
	}
	if _, err := s.Store().db.Exec(`UPDATE sessions SET created_at='not-a-number'`); err != nil {
		t.Fatal(err)
	}
	expectStatus(t, apiRequest(t, h.URL, "GET", "/v1/auth/sessions", token, nil), 500)
	if _, err := s.Store().db.Exec(`CREATE TRIGGER reject_session_insert BEFORE INSERT ON sessions BEGIN SELECT RAISE(ABORT,'secret insert'); END`); err != nil {
		t.Fatal(err)
	}
	expectStatus(t, login(t, h.URL, "alice", "phone"), 500)
	s.Store().Close()
	expectStatus(t, apiRequest(t, h.URL, "GET", "/healthz", "", nil), 503)
	expectStatus(t, login(t, h.URL, "alice", "phone"), 500)
	expectStatus(t, apiRequest(t, h.URL, "GET", "/v1/auth/me", token, nil), 500)
}
func TestCancelledLoginDoesNotPersistSession(t *testing.T) {
	s, _ := newTestServer(t, nil)
	createTestUser(t, s, "alice")
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	r := httptest.NewRequest("POST", "/v1/auth/login", strings.NewReader(`{"username":"alice","password":"`+testPassword+`"}`)).WithContext(ctx)
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	var count int
	if err := s.Store().db.QueryRow("SELECT COUNT(*) FROM sessions").Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatal("cancelled login created session")
	}
}
func TestPanicRecoveryDoesNotExposeDetails(t *testing.T) {
	s, h := newTestServer(t, nil)
	s.Handler().(*gin.Engine).GET("/test-panic", func(c *gin.Context) { panic("secret detail") })
	got := apiRequest(t, h.URL, "GET", "/test-panic", "", nil)
	expectStatus(t, got, 500)
	if strings.Contains(string(got.body), "secret") {
		t.Fatal("panic details leaked")
	}
}
func TestLimiterBoundedMemoryAndCleanup(t *testing.T) {
	limiter := &loginLimiter{buckets: map[string]rateBucket{}, config: RateLimitConfig{Window: time.Minute, IPMax: 10, AccountMax: 10, MaxEntries: 2}}
	now := time.Unix(1800000000, 0)
	if _, ok := limiter.allow("ip1", "alice", now); !ok {
		t.Fatal("first rejected")
	}
	if wait, ok := limiter.allow("ip2", "bob", now); ok || wait < 1 {
		t.Fatal("memory limit not enforced")
	}
	if len(limiter.buckets) > 2 {
		t.Fatal("memory exceeded")
	}
	if _, ok := limiter.allow("ip2", "bob", now.Add(time.Minute)); !ok {
		t.Fatal("expiry did not recover capacity")
	}
}
func TestStoreRejectsInvalidDataAndFailedUpdates(t *testing.T) {
	store := openTestStore(t)
	c := storeCredential(t, store, "alice")
	if _, _, err := store.createSession(credential{user: User{ID: "missing"}}, "phone", time.Now(), time.Hour); err == nil {
		t.Fatal("missing user session created")
	}
	if _, err := store.db.Exec(`UPDATE users SET created_at='invalid-integer'`); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ListUsers(); err == nil {
		t.Fatal("corrupt row accepted")
	}
	if _, err := store.db.Exec(`CREATE TRIGGER reject_user_update BEFORE UPDATE ON users BEGIN SELECT RAISE(ABORT,'test update failure'); END`); err != nil {
		t.Fatal(err)
	}
	if err := store.ResetPassword("alice", testPassword); err == nil {
		t.Fatal("failed update ignored")
	}
	if err := store.DisableUser("alice"); err == nil {
		t.Fatal("failed update ignored")
	}
	if _, err := store.verify("alice", testPassword); err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.createSession(c, "phone", time.Now(), time.Hour); errors.Is(err, ErrUnauthorized) {
		t.Fatalf("failed update changed version: %v", err)
	}
}
