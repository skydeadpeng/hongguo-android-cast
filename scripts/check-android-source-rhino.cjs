const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { build, SOURCE_IDS } = require('./build-android-source-modules.cjs');

const cache = path.join(process.env.GRADLE_USER_HOME || path.join(os.homedir(), '.gradle'),
  'caches/modules-2/files-2.1/org.mozilla/rhino/1.8.1');
const root = path.resolve(__dirname, '..');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'android-source-rhino-'));
try {
  const localJars = fs.existsSync(cache) ? fs.readdirSync(cache).map(entry => path.join(cache, entry, 'rhino-1.8.1.jar')) : [];
  const jar = process.env.RHINO_JAR || localJars.find(file => fs.existsSync(file));
  if (!jar) throw new Error('Resolve the Android Gradle dependencies or point RHINO_JAR at Rhino 1.8.1');
  build(temporary);
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java';
  const source = path.join(root, 'android/tests/SourceBundleSyntaxTest.java');
  const result = spawnSync(java, ['-classpath', jar, source, path.join(temporary, 'source-modules'), ...SOURCE_IDS], { stdio: 'inherit', timeout: 90000 });
  if (result.error) throw result.error;
  process.exitCode = result.status ?? 1;
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}
