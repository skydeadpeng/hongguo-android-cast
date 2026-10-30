/**
 * Live reachability self-check for the website QR login endpoints (aid 513755).
 *
 * GET-only, no SMS, no account writes, no credentials persisted: it requests one
 * QR code, checks the poll answer once, and prints the observed statuses. Run:
 *   node scripts/check-android-web-login-live.mjs
 */
const BASE = 'https://hongguoduanju.com';
const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36';
const QUERY = {
  aid: '513755', passport_jssdk_version: '3.4.10', passport_jssdk_type: 'normal',
  account_sdk_source: 'web', is_from_ttaccountsdk: '1', language: 'zh', next: BASE + '/',
};

function endpoint(pathname, extra = {}) {
  const url = new URL(pathname, BASE);
  for (const [key, value] of Object.entries({ ...QUERY, ...extra })) url.searchParams.set(key, value);
  return url.href;
}

async function main() {
  let failures = 0;
  const started = Date.now();
  let token = '';
  try {
    const response = await fetch(endpoint('/passport/web/get_qrcode/'), {
      headers: { 'User-Agent': UA, Referer: BASE + '/', Origin: BASE, Accept: 'application/json, text/javascript', 'Accept-Encoding': 'identity' },
      signal: AbortSignal.timeout(15000),
    });
    const payload = await response.json();
    const data = payload?.data || {};
    token = String(data.token || '');
    const image = String(data.qrcode || '');
    const checks = [
      ['get_qrcode HTTP 200', response.status === 200],
      ['message=success', String(payload?.message || '').toLowerCase() === 'success'],
      ['error_code=0', Number(data.error_code || 0) === 0],
      ['token is a bounded opaque session id', /^[A-Za-z0-9_-]{16,64}$/.test(token)],
      ['qrcode is a PNG data URL', image.startsWith('data:image/png;base64,') && image.length > 1024],
      ['expire_time present', Number(data.expire_time || 0) > 0],
    ];
    for (const [name, ok] of checks) { console.log((ok ? 'PASS ' : 'FAIL ') + name); if (!ok) failures++; }
  } catch (error) {
    failures++;
    console.log('FAIL get_qrcode reachable: ' + (error?.message || error));
  }
  if (token) {
    try {
      const response = await fetch(endpoint('/passport/web/check_qrconnect/', { token }), {
        headers: { 'User-Agent': UA, Referer: BASE + '/', Origin: BASE, Accept: 'application/json, text/javascript', 'Accept-Encoding': 'identity' },
        signal: AbortSignal.timeout(15000),
      });
      const payload = await response.json();
      const status = String(payload?.data?.status || '');
      const ok = response.status === 200 && ['1', 'new'].includes(status);
      console.log((ok ? 'PASS ' : 'FAIL ') + `check_qrconnect answers a fresh token (status=${status || 'missing'})`);
      if (!ok) failures++;
    } catch (error) {
      failures++;
      console.log('FAIL check_qrconnect reachable: ' + (error?.message || error));
    }
  }
  console.log(`WEB_LOGIN_LIVE_${failures === 0 ? 'OK' : 'FAILED'} failures=${failures} elapsedMs=${Date.now() - started} scanned=not_run credentials=none`);
  if (failures > 0) process.exitCode = 1;
}

main();
