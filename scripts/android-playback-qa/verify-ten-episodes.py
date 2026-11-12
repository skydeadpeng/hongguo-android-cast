#!/usr/bin/env python3
"""Ten fixed episode opening checks; never treats a partial batch as all-pass."""
import importlib.util
import json
import re
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location('contract', Path(__file__).with_name('verify-report.py'))
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)
CATEGORIES = {'none','unknown','guest_identity_mismatch','login_required','guest_http_401','guest_http_403',
    'guest_http_302','guest_http_other','guest_permission_not_explicit','guest_public_playback_denied',
    'guest_request_timeout','guest_request_incomplete_unknown_cause','guest_format_invalid',
    'guest_expired_or_invalid_expiry','guest_declared_metadata_invalid','guest_complete_public_source_unconfirmed',
    'guest_full_file_validation_failed','guest_media_address_rejected','guest_media_service_unsupported',
    'guest_provenance_recheck_required','source_http_error','network_dns','network_tls','network_socket_timeout',
    'decoder_error','decoder_prepare_timeout','buffering_timeout',
    'app_device_registration_incomplete','app_empty_response','app_lookup_timeout','app_model_or_source_missing',
    'app_model_response_invalid','app_network_request_failed_unknown_cause','app_playback_business_denied',
    'app_playback_signing_unavailable','app_preview_only','app_quality_or_codec_unavailable',
    'app_registration_or_lookup_incomplete_unknown_cause','app_source_http_error','app_source_identity_mismatch',
    'app_source_security_validation_failed','app_source_unavailable_unknown_cause','source_request_cancelled',
    'web_episode_unavailable','web_page_data_invalid','web_page_identity_mismatch','web_supported_auth_flow_required'}


def strict(path):
    return json.loads(path.read_text(), parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite')))


def episode(directory, commit, index, exit_code, private_log):
    report = None
    result = {'episode_index':index,'vid':contract.TEN_VIDS[index-1],'commit':commit,
              'status':'FAILED','attempted':True,'first_frame_observed':False,'five_seconds_verified':False}
    for name in ('first-frame.png','after-5s.png'):
        path = directory / name
        if path.exists() and not contract.png_exists(path):
            path.unlink()
    try:
        report = strict(directory / 'report.json')
        if not isinstance(report, dict):
            raise ValueError('not_object')
        verified = contract.validate(report, private_log.read_text(errors='replace'), directory, commit, exit_code, batch_index=index)
        result.update(verified, attempted=True, first_frame_observed=True, five_seconds_verified=True)
    except contract.InvalidEvidence as error:
        result['reason'] = str(error)
    except Exception:
        result['reason'] = 'missing_or_malformed_runtime_evidence'
        # A remote cat diagnostic must never be uploaded as an App JSON report.
        if report is None or not isinstance(report, dict):
            (directory / 'report.json').unlink(missing_ok=True)
    identity_ok = (isinstance(report, dict) and report.get('qa_commit') == commit
        and report.get('episode_index') == index and report.get('vid') == contract.TEN_VIDS[index-1]
        and report.get('series_id') == '7703410222160694296'
        and report.get('scope') == 'production_player_component_ten_episode_opening')
    if identity_ok:
        result['app_version'] = report.get('app_version_name') if re.fullmatch(r'[0-9.]{1,32}', str(report.get('app_version_name',''))) else 'unknown'
        # This is a clearly named partial observation, NOT a passing episode result.
        stages = report.get('stages') if isinstance(report.get('stages'),list) else []
        result['first_frame_observed'] = report.get('actual_first_frame_observed') is True or any(
            isinstance(row,dict) and row.get('stage')=='first_rendered_frame' and row.get('status')=='passed'
            for row in stages)
        diagnostic = report.get('playback_diagnostic', {})
        category = diagnostic.get('status_category') if isinstance(diagnostic,dict) else None
        result['error_category'] = category if category in CATEGORIES else 'unknown'
        result['guest_plan_observed'] = report.get('guest_plan_observed') is True
    else:
        result['error_category'] = 'unknown'
    if result['status'] != 'PASSED':
        for name in ('first-frame.png','after-5s.png'):
            (directory/name).unlink(missing_ok=True)
    (directory / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
    print('QA_EPISODE_RESULT=' + json.dumps(result, ensure_ascii=False, separators=(',',':')))


def summary(directory, commit, finished, selection='all'):
    if selection not in ('all', 'tenth-only'):
        raise ValueError('Unknown fixed episode selection')
    subset = selection == 'tenth-only'
    requested = 1 if subset else 10
    rows=[]
    for index,vid in enumerate(contract.TEN_VIDS,1):
        try:
            if subset and index != 10:
                raise ValueError('outside_current_subset')
            row=strict(directory/f'episode-{index:02}'/'result.json')
            if row.get('episode_index')!=index or row.get('vid')!=vid or row.get('commit')!=commit:
                raise ValueError('identity')
        except Exception:
            row={'episode_index':index,'vid':vid,'commit':commit,'status':'NOT_RUN','attempted':False,
                 'first_frame_observed':False,'five_seconds_verified':False,'reason':'no_matching_attempt_result'}
        rows.append(row)
    passed=sum(row.get('attempted') is True and row.get('status')=='PASSED' and row.get('five_seconds_verified') is True and row.get('required_advance_ms')==5000 and row.get('first_frame_observed') is True for row in rows)
    attempted=sum(row.get('attempted') is True for row in rows)
    host_stop_confirmed = not (directory/'host-stop-failed').exists()
    success = passed == requested and attempted == requested and host_stop_confirmed
    all_ten = finished and not subset and success
    data={'schema':1,'commit':commit,'series_id':'7703410222160694296','title':'都说了，别惹那个乞丐',
          'scope':'first_ten_episodes_native_opening_and_5_seconds_not_full_episodes',
          'mode':selection,'retest_indices':[10] if subset else [],'host_stop_confirmed':host_stop_confirmed,
          'requested_count':requested,'attempted_count':attempted,'passed_count':passed,
          'first_frame_observed_count':sum(row.get('first_frame_observed') is True for row in rows),
          'failed_count':attempted-passed,
          'not_run_count':10-attempted,'status':'PASSED' if finished and success else 'COMPLETE_WITH_FAILURES' if finished else 'IN_PROGRESS',
          'all_ten_opened_and_advanced':all_ten,'episodes':rows}
    (directory/'ten-episode-summary.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    if finished:
        state={'status':'PASSED' if success else 'FAILED','commit':commit,
               'reason':('tenth_episode_subset_verified' if subset else 'all_ten_verified') if success else 'one_or_more_episodes_or_host_stop_not_verified',
               'mode':selection,'retest_indices':[10] if subset else [],'host_stop_confirmed':host_stop_confirmed,
               'requested_count':requested,'attempted_count':attempted,'passed_count':passed,
          'first_frame_observed_count':sum(row.get('first_frame_observed') is True for row in rows),'all_ten_opened_and_advanced':all_ten}
        (directory/'workflow-status.json').write_text(json.dumps(state,indent=2)+'\n')
    print(f'QA_BATCH_COUNTS attempted={attempted} passed={passed} requested={requested} mode={selection} finished={str(finished).lower()}')
    return success


def main():
    mode=sys.argv[1]
    if mode=='--list':
        for i,vid in enumerate(contract.TEN_VIDS,1):print(f'{i}\t{vid}')
    elif mode=='--should-pull-images':
        try:
            r=strict(Path(sys.argv[2])/'report.json');index=int(sys.argv[4])
            ok=(r.get('status')=='passed' and r.get('qa_commit')==sys.argv[3]
                and r.get('schema')==2 and r.get('episode_index')==index
                and r.get('vid')==contract.TEN_VIDS[index-1] and r.get('series_id')=='7703410222160694296')
        except Exception:ok=False
        if not ok:raise SystemExit(1)
    elif mode=='--episode':
        episode(Path(sys.argv[2]),sys.argv[3],int(sys.argv[4]),int(sys.argv[5]),Path(sys.argv[6]))
    elif mode in ('--summary','--finish','--summary-tenth-subset','--finish-tenth-subset'):
        finished = mode in ('--finish', '--finish-tenth-subset')
        selection = 'tenth-only' if mode.endswith('-tenth-subset') else 'all'
        if not summary(Path(sys.argv[2]),sys.argv[3],finished,selection) and finished:raise SystemExit(1)
    else:raise SystemExit('Unknown fixed QA mode')

if __name__=='__main__':main()
