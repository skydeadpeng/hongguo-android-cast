package auth

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
)

const (
	maxHistoryRows            = 1000
	maxHistoryReceipts        = 256
	maxHistoryDevices         = 64
	maxHistoryBatch           = 20
	maxHistoryBody            = 1024 * 1024
	maxHistoryPageBytes       = 1024 * 1024
	maxHistoryInteger   int64 = 9007199254740991 // exact in Android's JS bridge too
)

var (
	historySeriesPattern   = regexp.MustCompile(`^[0-9]{6,30}$`)
	historyVideoPattern    = regexp.MustCompile(`^[0-9]{1,30}$`)
	historyIdentityPattern = regexp.MustCompile(`^[A-Za-z0-9_.:-]{8,80}$`)
	historyNumberPattern   = regexp.MustCompile(`^(0|[1-9][0-9]*)$`)
	errMutationReused      = errors.New("mutation ID reused with a different payload")
	errHistoryCapacity     = errors.New("history capacity reached")
	errDeviceCapacity      = errors.New("sync device capacity reached")
)

// Only stable identifiers and display metadata cross this boundary. No video URL,
// download path, playback queue, bearer token or client clock is accepted.
type historyInputRecord struct {
	SeriesID     string   `json:"seriesId"`
	Title        string   `json:"title"`
	CoverURL     string   `json:"coverUrl"`
	Description  string   `json:"description"`
	EpisodeCount int      `json:"episodeCount"`
	EpisodeLabel string   `json:"episodeLabel"`
	Tags         []string `json:"tags"`
	VID          string   `json:"vid"`
	Index        int      `json:"index"`
	PositionMS   int64    `json:"positionMs"`
}
type historyRecord struct {
	historyInputRecord
	Revision  int64 `json:"revision"`
	UpdatedAt int64 `json:"updatedAt"`
}
type historyMutation struct {
	MutationID   string             `json:"mutationId"`
	BaseRevision int64              `json:"baseRevision"`
	Sequence     int64              `json:"sequence"`
	Record       historyInputRecord `json:"record"`
}
type historySyncInput struct {
	DeviceID  string            `json:"deviceId"`
	Mutations []historyMutation `json:"mutations"`
}
type historyResult struct {
	MutationID string        `json:"mutationId"`
	Status     string        `json:"status"`
	Record     historyRecord `json:"record"`
}
type historyPage struct {
	Records []historyRecord `json:"records"`
	Cursor  int64           `json:"cursor"`
	HasMore bool            `json:"hasMore"`
}

// Go's JSON decoder otherwise maps null scalar values to zero. Required
// identity/position/revision fields must actually be present and typed.
func decodeHistoryFields(data []byte, destination any, required ...string) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(destination); err != nil {
		return err
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(data, &fields); err != nil {
		return err
	}
	for _, name := range required {
		raw, ok := fields[name]
		if !ok || bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
			return ErrInvalidInput
		}
	}
	return nil
}
func (record *historyInputRecord) UnmarshalJSON(data []byte) error {
	type plain historyInputRecord
	return decodeHistoryFields(data, (*plain)(record), "seriesId", "title", "vid", "index", "positionMs")
}
func (mutation *historyMutation) UnmarshalJSON(data []byte) error {
	type plain historyMutation
	return decodeHistoryFields(data, (*plain)(mutation), "mutationId", "sequence", "baseRevision", "record")
}

func (record *historyRecord) UnmarshalJSON(data []byte) error {
	// Explicit fields avoid promoting the embedded input-only decoder, which
	// intentionally rejects server-owned revision/updatedAt on incoming writes.
	var wire struct {
		SeriesID     string   `json:"seriesId"`
		Title        string   `json:"title"`
		CoverURL     string   `json:"coverUrl"`
		Description  string   `json:"description"`
		EpisodeCount int      `json:"episodeCount"`
		EpisodeLabel string   `json:"episodeLabel"`
		Tags         []string `json:"tags"`
		VID          string   `json:"vid"`
		Index        int      `json:"index"`
		PositionMS   int64    `json:"positionMs"`
		Revision     int64    `json:"revision"`
		UpdatedAt    int64    `json:"updatedAt"`
	}
	if err := json.Unmarshal(data, &wire); err != nil {
		return err
	}
	record.historyInputRecord = historyInputRecord{SeriesID: wire.SeriesID, Title: wire.Title, CoverURL: wire.CoverURL, Description: wire.Description, EpisodeCount: wire.EpisodeCount, EpisodeLabel: wire.EpisodeLabel, Tags: wire.Tags, VID: wire.VID, Index: wire.Index, PositionMS: wire.PositionMS}
	record.Revision = wire.Revision
	record.UpdatedAt = wire.UpdatedAt
	return nil
}

// Additive migration from 0.1.0. Never rewrites users, sessions or their data.
const historySchema = `
 CREATE TABLE IF NOT EXISTS history_clock (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, updated_at INTEGER NOT NULL);
 INSERT OR IGNORE INTO history_clock(id,revision,updated_at) VALUES(1,0,0);
 CREATE TABLE IF NOT EXISTS watch_history (
 user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 series_id TEXT NOT NULL, revision INTEGER NOT NULL UNIQUE, record_json TEXT NOT NULL,
 PRIMARY KEY(user_id,series_id));
 CREATE INDEX IF NOT EXISTS history_user_revision ON watch_history(user_id,revision);
 CREATE TABLE IF NOT EXISTS history_devices (
 user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, device_id TEXT NOT NULL, last_sequence INTEGER NOT NULL,
 PRIMARY KEY(user_id,device_id));
 CREATE TABLE IF NOT EXISTS history_receipts (
 user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 device_id TEXT NOT NULL, mutation_id TEXT NOT NULL, sequence INTEGER NOT NULL, request_hash TEXT NOT NULL, result_json TEXT NOT NULL,
 PRIMARY KEY(user_id,device_id,mutation_id));
 CREATE UNIQUE INDEX IF NOT EXISTS receipts_sequence ON history_receipts(user_id,device_id,sequence);`

func historyTextValid(text string, maximum int) bool {
	if !utf8.ValidString(text) {
		return false
	}
	// LibraryStore bounds strings by UTF-16 code units, not encoded bytes.
	length := 0
	for _, r := range text {
		length++
		if r > 0xffff {
			length++
		}
	}
	return length <= maximum
}
func validateHistoryInput(input historySyncInput) error {
	if !historyIdentityPattern.MatchString(input.DeviceID) || len(input.Mutations) < 1 || len(input.Mutations) > maxHistoryBatch {
		return ErrInvalidInput
	}
	seen := make(map[string]bool, len(input.Mutations))
	var previousSequence int64
	for _, m := range input.Mutations {
		r := m.Record
		if !historyIdentityPattern.MatchString(m.MutationID) || seen[m.MutationID] || m.BaseRevision < 0 || m.BaseRevision > maxHistoryInteger || m.Sequence <= previousSequence || m.Sequence > maxHistoryInteger {
			return ErrInvalidInput
		}
		seen[m.MutationID] = true
		previousSequence = m.Sequence
		if !historySeriesPattern.MatchString(r.SeriesID) || !historyVideoPattern.MatchString(r.VID) || r.Index < 1 || r.Index > 10000 || r.PositionMS < 0 || r.PositionMS > 7*24*60*60*1000 || r.EpisodeCount < 0 || r.EpisodeCount > 10000 {
			return ErrInvalidInput
		}
		if strings.TrimSpace(r.Title) == "" || !historyTextValid(r.Title, 200) || !historyTextValid(r.CoverURL, 4096) || !historyTextValid(r.Description, 8000) || !historyTextValid(r.EpisodeLabel, 100) || len(r.Tags) > 8 {
			return ErrInvalidInput
		}
		for _, tag := range r.Tags {
			if !historyTextValid(tag, 80) {
				return ErrInvalidInput
			}
		}
	}
	return nil
}
func decodeHistory(c *gin.Context) (*historySyncInput, bool) {
	media, _, err := mime.ParseMediaType(c.GetHeader("Content-Type"))
	if err != nil || media != "application/json" {
		replyError(c, 415, "UNSUPPORTED_MEDIA_TYPE", "请使用 application/json")
		return nil, false
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxHistoryBody)
	decoder := json.NewDecoder(c.Request.Body)
	decoder.DisallowUnknownFields()
	var input *historySyncInput
	if err = decoder.Decode(&input); err != nil {
		bodyError(c, err)
		return nil, false
	}
	if input == nil {
		bodyError(c, ErrInvalidInput)
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
	if err = validateHistoryInput(*input); err != nil {
		bodyError(c, err)
		return nil, false
	}
	return input, true
}
func historyStoreError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, ErrInvalidInput):
		replyError(c, 400, "INVALID_INPUT", "请求格式有误")
	case errors.Is(err, errMutationReused):
		replyError(c, 409, "MUTATION_REUSED", "同步请求标识已用于其他内容")
	case errors.Is(err, errHistoryCapacity):
		replyError(c, 409, "HISTORY_CAPACITY", "此账号已达到 1000 部观看记录上限")
	case errors.Is(err, errDeviceCapacity):
		replyError(c, 409, "DEVICE_CAPACITY", "此账号已达到 64 台同步设备上限")
	default:
		handleStoreError(c, err)
	}
}
func (s *Server) syncHistory(c *gin.Context) {
	input, ok := decodeHistory(c)
	if !ok {
		return
	}
	results, err := s.store.syncHistory(c.Request.Context(), getIdentity(c), *input, s.config.Now())
	if err != nil {
		historyStoreError(c, err)
		return
	}
	c.JSON(200, gin.H{"results": results})
}
func (s *Server) getHistory(c *gin.Context) {
	query, err := url.ParseQuery(c.Request.URL.RawQuery)
	if err != nil {
		bodyError(c, ErrInvalidInput)
		return
	}
	for key, values := range query {
		if (key != "cursor" && key != "limit") || len(values) != 1 {
			bodyError(c, ErrInvalidInput)
			return
		}
	}
	cursor, limit := int64(0), int64(100)
	if values, ok := query["cursor"]; ok {
		if !historyNumberPattern.MatchString(values[0]) {
			bodyError(c, ErrInvalidInput)
			return
		}
		cursor, err = strconv.ParseInt(values[0], 10, 64)
		if err != nil || cursor > maxHistoryInteger {
			bodyError(c, ErrInvalidInput)
			return
		}
	}
	if values, ok := query["limit"]; ok {
		if !historyNumberPattern.MatchString(values[0]) {
			bodyError(c, ErrInvalidInput)
			return
		}
		limit, err = strconv.ParseInt(values[0], 10, 64)
		if err != nil || limit < 1 || limit > 100 {
			bodyError(c, ErrInvalidInput)
			return
		}
	}
	page, err := s.store.historyPage(c.Request.Context(), getIdentity(c), cursor, int(limit), s.config.Now())
	if err != nil {
		historyStoreError(c, err)
		return
	}
	c.JSON(200, page)
}

func requireHistoryIdentity(ctx context.Context, tx *sql.Tx, i identity, now time.Time) error {
	var present int
	err := tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM sessions JOIN users ON sessions.user_id=users.id WHERE sessions.id=? AND sessions.user_id=? AND sessions.expires_at>? AND users.enabled=1`, i.SessionID, i.User.ID, now.Unix()).Scan(&present)
	if err != nil {
		return err
	}
	if present != 1 {
		return ErrUnauthorized
	}
	return nil
}

// Every batch is atomic. A retry replays its stored original result; it never
// grants a stale mutation a new revision. Each durable device high-water mark
// prevents replay after its bounded (256-result) receipt window is pruned.
func (s *Store) syncHistory(ctx context.Context, i identity, input historySyncInput, now time.Time) ([]historyResult, error) {
	if err := validateHistoryInput(input); err != nil {
		return nil, err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	if err = requireHistoryIdentity(ctx, tx, i, now); err != nil {
		return nil, err
	}
	var lastSequence int64
	err = tx.QueryRowContext(ctx, `SELECT last_sequence FROM history_devices WHERE user_id=? AND device_id=?`, i.User.ID, input.DeviceID).Scan(&lastSequence)
	if errors.Is(err, sql.ErrNoRows) {
		var devices int
		if err = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM history_devices WHERE user_id=?`, i.User.ID).Scan(&devices); err != nil {
			return nil, err
		}
		if devices >= maxHistoryDevices {
			return nil, errDeviceCapacity
		}
		if _, err = tx.ExecContext(ctx, `INSERT INTO history_devices(user_id,device_id,last_sequence) VALUES(?,?,0)`, i.User.ID, input.DeviceID); err != nil {
			return nil, err
		}
	} else if err != nil {
		return nil, err
	}
	results := make([]historyResult, 0, len(input.Mutations))
	for _, mutation := range input.Mutations {
		// Canonical input hash treats nil tags and [] identically for harmless retries.
		if mutation.Record.Tags == nil {
			mutation.Record.Tags = []string{}
		}
		encoded, err := json.Marshal(mutation)
		if err != nil {
			return nil, err
		}
		digest := sha256.Sum256(encoded)
		fingerprint := hex.EncodeToString(digest[:])
		var savedHash, savedResult string
		err = tx.QueryRowContext(ctx, `SELECT request_hash,result_json FROM history_receipts WHERE user_id=? AND device_id=? AND (mutation_id=? OR sequence=?)`, i.User.ID, input.DeviceID, mutation.MutationID, mutation.Sequence).Scan(&savedHash, &savedResult)
		if err == nil {
			if savedHash != fingerprint {
				return nil, errMutationReused
			}
			var result historyResult
			if err = json.Unmarshal([]byte(savedResult), &result); err != nil {
				return nil, err
			}
			results = append(results, result)
			continue
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return nil, err
		}
		var previous historyRecord
		var previousJSON string
		err = tx.QueryRowContext(ctx, `SELECT record_json FROM watch_history WHERE user_id=? AND series_id=?`, i.User.ID, mutation.Record.SeriesID).Scan(&previousJSON)
		exists := err == nil
		if exists {
			if err = json.Unmarshal([]byte(previousJSON), &previous); err != nil {
				return nil, err
			}
		} else if !errors.Is(err, sql.ErrNoRows) {
			return nil, err
		}
		result := historyResult{MutationID: mutation.MutationID, Status: "conflict", Record: previous}
		if mutation.Sequence <= lastSequence {
			if !exists {
				return nil, ErrInvalidInput
			}
			result.Status = "obsolete"
			results = append(results, result)
			continue
		}
		if previous.Revision == mutation.BaseRevision {
			if !exists {
				var count int
				if err = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM watch_history WHERE user_id=?`, i.User.ID).Scan(&count); err != nil {
					return nil, err
				}
				if count >= maxHistoryRows {
					return nil, errHistoryCapacity
				}
			}
			var revision, updatedAt int64
			if err = tx.QueryRowContext(ctx, `UPDATE history_clock SET revision=revision+1,updated_at=MAX(updated_at+1,?) WHERE id=1 AND revision<? RETURNING revision,updated_at`, now.UnixMilli(), maxHistoryInteger).Scan(&revision, &updatedAt); err != nil {
				return nil, err
			}
			record := historyRecord{historyInputRecord: mutation.Record, Revision: revision, UpdatedAt: updatedAt}
			data, err := json.Marshal(record)
			if err != nil {
				return nil, err
			}
			if _, err = tx.ExecContext(ctx, `INSERT INTO watch_history(user_id,series_id,revision,record_json) VALUES(?,?,?,?) ON CONFLICT(user_id,series_id) DO UPDATE SET revision=excluded.revision,record_json=excluded.record_json`, i.User.ID, record.SeriesID, record.Revision, string(data)); err != nil {
				return nil, err
			}
			result.Status = "applied"
			result.Record = record
		} else if !exists {
			// A fabricated revision for a missing record is malformed, not a record to
			// import as an empty canonical history entry.
			return nil, ErrInvalidInput
		}
		data, err := json.Marshal(result)
		if err != nil {
			return nil, err
		}
		if _, err = tx.ExecContext(ctx, `INSERT INTO history_receipts(user_id,device_id,mutation_id,sequence,request_hash,result_json) VALUES(?,?,?,?,?,?)`, i.User.ID, input.DeviceID, mutation.MutationID, mutation.Sequence, fingerprint, string(data)); err != nil {
			return nil, err
		}
		lastSequence = mutation.Sequence
		if _, err = tx.ExecContext(ctx, `UPDATE history_devices SET last_sequence=? WHERE user_id=? AND device_id=?`, lastSequence, i.User.ID, input.DeviceID); err != nil {
			return nil, err
		}
		results = append(results, result)
	}
	if _, err = tx.ExecContext(ctx, `DELETE FROM history_receipts WHERE user_id=? AND device_id=? AND sequence NOT IN (SELECT sequence FROM history_receipts WHERE user_id=? AND device_id=? ORDER BY sequence DESC LIMIT ?)`, i.User.ID, input.DeviceID, i.User.ID, input.DeviceID, maxHistoryReceipts); err != nil {
		return nil, err
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return results, nil
}

// This is an incremental current-state feed, not an event log. An updated row
// moves to a newer revision, so it is seen on a later page or later poll; no
// acknowledgement cursor from a write may replace the reader's own cursor.
func (s *Store) historyPage(ctx context.Context, i identity, cursor int64, limit int, now time.Time) (historyPage, error) {
	page := historyPage{Records: []historyRecord{}, Cursor: cursor}
	if cursor < 0 || cursor > maxHistoryInteger || limit < 1 || limit > 100 {
		return page, ErrInvalidInput
	}
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return page, err
	}
	defer tx.Rollback()
	if err = requireHistoryIdentity(ctx, tx, i, now); err != nil {
		return page, err
	}
	rows, err := tx.QueryContext(ctx, `SELECT record_json FROM watch_history WHERE user_id=? AND revision>? ORDER BY revision LIMIT ?`, i.User.ID, cursor, limit+1)
	if err != nil {
		return page, err
	}
	defer rows.Close()
	size := 128
	for rows.Next() {
		var data string
		if err = rows.Scan(&data); err != nil {
			return page, err
		}
		if len(page.Records) >= limit || (len(page.Records) > 0 && size+len(data) > maxHistoryPageBytes) {
			page.HasMore = true
			break
		}
		var record historyRecord
		if err = json.Unmarshal([]byte(data), &record); err != nil {
			return page, err
		}
		page.Records = append(page.Records, record)
		page.Cursor = record.Revision
		size += len(data) + 1
	}
	if err = rows.Err(); err != nil {
		return page, err
	}
	if err = rows.Close(); err != nil {
		return page, err
	}
	if err = tx.Commit(); err != nil {
		return page, err
	}
	return page, nil
}
