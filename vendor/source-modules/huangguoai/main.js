const siteBaseUrl = "https://huangguoai.com";
const userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1";
const channel = "huangguoai.com";
const searchPageSize = 20;
const edgeProtocol = "dx150-edge-v1";
const edgePlayback = "playback";
const edgeMaxSequence = 16384;
const edgeMaxLifetime = 600;
const edgeSessionFailure = "黄果播放会话已失效";
const baseMirrors = ["https://huangguoai.com", "https://thu.lbvrhyir.cc", "https://pku.xxbgwypx.cc", "https://ttvoij.ediayikma.cc", "https://thu.ediayikma.cc", "https://pku.ediayikma.cc", "https://fdu.ediayikma.cc", "https://thu.agdkczeyx.cc"];
const mirrorDomains = ["lbvrhyir.cc", "xxbgwypx.cc", "ediayikma.cc", "agdkczeyx.cc"];
const categories = [
  { id: "ai-duanju", name: "AI 短剧" },
  { id: "ai-manju", name: "AI 漫剧" },
  { id: "ai-huanlian", name: "AI 换脸" },
  { id: "ai-mogai", name: "AI 魔改" },
];
const rankingBoards = {
  "huangguo-hot": { name: "热播榜", path: "hot" },
  "huangguo-recommend": { name: "推荐榜", path: "recommend" },
  "huangguo-potential": { name: "潜力榜", path: "potential" },
};

const sourceIdPattern = /^[a-zA-Z0-9_-]{1,100}$/;
const titleFragmentPattern = /(?:\b(?:class|type|title|aria-label)\s*=|(?:data|ata)-search-input|hg-search(?:__|-))/i;
const episodeNumberPattern = /(?:第\s*0*(\d+)\s*(?:集|话|期)|(?:更新至|共|全)\s*0*(\d+)\s*(?:集|话|期)|(?:episode|ep)\s*#?\s*0*(\d+))/i;

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

function atoi(text) {
  return /^[+-]?\d+$/.test(text) ? parseInt(text, 10) : NaN;
}

function goJson(value) {
  return JSON.stringify(value).replace(/[<>&\u2028\u2029]/g, (character) => "\\u" + character.charCodeAt(0).toString(16).padStart(4, "0"));
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

function normalizeViews(text) {
  text = text.trim();
  if (text === "") {
    return "";
  }
  if (text.includes("次播放")) {
    return text;
  }
  if (/[w万]/.test(text.toLowerCase())) {
    return text + "次播放";
  }
  if (!/^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/.test(text)) {
    return text;
  }
  const number = Number(text);
  return number <= 0 ? "" : String(number) + "次播放";
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

function base64UrlEncode(data) {
  return host.base64Encode(data).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlDecode(text) {
  if (typeof text !== "string" || !/^[A-Za-z0-9_-]*$/.test(text) || text.length % 4 === 1) {
    return null;
  }
  try {
    return host.base64Decode(text.replace(/-/g, "+").replace(/_/g, "/"));
  } catch (error) {
    return null;
  }
}

function episodeIndex(text, fallback) {
  const match = text.match(episodeNumberPattern);
  if (match) {
    for (const group of match.slice(1)) {
      if (group !== undefined && group !== "") {
        const number = parseInt(group, 10);
        if (number > 0) {
          return number;
        }
      }
    }
  }
  return fallback;
}

function chapterEpisode(chapter, fallback) {
  const current = chapter.currentEpisode;
  if (Number.isInteger(current) && current > 0) {
    return current;
  }
  if (typeof current === "string") {
    return episodeIndex(current, fallback);
  }
  return episodeIndex(chapter.title || "", fallback);
}

function cleanId(text) {
  text = htmlUnescape(text).trim().replace(/^[ /\t\r\n"']+|[ /\t\r\n"']+$/g, "");
  const cut = text.search(/[?#]/);
  return cut < 0 ? text : text.slice(0, cut);
}

function detailIdFrom(text) {
  const parsed = parseAddress(htmlUnescape(text));
  const parts = (parsed ? parsed.path : text).replace(/^\/+|\/+$/g, "").split("/");
  for (let index = 0; index + 1 < parts.length; index++) {
    if (parts[index] === "detail") {
      return cleanId(parts[index + 1]);
    }
  }
  return "";
}

function titleNeedsRepair(title, sourceId) {
  title = title.trim();
  return title === "" || title === sourceId || title === "短剧" || title === "搜索" || titleFragmentPattern.test(title) || (title.includes("搜索记录") && title.includes("暂无搜索记录"));
}

function mapEpisodes(row) {
  for (const key of ["episode_count", "episodeCount", "total_episodes", "totalEpisodes", "totalEpisode", "total_episode", "episodes", "total"]) {
    const count = atoi(mapString(row, key));
    if (count > 0) {
      return String(count);
    }
  }
  return "";
}

function dramaFromMap(row, pageUrl, category) {
  let sourceId = firstNonEmpty(mapString(row, "id"), mapString(row, "videoId"), mapString(row, "video_id"), mapString(row, "vid"), mapString(row, "slug"));
  if (sourceId === "") {
    for (const value of Object.values(row)) {
      if (typeof value === "string") {
        const id = detailIdFrom(value);
        if (id !== "") {
          sourceId = id;
          break;
        }
      }
    }
  }
  const detailId = detailIdFrom(sourceId);
  if (detailId !== "") {
    sourceId = detailId;
  }
  sourceId = cleanId(sourceId);
  if (sourceId === "") {
    return null;
  }
  const title = firstNonEmpty(mapString(row, "title"), mapString(row, "name"), mapString(row, "videoTitle"), mapString(row, "video_title"), mapString(row, "videoName"), mapString(row, "video_name"));
  if (titleNeedsRepair(title, sourceId)) {
    return null;
  }
  const cover = resolveUrl(pageUrl, firstNonEmpty(...["cover", "coverUrl", "cover_url", "coverImage", "cover_image", "image", "imageUrl", "thumbnail", "poster", "posterUrl"].map((key) => mapString(row, key))));
  const description = firstNonEmpty(mapString(row, "desc"), mapString(row, "description"), mapString(row, "intro"), mapString(row, "summary"));
  const episodes = mapEpisodes(row);
  let status = "";
  const finished = mapString(row, "is_finished").toLowerCase();
  if (finished === "true" || finished === "1") {
    status = "finished";
  } else if (finished === "false" || finished === "0") {
    status = "ongoing";
  }
  let remark = firstNonEmpty(mapString(row, "vod_remarks"), mapString(row, "remark"), mapString(row, "remarks"));
  if (remark === "" && episodes !== "") {
    remark = status === "finished" ? "全" + episodes + "集" : "更新至 " + episodes + " 集";
  }
  const viewKeys = ["views", "view_count", "viewCount", "view_num", "viewNum", "views_text", "view_count_text", "play_count", "playCount", "play_num", "playNum", "play_count_text", "hits", "hit_count", "plays", "play"];
  const dateKeys = ["onlineDate", "online_date", "online_time", "onlineTime", "publish_date", "publishDate", "publish_time", "publishTime", "release_date", "releaseDate"];
  const drama = {
    id: host.dramaId(sourceId),
    source: host.source,
    sourceId,
    title,
    name: title,
    desc: description,
    intro: description,
    cover,
    coverUrl: cover,
    totalEpisode: episodes,
    episodeCount: episodes,
    categoryName: category,
    channelName: channel,
    remark,
    score: firstNonEmpty(mapString(row, "score"), mapString(row, "rating")),
    views: normalizeViews(firstNonEmpty(...viewKeys.map((key) => mapString(row, key)))),
    onlineDate: releaseDate(firstNonEmpty(...dateKeys.map((key) => mapString(row, key)))),
    releaseStatus: status,
  };
  const tags = mapStringSlice(row, "tags", "tag", "categories", "category");
  if (tags.length > 0) {
    drama.tags = tags;
  }
  return drama;
}

function parseCards(value, pageUrl, category) {
  const seen = new Set();
  const items = [];
  const walk = (node) => {
    if (Array.isArray(node)) {
      for (const item of node) {
        walk(item);
      }
    } else if (isRecord(node)) {
      const drama = dramaFromMap(node, pageUrl, category);
      if (drama !== null) {
        if (!seen.has(drama.sourceId)) {
          seen.add(drama.sourceId);
          items.push(drama);
        }
        return;
      }
      for (const child of Object.values(node)) {
        walk(child);
      }
    }
  };
  walk(value);
  return items;
}

function rehost(raw, mirror) {
  const address = parseAddress(raw);
  const target = parseAddress(mirror);
  if (!address || !target || target.host === "") {
    return "";
  }
  return target.scheme + "://" + target.host + raw.slice((address.scheme + "://" + address.host).length);
}

function generatedMirrors(seed, count) {
  const found = [];
  const seen = new Set();
  for (let index = 0; found.length < count && index < count * 4; index++) {
    const hex = host.hexEncode(host.sha256(host.utf8Encode(seed + ":" + index)));
    let sub = hex.slice(0, 6).replace(/^[0-9]+/, "");
    if (sub.length < 4) {
      sub = hex.slice(6, 12);
    }
    if (sub.length > 6) {
      sub = sub.slice(0, 6);
    }
    if (sub.length < 3 || seen.has(sub)) {
      continue;
    }
    seen.add(sub);
    found.push("https://" + sub + "." + mirrorDomains[found.length % mirrorDomains.length]);
  }
  return found;
}

function urlCandidates(raw) {
  const address = parseAddress(raw);
  if (!address || address.hostname !== "huangguoai.com") {
    return [raw];
  }
  const found = [];
  const add = (candidate) => {
    if (candidate !== "" && !found.includes(candidate)) {
      found.push(candidate);
    }
  };
  baseMirrors.forEach((mirror, index) => {
    if (index === 1) {
      for (const generated of generatedMirrors(raw, 8)) {
        add(rehost(raw, generated));
      }
    }
    add(rehost(raw, mirror));
  });
  return found.length === 0 ? [raw] : found;
}

function apiReply(text) {
  let reply;
  try {
    reply = JSON.parse(text);
  } catch (error) {
    throw new Error("黄果接口返回格式无效：" + error.message);
  }
  if (reply === null) {
    reply = {};
  }
  if (!isRecord(reply) || !(reply.status === undefined || reply.status === null || Number.isInteger(reply.status)) || !(reply.msg === undefined || reply.msg === null || typeof reply.msg === "string")) {
    throw new Error("黄果接口返回格式无效：字段类型不符");
  }
  if (reply.status !== 1) {
    throw new Error("黄果接口返回失败：" + firstNonEmpty(reply.msg || "", "未知错误"));
  }
  return reply.data === undefined ? null : reply.data;
}

function api(path) {
  const address = siteBaseUrl + "/api/edge/request";
  const envelope = goJson({ body: "", content_type: "", method: "GET", path });
  const response = host.fetchText({ url: address, referer: siteBaseUrl + "/", body: envelope, mirrors: urlCandidates(address) });
  return apiReply(response.body);
}

function dataShapeError(message) {
  return new Error("黄果接口数据格式已变化：" + message);
}

function chapterOf(sourceId, episode, title) {
  let key = "auto-1";
  let pageUrl = siteBaseUrl + "/video/" + sourceId + "/";
  if (episode > 1) {
    key = "ep-" + episode;
    pageUrl += key + "/";
  }
  return { id: host.chapterId(sourceId, key), source: host.source, title: firstNonEmpty(title, "第" + episode + "集"), pageUrl, currentEpisode: episode };
}

function sortChapters(chapters) {
  return chapters
    .map((chapter, position) => ({ chapter, position }))
    .sort((left, right) => left.chapter.currentEpisode - right.chapter.currentEpisode || (left.chapter.id < right.chapter.id ? -1 : left.chapter.id > right.chapter.id ? 1 : left.position - right.position))
    .map((entry) => entry.chapter);
}

function uniqueChapters(chapters) {
  const seen = new Set();
  return chapters.filter((chapter, index) => {
    const key = chapter.id || chapter.videoUrl || "idx_" + index;
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

function edgePost(base, body) {
  return host.fetch({
    method: "POST",
    url: base + "/api/" + edgePlayback + "/request",
    body: goJson(body),
    headers: { "Content-Type": "application/json", "User-Agent": userAgent, Origin: base, Referer: base + "/", "Accept-Language": "zh-CN,zh;q=0.9" },
    limit: 2 << 20,
    timeoutMs: 12000,
    anyStatus: true,
  });
}

function nowSeconds() {
  return Math.floor(Date.now() / 1000);
}

function stringFields(value, keys) {
  return keys.every((key) => value[key] === undefined || value[key] === null || typeof value[key] === "string");
}

function integerFields(value, keys) {
  return keys.every((key) => value[key] === undefined || value[key] === null || Number.isInteger(value[key]));
}

function openEdgeSession(base) {
  const failure = new Error("黄果播放会话握手失败，请稍后重试");
  const pair = host.ecdhP256KeyPair();
  const response = edgePost(base, { client_pub: base64UrlEncode(pair.publicKey), type: "hello", v: 1 });
  let hello;
  try {
    hello = JSON.parse(response.body);
  } catch (error) {
    throw failure;
  }
  if (hello === null) {
    hello = {};
  }
  if (
    response.status !== 200 ||
    !isRecord(hello) ||
    !stringFields(hello, ["type", "kid", "sid", "site", "server_pub", "salt"]) ||
    !integerFields(hello, ["server_time", "expires_at"]) ||
    hello.type !== "hello" ||
    !hello.kid ||
    !hello.sid ||
    !hello.site ||
    (hello.expires_at || 0) <= (hello.server_time || 0)
  ) {
    throw failure;
  }
  const serverPublic = base64UrlDecode(hello.server_pub || "");
  const salt = base64UrlDecode(hello.salt || "");
  if (serverPublic === null || salt === null || salt.length !== 32) {
    throw failure;
  }
  let secret;
  try {
    secret = host.ecdhP256(pair.privateKey, serverPublic);
  } catch (error) {
    throw failure;
  }
  const info = [edgeProtocol, edgePlayback, hello.site, hello.kid, hello.sid].join("\n");
  const lifetime = Math.min(hello.expires_at - (hello.server_time || 0), edgeMaxLifetime);
  return {
    base,
    info,
    kid: hello.kid,
    sid: hello.sid,
    offset: (hello.server_time || 0) - nowSeconds(),
    expires: Date.now() + lifetime * 1000,
    sequence: 0,
    requestKey: host.hexEncode(host.hkdfSha256(secret, salt, info + "\nreq", 32)),
    responseKey: host.hexEncode(host.hkdfSha256(secret, salt, info + "\nres", 32)),
  };
}

function sessionUsable(session, base) {
  return session !== null && session.base === base && session.sequence < edgeMaxSequence && Date.now() + 5000 < session.expires;
}

function edgeExchange(session, method, path, body, contentType) {
  session.sequence++;
  host.cacheSet("edge-session", JSON.stringify(session), 0);
  const sequence = session.sequence;
  const stamp = nowSeconds() + session.offset;
  const nonce = new Uint8Array(12);
  nonce[8] = (sequence >>> 24) & 255;
  nonce[9] = (sequence >>> 16) & 255;
  nonce[10] = (sequence >>> 8) & 255;
  nonce[11] = sequence & 255;
  const associated = (direction) => [session.info, direction, String(sequence), String(stamp)].join("\n");
  const packet = goJson({ request: { body: host.base64Encode(body), content_type: contentType, method, path } });
  const sealed = host.aesGcmSeal(host.hexDecode(session.requestKey), nonce, packet, associated("req"));
  const response = edgePost(session.base, { ciphertext: base64UrlEncode(sealed), kid: session.kid, seq: sequence, sid: session.sid, ts: stamp, type: "call", v: 1 });
  const expired = new Error(edgeSessionFailure);
  expired.session = true;
  let result;
  try {
    result = JSON.parse(response.body);
  } catch (error) {
    throw expired;
  }
  if (result === null) {
    result = {};
  }
  if (
    response.status !== 200 ||
    !isRecord(result) ||
    !stringFields(result, ["type", "sid", "ciphertext"]) ||
    !(result.seq === undefined || result.seq === null || (Number.isInteger(result.seq) && result.seq >= 0 && result.seq <= 4294967295)) ||
    result.type !== "result" ||
    result.sid !== session.sid ||
    result.seq !== sequence
  ) {
    throw expired;
  }
  const encrypted = base64UrlDecode(result.ciphertext || "");
  if (encrypted === null) {
    throw expired;
  }
  let plain;
  try {
    plain = host.aesGcmOpen(host.hexDecode(session.responseKey), nonce, encrypted, associated("res"));
  } catch (error) {
    throw expired;
  }
  let inner;
  try {
    inner = JSON.parse(host.utf8Decode(plain));
  } catch (error) {
    throw new Error("黄果接口返回格式无效：" + error.message);
  }
  if (inner === null) {
    inner = {};
  }
  if (!isRecord(inner) || !integerFields(inner, ["status"]) || !stringFields(inner, ["body"])) {
    throw new Error("黄果接口返回格式无效：字段类型不符");
  }
  const status = inner.status || 0;
  if (status < 200 || status >= 300) {
    throw new Error("黄果接口返回失败：HTTP " + status);
  }
  return host.utf8Decode(host.base64Decode(inner.body || ""));
}

function edgeCall(method, path, body, contentType) {
  const base = firstNonEmpty(host.preferredSite(), siteBaseUrl).replace(/\/+$/, "");
  const data = host.exclusive(() => {
    for (let attempt = 0; ; attempt++) {
      const cached = host.cacheGet("edge-session");
      let session = cached === null ? null : JSON.parse(cached);
      if (!sessionUsable(session, base)) {
        session = openEdgeSession(base);
        host.cacheSet("edge-session", JSON.stringify(session), 0);
      }
      try {
        return edgeExchange(session, method, path, body, contentType);
      } catch (error) {
        host.cacheSet("edge-session", null, 0);
        if (!error.session || attempt > 0) {
          throw error;
        }
      }
    }
  });
  return apiReply(data);
}

function playUrl(sourceId, episode) {
  if (!sourceIdPattern.test(sourceId) || episode < 1) {
    throw new Error("黄果集数无效");
  }
  const play = edgeCall("POST", "/api/videos/" + encodeURIComponent(sourceId) + "/episodes/" + episode + "/playback", "{}", "application/json");
  const streams = isRecord(play) ? play.streams : null;
  if (streams !== undefined && streams !== null && (!isRecord(streams) || !stringFields(streams, ["main", "hevc"]))) {
    throw dataShapeError("播放地址字段类型不符");
  }
  const found = isRecord(streams) ? firstNonEmpty(streams.main || "", streams.hevc || "") : "";
  return normalizeMediaUrl(siteBaseUrl + "/", found);
}

function rankingMetric(row) {
  const label = mapString(row, "metric_label");
  const text = mapString(row, "metric_value");
  const value = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/.test(text) ? Number(text) : NaN;
  if (label === "" || isNaN(value)) {
    return "";
  }
  if (value >= 10000) {
    return label + " " + (value / 10000).toFixed(1) + "万";
  }
  return label + " " + String(value);
}

function parseRanking(rows, pageUrl) {
  const failure = new Error("黄果未返回有效榜单");
  if (!Array.isArray(rows) || rows.length === 0 || rows.length > 100) {
    throw failure;
  }
  const items = [];
  const seen = new Set();
  rows.forEach((row, index) => {
    if (!isRecord(row)) {
      throw failure;
    }
    row.id = row.video_id === undefined ? null : row.video_id;
    const drama = dramaFromMap(row, pageUrl, mapString(row, "cate_name"));
    if (drama === null || !sourceIdPattern.test(drama.sourceId) || seen.has(drama.sourceId)) {
      throw failure;
    }
    seen.add(drama.sourceId);
    let rank = atoi(mapString(row, "rank"));
    if (!(rank >= 1)) {
      rank = index + 1;
    }
    const item = { rank, drama };
    const metric = rankingMetric(row);
    if (metric !== "") {
      item.metric = metric;
    }
    items.push(item);
  });
  return { items, totalPages: 1 };
}

module.exports = {
  validCategory(category) {
    return categories.some((entry) => entry.id === category);
  },

  categories() {
    return categories.slice();
  },

  catalogPage(page, category) {
    let selected = categories;
    if (category !== "") {
      const found = categories.find((entry) => entry.id === category);
      if (found) {
        selected = [found];
      }
    }
    let items = [];
    const failures = [];
    let more = false;
    for (const entry of selected) {
      let found = [];
      try {
        const body = api("/api/videos/category/" + encodeURIComponent(entry.id) + "?sort=hot&page=" + page + "&size=24");
        found = parseCards(body, siteBaseUrl + "/", entry.name);
      } catch (error) {
        failures.push(entry.name + ": " + error.message);
      }
      items = items.concat(found);
      more = more || found.length >= 24;
    }
    return { items, hasMore: more || failures.length > 0, warning: failures.join("\n") };
  },

  search(page, query) {
    query = query.trim();
    if (query === "" || byteLength(query) > 256) {
      throw new Error("黄果搜索关键词无效");
    }
    const result = api("/api/search?" + encodeQuery({ q: query, page: String(page), size: String(searchPageSize) }));
    if (result !== null && !isRecord(result)) {
      throw dataShapeError("搜索结果类型不符");
    }
    const pagination = result === null ? null : result.pagination;
    if (pagination !== undefined && pagination !== null && (!isRecord(pagination) || !integerFields(pagination, ["pages"]))) {
      throw dataShapeError("分页字段类型不符");
    }
    const pages = isRecord(pagination) ? pagination.pages || 0 : 0;
    const items = parseCards(result === null ? null : result.items, siteBaseUrl + "/", "");
    return { items, hasMore: page < pages };
  },

  detail(sourceId) {
    sourceId = sourceId.trim().replace(/^\/+|\/+$/g, "");
    if (sourceId.startsWith("detail/")) {
      sourceId = sourceId.slice("detail/".length);
    }
    if (!sourceIdPattern.test(sourceId)) {
      throw new Error("无效的黄果剧集 ID");
    }
    const detail = api("/api/videos/" + encodeURIComponent(sourceId));
    if (detail !== null && !isRecord(detail)) {
      throw dataShapeError("详情类型不符");
    }
    const drama = detail === null ? null : dramaFromMap(detail, siteBaseUrl + "/", "");
    if (drama === null || drama.sourceId !== sourceId) {
      throw new Error("黄果详情与请求剧集不符");
    }
    const rows = Array.isArray(detail.episodes) ? detail.episodes : [];
    let chapters = [];
    for (const value of rows) {
      const row = isRecord(value) ? value : null;
      const episode = atoi(mapString(row, "ep_num"));
      if (!(episode >= 1)) {
        continue;
      }
      chapters.push(chapterOf(sourceId, episode, mapString(row, "title")));
    }
    if (rows.length === 0) {
      chapters.push(chapterOf(sourceId, 1, drama.title.trim() !== "" ? drama.title : "短剧"));
    }
    chapters = uniqueChapters(sortChapters(chapters));
    return { drama, chapters };
  },

  resolveMedia(task) {
    const dramaId = task.dramaId.trim();
    const separator = dramaId.indexOf(":");
    const sourceId = separator < 0 ? "" : dramaId.slice(separator + 1).trim();
    if (separator < 0 || sourceId === "") {
      throw new Error("黄果剧集信息无效");
    }
    const address = playUrl(sourceId, chapterEpisode(task.chapter, task.index));
    return host.finishMedia({ url: address, referer: firstNonEmpty(task.chapter.referer || "", siteBaseUrl + "/") });
  },

  rankingPage(boardId, page) {
    const board = rankingBoards[boardId];
    if (!board) {
      throw new Error("未知的黄果榜单");
    }
    if (page !== 1) {
      throw new Error("该榜单只有一页");
    }
    const ranking = api("/api/ranks/" + board.path);
    if (ranking !== null && !isRecord(ranking)) {
      throw dataShapeError("榜单类型不符");
    }
    const rows = ranking === null || ranking.items === undefined || ranking.items === null ? [] : ranking.items;
    if (!Array.isArray(rows)) {
      throw dataShapeError("榜单类型不符");
    }
    try {
      return parseRanking(rows, siteBaseUrl + "/");
    } catch (error) {
      throw new Error(board.name + ": " + error.message);
    }
  },
};
