package org.yinyun.smtcraft.smtc;

/**
 * Immutable snapshot of one SMTC sample.
 *
 * <p>The bridge samples every {@code pollIntervalMs}; between two samples the
 * HUD extrapolates the play position locally so a 700 ms poll rate still looks
 * smooth at 60 fps.</p>
 */
public final class TrackInfo {
    public static final TrackInfo EMPTY =
            new TrackInfo(false, false, "", "", "", "", "unknown", 0L, 0L, 0L, 1.0f, 0L, 0L);

    private final boolean ok;
    private final boolean playing;
    private final String title;
    private final String artist;
    private final String album;
    private final String app;
    private final String status;
    private final long positionMs;
    private final long startMs;
    private final long endMs;
    private final float rate;
    private final long sampledAt;
    /** Wall clock of the first sample that carried this track, 0 when unknown. */
    private final long firstSeenAt;
    /**
     * Wall-clock milliseconds this track has actually been playing, accumulated
     * by the bridge while {@link #playing()} was true. Pauses must not count, so
     * this is the only honest timeline for apps that report no position.
     */
    private final long playedMs;

    public TrackInfo(boolean ok, boolean playing, String title, String artist, String album,
                     String app, String status, long positionMs, long startMs, long endMs,
                     float rate, long sampledAt) {
        this(ok, playing, title, artist, album, app, status, positionMs, startMs, endMs, rate,
                sampledAt, 0L);
    }

    public TrackInfo(boolean ok, boolean playing, String title, String artist, String album,
                     String app, String status, long positionMs, long startMs, long endMs,
                     float rate, long sampledAt, long firstSeenAt) {
        this(ok, playing, title, artist, album, app, status, positionMs, startMs, endMs, rate,
                sampledAt, firstSeenAt, 0L);
    }

    public TrackInfo(boolean ok, boolean playing, String title, String artist, String album,
                     String app, String status, long positionMs, long startMs, long endMs,
                     float rate, long sampledAt, long firstSeenAt, long playedMs) {
        this.ok = ok;
        this.playing = playing;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.app = app == null ? "" : app;
        this.status = status == null ? "" : status;
        this.positionMs = positionMs;
        this.startMs = startMs;
        this.endMs = endMs;
        this.rate = rate <= 0.0f ? 1.0f : rate;
        this.sampledAt = sampledAt;
        this.firstSeenAt = firstSeenAt;
        this.playedMs = Math.max(0L, playedMs);
    }

    public boolean ok() { return ok; }
    public boolean playing() { return playing; }
    public String title() { return title; }
    public String artist() { return artist; }
    public String album() { return album; }
    public String app() { return app; }
    public String status() { return status; }
    public long positionMs() { return positionMs; }
    public long startMs() { return startMs; }
    public long endMs() { return endMs; }
    public float rate() { return rate; }
    public long sampledAt() { return sampledAt; }
    public long firstSeenAt() { return firstSeenAt; }
    public long playedMs() { return playedMs; }

    public boolean hasTrack() { return ok && !title.isEmpty(); }

    /** Play position right now, extrapolated from the last sample. */
    public long livePositionMs() {
        long pos = positionMs;
        if (playing && sampledAt > 0L) {
            pos += (long) ((System.currentTimeMillis() - sampledAt) * rate);
        }
        if (endMs > startMs) {
            if (pos < startMs) pos = startMs;
            if (pos > endMs) pos = endMs;
        }
        return pos;
    }

    /** 0..1 progress, or -1 when the session reports no usable timeline. */
    public float progress() {
        long span = endMs - startMs;
        if (span <= 0L) return -1.0f;
        float p = (livePositionMs() - startMs) / (float) span;
        if (p < 0.0f) return 0.0f;
        return Math.min(p, 1.0f);
    }

    public long elapsedMs() {
        long e = livePositionMs() - startMs;
        return Math.max(0L, e);
    }

    public long durationMs() {
        return Math.max(0L, endMs - startMs);
    }

    /**
     * Elapsed time for players that report no timeline (the NetEase SMTC feed,
     * most browser sessions). The bridge accumulates playing time, so a pause
     * freezes the counter instead of letting the lyrics run on: subtracting the
     * first-seen stamp from a moving "now" counted every paused millisecond too.
     */
    public long fallbackElapsedMs() {
        if (firstSeenAt <= 0L && playedMs <= 0L) return Math.max(0L, elapsedMs());
        long base = playedMs > 0L ? playedMs : Math.max(0L, sampledAt - firstSeenAt);
        if (playing && sampledAt > 0L) {
            base += Math.max(0L, System.currentTimeMillis() - sampledAt);
        }
        return Math.max(0L, base);
    }

    public String dedupeKey() {
        return title + '\u0001' + artist + '\u0001' + album + '\u0001' + app;
    }
}
