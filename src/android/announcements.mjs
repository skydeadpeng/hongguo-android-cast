/**
 * Remote announcements for the Android build, ported from the desktop announcement service.
 * The native bridge returns raw file text only; schema validation, suppression and the
 * enabled switches live here so a malformed remote file never becomes a dialog.
 */
const MAX_TEXT = 32 * 1024;
const EMERGENCY_INTERVAL_MS = 5 * 60 * 1000;
const STORE_KEY = 'hongguo:announcements:v1';
const validId = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(value);
const validText = (value, limit, multiline = false) => typeof value === 'string'
  && value.length <= limit && value.trim().length > 0
  && !/[<>\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value)
  && (multiline || !/[\r\n\t]/.test(value));

/** One strict remote file: exactly four keys, active, bounded id/title/body, no control text. */
export function parseAnnouncement(raw, urgent) {
  if (typeof raw !== 'string' || !raw.trim() || raw.length > MAX_TEXT) return null;
  let data;
  try { data = JSON.parse(raw); } catch { return null; }
  if (!data || typeof data !== 'object' || Array.isArray(data)
    || Object.keys(data).length !== 4 || data.active !== true
    || !validId(data.id) || !validText(data.title, 120) || !validText(data.body, 4000, true)) return null;
  return { id: data.id, title: data.title.trim(), body: data.body.trim(), urgent: urgent === true };
}

function loadState() {
  try {
    const value = JSON.parse(localStorage.getItem(STORE_KEY) || '{}');
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  } catch { return {}; }
}

/** One persisted record holds the seen urgent ids, the normal suppression and both switches. */
export function createAnnouncements(native, version, now = Date.now) {
  if (typeof native !== 'function') throw new Error('公告需要设备请求函数');
  if (typeof version !== 'string' || !version.trim()) throw new Error('公告需要当前版本号');
  let normalRequest = null;
  let emergencyRequest = null;
  let manualRequest = null;
  let lastEmergencyAttempt = -Infinity;
  let latestEmergency = null;
  let normalGeneration = 0;
  let emergencyGeneration = 0;
  let receivedUrgentIds = new Set();

  const read = () => loadState();
  const write = patch => {
    const next = { ...read(), ...patch };
    localStorage.setItem(STORE_KEY, JSON.stringify(next));
    return next;
  };
  const preferences = () => {
    const stored = read().preferences;
    return { normalEnabled: stored?.normalEnabled !== false, emergencyEnabled: stored?.emergencyEnabled !== false };
  };
  const seenIds = () => {
    const stored = read().seenUrgentIds;
    return new Set(Array.isArray(stored) ? stored.filter(validId) : []);
  };
  const localDay = () => {
    const date = new Date(now());
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  };
  const normalSuppressed = () => {
    const suppression = read().normalSuppression;
    return suppression?.version === version && (suppression.mode === 'version'
      || (suppression.mode === 'day' && suppression.day === localDay()));
  };
  const fetchChannel = async channel => {
    try {
      const reply = await native('getAnnouncements', {}, 45000);
      return parseAnnouncement(reply?.[channel], channel === 'emergency');
    } catch { return null; }
  };
  const checkEmergency = () => {
    if (emergencyRequest) return emergencyRequest;
    if (now() - lastEmergencyAttempt < EMERGENCY_INTERVAL_MS) return Promise.resolve(latestEmergency);
    lastEmergencyAttempt = now();
    const epoch = emergencyGeneration;
    emergencyRequest = fetchChannel('emergency').then(announcement => {
      latestEmergency = announcement && epoch === emergencyGeneration && preferences().emergencyEnabled ? announcement : null;
      return latestEmergency;
    }).finally(() => { emergencyRequest = null; });
    return emergencyRequest;
  };

  return {
    /** Startup and periodic checks; an emergency wins and is auto-shown once per id. */
    async check({ urgentOnly = false } = {}) {
      try {
        if (typeof urgentOnly !== 'boolean') return { announcement: null };
        const current = preferences();
        const normalEpoch = normalGeneration, emergencyEpoch = emergencyGeneration;
        let normal = null;
        if (!urgentOnly && current.normalEnabled && !normalSuppressed()) {
          if (!normalRequest) normalRequest = fetchChannel('normal').then(announcement => announcement && normalEpoch === normalGeneration ? announcement : null);
          normal = normalRequest;
        }
        const emergency = current.emergencyEnabled ? await checkEmergency() : null;
        if (emergency && emergencyEpoch === emergencyGeneration && preferences().emergencyEnabled && !seenIds().has(emergency.id)) {
          receivedUrgentIds.add(emergency.id);
          return { announcement: emergency };
        }
        return { announcement: normalEpoch === normalGeneration && preferences().normalEnabled && !normalSuppressed() ? await normal : null };
      } catch { return { announcement: null }; }
    },
    /** The manual About-page view fetches both channels regardless of suppression. */
    manual() {
      if (manualRequest) return manualRequest;
      manualRequest = Promise.all([fetchChannel('emergency'), fetchChannel('normal')]).then(([emergency, normal]) => {
        const announcement = emergency || normal;
        if (!announcement) return { announcement: null, error: '当前暂无可查看的公告，或暂时无法读取，请稍后重试。' };
        if (announcement.urgent) receivedUrgentIds.add(announcement.id);
        return { announcement };
      }).finally(() => { manualRequest = null; });
      return manualRequest;
    },
    /** An urgent dialog may only be marked seen after this run actually displayed it. */
    acknowledge(id) {
      if (!validId(id) || !receivedUrgentIds.has(id)) return { success: false, error: '公告无效或尚未展示。' };
      const seen = seenIds();
      seen.add(id);
      try { write({ seenUrgentIds: [...seen] }); return { success: true }; }
      catch { return { success: false, error: '公告已在本次运行中记为已读，但未能保存；重启后可能再次显示。' }; }
    },
    dismiss(mode) {
      if (!['day', 'version'].includes(mode)) return { success: false, error: '公告免提醒方式无效。' };
      try { write({ normalSuppression: { mode, version, day: localDay() } }); return { success: true }; }
      catch { return { success: false, error: '普通公告免提醒设置保存失败，请检查存储空间后重试。' }; }
    },
    getPreferences() { return preferences(); },
    setPreferences(patch) {
      if (!patch || typeof patch !== 'object' || Array.isArray(patch)
        || Object.entries(patch).some(([key, value]) => !['normalEnabled', 'emergencyEnabled'].includes(key) || typeof value !== 'boolean')) {
        return { success: false, error: '公告开关设置无效。' };
      }
      try {
        const previous = preferences();
        const next = { ...previous, ...patch };
        write({ preferences: next });
        // Invalidate checks started before the switch, even if it is quickly turned back on.
        if (next.normalEnabled !== previous.normalEnabled) normalGeneration++;
        if (next.emergencyEnabled !== previous.emergencyEnabled) { emergencyGeneration++; latestEmergency = null; }
        return { success: true, preferences: next };
      } catch { return { success: false, error: '公告开关保存失败，请检查存储空间后重试。' }; }
    },
  };
}

/** First https URL in a body, for the open-in-browser action; only https is allowed. */
export function announcementLink(body) {
  const match = String(body || '').match(/https:\/\/[^\s<>"'）】」]+/);
  if (!match) return '';
  try { const url = new URL(match[0]); return url.protocol === 'https:' ? url.href : ''; } catch { return ''; }
}
