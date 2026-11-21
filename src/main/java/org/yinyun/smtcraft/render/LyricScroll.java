package org.yinyun.smtcraft.render;

/**
 * Where a scrolling lyric row sits horizontally.
 *
 * <p>Both readers of a lyric row need the same answer: the renderer knows the sung
 * part down to the glyph, the HUD only knows how much of the line's duration has
 * played. They used to spell the formula out separately, with the anchor borrowed
 * across the class boundary, which meant two metrics for one intent (R25-5).</p>
 *
 * <p>No Minecraft types here on purpose: this is arithmetic, and it has to be
 * testable without a client jar.</p>
 */
public final class LyricScroll {
    /** Where the karaoke play head sits inside a scrolling box, as a fraction of its width. */
    public static final float HEAD_ANCHOR = 0.68f;

    /**
     * Horizontal offset that pins the sung edge to {@link #HEAD_ANCHOR}, clamped so
     * the tail never runs past the box.
     *
     * @param sungPx   width of the line already sung, in pixels
     * @param drawnW   full width of the line, in pixels
     * @param maxWidth visible width of the box, in pixels
     * @return pixels to shift the row left by
     */
    public static float followOffset(float sungPx, float drawnW, float maxWidth) {
        if (maxWidth <= 0.0f || drawnW <= maxWidth) return 0.0f;
        return Math.max(0.0f, Math.min(drawnW - maxWidth, sungPx - maxWidth * HEAD_ANCHOR));
    }

    private LyricScroll() {}
}
