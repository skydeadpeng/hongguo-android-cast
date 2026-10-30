#!/usr/bin/env python3
"""Build a deterministic corresponding-source ZIP without reading keys or following links.

Usage: python3 scripts/package-android-source.py --root SOURCE --git-root PROJECT --output ZIP [--device phone|tv|tvnative]
Only local source files are read. No build, downloaded reference program, upload, or signing runs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tempfile
import zipfile

MANIFEST = "SOURCE-MANIFEST.json"
MAX_FILES = 20_000
MAX_FILE_BYTES = 64 * 1024 * 1024
MAX_SOURCE_BYTES = 128 * 1024 * 1024
MAX_MANIFEST_BYTES = 16 * 1024 * 1024
DENIED_DIRS = frozenset({
    ".git", "node_modules", "dist", "dist-react", "dist-signed", "dist-local", "build", ".gradle",
    "verification", ".local", ".cache", ".npm-cache", ".electron-cache", ".eb-cache", ".webpack",
    ".idea", ".vscode", ".zcode", ".planning", "output", "outputs", "out", "release", "rollback",
    "downloads", "logs", "work", "media", "attachments", "reference-source", "selected-source",
    ".ssh", ".aws", "__pycache__",
})
PRIVATE_SUFFIXES = (".key", ".pem", ".p12", ".pfx", ".jks", ".keystore", ".p8", ".mobileprovision", ".provisionprofile")
BINARY_SUFFIXES = (".apk", ".aab", ".dex", ".so", ".o", ".class", ".aar", ".zip", ".tar", ".tgz", ".7z", ".exe", ".dmg", ".asar", ".appimage", ".mp4", ".mkv", ".mov")
IMAGE_SUFFIXES = (".png", ".jpg", ".jpeg", ".webp", ".gif", ".heic", ".avif")
PRIVATE_NAMES = frozenset({"id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "credentials.json", "token.json", "keystore.properties", "signing.properties", "local.properties"})
REQUIRED = frozenset({
    "LICENSE", "NOTICE", "THIRD-PARTY-NOTICES.md", "README.md", "ANDROID.md", "package.json", "package-lock.json",
    "android/app/build.gradle", "android/build.gradle", "android/settings.gradle", "android/app/src/main/AndroidManifest.xml",
    "scripts/build-android.sh", "scripts/build-android-mac-source.mjs", "src/android/main.jsx", "src/android/mac-source/entry.mjs", "src/native/app-source.js",
})


class PackageError(Exception):
    """A package was not produced or did not pass validation."""


def safe_name(value: str) -> str:
    if not value or "\\" in value or ":" in value or any(ord(c) < 32 or ord(c) == 127 for c in value):
        raise PackageError("Unsafe archive path")
    path = PurePosixPath(value)
    if path.is_absolute() or value.startswith("/") or any(p in ("", ".", "..") for p in value.split("/")):
        raise PackageError("Non-relative or traversing archive path")
    if path.as_posix() != value:
        raise PackageError("Noncanonical archive path")
    return value


def private_name(value: str) -> bool:
    name = PurePosixPath(value).name.lower()
    if name in PRIVATE_NAMES or name.endswith(PRIVATE_SUFFIXES):
        return True
    if ".env" in name and not any(name.endswith(s) for s in (".env.example", ".env.sample", ".env.template")):
        return True
    return bool(re.fullmatch(r"(?:secrets?|service[-_]account|credentials?)(?:[-_.].*)?\.(?:json|ya?ml|toml|properties)", name))


def excluded(value: str) -> bool:
    parts = PurePosixPath(value).parts
    name = parts[-1].lower()
    if any(p.lower() in DENIED_DIRS for p in parts[:-1]) or name in DENIED_DIRS:
        return True
    if private_name(value) or name.endswith(BINARY_SUFFIXES) or name.endswith((".log", ".tmp", ".bak", ".backup", ".pyc", ".db")) or re.search(r"\.sqlite(?:[.-].*)?$", name):
        return True
    if name in {".ds_store", "config.json", "settings.json", "data.json", "task_plan.md", "findings.md", "progress.md", "handoff.md", "windows_handoff.md"}:
        return True
    if name.startswith(("screenshot_", "codex-clipboard-")) or len(parts) == 1 and name.endswith(IMAGE_SUFFIXES):
        return True
    return False


def git_output(git_root: Path, args: list[str], data: bytes | None = None) -> subprocess.CompletedProcess:
    try:
        result = subprocess.run(["git", "-C", str(git_root), *args], input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise PackageError("Git metadata/ignore query failed") from error
    return result


def git_identity(git_root: Path) -> dict:
    top = git_output(git_root, ["rev-parse", "--show-toplevel"])
    if top.returncode or Path(os.fsdecode(top.stdout).strip()).resolve() != git_root:
        raise PackageError("--git-root must name the original repository top-level")
    head = git_output(git_root, ["rev-parse", "HEAD"])
    branch = git_output(git_root, ["branch", "--show-current"])
    if head.returncode or branch.returncode:
        raise PackageError("Original Git reference could not be read")
    return {"baseCommit": head.stdout.decode("ascii").strip(), "baseBranch": os.fsdecode(branch.stdout).strip(), "note": "Base reference only; the embedded per-file hashes identify this working snapshot."}


def ignored_paths(git_root: Path, names: list[str]) -> set[str]:
    # These are SOURCE-relative paths, not verification/MODIFIED_FILE/... paths.
    # --no-index applies the original repository's ignore rules to newly added files too.
    if not names:
        return set()
    data = b"\0".join(os.fsencode(name) for name in names) + b"\0"
    result = git_output(git_root, ["check-ignore", "--no-index", "-z", "--stdin"], data)
    if result.returncode not in (0, 1):
        raise PackageError("Original Git ignore rules could not be checked")
    return {os.fsdecode(name) for name in result.stdout.split(b"\0") if name}


def regular_stream(path: Path):
    # Walk directory descriptors too: a parent swapped into a symlink must never
    # redirect a source read outside the frozen tree. macOS/Linux support openat.
    if os.open not in os.supports_dir_fd or not hasattr(os, "O_NOFOLLOW"):
        raise PackageError("Secure source packaging requires no-follow directory-descriptor support")
    directory_fd = None
    file_fd = None
    try:
        absolute = path.absolute()
        directory_fd = os.open(absolute.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        for part in absolute.parts[1:-1]:
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory_fd)
            os.close(directory_fd)
            directory_fd = next_fd
        file_fd = os.open(absolute.name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory_fd)
        info = os.fstat(file_fd)
        if not stat.S_ISREG(info.st_mode):
            raise PackageError("Only regular source files may be read")
        stream = os.fdopen(file_fd, "rb")
        file_fd = None
        return stream, info
    except OSError as error:
        raise PackageError("Source file changed or is not a regular readable file") from error
    finally:
        if file_fd is not None:
            os.close(file_fd)
        if directory_fd is not None:
            os.close(directory_fd)


def file_digest(path: Path) -> tuple[int, str, int]:
    stream, info = regular_stream(path)
    if info.st_size > MAX_FILE_BYTES:
        stream.close()
        raise PackageError("Source file exceeds the source-only size bound")
    digest = hashlib.sha256()
    size = 0
    with stream:
        while chunk := stream.read(64 * 1024):
            size += len(chunk)
            if size > MAX_FILE_BYTES:
                raise PackageError("Source file grew beyond the package bound")
            digest.update(chunk)
    return size, digest.hexdigest(), 0o755 if info.st_mode & 0o111 else 0o644


def source_files(root: Path, git_root: Path, generated: set[Path]) -> list[dict]:
    names = []
    for directory, dirs, files in os.walk(root, topdown=True, followlinks=False):
        directory = Path(directory)
        keep = []
        for name in sorted(dirs):
            path = directory / name
            relative = safe_name(path.relative_to(root).as_posix())
            if excluded(relative):
                continue
            if path.is_symlink():
                raise PackageError("Unexcluded source symlink rejected: " + relative)
            keep.append(name)
        dirs[:] = keep
        for name in sorted(files):
            path = directory / name
            relative = safe_name(path.relative_to(root).as_posix())
            if excluded(relative) or path.absolute() in generated:
                continue
            if relative == MANIFEST:
                raise PackageError("The source already contains the reserved package manifest name")
            # Key files have already been excluded by name, without opening their contents.
            if path.is_symlink() or not stat.S_ISREG(path.lstat().st_mode):
                raise PackageError("Unexcluded nonregular source file rejected: " + relative)
            names.append(relative)
            if len(names) > MAX_FILES:
                raise PackageError("Source file count exceeds the package bound")
    ignored = ignored_paths(git_root, names)
    names = sorted(set(names) - ignored)
    missing = sorted(REQUIRED - set(names))
    if missing:
        raise PackageError("Required Android source/build/license files are missing or ignored: " + ", ".join(missing))
    if not any(name.startswith("server/") for name in names):
        raise PackageError("Historical server sources must remain in the source archive")
    entries, total = [], 0
    for name in names:
        size, digest, mode = file_digest(root / name)
        total += size
        if total > MAX_SOURCE_BYTES:
            raise PackageError("Source-only snapshot exceeds the size bound")
        entries.append({"path": name, "size": size, "sha256": digest, "mode": format(mode, "04o")})
    return entries


def version_from(data: str, device: str = "phone") -> tuple[str, int]:
    """Read literal version fields from their Gradle DSL scopes, never execute Gradle.

    String/comment contents cannot invent scopes. Dynamic or conditional version
    assignments fail closed; the signed APK remains the release identity check.
    """
    if device not in ("phone", "tv", "tvnative"):
        raise PackageError("Unknown Android source device")
    pattern = re.compile(r"(?P<skip>\s+|//[^\n]*|/\*[\s\S]*?\*/)|(?P<string>'(?:\\[\s\S]|[^'\\])*'|\"(?:\\[\s\S]|[^\"\\])*\")|(?P<word>[A-Za-z_$][A-Za-z0-9_$]*|[0-9]+)|(?P<symbol>[\s\S])")
    tokens = [(m.group(), m.lastgroup, m.start(), m.end()) for m in pattern.finditer(data) if m.lastgroup != "skip"]
    depth = 0
    for text, kind, _, _ in tokens:
        if kind == "symbol" and text in ("'", '"'):
            raise PackageError("Unterminated Android Gradle string")
        if kind == "symbol":
            depth += (text == "{") - (text == "}")
            if depth < 0:
                raise PackageError("Unbalanced Android Gradle scope")
    if depth:
        raise PackageError("Unbalanced Android Gradle scope")

    def blocks(scope, name):
        found, depth, index = [], 0, 0
        while index < len(scope):
            text = scope[index][0]
            if depth == 0 and text == name and index + 1 < len(scope) and scope[index + 1][0] == "{":
                end, nesting = index + 2, 1
                while nesting:
                    nesting += (scope[end][0] == "{") - (scope[end][0] == "}")
                    end += 1
                found.append(scope[index + 2:end - 1])
                index = end
            else:
                depth += (text == "{") - (text == "}")
                index += 1
        if len(found) > 1:
            raise PackageError("Android Gradle scope is ambiguous: " + name)
        return found[0] if found else None

    def fields(scope):
        values, depth = {}, 0
        for index, (text, _, _, _) in enumerate(scope):
            depth += (text == "{") - (text == "}")
            if text not in ("versionName", "versionCode"):
                continue
            if depth or text in values:
                raise PackageError("Conditional or duplicate Android version field")
            value_index = index + 1
            if value_index < len(scope) and scope[value_index][0] == "=":
                value_index += 1
            if value_index >= len(scope):
                raise PackageError("Android version field has no literal value")
            value, kind, _, end = scope[value_index]
            if text == "versionName":
                if kind != "string" or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", value[1:-1]):
                    raise PackageError("Android versionName must be a literal semantic version")
                value = value[1:-1]
            else:
                if kind != "word" or not re.fullmatch(r"[1-9][0-9]*", value):
                    raise PackageError("Android versionCode must be a positive integer literal")
                value = int(value)
            if value_index + 1 < len(scope):
                next_token = scope[value_index + 1]
                gap = re.sub(r"/\*[\s\S]*?\*/|//[^\n]*", "", data[end:next_token[2]])
                if next_token[0] != ";" and "\n" not in gap and "\r" not in gap:
                    raise PackageError("Android version expressions are not supported")
            values[text] = value
        return values

    android = blocks(tokens, "android")
    if android is None:
        # Preserve older single-version inputs without a wrapper, but never
        # invent the native flavor when the project does not declare it.
        if device == "tvnative":
            raise PackageError("Native TV source flavor is missing")
        values = fields(tokens)
    else:
        default = blocks(android, "defaultConfig")
        if default is None:
            raise PackageError("Android defaultConfig is missing")
        values = fields(default)
        flavors = blocks(android, "productFlavors")
        selected = blocks(flavors, "tvNative" if device == "tvnative" else device) if flavors is not None else None
        if device == "tvnative" and selected is None:
            raise PackageError("Native TV source flavor is missing")
        if selected is not None:
            values.update(fields(selected))
    if "versionName" not in values or "versionCode" not in values:
        raise PackageError("Android versionName/versionCode could not be identified unambiguously")
    return values["versionName"], values["versionCode"]


def snapshot_digest(entries: list[dict]) -> str:
    data = json.dumps(entries, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def validate_archive(path: Path, expected: dict | None = None) -> dict:
    try:
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            names = [safe_name(info.filename) for info in infos]
            if len(infos) > MAX_FILES + 1 or len(names) != len(set(names)):
                raise PackageError("Archive entries are duplicated or excessive")
            for info in infos:
                mode = info.external_attr >> 16
                if info.is_dir() or stat.S_IFMT(mode) not in (0, stat.S_IFREG) or stat.S_ISLNK(mode):
                    raise PackageError("Archive contains a symlink or nonregular entry")
                if info.flag_bits & 1 or info.file_size > MAX_FILE_BYTES:
                    raise PackageError("Encrypted or oversized source entry rejected")
                if info.filename != MANIFEST and excluded(info.filename):
                    raise PackageError("Excluded/private path present in source archive")
            if MANIFEST not in names or archive.getinfo(MANIFEST).file_size > MAX_MANIFEST_BYTES:
                raise PackageError("Source manifest is absent or oversized")
            manifest = json.loads(archive.read(MANIFEST))
            if expected is not None and manifest != expected:
                raise PackageError("Reopened manifest does not match the source snapshot")
            entries = manifest.get("files")
            if not isinstance(entries, list) or len(entries) > MAX_FILES:
                raise PackageError("Source manifest file list is invalid")
            indexed = {}
            total = 0
            for entry in entries:
                name = safe_name(entry["path"])
                if name == MANIFEST or name in indexed or excluded(name) or not isinstance(entry["size"], int) or entry["size"] < 0 or entry["size"] > MAX_FILE_BYTES or not re.fullmatch(r"[0-9a-f]{64}", entry["sha256"]) or entry["mode"] not in ("0644", "0755"):
                    raise PackageError("Source manifest entry is invalid")
                indexed[name] = entry
                total += entry["size"]
            if total > MAX_SOURCE_BYTES or set(names) != set(indexed) | {MANIFEST} or not REQUIRED.issubset(indexed) or not any(name.startswith("server/") for name in indexed):
                raise PackageError("Archive membership does not match required source snapshot")
            if manifest.get("snapshotSha256") != snapshot_digest(entries):
                raise PackageError("Source snapshot checksum is inconsistent")
            if archive.testzip() is not None:
                raise PackageError("Reopened ZIP CRC check failed")
            for name, entry in indexed.items():
                info = archive.getinfo(name)
                if info.file_size != entry["size"] or stat.S_IMODE(info.external_attr >> 16) != int(entry["mode"], 8):
                    raise PackageError("Archive file size/mode differs from the source manifest")
                digest = hashlib.sha256()
                with archive.open(info) as stream:
                    while chunk := stream.read(64 * 1024):
                        digest.update(chunk)
                if digest.hexdigest() != entry["sha256"]:
                    raise PackageError("Archive source hash differs from the manifest")
            version, code = version_from(archive.read("android/app/build.gradle").decode("utf-8"), manifest.get("device", "phone"))
            if manifest.get("versionName") != version or manifest.get("versionCode") != code:
                raise PackageError("Archived Android version differs from the manifest")
            return manifest
    except PackageError:
        raise
    except (OSError, KeyError, TypeError, ValueError, zipfile.BadZipFile, RuntimeError) as error:
        raise PackageError("Archive could not be reopened and verified") from error


def zip_info(name: str, mode: int) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(safe_name(name), date_time=(1980, 1, 1, 0, 0, 0))
    info.create_system = 3
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = (stat.S_IFREG | mode) << 16
    return info


def package_source(root: Path, git_root: Path, output: Path, force: bool = False, device: str = "phone") -> dict:
    if root.is_symlink():
        raise PackageError("Source root cannot be a symlink")
    root, git_root = root.resolve(), git_root.resolve()
    if not root.is_dir() or not git_root.is_dir():
        raise PackageError("Source and Git roots must exist")
    output = output.absolute()
    if output.suffix.lower() != ".zip" or output.is_symlink():
        raise PackageError("Output must be a regular .zip path")
    output = output.parent.resolve() / output.name
    sidecar = output.with_suffix(output.suffix + ".manifest.json")
    checksum = output.with_suffix(output.suffix + ".sha256")
    if not force and any(p.exists() for p in (output, sidecar, checksum)):
        raise PackageError("Output or sidecars already exist; use --force explicitly")
    if any(p.is_symlink() for p in (sidecar, checksum)):
        raise PackageError("Output sidecars cannot be symlinks")
    identity = git_identity(git_root)
    entries = source_files(root, git_root, {output, sidecar, checksum})
    version, code = version_from((root / "android/app/build.gradle").read_text(encoding="utf-8"), device)
    manifest = {"schema": 1, "kind": "android-corresponding-source", "device": device, "versionName": version, "versionCode": code,
                "gitReference": identity, "snapshotSha256": snapshot_digest(entries), "files": entries}
    raw_manifest = (json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
    if len(raw_manifest) > MAX_MANIFEST_BYTES:
        raise PackageError("Source manifest exceeds its size bound")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix="." + output.name + ".", suffix=".tmp", delete=False) as temp:
            temporary = Path(temp.name)
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9, allowZip64=False) as archive:
            for entry in entries:
                digest, size = hashlib.sha256(), 0
                stream, info = regular_stream(root / entry["path"])
                if (0o755 if info.st_mode & 0o111 else 0o644) != int(entry["mode"], 8):
                    stream.close()
                    raise PackageError("Source executable mode changed during packaging")
                with stream, archive.open(zip_info(entry["path"], int(entry["mode"], 8)), "w") as destination:
                    while chunk := stream.read(64 * 1024):
                        size += len(chunk)
                        if size > MAX_FILE_BYTES:
                            raise PackageError("Source file grew while packaging")
                        digest.update(chunk)
                        destination.write(chunk)
                if size != entry["size"] or digest.hexdigest() != entry["sha256"]:
                    raise PackageError("Source bytes changed while packaging; freeze sources and retry")
            archive.writestr(zip_info(MANIFEST, 0o644), raw_manifest, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        validate_archive(temporary, manifest)
        # The published archive is replaced only after all source hashes and ZIP CRCs pass.
        archive_sha = hashlib.sha256(temporary.read_bytes()).hexdigest()
        os.replace(temporary, output)
        temporary = None
        sidecar.write_bytes(raw_manifest)
        checksum.write_text(archive_sha + "  " + output.name + "\n", encoding="utf-8")
        validate_archive(output, manifest)
        if json.loads(sidecar.read_text(encoding="utf-8")) != manifest or hashlib.sha256(output.read_bytes()).hexdigest() != checksum.read_text(encoding="utf-8").split()[0]:
            raise PackageError("Final ZIP/manifest/checksum did not reopen consistently")
        return {"zip": str(output), "sha256": archive_sha, "bytes": output.stat().st_size, "files": len(entries),
                "device": device, "versionName": version, "versionCode": code, "snapshotSha256": manifest["snapshotSha256"],
                "manifest": str(sidecar), "checksum": str(checksum), "crc": "PASS", "entrySafety": "PASS", "symlinks": 0}
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True, help="Frozen corresponding source directory (may be an ignored verification copy)")
    parser.add_argument("--git-root", type=Path, required=True, help="Original project Git top-level used only for reference and ignore rules")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--device", choices=("phone", "tv", "tvnative"), default="phone", help="Android variant identity (default: phone)")
    parser.add_argument("--force", action="store_true", help="Explicitly replace an existing package and its sidecars")
    args = parser.parse_args(argv)
    try:
        print(json.dumps(package_source(args.root, args.git_root, args.output, args.force, args.device), ensure_ascii=False, sort_keys=True))
        return 0
    except PackageError as error:
        print("SOURCE_PACKAGE_ERROR: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
