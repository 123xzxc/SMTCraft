package org.yinyun.smtcraft.util;

/**
 * One multiplier that keeps our pages the size they were designed at.
 *
 * <p>The settings pages and the HUD are laid out in logical pixels and look right at GUI
 * scale 2. At scale 4 every logical pixel is twice as wide on the glass, so the same layout
 * covers twice the screen: the fourteen rows of a tab no longer fit, the row pitch drops to
 * the 10 px floor, and the page reads as one dense block. At scale 1 the opposite happens -
 * everything is half the size it was designed at. Multiplying by {@code 2 / guiScale} puts
 * every scale back onto the scale 2 footprint.</p>
 *
 * <p>This is the pure half: no game types at all, so the self test can compile it offline
 * and drive it with plain numbers. {@link GameScale} is the half that asks the window.</p>
 */
public final class UiScale {

    /** The GUI scale the pages were drawn for; everything else is measured against it. */
    public static final int BASE = 2;

    /** Below this the text stops being readable, whatever the layout does with it. */
    public static final float MIN = 0.4f;

    /** GUI scale 1 would ask for 2x; larger than that only makes a page with no room. */
    public static final float MAX = 2.0f;

    private UiScale() {
    }

    /**
     * Multiplier for one GUI scale.
     *
     * <p>{@code 1} for an unknown or nonsense scale: a page drawn at its designed size is
     * the honest answer when the window cannot say what scale it is running at.</p>
     */
    public static float factor(int guiScale) {
        if (guiScale <= 0) return 1.0f;
        float raw = BASE / (float) guiScale;
        return Math.max(MIN, Math.min(MAX, raw));
    }

    /** A screen length expressed in the pixels the pages are laid out in. */
    public static int design(int length, float factor) {
        if (!(factor > 0.0f) || !Float.isFinite(factor)) return length;
        return Math.round(length / factor);
    }

    /**
     * Design pixels for one wheel notch, so that the travel on the glass stays constant.
     *
     * <p>14 is the distance the reader should see, so the design-space step is {@code 14 / ui}:
     * without the division one notch travels 28 px at GUI scale 1 and 7 px at scale 4, and the
     * smaller the interface the faster the list runs, which is the wrong way round
     * (AUDIT-35-3). A notch never collapses to nothing: a fractional scroll still moves one
     * design pixel, otherwise a trackpad that reports 0.05 per event scrolls nowhere.</p>
     */
    public static int scrollStep(double scrollY, float factor) {
        if (scrollY == 0.0d) return 0;
        if (!(factor > 0.0f) || !Float.isFinite(factor)) {
            int plain = (int) Math.round(scrollY * 14.0d);
            return plain != 0 ? plain : (scrollY > 0.0d ? 1 : -1);
        }
        int step = (int) Math.round(scrollY * 14.0d / factor);
        return step != 0 ? step : (scrollY > 0.0d ? 1 : -1);
    }
}
