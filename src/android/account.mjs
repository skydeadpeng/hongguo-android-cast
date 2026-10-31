/** Strict UI projection: native session/cookies and full phone never cross this boundary. */
const PHASES = new Set(['phone', 'sending', 'code', 'submitting', 'mfa', 'mfa_waiting', 'logged_in']);
export function accountView(value) {
  if (!value || value.provider !== 'hongguo' || typeof value.authenticated !== 'boolean' || value.officialLoginAvailable !== true || !PHASES.has(value.phase)) throw new Error('红果账号状态读取未完成，请重试。');
  const text = (value, max = 160) => typeof value === 'string' ? value.slice(0, max) : '';
  const count = value => Number.isSafeInteger(value) && value >= 0 ? value : 0;
  const masked = value => typeof value === 'string' && /^1\d{2}\*{4}\d{4}$/.test(value) ? value : '';
  const sync = value.sync && typeof value.sync === 'object' ? value.sync : {};
  const web = value.web && typeof value.web === 'object' ? value.web : {};
  return {
    provider: 'hongguo', authenticated: value.authenticated, officialLoginAvailable: true,
    revision: count(value.revision), phase: value.phase, busy: value.busy === true,
    maskedPhone: masked(value.maskedPhone), retryAfter: Math.min(3600, count(value.retryAfter)),
    smsState: ['sent', 'limited', 'rejected', 'uncertain'].includes(value.smsState) ? value.smsState : '',
    error: text(value.error), loginRequired: value.loginRequired === true,
    // Stable failure code for support: no URL, no credential, no phone fragment.
    diagnostic: /^[a-z_]{1,40}$/.test(value.diagnostic || '') ? value.diagnostic : '',
    user: value.authenticated && value.user && typeof value.user === 'object' ? { name: text(value.user.name, 80) || '红果用户', maskedPhone: masked(value.user.maskedPhone), idSuffix: /^尾号 \d{1,4}$/.test(value.user.idSuffix || '') ? value.user.idSuffix : '' } : null,
    smsNumber: /^\+?\d{5,20}$/.test(value.smsNumber || '') ? value.smsNumber : '',
    smsContent: text(value.smsContent, 256), mfaExpiresAt: count(value.mfaExpiresAt),
    libraryReady: value.libraryReady !== false,
    sync: { syncing: sync.syncing === true, pendingCount: count(sync.pendingCount), lastSyncAt: count(sync.lastSyncAt), error: text(sync.error), authExpired: sync.authExpired === true },
    // The website QR session is a separate credential: it never carries the mobile passport state.
    web: {
      available: web.available === true,
      phase: ['idle', 'qrcode', 'scanned', 'logged_in'].includes(web.phase) ? web.phase : 'idle',
      authenticated: web.authenticated === true,
      busy: web.busy === true,
      // The web cookie has proven itself on the signed cloud APIs (user/info answers the same id).
      cloudReady: web.cloudReady === true,
      error: text(web.error),
      diagnostic: /^[a-z_]{1,40}$/.test(web.diagnostic || '') ? web.diagnostic : '',
      expiresIn: Math.min(600, count(web.expiresIn)),
      scanState: ['waiting', 'scanned'].includes(web.scanState) ? web.scanState : '',
      user: web.authenticated && web.user && typeof web.user === 'object' ? { name: text(web.user.name, 80) || '红果用户', idSuffix: /^尾号 \d{1,4}$/.test(web.user.idSuffix || '') ? web.user.idSuffix : '' } : null,
    },
  };
}
export function accountStatus(account) {
  if (!account) return '正在读取红果账号状态…';
  if (account.authenticated) return account.sync.syncing ? '正在同步红果收藏、历史与进度…' : account.sync.error ? '云同步未完成 · 本机记录已保留' : account.sync.pendingCount ? `有 ${account.sync.pendingCount} 条操作待同步` : account.sync.lastSyncAt ? `最近同步：${new Date(account.sync.lastSyncAt).toLocaleString('zh-CN', { hour12: false })}` : '已登录红果 · 本机记录已保存';
  // Short-message login is retired from the UI; the website QR flow is the only entry.
  return account.loginRequired ? '红果会话已失效，请用扫码登录；本机记录保留' : '未登录红果 · 扫码登录后同步收藏与进度';
}

/** Acknowledged native import counts only; polling snapshots do not claim imports. */
export function accountImportSummary(result) {
  const keys = ['historyImportedCount', 'favoritesImportedCount', 'skippedCount', 'remainingCount'];
  if (!result || typeof result !== 'object' || !keys.every(key => Number.isSafeInteger(result[key]) && result[key] >= 0)) return '导入数量尚未确认，请检查本机记录后重试。';
  return `已导入收藏 ${result.favoritesImportedCount} 条、观看记录 ${result.historyImportedCount} 条；${result.skippedCount} 条已存在或已处理。${result.remainingCount ? `还有 ${result.remainingCount} 条本机记录待导入，请再次确认导入。` : '本次本机记录已处理完毕。'}`;
}
