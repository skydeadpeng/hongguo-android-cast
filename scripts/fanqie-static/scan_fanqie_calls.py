#!/usr/bin/env python3
"""Static dexdump call edges to selected video RPC/player methods, no APK execution."""
import argparse,json,pathlib,re,subprocess,zipfile
TARGET=re.compile(r'(?i)([mg]et.*(?:video|playlet|album|search).*(?:RxJava|Detail|Model|Feed)|setVideoModel|setDataSource|setVideoID|setVideoId|setDirectURL|setDirectUrl|VideoModel;\.<init>|VideoInfo;\.<init>)')
OWNER=re.compile(r'L(?:com/dragon/read/(?:component/shortvideo|pages/video|shortvideo)|seriessdk/)')
def main():
 p=argparse.ArgumentParser();p.add_argument('apk');p.add_argument('out');p.add_argument('dexdump');p.add_argument('dex',nargs='+');a=p.parse_args()
 out=pathlib.Path(a.out);out.mkdir(exist_ok=True,parents=True);z=zipfile.ZipFile(a.apk);rows=[]
 for name in a.dex:
  f=out/name;f.write_bytes(z.read(name));proc=subprocess.Popen([a.dexdump,'-d',str(f)],stdout=subprocess.PIPE,text=True,errors='replace')
  cls='';method='';hits=[];const=[]
  def flush():
   if hits:rows.append({'dex':name,'class':cls,'method':method,'call_edges':hits,'safe_constants':const})
  for line in proc.stdout:
   if 'Class descriptor' in line:
    flush();hits=[];const=[];cls=line.split("'")[1];method=''
   elif line.startswith('      name'):
    flush();hits=[];const=[];method=line.split("'")[1]
   elif True:
    if 'invoke-' in line and TARGET.search(line) and (OWNER.search(cls) or 'RxJava' in line):hits.append(line.strip())
    if 'const-string' in line:
     m=re.search(r'"([^"\n]{1,200})"',line)
     if m and re.search(r'(?i)(video|playlet|album|feed|timeout|detail|request)',m.group(1)) and not re.search(r'(?i)(key|token|secret|signature|spade)',m.group(1)):const.append(m.group(1))
  flush();status=proc.wait();f.unlink()
  if status:raise SystemExit(status)
 (out/'video-call-edges.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2));print(json.dumps({'matched_methods':len(rows)},indent=2))
if __name__=='__main__':main()
