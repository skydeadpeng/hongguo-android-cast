import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ChevronRight, Film, Search } from '../components/icons.jsx';

const failureText = error => error?.message?.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '') || '请求未完成，请重试。';

export function useJuguoSettings(api) {
  const [value, setValue] = useState({ ready: false, busy: false, enabled: false, error: '' });
  const pending = useRef(false);
  const update = useCallback(async patch => {
    if (!api || pending.current) return;
    pending.current = true;
    setValue(previous => ({ ...previous, busy: true, error: '' }));
    try {
      const next = await api.settings(patch ?? {});
      setValue({ ready: true, busy: false, enabled: next?.enabled === true, error: '' });
    } catch (error) {
      setValue(previous => ({ ...previous, busy: false, error: failureText(error) }));
    } finally {
      pending.current = false;
    }
  }, [api]);
  useEffect(() => { update(); }, [update]);
  return { ...value, update };
}

export function JuguoSettings({ config }) {
  return <section className="about-section" aria-label="剧果设置">
    <h2>剧果</h2>
    <div className="playback-setting">
      <div><strong>启用剧果</strong><p id="juguo-description">限制级第三方来源，默认关闭。开启后在导航显示剧果，仅支持在线播放。</p></div>
      <button className="setting-switch" type="button" role="switch" aria-label="启用剧果" aria-describedby="juguo-description" aria-checked={config.enabled} disabled={!config.ready || config.busy} onClick={() => config.update({ enabled: !config.enabled })}>{config.busy ? '处理中…' : config.enabled ? '开启' : '关闭'}</button>
    </div>
    {config.error && <><p className="text-error" role="alert">{config.error}</p><button className="button" type="button" onClick={() => config.update()}>重新读取剧果设置</button></>}
  </section>;
}

export function JuguoPage({ api, PosterGrid, Status }) {
  const [categories, setCategories] = useState({ busy: true, items: [], error: '' });
  const [category, setCategory] = useState('');
  const [keyword, setKeyword] = useState('');
  const [view, setView] = useState(null);
  const [list, setList] = useState({ busy: false, items: [], hasMore: false, error: '' });
  const [selected, setSelected] = useState(null);
  const [detail, setDetail] = useState({ busy: false, data: null, error: '' });
  const [play, setPlay] = useState({ episodeId: null, error: '' });
  const categoryEpoch = useRef(0);
  const detailEpoch = useRef(0);
  const lastPoster = useRef(null);
  const playPending = useRef(false);

  const loadCategories = useCallback(async () => {
    const epoch = ++categoryEpoch.current;
    setCategories(previous => ({ ...previous, busy: true, error: '' }));
    try {
      const items = await api.categories();
      if (epoch !== categoryEpoch.current) return;
      if (!Array.isArray(items) || !items.length) throw new Error('剧果未返回分类。');
      setCategories({ busy: false, items, error: '' });
      setCategory(items[0].id);
      setView(previous => previous?.query ? previous : { category: items[0].id, query: '', page: 1 });
    } catch (error) {
      if (epoch === categoryEpoch.current) setCategories(previous => ({ ...previous, busy: false, error: failureText(error) }));
    }
  }, [api]);
  useEffect(() => {
    loadCategories();
    return () => { categoryEpoch.current++; detailEpoch.current++; };
  }, [loadCategories]);

  useEffect(() => {
    if (!view) return undefined;
    let current = true;
    const append = view.page > 1;
    setList(previous => ({ view, busy: true, items: append ? previous.items : [], hasMore: append && previous.hasMore, error: '' }));
    const load = async () => {
      try {
        const result = view.query ? await api.search({ keyword: view.query, page: view.page }) : await api.catalog({ category: view.category, page: view.page });
        if (!current) return;
        setList(previous => {
          const unique = new Map((append ? previous.items : []).map(item => [item.seriesId, item]));
          for (const item of result.items) unique.set(item.seriesId, item);
          return { view, busy: false, items: [...unique.values()], hasMore: result.hasMore === true, error: '' };
        });
      } catch (error) {
        if (current) setList(previous => ({ ...previous, busy: false, error: failureText(error) }));
      }
    };
    load();
    return () => { current = false; };
  }, [api, view]);

  useEffect(() => {
    if (!selected) return undefined;
    const epoch = ++detailEpoch.current;
    setDetail({ busy: true, data: null, error: '' });
    const load = async () => {
      try {
        const data = await api.detail({ seriesId: selected.seriesId });
        if (epoch === detailEpoch.current) setDetail({ busy: false, data, error: '' });
      } catch (error) {
        if (epoch === detailEpoch.current) setDetail({ busy: false, data: null, error: failureText(error) });
      }
    };
    load();
    return () => { detailEpoch.current++; };
  }, [api, selected]);

  const openDetail = item => {
    lastPoster.current = document.activeElement;
    setDetail({ busy: true, data: null, error: '' });
    setPlay({ episodeId: null, error: '' });
    setSelected(item);
  };
  const closeDetail = useCallback(() => {
    detailEpoch.current++;
    setSelected(null);
    requestAnimationFrame(() => { if (lastPoster.current?.isConnected) lastPoster.current.focus(); });
  }, []);
  useEffect(() => {
    const back = event => {
      if (!selected) return;
      event.preventDefault();
      closeDetail();
    };
    window.addEventListener('juguoback', back);
    return () => window.removeEventListener('juguoback', back);
  }, [closeDetail, selected]);

  async function playEpisode(episode) {
    if (!detail.data || episode.playable !== true || playPending.current) return;
    const epoch = detailEpoch.current;
    playPending.current = true;
    setPlay({ episodeId: episode.episodeId, error: '' });
    try {
      await api.play({ seriesId: detail.data.seriesId, episodeId: episode.episodeId });
      if (epoch === detailEpoch.current) setPlay({ episodeId: null, error: '' });
    } catch (error) {
      if (epoch === detailEpoch.current) setPlay({ episodeId: null, error: `第 ${episode.index} 集播放失败：${failureText(error)}` });
    } finally {
      playPending.current = false;
    }
  }

  const loadCatalog = id => {
    if (!id) return;
    setCategory(id);
    setKeyword('');
    setView({ category: id, query: '', page: 1 });
  };
  const search = event => {
    event.preventDefault();
    const query = keyword.trim();
    if (query) setView({ category, query, page: 1 });
    else if (category) loadCatalog(category);
    else setView(null);
  };
  const visibleList = list.view?.category === view?.category && list.view?.query === view?.query ? list : { busy: true, items: [] };

  return <section aria-label="剧果短剧">
    <div hidden={!!selected}>
      <header className="page-heading"><h1>剧果短剧</h1><p>限制级第三方来源 · 仅在线播放</p></header>
      <form className="search-form" onSubmit={search}>
        <label className="visually-hidden" htmlFor="juguo-query">搜索剧果剧集</label>
        <div className="search-controls">
          <input id="juguo-query" type="search" value={keyword} onChange={event => setKeyword(event.target.value)} placeholder="搜索剧果剧集" maxLength={100} />
          <button type="submit" className="button"><Search size={18} />搜索</button>
          {!!view?.query && <button type="button" className="button" onClick={() => { if (category) loadCatalog(category); else { setKeyword(''); setView(null); } }}>清除</button>}
        </div>
      </form>
      {!view?.query && <Status busy={categories.busy} error={categories.error} onRetry={loadCategories}>
        <div className="category-tabs" role="group" aria-label="剧果分类">{categories.items.map(item => <button type="button" key={item.id} aria-pressed={category === item.id} onClick={() => loadCatalog(item.id)}>{item.label}</button>)}</div>
      </Status>}
      {view && <>
        {view.query && <p className="result-caption">「{view.query}」的搜索结果</p>}
        <Status busy={visibleList.busy} error={visibleList.error} preserve={visibleList.items.length > 0} onRetry={() => setView({ ...view })} empty={!visibleList.items.length && { title: '没有找到剧集', text: view.query ? '换个关键词试试。' : '这个分类暂无剧集。' }}>
          <PosterGrid items={visibleList.items} onOpen={openDetail} />
          {visibleList.items.length > 0 && visibleList.hasMore && !visibleList.error && <div className="feed-more"><button className="button" type="button" disabled={visibleList.busy} onClick={() => setView({ ...view, page: view.page + 1 })}>{visibleList.busy ? '正在加载…' : '加载更多'}</button></div>}
        </Status>
      </>}
    </div>
    {selected && <div>
      <button type="button" className="back-button" autoFocus onClick={closeDetail}><ChevronRight />返回剧果</button>
      <Status busy={detail.busy} error={detail.error} onRetry={() => setSelected({ ...selected })}>
        {detail.data && <>
          <header className="detail-summary">
            <span className="detail-cover">{detail.data.coverUrl ? <img src={detail.data.coverUrl} alt="" referrerPolicy="no-referrer" /> : <Film size={28} />}</span>
            <div className="detail-copy"><h1>{detail.data.title}</h1><p className="detail-meta">{detail.data.episodeCount || detail.data.episodes.length} 集</p><p className="detail-description">{detail.data.description || '暂无简介'}</p></div>
          </header>
          <section className="episode-section" aria-label="选集">
            <header><h2>选集</h2><p>仅播放站点开放的分集</p></header>
            {!!play.episodeId && <p className="episode-note" role="status">正在打开播放器…</p>}
            {play.error && <p className="text-error episode-note" role="alert">{play.error}</p>}
            {detail.data.episodes.length ? <div className="episode-grid">
              {detail.data.episodes.map(episode => <button type="button" key={episode.episodeId} aria-label={`第 ${episode.index} 集${episode.playable ? '' : '（暂不可播）'}`} disabled={episode.playable !== true || !!play.episodeId} onClick={() => playEpisode(episode)}>{episode.index}</button>)}
            </div> : <p className="empty-episodes">站点未返回分集。</p>}
          </section>
        </>}
      </Status>
    </div>}
  </section>;
}
