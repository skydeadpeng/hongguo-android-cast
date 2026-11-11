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
 * Vertical keys keep the previous global spatial scoring.
 *
 * @returns the chosen control record, or null when focus should stay put.
 */
export function pickSpatialCandidate(controls, focused, key) {
  if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(key)) return null;
  if (!focused || !controls.includes(focused)) return null;
  const horizontal = key === 'ArrowLeft' || key === 'ArrowRight';
  const positive = key === 'ArrowRight' || key === 'ArrowDown';
  const pool = horizontal && focused.boundary
    ? controls.filter(control => control !== focused && control.boundary === focused.boundary)
    : controls.filter(control => control !== focused);
  const origin = center(focused.rect);
  let best = null;
  for (const control of pool) {
    const target = center(control.rect);
    const along = horizontal ? target.x - origin.x : target.y - origin.y;
    const across = horizontal ? target.y - origin.y : target.x - origin.x;
    if ((positive ? along : -along) <= 2) continue;
    const score = Math.abs(along) + Math.abs(across) * 3 + across * across / Math.max(Math.abs(along), 1);
    if (!best || score < best.score) best = { control, score };
  }
  return best ? best.control : null;
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
