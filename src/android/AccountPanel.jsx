import React, { useCallback, useEffect, useRef, useState } from 'react';
import { nativeCall } from './bridge.mjs';
import { accountStatus, accountView, phoneFields, otpFields, accountImportSummary } from './account.mjs';

const signal = name => window.dispatchEvent(new Event(name));
export default function AccountPanel({ focusRequest }) {
  const [account, setAccount] = useState(null), [error, setError] = useState(''), [readError, setReadError] = useState(''), [action, setAction] = useState('');
  const [phone, setPhone] = useState(''), [code, setCode] = useState(''), [confirm, setConfirm] = useState(''), [importSummary, setImportSummary] = useState('');
  const reading = useRef(null);
  const mounted = useRef(false), operation = useRef(0), revision = useRef(-1), currentAccount = useRef(null), currentAction = useRef('');
  const section = useRef(null), phoneInput = useRef(null), codeInput = useRef(null), verifyButton = useRef(null), syncButton = useRef(null), logoutButton = useRef(null), refreshButton = useRef(null), cancelButton = useRef(null), shouldFocus = useRef(false);
  const accept = useCallback(value => {
    const next = accountView(value); if (next.revision < revision.current) return;
    revision.current = next.revision; currentAccount.current = next; setAccount(next);
    if (next.phase !== 'phone') setPhone('');
    if (next.phase !== 'code' && !(next.phase === 'phone' && next.smsState === 'uncertain' && next.maskedPhone)) setCode('');
    if (!next.authenticated) setImportSummary('');
  }, []);
  const refresh = useCallback(() => {
    // Resume, native events and the countdown timer share one status read. Slow
    // native workers must not accumulate a new 45-second RPC every second.
    if (reading.current) return reading.current;
    const request = (async () => {
      try { const value = await nativeCall('getAccount'); if (mounted.current) { accept(value); setReadError(''); } }
      catch (failure) { if (mounted.current) setReadError(failure?.message || '红果账号状态读取未完成，请重试。'); }
      finally { if (reading.current === request) reading.current = null; }
    })();
    reading.current = request; return request;
  }, [accept]);
  useEffect(() => {
    mounted.current = true; refresh();
    const changed = () => refresh();
    const visible = () => {
      if (document.visibilityState === 'hidden') {
        setCode(''); setPhone(''); operation.current++; currentAction.current = ''; setAction('');
        if (!currentAccount.current?.authenticated) nativeCall('officialCancelLogin', { reason: 'background' }, 10000).catch(() => {});
      } else refresh();
    };
    const timer = setInterval(() => { if (document.visibilityState !== 'hidden') refresh(); }, 1000);
    window.addEventListener('androidaccountchanged', changed); window.addEventListener('androidresume', refresh); document.addEventListener('visibilitychange', visible);
    return () => {
      mounted.current = false; operation.current++; clearInterval(timer); window.removeEventListener('androidaccountchanged', changed); window.removeEventListener('androidresume', refresh); document.removeEventListener('visibilitychange', visible);
      if (!currentAccount.current?.authenticated) nativeCall('officialCancelLogin', {}, 10000).catch(() => {});
    };
  }, [refresh]);
  useEffect(() => { if (focusRequest) { shouldFocus.current = true; (phoneInput.current || syncButton.current || verifyButton.current || codeInput.current)?.focus(); } }, [focusRequest]);
  useEffect(() => {
    if (!shouldFocus.current) return;
    const active = document.activeElement; if (active !== document.body && !section.current?.contains(active)) { shouldFocus.current = false; return; }
    const target = confirm ? cancelButton.current : account?.authenticated ? [syncButton.current, logoutButton.current, refreshButton.current].find(button => button && !button.disabled) : (account?.phase === 'code' || account?.phase === 'phone' && account?.smsState === 'uncertain' && account?.maskedPhone) ? codeInput.current : account?.phase === 'mfa' ? verifyButton.current : account?.busy ? cancelButton.current : phoneInput.current;
    if (target && !target.disabled) target.focus({ preventScroll: true });
  }, [account?.phase, account?.busy, account?.sync.syncing, action, confirm]);
  const perform = async (method, params = {}) => {
    const cancelling = method === 'officialCancelLogin';
    if (currentAction.current && !cancelling) return;
    const token = ++operation.current; currentAction.current = method; setAction(method); setError(''); setConfirm(''); setImportSummary(''); shouldFocus.current = true;
    const identity = ['officialLoginSMS', 'officialVerifyMFA', 'officialLogout'].includes(method);
    if (identity) signal('androidaccountchanging');
    try {
      const value = await nativeCall(method, params, method === 'officialVerifyMFA' ? 120000 : 90000);
      if (mounted.current && operation.current === token) { accept(value); if (value.error) setError(value.error); else if (method === 'officialImportGuest') setImportSummary(accountImportSummary(value.importResult)); }
    } catch (failure) { if (mounted.current && operation.current === token) setError(failure?.message || '账号操作未完成，请重试。'); }
    finally {
      if (identity) signal('androidaccountsettled');
      if (mounted.current && operation.current === token) { currentAction.current = ''; setAction(''); await refresh(); }
    }
  };
  const send = event => { event.preventDefault(); try { perform('officialSendCode', phoneFields(phone)); } catch (failure) { setError(failure.message); } };
  const login = event => { event.preventDefault(); try { const params = otpFields(code); setCode(''); perform('officialLoginSMS', params); } catch (failure) { setCode(''); setError(failure.message); } };
  const cancel = () => { setPhone(''); setCode(''); perform('officialCancelLogin'); };
  const busy = !!action || account?.busy, phase = account?.phase || 'phone', signedIn = account?.authenticated === true;
  const receivedCodeAllowed = phase === 'code' || phase === 'submitting' || phase === 'phone' && account?.smsState === 'uncertain' && !!account?.maskedPhone;
  const visibleError = error || account?.error || account?.sync.error || readError;
  return <section ref={section} className="about-section account-section" aria-labelledby="account-title" aria-busy={!!busy}>
    <h2 id="account-title">红果账号</h2>
    <p className="account-status" role="status" aria-live="polite">{action === 'officialVerifyMFA' ? '正在等待短信验证，最多90秒；可随时取消' : action === 'officialSendCode' ? '正在登记设备并获取短信验证码…' : action === 'officialLoginSMS' ? '正在验证短信并登录红果…' : accountStatus(account)}</p>
    <p className="account-note">直接登录红果官方账号；自建同步服务器已移除。未登录也可继续观看，本机记录不会自动上传或合并。</p>
    {visibleError && <p className="account-error" role="alert">{visibleError}</p>}
    {signedIn ? <>
      <dl className="account-details"><div><dt>当前账号</dt><dd>{account.user?.name} {account.user?.maskedPhone || account.user?.idSuffix}</dd></div><div><dt>待同步</dt><dd>{account.sync.pendingCount} 条</dd></div></dl>
      <div className="account-actions"><button ref={syncButton} type="button" className="button primary" disabled={!!busy || account.sync.syncing} onClick={() => perform('officialSync')}>{account.sync.syncing ? '同步中…' : '立即同步'}</button><button type="button" className="button" disabled={!!busy || account.sync.syncing} onClick={() => { shouldFocus.current = true; setConfirm('import'); }}>导入本机记录</button><button ref={logoutButton} type="button" className="button" disabled={!!busy} onClick={() => { shouldFocus.current = true; setConfirm('logout'); }}>退出红果账号</button></div>
      {importSummary && <p className="account-note account-import-result" role="status">{importSummary}</p>}
      {confirm && <div className="account-confirm" role="group" aria-label={confirm === 'logout' ? '确认退出红果账号' : '确认导入本机记录'}><p>{confirm === 'logout' ? '退出后显示原本机记录；当前账号的本机库和待同步操作保留。' : '只在确认后合并登录前的本机收藏和观看记录；原本机库保留，云端同步可能随后写入这些记录。'}</p><div className="account-actions"><button ref={cancelButton} type="button" className="button" onClick={() => setConfirm('')}>取消</button><button type="button" className="button" onClick={() => perform(confirm === 'logout' ? 'officialLogout' : 'officialImportGuest')}>{confirm === 'logout' ? '确认退出' : '确认导入'}</button></div></div>}
    </> : <>
      {((phase === 'phone' && !receivedCodeAllowed) || phase === 'sending') && <form className="account-form" onSubmit={send}><label htmlFor="official-phone">中国大陆手机号（+86）<input ref={phoneInput} id="official-phone" type="tel" inputMode="tel" autoComplete="off" maxLength={16} value={phone} disabled={!!busy} onChange={event => setPhone(event.target.value)} required /></label><p className="account-note">点击后向红果官方请求短信验证码。手机号、验证码和会话不会发送到自建服务器。</p><div className="account-actions"><button type="submit" className="button primary" disabled={!!busy || !phone.trim() || !!account?.retryAfter}>{account?.retryAfter ? `${account.retryAfter}秒后可重试` : '获取短信验证码'}</button></div></form>}
      {receivedCodeAllowed && <form className="account-form" onSubmit={login}>{account?.smsState === 'uncertain' && <p className="account-note">发码结果尚未确认；如果已收到短信，可直接输入验证码，无需再次发送。</p>}<label htmlFor="official-code">短信验证码<input ref={codeInput} id="official-code" type="text" inputMode="numeric" autoComplete="one-time-code" maxLength={8} value={code} disabled={!!busy} onChange={event => setCode(event.target.value.replace(/\D/g, ''))} required /></label><div className="account-actions"><button type="submit" className="button primary" disabled={!!busy || !code}>登录红果</button><button type="button" className="button" disabled={!!busy || !!account?.retryAfter} onClick={() => perform('officialSendCode')}>{account?.retryAfter ? `${account.retryAfter}秒后可重发` : '重新获取验证码'}</button></div></form>}
      {(phase === 'mfa' || phase === 'mfa_waiting') && <div className="account-confirm" role="group" aria-label="红果追加短信验证"><p>请使用 {account?.maskedPhone || '登录手机号'} 向以下号码发送指定短信：</p><dl className="account-details"><div><dt>收件号码</dt><dd>{account?.smsNumber}</dd></div><div><dt>短信内容</dt><dd style={{ overflowWrap: 'anywhere' }}>{account?.smsContent}</dd></div></dl><p className="account-note">短信由你在手机上手动发送；发完后点击下面按钮。本机验证最多等待90秒，可取消。</p><div className="account-actions"><button ref={verifyButton} type="button" className="button primary" disabled={!!busy} onClick={() => perform('officialVerifyMFA')}>我已发送短信，开始验证</button></div></div>}
      {(phase !== 'phone' || receivedCodeAllowed || busy || account?.retryAfter > 0) && <div className="account-actions"><button ref={cancelButton} type="button" className="button" onClick={cancel}>{busy ? '取消登录' : '换手机号 / 取消'}</button></div>}
    </>}
    <div className="account-actions"><button ref={refreshButton} type="button" className="button" disabled={!!action} onClick={refresh}>重新读取状态</button></div>
  </section>;
}
