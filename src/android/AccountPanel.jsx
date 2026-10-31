import React, { useCallback, useEffect, useRef, useState } from 'react';
import { nativeCall } from './bridge.mjs';
import { accountStatus, accountView, accountImportSummary } from './account.mjs';

const signal = name => window.dispatchEvent(new Event(name));
export default function AccountPanel({ focusRequest }) {
  const [account, setAccount] = useState(null), [error, setError] = useState(''), [readError, setReadError] = useState(''), [action, setAction] = useState('');
  const [confirm, setConfirm] = useState(''), [importSummary, setImportSummary] = useState('');
  const [qrImage, setQrImage] = useState(''), [qrError, setQrError] = useState('');
  const reading = useRef(null);
  const mounted = useRef(false), operation = useRef(0), revision = useRef(-1), currentAccount = useRef(null), currentAction = useRef('');
  const section = useRef(null), syncButton = useRef(null), logoutButton = useRef(null), refreshButton = useRef(null), cancelButton = useRef(null), qrButton = useRef(null), shouldFocus = useRef(false);
  const accept = useCallback(value => {
    const next = accountView(value); if (next.revision < revision.current) return;
    revision.current = next.revision; currentAccount.current = next; setAccount(next);
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
    const visible = () => { if (document.visibilityState !== 'hidden') refresh(); };
    const timer = setInterval(() => { if (document.visibilityState !== 'hidden') refresh(); }, 1000);
    window.addEventListener('androidaccountchanged', changed); window.addEventListener('androidresume', refresh); document.addEventListener('visibilitychange', visible);
    return () => {
      mounted.current = false; operation.current++; clearInterval(timer); window.removeEventListener('androidaccountchanged', changed); window.removeEventListener('androidresume', refresh); document.removeEventListener('visibilitychange', visible);
    };
  }, [refresh]);
  useEffect(() => { if (focusRequest) { shouldFocus.current = true; (syncButton.current || qrButton.current || refreshButton.current)?.focus(); } }, [focusRequest]);
  useEffect(() => {
    if (!shouldFocus.current) return;
    const active = document.activeElement; if (active !== document.body && !section.current?.contains(active)) { shouldFocus.current = false; return; }
    const target = confirm ? cancelButton.current : account?.authenticated ? [syncButton.current, logoutButton.current, refreshButton.current].find(button => button && !button.disabled) : qrButton.current && !qrButton.current.disabled ? qrButton.current : refreshButton.current;
    if (target && !target.disabled) target.focus({ preventScroll: true });
  }, [account?.authenticated, account?.busy, account?.sync.syncing, account?.web?.phase, action, confirm, qrImage]);
  const perform = async (method, params = {}) => {
    if (currentAction.current) return;
    const token = ++operation.current; currentAction.current = method; setAction(method); setError(''); setConfirm(''); setImportSummary(''); shouldFocus.current = true;
    if (method === 'officialLogout') signal('androidaccountchanging');
    try {
      const value = await nativeCall(method, params, 90000);
      if (mounted.current && operation.current === token) { accept(value); if (value.error) setError(value.error); else if (method === 'officialImportGuest') setImportSummary(accountImportSummary(value.importResult)); }
    } catch (failure) { if (mounted.current && operation.current === token) setError(failure?.message || '账号操作未完成，请重试。'); }
    finally {
      if (method === 'officialLogout') signal('androidaccountsettled');
      if (mounted.current && operation.current === token) { currentAction.current = ''; setAction(''); await refresh(); }
    }
  };
  // The website QR handshake is the only login: no SMS code, no extra verification.
  const startQr = async () => {
    setQrError(''); setQrImage('');
    try {
      const value = await nativeCall('officialWebQrcode', {}, 30000);
      if (!mounted.current) return;
      const image = typeof value?.image === 'string' ? value.image : '';
      setQrImage(image);
      if (!image) setQrError('二维码获取未完成，请重试。');
      refresh();
    } catch (failure) { if (mounted.current) setQrError(failure?.message || '二维码获取未完成，请重试。'); }
  };
  const cancelQr = async () => {
    setQrError(''); setQrImage('');
    try { await nativeCall('officialWebCancel', {}, 15000); } catch (failure) { if (mounted.current) setQrError(failure?.message || '取消扫码未完成，请重试。'); }
    refresh();
  };
  const webLogout = async () => {
    setQrError(''); setQrImage('');
    try { await nativeCall('officialWebLogout', {}, 20000); } catch (failure) { if (mounted.current) setQrError(failure?.message || '退出官网账号未完成，请重试。'); }
    refresh();
  };
  const busy = !!action || account?.busy;
  // The website QR session is a first-class login; the retired SMS session may coexist.
  const passportIn = account?.authenticated === true, webIn = account?.web?.authenticated === true;
  const signedIn = passportIn || webIn;
  const webPhase = account?.web?.phase || 'idle';
  // Poll the scan state while a QR is on screen. The phase string is the only dependency, so the
  // one-second status refresh never restarts this timer.
  useEffect(() => {
    if (webPhase !== 'qrcode' && webPhase !== 'scanned') return undefined;
    let stopped = false;
    const timer = setInterval(() => {
      if (stopped || document.visibilityState === 'hidden') return;
      nativeCall('officialWebPoll', {}, 20000).then(() => { if (!stopped && mounted.current) refresh(); })
        .catch(failure => { if (!stopped && mounted.current) setQrError(failure?.message || '扫码状态读取未完成，请重试。'); });
    }, 2000);
    return () => { stopped = true; clearInterval(timer); };
  }, [webPhase, refresh]);
  const visibleError = error || account?.error || account?.sync.error || readError;
  return <section ref={section} className="about-section account-section" aria-labelledby="account-title" aria-busy={!!busy}>
    <h2 id="account-title">红果账号</h2>
    <p className="account-status" role="status" aria-live="polite">{accountStatus(account)}</p>
    <p className="account-note">用手机红果 App 扫码登录官方账号，同步收藏、历史与进度。未登录也可继续观看，本机记录不会自动上传或合并。</p>
    {visibleError && <p className="account-error" role="alert">{visibleError}</p>}
    {signedIn && <>
      <dl className="account-details"><div><dt>当前账号</dt><dd>{(passportIn ? account.user?.name : account.web.user?.name) || '红果用户'} {passportIn ? account.user?.maskedPhone || account.user?.idSuffix : account.web.user?.idSuffix}</dd></div><div><dt>待同步</dt><dd>{account.sync.pendingCount} 条</dd></div></dl>
      <div className="account-actions"><button ref={syncButton} type="button" className="button primary" disabled={!!busy || account.sync.syncing} onClick={() => perform('officialSync')}>{account.sync.syncing ? '同步中…' : '立即同步'}</button><button type="button" className="button" disabled={!!busy || account.sync.syncing} onClick={() => { shouldFocus.current = true; setConfirm('import'); }}>导入本机记录</button><button ref={logoutButton} type="button" className="button" disabled={!!busy} onClick={() => { shouldFocus.current = true; if (passportIn) setConfirm('logout'); else webLogout(); }}>{passportIn ? '退出红果账号' : '退出官网账号'}</button></div>
      {importSummary && <p className="account-note account-import-result" role="status">{importSummary}</p>}
      {confirm && <div className="account-confirm" role="group" aria-label={confirm === 'logout' ? '确认退出红果账号' : '确认导入本机记录'}><p>{confirm === 'logout' ? '退出后显示原本机记录；当前账号的本机库和待同步操作保留。' : '只在确认后合并登录前的本机收藏和观看记录；原本机库保留，云端同步可能随后写入这些记录。'}</p><div className="account-actions"><button ref={cancelButton} type="button" className="button" onClick={() => setConfirm('')}>取消</button><button type="button" className="button" onClick={() => perform(confirm === 'logout' ? 'officialLogout' : 'officialImportGuest')}>{confirm === 'logout' ? '确认退出' : '确认导入'}</button></div></div>}
    </>}
    {account?.web?.available && (passportIn || !webIn) && <div className="account-web">
      <h3 className="account-web-title">扫码登录</h3>
      {!account.web.authenticated && <>
        {qrImage && <div className="account-qr"><img src={qrImage} alt="红果官网登录二维码" /></div>}
        {webPhase === 'scanned' && <p className="account-note">已扫描，请在手机红果 App 中确认登录。</p>}
        {webPhase === 'qrcode' && qrImage && <p className="account-note">等待扫码（{account.web.expiresIn} 秒后过期）…</p>}
        {qrError && <p className="account-error" role="alert">{qrError}</p>}
        <div className="account-actions">
          {qrImage
            ? <button type="button" className="button" disabled={!!busy} onClick={cancelQr}>取消扫码</button>
            : <button ref={qrButton} type="button" className="button primary" disabled={!!busy} onClick={startQr}>获取二维码</button>}
        </div>
      </>}
    </div>}
    <div className="account-actions"><button ref={refreshButton} type="button" className="button" disabled={!!action} onClick={refresh}>重新读取状态</button></div>
  </section>;
}
