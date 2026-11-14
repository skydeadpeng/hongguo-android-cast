// Spatial D-pad navigation model for the TV WebView layout, kept free of DOM
// calls so the geometry can be unit-tested. The App.jsx keydown handler turns
// live elements into these plain control records and applies the result.

/**
 * Pick the next focus target for a direction key.
 * Controls are `{ element, rect: {left, top, width, height}, boundary, ... }`;
 * `boundary` is an opaque reference to the horizontal grid the control lives
 * in (poster grid, episode grid, chip strip, sidebar list) or null.
 *
 * Left/right inside a boundary only considers siblings of that same boundary,
 * so reaching the edge of a grid stops there instead of jumping to whatever
 * sits beyond it (the sidebar was capturing posters on the first column).
 * Up/down follows the visual row order: the nearest row below/above first,
 * then the nearest column inside that row, so a sparse row or a between-
 * sections action is never skipped in favour of a farther same-column item.
 *
 * @returns the chosen control record, or null when focus should stay put.
 */
export function pickSpatialCandidate(controls, focused, key) {
  if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(key)) return null;
  if (!focused || !controls.includes(focused)) return null;
  if (key === 'ArrowUp' || key === 'ArrowDown') return pickVerticalCandidate(controls, focused, key === 'ArrowDown');
  const pool = focused.boundary
    ? controls.filter(control => control !== focused && control.boundary === focused.boundary)
    : controls.filter(control => control !== focused);
  let best = scoreHorizontalPool(pool, focused, key);
  // Left edge of a grid: the sidebar is the deliberate destination. 0.12.70
  // confined left/right inside the boundary, which left no way back to the
  // menu from the content; the first column (or leftmost chip) falls through
  // to the nav rail, mid-grid lefts never do.
  if (!best && key === 'ArrowLeft') {
    best = scoreHorizontalPool(controls.filter(control => control !== focused && control.nav), focused, key);
  }
  return best ? best.control : null;
}

function scoreHorizontalPool(pool, focused, key) {
  const origin = center(focused.rect);
  let best = null;
  for (const control of pool) {
    const target = center(control.rect);
    const along = target.x - origin.x;
    const across = target.y - origin.y;
    if ((key === 'ArrowRight' ? along : -along) <= 2) continue;
    const score = Math.abs(along) + Math.abs(across) * 3 + across * across / Math.max(Math.abs(along), 1);
    if (!best || score < best.score) best = { control, score };
  }
  return best;
}

/** Vertical moves walk visual rows (bands): candidates strictly above/below
 * the focused control, grouped by overlapping spans, then the nearest column
 * inside the nearest band. Global scoring let a same-column control two rows
 * down outrank the sparse row in between, so focus skipped whole sections.
 * Sidebar items interleave with content rows vertically (they span the whole
 * screen height), so they form phantom nearer bands: while the focus is in
 * the content they stay out of the pool (left/right still crosses over), and
 * from inside the sidebar up/down stays within it. */
function pickVerticalCandidate(controls, focused, down) {
  const focusedRect = focused.rect;
  const focusedBottom = focusedRect.top + focusedRect.height;
  const inDirection = controls.filter(control => {
    if (control === focused) return false;
    // Edges are derived from top+height: control rects may be plain records
    // (tests) or live DOMRects, and only the latter carries bottom.
    const bottom = control.rect.top + control.rect.height;
    return down ? control.rect.top >= focusedBottom - 2 : bottom <= focusedRect.top + 2;
  });
  if (!inDirection.length) return null;
  let pool = inDirection;
  if (!focused.nav) {
    const content = inDirection.filter(control => !control.nav);
    if (content.length) pool = content;
  }
  pool.sort((a, b) => a.rect.top - b.rect.top);
  const bands = [];
  for (const control of pool) {
    const bottom = control.rect.top + control.rect.height;
    const band = bands[bands.length - 1];
    if (band && control.rect.top < band.bottom) {
      band.controls.push(control);
      if (bottom > band.bottom) band.bottom = bottom;
    } else bands.push({ bottom, controls: [control] });
  }
  // Bands are ordered by top and their bottoms never decrease, so the nearest
  // band below is the first and the nearest above is the last.
  const band = down ? bands[0] : bands[bands.length - 1];
  const originX = focusedRect.left + focusedRect.width / 2;
  let best = null;
  for (const control of band.controls) {
    const distance = Math.abs(control.rect.left + control.rect.width / 2 - originX);
    if (!best || distance < best.distance) best = { control, distance };
  }
  return best.control;
}

/**
 * Where focus should land on the first direction press when nothing has focus
 * yet (fresh launch): the poster grid, else the first main-content control —
 * never the sidebar, which used to swallow the first press.
 */
export function pickInitialControl(controls) {
  if (!controls.length) return null;
  return controls.find(control => control.poster)
    || controls.find(control => !control.nav)
    || controls[0];
}

function center(rect) { return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }; }
