#!/usr/bin/env python3
"""Offline tests of the local source packager; only synthetic source trees and keys are created."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
import warnings
from unittest import mock
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("android_source_packager", HERE / "package-android-source.py")
packager = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = packager
spec.loader.exec_module(packager)


class SourcePackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hongguo-source-package-test-")
        self.base = Path(self.temp.name).resolve()
        self.git = self.base / "project"
        self.git.mkdir()
        self.run_git("init", "-q")
        self.run_git("config", "core.hooksPath", "/dev/null")
        self.run_git("config", "commit.gpgsign", "false")
        self.run_git("config", "user.name", "Source packaging fixture")
        self.run_git("config", "user.email", "fixture@example.invalid")
        (self.git / ".gitignore").write_text("verification/\nnode_modules/\nbuild/\n*.ignored\n.env\n", encoding="utf-8")
        self.run_git("add", ".gitignore")
        self.run_git("commit", "-qm", "synthetic baseline")
        self.source = self.git / "verification" / "MODIFIED_FILE"
        self.source.mkdir(parents=True)
        for relative in packager.REQUIRED:
            self.put(relative, "synthetic source fixture\n")
        self.put("android/app/build.gradle", "android { defaultConfig { versionCode 35\nversionName '0.12.12' } }\n")
        self.put("server/login/internal/history.go", "package login // synthetic fixture, never executed\n")
        self.put("src/native/licenses/NOTICE.txt", "synthetic license attribution\n")
        self.put("src/android/new-untracked-feature.mjs", "export const fixture = true;\n")
        (self.source / "scripts/build-android.sh").chmod(0o755)
        self.output = self.base / "Hongguo-0.12.12-source.zip"

    def tearDown(self):
        self.temp.cleanup()

    def run_git(self, *args):
        env = dict(os.environ, GIT_CONFIG_GLOBAL="/dev/null", GIT_CONFIG_NOSYSTEM="1")
        return subprocess.run(["git", "-C", str(self.git), *args], check=True, capture_output=True, env=env)

    def put(self, relative, value):
        target = self.source / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(value if isinstance(value, bytes) else value.encode("utf-8"))
        return target

    def package(self, output=None):
        return packager.package_source(self.source, self.git, output or self.output)

    def test_ignored_work_copy_uses_relative_rules_and_includes_untracked_sources(self):
        self.put("src/private.ignored", "ignored fixture")
        result = self.package()
        with zipfile.ZipFile(self.output) as archive:
            names = set(archive.namelist())
            self.assertTrue(packager.REQUIRED.issubset(names))
            self.assertIn("src/android/new-untracked-feature.mjs", names)
            self.assertIn("server/login/internal/history.go", names)
            self.assertIn("src/native/licenses/NOTICE.txt", names)
            self.assertNotIn("src/private.ignored", names)
            self.assertFalse(any(name.startswith("verification/") for name in names))
            self.assertEqual(archive.getinfo("scripts/build-android.sh").external_attr >> 16 & 0o777, 0o755)
            manifest = json.loads(archive.read(packager.MANIFEST))
        self.assertEqual(result["versionName"], "0.12.12")
        self.assertEqual(result["versionCode"], 35)
        self.assertEqual(result["crc"], "PASS")
        self.assertEqual(manifest["snapshotSha256"], result["snapshotSha256"])
        self.assertEqual(hashlib.sha256(self.output.read_bytes()).hexdigest(), result["sha256"])
        self.assertEqual(Path(result["checksum"]).read_text().split()[0], result["sha256"])
        self.assertEqual(json.loads(Path(result["manifest"]).read_text()), manifest)

    def test_exclusions_are_applied_before_keys_are_opened_but_app_assets_survive(self):
        denied = ["node_modules/private.js", "dist/app.apk", "android/app/build/secret.bin", "build/ffmpeg/ffmpeg", ".gradle/cache", "verification/reference-source/a.go", ".local/secret", "reference-source/a.go", ".env", ".env.production", "server/config.env", "private.key", "signing.keystore", "debug.jks", "id_ed25519", "keystore.properties", "local.properties", "Screenshot_20261026_180252.jpg", "codex-clipboard-fixture.jpg", "loose-photo.png"]
        for name in denied:
            self.put(name, "SYNTHETIC PRIVATE FIXTURE; never opened by packager")
        self.put("src/assets/needed-logo.png", b"synthetic resource bytes")
        self.put("android/app/src/main/res/drawable/app_icon.png", b"synthetic Android icon")
        self.put("server/login/config.env.example", "EXAMPLE_ONLY=example\n")
        original = packager.regular_stream
        seen = []
        def guarded(path):
            name = Path(path).relative_to(self.source).as_posix()
            self.assertNotIn(name, denied)
            seen.append(name)
            return original(path)
        with mock.patch.object(packager, "regular_stream", side_effect=guarded):
            self.package()
        with zipfile.ZipFile(self.output) as archive:
            names = set(archive.namelist())
        for name in denied:
            self.assertNotIn(name, names)
        for name in ["src/assets/needed-logo.png", "android/app/src/main/res/drawable/app_icon.png", "server/login/config.env.example"]:
            self.assertIn(name, names)
            self.assertIn(name, seen)

    def test_dependency_symlink_is_skipped_without_reading_target(self):
        external = self.base / "outside"
        external.mkdir()
        (external / "private.key").write_text("synthetic outside key")
        (self.source / "node_modules").symlink_to(external, target_is_directory=True)
        self.package()
        with zipfile.ZipFile(self.output) as archive:
            self.assertFalse(any("node_modules" in name for name in archive.namelist()))
            self.assertFalse(any(stat.S_ISLNK(info.external_attr >> 16) for info in archive.infolist()))

    def test_unexcluded_symlink_is_rejected_without_dereferencing(self):
        external = self.base / "outside-private"
        external.write_text("synthetic secret")
        (self.source / "src/link.js").symlink_to(external)
        with self.assertRaises(packager.PackageError):
            self.package()
        self.assertFalse(self.output.exists())

    def test_output_is_deterministic_and_sources_never_change(self):
        before = {p.relative_to(self.source).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in self.source.rglob("*") if p.is_file()}
        first = self.package()
        second = self.package(self.base / "second.zip")
        self.assertEqual(first["sha256"], second["sha256"])
        self.assertEqual(first["snapshotSha256"], second["snapshotSha256"])
        after = {p.relative_to(self.source).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in self.source.rglob("*") if p.is_file()}
        self.assertEqual(before, after)
        with self.assertRaises(packager.PackageError):
            self.package()
        self.assertEqual(packager.package_source(self.source, self.git, self.output, True)["sha256"], first["sha256"])

    def test_existing_reserved_manifest_or_missing_required_source_fails(self):
        self.put(packager.MANIFEST, "{}")
        with self.assertRaises(packager.PackageError):
            self.package()
        (self.source / packager.MANIFEST).unlink()
        (self.source / "LICENSE").unlink()
        with self.assertRaises(packager.PackageError):
            self.package()
        self.assertFalse(self.output.exists())

    def test_archive_validation_rejects_traversal_links_duplicates_and_private_names(self):
        cases = [("../escape.js", None), ("/absolute.js", None), ("a\\..\\escape.js", None), ("C:/escape.js", None), ("private.key", None), ("link.js", stat.S_IFLNK | 0o777)]
        for index, (name, mode) in enumerate(cases):
            output = self.base / f"malicious-{index}.zip"
            with zipfile.ZipFile(output, "w") as archive:
                info = zipfile.ZipInfo(name)
                if mode is not None:
                    info.external_attr = mode << 16
                archive.writestr(info, "synthetic fixture")
            with self.subTest(name=name), self.assertRaises(packager.PackageError):
                packager.validate_archive(output)

    def test_duplicate_archive_entries_and_output_symlinks_are_rejected(self):
        duplicate = self.base / "duplicate.zip"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(duplicate, "w") as archive:
                archive.writestr("source.js", "synthetic first")
                archive.writestr("source.js", "synthetic second")
        with self.assertRaises(packager.PackageError):
            packager.validate_archive(duplicate)
        outside = self.base / "outside-output"
        outside.write_text("synthetic existing data")
        self.output.symlink_to(outside)
        with self.assertRaises(packager.PackageError):
            self.package()
        self.assertEqual(outside.read_text(), "synthetic existing data")

    def test_secure_open_rejects_a_symlink_parent_without_reading_outside_file(self):
        outside = self.base / "outside-directory"
        outside.mkdir()
        (outside / "source.txt").write_text("synthetic outside bytes")
        (self.source / "src/swapped").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(packager.PackageError):
            packager.regular_stream(self.source / "src/swapped/source.txt")

    def test_crc_and_manifest_hash_tampering_are_rejected(self):
        result = self.package()
        changed = self.base / "tampered.zip"
        with zipfile.ZipFile(self.output) as source, zipfile.ZipFile(changed, "w") as target:
            for info in source.infolist():
                data = source.read(info)
                if info.filename == "README.md":
                    data += b" changed"
                target.writestr(info, data)
        with self.assertRaises(packager.PackageError):
            packager.validate_archive(changed)
        raw = bytearray(self.output.read_bytes())
        with zipfile.ZipFile(self.output) as archive:
            start = archive.start_dir
            self.assertEqual(raw[start:start+4], b"PK\x01\x02")
            # Alter the central-directory CRC while leaving the deflated bytes intact.
            raw[start+16] ^= 1
        corrupt = self.base / "crc-corrupt.zip"
        corrupt.write_bytes(raw)
        with self.assertRaises(packager.PackageError):
            packager.validate_archive(corrupt)
        self.assertEqual(packager.validate_archive(self.output)["snapshotSha256"], result["snapshotSha256"])
        wrong_version = self.base / "wrong-version.zip"
        with zipfile.ZipFile(self.output) as source, zipfile.ZipFile(wrong_version, "w") as target:
            for info in source.infolist():
                data = source.read(info)
                if info.filename == packager.MANIFEST:
                    manifest = json.loads(data)
                    manifest["versionName"] = "0.12.11"
                    data = json.dumps(manifest).encode("utf-8")
                target.writestr(info, data)
        with self.assertRaises(packager.PackageError):
            packager.validate_archive(wrong_version)

    def multi_flavor_gradle(self):
        return """// versionName '9.9.9' and braces { } in a comment are inert.
android {
    namespace 'example.versionName'
    defaultConfig { versionCode 41; versionName '0.12.18' }
    productFlavors {
        phone { dimension 'device' }
        tv { dimension 'device' }
        tvNative {
            dimension 'device'
            applicationIdSuffix '.tvnative'
            versionCode 3
            versionName '0.1.2'
        }
    }
}
"""

    def test_native_multiflavor_package_selects_its_own_version(self):
        self.put("android/app/build.gradle", self.multi_flavor_gradle())
        result = packager.package_source(self.source, self.git, self.output, device="tvnative")
        self.assertEqual((result["versionName"], result["versionCode"]), ("0.1.2", 3))
        manifest = packager.validate_archive(self.output)
        self.assertEqual(manifest["device"], "tvnative")
        self.assertEqual((manifest["versionName"], manifest["versionCode"]), ("0.1.2", 3))
        self.assertEqual(packager.version_from(self.multi_flavor_gradle(), "phone"), ("0.12.18", 41))
        self.assertEqual(packager.version_from(self.multi_flavor_gradle(), "tv"), ("0.12.18", 41))

    def rewrite_manifest(self, source, output, change):
        with zipfile.ZipFile(source) as old, zipfile.ZipFile(output, "w") as new:
            for info in old.infolist():
                data = old.read(info)
                if info.filename == packager.MANIFEST:
                    value = json.loads(data)
                    change(value)
                    data = json.dumps(value).encode("utf-8")
                new.writestr(info, data)

    def test_native_archive_wrong_version_or_flavor_is_rejected(self):
        self.put("android/app/build.gradle", self.multi_flavor_gradle())
        packager.package_source(self.source, self.git, self.output, device="tvnative")
        for key, value in [("device", "phone"), ("device", "TVNative"), ("versionCode", 41), ("versionName", "0.12.18")]:
            output = self.base / ("changed-" + key + str(value) + ".zip")
            self.rewrite_manifest(self.output, output, lambda m, k=key, v=value: m.update({k: v}))
            with self.subTest(key=key, value=value), self.assertRaises(packager.PackageError):
                packager.validate_archive(output)

    def test_legacy_manifest_without_device_defaults_to_phone(self):
        self.package()
        legacy = self.base / "legacy.zip"
        self.rewrite_manifest(self.output, legacy, lambda m: m.pop("device", None))
        self.assertEqual(packager.validate_archive(legacy)["versionName"], "0.12.12")

    def test_version_parser_skips_comments_strings_and_other_scopes(self):
        replacement = "namespace \"a { versionName '8.8.8' }\"\n    /* defaultConfig { versionCode 999; versionName '9.9.9' } */"
        source = self.multi_flavor_gradle().replace("namespace 'example.versionName'", replacement)
        self.assertEqual(packager.version_from(source, "tvnative"), ("0.1.2", 3))
        self.assertEqual(packager.version_from("versionCode 7; versionName '1.2.3'"), ("1.2.3", 7))

    def test_ambiguous_dynamic_or_missing_native_versions_are_rejected(self):
        fixtures = [
            self.multi_flavor_gradle().replace("versionCode 3", "versionCode 3; versionCode 4"),
            self.multi_flavor_gradle().replace("versionName '0.1.2'", "versionName nativeVersion"),
            self.multi_flavor_gradle().replace("versionName '0.1.2'", "versionName '0.1.2' + suffix"),
            self.multi_flavor_gradle().replace("versionCode 3", "if (enabled) { versionCode 3 }"),
            self.multi_flavor_gradle().replace("tvNative {", "other {"),
            self.multi_flavor_gradle() + "android { defaultConfig { versionCode 3; versionName '0.1.2' } }",
        ]
        for source in fixtures:
            with self.subTest(source=source), self.assertRaises(packager.PackageError):
                packager.version_from(source, "tvnative")
        with self.assertRaises(packager.PackageError):
            packager.version_from(self.multi_flavor_gradle(), "TVNative")

    def test_cli_device_argument_creates_native_metadata(self):
        self.put("android/app/build.gradle", self.multi_flavor_gradle())
        result = subprocess.run([sys.executable, str(HERE / "package-android-source.py"), "--root", str(self.source), "--git-root", str(self.git), "--output", str(self.output), "--device", "tvnative"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["device"], "tvnative")

    def test_changed_source_during_packaging_cannot_publish_a_stale_snapshot(self):
        original = packager.source_files
        def changed(*args, **kwargs):
            entries = original(*args, **kwargs)
            self.put("README.md", "changed after inventory")
            return entries
        with mock.patch.object(packager, "source_files", side_effect=changed), self.assertRaises(packager.PackageError):
            self.package()
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.output.parent.glob(".Hongguo-0.12.12-source.zip.*.tmp")))

    def test_nested_output_and_sidecars_are_not_added_to_their_own_source_snapshot(self):
        output = self.source / "source.zip"
        first = self.package(output)
        second = packager.package_source(self.source, self.git, output, True)
        self.assertEqual(first["sha256"], second["sha256"])
        with zipfile.ZipFile(output) as archive:
            self.assertNotIn("source.zip", archive.namelist())
            self.assertNotIn("source.zip.manifest.json", archive.namelist())
            self.assertNotIn("source.zip.sha256", archive.namelist())


if __name__ == "__main__":
    unittest.main(verbosity=2)
