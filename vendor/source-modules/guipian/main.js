const siteBaseUrl = "https://guipianwu.com";
const userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
const pageSize = 24;
const sourceName = "鬼片网";

const categories = [
  { id: "3", name: "恐怖片" },
  { id: "6", name: "大陆鬼片" },
  { id: "7", name: "日韩鬼片" },
  { id: "8", name: "林正英鬼片" },
  { id: "9", name: "港台鬼片" },
  { id: "10", name: "泰国鬼片" },
  { id: "11", name: "欧美鬼片" },
  { id: "31", name: "剧情片" },
  { id: "12", name: "国产剧" },
  { id: "13", name: "美剧" },
  { id: "14", name: "韩剧" },
  { id: "15", name: "日剧" },
  { id: "16", name: "泰剧" },
  { id: "17", name: "港台剧" },
  { id: "18", name: "其他剧" },
  { id: "23", name: "日韩动漫" },
  { id: "24", name: "国产动漫" },
  { id: "25", name: "欧美动漫" },
  { id: "26", name: "港台动漫" },
];

const mainListPattern = /<ul class="row video-list video-film-list[^"]*">(.*?)<\/ul>/is;
const cardPattern = /<a class="video-link" href="\/nv\/(\d+)\.html"[^>]*title="([^"]*)"(.*?)<\/a>/gis;
const coverPattern = /data-(?:original|background)="([^"]+)"/is;
const remarkPattern = /<div class="video-duration">(.*?)<\/div>/is;
const titlePattern = /<h1 class="media-title[^"]*">(.*?)<\/h1>/is;
const titleTagPattern = /<title>(.*?)<\/title>/is;
const detailPicturePattern = /<div class="detail-img"><img[^>]+src="([^"]+)"/is;
const detailCoverPattern = /<div class="detail-cover[^"]*"[^>]*data-original="([^"]+)"/is;
const contentPattern = /简介：(.*?)<\/li>/is;
const yearPattern = /年份：<span>(.*?)<\/span>/is;
const directorPattern = /导演：(.*?)<\/span>/is;
const actorPattern = /主演：(.*?)<\/span>/is;
const anchorPattern = /<a[^>]*>([^<]+)<\/a>/gis;
const areaPattern = /地区：<span><a[^>]*>(.*?)<\/a>/is;
const statusPattern = /状态：(.*?)<\/span>/is;
const tabPattern = /<li class="swiper-slide ewave-tab[^"]*" data-target="#(ewave-playlist-\d+)">([^<]+)<\/li>/gis;
const episodePattern = /<li><a title='([^']*)' href='([^']+)'/gis;
const nowPattern = /var\s+now\s*=\s*["']([^"']+)["']/is;
const mediaPattern = /https?:\/\/[^"'\s<>\\]+\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?/i;
const leadingNumberPattern = /(\d+)/;
const rssItemPattern = /<item>(.*?)<\/item>/gis;
const rssTitlePattern = /<title>\s*(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?\s*<\/title>/is;
const rssLinkPattern = /\/nv\/(\d+)\.html/is;
const tagPattern = /<[^>]+>/g;

const namedEntities = { "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"', "&apos;": "'", "&#39;": "'", "&nbsp;": " " };

function truncate(text, limit) {
  const trimmed = text.trim();
  const characters = Array.from(trimmed);
  return characters.length <= limit ? trimmed : characters.slice(0, limit).join("") + "...";
}

function unescapeHtml(text) {
  for (let round = 0; round < 3; round++) {
    const next = text
      .replace(/&(?:amp|lt|gt|quot|apos|#39|nbsp);/g, (entity) => namedEntities[entity])
      .replace(/&#x([0-9a-fA-F]+);/g, (entity, hex) => String.fromCodePoint(parseInt(hex, 16)))
      .replace(/&#(\d+);/g, (entity, decimal) => String.fromCodePoint(parseInt(decimal, 10)));
    if (next === text) {
      break;
    }
    text = next;
  }
  return text;
}

function clean(text) {
  if (!text) {
    return "";
  }
  return unescapeHtml(text.replace(tagPattern, ""))
    .replace(/　/g, " ")
    .split(/\s+/)
    .filter(Boolean)
    .join(" ");
}

function field(body, pattern) {
  const match = body.match(pattern);
  return match ? clean(match[1]) : "";
}

function validCategory(category) {
  if (category === "") {
    return true;
  }
  return category.length <= 16 && !/[|/\\\x00\r\n]/.test(category) && categories.some((entry) => entry.id === category);
}

function numericId(value) {
  const trimmed = value.trim();
  return /^\d{1,12}$/.test(trimmed) && parseInt(trimmed, 10) > 0;
}

function parseHttpUrl(raw) {
  let parsed;
  try {
    parsed = host.parseUrl(raw);
  } catch (failure) {
    return null;
  }
  const http = parsed.scheme === "https" || parsed.scheme === "http";
  return http && parsed.hostname !== "" && !parsed.userInfo ? parsed : null;
}

function fixUrl(raw) {
  raw = raw.trim();
  if (raw === "") {
    return "";
  }
  if (raw.startsWith("//")) {
    raw = "https:" + raw;
  } else if (raw.startsWith("/")) {
    raw = siteBaseUrl + raw;
  }
  const parsed = parseHttpUrl(raw);
  return parsed ? parsed.href : "";
}

function playUrl(href) {
  href = href.trim();
  if (href === "") {
    return "";
  }
  if (!href.startsWith("http")) {
    href = siteBaseUrl + "/" + href.replace(/^\//, "");
  }
  const parsed = parseHttpUrl(href);
  if (!parsed) {
    return "";
  }
  const hostname = parsed.hostname.toLowerCase();
  if (hostname !== "guipianwu.com" && hostname !== "www.guipianwu.com") {
    return "";
  }
  if (!parsed.path.toLowerCase().endsWith(".html") || !parsed.path.includes("/play/")) {
    return "";
  }
  return parsed.href;
}

function get(path) {
  const url = path.startsWith("http") ? path : siteBaseUrl + path;
  return host.fetchText({ url, referer: siteBaseUrl + "/", userAgent }).body;
}

function listedDrama(id, title, extra) {
  return Object.assign(
    {
      id: host.dramaId(id),
      source: host.source,
      sourceId: id,
      title: truncate(title, 512),
      name: truncate(title, 512),
      channelName: sourceName,
      categoryName: "影视",
    },
    extra
  );
}

function parseCards(body) {
  const mainList = body.match(mainListPattern);
  const segment = mainList ? mainList[1] : body;
  const items = [];
  const seen = new Set();
  for (const match of segment.matchAll(cardPattern)) {
    const id = match[1];
    if (!numericId(id) || seen.has(id)) {
      continue;
    }
    const title = clean(match[2]);
    if (title === "") {
      continue;
    }
    seen.add(id);
    const coverMatch = match[3].match(coverPattern);
    const cover = coverMatch ? fixUrl(coverMatch[1]) : "";
    items.push(listedDrama(id, title, { cover, coverUrl: cover, remark: truncate(field(match[3], remarkPattern), 64) }));
    if (items.length > 500) {
      break;
    }
  }
  return items;
}

function pageCount(body, category, page, count) {
  const pattern = new RegExp("/list/" + category + "_(\\d+)\\.html", "gis");
  let maxPage = page;
  for (const match of body.matchAll(pattern)) {
    const value = parseInt(match[1], 10);
    if (value > maxPage && value < 100000) {
      maxPage = value;
    }
  }
  if (maxPage > page) {
    return maxPage;
  }
  return count >= pageSize ? page + 1 : page;
}

function actors(body) {
  const match = body.match(actorPattern);
  if (!match) {
    return "";
  }
  const names = Array.from(match[1].matchAll(anchorPattern), (anchor) => clean(anchor[1])).filter(Boolean);
  return names.length > 0 ? names.join(" ") : clean(match[1]);
}

function pickPlaylist(body, sourceId) {
  const tabs = Array.from(body.matchAll(tabPattern));
  if (tabs.length === 0) {
    throw new Error("鬼片网未找到可用播放线路");
  }
  let best = [];
  const seenAnchors = new Set();
  for (const tab of tabs) {
    const anchor = tab[1];
    if (seenAnchors.has(anchor)) {
      continue;
    }
    seenAnchors.add(anchor);
    const marker = 'id="' + anchor + '"';
    const start = body.indexOf(marker);
    if (start < 0) {
      continue;
    }
    let segment = body.slice(start + marker.length);
    let end = segment.indexOf('<div class="playlist-slide');
    if (end < 0) {
      end = segment.indexOf("</section>");
    }
    if (end >= 0) {
      segment = segment.slice(0, end);
    }
    const chapters = [];
    const seenUrls = new Set();
    let index = 0;
    for (const episode of segment.matchAll(episodePattern)) {
      index++;
      let title = clean(episode[1]);
      const url = playUrl(episode[2]);
      if (url === "" || seenUrls.has(url)) {
        continue;
      }
      seenUrls.add(url);
      let order = index;
      const number = title.match(leadingNumberPattern);
      if (number && number[1].length <= 6) {
        const parsed = parseInt(number[1], 10);
        if (parsed > 0 && parsed <= 100000) {
          order = parsed;
        }
      }
      if (title === "") {
        title = "第 " + order + " 集";
      }
      chapters.push({
        id: host.chapterId(sourceId, url),
        source: host.source,
        title: truncate(title, 128),
        currentEpisode: order,
        videoUrl: "guipian-play://" + url,
        pageUrl: url,
        referer: siteBaseUrl + "/",
      });
    }
    if (chapters.length > best.length) {
      best = chapters;
    }
  }
  if (best.length === 0) {
    throw new Error("鬼片网线路暂无可播放分集");
  }
  return best.sort((left, right) => left.currentEpisode - right.currentEpisode);
}

module.exports = {
  validCategory,

  categories() {
    return categories;
  },

  catalogPage(page, category) {
    if (page < 1 || page > 100000) {
      throw new Error("鬼片网目录页码无效");
    }
    if (!validCategory(category)) {
      throw new Error("鬼片网内容分类无效");
    }
    category = category || categories[0].id;
    const path = page > 1 ? "/list/" + category + "_" + page + ".html" : "/list/" + category + ".html";
    const body = get(path);
    const items = parseCards(body);
    if (items.length === 0) {
      return { items: [], hasMore: false };
    }
    return { items, hasMore: page < pageCount(body, category, page, items.length) };
  },

  search(page, query) {
    query = query.trim();
    if (query === "" || query.length > 256) {
      throw new Error("鬼片网搜索关键词无效");
    }
    if (page < 1 || page > 100000) {
      throw new Error("鬼片网目录页码无效");
    }
    const needle = query.toLowerCase();
    const matched = [];
    const seen = new Set();
    for (const item of get("/xml/rss.xml").matchAll(rssItemPattern)) {
      const titleMatch = item[1].match(rssTitlePattern);
      const idMatch = item[1].match(rssLinkPattern);
      if (!titleMatch || !idMatch) {
        continue;
      }
      const title = clean(titleMatch[1]);
      const id = idMatch[1];
      if (title === "" || !numericId(id) || seen.has(id) || !title.toLowerCase().includes(needle)) {
        continue;
      }
      seen.add(id);
      matched.push(listedDrama(id, title, {}));
    }
    const start = (page - 1) * pageSize;
    const end = Math.min(start + pageSize, matched.length);
    return { items: matched.slice(start, end), hasMore: end < matched.length };
  },

  detail(sourceId) {
    if (!numericId(sourceId)) {
      throw new Error("鬼片网视频 ID 无效");
    }
    const body = get("/nv/" + sourceId + ".html");
    let name = field(body, titlePattern);
    if (name === "") {
      const raw = field(body, titleTagPattern);
      if (raw.includes("提示信息") || raw.includes("影片不存在")) {
        throw new Error("鬼片网影片不存在");
      }
      name = raw.replace(/《(.*?)》.*/, "$1").trim();
    }
    if (name === "") {
      throw new Error("鬼片网详情缺少视频名称");
    }
    const picture = body.match(detailPicturePattern);
    let cover = picture ? fixUrl(picture[1]) : "";
    if (cover === "") {
      const detailCover = body.match(detailCoverPattern);
      cover = detailCover ? fixUrl(detailCover[1]) : "";
    }
    const description = truncate(field(body, contentPattern), 12000);
    const year = field(body, yearPattern);
    const director = field(body, directorPattern);
    const area = field(body, areaPattern);
    const cast = actors(body);
    const tags = [];
    const addTag = (value) => {
      value = value.trim();
      if (value !== "" && !tags.includes(value) && Array.from(value).length <= 48 && tags.length < 16) {
        tags.push(value);
      }
    };
    if (year !== "") {
      addTag(year + "年");
    }
    addTag(area);
    if (director !== "") {
      addTag("导演 " + truncate(director, 32));
    }
    if (cast !== "") {
      addTag("主演 " + truncate(cast, 48));
    }
    const chapters = pickPlaylist(body, sourceId);
    const drama = listedDrama(sourceId, name, {
      desc: description,
      intro: description,
      cover,
      coverUrl: cover,
      remark: truncate(field(body, statusPattern), 64),
      tags,
      totalEpisode: chapters.length,
      episodeCount: chapters.length,
    });
    return { drama, chapters };
  },

  resolveMedia(task) {
    const dramaPrefix = host.source + ":";
    const sourceId = task.dramaId.startsWith(dramaPrefix) ? task.dramaId.slice(dramaPrefix.length).trim() : "";
    const chapterPrefix = host.chapterId(sourceId, "");
    if (!numericId(sourceId) || !task.chapter.id.startsWith(chapterPrefix)) {
      throw new Error("鬼片网播放分集信息无效，请刷新详情");
    }
    const pageUrl = task.chapter.id.slice(chapterPrefix.length) || task.chapter.pageUrl || "";
    if (playUrl(pageUrl) === "") {
      throw new Error("鬼片网播放页地址无效");
    }
    const body = get(pageUrl);
    const now = body.match(nowPattern);
    let address = now ? now[1].trim() : "";
    if (address === "") {
      const any = body.match(mediaPattern);
      address = any ? any[0] : "";
    }
    if (address === "") {
      throw new Error("鬼片网该集暂无可用播放地址");
    }
    if (address.startsWith("//")) {
      address = "https:" + address;
    }
    const parsed = parseHttpUrl(address.trim());
    if (!parsed) {
      throw new Error("鬼片网播放地址无效");
    }
    const media = { url: address, referer: siteBaseUrl + "/" };
    return parsed.path.toLowerCase().endsWith(".m3u8") ? host.prepareWebMedia(media, sourceName) : media;
  },
};
