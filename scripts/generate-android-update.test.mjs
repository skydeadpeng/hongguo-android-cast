import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createUpdateManifest } from './generate-android-update.mjs';
const signer='3a883cc41a4ce34b462a8c12c0d289e6c985d3e2c0d6ecf2b516b5cc75e353ab';
const input=device=>({device,badging:`package: name='io.github.jackotom.hongguo.android${device==='tv'?'.tv':''}' versionCode='34' versionName='0.12.11'\nsdkVersion:'26'`,signature:`Verifies\nSigner #1 certificate SHA-256 digest: ${signer}`,bytes:123,sha256:'a'.repeat(64),filename:`Hongguo-0.12.11-${device}-release-compatible.apk`});
test('phone and TV manifests use own immutable release and separate package',()=>{for(const device of ['phone','tv']){const m=createUpdateManifest(input(device));assert.equal(m.flavor,device);assert.equal(m.versionCode,34);assert.ok(m.apkUrl.includes('/skydeadpeng/hongguo-android-cast/releases/download/v0.12.11/'));assert.ok(m.packageName.endsWith(device==='tv'?'.tv':'.android'));assert.equal(m.channel,'android-compatible');}});
for(const [name,change] of Object.entries({wrongDevice:{device:'mpv'},wrongPackage:{badging:input('tv').badging},badVersion:{badging:input('phone').badging.replace("'34'","'0'")},badName:{badging:input('phone').badging.replace('0.12.11','latest')},debug:{badging:input('phone').badging+'\napplication-debuggable'},wrongSdk:{badging:input('phone').badging.replace("'26'","'23'")},wrongSigner:{signature:'Signer #1 certificate SHA-256 digest: '+'f'.repeat(64)},missingSigner:{signature:'Verifies'},multiSigner:{signature:input('phone').signature+'\nSigner #2 certificate SHA-256 digest: '+signer},empty:{bytes:0},huge:{bytes:101*1024*1024},badSha:{sha256:'xyz'},wrongFilename:{filename:'latest.apk'},longNotes:{notes:'x'.repeat(8001)}}))test(`reject ${name}`,()=>assert.throws(()=>createUpdateManifest({...input('phone'),...change})));

const nativeInput = (compatible = true) => ({
  device: 'tvnative',
  badging: "package: name='io.github.jackotom.hongguo.android.tvnative' versionCode='3' versionName='0.1.2'\nsdkVersion:'26'",
  signature: `Verifies\nSigner #1 certificate SHA-256 digest: ${signer}`,
  bytes: 123,
  sha256: 'b'.repeat(64),
  filename: `HongguoNative-0.1.2-tvNative-release${compatible ? '-compatible' : ''}.apk`,
});
test('native TV manifests retain independent version, package and Native asset identity', () => {
  for (const compatible of [true, false]) {
    const value = nativeInput(compatible);
    const manifest = createUpdateManifest(value);
    assert.equal(manifest.flavor, 'tvnative');
    assert.equal(manifest.packageName, 'io.github.jackotom.hongguo.android.tvnative');
    assert.equal(manifest.versionCode, 3);
    assert.equal(manifest.versionName, '0.1.2');
    assert.equal(manifest.sha256, 'b'.repeat(64));
    assert.equal(manifest.size, 123);
    assert.equal(manifest.apkUrl, `https://github.com/skydeadpeng/hongguo-android-cast/releases/download/v0.1.2/${value.filename}`);
  }
});
for (const [name, change] of Object.entries({
  nativePackageMissingSuffix: { badging: nativeInput().badging.replace('.tvnative', '') },
  nativePackageIsWebViewTV: { badging: nativeInput().badging.replace('.tvnative', '.tv') },
  nativeDeviceWrongCase: { device: 'tvNative' },
  nativeAssetMissingPrefix: { filename: 'Hongguo-0.1.2-tvNative-release-compatible.apk' },
  nativeAssetPhoneSuffix: { filename: 'HongguoNative-0.1.2-phone-release-compatible.apk' },
  nativeAssetTVSuffix: { filename: 'HongguoNative-0.1.2-tv-release-compatible.apk' },
  nativeAssetLowercaseSuffix: { filename: 'HongguoNative-0.1.2-tvnative-release-compatible.apk' },
  nativeAssetWrongVersion: { filename: 'HongguoNative-0.1.1-tvNative-release-compatible.apk' },
  nativeDebug: { badging: nativeInput().badging + '\napplication-debuggable' },
})) test(`reject ${name}`, () => assert.throws(() => createUpdateManifest({ ...nativeInput(), ...change })));
for (const device of ['phone', 'tv']) {
  test(`reject Native prefix in ${device} asset`, () => assert.throws(() => createUpdateManifest({
    ...input(device), filename: `HongguoNative-0.12.11-${device}-release-compatible.apk`,
  })));
  test(`reject native package in ${device} channel`, () => assert.throws(() => createUpdateManifest({
    ...input(device), badging: nativeInput().badging,
  })));
}
