/**
 * 入口一致性守卫（2026-11 评审新增）。
 * 曾经的教训：package.json 的 main 从 main.js 换成 main-android.js 时，build.files、
 * 渲染层产物与更新源没有跟着改，下一次构建会产出找不到入口的坏包，且更新链路
 * 检查与下载指向两个不同仓库。本脚本把以下不变式固化进 npm test：
 *  1. package.json main 指向的文件真实存在，且被 build.files 模式覆盖；
 *  2. 现入口（main-android.js）依赖的 preload、渲染产物、播放器页都在磁盘上；
 *  3. build.publish、build/app-update.yml、app-update-installer.js 的 setFeedURL
 *     三处更新源必须指向同一个 GitHub 仓库。
 */
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));

let failed = 0;
const fail = message => { console.error(`FAIL ${message}`); failed++; };

const entry = String(pkg.main || '');
if (!entry) {
  fail('package.json 缺少 main 字段');
} else {
  if (!fs.existsSync(path.join(root, entry))) fail(`package.json main 不存在: ${entry}`);
  const files = Array.isArray(pkg.build && pkg.build.files) ? pkg.build.files : [];
  const covered = files.some(pattern => {
    const base = String(pattern).split('/**')[0];
    return pattern === entry || entry === base || entry.startsWith(`${base}/`);
  });
  if (!covered) fail(`build.files 未覆盖入口 ${entry}，electron-builder 会打出找不到入口的 asar`);
}

if (entry === 'main-android.js') {
  for (const required of ['preload-android.js', 'desktop-android-player/player.html']) {
    if (!fs.existsSync(path.join(root, required))) fail(`入口依赖缺失: ${required}`);
  }
  // dist-react-android 是 gitignore 的构建产物：整体缺席只提示（构建时会产出），
  // 目录存在但缺 index.html 才是真正的残缺状态。
  const shellDir = path.join(root, 'dist-react-android');
  if (fs.existsSync(shellDir) && !fs.existsSync(path.join(shellDir, 'index.html'))) {
    fail('dist-react-android/ 目录残缺（缺 index.html），先运行 npm run build:react:shell');
  } else if (!fs.existsSync(shellDir)) {
    console.log('NOTE dist-react-android/ 尚未构建，打包前请运行 npm run build:react:shell');
  }
}

const publish = (pkg.build && pkg.build.publish) || {};
const ymlPath = path.join(root, 'build', 'app-update.yml');
const yml = fs.readFileSync(ymlPath, 'utf8');
const ymlOwner = /owner:\s*(\S+)/.exec(yml);
const ymlRepo = /repo:\s*(\S+)/.exec(yml);
if (!ymlOwner || !ymlRepo) {
  fail('build/app-update.yml 缺少 owner/repo');
} else if (ymlOwner[1] !== publish.owner || ymlRepo[1] !== publish.repo) {
  fail(`build/app-update.yml (${ymlOwner[1]}/${ymlRepo[1]}) 与 build.publish (${publish.owner}/${publish.repo}) 指向不同仓库`);
}

const installerSource = fs.readFileSync(path.join(root, 'src', 'native', 'app-update-installer.js'), 'utf8');
const feed = /setFeedURL\(\{[^}]*owner:\s*'([^']+)'[^}]*repo:\s*'([^']+)'/s.exec(installerSource);
if (!feed) {
  fail('app-update-installer.js 未找到 setFeedURL 配置');
} else if (feed[1] !== publish.owner || feed[2] !== publish.repo) {
  fail(`app-update-installer.js setFeedURL (${feed[1]}/${feed[2]}) 与 build.publish (${publish.owner}/${publish.repo}) 指向不同仓库`);
}

if (failed) {
  process.exitCode = 1;
} else {
  console.log(`PASS check-electron-entry (main=${entry}, feed=${publish.owner}/${publish.repo})`);
}
