#!/usr/bin/env python3
"""Offline metadata fixtures only. No media, Android execution or network requests."""
import copy
import importlib.util
import json
import runpy
import struct
import tempfile
import zlib
from pathlib import Path

ROOT=Path(__file__).parent
old=runpy.run_path(str(ROOT/'test-evidence-contract.py'))
contract=old['contract'];COMMIT=old['COMMIT'];LOG=old['LOG'];base=old['base']
spec=importlib.util.spec_from_file_location('batch',ROOT/'verify-ten-episodes.py')
batch=importlib.util.module_from_spec(spec);spec.loader.exec_module(batch)
checks=0

def fixture(index):
    r=copy.deepcopy(base)
    r.update(schema=2,scope='production_player_component_ten_episode_opening',acceptance_mode='ten_episode_opening_5s',required_advance_ms=5000,episode_index=index,vid=contract.TEN_VIDS[index-1],app_version_name='0.12.65')
    r['stages'][0]['evidence'].update(cold_playback_cache=False,target_playback_cache_absent=True,global_cache_empty=False)
    r['stages'][5]['stage']='continued_5s'
    r['stages'][5]['evidence'].update(position_ms=6200,position_advance_ms=5200,wall_elapsed_ms=5300,observations=20,surface_observations=5,frame_changes=4,max_no_advance_ms=900,max_frame_unchanged_ms=1400)
    return r

def png():
    def chunk(k,b):return struct.pack('>I',len(b))+k+b+struct.pack('>I',zlib.crc32(k+b)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',160,160,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(bytes((160*4+1)*160)))+chunk(b'IEND',b'')

with tempfile.TemporaryDirectory() as tmp:
    directory=Path(tmp)
    for name in ('first-frame.png','after-5s.png'): (directory/name).write_bytes(png())
    for index in range(1,11):
        result=contract.validate(fixture(index),LOG,directory,COMMIT,0,batch_index=index)
        assert result['episode_index']==index and result['continuous_30_seconds'] is False and result['required_advance_ms']==5000
        checks+=1
    def reject(change=lambda x:None,index=1,log=LOG):
        global checks
        r=fixture(1);change(r)
        try:contract.validate(r,log,directory,COMMIT,0,batch_index=index)
        except contract.InvalidEvidence:checks+=1
        else:raise AssertionError('invalid ten-episode metadata accepted')
    for index in [0,11,'1',True]:reject(index=index)
    reject(lambda r:r.update(episode_index=2))
    reject(lambda r:r.update(vid=contract.TEN_VIDS[1]))
    reject(lambda r:r.update(schema=1))
    reject(lambda r:r.update(required_advance_ms=30000))
    reject(lambda r:r.update(scope='production_player_component_not_browse_ui'))
    reject(lambda r:r.update(qa_commit='b'*40))
    reject(lambda r:r['stages'][0]['evidence'].update(target_playback_cache_absent=False))
    reject(lambda r:r['stages'][5].update(stage='continued_30s'))
    for key,value in [('wall_elapsed_ms',4999),('wall_elapsed_ms',15001),('position_advance_ms',4999),('observations',9),('surface_observations',3),('frame_changes',1),('buffering',True),('hud_dialog_present',True),('generation',2),('max_frame_unchanged_ms',5000)]:
        reject(lambda r,k=key,v=value:r['stages'][5]['evidence'].update({k:v}))
    reject(log='INSTRUMENTATION_RESULT: qa_status=failed\nINSTRUMENTATION_CODE: 0\n')
    (directory/'after-5s.png').unlink();reject();(directory/'after-5s.png').write_bytes(png())
    # Batch-only normal App opening proof is distinct from the guest full-file gate.
    app=fixture(1); first=app['stages'][4]['evidence']; last=app['stages'][5]['evidence']
    first.pop('full_mp4_gate'); first.pop('expected_duration_ms')
    first.update(source_category='production_app',official_guest_plan=False,cache_gate='production_app_cache_publication',
        duration_validation='decoder_reported_only',full_episode_verified=False,
        plan_binding='unique_registered_plan_and_protected_cache_identity',
        expected_width=first['video_width'],expected_height=first['video_height'])
    for row in (first,last):row.update(plan_identity_stable=True,production_cache=True,manual_source=False,remote_source=False,protected_cache_identity=True)
    for row in (app,last):
        row.pop('full_mp4_gate',None);row.pop('expected_duration_ms',None);row.pop('official_guest_plan',None)
        row.update(source_category='production_app',cache_gate='production_app_cache_publication',
            duration_validation='decoder_reported_only',full_episode_verified=False)
    last.update(plan_binding=first['plan_binding'],expected_width=first['expected_width'],expected_height=first['expected_height'])
    accepted=contract.validate(app,LOG,directory,COMMIT,0,batch_index=1)
    assert accepted['source_category']=='production_app' and not accepted['full_episode_verified'] and not accepted['guest_full_mp4_gate_verified'];checks+=1
    def reject_app(change):
        global checks
        r=copy.deepcopy(app);change(r)
        try:contract.validate(r,LOG,directory,COMMIT,0,batch_index=1)
        except contract.InvalidEvidence:checks+=1
        else:raise AssertionError('invalid App provenance accepted')
    for key,value in [('official_guest_plan',True),('cache_gate','unknown'),('plan_binding','unknown'),('duration_validation','full_file'),('full_episode_verified',True),('full_mp4_gate','production_guest_cache_publication_requires_verifier'),('expected_duration_ms',100000),('expected_width',1),('expected_height',0),('source_category','api')]:
        reject_app(lambda r,k=key,v=value:r['stages'][4]['evidence'].update({k:v}))
    for stage in (4,5):
        for key,value in [('plan_identity_stable',False),('protected_cache_identity',False),('production_cache',False),('manual_source',True),('remote_source',True)]:
            reject_app(lambda r,k=key,v=value,stage=stage:r['stages'][stage]['evidence'].update({k:v}))
    for location in ('report',4,5):
        for key,value in [('source_category','official_guest'),('cache_gate','guest'),('duration_validation','verified'),('full_episode_verified',True),('official_guest_plan',True),('full_mp4_gate','guest'),('expected_duration_ms',100000)]:
            reject_app(lambda r,k=key,v=value,location=location:(r if location=='report' else r['stages'][location]['evidence']).update({k:v}))
    reject_app(lambda r:r['stages'][5]['evidence'].update(plan_binding='captured_plan'))
    reject_app(lambda r:r['stages'][5]['evidence'].update(expected_width=1))
    # Legacy 30s remains guest-only even if every clock and screenshot were long enough.
    legacy=copy.deepcopy(app);legacy.update(schema=1,scope='production_player_component_not_browse_ui')
    legacy['stages'][5]['stage']='continued_30s'
    legacy['stages'][0]['evidence']['cold_playback_cache']=True
    legacy['stages'][5]['evidence'].update(position_ms=32000,position_advance_ms=31000,wall_elapsed_ms=31200,observations=100,surface_observations=28)
    (directory/'after-30s.png').write_bytes(png())
    try:contract.validate(legacy,LOG,directory,COMMIT,0)
    except contract.InvalidEvidence as e:assert str(e)=='guest_file_validation_not_observed';checks+=1
    else:raise AssertionError('legacy accepted App opening provenance')
    # Legacy cannot accept the shorter interval or use a batch result as its old pass.
    try:contract.validate(fixture(1),LOG,directory,COMMIT,0)
    except contract.InvalidEvidence:checks+=1
    else:raise AssertionError('legacy accepted 5-second batch report')
    # Partial batches, stale identities and missing attempts never become ten-pass.
    assert not batch.summary(directory,COMMIT,True);checks+=1
    for index in range(1,11):
        ep=directory/f'episode-{index:02}';ep.mkdir()
        row={'episode_index':index,'vid':contract.TEN_VIDS[index-1],'commit':COMMIT,'status':'PASSED','attempted':True,'five_seconds_verified':True,'first_frame_observed':True,'required_advance_ms':5000}
        (ep/'result.json').write_text(json.dumps(row))
    assert batch.summary(directory,COMMIT,True);checks+=1
    row['commit']='b'*40;(directory/'episode-10/result.json').write_text(json.dumps(row))
    assert not batch.summary(directory,COMMIT,True);checks+=1
    data=json.loads((directory/'ten-episode-summary.json').read_text());assert data['attempted_count']==9 and data['not_run_count']==1;checks+=1
    row['commit']=COMMIT;row.update(status='FAILED',five_seconds_verified=False)
    (directory/'episode-10/result.json').write_text(json.dumps(row))
    assert not batch.summary(directory,COMMIT,True);checks+=1
    data=json.loads((directory/'ten-episode-summary.json').read_text());assert data['attempted_count']==10 and data['failed_count']==1;checks+=1
print(f'TEN_EPISODE_EVIDENCE_CONTRACT_OK checks={checks} metadata_only=true android_execution=false network_requests=0')
