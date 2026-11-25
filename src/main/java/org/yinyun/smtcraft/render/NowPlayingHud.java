package org.yinyun.smtcraft.render;

import net.minecraft.client.Minecraft;
import net.minecraft.util.StatCollector;
import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.SmtcCraftClient;
import org.yinyun.smtcraft.media.MediaBridge;
import org.yinyun.smtcraft.smtc.LyricLine;
import org.yinyun.smtcraft.smtc.LyricService;
import org.yinyun.smtcraft.smtc.LyricTrack;
import org.yinyun.smtcraft.smtc.TrackInfo;
import org.yinyun.smtcraft.util.GameScale;

import java.util.List;
import java.util.logging.Logger;

/**
 * 正在播放面板。1.8.9 版由 {@link SmtcCraftClient} 在 RenderGameOverlayEvent.Post(TEXT)
 * 里调用 {@link #render(Canvas)}，绘制常量与布局数值与 Fabric 原件一致（AUDIT-36-1）。
 */
public final class NowPlayingHud {
    private static final Logger LOG = Logger.getLogger("SMTCraft");
    private static final int DESIGN_W = 208;
    private static final int MAX_W = 470;
    private static final int BASE_H = 58;
    private static final int DOT_W = 3;
    private static final int DOT_GAP = 3;
    private static final int LYRIC_GAP = 10;
    private static final int LYRIC_BOTTOM_PAD = 10;
    private static final int EDGE_FADE = 9;
    private static final int PAD = 5;
    private static final float MAX_SHARE_W = 0.55f;
    private static final float MAX_SHARE_H = 0.72f;
    private static final float MIN_FIT = 0.45f;
    private static final float HARD_MIN_FIT = 0.25f;
    private static final int ART = 44;
    private static final float NOTICE_SECONDS = 2.2f;
    private static final float SCROLL_CATCHUP = 360.0f;

    private final SmtcCraftClient mod;
    private final ArtworkCache artwork = new ArtworkCache();
    private float spin;
    private String spinKey = "";
    private float clock;
    private String noticeText = "";
    private long noticeAt;
    private final LyricAnimator animator = new LyricAnimator();
    private final LyricClock lyricClock = new LyricClock();
    private String animatorKey = "";
    private int scrollRow = -1;
    private float scrollX;
    private float scrollOut;
    private int handoverRow = -1;
    private float handoverOffset;
    private long lastNanos;
    /** 上次的画布像素比；只在变化时说话，免得每帧拼一个字符串来比较。 */
    private int lastPixels = -1;
    /** 上次的面板缩放（设计像素 -> 屏幕像素）。 */
    private float lastPanelScale = Float.NaN;
    /** 上次的过滤模式：-1 未知、0 最近邻、1 线性。模式翻转才值得一条 INFO。 */
    private int lastSmooth = -1;

    public NowPlayingHud(SmtcCraftClient mod) {
        this.mod = mod;
    }

    /** 面板右上角的小提示，例如"歌词源没有结果"。 */
    public void notice(String text) {
        noticeText = text == null ? "" : text;
        noticeAt = System.currentTimeMillis();
    }

    /** 每帧入口：{@code c} 由调用方建好，坐标原点在屏幕左上。 */
    public void render(Canvas c) {
        SmtcCraftClient client = mod;
        if (client == null) return;
        SmtcConfig cfg = client.config();
        Minecraft mc = Minecraft.getMinecraft();
        if (cfg == null || !cfg.enabled || mc == null) return;
        if (mc.gameSettings != null && mc.gameSettings.showDebugInfo) return;
        float step = wallStep();
        clock += step;
        if (clock > 100000.0f) clock -= 100000.0f;

        MediaBridge bridge = client.bridge();
        TrackInfo track = bridge == null ? TrackInfo.EMPTY : bridge.latest();
        byte[] artBytes = bridge == null ? null : bridge.artwork();
        String artHash = bridge == null ? "" : bridge.artworkHash();
        boolean hasTrack = track != null && track.hasTrack();
        advanceSpin(cfg, track, step);
        artwork.tick();
        if (cfg.hideWhenScreenOpen && mc.currentScreen != null) {
            animator.finish();
            return;
        }
        if (!hasTrack && cfg.hideWhenNoSession && !cfg.debug) return;
        if (hasTrack && !track.playing() && cfg.hideWhenPaused) return;

        LyricService service = client.lyrics();
        LyricTrack lyrics = (cfg.showLyrics && service != null && hasTrack && service.matches(track))
                ? service.track() : LyricTrack.EMPTY;
        float scale = Math.max(0.1f, cfg.scale) * GameScale.factor(cfg.followGuiScale);
        int width = resolveWidth(cfg, track, hasTrack, c.font());
        LyricLayout layout = LyricLayout.of(cfg);
        boolean lyricsOnly = cfg.lyricsOnly && hasTrack;
        int headerH = lyricsOnly ? 0 : BASE_H;
        int bandRows = layout.rows == 0 ? 1 : layout.rows;
        int lyricH = (layout.rows == 0 && !lyricsOnly) ? 0
                : LYRIC_GAP + bandRows * layout.blockH + LYRIC_BOTTOM_PAD;
        int height = lyricsOnly ? lyricH : BASE_H + (hasTrack ? lyricH : 0);
        int guiW = c.width();
        int guiH = c.height();
        int pixels = Math.max(1, c.pixelScale());
        float panelScale = scale * autoFit(cfg, guiW, guiH, width, height);
        // 字形是 8 px 位图图集，设备缩放非整数时最近邻会跳纹素行（标题缺顶行、竖笔画成双线）。
        // 修法不是把缩放量化——那样滑杆就变成逐档跳——而是这一段绘制临时改线性过滤（见 FontSmoothing）；
        // 设备缩放本来就贴着整数时保留最近邻，1:1 采样最锐。
        float device = panelScale * pixels;
        boolean smooth = Math.abs(device - Math.round(device)) > 0.02f;
        if (pixels != lastPixels || panelScale != lastPanelScale) {
            lastPixels = pixels;
            lastPanelScale = panelScale;
            String line = "hud scale: gui " + guiW + "x" + guiH + " px " + pixels
                    + " design " + width + "x" + height + " panel " + panelScale
                    + " device " + device + (smooth ? " linear" : " nearest")
                    + " lyric " + layout.lyricScale + " trans " + layout.transScale;
            // 拖设置页滑杆时连续几十帧都在变：只有过滤模式翻转（以及第一帧）才值得 INFO
            int mode = smooth ? 1 : 0;
            if (mode != lastSmooth) {
                lastSmooth = mode;
                LOG.info(line);
            } else {
                LOG.fine(line);
            }
        }
        c.push();
        FontSmoothing.begin(smooth);
        c.translate(anchorX(cfg, guiW, Math.round(width * panelScale)),
                anchorY(cfg, guiH, Math.round(height * panelScale)));
        c.scale(panelScale);
        try {
            drawChrome(cfg, c, width, height);
            if (!lyricsOnly) {
                renderPanel(cfg, c, track, hasTrack, artBytes, artHash, width);
            }
            if (layout.rows > 0 || lyricsOnly) {
                c.scissor(0, headerH - 1, width, height);
                renderLyrics(cfg, c, lyrics, track, service, layout, headerH, width, lyricsOnly, step);
                c.scissorOff();
            }
            drawNotice(c, width, height);
        } finally {
            FontSmoothing.end();
            c.pop();
        }
    }

    /** 关停时释放贴图；面板不再画之后调用。 */
    public void close() {
        artwork.clear();
    }

    // --- 面板 ---------------------------------------------------------------

    /**
     * 一帧的时间步长。原件用 {@code getRealtimeDeltaTicks()}，1.8.9 没有这个数，
     * 改用本机单调时钟，并钳到 (0,1] 免得卡顿后动画一次跳完。
     */
    private float wallStep() {
        long now = System.nanoTime();
        if (lastNanos == 0L) {
            lastNanos = now;
            return 0.05f;
        }
        long delta = now - lastNanos;
        lastNanos = now;
        float step = delta / 1.0e9f;
        if (step <= 0.0f) return 0.05f;
        return Math.min(1.0f, step);
    }

    private void advanceSpin(SmtcConfig cfg, TrackInfo track, float step) {
        if (!cfg.vinyl) {
            spin = 0.0f;
            return;
        }
        String key = track == null ? "" : track.dedupeKey();
        if (!key.equals(spinKey)) {
            spinKey = key;
            spin = 0.0f;
        }
        if (track == null || !track.playing()) return;
        float speed = Math.min(720.0f, Math.max(0.0f, cfg.vinylSpinSpeed));
        spin = (spin + speed * step) % 360.0f;
    }

    /** 小窗口下按比例缩小，让面板占屏比接近全屏时的样子。 */
    private static float autoFit(SmtcConfig cfg, int guiW, int guiH, int width, int height) {
        if (!cfg.autoScale || width <= 0 || height <= 0) return 1.0f;
        float fitW = guiW * MAX_SHARE_W / width;
        float fitH = guiH * MAX_SHARE_H / height;
        float fit = Math.min(1.0f, Math.min(fitW, fitH));
        if (fit >= MIN_FIT) return fit;
        return Math.max(HARD_MIN_FIT, fit);
    }


    /** 面板宽度：自适应时按标题/歌手/专辑/应用名撑开，上限 {@link #MAX_W}。 */
    private static int resolveWidth(SmtcConfig cfg, TrackInfo track, boolean hasTrack,
                                    net.minecraft.client.gui.FontRenderer font) {
        int base = Math.max(DESIGN_W, cfg.panelWidth);
        if (!cfg.adaptiveWidth || !hasTrack || track == null || cfg.lyricsOnly) return base;
        int artW = cfg.showArtwork ? ART + 7 : 0;
        String app = track.app() == null ? "" : Draw.appName(track.app());
        int appW = cfg.showSourceApp && !app.isEmpty() ? Text.width(font, app, false) + 8 : 0;
        int titleNeed = Text.width(font, track.title() == null ? "" : track.title(), false) + 6 + appW;
        int bodyNeed = Text.width(font, track.artist() == null ? "" : track.artist(), false);
        if (cfg.showAlbum && track.album() != null && !track.album().isEmpty()) {
            bodyNeed = Math.max(bodyNeed, Text.width(font, track.album(), false));
        }
        int need = Math.max(titleNeed, bodyNeed) + PAD * 2 + artW;
        return Math.max(base, Math.min(MAX_W, need));
    }

    private static int anchorX(SmtcConfig cfg, int guiW, int panelW) {
        int off = Math.max(0, cfg.offsetX);
        String anchor = cfg.anchor == null ? "top-left" : cfg.anchor;
        if (anchor.endsWith("right")) return guiW - panelW - off;
        if ("center".equals(anchor)) return (guiW - panelW) / 2;
        return off;
    }

    private static int anchorY(SmtcConfig cfg, int guiH, int panelH) {
        int off = Math.max(0, cfg.offsetY);
        String anchor = cfg.anchor == null ? "top-left" : cfg.anchor;
        if (anchor.startsWith("bottom")) return guiH - panelH - off;
        if ("center".equals(anchor)) return (guiH - panelH) / 2;
        return off;
    }

    private static void drawChrome(SmtcConfig cfg, Canvas c, int width, int height) {
        int bg = Draw.withAlpha(0x000000, cfg.backgroundOpacity);
        int border = Draw.withAlpha(0xFFFFFF, cfg.borderOpacity);
        int radius = Math.max(SmtcConfig.MIN_CORNER_RADIUS,
                Math.min(SmtcConfig.MAX_CORNER_RADIUS, cfg.cornerRadius));
        if (radius > 0) {
            Draw.fillRound(c, 0, 0, width, height, radius, bg);
            Draw.outlineRound(c, 0, 0, width, height, radius, border);
        } else {
            c.fill(0, 0, width, height, bg);
            c.fill(0, 0, width, 1, border);
            c.fill(0, height - 1, width, height, border);
        }
    }

    private void drawNotice(Canvas c, int width, int height) {
        if (noticeText == null || noticeText.isEmpty() || noticeAt <= 0L) return;
        float age = (System.currentTimeMillis() - noticeAt) / 1000.0f;
        if (age < 0.0f || age >= NOTICE_SECONDS) return;
        float alpha = Math.min(1.0f, Math.min(age / 0.12f, (NOTICE_SECONDS - age) / 0.5f));
        int color = Draw.withAlpha(0xFFFFFF, alpha);
        Text.drawCentered(c.font(), noticeText, width / 2, height + 4, color, false, false, true);
    }

    // --- 头部 ---------------------------------------------------------------

    private void renderPanel(SmtcConfig cfg, Canvas c, TrackInfo track, boolean hasTrack,
                             byte[] artBytes, String artHash, int width) {
        net.minecraft.client.gui.FontRenderer font = c.font();
        LyricsRenderer.Look look = LyricsRenderer.Look.plain(cfg.shadow);
        int textColor = Draw.withAlpha(Draw.color(cfg.textColor, 0xFFFFFF), 1.0f);
        int dimColor = Draw.withAlpha(Draw.color(cfg.dimTextColor, 0xB8BCC4), 1.0f);
        int accent = Draw.withAlpha(Draw.color(cfg.accentColor, 0x3FDC6E), 1.0f);
        int textX = PAD;
        int textW = width - 2 * PAD;
        if (!hasTrack) {
            String empty = StatCollector.translateToLocal("smtcraft.hud.empty");
            Text.draw(font, Draw.fit(font, empty, textW), textX, 14, Draw.withAlpha(dimColor, 0.7f),
                    false, false, cfg.shadow);
            return;
        }
        if (cfg.showArtwork) {
            int ax = PAD;
            int ay = PAD;
            int tex = cfg.vinyl ? artwork.disc(artHash, artBytes)
                    : artwork.texture(artHash, artBytes, cfg.artRound ? cfg.artRadius : 0, ART);
            if (tex > 0) {
                if (cfg.vinyl) {
                    // 唱片在旋转，裁剪换算失效，所以只在这块贴图上用旋转
                    c.push();
                    c.translate(ax + ART / 2.0f, ay + ART / 2.0f);
                    c.rotateDeg(spin);
                    c.blit(tex, -ART / 2, -ART / 2, ART / 2, ART / 2);
                    c.pop();
                } else {
                    c.blit(tex, ax, ay, ax + ART, ay + ART);
                }
            } else {
                c.fill(ax, ay, ax + ART, ay + ART, Draw.withAlpha(0xFFFFFF, 0.08f));
                Text.drawCentered(font, "\u266B", ax + ART / 2, ay + ART / 2 - 4,
                        Draw.withAlpha(dimColor, 0.8f), false, false, false);
            }
            textX = ax + ART + 7;
            textW = width - PAD - textX;
        }
        String app = cfg.showSourceApp && track.app() != null ? Draw.appName(track.app()) : "";
        int appW = 0;
        if (!app.isEmpty()) {
            int appColor = Draw.withAlpha(dimColor, 0.75f);
            int appX = width - PAD - Text.width(font, app, false);
            Text.draw(font, app, appX, 6 + 2, appColor, false, false, cfg.shadow);
            appW = Text.width(font, app, false) + 8;
        }
        boolean center = cfg.centerTextWithoutArtwork && !cfg.showArtwork;
        int titleBox = Math.max(24, textW - 6 - appW);
        String title = track.title() == null ? "" : track.title();
        int titleW = Text.width(font, title, LyricsRenderer.Look.of(cfg).bold);
        int titleLeft = textX;
        if (center) {
            titleLeft = textX + (titleBox - (titleW + DOT_W + DOT_GAP)) / 2;
        }
        int dotColor = track.playing() ? accent : Draw.withAlpha(dimColor, 0.6f);
        if (titleLeft < textX) titleLeft = textX;
        c.fill(titleLeft, 8, titleLeft + DOT_W, 8 + DOT_W, dotColor);
        int titleX = titleLeft + DOT_W + DOT_GAP;
        drawLine(cfg, c, title, titleX, 6, Math.max(24, textX + titleBox - titleX), textColor);
        int lineY = 17;
        if (track.artist() != null && !track.artist().isEmpty()) {
            if (lineY + 9 <= BASE_H - 20) {
                drawLine(cfg, c, track.artist(), textX, lineY, textW, textColor);
                lineY += 10;
            }
        }
        if (cfg.showAlbum && track.album() != null && !track.album().isEmpty() && lineY + 9 <= BASE_H - 20) {
            drawLine(cfg, c, track.album(), textX, lineY, textW, dimColor);
        }
        if (cfg.showProgress && !cfg.lyricsOnly) {
            renderProgress(cfg, c, track, textX, width, accent, dimColor);
        }
    }

    private void renderProgress(SmtcConfig cfg, Canvas c, TrackInfo track, int textX, int width,
                                int accent, int dimColor) {
        net.minecraft.client.gui.FontRenderer font = c.font();
        int barY = BASE_H - 18;
        int barX2 = width - PAD;
        int barW = barX2 - textX;
        if (barW <= 4) return;
        c.fill(textX, barY, barX2, barY + 2, Draw.withAlpha(0xFFFFFF, 0.16f));
        float p = track.progress();
        if (p < 0.0f && cfg.progressFromStart) {
            // 没有时间线：画一段来回走的"在放"指示，至少让人看出不是卡住了
            int seg = Math.max(18, barW / 6);
            float span = barW + seg;
            float sx = textX + (clock * 46.0f % span) - seg;
            int x0 = (int) Math.max(textX, sx);
            int x1 = (int) Math.min(barX2, sx + seg);
            if (x1 > x0) c.fill(x0, barY, x1, barY + 2, Draw.withAlpha(accent, 0.75f));
        } else if (p >= 0.0f) {
            float clamped = Math.min(0.999f, Math.max(0.0f, p));
            int filled = Math.round(barW * clamped);
            if (filled > 0) c.fill(textX, barY, textX + filled, barY + 2, accent);
            float frac = barW * clamped - filled;
            if (frac > 0.03f && textX + filled + 1 <= barX2) {
                c.fill(textX + filled, barY, textX + filled + 1, barY + 2, Draw.withAlpha(accent, 0.5f));
            }
        }
        String elapsed = Draw.duration(track.elapsedMs());
        String total = Draw.duration(track.durationMs());
        Text.draw(font, elapsed, textX, barY + 4, Draw.withAlpha(dimColor, 0.75f), false, false, false);
        int totalX = barX2 - Text.width(font, total, false);
        Text.draw(font, total, totalX, barY + 4, Draw.withAlpha(dimColor, 0.75f), false, false, false);
    }

    /** 标题行：放得下就居中（要求里给了居中选项），否则交给跑马灯。 */
    private void drawLine(SmtcConfig cfg, Canvas c, String text, int x, int y, int maxW, int color) {
        if (text == null || text.isEmpty()) return;
        net.minecraft.client.gui.FontRenderer font = c.font();
        LyricsRenderer.Look look = LyricsRenderer.Look.plain(cfg.shadow);
        LyricsRenderer.marquee(c, font, text, x, y, maxW, color, look, 1.0f, clock, cfg.marqueeTrack);
    }

    // --- 歌词 ---------------------------------------------------------------

    private void renderLyrics(SmtcConfig cfg, Canvas c, LyricTrack lyrics, TrackInfo track,
                              LyricService service, LyricLayout layout, int headerH, int width,
                              boolean lyricsOnly, float step) {
        net.minecraft.client.gui.FontRenderer font = c.font();
        LyricsRenderer.Look look = LyricsRenderer.Look.of(cfg);
        LyricsRenderer.Look plainLook = LyricsRenderer.Look.plain(cfg.shadow);
        int rows = layout.rows == 0 ? 1 : layout.rows;
        int blockTop = headerH + LYRIC_GAP;
        int bandBottom = blockTop + rows * layout.blockH + LYRIC_BOTTOM_PAD;
        int centerX = width / 2;
        int innerW = width - 2 * PAD;
        int lyricColor = Draw.withAlpha(Draw.color(cfg.lyricColor, 0xFFFFFF), 1.0f);
        int karaokeColor = Draw.withAlpha(Draw.color(cfg.karaokeColor, 0x3FDC6E), 1.0f);
        int quietColor = Draw.withAlpha(lyricColor, 0.55f);
        int translationColor = Draw.withAlpha(Draw.color(cfg.translationColor, 0xFFFFFF), 1.0f);
        int candidateColor = Draw.withAlpha(Draw.color(cfg.candidateColor, 0xB8BCC4), 1.0f);
        int dimColor = Draw.withAlpha(Draw.color(cfg.dimTextColor, 0xB8BCC4), 1.0f);
        if (headerH > 0) c.fill(0, headerH - 1, width, headerH, Draw.withAlpha(0xFFFFFF, 0.10f));
        if (lyrics == null || lyrics.isEmpty()) {
            lyricHint(cfg, c, service, blockTop, rows, layout, width);
            animator.snap(0);
            return;
        }
        long position = lyricClock.advance(livePosition(cfg, track, service),
                track == null ? "" : track.dedupeKey(), System.currentTimeMillis());
        int current = lyrics.indexAt(position);
        if (current >= 0 && current < lyrics.size()) {
            LyricLine sung = lyrics.line(current);
            if (sung != null && position >= sung.endMs() && current + 1 < lyrics.size()) current++;
        }
        int base = Math.max(0, current);
        String key = (track == null ? "" : track.dedupeKey()) + "#" + lyrics.source();
        if (!key.equals(animatorKey)) {
            animatorKey = key;
            animator.snap(base);
            lyricClock.reset();
            scrollRow = -1;
            scrollX = 0.0f;
            scrollOut = 0.0f;
            handoverRow = -1;
            handoverOffset = 0.0f;
        }
        float scroll = animator.update(step, base);
        boolean moving = animator.moving();
        int anchor = (int) Math.floor(scroll);
        float offset = scroll - anchor;
        int startOffset = rows >= 3 ? -1 : 0;
        int textBudget = Math.max(8, Math.round(innerW / layout.lyricScale));
        int transBudget = Math.max(8, Math.round(innerW / Math.max(0.1f, layout.transScale)));
        boolean dual = cfg.dualLineWhenNoTranslation && layout.transH > 0 && rows <= 1;
        int fade = Math.max(1, EDGE_FADE);
        for (int k = -1; k <= rows; k++) {
            int index = anchor + k + startOffset;
            if (index < 0 || index >= lyrics.size()) continue;
            LyricLine line = lyrics.line(index);
            if (line == null) continue;
            int rowY = Math.round(blockTop + (k - offset) * layout.blockH);
            float alpha = Math.min(LyricsRenderer.smooth((rowY - headerH) / (float) fade),
                    LyricsRenderer.smooth((bandBottom - (rowY + layout.blockH)) / (float) fade));
            if (alpha <= 0.02f) continue;
            long start = line.startMs();
            long end = line.endMs();
            boolean timed = end > start;
            boolean inside = timed
                    ? (position >= start && (position < end || index == current))
                    : index == current;
            boolean alreadySung = timed ? position >= end : index < current;
            boolean handover = !inside && moving && current > 0 && index == current - 1;
            boolean hasWords = cfg.wordTiming && line.words() != null && !line.words().isEmpty();
            String text = line.text() == null ? "" : line.text();
            if (inside) {
                float played = timed ? (position - start) / (float) Math.max(1L, end - start) : 1.0f;
                float progress = cfg.karaoke ? Math.min(1.0f, Math.max(0.0f, played)) : 1.0f;
                float scrollOffset = rowScroll(cfg, font, line, text, look, layout.lyricScale,
                        textBudget, index, played, hasWords, step);
                LyricsRenderer.karaokeLine(c, font, text, centerX, rowY, textBudget,
                        Draw.scaleAlpha(karaokeColor, alpha), Draw.scaleAlpha(quietColor, alpha),
                        progress, look, layout.lyricScale, cfg.lyricNoteEase, cfg.karaokePeak,
                        cfg.karaokePeakAmount, cfg.karaokePeakRange, clock, cfg.marqueeLyrics,
                        cfg.wordTiming ? line.words() : null, position, scrollOffset);
            } else if (handover) {
                int hot = Draw.scaleAlpha(Draw.lerp(karaokeColor, Draw.withAlpha(dimColor, 0.45f), 1.0f), alpha);
                int quiet = Draw.scaleAlpha(Draw.withAlpha(dimColor, 0.45f), alpha);
                float scrollOffset = hasWords ? -1.0f : (index == handoverRow ? handoverOffset : 0.0f);
                LyricsRenderer.karaokeLine(c, font, text, centerX, rowY, textBudget, hot, quiet,
                        1.0f, look, layout.lyricScale, false, false, 0.0f, 0, clock,
                        cfg.marqueeLyrics, null, position, scrollOffset);
            } else {
                int color = alreadySung ? Draw.withAlpha(dimColor, 0.45f * alpha)
                        : Draw.scaleAlpha(dimColor, alpha);
                LyricsRenderer.marqueeCentered(c, font, text, centerX, rowY, textBudget, color, look,
                        layout.lyricScale, clock, cfg.marqueeLyrics);
            }
            if (layout.transH <= 0) continue;
            int transY = rowY + layout.lineH;
            String trans = line.translation();
            if (line.hasTranslation() && trans != null && !trans.isEmpty()) {
                float ta = (inside || handover) ? 0.92f : 0.78f;
                LyricsRenderer.marqueeCentered(c, font, trans, centerX, transY, transBudget,
                        Draw.scaleAlpha(translationColor, ta * alpha), plainLook,
                        layout.transScale, clock, cfg.marqueeLyrics);
            } else if (dual && index == current && index + 1 < lyrics.size()) {
                LyricLine next = lyrics.line(index + 1);
                if (next != null && next.text() != null && !next.text().isEmpty()) {
                    LyricsRenderer.marqueeCentered(c, font, next.text(), centerX, transY, transBudget,
                            Draw.scaleAlpha(candidateColor, 0.80f * alpha), plainLook,
                            layout.transScale, clock, cfg.marqueeLyrics);
                }
            }
        }
    }

    /**
     * 太长的行滚到哪里。有逐字时间时返回 -1，让渲染器按字形跟随——同一行不许有两套度量（AUDIT-26-5）。
     */
    private float rowScroll(SmtcConfig cfg, net.minecraft.client.gui.FontRenderer font, LyricLine line,
                            String text, LyricsRenderer.Look look, float scale, int maxWidth,
                            int index, float played, boolean hasWords, float step) {
        if (index != scrollRow) {
            handoverRow = scrollRow;
            handoverOffset = scrollOut;
            scrollRow = index;
            scrollX = 0.0f;
            scrollOut = 0.0f;
        }
        if (!cfg.marqueeLyrics) return -1.0f;
        float drawnW = LyricsRenderer.width(font, text, look) * scale;
        if (drawnW <= maxWidth + 0.5f) return -1.0f;
        if (hasWords) return -1.0f;
        float target = LyricScroll.followOffset(drawnW * played, drawnW, maxWidth);
        float limit = Math.max(0.0f, drawnW - maxWidth);
        float maxStep = SCROLL_CATCHUP * Math.max(0.0f, Math.min(0.1f, step));
        if (target > scrollX) {
            scrollX = Math.min(target, scrollX + maxStep);
        } else if (target < scrollX) {
            scrollX = Math.max(target, scrollX - maxStep);
        }
        scrollX = Math.max(0.0f, Math.min(limit, scrollX));
        scrollOut = scrollX;
        return scrollX;
    }

    private void lyricHint(SmtcConfig cfg, Canvas c, LyricService service, int blockTop, int rows,
                           LyricLayout layout, int width) {
        net.minecraft.client.gui.FontRenderer font = c.font();
        LyricService.Phase phase = service == null ? LyricService.Phase.IDLE : service.phase();
        String key;
        if (phase == LyricService.Phase.IDLE) {
            key = "smtcraft.hud.lyrics_idle";
        } else if (phase == LyricService.Phase.SEARCHING) {
            key = "smtcraft.hud.lyrics_wait";
        } else {
            key = "smtcraft.hud.lyrics_none";
        }
        String text = StatCollector.translateToLocal(key);
        int y = blockTop + rows * layout.blockH / 2 - 4;
        LyricsRenderer.drawCentered(c, font, text, width / 2, y, Draw.withAlpha(0xB8BCC4, 0.7f),
                LyricsRenderer.Look.plain(cfg.shadow), layout.lyricScale);
    }

    /** 播放位置：有时间线就用实时位置，否则用会话自己数的时间。 */
    private static long livePosition(SmtcConfig cfg, TrackInfo track, LyricService service) {
        if (track == null) return 0L;
        long base = track.endMs() > track.startMs() ? track.livePositionMs() : track.fallbackElapsedMs();
        long offset = service == null ? 0L : service.offsetFor(track);
        return base + cfg.lyricOffsetMs + offset;
    }

    /** 歌词带的尺寸表：一行歌词加一行翻译的高度、字号与滚动框。 */
    public static final class LyricLayout {
        public final float lyricScale;
        public final float transScale;
        public final int lineH;
        public final int transH;
        public final int blockH;
        public final int rows;

        private LyricLayout(float lyricScale, float transScale, int lineH, int transH, int blockH, int rows) {
            this.lyricScale = lyricScale;
            this.transScale = transScale;
            this.lineH = lineH;
            this.transH = transH;
            this.blockH = blockH;
            this.rows = rows;
        }

        public static LyricLayout of(SmtcConfig cfg) {
            float lyricScale = clampScale(cfg.lyricFontSize);
            float transScale = clampScale(cfg.translationFontSize);
            int lineH = Math.max(9, Math.round(LyricsRenderer.LINE_H * lyricScale));
            int transH = cfg.showTranslation ? Math.max(8, Math.round(9 * transScale)) : 0;
            int blockH = lineH + transH + 1;
            int rows = cfg.showLyrics ? Math.max(1, Math.min(4, cfg.lyricLines)) : 0;
            return new LyricLayout(lyricScale, transScale, lineH, transH, blockH, rows);
        }


        private static float clampScale(float size) {
            if (size <= 0.05f) return 1.0f;
            return Math.max(0.6f, Math.min(2.0f, size));
        }
    }
}
