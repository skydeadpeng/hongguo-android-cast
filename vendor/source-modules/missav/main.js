const siteBaseUrl = "https://missav.live";
const userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";
const channel = "MissAV";

const slugPattern = /^[a-z0-9][a-z0-9._-]{0,127}$/;
const cardPattern = /\/(?:dm[0-9]+\/)?cn\/([a-z0-9][a-z0-9._-]{0,127})\/?$/;
const genrePattern = /\/(?:dm[0-9]+\/)?cn\/genres\/([^/?#]+)\/?$/;
const pagePattern = /[?&]page=([0-9]{1,7})/;
const mediaPattern = /https?:\/\/surrit\.com\/[0-9a-f-]{36}\/playlist\.m3u8/;
const packedPattern = /eval\(function\(p,a,c,k,e,d\)\{.*?\}\('((?:[^'\\]|\\.)*)',([0-9]+),([0-9]+),'((?:[^'\\]|\\.)*)'\.split\('\|'\)/gs;

function truncate(text, limit) {
  const trimmed = text.trim();
  const characters = Array.from(trimmed);
  return characters.length <= limit ? trimmed : characters.slice(0, limit).join("") + "...";
}

function firstNonEmpty(...values) {
  for (const value of values) {
    if (typeof value === "string" && value.trim() !== "") {
      return value.trim();
    }
  }
  return "";
}

function pad2(value) {
  return value < 10 ? "0" + value : String(value);
}

function normalizeDate(text) {
  const matches = String(text).trim().match(/\d{4}-\d{1,2}-\d{1,2}/g) || [];
  for (const match of matches) {
    const [year, month, day] = match.split("-").map((part) => parseInt(part, 10));
    if (year >= 2000 && year <= 2100 && month >= 1 && month <= 12 && day >= 1 && day <= 31) {
      return year + "-" + pad2(month) + "-" + pad2(day);
    }
  }
  return "";
}

function validDate(date) {
  const [year, month, day] = date.split("-").map((part) => parseInt(part, 10));
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return parsed.getUTCFullYear() === year && parsed.getUTCMonth() === month - 1 && parsed.getUTCDate() === day;
}

function releaseDate(value) {
  value = String(value || "").trim();
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/i.test(value)) {
    const stamp = Date.parse(value.replace(/(\.\d{3})\d+/, "$1"));
    if (!isNaN(stamp)) {
      const date = new Date(stamp + 8 * 3600 * 1000);
      value = date.getUTCFullYear() + "-" + pad2(date.getUTCMonth() + 1) + "-" + pad2(date.getUTCDate());
    }
  }
  const date = normalizeDate(value);
  return date !== "" && validDate(date) ? date : "";
}

function parseAddress(raw) {
  try {
    return host.parseUrl(raw);
  } catch (error) {
    return null;
  }
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

function pathEscape(text) {
  const kept = { "%24": "$", "%26": "&", "%2B": "+", "%2C": ",", "%3A": ":", "%3B": ";", "%3D": "=", "%40": "@" };
  return encodeURIComponent(text)
    .replace(/[!'()*]/g, (character) => "%" + character.charCodeAt(0).toString(16).toUpperCase())
    .replace(/%(?:24|26|2B|2C|3A|3B|3D|40)/g, (escaped) => kept[escaped]);
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

function tagged(...names) {
  return (node) => names.includes(node.tag);
}

function validCategory(category) {
  if (category === "") {
    return true;
  }
  return category.length <= 96 && !/[/\\?#\x00\r\n]/.test(category);
}

function coverAddress(raw) {
  raw = String(raw || "").trim();
  if (raw === "" || raw.startsWith("data:")) {
    return "";
  }
  const parsed = parseAddress(resolveUrl(siteBaseUrl + "/", raw));
  if (!parsed || (parsed.scheme !== "https" && parsed.scheme !== "http") || parsed.hostname === "" || parsed.userInfo) {
    return "";
  }
  return parsed.href;
}

function pathMatch(href, pattern) {
  const parsed = parseAddress(String(href || "").trim());
  if (!parsed) {
    return "";
  }
  const match = parsed.path.match(pattern);
  return match ? match[1] : "";
}

function cardSlug(href) {
  return pathMatch(href, cardPattern);
}

function genreSlug(href) {
  return pathMatch(href, genrePattern).trim();
}

function metadataOf(document) {
  const metadata = {};
  for (const node of htmlNodes(document, tagged("meta"))) {
    const key = firstNonEmpty(htmlAttr(node, "property"), htmlAttr(node, "name"));
    const value = htmlAttr(node, "content").trim();
    if (key !== "" && value !== "" && !metadata[key]) {
      metadata[key] = value;
    }
  }
  return metadata;
}

function heading(document) {
  for (const node of htmlNodes(document, tagged("h1"))) {
    const text = htmlText(node);
    if (text !== "") {
      return text;
    }
  }
  return "";
}

function poster(document) {
  for (const node of htmlNodes(document, tagged("video"))) {
    const value = htmlAttr(node, "data-poster");
    if (value !== "") {
      return value;
    }
  }
  return "";
}

function cardImage(card) {
  let fallback = null;
  for (const image of htmlNodes(card, tagged("img"))) {
    const source = htmlAttr(image, "data-src");
    if (source !== "" && !source.startsWith("data:")) {
      return image;
    }
    if (fallback === null) {
      const plain = htmlAttr(image, "src");
      if (plain !== "" && !plain.startsWith("data:")) {
        fallback = image;
      }
    }
  }
  return fallback;
}

function parseCards(document) {
  const cards = htmlNodes(document, (node) => node.tag === "div" && htmlClass(node, "thumbnail") && htmlClass(node, "group"));
  const items = [];
  const seen = new Set();
  for (const card of cards) {
    if (items.length >= 500) {
      break;
    }
    let slug = "";
    for (const anchor of htmlNodes(card, tagged("a"))) {
      slug = cardSlug(htmlAttr(anchor, "href"));
      if (slug !== "") {
        break;
      }
    }
    if (slug === "" || seen.has(slug)) {
      continue;
    }
    const image = cardImage(card);
    let title = htmlText(htmlFirstClass(card, "my-2"));
    if (title === "" && image !== null) {
      title = htmlAttr(image, "alt").trim();
    }
    if (title === "") {
      title = slug.toUpperCase();
    }
    const cover = image === null ? "" : coverAddress(firstNonEmpty(htmlAttr(image, "data-src"), htmlAttr(image, "src")));
    const badge = htmlNodes(card, (node) => node.tag === "span" && htmlClass(node, "absolute"))[0];
    seen.add(slug);
    items.push({
      id: host.dramaId(slug),
      source: host.source,
      sourceId: slug,
      title: truncate(title, 512),
      name: truncate(title, 512),
      cover,
      coverUrl: cover,
      channelName: channel,
      categoryName: "视频",
      remark: truncate(badge ? htmlText(badge) : "", 32),
    });
  }
  return items;
}

function hasMorePages(document, page) {
  let maxPage = 0;
  for (const anchor of htmlNodes(document, tagged("a"))) {
    const match = htmlAttr(anchor, "href").match(pagePattern);
    if (match) {
      maxPage = Math.max(maxPage, parseInt(match[1], 10));
    }
  }
  return maxPage > page;
}

function tagsOf(document) {
  const tags = [];
  for (const anchor of htmlNodes(document, tagged("a"))) {
    if (genreSlug(htmlAttr(anchor, "href")) === "") {
      continue;
    }
    const text = htmlText(anchor);
    if (text === "" || tags.includes(text) || Array.from(text).length > 48) {
      continue;
    }
    tags.push(text);
    if (tags.length >= 16) {
      break;
    }
  }
  return tags;
}

function digit(value) {
  if (value > 35) {
    return String.fromCharCode(value + 29);
  }
  if (value < 10) {
    return String.fromCharCode(48 + value);
  }
  return String.fromCharCode(97 + value - 10);
}

function symbol(index, radix) {
  return index < radix ? digit(index) : symbol(Math.floor(index / radix), radix) + digit(index % radix);
}

function unpack(match) {
  const radix = parseInt(match[2], 10);
  const count = parseInt(match[3], 10);
  if (!(radix >= 2 && radix <= 64) || !(count >= 1 && count <= 1024)) {
    return "";
  }
  let payload = match[1].replace(/\\'/g, "'").replace(/\\\\/g, "\\");
  const words = match[4].split("|");
  for (let index = count - 1; index >= 0; index--) {
    if (index >= words.length || words[index] === "") {
      continue;
    }
    const pattern = new RegExp("\\b" + symbol(index, radix) + "\\b", "g");
    payload = payload.replace(pattern, () => words[index]);
  }
  return payload;
}

function mediaAddress(body) {
  for (const match of body.matchAll(packedPattern)) {
    const found = unpack(match).match(mediaPattern);
    if (found) {
      return found[0];
    }
  }
  const direct = body.match(mediaPattern);
  return direct ? direct[0] : "";
}

function fetchDocument(address) {
  return htmlTree(host.fetchText({ url: address, referer: siteBaseUrl + "/", userAgent }).body);
}

function fetchCatalogPage(page, category, query) {
  if (page < 1 || page > 1000000 || query.length > 256 || !validCategory(category)) {
    throw new Error("MissAV 目录查询参数无效");
  }
  let address = siteBaseUrl + "/cn/new";
  if (query !== "") {
    address = siteBaseUrl + "/cn/search/" + pathEscape(query);
  } else if (category !== "") {
    address = siteBaseUrl + "/cn/genres/" + pathEscape(category);
  }
  const document = fetchDocument(address + "?page=" + page);
  const items = parseCards(document);
  const hasMore = hasMorePages(document, page);
  if (items.length === 0 && (hasMore || (page === 1 && query === "" && category === ""))) {
    throw new Error("MissAV 未返回应有的目录页，请重试");
  }
  return { items, hasMore };
}

module.exports = {
  validCategory,

  categories() {
    const document = fetchDocument(siteBaseUrl + "/cn/genres");
    const order = [];
    const names = {};
    for (const anchor of htmlNodes(document, tagged("a"))) {
      const slug = genreSlug(htmlAttr(anchor, "href"));
      if (slug === "" || !validCategory(slug)) {
        continue;
      }
      const name = htmlText(anchor);
      if (name === "") {
        continue;
      }
      if (!Object.prototype.hasOwnProperty.call(names, slug)) {
        names[slug] = name;
        order.push(slug);
      } else if (Array.from(name).length < Array.from(names[slug]).length) {
        names[slug] = name;
      }
    }
    return order.slice(0, 200).map((slug) => ({ id: slug, name: truncate(names[slug], 48) }));
  },

  catalogPage(page, category) {
    return fetchCatalogPage(page, category, "");
  },

  search(page, query) {
    query = query.trim();
    if (query === "" || query.length > 256) {
      throw new Error("MissAV 搜索关键词无效");
    }
    return fetchCatalogPage(page, "", query);
  },

  detail(sourceId) {
    if (!slugPattern.test(sourceId)) {
      throw new Error("MissAV 视频 ID 无效");
    }
    const pageUrl = siteBaseUrl + "/cn/" + sourceId;
    const document = fetchDocument(pageUrl);
    const metadata = metadataOf(document);
    const title = firstNonEmpty(heading(document), metadata["og:title"], metadata["twitter:title"]);
    if (title === "") {
      throw new Error("MissAV 详情缺少视频标题");
    }
    const description = firstNonEmpty(metadata["og:description"], metadata["description"]);
    const cover = coverAddress(firstNonEmpty(metadata["og:image"], metadata["twitter:image"], poster(document)));
    const drama = {
      id: host.dramaId(sourceId),
      source: host.source,
      sourceId,
      title: truncate(title, 512),
      name: truncate(title, 512),
      desc: truncate(description, 12000),
      intro: truncate(description, 12000),
      cover,
      coverUrl: cover,
      channelName: channel,
      categoryName: "视频",
      onlineDate: releaseDate(metadata["og:video:release_date"]),
      tags: tagsOf(document),
      totalEpisode: 1,
      episodeCount: 1,
    };
    const chapter = {
      id: host.chapterId(sourceId, sourceId),
      source: host.source,
      title: "正片",
      currentEpisode: 1,
      videoUrl: "missav-play://" + sourceId,
      pageUrl,
      referer: pageUrl,
    };
    return { drama, chapters: [chapter] };
  },

  resolveMedia(task) {
    const dramaPrefix = host.source + ":";
    const sourceId = task.dramaId.startsWith(dramaPrefix) ? task.dramaId.slice(dramaPrefix.length).trim() : "";
    if (!slugPattern.test(sourceId) || !task.chapter.id.startsWith(host.chapterId(sourceId, ""))) {
      throw new Error("MissAV 播放分集信息无效，请刷新详情");
    }
    const pageUrl = siteBaseUrl + "/cn/" + sourceId;
    const body = host.fetchText({ url: pageUrl, referer: siteBaseUrl + "/", userAgent }).body;
    const address = mediaAddress(body);
    if (address === "") {
      throw new Error("MissAV 页面里未解析到播放地址");
    }
    return host.prepareWebMedia({ url: address, referer: pageUrl }, channel);
  },
};
