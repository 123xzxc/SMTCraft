package org.yinyun.smtcraft.smtc;

import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.media.MediaBridge;
import org.yinyun.smtcraft.render.Draw;
import org.yinyun.smtcraft.util.Budgets;
import org.yinyun.smtcraft.util.Errors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the sampling thread. {@link SmtcNative} must stay on one thread, so this
 * class is the only place that talks to it: the render thread just reads the
 * volatile snapshot.
 */
public final class SmtcBridge implements MediaBridge {
    private static final Logger LOG = LoggerFactory.getLogger("smtcraft");

    private final Object wake = new Object();
    private final SmtcNative smtc = new SmtcNative();
    private final ProgressSmoother smoother = new ProgressSmoother();

    private volatile SmtcConfig config;
    private volatile TrackInfo latest = TrackInfo.EMPTY;
    private volatile byte[] artwork = new byte[0];
    private volatile String artworkHash = "";
    private volatile boolean running;
    private volatile String lastError = "";
    private volatile long lastSampleAt;
    private volatile long sampleCount;
    private String loggedError = "";
    private String loggedTrack = "";

    /** Set when a wake-up is meant to sample immediately, not just to re-read state. */
    private volatile boolean forceSample;
    /** Never sample faster than this, however many wake-ups arrive. */
    private static final long MIN_SAMPLE_GAP_MS = 50L;

    private String heldKey = "";
    private long heldSince;
    /** Last round that really carried a track, for the swap grace window. */
    private long lastTrackAt;
    /** Playing time accumulated for the current track, and when it was last sampled. */
    private long playedMs;
    private long playedSampledAt;

    private Thread worker;

    public SmtcBridge(SmtcConfig config) {
        this.config = config;
    }

    public void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "smtcraft-native");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * The client hands over its config object once per tick. Waking the worker on
     * every one of those calls turned the 150 ms sampler into a 20 Hz one: each
     * round does three WinRT async calls, and that churn was audible in the game
     * audio as a periodic stutter. Only a value the worker actually reads is
     * allowed to cut the interval short.
     */
    public void setConfig(SmtcConfig config) {
        if (config == null) return;
        SmtcConfig previous = this.config;
        this.config = config;
        if (previous == null || SamplerGate.readsDifferently(previous, config)) reconfigure();
    }

    /** Ask the worker to apply the new state now instead of after the interval. */
    public void reconfigure() {
        forceSample = true;
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    public TrackInfo latest() {
        return latest;
    }

    public byte[] artwork() {
        return artwork;
    }

    public String artworkHash() {
        return artworkHash;
    }

    public boolean running() {
        return running;
    }

    public String lastError() {
        return lastError;
    }

    /** Number of sampling rounds the worker has completed (any result). */
    public long sampleCount() {
        return sampleCount;
    }

    public long millisSinceSample() {
        long at = lastSampleAt;
        return at == 0L ? -1L : System.currentTimeMillis() - at;
    }

    @Override
    public void close() {
        close(MediaBridge.CLOSE_GRACE_MS);
    }

    /**
     * Closes, waiting at most {@code budgetMs} for the sampler.
     *
     * <p>The shutdown sequence has one budget of its own (AUDIT-76).</p>
     */
    @Override
    public void close(long budgetMs) {
        running = false;
        reconfigure();
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            try {
                // join(0) waits forever, so the floor is one millisecond, not zero (AUDIT-92)
                t.join(Math.max(Budgets.MIN_WAIT_MS,
                        Math.min(budgetMs, MediaBridge.CLOSE_GRACE_MS)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
    }

    // --- worker ------------------------------------------------------------

    private void loop() {
        try {
            while (running) {
                SmtcConfig cfg = config;
                if (cfg == null) cfg = new SmtcConfig();
                boolean wantArt = cfg.showArtwork;
                long roundStart = System.currentTimeMillis();
                try {
                    // sample() opens the manager lazily; never close here, the
                    // old "if (!isOpen()) close()" killed the arena every round.
                    SmtcNative.Sample sample = smtc.sample(wantArt);
                    lastSampleAt = System.currentTimeMillis();
                    sampleCount++;
                    lastError = sample == null || sample == SmtcNative.Sample.EMPTY
                            ? smtc.lastError() : "";
                    publish(sample, cfg);
                    logState(cfg);
                } catch (Throwable t) {
                    lastError = describe(t);
                    // one failed round is not a track change: keep the card for
                    // swapGraceMs, or a single WinRT hiccup blanks the HUD
                    // mid-song. MPRIS already behaved this way (AUDIT-67)
                    publishEmpty(cfg, lastError);
                    try {
                        smtc.close();
                    } catch (Throwable ignored) {
                        // reopen next round
                    }
                    logState(cfg);
                }
                // the same bounds the settings slider offers, so the value a
                // user picks is the value the worker uses (C3). A wake-up only
                // samples at once when it asked for that; a plain notify just
                // re-reads the config and keeps the cadence, so no amount of
                // per-tick hand-overs can turn this into a busy loop.
                long wait = Math.max(MIN_SAMPLE_GAP_MS,
                        clamp(cfg.pollIntervalMs, 60, 5000)
                                - (System.currentTimeMillis() - roundStart));
                if (forceSample) {
                    forceSample = false;
                    wait = 0L;
                }
                sleep(wait);
            }
        } finally {
            try {
                smtc.shutdown();
            } catch (Throwable ignored) {
                // shutting down
            }
            running = false;
        }
    }

    /** Talk to the log when the track or the failure reason actually changes. */
    private void logState(SmtcConfig cfg) {
        String err = lastError == null ? "" : lastError;
        if (!err.equals(loggedError)) {
            String had = loggedError;
            loggedError = err;
            if (err.isEmpty()) {
                LOG.info("SMTC reader ready (was: {})", had.isEmpty() ? "starting" : had);
            } else {
                LOG.warn("SMTC reader unavailable: {}", err);
            }
        }
        TrackInfo info = latest;
        String key = info.hasTrack() ? info.dedupeKey() : "";
        if (!key.equals(loggedTrack)) {
            loggedTrack = key;
            if (!key.isEmpty()) {
                LOG.info("SMTC now playing: {} - {} ({}), art {} bytes, status {}",
                        info.title(), info.artist(), info.app(), artwork.length, info.status());
            }
        }
        if (cfg.debug) {
            LOG.debug("sample #{} err='{}' pos={}/{}", sampleCount, err,
                    info.positionMs(), info.endMs());
        }
    }

    private void publish(SmtcNative.Sample sample, SmtcConfig cfg) {
        long now = System.currentTimeMillis();
        String title = sample == null ? null : sample.title();
        String app = sample == null ? "" : sample.app();

        boolean browser = cfg.ignoreBrowserSessions && Draw.isBrowserApp(app);
        if (title == null || title.isEmpty() || browser) {
            // Switching songs looks exactly like "no session" for a fraction of a
            // second; publishEmpty keeps the card it had instead of blanking the
            // whole HUD, and the failure branch shares the same rule (AUDIT-67)
            publishEmpty(cfg, "");
            return;
        }
        String key = sample.title() + '\u0001' + sample.artist() + '\u0001'
                + sample.album() + '\u0001' + app;
        boolean fresh = !key.equals(heldKey);
        if (fresh) {
            // The cover belongs to the track, not to the bridge: without this the
            // previous album art stayed on screen when the new song had none.
            heldKey = key;
            heldSince = now;
            playedMs = 0L;
            playedSampledAt = now;
            if (!artworkHash.isEmpty()) {
                artwork = new byte[0];
                artworkHash = "";
            }
        } else if (sample.playing() && playedSampledAt > 0L) {
            // count only time the track was really playing, so a pause freezes
            // the lyric clock instead of letting it drift past the song
            playedMs += Math.max(0L, Math.min(now - playedSampledAt, 10_000L));
        }
        playedSampledAt = now;
        // The reader stamps every cover with the track it belongs to, so the
        // cover is taken only when that stamp is this exact track (the stamp has
        // no app suffix, hence the explicit rebuild instead of a prefix test).
        String artKey = sample.artKey();
        boolean artOwned = artKey == null || artKey.isEmpty()
                || artKey.equals(sample.title() + '\u0001' + sample.artist() + '\u0001'
                        + sample.album());
        if (sample.art() != null && sample.art().length > 0 && artOwned) {
            String hash = SmtcNative.hashOf(sample.art());
            if (!hash.equals(artworkHash)) {
                artwork = sample.art();
                artworkHash = hash;
            }
        }
        lastTrackAt = now;
        long rawPosition = sample.positionMs();
        // Apps report their timeline with a lag, so a raw position can sit behind
        // what the HUD already extrapolated; keep the shown position continuous.
        long shownPosition = cfg.progressSmooth
                ? smoother.presented(rawPosition, sample.playing(), sample.rate(), key, now)
                : rawPosition;
        latest = new TrackInfo(true, sample.playing(), sample.title(), sample.artist(),
                sample.album(), app, sample.status(), shownPosition,
                sample.startMs(), sample.endMs(), sample.rate(), now, heldSince, playedMs);
    }

    /**
     * Clears the card unless a track was on screen within the swap grace window.
     *
     * <p>Both "no session" and "the round threw" come through here, so a track
     * change and a failed sample look the same to the HUD: the card stays put
     * instead of flashing white (AUDIT-67). The cover belongs to the track, so it
     * goes with it.</p>
     */
    private void publishEmpty(SmtcConfig cfg, String note) {
        long now = System.currentTimeMillis();
        boolean hasNote = note != null && !note.isEmpty();
        if (latest.hasTrack() && now - lastTrackAt <= Math.max(0, cfg.swapGraceMs)) {
            // the card stays during the grace window, but the reason does not
            if (hasNote) lastError = note;
            return;
        }
        if (hasNote) lastError = note;
        latest = TrackInfo.EMPTY;
        heldKey = "";
        heldSince = 0L;
        playedMs = 0L;
        playedSampledAt = 0L;
        artwork = new byte[0];
        artworkHash = "";
        smoother.reset();
    }

    private void sleep(long millis) {
        synchronized (wake) {
            try {
                wake.wait(millis <= 0L ? 1L : Math.max(20L, millis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Kept as a delegate so the existing call sites read the same (AUDIT-15-3). */
    public static String describe(Throwable t) {
        return Errors.describe(t);
    }
}
