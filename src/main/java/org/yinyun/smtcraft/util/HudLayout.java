package org.yinyun.smtcraft.util;

/**
 * Where the two blocks of the HUD sit relative to each other.
 *
 * <p>The panel is either stacked - the track block above the lyric band, the way it has
 * always looked - or side by side: the track block in one column and the lyrics in the
 * and the lyrics in the other. The arrangement is a switch, not a guess about the shape of
 * the window: a player who wants the columns in a wide window means it.</p>
 *
 * <p>Pure arithmetic, no Minecraft types, so the self test compiles and runs this class
 * without a client.</p>
 */
public final class HudLayout {
    public static final String STACK = "stack";
    public static final String SIDE = "side";
    /** Whole panel turned a quarter turn: track text and lyrics read downwards, columns run right to left. */
    public static final String VERTICAL = "vertical";
    /** Space between the two blocks of a side-by-side panel, in design pixels. */
    public static final int GAP = 8;

    private HudLayout() {
    }

    public static boolean side(String layout) {
        return layout != null && SIDE.equalsIgnoreCase(layout.trim());
    }

    /** Is the whole panel laid out as vertical columns? */
    public static boolean vertical(String layout) {
        return layout != null && VERTICAL.equalsIgnoreCase(layout.trim());
    }

    /**
     * The stored name, normalised to one of the two arrangements this class knows. A hand
     * edited file can name a layout that does not exist; the panel would then print the name
     * in its settings row and draw the stack anyway.
     */
    public static String clean(String layout) {
        if (vertical(layout)) return VERTICAL;
        if (side(layout)) return SIDE;
        return STACK;
    }

    /** One glyph box of vertical text, before scaling (matches the font's line height). */
    public static final int GLYPH = 11;
    /** Gap between two vertical columns. */
    public static final int COLUMN_GAP = 3;

    /**
     * One vertical column of lyric text: a glyph box plus a little breathing room, scaled.
     */
    public static int columnWidth(float scale) {
        float s = usable(scale);
        return Math.max(10, Math.round(GLYPH * s * 1.15f));
    }

    /** Distance between the left edges of two neighbouring vertical columns. */
    public static int columnStep(float scale) {
        return columnWidth(scale) + Math.max(2, Math.round(COLUMN_GAP * usable(scale)));
    }

    /**
     * The same for lyric columns, which sit closer together: a column of kanji already carries
     * its own side bearing, so the track gap read as a hole between two lines of one song.
     */
    public static int lyricStep(float scale) {
        return columnWidth(scale) + Math.max(1, Math.round(1 * usable(scale)));
    }

    /**
     * Left edge of the column that shows the line {@code offset} steps away from the one being
     * sung (0 = current, -1 = the previous line, +1 = the next). Columns are laid out from the
     * right edge, because that is the way vertical CJK text reads: the line being sung sits one
     * step in from the right, the line already read sits on the right, the next line to its
     * left. A column that falls off the left edge returns a negative x; the caller skips it.
     */
    public static int columnLeft(int offset, int width, int pad, float scale) {
        return width - pad - columnWidth(scale) - (offset + 1) * columnStep(scale);
    }

    /** A scale that is safe to multiply by: anything that is not a positive number means 1. */
    private static float usable(float scale) {
        return scale > 0.0f && !Float.isNaN(scale) && !Float.isInfinite(scale) ? scale : 1.0f;
    }

    /** Centre a run of text in its column. */
    public static final int CENTRE = 0;
    /** Hug the left edge of the column. */
    public static final int LEFT = 1;
    /** Hug the right edge of the column. */
    public static final int RIGHT = -1;

    /**
     * Which edge a lyric column hugs in a side-by-side panel: the edge next to the seam, so
     * the two blocks read as one table of text instead of two centred islands. A stacked
     * panel keeps its text centred.
     */
    public static int seamAlign(boolean side, boolean trackFirst) {
        if (!side) return CENTRE;
        return trackFirst ? LEFT : RIGHT;
    }

    /**
     * Where a run of text of width {@code textW} starts inside a column {@code width} wide,
     * with {@code pad} on both sides. The caller is expected to have fitted the run to the
     * column; a run wider than the room keeps to the left edge rather than hanging outside.
     */
    public static int textLeft(int align, int textW, int width, int pad) {
        int room = Math.max(0, width - pad * 2);
        int w = Math.min(Math.max(0, textW), room);
        if (align > 0) return pad;
        if (align < 0) return pad + room - w;
        return pad + (room - w) / 2;
    }

    /**
     * Design pixels a second a run travels when it does not fit: the one pace the marquee, the
     * vertical lyric column and the track columns all share, so nothing drifts apart (m12491).
     */
    public static final float MARQUEE_SPEED = 26.0f;
    /** Seconds a run holds at each end before it starts, and before it turns back (m12491). */
    public static final float MARQUEE_PAUSE = 1.3f;

    /**
     * How far a vertical run of {@code runH} pixels is scrolled inside a strip {@code room}
     * pixels tall at {@code clock} seconds. A run that fits never moves. A long one rests once,
     * slides down until its last glyph is in view, and stays there - the vertical twin of the
     * horizontal marquee, which a column of text cannot use: a line scrolled sideways in a
     * column is unreadable, so the whole run walks instead (m12260).
     *
     * <p>One way only (m12372). It used to travel down, rest, and travel back, so a long line
     * was read bottom-to-top every cycle and the eye could never settle on the tail of it.</p>
     *
     * <p>It walks at the marquee's pace (m12491): a column that slid at 22 px a second beside a
     * marquee sliding at 26 looked like a different panel.</p>
     */
    public static int verticalScroll(double clock, int runH, int room) {
        return Math.round(scrollOver(runH - Math.max(0, room), clock, MARQUEE_SPEED,
                MARQUEE_PAUSE));
    }

    /**
     * How far a box with {@code overflow} pixels hidden has scrolled at {@code clock} seconds:
     * hold the start for {@code pause}, travel with a smooth ease, then stay at the end.
     *
     * <p>One way only (m12372). Both scrollers used to cycle - travel, rest, come back - so a
     * line that did not fit was re-read from its beginning every few seconds and its tail never
     * felt arrived. The horizontal marquee and the vertical run share this one formula, so they
     * cannot drift apart again.</p>
     */
    public static float scrollOver(float overflow, double clock, float speed, float pause) {
        if (overflow <= 0.0f) return 0.0f;
        double travel = Math.max(1.0d, overflow / (double) Math.max(1.0f, speed));
        double u = clock - pause;
        if (u <= 0.0d) return 0.0f;
        if (u >= travel) return overflow;
        return (float) (overflow * ease(u / travel));
    }

    /**
     * The cyclic twin of {@link #scrollOver}: hold, slide, hold, slide back, forever. Track text
     * uses this - a title that parks at its tail is unreadable from the front, and it is short
     * enough that the return trip costs nothing (m12414). Only lyrics scroll one way.
     *
     * <p>Eased the same way as {@link #scrollOver} on both legs (R49-5). It used to slide
     * linearly while the lyric rows eased, so two columns of one panel set off together and
     * drifted apart for the first half second, then swapped - the eye reads that as one of them
     * lagging.</p>
     */
    public static float scrollCycle(float overflow, double clock, float speed, float pause) {
        if (overflow <= 0.0f) return 0.0f;
        double travel = Math.max(1.0d, overflow / (double) Math.max(1.0f, speed));
        double rest = Math.max(0.0d, pause);
        double period = 2.0d * (travel + rest);
        // The phase is taken in double, against this cycle's own period. Wrapping the clock
        // first, on any fixed constant, put every cycling column back at its first glyph on the
        // hour - the wrap constant is never a whole number of these periods (R51-1).
        double t = clock % period;
        if (t < 0.0d) t += period;
        double u = t - rest;
        if (u <= 0.0d) return 0.0f;
        if (u <= travel) return (float) (overflow * ease(u / travel));
        u -= travel;
        if (u <= rest) return overflow;
        return (float) (overflow * (1.0d - ease(Math.min(1.0d, (u - rest) / travel))));
    }

    /** Smoothstep, the one curve both scrollers use: soft start, soft stop, no drift apart. */
    private static double ease(double t) {
        double c = Math.max(0.0d, Math.min(1.0d, t));
        return c * c * (3.0d - 2.0d * c);
    }

    /** The cyclic twin of {@link #verticalScroll}, for a track column that must come back. */
    public static int verticalScrollCycle(double clock, int runH, int room) {
        return Math.round(scrollCycle(runH - Math.max(0, room), clock, MARQUEE_SPEED,
                MARQUEE_PAUSE));
    }

    /** The stored name for a switch that is either on (side by side) or off (stacked). */
    public static String name(boolean side) {
        return side ? SIDE : STACK;
    }

    /**
     * Place the two blocks inside the panel.
     *
     * <p>A block with no height is placed at the other block's origin, so a lyrics-only
     * panel does not slide and an empty one still reports the size it draws.</p>
     *
     * @return {trackX, trackY, lyricX, lyricY, panelW, panelH}
     */
    public static int[] arrange(boolean side, boolean trackFirst, int trackW, int trackH,
                                int lyricW, int lyricH) {
        int tw = Math.max(0, trackW);
        int th = Math.max(0, trackH);
        int lw = Math.max(0, lyricW);
        int lh = Math.max(0, lyricH);
        boolean both = tw > 0 && th > 0 && lw > 0 && lh > 0;

        if (side && both) {
            int panelW = tw + GAP + lw;
            int panelH = Math.max(th, lh);
            int lyricX = trackFirst ? tw + GAP : 0;
            int trackX = trackFirst ? 0 : lw + GAP;
            // the shorter block is centred against the taller one rather than hung from
            // the top edge, which is what makes a side-by-side panel look deliberate
            return new int[]{trackX, (panelH - th) / 2, lyricX, (panelH - lh) / 2, panelW, panelH};
        }

        // one block (or no band): the side-by-side arrangement has nothing to put beside
        int panelW = Math.max(tw, lw);
        int panelH = th + lh;
        return new int[]{0, trackFirst ? 0 : lh, 0, trackFirst ? th : 0, panelW, panelH};
    }
}
