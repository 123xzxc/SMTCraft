package org.yinyun.smtcraft.render;

import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.smtc.LyricLine;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Text helpers that know about an optional font override, bold/italic flags, a
 * local scale, the karaoke sweep with an optional swell around the play head and
 * the marquee used for text that is wider than its box.
 *
 * <p>26.3 removed the possibility of handing a raw {@code Font} for another
 * resource-pack font: a font is selected per text run through
 * {@link FontDescription}, so a custom font id becomes a {@code Component} with
 * a styled {@link FontDescription.Resource}.</p>
 */
public final class LyricsRenderer {
    public static final int LINE_H = 11;

    /** How far a held syllable is stretched horizontally while it is being sung. */
    private static final float STRETCH_MAX = 0.42f;
    /** A note is "long" once it sounds this many milliseconds. */
    private static final float HELD_FULL_MS = 720.0f;
    /** Notes shorter than this never swell, however short the rest of the line is. */
    private static final float HELD_MIN_MS = 210.0f;

    /** Marquee timing: hold at both ends, then slide. */
    private static final float MARQUEE_SPEED = 26.0f;
    private static final float MARQUEE_PAUSE = 1.3f;

    private LyricsRenderer() {}

    // ------------------------------------------------------------- measurements
    //
    // Measuring a styled run means building a Component and a Style per call, and
    // the karaoke path asks for every glyph of a line on every frame. Widths and
    // one-glyph strings are therefore memoised (A23): the cache key carries the
    // Font instance, so a resource reload that replaces the Font starts fresh.
    private static final int WIDTH_CACHE_MAX = 16_384;
    private static final int GLYPH_CACHE_MAX = 4_096;
    private static final java.util.Map<WidthKey, Float> WIDTHS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Integer, String> GLYPH_TEXT =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record WidthKey(Font font, Identifier fontId, boolean bold, boolean italic, int cp) {}

    /** Width of one code point under {@code look}, cached. */
    private static float glyphWidth(Font font, int cp, Look look) {
        Identifier id = look == null ? null : look.fontId;
        boolean bold = look != null && look.bold;
        boolean italic = look != null && look.italic;
        WidthKey key = new WidthKey(font, id, bold, italic, cp);
        Float hit = WIDTHS.get(key);
        if (hit != null) return hit;
        float w = width(font, glyphText(cp), look);
        if (WIDTHS.size() >= WIDTH_CACHE_MAX) WIDTHS.clear();
        WIDTHS.put(key, w);
        return w;
    }

    /** The one-code-point string for {@code cp}, cached so hot loops stop slicing. */
    private static String glyphText(int cp) {
        String hit = GLYPH_TEXT.get(cp);
        if (hit != null) return hit;
        String s = new String(Character.toChars(cp));
        if (GLYPH_TEXT.size() >= GLYPH_CACHE_MAX) GLYPH_TEXT.clear();
        GLYPH_TEXT.put(cp, s);
        return s;
    }

    /** Drop every memoised measurement (resource reload, font change). */
    public static void invalidateCaches() {
        WIDTHS.clear();
        GLYPH_TEXT.clear();
    }

    // ------------------------------------------------------------- frame scratch
    //
    // One HUD line is measured and swept per frame; allocating four float arrays
    // per line showed up as garbage in the profiler (A24). These buffers are grown
    // on demand and reused - the render thread is the only caller.
    private static float[] localBuf = new float[0];
    private static float[] weightBuf = new float[0];
    private static float[] fromBuf = new float[0];
    private static float[] toBuf = new float[0];
    private static long[] lenBuf = new long[0];

    private static float[] take(float[] buf, int n) {
        return buf.length >= n ? buf : new float[n];
    }

    /** Everything a text run needs to know about how it should look. */
    public static final class Look {
        public final Identifier fontId;
        public final boolean bold;
        public final boolean italic;
        public final boolean shadow;

        public Look(Identifier fontId, boolean bold, boolean italic, boolean shadow) {
            this.fontId = fontId;
            this.bold = bold;
            this.italic = italic;
            this.shadow = shadow;
        }

        public static Look plain(Identifier fontId, boolean shadow) {
            return new Look(fontId, false, false, shadow);
        }

        public static Look of(SmtcConfig cfg, Identifier fontId) {
            if (cfg == null) return plain(fontId, true);
            return new Look(fontId, cfg.lyricBold, cfg.lyricItalic, cfg.shadow);
        }

        public Look withoutStyle() {
            return new Look(fontId, false, false, shadow);
        }
    }

    public static Style style(Look look) {
        Style s = Style.EMPTY;
        if (look.bold) s = s.withBold(Boolean.TRUE);
        if (look.italic) s = s.withItalic(Boolean.TRUE);
        if (look.fontId != null) s = s.withFont(new FontDescription.Resource(look.fontId));
        return s;
    }

    public static net.minecraft.network.chat.Component styled(String text, Look look) {
        return net.minecraft.network.chat.Component.literal(text).withStyle(style(look));
    }

    public static int width(Font font, String text, Look look) {
        if (font == null || text == null || text.isEmpty()) return 0;
        if (look == null || (look.fontId == null && !look.bold && !look.italic)) {
            return font.width(text);
        }
        return font.width(styled(text, look));
    }

    public static void text(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color,
                            Look look) {
        if (text == null || text.isEmpty()) return;
        if (look == null || (look.fontId == null && !look.bold && !look.italic)) {
            g.text(font, text, x, y, color, look == null || look.shadow);
        } else {
            g.text(font, styled(text, look), x, y, color, look.shadow);
        }
    }

    public static void draw(GuiGraphicsExtractor g, Font font, String text, int x, int y,
                            int color, Look look, float scale) {
        if (text == null || text.isEmpty()) return;
        if (nearOne(scale)) {
            text(g, font, text, x, y, color, look);
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale);
        text(g, font, text, 0, 0, color, look);
        g.pose().popMatrix();
    }

    public static void drawCentered(GuiGraphicsExtractor g, Font font, String text, int centerX,
                                    int y, int color, Look look, float scale) {
        float scaled = width(font, text, look) * (nearOne(scale) ? 1.0f : scale);
        draw(g, font, text, Math.round(centerX - scaled / 2.0f), y, color, look, scale);
    }

    /**
     * Text that scrolls inside {@code maxWidth} when it does not fit: hold, slide
     * to the end, hold, jump back. Glyphs outside the box are skipped, so no
     * scissor is needed and the panel can be anchored anywhere.
     */
    public static void marquee(GuiGraphicsExtractor g, Font font, String text, int x, int y,
                               int maxWidth, int color, Look look, float scale, float clock,
                               boolean enabled) {
        if (text == null || text.isEmpty() || maxWidth <= 0) return;
        int textW = width(font, text, look);
        float scaledW = textW * scale;
        if (!enabled || scaledW <= maxWidth + 0.5f) {
            draw(g, font, text, x, y, color, look, scale);
            return;
        }
        float overflow = scaledW - maxWidth;
        float offset = marqueeOffset(overflow, clock);
        clipDraw(g, font, text, x, y, maxWidth, color, look, scale, offset);
    }

    public static void marqueeCentered(GuiGraphicsExtractor g, Font font, String text, int centerX,
                                       int y, int maxWidth, int color, Look look, float scale,
                                       float clock, boolean enabled) {
        int textW = Math.round(width(font, text, look) * scale);
        int x = leftForWidth(textW, maxWidth, centerX);
        marquee(g, font, text, x, y, maxWidth, color, look, scale, clock, enabled);
    }

    /** Left edge inside {@code maxWidth} for a run that should be centred. */
    public static int leftForWidth(int textW, int maxWidth, int centerX) {
        int boxLeft = centerX - maxWidth / 2;
        if (textW <= maxWidth) return boxLeft + (maxWidth - textW) / 2;
        return boxLeft;
    }

    private static float marqueeOffset(float overflow, float clock) {
        float travel = Math.max(1.0f, overflow / MARQUEE_SPEED);
        float cycle = MARQUEE_PAUSE + travel + MARQUEE_PAUSE;
        float u = clock % cycle;
        if (u < MARQUEE_PAUSE) return 0.0f;
        u -= MARQUEE_PAUSE;
        if (u >= travel) return overflow;
        float t = u / travel;
        float eased = t * t * (3.0f - 2.0f * t);
        return overflow * eased;
    }

    /** Per glyph draw with a manual left/right clip in design space. */
    private static void clipDraw(GuiGraphicsExtractor g, Font font, String text, int x, int y,
                                 int maxWidth, int color, Look look, float scale, float offset) {
        float pen = -offset;
        float left = 0.0f;
        float right = maxWidth;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            int gw = Math.round(glyphWidth(font, cp, look));
            float gx = pen;
            float gwScaled = gw * scale;
            if (gx + gwScaled > left && gx < right) {
                glyph(g, font, glyphText(cp), Math.round(x + gx), y, color, look, scale, 1.0f, 1.0f);
            }
            pen += gwScaled;
            i = next;
        }
    }

    /**
     * The sung line, drawn as one continuous sweep.
     *
     * <p>Everything the HUD needs is here on purpose: word-level timings when the
     * lyric source has them (NetEase {@code yrc}, QQ {@code QRC}, Kugou {@code KRC}),
     * a weighted estimate when it does not, and - when the line is wider than the
     * box - the same sweep scrolling as a marquee, glyph by glyph, still coloured by
     * its own progress instead of flooding the whole line with the sung colour.</p>
     *
     * @param words     syllable timings, or {@code null} for the estimate
     * @param marquee   let a too-wide line scroll inside {@code maxWidth}
     * @param noteEase  weight glyph durations by held length (estimate only)
     * @param peak      swell the syllables near the play head
     * @param peakRange how far that swell reaches, in design pixels
     */
    public static void karaokeLine(GuiGraphicsExtractor g, Font font, String text, int centerX,
                                   int y, int maxWidth, int hotColor, int quietColor, float progress,
                                   Look look, float scale, boolean noteEase, boolean peak,
                                   float peakAmount, int peakRange, float clock, boolean marquee,
                                   List<LyricLine.Word> words, long positionMs) {
        karaokeLine(g, font, text, centerX, y, maxWidth, hotColor, quietColor, progress, look,
                scale, noteEase, peak, peakAmount, peakRange, clock, marquee, words, positionMs,
                -1.0f);
    }

    /**
     * Same, with the scroll position supplied by the caller. A long line has to be
     * revealed from its first glyph (a marquee that runs front to back), which the
     * play-head anchor cannot do on its own: a line that becomes current halfway
     * through - after a seek, a late lyric fetch or a stalled position - would
     * otherwise jump straight to its tail and look truncated at the front.
     *
     * @param scrollOffset how far the box has already scrolled, in scaled pixels;
     *                     negative lets the play head decide
     */
    public static void karaokeLine(GuiGraphicsExtractor g, Font font, String text, int centerX,
                                   int y, int maxWidth, int hotColor, int quietColor, float progress,
                                   Look look, float scale, boolean noteEase, boolean peak,
                                   float peakAmount, int peakRange, float clock, boolean marquee,
                                   List<LyricLine.Word> words, long positionMs, float scrollOffset) {
        Glyphs gl = Glyphs.of(font, text, look);
        if (gl == null) return;

        float[] local = take(localBuf, gl.count);
        float[] weight = take(weightBuf, gl.count);
        localBuf = local;
        weightBuf = weight;
        if (words != null && !words.isEmpty()) {
            fillFromWords(gl, words, positionMs, local, weight);
        } else {
            fillFromProgress(gl, Math.min(1.0f, Math.max(0.0f, progress)), noteEase, local, weight);
        }

        float drawnW = gl.textW * scale;
        boolean scroll = marquee && maxWidth > 0 && drawnW > maxWidth + 0.5f;
        float startX;
        float clipLeft = Float.NEGATIVE_INFINITY;
        float clipRight = Float.POSITIVE_INFINITY;
        if (scroll) {
            float boxLeft = centerX - maxWidth / 2.0f;
            // A sung line scrolls with the play head instead of a free-running
            // clock: the rightmost filled edge is pinned to HEAD_ANCHOR, so the
            // text creeps forward as it is sung and never snaps back mid-line.
            float head = 0.0f;
            for (int k = 0; k < gl.count; k++) {
                float filled = Math.max(0.0f, Math.min(1.0f, local[k]));
                head = Math.max(head, gl.pen0[k] + gl.glyphW[k] * filled);
            }
            float offset = scrollOffset >= 0.0f
                    ? Math.max(0.0f, Math.min(scrollOffset, drawnW - maxWidth))
                    : LyricScroll.followOffset(head * scale, drawnW, maxWidth);
            startX = boxLeft - offset;
            clipLeft = boxLeft;
            clipRight = boxLeft + maxWidth;
        } else if (drawnW <= maxWidth) {
            startX = centerX - drawnW / 2.0f;
        } else {
            // marquee switched off: never centre a row that cannot fit, that would
            // eat the beginning of the line from both sides - anchor it on the left
            // and let the tail run under the clip
            startX = centerX - maxWidth / 2.0f;
            clipLeft = startX;
            clipRight = startX + maxWidth;
        }
        sweep(g, gl, startX, y, hotColor, quietColor, local, weight, noteEase, peak, peakAmount,
                peakRange, look, scale, clipLeft, clipRight);
    }


    /** Estimate mode: every glyph owns a slice of the line duration. */
    private static void fillFromProgress(Glyphs gl, float progress, boolean noteEase, float[] local,
                                         float[] weight) {
        float[] from = take(fromBuf, gl.count);
        float[] to = take(toBuf, gl.count);
        fromBuf = from;
        toBuf = to;
        float total = 0.0f;
        int prevCp = -1;
        for (int k = 0; k < gl.count; k++) {
            weight[k] = noteEase ? noteWeight(gl.cp[k], prevCp) : 1.0f;
            from[k] = total;
            total += weight[k];
            to[k] = total;
            prevCp = gl.cp[k];
        }
        if (total <= 0.0f) {
            java.util.Arrays.fill(local, progress);
            return;
        }
        for (int k = 0; k < gl.count; k++) {
            float start = from[k] / total;
            float span = Math.max(1.0e-4f, (to[k] - from[k]) / total);
            local[k] = Math.max(0.0f, Math.min(1.0f, (progress - start) / span));
        }
    }

    /** Real syllable timings: each glyph reads the progress of the word covering it. */
    private static void fillFromWords(Glyphs gl, List<LyricLine.Word> words, long positionMs,
                                      float[] local, float[] weight) {
        // the median word length of the line tells "long" apart from "this line is
        // simply slow" much better than the mean does
        long[] lengths = lenBuf.length >= words.size() ? lenBuf : new long[words.size()];
        lenBuf = lengths;
        for (int i = 0; i < words.size(); i++) lengths[i] = words.get(i).lengthMs();
        java.util.Arrays.sort(lengths, 0, words.size());
        long median = Math.max(1L, lengths[words.size() / 2]);

        java.util.Arrays.fill(local, 0, gl.count, -1.0f);
        for (int k = 0; k < gl.count; k++) weight[k] = 1.0f;

        // both lists are ordered by position, so one forward cursor per list is
        // enough - the old nested loop multiplied words by glyphs (A24)
        int g = 0;
        for (LyricLine.Word w : words) {
            while (g < gl.count && gl.charStart[g] < w.from()) g++;
            long span = Math.max(1L, w.endMs() - w.startMs());
            float t = Math.max(0.0f, Math.min(1.0f, (positionMs - w.startMs()) / (float) span));
            float held = heldStrength(w.lengthMs(), median);
            for (int k = g; k < gl.count && gl.charStart[k] < w.to(); k++) {
                local[k] = t;
                weight[k] = 1.0f + held * 1.6f;
            }
        }

        // glyphs between two syllables (spaces, punctuation) inherit the previous
        // syllable's fill; everything before the first / after the last is 0 / 1
        float carry = 0.0f;
        int lastTo = words.get(words.size() - 1).to();
        for (int k = 0; k < gl.count; k++) {
            if (local[k] >= 0.0f) {
                carry = local[k];
            } else if (gl.charStart[k] >= lastTo) {
                local[k] = 1.0f;
            } else {
                local[k] = carry;
            }
        }
    }

    /**
     * How much a syllable counts as a held note, 0..1: absolute length against
     * {@link #HELD_FULL_MS}, lifted when the note is well above the line's median.
     * Used by the word-timed path, where the sweep itself is already exact and
     * note easing only shows as the horizontal swell.
     */
    private static float heldStrength(long durationMs, long medianMs) {
        float absolute = (durationMs - HELD_MIN_MS) / (HELD_FULL_MS - HELD_MIN_MS);
        float relative = (durationMs / (float) Math.max(1L, medianMs) - 1.0f) / 1.2f;
        float held = Math.max(absolute, relative * 0.85f);
        return Math.max(0.0f, Math.min(1.0f, held));
    }

    /** Shared painter: {@code local[k]} is how much of glyph {@code k} is sung. */
    private static void sweep(GuiGraphicsExtractor g, Glyphs gl, float startX, int y, int hotColor,
                              int quietColor, float[] local, float[] weight, boolean noteEase,
                              boolean peak, float peakAmount, int peakRange, Look look,
                              float scale, float clipLeft, float clipRight) {
        float headPen = 0.0f;
        for (int k = 0; k < gl.count; k++) headPen += gl.glyphW[k] * local[k];
        float headX = startX + headPen * scale;

        boolean swell = peak && peakAmount > 0.01f && peakRange > 0;
        boolean clipped = clipLeft > Float.NEGATIVE_INFINITY;
        // The batched run path draws whole strings at 1:1, so it may only be used
        // when the row is not scaled; otherwise the glyphs come out at font size
        // while their advances are multiplied by the scale, which reads as the line
        // shrinking and drifting left the moment it becomes the sung row.
        // Mixed runs are safe: a decorated or clipped-out glyph switches to the
        // per-glyph path for that glyph alone, while the batch accumulator keeps the
        // remaining consecutive glyphs of the same colour (C14).
        boolean plain = !swell && !noteEase && !clipped && nearOne(scale);

        StringBuilder run = new StringBuilder();
        float runX = 0.0f;
        int runColor = 0;

        for (int k = 0; k < gl.count; k++) {
            float value = local[k];
            // the handover happens inside the glyph's own slice, so a long note fills slowly
            float t = smooth((value - 0.15f) / 0.7f);
            int color = blend(quietColor, hotColor, t);

            float stretch = 1.0f;
            if (noteEase) {
                float held = Math.max(0.0f, Math.min(1.0f, (weight[k] - 1.0f) / 1.6f));
                if (held > 0.01f) {
                    // bulge while the note is sounding, settle back once it is sung
                    float active = 4.0f * value * (1.0f - value);
                    stretch = 1.0f + STRETCH_MAX * held * active;
                }
            }

            float swellY = 1.0f;
            if (swell) {
                float centre = startX + (gl.pen0[k] + gl.glyphW[k] / 2.0f) * scale;
                float dist = Math.abs(centre - headX);
                float near = 1.0f - Math.min(1.0f, dist / Math.max(1.0f, peakRange));
                swellY = 1.0f + peakAmount * smooth(near) * t;
            }

            float gx = startX + gl.pen0[k] * scale;
            float gw = gl.glyphW[k] * scale;
            if (clipped && (gx + gw <= clipLeft || gx >= clipRight)) continue;

            int x = Math.round(gx);
            boolean decorated = Math.abs(stretch * scale - scale) > 0.01f
                    || Math.abs(swellY - 1.0f) > 0.01f;
            if (plain && !decorated) {
                if (run.length() == 0) {
                    runColor = color;
                    runX = gl.pen0[k];
                } else if (color != runColor) {
                    text(g, gl.font, run.toString(), Math.round(startX + runX * scale), y, runColor, look);
                    run.setLength(0);
                    runColor = color;
                    runX = gl.pen0[k];
                }
                run.append(glyphText(gl.cp[k]));
            } else {
                glyph(g, gl.font, glyphText(gl.cp[k]), x, y, color, look, scale, stretch, swellY);
            }
        }
        if (run.length() > 0) {
            text(g, gl.font, run.toString(), Math.round(startX + runX * scale), y, runColor, look);
        }
    }

    /** One line of text exploded into per-glyph quads in design pixels. */
    private static final class Glyphs {
        Font font;
        int count;
        int[] cp;
        float[] glyphW;
        float[] pen0;
        /** UTF-16 index in the source string where each glyph starts. */
        int[] charStart;
        float textW;

        static Glyphs of(Font font, String text, Look look) {
            if (font == null || text == null || text.isEmpty()) return null;
            int count = text.codePointCount(0, text.length());
            if (count <= 0) return null;
            Glyphs gl = new Glyphs();
            gl.font = font;
            gl.count = count;
            gl.cp = new int[count];
            gl.glyphW = new float[count];
            gl.pen0 = new float[count];
            gl.charStart = new int[count];
            float pen = 0.0f;
            int k = 0;
            for (int i = 0; i < text.length() && k < count; ) {
                int cp = text.codePointAt(i);
                int next = i + Character.charCount(cp);
                float w = Math.max(0.5f, glyphWidth(font, cp, look));
                gl.cp[k] = cp;
                gl.glyphW[k] = w;
                gl.pen0[k] = pen;
                gl.charStart[k] = i;
                pen += w;
                k++;
                i = next;
            }
            gl.textW = pen;
            return gl;
        }
    }

    /**
     * Rough share of a line's duration that one glyph owns, used only when the
     * lyric source has no syllable-level timings. Lengthening marks and repeated
     * letters stand for a held note; CJK sits in the middle; latin text, digits,
     * spaces and punctuation go by faster.
     */
    private static float noteWeight(int cp, int prevCp) {
        switch (cp) {
            case 'ー', '〜', '～', '~', '—', '－', '…', '-', '–':
                return 3.2f;
            case ' ':
                return 0.35f;
            case '，', '。', '、', '！', '？', '；', '：', ',', '.', '!', '?', ';', ':':
                return 0.5f;
            default:
                break;
        }
        if (prevCp == cp && cp > ' ') return 2.4f;
        if (cp < 0x2E80) return Character.isDigit(cp) ? 0.8f : 0.7f;
        return 1.0f;
    }

    /** One glyph, optionally stretched horizontally and swelled around its centre. */
    private static void glyph(GuiGraphicsExtractor g, Font font, String glyph, int x, int y,
                              int color, Look look, float scale, float stretchX, float stretchY) {
        float sx = scale * stretchX;
        float sy = scale * stretchY;
        if (Math.abs(sx - 1.0f) < 0.02f && Math.abs(sy - 1.0f) < 0.02f) {
            text(g, font, glyph, x, y, color, look);
            return;
        }
        float gw = glyphWidth(font, glyph.codePointAt(0), look);
        g.pose().pushMatrix();
        g.pose().translate(x + (gw * sx) / 2.0f, y);
        g.pose().scale(sx, sy);
        text(g, font, glyph, Math.round(-gw / 2.0f), 0, color, look);
        g.pose().popMatrix();
    }

    private static boolean nearOne(float scale) {
        return Math.abs(scale - 1.0f) < 0.01f;
    }

    public static float smooth(float t) {
        float c = Math.max(0.0f, Math.min(1.0f, t));
        return c * c * (3.0f - 2.0f * c);
    }

    /** Per-channel ARGB interpolation, so alpha fades along with the colour. */
    public static int blend(int from, int to, float t) {
        float c = Math.max(0.0f, Math.min(1.0f, t));
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * c);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * c);
        int gg = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * c);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * c);
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    /** Largest prefix of {@code text} that fits into {@code maxWidth}. */
    public static int prefixLength(Font font, String text, int maxWidth, Look look) {
        int used = 0;
        int end = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            int w = Math.round(glyphWidth(font, cp, look));
            if (used + w > maxWidth) break;
            used += w;
            i = next;
            end = i;
        }
        return end;
    }

    /** Truncate to {@code maxWidth} with an ellipsis, honouring a font override. */
    public static String fit(Font font, String text, int maxWidth, Look look) {
        if (text == null) return "";
        if (maxWidth <= 0) return "";
        if (width(font, text, look) <= maxWidth) return text;
        String ellipsis = "\u2026";
        int budget = maxWidth - width(font, ellipsis, look);
        if (budget <= 0) return ellipsis;
        return text.substring(0, prefixLength(font, text, budget, look)) + ellipsis;
    }
}
