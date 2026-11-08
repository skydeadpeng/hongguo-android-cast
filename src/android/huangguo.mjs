// 黄果漫剧数据层：分类、搜索、详情与播放入口，全部经原生桥访问官方站。
// 剧集编号在原生侧编码为 99 前缀数字，UI 不感知红果/黄果 ID 差异。
import { nativeCall } from './bridge.mjs';

export const HUANGGUO_CATEGORIES = [
  { id: 'ai-manju', label: 'AI成人漫剧' },
  { id: 'ai-duanju', label: 'AI成人短剧' },
];

const clean = value => String(value || '').trim();

export async function huangguoFeed(category, page = 1) {
  const result = await nativeCall('huangguoFeed', { category, page }, 30000);
  if (!Array.isArray(result?.items)) throw new Error('黄果目录返回无效，请重试');
  return { items: result.items, hasMore: result.hasMore === true, page };
}

export async function huangguoSearch(keyword, page = 1) {
  const result = await nativeCall('huangguoSearch', { keyword: clean(keyword), page }, 30000);
  if (!Array.isArray(result?.items)) throw new Error('黄果搜索返回无效，请重试');
  return { items: result.items, hasMore: result.hasMore === true, page };
}

export async function huangguoDetail(seriesId) {
  const result = await nativeCall('huangguoDetail', { seriesId }, 30000);
  if (String(result?.seriesId) !== String(seriesId) || !Array.isArray(result?.episodes)) {
    throw new Error('黄果详情返回无效，请重试');
  }
  return result;
}

export async function huangguoPlay(series, episode) {
  const params = {
    seriesId: String(series.seriesId || ''),
    title: clean(series.title) || '黄果漫剧',
    coverUrl: clean(series.coverUrl),
    episodeCount: Number(series.episodeCount) || 0,
    index: Number(episode.index) || 1,
  };
  return nativeCall('huangguoPlay', params, 60000);
}
