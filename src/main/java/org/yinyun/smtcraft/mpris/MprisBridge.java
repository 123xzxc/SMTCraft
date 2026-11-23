package org.yinyun.smtcraft.mpris;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.media.MediaBridge;
import org.yinyun.smtcraft.render.Draw;
import org.yinyun.smtcraft.smtc.ProgressSmoother;
import org.yinyun.smtcraft.smtc.TrackInfo;
import org.yinyun.smtcraft.util.Budgets;
import org.yinyun.smtcraft.util.Errors;

/**
 * Linux counterpart of {@code SmtcBridge}: samples MPRIS2 players off the
 * session bus and publishes the same {@link TrackInfo} the HUD already renders.
 *
 * <p>Two things differ from Windows and drive the design here. First, MPRIS has
 * no "current session" concept — every player publishes its own interface, so a
 * player has to be chosen each round (playing beats paused, the current player
 * wins ties, so the card does not hop between Spotify and a paused VLC).
 * Second, nothing is push-based: {@code Position} and {@code Metadata} are read
 * on the same interval as the Windows sampler, and the shared
 * {@link ProgressSmoother} keeps the position smooth in between.</p>
 */
public final class MprisBridge implements MediaBridge {

    private static final Logger LOG = LoggerFactory.getLogger("smtcraft-mpris");
    private static final long MIN_SAMPLE_GAP_MS = 50L;
    private static final long NAMES_REFRESH_MS = 2_000L;
    private static final long ART_RETRY_MS = 6_000L;
    private static final int ART_MAX_MISSES = 3;
    /** Consecutive empty replies before the session bus is treated as dead. */
    private static final int RECONNECT_AFTER = 5;
    /**
     * How long one round may spend asking players who they are (AUDIT-54).
     *
     * <p>It is also handed down as the reply timeout of each {@code GetAll}, so
     * the budget is a deadline instead of a check between two calls that could
     * each burn the full bus timeout (AUDIT-62). A probe on a live bus answers in
     * milliseconds, so a round still gets through several players.</p>
     */
    private static final long PROBE_BUDGET_MS = 600L;
    /** Reply timeout for the position/identity reads once a player is chosen (AUDIT-62). */
    private static final int QUERY_TIMEOUT_MS = 500;
    /** Players are re-enumerated more slowly when nobody is on the bus (AUDIT-58). */
    private static final long EMPTY_NAMES_REFRESH_MS = 3_000L;
    private static final long RECONNECT_MIN_MS = 500L;
    private static final long RECONNECT_MAX_MS = 30_000L;

    private final Object wake = new Object();
    private final ProgressSmoother smoother = new ProgressSmoother();

    private volatile SmtcConfig config;
    private volatile TrackInfo latest = TrackInfo.EMPTY;
    private volatile byte[] artwork = new byte[0];
    private volatile String artworkHash = "";
    private volatile boolean running;
    private volatile String lastError = "";
    private volatile long lastSampleAt;
    private volatile long sampleCount;
    private volatile boolean forceSample;

    private Thread worker;
    private String heldKey = "";
    private long heldSince;
    private long lastTrackAt;
    private int busFailures;
    private long reconnectAt;
    private int reconnectFailures;
    private int probeCursor;
    private long playedMs;
    private long playedSampledAt;

    private List<String> names = List.of();
    private long namesAt;
    private String currentName = "";
    private final Map<String, String> identities = new HashMap<>();
    private String loggedError = "";
    private String loggedTrack = "";
    private String artUrl = "";
    private long artTriedAt;
    private int artMisses;
    /**
     * URL whose cover was already taken; success is a state, not a non-failure
     * (AUDIT-61).
     *
     * <p>Invariant: it belongs to the same state as {@link #artUrl} and
     * {@link #artworkHash}, so every place that clears one has to clear this too.
     * The "show artwork" switch was the entry that forgot, and every later round
     * then returned early for the rest of the song (AUDIT-72).</p>
     */
    private String artDoneUrl = "";

    public MprisBridge(SmtcConfig config) {
        this.config = config;
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "smtcraft-mpris");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void setConfig(SmtcConfig config) {
        if (config == null) return;
        SmtcConfig previous = this.config;
        this.config = config;
        if (previous == null || org.yinyun.smtcraft.smtc.SamplerGate.readsDifferently(previous, config)) {
            reconfigure();
        }
    }

    @Override
    public void reconfigure() {
        forceSample = true;
        synchronized (wake) {
            wake.notifyAll();
        }
    }

    @Override public TrackInfo latest() { return latest; }
    @Override public byte[] artwork() { return artwork; }
    @Override public String artworkHash() { return artworkHash; }
    @Override public boolean running() { return running; }
    @Override public String lastError() { return lastError; }
    @Override public long sampleCount() { return sampleCount; }

    @Override
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
     * <p>The whole shutdown sequence has a budget of its own, so one bridge may
     * not spend the entire {@link MediaBridge#CLOSE_GRACE_MS} and starve the steps
     * after it (AUDIT-76). The thread is interrupted first either way and closes its
     * own bus and arena in its {@code finally} block whatever happens here.</p>
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
        MprisBus bus = null;
        try {
            while (running) {
                SmtcConfig cfg = config;
                if (cfg == null) cfg = new SmtcConfig();
                long roundStart = System.currentTimeMillis();
                try {
                    if (bus == null) {
                        long now = System.currentTimeMillis();
                        if (now < reconnectAt) {
                            // retrying every poll would open a connection per tick
                            // while the daemon is down (AUDIT-59)
                            lastSampleAt = now;
                            sampleCount++;
                            lastError = "dbus: reconnecting";
                            logState(cfg);
                            sleep(Math.max(MIN_SAMPLE_GAP_MS, reconnectAt - now));
                            continue;
                        }
                        try {
                            bus = new MprisBus();
                            reconnectFailures = 0;
                            LOG.info("MPRIS reader connected to the session bus");
                            namesAt = 0L;
                        } catch (IllegalStateException e) {
                            // the outer catch backs off once already; backing off
                            // here too stepped twice per failure and hit the 30 s
                            // ceiling in four attempts instead of seven (AUDIT-66)
                            throw e;
                        }
                    }
                    // cleared before sampling so a note written by publishEmpty
                    // survives the round (AUDIT-52)
                    lastError = "";
                    // per-round, not per-call: a failure early in the round has to
                    // stay readable after the reads that follow it (AUDIT-73)
                    bus.clearError();
                    sample(bus, cfg);
                    lastSampleAt = System.currentTimeMillis();
                    sampleCount++;
                    String busError = bus.lastError();
                    if (busError.isEmpty()) {
                        busFailures = 0;
                    } else {
                        lastError = "dbus: " + busError;
                        // only a connection-level failure is worth reopening the bus
                        // for; a player answering "no such property" would otherwise
                        // drop the connection every five rounds (AUDIT-73)
                        if (bus.transportFailed() && ++busFailures >= RECONNECT_AFTER) {
                            LOG.warn("MPRIS session bus stopped answering ({}), reconnecting", busError);
                            busFailures = 0;
                            try {
                                bus.close();
                            } catch (Throwable ignored) {
                                // a dead connection cannot be closed cleanly
                            }
                            bus = null;
                        }
                    }
                    logState(cfg);
                } catch (Throwable t) {
                    lastError = describe(t);
                    // a failed round is not a track change: publishEmpty keeps the
                    // current card until swapGraceMs has passed (AUDIT-57)
                    publishEmpty(cfg, lastError);
                    backoff();
                    if (bus != null) {
                        try {
                            bus.close();
                        } catch (Throwable ignored) {
                            // the connection is going away either way
                        }
                        bus = null;
                    }
                    logState(cfg);
                }
                long wait = Math.max(MIN_SAMPLE_GAP_MS,
                        clamp(cfg.pollIntervalMs, 60, 5_000)
                                - (System.currentTimeMillis() - roundStart));
                if (forceSample) {
                    forceSample = false;
                    wait = 0L;
                }
                sleep(wait);
            }
        } finally {
            if (bus != null) {
                try {
                    bus.close();
                } catch (Throwable ignored) {
                    // shutting down
                }
            }
            running = false;
        }
    }

    private void sample(MprisBus bus, SmtcConfig cfg) {
        long now = System.currentTimeMillis();
        long interval = names.isEmpty() ? EMPTY_NAMES_REFRESH_MS : NAMES_REFRESH_MS;
        if (namesAt == 0L || now - namesAt >= interval) {
            List<String> all = bus.listNames();
            List<String> players = new ArrayList<>();
            for (String name : all) {
                if (name.startsWith(MprisTrack.PREFIX)) players.add(name);
            }
            names = players;
            namesAt = now;
            // a player that left must not keep its identity cached forever (AUDIT-51-0).
            // The cache is keyed by app name while the bus names are not, so the
            // live set has to be mapped first: comparing raw names emptied the
            // whole cache every refresh and re-asked Identity forever (AUDIT-63)
            Set<String> live = new HashSet<>();
            for (String player : players) live.add(MprisTrack.filterName(player));
            identities.keySet().retainAll(live);
            if (!currentName.isEmpty() && !players.contains(currentName)) currentName = "";
        }
        if (names.isEmpty()) {
            // an empty list from a broken bus is not an idle desktop, and saying so
            // is what makes a Position timeout diagnosable later (AUDIT-73)
            String busError = bus.lastError();
            publishEmpty(cfg, busError.isEmpty()
                    ? "no MPRIS player on the session bus"
                    : "dbus: " + busError);
            return;
        }
        Chosen chosen = choose(bus, cfg);
        if (chosen == null) {
            String busError = bus.lastError();
            publishEmpty(cfg, !busError.isEmpty()
                    ? "dbus: " + busError
                    : cfg.ignoreBrowserSessions
                            ? "no MPRIS player left after ignoring browser sessions"
                            : "no MPRIS player answered");
            return;
        }
        currentName = chosen.name;
        Map<String, Object> props = chosen.props;
        long position = position(bus, chosen.name, props);
        String identity = identity(bus, chosen.name);
        MprisTrack.Snapshot snapshot = MprisTrack.of(chosen.name, identity, props, position);
        publish(snapshot, cfg);
    }

    /** The player this round, already fetched so the round costs one GetAll each. */
    private record Chosen(String name, Map<String, Object> props, int score) {}

    /**
     * The player this round.
     *
     * <p>Each probe is a blocking {@code GetAll} that can burn the full bus
     * timeout, so the round is bounded by {@link #PROBE_BUDGET_MS} rather than by
     * the number of players: with ten players and a dead one in the list, an
     * unbudgeted loop took twenty seconds a round (AUDIT-54). The order starts at the
     * current player (stickiness) and rotates through the rest, so every player
     * is eventually examined without letting the card hop between two of them.</p>
     */
    private Chosen choose(MprisBus bus, SmtcConfig cfg) {
        Chosen best = null;
        List<String> order = probeOrder();
        long deadline = System.currentTimeMillis() + PROBE_BUDGET_MS;
        int probed = 0;
        for (String name : order) {
            String suffix = MprisTrack.filterName(name);
            if (cfg.ignoreBrowserSessions && Draw.isBrowserApp(suffix)) continue;
            long remaining = deadline - System.currentTimeMillis();
            if (probed > 0 && remaining <= 0L) break;
            // the reply timeout is what is left of the budget, so an unreachable
            // player cannot spend the round on its own (AUDIT-62)
            int timeout = (int) Math.max(60L, Math.min(MprisBus.TIMEOUT_MS,
                    remaining > 0L ? remaining : PROBE_BUDGET_MS));
            // a player can drop off the bus between listNames and GetAll; the
            // bus reports that as an empty reply, so skip instead of failing
            Map<String, Object> props = bus.getAll(name, MprisTrack.PLAYER_IFACE, timeout);
            probed++;
            if (props.isEmpty()) continue;
            MprisTrack.Snapshot probe = MprisTrack.of(name, "", props, -1L);
            int score = MprisTrack.rank(probe);
            // stickiness: without it two idle players trade the card every round
            if (name.equals(currentName)) score += 3;
            if (best == null || score > best.score) best = new Chosen(name, props, score);
        }
        if (!names.isEmpty()) probeCursor = (probeCursor + 1) % names.size();
        return best;
    }

    /** Current player first, then everyone else from the rotating cursor. */
    private List<String> probeOrder() {
        int count = names.size();
        if (count == 0) return List.of();
        int start = Math.floorMod(probeCursor, count);
        List<String> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++) order.add(names.get((start + i) % count));
        if (!currentName.isEmpty() && order.remove(currentName)) order.add(0, currentName);
        return order;
    }

    /** Play position in milliseconds, or −1 when the player exposes none. */
    private long position(MprisBus bus, String name, Map<String, Object> props) {
        Object raw = props.get("Position");
        if (raw == null) {
            raw = bus.getProperty(name, MprisTrack.PLAYER_IFACE, "Position", QUERY_TIMEOUT_MS);
        }
        if (raw == null) return -1L;
        long micros = MprisTrack.integer(raw, -1L);
        return micros < 0L ? -1L : micros / 1000L;
    }

    private String identity(MprisBus bus, String name) {
        // keyed by app, not by bus name: a player restarting as .instance1234 is
        // the same identity, and the old key would leak (AUDIT-51-0)
        String base = MprisTrack.filterName(name);
        String cached = identities.get(base);
        if (cached != null) return cached;
        Object raw = bus.getProperty(name, MprisTrack.ROOT_IFACE, "Identity", QUERY_TIMEOUT_MS);
        String value = raw == null ? "" : String.valueOf(raw);
        if (value.isBlank()) value = MprisTrack.shortName(name);
        identities.put(base, value);
        return value;
    }

    private void publishEmpty(SmtcConfig cfg, String note) {
        long now = System.currentTimeMillis();
        boolean hasNote = note != null && !note.isEmpty();
        if (latest.hasTrack()) {
            long held = now - lastTrackAt;
            if (held <= Math.max(0, cfg.swapGraceMs)) {
                // the card stays during the grace window, but the reason does not
                if (hasNote) lastError = note;
                return;
            }
        }
        if (hasNote) lastError = note;
        latest = TrackInfo.EMPTY;
        heldKey = "";
        heldSince = 0L;
        playedMs = 0L;
        playedSampledAt = 0L;
        artUrl = "";
        artDoneUrl = "";
        artTriedAt = 0L;
        artMisses = 0;
        // artworkHash is the render side's cache key: leaving it behind kept a
        // texture alive that no track referred to any more (AUDIT-64)
        artwork = new byte[0];
        artworkHash = "";
        smoother.reset();
    }

    private void publish(MprisTrack.Snapshot snapshot, SmtcConfig cfg) {
        long now = System.currentTimeMillis();
        if (!snapshot.hasTrack()) {
            publishEmpty(cfg, "the player reports no title");
            return;
        }
        String key = snapshot.key();
        boolean fresh = !key.equals(heldKey);
        if (fresh) {
            heldKey = key;
            heldSince = now;
            playedMs = 0L;
            playedSampledAt = now;
            if (!artworkHash.isEmpty()) {
                artwork = new byte[0];
                artworkHash = "";
            }
            artUrl = "";
            artDoneUrl = "";
            artMisses = 0;
        } else if (snapshot.playing() && playedSampledAt > 0L) {
            playedMs += Math.max(0L, Math.min(now - playedSampledAt, 10_000L));
        }
        playedSampledAt = now;
        updateArt(snapshot, cfg, now);
        long raw = snapshot.positionMs() >= 0L ? snapshot.positionMs() : playedMs;
        long shown = cfg.progressSmooth
                ? smoother.presented(raw, snapshot.playing(), (float) snapshot.rate(), key, now)
                : raw;
        lastTrackAt = now;
        latest = new TrackInfo(true, snapshot.playing(), snapshot.title(), snapshot.artist(),
                snapshot.album(), snapshot.app(), snapshot.status(), shown,
                0L, snapshot.lengthMs(), (float) snapshot.rate(), now, heldSince, playedMs);
    }

    private void updateArt(MprisTrack.Snapshot snapshot, SmtcConfig cfg, long now) {
        if (!cfg.showArtwork) {
            if (!artworkHash.isEmpty()) {
                artwork = new byte[0];
                artworkHash = "";
            }
            artUrl = "";
            // re-opening the switch has to be able to take the cover again, so the
            // "already taken" state goes with the URL it referred to (AUDIT-72)
            artDoneUrl = "";
            return;
        }
        String url = snapshot.artUrl();
        if (url.isEmpty()) return;
        // success is a state of its own: without it the same cover was fetched
        // again every ART_RETRY_MS for the whole song (AUDIT-61)
        if (url.equals(artDoneUrl)) return;
        if (url.equals(artUrl) && artMisses >= ART_MAX_MISSES) return;
        if (!url.equals(artUrl)) {
            artUrl = url;
            artMisses = 0;
            artTriedAt = 0L;
        }
        if (now - artTriedAt < ART_RETRY_MS) return;
        artTriedAt = now;
        byte[] data = MprisArt.fetch(url);
        if (data.length == 0) {
            artMisses++;
            return;
        }
        artMisses = 0;
        String hash = MprisArt.hash(data);
        if (!hash.equals(artworkHash)) {
            artwork = data;
            artworkHash = hash;
        }
        artDoneUrl = url;
    }

    private void logState(SmtcConfig cfg) {
        String err = lastError == null ? "" : lastError;
        if (!err.equals(loggedError)) {
            String had = loggedError;
            loggedError = err;
            if (err.isEmpty()) {
                LOG.info("MPRIS reader ready (was: {})", had.isEmpty() ? "starting" : had);
            } else {
                LOG.warn("MPRIS reader: {}", err);
            }
        }
        TrackInfo info = latest;
        String key = info.hasTrack() ? info.dedupeKey() : "";
        if (!key.equals(loggedTrack)) {
            loggedTrack = key;
            if (!key.isEmpty()) {
                LOG.info("MPRIS now playing: {} - {} ({}), art {} bytes, status {}",
                        info.title(), info.artist(), info.app(), artwork.length, info.status());
            }
        }
        if (cfg.debug) {
            LOG.debug("sample #{} err='{}' player='{}' pos={}/{}", sampleCount, err,
                    currentName, info.positionMs(), info.endMs());
        }
    }

    /** Waits a little longer after each failed attempt to reach the bus. */
    private void backoff() {
        reconnectFailures++;
        long wait = RECONNECT_MIN_MS << Math.min(6, reconnectFailures - 1);
        reconnectAt = System.currentTimeMillis() + Math.min(RECONNECT_MAX_MS, wait);
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
        return value < min ? min : Math.min(value, max);
    }

    private static String describe(Throwable t) {
        return Errors.describe(t);
    }
}
