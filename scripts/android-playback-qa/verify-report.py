#!/usr/bin/env python3
"""Validate real-run evidence, not a command exit, as playback acceptance.

This is an evidence contract check. Only the isolated Android runner can produce
real renderer/MediaPlayer measurements; local fixture tests are never playback.
"""
import json
import re
import struct
import sys
import zlib
from pathlib import Path


class InvalidEvidence(Exception):
    pass


def need(condition, code):
    if not condition:
        raise InvalidEvidence(code)


def numeric(value):
    return type(value) in (int, float) and float('-inf') < value < float('inf')


def png_exists(path):
    """Bounded PNG structural/CRC/decompression check, not proof of image provenance."""
    try:
        if not path.is_file() or not 67 <= path.stat().st_size <= 16 * 1024 * 1024:
            return False
        data = path.read_bytes()
        if data[:8] != b'\x89PNG\r\n\x1a\n':
            return False
        offset, expected, width, channels, height = 8, None, 0, 0, 0
        compressed = bytearray()
        ended = False
        while offset < len(data):
            if offset + 12 > len(data):
                return False
            length = struct.unpack('>I', data[offset:offset+4])[0]
            kind = data[offset+4:offset+8]
            if offset + 12 + length > len(data):
                return False
            body = data[offset+8:offset+8+length]
            crc = struct.unpack('>I', data[offset+8+length:offset+12+length])[0]
            if (zlib.crc32(kind + body) & 0xffffffff) != crc:
                return False
            if offset == 8 and kind != b'IHDR':
                return False
            if kind == b'IHDR':
                if offset != 8 or length != 13:
                    return False
                width, height, depth, color, compression, filtering, interlace = struct.unpack('>IIBBBBB', body)
                if not (160 <= width <= 8192 and 160 <= height <= 8192 and width * height <= 16000000):
                    return False
                if depth != 8 or color not in (2, 6) or compression or filtering or interlace:
                    return False
                channels = 3 if color == 2 else 4
                expected = (width * channels + 1) * height
            elif kind == b'IDAT':
                compressed.extend(body)
            elif kind == b'IEND':
                if length or offset + 12 != len(data):
                    return False
                ended = True
            offset += 12 + length
        if not ended or not compressed or expected is None:
            return False
        decoder = zlib.decompressobj()
        raw = decoder.decompress(bytes(compressed), expected + 1)
        if len(raw) != expected or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
            return False
        stride = width * channels + 1
        return all(raw[row * stride] <= 4 for row in range(height))
    except (OSError, ValueError, struct.error, zlib.error):
        return False


TEN_VIDS = ['7703412149342719001','7703412063502093337','7703412110843186201',
            '7703412039816842265','7703412145559440409','7703412101368269849',
            '7703412128635423769','7703412191394810905','7703412032455855129','7703412076667997209']


def validate(report, instrumentation, directory, commit, command_exit, batch_index=None):
    batch = batch_index is not None
    need(not batch or type(batch_index) is int and 1 <= batch_index <= 10, 'invalid_batch_index')
    index = batch_index if batch else 1
    minimum = 5000 if batch else 30000
    maximum = 15000 if batch else 45000
    scope = 'production_player_component_ten_episode_opening' if batch else 'production_player_component_not_browse_ui'
    end_stage = 'continued_5s' if batch else 'continued_30s'
    end_image = 'after-5s.png' if batch else 'after-30s.png'
    if batch:
        need(report.get('schema') == 2 and report.get('acceptance_mode') == 'ten_episode_opening_5s' and report.get('required_advance_ms') == 5000, 'wrong_batch_acceptance_contract')
    need(command_exit == 0, 'instrumentation_command_failed_or_timed_out')
    need(re.fullmatch('[0-9a-f]{40}', commit) is not None, 'invalid_commit_identity')
    need(report.get('status') == 'passed', 'android_report_did_not_pass')
    need(report.get('qa_commit') == commit, 'report_commit_mismatch')
    final = re.findall(r'^INSTRUMENTATION_RESULT: qa_status=(\w+)\s*$', instrumentation, re.M)
    codes = re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', instrumentation, re.M)
    need(final == ['passed'] and codes == ['-1'] and 'FAILURES!!!' not in instrumentation,
         'instrumentation_final_result_did_not_pass')
    need(report.get('scope') == scope, 'unexpected_acceptance_scope')
    need(report.get('series_id') == '7703410222160694296' and report.get('vid') == TEN_VIDS[index-1] and type(report.get('episode_index')) is int and report.get('episode_index') == index, 'wrong_episode')
    need(report.get('source_selection') == 'production_resolve_in_player_no_url' and report.get('quality') == 'auto' and report.get('speed') == 1 and report.get('autoplay') is False and report.get('queue_length') == 1, 'playback_configuration_mismatch')
    stages = report.get('stages')
    names = ['environment', 'mute_before_launch', 'main_activity', 'player_launch', 'first_rendered_frame', end_stage]
    need(type(stages) is list and [row.get('stage') for row in stages] == names and all(row.get('status') == 'passed' for row in stages), 'required_stages_not_passed')
    env, mute, host, launch, first, last = [row.get('evidence', {}) for row in stages]
    need(env.get('isolated_avd') is True and env.get('debug_target') is True and (env.get('target_playback_cache_absent') is True if batch else env.get('cold_playback_cache') is True), 'not_isolated_cold_debug_test')
    need(host.get('production_page_ready') is True, 'production_main_not_ready')
    need(launch.get('production_intent_builder') is True and launch.get('url_supplied') is False and launch.get('play_info_supplied') is False, 'nonproduction_source_injection')
    need(mute.get('music_volume') == 0 and mute.get('music_stream_muted') is True, 'mute_before_launch_not_verified')
    for evidence in [first, last]:
        need(evidence.get('hud_dialog_present') is False and evidence.get('terminal_dialog_state') is False, 'dialog_during_verified_playback')
        need(evidence.get('playing') is True and evidence.get('same_episode') is True and evidence.get('buffering') is False, 'player_not_continuously_healthy')
        need(evidence.get('music_volume') == 0 and evidence.get('music_stream_muted') is True, 'media_output_not_muted')
        for key in ['position_ms', 'duration_ms', 'video_width', 'video_height', 'generation']:
            need(numeric(evidence.get(key)) and evidence[key] > 0, 'missing_actual_player_measurement')
        renderer = evidence.get('renderer', {})
        need(renderer.get('proof') == 'actual_video_surface_pixelcopy_buffer' and renderer.get('pixelcopy_success') is True, 'no_real_renderer_buffer')
        need(numeric(renderer.get('luma_range')) and renderer['luma_range'] >= 16 and numeric(renderer.get('nonblack_permille')) and renderer['nonblack_permille'] >= 20, 'blank_renderer_surface')
        need(re.fullmatch('[0-9a-f]{1,16}', renderer.get('frame_hash', '')) is not None, 'missing_renderer_hash')
    app_opening = batch and first.get('source_category') == 'production_app'
    if app_opening:
        need(first.get('official_guest_plan') is False and first.get('cache_gate') == 'production_app_cache_publication'
             and first.get('duration_validation') == 'decoder_reported_only'
             and first.get('plan_binding') == 'unique_registered_plan_and_protected_cache_identity'
             and first.get('full_episode_verified') is False and 'full_mp4_gate' not in first
             and 'expected_duration_ms' not in first, 'app_opening_must_not_claim_guest_or_full_episode_verification')
        for evidence in [report, first, last]:
            need(evidence.get('source_category') == 'production_app'
                 and evidence.get('cache_gate') == 'production_app_cache_publication'
                 and evidence.get('duration_validation') == 'decoder_reported_only'
                 and evidence.get('full_episode_verified') is False
                 and evidence.get('official_guest_plan') is not True
                 and 'full_mp4_gate' not in evidence and 'expected_duration_ms' not in evidence,
                 'contradictory_app_source_or_full_episode_claim')
        need(last.get('plan_binding') == first.get('plan_binding')
             and last.get('expected_width') == first.get('expected_width')
             and last.get('expected_height') == first.get('expected_height'), 'app_plan_metadata_not_retained')
        need(numeric(first.get('expected_width')) and first['expected_width'] > 0
             and numeric(first.get('expected_height')) and first['expected_height'] > 0
             and first['video_width'] == first['expected_width'] and first['video_height'] == first['expected_height'], 'app_plan_decoded_dimensions_mismatch')
        for evidence in [first, last]:
            need(evidence.get('plan_identity_stable') is True and evidence.get('production_cache') is True
                 and evidence.get('manual_source') is False and evidence.get('remote_source') is False
                 and evidence.get('protected_cache_identity') is True,
                 'app_plan_or_cache_provenance_not_retained')
    else:
        need(first.get('official_guest_plan') is True and first.get('full_mp4_gate') == 'production_guest_cache_publication_requires_verifier', 'guest_file_validation_not_observed')
        need(numeric(first.get('expected_duration_ms')) and abs(first['duration_ms'] - first['expected_duration_ms']) <= max(2000, first['expected_duration_ms'] / 50), 'media_duration_mismatch')
    need(all(first[k] == last[k] for k in ['duration_ms', 'video_width', 'video_height', 'generation']), 'player_changed_after_first_frame')
    need(last.get('same_episode_and_player') is True, 'player_identity_not_retained')
    need(numeric(last.get('wall_elapsed_ms')) and minimum <= last['wall_elapsed_ms'] <= maximum and numeric(last.get('position_advance_ms')) and last['position_advance_ms'] >= minimum, 'required_playback_interval_not_met')
    need(last['position_ms'] - first['position_ms'] == last['position_advance_ms'] and last['position_ms'] <= last['duration_ms'], 'position_measurements_inconsistent')
    need(numeric(last.get('observations')) and last['observations'] >= (10 if batch else 50) and numeric(last.get('max_no_advance_ms')) and last['max_no_advance_ms'] < 5000, 'continuous_observations_inadequate')
    need(numeric(last.get('surface_observations')) and last['surface_observations'] >= (4 if batch else 20) and numeric(last.get('frame_changes')) and last['frame_changes'] >= 2 and numeric(last.get('max_frame_unchanged_ms')) and last['max_frame_unchanged_ms'] < 5000, 'continuous_video_frames_not_verified')
    need(first['renderer']['frame_hash'] != last['renderer']['frame_hash'], 'renderer_image_did_not_change')
    need(png_exists(directory / 'first-frame.png') and png_exists(directory / end_image), 'app_screenshots_missing_or_invalid')
    return {'status':'PASSED', 'commit':commit, 'scope':report['scope'], 'first_frame':True,
            'continuous_30_seconds':not batch, 'required_advance_ms':minimum,
            'source_category':'production_app' if app_opening else 'official_guest',
            'full_episode_verified':False, 'guest_full_mp4_gate_verified':not app_opening,
            'episode_index':index, 'vid':TEN_VIDS[index-1], 'duration_ms':first['duration_ms'],
            'video_width':first['video_width'], 'video_height':first['video_height'],
            'wall_elapsed_ms':last['wall_elapsed_ms'], 'position_advance_ms':last['position_advance_ms'],
            'muted':True, 'media_url_injected':False, 'network_settings_changed':False}


def main():
    directory, commit, command_exit, private_log = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3]), Path(sys.argv[4])
    try:
        report = json.loads((directory / 'report.json').read_text(), parse_constant=lambda value: (_ for _ in ()).throw(ValueError('nonfinite')))
        instrumentation = private_log.read_text(errors='replace')
        result = validate(report, instrumentation, directory, commit, command_exit)
    except InvalidEvidence as error:
        result = {'status':'FAILED', 'reason':str(error), 'commit':commit, 'accepted_as_playback_success':False}
    except Exception:
        result = {'status':'FAILED', 'reason':'missing_or_malformed_runtime_evidence', 'commit':commit, 'accepted_as_playback_success':False}
    (directory / 'workflow-status.json').write_text(json.dumps(result, indent=2) + '\n')
    print('QA_EVIDENCE_STATUS=' + result['status'])
    if result['status'] != 'PASSED':
        print('QA_EVIDENCE_REASON=' + result['reason'])
        raise SystemExit(1)


if __name__ == '__main__':
    main()
