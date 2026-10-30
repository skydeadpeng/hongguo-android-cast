const SITE = 'https://hongguoduanju.com';
const fail = (message, code) => Object.assign(new Error(message), { code });

/** Two identity-checked page routes share one budget; the winner cancels the loser. */
export async function loadDetail(request, seriesId, parse, { timeoutMs = 8000, signal } = {}) {
  if (typeof request !== 'function' || typeof parse !== 'function' || typeof seriesId !== 'string' ||
      !/^\d{1,30}$/.test(seriesId) || !Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 12000) {
    throw fail('分集请求参数无效', 'INVALID_INPUT');
  }
  if (signal?.aborted) throw fail('分集请求已取消', 'ERR_CANCELED');
  const deadlineMs = Date.now() + timeoutMs;
  const controllers = [new AbortController(), new AbortController()];
  const urls = [`${SITE}/player/${seriesId}`, `${SITE}/detail?series_id=${seriesId}`];
  return new Promise((resolve, reject) => {
    let settled = false, failures = 0;
    const finish = (value, cause, winner = -1) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal?.removeEventListener('abort', cancel);
      controllers.forEach((controller, index) => { if (index !== winner) controller.abort(); });
      if (cause) reject(cause); else resolve(value);
    };
    const cancel = () => finish(null, fail('分集请求已取消', 'ERR_CANCELED'));
    const timer = setTimeout(() => finish(null, fail('分集加载超时，请重试', 'ETIMEDOUT')), timeoutMs);
    signal?.addEventListener('abort', cancel, { once: true });
    // Register cancellation before starting either request; both recheck settled.
    if (signal?.aborted) { cancel(); return; }
    urls.forEach((url, index) => {
      Promise.resolve().then(() => {
        if (settled) return;
        return request(url, { timeoutMs, deadlineMs, signal: controllers[index].signal });
      }).then(html => {
        if (settled) return;
        const detail = parse(html);
        if (!detail || !Array.isArray(detail.episodes) || !detail.episodes.length) throw fail('来源未返回有效分集', 'DETAIL_UNAVAILABLE');
        finish(detail, null, index);
      }).catch(() => {
        if (settled) return;
        if (++failures === urls.length) finish(null, fail('分集加载未成功，请重试', 'DETAIL_UNAVAILABLE'));
      });
    });
  });
}
