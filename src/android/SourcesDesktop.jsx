import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronRight, Film, Search } from '../components/icons.jsx';
import './sources-desktop.css';

const failureText = error => error?.message?.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '').replace(/^\[[A-Z][A-Z0-9_]{0,63}\]\s+/, '') || '请求未完成，请重试。';
const failureCode = error => error?.code || /\[([A-Z][A-Z0-9_]{0,63})\]/.exec(error?.message || '')?.[1];
const supports = (source, capability) => Array.isArray(source.capabilities) && source.capabilities.includes(capability);
const safeCaptchaImage = value => typeof value === 'string' && /^data:image\/(?:png|jpe?g|webp|gif);base64,[A-Za-z0-9+/=\r\n]+$/i.test(value) ? value : '';

function CaptchaSlider({ captcha, busy, onComplete }) {
  const [offset, setOffset] = useState(0);
  const gesture = useRef(null);
  const frame = useRef(null);
  const [availableWidth, setAvailableWidth] = useState(300);
  const validDimensions = ['backgroundWidth', 'backgroundHeight', 'templateWidth', 'templateHeight'].every(key => Number.isInteger(captcha[key]) && captcha[key] > 0 && captcha[key] <= 4000);
  const maximumWidth = validDimensions ? Math.min(300, captcha.backgroundWidth) : 300;
  const width = Math.min(maximumWidth, availableWidth);
  const scale = width / captcha.backgroundWidth;
  const height = captcha.backgroundHeight * scale;
  const templateWidth = captcha.templateWidth * scale;
  const templateHeight = captcha.templateHeight * scale;
  const limit = Math.max(0, Math.floor(width - templateWidth));
  const background = safeCaptchaImage(captcha.backgroundImage);
  const template = safeCaptchaImage(captcha.templateImage);
  useEffect(() => {
    const node = frame.current;
    if (!node) return undefined;
    const measure = () => setAvailableWidth(Math.max(1, Math.floor(node.getBoundingClientRect().width)));
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  useEffect(() => { gesture.current = null; setOffset(0); }, [width]);
  const resetGesture = () => { gesture.current = null; setOffset(0); };
  const record = (x, y, type) => {
    const current = gesture.current;
    if (!current) return;
    const next = Math.round(Math.max(0, Math.min(limit, x)));
    current.offset = next;
    if (current.points.length < 4999 || type === 'up') current.points.push({ x: next, y: Math.round(y), type, t: Math.max(0, Date.now() - current.startTime) });
    setOffset(next);
  };
  const begin = (x, y, keyboard = false, pointerId) => {
    if (busy || gesture.current) return;
    gesture.current = { originX: x, originY: y, keyboard, pointerId, startTime: Date.now(), points: [], offset: 0 };
    record(0, 0, 'down');
  };
  const finish = () => {
    const current = gesture.current;
    if (!current || busy) return;
    record(current.offset, current.keyboard ? 0 : current.lastY || 0, 'up');
    gesture.current = null;
    if (current.offset < 2 || current.points.length < 2) { setOffset(0); return; }
    onComplete({ bgImageWidth: Math.round(width), bgImageHeight: Math.round(height), templateImageWidth: Math.round(templateWidth), templateImageHeight: Math.round(templateHeight), startTime: current.startTime, stopTime: Date.now(), trackList: current.points });
  };
  if (!validDimensions || !background || !template || !Number.isFinite(limit) || !width || !height) return <p className="text-error" role="alert">验证码图片无效，请重新获取。</p>;
  return <div className="sources-captcha" ref={frame} style={{ width: '100%', maxWidth: maximumWidth }}>
    <div className="sources-captcha-image" style={{ width, height }} aria-hidden="true">
      <img src={background} alt="" draggable="false" />
      <img className="sources-captcha-piece" src={template} alt="" draggable="false" style={{ left: offset, width: templateWidth, height: templateHeight }} />
    </div>
    <p id="sources-captcha-help">拖动滑块完成拼图。键盘可用左右键移动，Enter 确认。</p>
    <div className="sources-captcha-slider" role="slider" aria-label="拼图验证" aria-describedby="sources-captcha-help" aria-valuemin={0} aria-valuemax={limit} aria-valuenow={offset} aria-disabled={busy} tabIndex={busy ? -1 : 0}
      onPointerDown={event => { if (busy || event.button !== 0) return; event.preventDefault(); if (gesture.current) return; event.currentTarget.setPointerCapture(event.pointerId); begin(event.clientX, event.clientY, false, event.pointerId); }}
      onPointerMove={event => { const current = gesture.current; if (!current || current.keyboard || current.pointerId !== event.pointerId) return; current.lastY = event.clientY - current.originY; record(event.clientX - current.originX, current.lastY, 'move'); }}
      onPointerUp={event => { const current = gesture.current; if (!current || current.keyboard || current.pointerId !== event.pointerId) return; current.lastY = event.clientY - current.originY; record(event.clientX - current.originX, current.lastY, 'move'); finish(); if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId); }}
      onPointerCancel={resetGesture}
      onLostPointerCapture={() => { if (gesture.current && !gesture.current.keyboard) resetGesture(); }}
      onBlur={resetGesture}
      onKeyDown={event => {
        if (busy) return;
        if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); if (!gesture.current) begin(0, 0, true); if (gesture.current?.keyboard) record(gesture.current.offset + (event.key === 'ArrowRight' ? 4 : -4), 0, 'move'); }
        else if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); if (gesture.current?.keyboard) finish(); }
        else if (event.key === 'Escape') { gesture.current = null; setOffset(0); }
      }}><span style={{ left: offset }}>↔</span></div>
  </div>;
}

const noSavedCredentials = { available: false, remembered: false, identifier: '' };
const savedCredentialsStatus = value => {
  if (value?.available !== true) return noSavedCredentials;
  const identifier = value.remembered === true && typeof value.identifier === 'string' && value.identifier.length <= 128 ? value.identifier.trim() : '';
  return { available: true, remembered: Boolean(identifier), identifier };
};

function SourceAccount({ api, sourceId, name, refreshKey }) {
  const [open, setOpen] = useState(false);
  const [status, setStatus] = useState(null);
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [captcha, setCaptcha] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(noSavedCredentials);
  const [rememberCredentials, setRememberCredentials] = useState(false);
  const [credentialWriteFailed, setCredentialWriteFailed] = useState(false);
  const pending = useRef(false);
  const epoch = useRef(0);
  const savedRead = useRef(0);
  useEffect(() => () => { epoch.current++; }, []);
  const refreshSaved = useCallback(async () => {
    if (sourceId !== 'sorani') return;
    const current = epoch.current, read = ++savedRead.current;
    let next = noSavedCredentials;
    try { next = savedCredentialsStatus(await api.account({ sourceId, command: 'savedStatus' })); }
    catch { /* Older or isolated bridges may not support local storage. */ }
    if (current !== epoch.current || read !== savedRead.current) return;
    setSaved(next);
    if (next.remembered) setIdentifier(previous => previous || next.identifier);
  }, [api, sourceId]);
  const perform = useCallback(async (command, payload = {}) => {
    if (pending.current) return null;
    pending.current = true;
    const current = epoch.current;
    setBusy(true); setError('');
    try {
      const value = await api.account({ sourceId, command, payload });
      if (current !== epoch.current) return null;
      if (command === 'captcha') setCaptcha(value);
      else if (command === 'forgetSaved') {
        const next = savedCredentialsStatus(value);
        if (next.remembered) throw new Error('本机已保存的账号和密码未清除，请重试。');
        setSaved(next);
      } else {
        if (typeof value?.loggedIn !== 'boolean') throw new Error('登录状态返回无效，请重新读取。');
        setStatus({ loggedIn: value.loggedIn, name: typeof value.name === 'string' ? value.name : '' });
      }
      return value;
    } catch (failure) { if (current === epoch.current) setError(failureText(failure)); return null; }
    finally { if (current === epoch.current) { pending.current = false; setBusy(false); } }
  }, [api, sourceId]);
  const previousRefresh = useRef(refreshKey);
  useEffect(() => {
    if (refreshKey === previousRefresh.current) return;
    previousRefresh.current = refreshKey;
    if (open && !pending.current) perform('');
  }, [refreshKey, open, perform]);
  const cancel = () => {
    epoch.current++;
    const wasPending = pending.current;
    pending.current = false;
    setBusy(false); setError(''); setPassword(''); setCaptcha(null); setRememberCredentials(false); setCredentialWriteFailed(false);
    if (wasPending) Promise.resolve(api.cancel?.({ sourceId })).catch(() => {});
  };
  const close = () => { cancel(); setOpen(false); };
  const useSavedCredentials = sourceId === 'sorani' && saved.remembered && !password && identifier.trim() === saved.identifier;
  const forgetSaved = async () => {
    if (pending.current || !saved.remembered) return;
    const current = epoch.current;
    savedRead.current++;
    const result = await perform('forgetSaved');
    if (!result || current !== epoch.current) return;
    setIdentifier(previous => previous.trim() === saved.identifier ? '' : previous);
    setPassword(''); setCaptcha(null); setRememberCredentials(false); setCredentialWriteFailed(false);
  };
  const loadCaptcha = event => {
    event?.preventDefault();
    if (!identifier.trim() || (!password && !useSavedCredentials)) { setError('请输入用户名或邮箱和密码。'); return; }
    setCaptcha(null);
    perform('captcha');
  };
  const submitTrack = async track => {
    if (!captcha || pending.current) return;
    const current = epoch.current;
    const payload = { identifier: identifier.trim(), password, captchaId: captcha.id, track,
      ...(sourceId === 'sorani' ? { useSavedCredentials, rememberCredentials: saved.available && rememberCredentials && !!password } : {}) };
    setPassword('');
    const result = await perform('login', payload);
    if (current !== epoch.current) return;
    setCaptcha(null);
    if (result?.loggedIn) {
      setIdentifier(''); setRememberCredentials(false);
      setCredentialWriteFailed(result.credentialWriteFailed === true);
      if (sourceId === 'sorani' && result.savedCredentials) {
        const next = savedCredentialsStatus(result.savedCredentials);
        savedRead.current++;
        setSaved(next);
        if (next.remembered) setIdentifier(next.identifier);
      } else void refreshSaved();
    }
  };
  return <section className="sources-account" aria-label={`${name}账号`}>
    <button className="button" type="button" aria-expanded={open} onClick={() => { if (open) close(); else { setOpen(true); perform(''); void refreshSaved(); } }}>{open ? '收起来源账号' : '来源账号'}</button>
    {open && <div className="sources-account-content">
      {busy && <div className="sources-account-pending"><p role="status">正在处理账号请求…</p><button className="button" type="button" onClick={cancel}>取消账号请求</button></div>}
      {error && <p className="text-error" role="alert">{error}</p>}
      {credentialWriteFailed && <p role="status">已登录，但本机未能加密保存此次输入的账号和密码。</p>}
      {!busy && <button className="button sources-account-refresh" type="button" onClick={() => perform('')}>{status ? '刷新登录状态' : '重新读取登录状态'}</button>}
      {saved.remembered && <div className="sources-form-actions"><p>此来源的账号和密码已在本机加密保存。退出登录不会清除。</p><button className="button" type="button" disabled={busy || !!captcha} onClick={forgetSaved}>清除已保存的账号和密码</button></div>}
      {status?.loggedIn ? <div className="sources-account-status"><p>已登录：{status.name || name}</p><button className="button" type="button" disabled={busy} onClick={() => { setPassword(''); setCaptcha(null); setCredentialWriteFailed(false); if (saved.remembered) setIdentifier(saved.identifier); perform('logout'); }}>退出登录</button></div> : status && <form onSubmit={loadCaptcha} className="sources-login">
        <p>登录信息由当前来源独立保存。{saved.available ? '只有勾选记住并成功登录后才会在本机加密保存密码。' : '本次不保存密码。'}</p>
        <label htmlFor="sources-account-identifier">用户名或邮箱<input id="sources-account-identifier" value={identifier} onChange={event => setIdentifier(event.target.value)} autoComplete="username" maxLength={128} required disabled={busy || !!captcha} /></label>
        <label htmlFor="sources-account-password">密码<input id="sources-account-password" type="password" value={password} onChange={event => setPassword(event.target.value)} autoComplete="off" maxLength={128} required={!useSavedCredentials} placeholder={useSavedCredentials ? '留空使用本机已保存的密码' : undefined} disabled={busy || !!captcha} /></label>
        {saved.available && <div><label htmlFor="sources-account-remember"><input id="sources-account-remember" type="checkbox" checked={rememberCredentials} onChange={event => setRememberCredentials(event.target.checked)} disabled={busy || !!captcha} style={{ width: 'auto', minHeight: 0, marginInlineEnd: 8 }} />记住本次输入的账号和密码</label></div>}
        {!captcha && <button className="button" type="submit" disabled={busy}>获取登录验证码</button>}
        {captcha && <><CaptchaSlider key={captcha.id} captcha={captcha} busy={busy} onComplete={submitTrack} /><div className="sources-form-actions"><button className="button" type="button" disabled={busy} onClick={loadCaptcha}>换一张</button><button className="button" type="button" disabled={busy} onClick={() => { setCaptcha(null); setPassword(''); }}>返回填写</button></div></>}
      </form>}
    </div>}
  </section>;
}

function AndroidSourceCover({ url }) {
  const [broken, setBroken] = useState(false);
  return <span className="detail-cover">{url && !broken ?
    <img src={url} alt="" referrerPolicy="no-referrer" onError={() => setBroken(true)} /> : <Film size={28} aria-hidden="true" />
  }</span>;
}

function SourceBrowser({ api, source, PosterGrid, Status, onOpenHongguo, android = false }) {
  const sourceId = source.id;
  const [categories, setCategories] = useState({ busy: true, items: [], error: '' });
  const [category, setCategory] = useState(null);
  const [keyword, setKeyword] = useState('');
  const [view, setView] = useState(null);
  const [list, setList] = useState({ busy: false, items: [], hasMore: false, error: '', warning: '' });
  const [selected, setSelected] = useState(null);
  const [descriptionOpen, setDescriptionOpen] = useState(false);
  const [accountRevision, setAccountRevision] = useState(0);
  const [detail, setDetail] = useState({ busy: false, data: null, error: '' });
  const [play, setPlay] = useState({ episodeId: null, error: '', variants: [], variant: '' });
  const categoryEpoch = useRef(0);
  const detailEpoch = useRef(0);
  const playEpoch = useRef(0);
  const playPending = useRef(false);
  const lastPoster = useRef(null);
  const loadCategories = useCallback(async () => {
    const epoch = ++categoryEpoch.current;
    setCategories(previous => ({ ...previous, busy: true, error: '' }));
    try {
      const items = await api.categories({ sourceId });
      if (epoch !== categoryEpoch.current) return;
      if (!Array.isArray(items)) throw new Error('来源分类返回无效。');
      setCategories({ busy: false, items, error: '' });
      const initialCategory = items[0]?.id ?? (android && source.allowEmptyCatalog ? '' : null);
      setCategory(initialCategory);
      setView(previous => previous?.query ? previous : initialCategory !== null ? { category: initialCategory, query: '', page: 1 } : null);
    } catch (error) { if (epoch === categoryEpoch.current) setCategories(previous => ({ ...previous, busy: false, error: failureText(error) })); }
  }, [api, sourceId, android, source.allowEmptyCatalog]);
  useEffect(() => {
    loadCategories();
    return () => { categoryEpoch.current++; detailEpoch.current++; playEpoch.current++; Promise.resolve(api.cancel?.({ sourceId })).catch(() => {}); };
  }, [loadCategories, api, sourceId]);
  useEffect(() => {
    if (!view) return undefined;
    let current = true;
    const append = view.page > 1;
    setList(previous => ({ view, busy: true, items: append ? previous.items : [], hasMore: append && previous.hasMore, error: '', warning: '' }));
    const request = view.query ? api.search({ sourceId, keyword: view.query, page: view.page }) : api.catalog({ sourceId, category: view.category, page: view.page });
    Promise.resolve(request).then(result => {
      if (!current) return;
      if (!Array.isArray(result?.items)) throw new Error('来源列表返回无效。');
      setList(previous => {
        const unique = new Map((append ? previous.items : []).map(item => [item.seriesId, item]));
        for (const item of result.items) unique.set(item.seriesId, item);
        return { view, busy: false, items: [...unique.values()], hasMore: result.hasMore === true, warning: result.warning || '', error: '' };
      });
    }).catch(error => { if (current) setList(previous => ({ ...previous, busy: false, error: failureText(error) })); });
    return () => { current = false; };
  }, [api, sourceId, view]);
  useEffect(() => {
    if (!selected) return undefined;
    const epoch = ++detailEpoch.current;
    setDetail({ busy: true, data: null, error: '' });
    api.detail({ sourceId, seriesId: selected.seriesId }).then(data => {
      if (epoch !== detailEpoch.current) return;
      if (!data || !Array.isArray(data.episodes) || String(data.seriesId) !== String(selected.seriesId)) throw new Error('来源详情返回无效。');
      setDetail({ busy: false, data, error: '' });
    }).catch(error => { if (epoch === detailEpoch.current) setDetail({ busy: false, data: null, error: failureText(error) }); });
    return () => { detailEpoch.current++; };
  }, [api, sourceId, selected]);
  const closeDetail = useCallback(() => {
    detailEpoch.current++; playEpoch.current++; playPending.current = false;
    Promise.resolve(api.cancel?.({ sourceId })).catch(() => {});
    setSelected(null);
    requestAnimationFrame(() => lastPoster.current?.isConnected && lastPoster.current.focus());
  }, [api, sourceId]);
  useEffect(() => {
    const back = event => { if (selected) { event.preventDefault(); closeDetail(); } };
    window.addEventListener('desktopsourcesback', back);
    return () => window.removeEventListener('desktopsourcesback', back);
  }, [selected, closeDetail]);
  const openDetail = item => {
    if (sourceId === 'hongguo' && onOpenHongguo) { onOpenHongguo(item); return; }
    lastPoster.current = document.activeElement;
    if (android) setDescriptionOpen(false);
    playEpoch.current++; playPending.current = false;
    setDetail({ busy: true, data: null, error: '' });
    setPlay({ episodeId: null, error: '', variants: [], variant: '' });
    setSelected(item);
  };
  const playEpisode = async (episode, variant = play.variant) => {
    if (!detail.data || episode.playable !== true || playPending.current) return;
    const hasPublicLines = detail.data.episodes.some(item => item.lineOptions?.length);
    // Android's resolved variations belong to the episode that offered them.
    if (android && !hasPublicLines && play.lastEpisodeId !== episode.episodeId) variant = '';
    if (variant && hasPublicLines && !episode.lineOptions?.some(option => option.id === variant)) {
      setPlay(previous => ({ ...previous, error: '此线路没有当前分集，请选择其他线路。' }));
      return;
    }
    const epoch = ++playEpoch.current;
    playPending.current = true;
    setPlay(previous => ({
      ...previous, episodeId: episode.episodeId, error: '',
      ...(android ? { variant, ...(previous.lastEpisodeId !== episode.episodeId ? { lastEpisodeId: null, variants: [] } : {}) } : {}),
    }));
    try {
      const result = await api.play({ sourceId, seriesId: detail.data.seriesId, episodeId: episode.episodeId, ...(variant !== '' ? { variant } : {}) });
      if (epoch === playEpoch.current) setPlay(previous => ({ ...previous, episodeId: null, lastEpisodeId: episode.episodeId, variants: Array.isArray(result?.variants) ? result.variants : [] }));
    } catch (error) {
      if (epoch === playEpoch.current) {
        setPlay(previous => ({ ...previous, episodeId: null, error: `第 ${episode.index} 集播放失败：${failureText(error)}` }));
        if (failureCode(error) === 'LOGIN_REQUIRED' || /需要登录|登录已失效|请[^。]*登录/.test(failureText(error))) setAccountRevision(value => value + 1);
      }
    }
    finally { if (epoch === playEpoch.current) playPending.current = false; }
  };
  const loadCatalog = id => { setCategory(id); setKeyword(''); setView({ category: id, query: '', page: 1 }); };
  const search = event => {
    event.preventDefault();
    const query = keyword.trim();
    if (query) setView({ category, query, page: 1 });
    else if (category !== null) loadCatalog(category);
    else setView(null);
  };
  const visibleList = list.view?.category === view?.category && list.view?.query === view?.query ? list : { busy: true, items: [] };
  const publicLines = [...new Map((detail.data?.episodes || []).flatMap(episode => episode.lineOptions || []).map(option => [option.id, option])).values()];
  const lineChoices = publicLines.length ? publicLines : play.variants;
  const description = detail.data?.description || '暂无简介';
  const canFoldDescription = android && typeof description === 'string' && (description.length > 58 || description.split('\n').length > 3);
  const activeCategory = android ? categories.items.find(item => item.id === view?.category) : null;
  return <div className="sources-browser">
    {android && !selected && <h2 className="source-catalog-title">{source.name}</h2>}
    {supports(source, 'account') && <SourceAccount key={sourceId} api={api} sourceId={sourceId} name={source.name} refreshKey={accountRevision} />}
    <div hidden={!!selected}>
      {supports(source, 'search') && <form className="search-form" onSubmit={search}>
        <label htmlFor="desktop-source-query">搜索{source.name}</label>
        <div className="search-controls"><input id="desktop-source-query" type="search" value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="输入片名" maxLength={200} /><button type="submit" className={android ? "button primary" : "button"}><Search size={18} />搜索</button>{!!view?.query && <button type="button" className="button" onClick={() => { setKeyword(''); if (category !== null) loadCatalog(category); else setView(null); }}>清除</button>}</div>
      </form>}
      {!view?.query && <Status busy={categories.busy} error={categories.error} errorTitle={android ? '分类读取失败' : undefined} onRetry={loadCategories}>
        {categories.items.length ? <div className="category-tabs" role="group" aria-label={`${source.name}分类`}>{categories.items.map(item => <button type="button" key={item.id} aria-pressed={category === item.id} onClick={() => loadCatalog(item.id)}>{item.label || item.name}</button>)}</div> : <p className="sources-note">{android && source.allowEmptyCatalog ? '此来源未返回分类，显示全部片单。' : supports(source, 'search') ? '此来源未返回分类，请使用搜索查找影片。' : '此来源暂未返回分类。'}</p>}
      </Status>}
      {view && <>
        {android && <header className="source-results-heading">
          <h3>{view.query ? '搜索结果' : activeCategory?.label || activeCategory?.name || '全部片单'}</h3>
          {view.query && <p className="result-caption">关键词：{view.query}</p>}
        </header>}
        {!android && view.query && <p className="result-caption">「{view.query}」的搜索结果 · {source.name}</p>}
        {visibleList.warning && <p className="sources-warning" role="status">{visibleList.warning}</p>}
        <Status busy={visibleList.busy} error={visibleList.error} errorTitle={android ? (view.query ? '搜索未完成' : '片单读取失败') : undefined} preserve={visibleList.items.length > 0} onRetry={() => setView({ ...view })} empty={!visibleList.items.length && { title: '没有找到影片', text: view.query ? '换个关键词试试。' : '这个分类暂无影片。' }}>
          <PosterGrid items={visibleList.items} onOpen={openDetail} />
          {visibleList.items.length > 0 && visibleList.hasMore && !visibleList.error && <div className="feed-more"><button className="button" type="button" disabled={visibleList.busy} onClick={() => setView({ ...view, page: view.page + 1 })}>{visibleList.busy ? '正在加载…' : '加载更多'}</button></div>}
        </Status>
      </>}
    </div>
    {selected && <div className="sources-detail">
      <button type="button" className="back-button" autoFocus onClick={closeDetail}><ChevronRight />返回{source.name}</button>
      <Status busy={detail.busy} error={detail.error} errorTitle={android ? '分集读取失败' : undefined} onRetry={() => setSelected({ ...selected })}>
        {detail.data && <><header className="detail-summary">
          {android ? <AndroidSourceCover key={detail.data.coverUrl || ''} url={detail.data.coverUrl} /> : <span className="detail-cover">{detail.data.coverUrl ? <img src={detail.data.coverUrl} alt="" referrerPolicy="no-referrer" /> : <Film size={28} />}</span>}
          <div className="detail-copy">
            <h1>{detail.data.title}</h1><p className="detail-meta">{detail.data.episodeCount || detail.data.episodes.length} 集 · {source.name}</p>
            <p id={canFoldDescription ? "source-description" : undefined} className={canFoldDescription && !descriptionOpen ? "detail-description source-description-folded" : "detail-description"}>{description}</p>
            {canFoldDescription && <button className="description-toggle" type="button" aria-controls="source-description" aria-expanded={descriptionOpen} onClick={() => setDescriptionOpen(open => !open)}>{descriptionOpen ? '收起简介' : '展开简介'}</button>}
          </div>
        </header>
          <section className="episode-section" aria-label="选集"><header><h2>选集</h2><p>{android && detail.data.episodes.some(episode => !episode.playable) ? '部分分集暂不可播' : '在线播放'}</p></header>
            {!!play.episodeId && <p className="episode-note" role="status">{android ? "正在读取分集片源，随后打开播放器…" : "正在打开播放器…"}</p>}
            {play.error && <p className="text-error episode-note" role="alert">{play.error}</p>}
            {lineChoices.length > 1 && <div className="sources-variants"><label htmlFor="desktop-source-variant">播放线路<select id="desktop-source-variant" value={play.variant} disabled={!!play.episodeId} onChange={event => setPlay(previous => ({ ...previous, variant: event.target.value }))}><option value="">{publicLines.length ? '站点默认线路' : '自动'}</option>{lineChoices.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}</select></label>{play.lastEpisodeId && <button type="button" className="button" disabled={!!play.episodeId} onClick={() => { const episode = detail.data.episodes.find(item => item.episodeId === play.lastEpisodeId); if (episode) playEpisode(episode); }}>切换线路并播放</button>}</div>}
            {detail.data.episodes.length ? <div className="episode-grid">{detail.data.episodes.map(episode => <button type="button" key={episode.episodeId} title={episode.title || `第 ${episode.index} 集`} aria-label={`${episode.title || `第 ${episode.index} 集`}${episode.playable ? '' : '（暂不可播）'}`} disabled={episode.playable !== true || !!play.episodeId} onClick={() => playEpisode(episode)}>{episode.index}</button>)}</div> : <p className="empty-episodes">来源未返回分集。</p>}
          </section></>}
      </Status>
    </div>}
  </div>;
}

export function SourcesDesktop({ api, PosterGrid, Status, onOpenHongguo, onOpenNativeHongguo, sourceButtons = false }) {
  const [sources, setSources] = useState({ busy: true, items: [], error: '' });
  const [selectedId, setSelectedId] = useState('');
  const [managing, setManaging] = useState(false);
  const [updating, setUpdating] = useState('');
  const epoch = useRef(0);
  const pending = useRef(false);
  const load = useCallback(async () => {
    const current = ++epoch.current;
    setSources(previous => ({ ...previous, busy: true, error: '' }));
    try {
      const items = await api.list();
      if (current !== epoch.current) return;
      if (!Array.isArray(items)) throw new Error('来源列表返回无效。');
      setSources({ busy: false, items: items.filter(Boolean), error: '' });
    } catch (error) { if (current === epoch.current) setSources(previous => ({ ...previous, busy: false, error: failureText(error) })); }
  }, [api]);
  useEffect(() => { load(); return () => { epoch.current++; }; }, [load]);
  const update = async source => {
    if (pending.current) return;
    pending.current = true;
    const current = epoch.current;
    setUpdating(source.id);
    try {
      const items = await api.settings({ sourceId: source.id, enabled: !source.enabled });
      if (current !== epoch.current) return;
      if (!Array.isArray(items)) throw new Error('来源设置返回无效。');
      setSources({ busy: false, items: items.filter(Boolean), error: '' });
    } catch (error) { if (current === epoch.current) setSources(previous => ({ ...previous, error: failureText(error) })); }
    finally { pending.current = false; if (current === epoch.current) setUpdating(''); }
  };
  const selected = sources.items.find(item => item.id === selectedId);
  const builtIn = item => sourceButtons && item?.nativePlayback === true;
  const activeChoice = useRef(null);
  useEffect(() => {
    // In the phone layout the full directory folds into one swipable row.
    // Keep the newly selected source visible without opening a new page.
    if (sourceButtons && selected) activeChoice.current?.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
  }, [sourceButtons, selectedId]);
  const managementButton = <button type="button" className="button" aria-expanded={managing} aria-controls={sourceButtons && managing ? "android-source-management" : undefined} onClick={() => setManaging(value => !value)}>{managing ? '收起来源管理' : '管理来源'}</button>;
  return <section className={sourceButtons ? "desktop-sources android-source-page" : "desktop-sources"} aria-label="影视来源">
    <header className="page-heading"><h1>影视来源</h1><p>{sourceButtons ? '浏览各来源的片单，也可以搜索片名。' : '选择来源后浏览分类或搜索，使用统一播放器在线播放。'}</p></header>
    <Status busy={sources.busy} error={sources.error} preserve={sources.items.length > 0} onRetry={load} empty={!sources.items.length && { title: '暂无已接入来源', text: '请重试读取来源列表。' }}>
      {sourceButtons ? <div className="sources-toolbar">
        <div className="source-directory-heading">
          <div><h2>{selected ? '切换来源' : '选择来源'}</h2><p>{sources.items.filter(item => item.enabled).length} 个已启用 · 共 {sources.items.length} 个</p></div>
          {managementButton}
        </div>
        <div className={`category-tabs sources-choices${selected ? ' has-selection' : ''}`} role="group" aria-label="当前来源">
          {sources.items.map(item => <button type="button" key={item.id} ref={selectedId === item.id ? activeChoice : undefined} aria-pressed={selectedId === item.id} onClick={() => setSelectedId(item.id)}>
            <strong>{item.name}</strong>{(item.adult === true || !item.enabled) && <span className="source-choice-state">{item.adult === true ? ' · 限制级' : ''}{item.enabled ? '' : '（未启用）'}</span>}
          </button>)}
        </div>
      </div> : <div className="sources-toolbar"><label htmlFor="desktop-source-select">当前来源<select id="desktop-source-select" value={selectedId} onChange={event => setSelectedId(event.target.value)}><option value="">请选择来源</option>{sources.items.map(item => <option key={item.id} value={item.id}>{item.name}{item.adult === true ? ' · 限制级' : ''}{item.enabled ? '' : '（未启用）'}</option>)}</select></label>{managementButton}</div>}
      {managing && <section id={sourceButtons ? "android-source-management" : undefined} className="sources-management" aria-label="来源管理"><h2>来源管理</h2>{sources.items.map(item => <div className="playback-setting" key={item.id}><div><strong>{item.name}</strong><p>{item.adult === true ? '限制级 · ' : ''}{item.description || '分类、搜索与在线播放'}{item.version ? ` · v${item.version}` : ''}</p></div>{builtIn(item) ? <span>内置</span> : <button type="button" className="setting-switch" role="switch" aria-label={`启用${item.name}`} aria-checked={item.enabled === true} disabled={!!updating} onClick={() => update(item)}>{updating === item.id ? '处理中…' : item.enabled ? '开启' : '关闭'}</button>}</div>)}</section>}
      {selected ? builtIn(selected) ? <div className="state-panel"><h2>红果</h2><p>使用现有的红果发现、搜索和播放。</p><button type="button" className={sourceButtons ? "button primary" : "button"} onClick={onOpenNativeHongguo}>打开红果发现页</button></div> : selected.enabled ? <SourceBrowser key={selected.id} api={api} source={selected} PosterGrid={PosterGrid} Status={Status} onOpenHongguo={onOpenHongguo} android={sourceButtons} /> : <div className="state-panel"><h2>{selected.name}未启用</h2><p>{selected.adult === true ? '此来源包含限制级内容，单独启用后才会请求内容。' : '启用后才会向该来源请求内容。'}</p><button className="button" type="button" disabled={!!updating} onClick={() => update(selected)}>启用{selected.name}</button></div> : !sourceButtons && <p className="sources-note">请选择一个来源开始浏览。</p>}
    </Status>
  </section>;
}
