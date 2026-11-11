#!/usr/bin/env node
/** Actual packaged Vite graph, real React/App/bridge/parser, synthetic native RPC.
 * This is legacy-capability simulation in isolated local Chrome, not TV/device QA. */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const own = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let root = own, output = path.join(own, 'verification/tv-release-black-screen/webview-current');
for (let i=2;i<process.argv.length;i++) {
  if(process.argv[i]==='--root'&&process.argv[i+1])root=path.resolve(process.argv[++i]);
  else if(process.argv[i]==='--output'&&process.argv[i+1])output=path.resolve(process.argv[++i]);
  else throw Error('Usage: --root PROJECT --output DIRECTORY');
}
fs.mkdirSync(output,{recursive:true});
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'hongguo-webview-')), project=path.join(tmp,'project');
const assets=path.join(project,'android/app/src/main/assets/web');
const require=createRequire(import.meta.url),{parse}=require('@babel/parser');
const report={root,kind:'actual built bundle; real React App, source parser and RPC; local Chrome legacy capability simulation only',tests:[],screenshots:[],externalRequests:[],pageErrors:[],deviceTests:'not_run',emulator:'not_used',adb:'not_used'};
let server,chrome,ws,checks=0,astNodes=0,passed=0,failed=0;
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const check=(value,message)=>{checks++;assert.ok(value,message);};
const eq=(actual,expected,message)=>{checks++;assert.deepEqual(actual,expected,message);};
const group=async(name,fn)=>{try{await fn();passed++;report.tests.push({name,result:'PASS'});console.log('PASS '+name);}catch(e){failed++;report.tests.push({name,result:'FAIL',error:e.message});console.log('FAIL '+name+': '+e.message);}};
function walk(node,visitor){if(!node||typeof node!=='object')return;visitor(node);for(const [key,value]of Object.entries(node)){if(['loc','start','end','extra','comments'].includes(key))continue;if(Array.isArray(value))for(const child of value)walk(child,visitor);else if(value&&typeof value==='object')walk(value,visitor);}}
function installMock() {
  var token='11111111-1111-4111-8111-111111111111';
  var mode=new URL(location.href).searchParams.get('case');
  window.__fixture={calls:[],ready:[],failed:[],events:[],mode:mode};
  var f=window.__fixture;
  window.AndroidStartup={ready:function(value){if(value===token){f.ready.push(value);f.events.push({type:'nativeReady',rootHasApp:!!document.querySelector('.android-app'),rootHasNav:!!document.querySelector('.app-nav')});}},failed:function(value,code){if(value===token)f.failed.push({token:value,code:code});}};
  var card={series_id:'1234567890123456789',series_name:'MOCK 旧电视启动',series_title:'MOCK 旧电视启动',series_cover:'',episode_cnt:8,episode_right_text:'全8集',category_list:[{name:'MOCK'}]};
  window.AndroidBridge={postMessage:function(raw){
    var m=JSON.parse(raw),result,error;f.calls.push(m);
    if(m.method==='getInfo')result={version:'MOCK-TV-WebView',platform:'android',isTv:true};
    else if(m.method==='getLibrary')result={favorites:[],history:[]};
    else if(m.method==='getPlaybackOptions')result={quality:'1080p',speed:1};
    else if(m.method==='getSearchHistory')result={keywords:[]};
    else if(m.method==='httpGet')result={status:200,url:m.params.url,body:'<script>window._ROUTER_DATA='+JSON.stringify({loaderData:{catalog:{isSuccess:true,categoryRoute:{contentType:new URL(m.params.url).pathname.split('/').pop()},recommendList:[card],pagination:{pageNum:1,totalPages:1,total:1}}}})+';</script>'};
    else if(m.method==='appResolveResult')result={received:true};
    else error='UNEXPECTED_MOCK_RPC_'+m.method;
    setTimeout(function(){window.__androidResponse({id:m.id,result:result,error:error});},2);
  }};
  // Remove APIs missing in real Chromium 67 before any packaged code executes.
  delete window.globalThis;delete Array.prototype.flat;delete Array.prototype.flatMap;delete String.prototype.matchAll;
  if(mode==='unsupported')window.BigInt=undefined;
  window.addEventListener('securitypolicyviolation',function(e){f.events.push({type:'csp',directive:e.violatedDirective});});
}
try {
  fs.mkdirSync(project,{recursive:true});
  fs.cpSync(path.join(root,'src'),path.join(project,'src'),{recursive:true,filter:source=>!source.includes('/android/generated')&&!source.includes('/public/generated')});
  fs.mkdirSync(path.join(project,'scripts'),{recursive:true});
  for(const name of ['build-android-mac-source.mjs','build-android-startup.mjs','check-android-mac-source.test.mjs']){
    const source=path.join(root,'scripts',name);if(fs.existsSync(source))fs.copyFileSync(source,path.join(project,'scripts',name));
  }
  for(const name of ['vite.android.config.mjs','LICENSE','package.json'])fs.copyFileSync(path.join(root,name),path.join(project,name));
  fs.symlinkSync(path.join(own,'node_modules'),path.join(project,'node_modules'),'dir');
  const build=spawnSync(process.execPath,[path.join(project,'scripts/build-android-mac-source.mjs')],{cwd:project,encoding:'utf8',timeout:60000});
  fs.writeFileSync(path.join(output,'adapter-build.log'),(build.stdout||'')+(build.stderr||''));if(build.status!==0)throw Error('adapter build exit='+build.status);
  const vite=spawnSync(process.execPath,[path.join(own,'node_modules/vite/bin/vite.js'),'build','--config',path.join(project,'vite.android.config.mjs')],{cwd:project,encoding:'utf8',timeout:60000});
  fs.writeFileSync(path.join(output,'vite-build.log'),(vite.stdout||'')+(vite.stderr||''));if(vite.status!==0)throw Error('Vite build exit='+vite.status);
  const html=fs.readFileSync(path.join(assets,'index.html'),'utf8');
  const modulePath=(/data-app-src="([^"]+)"/.exec(html)||/type="module"[^>]*src="([^"]+)"/.exec(html)||[])[1];
  check(!!modulePath,'built entry exists');
  const moduleFile=path.resolve(assets,modulePath),appJs=fs.readFileSync(moduleFile,'utf8');
  report.bundleBytes=Buffer.byteLength(appJs);report.modulePath=modulePath;
  await group('packaged ES5 guard precedes Chrome67-compatible real app graph',async()=>{
    check(!/<script[^>]*type="module"/.test(html),'HTML must not dispatch modern module before capability check');
    const bootFile=path.join(assets,'generated/startup-bootstrap.js');check(fs.existsSync(bootFile),'classic bootstrap is packaged');
    const bootJs=fs.readFileSync(bootFile,'utf8');
    const es5Forbidden=new Set(['ArrowFunctionExpression','ClassDeclaration','ClassExpression','TemplateLiteral','TaggedTemplateExpression','AwaitExpression','YieldExpression','SpreadElement','RestElement','ObjectPattern','ArrayPattern','ImportDeclaration','ExportNamedDeclaration','ExportDefaultDeclaration','BigIntLiteral','OptionalMemberExpression','OptionalCallExpression']);
    walk(parse(bootJs,{sourceType:'script'}),n=>{astNodes++;assert.ok(!es5Forbidden.has(n.type),'classic ES5 AST: '+n.type);if(n.type==='VariableDeclaration')assert.equal(n.kind,'var','ES5 variable');if(n.type==='ObjectMethod'||n.type==='ObjectProperty'&&n.shorthand||n.type==='AssignmentPattern')assert.fail('ES5 syntax: '+n.type);});
    walk(parse(appJs,{sourceType:'module'}),n=>{astNodes++;assert.ok(!['OptionalMemberExpression','OptionalCallExpression'].includes(n.type),'Chrome67 no optional chain');if(n.type==='LogicalExpression')assert.notEqual(n.operator,'??','Chrome67 no nullish operator');});
    const adapter=fs.readFileSync(path.join(project,'src/android/generated/mac-app-source.mjs'),'utf8');
    walk(parse(adapter,{sourceType:'module'}),n=>{astNodes++;assert.ok(!['OptionalMemberExpression','OptionalCallExpression'].includes(n.type),'adapter Chrome67 no optional chain');if(n.type==='LogicalExpression')assert.notEqual(n.operator,'??','adapter no nullish');});
    const csp=html.match(/Content-Security-Policy" content="([^"]+)"/)[1];check(csp.includes("script-src 'self'")&&!csp.includes("'unsafe-eval'")&&!csp.includes("script-src 'self' 'unsafe-inline'"),'self-only CSP retained');
    const notice=fs.readFileSync(path.join(assets,'third-party-core-js.txt'),'utf8');check(notice.includes('Permission is hereby granted, free of charge')&&notice.includes('THE SOFTWARE IS PROVIDED "AS IS"'),'complete MIT license packaged');
  });
  await group('unmodified Mac protocol fixed vectors and strict source contract',async()=>{
    const run=spawnSync(process.execPath,['--test',path.join(project,'scripts/check-android-mac-source.test.mjs')],{cwd:project,encoding:'utf8',timeout:60000,maxBuffer:4*1024*1024});
    const text=(run.stdout||'')+(run.stderr||'');fs.writeFileSync(path.join(output,'mac-protocol-vectors.log'),text);eq(run.status,0,'fixed vectors exit 0');check(/# fail 0\b/.test(text),'all real adapter vectors pass');
  });
  server=http.createServer((request,response)=>{
    const url=new URL(request.url,'http://localhost');let file=path.resolve(assets,'.'+url.pathname);
    if(url.pathname==='/'){response.setHeader('Content-Type','text/html');let page=html;
      if(['moduleload','parse','waiting'].includes(url.searchParams.get('case')))page=page.replace(/data-app-src="[^"]+"/,`data-app-src="./${url.searchParams.get('case')}.js"`);
      response.end(page);return;}
    if(url.pathname==='/parse.js'){response.setHeader('Content-Type','text/javascript');response.end('const = syntax failure;');return;}
    if(url.pathname==='/waiting.js'){response.setHeader('Content-Type','text/javascript');response.end('window.__fixture.events.push({type:"waitingModule"});');return;}
    if(!file.startsWith(assets+path.sep)||!fs.existsSync(file)||!fs.statSync(file).isFile()){response.statusCode=404;response.end('missing');return;}
    response.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/plain');response.end(fs.readFileSync(file));
  });
  await new Promise(r=>server.listen(0,'127.0.0.1',r));const base=`http://127.0.0.1:${server.address().port}/`;
  const profile=path.join(tmp,'chrome');chrome=spawn(process.env.CHROME_BIN||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',['--headless=new','--remote-debugging-port=0',`--user-data-dir=${profile}`,'--no-first-run','--disable-background-networking','--disable-default-apps','--no-default-browser-check','about:blank'],{stdio:['ignore','ignore',fs.openSync(path.join(output,'chrome.log'),'w')]});
  let port;for(let i=0;i<100;i++){try{port=Number(fs.readFileSync(path.join(profile,'DevToolsActivePort'),'utf8').split('\n')[0]);if(port)break;}catch{}await sleep(100);}if(!port)throw Error('Chrome startup failed');
  const target=await fetch(`http://127.0.0.1:${port}/json/new?about:blank`,{method:'PUT'}).then(r=>r.json());ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.addEventListener('open',r,{once:true});ws.addEventListener('error',j,{once:true});});
  let seq=0;const pending=new Map(),requests=[];ws.addEventListener('message',({data})=>{const m=JSON.parse(data);if(m.id){const p=pending.get(m.id);if(p){pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result);}}if(m.method==='Runtime.exceptionThrown')report.pageErrors.push(m.params.exceptionDetails.exception?.description||m.params.exceptionDetails.text);if(m.method==='Network.requestWillBeSent'){const url=m.params.request.url;requests.push(url);if(!url.startsWith(base)&&!url.startsWith('data:'))report.externalRequests.push(url);}});
  const call=(method,params={})=>new Promise((r,j)=>{const id=++seq;pending.set(id,{resolve:r,reject:j});ws.send(JSON.stringify({id,method,params}));});
  const evaluate=async expression=>{const r=await call('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(r.exceptionDetails)throw Error(r.exceptionDetails.exception?.description||r.exceptionDetails.text);return r.result.value;};
  const wait=async(expression,label=expression)=>{for(let i=0;i<100;i++){if(await evaluate(`Boolean(${expression})`))return;await sleep(30);}throw Error('WAIT_FAILED '+label);};
  const load=async(mode)=>{requests.length=0;await call('Page.navigate',{url:base+`?startup=11111111-1111-4111-8111-111111111111&case=${mode}`});await wait('window.__fixture','fixture installed');};
  const shot=async name=>{const file=path.join(output,name);fs.writeFileSync(file,Buffer.from((await call('Page.captureScreenshot',{format:'png'})).data,'base64'));report.screenshots.push(file);};
  await call('Page.enable');await call('Page.bringToFront');await call('Runtime.enable');await call('Network.enable');await call('Emulation.setDeviceMetricsOverride',{width:1280,height:720,deviceScaleFactor:1,mobile:false});await call('Page.addScriptToEvaluateOnNewDocument',{source:`(${installMock.toString()})();`});
  await group('missing globalThis/flat/flatMap/matchAll: real React and real source parser mount',async()=>{
    await load('legacy');try{await wait('document.querySelector(".poster strong")','real catalog poster');}catch(error){report.legacyFailure=await evaluate('({rootChildren:document.getElementById("root").childElementCount,rootText:document.getElementById("root").textContent,calls:window.__fixture.calls.length,ready:window.__fixture.ready.length,failed:window.__fixture.failed.length})');await shot('tv-baseline-legacy-black-screen.png');throw error;}eq(await evaluate('document.querySelector(".poster strong").textContent'),'MOCK 旧电视启动','real parser handled fixture HTML');
    eq(await evaluate('[typeof globalThis,typeof [].flat,typeof [].flatMap,typeof "".matchAll]'),['object','function','function','function'],'all missing APIs restored');
    eq(await evaluate('JSON.stringify([1,,[2,,[3]]].flat(2))'),'[1,2,3]','standard sparse flatten depth');eq(await evaluate('JSON.stringify([1,2].flatMap(function(x){return [x,x*2]}))'),'[1,2,2,4]','standard flatMap');
    eq(await evaluate('Array.from("a1a2".matchAll(/a(\u005cd)/g),function(m){return m[1]}).join(",")'),'1,2','standard matchAll capture iterator');
    eq(await evaluate('window.__fixture.ready.length'),1,'actual React commit ready once');eq(await evaluate('window.__fixture.events.filter(function(e){return e.type==="nativeReady"}).map(function(e){return [e.rootHasApp,e.rootHasNav]})'),[[true,true]],'native ready observed actual committed App and navigation DOM');eq(await evaluate('window.__fixture.failed.length'),0,'no startup failure');eq(await evaluate('typeof window.__androidAppResolve'),'function','shared source resolver really registered');
    await evaluate('window.HongguoStartup.ready();window.HongguoStartup.ready();window.AndroidStartup.ready("old-token")');eq(await evaluate('window.__fixture.ready.length'),1,'duplicate and stale readiness ignored');
    eq(await evaluate('window.__fixture.events.filter(function(e){return e.type==="csp"}).length'),0,'no CSP eval/inline violations');
    await wait('document.querySelector(".tv-layout")','TV layout applies');await evaluate('for(var sheet of document.styleSheets){for(var i=sheet.cssRules.length-1;i>=0;i--){var rule=sheet.cssRules[i];if(rule.conditionText&&rule.conditionText.indexOf("aspect-ratio")>=0)sheet.deleteRule(i);}}document.querySelector(".poster-art").style.aspectRatio="auto";document.querySelector(".poster").focus()');const size=await evaluate('(()=>{var e=document.querySelector(".poster-art"),r=e.getBoundingClientRect();return {width:r.width,height:r.height,outline:getComputedStyle(document.querySelector(".poster")).outlineWidth,active:document.activeElement.className,layout:document.querySelector(".android-app").className}})()');check(size.height>0&&Math.abs(size.height/size.width-4/3)<.02,'portrait fallback layout');eq(size.outline,'4px','TV focus fallback '+JSON.stringify(size));await shot('tv-real-app-legacy-apis.png');
  });
  await group('no BigInt: visible unsupported screen and no modern module dispatch',async()=>{
    await load('unsupported');await wait('window.__fixture.failed.length===1','unsupported callback');eq(await evaluate('window.__fixture.failed[0].code'),'unsupported_webview','sanitized failure code');check((await evaluate('document.body.textContent')).includes('Android System WebView'),'readable unsupported message');eq(await evaluate('window.__fixture.ready.length'),0,'unsupported not ready');eq(await evaluate('window.__fixture.calls.length'),0,'unsupported never initializes app RPC');check(!requests.some(u=>u.includes('/assets/')&&u.endsWith('.js')),'unsupported never requests modern app');await shot('tv-unsupported-webview.png');
  });
  await group('module 404 and parse faults show retry screen and sanitized failure',async()=>{
    for(const mode of ['moduleload','parse']){await load(mode);await wait('window.__fixture.failed.length===1',mode+' failure');eq(await evaluate('window.__fixture.failed[0].code'),'script_error',mode+' sanitized code');eq(await evaluate('window.__fixture.ready.length'),0,mode+' no false ready');check((await evaluate('document.body.textContent')).includes('应用页面加载失败'),mode+' visible fallback');check(await evaluate('document.querySelector(".startup-panel button")!==null'),mode+' retry action');}
    await shot('tv-module-parse-failure.png');
  });
  await group('parsed module is not ready; early errors and late duplicate ready stay failed',async()=>{
    await load('waiting');await wait('window.__fixture.events.some(function(e){return e.type==="waitingModule"})','waiting module executed');eq(await evaluate('window.__fixture.ready.length'),0,'parsed module alone not ready');
    await evaluate('window.dispatchEvent(new PromiseRejectionEvent("unhandledrejection",{promise:Promise.resolve(),reason:"synthetic startup error"}))');await wait('window.__fixture.failed.length===1');eq(await evaluate('window.__fixture.failed[0].code'),'script_error','unhandled startup error sanitized');await evaluate('window.HongguoStartup.ready();window.HongguoStartup.ready();window.HongguoStartup.failed("script_error")');eq(await evaluate('window.__fixture.ready.length'),0,'late ready ignored after failure');eq(await evaluate('window.__fixture.failed.length'),1,'failure notified once');
  });
  eq(report.externalRequests,[],'zero external requests');
} catch(error) {failed++;report.tests.push({name:'runner',result:'FAIL',error:error.stack});console.error(error.stack);}
finally {
  report.groupsPassed=passed;report.groupsFailed=failed;report.checks=checks;report.astNodes=astNodes;
  fs.writeFileSync(path.join(output,'webview-report.json'),JSON.stringify(report,null,2)+'\n');
  try{ws?.close();}catch{}try{chrome?.kill('SIGTERM');}catch{}if(server)await new Promise(r=>server.close(r));if(chrome&&chrome.exitCode===null)await Promise.race([new Promise(r=>chrome.once('exit',r)),sleep(3000)]);fs.rmSync(tmp,{recursive:true,force:true,maxRetries:8,retryDelay:100});
}
console.log(`ANDROID_TV_WEBVIEW_${failed?'FAILED':'OK'} groupsPassed=${passed} groupsFailed=${failed} checks=${checks} astNodes=${astNodes} externalRequests=${report.externalRequests.length} deviceTests=not_run`);process.exitCode=failed?1:0;
