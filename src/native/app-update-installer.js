const { availableRelease } = require('./app-update');

function createUpdateInstaller({ updater, currentVersion, isEnabled, installBlocker, emit }) {
  updater.autoDownload = false;
  updater.autoInstallOnAppQuit = false;
  updater.allowDowngrade = false;
  updater.allowPrerelease = false;
  updater.disableWebInstaller = true;
  // Release ZIPs are rebuilt after notarization; their old blockmaps are invalid.
  updater.disableDifferentialDownload = true;
  updater.logger = null;
  updater.setFeedURL({ provider: 'github', owner: 'skydeadpeng', repo: 'hongguo-android-cast' });
  let state = { supported: true, reason: '', status: 'idle', version: null, percent: 0, error: '' };
  let operation = null, cancellationToken = null, generation = 0, operationGeneration = 0;
  let nativeInstallListener = null, nativeReadyVersion = null;
  const getState = () => ({ ...state });
  const update = patch => { state = { ...state, ...patch }; emit(getState()); };
  const fail = error => ({ success: false, error, state: getState() });
  updater.on('download-progress', progress => {
    if (state.status !== 'downloading' || !isEnabled()) return;
    const percent = Math.max(0, Math.min(100, Math.floor(Number(progress.percent) || 0)));
    if (percent !== state.percent) update({ percent });
  });
  updater.on('error', () => {
    if (!['checking', 'downloading', 'installing'].includes(state.status) || !isEnabled()) return;
    clearNativeInstall();
    nativeReadyVersion = null;
    update({ status: 'error', error: state.status === 'installing'
      ? '安装更新失败，当前版本仍可使用。请重试或前往发布页下载安装。'
      : '更新下载或校验失败，请检查网络后重试。' });
  });
  function cancel() {
    generation++;
    clearNativeInstall();
    nativeReadyVersion = null;
    update({ status: 'idle', version: null, percent: 0, error: '' });
    cancellationToken?.cancel();
    return { success: true, state: getState() };
  }
  function download(expectedVersion) {
    if (!isEnabled()) return Promise.resolve(fail('自动检测更新已关闭，请先在设置中开启。'));
    if (state.status === 'installing') return Promise.resolve(fail('正在安装更新。'));
    // The updater shares request state; finish cancelled work before starting a retry.
    if (operation) {
      if (operationGeneration === generation) return operation;
      const queuedGeneration = generation;
      return operation.then(() => queuedGeneration === generation ? download(expectedVersion) : fail('更新已取消。'));
    }
    if (state.status === 'downloaded' && state.version === expectedVersion) return Promise.resolve({ success: true, state: getState() });
    const request = ++generation;
    operationGeneration = request;
    update({ status: 'checking', version: expectedVersion, percent: 0, error: '' });
    operation = (async () => {
      try {
        const result = await updater.checkForUpdates();
        if (request !== generation || !isEnabled()) { result?.cancellationToken?.cancel(); return fail('更新已取消。'); }
        const release = availableRelease({ tag_name: result?.updateInfo?.version }, currentVersion);
        if (!release || release.version !== expectedVersion) {
          update({ status: 'error', error: '新版信息已变化，请重新检查更新。' });
          return fail(state.error);
        }
        cancellationToken = result.cancellationToken;
        update({ status: 'downloading' });
        await updater.downloadUpdate(cancellationToken);
        if (request !== generation || !isEnabled()) return fail('更新已取消。');
        // Only this operation's verified completion can make the update installable.
        update({ status: 'downloaded', percent: 100, error: '' });
        return { success: true, state: getState() };
      } catch (_) {
        if (request !== generation || !isEnabled()) return fail('更新已取消。');
        update({ status: 'error', error: '更新下载或校验失败，请检查网络后重试。' });
        return fail(state.error);
      }
    })().finally(() => { operation = null; cancellationToken = null; });
    return operation;
  }
  function clearNativeInstall() {
    if (nativeInstallListener) updater.nativeUpdater.removeListener('update-downloaded', nativeInstallListener);
    nativeInstallListener = null;
  }
  function finishInstall() {
    clearNativeInstall();
    if (!isEnabled() || state.status !== 'installing') return;
    const blocked = installBlocker();
    if (blocked) { update({ status: 'downloaded', error: blocked }); return; }
    try {
      if (updater.nativeUpdater && nativeReadyVersion !== state.version) {
        const version = state.version;
        // MacUpdater's implicit deferred quit listener survives errors; own its lifetime instead.
        nativeInstallListener = () => { nativeReadyVersion = version; finishInstall(); };
        updater.nativeUpdater.once('update-downloaded', nativeInstallListener);
        updater.nativeUpdater.checkForUpdates();
      } else updater.quitAndInstall(false, true);
    } catch (_) {
      clearNativeInstall();
      update({ status: 'error', error: '无法启动更新安装，请重试或前往发布页下载安装。' });
    }
  }
  function install() {
    if (!isEnabled() || state.status !== 'downloaded') return fail('请先完成更新下载。');
    const blocked = installBlocker();
    if (blocked) return fail(blocked);
    update({ status: 'installing', error: '' });
    // Finish the IPC response before starting native preparation or quitting.
    setImmediate(finishInstall);
    return { success: true, state: getState() };
  }
  return { getState, download, install, cancel };
}

module.exports = { createUpdateInstaller };
