/**
 * Watch-history view logic: the completed verdict, the watching/completed filter and
 * the same-series hints, ported from the desktop's watch-history work. Pure functions:
 * rows are the projected history objects, never mutated.
 */
import { parseSeasonTitle } from './seasons.mjs';

/** A row counts as completed only on the last episode, near the end of its duration. */
export function completedEpisode(row) {
  if (!row || typeof row !== 'object') return false;
  const index = Number(row.index), count = Number(row.episodeCount);
  const duration = Number(row.durationMs), position = Number(row.positionMs);
  if (!Number.isFinite(index) || !Number.isFinite(count) || count < 1 || index < count) return false;
  if (!Number.isFinite(duration) || duration < 60_000 || !Number.isFinite(position)) return false;
  return position >= duration - 30_000;
}

/** 'all' keeps the provider order; 'watching' and 'completed' keep it inside their group. */
export function filterHistory(rows, mode) {
  if (!Array.isArray(rows) || !['all', 'watching', 'completed'].includes(mode)) throw new Error('观看记录筛选无效');
  if (mode === 'all') return rows;
  return rows.filter(row => completedEpisode(row) === (mode === 'completed'));
}

function editDistanceWithin(left, right, cap) {
  if (Math.abs(left.length - right.length) > cap) return false;
  let previous = Array.from({ length: right.length + 1 }, (_, at) => at);
  for (let at = 1; at <= left.length; at++) {
    const current = [at];
    let best = at;
    for (let other = 1; other <= right.length; other++) {
      const cost = left[at - 1] === right[other - 1] ? 0 : 1;
      current[other] = Math.min(previous[other] + 1, current[other - 1] + 1, previous[other - 1] + cost);
      best = Math.min(best, current[other]);
    }
    if (best > cap) return false;
    previous = current;
  }
  return previous[right.length] <= cap;
}

/**
 * Same-series hints: an explicit lower season of a family that also holds a higher one,
 * plus titles whose base is a near match (≤2 edits and ≤20% of the longer base). Hints
 * only inform the user; they never hide rows or change grouping. At most five.
 */
export function historyHints(rows, { limit = 5 } = {}) {
  if (!Array.isArray(rows) || !Number.isSafeInteger(limit) || limit < 0) throw new Error('观看记录提示参数无效');
  const families = new Map();
  for (const row of rows) {
    if (!row || typeof row !== 'object' || !row.title) continue;
    const parsed = parseSeasonTitle(row.title);
    let family = parsed.base, number = parsed.explicit && parsed.number ? parsed.number : 0, label = parsed.explicit ? parsed.label : '';
    // A bare trailing season number ("…人生3") counts like "…人生 第3季"; a base that
    // itself ends in a digit, and years, never split.
    if (!parsed.explicit) {
      const bare = /^(\D{2,60}?)(\d{1,2})$/.exec(family);
      if (bare && !/\d$/.test(bare[1]) && !/(?:19|20)\d{2}/.test(family)) {
        family = bare[1].trim();
        number = Number.parseInt(bare[2], 10);
        label = `第 ${number} 季`;
      }
    }
    if (!family) continue;
    const entry = families.get(family) || { family, latest: 0, latestLabel: '', rows: [] };
    if (number > entry.latest) { entry.latest = number; entry.latestLabel = label; }
    entry.rows.push({ row, number });
    families.set(family, entry);
  }
  const bases = [...families.values()];
  const hints = [];
  for (const entry of bases) {
    for (const member of entry.rows) {
      // The un-numbered row of a family that also holds a higher season is the classic case.
      if (entry.latest > member.number) {
        hints.push({ seriesId: member.row.seriesId, text: `这部剧可能还有${entry.latestLabel}，可在搜索页查看` });
      }
    }
  }
  for (let left = 0; left < bases.length && hints.length < limit; left++) {
    for (let right = left + 1; right < bases.length && hints.length < limit; right++) {
      const first = bases[left], second = bases[right];
      if (first.latest === second.latest) continue;
      const older = first.latest < second.latest ? first : second, newer = first.latest < second.latest ? second : first;
      if (newer.latest < 1) continue;
      // At most two edits and never a fifth of the longer base, but always at least one.
      const cap = Math.min(2, Math.max(1, Math.floor(Math.max(first.family.length, second.family.length) * 0.2)));
      if (first.family === second.family) continue;
      if (!editDistanceWithin(first.family, second.family, cap)) continue;
      for (const member of older.rows) {
        if (member.number === older.latest && !hints.some(hint => hint.seriesId === member.row.seriesId)) {
          hints.push({ seriesId: member.row.seriesId, text: `可能与「${newer.rows[0]?.row.title || newer.family}」是同一部剧，新版为${newer.latestLabel || '更新的季'}` });
        }
      }
    }
  }
  return hints.slice(0, limit);
}

/** Attaches non-mutating hint captions for the history posters. */
export function withHistoryHints(rows) {
  const hints = historyHints(rows);
  const byId = new Map(hints.map(hint => [hint.seriesId, hint.text]));
  return { hints, decorate: row => byId.has(row.seriesId) ? { ...row, hint: byId.get(row.seriesId) } : row };
}
