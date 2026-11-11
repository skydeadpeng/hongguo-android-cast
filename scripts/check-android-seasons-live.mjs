#!/usr/bin/env node
/** Opt-in, bounded public website season check: one search page plus two detail pages. */
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ownRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let root = ownRoot, output, resumeSearch;
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === '--root' && process.argv[i + 1]) root = path.resolve(process.argv[++i]);
  else if (process.argv[i] === '--output' && process.argv[i + 1]) output = path.resolve(process.argv[++i]);
  else if (process.argv[i] === '--resume-search-evidence' && process.argv[i + 1]) resumeSearch = path.resolve(process.argv[++i]);
  else throw Error('Usage: check-android-seasons-live.mjs [--root PROJECT] [--output DIRECTORY] [--resume-search-evidence JSON]');
}
output ??= path.join(root, 'verification/android-seasons-0121-20261021/live');
const quote = value => `'${String(value).replaceAll("'", "'\\''")}'`;
const command = [process.execPath, fileURLToPath(import.meta.url), '--root', root, '--output', output,
  ...(resumeSearch ? ['--resume-search-evidence', resumeSearch] : [])].map(quote).join(' ');
const started = new Date();
const evidence = {
  command, cwd: process.cwd(), root,
  startedAtUTC: started.toISOString(),
  startedAtAsiaShanghai: started.toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai', hour12: false }) + ' Asia/Shanghai',
  mode: 'live-public-website-search-and-details',
  coverage: 'Production createSeasonLookup and production createSource web search/detail parsers; metadata only.',
  notCovered: ['Android native SearchClient transport', 'media download or decode', 'phone/TV device UI', 'casting'],
  requests: [], checks: [],
};
const lines = [];
function log(line) { lines.push(line); console.log(line); }
function check(condition, description) { assert.ok(condition, description); evidence.checks.push(description); log('PASS ' + description); }
const sha = text => crypto.createHash('sha256').update(text).digest('hex');
const origin = 'https://hongguoduanju.com';
const firstId = '7671966197985856536', secondId = '7676706788724460568', variantId = '7692060436526533694';
const allowed = new Set([
  origin + '/search/' + encodeURIComponent('持械入宋'),
  origin + '/player/' + firstId,
  origin + '/player/' + secondId,
]);
const counts = new Map();
const plain = row => ({ seriesId: row.seriesId, title: row.title, episodeCount: row.episodeCount,
  ...(row.seasonNumber == null ? {} : { seasonNumber: row.seasonNumber }) });

try {
  const { createSource } = await import(pathToFileURL(path.join(root, 'src/android/source.mjs')).href);
  const { createSeasonLookup, parseSeasonTitle, seasonCandidates } = await import(pathToFileURL(path.join(root, 'src/android/seasons.mjs')).href);
  let savedSearch;
  if (resumeSearch) {
    const saved = JSON.parse(fs.readFileSync(resumeSearch, 'utf8'));
    assert.equal(saved.requests.length, 1, 'resume evidence contains only the prior search request');
    assert.equal(saved.requests[0].url, origin + '/search/' + encodeURIComponent('持械入宋'));
    assert.equal(saved.requests[0].status, 200);
    assert.equal(saved.search.keyword, '持械入宋');
    assert.equal(saved.search.page, 1);
    assert.ok(Array.isArray(saved.search.items) && saved.search.items.length > 0);
    evidence.resumedSearch = { evidenceFile: resumeSearch, evidenceSha256: sha(fs.readFileSync(resumeSearch)),
      originalCommand: saved.command, originalStartedAtUTC: saved.startedAtUTC,
      note: 'Reuse the already production-parsed public search response; do not issue a second search request.' };
    evidence.requests.push(saved.requests[0]);
    counts.set(saved.requests[0].url, 1);
    savedSearch = { items: saved.search.items, total: saved.search.totalReported };
  }
  const source = createSource(async url => {
    assert.ok(allowed.has(url), 'only the three explicitly allowed public metadata pages may be requested');
    assert.ok(!counts.has(url), 'no retries or duplicate network requests in the live check');
    counts.set(url, 1);
    const reply = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(15000),
      headers: { accept: 'text/html', 'accept-language': 'zh-CN,zh;q=0.9' } });
    const body = await reply.text();
    evidence.requests.push({ method: 'GET', url, status: reply.status,
      utf8Bytes: Buffer.byteLength(body), responseSha256: sha(body), receivedAtUTC: new Date().toISOString() });
    assert.equal(reply.status, 200, 'public metadata page must respond HTTP 200');
    assert.ok(body.length > 0 && body.length <= 8 * 1024 * 1024, 'bounded nonempty metadata HTML');
    return body;
  });
  const lookup = createSeasonLookup(async (keyword, page) => {
    assert.equal(keyword, '持械入宋');
    assert.equal(page, 1);
    const result = savedSearch || await source.search(keyword, page);
    evidence.search = { keyword, page, totalReported: result.total, items: result.items.map(plain) };
    return result;
  });
  const result = await lookup.load({ seriesId: firstId, title: '持械入宋：第一季', episodeCount: 100 });
  evidence.match = { keyword: result.keyword, page: result.page, matching: result.matching,
    notice: result.notice, hasMore: result.hasMore, items: result.items.map(plain) };
  check(evidence.search.items.some(row => row.seriesId === firstId && row.title === '持械入宋：第一季'), 'live search independently contains the exact current-season identity');
  check(result.items.some(row => row.seriesId === secondId && row.seasonNumber === 2), 'live search discovers the independent second-season identity');
  check(result.matching === 'title' && /按标题匹配/.test(result.notice), 'season relationship remains explicitly title-matched, not asserted authoritative');
  const variant = evidence.search.items.find(row => row.seriesId === variantId);
  const variantInput = variant || { seriesId: variantId, title: '持械入宋第四季', episodeCount: 219 };
  evidence.variantRegression = {
    source: variant ? 'present in this live search response' : 'separate regression input from earlier public metadata observation; not present on this live first page',
    input: variantInput,
  };
  const withVariant = seasonCandidates({ seriesId: firstId, title: '持械入宋：第一季', episodeCount: 100 }, [...evidence.search.items, variantInput]);
  check(!withVariant.some(row => row.seriesId === variantId), 'separate punctuation regression excludes the 219-episode no-colon variant');
  check(!result.items.some(row => row.seriesId === variantId), 'live matched list contains no different-punctuation variant');
  check(result.items.every(row => parseSeasonTitle(row.title).base === parseSeasonTitle('持械入宋：第一季').base), 'every selected season has the exact normalized punctuated base title');
  check(result.items.every((row, index, rows) => index === 0 || rows[index - 1].seasonNumber <= row.seasonNumber), 'matched seasons are numerically ordered');
  const details = [];
  for (const id of [firstId, secondId]) {
    const detail = await source.detail(id);
    check(detail.seriesId === id, 'detail remains bound to independent series ' + id);
    check(detail.episodeCount === 100 && detail.episodes.length === 100, 'single-season queue has exactly 100 episodes for ' + id);
    check(detail.episodes.every((episode, index) => episode.index === index + 1), 'single-season episode numbering restarts at one for ' + id);
    check(new Set(detail.episodes.map(episode => episode.vid)).size === 100, 'single-season episode IDs remain unique for ' + id);
    details.push({ ...plain(detail), queueLength: detail.episodes.length, firstEpisodeId: detail.episodes[0].vid,
      lastEpisodeId: detail.episodes.at(-1).vid,
      episodes: detail.episodes.map(({ index, vid }) => ({ index, vid })) });
  }
  evidence.details = details;
  check(details[0].firstEpisodeId === '7671977925326949400' && details[1].firstEpisodeId === '7676708598331739161', 'first two seasons retain their independently verified first episode IDs');
  const firstIds = new Set(details[0].episodes.map(row => row.vid));
  check(!details[1].episodes.some(row => firstIds.has(row.vid)), 'first and second season queues are disjoint and never joined');
  check(counts.size === 3 && [...counts.values()].every(count => count === 1), 'exact request budget: one search and two details, no media requests');
  evidence.result = `LIVE_SEASONS PASS requests=${counts.size} matched=${result.items.length} queues=100/100 checks=${evidence.checks.length}`;
  evidence.exitStatus = 0;
  log(evidence.result);
} catch (error) {
  evidence.exitStatus = 1;
  evidence.result = 'LIVE_SEASONS FAIL ' + String(error?.message || error).slice(0, 500);
  log(evidence.result);
  process.exitCode = 1;
} finally {
  evidence.finishedAtUTC = new Date().toISOString();
  fs.mkdirSync(output, { recursive: true });
  const report = [
    'Public website multi-season live verification',
    'Command: ' + command,
    'Working directory: ' + evidence.cwd,
    'Started: ' + evidence.startedAtAsiaShanghai,
    'Input: 持械入宋：第一季; independent first/second-season metadata only.',
    'Coverage: ' + evidence.coverage,
    'Not covered: ' + evidence.notCovered.join('; '),
    'Evidence omits response bodies, reviews, visitor identifiers and media URLs; only public title/episode metadata and response hashes are retained.',
    '', ...lines, '', 'Exit status: ' + evidence.exitStatus,
    'Relationship evidence: explicit season suffix and exact base title; no authoritative season-group ID was available.',
    'The existing public-web parseSearch fallback only exposes its first page; this check makes no full-catalog or native-search claim.',
    '219-episode variant test provenance: ' + (evidence.variantRegression?.source || 'not reached'),
    ...(evidence.resumedSearch ? ['Reused search evidence: ' + evidence.resumedSearch.evidenceFile,
      'Original search command: ' + evidence.resumedSearch.originalCommand] : []),
    '', 'Requests:', ...evidence.requests.map(row => `${row.method} ${row.url} => HTTP ${row.status}; SHA256 ${row.responseSha256}`),
    '', 'Exact title-matched candidates:', ...(evidence.match?.items || []).map(row => JSON.stringify(row)),
  ].join('\n') + '\n';
  fs.writeFileSync(path.join(output, 'evidence.json'), JSON.stringify(evidence, null, 2) + '\n');
  fs.writeFileSync(path.join(output, 'REPORT.txt'), report);
  fs.writeFileSync(path.join(output, 'run.log'), lines.join('\n') + '\n');
  for (const file of ['evidence.json', 'REPORT.txt', 'run.log']) assert.ok(fs.readFileSync(path.join(output, file)).length > 0);
}
