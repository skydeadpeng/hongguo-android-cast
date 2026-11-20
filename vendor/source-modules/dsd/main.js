const siteBaseUrl = "https://www.dsd.com.se";
const userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const channel = "帝果";
const categoriesKey = "categories";
const limitsKey = "page-limits";
const hourMs = 3600 * 1000;
const limitTtlMs = 15 * 60 * 1000;

const numericIdPattern = /^[1-9][0-9]{0,17}$/;
const backgroundUrlPattern = /url\([\t\n\f\r ]*["']?([^"')]+)/i;
const playerAssignmentPattern = /\bplayer_[a-zA-Z0-9_]+[\t\n\f\r ]*=[\t\n\f\r ]*/g;
const videoDirectoryPattern = /^\/video[0-9]*\//;
const posterFilenamePattern = /\/1\.(?:jpg|jpeg|png|webp)$/i;
const scriptBlockPattern = /<script\b[^>]*>([\s\S]*?)<\/script>/gi;
const vPathPattern = /\bvar[\t\n\f\r ]+vPath[\t\n\f\r ]*=[\t\n\f\r ]*["']([^"']+)["']/;

const aaMarker = "(ﾟДﾟ) ['_'] ( (ﾟДﾟ) ['_']";
const aaTailMarker = "+ (ﾟДﾟ)[ﾟoﾟ]";
const aaSeparator = "(ﾟДﾟ)[ﾟεﾟ]+";
const aaUnicode = "(oﾟｰﾟo)";
const aaB = "(ﾟДﾟ).ﾟΘﾟﾉ";
const aaReplacements = [
  ["(c^_^o)", "0"],
  ["(ﾟΘﾟ)", "1"],
  ["(o^_^o)", "3"],
  ["(ﾟｰﾟ)", "4"],
];
const aaFace = "(ﾟДﾟ)";

function truncate(text, limit) {
  const trimmed = text.trim();
  const characters = Array.from(trimmed);
  return characters.length <= limit ? trimmed : characters.slice(0, limit).join("") + "...";
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function present(value) {
  return value !== undefined && value !== null;
}

function byteLength(text) {
  return host.utf8Encode(text).length;
}

function firstNonEmpty(...values) {
  for (const value of values) {
    if (typeof value === "string" && value.trim() !== "") {
      return value.trim();
    }
  }
  return "";
}

function mapString(row, ...keys) {
  if (!isRecord(row)) {
    return "";
  }
  for (const key of keys) {
    const value = row[key];
    if (typeof value === "string") {
      const trimmed = value.trim();
      if (trimmed !== "") {
        return trimmed;
      }
    } else if (typeof value === "boolean" || typeof value === "number") {
      return String(value);
    }
  }
  return "";
}

function nativeText(value) {
  if (typeof value === "string") {
    return value.trim();
  }
  if (typeof value === "number") {
    return String(value);
  }
  if (Array.isArray(value)) {
    for (const entry of value) {
      const text = nativeText(entry);
      if (text !== "") {
        return text;
      }
    }
  } else if (isRecord(value)) {
    for (const key of ["url", "src", "cover", "image", "pic"]) {
      const text = nativeText(value[key]);
      if (text !== "") {
        return text;
      }
    }
  }
  return "";
}

function webInteger(value, maximum) {
  const text = nativeText(value);
  const valid = /^[+-]?\d+$/.test(text);
  const number = valid ? parseInt(text, 10) : 0;
  return { value: number, valid: valid && number >= 0 && number <= maximum };
}

function parseAddress(raw) {
  try {
    return host.parseUrl(raw);
  } catch (error) {
    return null;
  }
}

function httpAddress(raw) {
  const parsed = parseAddress(String(raw).trim());
  return parsed !== null && (parsed.scheme === "http" || parsed.scheme === "https") && parsed.hostname !== "" && !parsed.userInfo;
}

function origin(parsed) {
  const scheme = parsed.scheme.toLowerCase();
  let address = parsed.host.toLowerCase();
  if ((scheme === "https" && parsed.port === "443") || (scheme === "http" && parsed.port === "80")) {
    address = address.slice(0, address.length - parsed.port.length - 1);
  }
  return scheme + "://" + address;
}

function escapedPath(parsed) {
  const rest = parsed.href.slice((parsed.scheme + "://" + parsed.host).length);
  const end = rest.search(/[?#]/);
  return end < 0 ? rest : rest.slice(0, end);
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

function queryEscape(text) {
  return encodeURIComponent(text)
    .replace(/[!'()*]/g, (character) => "%" + character.charCodeAt(0).toString(16).toUpperCase())
    .replace(/%20/g, "+");
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

function htmlFirstClass(root, ...names) {
  for (const name of names) {
    const found = htmlNodes(root, (node) => htmlClass(node, name));
    if (found.length > 0) {
      return found[0];
    }
  }
  return null;
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

function fetchPage(address) {
  const response = host.fetchText({ url: address, referer: siteBaseUrl + "/", userAgent });
  return { document: htmlTree(response.body), pageUrl: response.url };
}

function cacheRead(key) {
  const raw = host.cacheGet(key);
  return raw === null ? null : JSON.parse(raw);
}

function validCategory(category) {
  return numericIdPattern.test(category);
}

function parseCategories(document, pageUrl) {
  const categories = [];
  const seen = new Set();
  for (const anchor of htmlNodes(document, tagged("a"))) {
    const route = routeParameters(pageUrl, htmlAttr(anchor, "href"));
    const id = route ? route.values.id || "" : "";
    const name = htmlText(anchor);
    if (!route || route.action !== "type" || !numericIdPattern.test(id) || seen.has(id) || name === "" || Array.from(name).length > 48) {
      continue;
    }
    seen.add(id);
    categories.push({ id, name });
  }
  if (categories.length === 0 || categories.length > 32) {
    throw new Error("帝果未返回有效的内容分类");
  }
  return categories;
}

function fetchCategories(force) {
  const cached = cacheRead(categoriesKey);
  if (cached !== null && !force) {
    return cached;
  }
  const { document, pageUrl } = fetchPage(siteBaseUrl + "/");
  const categories = parseCategories(document, pageUrl);
  host.cacheSet(categoriesKey, JSON.stringify(categories), hourMs);
  if (force) {
    host.cacheSet(limitsKey, null, 0);
  }
  return categories;
}

function cardCover(card, pageUrl) {
  const images = htmlNodes(card, tagged("img"));
  for (const image of images) {
    for (const attribute of ["data-src", "data-original"]) {
      const address = coverAddress(htmlAttr(image, attribute), pageUrl);
      if (address !== "") {
        return address;
      }
    }
  }
  const styled = htmlNodes(
    card,
    (node) => node === card || htmlClass(node, "img-bg") || htmlClass(node, "surface-img") || htmlClass(node, "openVip") || htmlClass(node, "video-before-ad")
  );
  for (const node of styled) {
    const match = htmlAttr(node, "style").match(backgroundUrlPattern);
    if (match) {
      const address = coverAddress(match[1].trim(), pageUrl);
      if (address !== "") {
        return address;
      }
    }
  }
  for (const image of images) {
    const raw = htmlAttr(image, "src");
    if (raw.startsWith("/video") || raw.includes("/upload")) {
      const address = coverAddress(raw, pageUrl);
      if (address !== "") {
        return address;
      }
    }
  }
  return "";
}

function parseCards(document, pageUrl, categoryName) {
  const items = [];
  const seen = new Set();
  for (const card of htmlNodes(document, (node) => node.tag === "a" && (htmlClass(node, "video-item") || htmlClass(node, "video-surface")))) {
    const route = routeParameters(pageUrl, htmlAttr(card, "href"));
    const id = route ? route.values.id || "" : "";
    if (!route || (route.action !== "play" && route.action !== "detail") || !numericIdPattern.test(id) || seen.has(id)) {
      continue;
    }
    let title = firstNonEmpty(htmlText(htmlFirstClass(card, "video-desc", "surface-title", "video-title", "title")), htmlAttr(card, "title"));
    if (title === "") {
      for (const image of htmlNodes(card, tagged("img"))) {
        title = htmlAttr(image, "alt").trim();
        if (title !== "") {
          break;
        }
      }
    }
    if (title === "") {
      throw new Error("帝果目录缺少视频名称");
    }
    seen.add(id);
    const cover = cardCover(card, pageUrl);
    let views = htmlText(htmlFirstClass(card, "video-item-tag-hits", "counts"));
    if (views.endsWith("次")) {
      views = views.slice(0, -1);
    }
    const drama = {
      id: host.dramaId(id),
      source: host.source,
      sourceId: id,
      title: truncate(title, 1024),
      name: truncate(title, 1024),
      cover,
      coverUrl: cover,
      categoryName: firstNonEmpty(categoryName, "视频"),
      channelName: channel,
      views,
    };
    if (htmlFirstClass(card, "video-item-tag-is-vip", "vip-tag") !== null) {
      drama.vip = true;
    }
    items.push(drama);
    if (items.length > 500) {
      throw new Error("帝果目录条目过多");
    }
  }
  return items;
}

function pagination(document, pageUrl, category, query, page) {
  const block = htmlFirstClass(document, "el-pagination", "pagination", "mac_pages");
  if (block === null) {
    if (page !== 1) {
      throw new Error("帝果未返回请求页码，请重试");
    }
    return 1;
  }
  let activePage = 0;
  for (const node of htmlNodes(block, (node) => (htmlClass(node, "active") && htmlClass(node, "number")) || htmlClass(node, "page-current"))) {
    const current = webInteger(htmlText(node), 1000000);
    if (current.valid) {
      activePage = current.value;
      break;
    }
  }
  if (activePage !== page) {
    throw new Error("帝果返回的页码与请求不符");
  }
  let pages = page;
  for (const anchor of htmlNodes(block, tagged("a"))) {
    const route = routeParameters(pageUrl, htmlAttr(anchor, "href"));
    if (!route) {
      continue;
    }
    if (query !== "") {
      if (route.action !== "search" || (route.values.wd || "") !== query) {
        continue;
      }
    } else if ((route.action !== "type" && route.action !== "show") || (route.values.id || "") !== category) {
      continue;
    }
    const value = webInteger(route.values.page, 1000000);
    if (value.valid) {
      pages = Math.max(pages, value.value);
    }
  }
  return pages;
}

function pageLimit(category) {
  const limits = cacheRead(limitsKey) || {};
  const limit = limits[category];
  return limit && Date.now() - limit.at < limitTtlMs ? limit.pages : null;
}

function rememberLimit(category, pages) {
  host.exclusive(() => {
    const limits = cacheRead(limitsKey) || {};
    limits[category] = { pages, at: Date.now() };
    host.cacheSet(limitsKey, JSON.stringify(limits), 0);
  });
}

function fetchListPage(page, category, query) {
  if (query === "" && page > 1) {
    let limit = pageLimit(category.id);
    if (limit === null) {
      fetchListPage(1, category, "");
      limit = pageLimit(category.id);
    }
    if (limit !== null && page > limit) {
      return { items: [], hasMore: false };
    }
  }
  let address = siteBaseUrl + "/index.php/vod/type/id/" + category.id + "/page/" + page + ".html";
  if (query !== "") {
    address = siteBaseUrl + "/index.php/vod/search.html?page=" + page + "&wd=" + queryEscape(query);
  }
  const { document, pageUrl } = fetchPage(address);
  const items = parseCards(document, pageUrl, category.name);
  if (items.length === 0 && htmlFirstClass(document, "video-contaner", "nvyou-wrap", "van-list", "search-page", "lists") === null) {
    throw new Error("帝果未返回内容列表，页面可能已变化");
  }
  const pages = pagination(document, pageUrl, category.id, query, page);
  if (items.length === 0 && pages > page) {
    throw new Error("帝果未返回应有的视频条目，请重试");
  }
  if (query === "") {
    rememberLimit(category.id, pages);
  }
  return { items, hasMore: pages > page };
}

function fetchCatalogPage(page, category, query) {
  if (page < 1 || page > 1000000 || byteLength(query) > 1024 || (category !== "" && !numericIdPattern.test(category))) {
    throw new Error("帝果目录查询参数无效");
  }
  if (query !== "") {
    return fetchListPage(page, { id: "", name: "" }, query);
  }
  const categories = fetchCategories(false);
  if (category !== "") {
    const found = categories.find((item) => item.id === category);
    if (!found) {
      throw new Error("帝果分类已变化，请刷新分类");
    }
    return fetchListPage(page, found, "");
  }
  const results = categories.map((item) => {
    try {
      return Object.assign({ error: null }, fetchListPage(page, item, ""));
    } catch (error) {
      return { items: [], hasMore: false, error };
    }
  });
  let hasMore = false;
  let count = 0;
  const failures = [];
  results.forEach((result, index) => {
    hasMore = hasMore || result.hasMore || result.error !== null;
    count = Math.max(count, result.items.length);
    if (result.error !== null) {
      failures.push(categories[index].name + "：" + result.error.message);
    }
  });
  const items = [];
  const seen = new Set();
  for (let index = 0; index < count; index++) {
    for (const result of results) {
      const item = result.items[index];
      if (item && !seen.has(item.id)) {
        seen.add(item.id);
        items.push(item);
      }
    }
  }
  return failures.length > 0 ? { items, hasMore, warning: failures.join("\n") } : { items, hasMore };
}

function leadingObject(text) {
  const start = text.search(/[^\t\n\r ]/);
  if (start < 0 || text[start] !== "{") {
    return null;
  }
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let index = start; index < text.length; index++) {
    const character = text[index];
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
    } else if (character === "{" || character === "[") {
      depth++;
    } else if (character === "}" || character === "]") {
      depth--;
      if (depth === 0) {
        try {
          const value = JSON.parse(host.preserveIntegers(text.slice(start, index + 1)));
          return isRecord(value) ? value : null;
        } catch (error) {
          return null;
        }
      }
    }
  }
  return null;
}

function parsePlayer(document) {
  for (const script of htmlNodes(document, tagged("script"))) {
    const first = (script.children || [])[0];
    if (!first) {
      continue;
    }
    const body = first.tag === undefined ? first.text : "";
    let count = 0;
    for (const match of body.matchAll(playerAssignmentPattern)) {
      if (++count > 8) {
        break;
      }
      const player = leadingObject(body.slice(match.index + match[0].length));
      if (player === null) {
        throw new Error("帝果播放器参数格式无效");
      }
      if (present(player.id) && present(player.url)) {
        return { player, found: true };
      }
    }
  }
  return { player: null, found: false };
}

function playerIdentity(player, sourceId) {
  const line = webInteger(player.sid, 1000000);
  const episode = webInteger(player.nid, 1000000);
  if (mapString(player, "id") !== sourceId || !line.valid || line.value < 1 || !episode.valid || episode.value < 1) {
    throw new Error("帝果播放器与请求视频不符");
  }
  return { line: line.value, episode: episode.value };
}

function episodePage(sourceId, line, episode) {
  return siteBaseUrl + "/index.php/vod/play/id/" + sourceId + "/sid/" + line + "/nid/" + episode + ".html";
}

function chapterOf(sourceId, line, episode, title, vip) {
  const chapter = {
    id: host.chapterId(sourceId, line + "-" + episode),
    source: host.source,
    title: truncate(firstNonEmpty(title, "第 " + episode + " 集"), 256),
    currentEpisode: episode,
    pageUrl: episodePage(sourceId, line, episode),
    referer: siteBaseUrl + "/",
  };
  if (vip) {
    chapter.vip = true;
  }
  return chapter;
}

function detailIdentity(document, sourceId, player, found) {
  if (found) {
    playerIdentity(player, sourceId);
    return;
  }
  const info = htmlFirstClass(document, "video-info");
  for (const node of htmlNodes(info, (node) => htmlAttr(node, "data-id") === sourceId)) {
    if (htmlClass(node, "btn-favs")) {
      return;
    }
  }
  throw new Error("帝果未返回所请求视频的详情");
}

function parseDetail(document, pageUrl, sourceId) {
  const { player, found } = parsePlayer(document);
  detailIdentity(document, sourceId, player, found);
  let line = 1;
  let current = 1;
  if (found) {
    ({ line, episode: current } = playerIdentity(player, sourceId));
  }
  const info = htmlFirstClass(document, "video-info");
  const box = htmlFirstClass(document, "video-box");
  const data = player !== null && isRecord(player.vod_data) ? player.vod_data : null;
  let title = mapString(data, "vod_name");
  if (title === "") {
    for (const heading of htmlNodes(info, tagged("h1", "h2", "h3"))) {
      title = htmlText(heading);
      if (title !== "") {
        break;
      }
    }
  }
  if (title === "") {
    throw new Error("帝果详情缺少视频名称");
  }
  const cover = firstNonEmpty(coverAddress(player !== null ? player.poster : undefined, pageUrl), cardCover(box, pageUrl));
  const description = truncate(htmlText(htmlFirstClass(info, "desc")), 12000);
  const drama = {
    id: host.dramaId(sourceId),
    source: host.source,
    sourceId,
    title: truncate(title, 1024),
    name: truncate(title, 1024),
    desc: description,
    intro: description,
    cover,
    coverUrl: cover,
    channelName: channel,
    tags: [],
  };
  const vip = htmlFirstClass(box, "noVip", "openVip") !== null;
  if (vip) {
    drama.vip = true;
  }
  const addTag = (tag) => {
    tag = tag.trim();
    if (tag !== "" && Array.from(tag).length <= 48 && drama.tags.length < 32 && !drama.tags.includes(tag)) {
      drama.tags.push(tag);
    }
  };
  mapString(data, "vod_class").split(",").forEach(addTag);
  for (const anchor of htmlNodes(htmlFirstClass(info, "labels"), tagged("a"))) {
    addTag(htmlText(anchor));
  }
  const chapters = [chapterOf(sourceId, line, current, "", vip)];
  const seen = new Set([current]);
  const addEpisode = (reference, title) => {
    const route = routeParameters(pageUrl, reference);
    const values = route ? route.values : {};
    const candidateLine = webInteger(values.sid, 1000000);
    const episode = webInteger(values.nid, 1000000);
    if (!route || route.action !== "play" || values.id !== sourceId || !candidateLine.valid || candidateLine.value !== line || !episode.valid || episode.value < 1 || seen.has(episode.value)) {
      return;
    }
    seen.add(episode.value);
    chapters.push(chapterOf(sourceId, line, episode.value, title, vip));
  };
  for (const anchor of htmlNodes(document, tagged("a"))) {
    addEpisode(htmlAttr(anchor, "href"), htmlText(anchor));
  }
  addEpisode(mapString(player, "link_pre"), "");
  addEpisode(mapString(player, "link_next"), "");
  if (chapters.length > 10000) {
    throw new Error("帝果分集目录过大");
  }
  chapters.sort((left, right) => left.currentEpisode - right.currentEpisode);
  if (chapters.length === 1) {
    chapters[0].title = "正片";
  }
  drama.totalEpisode = chapters.length;
  drama.episodeCount = chapters.length;
  return { drama, chapters };
}

function issuedMedia(player, pageUrl) {
  let address = mapString(player, "url");
  if (address === "") {
    return "";
  }
  const encrypt = mapString(player, "encrypt");
  if (encrypt === "1" || encrypt === "2") {
    let text = address;
    if (encrypt === "2") {
      try {
        text = host.utf8Decode(host.base64Decode(address));
      } catch (error) {
        throw new Error("帝果播放地址编码无效");
      }
    }
    const decoded = percentDecode(text, false);
    if (decoded === null) {
      throw new Error("帝果播放地址编码无效");
    }
    address = decoded;
  } else if (encrypt !== "" && encrypt !== "0") {
    throw new Error("帝果播放地址编码已变化");
  }
  address = resolveUrl(pageUrl, address);
  const parsed = parseAddress(address);
  if (!parsed || !httpAddress(address)) {
    throw new Error("帝果播放地址无效");
  }
  const path = parsed.path.toLowerCase();
  if (!path.endsWith(".m3u8") && !path.endsWith(".mp4")) {
    throw new Error("帝果暂未提供可直接播放的媒体");
  }
  return address;
}

function splitTopLevel(value) {
  const parts = [];
  let depth = 0;
  let start = 0;
  for (let index = 0; index < value.length; index++) {
    const character = value[index];
    if (character === "(") {
      depth++;
    } else if (character === ")") {
      if (depth > 0) {
        depth--;
      }
    } else if (character === "+" && depth === 0) {
      const part = value.slice(start, index).trim();
      if (part !== "") {
        parts.push(part);
      }
      start = index + 1;
    }
  }
  const last = value.slice(start).trim();
  if (last !== "") {
    parts.push(last);
  }
  return parts;
}

function evaluateDigits(text) {
  if (byteLength(text) > 4096 || /[^0-9()+\-*/% \t\r\n]/.test(text)) {
    return null;
  }
  let index = 0;
  let depth = 0;
  const skipSpaces = () => {
    while (index < text.length && " \t\r\n".includes(text[index])) {
      index++;
    }
  };
  let parseExpression;
  const parseFactor = () => {
    skipSpaces();
    let sign = 1;
    while (index < text.length && (text[index] === "+" || text[index] === "-")) {
      if (text[index] === "-") {
        sign = -sign;
      }
      index++;
      skipSpaces();
    }
    if (index >= text.length) {
      return null;
    }
    if (text[index] === "(") {
      if (depth >= 32) {
        return null;
      }
      depth++;
      index++;
      const value = parseExpression();
      depth--;
      skipSpaces();
      if (value === null || index >= text.length || text[index] !== ")") {
        return null;
      }
      index++;
      return sign * value;
    }
    const start = index;
    while (index < text.length && text[index] >= "0" && text[index] <= "9") {
      index++;
    }
    if (start === index || index - start > 18) {
      return null;
    }
    return sign * parseInt(text.slice(start, index), 10);
  };
  const parseTerm = () => {
    let value = parseFactor();
    if (value === null) {
      return null;
    }
    for (;;) {
      skipSpaces();
      if (index >= text.length || !"*/%".includes(text[index])) {
        return value;
      }
      const operator = text[index++];
      const right = parseFactor();
      if (right === null || (right === 0 && operator !== "*")) {
        return null;
      }
      if (operator === "*") {
        value *= right;
      } else if (operator === "/") {
        if (value % right !== 0) {
          return null;
        }
        value = value / right;
      } else {
        value %= right;
      }
    }
  };
  parseExpression = () => {
    let value = parseTerm();
    if (value === null) {
      return null;
    }
    for (;;) {
      skipSpaces();
      if (index >= text.length || (text[index] !== "+" && text[index] !== "-")) {
        return value;
      }
      const operator = text[index++];
      const right = parseTerm();
      if (right === null) {
        return null;
      }
      value = operator === "+" ? value + right : value - right;
    }
  };
  const result = parseExpression();
  skipSpaces();
  return result !== null && index === text.length ? result : null;
}

function aaDigit(part) {
  part = part.trim();
  if (part === "") {
    return null;
  }
  if (part.includes(aaUnicode)) {
    return "u";
  }
  if (part.includes(aaB)) {
    return "b";
  }
  for (const [from, to] of aaReplacements) {
    part = part.split(from).join(to);
  }
  const value = evaluateDigits(part);
  return value === null ? null : String(value === 0 ? 0 : value);
}

function runeText(code) {
  return code >= 0 && code <= 0x10ffff && (code < 0xd800 || code > 0xdfff) ? String.fromCodePoint(code) : "\ufffd";
}

function decodeAaencode(raw) {
  if (raw.length > (1 << 20) / 3 && byteLength(raw) > 1 << 20) {
    return null;
  }
  const start = raw.indexOf(aaMarker);
  if (start < 0) {
    return null;
  }
  const body = raw.slice(start);
  const end = body.lastIndexOf(aaTailMarker);
  if (end < 0) {
    return null;
  }
  const tokens = body.slice(0, end).split(aaSeparator);
  let decoded = "";
  let decodedBytes = 0;
  for (const token of tokens.slice(1)) {
    if (decodedBytes > 64 << 10) {
      return null;
    }
    let digits = "";
    for (const part of splitTopLevel(token)) {
      const digit = aaDigit(part);
      if (digit === null) {
        return null;
      }
      digits += digit;
    }
    let code = null;
    if (digits.startsWith("u") && digits.length === 5) {
      if (!/^[+-]?[0-9a-fA-F]+$/.test(digits.slice(1))) {
        return null;
      }
      code = parseInt(digits.slice(1), 16);
    } else if (digits !== "" && /^[0-7]+$/.test(digits)) {
      code = parseInt(digits, 8);
      if (code > 0x7fffffff) {
        return null;
      }
    }
    if (code !== null) {
      const text = runeText(code);
      decoded += text;
      decodedBytes += byteLength(text);
    }
  }
  return decoded;
}

function signedPathFromVplayer(body) {
  for (const match of body.matchAll(scriptBlockPattern)) {
    if (!match[1].includes(aaFace)) {
      continue;
    }
    const decoded = decodeAaencode(match[1]);
    if (decoded === null) {
      continue;
    }
    const found = decoded.match(vPathPattern);
    if (found) {
      return found[1].trim();
    }
  }
  return "";
}

function vplayerMediaPath(address) {
  const parsed = parseAddress(address);
  if (parsed && parsed.scheme !== "") {
    let path = escapedPath(parsed) || "/";
    if (parsed.query !== "") {
      path += "?" + parsed.query;
    }
    return path;
  }
  return address;
}

function vplayerReferer() {
  return siteBaseUrl.replace(/\/+$/, "") + "/addons/vplayer/";
}

function signMedia(address, pageUrl) {
  if (address.includes("sign=")) {
    return { address, signed: false };
  }
  const vplayerUrl = siteBaseUrl.replace(/\/+$/, "") + "/addons/vplayer/?url=" + queryEscape(vplayerMediaPath(address)) + "&jump=";
  const body = host.fetchText({ url: vplayerUrl, referer: siteBaseUrl + "/", userAgent }).body;
  const signed = signedPathFromVplayer(body);
  if (signed === "") {
    return { address, signed: false };
  }
  const resolved = resolveUrl(vplayerReferer(), signed);
  const parsed = parseAddress(resolved);
  if (!parsed || !httpAddress(resolved)) {
    throw new Error("帝果播放签名地址无效");
  }
  const path = parsed.path.toLowerCase();
  if (!path.endsWith(".m3u8") && !path.endsWith(".mp4")) {
    throw new Error("帝果签名后未返回可播放媒体");
  }
  if (origin(parsed) === "" || pageUrl === "") {
    throw new Error("帝果播放签名缺少来源信息");
  }
  return { address: resolved, signed: true };
}

function mediaFromPagePath(cover, pageUrl) {
  const address = parseAddress(cover);
  const page = parseAddress(pageUrl);
  if (!address || !page || !httpAddress(cover) || origin(address) !== origin(page) || !videoDirectoryPattern.test(address.path) || !posterFilenamePattern.test(address.path)) {
    return "";
  }
  return address.scheme + "://" + address.host + escapedPath(address).replace(posterFilenamePattern, "/1000k/index.m3u8");
}

module.exports = {
  validCategory,

  categories(force) {
    return fetchCategories(force);
  },

  catalogPage(page, category) {
    return fetchCatalogPage(page, category, "");
  },

  search(page, query) {
    return fetchCatalogPage(page, "", query);
  },

  detail(sourceId) {
    if (!numericIdPattern.test(sourceId)) {
      throw new Error("帝果视频 ID 无效");
    }
    const { document, pageUrl } = fetchPage(episodePage(sourceId, 1, 1));
    return parseDetail(document, pageUrl, sourceId);
  },

  resolveMedia(task) {
    const dramaPrefix = host.source + ":";
    const sourceId = task.dramaId.startsWith(dramaPrefix) ? task.dramaId.slice(dramaPrefix.length).trim() : "";
    const prefix = host.chapterId(sourceId, "");
    if (!numericIdPattern.test(sourceId) || !task.chapter.id.startsWith(prefix)) {
      throw new Error("帝果播放信息无效，请刷新详情");
    }
    const parts = task.chapter.id.slice(prefix.length).split("-");
    if (parts.length !== 2) {
      throw new Error("帝果播放分集无效");
    }
    const line = webInteger(parts[0], 1000000);
    const episode = webInteger(parts[1], 1000000);
    if (!line.valid || line.value < 1 || !episode.valid || episode.value < 1) {
      throw new Error("帝果播放分集无效");
    }
    const { document, pageUrl } = fetchPage(episodePage(sourceId, line.value, episode.value));
    const { player, found } = parsePlayer(document);
    detailIdentity(document, sourceId, player, found);
    let address = "";
    if (found) {
      const actual = playerIdentity(player, sourceId);
      if (line.value !== actual.line || episode.value !== actual.episode) {
        throw new Error("帝果返回的播放地址与请求分集不符");
      }
      const preview = webInteger(player.trysee, 86400);
      if (preview.valid && preview.value > 0) {
        throw new Error("帝果仅提供试看，暂未取得正片播放地址");
      }
      address = issuedMedia(player, pageUrl);
    }
    if (address === "") {
      if (line.value !== 1 || episode.value !== 1) {
        throw new Error("帝果未提供该分集的播放地址");
      }
      const box = htmlFirstClass(document, "video-box");
      const cover = firstNonEmpty(coverAddress(player !== null ? player.poster : undefined, pageUrl), cardCover(box, pageUrl));
      address = mediaFromPagePath(cover, pageUrl);
    }
    if (address === "") {
      throw new Error("帝果未提供播放地址，请重试或确认站源权限");
    }
    const signed = signMedia(address, pageUrl);
    address = signed.address;
    const referer = signed.signed || address.includes("sign=") ? vplayerReferer() : pageUrl;
    const parsed = parseAddress(address);
    const media = { url: address, referer, credentials: { origin: parsed ? origin(parsed) : "", referer, userAgent } };
    return host.prepareWebMedia(media, channel);
  },
};
