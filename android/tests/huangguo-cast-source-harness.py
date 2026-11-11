#!/usr/bin/env python3
"""Bind actual cache adapters/Prepared without Android or JSON dependencies."""
from pathlib import Path
import sys
src, out = map(Path, sys.argv[1:]); out.mkdir(parents=True, exist_ok=True)
def block(text, marker):
    start=text.index(marker); cursor=text.index('{',start); depth=0
    # Selected blocks contain no brace literals or comments with braces.
    for i in range(cursor,len(text)):
        if text[i]=='{': depth+=1
        elif text[i]=='}':
            depth-=1
            if depth==0:return text[start:i+1]
    raise ValueError(marker)
cache=(src/'PlaybackCache.java').read_text()
markers=['private void discardLegacyAuto(', 'File find(String sid,String vid,int index,String quality)',
         'void protect(String sid,String vid,int index,String quality)',
         'void release(String sid,String vid,int index,String quality)',
         'void invalidate(String sid,String vid,int index,String quality)', 'File fetchCast(']
methods='\n'.join(block(cache, marker) for marker in markers)
(out/'PlaybackCache.java').write_text('''package io.github.jackotom.hongguo.android;
import java.io.*;
final class PlaybackCache {
 final PlaybackCachePolicy policy;
 PlaybackCache(File directory) { policy=new PlaybackCachePolicy(directory,8_000_000,4_000_000,
  (url,file,max)->{throw new AssertionError("Wrong red-source/default downloader route");}); }
'''+methods+'\n}')
prepared=block((src/'DlnaSession.java').read_text(),'static final class Prepared')
(out/'DlnaSession.java').write_text('package io.github.jackotom.hongguo.android;\nimport java.io.*;import java.util.concurrent.atomic.AtomicBoolean;\nfinal class DlnaSession {\n'+prepared+'\n}')
for service in ['DlnaCastService','BrowserCastService']:
    text=(src/(service+'.java')).read_text()
    method=block(text,'private DlnaSession.Prepared prepareMedia(')
    assert method.index('HuangguoCastSource.prepare(')<method.index('AdaptiveQuality.prepare(')
    assert 'HuangguoApi.attach('+service+'.this)' in method
print('HUANGGUO_CAST_BINDING helper,HLS,cachePolicy,cacheAdapters,Prepared,MP4Validation=actual; API,network,remux=boundary doubles; service routes=source contracts')
