const RELEASES_URL = 'https://github.com/skydeadpeng/hongguo-android-cast/releases';

function versionParts(value) {
  const match = /^v?(\d+)\.(\d+)(?:\.(\d+))?$/.exec(String(value || ''));
  if (!match) return null;
  const parts = [Number(match[1]), Number(match[2]), Number(match[3] || 0)];
  return parts.every(Number.isSafeInteger) ? parts : null;
}

function availableRelease(data, currentVersion) {
  if (!data || data.draft || data.prerelease) return null;
  const next = versionParts(data.tag_name), current = versionParts(currentVersion);
  if (!next || !current) return null;
  const difference = next.findIndex((part, i) => part !== current[i]);
  if (difference < 0 || next[difference] < current[difference]) return null;
  return {
    version: data.tag_name.replace(/^v/, '').replace(/^(\d+\.\d+)\.0$/, '$1'),
    // Never open a remote-provided URL; only this project's release page.
    url: `${RELEASES_URL}/tag/${encodeURIComponent(data.tag_name)}`,
  };
}

module.exports = { availableRelease };
