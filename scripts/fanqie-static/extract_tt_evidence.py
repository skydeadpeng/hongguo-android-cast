#!/usr/bin/env python3
"""Extract only static TTVideoEngine model class disassembly; never executes APK."""
import argparse,pathlib,re
parser=argparse.ArgumentParser();parser.add_argument("work_dir",help="Directory containing dexdump outputs");args=parser.parse_args()
p=pathlib.Path(args.work_dir)
for b in re.split(r'(?m)^Class #',(p/'classes14-dump.txt').read_text(errors='replace')):
 m=re.search(r"Class descriptor\s+: 'Lcom/ss/ttvideoengine/model/(VideoModel|VideoRef|VideoInfo);'",b)
 if m:(p/(m[1]+'-tt-evidence.txt')).write_text(b)
