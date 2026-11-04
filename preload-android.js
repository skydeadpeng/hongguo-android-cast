/** Android-UI preload：contextBridge 注入安卓同款桥（RPC + 分季 + 播放器窗口）。
 *  contextIsolation 下 preload 看不到页面设置的全局回调，改为暴露注册函数由页面反挂。 */
const { contextBridge, ipcRenderer } = require('electron');

let responseSink = null, seriesSink = null, resumeSink = null;
ipcRenderer.on('android-rpc-response', (_event, message) => { try { responseSink && responseSink(message); } catch { } });
ipcRenderer.on('android-series-response', (_event, message) => { try { seriesSink && seriesSink(message); } catch { } });
ipcRenderer.on('android-resume', () => { try { resumeSink && resumeSink(); } catch { } });

contextBridge.exposeInMainWorld('AndroidBridge', {
  postMessage: text => { ipcRenderer.send('android-rpc', JSON.parse(text)); },
  onResponse: callback => { responseSink = callback; },
  onResume: callback => { resumeSink = callback; },
});
contextBridge.exposeInMainWorld('AndroidSeries', {
  postMessage: text => { ipcRenderer.send('android-series-rpc', JSON.parse(text)); },
  onResponse: callback => { seriesSink = callback; },
});
contextBridge.exposeInMainWorld('AndroidCrypto', {
  // 安卓 JNI 为同步契约：hash/解密都是小数据，走 sendSync。
  process: text => ipcRenderer.sendSync('android-crypto-sync', JSON.parse(text)),
});
contextBridge.exposeInMainWorld('HongguoPlayer', {
  onSession: handler => ipcRenderer.on('player-session', (_event, payload) => handler(payload)),
  onDownloadProgress: handler => ipcRenderer.on('player-download-progress', (_event, progress) => handler(progress)),
  resolve: async payload => {
    // 主进程 handler 直接返回解析结果（异常会以 rejected promise 形式抛出）。
    const result = await ipcRenderer.invoke('player-resolve', payload);
    if (!result || !result.playUrl) throw new Error('解析失败');
    return result;
  },
  progress: payload => { ipcRenderer.send('player-progress', payload); },
  setOption: payload => { ipcRenderer.send('player-option', payload); },
  close: () => ipcRenderer.send('player-close'),
});
