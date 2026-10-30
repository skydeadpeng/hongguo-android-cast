import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Home, Search, Bookmark, Download, Settings, Play, Heart, Clock, ChevronRight, ExternalLink, RefreshCw, Film, Radio } from '../components/icons.jsx';
import { getPlaybackPreferences, nativeCall, requestHtml, setPlaybackPreference } from './bridge.mjs';
import { CATEGORIES, createSource } from './source.mjs';
import { createSeasonLookup, parseSeasonTitle, seasonCandidates } from './seasons.mjs';
import { latestSeasonHistory, assertPlaybackRequestSize } from './season-playback.mjs';
import { fetchAppPlayUrl } from './generated/mac-app-source.mjs';
import gplLicense from '../../LICENSE?raw';
import AccountPanel from './AccountPanel.jsx';
import UpdatePanel from './UpdatePanel.jsx';
import { createOfficialSeasonLookup, requestOfficialSeries } from './official-series.mjs';

const source = createSource(requestHtml, { fallback: fetchAppPlayUrl,
  searchPage: params => nativeCall('searchQuery', params, 60000) });
const seasonLookup = createOfficialSeasonLookup(requestOfficialSeries, createSeasonLookup((keyword, page) => source.search(keyword, page)));
const EMPTY_LIBRARY = { favorites: [], history: [] };
const NAV = [
  { id: 'discover', label: '发现', Icon: Home },
  { id: 'search', label: '搜索', Icon: Search },
  { id: 'library', label: '我的追剧', Icon: Bookmark },
  { id: 'downloads', label: '下载', Icon: Download },
  { id: 'about', label: '关于', Icon: Settings },
];
const errorText = (error) => error?.message || String(error || '请求未完成，请重试。');
// Keep history RPCs ordered across SearchPage unmount/remount. A late old write
// must finish before a newly mounted page reads the persisted native history.
let searchHistoryQueue = Promise.resolve();
const SEARCH_HISTORY_METHODS = new Set(['getSearchHistory', 'addSearchHistory', 'removeSearchHistory', 'clearSearchHistory']);
function requestSearchHistory(method, keyword) {
  if (!SEARCH_HISTORY_METHODS.has(method)) return Promise.reject(new Error('搜索历史操作无效'));
  const request = searchHistoryQueue.then(async () => {
    const result = await nativeCall(method, keyword === undefined ? {} : { keyword });
    const values = result?.keywords;
    if (!Array.isArray(values) || values.length > 20 || values.some((value) => typeof value !== 'string' || !value.length || value.length > 100 || value.trim() !== value) || new Set(values).size !== values.length) throw new Error('设备返回的搜索历史无效，请重新读取。');
    return values.slice();
  });
  searchHistoryQueue = request.catch(() => {});
  return request;
}
const sameSeries = (a, b) => String(a?.seriesId) === String(b?.seriesId);
const timeText = (ms) => { const s = Math.floor((Number(ms) || 0) / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };
const byteText = (bytes) => { const n = Number(bytes) || 0; return n >= 1024 ** 2 ? `${(n / 1024 ** 2).toFixed(1)} MB` : `${Math.round(n / 1024)} KB`; };
// Do not duplicate an entire episode list inside library entries or bridge payloads.
const seriesSyncMetadata = value => ({
  ...(Number.isSafeInteger(value?.seasonIndex) && value.seasonIndex >= 0 && value.seasonIndex <= 10000 ? { seasonIndex: value.seasonIndex } : {}),
  ...(typeof value?.multiSeason === 'boolean' ? { multiSeason: value.multiSeason } : {}),
});
const compactSeries = value => { const { seriesId, title, coverUrl, episodeCount, episodeLabel, tags } = value; return { seriesId, title, coverUrl, episodeCount, episodeLabel, tags, ...seriesSyncMetadata(value) }; };
const needsSeriesPoint = (library, series) => !!(library.projection?.historyHasMore && !library.history.some(row => sameSeries(row, series)) || library.projection?.favoritesHasMore && !library.favorites.some(row => sameSeries(row, series)));
const POINT_FAVORITE_ERROR = '当前剧的账号状态暂未读取，请重试详情后再修改追剧。';
const POINT_RESUME_ERROR = '较早的本机进度暂未读取；可以重试详情或手动选集。';
const compactEpisode = ({ vid, index, webAvailable, availability }) => ({ vid, index, webAvailable: !!webAvailable, availability: availability || (webAvailable ? 'available' : 'unknown') });

function Status({ busy, error, onRetry, empty, children, preserve = false, errorTitle = '这次没有加载成功', retryLabel = '重新加载' }) {
  if (preserve) return <>
    {busy ? <div className="state-inline" role="status"><RefreshCw size={18} /><p>正在更新，仍可查看已有内容。</p></div> : error ? <div className="state-inline error-state" role="alert"><p><strong>{errorTitle}</strong><span>{error}，仍显示上次内容。</span></p><button type="button" className="button" onClick={onRetry}><RefreshCw />{retryLabel}</button></div> : null}
    {children}
  </>;
  if (busy) return <div className="state-panel" role="status"><RefreshCw size={24} /><h2>正在读取…</h2><p>请稍候，正在获取最新内容。</p></div>;
  if (error) return <div className="state-panel error-state" role="alert"><h2>{errorTitle}</h2><p>{error}</p><button type="button" className="button" onClick={onRetry}><RefreshCw />{retryLabel}</button></div>;
  if (empty) return <div className="state-panel"><Film size={28} /><h2>{empty.title}</h2><p>{empty.text}</p></div>;
  return children;
}

function Poster({ item, onOpen, history }) {
  const [failed, setFailed] = useState(false);
  return <button className="poster" onClick={() => onOpen(item, history?.vid)} aria-label={`打开${item.title}`}>
    <span className="poster-art">
      {item.coverUrl && !failed ? <img src={item.coverUrl} alt="" loading="lazy" onError={() => setFailed(true)} /> : <span className="poster-fallback"><Film size={32} /><span>暂无封面</span></span>}
      {item.episodeLabel || item.episodeCount ? <span className="poster-label">{item.episodeLabel || `${item.episodeCount} 集`}</span> : null}
    </span>
    <strong>{item.title || '未命名短剧'}</strong>
    {history ? <span className="poster-caption">看到第 {history.index} 集 · {timeText(history.positionMs)}</span> : item.tags?.length ? <span className="poster-caption">{item.tags.slice(0, 2).join(' / ')}</span> : null}
  </button>;
}

function PosterGrid({ items, onOpen, history = false }) {
  return <div className="poster-grid">{items.map((item, index) => <Poster key={`${item.seriesId}-${index}`} item={item} onOpen={onOpen} history={history ? item : null} />)}</div>;
}

function Discover({ onOpen }) {
  const [category, setCategory] = useState(CATEGORIES[0]?.id || 'all');
  const [page, setPage] = useState(1);
  const [reload, setReload] = useState(0);
  const [state, setState] = useState({ busy: true, items: [], category, page: 1 });
  const generation = useRef(0), fetching = useRef(false), refreshCatalog = useRef(false);
  useEffect(() => {
    const current = ++generation.current;
    fetching.current = true;
    const previous = state.category === category ? state : { items: [], category, page: 1 };
    setState({ ...previous, busy: true, error: '', requestedPage: page });
    const refresh = refreshCatalog.current;
    refreshCatalog.current = false;
    Promise.resolve().then(() => source.catalog(category, page, { refresh })).then((result) => {
      if (generation.current !== current) return;
      if (!Array.isArray(result?.items)) throw new Error('来源返回的片单无效，请重试。');
      if (result.page !== undefined && result.page !== page) throw new Error('来源返回的片单页码不一致，请重试。');
      if (!result.items.length && previous.items.length) throw new Error('这次未返回片单，请重试。');
      fetching.current = false;
      setState({ ...result, category, requestedPage: page, page: result.page || page, busy: false });
    }).catch((error) => {
      if (generation.current !== current) return;
      fetching.current = false;
      setState({ ...previous, busy: false, error: errorText(error), requestedPage: page });
    });
    return () => { generation.current++; };
  }, [category, page, reload]);
  // A changed query never renders the previous query's cards or page controls,
  // including the render before its effect has started.
  const shown = state.category === category ? state : { busy: true, items: [], page: 1 };
  const visiblePage = shown.page || 1;
  const loadPage = (target, refresh = false) => {
    if (fetching.current || target < 1) return;
    fetching.current = true; generation.current++;
    refreshCatalog.current = refresh;
    setState(previous => ({ ...previous, busy: true, error: '', requestedPage: target }));
    setPage(target); setReload(value => value + 1);
  };
  const changeCategory = (value) => {
    if (value === category) return;
    generation.current++; fetching.current = true; refreshCatalog.current = false;
    setState({ busy: true, items: [], category: value, page: 1, requestedPage: 1 });
    setCategory(value); setPage(1);
  };
  return <>
    <header className="page-heading heading-actions"><div><h1>发现短剧</h1><p>按分类浏览，选择喜欢的故事。</p></div><button type="button" className="button" disabled={shown.busy} aria-label="刷新片单" onClick={() => loadPage(visiblePage, true)}><RefreshCw /><span>刷新</span></button></header>
    <div className="category-tabs" role="group" aria-label="短剧分类">{CATEGORIES.map((item) => <button key={item.id} aria-pressed={item.id === category} onClick={() => changeCategory(item.id)}>{item.label}</button>)}</div>
    <Status busy={shown.busy} error={shown.error} preserve={!!shown.items.length} onRetry={() => loadPage(shown.requestedPage || page, true)} empty={!shown.items.length && { title: '这个分类暂时没有内容', text: '试试其他分类，或稍后回来查看。' }}>
      <PosterGrid items={shown.items} onOpen={onOpen} />
    </Status>
    {(!!shown.items.length || !shown.busy && !shown.error) && <div className="pagination" aria-label="分页"><button className="button" disabled={shown.busy || visiblePage <= 1} onClick={() => loadPage(visiblePage - 1)}>上一页</button><span>第 {visiblePage} 页{shown.totalPages ? ` / ${shown.totalPages}` : ''}{shown.busy ? ' · 读取中' : shown.error && shown.requestedPage !== visiblePage ? ` · 第 ${shown.requestedPage} 页未加载` : ''}</span><button className="button" disabled={shown.busy || !!shown.error || (shown.totalPages ? visiblePage >= shown.totalPages : !shown.items.length)} onClick={() => loadPage(visiblePage + 1)}>下一页</button></div>}
  </>;
}

function SearchPage({ onOpen }) {
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [reload, setReload] = useState(0);
  const [state, setState] = useState({ items: [], busy: false });
  const [history, setHistory] = useState({ keywords: [], busy: true, error: '' });
  const [confirmClear, setConfirmClear] = useState(false);
  const generation = useRef(0);
  const fetching = useRef(false);
  const refreshSearch = useRef(false);
  const results = useRef(null);
  const pageFocus = useRef(null);
  const historyVersion = useRef(0);
  const mounted = useRef(false);
  const input = useRef(null);
  const historyList = useRef(null);
  const clearButton = useRef(null);
  const confirmButton = useRef(null);
  const updateHistory = useCallback(async (method, value) => {
    const version = ++historyVersion.current;
    if (mounted.current) setHistory((previous) => ({ ...previous, busy: true, error: '' }));
    try {
      const keywords = await requestSearchHistory(method, value);
      if (!mounted.current) return;
      if (method === 'clearSearchHistory') setConfirmClear(false);
      if (version !== historyVersion.current) {
        // Mutations are serialized: even an older confirmed commit is the right
        // fallback if the newer queued mutation later fails. A stale read is not.
        if (method !== 'getSearchHistory') setHistory((previous) => ({ ...previous, keywords }));
        return;
      }
      setHistory({ keywords, busy: false, error: '' });
      if (method === 'clearSearchHistory' || method === 'removeSearchHistory') requestAnimationFrame(() => {
        if (!mounted.current || version !== historyVersion.current) return;
        (method === 'removeSearchHistory' ? historyList.current?.querySelector('.history-keyword') : null)?.focus();
        if (method === 'clearSearchHistory' || !keywords.length) input.current?.focus();
      });
    } catch (error) {
      if (!mounted.current || version !== historyVersion.current) return;
      const context = method === 'getSearchHistory' ? '搜索历史读取失败' : method === 'addSearchHistory' ? '搜索已提交，但历史保存失败' : method === 'removeSearchHistory' ? '删除搜索历史未成功' : '清空搜索历史未成功';
      setHistory((previous) => ({ ...previous, busy: false, error: `${context}：${errorText(error)}` }));
    }
  }, []);
  useEffect(() => {
    mounted.current = true;
    updateHistory('getSearchHistory');
    return () => { mounted.current = false; historyVersion.current++; };
  }, [updateHistory]);
  useEffect(() => { if (confirmClear) confirmButton.current?.focus(); }, [confirmClear]);
  useEffect(() => {
    const current = ++generation.current;
    if (!query) return;
    fetching.current = true;
    const previous = state.query === query ? state : { items: [], query, page: 1 };
    setState({ ...previous, busy: true, error: '', errorCode: undefined, requestedPage: page });
    const refresh = refreshSearch.current;
    refreshSearch.current = false;
    // Pager validation can reject synchronously (for example an expired cursor).
    Promise.resolve().then(() => source.search(query, page, { refresh })).then((result) => {
      if (generation.current !== current) return;
      if (!Array.isArray(result?.items)) throw new Error('来源返回的搜索结果无效，请重试。');
      if (result.page !== undefined && result.page !== page) throw new Error('来源返回的搜索页码不一致，请重试。');
      if (!result.items.length && previous.items.length) {
        // A valid empty terminal page is cached by the pager. Retrying it as a
        // transport failure would loop forever; retain the last useful page and
        // remember this boundary when returning through earlier cached pages.
        if (result.hasMore === false && page > (previous.page || 1)) {
          fetching.current = false;
          setState({ ...previous, busy: false, error: '', errorCode: undefined, hasMore: false,
            endBeforePage: Math.min(previous.endBeforePage || page, page), requestedPage: previous.page || 1,
            notice: [previous.notice, `没有更多结果，仍显示第 ${previous.page || 1} 页。`].filter(Boolean).join(' ') });
          return;
        }
        throw new Error('这次未返回搜索结果，请重试。');
      }
      fetching.current = false;
      const atKnownEnd = previous.endBeforePage && page + 1 >= previous.endBeforePage;
      setState({ ...result, query, page: result.page || page, requestedPage: page, busy: false,
        endBeforePage: previous.endBeforePage,
        ...(atKnownEnd ? { hasMore: false, notice: [result.notice, `没有更多结果，仍显示第 ${page} 页。`].filter(Boolean).join(' ') } : {}) });
    }).catch((error) => {
      if (generation.current !== current) return;
      fetching.current = false;
      setState({ ...previous, busy: false, query, requestedPage: page, error: errorText(error), errorCode: error?.code });
    });
    return () => { generation.current++; };
  }, [query, page, reload]);
  useEffect(() => {
    if (state.busy || !pageFocus.current) return;
    const previous = pageFocus.current;
    pageFocus.current = null;
    // A disabled paging button may lose focus while loading. Restore remote
    // navigation to the new results, unless the user moved elsewhere meanwhile.
    if (!results.current || results.current.closest('[hidden]') || (document.activeElement !== previous && document.activeElement !== document.body)) return;
    const next = results.current.querySelector('.poster, .error-state button, .search-pagination button:not(:disabled)');
    next?.focus();
    next?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, [state.busy]);
  const runSearch = (raw) => {
    const value = raw.trim();
    if (!value || value.length > 100) return;
    // Invalidate immediately, before effects run: an older keyword/page reply
    // must not replace a just-submitted search. Repeating a word starts at page 1.
    generation.current++;
    fetching.current = true;
    refreshSearch.current = true;
    pageFocus.current = null;
    setState(previous => previous.query === value ? { ...previous, busy: true, error: '', errorCode: undefined, endBeforePage: undefined, requestedPage: 1 } : { busy: true, items: [], query: value, page: 1, requestedPage: 1 });
    setKeyword(value); setQuery(value); setPage(1); setReload((n) => n + 1);
    updateHistory('addSearchHistory', value);
  };
  const visiblePage = state.page || 1;
  const loadPage = (nextPage, refresh = false) => {
    if (fetching.current || nextPage < 1) return;
    fetching.current = true;
    refreshSearch.current = refresh;
    generation.current++;
    pageFocus.current = results.current?.contains(document.activeElement) ? document.activeElement : null;
    setState(previous => ({ ...previous, busy: true, error: '', errorCode: undefined, requestedPage: nextPage }));
    setPage(nextPage); setReload((n) => n + 1);
  };
  const submit = (event) => { event.preventDefault(); runSearch(keyword); };
  const cancelClear = () => { setConfirmClear(false); clearButton.current?.focus(); };
  return <>
    <header className="page-heading"><h1>搜索短剧</h1><p>输入剧名，查找想看的短剧。</p></header>
    <form className="search-form" onSubmit={submit}><label htmlFor="search-keyword">短剧名称</label><div className="search-controls"><input ref={input} id="search-keyword" type="search" value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索短剧名称" maxLength={100} enterKeyHint="search" /><button className="button primary" type="submit" disabled={!keyword.trim()}><Search />搜索</button></div></form>
    <section className="search-history" aria-labelledby="search-history-title" aria-busy={history.busy}>
      <header><h2 id="search-history-title">最近搜索</h2><button ref={clearButton} className="history-clear" type="button" disabled={history.busy || !history.keywords.length} onClick={() => setConfirmClear(true)} aria-expanded={confirmClear} aria-controls="search-history-confirm">清空</button></header>
      {confirmClear && <div id="search-history-confirm" className="search-history-confirm" role="group" aria-label="确认清空搜索历史"><p>清空这台设备上的全部搜索历史？</p><div><button ref={confirmButton} className="button" type="button" disabled={history.busy} onClick={() => updateHistory('clearSearchHistory')}>{history.busy ? '正在更新…' : '确认清空'}</button><button className="button" type="button" disabled={history.busy} onClick={cancelClear}>取消</button></div></div>}
      {history.busy && <p className="history-note" role="status">正在更新搜索历史…</p>}
      {history.error && <div className="search-history-error" role="alert"><p>{history.error}</p><button type="button" className="history-clear" disabled={history.busy} onClick={() => updateHistory('getSearchHistory')}>重新读取</button></div>}
      {!!history.keywords.length && <ul ref={historyList} className="search-history-list">{history.keywords.map((value) => <li key={value}><button type="button" className="history-keyword" onClick={() => runSearch(value)} aria-label={`搜索：${value}`}>{value}</button><button type="button" className="history-remove" disabled={history.busy} onClick={() => updateHistory('removeSearchHistory', value)} aria-label={`删除搜索历史：${value}`} title={`删除“${value}”`}><span aria-hidden="true">×</span></button></li>)}</ul>}
      {!history.busy && !history.error && !history.keywords.length && <p className="history-note">提交搜索后会保留最近 20 条记录。</p>}
    </section>
    {query ? <section ref={results} aria-label="搜索结果" aria-busy={state.busy}>
      <p className="result-caption">“{query}”{!state.busy && !state.error ? state.totalKnown !== false && Number.isSafeInteger(state.total) ? ` · 共 ${state.total} 个结果` : ` · 本页 ${state.items.length} 个结果` : ''}</p>
      {!state.busy && !state.error && state.notice && <p className="result-caption" role="status">{state.notice}</p>}
      <Status busy={state.busy} error={state.error} preserve={!!state.items.length} errorTitle={state.errorCode === 'SEARCH_EXPIRED' ? '搜索已过期' : undefined} retryLabel={state.errorCode === 'SEARCH_EXPIRED' ? '重新搜索' : undefined} onRetry={() => state.errorCode === 'SEARCH_EXPIRED' ? runSearch(query) : loadPage(state.requestedPage || page, (state.requestedPage || page) === 1)} empty={!state.items.length && { title: visiblePage > 1 ? '这一页没有更多结果' : '没有找到相关短剧', text: visiblePage > 1 ? '可以返回上一页，或搜索其他关键词。' : '可以缩短名称，或换一个关键词。' }}><PosterGrid items={state.items} onOpen={onOpen} /></Status>
      <div className="pagination search-pagination" aria-label="搜索结果分页">
        <button type="button" className="button" disabled={state.busy || visiblePage <= 1} onClick={() => loadPage(visiblePage - 1)}>上一页</button>
        <span className="search-page-status" aria-live="polite">第 {visiblePage} 页{state.totalPages ? ` / ${state.totalPages}` : ''}{state.busy ? ' · 读取中' : state.error && state.requestedPage !== visiblePage ? ` · 第 ${state.requestedPage} 页未加载` : ''}</span>
        <button type="button" className="button" disabled={state.busy || !!state.error || !state.hasMore} onClick={() => loadPage(visiblePage + 1)}>下一页</button>
      </div>
    </section> : <Status empty={{ title: '找一部想看的短剧', text: '搜索结果会显示在这里。' }} />}
  </>;
}

function Library({ library, error, busy, onRetry, onOpen, onAccount }) {
  const [view, setView] = useState('favorites');
  const items = library[view] || [];
  return <>
    <header className="page-heading heading-actions"><div><h1>我的追剧</h1><p>本机保留记录；登录红果官方账号后同步收藏、历史和进度。</p></div><button type="button" className="button" onClick={onAccount}>账号</button></header>
    <div className="section-tabs" role="group" aria-label="我的内容"><button aria-pressed={view === 'favorites'} onClick={() => setView('favorites')}><Heart />追剧 ({library.favorites.length})</button><button aria-pressed={view === 'history'} onClick={() => setView('history')}><Clock />观看历史 ({library.history.length})</button></div>
    {library.projection?.[view === 'favorites' ? 'favoritesHasMore' : 'historyHasMore'] && <p className="account-note" role="status">当前展示最近 {items.length} 项；完整记录已保存在账号库，较早剧集的原生播放进度仍保留。</p>}
    <Status busy={busy} error={error} preserve={!!items.length} onRetry={onRetry} empty={!items.length && { title: view === 'favorites' ? '还没有追剧' : '还没有观看记录', text: view === 'favorites' ? '打开短剧详情，点击「加入追剧」。' : '播放短剧后，进度会自动记录。' }}><PosterGrid items={items} onOpen={onOpen} history={view === 'history'} /></Status>
  </>;
}

function Downloads({ notify }) {
  const [state, setState] = useState({ busy: true, items: [] });
  const [opening, setOpening] = useState(null);
  const [confirm, setConfirm] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const [deleteError, setDeleteError] = useState('');
  const alive = useRef(false);
  const fetching = useRef(false);
  const loaded = useRef(false);
  const queuedRefresh = useRef(false);
  const readEpoch = useRef(0);
  const operation = useRef(null);
  const deletedIds = useRef(new Set());
  const list = useRef(null);
  const refreshButton = useRef(null);
  const cancelButton = useRef(null);
  const restoreIndex = useRef(null);

  // Keep one list RPC across refreshes/mutations. Invalidating an old read does
  // not launch a replacement until that read settles, even after deletion.
  const refresh = useCallback(async function refresh(force = false) {
    if (!alive.current || document.visibilityState === 'hidden' || operation.current) return;
    if (fetching.current) { if (force) queuedRefresh.current = true; return; }
    fetching.current = true;
    const epoch = ++readEpoch.current;
    setState(previous => ({ ...previous, busy: true, error: '' }));
    try {
      const result = await nativeCall('getDownloads');
      if (alive.current && epoch === readEpoch.current) {
        setState({ busy: false, items: (Array.isArray(result) ? result : []).filter(item => !deletedIds.current.has(item.id)) });
        loaded.current = true;
      }
    } catch (error) {
      if (alive.current && epoch === readEpoch.current) setState(previous => ({ ...previous, busy: false, error: errorText(error) }));
    } finally {
      fetching.current = false;
      if (alive.current && queuedRefresh.current) { queuedRefresh.current = false; refresh(true); }
    }
  }, []);
  useEffect(() => {
    alive.current = true;
    refresh();
    const interval = setInterval(() => { if (loaded.current) refresh(); }, 2500);
    const onVisible = () => { if (document.visibilityState !== 'hidden') refresh(); };
    const onResume = () => refresh();
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('androidresume', onResume);
    return () => {
      alive.current = false; readEpoch.current++; queuedRefresh.current = false;
      clearInterval(interval); document.removeEventListener('visibilitychange', onVisible); window.removeEventListener('androidresume', onResume);
    };
  }, [refresh]);

  const cancelConfirmation = useCallback(() => {
    if (operation.current?.kind !== 'confirm') return;
    operation.current = null; setConfirm(null); setDeleteError('');
    refresh(true);
  }, [refresh]);
  useEffect(() => {
    const back = event => {
      if (!['confirm', 'delete'].includes(operation.current?.kind)) return;
      event.preventDefault();
      cancelConfirmation();
    };
    window.addEventListener('androiddownloadback', back);
    return () => window.removeEventListener('androiddownloadback', back);
  }, [cancelConfirmation]);
  useEffect(() => {
    if (confirm) { if (deleting === null) cancelButton.current?.focus(); return; }
    if (restoreIndex.current === null || opening !== null || deleting !== null) return;
    const buttons = list.current?.querySelectorAll('.download-delete') || [];
    const target = buttons[Math.min(restoreIndex.current, buttons.length - 1)] || (!state.busy && refreshButton.current);
    if (target && !target.disabled) { target.focus(); restoreIndex.current = null; }
  }, [confirm, deleting, opening, state.items, state.busy]);

  const askDelete = (item, index) => {
    if (operation.current) return;
    operation.current = { kind: 'confirm', id: item.id };
    readEpoch.current++; restoreIndex.current = index;
    setState(previous => ({ ...previous, busy: false }));
    setDeleteError(''); setConfirm({ ...item });
  };
  const remove = async () => {
    const selected = operation.current;
    if (selected?.kind !== 'confirm' || !confirm || selected.id !== confirm.id) return;
    const id = selected.id;
    operation.current = { kind: 'delete', id };
    readEpoch.current++; setDeleting(id); setDeleteError('');
    try {
      const result = await nativeCall('deleteDownload', { id, confirmed: true });
      if (!alive.current) return;
      if (result?.deleted !== true || result.id !== id) throw new Error('删除结果未确认，请重试。');
      deletedIds.current.add(id);
      setState(previous => ({ ...previous, busy: false, items: previous.items.filter(item => item.id !== id) }));
      setConfirm(null); operation.current = null;
      refresh(true);
    } catch (error) {
      if (alive.current) { operation.current = { kind: 'confirm', id }; setDeleteError(errorText(error)); }
    } finally {
      if (alive.current) setDeleting(null);
    }
  };
  const open = async id => {
    if (operation.current) return;
    operation.current = { kind: 'open', id }; setOpening(id);
    try { await nativeCall('playDownload', { id, ...getPlaybackPreferences() }); }
    catch (error) { if (alive.current) notify(errorText(error)); }
    finally { if (alive.current) { operation.current = null; setOpening(null); } }
  };
  const statuses = { pending: '等待下载', running: '正在下载', paused: '已暂停', success: '下载完成', failed: '下载失败' };
  const activeDownload = item => ['pending', 'running', 'paused'].includes(item.status);
  const locked = !!confirm || deleting !== null || opening !== null;
  return <>
    <header className="page-heading heading-actions"><div><h1>下载</h1><p>系统下载任务与设备上的离线视频。</p></div><button ref={refreshButton} className="button" onClick={() => refresh(true)} disabled={state.busy || locked} aria-label="刷新下载列表"><RefreshCw /><span>刷新</span></button></header>
    {confirm && <div className="download-confirm" role="group" aria-label="确认删除下载">
      <h2>{activeDownload(confirm) ? '取消并删除这个下载？' : '删除这个下载？'}</h2>
      <p>{confirm.title || '短剧'} · 第 {confirm.index} 集</p>
      <p>{activeDownload(confirm) ? '将取消下载，并删除本机视频文件和下载记录。' : '将删除本机视频文件和下载记录。'}收藏和观看历史会保留。</p>
      {deleteError && <p className="download-delete-error" role="alert">{deleteError}</p>}
      <div className="download-confirm-actions"><button ref={cancelButton} type="button" className="button" onClick={cancelConfirmation} disabled={deleting !== null}>取消</button><button type="button" className="button download-destructive" onClick={remove} disabled={deleting !== null}>{deleting !== null ? '删除中…' : '确认删除'}</button></div>
    </div>}
    <Status busy={state.busy} error={state.error} preserve={!!state.items.length} onRetry={() => refresh(true)} empty={!state.items.length && { title: '还没有下载任务', text: '在短剧详情中选择一集，再点击「下载本集」。' }}>
      <div className="download-list" ref={list}>{state.items.map((item, index) => {
        const percent = item.totalBytes > 0 ? Math.min(100, Math.max(0, Math.round(item.downloadedBytes / item.totalBytes * 100))) : null;
        return <article className="download-row" key={item.id}><div className="download-icon"><Download size={24} /></div><div className="download-info"><h2>{item.title || '短剧'} · 第 {item.index} 集</h2><p className={item.status === 'failed' ? 'text-error' : ''}>{statuses[item.status] || item.status}{percent !== null && item.status !== 'success' ? ` · ${percent}%` : ''} · {byteText(item.downloadedBytes)}{item.totalBytes > 0 ? ` / ${byteText(item.totalBytes)}` : ''}</p>{item.status === 'running' && <progress value={percent ?? undefined} max="100" aria-label={`${item.title}下载进度`} />}{item.reason && item.status !== 'success' ? <p className="download-reason">{item.reason}</p> : null}</div><div className="download-actions">{item.status === 'success' && <button type="button" className="button" onClick={() => open(item.id)} disabled={locked}><Play />{opening === item.id ? '打开中…' : '播放'}</button>}<button type="button" className="button download-delete" aria-label={`${activeDownload(item) ? '取消并删除' : '删除'} ${item.title || '短剧'} 第 ${item.index} 集`} onClick={() => askDelete(item, index)} disabled={locked}>{activeDownload(item) ? '取消并删除' : '删除'}</button></div></article>;
      })}</div>
    </Status>
  </>;
}

function About({ info, notify, accountFocus }) {
  const [cache, setCache] = useState({ busy: true });
  const [clearing, setClearing] = useState(false);
  const mounted = useRef(true);
  const refreshCache = useCallback(async () => {
    try { const result = await nativeCall('getPlaybackCache'); if (mounted.current) setCache({ ...result, busy: false }); }
    catch (error) { if (mounted.current) setCache((previous) => ({ ...previous, busy: false, error: errorText(error) })); }
  }, []);
  useEffect(() => { mounted.current = true; refreshCache(); window.addEventListener('androidresume', refreshCache); return () => { mounted.current = false; window.removeEventListener('androidresume', refreshCache); }; }, [refreshCache]);
  const clearCache = async () => {
    setClearing(true);
    try { const result = await nativeCall('clearPlaybackCache'); if (mounted.current) { notify(`已清理 ${result.removed || 0} 个播放缓存 · ${byteText(result.bytes)}`); await refreshCache(); } }
    catch (error) { if (mounted.current) setCache((previous) => ({ ...previous, error: errorText(error) })); }
    finally { if (mounted.current) setClearing(false); }
  };
  const external = async (url) => { try { await nativeCall('openExternal', { url }); } catch (error) { notify(errorText(error)); } };
  return <>
    <header className="page-heading"><h1>关于红果短剧</h1><p>适配手机触控与电视遥控器。</p></header>
    <AccountPanel isTv={!!info.isTv} focusRequest={accountFocus} />
    <UpdatePanel />
    <section className="about-section"><h2>应用信息</h2><dl><div><dt>版本</dt><dd>{info.version || '读取中'}</dd></div><div><dt>设备界面</dt><dd>{info.isTv ? 'Android TV / 电视盒子' : 'Android 手机 / 平板'}</dd></div><div><dt>播放方式</dt><dd>安卓原生全屏播放器</dd></div><div><dt>数据存储</dt><dd>本机记录；官方账号同步收藏、历史与进度</dd></div></dl></section>
    <section className="about-section"><h2>播放缓存</h2><p className="cache-status" role="status">{cache.busy ? '正在读取缓存…' : typeof cache.files === 'number' ? `${cache.files} 个文件 · ${byteText(cache.bytes)}${cache.budgetBytes ? ` / 预算 ${byteText(cache.budgetBytes)}` : ''}` : '缓存状态暂未读取'}</p><p className="cache-note">自动清理开启时，保留前 1 集和当前集；本机播放预取下一集，投屏连播时依次预缓存后面 5 集，优先下一集，不等待全部缓存再开播。预取与清理均限于当前季，不自动跨季。普通缓存预算 256 MiB，投屏期间 768 MiB，单集上限 96 MiB；容量不足或取源失败时预缓存可能不足 5 集。更早已看缓存自动释放，手动下载保留，正在供流的文件受保护。</p><div className="about-links"><button className="button" disabled={cache.busy || clearing} onClick={clearCache}>{clearing ? '清理中…' : '清理播放缓存'}</button><button className="button" disabled={clearing} onClick={refreshCache}><RefreshCw />刷新缓存状态</button></div>{cache.error && <p className="text-error" role="alert">{cache.error}</p>}</section>
    <section className="about-section"><h2>使用说明</h2><p>电视可用方向键移动焦点、确认键打开。返回键先关闭详情，再回到发现页；播放器左上角可直接返回选集。</p><p>完整分集都可选择；播放和下载依次尝试网页常规入口、网页备用入口与 App 片源。选集中的「自动取源」不是锁定标志，也不表示所有分集已经验证可播；失败会显示实际原因。</p><p>自动连播默认开启，当前集结束后继续为下一集自动取源；取源失败时保留重试和返回入口。下载完成后，可在「下载」页面离线播放。</p></section>
    <section className="about-section"><h2>开源与许可</h2><p>本应用基于 jackotom/hongguo-drama，遵循 GPL-3.0 许可。安卓适配代码与构建说明随完整项目源码提供，分发修改版时需保留许可与源码。</p><div className="about-links"><button className="button" onClick={() => external('https://github.com/jackotom/hongguo-drama')}><ExternalLink />上游项目</button><button className="button" onClick={() => external('https://www.gnu.org/licenses/gpl-3.0.html')}><ExternalLink />GPL-3.0 许可网页</button></div><details className="license-details"><summary tabIndex={0}>GPL-3.0 许可全文（离线）</summary><pre className="license-text" tabIndex={0} aria-label="GPL-3.0 许可全文，可滚动阅读">{gplLicense}</pre></details></section>
  </>;
}

/** Own the family directory outside the keyed detail, never merge season queues. */
function SeasonDetails({ seed, initialVid, ...props }) {
  // Only the entry's own detail may correct the family basis. Browsing another
  // season/edition must not silently switch the directory to a different family.
  const [familySeed, setFamilySeed] = useState(seed);
  const familySeedRef = useRef(familySeed);
  familySeedRef.current = familySeed;
  const [selection, setSelection] = useState({ seed, initialVid, epoch: 0 });
  const [directory, setDirectory] = useState(() => {
    const empty = { items: [], page: 0, busy: false, searched: false, hasMore: false, error: '', errorCode: '', notice: '' };
    try { return { ...empty, items: seasonCandidates(seed, []) }; }
    catch (_) { return { ...empty, searched: true, error: '当前标题暂不适合查找分季，可在搜索页继续查找。' }; }
  });
  const [locked, setLocked] = useState(false);
  const mounted = useRef(true);
  const selectionEpoch = useRef(0);
  const lookupEpoch = useRef(0);
  const seasonAbort = useRef(null);
  const fetching = useRef(false);
  const directoryRef = useRef(directory);
  const currentSelection = useRef(selection);
  const selectionGuard = useRef(null);
  const selector = useRef(null);
  const focusSelection = useRef(false);
  const [focusedSeasonId, setFocusedSeasonId] = useState(seed.seriesId);
  directoryRef.current = directory;
  currentSelection.current = selection;
  const loadSeasons = useCallback(async (page = 1) => {
    if (fetching.current) return;
    const generation = ++lookupEpoch.current;
    const controller = new AbortController(); seasonAbort.current = controller;
    const previous = directoryRef.current;
    const focused = selector.current?.contains(document.activeElement) ? document.activeElement : null;
    fetching.current = true;
    setDirectory(value => ({ ...value, busy: true, searched: true, error: '', errorCode: '', failedPage: page }));
    try {
      const result = await seasonLookup.load(familySeed, { page, previous: page > 1 ? previous.items : [], signal: controller.signal });
      if (!mounted.current || generation !== lookupEpoch.current) return;
      const selectedSeed = currentSelection.current.seed;
      let items = result.items;
      if (items.some(candidate => sameSeries(candidate, selectedSeed) && candidate.title !== selectedSeed.title)) {
        const confirmed = seasonCandidates(selectedSeed, [])[0];
        items = items.map(candidate => sameSeries(candidate, selectedSeed) ? { ...candidate, ...confirmed, seasonLabel: candidate.official ? candidate.seasonLabel : confirmed.seasonLabel, seasonNumber: candidate.official ? candidate.seasonNumber : confirmed.seasonNumber } : candidate);
      }
      // A later page can insert lower ordinals before the 40-row cap. Keep
      // both the entry season and the user's current season reachable.
      if (!items.some(candidate => sameSeries(candidate, selectedSeed))) {
        items = [...items];
        if (items.length >= 40) {
          let remove = items.length - 1;
          while (remove >= 0 && sameSeries(items[remove], familySeed)) remove--;
          if (remove >= 0) items.splice(remove, 1);
        }
        items.push(seasonCandidates(selectedSeed, [])[0]);
        items.sort((a, b) => (a.seasonNumber ?? 0) - (b.seasonNumber ?? 0) || a.seriesId.localeCompare(b.seriesId));
      }
      setDirectory({ ...result, items, busy: false, searched: true, error: '', errorCode: '', failedPage: null });
    } catch (error) {
      if (!mounted.current || generation !== lookupEpoch.current) return;
      setDirectory(value => ({ ...value, busy: false, error: errorText(error), errorCode: error?.code || '', failedPage: page }));
    } finally {
      if (mounted.current && generation === lookupEpoch.current) {
        fetching.current = false;
        if (focused) requestAnimationFrame(() => {
          if (!mounted.current || generation !== lookupEpoch.current || (document.activeElement !== focused && document.activeElement !== document.body)) return;
          const next = focused.isConnected && !focused.disabled ? focused : selector.current?.querySelector('[data-season-action="retry"], [data-season-id][aria-selected="true"]');
          next?.focus();
        });
      }
    }
  }, [familySeed]);
  useEffect(() => {
    mounted.current = true;
    const pauseLookup = () => { if (!fetching.current) return; seasonAbort.current?.abort(); lookupEpoch.current++; fetching.current = false; setDirectory(value => ({ ...value, busy: false, error: '分季读取已暂停，返回后可重试。', failedPage: 1 })); };
    window.addEventListener('androidseriespause', pauseLookup);
    if (parseSeasonTitle(familySeed.title)?.explicit) loadSeasons();
    return () => { mounted.current = false; lookupEpoch.current++; fetching.current = false; seasonAbort.current?.abort(); window.removeEventListener('androidseriespause', pauseLookup); };
  }, [familySeed, loadSeasons]);
  useEffect(() => {
    if (!focusSelection.current) return;
    focusSelection.current = false;
    const button = selector.current?.querySelector('[data-season-id][aria-selected="true"]');
    button?.focus();
    button?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, [selection.epoch]);
  useEffect(() => {
    // Newly loaded seasons can insert before the entry tab. Reveal the current
    // selection without stealing focus from search/retry or a browsing user.
    if (!directory.items.some(candidate => candidate.seriesId === focusedSeasonId)) setFocusedSeasonId(selection.seed.seriesId);
    const focused = document.activeElement;
    const tab = selector.current?.contains(focused) && focused?.matches('[role="tab"]')
      ? focused : selector.current?.querySelector('[role="tab"][aria-selected="true"]');
    const list = tab?.parentElement;
    if (tab && list) {
      const tabBox = tab.getBoundingClientRect(), listBox = list.getBoundingClientRect();
      if (tabBox.left < listBox.left + 6) list.scrollLeft -= listBox.left + 6 - tabBox.left;
      else if (tabBox.right > listBox.right - 6) list.scrollLeft += tabBox.right - listBox.right + 6;
    }
  }, [directory.items]);
  const moveSeasonFocus = event => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    // Manual activation: the D-pad moves only focus; native button Enter/Space
    // activates the chosen season. Keep horizontal keys out of TV navigation.
    event.preventDefault(); event.stopPropagation();
    const tabs = [...event.currentTarget.querySelectorAll('[role="tab"]:not(:disabled)')];
    if (!tabs.length) return;
    const current = tabs.indexOf(document.activeElement);
    const index = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
      : (Math.max(0, current) + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
    tabs[index].focus();
  };
  const chooseSeason = candidate => {
    if (locked || sameSeries(candidate, selection.seed)) return;
    if (candidate.playable === false || candidate.upcoming) { props.notify(candidate.upcoming ? "这一季尚未上线" : "这一季暂不可播放"); return; }
    // The guard reads refs, not a possibly stale rendered disabled state. In
    // particular, invalidate an old options promise before React can remount.
    if (selectionGuard.current && !selectionGuard.current()) return;
    const epoch = ++selectionEpoch.current;
    focusSelection.current = true;
    setFocusedSeasonId(candidate.seriesId);
    setLocked(false);
    const next = { seed: candidate, initialVid: null, epoch };
    currentSelection.current = next;
    setSelection(next);
  };
  const recent = latestSeasonHistory(familySeed, props.library.history);
  const continueSeason = () => {
    if (!recent || locked || selectionGuard.current && !selectionGuard.current()) return;
    const epoch = ++selectionEpoch.current;
    const candidate = { ...recent };
    const next = { seed: candidate, initialVid: recent.vid, epoch, resumeIntent: { token: epoch, seriesId: recent.seriesId, vid: recent.vid } };
    currentSelection.current = next; focusSelection.current = true; setFocusedSeasonId(candidate.seriesId); setLocked(false);
    setDirectory(value => {
      if (value.items.some(item => sameSeries(item, candidate))) return value;
      const items = value.items.length < 40 ? [...value.items] : value.items.filter(item => !sameSeries(item, seed)).slice(0, 38).concat(value.items.find(item => sameSeries(item, seed)) || []);
      try { items.push(seasonCandidates(candidate, [])[0]); } catch (_) { /* Legacy history can still open its exact detail. */ }
      return { ...value, items };
    });
    setSelection(next);
  };
  const isSeasonCurrent = () => mounted.current && selectionEpoch.current === selection.epoch;
  const onSeasonLock = value => { if (isSeasonCurrent()) setLocked(value); };
  const onDetailResolved = item => {
    const current = currentSelection.current;
    if (!isSeasonCurrent() || current.epoch !== selection.epoch || !sameSeries(item, current.seed)) return;
    let confirmed;
    try { confirmed = seasonCandidates(item, [])[0]; } catch (_) { return; }
    if (confirmed.title !== current.seed.title) {
      // Same key/epoch: publishing metadata must not remount Detail, reset its
      // remembered episode, or turn an ordinary directory update into playback.
      const next = { ...current, seed: { ...current.seed, ...confirmed } };
      currentSelection.current = next; setSelection(next);
    }
    const correctEntry = sameSeries(confirmed, familySeedRef.current) && confirmed.title !== familySeedRef.current.title;
    if (correctEntry) {
      // Invalidate synchronously, before React's effect cleanup, so an old
      // unmarked/abbreviated-title search cannot publish its filtered results.
      lookupEpoch.current++; fetching.current = false; seasonAbort.current?.abort();
      familySeedRef.current = confirmed; setFamilySeed(confirmed);
      setDirectory(value => ({ ...value, items: value.matching === "official" ? value.items.map(row => sameSeries(row, confirmed) ? { ...row, title: confirmed.title } : row) : seasonCandidates(confirmed, value.items), page: 0, busy: false, searched: false, hasMore: false, error: '', errorCode: '', notice: '', failedPage: null }));
    } else {
      setDirectory(value => value.items.some(candidate => sameSeries(candidate, confirmed) && candidate.title !== confirmed.title)
        ? { ...value, items: value.items.map(candidate => sameSeries(candidate, confirmed) ? { ...candidate, ...confirmed, seasonLabel: candidate.official ? candidate.seasonLabel : confirmed.seasonLabel, seasonNumber: candidate.official ? candidate.seasonNumber : confirmed.seasonNumber } : candidate) } : value);
    }
  };
  const seasonCount = candidate => candidate.upcoming ? "待上线" : candidate.playable === false ? "暂不可播放" : candidate.episodeCount ? `${candidate.episodeCount} 集` : candidate.episodeLabel || '集数待读取';
  const seasonProgress = candidate => props.library.history.find(entry => sameSeries(entry, candidate));
  const seasonEditions = candidate => directory.items.filter(other => other.seasonLabel === candidate.seasonLabel);
  const tabLabel = candidate => {
    const label = candidate.seasonLabel.replace(/\s+/g, '') + (candidate.upcoming ? ' · 待上线' : candidate.playable === false ? ' · 暂不可播' : '');
    const editions = seasonEditions(candidate);
    if (editions.length < 2) return label;
    const matchingCount = editions.filter(other => seasonCount(other) === seasonCount(candidate));
    return `${label} · ${seasonCount(candidate)}${matchingCount.length > 1 ? ` · 版本 ${matchingCount.findIndex(other => sameSeries(other, candidate)) + 1}` : ''}`;
  };
  const tabDescription = candidate => {
    const progress = seasonProgress(candidate);
    return `${candidate.seasonLabel}，${candidate.title}，${seasonCount(candidate)}${progress ? `，看到第 ${progress.index} 集 · ${timeText(progress.positionMs)}` : ''}${seasonEditions(candidate).length > 1 ? `，版本 ${candidate.seriesId}` : ''}`;
  };
  const selectedSeason = directory.items.find(candidate => sameSeries(candidate, selection.seed)) || selection.seed;
  const selectedProgress = seasonProgress(selectedSeason);
  const seasonSelector = <section ref={selector} className="season-selector" aria-label="选择季" aria-busy={directory.busy}>
    <header><h2>选择季</h2>{!directory.searched && <button type="button" className="button" data-season-action="search" disabled={locked || directory.busy} onClick={() => loadSeasons()}>查找其他季</button>}</header>
    {recent && <div className="season-history" aria-label="同系列观看进度"><div><strong>上次看到 {parseSeasonTitle(recent.title)?.explicit ? `${parseSeasonTitle(recent.title).label} · ` : ''}第 {recent.index} 集 · {timeText(recent.positionMs)}</strong><p>{recent.title}</p></div><button type="button" className="button" data-season-action="resume" disabled={locked} onClick={continueSeason}>继续播放</button></div>}
    <div className="season-list" role="tablist" aria-label="分季候选" aria-orientation="horizontal" onKeyDown={moveSeasonFocus}>{directory.items.map(candidate => <button key={candidate.seriesId} id={`season-tab-${candidate.seriesId}`} type="button" role="tab" className="season-choice" data-season-id={candidate.seriesId} aria-selected={sameSeries(candidate, selection.seed)} aria-controls={`season-panel-${candidate.seriesId}`} aria-label={tabDescription(candidate)} tabIndex={!locked && candidate.seriesId === focusedSeasonId ? 0 : -1} disabled={locked} onFocus={event => { setFocusedSeasonId(candidate.seriesId); event.currentTarget.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }} onClick={() => chooseSeason(candidate)}><span className="season-label">{tabLabel(candidate)}</span></button>)}</div>
    <div className="season-current" aria-label="当前季信息"><p className="season-title">{selectedSeason.title}</p><p className="season-count">{seasonCount(selectedSeason)}</p>{selectedProgress && <p className="season-progress">看到第 {selectedProgress.index} 集 · {timeText(selectedProgress.positionMs)}</p>}{seasonEditions(selectedSeason).length > 1 && <p className="season-version">版本 {selectedSeason.seriesId}</p>}</div>
    {directory.items.filter(candidate => !sameSeries(candidate, selection.seed)).map(candidate => <div key={candidate.seriesId} id={`season-panel-${candidate.seriesId}`} role="tabpanel" aria-labelledby={`season-tab-${candidate.seriesId}`} hidden />)}
    <p className="season-note">{directory.notice || (directory.searched ? '按标题匹配，可能存在不同版本；切季不会自动播放。' : '优先读取官方系列关系，失败再按标题查找；未标注季数不推断为第一季。')}</p>
    {directory.busy && <p className="season-status" role="status">正在查找分季…</p>}
    {directory.error && <div className="season-error" role="alert"><p>分季查找未完成：{directory.error} 当前季仍可播放。</p><button type="button" className="button" data-season-action="retry" disabled={locked || directory.busy} onClick={() => loadSeasons(directory.errorCode === 'SEARCH_EXPIRED' ? 1 : directory.failedPage || 1)}>{directory.errorCode === 'SEARCH_EXPIRED' ? '重新查找分季' : '重试查找'}</button></div>}
    {!directory.busy && !directory.error && directory.searched && directory.items.length === 1 && <p className="season-status">本页未匹配到其他季{directory.hasMore ? '，可继续查找。' : '；也可到搜索页查找不同剧名。'}</p>}
    {directory.hasMore && !directory.error && <button type="button" className="button season-more" data-season-action="more" disabled={locked || directory.busy} onClick={() => loadSeasons(directory.page + 1)}>{directory.busy ? '正在查找…' : '加载更多分季'}</button>}
    {locked && <p className="season-status" role="status">播放器返回或下载准备完成后可切换季。</p>}
  </section>;
  return <Detail key={`${selection.seed.seriesId}-${selection.epoch}`} {...props} seed={selection.seed} initialVid={selection.initialVid} resumeIntent={selection.resumeIntent} seasonSelector={seasonSelector} seasonPanel={directory.items.some(candidate => sameSeries(candidate, selection.seed)) ? { id: `season-panel-${selection.seed.seriesId}`, role: 'tabpanel', 'aria-labelledby': `season-tab-${selection.seed.seriesId}` } : null} selectionGuard={selectionGuard} isSeasonCurrent={isSeasonCurrent} onSeasonLock={onSeasonLock} onDetailResolved={onDetailResolved} />;
}

function Detail({ seed, initialVid, resumeIntent, library, onBack, onLibrary, notify, isTv = false, seasonSelector = null, seasonPanel = null, selectionGuard, isSeasonCurrent = () => true, onSeasonLock = () => {}, onDetailResolved = () => {} }) {
  const [state, setState] = useState({ busy: true });
  const [reload, setReload] = useState(0);
  const [selectedVid, setSelectedVid] = useState(initialVid || null);
  const [operation, setOperation] = useState(null);
  const [playDispatched, setPlayDispatched] = useState(false);
  const [opError, setOpError] = useState(null);
  const [resumeError, setResumeError] = useState('');
  const consumedResume = useRef(null);
  const [favoriteBusy, setFavoriteBusy] = useState(false);
  const [favoriteError, setFavoriteError] = useState('');
  const [pointEntry, setPointEntry] = useState(null);
  const [pointError, setPointError] = useState(false), [pointBusy, setPointBusy] = useState(false);
  const favoritePending = useRef(false);
  const [preferences, setPreferences] = useState(getPlaybackPreferences);
  const [mediaOptions, setMediaOptions] = useState({ quality: 'auto', speed: 1 });
  const [mediaBusy, setMediaBusy] = useState(false);
  const [mediaError, setMediaError] = useState('');
  const [episodeGroup, setEpisodeGroup] = useState(0);
  const [descriptionExpanded, setDescriptionExpanded] = useState(false);
  const [descriptionOverflow, setDescriptionOverflow] = useState(false);
  const description = useRef(null);
  const episodeGrid = useRef(null);
  const operationError = useRef(null);
  const activeOperation = useRef(null);
  const dispatchedPlay = useRef(null);
  const mediaVersion = useRef(0);
  const libraryRef = useRef(library);
  libraryRef.current = library;
  useEffect(() => {
    if (!selectionGuard) return;
    const guard = () => {
      if (dispatchedPlay.current || activeOperation.current?.action === 'download') return false;
      activeOperation.current = null;
      return true;
    };
    selectionGuard.current = guard;
    return () => { if (selectionGuard.current === guard) selectionGuard.current = null; };
  }, [selectionGuard]);
  useEffect(() => {
    let current = true;
    const refresh = () => { const version = mediaVersion.current; return nativeCall('getPlaybackOptions').then(value => { if (current && version === mediaVersion.current && value?.quality && Number.isFinite(Number(value.speed))) setMediaOptions({ quality: value.quality, speed: Number(value.speed) }); }).catch(error => { if (current && version === mediaVersion.current) setMediaError(errorText(error)); }); };
    refresh(); window.addEventListener('androidresume', refresh);
    return () => { current = false; window.removeEventListener('androidresume', refresh); };
  }, []);
  const changeMediaOption = async (key, value) => {
    const version = ++mediaVersion.current;
    // A pending options read belongs to the old preference. Never let it open
    // an episode after a new quality/speed choice (or overwrite that choice).
    if (!dispatchedPlay.current && activeOperation.current?.action === 'play') { activeOperation.current = null; setOperation(null); }
    setMediaBusy(true); setMediaError('');
    try { const options = await nativeCall('setPlaybackOptions', { [key]: value }); if (mounted.current && version === mediaVersion.current && options?.quality && Number.isFinite(Number(options.speed))) setMediaOptions({ quality: options.quality, speed: Number(options.speed) }); }
    catch (error) { if (mounted.current && version === mediaVersion.current) setMediaError(errorText(error)); }
    finally { if (mounted.current && version === mediaVersion.current) setMediaBusy(false); }
  };
  const mounted = useRef(true);
  const syncReturnedEpisode = useRef(false);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; activeOperation.current = null; mediaVersion.current++; }; }, []);
  useEffect(() => { const resume = () => { syncReturnedEpisode.current = true; }; window.addEventListener('androidresume', resume); return () => window.removeEventListener('androidresume', resume); }, []);
  useEffect(() => {
    let current = true;
    setState({ busy: true }); setPointEntry(null); setPointError(false); setPointBusy(false); setFavoriteError('');
    source.detail(seed.seriesId).then(async (resolved) => {
      if (!current || !isSeasonCurrent()) return;
      let item = { ...resolved, ...seriesSyncMetadata(seed), ...seriesSyncMetadata(resolved) };
      let rememberedRow = libraryRef.current.history.find((entry) => sameSeries(entry, seed));
      const favoriteRow = libraryRef.current.favorites.find((entry) => sameSeries(entry, seed));
      item = { ...item, ...seriesSyncMetadata(favoriteRow), ...seriesSyncMetadata(rememberedRow), ...seriesSyncMetadata(resolved) };
      if (needsSeriesPoint(libraryRef.current, seed)) {
        const owner = libraryRef.current.owner;
        try { const result = await nativeCall('getSeriesHistory', { seriesId: seed.seriesId }, 10000); if (!current || owner !== libraryRef.current.owner) return; if (result?.owner !== owner || result.history && result.history.seriesId !== seed.seriesId) throw new Error('剧集账号状态不匹配'); const row = result.history; if (row) { setPointEntry({ ...row, __owner: owner }); item = { ...item, ...seriesSyncMetadata(row), ...seriesSyncMetadata(resolved) }; if (row.index > 0 && typeof row.vid === 'string') rememberedRow = row; } setPointError(false); }
        catch (_) { if (current && owner === libraryRef.current.owner) { setPointError(true); setFavoriteError(POINT_FAVORITE_ERROR); setResumeError(POINT_RESUME_ERROR); } }
      }
      if (!current || !isSeasonCurrent()) return;
      setState({ busy: false, item });
      onDetailResolved(item);
      const remembered = initialVid || rememberedRow?.vid;
      const first = item.episodes?.[0];
      setSelectedVid(item.episodes?.some((episode) => String(episode.vid) === String(remembered)) ? remembered : first?.vid || null);
    }).catch((error) => { if (current) setState({ busy: false, error: errorText(error) }); });
    return () => { current = false; };
  }, [seed.seriesId, initialVid, reload, library.owner]);
  // A same-owner cloud refresh may change a row omitted from the bounded lists.
  // Re-read that point without re-fetching source detail or moving the user's selection.
  useEffect(() => {
    if (!state.item || typeof library.owner !== 'string') return;
    const clearPointErrors = () => { setPointError(false); setFavoriteError(value => value === POINT_FAVORITE_ERROR ? '' : value); setResumeError(value => value === POINT_RESUME_ERROR ? '' : value); };
    if (!needsSeriesPoint(library, seed)) { setPointEntry(null); setPointBusy(false); clearPointErrors(); return; }
    let current = true;
    const owner = library.owner; setPointBusy(true);
    nativeCall('getSeriesHistory', { seriesId: seed.seriesId }, 10000).then(result => {
      if (!current || owner !== libraryRef.current.owner) return;
      if (result?.owner !== owner || result.history && result.history.seriesId !== seed.seriesId) throw new Error('剧集账号状态不匹配');
      setPointEntry(result.history ? { ...result.history, __owner: owner } : null); clearPointErrors();
    }).catch(() => { if (current && owner === libraryRef.current.owner) { setPointError(true); setFavoriteError(POINT_FAVORITE_ERROR); setResumeError(POINT_RESUME_ERROR); } })
      .finally(() => { if (current && owner === libraryRef.current.owner) setPointBusy(false); });
    return () => { current = false; };
  }, [library]);
  const item = state.item || seed;
  const isFavorite = pointEntry && pointEntry.__owner === library.owner ? pointEntry.favoriteAt > 0 : library.favorites.some((entry) => sameSeries(entry, item));
  const episode = state.item?.episodes?.find((entry) => String(entry.vid) === String(selectedVid));
  // Measure rendered lines, not character count: short summaries can still be
  // long at large text sizes or in a narrow phone column.
  useEffect(() => {
    const node = description.current;
    if (isTv || !node) return;
    const measure = () => setDescriptionOverflow(node.scrollHeight > (Number.parseFloat(getComputedStyle(node).lineHeight) || 24) * 3 + 1);
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(measure) : null;
    observer?.observe(node); measure(); window.addEventListener('resize', measure);
    return () => { observer?.disconnect(); window.removeEventListener('resize', measure); };
  }, [state.busy, item.description, isTv]);
  const lastWatchedVid = library.history.find((entry) => sameSeries(entry, item))?.vid || (pointEntry?.__owner === library.owner && pointEntry?.seriesId === item.seriesId ? pointEntry.vid : null);
  const episodes = state.item?.episodes || [];
  const selectedPosition = episodes.findIndex((entry) => String(entry.vid) === String(selectedVid));
  const grouped = !isTv && episodes.length > 30;
  const groupCount = Math.ceil(episodes.length / 30);
  const visibleEpisodes = grouped ? episodes.slice(episodeGroup * 30, episodeGroup * 30 + 30) : episodes;
  // Returning from continuous playback reveals the last watched group without
  // launching a video. Browsing a group never changes the playback selection.
  useEffect(() => { if (grouped && selectedPosition >= 0) setEpisodeGroup(Math.floor(selectedPosition / 30)); }, [grouped, selectedVid, state.item]);
  useEffect(() => { if (opError) operationError.current?.scrollIntoView({ block: 'nearest' }); }, [opError]);
  const locateEpisode = () => {
    if (selectedPosition < 0) return;
    setEpisodeGroup(Math.floor(selectedPosition / 30));
    requestAnimationFrame(() => requestAnimationFrame(() => {
      const button = episodeGrid.current?.querySelector('[aria-pressed="true"]');
      button?.focus({ preventScroll: true });
      button?.scrollIntoView({ block: 'center', inline: 'nearest' });
    }));
  };
  useEffect(() => { if (syncReturnedEpisode.current && lastWatchedVid && state.item?.episodes?.some((entry) => String(entry.vid) === String(lastWatchedVid))) { syncReturnedEpisode.current = false; setSelectedVid(lastWatchedVid); } }, [lastWatchedVid]);
  const changePreference = (key) => setPreferences((previous) => {
    const next = { ...previous, [key]: !previous[key] };
    setPlaybackPreference(key, next[key]);
    return next;
  });
  const favorite = async () => {
    if (favoritePending.current || pointError || pointBusy) return;
    const owner = libraryRef.current.owner; favoritePending.current = true;
    setFavoriteBusy(true); setFavoriteError('');
    try { const result = await nativeCall('toggleFavorite', { series: compactSeries(item) }); if (mounted.current && owner === libraryRef.current.owner && (result.owner === undefined || result.owner === owner)) { setPointEntry(null); onLibrary(result); } }
    catch (error) { if (mounted.current && owner === libraryRef.current.owner) setFavoriteError(errorText(error)); }
    finally { favoritePending.current = false; if (mounted.current) setFavoriteBusy(false); }
  };
  const run = async (action, target = episode) => {
    if (!isSeasonCurrent() || !target || mediaBusy || dispatchedPlay.current || activeOperation.current?.action === 'download') return;
    if (activeOperation.current?.action === action && String(activeOperation.current.vid) === String(target.vid)) return;
    // The event's episode, not a future React state update, is authoritative.
    // Different pending play choices supersede one another; repeated confirms
    // on the same episode coalesce before any asynchronous bridge work starts.
    const request = { action, vid: target.vid };
    activeOperation.current = request;
    const current = () => mounted.current && isSeasonCurrent() && activeOperation.current === request;
    setOperation(action); setOpError(null); setResumeError('');
    if (action === 'download') onSeasonLock(true);
    try {
      const options = await nativeCall('getPlaybackOptions');
      if (!current()) return;
      setMediaOptions(options);
      const params = { series: compactSeries(item), episode: compactEpisode(target), quality: options.quality, speed: options.speed };
      if (action === 'play') {
        // Initial, selected and continuous episodes now use the same native
        // resolver/fallback state machine. No JS preflight can fail beforehand.
        Object.assign(params, { episodes: item.episodes.map(compactEpisode), ...preferences, resolveInPlayer: true });
        // Only the selected season enters native playback/casting. Manual season
        // discovery and history navigation must not delay play or extend its queue.
        assertPlaybackRequestSize(params);
        dispatchedPlay.current = request; setPlayDispatched(true); onSeasonLock(true);
      } else {
        // Manual downloads keep their exact chosen quality and existing model.
        const video = await source.playback(item.seriesId, target.vid, { quality: options.quality });
        if (!current()) return;
        const playInfo = { ...video, contentKey: video.contentKey ?? null, source: video.source || 'web', codec: video.codec || '', userAgent: video.userAgent || 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36', referer: video.referer ?? (['app', 'api'].includes(video.source) ? '' : 'https://hongguoduanju.com/'), vid: target.vid };
        Object.assign(params, { url: playInfo.url, playInfo });
      }
      await nativeCall(action, params);
      if (current() && action === 'download') notify(`第 ${target.index} 集已加入系统下载`);
    } catch (error) { if (current()) { const text = errorText(error); setOpError({ text: /HTTP\s*404/i.test(text) ? '官网当前未提供本集片源（HTTP 404）。公开网页片源与官方 App 的剧集范围可能不同。' : text, action, episode: target }); } }
    finally {
      if (dispatchedPlay.current === request) { dispatchedPlay.current = null; if (mounted.current) setPlayDispatched(false); }
      if (current()) { activeOperation.current = null; setOperation(null); onSeasonLock(false); }
    }
  };
  const playEpisode = (entry) => {
    if (mediaBusy || dispatchedPlay.current || activeOperation.current?.action === 'download') return;
    consumedResume.current = resumeIntent?.token; syncReturnedEpisode.current = false; setSelectedVid(entry.vid);
    run('play', entry);
  };
  useEffect(() => {
    if (!resumeIntent || consumedResume.current === resumeIntent.token || state.busy || state.error || !state.item) return;
    consumedResume.current = resumeIntent.token;
    const target = state.item.seriesId === resumeIntent.seriesId && state.item.episodes.find(entry => entry.vid === resumeIntent.vid);
    if (!target) { setSelectedVid(null); setResumeError('上次观看的分集已不在当前目录，请手动选择；未自动播放其他集。'); return; }
    setSelectedVid(target.vid); run('play', target);
  }, [state.item, resumeIntent?.token]);
  return <div className="detail-page">
    <div className="detail-scroll">
    <button className="back-button" onClick={onBack}><ChevronRight size={20} />返回</button>
    {seasonSelector}
    <div className="season-detail-panel" {...seasonPanel}>
    {resumeError && <p className="resume-error" role="alert">{resumeError}</p>}
    <Status busy={state.busy} error={state.error} onRetry={() => setReload((n) => n + 1)}>
      <section className="detail-summary"><div className="detail-cover">{item.coverUrl ? <img src={item.coverUrl} alt="" /> : <Film size={48} />}</div><div className="detail-copy"><h1>{item.title}</h1><p className="detail-meta">{item.episodeLabel || (item.episodeCount ? `全 ${item.episodeCount} 集` : '剧集详情')}{item.tags?.length ? ` · ${item.tags.join(' / ')}` : ''}</p><p ref={description} id="detail-description" className={`detail-description${!isTv && !descriptionExpanded ? ' description-collapsed' : ''}`}>{item.description || '暂无剧情简介。'}</p>{!isTv && descriptionOverflow && <button className="description-toggle" type="button" aria-expanded={descriptionExpanded} aria-controls="detail-description" onClick={() => setDescriptionExpanded((value) => !value)}>{descriptionExpanded ? '收起简介' : '展开简介'}</button>}<button className={`button ${isFavorite ? 'saved' : ''}`} onClick={favorite} disabled={favoriteBusy || pointError || pointBusy}><Heart />{favoriteBusy ? '保存中…' : isFavorite ? '已加入追剧' : '加入追剧'}</button>{favoriteError && <p className="text-error" role="alert">{favoriteError} {pointError ? <button type="button" className="button" onClick={() => setReload(n => n + 1)}>重试剧集状态</button> : "点击追剧按钮重试。"}</p>}</div></section>
      <section className="episode-section"><header><h2>选集</h2><p>共 {state.item?.episodes?.length || 0} 集</p></header><p className="episode-note">点击集数直接播放；电视方向键移动、确认键播放。网页 → App 自动取源，逐集点击时核验。</p><div className="episode-browser" hidden={!grouped}><label htmlFor="episode-range">分集范围<select id="episode-range" aria-label="分集范围" value={episodeGroup} onChange={(event) => setEpisodeGroup(Number(event.target.value))}>{Array.from({ length: groupCount }, (_, group) => <option key={group} value={group}>第 {group * 30 + 1}–{Math.min((group + 1) * 30, episodes.length)} 集</option>)}</select></label><button className="button" type="button" disabled={!episode} onClick={locateEpisode}>定位第 {episode?.index || '—'} 集</button></div><div ref={episodeGrid} className="episode-grid" role="group" aria-label="选择剧集">{visibleEpisodes.map((entry) => <button key={`${entry.index}-${entry.vid}`} disabled={operation === 'download' || mediaBusy || playDispatched} title={`播放第 ${entry.index} 集${entry.webAvailable ? '' : '，网页与 App 自动取源'}`} aria-label={`播放第 ${entry.index} 集${entry.webAvailable ? '' : '，自动取源'}`} aria-pressed={String(entry.vid) === String(selectedVid)} onClick={() => playEpisode(entry)}><span>{entry.index}</span>{!entry.webAvailable && <span className="episode-status">自动取源</span>}</button>)}</div>{!state.item?.episodes?.length && <p className="empty-episodes">当前没有剧集。</p>}</section>
      <section className="playback-settings" aria-label="清晰度与倍速">
        <div className="playback-setting"><div><h2>清晰度</h2><p>播放失败先自动重新取源，再按真实分辨率尝试较低档（720p / 480p / 360p），保留本集进度、暂停和倍速；降档仅影响当前播放，不覆盖首选。手动下载严格使用所选清晰度。</p></div><select aria-label="清晰度" value={mediaOptions.quality} disabled={!!operation || mediaBusy} onChange={event => changeMediaOption('quality', event.target.value)}><option value="auto">自动</option><option value="720p">720p 及以上</option><option value="1080p">1080p 及以上</option></select></div>
        <div className="playback-setting"><div><h2>播放倍速</h2><p>播放器内也可切换，连播沿用设置；下载文件保持原速度。</p></div><select aria-label="播放倍速" value={mediaOptions.speed} disabled={!!operation || mediaBusy} onChange={event => changeMediaOption('speed', Number(event.target.value))}>{[0.75, 1, 1.25, 1.5, 2].map(speed => <option key={speed} value={speed}>{speed}×</option>)}</select></div>
        {mediaError && <p className="text-error" role="alert">{mediaError}</p>}
      </section>
      <section className="playback-settings" aria-label="播放设置"><div className="playback-setting"><div><h2>自动连播</h2><p>本集结束后播放本季下一集；本季播完后停止，下一季请在分季标签中手动选择。</p></div><button className="setting-switch" type="button" role="switch" aria-checked={preferences.autoplay} aria-label="自动连播" disabled={!!operation} onClick={() => changePreference('autoplay')}>{preferences.autoplay ? '开启' : '关闭'}</button></div><div className="playback-setting"><div><h2>清理已看播放缓存</h2><p>按本季播放顺序保留前 1 集和当前集；本机预取下一集，投屏连播预缓存后 5 集，均不跨季。手动下载保留。</p></div><button className="setting-switch" type="button" role="switch" aria-checked={preferences.cleanupPlaybackCache} aria-label="清理已看播放缓存" disabled={!!operation} onClick={() => changePreference('cleanupPlaybackCache')}>{preferences.cleanupPlaybackCache ? '开启' : '关闭'}</button></div></section>
      {opError && <div ref={operationError} className="operation-error" role="alert"><p>{opError.text}</p><button className="button" onClick={() => run(opError.action, opError.episode)}>重试{opError.action === 'play' ? '播放' : '下载'}</button></div>}
    </Status>
    </div>
    </div>
    {!state.busy && !state.error && state.item && <div className="play-actions"><p role="status">{episode ? `已选第 ${episode.index} 集 · 自动取源` : '请选择剧集'}</p><div><button className="button primary" disabled={!episode || !!operation || mediaBusy} onClick={() => run('play')}><Play />{operation === 'play' ? '取源并打开中…' : '播放本集'}</button><button className="button" disabled={!episode || !!operation || mediaBusy} onClick={() => run('download')}><Download />{operation === 'download' ? '正在准备下载…' : '下载本集'}</button></div></div>}
  </div>;
}

/** Spatial D-pad navigation: only visible enabled controls participate. */
function useTvNavigation(isTv) {
  useEffect(() => {
    if (!isTv) return;
    const keydown = (event) => {
      if (event.defaultPrevented) return;
      if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Enter'].includes(event.key)) return;
      const focused = document.activeElement;
      if (event.key === 'Enter') {
        if (focused?.matches('button, [role="button"], summary')) { event.preventDefault(); focused.click(); }
        return;
      }
      if (focused?.matches('input,textarea') && ['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
      // The account form is a linear task. Spatial scoring otherwise jumps from
      // a wide password input to distant cache controls rather than Login.
      const accountForm = focused?.closest('.account-form');
      if (accountForm && ['ArrowUp', 'ArrowDown'].includes(event.key)) {
        const fields = [...accountForm.querySelectorAll('input:not(:disabled),button:not(:disabled)')].filter(element => element.getClientRects().length);
        const at = fields.indexOf(focused), next = at + (event.key === 'ArrowDown' ? 1 : -1);
        if (at >= 0 && next >= 0 && next < fields.length) {
          event.preventDefault(); fields[next].focus(); fields[next].scrollIntoView({ block: 'nearest', inline: 'nearest' }); return;
        }
      }
      if (focused?.matches('.license-text') && ['ArrowUp', 'ArrowDown'].includes(event.key)) {
        const down = event.key === 'ArrowDown';
        if (down ? focused.scrollTop + focused.clientHeight < focused.scrollHeight - 1 : focused.scrollTop > 0) {
          event.preventDefault();
          focused.scrollTop += (down ? 1 : -1) * focused.clientHeight * 0.7;
          return;
        }
      }
      const controls = [...document.querySelectorAll('button:not(:disabled),input:not(:disabled),select:not(:disabled),a[href],[tabindex="0"]')].filter((element) => element.getClientRects().length && getComputedStyle(element).visibility !== 'hidden'
        && (!element.matches('.season-choice[role="tab"]') || element.tabIndex >= 0 || element === focused));
      if (!controls.length) return;
      event.preventDefault();
      if (!controls.includes(focused)) { controls[0].focus(); return; }
      const origin = focused.getBoundingClientRect();
      const ox = origin.left + origin.width / 2; const oy = origin.top + origin.height / 2;
      const horizontal = ['ArrowLeft', 'ArrowRight'].includes(event.key);
      const positive = ['ArrowRight', 'ArrowDown'].includes(event.key);
      const candidate = controls.filter((element) => element !== focused).map((element) => {
        const rect = element.getBoundingClientRect(); const dx = rect.left + rect.width / 2 - ox; const dy = rect.top + rect.height / 2 - oy;
        const along = horizontal ? dx : dy; const across = horizontal ? dy : dx;
        if ((positive ? along : -along) <= 2) return null;
        return { element, score: Math.abs(along) + Math.abs(across) * 3 + across * across / Math.max(Math.abs(along), 1) };
      }).filter(Boolean).sort((a, b) => a.score - b.score)[0];
      if (candidate) { candidate.element.focus(); candidate.element.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
    };
    document.addEventListener('keydown', keydown);
    return () => document.removeEventListener('keydown', keydown);
  }, [isTv]);
}

export default function App() {
  const [tab, setTab] = useState('discover');
  const [detail, setDetail] = useState(null);
  const [info, setInfo] = useState({ isTv: false, version: '' });
  const [infoError, setInfoError] = useState('');
  const [library, setLibrary] = useState(EMPTY_LIBRARY);
  const [libraryBusy, setLibraryBusy] = useState(true);
  const [libraryError, setLibraryError] = useState('');
  const [notice, setNotice] = useState('');
  const [accountFocus, setAccountFocus] = useState(0);
  const content = useRef(null);
  const lastDetailFocus = useRef(null);
  const libraryReadEpoch = useRef(0);
  const libraryOwner = useRef(null);
  const accountTransition = useRef(false);
  const previousScroll = useRef(0);
  const noticeTimer = useRef(null);
  const loadInfo = useCallback(async () => { try { const result = await nativeCall('getInfo'); setInfo(result); setInfoError(''); } catch (error) { setInfoError(errorText(error)); } }, []);
  const loadLibrary = useCallback(async () => { if (accountTransition.current) return; const epoch = ++libraryReadEpoch.current; setLibraryBusy(true); setLibraryError(''); try { const result = await nativeCall('getLibrary'); if (epoch !== libraryReadEpoch.current) return; if (typeof result.owner === "string") libraryOwner.current = result.owner; setLibrary({ favorites: result.favorites || [], history: result.history || [], projection: result.projection || null, owner: result.owner }); setLibraryError(''); } catch (error) { if (epoch === libraryReadEpoch.current) setLibraryError(errorText(error)); } finally { if (epoch === libraryReadEpoch.current) setLibraryBusy(false); } }, []);
  const replaceLibrary = useCallback(value => { libraryReadEpoch.current++; setLibrary(value); setLibraryBusy(false); }, []);
  const notify = useCallback((text) => { setNotice(text); clearTimeout(noticeTimer.current); noticeTimer.current = setTimeout(() => setNotice(''), 4500); }, []);
  useEffect(() => {
    loadInfo(); loadLibrary();
    const refresh = event => { const owner = event?.detail?.owner; if (typeof owner === 'string' && owner !== libraryOwner.current) { libraryOwner.current = owner; libraryReadEpoch.current++; setLibrary({ ...EMPTY_LIBRARY }); setLibraryError(''); } loadLibrary(); };
    const changing = () => { accountTransition.current = true; libraryReadEpoch.current++; libraryOwner.current = null; setLibrary({ ...EMPTY_LIBRARY }); setLibraryBusy(true); setLibraryError(''); };
    const settled = () => { accountTransition.current = false; loadLibrary(); };
    window.addEventListener('androidresume', refresh);
    window.addEventListener('androidaccountchanged', refresh);
    window.addEventListener('androidaccountchanging', changing);
    window.addEventListener('androidaccountsettled', settled);
    return () => {
      libraryReadEpoch.current++;
      window.removeEventListener('androidresume', refresh);
      window.removeEventListener('androidaccountchanged', refresh);
      window.removeEventListener('androidaccountchanging', changing);
      window.removeEventListener('androidaccountsettled', settled);
      clearTimeout(noticeTimer.current);
    };
  }, [loadInfo, loadLibrary]);
  useEffect(() => { document.documentElement.dataset.tv = String(!!info.isTv); }, [info.isTv]);
  useTvNavigation(!!info.isTv);
  const closeDetail = useCallback(() => { setDetail(null); setTimeout(() => lastDetailFocus.current?.isConnected && lastDetailFocus.current.focus(), 0); }, []);
  const openDetail = (series, vid) => { lastDetailFocus.current = document.activeElement; previousScroll.current = content.current?.scrollTop || 0; setDetail({ seed: series, initialVid: vid }); };
  const navigate = (id) => { previousScroll.current = 0; setDetail(null); setTab(id); };
  useEffect(() => { if (content.current) content.current.scrollTop = 0; }, [tab]);
  useEffect(() => { if (content.current) content.current.scrollTop = detail ? 0 : previousScroll.current; if (detail) content.current?.querySelector('.back-button')?.focus(); }, [detail?.seed?.seriesId]);
  useEffect(() => {
    const back = () => { if (!window.dispatchEvent(new CustomEvent('androiddownloadback', { cancelable: true }))) return true; if (detail) { closeDetail(); return true; } if (tab !== 'discover') { setTab('discover'); return true; } return false; };
    window.__androidBack = back;
    const keydown = (event) => { if (event.key !== 'Escape' && event.key !== 'Backspace') return; if (event.target?.matches('input,textarea')) return; if (back()) event.preventDefault(); };
    document.addEventListener('keydown', keydown);
    return () => { document.removeEventListener('keydown', keydown); if (window.__androidBack === back) delete window.__androidBack; };
  }, [detail, tab, closeDetail]);
  return <div className={`android-app ${info.isTv ? 'tv-layout' : 'phone-layout'}`}>
    <nav className="app-nav" aria-label="主要导航"><div className="nav-brand"><span><Play size={21} /></span><strong>红果短剧</strong></div><div className="nav-items">{NAV.map(({ id, label, Icon }) => <button key={id} aria-current={!detail && tab === id ? 'page' : undefined} onClick={() => navigate(id)}><Icon size={22} /><span>{label === '我的追剧' ? <><span className="nav-word">我的</span><span className="nav-word">追剧</span></> : label}</span></button>)}</div>{info.isTv && <p className="nav-version">遥控器方向键选择<br />v{info.version || '—'}</p>}</nav>
    <div className="content-shell"><div className="phone-brand"><span><Play size={18} /></span><strong>红果短剧</strong><button className="brand-search" type="button" aria-label="搜索短剧" onClick={() => navigate('search')}><Search size={20} /></button></div>{infoError && <div className="environment-error" role="alert"><span>{infoError}</span><button onClick={loadInfo}>重试连接</button></div>}<main ref={content} className={`main-content${detail ? ' detail-open' : ''}`} id="main-content">
      {detail && <SeasonDetails key={detail.seed.seriesId} {...detail} library={library} onBack={closeDetail} onLibrary={replaceLibrary} notify={notify} isTv={!!info.isTv} />}
      <div className="page-slot" hidden={!!detail}>
        {tab === 'discover' && <Discover onOpen={openDetail} />}
        {tab === 'search' && <SearchPage onOpen={openDetail} />}
        {tab === 'library' && <Library library={library} error={libraryError} busy={libraryBusy} onRetry={loadLibrary} onOpen={openDetail} onAccount={() => { navigate('about'); setAccountFocus(value => value + 1); }} />}
        {tab === 'downloads' && !detail && <Downloads notify={notify} />}
        {tab === 'about' && <About info={info} notify={notify} accountFocus={accountFocus} />}
      </div>
    </main></div>{notice && <div className="toast" role="status">{notice}</div>}
  </div>;
}
