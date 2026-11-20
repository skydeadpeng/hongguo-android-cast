const siteBaseUrl = "https://jinguoduanju.app";
const userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
const homeCategory = "home";
const heatCategory = "heat";
const pageSize = 24;
const channel = "禁果短剧";
const tokenKey = "access-token";
const deviceKey = "device-id";
const baseKey = "api-base";
const listingTtlMs = 10 * 60 * 1000;
const numericIdPattern = /^[1-9][0-9]{0,17}$/;

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
  const number = /^[+-]?\d+$/.test(text) ? parseInt(text, 10) : 0;
  return { value: number, valid: /^[+-]?\d+$/.test(text) && number >= 0 && number <= maximum };
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
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value)) {
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

function httpAddress(raw) {
  const parsed = parseAddress(String(raw).trim());
  return parsed !== null && (parsed.scheme === "http" || parsed.scheme === "https") && parsed.hostname !== "" && !parsed.userInfo;
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

function goUnquote(text) {
  if (text.includes("\n")) {
    return null;
  }
  const simple = { a: 7, b: 8, f: 12, n: 10, r: 13, t: 9, v: 11, "\\": 92, '"': 34 };
  const bytes = [];
  const pushText = (value) => {
    for (const byte of host.utf8Encode(value)) {
      bytes.push(byte);
    }
  };
  for (let index = 0; index < text.length; index++) {
    const character = text[index];
    if (character === '"') {
      return null;
    }
    if (character !== "\\") {
      pushText(character);
      continue;
    }
    const escape = text[++index];
    if (escape === undefined) {
      return null;
    }
    if (Object.prototype.hasOwnProperty.call(simple, escape)) {
      bytes.push(simple[escape]);
    } else if (escape === "x") {
      const hex = text.slice(index + 1, index + 3);
      if (!/^[0-9a-fA-F]{2}$/.test(hex)) {
        return null;
      }
      bytes.push(parseInt(hex, 16));
      index += 2;
    } else if (escape === "u" || escape === "U") {
      const size = escape === "u" ? 4 : 8;
      const hex = text.slice(index + 1, index + 1 + size);
      const code = /^[0-9a-fA-F]+$/.test(hex) && hex.length === size ? parseInt(hex, 16) : -1;
      if (code < 0 || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) {
        return null;
      }
      pushText(String.fromCodePoint(code));
      index += size;
    } else if (escape >= "0" && escape <= "7") {
      const octal = text.slice(index, index + 3);
      if (!/^[0-7]{3}$/.test(octal) || parseInt(octal, 8) > 255) {
        return null;
      }
      bytes.push(parseInt(octal, 8));
      index += 2;
    } else {
      return null;
    }
  }
  return host.utf8Decode(bytes);
}

function normalizeMediaUrl(pageUrl, raw) {
  raw = htmlUnescape(String(raw || "")).trim().replace(/^[ \t\r\n"']+|[ \t\r\n"']+$/g, "");
  if (raw === "") {
    return "";
  }
  const unquoted = goUnquote(raw.replace(/"/g, '\\"'));
  if (unquoted !== null) {
    raw = unquoted;
  }
  raw = raw.replace(/\\\//g, "/");
  const resolved = resolveUrl(pageUrl, raw);
  const lower = resolved.toLowerCase();
  return lower.includes(".m3u8") || lower.includes(".mp4") ? resolved : "";
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

function queryEscape(text) {
  return encodeURIComponent(text)
    .replace(/[!'()*]/g, (character) => "%" + character.charCodeAt(0).toString(16).toUpperCase())
    .replace(/%20/g, "+");
}

function encodeQuery(params) {
  return Object.keys(params)
    .sort()
    .map((key) => queryEscape(key) + "=" + queryEscape(String(params[key])))
    .join("&");
}

function splitCategory(category) {
  const separator = category.indexOf(":");
  return separator < 0 ? null : { kind: category.slice(0, separator), id: category.slice(separator + 1) };
}

function validCategory(category) {
  if (category === "" || category === homeCategory || category === heatCategory) {
    return true;
  }
  const parts = splitCategory(category);
  return parts !== null && (parts.kind === "nav" || parts.kind === "tag") && numericIdPattern.test(parts.id);
}

function active(row, key) {
  const value = isRecord(row) ? row[key] : undefined;
  return !present(value) || value === true;
}

function failure(text, flags) {
  return Object.assign(new Error(text), flags);
}

function authExpired() {
  return failure("禁果短剧访问授权已失效，请重试", { authExpired: true });
}

function newDeviceId() {
  const bytes = host.randomBytes(16);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = host.hexEncode(bytes);
  return hex.slice(0, 8) + "-" + hex.slice(8, 12) + "-" + hex.slice(12, 16) + "-" + hex.slice(16, 20) + "-" + hex.slice(20);
}

function apiOrigin(base) {
  const parsed = parseAddress(base);
  return parsed && parsed.scheme !== "" && parsed.host !== "" ? parsed.scheme + "://" + parsed.host : "";
}

function pinnedBase() {
  return host.cacheGet(baseKey) || "";
}

function siteUrl() {
  const pinned = pinnedBase();
  return pinned !== "" ? apiOrigin(pinned) || siteBaseUrl : siteBaseUrl;
}

function candidates() {
  const bases = [];
  for (const base of [pinnedBase(), siteBaseUrl + "/api/v1", "https://jgdj.app/api/v1", "https://play.jinguoduanju.app/api/v1"]) {
    if (base !== "" && !bases.includes(base)) {
      bases.push(base);
    }
  }
  return bases;
}

function errorMessage(body) {
  let row;
  try {
    row = JSON.parse(body);
  } catch (error) {
    return "";
  }
  const message = mapString(row, "message", "msg", "tip");
  if (message === "" || Array.from(message).length > 180 || /[\r\n]/.test(message)) {
    return "";
  }
  return message;
}

function requestBase(base, method, route, query, body, token, retryAuth, limit) {
  let address = base.replace(/\/+$/, "") + route;
  if (query && Object.keys(query).length > 0) {
    address += "?" + encodeQuery(query);
  }
  const origin = apiOrigin(base);
  const headers = {
    "User-Agent": userAgent,
    Accept: "application/json, text/plain, */*",
    "Accept-Language": "zh-CN,zh;q=0.9",
    Referer: origin + "/",
    Origin: origin,
  };
  if (body !== "") {
    headers["Content-Type"] = "application/json";
  }
  if (token !== "") {
    headers.Authorization = "Bearer " + token;
  }
  let response;
  try {
    response = host.fetch({ method, url: address, headers, body, limit, timeoutMs: 15000 });
  } catch (error) {
    const status = error.status || 0;
    if (retryAuth && token !== "" && status === 401 && !error.blocked) {
      throw authExpired();
    }
    if (status === 0) {
      throw error;
    }
    const message = errorMessage(error.body || "");
    throw failure(message !== "" && !error.blocked ? "禁果短剧接口返回失败：" + message : error.message, { statusError: true });
  }
  try {
    return JSON.parse(host.preserveIntegers(response.body));
  } catch (error) {
    throw new Error("禁果短剧返回的数据格式无效");
  }
}

function request(method, route, query, body, token, retryAuth, limit) {
  let lastError = null;
  for (const base of candidates()) {
    try {
      const decoded = requestBase(base, method, route, query, body, token, retryAuth, limit);
      host.cacheSet(baseKey, base, 0);
      return decoded;
    } catch (error) {
      if (error.authExpired || error.statusError) {
        throw error;
      }
      lastError = error;
    }
  }
  throw lastError || new Error("禁果短剧线路暂不可用，请稍后重试");
}

function validToken(token) {
  return token !== "" && byteLength(token) <= 8192 && !/[\u0000-\u0020\u007f-\uffff]/.test(token);
}

function accessToken() {
  const cached = host.cacheGet(tokenKey);
  if (cached !== null) {
    return cached;
  }
  return host.exclusive(() => {
    const current = host.cacheGet(tokenKey);
    if (current !== null) {
      return current;
    }
    let deviceId = host.cacheGet(deviceKey);
    if (deviceId === null) {
      deviceId = newDeviceId();
      host.cacheSet(deviceKey, deviceId, 0);
    }
    const body = JSON.stringify({ attributionToken: "", channelCode: "", deviceId: "h5:" + deviceId });
    const token = mapString(request("POST", "/auth/device", null, body, "", false, 64 << 10), "token");
    if (!validToken(token)) {
      throw new Error("禁果短剧未返回有效的访问授权");
    }
    host.cacheSet(tokenKey, token, 0);
    return token;
  });
}

function apiGet(route, query, limit) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const token = accessToken();
    try {
      return request("GET", route, query, "", token, attempt === 0, limit);
    } catch (error) {
      if (attempt === 0 && error.authExpired) {
        if (host.cacheGet(tokenKey) === token) {
          host.cacheSet(tokenKey, null, 0);
        }
        continue;
      }
      throw error;
    }
  }
  throw authExpired();
}

function firstPresent(row, ...keys) {
  for (const key of keys) {
    const value = row[key];
    if (present(value) && nativeText(value) !== "") {
      return value;
    }
  }
  return null;
}

function dramaFromRow(row, site, sourceId) {
  row = isRecord(row) ? row : {};
  const id = mapString(row, "id");
  const title = mapString(row, "title").trim();
  if (!numericIdPattern.test(id) || title === "") {
    throw new Error("禁果短剧剧集资料不完整");
  }
  if (sourceId !== "" && sourceId !== id) {
    throw new Error("禁果短剧详情与请求剧集不符");
  }
  const episodes = webInteger(row.episodeCount, 100000).value;
  const tags = [];
  if (Array.isArray(row.tags)) {
    for (const value of row.tags) {
      const tag = nativeText(value).trim();
      if (tag === "" || Array.from(tag).length > 24 || tags.includes(tag) || tags.length >= 32) {
        continue;
      }
      tags.push(tag);
    }
  }
  const description = truncate(firstNonEmpty(mapString(row, "description"), mapString(row, "subtitle")), 12000);
  const cover = coverAddress(firstPresent(row, "coverUrl", "cover"), site + "/");
  const drama = {
    id: host.dramaId(id),
    source: host.source,
    sourceId: id,
    title: truncate(title, 512),
    name: truncate(title, 512),
    desc: description,
    intro: description,
    cover,
    coverUrl: cover,
    episodeCount: episodes,
    totalEpisode: episodes,
    categoryName: tags.length > 0 ? tags[0] : "短剧",
    channelName: channel,
    tags,
    onlineDate: releaseDate(mapString(row, "publishedAt")),
  };
  const heat = webInteger(row.heat, 1000000000);
  if (heat.valid && heat.value > 0) {
    drama.heat = String(heat.value);
  }
  const filmType = mapString(row, "filmType").toUpperCase();
  if (filmType === "FREE") {
    drama.vip = false;
  } else if (filmType !== "") {
    drama.vip = true;
  }
  return drama;
}

function dramaRows(value) {
  if (Array.isArray(value)) {
    if (value.length > 20000) {
      throw new Error("禁果短剧目录格式无效");
    }
    return value;
  }
  if (isRecord(value)) {
    for (const key of ["dramas", "list", "items", "records"]) {
      if (Array.isArray(value[key])) {
        if (value[key].length > 20000) {
          throw new Error("禁果短剧目录格式无效");
        }
        return value[key];
      }
    }
  }
  throw new Error("禁果短剧目录格式无效");
}

function catalogQuery(category, query) {
  query = query.trim();
  if (!validCategory(category) || byteLength(query) > 256) {
    throw new Error("禁果短剧目录查询参数无效");
  }
  const values = { sort: "latest" };
  if (query !== "") {
    values.q = query;
    return values;
  }
  if (category === homeCategory) {
    values.displayArea = "HOME_FEED";
  } else if (category === heatCategory) {
    values.sort = "heat";
  } else {
    const parts = splitCategory(category);
    if (parts && parts.kind === "nav") {
      values.navigationId = parts.id;
    } else if (parts && parts.kind === "tag") {
      values.tags = parts.id;
    }
  }
  return values;
}

function fetchDramas(category, query) {
  const values = catalogQuery(category, query);
  const rows = dramaRows(apiGet("/dramas", values, 8 << 20));
  const site = siteUrl();
  const items = [];
  const seen = new Set();
  for (const value of rows) {
    let drama;
    try {
      drama = dramaFromRow(value, site, "");
    } catch (error) {
      continue;
    }
    if (seen.has(drama.id)) {
      continue;
    }
    seen.add(drama.id);
    items.push(drama);
  }
  return items;
}

function fetchCatalogPage(page, category, query) {
  const key = "listing:" + category + "\u0000" + query.trim();
  const cached = host.cacheGet(key);
  let items = cached === null ? null : JSON.parse(cached);
  if (page === 1 || items === null) {
    items = fetchDramas(category, query);
    try {
      host.cacheSet(key, JSON.stringify(items), listingTtlMs);
    } catch (error) {
      host.cacheSet(key, null, 0);
    }
  }
  if (page < 1 || page > 1000000) {
    throw new Error("禁果短剧目录页码无效");
  }
  const start = (page - 1) * pageSize;
  if (start >= items.length) {
    return { items: [], hasMore: false };
  }
  const end = Math.min(start + pageSize, items.length);
  return { items: items.slice(start, end), hasMore: end < items.length };
}

function chaptersOf(site, sourceId, rows) {
  if (rows.length > 10000) {
    throw new Error("禁果短剧分集目录格式无效");
  }
  const chapters = [];
  const seenIds = new Set();
  const seenNumbers = new Set();
  for (const value of rows) {
    const row = isRecord(value) ? value : {};
    const id = mapString(row, "id");
    const number = webInteger(row.number, 100000);
    if (!numericIdPattern.test(id) || !number.valid || number.value < 1 || seenIds.has(id) || seenNumbers.has(number.value)) {
      throw new Error("禁果短剧分集编号无效或重复，请刷新详情");
    }
    seenIds.add(id);
    seenNumbers.add(number.value);
    const chapter = {
      id: host.chapterId(sourceId, id),
      source: host.source,
      title: truncate(firstNonEmpty(mapString(row, "title"), "第" + number.value + "集"), 256),
      currentEpisode: number.value,
      videoUrl: "jinguoduanju-play://" + id,
      referer: site + "/",
    };
    if (row.locked === true) {
      chapter.vip = true;
    }
    chapters.push(chapter);
  }
  return chapters;
}

module.exports = {
  validCategory,

  categories() {
    const navigation = apiGet("/top-navigation", null, 1 << 20);
    let filters = null;
    let filterFailed = false;
    try {
      filters = apiGet("/filter-config", null, 1 << 20);
    } catch (error) {
      filterFailed = true;
    }
    if (!Array.isArray(navigation) || navigation.length > 200) {
      throw new Error("禁果短剧分类数据格式无效");
    }
    const categories = [
      { id: homeCategory, name: "推荐" },
      { id: heatCategory, name: "热度" },
    ];
    const seenIds = new Set([homeCategory, heatCategory]);
    const seenNames = new Set(["推荐", "热度", "全部"]);
    const add = (id, name) => {
      name = name.trim();
      if (categories.length >= 64 || !validCategory(id) || name === "" || Array.from(name).length > 24 || seenIds.has(id) || seenNames.has(name)) {
        return;
      }
      seenIds.add(id);
      seenNames.add(name);
      categories.push({ id, name });
    };
    for (const row of navigation) {
      if (!active(row, "active") || !active(row, "valid") || mapString(row, "targetType").toUpperCase() === "SYSTEM") {
        continue;
      }
      const id = mapString(row, "id");
      if (numericIdPattern.test(id)) {
        add("nav:" + id, mapString(row, "frontendName"));
      }
    }
    if (!filterFailed) {
      const groups = Array.isArray(filters) ? filters : [];
      if (groups.length > 80) {
        throw new Error("禁果短剧分类数据格式无效");
      }
      for (const group of groups) {
        if (!active(group, "active") || !active(group, "frontendVisible")) {
          continue;
        }
        const tags = isRecord(group) && Array.isArray(group.tags) ? group.tags : [];
        if (tags.length > 300) {
          throw new Error("禁果短剧分类条目过多");
        }
        for (const tag of tags) {
          if (!active(tag, "active") || !active(tag, "frontendVisible")) {
            continue;
          }
          const id = mapString(tag, "id");
          if (numericIdPattern.test(id)) {
            add("tag:" + id, mapString(tag, "name"));
          }
        }
      }
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
      throw new Error("禁果短剧剧集 ID 无效");
    }
    const decoded = apiGet("/dramas/" + pathEscape(sourceId), null, 4 << 20);
    const object = isRecord(decoded) ? decoded : null;
    let row = object !== null && isRecord(object.drama) ? object.drama : object;
    const description = mapString(object, "description");
    if (description !== "" && mapString(row, "description") === "") {
      row.description = description;
    }
    const site = siteUrl();
    const drama = dramaFromRow(row, site, sourceId);
    if (!Array.isArray(object.episodes)) {
      throw new Error("禁果短剧分集目录格式无效");
    }
    const chapters = chaptersOf(site, sourceId, object.episodes);
    const count = chapters.length;
    if (count > 0) {
      const listed = webInteger(drama.episodeCount, 100000);
      if (!listed.valid || count > listed.value) {
        drama.episodeCount = count;
        drama.totalEpisode = count;
      }
    }
    return { drama, chapters };
  },

  resolveMedia(task) {
    const dramaPrefix = host.source + ":";
    const sourceId = task.dramaId.startsWith(dramaPrefix) ? task.dramaId.slice(dramaPrefix.length).trim() : "";
    const prefix = host.chapterId(sourceId, "");
    if (!numericIdPattern.test(sourceId) || !task.chapter.id.startsWith(prefix)) {
      throw new Error("禁果短剧播放分集信息无效，请刷新详情");
    }
    const episodeId = task.chapter.id.slice(prefix.length);
    if (!numericIdPattern.test(episodeId)) {
      throw new Error("禁果短剧播放分集 ID 无效");
    }
    const row = apiGet("/episodes/" + pathEscape(episodeId) + "/play", null, 256 << 10);
    const id = mapString(row, "episodeId");
    if (id !== "" && id !== episodeId) {
      throw new Error("禁果短剧返回的播放地址与请求分集不符");
    }
    if (isRecord(row) && isRecord(row.allowance) && row.allowance.allowed === false) {
      throw new Error("禁果短剧该集需要会员或解锁后才能播放");
    }
    const referer = siteUrl() + "/";
    const address = normalizeMediaUrl(referer, mapString(row, "videoUrl"));
    if (!httpAddress(address)) {
      throw new Error("禁果短剧未提供该集播放地址，请确认是否需要会员或解锁");
    }
    return host.prepareWebMedia({ url: address, referer }, channel);
  },
};
