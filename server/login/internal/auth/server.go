package auth

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/gin-gonic/gin"
	"io"
	"mime"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf8"
)

type RateLimitConfig struct {
	Window     time.Duration
	IPMax      int
	AccountMax int
	MaxEntries int
}
type Config struct {
	DBPath            string
	SessionTTL        time.Duration
	TrustProxy        bool
	TrustedProxies    []string
	AllowInsecureHTTP bool
	Now               func() time.Time
	RateLimit         RateLimitConfig
}
type Server struct {
	store     *Store
	handler   http.Handler
	config    Config
	proxies   []*net.IPNet
	limiter   *loginLimiter
	hashSlots chan struct{}
}
type rateBucket struct {
	count int
	until time.Time
}
type loginLimiter struct {
	mu        sync.Mutex
	buckets   map[string]rateBucket
	config    RateLimitConfig
	cleanupAt time.Time
}

func NewServer(config Config) (*Server, error) {
	if config.SessionTTL == 0 {
		config.SessionTTL = 30 * 24 * time.Hour
	}
	if config.SessionTTL < time.Second || config.SessionTTL > 365*24*time.Hour {
		return nil, ErrInvalidInput
	}
	if config.Now == nil {
		config.Now = time.Now
	}
	if config.RateLimit.Window == 0 {
		config.RateLimit.Window = 15 * time.Minute
	}
	if config.RateLimit.IPMax == 0 {
		config.RateLimit.IPMax = 30
	}
	if config.RateLimit.AccountMax == 0 {
		config.RateLimit.AccountMax = 10
	}
	if config.RateLimit.MaxEntries == 0 {
		config.RateLimit.MaxEntries = 10000
	}
	if config.RateLimit.Window < time.Second || config.RateLimit.IPMax < 1 || config.RateLimit.AccountMax < 1 || config.RateLimit.MaxEntries < 2 {
		return nil, ErrInvalidInput
	}
	s := &Server{config: config, hashSlots: make(chan struct{}, 4), limiter: &loginLimiter{buckets: make(map[string]rateBucket), config: config.RateLimit}}
	if config.TrustProxy {
		proxies := config.TrustedProxies
		if len(proxies) == 0 {
			proxies = []string{"127.0.0.1/32", "::1/128"}
		}
		for _, proxy := range proxies {
			ip, network, err := net.ParseCIDR(strings.TrimSpace(proxy))
			if err != nil {
				ip = net.ParseIP(strings.TrimSpace(proxy))
				if ip == nil {
					return nil, ErrInvalidInput
				}
				bits := 128
				if ip.To4() != nil {
					bits = 32
				}
				network = &net.IPNet{IP: ip, Mask: net.CIDRMask(bits, bits)}
			}
			s.proxies = append(s.proxies, network)
		}
	}
	if err := initializeDummy(); err != nil {
		return nil, err
	}
	store, err := OpenStore(config.DBPath)
	if err != nil {
		return nil, err
	}
	s.store = store
	router := gin.New()
	// Disable Gin's default trust-all behavior; validate proxy peers explicitly.
	if err = router.SetTrustedProxies(nil); err != nil {
		store.Close()
		return nil, err
	}
	router.RedirectTrailingSlash = false
	router.RedirectFixedPath = false
	router.Use(func(c *gin.Context) {
		c.Header("Cache-Control", "no-store")
		c.Header("X-Content-Type-Options", "nosniff")
		c.Header("Referrer-Policy", "no-referrer")
		c.Next()
	})
	router.Use(gin.CustomRecoveryWithWriter(io.Discard, func(c *gin.Context, _ any) { replyError(c, 500, "INTERNAL_ERROR", "服务暂时不可用") }))
	router.NoRoute(func(c *gin.Context) { replyError(c, 404, "NOT_FOUND", "接口不存在") })
	router.GET("/healthz", func(c *gin.Context) {
		if err := s.store.db.PingContext(c.Request.Context()); err != nil {
			replyError(c, 503, "UNAVAILABLE", "服务暂时不可用")
			return
		}
		c.JSON(200, gin.H{"status": "ok"})
	})
	api := router.Group("/v1/auth", s.requireHTTPS)
	api.POST("/login", s.login)
	protected := api.Group("", s.requireSession)
	protected.GET("/me", func(c *gin.Context) { c.JSON(200, gin.H{"user": getIdentity(c).User}) })
	protected.POST("/logout", func(c *gin.Context) {
		i := getIdentity(c)
		if err := s.store.revokeSession(i, i.SessionID); err != nil {
			handleStoreError(c, err)
			return
		}
		c.Status(204)
	})
	protected.GET("/sessions", func(c *gin.Context) {
		sessions, err := s.store.listSessions(getIdentity(c), s.config.Now())
		if err != nil {
			handleStoreError(c, err)
			return
		}
		c.JSON(200, gin.H{"sessions": sessions})
	})
	protected.DELETE("/sessions/:id", func(c *gin.Context) {
		if err := s.store.revokeSession(getIdentity(c), c.Param("id")); err != nil {
			handleStoreError(c, err)
			return
		}
		c.Status(204)
	})
	protected.POST("/logout-all", func(c *gin.Context) {
		if err := s.store.revokeAll(getIdentity(c)); err != nil {
			handleStoreError(c, err)
			return
		}
		c.Status(204)
	})
	history := router.Group("/v1/history", s.requireHTTPS, s.requireSession)
	history.GET("", s.getHistory)
	history.POST("/sync", s.syncHistory)
	s.handler = router
	return s, nil
}
func (s *Server) Handler() http.Handler { return s.handler }
func (s *Server) Store() *Store         { return s.store }
func (s *Server) Close() error          { return s.store.Close() }

// Shut down this HTTP server before closing its Store.
func (s *Server) HTTPServer(address string) *http.Server {
	return &http.Server{Addr: address, Handler: s.handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 * 1024}
}
func replyError(c *gin.Context, status int, code, message string) {
	c.AbortWithStatusJSON(status, gin.H{"error": gin.H{"code": code, "message": message}})
}
func handleStoreError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, ErrUnauthorized):
		replyError(c, 401, "UNAUTHORIZED", "登录已失效，请重新登录")
	case errors.Is(err, ErrSessionNotFound):
		replyError(c, 404, "SESSION_NOT_FOUND", "会话不存在")
	default:
		replyError(c, 500, "INTERNAL_ERROR", "服务暂时不可用")
	}
}
func (s *Server) trustedPeer(r *http.Request) bool {
	if !s.config.TrustProxy {
		return false
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	for _, network := range s.proxies {
		if network.Contains(ip) {
			return true
		}
	}
	return false
}
func (s *Server) requireHTTPS(c *gin.Context) {
	if s.config.AllowInsecureHTTP || c.Request.TLS != nil || (s.trustedPeer(c.Request) && c.GetHeader("X-Forwarded-Proto") == "https") {
		c.Next()
		return
	}
	replyError(c, 403, "HTTPS_REQUIRED", "请通过 HTTPS 连接登录服务")
}
func (s *Server) clientIP(r *http.Request) string {
	if s.trustedPeer(r) {
		parts := strings.Split(r.Header.Get("X-Forwarded-For"), ",")
		if ip := net.ParseIP(strings.TrimSpace(parts[len(parts)-1])); ip != nil {
			return ip.String()
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if ip := net.ParseIP(host); ip != nil {
		return ip.String()
	}
	return "unknown"
}
func (s *Server) requireSession(c *gin.Context) {
	fields := strings.Fields(c.GetHeader("Authorization"))
	if len(fields) != 2 || !strings.EqualFold(fields[0], "Bearer") || len(fields[1]) != 64 {
		replyError(c, 401, "UNAUTHORIZED", "登录已失效，请重新登录")
		return
	}
	if _, err := hex.DecodeString(fields[1]); err != nil {
		replyError(c, 401, "UNAUTHORIZED", "登录已失效，请重新登录")
		return
	}
	i, err := s.store.authenticate(fields[1], s.config.Now())
	if err != nil {
		handleStoreError(c, err)
		return
	}
	c.Set("identity", i)
	c.Next()
}
func getIdentity(c *gin.Context) identity { value, _ := c.Get("identity"); return value.(identity) }

type loginInput struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	DeviceName string `json:"deviceName"`
}

func decodeLogin(c *gin.Context) (*loginInput, bool) {
	media, _, err := mime.ParseMediaType(c.GetHeader("Content-Type"))
	if err != nil || media != "application/json" {
		replyError(c, 415, "UNSUPPORTED_MEDIA_TYPE", "请使用 application/json")
		return nil, false
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 8*1024)
	decoder := json.NewDecoder(c.Request.Body)
	decoder.DisallowUnknownFields()
	var input *loginInput
	if err = decoder.Decode(&input); err != nil {
		bodyError(c, err)
		return nil, false
	}
	if input == nil {
		replyError(c, 400, "INVALID_INPUT", "请求格式有误")
		return nil, false
	}
	var trailing any
	if err = decoder.Decode(&trailing); err != io.EOF {
		if err == nil {
			err = ErrInvalidInput
		}
		bodyError(c, err)
		return nil, false
	}
	return input, true
}
func bodyError(c *gin.Context, err error) {
	var tooLarge *http.MaxBytesError
	if errors.As(err, &tooLarge) {
		replyError(c, 413, "BODY_TOO_LARGE", "请求内容过大")
	} else {
		replyError(c, 400, "INVALID_INPUT", "请求格式有误")
	}
}
func (s *Server) login(c *gin.Context) {
	input, ok := decodeLogin(c)
	if !ok {
		return
	}
	name, err := normalizeUsername(input.Username)
	if err != nil || validatePassword(input.Password) != nil {
		replyError(c, 400, "INVALID_INPUT", "用户名或密码格式有误")
		return
	}
	device := strings.TrimSpace(input.DeviceName)
	if len(device) > 256 || utf8.RuneCountInString(device) > 80 || !utf8.ValidString(device) {
		replyError(c, 400, "INVALID_INPUT", "设备名称格式有误")
		return
	}
	for _, r := range device {
		if unicode.IsControl(r) {
			replyError(c, 400, "INVALID_INPUT", "设备名称格式有误")
			return
		}
	}
	if device == "" {
		device = "未命名设备"
	}
	if wait, allowed := s.limiter.allow(s.clientIP(c.Request), name, s.config.Now()); !allowed {
		c.Header("Retry-After", strconv.Itoa(wait))
		replyError(c, 429, "RATE_LIMITED", "尝试过于频繁，请稍后再试")
		return
	}
	select {
	case s.hashSlots <- struct{}{}:
		defer func() { <-s.hashSlots }()
	default:
		c.Header("Retry-After", "2")
		replyError(c, 503, "BUSY", "登录请求较多，请稍后再试")
		return
	}
	credential, err := s.store.verify(name, input.Password)
	if errors.Is(err, ErrUnauthorized) {
		replyError(c, 401, "INVALID_CREDENTIALS", "用户名或密码不正确")
		return
	}
	if err != nil {
		handleStoreError(c, err)
		return
	}
	if c.Request.Context().Err() != nil {
		return
	}
	token, session, err := s.store.createSession(credential, device, s.config.Now(), s.config.SessionTTL)
	if errors.Is(err, ErrUnauthorized) {
		replyError(c, 401, "INVALID_CREDENTIALS", "用户名或密码不正确")
		return
	}
	if err != nil {
		handleStoreError(c, err)
		return
	}
	c.JSON(200, gin.H{"token": token, "expiresAt": session.ExpiresAt, "user": credential.user, "session": gin.H{"id": session.ID, "deviceName": session.DeviceName}})
}
func (l *loginLimiter) allow(ip, account string, now time.Time) (int, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if !now.Before(l.cleanupAt) || len(l.buckets) >= l.config.MaxEntries {
		for key, b := range l.buckets {
			if !now.Before(b.until) {
				delete(l.buckets, key)
			}
		}
		l.cleanupAt = now.Add(time.Minute)
	}
	keys := []string{"ip:" + ip, "account:" + account}
	limits := []int{l.config.IPMax, l.config.AccountMax}
	missing := 0
	wait := 0
	for n, key := range keys {
		b, exists := l.buckets[key]
		if !exists || !now.Before(b.until) {
			missing++
			continue
		}
		if b.count >= limits[n] {
			seconds := int(b.until.Sub(now).Seconds()) + 1
			if seconds > wait {
				wait = seconds
			}
		}
	}
	if wait > 0 {
		return wait, false
	}
	if len(l.buckets)+missing > l.config.MaxEntries {
		return int(l.config.Window.Seconds()) + 1, false
	}
	for _, key := range keys {
		b := l.buckets[key]
		if !now.Before(b.until) {
			b = rateBucket{until: now.Add(l.config.Window)}
		}
		b.count++
		l.buckets[key] = b
	}
	return 0, true
}
