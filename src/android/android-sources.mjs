import { nativeCall } from './bridge.mjs';

// The shared page receives only source/series/episode identifiers, not upstream
// media addresses or request headers. Keep its API stable across renders.
const androidSourcesApi = Object.freeze({
  list: () => nativeCall('sourceService', { operation: 'list' }, 5000),
  settings: ({ sourceId, enabled }) => nativeCall('sourceService', { operation: 'settings', sourceId, enabled }, 5000),
  categories: ({ sourceId }) => nativeCall('sourceService', { operation: 'categories', sourceId }, 50000),
  catalog: ({ sourceId, category, page }) => nativeCall('sourceService', { operation: 'catalog', sourceId, category, page }, 50000),
  search: ({ sourceId, keyword, page }) => nativeCall('sourceService', { operation: 'search', sourceId, keyword, page }, 50000),
  detail: ({ sourceId, seriesId }) => nativeCall('sourceService', { operation: 'detail', sourceId, seriesId }, 50000),
  play: ({ sourceId, seriesId, episodeId, variant, currentIndex }) => nativeCall('sourceService', { operation: 'play', sourceId, seriesId, episodeId, variant, currentIndex }, 65000),
  account: ({ sourceId, command = '', payload }) => {
    const params = { operation: 'account', sourceId, command };
    if (command === 'login' && payload) {
      const { identifier, password, captchaId, track } = payload;
      params.payload = { identifier, password, captchaId, track };
    }
    return nativeCall('sourceService', params, 50000);
  },
  cancel: ({ sourceId }) => nativeCall('sourceService', { operation: 'cancel', sourceId }, 5000),
});

export function getAndroidSourcesApi(info, bridge = globalThis.window?.AndroidBridge) {
  return info?.sourceService === true && typeof bridge?.postMessage === 'function' ? androidSourcesApi : undefined;
}
