#!/usr/bin/env python3
"""Extract named class disassembly with the installed official Android SDK dexdump."""
import argparse,pathlib,subprocess,zipfile
p=argparse.ArgumentParser();p.add_argument('apk');p.add_argument('dex');p.add_argument('class_name');p.add_argument('dexdump');p.add_argument('output');a=p.parse_args()
out=pathlib.Path(a.output);out.parent.mkdir(parents=True,exist_ok=True);tmp=out.with_suffix('.input.dex')
tmp.write_bytes(zipfile.ZipFile(a.apk).read(a.dex));proc=subprocess.Popen([a.dexdump,'-d',str(tmp)],stdout=subprocess.PIPE,text=True,errors='replace')
keep=False;lines=[]
for line in proc.stdout:
 if 'Class descriptor' in line:keep=line.split("'")[1]==a.class_name
 if keep:lines.append(line)
status=proc.wait();tmp.unlink()
if status: raise SystemExit(status)
if not lines: raise SystemExit('Requested class was not found')
out.write_text(''.join(lines));print(f'{out}: {len(lines)} lines, dexdump={status}')
