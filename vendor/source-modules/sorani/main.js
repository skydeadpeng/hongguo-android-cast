const siteBaseUrl = "https://www.sorani.net";
const apiBaseUrl = "https://api.sorani.cc/sorani-cms/api";
const userAgent = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
const sourceName = "青空次元";
const defaultLineCode = "anime_jp_m3u8";
const pageSize = 24;
const sessionState = "session";
const captchaScene = "login";
const renewBeforeMs = 5 * 60 * 1000;
const captchaIdPattern = /^[A-Za-z0-9_-]{1,128}$/;
const loginRequiredMessage = "青空次元需要登录后才能播放，请在 设置 > 站源 > 站源账号 中登录青空账号";
const loginExpiredMessage = "青空次元登录已失效，请在 设置 > 站源 > 站源账号 中重新登录";

const categories = [
  { id: "1", name: "TV番剧" },
  { id: "2", name: "剧场动画" },
  { id: "5", name: "特摄剧场" },
];

function loginError(message) {
  const failure = new Error(message);
  failure.loginRequired = true;
  return failure;
}

function apiError(code, message) {
  const failure = new Error("青空次元接口错误：" + message);
  failure.apiCode = code;
  failure.apiMessage = message;
  return failure;
}

function truncate(text, limit) {
  const trimmed = text.trim();
  const characters = Array.from(trimmed);
  return characters.length <= limit ? trimmed : characters.slice(0, limit).join("") + "...";
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function text(value) {
  if (typeof value === "string") {
    return value.trim();
  }
  if (typeof value === "number") {
    return String(Math.trunc(value));
  }
  if (Array.isArray(value)) {
    for (const entry of value) {
      const found = text(entry);
      if (found !== "") {
        return found;
      }
    }
  } else if (isRecord(value)) {
    for (const key of ["url", "src", "cover", "image", "pic"]) {
      const found = text(value[key]);
      if (found !== "") {
        return found;
      }
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
      if (value.trim() !== "") {
        return value.trim();
      }
    } else if (typeof value === "boolean" || typeof value === "number") {
      return String(value);
    }
  }
  return "";
}

function mapStringList(row, ...keys) {
  const found = [];
  const add = (value) => {
    value = value.trim();
    if (value !== "" && !found.includes(value)) {
      found.push(value);
    }
  };
  for (const key of keys) {
    const value = row[key];
    if (typeof value === "string") {
      value.split(/[,/，、]/).forEach(add);
    } else if (Array.isArray(value)) {
      for (const item of value) {
        if (isRecord(item)) {
          add(mapString(item, "name", "title", "label", "tag"));
        } else {
          add(String(item));
        }
      }
    }
  }
  return found;
}

function wholeNumber(value) {
  const digits = text(value);
  return /^[+-]?\d+$/.test(digits) ? parseInt(digits, 10) : NaN;
}

function numericId(value) {
  const trimmed = String(value).trim();
  return trimmed.length <= 64 && /^[+-]?\d+$/.test(trimmed) && parseInt(trimmed, 10) > 0;
}

function validCategory(category) {
  if (category === "") {
    return true;
  }
  return category.length <= 128 && !/[|/\\\x00\r\n]/.test(category) && categories.some((entry) => entry.id === category);
}

function httpUrl(raw) {
  let parsed;
  try {
    parsed = host.parseUrl(raw);
  } catch (failure) {
    return null;
  }
  const http = parsed.scheme === "https" || parsed.scheme === "http";
  return http && parsed.hostname !== "" && !parsed.userInfo ? parsed : null;
}

function coverAddress(value) {
  if (Array.isArray(value)) {
    for (const entry of value) {
      const address = coverAddress(entry);
      if (address !== "") {
        return address;
      }
    }
    return "";
  }
  if (isRecord(value)) {
    for (const key of ["url", "contentUrl", "thumbnailUrl"]) {
      const address = coverAddress(value[key]);
      if (address !== "") {
        return address;
      }
    }
    return "";
  }
  if (typeof value !== "string" || value.trim() === "") {
    return "";
  }
  let address = value.trim();
  if (address.startsWith("//")) {
    address = "https:" + address;
  } else if (address.startsWith("/")) {
    address = siteBaseUrl + address;
  } else if (!/^[a-z][a-z0-9+.-]*:/i.test(address)) {
    address = siteBaseUrl + "/" + address;
  }
  const parsed = httpUrl(address);
  return parsed ? parsed.href : "";
}

function firstValue(row, ...keys) {
  for (const key of keys) {
    if (key in row && text(row[key]) !== "") {
      return row[key];
    }
  }
  return null;
}

function leadingYear(value) {
  const digits = value.trim().slice(0, 4);
  if (!/^\d{4}$/.test(digits)) {
    return "";
  }
  const year = parseInt(digits, 10);
  return year >= 1900 && year <= 2200 ? digits : "";
}

function queryString(values) {
  return Object.keys(values)
    .sort()
    .map((key) => encodeURIComponent(key) + "=" + encodeURIComponent(values[key]).replace(/%20/g, "+"))
    .join("&");
}

function call(method, route, query, body, bearer, limit) {
  let url = apiBaseUrl + route;
  if (query && Object.keys(query).length > 0) {
    url += "?" + queryString(query);
  }
  const headers = {
    "User-Agent": userAgent,
    Accept: "application/json, text/plain, */*",
    "Accept-Language": "zh-CN,zh;q=0.9",
    Referer: siteBaseUrl + "/",
    Origin: siteBaseUrl,
  };
  const request = { method, url, headers, limit: limit || 4 << 20, timeoutMs: 15000 };
  if (body !== null && body !== undefined) {
    request.body = JSON.stringify(body);
    headers["Content-Type"] = "application/json";
  }
  if (bearer) {
    headers.Authorization = "Bearer " + bearer;
  }
  const response = host.fetch(request);
  let decoded;
  try {
    decoded = JSON.parse(response.body);
  } catch (failure) {
    throw new Error("青空次元返回的数据格式无效");
  }
  if (isRecord(decoded)) {
    const code = text(decoded.code);
    if (code !== "" && code !== "200") {
      throw apiError(code, truncate(mapString(decoded, "message", "msg") || "上游返回错误码 " + code, 120));
    }
  }
  return decoded;
}

function payloadOf(value) {
  return isRecord(value) && "data" in value ? value.data : value;
}

function recordList(payload) {
  if (Array.isArray(payload)) {
    return payload;
  }
  if (isRecord(payload)) {
    for (const key of ["records", "list", "items", "rows"]) {
      if (Array.isArray(payload[key])) {
        return payload[key];
      }
    }
  }
  return null;
}

function dramaFromRow(row) {
  if (!isRecord(row)) {
    throw new Error("青空次元返回的剧集信息不完整");
  }
  const id = mapString(row, "id", "videoId");
  const title = mapString(row, "title", "name", "videoTitle");
  if (!numericId(id) || title === "") {
    throw new Error("青空次元返回的剧集信息不完整");
  }
  const description = truncate(mapString(row, "summary", "description", "intro", "desc"), 12000);
  const cover = coverAddress(firstValue(row, "cover", "coverLarge", "coverSmall", "coverThumb", "backgroundImage"));
  let episodes = 0;
  for (const key of ["episodeCount", "totalEpisode", "episodes", "total"]) {
    const count = wholeNumber(row[key]);
    if (count > episodes && count <= 100000) {
      episodes = count;
    }
  }
  const category = mapString(row, "categoryName", "category");
  const tags = [];
  const addTag = (value) => {
    value = value.trim();
    if (value !== "" && !tags.includes(value) && Array.from(value).length <= 48 && tags.length < 16) {
      tags.push(value);
    }
  };
  mapStringList(row, "tags", "tagList", "categoryTags").forEach(addTag);
  addTag(truncate(mapString(row, "alias"), 48));
  addTag(truncate(mapString(row, "area"), 48));
  const year = leadingYear(mapString(row, "year", "publishDate"));
  if (year !== "") {
    addTag(year + "年");
  }
  const actor = mapString(row, "actors", "actor");
  if (actor !== "") {
    addTag("声优 " + truncate(actor, 32));
  }
  return {
    id: host.dramaId(id),
    source: host.source,
    sourceId: id,
    title: truncate(title, 256),
    name: truncate(title, 256),
    desc: description,
    intro: description,
    cover,
    coverUrl: cover,
    totalEpisode: episodes,
    episodeCount: episodes,
    category: category || "番剧",
    channelName: sourceName,
    tags,
    score: mapString(row, "bangumiRating", "score"),
  };
}

function page(pageNumber, category, query) {
  if (pageNumber < 1 || pageNumber > 100000) {
    throw new Error("青空次元目录页码无效");
  }
  if (!validCategory(category)) {
    throw new Error("青空次元内容分类无效");
  }
  if (query.length > 256) {
    throw new Error("青空次元搜索关键词过长");
  }
  const values = { page: String(pageNumber), size: String(pageSize) };
  let route = "/video";
  if (query !== "") {
    route = "/video/search";
    values.keyword = query;
  } else if (category !== "") {
    values.categoryId = category;
  }
  const payload = payloadOf(call("GET", route, values, null, "", 4 << 20));
  const rows = recordList(payload);
  if (!rows || rows.length > 500) {
    throw new Error("青空次元目录数据格式无效");
  }
  let hasMore = rows.length >= pageSize;
  if (isRecord(payload)) {
    const total = wholeNumber(payload.total);
    if (total >= 0 && total <= 100000000) {
      hasMore = pageNumber * pageSize < total;
    }
  }
  if (hasMore && rows.length === 0) {
    throw new Error("青空次元未返回应有的目录页，请重试");
  }
  const items = rows.map(dramaFromRow);
  if (new Set(items.map((drama) => drama.id)).size !== items.length) {
    throw new Error("青空次元目录包含重复剧集，请重试");
  }
  return { items, hasMore };
}

function base64UrlText(encoded) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
  let bits = 0;
  let width = 0;
  let escaped = "";
  for (const character of encoded) {
    const value = alphabet.indexOf(character);
    if (value < 0) {
      throw new Error("invalid base64url");
    }
    bits = (bits << 6) | value;
    width += 6;
    if (width >= 8) {
      width -= 8;
      escaped += "%" + ((bits >> width) & 0xff).toString(16).padStart(2, "0");
    }
  }
  return decodeURIComponent(escaped);
}

function tokenExpiryMs(token) {
  const parts = String(token || "").split(".");
  if (token.length > 4096 || parts.length !== 3 || parts[2] === "") {
    return 0;
  }
  try {
    const claims = JSON.parse(base64UrlText(parts[1]));
    return typeof claims.exp === "number" && claims.exp > 0 ? claims.exp * 1000 : 0;
  } catch (failure) {
    return 0;
  }
}

function loadSession() {
  const empty = { version: 1, accessToken: "", refreshToken: "", name: "" };
  const raw = host.loadState(sessionState);
  if (raw === "" || raw.length > 32 * 1024) {
    return empty;
  }
  let stored;
  try {
    stored = JSON.parse(raw);
  } catch (failure) {
    return empty;
  }
  const usable =
    isRecord(stored) && stored.version === 1 && tokenExpiryMs(stored.accessToken) > 0 && Date.now() < tokenExpiryMs(stored.refreshToken);
  return usable ? stored : empty;
}

function saveSession(session) {
  host.saveState(sessionState, session.refreshToken ? JSON.stringify(session) : "");
}

function sessionFromLogin(value, name) {
  const row = payloadOf(value);
  const accessToken = mapString(row, "token");
  const refreshToken = mapString(row, "refreshToken");
  if (tokenExpiryMs(accessToken) === 0 || tokenExpiryMs(refreshToken) === 0) {
    throw new Error("青空次元未返回有效的登录凭证");
  }
  if (isRecord(row.user)) {
    name = mapString(row.user, "displayName", "username") || name;
  }
  return { version: 1, accessToken, refreshToken, name: truncate(name, 64) };
}

function refresh(session) {
  let value;
  try {
    value = call("POST", "/auth/refresh", null, null, session.refreshToken, 256 << 10);
  } catch (failure) {
    if (failure.apiCode === "401" || failure.apiCode === "403") {
      saveSession({ refreshToken: "" });
      throw loginError(loginExpiredMessage);
    }
    throw failure;
  }
  const renewed = sessionFromLogin(value, session.name);
  saveSession(renewed);
  return renewed.accessToken;
}

function accessToken() {
  return host.exclusive(() => {
    const session = loadSession();
    if (session.refreshToken === "") {
      return "";
    }
    return tokenExpiryMs(session.accessToken) - Date.now() > renewBeforeMs ? session.accessToken : refresh(session);
  });
}

function renew(rejected) {
  return host.exclusive(() => {
    const session = loadSession();
    if (session.refreshToken === "") {
      throw loginError(loginExpiredMessage);
    }
    return session.accessToken !== rejected ? session.accessToken : refresh(session);
  });
}

function accountStatus() {
  const session = host.exclusive(loadSession);
  return { loggedIn: session.refreshToken !== "", name: session.name };
}

function captcha() {
  const row = payloadOf(call("POST", "/auth/behavior-captcha/generate", { scene: captchaScene }, {}, "", 2 << 20));
  const kind = mapString(row, "type");
  if (kind !== "SLIDER") {
    throw new Error("青空次元返回了暂不支持的验证方式（" + truncate(kind, 32) + "），请稍后重试");
  }
  const size = (key) => {
    const number = wholeNumber(row[key]);
    return number > 0 && number <= 4000 ? number : 0;
  };
  const result = {
    id: mapString(row, "id"),
    backgroundImage: mapString(row, "backgroundImage"),
    templateImage: mapString(row, "templateImage"),
    backgroundWidth: size("backgroundImageWidth"),
    backgroundHeight: size("backgroundImageHeight"),
    templateWidth: size("templateImageWidth"),
    templateHeight: size("templateImageHeight"),
  };
  const complete =
    captchaIdPattern.test(result.id) &&
    result.backgroundImage.startsWith("data:image/") &&
    result.templateImage.startsWith("data:image/") &&
    result.backgroundWidth > 0 &&
    result.backgroundHeight > 0 &&
    result.templateWidth > 0 &&
    result.templateHeight > 0;
  if (!complete) {
    throw new Error("青空次元验证码数据无效，请重试");
  }
  return result;
}

function validTrack(track) {
  const dimension = (value) => Number.isInteger(value) && value > 0 && value <= 4000;
  if (!isRecord(track) || !Array.isArray(track.trackList)) {
    return false;
  }
  const sized =
    dimension(track.bgImageWidth) && dimension(track.bgImageHeight) && dimension(track.templateImageWidth) && dimension(track.templateImageHeight);
  if (!sized || !(track.startTime > 0) || !(track.stopTime >= track.startTime) || track.trackList.length < 2 || track.trackList.length > 5000) {
    return false;
  }
  return track.trackList.every((point) => isRecord(point) && ["down", "move", "up"].includes(point.type) && point.t >= 0);
}

function login(input) {
  const identifier = String(input.identifier || "").trim();
  const password = String(input.password || "");
  if (identifier === "" || identifier.length > 128) {
    throw new Error("请输入青空次元用户名或邮箱");
  }
  if (password === "" || password.length > 128) {
    throw new Error("请输入青空次元密码");
  }
  if (!captchaIdPattern.test(String(input.captchaId || "")) || !validTrack(input.track)) {
    throw new Error("安全验证数据无效，请重新拖动滑块");
  }
  let verified;
  try {
    verified = call("POST", "/auth/behavior-captcha/verify", { scene: captchaScene }, { id: input.captchaId, data: input.track }, "", 64 << 10);
  } catch (failure) {
    if (failure.apiCode) {
      throw new Error("安全验证未通过（" + failure.apiMessage + "），请重新拖动滑块");
    }
    throw failure;
  }
  const captchaToken = mapString(payloadOf(verified), "token");
  if (captchaToken === "" || captchaToken.length > 4096) {
    throw new Error("安全验证未通过，请重新拖动滑块");
  }
  const value = call("POST", "/auth/login", null, { identifier, password, captchaToken }, "", 256 << 10);
  const session = sessionFromLogin(value, identifier);
  host.exclusive(() => saveSession(session));
  return { loggedIn: true, name: session.name };
}

function logout() {
  const access = host.exclusive(() => {
    const session = loadSession();
    saveSession({ refreshToken: "" });
    return session.accessToken;
  });
  if (access !== "") {
    try {
      call("POST", "/auth/logout", null, null, access, 64 << 10);
    } catch (failure) {
      host.log("退出登录请求失败：" + failure.message);
    }
  }
  return { loggedIn: false, name: "" };
}

function play(episodeId, token) {
  const route = "/video/episode/" + encodeURIComponent(episodeId) + "/play";
  const row = payloadOf(call("GET", route, { lineCode: defaultLineCode }, null, token, 64 << 10));
  return isRecord(row) ? row : {};
}

function loginRejected(row) {
  return row.canPlay === false && mapString(row, "message").includes("登录");
}

module.exports = {
  validCategory,

  categories() {
    return categories;
  },

  catalogPage(pageNumber, category) {
    return page(pageNumber, category, "");
  },

  search(pageNumber, query) {
    return page(pageNumber, "", query);
  },

  detail(sourceId) {
    if (!numericId(sourceId)) {
      throw new Error("青空次元剧集 ID 无效");
    }
    const row = payloadOf(call("GET", "/video/" + encodeURIComponent(sourceId), null, null, "", 8 << 20));
    if (!isRecord(row)) {
      throw new Error("青空次元详情数据格式无效");
    }
    const drama = dramaFromRow(row);
    if (drama.sourceId !== sourceId) {
      throw new Error("青空次元详情与请求剧集不符");
    }
    if (!Array.isArray(row.episodes) || row.episodes.length > 10000) {
      throw new Error("青空次元分集目录格式无效");
    }
    const chapters = [];
    const seenIds = new Set();
    const seenOrders = new Set();
    row.episodes.forEach((episode, index) => {
      if (!isRecord(episode) || episode.enabled === false) {
        return;
      }
      const episodeId = mapString(episode, "episodeId", "id");
      if (!numericId(episodeId) || seenIds.has(episodeId)) {
        throw new Error("青空次元分集目录包含无效或重复分集");
      }
      const declared = wholeNumber(episode.episodeOrder);
      const order = declared > 0 && declared <= 100000 ? declared : index + 1;
      if (seenOrders.has(order)) {
        throw new Error("青空次元分集编号重复，请刷新目录");
      }
      seenIds.add(episodeId);
      seenOrders.add(order);
      const title = mapString(episode, "episodeLabel", "title", "displayTitle") || "第 " + order + " 集";
      chapters.push({
        id: host.chapterId(sourceId, episodeId),
        source: host.source,
        title: truncate(title, 128),
        currentEpisode: order,
        videoUrl: "sorani-play://" + encodeURIComponent(episodeId),
        referer: siteBaseUrl + "/",
      });
    });
    chapters.sort((left, right) => left.currentEpisode - right.currentEpisode);
    return { drama, chapters };
  },

  resolveMedia(task) {
    const dramaPrefix = host.source + ":";
    const sourceId = task.dramaId.startsWith(dramaPrefix) ? task.dramaId.slice(dramaPrefix.length).trim() : "";
    const chapterPrefix = host.chapterId(sourceId, "");
    if (!numericId(sourceId) || !task.chapter.id.startsWith(chapterPrefix)) {
      throw new Error("青空次元播放分集信息无效，请刷新详情");
    }
    const episodeId = task.chapter.id.slice(chapterPrefix.length);
    if (!numericId(episodeId)) {
      throw new Error("青空次元分集 ID 无效");
    }
    let token = accessToken();
    let row = play(episodeId, token);
    if (token !== "" && loginRejected(row)) {
      token = renew(token);
      row = play(episodeId, token);
    }
    if (row.canPlay === false) {
      if (loginRejected(row)) {
        throw loginError(token === "" ? loginRequiredMessage : loginExpiredMessage);
      }
      const message = mapString(row, "message");
      throw new Error(message !== "" ? "青空次元：" + truncate(message, 120) : "青空次元该分集暂不可播放");
    }
    const address = mapString(row, "playUrl", "url");
    if (address === "") {
      throw new Error("青空次元该分集暂无可用播放地址（线路 " + defaultLineCode + "）");
    }
    const parsed = httpUrl(address);
    if (!parsed) {
      throw new Error("青空次元播放地址无效");
    }
    const media = { url: address, referer: siteBaseUrl + "/" };
    if (parsed.path.toLowerCase().endsWith(".m3u8")) {
      let fetched;
      try {
        fetched = host.fetchPlaylist({ url: media.url, referer: media.referer });
      } catch (failure) {
        throw new Error("获取青空次元播放列表失败：" + failure.message);
      }
      if (!fetched.playlist.replace(/^﻿/, "").trim().startsWith("#EXTM3U")) {
        throw new Error("青空次元未返回有效 M3U8，链接可能已失效");
      }
      media.playlist = fetched.playlist;
      media.url = fetched.url;
      media.durationMs = fetched.durationMs;
    }
    return media;
  },

  account(command, input) {
    switch (command) {
      case "":
        return accountStatus();
      case "captcha":
        return captcha();
      case "login":
        return login(isRecord(input) ? input : {});
      case "logout":
        return logout();
    }
    throw new Error("不支持的应用操作");
  },
};
