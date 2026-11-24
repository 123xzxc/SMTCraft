package org.yinyun.smtcraft.render;

import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.smtc.LyricLine;

import net.minecraft.client.gui.FontRenderer;

import java.util.List;

/**
 * 文字绘制：粗斜体、局部缩放、带峰值起伏的卡拉 OK 扫色，以及放不下时的跑马灯。
 *
 * <p>1.7.10 的 {@code FontRenderer} 没有 per-run 字体描述，也没有 {@code Component}：
 * 样式只能靠 {@code \u00a7} 格式码，宽度靠 {@code getStringWidth}。所以这里把
 * "量一个字形有多宽"和"画一个字形"绑在 {@link Text} 上——卡拉 OK 是逐字形推进的，
 * 两边各算一次宽度会让整行随进度越飘越远。</p>
 */
public final class LyricsRenderer {
    public static final int LINE_H = 11;

    /** 一个被拉长的音在唱到一半时最多横向撑开多少。 */
    private static final float STRETCH_MAX = 0.42f;
    /** 超过这个时长才算"长音"。 */
    private static final float HELD_FULL_MS = 720.0f;
    /** 比这个短的音永远不撑开。 */
    private static final float HELD_MIN_MS = 210.0f;

    /** 跑马灯：两端各停一会儿，中间滑过去。 */
    private static final float MARQUEE_SPEED = 26.0f;
    private static final float MARQUEE_PAUSE = 1.3f;

    private LyricsRenderer() {}

    // ----------------------------------------------------------- 缓存
    //
    // 一帧里同一行的每个字形都要问一次宽度，而卡拉 OK 还在逐字重画。宽度和
    // 单字字符串都记住（A23）；键里带字体对象，资源重载换掉字体就自然失效。
    private static final int WIDTH_CACHE_MAX = 16384;
    private static final int GLYPH_CACHE_MAX = 4096;
    private static final java.util.Map<WidthKey, Float> WIDTHS =
            new java.util.concurrent.ConcurrentHashMap<WidthKey, Float>();
    private static final java.util.Map<Integer, String> GLYPH_TEXT =
            new java.util.concurrent.ConcurrentHashMap<Integer, String>();

    private static final class WidthKey {
        private final int font;
        private final boolean bold;
        private final boolean italic;
        private final int cp;

        WidthKey(FontRenderer f, boolean bold, boolean italic, int cp) {
            this.font = System.identityHashCode(f);
            this.bold = bold;
            this.italic = italic;
            this.cp = cp;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof WidthKey)) return false;
            WidthKey k = (WidthKey) o;
            return font == k.font && bold == k.bold && italic == k.italic && cp == k.cp;
        }

        @Override public int hashCode() {
            int h = font;
            h = 31 * h + (bold ? 1 : 0);
            h = 31 * h + (italic ? 1 : 0);
            return 31 * h + cp;
        }
    }

    /** 一个码点在 {@code look} 下的宽度，带缓存。 */
    private static float glyphWidth(FontRenderer font, int cp, Look look) {
        boolean bold = look != null && look.bold;
        boolean italic = look != null && look.italic;
        WidthKey key = new WidthKey(font, bold, italic, cp);
        Float hit = WIDTHS.get(key);
        if (hit != null) return hit.floatValue();
        float w = width(font, glyphText(cp), look);
        if (WIDTHS.size() >= WIDTH_CACHE_MAX) WIDTHS.clear();
        WIDTHS.put(key, Float.valueOf(w));
        return w;
    }

    /** 某个码点对应的单字字符串，缓存起来，热循环里不再反复切片。 */
    private static String glyphText(int cp) {
        String hit = GLYPH_TEXT.get(Integer.valueOf(cp));
        if (hit != null) return hit;
        String s = new String(Character.toChars(cp));
        if (GLYPH_TEXT.size() >= GLYPH_CACHE_MAX) GLYPH_TEXT.clear();
        GLYPH_TEXT.put(Integer.valueOf(cp), s);
        return s;
    }

    /** 清空所有度量缓存（资源重载、换字体时调用）。 */
    public static void invalidateCaches() {
        WIDTHS.clear();
        GLYPH_TEXT.clear();
    }

    // ----------------------------------------------------------- 每帧暂存
    //
    // 一行歌词每帧要量一次、扫一次；每行分配四个 float 数组在 profiler 里就是垃圾
    // （A24）。这些缓冲按需增长后复用——只有渲染线程会碰它们。
    private static float[] localBuf = new float[0];
    private static float[] weightBuf = new float[0];
    private static float[] fromBuf = new float[0];
    /** 逐字形信息同样跨行复用：每帧每行 new 四个数组只是年轻代噪音，但面板多起来就是几十次。 */
    private static Glyphs glyphScratch;
    private static float[] toBuf = new float[0];
    private static long[] lenBuf = new long[0];

    private static float[] take(float[] buf, int n) {
        return buf.length >= n ? buf : new float[n];
    }

    /** 一段文字的画法：粗体、斜体、阴影。 */
    public static final class Look {
        public final boolean bold;
        public final boolean italic;
        public final boolean shadow;

        public Look(boolean bold, boolean italic, boolean shadow) {
            this.bold = bold;
            this.italic = italic;
            this.shadow = shadow;
        }

        public static Look plain(boolean shadow) {
            return new Look(false, false, shadow);
        }

        public static Look of(SmtcConfig cfg) {
            if (cfg == null) return plain(true);
            return new Look(cfg.lyricBold, cfg.lyricItalic, cfg.shadow);
        }

        public Look withoutStyle() {
            return new Look(false, false, shadow);
        }
    }

    public static int width(FontRenderer font, String text, Look look) {
        if (font == null || text == null || text.isEmpty()) return 0;
        boolean bold = look != null && look.bold;
        return Text.width(font, text, bold);
    }

    public static void text(Canvas c, FontRenderer font, String text, int x, int y, int color,
                            Look look) {
        if (text == null || text.isEmpty()) return;
        boolean bold = look != null && look.bold;
        boolean italic = look != null && look.italic;
        boolean shadow = look == null || look.shadow;
        Text.draw(font, text, x, y, color, bold, italic, shadow);
    }

    public static void draw(Canvas c, FontRenderer font, String text, int x, int y, int color,
                            Look look, float scale) {
        if (text == null || text.isEmpty()) return;
        if (nearOne(scale)) {
            text(c, font, text, x, y, color, look);
            return;
        }
        c.push();
        c.translate(x, y);
        c.scale(scale);
        Text.draw(font, text, 0, 0, color, look != null && look.bold,
                look != null && look.italic, look == null || look.shadow);
        c.pop();
    }

    public static void drawCentered(Canvas c, FontRenderer font, String text, int centerX, int y,
                                    int color, Look look, float scale) {
        float scaled = width(font, text, look) * (nearOne(scale) ? 1.0f : scale);
        draw(c, font, text, Math.round(centerX - scaled / 2.0f), y, color, look, scale);
    }

    /**
     * 放不下就在 {@code maxWidth} 里滚动：停、滑到尾、停、跳回。框外的字形直接
     * 跳过不画，所以不需要裁剪，面板锚在哪都行。
     */
    public static void marquee(Canvas c, FontRenderer font, String text, int x, int y, int maxWidth,
                               int color, Look look, float scale, float clock, boolean enabled) {
        if (text == null || text.isEmpty() || maxWidth <= 0) return;
        int textW = width(font, text, look);
        float scaledW = textW * scale;
        if (!enabled || scaledW <= maxWidth + 0.5f) {
            draw(c, font, text, x, y, color, look, scale);
            return;
        }
        float overflow = scaledW - maxWidth;
        float offset = marqueeOffset(overflow, clock);
        clipDraw(c, font, text, x, y, maxWidth, color, look, scale, offset);
    }

    public static void marqueeCentered(Canvas c, FontRenderer font, String text, int centerX, int y,
                                       int maxWidth, int color, Look look, float scale, float clock,
                                       boolean enabled) {
        int textW = Math.round(width(font, text, look) * scale);
        int x = leftForWidth(textW, maxWidth, centerX);
        marquee(c, font, text, x, y, maxWidth, color, look, scale, clock, enabled);
    }

    /** 要居中的一段文字在 {@code maxWidth} 里的左边界。 */
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

    /** 逐字形绘制，并在设计空间里手工做左右裁剪。 */
    private static void clipDraw(Canvas c, FontRenderer font, String text, int x, int y, int maxWidth,
                                 int color, Look look, float scale, float offset) {
        float pen = -offset;
        float right = maxWidth;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            int gw = Math.round(glyphWidth(font, cp, look));
            float gx = pen;
            float gwScaled = gw * scale;
            if (gx + gwScaled > 0.0f && gx < right) {
                glyph(c, font, glyphText(cp), Math.round(x + gx), y, color, look, scale, 1.0f, 1.0f);
            }
            pen += gwScaled;
            i = next;
        }
    }

    /**
     * 正在唱的那一行，一次性扫色。
     *
     * <p>歌词源带逐字时间就按字走（网易 {@code yrc}、QQ {@code QRC}、酷狗 {@code KRC}），
     * 没有就按加权比例估；行比框宽时，同一个扫色边滚边画，仍然是每个字形按它自己的
     * 进度上色，而不是整行一起染成"已唱"色。</p>
     *
     * @param words     逐字时间，{@code null} 表示用估算
     * @param marquee   太宽的行是否在 {@code maxWidth} 里滚动
     * @param noteEase  按音长给字形加权（只影响估算）
     * @param peak      让播放头附近的字微微鼓起
     * @param peakRange 鼓起的影响半径，设计像素
     */
    public static void karaokeLine(Canvas c, FontRenderer font, String text, int centerX, int y,
                                   int maxWidth, int hotColor, int quietColor, float progress,
                                   Look look, float scale, boolean noteEase, boolean peak,
                                   float peakAmount, int peakRange, float clock, boolean marquee,
                                   List<LyricLine.Word> words, long positionMs) {
        karaokeLine(c, font, text, centerX, y, maxWidth, hotColor, quietColor, progress, look, scale,
                noteEase, peak, peakAmount, peakRange, clock, marquee, words, positionMs, -1.0f);
    }

    /**
     * 同上，滚动位置由调用方给。长句必须从第一个字形开始露出来（跑马灯式），
     * 单靠播放头锚定做不到：一句在唱到一半时才变成当前行（跳转、歌词迟到、位置
     * 卡住）会直接跳到尾巴，看起来像前面被切掉了。
     *
     * @param scrollOffset 框已经滚过的缩放像素；负数表示让播放头自己决定
     */
    public static void karaokeLine(Canvas c, FontRenderer font, String text, int centerX, int y,
                                   int maxWidth, int hotColor, int quietColor, float progress,
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
            // 正在唱的行跟着播放头滚，而不是自由跑：已唱的最右端被钉在
            // HEAD_ANCHOR 上，于是文字随着演唱一点点往前爬，中途绝不会弹回。
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
            // 关掉跑马灯时，放不下的行绝不能居中：那会从两头吃掉开头。靠左放，
            // 尾巴跑到裁剪框外面去。
            startX = centerX - maxWidth / 2.0f;
            clipLeft = startX;
            clipRight = startX + maxWidth;
        }
        sweep(c, gl, startX, y, hotColor, quietColor, local, weight, noteEase, peak, peakAmount,
                peakRange, look, scale, clipLeft, clipRight);
    }

    /** 估算模式：每个字形分到这一行时长里的一小段。 */
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
            java.util.Arrays.fill(local, 0, gl.count, progress);
            return;
        }
        for (int k = 0; k < gl.count; k++) {
            float start = from[k] / total;
            float span = Math.max(1.0e-4f, (to[k] - from[k]) / total);
            local[k] = Math.max(0.0f, Math.min(1.0f, (progress - start) / span));
        }
    }

    /** 真实逐字时间：每个字形读它所在那个字的进度。 */
    private static void fillFromWords(Glyphs gl, List<LyricLine.Word> words, long positionMs,
                                      float[] local, float[] weight) {
        // 用中位数而不是平均值来判断"长音"：一行里有一个拖长的尾音，平均值就被带偏
        long[] lengths = lenBuf.length >= words.size() ? lenBuf : new long[words.size()];
        lenBuf = lengths;
        for (int i = 0; i < words.size(); i++) lengths[i] = words.get(i).lengthMs();
        java.util.Arrays.sort(lengths, 0, words.size());
        long median = Math.max(1L, lengths[words.size() / 2]);

        java.util.Arrays.fill(local, 0, gl.count, -1.0f);
        for (int k = 0; k < gl.count; k++) weight[k] = 1.0f;

        // 两个表都按位置有序，各用一个前向游标就够——旧的嵌套循环把字数乘上字形数（A24）
        int g = 0;
        for (int i = 0; i < words.size(); i++) {
            LyricLine.Word w = words.get(i);
            while (g < gl.count && gl.charStart[g] < w.from()) g++;
            long span = Math.max(1L, w.endMs() - w.startMs());
            float t = Math.max(0.0f, Math.min(1.0f, (positionMs - w.startMs()) / (float) span));
            float held = heldStrength(w.lengthMs(), median);
            for (int k = g; k < gl.count && gl.charStart[k] < w.to(); k++) {
                local[k] = t;
                weight[k] = 1.0f + held * 1.6f;
            }
        }

        // 两个字之间的空格、标点继承前一个字的进度；首个字之前是 0，最后一个字之后是 1
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
     * 一个字有多像"被拖长的音"，0..1：绝对时长对 {@link #HELD_FULL_MS}，再按是否
     * 明显长于本行中位数抬高。逐字有时间的路径里扫色本来就准，音长只体现为横向撑开。
     */
    private static float heldStrength(long durationMs, long medianMs) {
        float absolute = (durationMs - HELD_MIN_MS) / (HELD_FULL_MS - HELD_MIN_MS);
        float relative = (durationMs / (float) Math.max(1L, medianMs) - 1.0f) / 1.2f;
        float held = Math.max(absolute, relative * 0.85f);
        return Math.max(0.0f, Math.min(1.0f, held));
    }

    /** 共用画笔：{@code local[k]} 是第 k 个字形已经唱了多少。 */
    private static void sweep(Canvas c, Glyphs gl, float startX, int y, int hotColor, int quietColor,
                              float[] local, float[] weight, boolean noteEase, boolean peak,
                              float peakAmount, int peakRange, Look look, float scale,
                              float clipLeft, float clipRight) {
        float headPen = 0.0f;
        for (int k = 0; k < gl.count; k++) headPen += gl.glyphW[k] * local[k];
        float headX = startX + headPen * scale;

        boolean swell = peak && peakAmount > 0.01f && peakRange > 0;
        boolean clipped = clipLeft > Float.NEGATIVE_INFINITY;
        // 整串批量绘制只在 1:1 时可用：否则字形按字号画出来、步进却乘了缩放，
        // 一行刚变成当前行就会看起来缩小并往左飘。
        // 混合是安全的：装饰过或出框的字形单独走逐字路径，批量累加器继续吃后面
        // 同色的连续字形（C14）。
        boolean plain = !swell && !noteEase && !clipped && nearOne(scale);

        StringBuilder run = new StringBuilder();
        float runX = 0.0f;
        int runColor = 0;

        for (int k = 0; k < gl.count; k++) {
            float value = local[k];
            // 换色发生在字形自己的那一段里，长音才会慢慢被填满
            float t = smooth((value - 0.15f) / 0.7f);
            int color = blend(quietColor, hotColor, t);

            float stretch = 1.0f;
            if (noteEase) {
                float held = Math.max(0.0f, Math.min(1.0f, (weight[k] - 1.0f) / 1.6f));
                if (held > 0.01f) {
                    // 音在响的时候鼓起来，唱完再落回去
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
                    text(c, gl.font, run.toString(), Math.round(startX + runX * scale), y, runColor,
                            look);
                    run.setLength(0);
                    runColor = color;
                    runX = gl.pen0[k];
                }
                run.append(glyphText(gl.cp[k]));
            } else {
                glyph(c, gl.font, glyphText(gl.cp[k]), x, y, color, look, scale, stretch, swellY);
            }
        }
        if (run.length() > 0) {
            text(c, gl.font, run.toString(), Math.round(startX + runX * scale), y, runColor, look);
        }
    }

    /** 一行文字拆成设计像素下的逐字形信息。 */
    private static final class Glyphs {
        FontRenderer font;
        int count;
        int[] cp;
        float[] glyphW;
        float[] pen0;
        /** 每个字形在源字符串里的 UTF-16 起始下标。 */
        int[] charStart;
        float textW;

        static Glyphs of(FontRenderer font, String text, Look look) {
            if (font == null || text == null || text.isEmpty()) return null;
            int count = text.codePointCount(0, text.length());
            if (count <= 0) return null;
            Glyphs gl = glyphScratch;
            if (gl == null || gl.cp.length < count) {
                gl = new Glyphs();
                gl.cp = new int[count];
                gl.glyphW = new float[count];
                gl.pen0 = new float[count];
                gl.charStart = new int[count];
                glyphScratch = gl;
            }
            gl.font = font;
            gl.count = count;
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
     * 一行时长里一个字形大致该占多大份额，只在歌词源没有逐字时间时使用。长音符、
     * 重复字母代表拖音；中文居中；拉丁字母、数字、空格、标点走得快一些。
     */
    private static float noteWeight(int cp, int prevCp) {
        switch (cp) {
            case '\u30fc': // ー
            case '\u301c': // 〜
            case '\uff5e': // ～
            case '~':
            case '\u2014': // —
            case '\uff0d': // －
            case '\u2026': // …
            case '-':
            case '\u2013': // –
                return 3.2f;
            case ' ':
                return 0.35f;
            case '\uff0c': case '\u3002': case '\u3001': case '\uff01': case '\uff1f':
            case '\uff1b': case '\uff1a':
            case ',': case '.': case '!': case '?': case ';': case ':':
                return 0.5f;
            default:
                break;
        }
        if (prevCp == cp && cp > ' ') return 2.4f;
        if (cp < 0x2E80) return Character.isDigit(cp) ? 0.8f : 0.7f;
        return 1.0f;
    }

    /** 单个字形，可横向拉伸、绕中心纵向鼓起。 */
    private static void glyph(Canvas c, FontRenderer font, String glyph, int x, int y, int color,
                              Look look, float scale, float stretchX, float stretchY) {
        float sx = scale * stretchX;
        float sy = scale * stretchY;
        if (Math.abs(sx - 1.0f) < 0.02f && Math.abs(sy - 1.0f) < 0.02f) {
            text(c, font, glyph, x, y, color, look);
            return;
        }
        float gw = glyphWidth(font, glyph.codePointAt(0), look);
        c.push();
        c.translate(x + (gw * sx) / 2.0f, y);
        c.scale(sx, sy);
        text(c, font, glyph, Math.round(-gw / 2.0f), 0, color, look);
        c.pop();
    }

    private static boolean nearOne(float scale) {
        return Math.abs(scale - 1.0f) < 0.01f;
    }

    public static float smooth(float t) {
        float v = Math.max(0.0f, Math.min(1.0f, t));
        return v * v * (3.0f - 2.0f * v);
    }

    /** 逐通道 ARGB 插值，alpha 跟着颜色一起淡。 */
    public static int blend(int from, int to, float t) {
        float k = Math.max(0.0f, Math.min(1.0f, t));
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * k);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** {@code text} 里能塞进 {@code maxWidth} 的最长前缀长度。 */
    public static int prefixLength(FontRenderer font, String text, int maxWidth, Look look) {
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

    /** 截断到 {@code maxWidth} 并补省略号。 */
    public static String fit(FontRenderer font, String text, int maxWidth, Look look) {
        if (text == null) return "";
        if (maxWidth <= 0) return "";
        if (width(font, text, look) <= maxWidth) return text;
        String ellipsis = "\u2026";
        int budget = maxWidth - width(font, ellipsis, look);
        if (budget <= 0) return ellipsis;
        return text.substring(0, prefixLength(font, text, budget, look)) + ellipsis;
    }
}
