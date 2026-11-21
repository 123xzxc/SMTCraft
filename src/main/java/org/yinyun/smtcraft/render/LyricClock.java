package org.yinyun.smtcraft.render;

/**
 * Play position for the lyric band, guarded against short rewinds.
 *
 * <p>What reaches the renderer is a chunky timeline that several layers have
 * re-anchored on the way: the player publishes a step, the bridge smooths it, and
 * a session that reports "paused" for a single sample (buffering, a track that is
 * just starting, a media session that was recreated) sends the whole chain back to
 * the last published step. A dip of even 300 ms is not cosmetic here: the karaoke
 * fill retreats to an earlier word and the band can scroll back a row, which reads
 * as the lyrics flashing backwards for one frame.</p>
 *
 * <p>So the position handed to the renderer only moves forward. A dip of
 * {@link #SEEK_BACK_MS} or more on the same track is a real seek and is followed
 * at once; a smaller one is held for {@link #HOLD_MS} in case it is a glitch and
 * then accepted, so a small seek or a changed lyric offset still takes effect</p>
 */
public final class LyricClock {
    /** A rewind at least this big is the viewer seeking, not a stalled sample. */
    public static final long SEEK_BACK_MS = 1_200L;
    /** How long a smaller rewind is held before it is believed. */
    public static final long HOLD_MS = 400L;

    private String key = "";
    private long shown = Long.MIN_VALUE;
    private long heldSince = 0L;

    /**
     * @param raw      position for this frame, as the bridge reported it
     * @param trackKey identity of the current track; a new one drops the guard
     * @param now      wall clock of this frame, in ms
     * @return the position that may be drawn
     */
    public long advance(long raw, String trackKey, long now) {
        String k = trackKey == null ? "" : trackKey;
        if (shown == Long.MIN_VALUE || !k.equals(key)) {
            key = k;
            shown = raw;
            heldSince = 0L;
            return raw;
        }
        if (raw >= shown) {
            shown = raw;
            heldSince = 0L;
            return raw;
        }
        if (shown - raw >= SEEK_BACK_MS) {
            shown = raw;
            heldSince = 0L;
            return raw;
        }
        if (heldSince == 0L) heldSince = now;
        if (now - heldSince < HOLD_MS) return shown;
        // the feed did not come back: this is a small seek or a new offset
        shown = raw;
        heldSince = 0L;
        return raw;
    }

    /** Drops the guard, so the next frame starts from the raw value. */
    public void reset() {
        key = "";
        shown = Long.MIN_VALUE;
        heldSince = 0L;
    }
}
