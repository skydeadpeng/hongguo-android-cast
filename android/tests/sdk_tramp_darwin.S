// SPDX-License-Identifier: GPL-3.0-only
// macOS spells the C symbol `name` as `_name` in assembly; the adapter's trampolines are written with
// the Android convention. This file only gives them their macOS names, for the host fault test.
#if defined(__APPLE__)
    .globl _sdk_tramp_start, _sdk_tramp_dispatch, _sdk_lit_core, _sdk_lit_dispatch_resume, _sdk_lit_core_handler
    .globl _sdk_tramp_load, _sdk_lit_load_resume, _sdk_tramp_svc, _sdk_lit_svc_resume, _sdk_tramp_end
_sdk_tramp_start = sdk_tramp_start
_sdk_tramp_dispatch = sdk_tramp_dispatch
_sdk_lit_core = sdk_lit_core
_sdk_lit_dispatch_resume = sdk_lit_dispatch_resume
_sdk_lit_core_handler = sdk_lit_core_handler
_sdk_tramp_load = sdk_tramp_load
_sdk_lit_load_resume = sdk_lit_load_resume
_sdk_tramp_svc = sdk_tramp_svc
_sdk_lit_svc_resume = sdk_lit_svc_resume
_sdk_tramp_end = sdk_tramp_end
#endif
