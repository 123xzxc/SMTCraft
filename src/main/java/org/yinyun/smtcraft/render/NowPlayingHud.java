package org.yinyun.smtcraft.render;

import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.SmtcCraftClient;
import org.yinyun.smtcraft.smtc.LyricLine;
import org.yinyun.smtcraft.smtc.LyricService;
import org.yinyun.smtcraft.smtc.LyricTrack;
import org.yinyun.smtcraft.media.MediaBridge;
import org.yinyun.smtcraft.smtc.TrackInfo;
import org.yinyun.smtcraft.util.GameScale;
import org.yinyun.smtcraft.util.HudLayout;
import org.yinyun.smtcraft.util.Screens;
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

    /** Last frame's clock, for the versions whose HUD entry point carries no tracker. */
    private long lastNanos;
    private static final int DESIGN_W = 208;
    /** Hard ceiling for the adaptive width so a 120 glyph title does not fill the screen. */
    private static final int MAX_W = 470;
    /** Height of the track block without any lyrics. */
    private static final int BASE_H = 58;
    /** The vertical panel is at least this tall, and never taller than the third value. */
    private static final int VERTICAL_MIN_H = 168;
    private static final int VERTICAL_MAX_H = 320;
    /** Floor for the content height, so the lyric columns have room for a few lines. */
    private static final int VERTICAL_MIN_CONTENT = 150;
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
    /**
     * Seconds since the HUD first drew, used by the marquee, the disc angle and the vertical
     * columns. It is a double on purpose: a float can only add a small step while it is small,
     * and this one is added to sixty times a second for as long as the game stays open. Past
     * 2^18 s (3.03 days) one float step is worth a whole ULP, so the clock ran at 1.875x; past
     * 2^19 s (6.07 days) the addition rounded back to the same value and the marquee froze
     * mid-glyph until the game was restarted (R50-4). A double has room for centuries.
     */
    private double clock;

    /** Short-lived line under the panel ("本曲歌词延迟 300 ms"), cleared by the clock. */
    private String noticeText = "";
    private double noticeAt = -999.0d;
    /** Continuous vertical scroll of the lyric band. */
    private final LyricAnimator animator = new LyricAnimator();
    /** Guarded position for the karaoke fill: it never steps backwards on a glitch. */
    private final LyricClock lyricClock = new LyricClock();
    /** Row the animator was last asked to show, so a track change resets the scroll. */
    private String animatorKey = "";
    /** Key and answer of the last "tallest lyric column" measurement (see longestLyricHeight). */
    private String longestKey = "";
    private int longestHeight = 0;
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

    /**
     * 26.4 dropped the tracker argument from {@code HudElement}, so this entry point is the
     * abstract one there; 26.1–26.3 hand the tracker in and never call this. Both are
     * concrete, and no {@code @Override} is written: only one of them exists per version,
     * and an inherited abstract method that only one side declares is an
     * {@code AbstractMethodError} at the first frame in the world.
     */
    public void extractRenderState(GuiGraphicsExtractor g) {
        drawHud(g, null);
    }

    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        drawHud(g, delta);
    }

    /** The body both entry points share. {@code delta} is null where the game has none. */
    private void drawHud(GuiGraphicsExtractor g, DeltaTracker delta) {
        SmtcConfig cfg = mod.config();
        if (cfg == null || !cfg.enabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (mc.getDebugOverlay() != null && mc.getDebugOverlay().showDebugScreen()) return;
        // time keeps running while the panel is hidden, so the marquee phase and the disc
        // angle do not jump when it reappears (C13). Every hide check sits *below* this line:
        // one above it freezes the clock for that option only, which is how this method used
        // to be laid out (AUDIT-34-3).
        float step = delta == null ? wallStep() : deltaSeconds(delta);
        clock += step;

        MediaBridge bridge = mod.bridge();
        TrackInfo track = bridge == null ? TrackInfo.EMPTY : bridge.latest();
        // the panel is drawn even while no session exists, so the art path below
        // must not reach through a null bridge (E6)
        byte[] artBytes = bridge == null ? null : bridge.artwork();
        String artHash = bridge == null ? "" : bridge.artworkHash();
        boolean hasTrack = track.hasTrack();
        advanceSpin(cfg, track, step);

        // any open screen draws over the HUD anyway; this option stops it from being
        // painted behind the inventory, the settings page, the pause menu (AUDIT-38)
        if (cfg.hideWhenScreenOpen && Screens.menuOpen()) {
            animator.finish();
            return;
        }
        // debug mode always draws, so a broken reader is visible instead of blank
        if (!hasTrack && cfg.hideWhenNoSession && !cfg.debug) {
            animator.finish();
            return;
        }
        if (hasTrack && !track.playing() && cfg.hideWhenPaused) {
            animator.finish();
            return;
        }

        LyricService service = mod.lyrics();
        LyricTrack lyrics = cfg.showLyrics && service != null && service.matches(track)
                ? service.track()
                : LyricTrack.EMPTY;

        Font font = mc.font;
        // the panel is drawn for GUI scale 2; without this it covers twice the glass at
        // scale 4 and half of it at scale 1 (AUDIT-36-1)
        float scale = (cfg.scale <= 0.1f ? 0.1f : cfg.scale) * GameScale.factor(cfg.followGuiScale);
        int trackW = resolveWidth(cfg, font, track, hasTrack);

        LyricLayout layout = LyricLayout.of(cfg);
        // lyrics-only: the track block (cover, title, progress) is not drawn at all, so
        // the band starts at the panel edge. Without a session it stays a full panel -
        // a band with no lyrics in it would be an invisible mod.
        boolean lyricsOnly = cfg.lyricsOnly && hasTrack && cfg.showLyrics;
        int bandRows = layout.rows == 0 ? 1 : layout.rows;
        boolean hasBand = layout.rows > 0 || lyricsOnly;
        if (HudLayout.vertical(cfg.hudLayout)) {
            drawVerticalPanel(g, mc, font, cfg, layout, bandRows, track, hasTrack, lyrics, scale,
                    step, artHash, artBytes);
            return;
        }
        int lyricH = hasBand ? LYRIC_GAP + bandRows * layout.blockH + LYRIC_BOTTOM_PAD : 0;
        int trackH = lyricsOnly ? 0 : BASE_H;
        // a tall panel needs both blocks: with no lyrics there is nothing to put beside
        // the track block, so the arrangement falls back to the stack
        boolean side = HudLayout.side(cfg.hudLayout) && trackH > 0 && lyricH > 0;
        // the lyric column keeps the design width even when the track block grew for a
        // long title: a 470 px wide band would leave two words on each row
        int lyricW = side ? Math.max(DESIGN_W, cfg.panelWidth) : trackW;
        int[] boxes = HudLayout.arrange(side, cfg.trackFirst, trackW, trackH, lyricW, lyricH);
        int width = boxes[4];
        int height = boxes[5];

        // a windowed client makes the same GUI-unit panel cover far more of the
        // screen; shrink it back towards the full-screen proportions
        float panelScale = scale * autoFit(cfg, g, width, height, scale);

        g.pose().pushMatrix();
        // pushed outside the try, popped in the finally: an exception halfway through would
        // otherwise leave the pose scaled for the whole rest of the frame - and, if it happens
        // while the scissor is on, the entire screen clipped down to the panel (AUDIT-35-5)
        try {
            g.pose().translate(anchorX(cfg, g.guiWidth(), Math.round(width * panelScale)),
                               anchorY(cfg, g.guiHeight(), Math.round(height * panelScale)));
            g.pose().scale(panelScale);

            drawChrome(g, cfg, width, height);
            if (trackH > 0) {
                // each block is drawn in its own corner: the track block keeps its own
                // width, so the progress bar of a tall panel does not stretch across the
                // lyric column (AUDIT-48-1)
                g.pose().translate(boxes[0], boxes[1]);
                try {
                    renderPanel(g, mc, font, cfg, trackW, trackH, track, hasTrack, step, artHash, artBytes);
                } finally {
                    g.pose().translate(-boxes[0], -boxes[1]);
                }
                if (side) {
                    // the stacked panel separates the blocks with a hairline under the
                    // header; side by side the same line runs between the columns
                    int seam = cfg.trackFirst
                            ? boxes[2] - HudLayout.GAP / 2
                            : boxes[2] + lyricW + HudLayout.GAP / 2;
                    g.fill(seam, PAD, seam + 1, height - PAD, Draw.withAlpha(0xFFFFFF, 0.10f));
                }
            }

            if (lyricH > 0) {
                // the line-change animation slides rows through the lyric band, so the
                // band is clipped to the panel: enableScissor() transforms its rect by
                // the current pose, which here is the panel's own design space
                g.enableScissor(boxes[2], boxes[3] - 1, boxes[2] + lyricW, boxes[3] + lyricH);
                try {
                    g.pose().translate(boxes[2], boxes[3]);
                    try {
                        renderLyrics(g, mc, cfg, lyricW, !side,
                                HudLayout.seamAlign(side, cfg.trackFirst), bandRows,
                                bandRows * layout.blockH + LYRIC_BOTTOM_PAD, layout, track,
                                lyrics, step);
                    } finally {
                        g.pose().translate(-boxes[2], -boxes[3]);
                    }
                } finally {
                    g.disableScissor();
                }
            }

            if (!noticeText.isEmpty()) {
                float age = (float) (clock - noticeAt);
                if (age >= 0.0f && age < NOTICE_SECONDS) {
                    float a = Math.min(1.0f, Math.min(age / 0.12f, (NOTICE_SECONDS - age) / 0.5f));
                    g.centeredText(font, noticeText, width / 2, height + 4,
                            Draw.scaleAlpha(0xFFE8F0FF, a));
                }
            }
        } finally {
            g.pose().popMatrix();
        }
    }

    /** Padding inside the vertical panel; its columns sit close together on purpose. */
    private static final int VERTICAL_PAD = 3;
    /** The times run in the smaller type so they fit one narrow column left of the bar. */
    private static final float TIME_SCALE = 0.72f;
    /** Room between the last track column and the first lyric column of a vertical panel. */
    private static final int VERTICAL_SEAM = 1;

    /**
     * Width of the lyric strip: the sung line in one column and its translation in the next.
     * There is no preview column any more (m12239) - a third run read as a second translation.
     * Measured at the lyric scale actually in use: the columns are as wide as the glyphs they
     * hold, so a strip sized at 1.0x would clip the outer column once the lyrics grow.
     */
    private int verticalLyricWidth(float scale) {
        return HudLayout.lyricStep(scale) + HudLayout.columnWidth(scale) + VERTICAL_PAD;
    }

    /**
     * Width of the vertical panel: one column per text run, the times and bar, then lyrics.
     *
     * <p>The cover is not a column - it sits at the top left and the title column runs down
     * beside it - so it has to widen the panel on its own. It used to be left out: with only the
     * title column on, the 44 px square reached 28 px past the panel's own background, which
     * nothing clips (R49-2). "The wider of the two" is enough because the cover and the first
     * column share that space rather than standing side by side.</p>
     */
    private int verticalPanelWidth(SmtcConfig cfg, boolean hasArtist, boolean hasAlbum, int lyricW) {
        int columns = 1;                                     // the title, under the cover
        if (hasArtist) columns++;
        if (hasAlbum) columns++;
        if (cfg.showProgress) columns++;                     // the bar, its times above and below
        int w = VERTICAL_PAD * 2 + HudLayout.columnStep(1.0f) * (columns - 1)
                + HudLayout.columnWidth(1.0f);
        if (cfg.showArtwork) w = Math.max(w, VERTICAL_PAD * 2 + ART);
        return lyricW > 0 ? w + VERTICAL_SEAM + lyricW : w;
    }

    /**
     * Height of the vertical panel: the configured one, or - while it is left at 0 - as tall
     * as the longest run it has to show, within limits. The cover counts as part of the title
     * column, a short title still leaves the lyric strip room to breathe, and a very long one
     * is clipped rather than turned into a wall of text.
     *
     * <p>On auto the lyric strip is what the panel follows: the height is the longest line of
     * the whole song (and its translation), so a long line is never cut off and the panel does
     * not breathe from line to line. Measured once per song and font, then kept - walking a
     * few hundred lines of CJK with a glyph lookup each, every frame, is not free.</p>
     */
    private int verticalPanelHeight(Font font, SmtcConfig cfg, LyricLayout layout, TrackInfo track,
                                    LyricTrack lyrics, boolean onlyLyrics) {
        if (cfg.verticalPanelHeight > 0) {
            return Math.max(SmtcConfig.MIN_VERTICAL_H,
                    Math.min(SmtcConfig.MAX_VERTICAL_H, cfg.verticalPanelHeight));
        }
        LyricsRenderer.Look look = LyricsRenderer.Look.plain(lyricFontId(cfg), cfg.shadow);
        int need = 0;
        if (!onlyLyrics && track.hasTrack()) {
            int top = cfg.showArtwork ? ART + 6 : 0;
            need = Math.max(need, top + LyricsRenderer.verticalHeight(font, track.title(), look, 1.0f));
            need = Math.max(need, LyricsRenderer.verticalHeight(font, track.artist(), look, 1.0f));
            if (cfg.showAlbum) {
                need = Math.max(need, LyricsRenderer.verticalHeight(font, track.album(), look, 1.0f));
            }
        }
        need = Math.max(need, longestLyricHeight(font, cfg, layout, track, lyrics));
        int h = VERTICAL_PAD * 2 + Math.max(need, VERTICAL_MIN_CONTENT);
        return Math.max(VERTICAL_MIN_H, Math.min(VERTICAL_MAX_H, h));
    }

    /** The tallest lyric column of the song: its longest line, or the longest translation. */
    private int longestLyricHeight(Font font, SmtcConfig cfg, LyricLayout layout, TrackInfo track,
                                   LyricTrack lyrics) {
        if (lyrics == null || lyrics.isEmpty()) return 0;
        // the font is part of the measurement, so it is part of the key: without it a font
        // change kept the old height until the song did (R49-3)
        String key = track.dedupeKey() + "|" + lyrics.size() + "|" + layout.lyricScale + "|"
                + layout.transScale + "|" + cfg.shadow + "|" + lyricFontId(cfg);
        if (key.equals(longestKey)) return longestHeight;
        LyricsRenderer.Look look = LyricsRenderer.Look.plain(lyricFontId(cfg), cfg.shadow);
        int tallest = 0;
        for (int i = 0; i < lyrics.size(); i++) {
            LyricLine line = lyrics.line(i);
            if (line == null) continue;
            tallest = Math.max(tallest,
                    LyricsRenderer.verticalHeight(font, line.text(), look, layout.lyricScale));
            if (layout.transH > 0 && line.hasTranslation()) {
                tallest = Math.max(tallest, LyricsRenderer.verticalHeight(font,
                        line.translation(), look, layout.transScale));
            }
        }
        longestKey = key;
        longestHeight = tallest;
        return tallest;
    }

    /**
     * The whole panel as vertical columns: the cover at the top left with the title running
     * down the same column, the artist and album as columns of their own, the two times and
     * the bar beside them, and the lyrics in their columns on the right.
     *
     * <p>Nothing here is an image of rotated text: every run goes through
     * {@link LyricsRenderer#verticalRun}, glyph by glyph, so the font, the colours, the shadow
     * and the karaoke bead are the same ones the horizontal panel uses. Han and kana glyphs
     * are turned a quarter turn; Latin letters and digits stay upright, because a turned 'D'
     * reads as a wedge.</p>
     */
    private void drawVerticalPanel(GuiGraphicsExtractor g, Minecraft mc, Font font, SmtcConfig cfg,
                                   LyricLayout layout, int bandRows, TrackInfo track,
                                   boolean hasTrack, LyricTrack lyrics, float scale, float dt,
                                   String artHash, byte[] artBytes) {
        // "show lyrics" off means no lyric block at all: no strip, no seam, and no "no lyrics
        // yet" line standing in for one (m12261)
        boolean wantLyrics = cfg.showLyrics;
        boolean onlyLyrics = cfg.lyricsOnly && hasTrack && wantLyrics;
        int lyricW = wantLyrics ? verticalLyricWidth(layout.lyricScale) : 0;
        int width = onlyLyrics ? lyricW + VERTICAL_PAD * 2
                : verticalPanelWidth(cfg, !track.artist().isEmpty(),
                        cfg.showAlbum && !track.album().isEmpty(), lyricW);
        int height = verticalPanelHeight(font, cfg, layout, track, lyrics, onlyLyrics);
        float panelScale = scale * autoFit(cfg, g, width, height, scale);
        g.pose().pushMatrix();
        try {
            g.pose().translate(anchorX(cfg, g.guiWidth(), Math.round(width * panelScale)),
                    anchorY(cfg, g.guiHeight(), Math.round(height * panelScale)));
            g.pose().scale(panelScale);
            drawChrome(g, cfg, width, height);
            if (!onlyLyrics) {
                drawVerticalTrack(g, font, cfg, height, track, hasTrack, artHash, artBytes);
            }
            if (wantLyrics) {
                int lyricX = onlyLyrics ? VERTICAL_PAD : width - VERTICAL_PAD - lyricW;
                g.enableScissor(lyricX, VERTICAL_PAD, lyricX + lyricW, height - VERTICAL_PAD);
                try {
                    g.pose().translate(lyricX, VERTICAL_PAD);
                    try {
                        renderLyrics(g, mc, cfg, lyricW, false, HudLayout.CENTRE, bandRows,
                                height - VERTICAL_PAD * 2 - LYRIC_GAP, layout, track, lyrics, dt);
                    } finally {
                        g.pose().translate(-lyricX, -VERTICAL_PAD);
                    }
                } finally {
                    g.disableScissor();
                }
            }
            if (!noticeText.isEmpty()) {
                float age = (float) (clock - noticeAt);
                if (age >= 0.0f && age < NOTICE_SECONDS) {
                    float a = Math.min(1.0f, Math.min(age / 0.12f, (NOTICE_SECONDS - age) / 0.5f));
                    g.centeredText(font, noticeText, width / 2, height + 4,
                            Draw.scaleAlpha(0xFFE8F0FF, a));
                }
            }
        } finally {
            g.pose().popMatrix();
        }
    }

    /**
     * One run down a vertical column, hung from {@code top}: every column of the track block
     * starts at the same line - the bottom edge of the square cover - the way the columns of a
     * printed page share a top edge. Centring them made short runs float in the middle of the
     * panel and long ones start above the cover (m12141).
     *
     * @return how far down the run reached
     */
    private static int verticalColumn(GuiGraphicsExtractor g, Font font, String text, int x,
                                      int top, int bottom, int color, LyricsRenderer.Look look,
                                      float scale, double clock, boolean walk) {
        if (text == null || text.isEmpty() || bottom <= top) return 0;
        // a title longer than its column walks down and back, the way the stacked panel
        // marquees it sideways (m12414); the lyrics are the only run that goes one way.
        // It obeys the same switch as the stacked panel's marquee (R49-1). The walk lifts
        // glyphs past the top edge, so the column is clipped to its own box: enableScissor()
        // applies the current pose to its rect, which here is the panel's design space.
        int runH = LyricsRenderer.verticalHeight(font, text, look, scale);
        int shift = walk ? HudLayout.verticalScrollCycle(clock, runH, bottom - top) : 0;
        g.enableScissor(x, top, x + HudLayout.columnWidth(scale), bottom);
        try {
            return LyricsRenderer.verticalRun(g, font, text, x, top - shift, bottom - top + shift,
                    color, color, 1.0f, look, scale);
        } finally {
            g.disableScissor();
        }
    }

    /** The track columns of a vertical panel: cover, title, artist, album, times, bar. */
    private void drawVerticalTrack(GuiGraphicsExtractor g, Font font, SmtcConfig cfg, int height,
                                   TrackInfo track, boolean hasTrack, String artHash,
                                   byte[] artBytes) {
        int textColor = Draw.color(cfg.textColor, 0xFFFFFFFF);
        int dimColor = Draw.color(cfg.dimTextColor, 0xFFB8BCC4);
        int accent = Draw.color(cfg.accentColor, 0xFF3FDC6E);
        LyricsRenderer.Look look = LyricsRenderer.Look.plain(null, cfg.shadow);
        int pad = VERTICAL_PAD;
        int top = pad;
        int bottom = height - pad;
        int step = HudLayout.columnStep(1.0f);
        int col = pad;
        if (!hasTrack) {
            verticalColumn(g, font, Component.translatable("smtcraft.hud.empty").getString(), col,
                    top, bottom, dimColor, look, 1.0f, clock, false);
            return;
        }
        // one baseline for every column: the bottom edge of the square cover, or the padding
        // when there is no cover to hang from
        int baseline = top;
        if (cfg.showArtwork) {
            paintArt(g, font, cfg, artHash, artBytes, pad, pad);
            baseline = pad + ART + 2;
        }
        boolean walk = cfg.marqueeTrack;
        verticalColumn(g, font, track.title(), col, baseline, bottom, textColor, look, 1.0f,
                clock, walk);
        col += step;
        if (!track.artist().isEmpty()) {
            verticalColumn(g, font, track.artist(), col, baseline, bottom, textColor, look, 1.0f,
                    clock, walk);
            col += step;
        }
        if (cfg.showAlbum && !track.album().isEmpty()) {
            verticalColumn(g, font, track.album(), col, baseline, bottom, dimColor, look, 1.0f,
                    clock, walk);
            col += step;
        }
        if (!cfg.showProgress) return;
        // the bar owns the column and the times cap it: elapsed above the bar, the total
        // below it, both in the smaller type (m12308). Two stacked labels used to sit in a
        // column of their own and pushed the bar right up against the lyrics.
        long elapsed = track.durationMs() > 0L
                ? track.elapsedMs()
                : (cfg.progressFromStart ? track.fallbackElapsedMs() : -1L);
        boolean hasTotal = track.durationMs() > 0L;
        int runE = elapsed >= 0L
                ? LyricsRenderer.verticalHeight(font, Draw.duration(elapsed), look, TIME_SCALE)
                : 0;
        int runT = hasTotal
                ? LyricsRenderer.verticalHeight(font, Draw.duration(track.durationMs()), look,
                        TIME_SCALE)
                : 0;
        // the group hangs from the top of the panel, not from the cover's baseline: the first
        // digit starts level with the top of the cover art and the bar follows under it
        // (m12330). The bottom label keeps a wider gap so it does not look welded to the bar.
        int capGap = 2;
        int footGap = 6;
        int capTop = top;
        int barTop = capTop + (runE > 0 ? runE + capGap : 0);
        int barBottom = bottom - (runT > 0 ? runT + footGap : 0);
        // a stub of a bar says nothing, so a squeezed column drops it to the inner height
        if (barBottom - barTop < 6) {
            barTop = top;
            barBottom = bottom;
        }
        int barX = col + 1;
        g.fill(barX, barTop, barX + 2, barBottom, Draw.withAlpha(0xFFFFFF, 0.16f));
        float p = track.progress();
        if (p > 0.0f) {
            int filled = Math.round((barBottom - barTop) * Math.min(1.0f, p));
            if (filled > 0) g.fill(barX, barTop, barX + 2, barTop + filled, accent);
        }
        if (runE > 0) {
            LyricsRenderer.verticalRun(g, font, Draw.duration(elapsed), col, capTop,
                    barTop - capGap - capTop, dimColor, dimColor, 1.0f, look, TIME_SCALE);
        }
        if (runT > 0 && barBottom + footGap + runT <= bottom) {
            LyricsRenderer.verticalRun(g, font, Draw.duration(track.durationMs()), col,
                    bottom - runT, runT, dimColor, dimColor, 1.0f, look, TIME_SCALE);
        }
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
        // a lyrics-only panel has no title or cover to widen for; the band is centred
        // anyway, so measuring the header would only make the overlay wider than the
        // text it now shows
        if (!cfg.adaptiveWidth || !hasTrack || cfg.lyricsOnly) return base;
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

    /**
     * The plate itself: fill and border only. It is drawn for a lyrics-only panel too, which
     * has no header to paint but still has to sit on something.
     */
    private void drawChrome(GuiGraphicsExtractor g, SmtcConfig cfg, int width, int height) {
        int bg = Draw.withAlpha(0x000000, cfg.backgroundOpacity);
        int border = Draw.withAlpha(0xFFFFFF, cfg.borderOpacity);
        int radius = Math.max(0, Math.min(24, cfg.cornerRadius));
        if (radius > 0) {
            Draw.fillRound(g, 0, 0, width, height, radius, bg);
            Draw.outlineRound(g, 0, 0, width, height, radius, border);
        } else {
            g.fill(0, 0, width, height, bg);
            g.fill(0, 0, width, 1, border);
            g.fill(0, height - 1, width, height, border);
        }
    }

    /**
     * One cover at {@code ax,ay}: the spinning disc when the vinyl is on, the rounded texture
     * otherwise, the note placeholder while the art has not arrived. Split out so a vertical
     * panel can put the same cover in its own corner.
     */
    private void paintArt(GuiGraphicsExtractor g, Font font, SmtcConfig cfg, String artHash,
                          byte[] artBytes, int ax, int ay) {
        int dimColor = Draw.color(cfg.dimTextColor, 0xFFB8BCC4);
        long artStart = System.nanoTime();
        Identifier tex = cfg.vinyl
                ? artwork.texture(artHash, artBytes, true)
                : artwork.textureRounded(artHash, artBytes, cfg.artRound ? cfg.artRadius : 0, ART);
        long artMs = (System.nanoTime() - artStart) / 1_000_000L;
        if (artMs >= 30L) {
            // decoding happens on a background thread, so a slow frame here means
            // the texture upload itself is big: report it once per cover
            org.slf4j.LoggerFactory.getLogger("smtcraft")
                    .warn("slow cover upload: {} ms ({} bytes)", artMs, artBytes == null ? 0 : artBytes.length);
        }
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
            int r = cfg.artRound ? Math.max(0, Math.min(ART / 2, cfg.artRadius)) : 0;
            if (r > 0) {
                Draw.fillRound(g, ax, ay, ART, ART, r, Draw.withAlpha(0xFFFFFF, 0.08f));
            } else {
                g.fill(ax, ay, ax + ART, ay + ART, Draw.withAlpha(0xFFFFFF, 0.08f));
            }
            String note = "\u266B";
            g.text(font, note, ax + ART / 2 - font.width(note) / 2, ay + ART / 2 - 4, dimColor);
        }
    }

    private void renderPanel(GuiGraphicsExtractor g, Minecraft mc, Font font, SmtcConfig cfg,
                             int width, int height, TrackInfo track, boolean hasTrack,
                             float dt, String artHash, byte[] artBytes) {
        int x = 0;
        int y = 0;

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
            int ax = x + PAD;
            int ay = y + PAD;
            paintArt(g, font, cfg, artHash, artBytes, ax, ay);
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
                Math.max(24, textX + titleBox - titleLeft - 6), textColor, look, clock, marquee,
                false);

        int lineY = y + 17;
        String artist = track.artist();
        if (!artist.isEmpty()) {
            drawLine(g, font, artist, textX, lineY, textW, textColor, look, clock, marquee, center);
            lineY += 10;
        }
        if (cfg.showAlbum && !track.album().isEmpty()) {
            drawLine(g, font, track.album(), textX, lineY, textW, dimColor, look, clock, marquee,
                center);
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
                          int color, LyricsRenderer.Look look, double clock, boolean marquee,
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
            // on rows with missing word tags, and wherever the font is narrow (AUDIT-26-5).
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
        // The third of the three push/pop pairs (AUDIT-44-6): the other two were wrapped in
        // AUDIT-35-5 and this one was missed. A throw here would leave the matrix rotated, and
        // every later frame would draw one notch off.
        g.pose().pushMatrix();
        try {
            g.pose().translate(cx, cy);
            g.pose().rotate((float) Math.toRadians(spin));
            g.blit(tex, -half, -half, half, half, 0f, 1f, 0f, 1f);
            // spindle hole, painted in the rotated space so it stays in the middle
            g.fill(-1, -1, 1, 1, 0xCC101010);
        } finally {
            g.pose().popMatrix();
        }
    }

    /**
     * @param separator draw the hairline above the band. A lyrics-only panel has no track
     *                  block to separate and a side-by-side panel draws its own vertical
     *                  seam, so both pass false.
     * @param align     {@link HudLayout#LEFT} or {@link HudLayout#RIGHT} when the column sits
     *                  beside the seam, {@link HudLayout#CENTRE} for a stacked panel
     */
    private void renderLyrics(GuiGraphicsExtractor g, Minecraft mc, SmtcConfig cfg, int width,
                              boolean separator, int align, int rows, int bandHeight,
                              LyricLayout layout, TrackInfo track, LyricTrack lyrics,
                              float dt) {
        boolean vertical = HudLayout.vertical(cfg.hudLayout);
        // drawn in the lyric block's own corner, so the band starts one gap below it; a
        // vertical strip has no separator above it, so its run starts at the very top
        int blockTop = vertical ? 0 : LYRIC_GAP;
        int bandBottom = blockTop + bandHeight;
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
        // The translation has its own colour now, and the slot that previews the next
        // line when there is no translation has a second one: both used to borrow the
        // lyric and dim colours, so a song with a tinted translation could not be told
        // from the row above it.
        int translationColor = Draw.color(cfg.translationColor, lyricColor);
        int candidateColor = Draw.color(cfg.candidateColor, dimColor);

        if (separator) {
            g.fill(PAD, -1, width - PAD, 0, Draw.withAlpha(0xFFFFFF, 0.10f));
        }

        if (lyrics == null || lyrics.isEmpty()) {
            String hint = lyricHint(track);
            if (vertical) {
                // the same column the sung line uses: the hint is not a second layout
                int hintX = 0;
                LyricsRenderer.verticalRun(g, font, hint, hintX, blockTop, bandHeight,
                        Draw.withAlpha(dimColor, 0.70f), Draw.withAlpha(dimColor, 0.70f), 1.0f,
                        look, layout.lyricScale);
                animator.snap(0);
                return;
            }
            int rowY = blockTop + (rows * layout.blockH) / 2 - 4;
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
            // would do it implicitly, but then reset() had no production caller (AUDIT-25-2)
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

        if (vertical) {
            renderVertical(g, font, cfg, width, blockTop, bandBottom, rows, layout, lyrics,
                    position, base, look);
            return;
        }

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
                    LyricsRenderer.smooth(rowY / fade),
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
            // The marquee clock is this row's own age, not the panel's: scrolling is one way
            // now (m12372), so a row that starts at minute three must begin at its first
            // glyph rather than at the tail the global clock would put it on. A line already
            // sung sits at its tail; one still ahead waits at the start.
            float age = inside ? Math.max(0.0f, (position - lineStart) / 1000.0f)
                    : (alreadySung ? 3600.0f : 0.0f);
            String shown = line.text();

            // One metric per row: rows with word timings are driven by the renderer
            // (glyph pixels), the rest by the time fraction below (AUDIT-26-5).
            boolean hasWords = cfg.wordTiming && line.words() != null;
            // A side-by-side column hugs the seam: each row is laid out from the seam edge
            // rather than around the column centre, so the two blocks line up as one table
            // of text. A stacked panel keeps the centred layout it always had.
            int rowX = rowCentre(align, font, shown, look, layout.lyricScale, width, innerW,
                    centerX);
            if (inside) {
                float played = Math.max(0.0f, Math.min(1.0f,
                        (position - lineStart) / (float) Math.max(1L, lineEnd - lineStart)));
                float progress = cfg.karaoke ? played : 1.0f;
                LyricsRenderer.karaokeLine(g, font, shown, rowX, rowY, innerW,
                        Draw.scaleAlpha(karaokeColor, alpha),
                        Draw.scaleAlpha(quietColor, alpha), progress, look,
                        layout.lyricScale, cfg.lyricNoteEase,
                        cfg.karaokePeak, cfg.karaokePeakAmount,
                        cfg.karaokePeakRange, age, marquee,
                        cfg.wordTiming ? line.words() : null, position,
                        rowScroll(font, shown, look, innerW, layout.lyricScale, index, played, dt,
                                hasWords));
            } else if (handover) {
                // the row that was just sung keeps its karaoke layout while it scrolls
                // out, but dims over the same 0.3 s instead of snapping to grey after it
                int gone = Draw.withAlpha(dimColor, 0.45f);
                LyricsRenderer.karaokeLine(g, font, shown, rowX, rowY, innerW,
                        Draw.scaleAlpha(Draw.lerp(karaokeColor, gone, swap), alpha),
                        Draw.scaleAlpha(Draw.lerp(quietColor, gone, swap), alpha), 1.0f, look,
                        layout.lyricScale, false, false, 0.0f, 0, age, marquee,
                        // A row leaving the stage keeps its own offset; only the
                        // "let the renderer follow" case may carry -1, and a word-timed
                        // row is exactly that case. Passing -1 for a row that has no
                        // offset pushed a finished line to its tail (AUDIT-26-7).
                        null, position, hasWords ? -1.0f
                                : (index == handoverRow ? handoverOffset : 0.0f));
            } else {
                int color = alreadySung ? Draw.withAlpha(dimColor, 0.45f) : dimColor;
                LyricsRenderer.marqueeCenteredOnce(g, font, shown, rowX, rowY, innerW,
                        Draw.scaleAlpha(color, alpha), look, layout.lyricScale, age, marquee);
            }

            if (layout.transH <= 0) continue;
            int transY = rowY + layout.lineH;
            if (line.hasTranslation()) {
                int color = inside || handover
                        ? Draw.withAlpha(translationColor, 0.92f)
                        : Draw.withAlpha(translationColor, 0.78f);
                LyricsRenderer.marqueeCenteredOnce(g, font, line.translation(),
                        rowCentre(align, font, line.translation(), look, layout.transScale,
                                width, innerW, centerX), transY,
                        transBudget, Draw.scaleAlpha(color, alpha), look, layout.transScale,
                        age, marquee);
            } else if (dual && index == current) {
                // No translation for this song: the second slot previews the next line,
                // so the reader still gets a two-line view (single-row mode only). It has
                // to hang off "this is the current row", not "this row is being sung" —
                // otherwise the preview only popped up after the line had already begun
                // and the intro/gaps showed a single lonely row.
                LyricLine next = lyrics.line(index + 1);
                if (next != null && !next.text().isEmpty()) {
                    LyricsRenderer.marqueeCentered(g, font, next.text(),
                            rowCentre(align, font, next.text(), look, layout.transScale, width,
                                    innerW, centerX), transY, transBudget,
                            Draw.scaleAlpha(Draw.withAlpha(candidateColor, 0.80f), alpha), look,
                            layout.transScale, 0.0f, false);
                }
            }
        }
    }

    /**
     * The lyrics as vertical columns: the line being sung in the column that hugs the seam,
     * its translation in the column beside it. There is no preview column - a third grey run
     * read as a second translation of the same line (m12239).
     *
     * <p>The run is drawn glyph by glyph, so the karaoke fill and its bead work here too: a
     * glyph is part sung, part unsung as the play head passes through it, and the bead
     * brightens the glyphs around the head (AUDIT-49-1).</p>
     *
     * <p>A column taller than the strip walks down at a steady pace, one way, and stops at its
     * last glyph. It follows the line's own age, not the play head: the stacked panel pins the
     * sung edge at 68% of the band, which a column cannot do - a column has no width to pin it
     * in, and sliding it in step with the singing would leave the glyphs ahead of the voice
     * off-screen for whole bars. Fixed pace is the shape a column allows, so it is what the
     * README documents (R49-6). The marquee switch covers this walk as well (R49-1).</p>
     */
    private void renderVertical(GuiGraphicsExtractor g, Font font, SmtcConfig cfg, int width,
                                int blockTop, int bandBottom, int rows, LyricLayout layout,
                                LyricTrack lyrics, long position, int base,
                                LyricsRenderer.Look look) {
        int height = bandBottom - blockTop;
        if (height <= 0) return;
        int pad = VERTICAL_PAD;
        int lyricColor = Draw.color(cfg.lyricColor, Draw.color(cfg.textColor, 0xFFFFFFFF));
        int karaokeColor = Draw.color(cfg.karaokeColor,
                Draw.color(cfg.accentColor, 0xFF3FDC6E));
        int quietColor = Draw.withAlpha(lyricColor, 0.55f);
        int dimColor = Draw.color(cfg.dimTextColor, 0xFFB8BCC4);
        int translationColor = Draw.color(cfg.translationColor, lyricColor);
        float scale = layout.lyricScale;
        boolean peak = cfg.karaoke && cfg.karaokePeakAmount > 0.01f && cfg.karaokePeakRange > 0;

        LyricLine current = lyrics.line(base);
        if (current != null) {
            long start = current.startMs();
            long end = current.endMs();
            float progress;
            if (end > start) {
                progress = cfg.karaoke
                        ? Math.max(0.0f, Math.min(1.0f,
                                (position - start) / (float) Math.max(1L, end - start)))
                        : (position >= start ? 1.0f : 0.0f);
            } else {
                progress = 1.0f;
            }
            // this line's own age drives the one-way walk, not the panel's clock (m12372),
            // and the lyric marquee switch turns the walk off here exactly as it does in the
            // stacked panel (R49-1)
            float age = Math.max(0.0f, (position - start) / 1000.0f);
            boolean walk = cfg.marqueeLyrics;
            // the columns start at the seam side and run outwards: the line being sung hugs
            // the vertical line that separates the lyrics from the track block, its
            // translation sits one column further out
            int columnStep = HudLayout.lyricStep(scale);
            // the sung column sits flush on the seam: no inner pad, so the glyphs
            // start one pixel after the vertical divider (m12292)
            int sungX = 0;
            int transX = columnStep;
            // a line taller than the strip walks up and down instead of being cut off at the
            // last glyph that happened to fit: a marquee cannot work in a column, but the run
            // can travel on its own axis (m12260)
            int runH = LyricsRenderer.verticalHeight(font, current.text(), look, scale);
            // the run's own age, so a line that arrives late still starts at its first glyph
            int shift = walk ? HudLayout.verticalScroll(age, runH, height) : 0;
            LyricsRenderer.verticalRun(g, font, current.text(), sungX, blockTop - shift,
                    height + shift, karaokeColor, quietColor, progress, look, scale, peak,
                    cfg.karaokePeakAmount, cfg.karaokePeakRange);
            // the translation gets a column of its own: drawn below the run it used to run on
            // into the next line and read as more of the same text
            if (layout.transH > 0 && current.hasTranslation()) {
                if (transX >= pad) {
                    int transRun = LyricsRenderer.verticalHeight(font, current.translation(), look,
                            layout.transScale);
                    int transShift = walk ? HudLayout.verticalScroll(age, transRun, height) : 0;
                    LyricsRenderer.verticalRun(g, font, current.translation(), transX,
                            blockTop - transShift, height + transShift,
                            Draw.withAlpha(translationColor, 0.95f),
                            Draw.withAlpha(translationColor, 0.62f), 1.0f, look,
                            layout.transScale);
                }
            }
        }
        // no preview column: a third grey run read as a second translation of the same line,
        // and the panel only ever needs the line being sung and its translation (m12239)
    }

    /**
     * Where the middle of a row sits when the lyric column hugs the seam. Rows wider than the
     * column keep the centred box: that box is what the marquee scrolls inside, so shifting
     * it would push the run out of the column.
     */
    private static int rowCentre(int align, Font font, String text, LyricsRenderer.Look look,
                                 float scale, int width, int innerW, int fallback) {
        if (align == 0 || text == null || text.isEmpty()) return fallback;
        int textW = Math.round(LyricsRenderer.width(font, text, look) * scale);
        if (textW > innerW) return fallback;
        return HudLayout.textLeft(align, textW, width, PAD) + textW / 2;
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

    /**
     * Frame time measured here, for the versions whose HUD gets no tracker (26.4). Clamped
     * the same way as {@link #deltaSeconds}, so a stalled frame cannot make the marquee jump.
     */
    private float wallStep() {
        long now = System.nanoTime();
        long previous = lastNanos;
        lastNanos = now;
        if (previous == 0L) return 0.05f;
        float d = (now - previous) / 1_000_000_000.0f;
        if (d <= 0.0f || d > 1.0f) return 0.05f;
        return d;
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
