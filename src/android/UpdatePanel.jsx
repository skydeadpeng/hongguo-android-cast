import React, { useEffect, useRef, useState } from 'react';
import { updateCall, updateProgress } from './update-bridge.mjs';

export default function UpdatePanel() {
  const [value, setValue] = useState(null), [error, setError] = useState(''), [sending, setSending] = useState(false);
  const mounted = useRef(false), action = useRef(false), actionFocus = useRef(false), installButton = useRef(null), checkButton = useRef(null), downloadButton = useRef(null), cancelButton = useRef(null), section = useRef(null);
  const receive = next => { if (mounted.current && next && typeof next.state === 'string') setValue(next); };
  useEffect(() => {
    mounted.current = true;
    const refresh = () => updateCall('status').then(receive).catch(e => { if (mounted.current) setError(e.message); });
    const changed = event => receive(event.detail);
    window.addEventListener('androidupdateschanged', changed); window.addEventListener('androidresume', refresh); refresh();
    return () => { mounted.current = false; window.removeEventListener('androidupdateschanged', changed); window.removeEventListener('androidresume', refresh); };
  }, []);
  useEffect(() => {
    if (!actionFocus.current) return;
    const active = document.activeElement;
    if (active !== document.body && !section.current?.contains(active)) { actionFocus.current = false; return; }
    const candidates = value?.state === 'ready' ? [installButton, checkButton]
      : ['checking', 'downloading', 'verifying'].includes(value?.state) ? [cancelButton, checkButton]
      : ['available', 'error'].includes(value?.state) ? [downloadButton, checkButton] : [checkButton];
    const target = candidates.map(ref => ref.current).find(button => button && !button.disabled);
    target?.focus({ preventScroll: true });
  }, [value?.state, sending]);
  const perform = async method => {
    if (action.current) return; action.current = true; actionFocus.current = true; setSending(true); setError('');
    try { receive(await updateCall(method)); }
    catch (e) { if (mounted.current) setError(e.message || '更新未完成，请重试'); }
    finally { action.current = false; if (mounted.current) setSending(false); }
  };
  const busy = sending || value?.busy;
  const canDownload = value?.versionCode > value?.installedCode && ['available', 'error'].includes(value?.state);
  const canInstall = value?.state === 'ready';
  return <section ref={section} className="about-section" aria-labelledby="updates-title" aria-busy={!!busy}>
    <h2 id="updates-title">版本更新</h2>
    <p className="account-note">当前版本 {value?.installedVersion || '读取中…'}{value?.flavor === 'tv' ? ' · TV版' : value?.flavor === 'phone' ? ' · 手机版' : ''}</p>
    <p role="status" aria-live="polite">{value?.message || '正在读取更新状态…'}</p>
    {error && <p className="account-error" role="alert">{error}</p>}
    {value?.versionName && value.versionCode > value.installedCode && <p className="account-note">新版本 {value.versionName}{value.notes ? `：${value.notes}` : ''}</p>}
    {['downloading', 'verifying'].includes(value?.state) && <div><progress aria-label="更新下载进度" max="100" value={updateProgress(value)} /><span> {updateProgress(value)}%</span></div>}
    <div className="account-actions">
      <button ref={checkButton} type="button" className="button" disabled={!!busy || value?.state === 'unsupported'} onClick={() => perform('check')}>检查更新</button>
      {canDownload && <button ref={downloadButton} type="button" className="button primary" disabled={!!busy} onClick={() => perform('download')}>下载更新</button>}
      {canInstall && <button ref={installButton} type="button" className="button primary" disabled={!!busy} onClick={() => perform('install')}>安装更新</button>}
      {busy && ['checking', 'downloading', 'verifying'].includes(value?.state) && <button ref={cancelButton} type="button" className="button" disabled={sending} onClick={() => perform('cancel')}>取消</button>}
    </div>
    <p className="account-note">每天自动检查一次，不打断播放；下载需确认，安装由系统确认。手机与TV使用各自的更新包。检查和下载自动尝试内置加速线路，失败后回退 GitHub；每条线路都校验完整安装包。</p>
  </section>;
}
