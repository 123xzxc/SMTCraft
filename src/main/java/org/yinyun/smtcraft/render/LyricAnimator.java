package org.yinyun.smtcraft.render;

/**
 * Continuous vertical scroll for the lyric band.
 *
 * <p>The top slot is tracked as a float row index that eases toward the row that
 * should be on screen. Every row is then placed from that single offset, so a line
 * change is a real one-row scroll instead of a jump, and the renderer can fade a
 * row out by its distance to the band edge (which is what keeps glyphs from being
 * drawn half sliced by the scissor).</p>
 */
public final class LyricAnimator {
    /** Seconds a one-row hand-over takes. */
    private static final float SECONDS = 0.30f;

    private float scroll;
    private float from;
    private float progress = 1.0f;
    private int target = Integer.MIN_VALUE;

    /**
     * Advances the scroll toward {@code base} and returns the current top row as a
     * float. A jump of more than one row (seek, skip, new song) lands immediately
     * instead of scrolling through everything in between.
     */
    public float update(float dt, int base) {
        if (target == Integer.MIN_VALUE) {
            snap(base);
            return scroll;
        }
        if (base != target) {
            if (Math.abs(base - scroll) > 1.6f) {
                snap(base);
                return scroll;
            }
            from = scroll;
            target = base;
            progress = 0.0f;
        }
        if (progress < 1.0f) {
            progress = Math.min(1.0f, progress + (dt <= 0.0f ? 0.05f : dt / SECONDS));
            scroll = from + (target - from) * LyricsRenderer.smooth(progress);
        }
        return scroll;
    }

    /** True while a hand-over is still in flight. */
    public boolean moving() {
        return progress < 1.0f;
    }

    /** Eased 0..1 progress of the current hand-over; 1 when nothing moves. */
    public float eased() {
        return progress >= 1.0f ? 1.0f : LyricsRenderer.smooth(progress);
    }

    /**
     * Lands the hand-over immediately. Used while the panel is hidden: the scroll
     * clock is not advanced then, and resuming mid-ease looked like a stutter.
     */
    public void finish() {
        if (target == Integer.MIN_VALUE) return;
        scroll = target;
        from = target;
        progress = 1.0f;
    }

    /** Jumps to {@code base} without animating. */
    public void snap(int base) {
        target = base;
        scroll = base;
        from = base;
        progress = 1.0f;
    }
}
