package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"golang.org/x/crypto/bcrypt"
	_ "modernc.org/sqlite"
)

var (
	ErrInvalidInput    = errors.New("invalid input")
	ErrUserExists      = errors.New("user already exists")
	ErrUserNotFound    = errors.New("user not found")
	ErrUnauthorized    = errors.New("unauthorized")
	ErrSessionNotFound = errors.New("session not found")
	usernamePattern    = regexp.MustCompile(`^[a-z0-9_.-]{3,32}$`)
	dummyOnce          sync.Once
	dummyHash          []byte
	dummyErr           error
)

const passwordCost = 12
const maxSessions = 20

type User struct {
	ID        string `json:"id"`
	Username  string `json:"username"`
	Disabled  bool   `json:"disabled,omitempty"`
	CreatedAt string `json:"createdAt,omitempty"`
}

type Session struct {
	ID         string `json:"id"`
	DeviceName string `json:"deviceName"`
	CreatedAt  string `json:"createdAt"`
	ExpiresAt  string `json:"expiresAt"`
	Current    bool   `json:"current"`
}

type Store struct{ db *sql.DB }
type credential struct {
	user    User
	hash    []byte
	version int64
	enabled bool
}
type identity struct {
	User      User
	SessionID string
}

func normalizeUsername(username string) (string, error) {
	username = strings.ToLower(strings.TrimSpace(username))
	if !usernamePattern.MatchString(username) {
		return "", ErrInvalidInput
	}
	return username, nil
}
func validatePassword(password string) error {
	if !utf8.ValidString(password) || utf8.RuneCountInString(password) < 12 || len(password) > 72 {
		return ErrInvalidInput
	}
	return nil
}
func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}
func tokenHash(token string) string {
	h := sha256.Sum256([]byte(token))
	return hex.EncodeToString(h[:])
}
func timestamp(t time.Time) string { return t.UTC().Format(time.RFC3339) }

// OpenStore uses a dedicated private data directory and never changes ancestor permissions.
func OpenStore(dbPath string) (*Store, error) {
	if strings.TrimSpace(dbPath) == "" || dbPath == ":memory:" {
		return nil, fmt.Errorf("DB_PATH must name a persistent file")
	}
	absolute, err := filepath.Abs(dbPath)
	if err != nil {
		return nil, err
	}
	dir := filepath.Dir(absolute)
	if err = os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	if fi, statErr := os.Lstat(absolute); statErr == nil && !fi.Mode().IsRegular() {
		return nil, fmt.Errorf("database must be a regular file")
	} else if statErr != nil && !os.IsNotExist(statErr) {
		return nil, statErr
	}
	f, err := os.OpenFile(absolute, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return nil, err
	}
	if err = f.Chmod(0600); err != nil {
		f.Close()
		return nil, err
	}
	if err = f.Close(); err != nil {
		return nil, err
	}
	query := url.Values{}
	query.Add("_pragma", "busy_timeout(5000)")
	query.Add("_pragma", "foreign_keys(1)")
	dsn := (&url.URL{Scheme: "file", Path: filepath.ToSlash(absolute), RawQuery: query.Encode()}).String()
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)
	schema := `PRAGMA busy_timeout=5000;
 PRAGMA foreign_keys=ON;
 PRAGMA journal_mode=WAL;
 CREATE TABLE IF NOT EXISTS users (
 id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, password_hash BLOB NOT NULL,
 enabled INTEGER NOT NULL DEFAULT 1, auth_version INTEGER NOT NULL DEFAULT 0,
 created_at INTEGER NOT NULL
 );
 CREATE TABLE IF NOT EXISTS sessions (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 token_hash TEXT NOT NULL UNIQUE, device_name TEXT NOT NULL,
 created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL
 );
 CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id,created_at);
 CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires_at);`
	if _, err = db.Exec(schema + historySchema); err != nil {
		db.Close()
		return nil, err
	}
	return &Store{db: db}, nil
}
func (s *Store) Close() error { return s.db.Close() }
func (s *Store) CreateUser(username, password string) (User, error) {
	name, err := normalizeUsername(username)
	if err != nil {
		return User{}, err
	}
	if err = validatePassword(password); err != nil {
		return User{}, err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), passwordCost)
	if err != nil {
		return User{}, err
	}
	id, err := randomHex(16)
	if err != nil {
		return User{}, err
	}
	now := time.Now()
	_, err = s.db.Exec("INSERT INTO users(id,username,password_hash,created_at) VALUES(?,?,?,?)", id, name, hash, now.Unix())
	if err != nil {
		var exists int
		if e := s.db.QueryRow("SELECT COUNT(*) FROM users WHERE username=?", name).Scan(&exists); e == nil && exists > 0 {
			return User{}, ErrUserExists
		}
		return User{}, err
	}
	return User{ID: id, Username: name, CreatedAt: timestamp(now)}, nil
}
func (s *Store) ResetPassword(username, password string) error {
	name, err := normalizeUsername(username)
	if err != nil {
		return err
	}
	if err = validatePassword(password); err != nil {
		return err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), passwordCost)
	if err != nil {
		return err
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	result, err := tx.Exec("UPDATE users SET password_hash=?,auth_version=auth_version+1 WHERE username=?", hash, name)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count == 0 {
		return ErrUserNotFound
	}
	if _, err = tx.Exec("DELETE FROM sessions WHERE user_id IN (SELECT id FROM users WHERE username=?)", name); err != nil {
		return err
	}
	return tx.Commit()
}
func (s *Store) DisableUser(username string) error {
	name, err := normalizeUsername(username)
	if err != nil {
		return err
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	result, err := tx.Exec("UPDATE users SET enabled=0,auth_version=auth_version+1 WHERE username=?", name)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count == 0 {
		return ErrUserNotFound
	}
	if _, err = tx.Exec("DELETE FROM sessions WHERE user_id IN (SELECT id FROM users WHERE username=?)", name); err != nil {
		return err
	}
	return tx.Commit()
}
func (s *Store) ListUsers() ([]User, error) {
	rows, err := s.db.Query("SELECT id,username,enabled,created_at FROM users ORDER BY username")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	users := []User{}
	for rows.Next() {
		var u User
		var enabled int
		var created int64
		if err = rows.Scan(&u.ID, &u.Username, &enabled, &created); err != nil {
			return nil, err
		}
		u.Disabled = enabled == 0
		u.CreatedAt = timestamp(time.Unix(created, 0))
		users = append(users, u)
	}
	return users, rows.Err()
}
func initializeDummy() error {
	dummyOnce.Do(func() {
		dummyHash, dummyErr = bcrypt.GenerateFromPassword([]byte("dummy-password-not-a-valid-user-secret"), passwordCost)
	})
	return dummyErr
}
func (s *Store) verify(username, password string) (credential, error) {
	var c credential
	var enabled int
	err := s.db.QueryRow("SELECT id,username,password_hash,auth_version,enabled FROM users WHERE username=?", username).Scan(&c.user.ID, &c.user.Username, &c.hash, &c.version, &enabled)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return c, err
	}
	hash := c.hash
	if errors.Is(err, sql.ErrNoRows) {
		hash = dummyHash
	}
	matched := bcrypt.CompareHashAndPassword(hash, []byte(password)) == nil
	if !matched || err != nil || enabled != 1 {
		return credential{}, ErrUnauthorized
	}
	c.enabled = true
	return c, nil
}
func (s *Store) createSession(c credential, device string, now time.Time, ttl time.Duration) (string, Session, error) {
	token, err := randomHex(32)
	if err != nil {
		return "", Session{}, err
	}
	id, err := randomHex(16)
	if err != nil {
		return "", Session{}, err
	}
	tx, err := s.db.Begin()
	if err != nil {
		return "", Session{}, err
	}
	defer tx.Rollback()
	var enabled int
	var version int64
	if err = tx.QueryRow("SELECT enabled,auth_version FROM users WHERE id=?", c.user.ID).Scan(&enabled, &version); err != nil {
		return "", Session{}, err
	}
	if enabled != 1 || version != c.version {
		return "", Session{}, ErrUnauthorized
	}
	if _, err = tx.Exec("DELETE FROM sessions WHERE expires_at<=?", now.Unix()); err != nil {
		return "", Session{}, err
	}
	if _, err = tx.Exec("DELETE FROM sessions WHERE user_id=? AND id NOT IN (SELECT id FROM sessions WHERE user_id=? ORDER BY created_at DESC,rowid DESC LIMIT ?)", c.user.ID, c.user.ID, maxSessions-1); err != nil {
		return "", Session{}, err
	}
	expires := now.Add(ttl)
	if _, err = tx.Exec("INSERT INTO sessions(id,user_id,token_hash,device_name,created_at,expires_at) VALUES(?,?,?,?,?,?)", id, c.user.ID, tokenHash(token), device, now.Unix(), expires.Unix()); err != nil {
		return "", Session{}, err
	}
	if err = tx.Commit(); err != nil {
		return "", Session{}, err
	}
	return token, Session{ID: id, DeviceName: device, CreatedAt: timestamp(now), ExpiresAt: timestamp(expires), Current: true}, nil
}
func (s *Store) authenticate(token string, now time.Time) (identity, error) {
	var i identity
	err := s.db.QueryRow(`SELECT users.id,users.username,sessions.id FROM sessions JOIN users ON users.id=sessions.user_id WHERE sessions.token_hash=? AND sessions.expires_at>? AND users.enabled=1`, tokenHash(token), now.Unix()).Scan(&i.User.ID, &i.User.Username, &i.SessionID)
	if errors.Is(err, sql.ErrNoRows) {
		return i, ErrUnauthorized
	}
	return i, err
}
func (s *Store) listSessions(i identity, now time.Time) ([]Session, error) {
	rows, err := s.db.Query(`SELECT sessions.id,device_name,sessions.created_at,expires_at FROM sessions JOIN users ON users.id=sessions.user_id WHERE user_id=? AND users.enabled=1 AND expires_at>? ORDER BY sessions.created_at DESC,sessions.rowid DESC`, i.User.ID, now.Unix())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	sessions := []Session{}
	for rows.Next() {
		var ss Session
		var created, expires int64
		if err = rows.Scan(&ss.ID, &ss.DeviceName, &created, &expires); err != nil {
			return nil, err
		}
		ss.CreatedAt = timestamp(time.Unix(created, 0))
		ss.ExpiresAt = timestamp(time.Unix(expires, 0))
		ss.Current = ss.ID == i.SessionID
		sessions = append(sessions, ss)
	}
	return sessions, rows.Err()
}
func (s *Store) revokeSession(i identity, id string) error {
	result, err := s.db.Exec(`DELETE FROM sessions WHERE id=? AND user_id=? AND EXISTS(SELECT 1 FROM users WHERE id=? AND enabled=1)`, id, i.User.ID, i.User.ID)
	if err != nil {
		return err
	}
	n, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return ErrSessionNotFound
	}
	return nil
}
func (s *Store) revokeAll(i identity) error {
	_, err := s.db.Exec(`DELETE FROM sessions WHERE user_id=? AND EXISTS(SELECT 1 FROM users WHERE id=? AND enabled=1)`, i.User.ID, i.User.ID)
	return err
}
