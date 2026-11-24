package org.yinyun.smtcraft.smtc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Logger;
import org.yinyun.smtcraft.util.Async;
import org.yinyun.smtcraft.util.Budgets;
import org.yinyun.smtcraft.util.Errors;

/**
 * Owns the lyric worker thread: one request per song, a provider chain that is
 * tried in the configured order (NetEase / QQ Music / Kugou), a disk cache so a
 * replay is instant and works offline, and a published {@link LyricTrack} the HUD
 * reads without blocking.
 *
 * <p>Every provider can return syllable-synced lyrics: NetEase {@code yrc}, QQ
 * {@code QRC} and Kugou {@code KRC}. Whichever answers first with real per-word
 * timings wins; plain LRC is the fallback.</p>
 */
public final class LyricService {
    // 1.7.10 的类路径上只有 java.util.logging，没有 slf4j；级别与原调用一一对应
    // （warn -> warning、info -> info、debug -> fine），语义不变
    private static final Logger LOG = Logger.getLogger("smtcraft");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long CACHE_TTL_MS = 120L * 24L * 60L * 60L * 1000L;
    /** Minimum score for a search hit to be trusted. */
    private static final int ACCEPT_SCORE = SongMatch.ACCEPT;
    /** Hits asked of a provider per query; the candidate cap scales with it. */
    private static final int PER_QUERY = 8;
    /**
     * Cached sheets from an older matcher are dropped, they may be the wrong song.
     * 5: the matcher started comparing edition marks, so a sheet cached before that
     * may belong to another cut of the same title (a remix filed under the original,
     * or the original under a remix).
     */
    private static final int CACHE_VERSION = 5;
    /**
     * Wall-clock budget for <em>one song</em>, retries included: providers answer in
     * a second or two when they know the track, and a silent one must not leave the
     * HUD saying "loading" forever. Every resolve of the same song shares this
     * deadline, so the three retries cannot multiply it (A17).
     */
    private static final long RESOLVE_BUDGET_MS = 32_000L;
    /** No single provider may eat the whole budget and starve the next one. */
    private static final long PROVIDER_BUDGET_MS = 10_000L;
    /**
     * Providers this build can talk to. The chain is Kugou -> QQ -> NetEase:
     * Kugou answers with syllable-timed KRC, QQ is the line-level fallback and
     * NetEase is the last resort, tried only when the first two found nothing.
     */
    private static final Set<String> ENABLED =
            Collections.unmodifiableSet(new HashSet<String>(LyricProvider.KNOWN));

    private final NetEaseClient netease = new NetEaseClient();
    private final QQMusicClient qq = new QQMusicClient();
    private final KugouClient kugou = new KugouClient();

    private final Object lock = new Object();
    private final Thread worker;
    /**
     * Bounded probe pool: a cached pool would let a stuck provider plus its retries
     * spawn threads without limit while the worker keeps submitting (A16).
     */
    private final ExecutorService probes = new ThreadPoolExecutor(2, 4, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(16),
            r -> {
                Thread t = new Thread(r, "smtcraft-lyric-probe");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private final Path cacheDir;
    private final SongOffsets offsets;

    private volatile boolean running = true;
    private volatile String order = LyricProvider.DEFAULT_ORDER;
    private TrackInfo wanted;
    private String wantedKey = "";
    /** Last key ever asked for: tells a retry whether the song already moved on. */
    private volatile String latestKey = "";
    private volatile long lastRetryAt;
    private int autoRetries;
    private volatile String currentKey = "";
    private volatile LyricTrack track = LyricTrack.EMPTY;
    private volatile String status = "idle";
    private volatile Phase phase = Phase.IDLE;
    /** The sheet behind the published track: re-parsed in place when an option flips. */
    private volatile CacheFile lastSheet;
    /** True while the current song is being re-parsed on the worker pool (A4). */
    private boolean reparsing;
    /**
     * Progress text from a provider thread. Providers used to write {@link #status}
     * directly, racing the worker that owns it; they now leave a note and the worker
     * publishes it (A16).
     */
    private volatile String providerNote = "";
    /** Deadline shared by every attempt at the song that is queued right now. */
    private volatile long songDeadline;
    /** Song the current {@link #songDeadline} belongs to. */
    private String deadlineKey = "";

    public LyricService() {
        this(defaultCacheDir());
    }

    /**
     * 1.7.10 没有 FabricLoader：Forge 以游戏目录作为进程工作目录，所以
     * {@code config/smtcraft/lyrics} 与源文件的 getConfigDir().resolve("smtcraft")
     * .resolve("lyrics") 指向同一处。
     */
    private static Path defaultCacheDir() {
        return Paths.get(System.getProperty("user.dir", "."), "config", "smtcraft", "lyrics");
    }

    /** Explicit cache root: keeps the resolve pipeline testable outside the game. */
    public LyricService(Path cacheDir) {
        this.cacheDir = cacheDir;
        this.offsets = new SongOffsets(cacheDir);
        this.worker = new Thread(this::loop, "smtcraft-lyrics");
        this.worker.setDaemon(true);
    }

    public void start() {
        if (!worker.isAlive()) worker.start();
    }

    public void close() {
        close(Budgets.LYRICS_CLOSE_MS);
    }

    /**
     * Blocks at most {@code budgetMs}: one worker join plus the probe pool drain.
     *
     * <p>The four actions before them are non-blocking, so a caller that ran out
     * of shutdown budget still gets the interrupt and the queue drain; it just
     * does not wait 3.5 s on top of the budget (AUDIT-91).</p>
     */
    public void close(long budgetMs) {
        running = false;
        probes.shutdownNow();
        synchronized (lock) {
            lock.notifyAll();
        }
        worker.interrupt();
        // a provider call can still be in flight; leaving it behind keeps the JVM
        // alive for an extra second on quit and lets it touch a closed cache
        // Given in order rather than split down the middle: an even split handed
        // the join 1.75 s against the 2 s the javadoc above promises, on every
        // normal exit rather than in the worst case (AUDIT-10-2).
        long join = Math.max(Budgets.MIN_WAIT_MS, Math.min(budgetMs, Budgets.LYRICS_JOIN_MS));
        long drain = Math.max(Budgets.MIN_WAIT_MS, Math.min(budgetMs - join, Budgets.LYRICS_DRAIN_MS));
        try {
            worker.join(join);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            probes.awaitTermination(drain, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public LyricTrack track() { return track; }
    public String status() { return status; }

    /**
     * Coarse state of the lyric lookup, for callers that must not poke at
     * {@link #status()} strings: the HUD used to test {@code "queued"} and
     * {@code endsWith(": searching")}, which broke whenever a provider learned a
     * new note (E2).
     */
    public enum Phase {
        /** Nothing is being looked up: no song, or it was just cleared. */
        IDLE,
        /** A provider is being asked right now. */
        SEARCHING,
        /** A sheet is available. */
        READY,
        /** The lookup finished and found nothing. */
        EMPTY
    }

    /** Current phase, safe to poll every frame. */
    public Phase phase() { return phase; }

    // ------------------------------------------------------- per-song offset

    /** Memoised offset lookup: the HUD asks for it every frame, the key is stable. */
    private volatile TrackInfo offsetTrack;
    private volatile String offsetKey = "";
    private volatile long offsetValue;

    /** This song's lyric offset in milliseconds; 0 when it was never nudged. */
    public long offsetFor(TrackInfo info) {
        if (info == null) return 0L;
        if (info == offsetTrack) return offsetValue;
        String key = keyOf(info);
        long value = offsets.get(key);
        offsetTrack = info;
        offsetKey = key;
        offsetValue = value;
        return value;
    }

    /** Shift this song's offset by {@code delta} ms and return the new value. */
    public long adjustOffset(TrackInfo info, long delta) {
        if (info == null) return 0L;
        String key = keyOf(info);
        long value = offsets.adjust(key, delta);
        offsetTrack = info;
        offsetKey = key;
        offsetValue = value;
        return value;
    }

    /** Write the per-song offset table now; used on shutdown, after the lanes drain. */
    public void flushOffsets() {
        offsets.flush();
    }

    /** Back to the sheet's own timing. */
    public long resetOffset(TrackInfo info) {
        if (info == null) return 0L;
        String key = keyOf(info);
        long value = offsets.set(key, 0L);
        offsetTrack = info;
        offsetKey = key;
        offsetValue = value;
        return value;
    }

    /**
     * Re-parse the sheet that is on screen, so options that live inside the parser
     * (the reading filter) show up on the spot instead of on the next song. The raw
     * text is already in memory, so no provider is asked again.
     */
    public void reparse() {
        CacheFile sheet = lastSheet;
        if (sheet == null) {
            requeue();
            return;
        }
        synchronized (lock) {
            if (reparsing) return;
            reparsing = true;
        }
        try {
            // parsing a 3000 line sheet must not run on the render thread (A4)
            probes.submit(() -> {
                LyricTrack reparsed = null;
                try {
                    reparsed = parse(sheet);
                } catch (RuntimeException e) {
                    LOG.warning("reparse failed: " + Errors.describe(e));
                } finally {
                    synchronized (lock) {
                        reparsing = false;
                    }
                }
                if (reparsed != null && !reparsed.isEmpty()) {
                    track = reparsed;
                    phase = reparsed.isEmpty() ? Phase.EMPTY : Phase.READY;
                } else {
                    requeue();
                }
            });
        } catch (RejectedExecutionException busy) {
            synchronized (lock) {
                reparsing = false;
            }
            requeue();
        }
    }

    /** Ask the worker for the current song again, without touching the network order. */
    private void requeue() {
        resetBudget();
        synchronized (lock) {
            if (wantedKey.isEmpty()) return;
            wantedKey = "";
            wanted = null;
            status = "queued";
            phase = Phase.SEARCHING;
            lock.notifyAll();
        }
    }

    /** Provider order, e.g. {@code "kugou,qq,netease"}; unknown names are skipped. */
    public void setOrder(String value) {
        if (value == null || value.trim().isEmpty()) return;
        order = value;
    }

    /** True when the published track belongs to the song currently playing. */
    public boolean matches(TrackInfo info) {
        if (info == null || !info.hasTrack()) return false;
        return keyOf(info).equals(currentKey);
    }

    /** Ask for the lyrics of {@code info}; cheap and safe to call every tick. */
    public void request(TrackInfo info) {
        if (!running || info == null || !info.hasTrack()) return;
        String key = keyOf(info);
        latestKey = key;
        synchronized (lock) {
            if (key.equals(wantedKey)) return;
            lastRetryAt = 0L;
            autoRetries = 0;
            wantedKey = key;
            wanted = info;
            status = "queued";
            phase = Phase.SEARCHING;
            lock.notifyAll();
        }
    }

    /**
     * Ask again for a song that came back empty. A failed search is often just a
     * throttled catalogue or a half-published track, so the HUD pokes this a few
     * times per song instead of leaving "no lyrics" up for the whole track.
     */
    public void recheck(TrackInfo info) {
        if (!running || info == null || !info.hasTrack()) return;
        if (!status.startsWith("no lyrics")) return;
        long now = System.currentTimeMillis();
        if (now - lastRetryAt < 15_000L || autoRetries >= 3) return;
        lastRetryAt = now;
        autoRetries++;
        // the previous attempt already burned the song budget; without this the retry
        // inherited an expired deadline and timed out before touching the network
        resetBudget();
        String key = keyOf(info);
        synchronized (lock) {
            // wantedKey is only cleared when a resolve starts again, so comparing it
            // alone made every retry a no-op. A pending job is "wanted != null" (A1).
            if (key.equals(wantedKey) && wanted != null) return;
            latestKey = key;
            wantedKey = key;
            wanted = info;
            status = "queued";
            phase = Phase.SEARCHING;
            lock.notifyAll();
        }
    }

    /**
     * Cache / offset identity. Album and a coarse duration bucket are part of it:
     * with title+artist alone, a cover, a live take and the studio version shared
     * one cache entry, one offset and one lyric sheet (A18).
     */
    private static String keyOf(TrackInfo info) {
        long duration = info.endMs() > info.startMs() ? info.endMs() - info.startMs() : 0L;
        long bucket = duration > 0L ? duration / 10_000L : 0L;
        return info.title().trim().toLowerCase(Locale.ROOT)
                + '\u0001' + info.artist().trim().toLowerCase(Locale.ROOT)
                + '\u0001' + (info.album() == null ? "" : info.album().trim().toLowerCase(Locale.ROOT))
                + '\u0001' + bucket;
    }

    private void loop() {
        while (running) {
            TrackInfo info;
            String key;
            synchronized (lock) {
                while (running && wanted == null) {
                    try {
                        lock.wait(1_000L);
                    } catch (InterruptedException e) {
                        if (!running) return;
                    }
                }
                if (!running) return;
                info = wanted;
                key = wantedKey;
                wanted = null;
                if (!key.equals(deadlineKey)) {
                    // one budget per song, not per attempt
                    deadlineKey = key;
                    songDeadline = now() + RESOLVE_BUDGET_MS;
                }
            }
            LyricTrack loaded;
            try {
                // A miss is often a hiccup rather than a missing sheet: catalogues
                // answer an empty first page, throw a 503 under a burst of queries,
                // or time out while the app is still handing over the track. Retry
                // a few times with growing pauses, and abandon the retries the
                // moment the song changes so a switch is never held up.
                long[] pauses = {400L, 2_500L, 5_000L};
                loaded = resolve(info, key);
                for (int i = 0; loaded.isEmpty() && i < pauses.length && running; i++) {
                    if (!key.equals(latestKey) || now() >= songDeadline) break;
                    synchronized (lock) {
                        lock.wait(pauses[i]);
                    }
                    if (!running || !key.equals(latestKey)) break;
                    loaded = resolve(info, key);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                status = "error: " + t.getClass().getSimpleName();
                phase = Phase.EMPTY;
                LOG.warning("lyrics for " + key + " failed: " + Errors.describe(t));
                loaded = LyricTrack.empty("error");
            }
            currentKey = key;
            track = loaded;
            phase = loaded.isEmpty() ? Phase.EMPTY : Phase.READY;
            if (!loaded.isEmpty()) {
                LOG.info("lyrics: " + loaded.size() + " line(s) from " + loaded.source()
                        + " for " + loaded.label());
            }
        }
    }

    private LyricTrack resolve(TrackInfo info, String key) throws Exception {
        Files.createDirectories(cacheDir);
        Path file = cacheDir.resolve(hash(key) + ".json");
        CacheFile cached = readCache(file);
        if (cached != null && !cached.song.trim().isEmpty()
                && !SongMatch.sameEdition(info.title(), cached.song)) {
            // Same key, another recording: the key is built from the title, so an
            // older matcher could file a remix's sheet here. It cannot be reused.
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
                // read-only cache dir: the entry is simply ignored
            }
            cached = null;
        }
        long now = System.currentTimeMillis();
        // A line-level sheet is only a fallback: keep looking for a provider that
        // carries real syllable timings, which is what the karaoke sweep needs.
        List<LyricTrack> pool = new ArrayList<LyricTrack>();
        List<CacheFile> sources = new ArrayList<CacheFile>();
        if (cached != null && cached.empty) {
            // A miss used to be cached for six hours, so switching away and back read
            // the empty sheet again. Drop it and search afresh; the resolve budget
            // ({@link #RESOLVE_BUDGET_MS}) shares one deadline across the retries, so
            // that cheap even when every provider is slow.
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
                // read-only config dir: just ignore the stale file
            }
            cached = null;
        }
        if (cached != null && now - cached.ts < CACHE_TTL_MS) {
            LyricTrack hit = parse(cached);
            if (!hit.isEmpty()) {
                pool.add(hit);
                sources.add(cached);
            }
        }
        if (quality(pool) >= QUALITY_FULL) {
            status = "cached";
            phase = Phase.READY;
            return best(pool);
        }

        long deadline = songDeadline > 0L ? songDeadline : now() + RESOLVE_BUDGET_MS;
        boolean timedOut = false;
        for (String raw : order.split(",")) {
            String provider = raw.trim().toLowerCase(Locale.ROOT);
            if (provider.isEmpty() || !ENABLED.contains(provider)) continue;
            if (now() >= deadline) {
                timedOut = true;
                break;
            }
            // each provider gets its own slice, so a silent one cannot starve the next
            long providerDeadline = Math.min(deadline, now() + PROVIDER_BUDGET_MS);
            providerNote = "";
            // 源文件是 Java 14 的箭头 switch 表达式，这里展开成等价的 if 链
            CacheFile fetched = fetchWithBudget(provider, providerDeadline, () -> {
                if ("netease".equals(provider)) return fetchNetease(info, key);
                if ("qq".equals(provider)) return fetchQq(info, key);
                if ("kugou".equals(provider)) return fetchKugou(info, key);
                return null;
            });
            String note = providerNote;
            if (!note.trim().isEmpty()) status = note;
            if (fetched == null) {
                if (status.endsWith(": timeout")) timedOut = true;
                continue;
            }
            LyricTrack hit = parse(fetched);
            if (hit.isEmpty()) continue;
            pool.add(hit);
            sources.add(fetched);
            if (quality(pool) >= QUALITY_FULL) break;
        }

        if (pool.isEmpty()) {
            // never persist a miss: the next switch back would read it and show
            // nothing until the negative TTL expired
            status = timedOut ? "no lyrics (timeout)" : "no lyrics";
            phase = Phase.EMPTY;
            return LyricTrack.empty(timedOut ? "timeout" : "no-match");
        }

        int index = bestIndex(pool);
        LyricTrack chosen = pool.get(index);
        // a syllable sheet that is only partly translated borrows the rest from
        // another provider: the timing stays exact, the meaning comes from the
        // sheet that has it. Partial borrows matter too — one translated credit
        // line is not a translated song.
        float own = translationRatio(chosen);
        if (own < 0.5f) {
            for (int i = 0; i < pool.size() && i < sources.size(); i++) {
                if (i == index) continue;
                LyricTrack other = pool.get(i);
                if (translationRatio(other) <= own) continue;
                LyricTrack merged = chosen.withTranslation(other);
                float after = translationRatio(merged);
                if (after > own + 0.02f) {
                    chosen = merged;
                    own = after;
                    rememberTranslation(sources.get(index), toLrc(merged));
                }
            }
        }
        lastSheet = sources.get(index);
        writeCache(file, sources.get(index));
        return chosen;
    }

    /**
     * One provider inside the song's budget: whatever it is doing (DNS, a slow
     * catalogue, four candidate downloads) cannot hold the HUD past the deadline.
     */
    private CacheFile fetchWithBudget(String provider, long deadline, ThrowingSupplier task) {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) {
            status = provider + ": timeout";
            return null;
        }
        Future<CacheFile> future;
        // submit(Runnable) 与 submit(Callable) 对一个返回值的方法引用都成立，
        // 直接传会得到 "reference to submit is ambiguous"；先定型成 Callable
        Callable<CacheFile> call = task::get;
        try {
            future = probes.submit(call);
        } catch (RejectedExecutionException busy) {
            status = provider + ": busy";
            return null;
        }
        try {
            return future.get(left, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            status = provider + ": timeout";
            LOG.warning(provider + " lyrics timed out after " + left + " ms");
            return null;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            status = provider + " error";
            LOG.warning(provider + " lyrics failed: " + Errors.describe(cause));
            return null;
        }
    }

    /** Progress note from a provider thread; published by the worker. */
    private void note(String text) {
        providerNote = text;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** A provider call that needs the budget guard. */
    @FunctionalInterface
    private interface ThrowingSupplier {
        CacheFile get() throws Exception;
    }

    /** 4 points for syllable timings + up to 2 for translation coverage; 6 is the best sheet. */
    private static final int QUALITY_FULL = 6;

    /**
     * Share of transcribable rows that actually carry a translation. A single
     * translated credit line (or one romaji row) must never make a sheet look
     * complete: that is exactly how a whole song ends up showing the next row's
     * Japanese text where the Chinese line belongs.
     */
    private static float translationRatio(LyricTrack track) {
        if (track == null || track.isEmpty()) return 0.0f;
        int total = 0;
        int done = 0;
        for (int i = 0; i < track.size(); i++) {
            LyricLine line = track.line(i);
            if (line == null || line.text().trim().isEmpty()) continue;
            total++;
            if (line.hasTranslation()) done++;
        }
        return total == 0 ? 0.0f : done / (float) total;
    }

    private static int quality(LyricTrack track) {
        if (track == null || track.isEmpty()) return -1;
        boolean words = false;
        for (int i = 0; i < track.size() && !words; i++) {
            LyricLine line = track.line(i);
            words = line != null && line.hasWords();
        }
        float ratio = translationRatio(track);
        int score = words ? 4 : 0;
        if (ratio >= 0.5f) score += 2;
        else if (ratio >= 0.1f) score += 1;
        return score;
    }

    private static int quality(List<LyricTrack> pool) {
        int best = -1;
        for (LyricTrack t : pool) best = Math.max(best, quality(t));
        return best;
    }

    private static int bestIndex(List<LyricTrack> pool) {
        int index = 0;
        int best = -1;
        for (int i = 0; i < pool.size(); i++) {
            int q = quality(pool.get(i));
            if (q > best) {
                best = q;
                index = i;
            }
        }
        return index;
    }

    private static LyricTrack best(List<LyricTrack> pool) {
        return pool.isEmpty() ? LyricTrack.EMPTY : pool.get(bestIndex(pool));
    }

    /**
     * Store a merged translation sheet in every field its provider's reader looks
     * at. The QQ reader used to consume only {@code tqrc}, so a merged translation
     * showed up until the next start and then vanished, because the merge had been
     * written to {@code tlyric}.
     */
    private static void rememberTranslation(CacheFile entry, String lrc) {
        if (entry == null || lrc == null || lrc.trim().isEmpty()) return;
        entry.tlyric = lrc;
        entry.tqrc = lrc;
    }

    /** Serialise the merged translations back into a plain LRC so they survive a cache hit. */
    private static String toLrc(LyricTrack track) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < track.size(); i++) {
            LyricLine line = track.line(i);
            if (line == null || !line.hasTranslation()) continue;
            long ms = line.startMs();
            sb.append(String.format("[%02d:%02d.%02d]", ms / 60_000L, (ms / 1_000L) % 60L,
                    (ms / 10L) % 100L)).append(line.translation()).append("\n");
        }
        return sb.toString();
    }

    /** Turn a cache entry back into a track, honouring the provider's format. */
    private LyricTrack parse(CacheFile c) {
        String label = c.song + (c.artists == null || c.artists.isEmpty() ? "" : " - " + c.artists);
        String provider = c.provider == null || c.provider.isEmpty() ? "netease" : c.provider;
        switch (provider) {
            case "qq": {
                // tlyric wins when present: only a merged sheet writes it, and a
                // merge always knows more than the single provider fetch did.
                String trans = c.tlyric != null && !c.tlyric.trim().isEmpty() ? c.tlyric : c.tqrc;
                if (c.qrc != null && !c.qrc.trim().isEmpty()) {
                    return SyllableParsers.parseQrc(c.qrc, trans, "qq:" + c.id, label);
                }
                return LrcParser.parse(c.lrc, trans, "qq:" + c.id, label);
            }
            case "kugou":
                if (c.krc != null && !c.krc.trim().isEmpty()) {
                    return SyllableParsers.parseKrc(c.krc, c.tlyric, "kugou:" + c.id, label);
                }
                return LrcParser.parse(c.lrc, null, "kugou:" + c.id, label);
            default:
                return LrcParser.parseRich(c.yrc, c.lrc, c.tlyric, "netease:" + c.id, label);
        }
    }

    // ------------------------------------------------------------- providers

    private CacheFile fetchNetease(TrackInfo info, String key) throws Exception {
        note("netease: searching");
        Hit best = best(hitsNetease(info), info);
        if (best == null) return null;
        note("netease: fetching");
        NetEaseClient.Lyrics lyrics = netease.lyrics(Long.parseLong(best.id));
        if (lyrics.lrc().trim().isEmpty() && lyrics.yrc().trim().isEmpty()) return null;
        CacheFile hit = new CacheFile();
        hit.key = key;
        hit.provider = "netease";
        hit.id = best.id;
        hit.song = best.name;
        hit.artists = best.artists;
        hit.lrc = nz(lyrics.lrc());
        hit.tlyric = nz(lyrics.translation());
        hit.yrc = nz(lyrics.yrc());
        hit.ts = System.currentTimeMillis();
        note("netease: " + best.name);
        return hit;
    }

    private CacheFile fetchQq(TrackInfo info, String key) throws Exception {
        note("qq: searching");
        Hit best = best(hitsQq(info), info);
        if (best == null) return null;
        note("qq: fetching");
        QQMusicClient.Song song = new QQMusicClient.Song(best.id, best.mid, best.name, best.artists,
                best.durationMs);
        QQMusicClient.Lyrics lyrics = qq.lyrics(song);
        if (nz(lyrics.qrc()).trim().isEmpty() && nz(lyrics.lrc()).trim().isEmpty()) return null;
        CacheFile hit = new CacheFile();
        hit.key = key;
        hit.provider = "qq";
        hit.id = best.id;
        hit.song = best.name;
        hit.artists = best.artists;
        hit.qrc = nz(lyrics.qrc());
        hit.tqrc = nz(lyrics.translationQrc());
        hit.lrc = nz(lyrics.lrc());
        hit.ts = System.currentTimeMillis();
        note("qq: " + best.name);
        return hit;
    }

    private CacheFile fetchKugou(TrackInfo info, String key) throws Exception {
        note("kugou: searching");
        // The lyric endpoint only answers with the catalogue hash in hand: a bare
        // keyword search misses most Japanese / non-mainland titles entirely.
        // The endpoint is literal about editions: asking for "Euphoria" answers with
        // the plain cut, so the full title is tried first and the bare one is only a
        // fallback (Kugou files "[US40 Remix]" entries under their own names).
        List<String> names = new ArrayList<String>();
        String full = info.title() == null ? "" : info.title().trim();
        if (!full.isEmpty()) names.add(full);
        String bareTitle = bare(info.title());
        if (!bareTitle.isEmpty() && !bareTitle.equalsIgnoreCase(full)) names.add(bareTitle);
        String title = names.isEmpty() ? "" : names.get(0);
        String hash = "";
        for (String q : SongMatch.queries(info)) {
            if (q.trim().isEmpty()) continue;
            try {
                for (KugouClient.Song song : kugou.searchSongs(q)) {
                    Hit h = new Hit(song.hash(), "", song.name(), song.singer(), song.durationMs());
                    if (score(h, info) >= ACCEPT_SCORE) {
                        hash = song.hash();
                        break;
                    }
                }
            } catch (Exception e) {
                LOG.warning("kugou search '" + q + "' failed: " + Errors.describe(e));
            }
            if (!hash.isEmpty()) break;
        }

        // A failing catalogue call is not a missing song: fall through to the
        // keyword search instead of dropping the whole provider.
        List<KugouClient.Candidate> found = new ArrayList<KugouClient.Candidate>();
        if (!hash.isEmpty()) {
            try {
                found = new ArrayList<KugouClient.Candidate>(kugou.searchByHash(title,
                        info.durationMs(), hash));
            } catch (Exception e) {
                LOG.warning("kugou hash search failed: " + Errors.describe(e));
            }
        }
        // "Found something" is not "found the song": with the edition gate on, an answer
        // made of other cuts is the ordinary case, and stopping there dropped the song
        // while the next name could still have answered (AUDIT-33-3).
        for (String name : names) {
            if (bestCandidateScore(found, info) >= ACCEPT_SCORE) break;
            try {
                for (KugouClient.Candidate c : kugou.search(name, info.durationMs())) {
                    if (!found.contains(c)) found.add(c);
                }
            } catch (Exception e) {
                LOG.warning("kugou keyword search '" + name + "' failed: " + Errors.describe(e));
            }
        }

        Hit best = null;
        int bestScore = Integer.MIN_VALUE;
        for (KugouClient.Candidate c : found) {
            Hit h = new Hit(c.id() + "\u0001" + c.accessKey(), "", c.song(), c.singer(), c.durationMs());
            int score = score(h, info);
            if (score > bestScore) {
                bestScore = score;
                best = h;
            }
        }
        if (best == null || bestScore < ACCEPT_SCORE) return null;

        // Several KRC variants exist per song and only some carry a complete
        // translation block — one variant may have a single romaji row while
        // another translates every line. Download a few and keep the best sheet
        // instead of the first one that merely mentions [language:].
        List<KugouClient.Candidate> ranked = new ArrayList<KugouClient.Candidate>(found);
        ranked.sort((a, b) -> Integer.compare(score(new Hit(b.id(), "", b.song(), b.singer(),
                b.durationMs()), info), score(new Hit(a.id(), "", a.song(), a.singer(),
                a.durationMs()), info)));
        String krc = null;
        KugouClient.Candidate chosen = null;
        int bestQuality = Integer.MIN_VALUE;
        for (int i = 0; i < Math.min(4, ranked.size()); i++) {
            KugouClient.Candidate c = ranked.get(i);
            if (score(new Hit(c.id(), "", c.song(), c.singer(), c.durationMs()), info) < ACCEPT_SCORE) {
                continue;
            }
            note("kugou: fetching");
            String text;
            try {
                text = kugou.krc(c);
            } catch (Exception e) {
                LOG.warning("kugou krc download failed: " + Errors.describe(e));
                continue;
            }
            if (text == null || text.trim().isEmpty()) continue;
            int q = quality(SyllableParsers.parseKrc(text, null, "kugou", "probe"));
            if (q > bestQuality) {
                chosen = c;
                krc = text;
                bestQuality = q;
            }
            if (bestQuality >= QUALITY_FULL) break;
        }
        if (chosen == null || krc == null) return null;
        String id = chosen.id();
        CacheFile hit = new CacheFile();
        hit.key = key;
        hit.provider = "kugou";
        hit.id = id;
        hit.song = chosen.song();
        hit.artists = chosen.singer();
        hit.krc = krc;
        hit.ts = System.currentTimeMillis();
        note("kugou: " + best.name);
        return hit;
    }

    /**
     * Providers rank a bare keyword search badly, so every query variant (title +
     * artist, title + album, title alone, the raw SMTC string) is searched and the
     * union is scored. A specific query that finds nothing therefore no longer
     * means "no lyrics".
     */
    private List<Hit> hitsNetease(TrackInfo info) throws Exception {
        Map<String, Hit> out = new LinkedHashMap<String, Hit>();
        List<String> queries = SongMatch.queries(info);
        // Eight hits per query, so the cap has to scale with the list length: a fixed 24
        // is exactly "the first three queries" and silently dropped the untouched title
        // whenever an album added a fourth one (AUDIT-33-2).
        int cap = queries.size() * PER_QUERY;
        for (String q : queries) {
            if (q.trim().isEmpty()) continue;
            // one rejected query (a 503 page, an HTML error body) must not kill the
            // whole provider: the next variant often still answers
            try {
                for (NetEaseClient.Song s : netease.search(q, PER_QUERY)) {
                    String id = s.id() + ":" + s.name();
                    if (!out.containsKey(id)) {
                        out.put(id, new Hit(Long.toString(s.id()), "",
                                s.name(), s.artists(), s.durationMs()));
                    }
                }
            } catch (Exception e) {
                LOG.warning("netease search '" + q + "' failed: " + Errors.describe(e));
            }
            if (out.size() >= cap) break;
        }
        return Collections.unmodifiableList(new ArrayList<Hit>(out.values()));
    }

    private List<Hit> hitsQq(TrackInfo info) throws Exception {
        Map<String, Hit> out = new LinkedHashMap<String, Hit>();
        List<String> queries = SongMatch.queries(info);
        int cap = queries.size() * PER_QUERY;   // see hitsNetease
        for (String q : queries) {
            if (q.trim().isEmpty()) continue;
            try {
                for (QQMusicClient.Song s : qq.search(q, PER_QUERY)) {
                    String id = s.id() + ":" + s.name();
                    if (!out.containsKey(id)) {
                        out.put(id, new Hit(s.id(), s.mid(), s.name(),
                                s.artists(), s.durationMs()));
                    }
                }
            } catch (Exception e) {
                LOG.warning("qq search '" + q + "' failed: " + Errors.describe(e));
            }
            if (out.size() >= cap) break;
        }
        return Collections.unmodifiableList(new ArrayList<Hit>(out.values()));
    }

    private static Hit best(List<Hit> hits, TrackInfo info) {
        Hit best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Hit h : hits) {
            int score = score(h, info);
            if (score > bestScore) {
                bestScore = score;
                best = h;
            }
        }
        return bestScore >= ACCEPT_SCORE ? best : null;
    }

    // ---------------------------------------------------------------- scoring

    /** A search result normalised across providers. */
    private static final class Hit {
        private final String id;
        private final String mid;
        private final String name;
        private final String artists;
        private final long durationMs;

        Hit(String id, String mid, String name, String artists, long durationMs) {
            this.id = id;
            this.mid = mid;
            this.name = name;
            this.artists = artists;
            this.durationMs = durationMs;
        }

        String id() { return id; }
        String mid() { return mid; }
        String name() { return name; }
        String artists() { return artists; }
        long durationMs() { return durationMs; }
    }

    /** The best score any candidate of {@code found} reaches, for "is there anything usable". */
    private int bestCandidateScore(List<KugouClient.Candidate> found, TrackInfo info) {
        int best = Integer.MIN_VALUE;
        for (KugouClient.Candidate c : found) {
            int score = score(new Hit(c.id(), "", c.song(), c.singer(), c.durationMs()), info);
            if (score > best) best = score;
        }
        return best;
    }

    private static String query(TrackInfo info) {
        List<String> queries = SongMatch.queries(info);
        return queries.isEmpty() ? "" : queries.get(0);
    }

    private static String bare(String title) {
        return SongMatch.bare(title);
    }

    private static int score(Hit song, TrackInfo info) {
        return SongMatch.score(song.name(), song.artists(), song.durationMs(), info);
    }

    // ------------------------------------------------------------------ cache

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * A fresh search must not inherit the deadline of the attempt the user just
     * threw away: an expired budget makes every provider give up immediately, which
     * showed up as "no lyrics (timeout)" right after clearing the cache.
     */
    private void resetBudget() {
        synchronized (lock) {
            deadlineKey = "";
            songDeadline = 0L;
        }
    }

    /**
     * Delete every cached lyric sheet and forget the song in progress, so the next
     * tick searches again. Returns how many files were removed.
     */
    public int clearCache() {
        resetAllCache();
        int removed = purgeCacheFiles();
        LOG.info("lyric cache cleared (" + removed + " file(s))");
        return removed;
    }

    /**
     * Same as {@link #clearCache()} but the directory walk and the deletes happen on
     * the background lane; {@code done} receives the count on the client thread.
     */
    public void clearCacheAsync(IntConsumer done) {
        resetAllCache();
        Async.run("clear lyric cache", () -> {
            int removed = purgeCacheFiles();
            LOG.info("lyric cache cleared (" + removed + " file(s))");
            if (done != null) Async.onClient(() -> done.accept(removed));
        });
    }

    /** Forget everything the service knows about the current song, without any file access. */
    private void resetAllCache() {
        resetBudget();
        synchronized (lock) {
            wanted = null;
            wantedKey = "";
            currentKey = "";
            status = "idle";
            phase = Phase.IDLE;
        }
        lastSheet = null;
        track = LyricTrack.EMPTY;
        offsets.clear();
    }

    private int purgeCacheFiles() {
        int removed = 0;
        try {
            Files.createDirectories(cacheDir);
            // Stream.toList() is Java 16; a directory stream is the Java 7 way to walk
            // one flat folder
            DirectoryStream<Path> files = Files.newDirectoryStream(cacheDir);
            try {
                for (Path file : files) {
                    try {
                        if (Files.deleteIfExists(file)) removed++;
                    } catch (Exception ignored) {
                        // a locked file is not worth a failure
                    }
                }
            } finally {
                files.close();
            }
        } catch (Exception e) {
            LOG.warning("cannot clear the lyric cache: " + Errors.describe(e));
        }
        return removed;
    }

    /**
     * Delete the cached sheet of the song that is on screen and search it again on
     * the next tick; the other songs keep their cache. Returns true when a file was
     * actually removed.
     */
    public boolean clearCurrent() {
        String key = currentKey;
        boolean removed = deleteSheet(key);
        resetCurrent();
        LOG.info("lyric cache cleared for one song (removed=" + removed + ")");
        return removed;
    }

    /** Same as {@link #clearCurrent()} with the delete moved off the client thread. */
    public void clearCurrentAsync(Consumer<Boolean> done) {
        String key = currentKey;
        resetCurrent();
        Async.run("clear song cache", () -> {
            boolean removed = deleteSheet(key);
            LOG.info("lyric cache cleared for one song (removed=" + removed + ")");
            if (done != null) Async.onClient(() -> done.accept(removed));
        });
    }

    private boolean deleteSheet(String key) {
        if (key == null || key.isEmpty()) return false;
        try {
            return Files.deleteIfExists(cacheDir.resolve(hash(key) + ".json"));
        } catch (Exception e) {
            LOG.warning("cannot clear this song's cache: " + Errors.describe(e));
            return false;
        }
    }

    /** Re-queue the current song immediately; no file access, safe on any thread. */
    private void resetCurrent() {
        resetBudget();
        synchronized (lock) {
            wanted = null;
            wantedKey = "";
            currentKey = "";
            status = "queued";
            phase = Phase.SEARCHING;
            lock.notifyAll();
        }
        lastSheet = null;
        track = LyricTrack.EMPTY;
    }

    /** Directory holding the per-song sheets, shown in the settings screen. */
    public Path cacheDir() {
        return cacheDir;
    }

    private CacheFile readCache(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            String json = readText(file);
            CacheFile parsed = GSON.fromJson(json, CacheFile.class);
            if (parsed == null || parsed.key == null) return null;
            if (parsed.v != CACHE_VERSION) {
                // written by the looser matcher: it may hold the wrong song
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    // nothing to do, it will simply be ignored
                }
                return null;
            }
            return parsed;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(Path file, CacheFile data) {
        try {
            Files.createDirectories(file.getParent());
            writeText(file, GSON.toJson(data));
        } catch (Exception e) {
            LOG.fine("cannot write lyric cache " + file + ": " + Errors.describe(e));
        }
    }

    /** Files.readString is Java 11; readAllBytes plus the UTF-8 decoder is its Java 7 form. */
    private static String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** Files.writeString is Java 11; Files.write of the UTF-8 bytes is the same write. */
    private static void writeText(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(20);
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    /** On-disk lyric cache entry: one provider's raw payload plus its metadata. */
    private static final class CacheFile {
        int v = CACHE_VERSION;
        String key;
        String provider = "";
        String id = "";
        String song = "";
        String artists = "";
        String lrc = "";
        String tlyric = "";
        String yrc = "";
        String qrc = "";
        String tqrc = "";
        String krc = "";
        long ts;
        boolean empty;

        static CacheFile empty(String key) {
            CacheFile f = new CacheFile();
            f.key = key;
            f.ts = System.currentTimeMillis();
            f.empty = true;
            return f;
        }
    }
}
