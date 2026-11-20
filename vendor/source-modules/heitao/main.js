const siteBaseUrl = "https://heitaodj.com";
const userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
const ageCookie = "heitao_age_confirmed=1";
const channel = "黑桃";

const numericIdPattern = /^[1-9][0-9]{0,17}$/;
const episodeHrefPattern = /\/index\.php\/vod\/play\/id\/(\d+)\/sid\/(\d+)\/nid\/(\d+)\.html/i;
const playerRoutePattern = /\/index\.php\/vod\/player\/id\/\d+\/nid\/(\d+)\/sid\/(\d+)\.html/i;
const episodeCountPattern = /^(\d+)集$/;
const searchPagePattern = /\/vod\/search\/page\/(\d+)\/wd\//i;

function truncate(text, limit) {
  const trimmed = text.trim();
  const characters = Array.from(trimmed);
  return characters.length <= limit ? trimmed : characters.slice(0, limit).join("") + "...";
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function parseAddress(raw) {
  try {
    return host.parseUrl(raw);
  } catch (error) {
    return null;
  }
}

function parseInteger(text) {
  return /^[+-]?\d+$/.test(text) ? parseInt(text, 10) : NaN;
}

function htmlUnescape(text) {
  const named = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: "\u00a0" };
  return text.replace(/&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);/g, (entity, body) => {
    if (body[0] === "#") {
      const code = body[1] === "x" || body[1] === "X" ? parseInt(body.slice(2), 16) : parseInt(body.slice(1), 10);
      return code > 0 && code <= 0x10ffff ? String.fromCodePoint(code) : entity;
    }
    return Object.prototype.hasOwnProperty.call(named, body) ? named[body] : entity;
  });
}

function resolveUrl(base, ref) {
  ref = htmlUnescape(String(ref || "")).trim();
  if (ref === "") {
    return "";
  }
  ref = ref.replace(/\\\//g, "/");
  if (ref.startsWith("//")) {
    return "https:" + ref;
  }
  const parsed = parseAddress(ref);
  if (!parsed) {
    return ref;
  }
  let resolved = parsed.href;
  if (parsed.scheme === "") {
    try {
      resolved = host.resolveUrl(base, ref);
    } catch (error) {
      return ref;
    }
  }
  return resolved.replace(/^http:/, "https:");
}

function httpAddress(raw) {
  const parsed = parseAddress(String(raw).trim());
  return parsed !== null && (parsed.scheme === "http" || parsed.scheme === "https") && parsed.hostname !== "" && !parsed.userInfo;
}

function coverAddress(value, pageUrl) {
  if (Array.isArray(value)) {
    for (const entry of value) {
      const found = coverAddress(entry, pageUrl);
      if (found !== "") {
        return found;
      }
    }
  } else if (isRecord(value)) {
    for (const key of ["url", "contentUrl", "thumbnailUrl"]) {
      const found = coverAddress(value[key], pageUrl);
      if (found !== "") {
        return found;
      }
    }
  } else if (typeof value === "string" && value.trim() !== "") {
    let resolved;
    try {
      resolved = host.resolveUrl(pageUrl, value.trim());
    } catch (error) {
      return "";
    }
    if (httpAddress(resolved)) {
      return resolved;
    }
  }
  return "";
}

function pathEscape(text) {
  const kept = { "%24": "$", "%26": "&", "%2B": "+", "%2C": ",", "%3A": ":", "%3B": ";", "%3D": "=", "%40": "@" };
  return encodeURIComponent(text)
    .replace(/[!'()*]/g, (character) => "%" + character.charCodeAt(0).toString(16).toUpperCase())
    .replace(/%(?:24|26|2B|2C|3A|3B|3D|40)/g, (escaped) => kept[escaped]);
}

function percentDecode(text, plusIsSpace) {
  if (/%(?![0-9a-fA-F]{2})/.test(text)) {
    return null;
  }
  const bytes = [];
  for (let index = 0; index < text.length; index++) {
    const character = text[index];
    if (character === "%") {
      bytes.push(parseInt(text.slice(index + 1, index + 3), 16));
      index += 2;
    } else if (plusIsSpace && character === "+") {
      bytes.push(32);
    } else {
      for (const byte of host.utf8Encode(character)) {
        bytes.push(byte);
      }
    }
  }
  return host.utf8Decode(bytes);
}

function queryParameters(query) {
  const values = {};
  for (const part of query.split("&")) {
    if (part === "" || part.includes(";")) {
      continue;
    }
    const separator = part.indexOf("=");
    const key = percentDecode(separator < 0 ? part : part.slice(0, separator), true);
    const value = percentDecode(separator < 0 ? "" : part.slice(separator + 1), true);
    if (key === null || value === null) {
      continue;
    }
    (values[key] = values[key] || []).push(value);
  }
  return values;
}

function origin(parsed) {
  const scheme = parsed.scheme.toLowerCase();
  let address = parsed.host.toLowerCase();
  if ((scheme === "https" && parsed.port === "443") || (scheme === "http" && parsed.port === "80")) {
    address = address.slice(0, address.length - parsed.port.length - 1);
  }
  return scheme + "://" + address;
}

function routeParameters(pageUrl, reference) {
  const page = parseAddress(pageUrl);
  if (!page || reference === "") {
    return null;
  }
  let resolved;
  try {
    resolved = host.resolveUrl(pageUrl, reference);
  } catch (error) {
    return null;
  }
  const address = parseAddress(resolved);
  if (!address || address.userInfo || origin(address) !== origin(page)) {
    return null;
  }
  let path = address.path.startsWith("/index.php") ? address.path.slice("/index.php".length) : address.path;
  if (path.endsWith(".html")) {
    path = path.slice(0, -5);
  }
  const parts = path.replace(/^\/+|\/+$/g, "").split("/");
  if (parts.length < 2 || parts[0] !== "vod" || parts.length % 2 !== 0) {
    return null;
  }
  if (!["type", "show", "search", "play", "detail"].includes(parts[1])) {
    return null;
  }
  const values = {};
  for (let index = 2; index + 1 < parts.length; index += 2) {
    values[parts[index]] = parts[index + 1];
  }
  const query = queryParameters(address.query);
  for (const key of Object.keys(query)) {
    if (query[key].length === 1 && !values[key]) {
      values[key] = query[key][0];
    }
  }
  return { action: parts[1], values };
}

function htmlTree(body) {
  if (body.length > 4 << 20) {
    throw new Error("站源页面过大");
  }
  const root = host.parseHtml(body);
  const pending = [root];
  while (pending.length > 0) {
    const node = pending.pop();
    for (const child of node.children || []) {
      child.parent = node;
      pending.push(child);
    }
  }
  return root;
}

function htmlNodes(root, match) {
  const found = [];
  if (!root) {
    return found;
  }
  const pending = [root];
  while (pending.length > 0) {
    const node = pending.pop();
    if (match(node)) {
      found.push(node);
    }
    const children = node.children || [];
    for (let index = children.length - 1; index >= 0; index--) {
      pending.push(children[index]);
    }
  }
  return found;
}

function htmlAttr(node, name) {
  return node && node.attrs && typeof node.attrs[name] === "string" ? node.attrs[name] : "";
}

function htmlClass(node, name) {
  return htmlAttr(node, "class").split(/\s+/).includes(name);
}

function htmlFirstClass(root, name) {
  return htmlNodes(root, (node) => htmlClass(node, name))[0] || null;
}

function htmlText(root) {
  const parts = [];
  for (const node of htmlNodes(root, (node) => node.tag === undefined && typeof node.text === "string")) {
    if (node.parent && (node.parent.tag === "script" || node.parent.tag === "style")) {
      continue;
    }
    parts.push(node.text);
  }
  return parts.join(" ").split(/\s+/).filter((part) => part !== "").join(" ");
}

function tagged(name) {
  return (node) => node.tag === name;
}

function fetchPage(address) {
  const response = host.fetchText({ url: address, referer: siteBaseUrl + "/", userAgent });
  return { document: htmlTree(response.body), pageUrl: response.url };
}

function validCategory(category) {
  return category === "" || (category.length <= 6 && numericIdPattern.test(category));
}

function scriptText(node) {
  return (node.children || [])
    .filter((child) => child.tag === undefined)
    .map((child) => child.text)
    .join("")
    .trim();
}

function seriesShaped(value) {
  return value === null || (isRecord(value) && ["@type", "name", "description"].every((key) => value[key] === undefined || value[key] === null || typeof value[key] === "string"));
}

function seriesMeta(value) {
  return value === null ? {} : value;
}

function isSeries(value) {
  return typeof value["@type"] === "string" && value["@type"].toLowerCase() === "tvseries";
}

function seriesMetadata(document) {
  for (const script of htmlNodes(document, (node) => node.tag === "script" && htmlAttr(node, "type").toLowerCase().includes("ld+json"))) {
    const text = scriptText(script);
    if (text === "") {
      continue;
    }
    let value;
    try {
      value = JSON.parse(text);
    } catch (error) {
      continue;
    }
    if (Array.isArray(value)) {
      if (value.every(seriesShaped)) {
        const found = value.map(seriesMeta).find(isSeries);
        if (found) {
          return found;
        }
      }
      continue;
    }
    if (seriesShaped(value) && isSeries(seriesMeta(value))) {
      return value;
    }
  }
  return {};
}

function categoryName(document) {
  for (const node of htmlNodes(document, tagged("li"))) {
    const text = htmlText(node).trim();
    if (text.startsWith("分类：")) {
      const name = text.slice("分类：".length).trim();
      if (name !== "") {
        return name;
      }
    }
  }
  return "";
}

function tagsOf(document) {
  const block = htmlFirstClass(document, "detail-tags");
  const tags = [];
  for (const anchor of htmlNodes(block, tagged("a"))) {
    const value = htmlText(anchor).trim();
    if (value === "" || Array.from(value).length > 48 || tags.includes(value)) {
      continue;
    }
    tags.push(value);
    if (tags.length >= 32) {
      break;
    }
  }
  return tags;
}

function episodeCount(remark) {
  const match = remark.trim().match(episodeCountPattern);
  if (!match) {
    return 0;
  }
  const value = parseInt(match[1], 10);
  return value >= 1 && value <= 100000 ? value : 0;
}

function parseCards(document, pageUrl) {
  const items = [];
  const seen = new Set();
  for (const card of htmlNodes(document, (node) => node.tag === "a" && htmlClass(node, "thumb"))) {
    const route = routeParameters(pageUrl, htmlAttr(card, "href"));
    const id = route ? route.values.id || "" : "";
    if (!route || route.action !== "detail" || !numericIdPattern.test(id) || seen.has(id)) {
      continue;
    }
    const images = htmlNodes(card, tagged("img"));
    let title = htmlAttr(card, "title").trim();
    for (const image of images) {
      if (title === "") {
        title = htmlAttr(image, "alt").trim();
      }
      if (title !== "") {
        break;
      }
    }
    if (title === "") {
      title = htmlText(card).trim();
    }
    if (title === "") {
      continue;
    }
    seen.add(id);
    let cover = "";
    for (const image of images) {
      for (const attribute of ["src", "data-src", "data-original"]) {
        cover = coverAddress(htmlAttr(image, attribute), pageUrl);
        if (cover !== "") {
          break;
        }
      }
      if (cover !== "") {
        break;
      }
    }
    const label = htmlFirstClass(card, "label");
    const remark = label ? truncate(htmlText(label), 64) : "";
    const drama = {
      id: host.dramaId(id),
      source: host.source,
      sourceId: id,
      title: truncate(title, 512),
      name: truncate(title, 512),
      cover,
      coverUrl: cover,
      channelName: channel,
      categoryName: "短剧",
      remark,
      badge: remark,
    };
    const count = episodeCount(remark);
    if (count > 0) {
      drama.episodeCount = count;
      drama.totalEpisode = count;
    }
    items.push(drama);
    if (items.length >= 500) {
      break;
    }
  }
  return items;
}

function pageCount(document, pageUrl, category, query, page) {
  let pages = page;
  for (const anchor of htmlNodes(document, tagged("a"))) {
    const href = htmlAttr(anchor, "href");
    let value = "";
    if (query !== "") {
      const match = href.match(searchPagePattern);
      if (match) {
        value = match[1];
      }
    } else {
      const route = routeParameters(pageUrl, href);
      if (route && (route.action === "show" || route.action === "type") && (route.values.id || "") === category) {
        value = route.values.page || "";
      }
    }
    const number = parseInteger(value);
    if (number > pages && number < 100000) {
      pages = number;
    }
  }
  return pages;
}

function catalogAddress(page, category, query) {
  if (query !== "") {
    const escaped = pathEscape(query);
    return page <= 1 ? siteBaseUrl + "/index.php/vod/search/wd/" + escaped + ".html" : siteBaseUrl + "/index.php/vod/search/page/" + page + "/wd/" + escaped + ".html";
  }
  let show = siteBaseUrl + "/index.php/vod/show";
  if (category !== "") {
    show += "/id/" + category;
  }
  return page <= 1 ? show + ".html" : show + "/page/" + page + ".html";
}

function fetchCatalogPage(page, category, query) {
  query = query.trim();
  if (page < 1 || page > 100000 || query.length > 256 || !validCategory(category)) {
    throw new Error("黑桃目录查询参数无效");
  }
  const { document, pageUrl } = fetchPage(catalogAddress(page, category, query));
  const items = parseCards(document, pageUrl);
  return { items, hasMore: pageCount(document, pageUrl, category, query, page) > page };
}

function chaptersOf(document, sourceId) {
  const groups = new Map();
  const seen = new Set();
  for (const anchor of htmlNodes(document, tagged("a"))) {
    const match = htmlAttr(anchor, "href").match(episodeHrefPattern);
    if (!match || match[1] !== sourceId) {
      continue;
    }
    const sid = parseInt(match[2], 10);
    const nid = parseInt(match[3], 10);
    if (!(sid >= 1) || !(nid >= 1)) {
      continue;
    }
    const key = sid + ":" + nid;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    const title = htmlText(anchor).trim() || "第 " + nid + " 集";
    if (!groups.has(sid)) {
      groups.set(sid, []);
    }
    groups.get(sid).push({ sid, nid, title });
  }
  let best = [];
  let bestSid = 0;
  for (const [sid, items] of groups) {
    if (items.length > best.length || (items.length === best.length && sid < bestSid)) {
      best = items;
      bestSid = sid;
    }
  }
  const chapters = best.map((item) => {
    const key = item.sid + ":" + item.nid;
    return {
      id: host.chapterId(sourceId, key),
      source: host.source,
      title: truncate(item.title, 128),
      currentEpisode: item.nid,
      videoUrl: "heitao-player://" + key,
      pageUrl: siteBaseUrl + "/index.php/vod/player/id/" + sourceId + "/nid/" + item.nid + "/sid/" + item.sid + ".html",
      referer: siteBaseUrl + "/",
    };
  });
  return chapters
    .map((chapter, index) => ({ chapter, index }))
    .sort((left, right) => left.chapter.currentEpisode - right.chapter.currentEpisode || (left.chapter.id < right.chapter.id ? -1 : left.chapter.id > right.chapter.id ? 1 : left.index - right.index))
    .map((entry) => entry.chapter);
}

function separateEpisode(key) {
  const parts = key.split(":");
  if (parts.length !== 2) {
    return null;
  }
  const sid = parseInteger(parts[0]);
  const nid = parseInteger(parts[1]);
  return sid >= 1 && nid >= 1 ? { sid, nid } : null;
}

function playerId(raw) {
  const match = String(raw || "").match(playerRoutePattern);
  if (!match) {
    return null;
  }
  const nid = parseInteger(match[1]);
  const sid = parseInteger(match[2]);
  return sid >= 1 && nid >= 1 ? { sid, nid } : null;
}

function readPlayerConfig(body) {
  const index = body.indexOf("player_aaaa");
  if (index < 0) {
    throw new Error("黑桃播放器未返回配置");
  }
  const text = body.slice(index);
  const open = text.indexOf("{");
  if (open < 0) {
    throw new Error("黑桃播放器配置无效");
  }
  let depth = 0;
  let inString = false;
  let escaped = false;
  let end = -1;
  for (let position = open; position < text.length && end < 0; position++) {
    const character = text[position];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === '"') {
        inString = false;
      }
      continue;
    }
    if (character === '"') {
      inString = true;
    } else if (character === "{") {
      depth++;
    } else if (character === "}") {
      depth--;
      if (depth === 0) {
        end = position;
      }
    }
  }
  if (end < 0) {
    throw new Error("黑桃播放器配置不完整");
  }
  let entry;
  try {
    entry = JSON.parse(text.slice(open, end + 1));
  } catch (error) {
    throw new Error("黑桃播放器配置解析失败：" + error.message);
  }
  if (!isRecord(entry) || (entry.url !== undefined && entry.url !== null && typeof entry.url !== "string")) {
    throw new Error("黑桃播放器配置解析失败：播放地址格式无效");
  }
  if (typeof entry.url !== "string" || entry.url.trim() === "") {
    throw new Error("黑桃该集暂无可用播放地址");
  }
  return entry;
}

function encryptMode(value) {
  if (typeof value === "number") {
    return Math.trunc(value);
  }
  if (typeof value === "string") {
    const parsed = parseInteger(value.trim());
    return isNaN(parsed) ? 0 : parsed;
  }
  return value === true ? 1 : 0;
}

function decryptMediaUrl(raw, encrypt) {
  raw = raw.trim();
  if (encrypt === 0) {
    return raw;
  }
  if (encrypt !== 1 && encrypt !== 2) {
    throw new Error("黑桃播放地址加密方式暂不支持");
  }
  let text = raw;
  if (encrypt === 2) {
    try {
      text = host.utf8Decode(host.base64Decode(raw));
    } catch (error) {
      throw new Error("黑桃播放地址解码失败");
    }
  }
  const decoded = percentDecode(text, false);
  if (decoded === null) {
    throw new Error("黑桃播放地址解码失败");
  }
  return decoded;
}

module.exports = {
  validCategory,

  categories() {
    const { document, pageUrl } = fetchPage(siteBaseUrl + "/");
    const categories = [];
    const seen = new Set();
    for (const anchor of htmlNodes(document, tagged("a"))) {
      const route = routeParameters(pageUrl, htmlAttr(anchor, "href"));
      const id = route ? route.values.id || "" : "";
      const name = htmlText(anchor).trim();
      if (!route || route.action !== "type" || !validCategory(id) || id === "" || seen.has(id) || name === "" || Array.from(name).length > 48) {
        continue;
      }
      seen.add(id);
      categories.push({ id, name });
      if (categories.length >= 32) {
        break;
      }
    }
    if (categories.length === 0) {
      throw new Error("黑桃未返回有效的内容分类");
    }
    return categories;
  },

  catalogPage(page, category) {
    return fetchCatalogPage(page, category, "");
  },

  search(page, query) {
    return fetchCatalogPage(page, "", query);
  },

  detail(sourceId) {
    if (!numericIdPattern.test(sourceId)) {
      throw new Error("黑桃视频 ID 无效");
    }
    const { document, pageUrl } = fetchPage(siteBaseUrl + "/index.php/vod/detail/id/" + sourceId + ".html");
    const meta = seriesMetadata(document);
    let name = typeof meta.name === "string" ? meta.name.trim() : "";
    if (name === "") {
      const heading = htmlFirstClass(document, "title");
      name = heading ? htmlText(heading).trim() : "";
    }
    if (name === "") {
      throw new Error("黑桃详情缺少剧名");
    }
    const chapters = chaptersOf(document, sourceId);
    if (chapters.length === 0) {
      throw new Error("黑桃该剧暂无可播放分集");
    }
    let cover = coverAddress(meta.image, pageUrl);
    if (cover === "") {
      for (const image of htmlNodes(htmlFirstClass(document, "albumDetailImg"), tagged("img"))) {
        cover = coverAddress(htmlAttr(image, "src"), pageUrl);
        if (cover !== "") {
          break;
        }
      }
    }
    const description = truncate(typeof meta.description === "string" ? meta.description : "", 12000);
    const drama = {
      id: host.dramaId(sourceId),
      source: host.source,
      sourceId,
      title: truncate(name, 512),
      name: truncate(name, 512),
      desc: description,
      intro: description,
      cover,
      coverUrl: cover,
      channelName: channel,
      categoryName: categoryName(document) || "短剧",
      tags: tagsOf(document),
      totalEpisode: chapters.length,
      episodeCount: chapters.length,
    };
    return { drama, chapters };
  },

  resolveMedia(task) {
    const dramaPrefix = host.source + ":";
    const sourceId = task.dramaId.startsWith(dramaPrefix) ? task.dramaId.slice(dramaPrefix.length).trim() : "";
    const prefix = host.chapterId(sourceId, "");
    if (!numericIdPattern.test(sourceId) || !task.chapter.id.startsWith(prefix)) {
      throw new Error("黑桃播放分集信息无效，请刷新详情");
    }
    const episode = separateEpisode(task.chapter.id.slice(prefix.length)) || playerId(task.chapter.pageUrl);
    if (!episode) {
      throw new Error("黑桃播放分集信息无效，请刷新详情");
    }
    const playerUrl = siteBaseUrl + "/index.php/vod/player/id/" + sourceId + "/nid/" + episode.nid + "/sid/" + episode.sid + ".html";
    const playUrl = siteBaseUrl + "/index.php/vod/play/id/" + sourceId + "/sid/" + episode.sid + "/nid/" + episode.nid + ".html";
    const body = host.fetchText({ url: playerUrl, referer: playUrl, userAgent, cookie: ageCookie }).body;
    const entry = readPlayerConfig(body);
    const address = resolveUrl(playerUrl, decryptMediaUrl(entry.url, encryptMode(entry.encrypt)));
    if (!httpAddress(address)) {
      throw new Error("黑桃播放地址无效");
    }
    return host.prepareWebMedia({ url: address, referer: siteBaseUrl + "/" }, channel);
  },
};
