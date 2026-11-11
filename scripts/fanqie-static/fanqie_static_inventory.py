#!/usr/bin/env python3
"""Read-only APK inventory and bounded video-protocol indexes. Never executes APK code."""
import argparse, collections, hashlib, json, pathlib, re, struct, zipfile, zlib

def u32(b, off): return struct.unpack_from('<I', b, off)[0]
def dex_strings(b):
    count, off = struct.unpack_from('<II', b, 56)
    result = []
    for i in range(count):
        p = u32(b, off + 4*i)
        while b[p] & 128: p += 1
        p += 1
        result.append(b[p:b.index(b'\0', p)].decode('utf-8', errors='replace'))
    return result

def main():
    a = argparse.ArgumentParser(); a.add_argument('apk'); a.add_argument('out'); args = a.parse_args()
    src=pathlib.Path(args.apk); out=pathlib.Path(args.out); out.mkdir(parents=True,exist_ok=True)
    z=zipfile.ZipFile(src); entries=z.infolist()
    inventory={'apk':src.name,'sha256':hashlib.sha256(src.read_bytes()).hexdigest(),'bytes':src.stat().st_size,'entries':len(entries),'uncompressed_bytes':sum(i.file_size for i in entries),'dex':[],'native':[],'prefix_counts':dict(collections.Counter(n.filename.split('/')[0] for n in entries))}
    api=[]; classes=[]; public_urls=[]; schema_candidates=[]
    for entry in entries:
        n=entry.filename
        if n.endswith('.so'):
            inventory['native'].append({'path':n,'bytes':entry.file_size,'sha256':hashlib.sha256(z.read(n)).hexdigest()})
        if not re.fullmatch(r'classes[0-9]*\.dex',n): continue
        b=z.read(n);strings=dex_strings(b);types=[strings[u32(b,u32(b,68)+4*i)] for i in range(u32(b,64))]
        class_defs=[types[u32(b,u32(b,100)+32*i)] for i in range(u32(b,96))]
        inventory['dex'].append({'name':n,'bytes':len(b),'strings':len(strings),'types':len(types),'method_ids':u32(b,88),'class_defs':len(class_defs),'sha1_ok':hashlib.sha1(b[32:]).digest()==b[12:32],'adler32_ok':zlib.adler32(b[12:])&0xffffffff==u32(b,8)})
        for i,s in enumerate(strings):
            if len(s)<600 and (s.startswith('$GET ') or s.startswith('$POST ') or s.startswith('/reading/') or s.startswith('/novel/')) and any(v in s.lower() for v in ['video','search','album','short','comic','series']):
                api.append({'dex':n,'string_id':i,'value':s})
            if len(s)<800 and (s.startswith('https://') or s.startswith('dragon1967://')) and any(v in s.lower() for v in ['video','short','comic','playlet','drama','manju']):
                public_urls.append({'dex':n,'string_id':i,'value':s})
        for s in class_defs:
            if re.search(r'(?i)(shortvideo|short_video|saas/rpc|video_tab|playlet)',s): classes.append({'dex':n,'class':s})
            if ('/rpc/' in s or '/model/' in s) and re.search(r'(?i)(Video.*(Request|Response|Type)|Album|VideoBiz|ContentType|Series.*(Request|Response))',s): schema_candidates.append({'dex':n,'class':s})
    for name,obj in [('inventory',inventory),('video-api-strings',api),('video-classes',classes),('video-public-urls',public_urls),('video-schema-candidates',schema_candidates)]:
        (out/(name+'.json')).write_text(json.dumps(obj,ensure_ascii=False,indent=2))
    print(json.dumps({'entries':inventory['entries'],'dex':len(inventory['dex']),'native':len(inventory['native']),'class_defs':sum(x['class_defs'] for x in inventory['dex']),'method_ids_sum':sum(x['method_ids'] for x in inventory['dex']),'api_strings':len(api),'video_classes':len(classes),'schema_candidates':len(schema_candidates)},indent=2))

if __name__=='__main__': main()
