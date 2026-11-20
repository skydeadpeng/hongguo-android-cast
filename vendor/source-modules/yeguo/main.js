const baseUrl = "https://attract.vgbyziuz.cc";
const transitUrl = "https://ygdj15.com";
const backupSites = ["https://as.vgbyziuz.cc", "https://anyway.vgbyziuz.cc", "https://analyze.buxefaex.cc"];
const aliases = ["yeguo", "ygdj7.com", "www.ygdj7.com", "ygdj15.com", "www.ygdj15.com", "analyze.buxefaex.cc", "attract.vgbyziuz.cc", "delta.ygrwdsgt.cc", "yeguodj.com", "www.yeguodj.com"];
const activeSuffixes = [".buxefaex.cc", ".fzchosdi.cc", ".lwblvxvy.cc", ".vgbyziuz.cc"];
const userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1";
const filterFields = ["theme", "setting", "background", "time", "recommend"];
const accessTtlMs = 60 * 60 * 1000;
const channel = "野果";
const decodeFailure = "野果接口响应校验或解码失败";

const numericIdPattern = /^[1-9][0-9]{0,17}$/;
const moduleImportPattern = /import\s*\{([^{};]+)\}\s*from\s*["'`]([^"'`]+)["'`]/g;
const scriptBaseCallPattern = /baseURL:([a-zA-Z_$][a-zA-Z0-9_$]*)\([a-zA-Z_$]?[a-zA-Z0-9_$]*\)/g;
const publicFieldPattern = /\b(version|mode|padding|key|iv|sign_key)\s*:\s*(?:[a-zA-Z_$][a-zA-Z0-9_$]*\(\s*)?["'`]([^"'`\\\r\n]{1,512})["'`]/g;
const transitBase64Pattern = /Base64\.decode\(["']([A-Za-z0-9+/=]{128,})["']\)/g;
const transitSuffixPattern = /\+\s*["']\.([a-z0-9-]+(?:\.[a-z0-9-]+)+)["']/gi;
const transitWordsPattern = /\bwords\s*=\s*["']([^"']+)["']\s*\.split\(["'],["']\)/;
const transitLiteralPattern = /https?:\/\/[a-z0-9-]+(?:\.[a-z0-9-]+)+\/?/g;
const transitHostLabel = /^[a-z0-9][a-z0-9-]{0,62}$/;

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

function firstPresent(values, ...keys) {
  for (const key of keys) {
    const value = values[key];
    if (value !== undefined && value !== null && nativeText(value) !== "") {
      return value;
    }
  }
  return null;
}

function flagOf(value) {
  if (typeof value === "boolean") {
    return { value, known: true };
  }
  const text = nativeText(value);
  if (text === "0" || text === "false") {
    return { value: false, known: true };
  }
  if (text === "1" || text === "true") {
    return { value: true, known: true };
  }
  return { value: false, known: false };
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

function encodeQuery(params) {
  return Object.keys(params)
    .sort()
    .map((key) => queryEscape(key) + "=" + queryEscape(String(params[key])))
    .join("&");
}

function quoteMeta(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function allMatches(pattern, text, limit) {
  const found = [];
  pattern.lastIndex = 0;
  let match;
  while (found.length < limit && (match = pattern.exec(text)) !== null) {
    found.push(match);
    if (match[0] === "") {
      pattern.lastIndex++;
    }
  }
  return found;
}

function joinedError(message, cause) {
  return new Error(cause ? message + "\n" + cause.message : message);
}

function htmlNodes(root, match) {
  const found = [];
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

function validCategory(category) {
  const separator = category.indexOf(":");
  if (separator < 0 || !numericIdPattern.test(category.slice(separator + 1))) {
    return false;
  }
  return filterFields.includes(category.slice(0, separator));
}

function knownHost(hostname) {
  hostname = hostname.toLowerCase();
  return aliases.includes(hostname) || activeSuffixes.some((suffix) => hostname.endsWith(suffix));
}

function validSite(raw) {
  const address = parseAddress(String(raw).trim());
  if (!address || !httpAddress(raw) || address.userInfo || address.query !== "" || address.fragment !== "" || address.host === "") {
    return "";
  }
  if (!knownHost(address.hostname) || address.path.replace(/\/+$/, "") !== "") {
    return "";
  }
  return address.scheme + "://" + address.host;
}

function activeSite(raw) {
  const address = parseAddress(raw);
  if (!address) {
    return false;
  }
  const hostname = address.hostname.toLowerCase();
  return hostname === "analyze.buxefaex.cc" || activeSuffixes.some((suffix) => hostname.endsWith(suffix));
}

function transitDecodedText(body) {
  const parts = [body];
  for (const match of allMatches(transitBase64Pattern, body, 4)) {
    try {
      parts.push(host.utf8Decode(host.base64Decode(match[1])));
    } catch (error) {
      continue;
    }
  }
  return parts.join("\n");
}

function transitSeedWords(text) {
  const words = [];
  const add = (word) => {
    word = word.trim().toLowerCase();
    if (transitHostLabel.test(word) && !words.includes(word) && words.length < 4) {
      words.push(word);
    }
  };
  const all = [];
  const match = text.match(transitWordsPattern);
  if (match) {
    if (match[1].length > 20000) {
      return ["analyze"];
    }
    for (const word of match[1].split(",")) {
      const cleaned = word.trim().toLowerCase();
      if (transitHostLabel.test(cleaned)) {
        all.push(cleaned);
      }
    }
  }
  for (const preferred of ["analyze", "ability", "abandon"]) {
    if (all.length === 0 || all.includes(preferred)) {
      add(preferred);
    }
  }
  for (const word of all) {
    add(word);
  }
  return words.length === 0 ? ["analyze"] : words;
}

function transitSites(body) {
  const text = transitDecodedText(body);
  const sites = [];
  const add = (site) => {
    site = validSite(site);
    if (site !== "" && site !== transitUrl && !sites.includes(site)) {
      sites.push(site);
    }
  };
  for (const match of allMatches(transitLiteralPattern, text, 32)) {
    const parsed = parseAddress(match[0]);
    if (parsed && activeSuffixes.some((suffix) => parsed.hostname.toLowerCase().endsWith(suffix))) {
      add(match[0]);
    }
  }
  const words = transitSeedWords(text);
  for (const match of allMatches(transitSuffixPattern, text, 8)) {
    const suffix = match[1].toLowerCase().replace(/^\.+|\.+$/g, "");
    if (suffix === "") {
      continue;
    }
    for (const word of words) {
      add("https://" + word + "." + suffix);
    }
  }
  return sites;
}

function discoverTransitSites() {
  try {
    return transitSites(host.fetchText({ url: transitUrl + "/", referer: transitUrl + "/", noCache: true }).body);
  } catch (error) {
    return [];
  }
}

function validApiBase(base) {
  const address = parseAddress(base);
  if (!address || !httpAddress(base) || address.userInfo || address.query !== "" || address.fragment !== "") {
    return { base: "", error: new Error("野果页面未提供有效的接口入口") };
  }
  return { base: base.replace(/\/+$/, ""), error: null };
}

function scriptText(node) {
  const first = (node.children || [])[0];
  return first ? first.text || "" : null;
}

function documentApiBase(document) {
  let base = "";
  for (const node of htmlNodes(document, (node) => node.tag === "script" && htmlAttr(node, "id") === "__NUXT_DATA__")) {
    const text = scriptText(node);
    if (text === null) {
      continue;
    }
    let table;
    try {
      table = JSON.parse(text);
    } catch (error) {
      continue;
    }
    if (!Array.isArray(table) || table.length > 100000) {
      continue;
    }
    for (const entry of table) {
      if (!isRecord(entry) || !Object.prototype.hasOwnProperty.call(entry, "apiBaseURL")) {
        continue;
      }
      const reference = entry.apiBaseURL;
      if (Number.isInteger(reference) && reference >= 0 && reference < table.length && typeof table[reference] === "string") {
        base = table[reference];
      }
      if (base !== "") {
        break;
      }
    }
  }
  return validApiBase(base);
}

function scriptApiBase(script, siteOrigin) {
  for (const call of allMatches(scriptBaseCallPattern, script, 16)) {
    const definition = "(?:^|[^a-zA-Z0-9_$.])" + quoteMeta(call[1]) + "=";
    const literal = script.match(new RegExp(definition + "\\(\\)=>[\"'`](https?://[^\"'`\\s]{1,512})[\"'`]"));
    if (literal) {
      const checked = validApiBase(literal[1]);
      if (checked.error === null) {
        return checked.base;
      }
    }
    if (siteOrigin === "") {
      continue;
    }
    const sameOrigin = script.match(new RegExp(definition + "\\([^()]{0,40}\\)=>\\{[\\s\\S]{0,400}?origin:([a-zA-Z_$][a-zA-Z0-9_$]*)[\\s\\S]{0,400}?`\\$\\{([a-zA-Z_$][a-zA-Z0-9_$]*)\\}/api\\.php`"));
    if (sameOrigin && sameOrigin[1] === sameOrigin[2]) {
      const checked = validApiBase(siteOrigin + "/api.php");
      if (checked.error === null) {
        return checked.base;
      }
    }
  }
  return "";
}

function scriptUrl(base, reference) {
  const page = parseAddress(base);
  if (!page) {
    return "";
  }
  let resolved;
  try {
    resolved = host.resolveUrl(base, reference);
  } catch (error) {
    return "";
  }
  const address = parseAddress(resolved);
  if (!address || address.userInfo || origin(address) !== origin(page) || !address.path.startsWith("/_nuxt/") || !address.path.endsWith(".js")) {
    return "";
  }
  const cut = resolved.indexOf("#");
  return cut < 0 ? resolved : resolved.slice(0, cut);
}

function publicBytes(value) {
  if (!value.includes("_")) {
    return host.utf8Encode(value);
  }
  const decoded = [];
  for (const part of value.split("_")) {
    if (!/^\d+$/.test(part) || parseInt(part, 10) > 255) {
      return null;
    }
    decoded.push(parseInt(part, 10));
  }
  return new Uint8Array(decoded);
}

function publicConfiguration(script) {
  const fields = {};
  for (const match of allMatches(publicFieldPattern, script, 32)) {
    fields[match[1]] = match[2];
  }
  if (fields.version !== "v0" || fields.mode !== "CBC" || fields.padding !== "Pkcs7") {
    return null;
  }
  const key = publicBytes(fields.key || "");
  const iv = publicBytes(fields.iv || "");
  const signKey = publicBytes(fields.sign_key || "");
  if (key === null || iv === null || signKey === null || ![16, 24, 32].includes(key.length) || iv.length !== 16 || signKey.length === 0 || signKey.length > 128) {
    return null;
  }
  return { key: host.hexEncode(key), iv: host.hexEncode(iv), signKey: host.hexEncode(signKey) };
}

function fetchDocument(site) {
  const response = host.fetchText({ url: site + "/", referer: site + "/" });
  if (response.body.length > 4 << 20) {
    throw new Error("站源页面过大");
  }
  return { document: host.parseHtml(response.body), pageUrl: response.url };
}

function discoverConfigurationAt(site) {
  const { document, pageUrl } = fetchDocument(site);
  const documentBase = documentApiBase(document);
  let apiBase = documentBase.base;
  const page = parseAddress(pageUrl);
  const siteOrigin = page ? origin(page) : "";
  let entry = "";
  for (const script of htmlNodes(document, (node) => node.tag === "script" && htmlAttr(node, "type") === "module")) {
    entry = scriptUrl(pageUrl, htmlAttr(script, "src"));
    if (entry !== "") {
      break;
    }
  }
  if (entry === "") {
    throw new Error("野果页面未提供接口配置脚本");
  }
  const entryBody = host.fetchText({ url: entry, referer: pageUrl }).body;
  if (apiBase === "") {
    apiBase = scriptApiBase(entryBody, siteOrigin);
  }
  const imports = allMatches(moduleImportPattern, entryBody, 64)
    .map((match, index) => ({ match, index }))
    .sort((left, right) => (left.match[1].includes(",") ? 1 : 0) - (right.match[1].includes(",") ? 1 : 0) || left.index - right.index)
    .map((entry) => entry.match);
  const seen = new Set();
  let lastError = null;
  let access = null;
  for (const imported of imports) {
    if (access !== null && apiBase !== "") {
      break;
    }
    if (imported[1].length > 2400) {
      continue;
    }
    const address = scriptUrl(entry, imported[2]);
    if (address === "" || seen.has(address)) {
      continue;
    }
    if (seen.size >= 20) {
      break;
    }
    seen.add(address);
    let body;
    try {
      body = host.fetchText({ url: address, referer: pageUrl }).body;
    } catch (error) {
      lastError = error;
      if (error.backoff) {
        throw error;
      }
      continue;
    }
    if (apiBase === "") {
      apiBase = scriptApiBase(body, siteOrigin);
    }
    if (access === null) {
      access = publicConfiguration(body);
    }
  }
  if (access === null) {
    throw joinedError("野果接口配置已变化，暂时无法解码，请稍后重试", lastError);
  }
  if (apiBase === "") {
    throw documentBase.error;
  }
  access.base = apiBase;
  access.site = site;
  access.identifier = host.hexEncode(host.randomBytes(16));
  return access;
}

function discoverConfiguration() {
  const sites = [];
  const add = (site) => {
    site = validSite(site);
    if (site !== "" && activeSite(site) && !sites.includes(site)) {
      sites.push(site);
    }
  };
  add(baseUrl);
  for (const site of backupSites) {
    add(site);
  }
  let lastError = null;
  for (let index = 0; index < sites.length; index++) {
    try {
      return discoverConfigurationAt(sites[index]);
    } catch (error) {
      lastError = error;
    }
    if (index === 0) {
      for (const site of discoverTransitSites()) {
        add(site);
      }
    }
  }
  throw joinedError("野果线路暂不可用，请稍后重试", lastError);
}

function configuration() {
  const cached = host.cacheGet("access");
  if (cached !== null) {
    return JSON.parse(cached);
  }
  return host.exclusive(() => {
    const current = host.cacheGet("access");
    if (current !== null) {
      return JSON.parse(current);
    }
    const access = discoverConfiguration();
    host.cacheSet("access", JSON.stringify(access), accessTtlMs);
    return access;
  });
}

function siteUrl() {
  const cached = host.cacheGet("access");
  const access = cached === null ? null : JSON.parse(cached);
  return access !== null && access.site !== "" ? access.site : baseUrl;
}

function decodeObject(text) {
  let value;
  try {
    value = JSON.parse(host.preserveIntegers(text));
  } catch (error) {
    return null;
  }
  return isRecord(value) ? value : null;
}

function responseSignature(envelope, signKey) {
  const pieces = [];
  for (const name of Object.keys(envelope).sort()) {
    const value = envelope[name];
    if (name === "sign" || name === "_ver" || value === null) {
      continue;
    }
    let field;
    if (typeof value === "string") {
      field = value;
    } else if (typeof value === "number" || typeof value === "boolean") {
      field = String(value);
    } else {
      return null;
    }
    if (name === "data") {
      field = field.replace(/ /g, "+");
    }
    pieces.push(name + "=" + field);
  }
  const text = host.utf8Encode(pieces.join("&"));
  const salted = new Uint8Array(text.length + signKey.length);
  salted.set(text, 0);
  salted.set(signKey, text.length);
  return host.hexEncode(host.md5(host.hexEncode(host.sha256(salted))));
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

function decodeResponse(body, access) {
  const envelope = decodeObject(body);
  if (envelope === null) {
    return null;
  }
  const signature = mapString(envelope, "sign");
  if (signature !== "" && responseSignature(envelope, host.hexDecode(access.signKey)) !== signature.toLowerCase()) {
    return null;
  }
  if (typeof envelope.data !== "string") {
    return envelope;
  }
  let sealed;
  try {
    sealed = host.base64Decode(envelope.data.trim().replace(/ /g, "+"));
  } catch (error) {
    return null;
  }
  if (sealed.length === 0 || sealed.length % 16 !== 0) {
    return null;
  }
  const plain = unpad(host.aesCbcDecrypt(host.hexDecode(access.key), host.hexDecode(access.iv), sealed));
  return plain === null ? null : decodeObject(host.utf8Decode(plain));
}

function call(route, parameters) {
  for (let attempt = 0; attempt < 2; attempt++) {
    const access = configuration();
    const values = { bundleId: "com.pwa.mater", version: "1.3.2", oauth_type: "web", language: "zh", via: "pwa", oauth_id: access.identifier, trace_id: access.identifier, token: "" };
    Object.assign(values, parameters);
    const method = route === "/api/home/contentOptions" ? "GET" : "POST";
    let address = access.base + route;
    const site = firstNonEmpty(access.site, baseUrl);
    const headers = { "User-Agent": userAgent, Accept: "application/json, text/plain, */*", Origin: site, Referer: site + "/" };
    const request = { method, url: address, headers, limit: 8 << 20, timeoutMs: 30000, direct: true, followRedirects: true };
    if (method === "GET") {
      request.url = address + "?" + encodeQuery(values);
    } else {
      headers["Content-Type"] = "application/x-www-form-urlencoded";
      request.body = encodeQuery(values);
    }
    const response = host.fetch(request);
    const payload = decodeResponse(response.body, access);
    if (payload === null) {
      if (attempt === 0) {
        if (host.cacheGet("access") === JSON.stringify(access)) {
          host.cacheSet("access", null, 0);
        }
        continue;
      }
      throw new Error(decodeFailure);
    }
    const status = mapString(payload, "status");
    if (status !== "1") {
      if (status === "-1") {
        throw new Error("野果当前内容需要站源授权");
      }
      throw new Error("野果请求未完成：" + (truncate(cleanText(mapString(payload, "msg")), 180) || "请稍后重试"));
    }
    if (!isRecord(payload.data)) {
      throw new Error("野果返回的数据格式无效");
    }
    return payload.data;
  }
  throw new Error(decodeFailure);
}

function dramaFromRow(row, site) {
  const id = mapString(row, "video_id", "id");
  const title = mapString(row, "title");
  if (!numericIdPattern.test(id) || title === "") {
    throw new Error("野果剧集资料不完整");
  }
  let episodes = 0;
  for (const key of ["episode_count", "episodes", "total_serial"]) {
    const count = webInteger(row[key], 100000);
    if (count.valid) {
      episodes = Math.max(episodes, count.value);
    }
  }
  const serialize = mapString(row, "serialize_status");
  const tags = [];
  if (Array.isArray(row.tags)) {
    for (const value of row.tags) {
      if (typeof value === "string" && tags.length < 32) {
        const tag = value.trim();
        if (tag !== "" && Array.from(tag).length <= 48 && !tags.includes(tag)) {
          tags.push(tag);
        }
      }
    }
  }
  const cover = coverAddress(row.cover, site + "/");
  const description = truncate(mapString(row, "description"), 12000);
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
    categoryName: "短剧",
    channelName: channel,
    views: normalizeViews(mapString(row, "play_count", "play_count_text")),
    onlineDate: releaseDate(mapString(row, "published_at", "created_at")),
  };
  if (tags.length > 0) {
    drama.tags = tags;
  }
  if (serialize === "1") {
    drama.releaseStatus = "ongoing";
  } else if (serialize === "2") {
    drama.releaseStatus = "finished";
  }
  const vip = flagOf(row.is_vip);
  if (vip.known) {
    drama.vip = vip.value;
  }
  return drama;
}

function fetchCatalogPage(page, category, query) {
  if (page < 1 || page > 1000000 || byteLength(query) > 1024 || (category !== "" && !validCategory(category))) {
    throw new Error("野果目录查询参数无效");
  }
  let route = "/api/theater/exploreList";
  const values = { page: String(page), limit: "20" };
  if (query !== "") {
    route = "/api/search/result";
    values.keyword = query;
    values.tab = "video";
  } else if (category !== "") {
    const separator = category.indexOf(":");
    values[category.slice(0, separator)] = category.slice(separator + 1);
  }
  const data = call(route, values);
  const rows = data.list;
  if (!Array.isArray(rows) || rows.length > 500) {
    throw new Error("野果目录格式无效");
  }
  const actualPage = webInteger(firstPresent(data, "page", "page_num", "pageNum", "current_page", "currentPage"), 1000000);
  if (actualPage.valid && actualPage.value !== page) {
    throw new Error("野果分页信息无效，请重试");
  }
  const limitValue = webInteger(firstPresent(data, "limit", "page_size", "pageSize", "per_page", "perPage"), 500);
  let limit = limitValue.value;
  if (!limitValue.valid || limit < 1 || limit < rows.length) {
    limit = rows.length;
  }
  const total = webInteger(firstPresent(data, "total", "total_count", "totalCount"), 100000000);
  const totalValid = total.valid && total.value >= rows.length;
  let hasMore = limit > 0 && rows.length >= limit;
  if (totalValid && limit > 0) {
    hasMore = page < Math.ceil(total.value / limit);
  }
  if (data.has_more !== undefined && data.has_more !== null) {
    const more = flagOf(data.has_more);
    if (!more.known) {
      throw new Error("野果分页信息无效，请重试");
    }
    hasMore = more.value;
  }
  if (hasMore && rows.length === 0) {
    throw new Error("野果未返回应有的目录页，请重试");
  }
  const site = siteUrl();
  const items = [];
  const seen = new Set();
  for (const row of rows) {
    const drama = dramaFromRow(isRecord(row) ? row : {}, site);
    if (seen.has(drama.id)) {
      throw new Error("野果目录包含重复剧集");
    }
    seen.add(drama.id);
    items.push(drama);
  }
  return { items, hasMore };
}

function episodePage(site, id, episode) {
  let address = site + "/drama/video/" + id + "/";
  if (episode > 1) {
    address += "ep-" + episode + "/";
  }
  return address;
}

function mediaQuality(resolution) {
  const parts = resolution.toLowerCase().split("x");
  if (parts.length !== 2) {
    return 0;
  }
  const width = webInteger(parts[0], 16384);
  const height = webInteger(parts[1], 16384);
  if (!width.valid || !height.valid || width.value < 1 || height.value < 1) {
    return 0;
  }
  return Math.min(width.value, height.value);
}

module.exports = {
  validCategory,

  categories() {
    const data = call("/api/home/contentOptions", {});
    const filters = data.video_filter;
    if (!isRecord(filters)) {
      throw new Error("野果分类数据格式无效");
    }
    const categories = [];
    const seen = new Set();
    for (const field of filterFields) {
      const filter = filters[field];
      const options = isRecord(filter) && Array.isArray(filter.list) ? filter.list : [];
      if (options.length > 300) {
        throw new Error("野果分类条目过多");
      }
      for (const option of options) {
        const row = isRecord(option) ? option : {};
        const value = mapString(row, "value");
        const name = mapString(row, "name");
        if (value === "0") {
          continue;
        }
        const id = field + ":" + value;
        if (!validCategory(id) || name === "" || Array.from(name).length > 48 || seen.has(id)) {
          throw new Error("野果分类数据包含无效或重复条目");
        }
        seen.add(id);
        categories.push({ id, name });
      }
    }
    if (categories.length === 0) {
      throw new Error("野果暂未返回分类");
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
      throw new Error("野果剧集 ID 无效");
    }
    const row = call("/api/playlet/detail", { video_id: sourceId, id: sourceId, episode_id: "0", related_limit: "0" });
    const site = siteUrl();
    const drama = dramaFromRow(row, site);
    if (drama.sourceId !== sourceId) {
      throw new Error("野果详情与请求剧集不符");
    }
    const episodes = row.episodes;
    if (!Array.isArray(episodes) || episodes.length > 10000) {
      throw new Error("野果分集目录格式无效");
    }
    const chapters = [];
    const seenIds = new Set();
    const seenNumbers = new Set();
    for (const value of episodes) {
      const episode = isRecord(value) ? value : {};
      if (flagOf(episode.is_adv).value) {
        continue;
      }
      const id = mapString(episode, "id");
      const number = webInteger(episode.sort, 100000);
      if (!numericIdPattern.test(id) || !number.valid || number.value < 1 || seenIds.has(id) || seenNumbers.has(number.value)) {
        throw new Error("野果分集编号无效或重复，请刷新详情");
      }
      seenIds.add(id);
      seenNumbers.add(number.value);
      chapters.push({
        id: host.chapterId(sourceId, id),
        source: host.source,
        title: truncate(firstNonEmpty(mapString(episode, "title"), "第 " + number.value + " 集"), 256),
        currentEpisode: number.value,
        videoUrl: "yeguo-play://" + id,
        pageUrl: episodePage(site, sourceId, number.value),
        referer: site + "/",
      });
    }
    chapters.sort((left, right) => left.currentEpisode - right.currentEpisode);
    return { drama, chapters };
  },

  resolveMedia(task) {
    const failure = "野果播放分集信息无效，请刷新详情";
    const dramaId = task.dramaId.trim();
    const separator = dramaId.indexOf(":");
    const sourceId = separator < 0 ? "" : dramaId.slice(separator + 1).trim();
    const prefix = host.chapterId(sourceId, "");
    if (separator < 0 || dramaId.slice(0, separator).trim().toLowerCase() !== host.source || !numericIdPattern.test(sourceId) || !task.chapter.id.startsWith(prefix)) {
      throw new Error(failure);
    }
    const episodeId = task.chapter.id.slice(prefix.length);
    if (!numericIdPattern.test(episodeId)) {
      throw new Error("野果播放分集 ID 无效");
    }
    const row = call("/api/playlet/play", { playlet_id: sourceId, video_id: sourceId, episode_id: episodeId });
    if (mapString(row, "playlet_id") !== sourceId || mapString(row, "id") !== episodeId) {
      throw new Error("野果返回的播放地址与请求分集不符");
    }
    if (flagOf(row.is_adv).value) {
      throw new Error("野果未返回该集正片，请重试");
    }
    const number = webInteger(row.episode_sort, 100000);
    if (!number.valid || number.value < 1) {
      throw new Error("野果播放分集编号无效");
    }
    const referer = episodePage(siteUrl(), sourceId, number.value);
    const quality = mediaQuality(mapString(row, "resolution"));
    const durationText = mapString(row, "episode_duration");
    const duration = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/.test(durationText) ? Number(durationText) : NaN;
    const options = [];
    for (const field of ["video_url", "video_url_h265"]) {
      const raw = mapString(row, field);
      if (raw === "") {
        continue;
      }
      const address = resolveUrl(referer, raw);
      if (!httpAddress(address) || options.some((option) => option.url === address)) {
        continue;
      }
      const media = { url: address, referer, quality };
      if (duration > 0 && duration <= 604800) {
        media.durationMs = Math.floor(duration * 1000);
      }
      options.push(media);
    }
    if (options.length === 0) {
      throw new Error("野果未提供该集播放地址，请重试或确认站源权限");
    }
    const media = Object.assign({}, options[0], { variants: options });
    return host.prepareWebMedia(media, channel);
  },
};
