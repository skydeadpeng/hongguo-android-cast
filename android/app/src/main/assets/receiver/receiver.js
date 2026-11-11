// The receiver reports real HTMLMediaElement events. HTTP acceptance is never playback proof.
export function createReceiver({ document, window, fetch: fetcher, setTimeout: later = setTimeout, clearTimeout: cancel = clearTimeout }) {
  const element = (id) => document.getElementById(id);
  const ui = Object.fromEntries(['pair-section','pair-form','pair-code','pair-submit','player-section','connection','status','video','title','quality','previous','toggle','next','autoplay','stop','start-play','airplay','airplay-section'].map(id => [id, element(id)]));
  const video = ui.video;
  const storageKey = 'hongguo.browser.receiver';
  let token = '', state = null, seq = 0, savedSession = '', mediaEpoch = 0, actionEpoch = 0;
  let disposed = false, activated = false, applying = false, sourceReady = false, pairing = false;
  let appliedCommand = -1, blockedCommand = -1, remotePause = false, remotePlay = false, remoteSeek = null;
  let pendingCommands = 0, commandTail = Promise.resolve(), stateFlight = null, pollTimer = null, failures = 0;
  let eventQueue = [], pumping = false, retryTimer = null, lastProgressAt = 0, endedMedia = '';
  let unbindMedia = () => {}, off = [], aborters = new Set();
  let playbackObservation = null, confirmedPlaybackCommand = -1;

  function listen(target, type, handler) { target.addEventListener(type, handler); return () => target.removeEventListener(type, handler); }
  function status(text, error = false) { ui.status.textContent = text; ui.status.dataset.error = String(error); }
  function remember() { try { window.sessionStorage.setItem(storageKey, JSON.stringify({ token, seq, sessionId: state?.sessionId || savedSession })); } catch (_) {} }
  function forget() { token = ''; try { window.sessionStorage.removeItem(storageKey); } catch (_) {} }
  function context() { return { epoch: mediaEpoch, mediaId: state?.media?.id, sessionId: state?.sessionId }; }
  function current(ctx) { return !disposed && ctx.epoch === mediaEpoch && ctx.mediaId === state?.media?.id && ctx.sessionId === state?.sessionId; }
  function milliseconds(value) { return Number.isFinite(value) && value >= 0 ? Math.floor(value * 1000) : 0; }
  function sample() { return { positionMs: milliseconds(video.currentTime), durationMs: milliseconds(video.duration) }; }
  function realSource() { return sourceReady && !!state?.media && video.currentSrc === new URL(state.media.url, window.location.href).href; }
  function genericName() {
    const agent = window.navigator?.userAgent || '';
    if (/iPhone/.test(agent)) return 'iPhone Safari';
    if (/iPad/.test(agent) || (/Macintosh/.test(agent) && window.navigator?.maxTouchPoints > 1)) return 'iPad 浏览器';
    if (/Macintosh|Mac OS/.test(agent)) return 'Mac 浏览器';
    return '浏览器接收端';
  }
  async function request(path, body, authenticated = true) {
    const controller = new AbortController();
    aborters.add(controller);
    const timeout = later(() => controller.abort(), 5000);
    try {
      const headers = authenticated ? { 'X-Cast-Token': token } : {};
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      const response = await fetcher(path, { method: body === undefined ? 'GET' : 'POST', headers, body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal, cache: 'no-store', credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer' });
      if (!response.ok) { const error = new Error('HTTP_' + response.status); error.status = response.status; throw error; }
      return await response.json();
    } finally { cancel(timeout); aborters.delete(controller); }
  }
  function buttons() {
    const media = state?.media, busy = pendingCommands > 0 || !media || !state?.active;
    ui.previous.disabled = busy || media.queuePosition <= 0;
    ui.next.disabled = busy || media.queuePosition >= media.count - 1;
    ui.toggle.disabled = busy;
    ui.autoplay.disabled = !state?.active || pendingCommands > 0;
    ui.stop.disabled = pendingCommands > 0;
    ui.toggle.textContent = video.paused || video.ended ? (video.ended ? '重新播放' : '播放') : '暂停';
    ui.autoplay.checked = !!state?.autoplay;
  }
  function disconnected(message) {
    playbackObservation = null; confirmedPlaybackCommand = -1;
    mediaEpoch++; actionEpoch++; eventQueue = []; unbindMedia();
    cancel(pollTimer); cancel(retryTimer); pollTimer = retryTimer = null;
    applying = true; video.pause(); video.removeAttribute('src'); video.load(); applying = false;
    sourceReady = false; state = null; appliedCommand = blockedCommand = -1; activated = false;
    forget(); ui['pair-section'].hidden = false; ui['player-section'].hidden = true;
    ui.connection.textContent = '未连接'; status(message, true); buttons();
  }
  function enqueueEvent(type, extra = {}) {
    if (!state?.active || !state.media || disposed || !realSource()) return;
    const record = { ctx: context(), action: actionEpoch, commandId: pendingCommands ? null : state.intent.commandId, type, ...sample(), ...extra };
    if (type === 'timeupdate') eventQueue = eventQueue.filter(item => item.type !== 'timeupdate');
    if (type === 'ended' && (endedMedia === state.media.id || eventQueue.some(item => item.type === 'ended' && item.ctx.mediaId === state.media.id))) return;
    if (eventQueue.length >= 24) {
      const expendable = eventQueue.findIndex(item => item.type === 'timeupdate' || item.type === 'waiting');
      if (expendable >= 0) eventQueue.splice(expendable, 1); else eventQueue.shift();
    }
    eventQueue.push(record); pumpEvents();
  }
  async function pumpEvents() {
    if (pumping || pendingCommands || !token || disposed || retryTimer) return;
    pumping = true;
    try {
      while (eventQueue.length && !pendingCommands && token && !disposed) {
        const record = eventQueue[0];
        if (!current(record.ctx) || record.action !== actionEpoch || (record.commandId !== null && record.commandId !== state.intent.commandId)) { eventQueue.shift(); continue; }
        const commandId = record.commandId ?? state.intent.commandId;
        if (!record.eventSeq) { record.eventSeq = ++seq; remember(); }
        const event = { sessionId: record.ctx.sessionId, mediaId: record.ctx.mediaId, eventSeq: record.eventSeq, commandId, type: record.type, positionMs: record.positionMs, durationMs: record.durationMs };
        if (record.errorCode !== undefined) event.errorCode = record.errorCode;
        try {
          await request('/event', event);
          if (eventQueue[0] === record) eventQueue.shift();
          if (record.type === 'ended' && current(record.ctx)) endedMedia = record.ctx.mediaId;
          if (record.type === 'playing' && current(record.ctx) && commandId === state.intent.commandId) { confirmedPlaybackCommand = commandId; playbackObservation = null; }
        } catch (error) {
          if (error.status === 401) { disconnected('连接已失效，请重新输入手机上的连接码。'); break; }
          if (!current(record.ctx)) continue;
          status('接收端状态尚未送达手机，正在重新连接。', true);
          const delay = Math.min(8000, 1000 * 2 ** Math.min(record.retries || 0, 3));
          record.retries = (record.retries || 0) + 1;
          retryTimer = later(() => { retryTimer = null; pumpEvents(); }, delay);
          break;
        }
      }
    } finally { pumping = false; }
  }
  function showBlocked(ctx, commandId, code = 'GESTURE_REQUIRED') {
    if (!current(ctx) || commandId !== state.intent.commandId || !state.intent.play) return;
    ui['start-play'].hidden = false;
    status('浏览器需要一次点按才能播放。请点“点按开始播放”。');
    if (blockedCommand !== commandId) { blockedCommand = commandId; enqueueEvent('blocked', { errorCode: code }); }
    buttons();
  }
  function attemptPlay(gesture) {
    if (!state?.media || !sourceReady) return;
    const ctx = context(), commandId = state.intent.commandId;
    if (gesture) activated = true;
    if (!activated) { showBlocked(ctx, commandId); return; }
    remotePlay = true;
    let promise;
    try { promise = video.play(); } catch (error) { promise = Promise.reject(error); }
    Promise.resolve(promise).catch(error => {
      if (!current(ctx)) return;
      if (error?.name === 'NotAllowedError') { activated = false; showBlocked(ctx, state.intent.commandId, 'NOT_ALLOWED'); }
      else if (error?.name !== 'AbortError') { status('视频尚未开始播放，请重试或在手机上更换画质。', true); enqueueEvent('error', { errorCode: 'PLAY_FAILED' }); }
    });
  }
  // Safari may keep the video playing through a seek without a second `playing` event.
  // Reconfirm only after its real media clock advances; a command ACK is not evidence.
  function observePlayback() {
    if (!realSource() || !state.intent.play || video.paused || video.ended || video.seeking || video.readyState < 3 || playbackObservation) return;
    if (eventQueue.some(item => item.type === 'playing' && current(item.ctx) && item.action === actionEpoch)) return;
    playbackObservation = { ctx: context(), action: actionEpoch, positionMs: milliseconds(video.currentTime) };
  }
  function applyIntent() {
    if (!state?.media || !sourceReady || pendingCommands || appliedCommand === state.intent.commandId) return;
    const intent = state.intent;
    appliedCommand = intent.commandId;
    applying = true;
    if (Number.isFinite(intent.seekMs) && intent.seekMs >= 0 && Math.abs(video.currentTime * 1000 - intent.seekMs) > 500) {
      remoteSeek = intent.seekMs;
      try { video.currentTime = intent.seekMs / 1000; } catch (_) { remoteSeek = null; }
    }
    if (intent.play) {
      if (video.paused || video.ended) attemptPlay(false);
      else if (confirmedPlaybackCommand !== intent.commandId) observePlayback();
    } else {
      remotePause = !video.paused;
      video.pause(); ui['start-play'].hidden = true;
    }
    applying = false; buttons();
  }
  function attachMedia(media) {
    mediaEpoch++; actionEpoch++; eventQueue = []; playbackObservation = null; confirmedPlaybackCommand = -1; cancel(retryTimer); retryTimer = null;
    unbindMedia(); sourceReady = false; appliedCommand = blockedCommand = -1; remoteSeek = null; remotePause = remotePlay = false; endedMedia = '';
    applying = true; video.pause(); video.src = media.url; video.load(); applying = false;
    const ctx = context(), listeners = [];
    const bind = (type, fn) => listeners.push(listen(video, type, () => { if (current(ctx)) fn(); }));
    bind('loadedmetadata', () => {
      if (video.currentSrc !== new URL(media.url, window.location.href).href) return;
      sourceReady = true; applyIntent(); buttons();
    });
    bind('play', () => {
      if (!realSource()) return;
      if (remotePlay || applying) { remotePlay = false; return; }
      if (!state.intent.play && !pendingCommands) command('play');
    });
    bind('playing', () => {
      if (!realSource() || video.paused || video.readyState < 2) return;
      activated = true; playbackObservation = null; ui['start-play'].hidden = true; blockedCommand = -1;
      status('正在播放'); enqueueEvent('playing'); buttons();
    });
    bind('timeupdate', () => {
      if (!realSource()) return;
      const observation = playbackObservation;
      if (observation && current(observation.ctx) && observation.action === actionEpoch && state.intent.play && !video.paused && !video.ended && !video.seeking && video.readyState >= 3 && milliseconds(video.currentTime) >= observation.positionMs + 250) {
        playbackObservation = null; enqueueEvent('playing'); lastProgressAt = 0;
      }
      const now = Date.now();
      if (now - lastProgressAt >= 900 || video.ended) { lastProgressAt = now; enqueueEvent('timeupdate'); }
      buttons();
    });
    bind('pause', () => {
      playbackObservation = null; confirmedPlaybackCommand = -1;
      if (!realSource() || video.ended) { remotePause = false; return; }
      if (applying || remotePause || pendingCommands) { remotePause = false; enqueueEvent('pause'); buttons(); return; }
      command('pause'); enqueueEvent('pause'); buttons();
    });
    bind('waiting', () => { playbackObservation = null; confirmedPlaybackCommand = -1; if (realSource() && !video.paused) { status('视频正在缓冲…'); enqueueEvent('waiting'); } });
    bind('seeking', () => {
      if (!realSource()) return;
      if (remoteSeek === null && !applying) command('seek', milliseconds(video.currentTime));
      enqueueEvent('seeking');
    });
    bind('seeked', () => { if (realSource()) { remoteSeek = null; confirmedPlaybackCommand = -1; enqueueEvent('seeked'); observePlayback(); } });
    bind('ended', () => {
      if (!realSource() || !video.ended || !Number.isFinite(video.duration) || video.duration <= 0 || video.currentTime < video.duration - 1) return;
      status(state.autoplay && state.media.queuePosition < state.media.count - 1 ? '本集已播完，等待手机准备下一集。' : '本集已播完。'); enqueueEvent('ended'); buttons();
    });
    bind('error', () => {
      if (!sourceReady) sourceReady = video.currentSrc === new URL(media.url, window.location.href).href;
      const code = Number(video.error?.code) || 0;
      status(code === 4 ? '此浏览器不支持该视频格式，请在手机上更换画质后重试。' : '视频加载失败，请检查手机连接后重试。', true);
      enqueueEvent('error', { errorCode: code });
    });
    unbindMedia = () => listeners.forEach(remove => remove());
    ui['start-play'].hidden = activated;
  }
  function acceptState(next) {
    if (!next || typeof next.sessionId !== 'string' || !next.intent || !Number.isFinite(next.intent.commandId)) throw new Error('INVALID_STATE');
    if (state?.sessionId === next.sessionId && Number(next.revision) < Number(state.revision)) return;
    const old = state;
    if (savedSession !== next.sessionId) { seq = 0; savedSession = next.sessionId; }
    seq = Math.max(seq, Number(next.lastEventSeq) || 0);
    if (!next.active) { disconnected('投屏已结束。再次投屏请在手机上开启新连接。'); return; }
    state = next; remember();
    ui['pair-section'].hidden = true; ui['player-section'].hidden = false; ui.connection.textContent = '已连接手机';
    ui.title.textContent = next.media?.title || '等待手机准备视频';
    ui.quality.textContent = next.media ? `第 ${Number(next.media.episodeIndex)} 集 · ${next.media.quality || ''}` : '';
    if (next.media?.id !== old?.media?.id || next.sessionId !== old?.sessionId) {
      if (next.media) attachMedia(next.media);
      else { mediaEpoch++; unbindMedia(); sourceReady = false; applying = true; video.pause(); video.removeAttribute('src'); video.load(); applying = false; }
    } else if (old && old.intent.commandId !== next.intent.commandId && !pendingCommands) {
      actionEpoch++; eventQueue = []; endedMedia = '';
    }
    if (next.error) status('手机提示：' + String(next.error).slice(0, 200), true);
    else if (next.state === 'PREPARING') status('手机正在准备视频…');
    else if (next.state === 'DISCONNECTED') { status('正在恢复与手机的播放状态，请保持此页面打开。', true); confirmedPlaybackCommand = -1; observePlayback(); }
    else if (next.state === 'BUFFERING') status('视频正在缓冲…');
    else if (next.state === 'WAITING_RECEIVER_PLAY' && !video.paused && !video.ended) { confirmedPlaybackCommand = -1; observePlayback(); }
    else if (next.state === 'WAITING_RECEIVER' || !next.media) status('已连接，等待手机准备视频。');
    else if (next.state === 'PAUSED') status('已暂停');
    else if (next.state === 'ENDED') status(next.autoplay && next.media?.queuePosition < next.media?.count - 1 ? '本集已播完，等待下一集。' : '本集已播完。');
    else if (next.state === 'PLAYING' && !video.paused && realSource()) status('正在播放');
    applyIntent(); buttons(); pumpEvents();
  }
  async function readState(fresh = false) {
    while (stateFlight) { await stateFlight; if (!fresh) return; }
    if (!token || disposed) return;
    const flight = request('/state').then(acceptState);
    stateFlight = flight;
    try { await flight; } finally { if (stateFlight === flight) stateFlight = null; }
  }
  function schedulePoll(delay = 1000) { cancel(pollTimer); if (token && !disposed) pollTimer = later(poll, delay); }
  async function poll() {
    if (!token || disposed) return;
    try { await readState(); failures = 0; }
    catch (error) {
      if (disposed) return;
      if (error.status === 401) { disconnected('连接已失效，请重新输入手机上的连接码。'); return; }
      failures++; ui.connection.textContent = '连接中断'; status('正在重新连接手机，请检查局域网和手机投屏服务。', true);
    }
    schedulePoll(Math.min(8000, 1000 * 2 ** Math.min(failures, 3)));
  }
  function command(type, value, localAction) {
    if (!state?.active || (!state.media && type !== 'stop') || disposed || pendingCommands >= 8) return Promise.resolve();
    const ctx = context();
    pendingCommands++; actionEpoch++; eventQueue = []; endedMedia = ''; cancel(retryTimer); retryTimer = null;
    if (localAction) localAction();
    buttons();
    const operation = async () => {
      if (!current(ctx) && type !== 'stop') return;
      try {
        const payload = { sessionId: ctx.sessionId, mediaId: ctx.mediaId, type };
        if (value !== undefined) payload.value = value;
        const beforeCommand = state.intent.commandId, beforeRevision = state.revision;
        await request('/command', payload);
        if (type === 'stop') { disconnected('投屏已结束。'); return; }
        // HTTP acceptance may precede the phone's serial state-machine mutation.
        // Keep genuine media events behind the command barrier until its new state is visible.
        const deadline = Date.now() + 5000;
        for (;;) {
          await readState(true);
          if (!current(ctx)) break;
          const acknowledged = ['play','pause','seek'].includes(type)
            ? state.intent.commandId > beforeCommand
            : type === 'autoplay'
              ? state.revision > beforeRevision && state.autoplay === value
              : state.media?.id !== ctx.mediaId;
          if (acknowledged) break;
          if (disposed || Date.now() >= deadline) throw new Error('COMMAND_NOT_APPLIED');
          await new Promise(resolve => later(resolve, 50));
        }
      } catch (error) {
        if (error.status === 401) disconnected('连接已失效，请重新输入手机上的连接码。');
        else if (current(ctx)) { actionEpoch++; eventQueue = []; status('控制指令未确认，请稍后重试。', true); }
      }
    };
    const result = commandTail.then(operation).finally(() => { pendingCommands--; applyIntent(); buttons(); pumpEvents(); });
    commandTail = result.catch(() => {});
    return result;
  }
  function userPlay() { return command('play', undefined, () => { if (video.ended) { remoteSeek = 0; video.currentTime = 0; } attemptPlay(true); }); }
  async function pair(event) {
    event.preventDefault();
    if (pairing || disposed) return;
    const code = ui['pair-code'].value.trim();
    if (!/^\d{6}$/.test(code)) { status('请输入手机上的 6 位数字连接码。', true); return; }
    pairing = true; ui['pair-submit'].disabled = true;
    try {
      const result = await request('/pair', { code, name: genericName() }, false);
      if (typeof result.token !== 'string' || !/^[a-zA-Z0-9_-]{32,128}$/.test(result.token)) throw new Error('INVALID_PAIR');
      token = result.token; seq = 0; savedSession = ''; remember(); ui['pair-code'].value = ''; failures = 0;
      await readState(); schedulePoll();
    } catch (error) {
      status(error.status === 410 ? '连接码已过期，请在手机上结束投屏，再重新开启浏览器投屏。' : error.status === 409 ? '已有其他接收设备连接，请先在手机上结束原连接。' : error.status === 429 ? '连接码尝试次数较多，请一分钟后重试。' : error.status === 403 || error.status === 401 ? '连接码不匹配或已过期，请查看手机上的连接信息。' : '连接未完成，请确认手机仍在投屏并连接同一局域网。', true);
    } finally { pairing = false; ui['pair-submit'].disabled = false; }
  }
  function start() {
    off.push(listen(ui['pair-form'], 'submit', pair));
    off.push(listen(ui['start-play'], 'click', userPlay));
    off.push(listen(ui.toggle, 'click', () => { if (video.paused || video.ended) userPlay(); else command('pause', undefined, () => { remotePause = true; video.pause(); }); }));
    off.push(listen(ui.previous, 'click', () => command('previous')));
    off.push(listen(ui.next, 'click', () => command('next')));
    off.push(listen(ui.autoplay, 'change', () => command('autoplay', ui.autoplay.checked)));
    off.push(listen(ui.stop, 'click', () => command('stop')));
    const airplay = typeof video.webkitShowPlaybackTargetPicker === 'function';
    ui['airplay-section'].hidden = !airplay;
    if (airplay) off.push(listen(ui.airplay, 'click', () => { try { video.webkitShowPlaybackTargetPicker(); } catch (_) { status('请在 Safari 中打开系统 AirPlay 选择器后重试。', true); } }));
    off.push(listen(window, 'online', () => schedulePoll(0)));
    off.push(listen(document, 'visibilitychange', () => { if (!document.hidden) schedulePoll(0); }));
    try {
      const saved = JSON.parse(window.sessionStorage.getItem(storageKey) || 'null');
      if (saved && typeof saved.token === 'string' && /^[a-zA-Z0-9_-]{32,128}$/.test(saved.token)) { token = saved.token; seq = Number(saved.seq) || 0; savedSession = String(saved.sessionId || ''); poll(); }
    } catch (_) {}
    buttons();
    return api;
  }
  function destroy() { disposed = true; cancel(pollTimer); cancel(retryTimer); for (const controller of aborters) controller.abort(); off.forEach(remove => remove()); unbindMedia(); eventQueue = []; }
  const api = { start, destroy, inspect: () => ({ connected: !!token, mediaId: state?.media?.id || '', queuedEvents: eventQueue.length, pendingCommands, activated, sourceReady }) };
  return api;
}
if (typeof window !== 'undefined' && typeof document !== 'undefined') createReceiver({ document, window, fetch: window.fetch.bind(window) }).start();
