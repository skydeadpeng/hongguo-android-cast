#!/usr/bin/env python3
"""Read dexdump -a -d output; extract model evidence and descriptor wire fields."""
import argparse,pathlib,re,json
parser=argparse.ArgumentParser();parser.add_argument("work_dir",help="Directory containing dexdump outputs");args=parser.parse_args()
P=pathlib.Path(args.work_dir)
names={'EpisodeLockData','VideoErr','VideoDetailInfo','VideoAlbumDetailInfo','SaaSVideoModelData','SaaSVideoDetailData','SaaSVideoAlbumDetailData','VideoSeriesIdType','VideoPlatformType','DeviceLevel','VideoDetailSource','MGetVideoModelData','GetVideoAlbumDetailRequest','GetVideoAlbumDetailResponse','GetVideoDetailRequest','GetVideoDetailResponse','GetVideoModelRequest','GetVideoModelResponse','GetVideoBizParam','MGetVideoModelV2Request','MGetVideoModelResponse','ContentType','VideoContentType','VideoIdType','VideoIDType','VideoModel','VideoDetail','VideoAlbumDetail','VideoData','VideoModelData'}
out={}
for dex in (9,16,17):
 for b in re.split(r'(?m)^Class #',(P/f'classes{dex}-dump.txt').read_text(errors='replace')):
  m=re.search(r"Class descriptor\s+: 'L([^;]+);'",b)
  if not m:continue
  cls=m[1];short=cls.rsplit('/',1)[-1]
  if '/rpc/model/' not in cls or short.split('$')[0] not in names:continue
  dest=P/'model-evidence'/f'classes{dex}'/(cls.replace('/','.')+'.txt');dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(b)
  obj={'dex':dex,'evidence':str(dest),'fields':[],'wire_fields':[]}
  inst=b.split('  Instance fields   -')[-1].split('  Direct methods')[0]
  obj['fields']=[dict(name=n,type=t) for n,t in re.findall(r"name\s+: '([^']+)'\n\s+type\s+: '([^']+)'",inst)]
  regs={}
  for line in b.splitlines():
   if '|[' in line:regs={}
   mm=re.search(r'const-string(?:/jumbo)? (v\d+), "([^"]*)"',line)
   if mm:regs[mm[1]]=mm[2]
   mm=re.search(r'const(?:/4|/16)? (v\d+), #int (-?\d+)',line)
   if mm:regs[mm[1]]=int(mm[2])
   if ';.addElement:' in line:
    a=re.search(r'\{([^}]+)\}',line)[1].split(', ')
    obj['wire_fields'].append({'name':regs.get(a[1]),'optional':bool(regs[a[2]]) if a[2] in regs else None,'evidence':line.strip()})
  out[cls]=obj
(P/'model-contract-data.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
for k,v in out.items():
 if v['wire_fields']:print(k,[(i['name'],i['optional']) for i in v['wire_fields']])
