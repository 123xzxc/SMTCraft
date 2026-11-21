package org.yinyun.smtcraft.render;

import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.SmtcCraftClient;
import org.yinyun.smtcraft.smtc.LyricLine;
import org.yinyun.smtcraft.smtc.LyricService;
import org.yinyun.smtcraft.smtc.LyricTrack;
import org.yinyun.smtcraft.media.MediaBridge;
import org.yinyun.smtcraft.smtc.TrackInfo;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HUD panel: album art (flat or spinning vinyl), title / artist / album, a
 * progress bar, the elapsed / total time and, when enabled, the lyric block with
 * a translation line under every visible row.
 *
 * <p>Everything is laid out in a design space that is {@code panelWidth} wide and
 * {@code 58 + lyric block} tall, then scaled once by the config {@code scale}
 * value, so the anchor maths stays readable. Lyric and translation glyph sizes
 * are separate scales applied inside that space.</p>
 */
public final class NowPlayingHud implements HudElement {
    private static final int DESIGN_W = 208;
    /** Hard ceiling for the adaptive width so a 120 glyph title does not fill the screen. */
    private static final int MAX_W = 470;
    /** Height of the track block without any lyrics. */
    private static final int BASE_H = 58;
    /** Width of the played/paused dot and the gap that follows it. */
    private static final int DOT_W = 3;
    private static final int DOT_GAP = 3;
    /** Breathing room above the first lyric row; must be >= {@link #EDGE_FADE}. */
    private static final int LYRIC_GAP = 10;
    /** Breathing room below the last lyric row; must be >= {@link #EDGE_FADE}. */
    private static final int LYRIC_BOTTOM_PAD = 10;
    /**
     * Height of the fade mask at both ends of the lyric band. A row reaches zero
     * alpha exactly when its box touches the band edge, so no glyph is ever drawn
     * half sheared by the scissor, however fast the list scrolls.
     */
    private static final int EDGE_FADE = 9;
    private static final int PAD = 5;
    /** With auto scaling on, the panel may cover this share of the viewport width. */
    private static final float MAX_SHARE_W = 0.55f;
    /** ... and this share of its height. */
    private static final float MAX_SHARE_H = 0.72f;
    /** Never shrink past this, or the text stops being legible. */
    private static final float MIN_FIT = 0.45f;
    /** Floor used when even {@link #MIN_FIT} would overflow the viewport. */
    private static final float HARD_MIN_FIT = 0.25f;
    private static final int ART = 44;
    /** How long the "lyric offset +100 ms" feedback stays under the panel. */
    private static final float NOTICE_SECONDS = 2.2f;
    /** Catch-up speed for a long lyric line that has to be revealed from its front. */
    private static final float SCROLL_CATCHUP = 360.0f;


    public static final Logger LOG = LoggerFactory.getLogger("smtcraft-hud");
    private final SmtcCraftClient mod;
    private final ArtworkCache artwork = new ArtworkCache();

    private float spin;
    private String spinKey = "";
    /** Seconds since the HUD first drew, used by the marquee. */
    private float clock;
    /** Short-lived line under the panel ("本曲歌词延迟 300 ms"), cleared by the clock. */
    private String noticeText = "";
    private float noticeAt = -999.0f;
    /** Continuous vertical scroll of the lyric band. */
    private final LyricAnimator animator = new LyricAnimator();
    /** Guarded position for the karaoke fill: it never steps backwards on a glitch. */
    private final LyricClock lyricClock = new LyricClock();
    /** Row the animator was last asked to show, so a track change resets the scroll. */
    private String animatorKey = "";
    /** Row whose long-line horizontal scroll is being tracked. */
    private int scrollRow = Integer.MIN_VALUE;
    /** How far that row has scrolled, in scaled pixels. */
    private float scrollX;
    /** Offset the finished row keeps while it scrolls out. */
    private float scrollOut;
    /** Row that just finished, so its scroll position can be frozen during the fade. */
    private int handoverRow = Integer.MIN_VALUE;
    private float handoverOffset;

    public NowPlayingHud(SmtcCraftClient mod) {
        this.mod = mod;
    }

    /**
     * @return a factor in {@code [MIN_FIT, 1]} that keeps the panel within
     *         {@link #MAX_SHARE_W} of the viewport width and {@link #MAX_SHARE_H}
     *         of its height; 1 when auto scaling is off or the window is large
     */
    private static float autoFit(SmtcConfig cfg, GuiGraphicsExtractor g, int width,
                                 int height, float scale) {
        if (!cfg.autoScale) return 1.0f;
        int vw = Math.max(1, g.guiWidth());
        int vh = Math.max(1, g.guiHeight());
        float needW = Math.max(1.0f, width * scale);
        float needH = Math.max(1.0f, height * scale);
        float fitW = vw * MAX_SHARE_W / needW;
        float fitH = vh * MAX_SHARE_H / needH;
        float fit = Math.min(1.0f, Math.min(fitW, fitH));
        // MIN_FIT keeps the panel readable, but the viewport wins: shrinking past it
        // is still better than drawing the panel off screen (C11)
        return fit >= MIN_FIT ? fit : Math.max(HARD_MIN_FIT, fit);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        SmtcConfig cfg = mod.config();
        if (cfg == null || !cfg.enabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.getDebugOverlay() != null && mc.getDebugOverlay().showDebugScreen()) return;

        // time keeps running while the panel is hidden, so the marquee phase and
        // the disc angle do not jump when it reappears (C13)
        float step = deltaSeconds(delta);
        clock += step;

        MediaBridge bridge = mod.bridge();
        TrackInfo track = bridge == null ? TrackInfo.EMPTY : bridge.latest();
        // the panel is drawn even while no session exists, so the art path below
        // must not reach through a null bridge (E6)
        byte[] artBytes = bridge == null ? null : bridge.artwork();
        String artHash = bridge == null ? "" : bridge.artworkHash();
        boolean hasTrack = track.hasTrack();
        // debug mode always draws, so a broken reader is visible instead of blank
        if (!hasTrack && cfg.hideWhenNoSession && !cfg.debug) {
            animator.finish();
            return;
        }
        if (hasTrack && !track.playing() && cfg.hideWhenPaused) {
            animator.finish();
            return;
        }

        advanceSpin(cfg, track, step);

        LyricService service = mod.lyrics();
        LyricTrack lyrics = cfg.showLyrics && service != null && service.matches(track)
                ? service.track()
                : LyricTrack.EMPTY;

        Font font = mc.font;
        float scale = cfg.scale <= 0.1f ? 0.1f : cfg.scale;
        int width = resolveWidth(cfg, font, track, hasTrack);

        LyricLayout layout = LyricLayout.of(cfg);
        int lyricH = layout.rows == 0 ? 0 : LYRIC_GAP + layout.rows * layout.blockH
                + LYRIC_BOTTOM_PAD;
        // with no session there is no lyric band to reserve space for
        int height = BASE_H + (hasTrack ? lyricH : 0);

        // a windowed client makes the same GUI-unit panel cover far more of the
        // screen; shrink it back towards the full-screen proportions
        float panelScale = scale * autoFit(cfg, g, width, height, scale);

        g.pose().pushMatrix();
        g.pose().translate(anchorX(cfg, g.guiWidth(), Math.round(width * panelScale)),
                           anchorY(cfg, g.guiHeight(), Math.round(height * panelScale)));
        g.pose().scale(panelScale);

        renderPanel(g, mc, font, cfg, width, height, track, hasTrack, step, artHash, artBytes);

        if (layout.rows > 0) {
            // the line-change animation slides rows through the lyric band, so the
            // band is clipped to the panel: enableScissor() transforms its rect by
            // the current pose, which here is the panel's own design space
            g.enableScissor(0, BASE_H - 1, width, height);
            renderLyrics(g, mc, cfg, width, layout, track, lyrics, step);
            g.disableScissor();
        }

        if (!noticeText.isEmpty()) {
            float age = clock - noticeAt;
            if (age >= 0.0f && age < NOTICE_SECONDS) {
                float a = Math.min(1.0f, Math.min(age / 0.12f, (NOTICE_SECONDS - age) / 0.5f));
                g.centeredText(font, noticeText, width / 2, height + 4,
                        Draw.scaleAlpha(0xFFE8F0FF, a));
            }
        }

        g.pose().popMatrix();
    }

    /** Show a line under the panel for a couple of seconds (offset nudge feedback). */
    public void notice(String text) {
        noticeText = text == null ? "" : text;
        noticeAt = clock;
    }

    /** This song's own offset; the global slider stays the baseline for everything. */
    private long songOffset(TrackInfo track) {
        LyricService service = mod.lyrics();
        return service == null ? 0L : service.offsetFor(track);
    }

    /** Panel width: the configured minimum, or wider when the text needs it. */
    private int resolveWidth(SmtcConfig cfg, Font font, TrackInfo track, boolean hasTrack) {
        int base = Math.max(DESIGN_W, cfg.panelWidth);
        if (!cfg.adaptiveWidth || !hasTrack) return base;
        int artW = cfg.showArtwork ? ART + 7 : 0;
        int appW = cfg.showSourceApp && !track.app().isEmpty()
                ? font.width(Draw.appName(track.app())) + 8
                : 0;
        int titleNeed = font.width(track.title()) + 6 + appW;
        int bodyNeed = Math.max(font.width(track.artist()),
                cfg.showAlbum ? font.width(track.album()) : 0);
        int need = Math.max(titleNeed, bodyNeed) + PAD * 2 + artW;
        return Math.max(base, Math.min(MAX_W, need));
    }

    /** Rotate the disc only while a track is really playing, and reset per track. */
    private void advanceSpin(SmtcConfig cfg, TrackInfo track, float step) {
        if (!cfg.vinyl) {
            spin = 0.0f;
            return;
        }
        String key = track.hasTrack() ? track.dedupeKey() : "";
        if (!key.equals(spinKey)) {
            spinKey = key;
            spin = 0.0f;
        }
        if (!track.playing()) return;
        float speed = cfg.vinylSpinSpeed <= 0.0f ? 0.0f : Math.min(720.0f, cfg.vinylSpinSpeed);
        spin = (spin + speed * step) % 360.0f;
    }

    private void renderPanel(GuiGraphicsExtractor g, Minecraft mc, Font font, SmtcConfig cfg,
                             int width, int height, TrackInfo track, boolean hasTrack,
                             float dt, String artHash, byte[] artBytes) {
        int x = 0;
        int y = 0;

        int bg = Draw.withAlpha(0x000000, cfg.backgroundOpacity);
        int radius = Math.max(0, Math.min(24, cfg.cornerRadius));
        if (radius > 0) {
            Draw.fillRound(g, x, y, width, height, radius, bg);
            Draw.outlineRound(g, x, y, width, height, radius, Draw.withAlpha(0xFFFFFF, 0.10f));
        } else {
            g.fill(x, y, x + width, y + height, bg);
            g.fill(x, y, x + width, y + 1, Draw.withAlpha(0xFFFFFF, 0.10f));
            g.fill(x, y + height - 1, x + width, y + height, Draw.withAlpha(0xFFFFFF, 0.10f));
        }

        int textColor = Draw.color(cfg.textColor, 0xFFFFFFFF);
        int dimColor = Draw.color(cfg.dimTextColor, 0xFFB8BCC4);
        int accent = Draw.color(cfg.accentColor, 0xFF3FDC6E);

        int textX = x + PAD;
        int textW = width - PAD * 2;

        if (!hasTrack) {
            String empty = Component.translatable("smtcraft.hud.empty").getString();
            g.text(font, Draw.fit(font, empty, textW), textX, y + 14, dimColor);
            return;
        }

        if (cfg.showArtwork) {
            long artStart = System.nanoTime();
            Identifier tex = artwork.texture(artHash, artBytes, cfg.vinyl);
            long artMs = (System.nanoTime() - artStart) / 1_000_000L;
            if (artMs >= 30L) {
                // decoding happens on a background thread, so a slow frame here means
                // the texture upload itself is big: report it once per cover
                org.slf4j.LoggerFactory.getLogger("smtcraft")
                        .warn("slow cover upload: {} ms ({} bytes)", artMs, artBytes == null ? 0 : artBytes.length);
            }
            int ax = x + PAD;
            int ay = y + PAD;
            if (tex != null) {
                if (cfg.vinyl) {
                    drawDisc(g, tex, ax, ay);
                } else {
                    // 26.3 blit(Identifier, x0, y0, x1, y1, u0, u1, v0, v1): the four
                    // floats are grouped as a u range then a v range, NOT (u1, v1, u2, v2).
                    // Passing 0,0,1,1 collapses the UV to a single texel -> blank cover.
                    g.blit(tex, ax, ay, ax + ART, ay + ART, 0f, 1f, 0f, 1f);
                }
            } else {
                g.fill(ax, ay, ax + ART, ay + ART, Draw.withAlpha(0xFFFFFF, 0.08f));
                String note = "\u266B";
                g.text(font, note, ax + ART / 2 - font.width(note) / 2, ay + ART / 2 - 4, dimColor);
            }
            textX = ax + ART + 7;
            textW = x + width - PAD - textX;
        }

        boolean playing = track.playing();
        LyricsRenderer.Look look = LyricsRenderer.Look.plain(null, true);
        boolean marquee = cfg.marqueeTrack;

        // --- title, with a status dot ---------------------------------------
        int appW = 0;
        if (cfg.showSourceApp && !track.app().isEmpty()) {
            String app = Draw.appName(track.app());
            appW = font.width(app) + 8;
            int appColor = Draw.withAlpha(dimColor, 0.75f);
            int appX = x + width - PAD - font.width(app);
            // a title that reaches under the app name looked broken: the title box
            // stops 8 px before it, and adaptive width grows the panel instead
            g.text(font, app, appX, y + 6, appColor);
        }
        // item 19: with no artwork the text block is centred, the app name is not
        boolean center = cfg.centerTextWithoutArtwork && !cfg.showArtwork;
        int dot = playing ? accent : dimColor;
        int titleBox = Math.max(24, textW - 6 - appW);
        int titleLeft = textX;
        if (center) {
            // the block is dot + gap + title, so the dot counts towards centring
            int need = font.width(track.title()) + DOT_W + DOT_GAP;
            if (need <= titleBox) titleLeft = textX + (titleBox - need) / 2;
        }
        g.fill(titleLeft, y + 8, titleLeft + 3, y + 11, dot);
        drawLine(g, font, track.title(), titleLeft + 6, y + 6,
                Math.max(24, textX + titleBox - titleLeft - 6), textColor, look, clock, marquee, false);

        int lineY = y + 17;
        String artist = track.artist();
        if (!artist.isEmpty()) {
            drawLine(g, font, artist, textX, lineY, textW, textColor, look, clock, marquee, center);
            lineY += 10;
        }
        if (cfg.showAlbum && !track.album().isEmpty()) {
            drawLine(g, font, track.album(), textX, lineY, textW, dimColor, look, clock, marquee, center);
            lineY += 10;
        }

        // --- progress --------------------------------------------------------
        if (cfg.showProgress) {
            int barY = y + BASE_H - 18;
            int barX2 = x + width - PAD;
            int barW = barX2 - textX;
            g.fill(textX, barY, barX2, barY + 2, Draw.withAlpha(0xFFFFFF, 0.16f));
            float p = track.progress();
            if (p < 0f && cfg.progressFromStart) {
                // no timeline from the app: keep the bar alive with a travelling
                // segment so the panel does not look frozen
                int seg = Math.max(18, barW / 6);
                int span = barW + seg;
                int sx = textX + (int) ((clock * 46.0f) % span) - seg;
                int from = Math.max(textX, sx);
                int to = Math.min(barX2, sx + seg);
                if (to > from) g.fill(from, barY, to, barY + 2, Draw.withAlpha(accent, 0.75f));
            } else if (p >= 0f) {
                // subpixel head: rounding the whole bar to a pixel makes it crawl in
                // visible 1 px steps, so the last pixel is drawn with partial alpha
                float edge = barW * p;
                int full = (int) Math.floor(edge);
                if (full > 0) g.fill(textX, barY, textX + full, barY + 2, accent);
                float frac = edge - full;
                int headX = textX + full;
                if (frac > 0.03f && headX < barX2) {
                    g.fill(headX, barY, headX + 1, barY + 2, Draw.withAlpha(accent, frac));
                }
            }
            long elapsed = track.durationMs() > 0L
                    ? track.elapsedMs()
                    : (cfg.progressFromStart ? track.fallbackElapsedMs() : -1L);
            if (elapsed >= 0L) {
                g.text(font, Draw.duration(elapsed), textX, barY + 4, dimColor);
            }
            if (track.durationMs() > 0L) {
                String total = Draw.duration(track.durationMs());
                g.text(font, total, barX2 - font.width(total), barY + 4, dimColor);
            }
        }

    }

    /**
     * Title / artist / album line. A line that does not fit scrolls instead of being
     * cut off (item 5); when {@code center} is set and it does fit, it is centred
     * inside [x, x + maxW] (item 19).
     */
    private void drawLine(GuiGraphicsExtractor g, Font font, String text, int x, int y, int maxW,
                          int color, LyricsRenderer.Look look, float clock, boolean marquee,
                          boolean center) {
        if (center) {
            int w = font.width(text);
            if (w <= maxW) {
                LyricsRenderer.draw(g, font, text, x + (maxW - w) / 2, y, color, look, 1.0f);
                return;
            }
        }
        LyricsRenderer.marquee(g, font, text, x, y, maxW, color, look, 1.0f, clock, marquee);
    }

    /**
     * Horizontal scroll for a lyric row wider than the panel. A long line has to be
     * read front to back: it starts with its first glyph on the left edge and then
     * crawls after the play head, so a line that becomes current halfway through -
     * after a seek, a late lyric fetch or a stalled position - no longer jumps to
     * its tail and looks as if the front had been cut off.
     *
     * @param hasWords whether this row carries per-word timings, in which case the
     *                 renderer is the only one that knows where the sung edge is
     * @return pixels the row has scrolled, or -1 to let the renderer follow the head
     */
    private float rowScroll(Font font, String text, LyricsRenderer.Look look, int maxWidth,
                            float scale, int index, float played, float dt, boolean hasWords) {
        boolean fresh = index != scrollRow;
        if (index != scrollRow) {
            if (scrollRow != Integer.MIN_VALUE && index > scrollRow) {
                handoverRow = scrollRow;
                handoverOffset = scrollOut;
            }
            scrollRow = index;
            scrollX = 0.0f;
        }
        float drawnW = LyricsRenderer.width(font, text, look) * scale;
        if (drawnW <= maxWidth + 0.5f) {
            scrollOut = 0.0f;
            return -1.0f;
        }
        if (hasWords) {
            // This row has per-word timings, so the renderer already knows the sung
            // edge in glyph pixels. A time fraction here would be a second, different
            // metric driving the same row - and the two disagree on mixed scripts,
            // on rows with missing word tags, and wherever the font is narrow (R26-5).
            scrollOut = 0.0f;
            return -1.0f;
        }
        float limit = drawnW - maxWidth;
        float target = LyricScroll.followOffset(drawnW * played, drawnW, maxWidth);
        float step = SCROLL_CATCHUP * Math.min(0.1f, Math.max(0.0f, dt));
        scrollX += Math.max(-step, Math.min(step, target - scrollX));
        scrollX = Math.max(0.0f, Math.min(scrollX, limit));
        scrollOut = scrollX;
        if (fresh) {
            LOG.debug("long lyric row {}: drawn={} box={} played={} start-target={} {}",
                    index, Math.round(drawnW), maxWidth, played, Math.round(target), text);
        }
        return scrollX;
    }

    /** Spinning vinyl: the circular texture is rotated around the art centre. */
    private void drawDisc(GuiGraphicsExtractor g, Identifier tex, int ax, int ay) {
        float cx = ax + ART / 2.0f;
        float cy = ay + ART / 2.0f;
        int half = ART / 2;
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate((float) Math.toRadians(spin));
        g.blit(tex, -half, -half, half, half, 0f, 1f, 0f, 1f);
        // spindle hole, painted in the rotated space so it stays in the middle
        g.fill(-1, -1, 1, 1, 0xCC101010);
        g.pose().popMatrix();
    }

    private void renderLyrics(GuiGraphicsExtractor g, Minecraft mc, SmtcConfig cfg, int width,
                              LyricLayout layout, TrackInfo track, LyricTrack lyrics, float dt) {
        int blockTop = BASE_H + LYRIC_GAP;
        int bandBottom = blockTop + layout.rows * layout.blockH + LYRIC_BOTTOM_PAD;
        int centerX = width / 2;
        int innerW = width - PAD * 2;
        Identifier fontId = lyricFontId(cfg);
        Font font = lyricFont(mc, cfg);
        LyricsRenderer.Look look = LyricsRenderer.Look.of(cfg, fontId);

        int textColor = Draw.color(cfg.textColor, 0xFFFFFFFF);
        int dimColor = Draw.color(cfg.dimTextColor, 0xFFB8BCC4);
        int lyricColor = Draw.color(cfg.lyricColor, textColor);
        int karaokeColor = Draw.color(cfg.karaokeColor, Draw.color(cfg.accentColor, 0xFF3FDC6E));
        int quietColor = Draw.withAlpha(lyricColor, 0.55f);

        // hairline between the track block and the lyrics
        g.fill(PAD, BASE_H - 1, width - PAD, BASE_H, Draw.withAlpha(0xFFFFFF, 0.10f));

        if (lyrics == null || lyrics.isEmpty()) {
            String hint = lyricHint(track);
            int rowY = blockTop + (layout.rows * layout.blockH) / 2 - 4;
            LyricsRenderer.drawCentered(g, font, LyricsRenderer.fit(font, hint, innerW, look),
                    centerX, rowY, Draw.withAlpha(dimColor, 0.70f), look, layout.lyricScale);
            animator.snap(0);
            return;
        }

        // Apps that publish no timeline (NetEase cloudmusic, some browsers) report
        // 0/0, so a position of -1 used to freeze the lyrics on the first line. The
        // wall clock keeps them moving instead.
        long raw = (track.endMs() > track.startMs()
                ? track.livePositionMs()
                : track.fallbackElapsedMs()) + cfg.lyricOffsetMs + songOffset(track);
        // A single sample that reports "paused" (buffering, a track that is just
        // starting, a recreated media session) sends the whole chain back to the last
        // published step, and the karaoke fill visibly retreats for one frame. The
        // clock holds that dip instead of drawing it (lyric rewind fix).
        long position = lyricClock.advance(raw, track.dedupeKey(), System.currentTimeMillis());
        int current = lyrics.indexAt(position);
        // A line that has been sung out must not keep the top slot: once the position
        // passes its end the next line takes over, so the finished one scrolls away
        // instead of sitting there in full karaoke colour while the preview below it
        // already shows the following line.
        LyricLine sung = lyrics.line(current);
        if (sung != null && sung.endMs() > sung.startMs() && position >= sung.endMs()
                && current + 1 < lyrics.size()) {
            current += 1;
        }
        int base = Math.max(0, current);

        // a different song starts at its row instead of scrolling down from row 0
        String key = track.dedupeKey();
        if (!key.equals(animatorKey)) {
            animatorKey = key;
            animator.snap(base);
            // one place says 'a new song starts clean': the clock's own track check
            // would do it implicitly, but then reset() had no production caller (R25-2)
            lyricClock.reset();
            scrollRow = Integer.MIN_VALUE;
            scrollX = 0.0f;
            scrollOut = 0.0f;
            handoverRow = Integer.MIN_VALUE;
            handoverOffset = 0.0f;
        }

        float scroll = animator.update(dt, base);
        // 0 while a hand-over starts, 1 when it has landed; used to dim the row that
        // is scrolling out over the same 0.3 s instead of after it
        float swap = animator.eased();
        int anchor = (int) Math.floor(scroll);
        float offset = scroll - anchor;
        int startOffset = layout.rows >= 3 ? -1 : 0;

        int textBudget = Math.max(8, Math.round(innerW / layout.lyricScale));
        int transBudget = Math.max(8, Math.round(innerW / layout.transScale));
        boolean marquee = cfg.marqueeLyrics;
        // The dual-line fallback borrows the translation slot to preview the next line.
        // That only makes sense while a single lyric row is on screen; with 2+ rows the
        // next line is already a row of its own, so doing both printed it twice.
        boolean dual = cfg.dualLineWhenNoTranslation && layout.transH > 0 && layout.rows <= 1;
        boolean moving = animator.moving();
        float fade = Math.max(1.0f, EDGE_FADE);

        // One row of margin on either side of the band. The edge mask drives their alpha
        // to zero before they can be sliced by the scissor, so the band never shows a
        // half-drawn glyph no matter how fast the list moves.
        for (int k = -1; k <= layout.rows; k++) {
            int index = anchor + k + startOffset;
            LyricLine line = lyrics.line(index);
            if (line == null) continue;
            int rowY = Math.round(blockTop + (k - offset) * layout.blockH);
            float alpha = Math.min(
                    LyricsRenderer.smooth((rowY - BASE_H) / fade),
                    LyricsRenderer.smooth((bandBottom - (rowY + layout.blockH)) / fade));
            if (alpha <= 0.02f) continue;

            long lineStart = line.startMs();
            long lineEnd = line.endMs();
            boolean timed = lineEnd > lineStart;
            // Colour follows the clock rather than the row index: a line is "being sung"
            // the moment the play position sits inside it, so a multi-row window can
            // never light the wrong row up (or leave a finished one hot) for a line.
            // A line stays lit until the next one takes over (and forever on the last
            // line): passing endMs with no successor must not drop the colour to grey.
            boolean inside = timed
                    ? position >= lineStart && (position < lineEnd || index == current)
                    : index == current;
            boolean alreadySung = timed ? position >= lineEnd : index < current;
            boolean handover = !inside && moving && current > 0 && index == current - 1;
            String shown = line.text();

            // One metric per row: rows with word timings are driven by the renderer
            // (glyph pixels), the rest by the time fraction below (R26-5).
            boolean hasWords = cfg.wordTiming && line.words() != null;
            if (inside) {
                float played = Math.max(0.0f, Math.min(1.0f,
                        (position - lineStart) / (float) Math.max(1L, lineEnd - lineStart)));
                float progress = cfg.karaoke ? played : 1.0f;
                LyricsRenderer.karaokeLine(g, font, shown, centerX, rowY, innerW,
                        Draw.scaleAlpha(karaokeColor, alpha),
                        Draw.scaleAlpha(quietColor, alpha), progress, look,
                        layout.lyricScale, cfg.lyricNoteEase,
                        cfg.karaokePeak, cfg.karaokePeakAmount,
                        cfg.karaokePeakRange, clock, marquee,
                        cfg.wordTiming ? line.words() : null, position,
                        rowScroll(font, shown, look, innerW, layout.lyricScale, index, played, dt,
                                hasWords));
            } else if (handover) {
                // the row that was just sung keeps its karaoke layout while it scrolls
                // out, but dims over the same 0.3 s instead of snapping to grey after it
                int gone = Draw.withAlpha(dimColor, 0.45f);
                LyricsRenderer.karaokeLine(g, font, shown, centerX, rowY, innerW,
                        Draw.scaleAlpha(Draw.lerp(karaokeColor, gone, swap), alpha),
                        Draw.scaleAlpha(Draw.lerp(quietColor, gone, swap), alpha), 1.0f, look,
                        layout.lyricScale, false, false, 0.0f, 0, clock, marquee,
                        // A row leaving the stage keeps its own offset; only the
                        // "let the renderer follow" case may carry -1, and a word-timed
                        // row is exactly that case. Passing -1 for a row that has no
                        // offset pushed a finished line to its tail (R26-7).
                        null, position, hasWords ? -1.0f
                                : (index == handoverRow ? handoverOffset : 0.0f));
            } else {
                int color = alreadySung ? Draw.withAlpha(dimColor, 0.45f) : dimColor;
                LyricsRenderer.marqueeCentered(g, font, shown, centerX, rowY, innerW,
                        Draw.scaleAlpha(color, alpha), look, layout.lyricScale, clock, marquee);
            }

            if (layout.transH <= 0) continue;
            int transY = rowY + layout.lineH;
            if (line.hasTranslation()) {
                int color = inside || handover
                        ? Draw.withAlpha(lyricColor, 0.92f)
                        : Draw.withAlpha(dimColor, 0.78f);
                LyricsRenderer.marqueeCentered(g, font, line.translation(), centerX, transY,
                        transBudget, Draw.scaleAlpha(color, alpha), look, layout.transScale,
                        clock, marquee);
            } else if (dual && index == current) {
                // No translation for this song: the second slot previews the next line,
                // so the reader still gets a two-line view (single-row mode only). It has
                // to hang off "this is the current row", not "this row is being sung" —
                // otherwise the preview only popped up after the line had already begun
                // and the intro/gaps showed a single lonely row.
                LyricLine next = lyrics.line(index + 1);
                if (next != null && !next.text().isEmpty()) {
                    LyricsRenderer.marqueeCentered(g, font, next.text(), centerX, transY,
                            transBudget,
                            Draw.scaleAlpha(Draw.withAlpha(dimColor, 0.80f), alpha), look,
                            layout.transScale, clock, marquee);
                }
            }
        }
    }

    /** What to show in the lyric area while there is nothing to sing yet. */
    private String lyricHint(TrackInfo track) {
        if (!track.hasTrack()) return Component.translatable("smtcraft.hud.lyrics_idle").getString();
        LyricService service = mod.lyrics();
        // "loading" is only honest while a provider is actually being asked; the
        // service owns that answer, the HUD no longer pattern-matches its status
        // strings (C10/E2)
        LyricService.Phase phase = service == null ? LyricService.Phase.EMPTY : service.phase();
        if (phase == LyricService.Phase.IDLE || phase == LyricService.Phase.SEARCHING) {
            return Component.translatable("smtcraft.hud.lyrics_wait").getString();
        }
        return Component.translatable("smtcraft.hud.lyrics_none").getString();
    }

    /** Font override id, or null for the vanilla font object. */
    public static Identifier lyricFontId(SmtcConfig cfg) {
        if (cfg == null) return null;
        String mode = cfg.lyricFontMode == null ? "default" : cfg.lyricFontMode;
        // only reference the generated font once the pack is actually loaded
        if ("system".equals(mode)) {
            return org.yinyun.smtcraft.font.FontPack.isApplied(cfg)
                    ? Identifier.fromNamespaceAndPath("smtcraft", "systemfont") : null;
        }
        if (!"custom".equals(mode)) return null;
        String id = cfg.lyricFontId == null ? "" : cfg.lyricFontId.trim();
        if (id.isEmpty()) return null;
        return Identifier.tryParse(id);
    }

    /** The base font object; "unicode" pins the forced-Unicode font. */
    public static Font lyricFont(Minecraft mc, SmtcConfig cfg) {
        if (mc == null) return null;
        if (cfg != null && "unicode".equals(cfg.lyricFontMode)) return mc.fontFilterFishy;
        return mc.font;
    }

    private static float deltaSeconds(DeltaTracker delta) {
        if (delta == null) return 0.05f;
        float d = delta.getRealtimeDeltaTicks() / 20.0f;
        if (d <= 0.0f || d > 1.0f) return 0.05f;
        return d;
    }

    private static float clampScale(float value) {
        return value <= 0.05f ? 1.0f : Math.max(0.6f, Math.min(2.0f, value));
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static int anchorX(SmtcConfig cfg, int screenW, int panelW) {
        int off = Math.max(0, cfg.offsetX);
        return switch (cfg.anchor == null ? "" : cfg.anchor) {
            case "top-right", "bottom-right" -> screenW - panelW - off;
            case "center" -> (screenW - panelW) / 2;
            default -> off;
        };
    }

    private static int anchorY(SmtcConfig cfg, int screenH, int panelH) {
        int off = Math.max(0, cfg.offsetY);
        return switch (cfg.anchor == null ? "" : cfg.anchor) {
            case "bottom-left", "bottom-right" -> screenH - panelH - off;
            case "center" -> (screenH - panelH) / 2;
            default -> off;
        };
    }

    /** Row geometry, resolved once per frame from the config. */
    private static final class LyricLayout {
        int rows;
        int lineH;
        int transH;
        int blockH;
        float lyricScale;
        float transScale;

        static LyricLayout of(SmtcConfig cfg) {
            LyricLayout l = new LyricLayout();
            l.lyricScale = clampScale(cfg.lyricFontSize);
            l.transScale = clampScale(cfg.translationFontSize);
            l.lineH = Math.max(9, Math.round(11.0f * l.lyricScale));
            l.transH = cfg.showTranslation ? Math.max(8, Math.round(9.0f * l.transScale)) : 0;
            l.blockH = l.lineH + l.transH + 1;
            l.rows = cfg.showLyrics ? clamp(cfg.lyricLines, 1, 4) : 0;
            return l;
        }
    }
}
