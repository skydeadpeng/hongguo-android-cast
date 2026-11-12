package io.github.jackotom.hongguo.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.widget.VideoView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in, isolated-AVD player-component acceptance, not a browse-UI test.
 * No URL, source response, callback, player state, or media fixture is injected.
 * Reflection reads only this application's existing state, never framework fields.
 */
public final class ProductionPlaybackInstrumentation extends Instrumentation {
    private static final String PACKAGE = "io.github.jackotom.hongguo.android";
    private static final String SERIES = PlaybackProbeConfig.SERIES;
    private static final String TITLE = "都说了，别惹那个乞丐";
    private static final long STALL_MS = 5_000;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final JSONObject report = new JSONObject();
    private final JSONArray stages = new JSONArray();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Bundle arguments;
    private PlaybackProbeConfig config = PlaybackProbeConfig.original();
    private long started;
    private File output;
    private UiAutomation automation;
    private AudioManager audio;
    private volatile String stage = "environment";
    private volatile PlayerActivity player;
    private volatile MainActivity host;
    private volatile boolean guestPlanObserved;
    private volatile boolean historyObservedBeforeFrame;
    private volatile boolean actualFirstFrameObserved;
    private volatile PlaybackSources.Plan acceptedAppPlan;
    private volatile Diagnostic diagnostic = new Diagnostic();

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : new Bundle(args);
        start();
    }

    @Override public void onStart() {
        started = SystemClock.elapsedRealtime();
        sendStatus(1, testStatus("started"));
        try {
            Map<String, Object> supplied = new HashMap<>();
            for (String key : arguments.keySet()) supplied.put(key, arguments.get(key));
            try { config = PlaybackProbeConfig.parse(supplied); }
            catch (IllegalArgumentException invalid) { throw blocked(invalid.getMessage()); }
            Thread deadline = new Thread(() -> {
                SystemClock.sleep(Math.max(0, started + config.budgetMs - SystemClock.elapsedRealtime()));
                finishProbe("failed", "overall_deadline_exceeded");
            }, "playback-qa-deadline");
            deadline.setDaemon(true);
            deadline.start();
            requireIsolatedAvd();
            File evidenceRoot = new File(getTargetContext().getFilesDir(), "playback-qa");
            if (config.batch && config.episodeIndex == 1) {
                String[] priorBatch = evidenceRoot.list();
                if (priorBatch != null && priorBatch.length > 0)
                    throw blocked("fresh_evidence_directory_required");
            }
            File freshOutput = new File(getTargetContext().getFilesDir(), config.outputRelativePath);
            String[] previousEvidence = freshOutput.list();
            if ((previousEvidence != null && previousEvidence.length > 0)
                    || (!freshOutput.isDirectory() && !freshOutput.mkdirs()))
                throw blocked("fresh_evidence_directory_required");
            output = freshOutput;
            synchronized (report) {
                report.put("schema", config.schema).put("status", "running")
                    .put("qa_commit", arguments.getString("qaCommit"))
                    .put("scope", config.scope).put("acceptance_mode", config.acceptanceMode)
                    .put("test_scope", config.testScope).put("batch_ten_episodes", config.batch)
                    .put("required_advance_ms", config.advanceMs)
                    .put("minimum_surface_observations", config.minimumSurfaceObservations)
                    .put("minimum_state_observations", config.minimumStateObservations)
                    .put("minimum_frame_changes", config.minimumFrameChanges)
                    .put("app_version_name", BuildConfig.VERSION_NAME)
                    .put("app_version_code", BuildConfig.VERSION_CODE)
                    .put("series_id", SERIES).put("vid", config.vid).put("episode_index", config.episodeIndex)
                    .put("title", TITLE).put("api_level", Build.VERSION.SDK_INT)
                    .put("source_selection", "production_resolve_in_player_no_url")
                    .put("quality", "auto").put("speed", 1)
                    .put("autoplay", false).put("queue_length", 1)
                    .put("overall_budget_ms", config.budgetMs).put("stages", stages);
                if (config.batch) report.put("full_episode_verified", false).put("source_category", "unverified");
            }
            // Only the first episode requires an empty global playback cache. The normal
            // queue cleanup retains its current episode after close and prunes older entries
            // when the next queue opens. Read names only, before opening that next queue.
            // Never call cache.find (it mutates legacy entries), clear data, or delete caches.
            File cache = new File(getTargetContext().getCacheDir(), "playback-v2");
            String[] cacheFiles = cache.list();
            if (cache.exists() && cacheFiles == null) throw blocked("playback_cache_not_readable");
            boolean initial = !config.batch || config.episodeIndex == 1;
            if (initial && cacheFiles != null && cacheFiles.length != 0)
                throw blocked("fresh_playback_cache_required");
            if (cacheFiles != null) for (String name : cacheFiles)
                if (config.targetCacheEntry(name)) throw blocked("target_episode_cache_already_present");
            JSONObject environment = new JSONObject().put("isolated_avd", true).put("debug_target", true)
                .put("cache_scope", initial ? "cold_first_install" : "independent_episode_identity")
                .put("target_playback_cache_absent", true)
                .put("global_cache_empty", cacheFiles == null || cacheFiles.length == 0)
                .put("production_cleanup_unchanged", true);
            if (initial) environment.put("cold_playback_cache", true);
            record("environment", environment);

            stage = "mute_before_launch";
            automation = getUiAutomation();
            audio = (AudioManager) getTargetContext().getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) throw blocked("audio_service_unavailable");
            // Shell identity is adopted only for the QA emulator's media volume.
            automation.adoptShellPermissionIdentity(android.Manifest.permission.MODIFY_AUDIO_SETTINGS);
            try {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            } finally {
                automation.dropShellPermissionIdentity();
            }
            requireMuted();
            record(stage, new JSONObject().put("music_volume", 0).put("music_stream_muted", true)
                .put("silence_evidence", "android_media_output_stream_zero_and_muted"));

            stage = "main_activity";
            host = (MainActivity) launch(new Intent(getTargetContext(), MainActivity.class), MainActivity.class);
            long hostDeadline = Math.min(started + config.budgetMs, SystemClock.elapsedRealtime() + 35_000);
            while (!onMain(() -> (Boolean) read(host, "loaded"))) {
                requireMuted();
                if (host.isFinishing() || host.isDestroyed()) throw failed("main_activity_closed");
                if (SystemClock.elapsedRealtime() >= hostDeadline) throw blocked("production_webview_not_ready");
                SystemClock.sleep(200);
            }
            record(stage, new JSONObject().put("production_page_ready", true));

            stage = "player_launch";
            JSONObject series = new JSONObject().put("seriesId", SERIES).put("title", TITLE);
            JSONObject episode = new JSONObject().put("vid", config.vid).put("index", config.episodeIndex);
            JSONObject options = new JSONObject().put("quality", "auto").put("speed", 1)
                .put("autoplay", false).put("cleanupPlaybackCache", true);
            Intent intent = PlayLauncher.build(host, series, episode, new JSONArray().put(episode),
                null, false, options);
            if (intent.getStringExtra("url") != null || !intent.getStringExtra("playInfo").isEmpty())
                throw failed("unexpected_source_injection");
            requireMuted();
            player = (PlayerActivity) launch(intent, PlayerActivity.class);
            record(stage, new JSONObject().put("production_intent_builder", true)
                .put("url_supplied", false).put("play_info_supplied", false));

            stage = "first_rendered_frame";
            Sample first = null;
            SurfaceEvidence firstSurface = null;
            long frameDeadline = Math.min(started + config.budgetMs - config.continuousBudgetMs,
                SystemClock.elapsedRealtime() + 150_000);
            while (SystemClock.elapsedRealtime() < frameDeadline) {
                Sample sample = sample();
                requireHealthy(sample, false);
                if (sample.prepared && sample.playing && sample.position > 0
                        && sample.duration > config.advanceMs + 2_000 && sample.width > 0 && sample.height > 0) {
                    SurfaceEvidence surface = copyRenderedSurface(sample.view);
                    if (surface.ready()) {
                        // Start both acceptance clocks only after a real decoder buffer was
                        // observed, never before the asynchronous PixelCopy finishes.
                        Sample afterSurface = sample();
                        requireHealthy(afterSurface, true);
                        requireStable(sample, afterSurface);
                        first = afterSurface; firstSurface = surface; break;
                    }
                }
                SystemClock.sleep(200);
            }
            if (first == null) throw failed("no_verified_rendered_frame");
            actualFirstFrameObserved = true;
            GuestEvidence guest = guestEvidence();
            boolean verifiedGuest = guest.verified && first.cached && !first.manual && !first.remote;
            JSONObject firstEvidence = first.json().put("renderer", firstSurface.json());
            if (verifiedGuest) {
                if (first.width != guest.width || first.height != guest.height
                        || Math.abs(first.duration - guest.durationMs) > Math.max(2_000, guest.durationMs / 50))
                    throw failed("decoded_media_metadata_mismatch");
                firstEvidence.put("official_guest_plan", true)
                    .put("full_mp4_gate", "production_guest_cache_publication_requires_verifier")
                    .put("expected_duration_ms", guest.durationMs)
                    .put("source_category", "official_guest")
                    .put("cache_gate", "production_guest_cache_publication_requires_verifier")
                    .put("duration_validation", "guest_declared_and_complete_file_verified")
                    .put("expected_width", guest.width).put("expected_height", guest.height);
            } else {
                if (!config.batch) throw failed("production_guest_verified_cache_not_observed");
                PlaybackSources.Plan appPlan = appPlanEvidence(first);
                if (appPlan == null) throw blocked("production_app_plan_or_cache_identity_unverified");
                acceptedAppPlan = appPlan;
                firstEvidence.put("official_guest_plan", false)
                    .put("source_category", "production_app")
                    .put("cache_gate", "production_app_cache_publication")
                    .put("duration_validation", "decoder_reported_only")
                    .put("plan_binding", "unique_registered_plan_and_protected_cache_identity")
                    .put("plan_identity_stable", true)
                    .put("expected_width", appPlan.width).put("expected_height", appPlan.height);
            }
            if (config.batch) {
                firstEvidence.put("full_episode_verified", false);
                synchronized (report) {
                    report.put("source_category", firstEvidence.getString("source_category"))
                        .put("cache_gate", firstEvidence.getString("cache_gate"))
                        .put("duration_validation", firstEvidence.getString("duration_validation"));
                }
            }
            saveScreenshot("first-frame.png", first);
            record(stage, firstEvidence.put("screenshot", "first-frame.png"));

            stage = config.continuedStage;
            Sample last = first;
            long lastAdvance = first.elapsed, maxNoAdvance = 0;
            long lastSurfaceCheck = first.elapsed, lastFrameChange = first.elapsed;
            long lastFrameHash = firstSurface.hash, maxFrameUnchanged = 0;
            int sampleCount = 1;
            int surfaceObservations = 1, frameChanges = 0;
            while (true) {
                SystemClock.sleep(250);
                Sample current = sample();
                sampleCount++;
                requireHealthy(current, true);
                requireStable(first, current);
                if (current.width != first.width || current.height != first.height
                        || current.duration != first.duration)
                    throw failed("decoded_metadata_changed");
                if (current.position + 100 < last.position) throw failed("playback_position_reversed");
                long gap = current.elapsed - lastAdvance;
                maxNoAdvance = Math.max(maxNoAdvance, gap);
                if (current.position > last.position + 20) lastAdvance = current.elapsed;
                if (gap >= STALL_MS) throw failed("playback_stalled");
                if (current.elapsed - lastSurfaceCheck >= 1_000) {
                    SurfaceEvidence surface = copyRenderedSurface(current.view);
                    long checkedAt = SystemClock.elapsedRealtime();
                    lastSurfaceCheck = checkedAt;
                    long frameGap = checkedAt - lastFrameChange;
                    maxFrameUnchanged = Math.max(maxFrameUnchanged, frameGap);
                    if (surface.ready()) {
                        surfaceObservations++;
                        if (surface.hash != lastFrameHash) {
                            frameChanges++;
                            lastFrameHash = surface.hash;
                            lastFrameChange = checkedAt;
                        }
                    }
                    // Conservative by design: a real five-second still scene can fail.
                    // The media clock alone cannot exclude a frozen video renderer.
                    if (frameGap >= STALL_MS) throw failed("renderer_unchanged_or_unavailable_5s");
                }
                last = current;
                if (config.batch && current.elapsed - first.elapsed > config.continuousBudgetMs)
                    throw failed("insufficient_5s_position_advance");
                // Screenshot/readback overhead can reduce the sample count at exactly five
                // seconds. Keep observing within the same bound instead of lowering a gate.
                if (config.observationWindowSatisfied(current.elapsed - first.elapsed,
                        current.position - first.position, sampleCount, surfaceObservations, frameChanges)) break;
                if (current.elapsed - first.elapsed > config.continuousBudgetMs)
                    throw failed(config.batch ? "insufficient_5s_position_advance" : "insufficient_30s_position_advance");
            }
            SurfaceEvidence finalSurface = copyRenderedSurface(last.view);
            if (!finalSurface.ready()) throw failed("final_surface_has_no_video_frame");
            if (finalSurface.hash == firstSurface.hash)
                throw failed(config.batch ? "unchanged_renderer_frame_after_5s" : "unchanged_renderer_frame_after_30s");
            surfaceObservations++;
            long finalFrameGap = SystemClock.elapsedRealtime() - lastFrameChange;
            maxFrameUnchanged = Math.max(maxFrameUnchanged, finalFrameGap);
            if (finalFrameGap >= STALL_MS) throw failed("renderer_unchanged_or_unavailable_5s");
            if (finalSurface.hash != lastFrameHash) frameChanges++;
            if (!config.enoughObservations(sampleCount, surfaceObservations, frameChanges))
                throw failed("insufficient_actual_frame_observations");
            saveScreenshot(config.finalScreenshot, first);
            JSONObject continuedEvidence = last.json().put("renderer", finalSurface.json())
                .put("wall_elapsed_ms", last.elapsed - first.elapsed)
                .put("position_advance_ms", last.position - first.position)
                .put("observations", sampleCount).put("max_no_advance_ms", maxNoAdvance)
                .put("surface_observations", surfaceObservations).put("frame_changes", frameChanges)
                .put("max_frame_unchanged_ms", maxFrameUnchanged)
                .put("same_episode_and_player", true).put("required_advance_ms", config.advanceMs)
                .put("screenshot", config.finalScreenshot);
            if (acceptedAppPlan != null) continuedEvidence.put("plan_identity_stable", true)
                .put("source_category", "production_app").put("cache_gate", "production_app_cache_publication")
                .put("plan_binding", "unique_registered_plan_and_protected_cache_identity")
                .put("duration_validation", "decoder_reported_only").put("full_episode_verified", false)
                .put("expected_width", acceptedAppPlan.width).put("expected_height", acceptedAppPlan.height);
            record(stage, continuedEvidence);
            finishProbe("passed", config.passReason);
        } catch (ProbeProblem problem) {
            finishProbe(problem.status, problem.code);
        } catch (Throwable problem) {
            // Do not expose exception text, stack traces, signed URLs, or model data.
            finishProbe("failed", "probe_internal_error");
        }
    }

    private void requireIsolatedAvd() throws ProbeProblem {
        if (!"true".equals(arguments.getString("isolatedQaAvd"))) throw blocked("isolated_avd_opt_in_required");
        String commit = arguments.getString("qaCommit", "");
        if (!commit.matches("[0-9a-f]{40}")) throw blocked("full_qa_commit_required");
        if (Build.VERSION.SDK_INT != 30 || !Arrays.asList(Build.SUPPORTED_ABIS).contains("x86_64")
                || !("ranchu".equals(Build.HARDWARE) || "goldfish".equals(Build.HARDWARE)))
            throw blocked("api30_x86_64_emulator_required");
        Context target = getTargetContext();
        if (!PACKAGE.equals(target.getPackageName()) || BuildConfig.IS_TV
                || (target.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0)
            throw blocked("phone_debug_target_required");
    }

    private Activity launch(Intent intent, Class<? extends Activity> type) throws Exception {
        ActivityMonitor monitor = addMonitor(type.getName(), null, false);
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            onMain(() -> { getTargetContext().startActivity(intent); return null; });
            Activity activity = monitor.waitForActivityWithTimeout(15_000);
            if (activity == null) throw failed("activity_launch_timeout");
            return activity;
        } finally { removeMonitor(monitor); }
    }

    private void requireMuted() throws ProbeProblem {
        if (audio == null || audio.getStreamVolume(AudioManager.STREAM_MUSIC) != 0
                || !audio.isStreamMute(AudioManager.STREAM_MUSIC)) throw failed("media_output_not_muted");
    }

    private Sample sample() throws Exception {
        requireMuted();
        guestPlanObserved |= guestEvidence().observed;
        return onMain(() -> {
            Sample s = new Sample();
            s.elapsed = SystemClock.elapsedRealtime();
            s.closed = player.isFinishing() || player.isDestroyed() || (Boolean) read(player, "destroyed");
            JSONObject series = (JSONObject) read(player, "series");
            JSONObject episode = (JSONObject) read(player, "episode");
            s.sameEpisode = series != null && episode != null && SERIES.equals(series.optString("seriesId"))
                && config.vid.equals(episode.optString("vid")) && episode.optInt("index") == config.episodeIndex;
            s.prepared = (Boolean) read(player, "prepared");
            s.active = (Boolean) read(player, "active");
            s.completed = (Boolean) read(player, "completed");
            s.paused = (Boolean) read(player, "userPaused");
            s.buffering = (Boolean) read(player, "buffering");
            s.historyErrorIdentity = read(player, "lastMediaError");
            s.historyErrorPresent = s.historyErrorIdentity != null;
            s.hudDialogPresent = (Integer) read(player, "hudDialogs") > 0;
            s.decoderPreparing = (Boolean) read(player, "decoderPreparing");
            s.terminalDialog = PlaybackFailureCategory.terminalDialogState(s.hudDialogPresent, s.prepared, s.decoderPreparing);
            Future<?> resolution = (Future<?>) read(player, "resolution");
            s.resolutionPending = resolution != null && !resolution.isDone();
            TextView statusView = (TextView) read(player, "status");
            Diagnostic observed = new Diagnostic();
            observed.hudDialogPresent = s.hudDialogPresent;
            observed.terminalDialog = s.terminalDialog;
            observed.historyErrorPresent = s.historyErrorPresent;
            observed.decoderPreparing = s.decoderPreparing;
            observed.resolutionPending = s.resolutionPending;
            observed.statusTextPresent = statusView != null && statusView.getText() != null && statusView.getText().length() > 0;
            // Input text remains in memory only. Every serialized category is an enum constant.
            observed.statusCategory = PlaybackFailureCategory.classify(statusView == null || statusView.getText() == null ? null : statusView.getText().toString());
            observed.historyCategory = PlaybackFailureCategory.classify(s.historyErrorIdentity instanceof String ? (String) s.historyErrorIdentity : null);
            diagnostic = observed;
            if ("first_rendered_frame".equals(stage) && s.historyErrorPresent) historyObservedBeforeFrame = true;
            s.cached = (Boolean) read(player, "currentCache");
            s.manual = (Boolean) read(player, "currentManual");
            s.remote = (Boolean) read(player, "currentWeb");
            if (config.batch) synchronized (player) {
                JSONObject protectedEpisode = (JSONObject) read(player, "protectedEpisode");
                s.protectedCacheIdentity = (Boolean) read(player, "protectedCurrent")
                    && SERIES.equals(read(player, "protectedSeriesId"))
                    && protectedEpisode != null && config.vid.equals(protectedEpisode.optString("vid"))
                    && config.episodeIndex == protectedEpisode.optInt("index")
                    && "auto".equals(read(player, "actualQuality"))
                    && "auto".equals(read(player, "protectedQuality"))
                    && (Integer) read(player, "protectedCeiling") == 0;
            }
            s.generation = (Integer) read(player, "generation");
            s.view = (VideoView) read(player, "video");
            s.player = (MediaPlayer) read(player, "mediaPlayer");
            if (s.prepared && s.player != null) {
                s.playing = s.player.isPlaying() && s.view.isPlaying();
                s.position = s.player.getCurrentPosition();
                s.duration = s.player.getDuration();
                s.width = s.player.getVideoWidth();
                s.height = s.player.getVideoHeight();
            }
            return s;
        });
    }

    private void requireHealthy(Sample s, boolean continuous) throws ProbeProblem {
        if (s.closed) throw failed("production_player_closed");
        if (!s.sameEpisode) throw failed("episode_identity_changed");
        if (s.terminalDialog) throw failed("production_terminal_playback_dialog");
        if (continuous && s.hudDialogPresent) throw failed("production_dialog_after_first_frame");
        if (s.completed) throw failed("episode_completed_before_acceptance");
        if (continuous && (!s.active || !s.prepared || !s.playing || s.paused || s.buffering))
            throw failed("playback_interrupted_after_first_frame");
    }

    private void requireStable(Sample first, Sample current) throws Exception {
        if (current.player != first.player || current.view != first.view || current.generation != first.generation)
            throw failed("player_or_source_changed_after_first_frame");
        if (current.historyErrorIdentity != first.historyErrorIdentity)
            throw failed("new_media_error_after_first_frame");
        if (acceptedAppPlan != null && !PlaybackProbeSourcePolicy.sameRegisteredPlan(acceptedAppPlan, appPlanEvidence(current)))
            throw failed("production_app_plan_or_cache_identity_changed");
    }

    private PlaybackSources.Plan appPlanEvidence(Sample sample) throws Exception {
        // The player does not retain its PreparedMedia Plan after adoption. Do not
        // pretend to read a captured-plan field or inspect framework decoder internals.
        // A single matching registry entry + the current protected cache identity is
        // conservative opening evidence, not proof of the complete episode's duration.
        Map<?, ?> plans = (Map<?, ?>) readStatic(PlaybackSources.class, "plans");
        PlaybackSources.Plan only = null;
        PlaybackProbeSourcePolicy.AppEvidence e = new PlaybackProbeSourcePolicy.AppEvidence();
        synchronized (plans) {
            for (Object value : plans.values()) {
                PlaybackSources.Plan plan = (PlaybackSources.Plan) value;
                if (!PlaybackProbeSourcePolicy.selectedIdentity(plan.seriesId, plan.vid, SERIES, config.vid)) continue;
                e.matchingPlans++; only = plan;
            }
        }
        if (only == null) return null;
        e.batch = config.batch; e.source = only.source; e.guest = only.guestPlayback;
        e.requestedQuality = only.requestedQuality; e.playbackCeiling = only.playbackCeiling;
        e.planWidth = only.width; e.planHeight = only.height;
        e.decodedWidth = sample.width; e.decodedHeight = sample.height;
        e.protectedIdentity = sample.protectedCacheIdentity;
        e.cached = sample.cached; e.manual = sample.manual; e.remote = sample.remote;
        return PlaybackProbeSourcePolicy.acceptsApp(e) ? only : null;
    }

    private GuestEvidence guestEvidence() throws Exception {
        // Read only provenance and numerical metadata; never read keys/URLs or serialize a Plan.
        Map<?, ?> plans = (Map<?, ?>) readStatic(PlaybackSources.class, "plans");
        GuestEvidence evidence = new GuestEvidence();
        boolean conflictingPlan = false;
        synchronized (plans) {
            for (Object value : plans.values()) {
                PlaybackSources.Plan plan = (PlaybackSources.Plan) value;
                if (!SERIES.equals(plan.seriesId) || !config.vid.equals(plan.vid)) continue;
                evidence.observed |= plan.guestPlayback;
                if (!plan.guestPlayback || !"auto".equals(plan.requestedQuality)) {
                    conflictingPlan = true;
                } else {
                    evidence.verified = true;
                    evidence.width = plan.width;
                    evidence.height = plan.height;
                    evidence.durationMs = Math.round(plan.expectedDurationSeconds * 1000);
                }
            }
        }
        evidence.verified &= !conflictingPlan;
        return evidence;
    }

    private SurfaceEvidence copyRenderedSurface(VideoView view) throws Exception {
        SurfaceEvidence evidence = new SurfaceEvidence();
        Bitmap bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
        CountDownLatch copied = new CountDownLatch(1);
        try {
            onMain(() -> {
                if (view == null || !view.isShown() || !view.getHolder().getSurface().isValid()) {
                    evidence.result = PixelCopy.ERROR_SOURCE_INVALID;
                    copied.countDown();
                } else {
                    // A SurfaceView copy reads the decoder's buffer, not the surrounding UI.
                    // ERROR_SOURCE_NO_DATA is not success and cannot satisfy this assertion.
                    PixelCopy.request(view, bitmap, result -> { evidence.result = result; copied.countDown(); }, main);
                }
                return null;
            });
            if (!copied.await(4, TimeUnit.SECONDS)) throw blocked("renderer_readback_timeout");
            if (evidence.result != PixelCopy.SUCCESS) return evidence;
            int[] pixels = new int[160 * 160];
            bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 160);
            int min = 255, max = 0, bright = 0;
            long hash = 0xcbf29ce484222325L;
            for (int pixel : pixels) {
                int luma = (Color.red(pixel) * 77 + Color.green(pixel) * 150 + Color.blue(pixel) * 29) >> 8;
                min = Math.min(min, luma); max = Math.max(max, luma);
                if (luma > 12) bright++;
                hash = (hash ^ pixel) * 0x100000001b3L;
            }
            evidence.lumaRange = max - min;
            evidence.brightPermille = bright * 1000 / pixels.length;
            evidence.hash = hash;
            return evidence;
        } finally {
            // A timed-out copy may still have a native writer. Do not recycle its buffer.
            if (copied.getCount() == 0) bitmap.recycle();
        }
    }

    private void saveScreenshot(String name, Sample baseline) throws Exception {
        requireMuted();
        Sample before = sample();
        requireHealthy(before, true);
        requireStable(baseline, before);
        Bitmap screenshot = automation.takeScreenshot();
        if (screenshot == null) throw blocked("app_screenshot_unavailable");
        try {
            // A terminal error dialog persists until user action. Recheck before writing
            // so a screenshot racing that transition is discarded, never published.
            Sample after = sample();
            requireHealthy(after, true);
            requireStable(baseline, after);
            try (FileOutputStream file = new FileOutputStream(new File(output, name))) {
                if (!screenshot.compress(Bitmap.CompressFormat.PNG, 100, file)) throw blocked("app_screenshot_failed");
            }
        } finally { screenshot.recycle(); }
    }

    private <T> T onMain(Callable<T> operation) throws Exception {
        if (finished.get()) throw failed("probe_already_finished");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        main.post(() -> {
            if (!finished.get()) {
                try { result.set(operation.call()); } catch (Throwable error) { failure.set(error); }
            }
            done.countDown();
        });
        if (!done.await(8, TimeUnit.SECONDS)) throw blocked("app_main_thread_unresponsive");
        if (failure.get() != null) throw blocked("production_state_observation_failed");
        return result.get();
    }

    private static Object read(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static Object readStatic(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private void record(String name, JSONObject evidence) throws Exception {
        synchronized (report) {
            stages.put(new JSONObject().put("stage", name).put("status", "passed")
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("evidence", evidence));
            writeReport();
        }
    }

    private void writeReport() throws Exception {
        if (output == null) return;
        try (FileOutputStream file = new FileOutputStream(new File(output, "report.json"))) {
            file.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void finishProbe(String status, String reason) {
        if (!finished.compareAndSet(false, true)) return;
        boolean pass = "passed".equals(status);
        try {
            synchronized (report) {
                report.put("status", status).put("reason", reason).put("final_stage", stage)
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                    .put("guest_plan_observed", guestPlanObserved)
                    .put("actual_first_frame_observed", actualFirstFrameObserved)
                    .put("historical_error_observed_before_first_frame", historyObservedBeforeFrame)
                    .put("playback_diagnostic", diagnostic.json());
                if (!pass) stages.put(new JSONObject().put("stage", stage).put("status", status).put("reason", reason));
                report.put("stages", stages);
                writeReport();
            }
        } catch (Exception ignored) { pass = false; status = "failed"; reason = "evidence_write_failed"; }
        // Keep the emulator muted. Never restore a potentially audible previous volume.
        main.post(() -> { if (player != null) player.finish(); if (host != null) host.finish(); });
        Bundle result = testStatus(status);
        result.putString("qa_status", status);
        result.putString("qa_reason", reason);
        result.putString("qa_stage", stage);
        result.putString("stream", pass ? "\nOK (1 test)\n" : "\nFAILURES!!!\nPlayback acceptance did not pass.\n");
        sendStatus(pass ? 0 : -2, result);
        finish(pass ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private Bundle testStatus(String status) {
        Bundle result = new Bundle();
        result.putString("id", "ProductionPlaybackInstrumentation");
        result.putString("class", getClass().getName());
        result.putString("test", "productionGuestPlayback");
        result.putInt("numtests", 1);
        result.putInt("current", 1);
        result.putString("qa_status", status);
        return result;
    }

    private static ProbeProblem failed(String code) { return new ProbeProblem("failed", code); }
    private static ProbeProblem blocked(String code) { return new ProbeProblem("blocked", code); }

    private static final class ProbeProblem extends Exception {
        final String status, code;
        ProbeProblem(String status, String code) { this.status = status; this.code = code; }
    }

    private static final class GuestEvidence {
        boolean verified, observed;
        int width, height;
        long durationMs;
    }

    private static final class Diagnostic {
        boolean statusTextPresent, historyErrorPresent, hudDialogPresent, terminalDialog;
        boolean decoderPreparing, resolutionPending;
        PlaybackFailureCategory.Category statusCategory = PlaybackFailureCategory.Category.NONE;
        PlaybackFailureCategory.Category historyCategory = PlaybackFailureCategory.Category.NONE;

        JSONObject json() throws Exception {
            return new JSONObject().put("status_text_present", statusTextPresent)
                .put("last_media_error_present", historyErrorPresent).put("hud_dialog_present", hudDialogPresent)
                .put("terminal_dialog_state", terminalDialog).put("decoder_preparing", decoderPreparing)
                .put("resolution_pending", resolutionPending).put("status_category", statusCategory.code)
                .put("last_media_error_category", historyCategory.code)
                .put("status_error_classified", statusCategory.knownError())
                .put("historical_error_classified", historyCategory.knownError());
        }
    }

    private static final class SurfaceEvidence {
        int result = PixelCopy.ERROR_UNKNOWN, lumaRange, brightPermille;
        long hash;
        boolean ready() { return result == PixelCopy.SUCCESS && lumaRange >= 16 && brightPermille >= 20; }
        JSONObject json() throws Exception {
            return new JSONObject().put("proof", "actual_video_surface_pixelcopy_buffer")
                .put("pixelcopy_success", result == PixelCopy.SUCCESS).put("luma_range", lumaRange)
                .put("nonblack_permille", brightPermille).put("frame_hash", Long.toHexString(hash));
        }
    }

    private static final class Sample {
        long elapsed;
        int position, duration, width, height, generation;
        boolean closed, sameEpisode, prepared, active, completed, paused, buffering, playing;
        boolean historyErrorPresent, hudDialogPresent, terminalDialog, decoderPreparing, resolutionPending;
        Object historyErrorIdentity;
        boolean cached, manual, remote, protectedCacheIdentity;
        VideoView view;
        MediaPlayer player;
        JSONObject json() throws Exception {
            return new JSONObject().put("position_ms", position).put("duration_ms", duration)
                .put("video_width", width).put("video_height", height).put("generation", generation)
                .put("playing", playing).put("same_episode", sameEpisode).put("buffering", buffering)
                .put("production_cache", cached).put("manual_source", manual).put("remote_source", remote)
                .put("protected_cache_identity", protectedCacheIdentity)
                .put("historical_error_present", historyErrorPresent).put("hud_dialog_present", hudDialogPresent)
                .put("terminal_dialog_state", terminalDialog)
                .put("music_volume", 0).put("music_stream_muted", true);
        }
    }
}
