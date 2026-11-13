#!/usr/bin/env python3
"""Extract production strict resolver; JSON construction and preferences are explicit doubles."""
from pathlib import Path
import re, sys
src, out = map(Path, sys.argv[1:]); out.mkdir(parents=True, exist_ok=True)
source=(src/'HuangguoApi.java').read_text()
def block(marker):
    start=source.index(marker);cursor=source.index('{',start);depth=0;quote=None;escape=False;comment=None
    while cursor<len(source):
        ch=source[cursor];pair=source[cursor:cursor+2]
        if comment=='line':
            if ch=='\n':comment=None
        elif comment=='block':
            if pair=='*/':comment=None;cursor+=1
        elif quote:
            if escape:escape=False
            elif ch=='\\':escape=True
            elif ch==quote:quote=None
        elif pair=='//':comment='line';cursor+=1
        elif pair=='/*':comment='block';cursor+=1
        elif ch in (chr(34), chr(39)):quote=ch
        elif ch=='{':depth+=1
        elif ch=='}':
            depth-=1
            if depth==0:return source[start:cursor+1]
        cursor+=1
    raise ValueError(marker)
methods='\n'.join(block(marker) for marker in [
    'static String encodeSeries(', 'static String encodeEpisode(',
    'static long decodeSeries(', 'static int decodeEpisodeIndex(',
    'private static int pinnedOrigin(',
    'private static JSONObject get(String path)',
    'private static JSONObject post(String path)',
    'private static JSONObject fetch(String path, boolean asPost)',
    'private static void ensureOriginReady(',
    'private static int probeOrigins(',
    'private static JSONObject fetch(String path, boolean asPost, boolean strict)',
    'private static JSONObject fetch(String path, boolean asPost, boolean strict, long deadline)',
    'private static JSONObject fetchFrom(String origin, String path, boolean asPost, boolean strict)',
    'private static JSONObject fetchFrom(String origin, String path, boolean asPost, boolean strict, long deadline)',
    'private static final class SourceDeniedException', 'static final class CastStream',
    'static CastStream episodeForCast(String sid, String vid, int index)',
    'static CastStream episodeForCast(String sid, String vid, int index, long deadline)'])
origins=block('private static final String[] ORIGINS')+';'
timeout=re.search(r'private static final long PER_ORIGIN_MS\s*=\s*[^;]+;',source).group()
cooldown=re.search(r'private static volatile long probeCooldownUntil\s*=\s*[^;]+;',source).group()
proxy=re.search(r'private static final int PROXY_INDEX\s*=\s*[^;]+;',source).group()
(out/'HuangguoApi.java').write_text('''package io.github.jackotom.hongguo.android;
import java.io.*;import java.util.*;
final class HuangguoApi {
 static int originIndex;
 static final TestPrefs preferences=new TestPrefs();
 private static TestPrefs prefs(){return preferences;}
 // Persistent Android storage is a boundary, not emulated as production here.
 private static void stickOrigin(int index){originIndex=index;preferences.remembered=true;}
'''+origins+'\n'+timeout+'\n'+cooldown+'\n'+proxy+'\n'+methods+'\n}')
print('HUANGGUO_API_BINDING strictFetch,fetchFrom,episodeForCast,IDs,pinnedOrigin,lineProbe=actual; transport,preferenceStorage,JSONParser=explicit doubles (registered tokens, not real JSON parsing)')
