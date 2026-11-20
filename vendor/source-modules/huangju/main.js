const siteBaseUrl = "https://huangju.net";
const apiBaseUrl = "https://api.huangju.net";
const userAgent = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36";
const newestCategory = "@new";
const channel = "剧果";
const tokenKey = "guest-token";
const deviceKey = "guest-device";
const signedCookieNames = ["CloudFront-Policy", "CloudFront-Signature", "CloudFront-Key-Pair-Id"];
const signedCookiePattern = /(?:^|[,;\t\n\f\r ])CloudFront-(Policy|Signature|Key-Pair-Id)[\t\n\f\r ]*=[\t\n\f\r ]*([^;,\t\n\f\r "']+)/g;

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

function integer(value, maximum) {
  const text = nativeText(value);
  if (!/^[+-]?\d+$/.test(text)) {
    return null;
  }
  const number = parseInt(text, 10);
  return number >= 0 && number <= maximum ? number : null;
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

function origin(parsed) {
  const scheme = parsed.scheme.toLowerCase();
  let address = parsed.host.toLowerCase();
  if ((scheme === "https" && parsed.port === "443") || (scheme === "http" && parsed.port === "80")) {
    address = address.slice(0, address.length - parsed.port.length - 1);
  }
  return scheme + "://" + address;
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

function validId(value) {
  return (
    value !== "" &&
    byteLength(value) <= 450 &&
    value.trim() === value &&
    value !== "." &&
    value !== ".." &&
    !/[/\\:?#%|"<>]/.test(value) &&
    !/[\u0000-\u001f\u007f-\u009f]/.test(value)
  );
}

function validCategory(category) {
  if (category === "") {
    return true;
  }
  if (byteLength(category) > 128 || /[|/\\\x00\r\n]/.test(category)) {
    return false;
  }
  return category === newestCategory || (validId(category) && !category.startsWith("@"));
}

function guestExpired() {
  const error = new Error("剧果访客授权已失效，请重试");
  error.guestExpired = true;
  return error;
}

function newDeviceId() {
  const bytes = host.randomBytes(16);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = host.hexEncode(bytes);
  return hex.slice(0, 8) + "-" + hex.slice(8, 12) + "-" + hex.slice(12, 16) + "-" + hex.slice(16, 20) + "-" + hex.slice(20);
}

function request(method, route, query, body, token, retryAuth, limit) {
  let address = apiBaseUrl + route;
  if (query && Object.keys(query).length > 0) {
    address += "?" + encodeQuery(query);
  }
  const headers = {
    "User-Agent": userAgent,
    Accept: "application/json, text/plain, */*",
    "Accept-Language": "zh-CN,zh;q=0.9",
    Referer: siteBaseUrl + "/",
    Origin: siteBaseUrl,
  };
  if (body !== "") {
    headers["Content-Type"] = "application/json";
  }
  if (token !== "") {
    headers.Authorization = "Bearer " + token;
  }
  const input = { method, url: address, headers, body, limit, timeoutMs: 15000 };
  if (retryAuth && token !== "") {
    input.acceptStatus = [401, 403];
  }
  const response = host.fetch(input);
  if (response.status === 401 || response.status === 403) {
    throw guestExpired();
  }
  let value;
  try {
    value = JSON.parse(host.preserveIntegers(response.body));
  } catch (error) {
    throw new Error("剧果返回的数据格式无效");
  }
  return { value, headers: response.headers || {} };
}

function validToken(token) {
  return typeof token === "string" && token !== "" && byteLength(token) <= 8192 && !/[\u0000-\u0020\u007f-\uffff]/.test(token);
}

function guestToken() {
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
    const response = request("POST", "/auth/guest", null, JSON.stringify({ deviceId }), "", false, 64 << 10);
    const row = isRecord(response.value) ? response.value : {};
    let token = typeof row.token === "string" ? row.token : "";
    if (token === "" && isRecord(row.data) && typeof row.data.token === "string") {
      token = row.data.token;
    }
    if (!validToken(token)) {
      throw new Error("剧果未返回有效的访客授权");
    }
    host.cacheSet(tokenKey, token, 0);
    return token;
  });
}

function apiGet(route, query, limit) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const token = guestToken();
    try {
      return request("GET", route, query, "", token, attempt === 0, limit);
    } catch (error) {
      if (attempt === 0 && error.guestExpired) {
        if (host.cacheGet(tokenKey) === token) {
          host.cacheSet(tokenKey, null, 0);
        }
        continue;
      }
      throw error;
    }
  }
  throw guestExpired();
}

function payload(value) {
  if (isRecord(value) && present(value.data) && !present(value.id) && !present(value.items) && !present(value.url)) {
    return value.data;
  }
  return value;
}

function categoryNames(row) {
  const names = [];
  const add = (value) => {
    let name = "";
    if (isRecord(value)) {
      name = typeof value.name === "string" ? value.name : "";
    } else if (typeof value === "string") {
      name = value;
    }
    name = name.trim();
    if (name !== "" && Array.from(name).length <= 48 && !names.includes(name) && names.length < 24) {
      names.push(name);
    }
  };
  add(row.category);
  if (Array.isArray(row.categories)) {
    row.categories.forEach(add);
  }
  return names;
}

function dramaFromRow(row, sourceId) {
  row = isRecord(row) ? row : {};
  const id = mapString(row, "id");
  const slug = mapString(row, "slug");
  const title = typeof row.title === "string" ? row.title : "";
  if (!validId(id) || title.trim() === "") {
    throw new Error("剧果返回的剧集信息不完整");
  }
  if (sourceId === "") {
    if (!validId(slug)) {
      throw new Error("剧果返回的剧集地址无效");
    }
    sourceId = slug + "-" + id;
  }
  if (!validId(sourceId) || !sourceId.endsWith("-" + id)) {
    throw new Error("剧果详情与请求剧集不符");
  }
  let episodes = 0;
  if (present(row.totalEpisodes)) {
    episodes = integer(row.totalEpisodes, 100000);
    if (episodes === null) {
      throw new Error("剧果集数格式无效");
    }
  }
  const categories = categoryNames(row);
  const tags = categories.slice();
  const region = mapString(row, "region");
  if (region !== "") {
    tags.push(truncate(region, 48));
  }
  const year = integer(row.year, 9999);
  if (year !== null && year >= 1000) {
    tags.push(year + "年");
  }
  let status = mapString(row, "status");
  if (status === "completed") {
    status = "finished";
  }
  if (status !== "finished" && status !== "ongoing") {
    status = "";
  }
  const description = typeof row.description === "string" ? row.description : "";
  const cover = coverAddress(row.coverUrl, siteBaseUrl + "/");
  return {
    id: host.dramaId(sourceId),
    source: host.source,
    sourceId,
    title: truncate(title, 256),
    name: truncate(title, 256),
    desc: truncate(description, 12000),
    intro: truncate(description, 12000),
    cover,
    coverUrl: cover,
    totalEpisode: episodes,
    episodeCount: episodes,
    category: categories.length > 0 ? categories[0] : "短剧",
    channelName: channel,
    tags,
    releaseStatus: status,
    score: mapString(row, "score"),
  };
}

function fetchCatalogPage(page, category, query) {
  if (page < 1 || page > 1000000 || byteLength(query) > 1024 || !validCategory(category)) {
    throw new Error("剧果目录查询参数无效");
  }
  const values = { page: String(page) };
  if (query !== "") {
    values.q = query;
  } else if (category === newestCategory) {
    values.sort = "new";
  } else if (category === "") {
    values.sort = "hot";
  } else {
    values.category = category;
  }
  const response = apiGet("/dramas", values, 4 << 20);
  const row = payload(response.value);
  const rows = isRecord(row) ? row.items : undefined;
  if (!Array.isArray(rows) || rows.length > 500) {
    throw new Error("剧果目录数据格式无效");
  }
  let pageSize = 20;
  if (present(row.pageSize)) {
    pageSize = integer(row.pageSize, 500);
    if (pageSize === null || pageSize < 1) {
      throw new Error("剧果分页数据格式无效");
    }
  }
  if (present(row.page) && integer(row.page, 1000000) !== page) {
    throw new Error("剧果返回的页码与请求不符");
  }
  if (rows.length > pageSize) {
    throw new Error("剧果分页数据格式无效");
  }
  let hasMore = rows.length >= pageSize;
  if (present(row.total)) {
    const total = integer(row.total, 100000000);
    if (total === null || total < rows.length) {
      throw new Error("剧果分页总数无效");
    }
    hasMore = page < Math.floor((total + pageSize - 1) / pageSize);
  }
  if (hasMore && rows.length === 0) {
    throw new Error("剧果未返回应有的目录页，请重试");
  }
  const items = [];
  const seen = new Set();
  for (const entry of rows) {
    const drama = dramaFromRow(entry, "");
    if (seen.has(drama.id)) {
      throw new Error("剧果目录包含重复剧集，请重试");
    }
    seen.add(drama.id);
    items.push(drama);
  }
  return { items, hasMore };
}

function parseExpiration(value) {
  const text = nativeText(value);
  if (/^[+-]?\d+$/.test(text)) {
    const stamp = Number(text);
    if (stamp > 0 && stamp <= 253402300799000) {
      return stamp > 100000000000 ? stamp : stamp * 1000;
    }
  }
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(text)) {
    const parsed = Date.parse(text.replace(/(\.\d{3})\d+/, "$1"));
    return isNaN(parsed) ? null : parsed;
  }
  return null;
}

const months = { Jan: 0, Feb: 1, Mar: 2, Apr: 3, May: 4, Jun: 5, Jul: 6, Aug: 7, Sep: 8, Oct: 9, Nov: 10, Dec: 11 };

function cookieExpires(value) {
  const match = value.match(/^[A-Z][a-z]{2}, (\d{2})[ -]([A-Z][a-z]{2})[ -](\d{4}) (\d{2}):(\d{2}):(\d{2}) [A-Z]+$/);
  if (!match || !Object.prototype.hasOwnProperty.call(months, match[2])) {
    return null;
  }
  return Date.UTC(parseInt(match[3], 10), months[match[2]], parseInt(match[1], 10), parseInt(match[4], 10), parseInt(match[5], 10), parseInt(match[6], 10));
}

function cookieValue(raw) {
  if (raw.length > 1 && raw[0] === '"' && raw[raw.length - 1] === '"') {
    raw = raw.slice(1, -1);
  }
  return /^[\u0020\u0021\u0023-\u002b\u002d-\u003a\u003c-\u005b\u005d-\u007e]*$/.test(raw) ? raw : null;
}

function parseSetCookie(line) {
  const parts = line.trim().split(";");
  if (parts.length === 1 && parts[0] === "") {
    return null;
  }
  const first = parts[0].trim();
  const separator = first.indexOf("=");
  if (separator < 0) {
    return null;
  }
  const name = first.slice(0, separator).trim();
  const value = cookieValue(first.slice(separator + 1));
  if (name === "" || value === null) {
    return null;
  }
  const cookie = { name, value, expires: null, maxAge: 0 };
  for (let index = 1; index < parts.length; index++) {
    const part = parts[index].trim();
    if (part === "") {
      continue;
    }
    const cut = part.indexOf("=");
    const attribute = (cut < 0 ? part : part.slice(0, cut)).toLowerCase();
    const raw = cookieValue(cut < 0 ? "" : part.slice(cut + 1));
    if (raw === null) {
      continue;
    }
    if (attribute === "max-age") {
      if (!/^[+-]?\d+$/.test(raw) || (raw !== "0" && raw[0] === "0")) {
        continue;
      }
      const seconds = parseInt(raw, 10);
      cookie.maxAge = seconds <= 0 ? -1 : seconds;
    } else if (attribute === "expires") {
      cookie.expires = cookieExpires(raw);
    }
  }
  return cookie;
}

function mediaCredentials(headers, address, expiration) {
  const lines = Array.isArray(headers["Set-Cookie"]) ? headers["Set-Cookie"] : [];
  const cookies = {};
  let expires = parseExpiration(expiration);
  const remember = (value) => {
    if (value !== null && (expires === null || value < expires)) {
      expires = value;
    }
  };
  for (const line of lines) {
    const cookie = parseSetCookie(line);
    if (cookie && signedCookieNames.includes(cookie.name)) {
      cookies[cookie.name] = cookie.value;
      remember(cookie.expires);
      if (cookie.maxAge !== 0) {
        remember(Date.now() + Math.min(cookie.maxAge, 86400 * 365) * 1000);
      }
    }
  }
  for (const line of lines) {
    let count = 0;
    for (const match of line.matchAll(signedCookiePattern)) {
      if (++count > 12) {
        break;
      }
      cookies["CloudFront-" + match[1]] = match[2];
    }
  }
  const values = [];
  for (const name of signedCookieNames) {
    const value = cookies[name] || "";
    if (value === "" || byteLength(value) > 8192 || /[,;"\\]/.test(value) || /[\u0000-\u0020\u007f-\uffff]/.test(value)) {
      throw new Error("剧果未返回完整的媒体凭证，请重试");
    }
    values.push(name + "=" + value);
  }
  if (expires !== null && Date.now() >= expires) {
    throw new Error("剧果返回的媒体凭证已过期，请重试；反复出现时请检查设备时间");
  }
  const parsed = parseAddress(address);
  if (!parsed || !httpAddress(address)) {
    throw new Error("剧果播放地址无效");
  }
  return {
    cookie: values.join("; "),
    origin: origin(parsed),
    referer: siteBaseUrl + "/",
    userAgent,
    expiresMs: expires === null ? 0 : expires,
  };
}

module.exports = {
  validCategory,

  categories() {
    const rows = payload(apiGet("/categories", null, 256 << 10).value);
    if (!Array.isArray(rows) || rows.length > 1000) {
      throw new Error("剧果分类数据格式无效");
    }
    const categories = [{ id: newestCategory, name: "最新" }];
    const seen = new Set([newestCategory]);
    for (const entry of rows) {
      const id = mapString(entry, "slug");
      const name = mapString(entry, "name");
      if (id === "hot") {
        continue;
      }
      if (!validCategory(id) || id === "" || name === "" || Array.from(name).length > 48 || seen.has(id)) {
        throw new Error("剧果分类数据包含无效或重复条目");
      }
      seen.add(id);
      categories.push({ id, name });
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
    if (!validId(sourceId)) {
      throw new Error("剧果剧集地址无效");
    }
    const row = payload(apiGet("/dramas/" + pathEscape(sourceId), null, 8 << 20).value);
    const drama = dramaFromRow(row, sourceId);
    const rows = row.episodes;
    if (!Array.isArray(rows) || rows.length > 10000) {
      throw new Error("剧果分集目录格式无效");
    }
    const chapters = [];
    const seenIds = new Set();
    const seenNumbers = new Set();
    rows.forEach((entry, index) => {
      const episode = isRecord(entry) ? entry : {};
      if (episode.playable === false) {
        return;
      }
      const id = mapString(episode, "id");
      if (!validId(id) || seenIds.has(id)) {
        throw new Error("剧果分集目录包含无效或重复分集");
      }
      let number = index + 1;
      if (present(episode.epNo)) {
        number = integer(episode.epNo, 100000);
        if (number === null) {
          throw new Error("剧果分集编号无效");
        }
        if (number === 0) {
          number = index + 1;
        }
      }
      if (seenNumbers.has(number)) {
        throw new Error("剧果分集编号重复，请刷新目录");
      }
      seenIds.add(id);
      seenNumbers.add(number);
      chapters.push({
        id: host.chapterId(sourceId, id),
        source: host.source,
        title: "第 " + number + " 集",
        currentEpisode: number,
        videoUrl: "huangju-play://" + pathEscape(id),
        referer: siteBaseUrl + "/",
      });
    });
    chapters.sort((left, right) => left.currentEpisode - right.currentEpisode);
    return { drama, chapters };
  },

  resolveMedia(task) {
    const dramaPrefix = host.source + ":";
    const sourceId = task.dramaId.startsWith(dramaPrefix) ? task.dramaId.slice(dramaPrefix.length).trim() : "";
    const prefix = host.chapterId(sourceId, "");
    if (!validId(sourceId) || !task.chapter.id.startsWith(prefix)) {
      throw new Error("剧果播放分集信息无效，请刷新详情");
    }
    const id = task.chapter.id.slice(prefix.length);
    if (!validId(id)) {
      throw new Error("剧果分集地址无效");
    }
    const response = apiGet("/play/" + pathEscape(id), null, 64 << 10);
    const row = payload(response.value);
    const address = isRecord(row) && typeof row.url === "string" ? row.url : "";
    const credentials = mediaCredentials(response.headers, address, isRecord(row) ? row.expiresAt : undefined);
    const media = { url: address, referer: credentials.referer, credentials };
    if (parseAddress(address).path.toLowerCase().endsWith(".m3u8")) {
      let playlist;
      try {
        playlist = host.fetchPlaylist({ url: address, referer: media.referer, credentials });
      } catch (error) {
        throw new Error("获取剧果播放列表失败：" + error.message);
      }
      media.playlist = playlist.playlist;
      media.url = playlist.url;
      media.durationMs = playlist.durationMs;
    }
    return media;
  },
};
