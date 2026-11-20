#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { parse } = require('@babel/parser');
const traverse = require('@babel/traverse').default;
const generate = require('@babel/generator').default;
const t = require('@babel/types');
const { transformSync } = require('esbuild');

const ROOT = path.resolve(__dirname, '..');
const BUNDLE = path.join(ROOT, 'vendor', 'source-modules');
const SOURCE_IDS = Object.freeze([
  'beabox', 'dsd', 'guipian', 'hanime1', 'hanxiaoquan', 'heitao', 'hongguo',
  'huangguo-video', 'huangguoai', 'huangju', 'ikanbot', 'jinguoduanju', 'missav', 'sorani', 'yeguo',
]);
const FILES = ['manifest.json', 'main.js'];
const MAX_BYTES = 1024 * 1024;
const digest = (data) => createHash('sha256').update(data).digest('hex');

let punctuationWithWhitespace;
const unitEscape = unit => '\\u' + unit.toString(16).padStart(4, '0');
function codeUnitClass(values, extra = '') {
  let out = extra;
  for (let i = 0; i < values.length; i++) {
    const start = values[i];
    while (i + 1 < values.length && values[i + 1] === values[i] + 1) i++;
    out += unitEscape(start) + (start === values[i] ? '' : '-' + unitEscape(values[i]));
  }
  return '[' + out + ']';
}

function punctuationOrSpace() {
  if (punctuationWithWhitespace) return punctuationWithWhitespace;
  // Rhino lacks the regexp Unicode flag. Preserve the locked red source's
  // punctuation matching semantics using BMP classes and paired surrogates.
  const punctuation = /^\p{P}$/u;
  const bmp = [], astral = new Map();
  for (let cp = 0; cp <= 0x10ffff; cp++) {
    if (!punctuation.test(String.fromCodePoint(cp))) continue;
    if (cp < 0x10000) bmp.push(cp);
    else {
      const high = 0xd800 + ((cp - 0x10000) >> 10);
      const low = 0xdc00 + ((cp - 0x10000) & 0x3ff);
      if (!astral.has(high)) astral.set(high, []);
      astral.get(high).push(low);
    }
  }
  return punctuationWithWhitespace = '(?:' + [
    codeUnitClass(bmp, '\\s'),
    ...[...astral].map(([high, lows]) => unitEscape(high) + codeUnitClass(lows)),
  ].join('|') + ')';
}

function forRhino(js, id = 'source') {
  // esbuild handles optional chaining / object spread and keeps exact BigInts.
  const lowered = transformSync(js, {
    sourcefile: id + '/main.js', target: 'es2016', supported: { bigint: true }, format: 'cjs', legalComments: 'inline',
  }).code;
  const tree = parse(lowered, { sourceType: 'script' });
  const bindings = new Set(), names = new Set();
  traverse(tree, {
    Identifier: ({ node }) => { names.add(node.name); },
    Scope: ({ scope }) => {
      for (const binding of Object.values(scope.bindings)) {
        if (binding.kind === 'const' || binding.kind === 'let') bindings.add(binding);
      }
    },
  });
  // Rhino rejects legal reuse of block-scoped bindings across sibling blocks.
  // Scope-aware alpha-renaming (including shorthand properties) keeps behavior.
  let sequence = 0;
  for (const binding of bindings) {
    let name;
    do { name = '$as' + sequence++; } while (names.has(name));
    names.add(name);
    binding.scope.rename(binding.identifier.name, name);
  }

  const arrayFrom = value => t.callExpression(t.memberExpression(t.identifier('Array'), t.identifier('from')), [value]);
  const reflectApply = (fn, receiver, args) => t.callExpression(
    t.memberExpression(t.identifier('Reflect'), t.identifier('apply')), [fn, receiver, args]);
  traverse(tree, {
    VariableDeclaration: ({ node }) => {
      // Rhino also retains the first value of const declarations in a loop
      // body. With validated source we can use let and keep per-entry values.
      if (node.kind === 'const') node.kind = 'let';
    },
    ArrayExpression: path => {
      const { elements } = path.node;
      if (!elements.some(value => t.isSpreadElement(value))) return;
      const [spread] = elements;
      if (elements.length !== 1 || !t.isSpreadElement(spread)
        || !t.isNewExpression(spread.argument) || !t.isIdentifier(spread.argument.callee, { name: 'Set' })) {
        throw new Error('Android source runtime found an unreviewed array spread: ' + id);
      }
      path.replaceWith(arrayFrom(spread.argument));
    },
    CallExpression: path => {
      const { callee, arguments: args } = path.node;
      // Restricted Rhino globals keep Reflect.apply but not Function construction.
      // Lower the exact builtin call/apply patterns present in the pinned scripts.
      if (t.matchesPattern(callee, 'Object.prototype.hasOwnProperty.call') && args.length === 2 &&
          args.every(value => !t.isSpreadElement(value)) && !path.scope.getBinding('Object') && !path.scope.getBinding('Reflect')) {
        path.replaceWith(reflectApply(callee.object, args[0], t.arrayExpression([args[1]])));
        return;
      }
      if (t.matchesPattern(callee, 'String.fromCharCode.apply') && args.length === 2 &&
          args.every(value => !t.isSpreadElement(value)) && !path.scope.getBinding('String') && !path.scope.getBinding('Reflect')) {
        path.replaceWith(reflectApply(callee.object, args[0], args[1]));
        return;
      }
      if (!args.some(value => t.isSpreadElement(value))) return;
      const [spread] = args;
      // Only permitted shapes from the pinned modules: f(...items) and
      // object.method(...items). No computed receivers or repeated side effects.
      if (args.length !== 1 || !t.isSpreadElement(spread)
        || !(t.isIdentifier(callee) || (t.isMemberExpression(callee) && !callee.computed && t.isIdentifier(callee.object)))) {
        throw new Error('Android source runtime found an unreviewed call spread: ' + id);
      }
      const receiver = t.isIdentifier(callee) ? t.unaryExpression('void', t.numericLiteral(0)) : t.cloneNode(callee.object);
      if (path.scope.getBinding('Reflect')) throw new Error('Android source runtime cannot lower spread with a shadowed Reflect: ' + id);
      path.replaceWith(reflectApply(callee, receiver, arrayFrom(spread.argument)));
    },
    RegExpLiteral: ({ node }) => {
      if (!node.flags.includes('u')) return;
      const original = node.pattern;
      const cc = '\\u0000-\\u001f\\u007f-\\u009f';
      node.pattern = original
        .replace(/\[\\s\\p\{P\}\]|\[\\p\{P\}\\s\]/g, () => punctuationOrSpace())
        .replace('[\\p{Cc}\\u2028\\u2029]', '[' + cc + '\\u2028\\u2029]')
        .replace(/\\p\{Cc\}/g, '[' + cc + ']');
      if (id !== 'hongguo' || node.pattern === original || /\\[pP]\{/.test(node.pattern)) {
        throw new Error('Android source runtime found an unreviewed Unicode regexp: ' + id);
      }
      node.flags = node.flags.replace('u', '');
    },
  });
  return generate(tree, { comments: true }).code + '\n';
}

function build(assetsRoot, bundle = BUNDLE) {
  const index = JSON.parse(fs.readFileSync(path.join(bundle, 'index.json'), 'utf8'));
  if (index?.format !== 1 || !Array.isArray(index.modules) || index.modules.length !== SOURCE_IDS.length) {
    throw new Error('Android source index is invalid');
  }
  const files = new Map();
  const modules = SOURCE_IDS.map(id => {
    const matches = index.modules.filter(entry => entry?.id === id);
    const entry = matches[0];
    if (matches.length !== 1 || typeof entry.adult !== 'boolean' || entry.api !== 1) throw new Error(`Android source identity is invalid: ${id}`);
    for (const name of FILES) {
      const data = fs.readFileSync(path.join(bundle, id, name));
      if (data.length > MAX_BYTES || digest(data) !== entry.sha256?.[name]) throw new Error(`Android source SHA-256 mismatch: ${id}/${name}`);
      files.set(`${id}/${name}`, data);
    }
    const manifest = JSON.parse(files.get(`${id}/manifest.json`).toString('utf8'));
    if (manifest.id !== id || manifest.version !== entry.version || manifest.adult !== entry.adult || manifest.api !== 1 || manifest.runtime !== 'js') {
      throw new Error(`Android source manifest is invalid: ${id}`);
    }
    const runtime = Buffer.from(forRhino(files.get(`${id}/main.js`).toString('utf8'), id), 'utf8');
    if (runtime.length > MAX_BYTES) throw new Error(`Android source runtime is too large: ${id}`);
    files.set(`${id}/runtime.js`, runtime);
    return { id, version: manifest.version, adult: manifest.adult, sha256: { 'manifest.json': entry.sha256['manifest.json'], 'main.js': entry.sha256['main.js'], 'runtime.js': digest(runtime) } };
  });
  files.set('index.json', JSON.stringify({ format: 1, modules }, null, 2) + '\n');
  for (const name of ['LICENSE', 'README.md']) files.set(name, fs.readFileSync(path.join(bundle, name)));
  const output = path.join(assetsRoot, 'source-modules');
  // Verify and transform the complete input set before replacing old assets.
  fs.rmSync(output, { recursive: true, force: true });
  for (const [relative, content] of files) {
    const dest = path.join(output, relative);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest, content);
  }
  return modules;
}

if (require.main === module) {
  try {
    const output = path.resolve(process.argv[2] || path.join(ROOT, 'android/app/build/generated/androidSourceModules/assets'));
    const modules = build(output);
    console.log(`ANDROID_SOURCE_MODULES_BUILT modules=${modules.map(row => row.id).join(',')} lockedFiles=${modules.length * FILES.length}`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}

module.exports = { build, forRhino, SOURCE_IDS };
