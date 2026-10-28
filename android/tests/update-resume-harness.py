#!/usr/bin/env python3
"""Run unchanged production resume/install/handoff methods with host-side platform fakes.

This is a state-transition regression test, not an Android package-installer test.
Integrity/checkpoint fakes record order and fail closed; actual policy, integrity and
checkpoint implementations have separate tests and APK installation needs device QA.
"""
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text()


def method(signature):
    start = source.index(signature)
    opening = source.index('{', start)
    # These lifecycle methods contain no brace characters in string literals.
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


methods = '\n'.join(method(s) for s in (
    'void resume()', 'private void install()',
    'private void handoff(Candidate target)',
    'private void finish(long operation, Runnable action)',
))
template = r'''
import java.io.*;
import java.util.*;

public final class UpdateResumeHarness {
  boolean foreground, started = true, busy, destroyed;
  long epoch;
  String state = "waiting_permission", message = "";
  Candidate candidate = new Candidate(), verifiedCandidate = candidate;
  final FakePrefs prefs = new FakePrefs();
  final FakeFile apk = new FakeFile();
  final Host host = new Host();
  int verifies, publishes, starts;
  boolean integrityFail;
  final List<String> events = new ArrayList<>();
  static int checks, groups;
  static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
  void publish() { publishes++; }
  void maybePrompt() { }
  void automaticCheck() { }
  void start() { starts++; }
  void submit(long operation, Runnable task) { task.run(); }
  void live(long operation) throws IOException { if (destroyed || epoch != operation) throw new IOException("cancelled"); }
  void verify(Candidate target, FakeFile file) throws IOException { events.add("verify"); verifies++; if(integrityFail || !file.exists) throw new IOException("integrity"); }
  String userError(Exception error) { return error.getMessage(); }
  static final class Candidate { }
  static final class FakeFile { boolean exists = true; void delete() { exists = false; } }
  static final class FakePrefs {
    final Map<String,Object> values = new HashMap<>();
    FakePrefs() { values.put("waiting_permission", true); values.put("manifest", "kept"); }
    boolean getBoolean(String key, boolean fallback) { Object value = values.get(key); return value instanceof Boolean ? (Boolean)value : fallback; }
    FakePrefs edit() { return this; }
    FakePrefs putBoolean(String key, boolean value) { values.put(key, value); return this; }
    FakePrefs remove(String key) { values.remove(key); return this; }
    void apply() { }
    boolean commit() { return true; }
  }
  final class Host {
    final PackageManager manager = new PackageManager();
    final List<Intent> intents = new ArrayList<>();
    boolean missingInstaller;
    PackageManager getPackageManager() { return manager; }
    Object getApplicationContext() { return UpdateResumeHarness.this; }
    String getPackageName() { return "io.github.jackotom.hongguo.android"; }
    Object getContentResolver() { return this; }
    void runOnUiThread(Runnable action) { action.run(); }
    void startActivity(Intent intent) {
      if (missingInstaller && Intent.ACTION_VIEW.equals(intent.action)) throw new ActivityNotFoundException();
      events.add("handoff:" + intent.action); intents.add(intent);
    }
  }
  static final class PackageManager { boolean permission = true; boolean canRequestPackageInstalls() { return permission; } }
  static final class Uri { static Uri parse(String value) { return new Uri(); } }
  static final class ClipData { static ClipData newUri(Object resolver,String label,Uri uri) { return new ClipData(); } }
  static final class Intent {
    static final String ACTION_VIEW = "view";
    static final int FLAG_GRANT_READ_URI_PERMISSION = 1;
    final String action;
    Intent(String action) { this.action = action; }
    Intent(String action, Uri uri) { this.action = action; }
    Intent setDataAndType(Uri uri,String type) { return this; }
    void setClipData(ClipData clip) { }
    void addFlags(int flags) { }
  }
  static final class Settings { static final String ACTION_MANAGE_UNKNOWN_APP_SOURCES = "permission"; }
  static final class ActivityNotFoundException extends RuntimeException { }
  static final class UpdateInstallCheckpoint {
    static Object owner;
    static boolean fail;
    static int prepares, releases;
    static void prepare(Object context,Object claimant) throws IOException {
      UpdateResumeHarness current = (UpdateResumeHarness)context;
      current.events.add("checkpoint"); prepares++;
      if (fail || owner != null && owner != claimant) throw new IOException("checkpoint");
      owner = claimant;
    }
    static void release(Object claimant) { releases++; if(owner == claimant)owner = null; }
    static void reset() { owner = null; fail = false; prepares = 0; releases = 0; }
  }

  // Exact methods extracted mechanically from the production source under test.
  __METHODS__

  static UpdateResumeHarness fresh() { UpdateInstallCheckpoint.reset(); return new UpdateResumeHarness(); }
  static void installed(UpdateResumeHarness h) {
    check(h.state.equals("installing"), "permission granted return must reach installer, actual=" + h.state);
    check(h.verifies == 1, "must reverify complete APK before handoff");
    check(UpdateInstallCheckpoint.prepares == 1, "must checkpoint data and acquire install gate");
    check(UpdateInstallCheckpoint.releases == 1 && UpdateInstallCheckpoint.owner == null, "must release own gate");
    check(h.events.equals(List.of("verify","checkpoint","handoff:view")), "verification/checkpoint/installer ordering");
    check(!h.prefs.getBoolean("waiting_permission", false), "permission marker must clear");
  }
  public static void main(String[] args) {
    UpdateResumeHarness h = fresh(); h.resume(); installed(h); groups++;

    h = fresh(); h.host.manager.permission = false; h.resume();
    check(h.state.equals("ready") && h.message.contains("权限尚未开启"), "permission rejection leaves usable retry");
    check(!h.prefs.getBoolean("waiting_permission", false) && h.host.intents.isEmpty() && h.verifies == 0, "denied return never opens installer"); groups++;

    h = fresh(); h.integrityFail = true; h.resume();
    check(h.state.equals("error") && !h.apk.exists && h.verifiedCandidate == null, "integrity failure clears invalid archive");
    check(h.verifies == 1 && UpdateInstallCheckpoint.prepares == 0 && h.host.intents.isEmpty(), "integrity failure cannot handoff or checkpoint"); groups++;

    h = fresh(); UpdateInstallCheckpoint.fail = true; h.resume();
    check(h.state.equals("ready") && h.apk.exists && h.host.intents.isEmpty(), "checkpoint failure retains retry but prevents installation");
    check(h.verifies == 1 && UpdateInstallCheckpoint.prepares == 1 && UpdateInstallCheckpoint.owner == null, "failed prepare releases own gate");
    UpdateInstallCheckpoint.fail = false; h.install();
    check(h.state.equals("installing") && h.verifies == 2 && UpdateInstallCheckpoint.prepares == 2, "retry repeats integrity and checkpoint"); groups++;

    h = fresh(); Object other = new Object(); UpdateInstallCheckpoint.owner = other; h.resume();
    check(h.state.equals("ready") && h.host.intents.isEmpty(), "other owner's install gate blocks handoff");
    check(UpdateInstallCheckpoint.owner == other, "failed install must not release other owner's gate"); groups++;

    h = fresh(); h.state = "installing"; h.prefs.putBoolean("waiting_permission", false); h.resume();
    check(h.state.equals("ready") && h.apk.exists && h.verifies == 0, "installer cancel restores ready without deleting validated APK");
    h.install(); installed(h); groups++;

    h = fresh(); h.state = "ready"; h.foreground = true; h.prefs.putBoolean("waiting_permission", false); h.host.manager.permission = false; h.install();
    check(h.state.equals("waiting_permission") && h.host.intents.size() == 1 && h.host.intents.get(0).action.equals("permission"), "first install opens unknown-sources settings");
    check(h.prefs.getBoolean("waiting_permission", false) && h.verifies == 1 && UpdateInstallCheckpoint.prepares == 1, "permission trip checkpoints before leaving app");
    h.host.manager.permission = true; h.resume();
    check(h.state.equals("installing") && h.verifies == 2 && UpdateInstallCheckpoint.prepares == 2, "full permission trip revalidates before installer");
    check(h.host.intents.size() == 2 && h.host.intents.get(1).action.equals("view"), "settings return opens installer once"); groups++;

    h = fresh(); h.host.missingInstaller = true; h.resume();
    check(h.state.equals("ready") && h.apk.exists && UpdateInstallCheckpoint.owner == null, "missing installer retains retry and releases gate"); groups++;

    h = fresh(); h.busy = true; h.resume();
    check(h.state.equals("waiting_permission") && h.verifies == 0 && h.prefs.getBoolean("waiting_permission", false), "busy return leaves pending permission untouched"); groups++;

    h = fresh(); h.started = false; h.resume();
    check(h.starts == 1 && h.verifies == 0 && h.prefs.getBoolean("waiting_permission", false), "cold restart delegates to existing restore verification before resume"); groups++;

    h = fresh(); h.destroyed = true; h.resume();
    check(h.verifies == 0 && h.host.intents.isEmpty(), "destroyed activity cannot reinstall"); groups++;
    System.out.println("UPDATE_RESUME_OK groups=" + groups + " checks=" + checks + " actualLifecycleMethods=true platform=hostJvm integrityCheckpointOrder=verified");
  }
}
'''
Path(sys.argv[2]).write_text(template.replace('__METHODS__', methods))
