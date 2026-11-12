#!/usr/bin/env python3
"""Offline shell orchestration checks, with fake ADB; NOT device/playback evidence."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import contextlib
import io
import runpy

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
spec = importlib.util.spec_from_file_location('contract', HERE / 'verify-report.py')
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)
SHA = 'a' * 40
with contextlib.redirect_stdout(io.StringIO()):
    fixtures = runpy.run_path(str(HERE/'test-ten-evidence-contract.py'))
FAKE_ADB = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
root=pathlib.Path(os.environ['MOCK_ROOT']); args=sys.argv[1:]
# Real adb shell can read stdin even when its command does not need it.
drained=sys.stdin.read()
with (root/'calls.jsonl').open('a') as out:
    out.write(json.dumps({'args':args,'stdin_bytes':len(drained)})+'\n')
if args[2:5]==['shell','am','force-stop']:
    count_file=root/'stops'; n=int(count_file.read_text())+1 if count_file.exists() else 1
    count_file.write_text(str(n))
    if n==int(os.environ.get('MOCK_STOP_FAIL','0')):sys.exit(1)
elif args[2:5]==['shell','am','instrument']:
    i=int(args[args.index('episodeIndex')+1]); v=args[args.index('episodeVid')+1]
    if os.environ.get('MOCK_VALID')=='1':
        (root/'report.json').write_text((root/'valid-report.json').read_text())
        print((root/'valid-log.txt').read_text());sys.exit(int(os.environ.get('MOCK_INSTRUMENT_EXIT','0')))
    report={'schema':2,'scope':'production_player_component_ten_episode_opening',
        'qa_commit':os.environ['GITHUB_SHA'],'episode_index':i,'vid':v,
        'series_id':'7703410222160694296','status':'passed' if os.environ.get('MOCK_PULL_IMAGES')=='1' else 'failed','reason':'offline_mock_failure',
        'stages':[],'playback_diagnostic':{'status_category':'unknown'}}
    (root/'report.json').write_text(json.dumps(report))
    # Every fake run fails: prove a failed episode does not skip subsequent episodes.
    print('INSTRUMENTATION_CODE: 0');sys.exit(int(os.environ.get('MOCK_INSTRUMENT_EXIT','1')))
elif args[2:5]==['exec-out','run-as','io.github.jackotom.hongguo.android']:
    assert args[5]=='cat', args
    if args[6].endswith('/report.json'):sys.stdout.write((root/'report.json').read_text())
    else:
        assert args[6].endswith(('/first-frame.png','/after-5s.png')),args
        if os.environ.get('MOCK_VALID')=='1':sys.stdout.buffer.write((root/'valid.png').read_bytes())
    # Deliberately empty image pulls. They must fail validation, never become frames.
else:raise SystemExit('Unexpected fake command')
'''
checks = 0

def require(ok, message):
    global checks
    checks += 1
    if not ok:raise AssertionError(message)

def run(stop_fail, attempts, instrument_exit=1, pull_images=False, selection='all', valid=False):
    with tempfile.TemporaryDirectory(prefix='hongguo-offline-batch-') as temp:
        root=Path(temp); (root/'sdk/platform-tools').mkdir(parents=True)
        adb=root/'sdk/platform-tools/adb';adb.write_text(FAKE_ADB);adb.chmod(0o755)
        (root/'bin').mkdir(); sleep=root/'bin/sleep';sleep.write_text('#!/bin/sh\nexit 0\n');sleep.chmod(0o755)
        out=root/'out';out.mkdir();private=root/'private';private.mkdir()
        report=fixtures['fixture'](10);report['qa_commit']=SHA
        (root/'valid-report.json').write_text(json.dumps(report))
        (root/'valid-log.txt').write_text(fixtures['LOG'])
        (root/'valid.png').write_bytes(fixtures['png']())
        env={**os.environ,'ANDROID_HOME':str(root/'sdk'),'QA_OUT':str(out),'QA_PRIVATE':str(private),
             'GITHUB_SHA':SHA,'MOCK_ROOT':str(root),'MOCK_STOP_FAIL':str(stop_fail),
             'MOCK_INSTRUMENT_EXIT':str(instrument_exit),'MOCK_PULL_IMAGES':'1' if pull_images else '0',
             'QA_EPISODE_SELECTION':selection,'MOCK_VALID':'1' if valid else '0',
             'PATH':str(root/'bin')+os.pathsep+os.environ['PATH'],'PYTHONDONTWRITEBYTECODE':'1'}
        result=subprocess.run(['bash',str(HERE/'run-ten-episodes.sh')],cwd=ROOT,env=env,
                              input='not an episode manifest\n',text=True,capture_output=True,timeout=40)
        success=valid and not stop_fail and instrument_exit==0
        require(result.returncode==(0 if success else 1), 'only valid evidence and confirmed stop pass')
        calls=[json.loads(line) for line in (root/'calls.jsonl').read_text().splitlines()] if (root/'calls.jsonl').exists() else []
        if selection not in ('all','tenth-only'):
            require(not calls,'unknown selection rejected before any ADB action')
            require(not (out/'ten-episode-summary.json').exists(),'unknown selection creates no misleading summary')
            return
        require(all(row['stdin_bytes']==0 for row in calls),'all adb children must receive EOF, not the manifest/caller stdin')
        instrument=[row['args'] for row in calls if row['args'][2:5]==['shell','am','instrument']]
        require(len(instrument)==attempts,'exact expected instrument invocation count')
        pulls=[row for row in calls if row['args'][-1].endswith(('/first-frame.png','/after-5s.png'))]
        require(len(pulls)==(2*attempts if pull_images or valid else 0),'image-pull stdin paths covered only when requested')
        for index,args in enumerate(instrument,10 if selection=='tenth-only' else 1):
            require(args[args.index('episodeIndex')+1]==str(index),'contiguous unique index')
            require(args[args.index('episodeVid')+1]==contract.TEN_VIDS[index-1],'fixed identity match')
        summary=json.loads((out/'ten-episode-summary.json').read_text())
        require(summary['attempted_count']==attempts,'exact attempted summary')
        passed=attempts if valid and instrument_exit==0 else 0
        require(summary['failed_count']==attempts-passed,'failed episodes retained')
        require(summary['not_run_count']==10-attempts,'remaining episodes not falsely attempted')
        require(summary['passed_count']==passed and not summary['all_ten_opened_and_advanced'],'subset cannot claim ten-pass')
        require(len(summary['episodes'])==10,'complete ten-row report even on stop failure')
        require(bool(list(out.rglob('*.png')))==bool(passed),'only accepted fixture evidence retains images')
        require(('QA_BATCH_STOP=' in result.stdout)==bool(stop_fail),'stop failure explicitly reported')
        require('QA_ACCEPTANCE=' not in result.stdout,'failure has no success marker')
        require(('QA_SUBSET_ACCEPTANCE=' in result.stdout)==success,'subset success marker precisely gated')
        require(summary['mode']==selection,'explicit summary selection')
        if selection=='tenth-only':
            require(summary['retest_indices']==[10] and summary['requested_count']==1,'subset scope explicit')
            require(all(row['status']=='NOT_RUN' for row in summary['episodes'][:9]),'other nine not attempted this run')
        require(summary['host_stop_confirmed']==(not bool(stop_fail)),'stop failure gates acceptance')

run(0,10)
run(0,10,instrument_exit=124)  # A host timeout must still stop the process before continuing.
run(0,10,pull_images=True)  # Exercise image pull paths without manufacturing images.
run(3,1)  # The second episode cannot confirm the previous QA process is stopped.
run(6,3)  # The third attempt cannot confirm its own process stopped; do not overlap.
run(0,1,selection='tenth-only')
run(0,1,selection='tenth-only',instrument_exit=124)
run(1,0,selection='tenth-only')
run(2,1,selection='tenth-only')
run(0,1,selection='tenth-only',valid=True,instrument_exit=0)
run(2,1,selection='tenth-only',valid=True,instrument_exit=0)
run(0,1,selection='tenth-only',valid=True,instrument_exit=124)
run(0,0,selection='episode-9')
print(f'Ten-episode offline orchestration: {checks} checks passed; zero devices/media/network')
