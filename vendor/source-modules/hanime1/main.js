const siteBaseUrl = "https://hanime1.me";
const sourceName = "Hanime1";
const userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

function attr(node, name) {
  return node && node.attrs ? node.attrs[name] || "" : "";
}

function hasClass(node, name) {
  return attr(node, "class").split(/\s+/).includes(name);
}

function select(root, matches) {
  const found = [];
  const pending = root ? [root] : [];
  while (pending.length > 0) {
    const node = pending.pop();
    if (matches(node)) {
      found.push(node);
    }
    const children = node.children || [];
    for (let index = children.length - 1; index >= 0; index--) {
      pending.push(children[index]);
    }
  }
  return found;
}

function text(node) {
  if (!node || node.tag === "script" || node.tag === "style") {
    return "";
  }
  return (node.text || "") + (node.children || []).map(text).join("");
}

function clean(value) {
  return String(value || "").replace(/\s+/g, " ").trim();
}

function httpUrl(raw, base) {
  if (typeof raw !== "string" || raw.trim() === "" || raw.length > 8192) {
    return null;
  }
  try {
    const parsed = host.parseUrl(host.resolveUrl(base || siteBaseUrl + "/", raw.trim()));
    return (parsed.scheme === "https" || parsed.scheme === "http") && parsed.hostname && !parsed.userInfo ? parsed : null;
  } catch (error) {
    return null;
  }
}

function siteUrl(raw, base) {
  const parsed = httpUrl(raw, base);
  return parsed && parsed.scheme === "https" && (!parsed.port || parsed.port === "443") &&
    ["hanime1.me", "www.hanime1.me"].includes(parsed.hostname.toLowerCase()) ? parsed : null;
}

function queryValue(query, name) {
  for (const part of String(query || "").split("&")) {
    const separator = part.indexOf("=");
    try {
      const key = decodeURIComponent((separator < 0 ? part : part.slice(0, separator)).replace(/\+/g, " "));
      if (key === name) {
        return decodeURIComponent((separator < 0 ? "" : part.slice(separator + 1)).replace(/\+/g, " "));
      }
    } catch (error) {
      return "";
    }
  }
  return "";
}

function numericId(value) {
  return typeof value === "string" && /^[1-9][0-9]{0,19}$/.test(value);
}

function videoId(raw) {
  const parsed = siteUrl(raw);
  const id = parsed && parsed.path === "/watch" ? queryValue(parsed.query, "v") : "";
  return numericId(id) ? id : "";
}

function watchUrl(id) {
  if (!numericId(id)) {
    throw new Error("Hanime1 视频 ID 无效");
  }
  return siteBaseUrl + "/watch?v=" + id;
}

function genreValue(category) {
  if (typeof category !== "string" || !category.startsWith("genre:") || category.length > 800) {
    return "";
  }
  try {
    const genre = decodeURIComponent(category.slice(6));
    return genre.length > 0 && genre.length <= 80 && !/[\x00-\x1f\x7f]/.test(genre) &&
      "genre:" + encodeURIComponent(genre) === category ? genre : "";
  } catch (error) {
    return "";
  }
}

function validCategory(category) {
  return category === "" || genreValue(category) !== "";
}

function fetchPage(address, noCache) {
  const response = host.fetchText({ url: address, referer: siteBaseUrl + "/", userAgent, noCache: noCache === true });
  const requested = siteUrl(address);
  const actual = siteUrl(response.url || address);
  if (!actual || actual.path !== requested.path) {
    throw new Error("Hanime1 页面发生跳转，请在站点确认访问权限");
  }
  for (const key of ["v", "query", "genre", "page"]) {
    const expected = queryValue(requested.query, key) || (key === "page" ? "1" : "");
    const received = queryValue(actual.query, key) || (key === "page" ? "1" : "");
    if (expected !== received) {
      throw new Error("Hanime1 返回的页面与请求不一致");
    }
  }
  return host.parseHtml(response.body);
}

function categories(force) {
  const root = fetchPage(siteBaseUrl + "/search", force);
  const found = [];
  const seen = new Set();
  for (const anchor of select(root, (node) => node.tag === "a" &&
    (hasClass(node, "nav-desktop-items") || hasClass(node, "home-genre-tabs")))) {
    const parsed = siteUrl(attr(anchor, "href"));
    const genre = parsed && parsed.path === "/search" ? queryValue(parsed.query, "genre") : "";
    const id = "genre:" + encodeURIComponent(genre);
    if (genreValue(id) === "" || seen.has(id)) {
      continue;
    }
    seen.add(id);
    found.push({ id, name: genre });
  }
  if (found.length === 0) {
    throw new Error("Hanime1 未返回可识别的分类，请确认页面结构或访问权限");
  }
  return found;
}

function drama(id, title, cover, intro) {
  return {
    id: host.dramaId(id), source: host.source, sourceId: id,
    title, name: title, cover, coverUrl: cover, intro: intro || "", desc: intro || "",
    channelName: sourceName, totalEpisode: 1, episodeCount: 1,
  };
}

function coverUrl(raw) {
  const parsed = httpUrl(raw);
  return parsed ? parsed.href : "";
}

function listing(page, params) {
  if (!Number.isInteger(page) || page < 1 || page > 100000) {
    throw new Error("Hanime1 页码无效");
  }
  const query = Object.assign({}, params, { page: String(page) });
  const address = siteBaseUrl + "/search?" + Object.keys(query)
    .map((key) => encodeURIComponent(key) + "=" + encodeURIComponent(query[key])).join("&");
  const root = fetchPage(address, false);
  const scope = select(root, (node) => attr(node, "id") === "home-rows-wrapper" && hasClass(node, "search-rows-wrapper"))[0];
  if (!scope) {
    throw new Error("Hanime1 未返回搜索列表，请确认页面结构或访问权限");
  }
  const items = [];
  const seen = new Set();
  const cards = select(scope, (node) => hasClass(node, "video-item-container") ||
    (node.tag === "a" && (node.children || []).some((child) => hasClass(child, "home-rows-videos-div") && hasClass(child, "search-videos"))));
  for (const card of cards) {
    const portrait = card.tag === "a";
    const anchor = portrait ? card : select(card, (node) => node.tag === "a" && hasClass(node, "video-link") && videoId(attr(node, "href")) !== "")[0];
    const id = videoId(attr(anchor, "href"));
    if (id === "" || seen.has(id)) {
      continue;
    }
    const titleNode = select(anchor, (node) => hasClass(node, portrait ? "home-rows-videos-title" : "title"))[0];
    const title = clean(text(titleNode)) || clean(attr(card, "title"));
    if (!title) {
      throw new Error("Hanime1 视频卡片缺少标题，请确认页面结构");
    }
    const image = select(anchor, (node) => node.tag === "img" && (portrait || hasClass(node, "main-thumb")))[0];
    const cover = coverUrl(attr(image, "data-src")) || coverUrl(attr(image, "data-original")) || coverUrl(attr(image, "src"));
    seen.add(id);
    items.push(drama(id, title, cover));
  }
  let hasMore = false;
  for (const anchor of select(root, (node) => node.tag === "a" && hasClass(node, "page-link"))) {
    const parsed = siteUrl(attr(anchor, "href"), address);
    if (!parsed || parsed.path !== "/search" ||
      queryValue(parsed.query, "query") !== (params.query || "") ||
      queryValue(parsed.query, "genre") !== (params.genre || "")) {
      continue;
    }
    const next = queryValue(parsed.query, "page");
    if (/^[1-9][0-9]{0,5}$/.test(next) && Number(next) > page) {
      hasMore = true;
    }
  }
  if (items.length === 0 && (hasMore || select(scope, (node) => node.tag === "a" && videoId(attr(node, "href")) !== "").length > 0)) {
    throw new Error("Hanime1 列表中存在视频或下一页，但未解析到有效卡片");
  }
  return { items, hasMore };
}

function catalogPage(page, category) {
  if (!validCategory(category)) {
    throw new Error("Hanime1 分类无效");
  }
  return listing(page, category === "" ? {} : { genre: genreValue(category) });
}

function search(page, query) {
  const keyword = clean(query);
  if (!keyword) {
    return { items: [], hasMore: false };
  }
  if (keyword.length > 200 || /[\x00-\x1f\x7f]/.test(keyword)) {
    throw new Error("Hanime1 搜索词无效");
  }
  return listing(page, { query: keyword });
}

function detailPage(id) {
  const root = fetchPage(watchUrl(id), true);
  const metadata = {};
  for (const node of select(root, (entry) => entry.tag === "meta")) {
    const key = attr(node, "property") || attr(node, "name");
    if (key.startsWith("og:")) {
      metadata[key] = attr(node, "content");
    }
  }
  if (videoId(metadata["og:url"]) !== id) {
    throw new Error("Hanime1 详情与请求的视频 ID 不一致");
  }
  const player = select(root, (node) => node.tag === "video" && attr(node, "id") === "player")[0];
  const titleNode = select(root, (node) => attr(node, "id") === "shareBtn-title")[0];
  const title = clean(text(titleNode)) || clean(metadata["og:title"]);
  if (!player || !title) {
    throw new Error("Hanime1 详情缺少播放器或标题，请在站点确认访问权限");
  }
  return { player, metadata, title };
}

function detail(sourceId) {
  const page = detailPage(sourceId);
  const cover = coverUrl(page.metadata["og:image"]) || coverUrl(attr(page.player, "poster"));
  return {
    drama: drama(sourceId, page.title, cover, clean(page.metadata["og:description"])),
    chapters: [{
      id: host.chapterId(sourceId, "1"), source: host.source, title: "正片", currentEpisode: 1,
      videoUrl: watchUrl(sourceId), referer: siteBaseUrl + "/",
    }],
  };
}

function resolveMedia(task) {
  const prefix = host.source + ":";
  const id = typeof task.dramaId === "string" && task.dramaId.startsWith(prefix) ? task.dramaId.slice(prefix.length) : "";
  if (!numericId(id) || !task.chapter || videoId(task.chapter.videoUrl) !== id) {
    throw new Error("Hanime1 播放任务与视频 ID 不一致，请重新获取详情");
  }
  const page = detailPage(id);
  const seconds = Number(page.metadata["og:video:duration"]);
  const durationMs = Number.isFinite(seconds) && seconds > 0 && seconds <= 86400 ? Math.round(seconds * 1000) : 0;
  const variants = [];
  const seen = new Set();
  for (const source of select(page.player, (node) => node.tag === "source")) {
    const parsed = httpUrl(attr(source, "src"));
    if (!parsed || parsed.scheme !== "https" || parsed.hostname.toLowerCase() !== "vdownload.hembed.com" ||
      (parsed.port && parsed.port !== "443") || attr(source, "type").toLowerCase() !== "video/mp4" || !/\.mp4$/i.test(parsed.path) || seen.has(parsed.href)) {
      continue;
    }
    const size = attr(source, "size");
    const quality = /^[1-9][0-9]{1,3}$/.test(size) ? Number(size) : 0;
    seen.add(parsed.href);
    variants.push({ url: parsed.href, referer: watchUrl(id), quality, durationMs });
  }
  if (variants.length === 0) {
    throw new Error("Hanime1 未返回支持的 MP4 地址，请确认访问权限或媒体域名是否变化");
  }
  variants.sort((left, right) => right.quality - left.quality);
  return Object.assign({}, variants[0], { variants });
}

module.exports = { validCategory, categories, catalogPage, search, detail, resolveMedia };
