#!/usr/bin/env python3
"""Read existing runner KVM access only. Never grants permissions or changes host settings."""
import argparse
import errno
import fcntl
import grp
import json
import os
from pathlib import Path
import re
import stat
import subprocess


def group_name(gid):
    try:
        return grp.getgrgid(gid).gr_name
    except KeyError:
        return str(gid)


def safe_text(value):
    value = re.sub(r"https?://[^\s\"'<>]+", "[URL]", value or "")
    return re.sub(r"[^\x20-\x7e\n\t]", "?", value)[:2000]


def inspect_runner():
    report = {
        "phase": "runner_preflight_only",
        "verification_status": "SKIPPED_ENVIRONMENT",
        "apk_execution": "NOT_RUN",
        "first_frame": "NOT_RUN",
        "continuous_30_seconds": "NOT_RUN",
        "commit": os.environ.get("GITHUB_SHA", "local-inspection"),
        "ref": os.environ.get("GITHUB_REF", "local-inspection"),
        "identity": {
            "uid": os.geteuid(),
            "gid": os.getegid(),
            "groups": [{"gid": gid, "name": group_name(gid)} for gid in sorted(set(os.getgroups()))],
        },
        "kvm": {"path": "/dev/kvm", "present": False, "opened": False},
        "emulator": {"present": False},
        "blockers": [],
        "probe_changes": "NONE: this read-only probe never grants permissions or changes host settings",
    }
    kvm = report["kvm"]
    fd = None
    try:
        info = os.stat("/dev/kvm", follow_symlinks=False)
        kvm.update(present=True, character_device=stat.S_ISCHR(info.st_mode),
                   mode=oct(stat.S_IMODE(info.st_mode)), owner_uid=info.st_uid,
                   owner_gid=info.st_gid, owner_group=group_name(info.st_gid))
        if not stat.S_ISCHR(info.st_mode):
            report["blockers"].append({"stage": "kvm_device", "category": "NOT_CHARACTER_DEVICE"})
        else:
            # O_RDWR is required by KVM. This opens the existing device only;
            # KVM_GET_API_VERSION is a query, not VM creation or configuration.
            fd = os.open("/dev/kvm", os.O_RDWR | os.O_CLOEXEC)
            kvm["opened"] = True
            kvm["api_version"] = fcntl.ioctl(fd, 0xAE00, 0)
            if kvm["api_version"] != 12:
                report["blockers"].append({"stage": "kvm_api", "category": "UNSUPPORTED_API_VERSION"})
    except OSError as error:
        category = "MISSING_DEVICE" if error.errno == errno.ENOENT else "PERMISSION_DENIED" if error.errno in (errno.EACCES, errno.EPERM) else "KVM_QUERY_ERROR"
        report["blockers"].append({"stage": "kvm_open_or_query", "category": category,
                                   "errno": error.errno, "message": os.strerror(error.errno) if error.errno else "Unknown OS error"})
    finally:
        if fd is not None:
            os.close(fd)

    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    candidates = [Path(root) / "emulator" / "emulator" for root in roots if root]
    emulator = next((path for path in candidates if path.is_file() and os.access(path, os.X_OK)), None)
    if emulator is None:
        report["blockers"].append({"stage": "official_emulator", "category": "PREINSTALLED_SDK_EMULATOR_MISSING"})
    else:
        report["emulator"]["present"] = True
        try:
            checked = subprocess.run([str(emulator), "-accel-check"], capture_output=True,
                                     text=True, timeout=30, check=False)
            report["emulator"].update(accel_check_exit=checked.returncode,
                                       accel_check_output=safe_text(checked.stdout + checked.stderr))
            if checked.returncode != 0:
                report["blockers"].append({"stage": "official_emulator_accel_check", "category": "ACCELERATION_UNAVAILABLE"})
        except subprocess.TimeoutExpired:
            report["blockers"].append({"stage": "official_emulator_accel_check", "category": "TIMEOUT_30_SECONDS"})
        except OSError as error:
            report["blockers"].append({"stage": "official_emulator_accel_check", "category": type(error).__name__, "errno": error.errno})
    if not report["blockers"]:
        report["verification_status"] = "READY_FOR_PLAYBACK_TEST"
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = inspect_runner()
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "preflight.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    status = report["verification_status"]
    detail = ", ".join(item["category"] for item in report["blockers"]) or "Existing KVM access and official emulator acceleration check succeeded."
    summary = f"{status}\n{detail}\nThis is preflight only. APK execution, first frame and 30-second playback have NOT RUN.\n"
    (args.output / "summary.txt").write_text(summary, encoding="utf-8")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"ready={'true' if status == 'READY_FOR_PLAYBACK_TEST' else 'false'}\nverification_status={status}\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(summary)
    print(summary, end="")
    # A completed preflight can be a documented environment skip. It is never
    # labeled playback PASS; later playback jobs must require ready == true.


if __name__ == "__main__":
    main()
