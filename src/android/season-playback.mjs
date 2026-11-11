/** Per-season history and bounded native play requests. Season changes are manual. */
import { parseSeasonTitle } from './seasons.mjs';
const ID = /^\d{1,30}$/;
const SERIES_ID = /^\d{6,30}$/;
const MAX_PAYLOAD = 240 * 1024;
/** Preserve the native bridge request limit without preparing later seasons. */
export function assertPlaybackRequestSize(params) {
  if (new TextEncoder().encode(JSON.stringify(params)).length > MAX_PAYLOAD) {
    throw Object.assign(new Error('本次播放目录超过设备请求容量，请缩小播放范围后重试。'), { code: 'PLAY_REQUEST_TOO_LARGE' });
  }
}
export function sameSeasonFamily(a, b) {
  const x = parseSeasonTitle(a?.title), y = parseSeasonTitle(b?.title);
  return !!x?.explicit && !!y?.explicit && x.base === y.base && x.unit === y.unit;
}
/** Do not infer recency from season/episode order. Legacy undated rows only match their own ID. */
export function latestSeasonHistory(seed, history) {
  if (!Array.isArray(history)) return null;
  const valid = history.filter(row => row && typeof row.seriesId === 'string' && SERIES_ID.test(row.seriesId) && typeof row.vid === 'string' && ID.test(row.vid) && Number.isSafeInteger(row.index) && row.index > 0 && row.index <= 10000 && (row.seriesId === seed?.seriesId || sameSeasonFamily(seed, row)));
  const dated = valid.filter(row => Number.isFinite(row.updatedAt) && row.updatedAt > 0).sort((a, b) => b.updatedAt - a.updatedAt);
  return dated[0] || valid.find(row => row.seriesId === seed?.seriesId) || null;
}
