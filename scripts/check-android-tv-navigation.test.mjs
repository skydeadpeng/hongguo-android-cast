import test from 'node:test';
import assert from 'node:assert/strict';
import { pickSpatialCandidate, pickInitialControl } from '../src/android/tv-navigation.mjs';

// Geometry mirrors the tv-layout: 208px sidebar, five poster columns, 44px-wide
// category chips above the grid. element/boundary are opaque references; the
// module only compares identity.
// Sidebar entries live outside the horizontal grids: closest(grid) is null.
const nav = (name, top) => ({ element: name, kind: 'nav', nav: true, boundary: null, rect: rect(16, top, 176, 60) });
const poster = (row, col, boundary = GRID) => ({ element: `p${row}${col}`, kind: 'poster', poster: true, boundary, rect: rect(250 + col * 200, 180 + row * 330, 160, 240) });
const chip = (name, col) => ({ element: name, kind: 'chip', boundary: CHIPS, rect: rect(250 + col * 120, 90, 100, 44) });
function rect(left, top, width, height) { return { left, top, width, height }; }
const NAV = {}, GRID = {}, CHIPS = {};

const page = [
  nav('发现', 40), nav('黄果', 116), nav('搜索', 192), nav('追剧', 268), nav('下载', 344), nav('关于', 420),
  { element: 'search', kind: 'input', rect: rect(250, 24, 520, 56) },
  chip('真人剧', 0), chip('漫剧', 1), chip('AI剧', 2),
  poster(0, 0), poster(0, 1), poster(0, 2), poster(0, 3), poster(0, 4),
  poster(1, 0), poster(1, 1), poster(1, 2), poster(1, 3), poster(1, 4),
];

test('poster grid moves within the row and stops at the edge instead of leaving the grid', () => {
  const at = name => page.find(control => control.element === name);
  assert.equal(pickSpatialCandidate(page, at('p11'), 'ArrowRight')?.element, 'p12');
  assert.equal(pickSpatialCandidate(page, at('p11'), 'ArrowLeft')?.element, 'p10');
  // Right edge stops; the left edge deliberately enters the sidebar (below).
  assert.equal(pickSpatialCandidate(page, at('p04'), 'ArrowRight'), null);
});

test('left edge of a grid deliberately enters the sidebar, nearest menu item first', () => {
  const at = name => page.find(control => control.element === name);
  // 0.12.70 made the menu unreachable from content: left/right stayed inside
  // the boundary and vertical keys excluded nav. The first column falls back
  // to the nav rail; mid-grid lefts never do.
  assert.equal(pickSpatialCandidate(page, at('p00'), 'ArrowLeft')?.element, '追剧');
  assert.equal(pickSpatialCandidate(page, at('p10'), 'ArrowLeft')?.element, '关于');
  assert.equal(pickSpatialCandidate(page, at('真人剧'), 'ArrowLeft')?.element, '黄果');
});

test('vertical moves stay spatial and cross containers as before', () => {
  const at = name => page.find(control => control.element === name);
  assert.equal(pickSpatialCandidate(page, at('p02'), 'ArrowDown')?.element, 'p12');
  assert.equal(pickSpatialCandidate(page, at('p12'), 'ArrowUp')?.element, 'p02');
  // Chips sit above the first poster row: down from a chip enters the grid
  // (漫剧's center is nearest column 0 of the row below).
  assert.equal(pickSpatialCandidate(page, at('漫剧'), 'ArrowDown')?.element, 'p00');
});

test('category chips move inside their own strip and stop at both ends', () => {
  const at = name => page.find(control => control.element === name);
  assert.equal(pickSpatialCandidate(page, at('真人剧'), 'ArrowRight')?.element, '漫剧');
  assert.equal(pickSpatialCandidate(page, at('漫剧'), 'ArrowLeft')?.element, '真人剧');
  // Left edge of the strip enters the sidebar (deliberate menu access).
  assert.equal(pickSpatialCandidate(page, at('真人剧'), 'ArrowLeft')?.element, '黄果');
  assert.equal(pickSpatialCandidate(page, at('AI剧'), 'ArrowRight'), null);
});

test('sidebar left/right: right leaves into main content, left has nowhere to go', () => {
  const at = name => page.find(control => control.element === name);
  // The sidebar is a vertical list, not a horizontal grid: right must keep the
  // global spatial move so the user can leave the sidebar into the content.
  assert.equal(pickSpatialCandidate(page, at('发现'), 'ArrowRight')?.element, '真人剧');
  assert.equal(pickSpatialCandidate(page, at('下载'), 'ArrowRight')?.element, 'p00');
  assert.equal(pickSpatialCandidate(page, at('发现'), 'ArrowLeft'), null);
  assert.equal(pickSpatialCandidate(page, at('发现'), 'ArrowDown')?.element, '黄果');
});

test('controls outside any grid keep the global spatial fallback', () => {
  const lone = [...page, { element: 'retry', kind: 'button', rect: rect(250, 2600, 160, 54) }];
  const retry = lone.at(-1);
  assert.equal(pickSpatialCandidate(lone, retry, 'ArrowUp')?.element, 'p10');
  // No boundary: left is still a global spatial move (the sidebar is to the left).
  assert.notEqual(pickSpatialCandidate(lone, retry, 'ArrowLeft'), null);
});

test('the first press after focus loss lands on main content, never the sidebar', () => {
  // Poster grid first: that is where browsing starts on a TV.
  assert.equal(pickInitialControl(page)?.element, 'p00');
  // Without posters the first main-content control wins over the sidebar.
  const noPosters = page.filter(item => item.kind !== 'poster');
  assert.equal(pickInitialControl(noPosters)?.element, 'search');
  // Total fallback when nothing but sidebar controls exist.
  assert.equal(pickInitialControl(page.filter(item => item.kind === 'nav'))?.element, '发现');
});

test('non-direction keys and unknown focus are inert', () => {
  assert.equal(pickSpatialCandidate(page, poster(0, 0), 'Enter'), null);
  assert.equal(pickSpatialCandidate(page, { element: 'x', rect: rect(0, 0, 1, 1) }, 'ArrowRight'), null);
});

// A sparse middle row (partial row or a between-sections action) must never be
// skipped: vertical moves follow the visual row order, nearest row first, then
// nearest column inside that row.
const sparse = [
  nav('发现', 40), nav('黄果', 116), nav('搜索', 192), nav('追剧', 268), nav('下载', 344), nav('关于', 420),
  { element: 'search', kind: 'input', rect: rect(250, 24, 520, 56) },
  chip('真人剧', 0), chip('漫剧', 1), chip('AI剧', 2),
  poster(0, 0), poster(0, 1), poster(0, 2), poster(0, 3), poster(0, 4),
  poster(1, 0), poster(1, 1), // partial middle row: only columns 0-1 exist
  { element: 'homeNext', kind: 'button', rect: rect(250, 770, 160, 54) },
  poster(2, 0), poster(2, 1), poster(2, 2), poster(2, 3), poster(2, 4),
];

test('vertical down lands in the nearest visual row, never skips a sparse row', () => {
  const at = name => sparse.find(control => control.element === name);
  // Row 1 only has columns 0-1: down from p02 must land in row 1 (p11),
  // not jump to the same column two rows down (p22).
  assert.equal(pickSpatialCandidate(sparse, at('p02'), 'ArrowDown')?.element, 'p11');
  // Row 1 still wins from a column that row 1 covers directly.
  assert.equal(pickSpatialCandidate(sparse, at('p00'), 'ArrowDown')?.element, 'p10');
});

test('vertical down reaches the between-sections action before the next section', () => {
  const at = name => sparse.find(control => control.element === name);
  // 换一批 sits between row 1 and row 2: down from p11 must take it first.
  assert.equal(pickSpatialCandidate(sparse, at('p11'), 'ArrowDown')?.element, 'homeNext');
});

test('vertical up retraces through the between-sections action in reverse', () => {
  const at = name => sparse.find(control => control.element === name);
  // Back up from row 2: 换一批 first, then out of row 1 to row 0.
  assert.equal(pickSpatialCandidate(sparse, at('p21'), 'ArrowUp')?.element, 'homeNext');
  assert.equal(pickSpatialCandidate(sparse, at('p11'), 'ArrowUp')?.element, 'p01');
});
