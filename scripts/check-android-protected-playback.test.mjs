import test from 'node:test';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const sourceRoot = resolve(process.env.ANDROID_SOURCE_ROOT || fileURLToPath(new URL('..', import.meta.url)));
const { SITE_ORIGIN, parsePlayback, parseDetail, createSource } =
  await import(pathToFileURL(resolve(sourceRoot, 'src/android/source.mjs')).href);
const sid = '970001', vid = '970006';
const keys = ['player_(series_id)/(vid)/page', 'player_(series_id)/page'];
function page(protection = undefined, { key = keys[0], data = {}, duration = 'PT120S', media = 'https://cdn.example.net/preview.mp4' } = {}) {
  const model = { isSuccess: true, series_id: sid, vid,
    seriesDetail: { series_id: sid, series_name: '测试剧：第二季', vid_list: ['970005', vid, '970007'], accessible_episode_cnt: 1 },
    publicVideoSeo: { available: true, seriesId: sid, itemId: vid, duration: 120 }, ...data };
  if (protection !== undefined) model.protectedPlayback = protection;
  return `<script>window._ROUTER_DATA=${JSON.stringify({loaderData:{[key]:model}})};</script>` +
    `<script type="application/ld+json">${JSON.stringify({'@type':'VideoObject',url:`${SITE_ORIGIN}/player/${sid}/${vid}`,contentUrl:media,duration})}</script>`;
}
const appInfo = (quality = 'auto', extra = {}) => ({ seriesId: sid, vid, source: 'app',
  url: 'https://cdn.qznovelvod.com/full-episode.mp4', requestedQuality: quality,
  contentKey: '00112233445566778899aabbccddeeff', width: 1920, height: 1080, ...extra });
const code = expected => error => error?.code === expected;

test('protectedPlayback boolean true rejects SEO preview for both exact loader keys', () => {
  for (const key of keys) assert.throws(() => parsePlayback(page(true, {key}), sid, vid), code('PREVIEW_ONLY'));
});
test('protected flag is strict and does not infer preview from metadata duration', () => {
  for (const protection of [undefined, false, null, 'true', 1, {}, []]) {
    for (const key of keys) assert.equal(parsePlayback(page(protection, {key, duration:'PT10S'}), sid, vid).vid, vid);
  }
});
test('preview classification follows exact episode identity and public availability', () => {
  assert.throws(() => parsePlayback(page(true, {data:{vid:'970007'}}), sid, vid), code('IDENTITY_MISMATCH'));
  assert.throws(() => parsePlayback(page(true, {data:{publicVideoSeo:{available:false,seriesId:sid,itemId:vid}}}), sid, vid), code('EPISODE_UNAVAILABLE'));
});
test('both protected webpage routes recover to same series and episode App source', async () => {
  const gets = [], apps = [];
  const source = createSource(async url => { gets.push(url); return page(true); },
    {fallback:async (...args) => { apps.push(args); return appInfo(); }});
  const result = await source.playback(sid, vid);
  assert.equal(result.source, 'app'); assert.equal(result.url, appInfo().url);
  assert.deepEqual(gets, [`${SITE_ORIGIN}/player/${sid}/${vid}`, `${SITE_ORIGIN}/player/_/${vid}`]);
  assert.deepEqual(apps, [[vid,{seriesId:sid,quality:'auto'}]]);
});
test('a protected first route can use strictly verified unprotected same-episode backup', async () => {
  let gets = 0, apps = 0;
  const source = createSource(async () => page(++gets === 1), {fallback:async () => { apps++; return appInfo(); }});
  const result = await source.playback(sid, vid);
  assert.equal(result.url, 'https://cdn.example.net/preview.mp4'); assert.equal(gets, 2); assert.equal(apps, 0);
});
test('App failure never falls back to either rejected protected preview', async () => {
  let gets = 0, apps = 0;
  const source = createSource(async () => { gets++; return page(true); },
    {fallback:async () => { apps++; throw new Error('fixture App unavailable'); }});
  await assert.rejects(source.playback(sid, vid), code('SOURCE_FALLBACK_FAILED'));
  assert.equal(gets, 2); assert.equal(apps, 1);
});
test('without App source both protected routes retain PREVIEW_ONLY failure', async () => {
  let gets = 0;
  const source = createSource(async () => { gets++; return page(true); }, {webFallback:true});
  await assert.rejects(source.playback(sid, vid), code('PREVIEW_ONLY')); assert.equal(gets, 2);
});
test('wrong App episode and unavailable requested quality stay failures, not preview success', async () => {
  const wrong = createSource(async () => page(true), {fallback:async () => appInfo('auto',{vid:'970007'})});
  await assert.rejects(wrong.playback(sid, vid), code('SOURCE_FALLBACK_INVALID'));
  const unavailable = createSource(async () => page(true), {fallback:async () => { throw Object.assign(new Error('fixture missing resolution'),{code:'QUALITY_UNAVAILABLE'}); }});
  await assert.rejects(unavailable.playback(sid, vid), code('QUALITY_UNAVAILABLE'));
});
test('1080p and 720p go directly to measured App source and never request preview pages', async () => {
  for (const quality of ['1080p','720p']) {
    let gets = 0; const apps = [];
    const source = createSource(async () => { gets++; return page(true); },
      {fallback:async (actualVid, options) => { apps.push([actualVid,options]); return appInfo(quality); }});
    const result = await source.playback(sid,vid,{quality});
    assert.equal(result.source,'app'); assert.equal(result.requestedQuality,quality); assert.equal(gets,0);
    assert.deepEqual(apps,[[vid,{seriesId:sid,quality}]]);
  }
});
test('protected playback flag never trims same-season directory or locks later episodes', () => {
  const detail = parseDetail(page(true),sid);
  assert.equal(detail.title,'测试剧：第二季'); assert.equal(detail.episodes.length,3);
  assert.deepEqual(detail.episodes.map(row=>row.vid),['970005',vid,'970007']);
  assert.deepEqual(detail.episodes.map(row=>row.availability),['available','unknown','unknown']);
});
