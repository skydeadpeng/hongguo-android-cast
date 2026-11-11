package io.github.jackotom.hongguo.android;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** App-private temporary playback files only. No DownloadManager IDs or user paths enter this class. */
final class PlaybackCachePolicy {
    interface Downloader { void download(String url, File destination, long maxBytes) throws Exception; }
    static final class Stats {
        final int files, removed; final long bytes;
        Stats(int files, long bytes, int removed) { this.files = files; this.bytes = bytes; this.removed = removed; }
    }
    private static final Pattern OWNED = Pattern.compile("([a-f0-9]{64})_([0-9]{1,10})_([a-f0-9]{64})\\.mp4(\\.part)?");
    private final File directory;
    private final long defaultBudgetBytes, castBudgetBytes, maxFileBytes;
    private final Downloader downloader;
    private final Map<String, Integer> protectedKeys = new HashMap<>();
    private final Map<String, Flight> flights = new HashMap<>();
    private final List<CastWindow> castWindows = new ArrayList<>();
    private final List<QueueWindow> queueWindows = new ArrayList<>();
    // Closed windows can still be held by a reader callback. Keep only tiny latest
    // retention states in those callbacks, not newer sessions or entire queues.
    private final Set<QueueWindow> knownQueueWindows = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean initialized;
    private boolean trimAfterRelease;

    /** A bounded retention lease, not a file reader; protectedKeys still owns reader safety. */
    final class CastWindow implements AutoCloseable {
        private final String series;
        private final boolean cleanup;
        private int currentIndex;
        private boolean closed;
        private CastWindow(String series, int currentIndex, boolean cleanup) { this.series = series; this.currentIndex = currentIndex; this.cleanup = cleanup; }
        void moveTo(int index) {
            if (index < 0) throw new IllegalArgumentException("缓存集数无效");
            synchronized (PlaybackCachePolicy.this) {
                if (closed) return;
                currentIndex = index;
                if (cleanup) pruneWindowQuietly(series, index);
            }
        }
        @Override public void close() {
            synchronized (PlaybackCachePolicy.this) {
                if (closed) return;
                closed = true;
                castWindows.remove(this);
                // Downloads from this lease must not refill an already closed cast window.
                if (!hasCastWindow(series)) for (Map.Entry<String, Flight> item : flights.entrySet())
                    if (item.getValue().series.equals(series) && !protectedKeys.containsKey(item.getKey())) item.getValue().obsolete = true;
                trimAfterRelease = true;
                if (cleanup) pruneWindowQuietly(series, currentIndex);
                else trimDeferredBudget();
            }
        }
    }

    /** True series/index coordinates for a bounded flat playback queue; never a cache-key rewrite. */
    static final class QueueEntry {
        final String series; final int index;
        QueueEntry(String sid, int index) {
            if (index < 1 || index > 10000) throw new IllegalArgumentException("缓存集数无效");
            this.series = hash(sid); this.index = index;
        }
        String coordinate() { return series + ":" + index; }
    }
    private static final class QueueRetention {
        final boolean cleanup;
        final Set<Integer> indexes = new HashSet<>();
        QueueRetention(boolean cleanup) { this.cleanup = cleanup; }
    }
    final class QueueWindow implements AutoCloseable {
        private final List<QueueEntry> entries;
        private final Set<String> scope = new HashSet<>();
        private final Map<String,QueueRetention> ownRetention = new HashMap<>(), latestRetention = new HashMap<>();
        private final boolean cleanup, casting;
        private final int ahead;
        private int current;
        private boolean closed;
        private QueueWindow(List<QueueEntry> entries,int current,boolean cleanup,int ahead,boolean casting) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries)); this.current=current;this.cleanup=cleanup;this.ahead=ahead;this.casting=casting;
            for(QueueEntry entry:entries)scope.add(entry.series);
            for(String series:scope)ownRetention.put(series,new QueueRetention(cleanup));
            latestRetention.putAll(ownRetention);refreshRetention();
        }
        private void refreshRetention() {
            for(QueueRetention retention:ownRetention.values())retention.indexes.clear();
            for(int i=Math.max(0,current-1);i<=Math.min(entries.size()-1,current+(closed?1:ahead));i++) {
                QueueEntry entry=entries.get(i);ownRetention.get(entry.series).indexes.add(entry.index);
            }
        }
        private boolean contains(String series,int index) {
            int future=closed?1:ahead;
            for(int i=Math.max(0,current-1);i<=Math.min(entries.size()-1,current+future);i++) {
                QueueEntry entry=entries.get(i);if(entry.series.equals(series)&&entry.index==index)return true;
            }
            return false;
        }
        private boolean ownsCleanup(String series) { return scope.contains(series) && latestRetention.get(series)==ownRetention.get(series); }
        private boolean shouldPrune(String series,int index) {
            QueueRetention retention=latestRetention.get(series);
            return retention!=null&&retention.cleanup&&!retention.indexes.contains(index);
        }
        void moveTo(int position) {
            synchronized(PlaybackCachePolicy.this) {
                if(position<0||position>=entries.size())throw new IllegalArgumentException("缓存队列位置无效");
                if(closed)return;current=position;refreshRetention();prune();
            }
        }
        /** Also usable by a late reader release after close; only this queue's owned cache is eligible. */
        void prune() {
            synchronized(PlaybackCachePolicy.this) {
                try { pruneQueue(this); } catch(IOException failure) { trimAfterRelease=true; }
            }
        }
        @Override public void close() {
            synchronized(PlaybackCachePolicy.this) {
                if(closed)return;closed=true;refreshRetention();queueWindows.remove(this);trimAfterRelease=true;
                // A detached background transfer must not refill a finished session.
                for(Map.Entry<String,Flight> pending:flights.entrySet()) {
                    Flight flight=pending.getValue();
                    if(ownsCleanup(flight.series)&&!hasCastWindow(flight.series)&&!protectedKeys.containsKey(pending.getKey()))flight.obsolete=true;
                }
                prune();trimDeferredBudget();
            }
        }
    }
    synchronized QueueWindow openQueueWindow(List<QueueEntry> entries,int position,boolean cleanup,int ahead,boolean casting) {
        if(entries==null||entries.isEmpty()||entries.size()>1000||position<0||position>=entries.size()||ahead<1||ahead>5)throw new IllegalArgumentException("缓存播放队列无效");
        Set<String> coordinates=new HashSet<>();
        for(QueueEntry entry:entries)if(entry==null||!coordinates.add(entry.coordinate()))throw new IllegalArgumentException("缓存播放队列重复");
        QueueWindow window=new QueueWindow(entries,position,cleanup,ahead,casting);
        for(QueueWindow prior:knownQueueWindows)for(String series:window.scope)if(prior.scope.contains(series))prior.latestRetention.put(series,window.ownRetention.get(series));
        knownQueueWindows.add(window);queueWindows.add(window);window.prune();return window;
    }
    private void pruneQueue(QueueWindow window) throws IOException {
        for(Map.Entry<String,Flight> pending:flights.entrySet()) {
            Flight flight=pending.getValue();
            if(window.shouldPrune(flight.series,flight.index)&&!inCastWindow(flight.series,flight.index)&&!protectedKeys.containsKey(pending.getKey()))flight.obsolete=true;
        }
        for(Entry entry:entries())if(window.shouldPrune(entry.series,entry.index)&&!inCastWindow(entry.series,entry.index)&&!protectedOrActive(entry.key))delete(entry.file);
        fitBudget(reservations());
    }

    private static final class Flight {
        final String series; final int index;
        final boolean foreground;
        boolean complete, obsolete, invalidated; File result; Exception error;
        Flight(String series, int index, boolean foreground) { this.series = series; this.index = index; this.foreground = foreground; }
    }
    private static final class Entry {
        final File file; final String key, series; final int index; final long bytes;
        Entry(File file, Matcher match) throws IOException {
            this.file = file; key = file.getName(); series = match.group(1);
            try { index = Integer.parseInt(match.group(2)); } catch (NumberFormatException error) { throw new IOException("缓存集数无效", error); }
            bytes = file.length();
        }
    }

    PlaybackCachePolicy(File directory, long budgetBytes, long maxFileBytes, Downloader downloader) {
        this(directory, budgetBytes, maxFileBytes, budgetBytes, downloader);
    }
    PlaybackCachePolicy(File directory, long budgetBytes, long maxFileBytes, long castBudgetBytes, Downloader downloader) {
        if (directory == null || downloader == null || maxFileBytes <= 0 || budgetBytes < maxFileBytes || castBudgetBytes < budgetBytes) throw new IllegalArgumentException("缓存预算无效");
        this.directory = directory; this.defaultBudgetBytes = budgetBytes; this.castBudgetBytes = castBudgetBytes; this.maxFileBytes = maxFileBytes; this.downloader = downloader;
    }
    synchronized CastWindow openCastWindow(String sid, int currentIndex) {
        return openCastWindow(sid, currentIndex, true);
    }
    synchronized CastWindow openCastWindow(String sid, int currentIndex, boolean cleanup) {
        if (currentIndex < 0) throw new IllegalArgumentException("缓存集数无效");
        CastWindow window = new CastWindow(hash(sid), currentIndex, cleanup);
        castWindows.add(window);
        if (cleanup) pruneWindowQuietly(window.series, currentIndex);
        return window;
    }
    synchronized long budgetBytes() {
        if(!castWindows.isEmpty())return castBudgetBytes;
        for(QueueWindow window:queueWindows)if(window.casting)return castBudgetBytes;
        return defaultBudgetBytes;
    }
    private boolean hasCastWindow(String series) {
        for (CastWindow window : castWindows) if (window.series.equals(series)) return true;
        for (QueueWindow window : queueWindows) if(window.scope.contains(series))return true;
        return false;
    }
    private boolean inCastWindow(String series, int index) {
        for (CastWindow window : castWindows)
            if (window.series.equals(series) && index >= Math.max(0L, (long) window.currentIndex - 1) && index <= (long) window.currentIndex + 5) return true;
        for (QueueWindow window : queueWindows) if(window.contains(series,index))return true;
        return false;
    }
    private void pruneWindowQuietly(String series, int currentIndex) {
        try { pruneSeries(series, currentIndex, 1); }
        catch (IOException ignored) { trimAfterRelease = true; }
    }
    private void trimDeferredBudget() {
        if (!trimAfterRelease) return;
        try { fitBudget(reservations()); trimAfterRelease = false; }
        catch (IOException ignored) { /* A remaining reader/transfer can temporarily prevent shrinking. */ }
    }
    private static String hash(String value) {
        if (value == null || value.isEmpty() || value.length() > 4096) throw new IllegalArgumentException("缓存标识无效");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static String key(String sid, String vid, int index) {
        if (index < 0) throw new IllegalArgumentException("缓存集数无效");
        return hash(sid) + "_" + index + "_" + hash(vid) + ".mp4";
    }
    private boolean withinDirectory(File file) throws IOException {
        return !Files.isSymbolicLink(file.toPath()) && directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile());
    }
    private void ensureDirectory() throws IOException {
        if (Files.isSymbolicLink(directory.toPath())) throw new IOException("缓存目录无效");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("创建播放缓存失败");
        if (!initialized) {
            File[] entries = directory.listFiles();
            if (entries == null) throw new IOException("读取播放缓存失败");
            for (File file : entries) if (OWNED.matcher(file.getName()).matches() && file.getName().endsWith(".part") && withinDirectory(file) && file.isFile()) delete(file);
            initialized = true;
        }
    }
    private List<Entry> entries() throws IOException {
        ensureDirectory();
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("读取播放缓存失败");
        List<Entry> entries = new ArrayList<>();
        for (File file : children) {
            Matcher match = OWNED.matcher(file.getName());
            if (!match.matches() || match.group(4) != null || !withinDirectory(file) || !file.isFile()) continue;
            entries.add(new Entry(file, match));
        }
        return entries;
    }
    private static void delete(File file) throws IOException {
        if (!file.delete() && file.exists()) throw new IOException("清理播放缓存失败");
    }
    synchronized File find(String sid, String vid, int index) throws IOException { return findKey(key(sid, vid, index)); }
    private File findKey(String key) throws IOException {
        ensureDirectory();
        File file = new File(directory, key);
        if (!withinDirectory(file) || !file.isFile()) return null;
        if (file.length() <= 0 || file.length() > maxFileBytes) { if (!protectedKeys.containsKey(key)) delete(file); return null; }
        file.setLastModified(System.currentTimeMillis());
        return file;
    }
    synchronized void protect(String sid, String vid, int index) {
        String key = key(sid, vid, index);
        protectedKeys.put(key, protectedKeys.getOrDefault(key, 0) + 1);
        Flight flight = flights.get(key);
        if (flight != null) flight.obsolete = false;
    }
    synchronized void release(String sid, String vid, int index) {
        String key = key(sid, vid, index);
        int count = protectedKeys.getOrDefault(key, 0);
        if (count <= 1) protectedKeys.remove(key); else protectedKeys.put(key, count - 1);
        trimDeferredBudget();
    }
    private boolean protectedOrActive(String key) { return protectedKeys.containsKey(key) || flights.containsKey(key); }
    private long reservations() { return maxFileBytes * flights.size(); }
    /** Refuse another download if all remaining bytes belong to protected/current work. */
    private void fitBudget(long reservation) throws IOException {
        List<Entry> entries = entries();
        long total = 0;
        for (Entry entry : entries) total += entry.bytes;
        // Keep the five-ahead window ahead of ordinary history in LRU order, but do
        // not make released old-quality variants immortal. Prepared/reading files
        // have protectedKeys leases; only those may defer a hard budget trim.
        entries.sort(Comparator.<Entry>comparingInt(entry -> inCastWindow(entry.series, entry.index) ? 1 : 0)
            .thenComparingLong(entry -> entry.file.lastModified()));
        long budgetBytes = budgetBytes();
        for (Entry entry : entries) {
            if (total + reservation <= budgetBytes) break;
            if (!protectedOrActive(entry.key)) { delete(entry.file); total -= entry.bytes; }
        }
        if (total + reservation > budgetBytes) throw new IOException("播放缓存预算已满，使用在线播放");
    }
    File fetch(String sid, String vid, int index, String url) throws Exception {
        return fetch(sid, vid, index, url, downloader);
    }
    File fetch(String sid, String vid, int index, String url, Downloader transfer) throws Exception {
        return fetch(sid, vid, index, url, transfer, false);
    }
    File fetchForeground(String sid, String vid, int index, String url, Downloader transfer) throws Exception {
        return fetch(sid, vid, index, url, transfer, true);
    }
    private File fetch(String sid, String vid, int index, String url, Downloader transfer, boolean foreground) throws Exception {
        if (transfer == null) throw new IllegalArgumentException("缓存下载任务无效");
        String key = key(sid, vid, index);
        Flight flight;
        boolean tookOver = false;
        for (;;) { synchronized (this) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("播放缓存任务已取消");
            File hit = findKey(key);
            if (hit != null) return hit;
            flight = flights.get(key);
            if (flight != null) {
                while (!flight.complete) {
                    try { wait(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("播放缓存任务已取消", error); }
                }
                if (flight.error != null) {
                    // An opportunistic prefetch must not poison its foreground waiter.
                    // Leave this monitor before starting the caller's one fresh transfer.
                    // Cancellation belongs to the old background generation, not to a new
                    // foreground waiter (for example Activity -> cast Service handoff).
                    if (foreground && !flight.foreground && !tookOver && !Thread.currentThread().isInterrupted()) { tookOver = true; continue; }
                    throw flight.error;
                }
                return flight.result;
            }
            fitBudget(reservations() + maxFileBytes);
            flight = new Flight(hash(sid), index, foreground);
            flights.put(key, flight);
            break;
        } }
        File part = new File(directory, key + ".part"), target = new File(directory, key);
        File result = null; Exception failure = null;
        try {
            if (!withinDirectory(part) || !withinDirectory(target)) throw new IOException("缓存文件路径无效");
            if (part.exists()) delete(part);
            transfer.download(url, part, maxFileBytes);
            synchronized (this) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("播放缓存任务已取消");
                if (flight.invalidated) throw new IOException("播放缓存已失效");
                if (flight.obsolete && !protectedKeys.containsKey(key)) throw new IOException("播放缓存预取已过期");
                if (!withinDirectory(part) || !part.isFile() || part.length() <= 0 || part.length() > maxFileBytes) throw new IOException("播放缓存大小无效");
                try { Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException error) { Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                target.setLastModified(System.currentTimeMillis());
                result = target;
            }
        } catch (Exception error) { failure = error; }
        finally {
            try { if (withinDirectory(part) && part.exists()) delete(part); }
            catch (Exception cleanupError) { if (failure == null) failure = cleanupError; else failure.addSuppressed(cleanupError); }
            synchronized (this) {
                // clear/prune may run between the atomic move and this completion publication.
                if ((flight.invalidated || flight.obsolete && !protectedKeys.containsKey(key)) && result != null) {
                    try { if (withinDirectory(result)) delete(result); }
                    catch (Exception cleanupError) { if (failure == null) failure = cleanupError; else failure.addSuppressed(cleanupError); }
                    result = null;
                    if (failure == null) failure = new IOException("播放缓存预取已过期");
                }
                flight.result = result; flight.error = failure; flight.complete = true;
                flights.remove(key); trimDeferredBudget(); notifyAll();
            }
        }
        if (failure != null) throw failure;
        return result;
    }
    /** Explicit decoder-failure invalidation; only this owned key, never manual download paths. */
    synchronized void invalidate(String sid, String vid, int index) throws IOException {
        ensureDirectory(); String key = key(sid, vid, index);
        Flight flight = flights.get(key); if (flight != null) flight.invalidated = true;
        File file = new File(directory, key);
        if (withinDirectory(file) && file.isFile()) delete(file);
    }
    synchronized void prune(String sid, int currentIndex, int keepPrevious) throws IOException {
        if (currentIndex < 0 || keepPrevious < 0) throw new IllegalArgumentException("缓存保留集数无效");
        String series = hash(sid);
        pruneSeries(series, currentIndex, keepPrevious);
    }
    private void pruneSeries(String series, int currentIndex, int keepPrevious) throws IOException {
        long earliest = Math.max(0L, (long) currentIndex - keepPrevious), latest = (long) currentIndex + 1;
        boolean casting = hasCastWindow(series);
        for (Map.Entry<String, Flight> pending : flights.entrySet()) {
            Flight flight = pending.getValue();
            boolean outside = casting ? !inCastWindow(series, flight.index) : flight.index < earliest || flight.index > latest;
            if (flight.series.equals(series) && outside && !protectedKeys.containsKey(pending.getKey())) flight.obsolete = true;
        }
        for (Entry entry : entries()) {
            boolean outside = casting ? !inCastWindow(series, entry.index) : entry.index < earliest || entry.index > latest;
            if (entry.series.equals(series) && outside && !protectedOrActive(entry.key)) delete(entry.file);
        }
        fitBudget(reservations());
    }
    synchronized Stats status() throws IOException {
        List<Entry> entries = entries(); long bytes = 0;
        for (Entry entry : entries) bytes += entry.bytes;
        return new Stats(entries.size(), bytes, 0);
    }
    synchronized Stats clear() throws IOException {
        int removed = 0; long bytes = 0;
        for (Map.Entry<String, Flight> pending : flights.entrySet()) if (!protectedKeys.containsKey(pending.getKey())) pending.getValue().obsolete = true;
        for (Entry entry : entries()) if (!protectedOrActive(entry.key)) { delete(entry.file); removed++; bytes += entry.bytes; }
        return new Stats(0, bytes, removed);
    }
}
