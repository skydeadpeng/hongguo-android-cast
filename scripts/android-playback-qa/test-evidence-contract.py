#!/usr/bin/env python3
"""Metadata-only acceptance-rule regression. NEVER device or media playback evidence."""
import copy
import importlib.util
import struct
import tempfile
import zlib
from pathlib import Path

spec = importlib.util.spec_from_file_location('contract', Path(__file__).with_name('verify-report.py'))
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)
COMMIT = 'a' * 40
LOG = 'INSTRUMENTATION_RESULT: qa_status=passed\nINSTRUMENTATION_CODE: -1\n'
renderer = {'proof':'actual_video_surface_pixelcopy_buffer','pixelcopy_success':True,'luma_range':150,'nonblack_permille':900,'frame_hash':'abcdef'}
first = {'position_ms':1000,'duration_ms':139034,'video_width':360,'video_height':640,'generation':1,'playing':True,'same_episode':True,'buffering':False,'music_volume':0,'music_stream_muted':True,'renderer':renderer,'official_guest_plan':True,'full_mp4_gate':'production_guest_cache_publication_requires_verifier','expected_duration_ms':139034}
last = copy.deepcopy(first)
last.update(position_ms=31500,wall_elapsed_ms=30500,position_advance_ms=30500,observations=120,max_no_advance_ms=500,same_episode_and_player=True,surface_observations=30,frame_changes=29,max_frame_unchanged_ms=1100)
last['renderer']['frame_hash']='fedcba'
evidence=[{'isolated_avd':True,'debug_target':True,'cold_playback_cache':True},
          {'music_volume':0,'music_stream_muted':True},{'production_page_ready':True},
          {'production_intent_builder':True,'url_supplied':False,'play_info_supplied':False},first,last]
names=['environment','mute_before_launch','main_activity','player_launch','first_rendered_frame','continued_30s']
base={'status':'passed','qa_commit':COMMIT,'scope':'production_player_component_not_browse_ui','series_id':'7703410222160694296','vid':'7703412149342719001','episode_index':1,'source_selection':'production_resolve_in_player_no_url','quality':'auto','speed':1,'autoplay':False,'queue_length':1,'stages':[{'stage':name,'status':'passed','evidence':data} for name,data in zip(names,evidence)]}
checks=0
with tempfile.TemporaryDirectory() as temporary:
    directory=Path(temporary)
    # Valid PNG structure fixtures only, NEVER renderer output or replacement video.
    def chunk(kind, data):
        return struct.pack('>I', len(data))+kind+data+struct.pack('>I', zlib.crc32(kind+data)&0xffffffff)
    header = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB',160,160,8,6,0,0,0))
              + chunk(b'IDAT',zlib.compress(b'\0' * ((160*4+1)*160))) + chunk(b'IEND',b''))
    for name in ['first-frame.png','after-30s.png']:(directory/name).write_bytes(header)
    assert contract.validate(base,LOG,directory,COMMIT,0)['status']=='PASSED';checks+=1
    def rejected(change=lambda value:None,log=LOG,sha=COMMIT,exit_code=0):
        global checks
        value=copy.deepcopy(base);change(value)
        try:contract.validate(value,log,directory,sha,exit_code)
        except contract.InvalidEvidence:checks+=1
        else:raise AssertionError('Invalid fixture was accepted')
    rejected(lambda r:r.update(status='blocked'))
    rejected(lambda r:r.update(qa_commit='b'*40))
    rejected(lambda r:r.update(vid='wrong'))
    rejected(lambda r:r.update(autoplay=True))
    rejected(lambda r:r.update(source_selection='injected'))
    rejected(lambda r:r['stages'].pop())
    rejected(lambda r:r['stages'][1]['evidence'].update(music_volume=5))
    rejected(lambda r:r['stages'][3]['evidence'].update(url_supplied=True))
    for field,value in [('playing',False),('buffering',True),('official_guest_plan',False),('duration_ms',0)]:
        rejected(lambda r,f=field,v=value:r['stages'][4]['evidence'].update({f:v}))
    rejected(lambda r:r['stages'][4]['evidence']['renderer'].update(pixelcopy_success=False))
    rejected(lambda r:r['stages'][4]['evidence']['renderer'].update(luma_range=0))
    for field,value in [('generation',2),('position_advance_ms',29000),('wall_elapsed_ms',29000),('observations',2),('max_no_advance_ms',6000),('same_episode_and_player',False),('surface_observations',2),('frame_changes',0),('max_frame_unchanged_ms',6000)]:
        rejected(lambda r,f=field,v=value:r['stages'][5]['evidence'].update({f:v}))
    rejected(lambda r:r['stages'][5]['evidence']['renderer'].update(frame_hash='abcdef'))
    rejected(log='INSTRUMENTATION_CODE: 0\n')
    rejected(log=LOG.replace('qa_status=passed','qa_status=failed'))
    rejected(log=LOG+'FAILURES!!!\n')
    rejected(log=LOG+LOG)
    rejected(exit_code=124)
    for invalid in [header[:-1], header[:-12], header+b'extra', header[:30]+bytes([header[30]^1])+header[31:]]:
        (directory/'after-30s.png').write_bytes(invalid);rejected()
    (directory/'after-30s.png').unlink();rejected()
print(f'EVIDENCE_CONTRACT_OK checks={checks} metadata_fixtures_only=true android_execution=false real_media=false')
