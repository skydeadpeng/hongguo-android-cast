#!/usr/bin/env python3
import argparse,pathlib,re,json
parser=argparse.ArgumentParser();parser.add_argument("work_dir",help="Directory containing dexdump outputs");args=parser.parse_args()
p=pathlib.Path(args.work_dir);out={}
for f in (p/'model-evidence').glob('*/*.txt'):
 if not re.search(r'(ContentType|VideoSeriesIdType|VideoPlatformType|DeviceLevel|VideoDetailSource|VideoErr)\.txt$',f.name):continue
 regs={};vals=[]
 for line in f.read_text().splitlines():
  if '|[' in line:regs={}
  m=re.search(r'const-string(?:/jumbo)? (v\d+), "([^"]*)"',line)
  if m:regs[m[1]]=m[2]
  m=re.search(r'const(?:/4|/16)? (v\d+), #int (-?\d+)',line)
  if m:regs[m[1]]=int(m[2])
  m=re.search(r'const (v\d+), #float .* // #([0-9a-f]+)',line)
  if m:regs[m[1]]=int(m[2],16)
  m=re.search(r'move(?:-object)?(?:/from16|/16)? (v\d+), (v\d+)',line)
  if m:regs[m[1]]=regs.get(m[2])
  if ';.<init>:(Ljava/lang/String;II)V' in line:
   args=re.search(r'\{([^}]+)\}',line)[1].split(', ')
   vals.append({'name':regs.get(args[1]),'ordinal':regs.get(args[2]),'value':regs.get(args[3]),'evidence':line.strip()})
 if vals:out[f.name[:-4]]={'dex':f.parent.name,'values':vals}
(p/'enum-contract-data.json').write_text(json.dumps(out,indent=2))
for k,v in out.items():print(k,[(i['name'],i['value']) for i in v['values']])
