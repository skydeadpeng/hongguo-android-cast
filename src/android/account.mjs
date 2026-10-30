/** Strict UI projection: native session/cookies and full phone never cross this boundary. */
const PHASES = new Set(['phone', 'sending', 'code', 'submitting', 'mfa', 'mfa_waiting', 'logged_in']);
export function accountView(value) {
  if (!value || value.provider !== 'hongguo' || typeof value.authenticated !== 'boolean' || value.officialLoginAvailable !== true || !PHASES.has(value.phase)) throw new Error('红果账号状态读取未完成，请重试。');
  const text = (value, max = 160) => typeof value === 'string' ? value.slice(0, max) : '';
  const count = value => Number.isSafeInteger(value) && value >= 0 ? value : 0;
  const masked = value => typeof value === 'string' && /^1\d{2}\*{4}\d{4}$/.test(value) ? value : '';
  const sync = value.sync && typeof value.sync === 'object' ? value.sync : {};
  return {
    provider: 'hongguo', authenticated: value.authenticated, officialLoginAvailable: true,
    revision: count(value.revision), phase: value.phase, busy: value.busy === true,
    maskedPhone: masked(value.maskedPhone), retryAfter: Math.min(3600, count(value.retryAfter)),
    smsState: ['sent', 'limited', 'rejected', 'uncertain'].includes(value.smsState) ? value.smsState : '',
    error: text(value.error), loginRequired: value.loginRequired === true,
    user: value.authenticated && value.user && typeof value.user === 'object' ? { name: text(value.user.name, 80) || '红果用户', maskedPhone: masked(value.user.maskedPhone), idSuffix: /^尾号 \d{1,4}$/.test(value.user.idSuffix || '') ? value.user.idSuffix : '' } : null,
    smsNumber: /^\+?\d{5,20}$/.test(value.smsNumber || '') ? value.smsNumber : '',
    smsContent: text(value.smsContent, 256), mfaExpiresAt: count(value.mfaExpiresAt),
    libraryReady: value.libraryReady !== false,
    sync: { syncing: sync.syncing === true, pendingCount: count(sync.pendingCount), lastSyncAt: count(sync.lastSyncAt), error: text(sync.error), authExpired: sync.authExpired === true },
  };
}
export function phoneFields(phone) {
  let value = String(phone || '').trim().replace(/ /g, '');
  if (value.startsWith('+86')) value = value.slice(3); else if (value.length === 13 && value.startsWith('86')) value = value.slice(2);
  if (!/^1[3-9]\d{9}$/.test(value)) throw new Error('请输入有效的中国大陆手机号（+86）。');
  return { phone: value };
}
export function otpFields(code) {
  if (typeof code !== 'string' || !/^\d{4,8}$/.test(code)) throw new Error('请输入4至8位短信验证码。');
  return { code };
}
export function accountStatus(account) {
  if (!account) return '正在读取红果账号状态…';
  if (account.authenticated) return account.sync.syncing ? '正在同步红果收藏、历史与进度…' : account.sync.error ? '云同步未完成 · 本机记录已保留' : account.sync.pendingCount ? `有 ${account.sync.pendingCount} 条操作待同步` : account.sync.lastSyncAt ? `最近同步：${new Date(account.sync.lastSyncAt).toLocaleString('zh-CN', { hour12: false })}` : '已登录红果 · 本机记录已保存';
  if (account.phase === 'sending') return '正在登记设备并获取短信验证码…';
  if (account.phase === 'submitting') return '正在验证短信并登录红果…';
  if (account.phase === 'mfa_waiting') return '正在等待短信验证，最多90秒；可随时取消';
  if (account.phase === 'mfa') return '红果要求追加短信验证，请使用登录手机号发送下方短信';
  if (account.phase === 'code') return account.smsState === 'uncertain' ? '发码结果尚未确认；如果已收到短信，可直接输入验证码' : `验证码已发送至 ${account.maskedPhone || '当前手机号'}`;
  if (account.smsState === 'uncertain') return '发码结果尚未确认，请先检查短信并等待倒计时，避免重复发送';
  if (account.smsState === 'limited') return '红果暂时限制发码，请等待倒计时后重试';
  return account.loginRequired ? '红果会话已失效，请重新登录；本机记录保留' : '未登录红果 · 本机观看、收藏与下载继续可用';
}

/** Acknowledged native import counts only; polling snapshots do not claim imports. */
export function accountImportSummary(result) {
  const keys = ['historyImportedCount', 'favoritesImportedCount', 'skippedCount', 'remainingCount'];
  if (!result || typeof result !== 'object' || !keys.every(key => Number.isSafeInteger(result[key]) && result[key] >= 0)) return '导入数量尚未确认，请检查本机记录后重试。';
  return `已导入收藏 ${result.favoritesImportedCount} 条、观看记录 ${result.historyImportedCount} 条；${result.skippedCount} 条已存在或已处理。${result.remainingCount ? `还有 ${result.remainingCount} 条本机记录待导入，请再次确认导入。` : '本次本机记录已处理完毕。'}`;
}
