package org.yinyun.smtcraft.smtc;

/**
 * Turns a chunky timeline into a smooth one.
 *
 * <p>Music apps publish their position in coarse steps — CloudMusic and a few
 * browsers hand out a new value once a second, some players every 500 ms — and
 * the value describes the moment it was published, not the moment this mod polls
 * it. Extrapolating from the poll therefore kept the HUD up to a full step behind
 * the audio, which reads as "the lyrics are about one second late".</p>
 *
 * <p>So the position is extrapolated from the moment the raw value last *changed*
 * ({@code raw + (now - changedAt)}): a fresh step resets the anchor, and between
 * steps the clock keeps running. That is continuous (no rewinds, so the progress
 * bar never hops back) and it tracks the audio instead of the polling.</p>
 */
public final class ProgressSmoother {
    /** Backward jumps bigger than this are a real seek / restart, not a glitch. */
    private static final long SEEK_MS = 1500L;
    /**
     * Forward jumps bigger than this are a seek unless the wall clock already
     * explains them: a player that publishes every four seconds jumps four
     * seconds and is not seeking (AUDIT-26-3).
     */
    private static final long SEEK_STEP_MS = 2500L;
    /**
     * Longest gap that may be learned as a publishing period. It used to be
     * {@link #SEEK_STEP_MS}, so a slow publisher was permanently classified as a
     * stall and its cap never grew past 1.5 s while the screen fell 2.4 s behind
     * (AUDIT-26-3).
     */
    private static final long LEARN_MAX_MS = 10000L;
    /**
     * Largest step/gap mismatch that still counts as a publish beat. A frozen app
     * closes a four second gap while the audio moved 600 ms, which lands far
     * outside this; a player that quantises its position to 500 ms lands inside
     * {@code gap/2} of it and keeps the default period (R26-3, R26-4).
     */
    private static final long STEP_SLACK_MS = 400L;
    /** A gap has to arrive this many times before it becomes the period. */
    private static final int LEARN_CONFIRMATIONS = 2;
    /** Floor for how long a step may be extrapolated when the app publishes slowly. */
    private static final long MIN_EXTRAPOLATE_MS = 1500L;
    /** Ceiling: nothing sane publishes its timeline less often than this. */
    private static final long MAX_EXTRAPOLATE_MS = 10000L;

    private String key = "";
    private long presented;
    private long lastRaw;
    /** Wall clock of the sample that carried the newest raw value. */
    private long changedAt;
    /** How often this player publishes a new position, learned from the steps. */
    private long interval = 1000L;
    /** Candidate period and how many times it has shown up in a row. */
    private long pendingGap;
    private int pendingSeen;
    private boolean primed;

    /**
     * @param rawPos   position reported by SMTC for this sample
     * @param playing  whether the session is playing right now
     * @param rate     playback rate reported by SMTC (1.0 for normal speed)
     * @param trackKey identity of the current track, used to detect track changes
     * @param now      wall clock of this sample, in ms
     * @return the position that should be shown on screen
     */
    public long presented(long rawPos, boolean playing, float rate, String trackKey, long now) {
        float r = rate <= 0.0f ? 1.0f : rate;
        String k = trackKey == null ? "" : trackKey;
        if (!primed || !k.equals(key)) {
            // New track (or the first sample): trust the raw timeline as it is.
            reset(k, rawPos, now);
            return rawPos;
        }
        if (!playing) {
            // Paused or buffering. The raw value describes the last step the app
            // published, which is a step behind the position already on screen, so
            // handing it back moved the karaoke fill backwards for one poll and
            // forward again when playback resumed. Hold the line and keep the
            // anchor here, so a resume counts from what the viewer saw.
            changedAt = now;
            lastRaw = rawPos;
            return presented;
        }
        long step = rawPos - lastRaw;
        long gap = Math.max(0L, now - changedAt);
        // A step the wall clock accounts for is the app catching up, however large:
        // a four second publisher jumps four seconds and is not seeking (AUDIT-26-3).
        boolean explained = step > 0L
                && Math.abs(step - Math.round(gap * r)) <= STEP_SLACK_MS;
        long out;
        if (step < -SEEK_MS || (step > SEEK_STEP_MS && !explained)) {
            // a user seek or a restart: follow it immediately, in both directions
            changedAt = now;
            forgetBeat();
            out = rawPos;
        } else {
            if (step != 0L) {
                // A new step arrived: the value describes this moment, so re-anchor.
                // Learn the publishing period from the gap it just closed, but only
                // when the gap looks like a beat. A stall (the app froze for four
                // seconds while still reporting 'playing') closes a four second gap,
                // and learning that raised the extrapolation cap exactly when the
                // feed was least trustworthy - the cap is there to survive a stall,
                // not to grow on one (AUDIT-25-3). The gap also has to repeat before it
                // is adopted: one stall-shaped gap is a guess, two equal ones are a
                // period (AUDIT-26-3).
                if (isBeat(step, gap, r)) {
                    learn(gap);
                } else {
                    forgetBeat();
                }
                changedAt = now;
            }
            long cap = Math.max(MIN_EXTRAPOLATE_MS, interval + 400L);
            long since = Math.min(Math.max(0L, now - changedAt), cap);
            out = rawPos + Math.round(since * r);
            if (out < presented) out = presented;   // never rewind while playing
        }
        this.key = k;
        this.presented = out;
        this.lastRaw = rawPos;
        return out;
    }

    /**
     * Whether a step closed a publish beat rather than a stall: the gap has to be
     * believable, and the distance the position moved has to match the time that
     * passed. The second half is what a frozen app cannot fake, and what a player
     * that only reports whole half seconds fails - which is why it keeps the
     * default period instead of teaching the smoother something wrong (AUDIT-26-4).
     */
    private static boolean isBeat(long step, long gap, float rate) {
        return gap >= 100L && gap <= LEARN_MAX_MS
                && Math.abs(step - Math.round(gap * rate)) <= STEP_SLACK_MS;
    }

    /** Adopt a period only after it has shown up twice: one gap is a guess. */
    private void learn(long gap) {
        if (Math.abs(gap - pendingGap) <= STEP_SLACK_MS) {
            pendingSeen++;
        } else {
            pendingGap = gap;
            pendingSeen = 1;
        }
        if (pendingSeen >= LEARN_CONFIRMATIONS) {
            interval = Math.max(100L, Math.min(MAX_EXTRAPOLATE_MS, gap));
        }
    }

    private void forgetBeat() {
        pendingGap = 0L;
        pendingSeen = 0;
    }

    /** Forget the current track (no session, or a sample that carried no title). */
    public void reset() {
        primed = false;
        key = "";
        presented = 0L;
        lastRaw = 0L;
        changedAt = 0L;
        interval = 1000L;
        forgetBeat();
    }

    private void reset(String k, long rawPos, long now) {
        key = k;
        presented = rawPos;
        lastRaw = rawPos;
        changedAt = now;
        forgetBeat();
        primed = true;
    }
}
