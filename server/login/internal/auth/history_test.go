package auth

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func testHistoryRecord(id string, position int64) historyInputRecord {
	return historyInputRecord{SeriesID: id, Title: "持械入宋", CoverURL: "https://example.com/cover.jpg", Description: "测试简介", EpisodeCount: 68, EpisodeLabel: "共68集", Tags: []string{"短剧"}, VID: "888888888", Index: 8, PositionMS: position}
}
func testMutation(id string, seq, base int64, record historyInputRecord) historyMutation {
	return historyMutation{MutationID: id, Sequence: seq, BaseRevision: base, Record: record}
}
func historyHTTP(t *testing.T, url, token, device string, mutations ...historyMutation) ([]historyResult, apiResult) {
	t.Helper()
	got := apiRequest(t, url, http.MethodPost, "/v1/history/sync", token, historySyncInput{DeviceID: device, Mutations: mutations})
	var result struct {
		Results []historyResult `json:"results"`
	}
	_ = json.Unmarshal(got.body, &result)
	return result.Results, got
}
func historyGetHTTP(t *testing.T, url, token, query string) historyPage {
	t.Helper()
	got := apiRequest(t, url, http.MethodGet, "/v1/history"+query, token, nil)
	expectStatus(t, got, 200)
	var page historyPage
	if err := json.Unmarshal(got.body, &page); err != nil {
		t.Fatal(err)
	}
	return page
}
func TestHistoryTwoDevicesCASIdempotencyAndRewatch(t *testing.T) {
	service, web := newTestServer(t, nil)
	createTestUser(t, service, "alice")
	phone := tokenOf(t, login(t, web.URL, "alice", "phone"))
	tv := tokenOf(t, login(t, web.URL, "alice", "TV"))
	record := testHistoryRecord("123456", 50000)
	first := testMutation("mutation-0001", 1, 0, record)
	a, got := historyHTTP(t, web.URL, phone, "device-phone", first)
	expectStatus(t, got, 200)
	if len(a) != 1 || a[0].Status != "applied" || a[0].Record.Revision < 1 || a[0].Record.UpdatedAt <= 0 {
		t.Fatalf("first %#v", a)
	}
	page := historyGetHTTP(t, web.URL, tv, "?cursor=0&limit=100")
	if len(page.Records) != 1 || page.Records[0].PositionMS != 50000 || page.Cursor != a[0].Record.Revision || page.HasMore {
		t.Fatalf("page %#v", page)
	}
	record.Index = 9
	record.VID = "99999999"
	record.PositionMS = 10000
	b, got := historyHTTP(t, web.URL, tv, "device-television", testMutation("mutation-0002", 1, a[0].Record.Revision, record))
	expectStatus(t, got, 200)
	if b[0].Status != "applied" || b[0].Record.Revision <= a[0].Record.Revision {
		t.Fatal(b)
	}
	stale := first
	stale.MutationID = "mutation-0003"
	stale.Sequence = 2
	stale.BaseRevision = a[0].Record.Revision
	stale.Record.PositionMS = 60000
	conflict, got := historyHTTP(t, web.URL, phone, "device-phone", stale)
	expectStatus(t, got, 200)
	if conflict[0].Status != "conflict" || conflict[0].Record.Index != 9 || conflict[0].Record.Revision != b[0].Record.Revision {
		t.Fatal(conflict)
	}
	replay, got := historyHTTP(t, web.URL, phone, "device-phone", first)
	expectStatus(t, got, 200)
	left, _ := json.Marshal(replay)
	right, _ := json.Marshal(a)
	if !bytes.Equal(left, right) {
		t.Fatalf("retry changed response %s !=%s", left, right)
	}
	changed := first
	changed.Record.PositionMS++
	_, got = historyHTTP(t, web.URL, phone, "device-phone", changed)
	expectStatus(t, got, 409)
	if !bytes.Contains(got.body, []byte("MUTATION_REUSED")) {
		t.Fatal(string(got.body))
	}
	record.Index = 1
	record.VID = "111111"
	record.PositionMS = 0
	rewatch, got := historyHTTP(t, web.URL, tv, "device-television", testMutation("mutation-0004", 2, b[0].Record.Revision, record))
	expectStatus(t, got, 200)
	if rewatch[0].Status != "applied" || rewatch[0].Record.PositionMS != 0 || rewatch[0].Record.Index != 1 {
		t.Fatal(rewatch)
	}
	if got := historyGetHTTP(t, web.URL, phone, fmt.Sprintf("?cursor=%d", page.Cursor)); len(got.Records) != 1 || got.Records[0].Index != 1 {
		t.Fatal(got)
	}
}

// Fast store fixture bypasses password work only in tests; all API tests use the
// real login endpoint. The rows still have actual session and user relationships.
func historyStoreFixture(t *testing.T) (*Store, identity, time.Time) {
	t.Helper()
	store := openTestStore(t)
	now := time.Unix(1800000000, 0)
	_, err := store.db.Exec(`INSERT INTO users(id,username,password_hash,created_at) VALUES('fixture-user','fixture',X'00',?)`, now.Unix())
	if err != nil {
		t.Fatal(err)
	}
	_, err = store.db.Exec(`INSERT INTO sessions(id,user_id,token_hash,device_name,created_at,expires_at) VALUES('fixture-session','fixture-user','fixture-hash','fixture',?,?)`, now.Unix(), now.Add(time.Hour).Unix())
	if err != nil {
		t.Fatal(err)
	}
	return store, identity{User: User{ID: "fixture-user", Username: "fixture"}, SessionID: "fixture-session"}, now
}
func storeHistory(t *testing.T, s *Store, i identity, now time.Time, device string, mutations ...historyMutation) []historyResult {
	t.Helper()
	result, err := s.syncHistory(context.Background(), i, historySyncInput{DeviceID: device, Mutations: mutations}, now)
	if err != nil {
		t.Fatal(err)
	}
	return result
}
func TestHistoryReceiptWindowWatermarkAndDeviceCapacity(t *testing.T) {
	s, i, now := historyStoreFixture(t)
	var revision int64
	initial := testMutation("mutation-0001", 1, 0, testHistoryRecord("123456", 1))
	for sequence := int64(1); sequence <= 260; sequence++ {
		r := storeHistory(t, s, i, now, "device-phone", testMutation(fmt.Sprintf("mutation-%04d", sequence), sequence, revision, testHistoryRecord("123456", sequence)))
		revision = r[0].Record.Revision
	}
	var count int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM history_receipts`).Scan(&count); err != nil || count != maxHistoryReceipts {
		t.Fatalf("receipts=%d error=%v", count, err)
	}
	replay := storeHistory(t, s, i, now, "device-phone", initial)
	if replay[0].Status != "obsolete" || replay[0].Record.PositionMS != 260 || replay[0].Record.Revision != revision {
		t.Fatal(replay)
	}
	mutation := testMutation("mutation-0260", 260, revision-1, testHistoryRecord("123456", 260))
	replay = storeHistory(t, s, i, now, "device-phone", mutation)
	if replay[0].Status != "applied" || replay[0].Record.Revision != revision {
		t.Fatal(replay)
	}
	mutation.Sequence = 261
	if _, err := s.syncHistory(context.Background(), i, historySyncInput{DeviceID: "device-phone", Mutations: []historyMutation{mutation}}, now); !errors.Is(err, errMutationReused) {
		t.Fatal(err)
	}
	mutation.MutationID = "different-mutation"
	mutation.Sequence = 260
	if _, err := s.syncHistory(context.Background(), i, historySyncInput{DeviceID: "device-phone", Mutations: []historyMutation{mutation}}, now); !errors.Is(err, errMutationReused) {
		t.Fatal(err)
	}
	for n := 1; n < maxHistoryDevices; n++ {
		storeHistory(t, s, i, now, fmt.Sprintf("device-%04d", n), testMutation("mutation-new", 1, 0, testHistoryRecord("123456", 5)))
	}
	if _, err := s.syncHistory(context.Background(), i, historySyncInput{DeviceID: "device-over-limit", Mutations: []historyMutation{testMutation("mutation-new", 1, 0, testHistoryRecord("123456", 6))}}, now); !errors.Is(err, errDeviceCapacity) {
		t.Fatal(err)
	}
}
func TestHistoryPaginationConcurrentUpdateAndIsolation(t *testing.T) {
	s, i, now := historyStoreFixture(t)
	for n := 1; n <= 5; n++ {
		storeHistory(t, s, i, now, "device-phone", testMutation(fmt.Sprintf("mutation-%04d", n), int64(n), 0, testHistoryRecord(fmt.Sprintf("12345%d", n), int64(n))))
	}
	page, err := s.historyPage(context.Background(), i, 0, 2, now)
	if err != nil || len(page.Records) != 2 || !page.HasMore {
		t.Fatalf("%#v %v", page, err)
	}
	beforeCursor := page.Cursor
	first := page.Records[0]
	changed := storeHistory(t, s, i, now, "device-phone", testMutation("mutation-0006", 6, first.Revision, testHistoryRecord(first.SeriesID, 600)))[0]
	seen := map[string]historyRecord{}
	for _, r := range page.Records {
		seen[r.SeriesID] = r
	}
	for page.HasMore {
		page, err = s.historyPage(context.Background(), i, page.Cursor, 2, now)
		if err != nil {
			t.Fatal(err)
		}
		for _, r := range page.Records {
			seen[r.SeriesID] = r
		}
	}
	if len(seen) != 5 || seen[first.SeriesID].PositionMS != 600 || page.Cursor != changed.Record.Revision || page.Cursor <= beforeCursor {
		t.Fatalf("%#v %#v", seen, page)
	}
	empty, err := s.historyPage(context.Background(), i, page.Cursor, 2, now)
	if err != nil || len(empty.Records) != 0 || empty.Cursor != page.Cursor || empty.HasMore {
		t.Fatalf("%#v %v", empty, err)
	}
	_, err = s.db.Exec(`INSERT INTO users(id,username,password_hash,created_at) VALUES('other','other',X'00',0); INSERT INTO sessions(id,user_id,token_hash,device_name,created_at,expires_at) VALUES('other-session','other','other-hash','other',0,?)`, now.Add(time.Hour).Unix())
	if err != nil {
		t.Fatal(err)
	}
	other := identity{User: User{ID: "other"}, SessionID: "other-session"}
	empty, err = s.historyPage(context.Background(), other, 0, 100, now)
	if err != nil || len(empty.Records) != 0 {
		t.Fatalf("cross-user read: %#v %v", empty, err)
	}
	otherResult := storeHistory(t, s, other, now, "device-phone", testMutation("mutation-0001", 1, 0, testHistoryRecord(first.SeriesID, 10000)))
	if otherResult[0].Status != "applied" {
		t.Fatal(otherResult)
	}
	own, err := s.historyPage(context.Background(), i, 0, 100, now)
	if err != nil || len(own.Records) != 5 {
		t.Fatal(err)
	}
	for _, r := range own.Records {
		if r.SeriesID == first.SeriesID && r.PositionMS != 600 {
			t.Fatal("cross-user overwrite")
		}
	}
}
func TestHistoryAtomicBatchAndConcurrentCAS(t *testing.T) {
	s, i, now := historyStoreFixture(t)
	ctx := context.Background()
	a := testMutation("mutation-0001", 1, 0, testHistoryRecord("123456", 1))
	b := testMutation("mutation-0002", 2, 100, testHistoryRecord("123457", 2))
	if _, err := s.syncHistory(ctx, i, historySyncInput{DeviceID: "device-phone", Mutations: []historyMutation{a, b}}, now); !errors.Is(err, ErrInvalidInput) {
		t.Fatal(err)
	}
	page, err := s.historyPage(ctx, i, 0, 100, now)
	if err != nil || len(page.Records) != 0 {
		t.Fatal(page, err)
	}
	results := make(chan string, 2)
	errs := make(chan error, 2)
	var wg sync.WaitGroup
	for _, device := range []string{"device-phone", "device-television"} {
		wg.Add(1)
		go func(device string) {
			defer wg.Done()
			r, e := s.syncHistory(ctx, i, historySyncInput{DeviceID: device, Mutations: []historyMutation{a}}, now)
			if e != nil {
				errs <- e
			} else {
				results <- r[0].Status
			}
		}(device)
	}
	wg.Wait()
	close(results)
	close(errs)
	for err := range errs {
		t.Error(err)
	}
	statuses := map[string]int{}
	for v := range results {
		statuses[v]++
	}
	if statuses["applied"] != 1 || statuses["conflict"] != 1 {
		t.Fatal(statuses)
	}
}
func TestHistoryAuthIsolationDisabledAndStrictHTTPInputs(t *testing.T) {
	service, web := newTestServer(t, nil)
	createTestUser(t, service, "alice")
	token := tokenOf(t, login(t, web.URL, "alice", "phone"))
	mutation := testMutation("mutation-0001", 1, 0, testHistoryRecord("123456", 1))
	payload := historySyncInput{DeviceID: "device-phone", Mutations: []historyMutation{mutation}}
	expectStatus(t, apiRequest(t, web.URL, http.MethodPost, "/v1/history/sync", "", payload), 401)
	expectStatus(t, apiRequest(t, web.URL, http.MethodGet, "/v1/history", "", nil), 401)
	for _, query := range []string{"?cursor=-1", "?cursor=1.2", "?cursor=01", "?cursor=", "?limit=0", "?limit=101", "?limit=999999999999999999999", "?cursor=9007199254740992", "?cursor=1&cursor=2", "?userId=other", "?cursor=%FF", "?cursor=1%3Blimit=1"} {
		expectStatus(t, apiRequest(t, web.URL, http.MethodGet, "/v1/history"+query, token, nil), 400)
	}
	raw, _ := json.Marshal(payload)
	injected := bytes.Replace(raw, []byte(`"deviceId":`), []byte(`"userId":"other","deviceId":`), 1)
	for _, body := range [][]byte{[]byte("null"), []byte("{}"), []byte("[]"), append(append([]byte{}, raw...), []byte(` {}`)...), injected, bytes.Replace(raw, []byte(`"index":8`), []byte(`"index":8.5`), 1), bytes.Replace(raw, []byte(`"positionMs":1`), []byte(`"positionMs":null`), 1)} {
		req, _ := http.NewRequest(http.MethodPost, web.URL+"/v1/history/sync", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+token)
		response, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != 400 {
			t.Fatalf("status=%d body=%s", response.StatusCode, body)
		}
	}
	req, _ := http.NewRequest(http.MethodPost, web.URL+"/v1/history/sync", strings.NewReader(`{"deviceId":"`+strings.Repeat("x", maxHistoryBody)+`"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	response, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 413 {
		t.Fatalf("large body=%d", response.StatusCode)
	}
	req, _ = http.NewRequest(http.MethodPost, web.URL+"/v1/history/sync", bytes.NewReader(raw))
	req.Header.Set("Authorization", "Bearer "+token)
	response, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != 415 {
		t.Fatal(response.StatusCode)
	}
	if err := service.Store().DisableUser("alice"); err != nil {
		t.Fatal(err)
	}
	expectStatus(t, apiRequest(t, web.URL, http.MethodPost, "/v1/history/sync", token, payload), 401)
	expectStatus(t, apiRequest(t, web.URL, http.MethodGet, "/v1/history", token, nil), 401)
}
func TestHistoryValidationBounds(t *testing.T) {
	baseline := historySyncInput{DeviceID: "device-phone", Mutations: []historyMutation{testMutation("mutation-0001", 1, 0, testHistoryRecord("123456", 0))}}
	if err := validateHistoryInput(baseline); err != nil {
		t.Fatal(err)
	}
	cases := []func(*historySyncInput){
		func(x *historySyncInput) { x.DeviceID = "a" }, func(x *historySyncInput) { x.DeviceID = strings.Repeat("x", 81) }, func(x *historySyncInput) { x.Mutations = nil }, func(x *historySyncInput) { x.Mutations = make([]historyMutation, 21) },
		func(x *historySyncInput) { x.Mutations[0].MutationID = "a" }, func(x *historySyncInput) { x.Mutations = append(x.Mutations, x.Mutations[0]) }, func(x *historySyncInput) { x.Mutations[0].Sequence = 0 }, func(x *historySyncInput) { x.Mutations[0].Sequence = maxHistoryInteger + 1 }, func(x *historySyncInput) { x.Mutations[0].BaseRevision = -1 }, func(x *historySyncInput) { x.Mutations[0].BaseRevision = maxHistoryInteger + 1 },
		func(x *historySyncInput) { x.Mutations[0].Record.SeriesID = "12345" }, func(x *historySyncInput) { x.Mutations[0].Record.VID = "evil/path" }, func(x *historySyncInput) { x.Mutations[0].Record.Index = 0 }, func(x *historySyncInput) { x.Mutations[0].Record.Index = 10001 }, func(x *historySyncInput) { x.Mutations[0].Record.PositionMS = -1 }, func(x *historySyncInput) { x.Mutations[0].Record.PositionMS = 7*24*60*60*1000 + 1 }, func(x *historySyncInput) { x.Mutations[0].Record.EpisodeCount = -1 }, func(x *historySyncInput) { x.Mutations[0].Record.EpisodeCount = 10001 },
		func(x *historySyncInput) { x.Mutations[0].Record.Title = " " }, func(x *historySyncInput) { x.Mutations[0].Record.Title = strings.Repeat("剧", 201) }, func(x *historySyncInput) { x.Mutations[0].Record.Title = string([]byte{0xff}) }, func(x *historySyncInput) { x.Mutations[0].Record.CoverURL = strings.Repeat("x", 4097) }, func(x *historySyncInput) { x.Mutations[0].Record.Description = strings.Repeat("x", 8001) }, func(x *historySyncInput) { x.Mutations[0].Record.EpisodeLabel = strings.Repeat("x", 101) }, func(x *historySyncInput) { x.Mutations[0].Record.Tags = make([]string, 9) }, func(x *historySyncInput) { x.Mutations[0].Record.Tags = []string{strings.Repeat("x", 81)} },
	}
	for n, change := range cases {
		copy := baseline
		copy.Mutations = append([]historyMutation{}, baseline.Mutations...)
		change(&copy)
		if !errors.Is(validateHistoryInput(copy), ErrInvalidInput) {
			t.Fatalf("accepted case %d", n)
		}
	}
	if !historyTextValid(strings.Repeat("😀", 100), 200) || historyTextValid(strings.Repeat("😀", 101), 200) {
		t.Fatal("UTF16 bounds")
	}
}
func TestHistoryPageByteCapAndStorageCapacity(t *testing.T) {
	s, i, now := historyStoreFixture(t)
	ctx := context.Background()
	record := testHistoryRecord("123456", 0)
	record.Description = strings.Repeat("\x01", 8000)
	record.CoverURL = strings.Repeat("\x01", 4096)
	for n := 1; n <= 20; n++ {
		record.SeriesID = fmt.Sprintf("1234%02d", n)
		storeHistory(t, s, i, now, "device-phone", testMutation(fmt.Sprintf("mutation-%04d", n), int64(n), 0, record))
	}
	page, err := s.historyPage(ctx, i, 0, 100, now)
	data, _ := json.Marshal(page)
	if err != nil || !page.HasMore || len(page.Records) < 1 || len(data) > maxHistoryPageBytes {
		t.Fatalf("rows=%d bytes=%d hasMore=%v err=%v", len(page.Records), len(data), page.HasMore, err)
	}
	tx, err := s.db.Begin()
	if err != nil {
		t.Fatal(err)
	}
	for n := 21; n <= maxHistoryRows; n++ {
		r := historyRecord{historyInputRecord: testHistoryRecord(fmt.Sprintf("%06d", n), 1), Revision: int64(n), UpdatedAt: 1}
		data, _ := json.Marshal(r)
		if _, err = tx.Exec(`INSERT INTO watch_history(user_id,series_id,revision,record_json) VALUES(?,?,?,?)`, i.User.ID, r.SeriesID, r.Revision, string(data)); err != nil {
			t.Fatal(err)
		}
	}
	if _, err = tx.Exec(`UPDATE history_clock SET revision=?`, maxHistoryRows); err != nil {
		t.Fatal(err)
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	_, err = s.syncHistory(ctx, i, historySyncInput{DeviceID: "device-phone", Mutations: []historyMutation{testMutation("mutation-overflow", 21, 0, testHistoryRecord("987654", 1))}}, now)
	if !errors.Is(err, errHistoryCapacity) {
		t.Fatal(err)
	}
	// Existing rows remain writable at the limit.
	storeHistory(t, s, i, now, "device-phone", testMutation("mutation-existing", 21, 1, testHistoryRecord("123401", 2)))
}
func TestHistoryAdditiveMigrationAndPersistedReplayProtection(t *testing.T) {
	path := filepath.Join(t.TempDir(), "login.sqlite")
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`CREATE TABLE users(id TEXT PRIMARY KEY,username TEXT NOT NULL UNIQUE,password_hash BLOB NOT NULL,enabled INTEGER NOT NULL DEFAULT 1,auth_version INTEGER NOT NULL DEFAULT 0,created_at INTEGER NOT NULL); CREATE TABLE sessions(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),token_hash TEXT NOT NULL UNIQUE,device_name TEXT NOT NULL,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL); INSERT INTO users VALUES('existing-user','alice',X'616263',1,7,123); INSERT INTO sessions VALUES('existing-session','existing-user','existing-hash','phone',123,2000000000);`)
	if err != nil {
		t.Fatal(err)
	}
	db.Close()
	store, err := OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	var hash []byte
	var version int
	if err = store.db.QueryRow(`SELECT password_hash,auth_version FROM users WHERE id='existing-user'`).Scan(&hash, &version); err != nil || string(hash) != "abc" || version != 7 {
		t.Fatal(hash, version, err)
	}
	i := identity{User: User{ID: "existing-user"}, SessionID: "existing-session"}
	now := time.Unix(1800000000, 0)
	first := testMutation("mutation-0001", 1, 0, testHistoryRecord("123456", 100))
	before := storeHistory(t, store, i, now, "device-phone", first)
	store.Close()
	store, err = OpenStore(path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	after := storeHistory(t, store, i, now, "device-phone", first)
	a, _ := json.Marshal(after)
	b, _ := json.Marshal(before)
	if !bytes.Equal(a, b) {
		t.Fatal("restart changed replay")
	}
}
func TestHistorySessionRecheckAndErrors(t *testing.T) {
	s, i, now := historyStoreFixture(t)
	ctx := context.Background()
	input := historySyncInput{DeviceID: "device-phone", Mutations: []historyMutation{testMutation("mutation-0001", 1, 0, testHistoryRecord("123456", 0))}}
	for _, bad := range []identity{{User: User{ID: "other"}, SessionID: i.SessionID}, {User: i.User, SessionID: "wrong"}} {
		if _, err := s.syncHistory(ctx, bad, input, now); !errors.Is(err, ErrUnauthorized) {
			t.Fatal(err)
		}
		if _, err := s.historyPage(ctx, bad, 0, 100, now); !errors.Is(err, ErrUnauthorized) {
			t.Fatal(err)
		}
	}
	if _, err := s.syncHistory(ctx, i, input, now.Add(2*time.Hour)); !errors.Is(err, ErrUnauthorized) {
		t.Fatal(err)
	}
	if _, err := s.historyPage(ctx, i, -1, 100, now); !errors.Is(err, ErrInvalidInput) {
		t.Fatal(err)
	}
	if _, err := s.historyPage(ctx, i, 0, 101, now); !errors.Is(err, ErrInvalidInput) {
		t.Fatal(err)
	}
	if err := s.DisableUser("fixture"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.syncHistory(ctx, i, input, now); !errors.Is(err, ErrUnauthorized) {
		t.Fatal(err)
	}
	s.Close()
	if _, err := s.syncHistory(ctx, i, input, now); err == nil {
		t.Fatal("closed db accepted write")
	}
	if _, err := s.historyPage(ctx, i, 0, 100, now); err == nil {
		t.Fatal("closed db accepted read")
	}
}
func TestHistoryHTTPSRequired(t *testing.T) {
	server, err := NewServer(Config{DBPath: filepath.Join(t.TempDir(), "login.sqlite")})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	for _, method := range []string{http.MethodGet, http.MethodPost} {
		path := "/v1/history"
		if method == http.MethodPost {
			path += "/sync"
		}
		req := httptest.NewRequest(method, path, nil)
		recorder := httptest.NewRecorder()
		server.Handler().ServeHTTP(recorder, req)
		if recorder.Code != 403 {
			t.Fatal(recorder.Code)
		}
	}
}
