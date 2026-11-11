package auth

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/bcrypt"
)

func openTestStore(t *testing.T) *Store {
	t.Helper()
	store, err := OpenStore(filepath.Join(t.TempDir(), "auth.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = store.Close() })
	return store
}

func storeCredential(t *testing.T, store *Store, username string) credential {
	t.Helper()
	if _, err := store.CreateUser(username, testPassword); err != nil {
		t.Fatal(err)
	}
	c, err := store.verify(username, testPassword)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestCredentialAndTokenStorageAreHashed(t *testing.T) {
	path := filepath.Join(t.TempDir(), "private", "auth.sqlite")
	store, err := OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	c := storeCredential(t, store, "alice")
	token, session, err := store.createSession(c, "phone", time.Now(), time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	var passwordHash []byte
	var savedToken string
	if err = store.db.QueryRow("SELECT password_hash FROM users WHERE id=?", c.user.ID).Scan(&passwordHash); err != nil {
		t.Fatal(err)
	}
	if err = store.db.QueryRow("SELECT token_hash FROM sessions WHERE id=?", session.ID).Scan(&savedToken); err != nil {
		t.Fatal(err)
	}
	if string(passwordHash) == testPassword || bcrypt.CompareHashAndPassword(passwordHash, []byte(testPassword)) != nil {
		t.Fatal("password is not a valid bcrypt hash")
	}
	if cost, err := bcrypt.Cost(passwordHash); err != nil || cost < 12 {
		t.Fatalf("bcrypt cost=%d err=%v", cost, err)
	}
	if savedToken == token || savedToken != tokenHash(token) {
		t.Fatal("session stores a raw token or wrong digest")
	}
	if err = store.Close(); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(b, []byte(token)) || bytes.Contains(b, []byte(testPassword)) {
		t.Fatal("database exposes plaintext secrets")
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0600 {
		t.Fatalf("database permissions=%o", info.Mode().Perm())
	}
	info, err = os.Stat(filepath.Dir(path))
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0700 {
		t.Fatalf("new data directory permissions=%o", info.Mode().Perm())
	}
}

func TestStaleVerifiedCredentialsCannotCreateSessionAfterResetOrDisable(t *testing.T) {
	for _, operation := range []string{"reset", "disable"} {
		t.Run(operation, func(t *testing.T) {
			store := openTestStore(t)
			c := storeCredential(t, store, "alice")
			if operation == "reset" {
				if err := store.ResetPassword("alice", "replacement-password-123"); err != nil {
					t.Fatal(err)
				}
			} else {
				if err := store.DisableUser("alice"); err != nil {
					t.Fatal(err)
				}
			}
			_, _, err := store.createSession(c, "late-login", time.Now(), time.Hour)
			if !errors.Is(err, ErrUnauthorized) {
				t.Fatalf("stale login created session after %s: %v", operation, err)
			}
			var count int
			if err := store.db.QueryRow("SELECT COUNT(*) FROM sessions").Scan(&count); err != nil {
				t.Fatal(err)
			}
			if count != 0 {
				t.Fatalf("stale session persisted: %d", count)
			}
		})
	}
}

func TestSessionBoundedRetentionAndExpiryCleanup(t *testing.T) {
	store := openTestStore(t)
	c := storeCredential(t, store, "alice")
	now := time.Unix(1_800_000_000, 0)
	oldest, _, err := store.createSession(c, "expired", now.Add(-2*time.Hour), time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	first, _, err := store.createSession(c, "first", now, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	var newest string
	for i := 0; i < maxSessions; i++ {
		newest, _, err = store.createSession(c, "phone", now.Add(time.Duration(i+1)*time.Second), time.Hour)
		if err != nil {
			t.Fatal(err)
		}
	}
	for _, token := range []string{oldest, first} {
		if _, err := store.authenticate(token, now.Add(time.Minute)); !errors.Is(err, ErrUnauthorized) {
			t.Fatalf("expired/evicted session still authenticates: %v", err)
		}
	}
	identity, err := store.authenticate(newest, now.Add(time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	sessions, err := store.listSessions(identity, now.Add(time.Minute))
	if err != nil || len(sessions) != maxSessions {
		t.Fatalf("sessions=%d err=%v", len(sessions), err)
	}
	current := 0
	for _, session := range sessions {
		if session.Current {
			current++
		}
	}
	if current != 1 {
		t.Fatalf("current session count=%d", current)
	}
	sessions, err = store.listSessions(identity, now.Add(2*time.Hour))
	if err != nil || len(sessions) != 0 {
		t.Fatalf("expired sessions returned=%#v err=%v", sessions, err)
	}
}

func TestInputBoundariesAndTimestamp(t *testing.T) {
	for _, input := range []string{strings.Repeat("x", 12), strings.Repeat("x", 72), strings.Repeat("中", 24)} {
		if err := validatePassword(input); err != nil {
			t.Errorf("rejected valid password: bytes=%d error=%v", len(input), err)
		}
	}
	for _, input := range []string{strings.Repeat("x", 11), strings.Repeat("x", 73), strings.Repeat("中", 25), strings.Repeat("中", 11), string([]byte{0xff, 0xff}) + strings.Repeat("x", 12)} {
		if err := validatePassword(input); !errors.Is(err, ErrInvalidInput) {
			t.Errorf("accepted invalid password: bytes=%d error=%v", len(input), err)
		}
	}
	for _, input := range []string{"abc", strings.Repeat("a", 32), "a_b.c-d", "  ALICE  "} {
		if _, err := normalizeUsername(input); err != nil {
			t.Errorf("rejected valid username %q: %v", input, err)
		}
	}
	stamp := timestamp(time.Date(2026, 10, 23, 8, 0, 0, 0, time.FixedZone("CST", 8*3600)))
	if stamp != "2026-10-23T00:00:00Z" {
		t.Fatalf("timestamp=%q", stamp)
	}
}

func TestStoreRejectsNonPersistentAndNonRegularPaths(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "ordinary-file")
	if err := os.WriteFile(file, []byte("not sqlite"), 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "symlink.sqlite")
	if err := os.Symlink(file, link); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"", " ", ":memory:", dir, link, filepath.Join(file, "auth.sqlite"), file} {
		store, err := OpenStore(path)
		if err == nil {
			store.Close()
			t.Errorf("accepted unsupported path %q", path)
		}
	}
}

func TestStoreErrorsAfterCloseAndInvalidAdminUsernames(t *testing.T) {
	store := openTestStore(t)
	c := storeCredential(t, store, "alice")
	token, _, err := store.createSession(c, "phone", time.Now(), time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	identity, err := store.authenticate(token, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	if err := store.ResetPassword("ab", testPassword); !errors.Is(err, ErrInvalidInput) {
		t.Errorf("invalid reset username: %v", err)
	}
	if err := store.DisableUser("ab"); !errors.Is(err, ErrInvalidInput) {
		t.Errorf("invalid disable username: %v", err)
	}
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := store.ListUsers(); err == nil {
		t.Error("ListUsers succeeded after close")
	}
	if _, err := store.CreateUser("bob", testPassword); err == nil {
		t.Error("CreateUser succeeded after close")
	}
	if err := store.ResetPassword("alice", testPassword); err == nil {
		t.Error("ResetPassword succeeded after close")
	}
	if err := store.DisableUser("alice"); err == nil {
		t.Error("DisableUser succeeded after close")
	}
	if _, err := store.verify("alice", testPassword); err == nil {
		t.Error("verify succeeded after close")
	}
	if _, _, err := store.createSession(c, "phone", time.Now(), time.Hour); err == nil {
		t.Error("createSession succeeded after close")
	}
	if _, err := store.authenticate(token, time.Now()); err == nil {
		t.Error("authenticate succeeded after close")
	}
	if _, err := store.listSessions(identity, time.Now()); err == nil {
		t.Error("listSessions succeeded after close")
	}
	if err := store.revokeSession(identity, identity.SessionID); err == nil {
		t.Error("revokeSession succeeded after close")
	}
	if err := store.revokeAll(identity); err == nil {
		t.Error("revokeAll succeeded after close")
	}
}

func TestPasswordResetAndDisableAreAtomicWhenSessionDeletionFails(t *testing.T) {
	for _, operation := range []string{"reset", "disable"} {
		t.Run(operation, func(t *testing.T) {
			store := openTestStore(t)
			c := storeCredential(t, store, "alice")
			token, _, err := store.createSession(c, "phone", time.Now(), time.Hour)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := store.db.Exec(`CREATE TRIGGER reject_session_delete BEFORE DELETE ON sessions BEGIN SELECT RAISE(ABORT,'test deletion failure'); END`); err != nil {
				t.Fatal(err)
			}
			if operation == "reset" {
				err = store.ResetPassword("alice", "replacement-password-123")
			} else {
				err = store.DisableUser("alice")
			}
			if err == nil {
				t.Fatal("operation ignored required revocation failure")
			}
			if _, err := store.verify("alice", testPassword); err != nil {
				t.Fatalf("user update partially committed: %v", err)
			}
			if _, err := store.authenticate(token, time.Now()); err != nil {
				t.Fatalf("session state partially changed: %v", err)
			}
		})
	}
}
