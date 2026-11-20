const baseUrl = "https://bh4066.top";
const publishUrl = "https://beabox.cc";
const permanentUrl = "https://beabox.live";
const apiHosts = ["bh4066.top"];
const userAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
const cipherKey = host.hexDecode("f6322fa1c064370ea40c8cdc20649f8e");
const tagPrefix = "tag:";
const pageSize = 20;
const poolTtlMs = 30 * 60 * 1000;
const poolRetryTtlMs = 60 * 1000;
const maxBodyBytes = 20 * 1024 * 1024;
const requestTimeoutMs = 12000;
const channel = "笔盒";

const domainsPattern = /__ENC_DOMAINS\s*=\s*["']([A-Za-z0-9+/=_-]+)["']/;
const keyPattern = /__KEY\s*=\s*["']([^"']+)["']/;
const idPattern = /^[A-Za-z0-9]{1,64}$/;
const hostPattern = /^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?)+$/;
const emPattern = /<\/?em\b[^>]*>/gi;

function truncate(text, limit) {
  const trimmed = text.trim();
  const characters = Array.from(trimmed);
  return characters.length <= limit ? trimmed : characters.slice(0, limit).join("") + "...";
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
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

function mapStringSlice(row, ...keys) {
  const found = [];
  const add = (text) => {
    text = String(text).trim();
    if (text !== "" && !found.includes(text)) {
      found.push(text);
    }
  };
  if (!isRecord(row)) {
    return found;
  }
  for (const key of keys) {
    const value = row[key];
    if (typeof value === "string") {
      for (const part of value.split(/[,\/，、]/)) {
        add(part);
      }
    } else if (Array.isArray(value)) {
      for (const item of value) {
        if (isRecord(item)) {
          const name = mapString(item, "name", "title", "label", "tag");
          if (name !== "") {
            add(name);
          }
          continue;
        }
        add(item === null ? "<nil>" : typeof item === "object" ? JSON.stringify(item) : item);
      }
    }
  }
  return found;
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

function cleanText(text) {
  text = text.replace(/<script\b[\s\S]*?<\/script>/gi, " ").replace(/<style\b[\s\S]*?<\/style>/gi, " ").replace(/<[^>]+>/g, " ");
  return htmlUnescape(text).replace(/[\t\n\f\r ]+/g, " ").trim();
}

function boxText(text) {
  return cleanText(htmlUnescape(htmlUnescape(text)).replace(emPattern, ""));
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

function decodeJson(text) {
  return JSON.parse(host.preserveIntegers(text));
}

function validId(id) {
  return idPattern.test(id.trim());
}

function validCategory(category) {
  if (category === "") {
    return true;
  }
  if (!category.startsWith(tagPrefix)) {
    return false;
  }
  const tag = category.slice(tagPrefix.length).trim();
  return tag !== "" && Array.from(tag).length <= 24 && !/[|/\\\u0000\r\n]/.test(tag);
}

function tagName(category) {
  return (category.startsWith(tagPrefix) ? category.slice(tagPrefix.length) : category).trim();
}

function siteUrl(raw) {
  const address = parseAddress(String(raw || "").trim());
  if (!address || address.scheme !== "https" || address.userInfo || address.path.replace(/^\/+|\/+$/g, "") !== "" || address.query !== "" || address.port !== "" || !hostPattern.test(address.hostname)) {
    return "";
  }
  return "https://" + address.hostname;
}

function apiSite(site) {
  return apiHosts.includes(site.slice("https://".length));
}

function loadSiteState() {
  const cached = host.cacheGet("site-state");
  if (cached !== null) {
    return JSON.parse(cached);
  }
  const state = { version: 1, site: "", pool: [] };
  let raw = "";
  try {
    raw = host.loadState("sites");
  } catch (error) {
    raw = "";
  }
  if (raw !== "" && raw.length <= 64 * 1024) {
    let saved = null;
    try {
      saved = JSON.parse(raw);
    } catch (error) {
      saved = null;
    }
    if (isRecord(saved) && saved.version === 1) {
      state.site = siteUrl(saved.site);
      for (const entry of Array.isArray(saved.pool) ? saved.pool : []) {
        const site = siteUrl(entry);
        if (site !== "" && !state.pool.includes(site) && state.pool.length < 256) {
          state.pool.push(site);
        }
      }
    }
  }
  host.cacheSet("site-state", JSON.stringify(state), 0);
  return state;
}

function saveSiteState(state) {
  const encoded = JSON.stringify(state);
  host.cacheSet("site-state", encoded, 0);
  host.saveState("sites", encoded);
}

function rememberSite(site) {
  const state = loadSiteState();
  if (state.site !== site) {
    state.site = site;
    saveSiteState(state);
  }
}

function knownSites() {
  const sites = [];
  for (const value of [loadSiteState().site, baseUrl]) {
    const site = value.trim().replace(/\/+$/, "");
    if (site !== "" && !sites.includes(site)) {
      sites.push(site);
    }
  }
  return sites;
}

function boxFetch(method, address, referer, payload) {
  const headers = { "User-Agent": userAgent, "Accept-Language": "zh-CN,zh;q=0.9", Referer: referer };
  if (payload !== null) {
    headers["Content-Type"] = "application/json";
    headers.Accept = "application/json";
  }
  const request = { method, url: address, headers, limit: maxBodyBytes, timeoutMs: requestTimeoutMs, followRedirects: true, anyStatus: true };
  if (payload !== null) {
    request.body = payload;
  }
  const response = host.fetch(request);
  return { status: response.status, body: response.body };
}

function decodeBase64Any(value) {
  try {
    return host.base64Decode(value.replace(/-/g, "+").replace(/_/g, "/"));
  } catch (error) {
    return null;
  }
}

function parsePublishPage(body) {
  const encoded = body.match(domainsPattern);
  const key = body.match(keyPattern);
  if (!encoded || !key || key[1] === "" || key[1].length > 64) {
    return [];
  }
  const raw = decodeBase64Any(encoded[1]);
  if (raw === null || raw.length === 0 || raw.length > 1 << 20) {
    return [];
  }
  const secret = host.utf8Encode(key[1]);
  const plain = new Uint8Array(raw.length);
  for (let index = 0; index < raw.length; index++) {
    plain[index] = raw[index] ^ secret[index % secret.length];
  }
  let hosts;
  try {
    hosts = JSON.parse(host.utf8Decode(plain));
  } catch (error) {
    return [];
  }
  if (hosts === null) {
    hosts = [];
  }
  if (!Array.isArray(hosts) || hosts.length > 256 || hosts.some((entry) => typeof entry !== "string")) {
    return [];
  }
  const sites = [];
  for (let entry of hosts) {
    entry = entry.trim().toLowerCase();
    const site = "https://" + entry;
    if (hostPattern.test(entry) && !sites.includes(site)) {
      sites.push(site);
    }
  }
  return sites;
}

function discoverSites() {
  for (const seed of [publishUrl, permanentUrl]) {
    let response;
    try {
      response = boxFetch("GET", seed + "/", seed + "/", null);
    } catch (error) {
      continue;
    }
    if (response.status < 200 || response.status >= 300) {
      continue;
    }
    const sites = parsePublishPage(response.body);
    if (sites.length > 0) {
      return sites;
    }
  }
  return [];
}

function discoveredSites() {
  return host.exclusive(() => {
    const state = loadSiteState();
    if (host.cacheGet("pool-fresh") !== null) {
      return state.pool;
    }
    const sites = discoverSites().filter(apiSite);
    const changed = sites.length > 0 && JSON.stringify(sites) !== JSON.stringify(state.pool);
    if (sites.length > 0) {
      state.pool = sites;
      host.cacheSet("pool-fresh", "1", poolTtlMs);
    } else {
      host.cacheSet("pool-fresh", "1", poolRetryTtlMs);
    }
    if (changed) {
      saveSiteState(state);
    }
    return state.pool;
  });
}

function parseEnvelope(body) {
  let value;
  try {
    value = decodeJson(body);
  } catch (error) {
    return null;
  }
  return isRecord(value) && value.code !== undefined && value.code !== null ? value : null;
}

function isCiphertext(value) {
  return value.length >= 64 && value.length % 2 === 0 && /^[0-9a-fA-F]+$/.test(value);
}

function unpad(data) {
  const pad = data.length > 0 ? data[data.length - 1] : 0;
  if (data.length === 0 || data.length % 16 !== 0 || pad === 0 || pad > 16) {
    return null;
  }
  for (let index = data.length - pad; index < data.length; index++) {
    if (data[index] !== pad) {
      return null;
    }
  }
  return data.subarray(0, data.length - pad);
}

function decodeData(encoded) {
  const failure = new Error("笔盒接口数据解码失败");
  const raw = host.hexDecode(encoded);
  if (raw.length < 32 || (raw.length - 16) % 16 !== 0) {
    throw failure;
  }
  const plain = unpad(host.aesCbcDecrypt(cipherKey, raw.subarray(0, 16), raw.subarray(16)));
  if (plain === null) {
    throw failure;
  }
  try {
    return decodeJson(host.utf8Decode(plain));
  } catch (error) {
    throw failure;
  }
}

function envelopeData(envelope) {
  const code = webInteger(envelope.code, 1000000);
  if (!code.valid || code.value !== 200) {
    const message = truncate(cleanText(mapString(envelope, "msg")), 180) || "请稍后重试";
    throw new Error("笔盒请求未完成：" + message);
  }
  const data = envelope.data;
  if (typeof data === "string" && isCiphertext(data)) {
    return decodeData(data);
  }
  return data === undefined ? null : data;
}

function boxCall(route, query, payload) {
  let method = "GET";
  let path = "/api" + route;
  if (query !== null && Object.keys(query).length > 0) {
    path += "?" + encodeQuery(query);
  }
  let body = null;
  if (payload !== null) {
    method = "POST";
    body = JSON.stringify(payload);
  }
  const tried = new Set();
  let lastError = null;
  const call = (sites) => {
    for (const site of sites) {
      if (tried.has(site)) {
        continue;
      }
      tried.add(site);
      let response;
      try {
        response = boxFetch(method, site + path, site + "/home", body);
      } catch (error) {
        lastError = error;
        continue;
      }
      const envelope = parseEnvelope(response.body);
      if (envelope === null) {
        lastError = new Error("笔盒线路 " + site.slice("https://".length) + " 不可用（HTTP " + response.status + "）");
        continue;
      }
      return { envelope, site };
    }
    return null;
  };
  let found = call(knownSites());
  if (found === null) {
    found = call(discoveredSites());
  }
  if (found === null) {
    throw lastError || new Error("笔盒线路暂不可用，请稍后重试");
  }
  rememberSite(found.site);
  return { value: envelopeData(found.envelope), site: found.site };
}

function pad2(value) {
  return value < 10 ? "0" + value : String(value);
}

function onlineDate(value) {
  const text = nativeText(value);
  const seconds = /^[+-]?\d+$/.test(text) ? parseInt(text, 10) : NaN;
  if (!(seconds >= 946684800 && seconds <= 4102444800)) {
    return "";
  }
  const date = new Date(seconds * 1000);
  return date.getFullYear() + "-" + pad2(date.getMonth() + 1) + "-" + pad2(date.getDate());
}

function tagsOf(row) {
  const tags = [];
  for (const value of mapStringSlice(row, "vodClass")) {
    const tag = boxText(value).trim();
    if (tag === "" || Array.from(tag).length > 48 || tags.includes(tag)) {
      continue;
    }
    tags.push(tag);
    if (tags.length >= 32) {
      break;
    }
  }
  return tags;
}

function dramaFromRow(row, site, fallbackCategory) {
  const id = mapString(row, "vodId").trim();
  const title = truncate(boxText(mapString(row, "vodName")), 512);
  if (!validId(id) || title === "") {
    return null;
  }
  const cover = coverAddress(isRecord(row) ? row.vodPic : undefined, site + "/");
  let heat = mapString(row, "heatValue");
  if (heat === "0") {
    heat = "";
  }
  const tags = tagsOf(row);
  const drama = {
    id: host.dramaId(id),
    source: host.source,
    sourceId: id,
    title,
    name: title,
    cover,
    coverUrl: cover,
    categoryName: tags.length > 0 ? tags[0] : fallbackCategory,
    channelName: channel,
  };
  if (tags.length > 0) {
    drama.tags = tags;
  }
  if (heat !== "") {
    drama.heat = heat;
  }
  const date = onlineDate(row.vodTime);
  if (date !== "") {
    drama.onlineDate = date;
  }
  return drama;
}

function fetchCatalogPage(page, category, query) {
  if (page < 1 || page > 1000000 || byteLength(query) > 256 || !validCategory(category)) {
    throw new Error("笔盒目录查询参数无效");
  }
  let keyword = query.trim();
  let fallbackCategory = "";
  if (keyword === "") {
    fallbackCategory = tagName(category);
    keyword = fallbackCategory;
  }
  let route = "/vod/latest";
  const values = { page: String(page), limit: String(pageSize) };
  if (keyword !== "") {
    route = "/vod/search";
    values.keyword = keyword;
    values.sort = "latest";
  }
  const { value, site } = boxCall(route, values, null);
  if (!isRecord(value)) {
    throw new Error("笔盒目录格式无效");
  }
  let rows = [];
  if (value.data !== undefined && value.data !== null) {
    if (!Array.isArray(value.data)) {
      throw new Error("笔盒目录格式无效");
    }
    rows = value.data;
  }
  if (rows.length > 500) {
    throw new Error("笔盒目录格式无效");
  }
  const totalPages = webInteger(value.totalPages, 1000000);
  const hasMore = totalPages.valid ? page < totalPages.value : rows.length >= pageSize;
  const items = [];
  const seen = new Set();
  for (const row of rows) {
    const drama = dramaFromRow(isRecord(row) ? row : null, site, fallbackCategory);
    if (drama === null || seen.has(drama.id)) {
      continue;
    }
    seen.add(drama.id);
    items.push(drama);
  }
  return { items, hasMore };
}

function playValue(row) {
  for (const key of ["vodPlaySource", "vodPlay"]) {
    const value = row[key];
    if (isRecord(value) && Object.keys(value).length > 0) {
      return value;
    }
    if (Array.isArray(value) && value.length > 0) {
      return value;
    }
    if (typeof value === "string" && value.trim() !== "") {
      return value;
    }
  }
  return null;
}

function playEntries(value) {
  return Array.isArray(value) ? value.filter(isRecord) : [];
}

function playGroups(value) {
  if (isRecord(value)) {
    return Object.keys(value)
      .sort()
      .map((name) => ({ name, entries: playEntries(value[name]) }));
  }
  if (Array.isArray(value)) {
    return [{ name: "", entries: playEntries(value) }];
  }
  if (typeof value === "string" && value.trim() !== "") {
    return [{ name: "", entries: [{ url: value }] }];
  }
  return [];
}

function entryPlayable(entry) {
  return firstNonEmpty(mapString(entry, "url"), mapString(entry, "playUrl")) !== "";
}

function bestGroup(groups) {
  let best = { name: "", entries: [] };
  let bestScore = -1;
  for (const group of groups) {
    const score = group.entries.filter(entryPlayable).length;
    if (score > bestScore || (score === bestScore && group.name === "beabox-app" && best.name !== "beabox-app")) {
      best = group;
      bestScore = score;
    }
  }
  return best;
}

function flag(value) {
  if (typeof value === "boolean") {
    return value;
  }
  if (typeof value === "string") {
    return value === "1" || value.toLowerCase() === "true";
  }
  return value === 1;
}

function chaptersOf(row, site, sourceId) {
  const best = bestGroup(playGroups(playValue(row)));
  const chapters = [];
  best.entries.forEach((entry, index) => {
    if (!entryPlayable(entry)) {
      return;
    }
    const key = best.name + ":" + index;
    const title = boxText(mapString(entry, "flag")).trim() || "第 " + (chapters.length + 1) + " 集";
    chapters.push({
      id: host.chapterId(sourceId, key),
      source: host.source,
      title: truncate(title, 128),
      currentEpisode: chapters.length + 1,
      videoUrl: "beabox-play://" + key,
      pageUrl: site + "/vods/" + encodeURIComponent(sourceId),
      referer: site + "/",
    });
  });
  return chapters;
}

function fetchDetailRow(sourceId) {
  return boxCall("/vod/detail/" + encodeURIComponent(sourceId), null, null);
}

module.exports = {
  validCategory,

  categories() {
    const { value } = boxCall("/configs", { platformType: "h5" }, null);
    if (!isRecord(value)) {
      throw new Error("笔盒分类数据格式无效");
    }
    const categories = [];
    const seen = new Set();
    for (let name of mapString(value, "home_tags").split(",")) {
      name = name.trim();
      const id = tagPrefix + name;
      if (name === "" || Array.from(name).length > 24 || seen.has(id) || !validCategory(id)) {
        continue;
      }
      seen.add(id);
      categories.push({ id, name });
      if (categories.length >= 64) {
        break;
      }
    }
    if (categories.length === 0) {
      throw new Error("笔盒暂未返回内容分类");
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
      throw new Error("笔盒视频 ID 无效");
    }
    const { value, site } = fetchDetailRow(sourceId);
    if (!isRecord(value) || mapString(value, "vodId").trim() !== sourceId) {
      throw new Error("笔盒详情与请求视频不符");
    }
    const drama = dramaFromRow(value, site, "");
    if (drama === null) {
      throw new Error("笔盒视频资料不完整");
    }
    const chapters = chaptersOf(value, site, sourceId);
    if (chapters.length === 0) {
      throw new Error("笔盒该视频暂无可播放线路");
    }
    drama.episodeCount = chapters.length;
    drama.totalEpisode = chapters.length;
    return { drama, chapters };
  },

  resolveMedia(task) {
    const failure = "笔盒播放信息无效，请刷新详情";
    const dramaId = task.dramaId.trim();
    const separator = dramaId.indexOf(":");
    const sourceId = separator < 0 ? "" : dramaId.slice(separator + 1).trim();
    const prefix = host.chapterId(sourceId, "");
    if (separator < 0 || dramaId.slice(0, separator).trim().toLowerCase() !== host.source || !validId(sourceId) || !task.chapter.id.startsWith(prefix)) {
      throw new Error(failure);
    }
    const key = task.chapter.id.slice(prefix.length);
    const cut = key.lastIndexOf(":");
    if (cut < 0) {
      throw new Error(failure);
    }
    const group = key.slice(0, cut);
    const index = parseInteger(key.slice(cut + 1));
    if (!(index >= 0)) {
      throw new Error(failure);
    }
    const { value, site } = fetchDetailRow(sourceId);
    if (!isRecord(value)) {
      throw new Error("笔盒详情格式无效");
    }
    const found = playGroups(playValue(value)).find((item) => item.name === group && index < item.entries.length);
    if (!found) {
      throw new Error("笔盒分集已变化，请刷新详情");
    }
    const entry = found.entries[index];
    let address = firstNonEmpty(mapString(entry, "url"), mapString(entry, "playUrl"));
    if (flag(entry.needParse) || flag(value.playNeedParse)) {
      const parsed = boxCall("/vod/parse-url", null, { playUrl: mapString(entry, "playUrl").trim(), playerFrom: mapString(entry, "from").trim() }).value;
      address = mapString(isRecord(parsed) ? parsed : null, "url");
    }
    if (!httpAddress(address)) {
      throw new Error("笔盒未返回有效播放地址，请刷新详情后重试");
    }
    return host.prepareWebMedia({ url: address, referer: site + "/" }, channel);
  },
};
