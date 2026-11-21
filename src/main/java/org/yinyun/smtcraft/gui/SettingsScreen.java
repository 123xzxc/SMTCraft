package org.yinyun.smtcraft.gui;

import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.SmtcCraftClient;
import org.yinyun.smtcraft.font.FontPack;
import org.yinyun.smtcraft.render.ArtworkCache;
import org.yinyun.smtcraft.render.Theme;
import org.yinyun.smtcraft.smtc.LyricProvider;
import org.yinyun.smtcraft.smtc.LyricService;
import org.yinyun.smtcraft.media.MediaBridge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

/**
 * In-game settings screen, split into four tabs: 显示 / 歌词 / 主题 / 字体.
 *
 * <p>Every control writes straight into the config object and saves it, so the
 * HUD updates on the next frame and the JSON on disk stays authoritative.</p>
 */
public final class SettingsScreen extends Screen {
    private static final String[] ANCHORS = {"top-left", "top-right", "bottom-left", "bottom-right", "center"};
    private static final String[] FONT_MODES = {"default", "unicode", "custom", "system"};
    private static final String[][] TABS = {
            {"smtcraft.tab.display", "display"},
            {"smtcraft.tab.lyrics", "lyrics"},
            {"smtcraft.tab.theme", "theme"},
            {"smtcraft.tab.fonts", "fonts"}};

    private static final int TAB = 4;

    private final SmtcCraftClient mod;
    private final SmtcConfig cfg;
    /** Screen to return to (the Mod Menu list) - null means back to the game. */
    private final Screen parent;

    private final List<ToggleRow> toggles = new ArrayList<>();
    private final List<TabButton> tabButtons = new ArrayList<>();
    private final List<ColorButton> colorButtons = new ArrayList<>();
    private final List<Caption> captions = new ArrayList<>();
    private final List<SlotButton> slots = new ArrayList<>();
    private final List<SlotPreview> slotPreviews = new ArrayList<>();
    private Button anchorButton;
    private Button fontModeButton;
    private Button sourceButton;
    private Button themeButton;
    private Button clearButton;
    private int clearedFiles = -1;
    private long clearedAt;
    /** True when the last clear only dropped the song on screen. */
    private boolean clearedOne;
    private EditBox fontIdBox;
    private int tab;
    private int contentLeft;
    private int contentWidth;
    private boolean building;
    /** Slider drags coalesce their disk writes (C5). */
    private boolean savePending;
    private long saveAt;
    /** Last time the per-slot font previews re-checked the generated pack (C6). */
    private long slotRefreshAt;
    /** Layout as built, so a wheel scroll can be applied and taken back (C1). */
    private final Map<AbstractWidget, Integer> baseY = new IdentityHashMap<>();
    private final List<Caption> baseCaptions = new ArrayList<>();
    private final List<SlotPreview> basePreviews = new ArrayList<>();
    private int scroll;
    private int maxScroll;
    /** True while the scrollbar thumb is being dragged (R7-5). */
    private boolean draggingBar;
    /** Right-edge gutter, so the bar cannot cover the widest control's right end. */
    private static final int BAR_GUTTER = 6;
    private static final int BAR_W = 3;

    public SettingsScreen(SmtcCraftClient mod) {
        this(mod, null);
    }

    public SettingsScreen(SmtcCraftClient mod, Screen parent) {
        super(Component.translatable("smtcraft.settings.title"));
        this.mod = mod;
        this.cfg = mod != null && mod.config() != null ? mod.config() : new SmtcConfig();
        this.parent = parent;
    }

    @Override
    protected void init() {
        building = true;
        toggles.clear();
        tabButtons.clear();
        colorButtons.clear();
        captions.clear();
        slots.clear();
        slotPreviews.clear();
        baseY.clear();
        baseCaptions.clear();
        basePreviews.clear();
        scroll = 0;
        // Minecraft calls init() again on the same instance when the window
        // resizes; a release event that lands elsewhere would otherwise leave the
        // page stuck in "dragging" and swallow every later drag (R8-4).
        draggingBar = false;
        anchorButton = null;
        fontModeButton = null;
        themeButton = null;
        fontIdBox = null;

        contentWidth = Math.min(this.width - 24, 340);
        contentLeft = (this.width - contentWidth) / 2;

        final int gap = 6;
        final int colW = (contentWidth - gap) / 2;
        final int x0 = contentLeft;
        final int x1 = contentLeft + colW + gap;

        // --- tab selector ----------------------------------------------------
        final int tabW = (contentWidth - (TAB - 1) * gap) / TAB;
        final int tabY = 20;
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            Button button = Button.builder(Component.translatable(TABS[i][0]), b -> {
                tab = index;
                rebuildWidgets();
            }).bounds(contentLeft + i * (tabW + gap), tabY, tabW, 18).build();
            tabButtons.add(new TabButton(button, index));
            addRenderableWidget(button);
        }

        switch (this.tab) {
            case 1 -> layoutLyricsTab(x0, x1, colW, gap);
            case 2 -> layoutThemeTab(x0, x1, colW, gap);
            case 3 -> layoutFontsTab(x0, colW, gap);
            default -> layoutDisplayTab(x0, x1, colW, gap);
        }

        refresh();
        captureLayout();
        building = false;
    }

    /** Smallest row pitch the 9 px font still fits in, header and footer included. */
    private static final int MIN_STEP = 13;
    /** Content starts under the tab row and ends above the status line. */
    private static final int CONTENT_TOP = 40;
    private static final int CONTENT_BOTTOM_MARGIN = 22;

    /** Last-resort row pitch: cramped, but still clickable. */
    private static final int MIN_TIGHT_STEP = 10;

    /**
     * Row geometry that fits {@code rows} rows into the window.
     *
     * <p>The old version kept a 20 px floor and only gave up below it, so at GUI
     * scale 4 or in a small window the last rows were pushed off the screen with
     * no way to reach them (C1). There is nothing below 13 px that still fits the
     * font, and every tab is laid out to fit above that floor.</p>
     */
    private int step(int rows, int max) {
        int available = Math.max(0, this.height - contentTop() - contentBottom());
        int fit = available / Math.max(1, rows);
        int chosen = Math.min(max, fit);
        // 768p at GUI scale 4 leaves 192 px, and fourteen rows at the 13 px floor need
        // 244 px. Squeezing the margins and allowing a 10 px row keeps every row on
        // screen; a scrolling container would be nicer but this is a two-line fix (C1).
        return chosen >= MIN_STEP ? chosen : Math.max(MIN_TIGHT_STEP, chosen);
    }

    /**
     * Records the geometry each tab was laid out with and how far it overflows.
     *
     * <p>Rows are laid out top-down at build time, which is why a window too short
     * for a tab used to lose its last controls instead of scrolling: there was
     * nothing to move. Keeping the baseline lets the wheel shift everything while
     * the tab row and the footer stay put (C1).</p>
     */
    private void captureLayout() {
        baseY.clear();
        baseCaptions.clear();
        basePreviews.clear();
        baseCaptions.addAll(captions);
        basePreviews.addAll(slotPreviews);
        int top = contentTop();
        int lowest = top;
        for (GuiEventListener child : children()) {
            if (!(child instanceof AbstractWidget widget)) continue;
            if (widget.getY() < top) continue;
            baseY.put(widget, widget.getY());
            lowest = Math.max(lowest, widget.getY() + widget.getHeight());
        }
        maxScroll = Math.max(0, lowest + 8 - (this.height - contentBottom()));
        scroll = 0;
        applyScroll();
    }

    /** Re-applies the current scroll offset to every scrollable element. */
    private void applyScroll() {
        int top = contentTop();
        int bottom = this.height - contentBottom();
        for (Map.Entry<AbstractWidget, Integer> entry : baseY.entrySet()) {
            AbstractWidget widget = entry.getKey();
            int y = entry.getValue() - scroll;
            widget.setY(y);
            // hidden, not merely covered: a widget scrolled under the tab row would
            // otherwise paint over it and still take clicks
            widget.visible = y + widget.getHeight() > top - 2 && y < bottom + 2;
        }
        captions.clear();
        for (Caption caption : baseCaptions) {
            captions.add(new Caption(caption.x, caption.y - scroll, caption.text));
        }
        slotPreviews.clear();
        for (SlotPreview preview : basePreviews) {
            slotPreviews.add(new SlotPreview(preview.slot, preview.sample, preview.x,
                    preview.y - scroll, preview.w, preview.h, preview.ready));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll <= 0 || scrollY == 0.0d) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int step = (int) Math.round(scrollY * 14.0d);
        if (step == 0) step = scrollY > 0 ? 1 : -1;
        int next = Math.max(0, Math.min(maxScroll, scroll - step));
        if (next != scroll) {
            scroll = next;
            applyScroll();
        }
        return true;
    }

    /** Content starts under the tab row; short windows give up a little of that gap. */
    private int contentTop() {
        return this.height >= 200 ? CONTENT_TOP : 32;
    }

    private int contentBottom() {
        return this.height >= 200 ? CONTENT_BOTTOM_MARGIN : 6;
    }

    private int rowH(int step) {
        return Math.min(20, Math.max(9, step - 3));
    }

    private void layoutDisplayTab(int x0, int x1, int colW, int gap) {
        int step = step(14, 26);
        int rowH = rowH(step);
        int y = contentTop();

        addToggle("smtcraft.opt.enabled", () -> cfg.enabled, v -> cfg.enabled = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.artwork", () -> cfg.showArtwork, v -> cfg.showArtwork = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.vinyl", () -> cfg.vinyl, v -> cfg.vinyl = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.album", () -> cfg.showAlbum, v -> cfg.showAlbum = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.progress", () -> cfg.showProgress, v -> cfg.showProgress = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.progress_smooth", () -> cfg.progressSmooth,
                v -> cfg.progressSmooth = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.progress_start", () -> cfg.progressFromStart,
                v -> cfg.progressFromStart = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.source", () -> cfg.showSourceApp, v -> cfg.showSourceApp = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.ignore_browser", () -> cfg.ignoreBrowserSessions,
                v -> cfg.ignoreBrowserSessions = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.adaptive_width", () -> cfg.adaptiveWidth,
                v -> cfg.adaptiveWidth = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.marquee_track", () -> cfg.marqueeTrack,
                v -> cfg.marqueeTrack = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.center_no_art", () -> cfg.centerTextWithoutArtwork,
                v -> cfg.centerTextWithoutArtwork = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.hide_paused", () -> cfg.hideWhenPaused, v -> cfg.hideWhenPaused = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.hide_empty", () -> cfg.hideWhenNoSession, v -> cfg.hideWhenNoSession = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.mute_music", () -> cfg.muteGameMusic, v -> cfg.muteGameMusic = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.shadow", () -> cfg.shadow, v -> cfg.shadow = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.auto_scale", () -> cfg.autoScale,
                v -> cfg.autoScale = v, x0, y, x1 + colW - x0, rowH);
        y += step;
        addSlider(x0, y, colW, rowH, 0.5d, 3.0d, cfg.scale,
                v -> cfg.scale = (float) v,
                v -> Component.translatable("smtcraft.opt.scale", String.format("%.2fx", v)));
        addSlider(x1, y, colW, rowH, 0.0d, 1.0d, cfg.backgroundOpacity,
                v -> cfg.backgroundOpacity = (float) v,
                v -> Component.translatable("smtcraft.opt.opacity", Math.round(v * 100) + "%"));
        y += step;
        addSlider(x0, y, colW, rowH, 120d, 420d, cfg.panelWidth,
                v -> cfg.panelWidth = (int) Math.round(v),
                v -> Component.translatable("smtcraft.opt.width", (int) Math.round(v)));
        addSlider(x1, y, colW, rowH, 0d, 24d, cfg.cornerRadius,
                v -> cfg.cornerRadius = (int) Math.round(v),
                v -> Component.translatable("smtcraft.opt.radius", (int) Math.round(v)));
        y += step;
        addSlider(x0, y, colW, rowH, 0d, 400d, cfg.offsetX,
                v -> cfg.offsetX = (int) Math.round(v),
                v -> Component.translatable("smtcraft.opt.offset_x", (int) Math.round(v)));
        addSlider(x1, y, colW, rowH, 0d, 400d, cfg.offsetY,
                v -> cfg.offsetY = (int) Math.round(v),
                v -> Component.translatable("smtcraft.opt.offset_y", (int) Math.round(v)));
        y += step;
        addSlider(x0, y, colW, rowH, 0d, 720d, cfg.vinylSpinSpeed,
                v -> {
                    cfg.vinylSpinSpeed = (float) v;
                    if (!cfg.vinyl) cfg.vinyl = true;
                },
                v -> Component.translatable("smtcraft.opt.vinyl_speed", Math.round(v) + "\u00B0/s"));
        addSlider(x1, y, colW, rowH, 60d, 2000d, cfg.pollIntervalMs,
                v -> {
                    cfg.pollIntervalMs = (int) Math.round(v);
                    if (mod != null && mod.bridge() != null) mod.bridge().reconfigure();
                },
                v -> Component.translatable("smtcraft.opt.interval", (int) Math.round(v)));
        y += step;
        addSlider(x0, y, colW, rowH, 0d, 3000d, cfg.swapGraceMs,
                v -> cfg.swapGraceMs = (int) Math.round(v),
                v -> Component.translatable("smtcraft.opt.swap_grace", (int) Math.round(v)));

        anchorButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            cfg.anchor = nextAnchor(cfg.anchor);
            cfg.save();
            refresh();
        }).bounds(x1, y, colW, rowH).build());
        y += step;

        // clear this song / clear all / reset / done share one row
        int quarter = (contentWidth - 3 * gap) / 4;
        addRenderableWidget(Button.builder(Component.translatable("smtcraft.opt.clear_current"),
                b -> {
                    if (mod != null && mod.lyrics() != null) {
                        // the delete runs on the I/O lane; the count arrives on this thread
                        mod.lyrics().clearCurrentAsync(removed -> {
                            clearedFiles = removed ? 1 : 0;
                            clearedOne = true;
                            clearedAt = System.currentTimeMillis();
                            refresh();
                        });
                    }
                    clearedFiles = -1;
                    clearedOne = true;
                    clearedAt = System.currentTimeMillis();
                    refresh();
                }).bounds(x0, y, quarter, rowH).build());
        clearButton = addRenderableWidget(Button.builder(Component.translatable("smtcraft.opt.clear_cache"),
                b -> {
                    ArtworkCache.clearAll();
                    if (mod != null && mod.lyrics() != null) {
                        mod.lyrics().clearCacheAsync(removed -> {
                            clearedFiles = removed;
                            clearedOne = false;
                            clearedAt = System.currentTimeMillis();
                            refresh();
                        });
                    }
                    clearedFiles = -1;
                    clearedOne = false;
                    clearedAt = System.currentTimeMillis();
                    refresh();
                }).bounds(x0 + quarter + gap, y, quarter, rowH).build());
        addRenderableWidget(Button.builder(Component.translatable("smtcraft.opt.reset"), b -> {
            SmtcConfig defaults = new SmtcConfig();
            cfg.copyFrom(defaults, "anchor");
            cfg.save();
            FontPack.invalidate();
            rebuildWidgets();
        }).bounds(x0 + 2 * (quarter + gap), y, quarter, rowH).build());
        addRenderableWidget(Button.builder(Component.translatable("smtcraft.done"), b -> onClose())
                .bounds(x0 + 3 * (quarter + gap), y, quarter, rowH).build());
    }

    private void layoutLyricsTab(int x0, int x1, int colW, int gap) {
        int step = step(12, 26);
        int rowH = rowH(step);
        int y = contentTop();

        addToggle("smtcraft.opt.lyrics", () -> cfg.showLyrics, v -> cfg.showLyrics = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.translation", () -> cfg.showTranslation, v -> cfg.showTranslation = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.karaoke", () -> cfg.karaoke, v -> cfg.karaoke = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.karaoke_peak", () -> cfg.karaokePeak,
                v -> cfg.karaokePeak = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.lyric_bold", () -> cfg.lyricBold, v -> cfg.lyricBold = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.lyric_italic", () -> cfg.lyricItalic, v -> cfg.lyricItalic = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.note_ease", () -> cfg.lyricNoteEase,
                v -> cfg.lyricNoteEase = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.word_timing", () -> cfg.wordTiming,
                v -> cfg.wordTiming = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.marquee_lyrics", () -> cfg.marqueeLyrics,
                v -> cfg.marqueeLyrics = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.dual_line", () -> cfg.dualLineWhenNoTranslation,
                v -> cfg.dualLineWhenNoTranslation = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.show_reading", () -> cfg.showReading, v -> {
            cfg.showReading = v;
            if (mod != null) mod.applyReadingMode();
        }, x0, y, colW, rowH);
        y += step;
        // the lyric source lives on its own row: it used to share a row with the
        // line-count slider and ended up hidden underneath it
        sourceButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            cfg.lyricOrder = nextSourceOrder(cfg.lyricOrder);
            cfg.save();
            refresh();
        }).bounds(x0, y, colW, rowH).build());
        addSlider(x1, y, colW, rowH, 1d, 4d, cfg.lyricLines,
                v -> cfg.lyricLines = (int) Math.round(v),
                v -> Component.translatable("smtcraft.opt.lyric_lines", (int) Math.round(v)));
        y += step;
        addSlider(x0, y, colW / 2 - 3, rowH, 0.6d, 2.0d, cfg.lyricFontSize,
                v -> cfg.lyricFontSize = (float) v,
                v -> Component.translatable("smtcraft.opt.lyric_size", String.format("%.2fx", v)));
        addSlider(x0 + colW / 2 + 3, y, colW / 2 - 3, rowH, 0.6d, 2.0d, cfg.translationFontSize,
                v -> cfg.translationFontSize = (float) v,
                v -> Component.translatable("smtcraft.opt.trans_size", String.format("%.2fx", v)));
        addSlider(x1, y, colW, rowH, 0d, 0.8d, cfg.karaokePeakAmount,
                v -> cfg.karaokePeakAmount = (float) v,
                v -> Component.translatable("smtcraft.opt.peak_amount", String.format("%.2f", v)));
        y += step;
        addSlider(x0, y, colW, rowH, 4d, 80d, cfg.karaokePeakRange,
                v -> cfg.karaokePeakRange = (int) Math.round(v),
                v -> Component.translatable("smtcraft.opt.peak_range", (int) Math.round(v)));
        addSlider(x1, y, colW, rowH, -3000d, 3000d, cfg.lyricOffsetMs,
                v -> cfg.lyricOffsetMs = (int) Math.round(v),
                v -> Component.translatable("smtcraft.opt.lyric_offset", (int) Math.round(v)));
    }

    private void layoutThemeTab(int x0, int x1, int colW, int gap) {
        // colour rows carry a caption line above them, so they need more room
        int step = step(8, 34);
        int rowH = 16;
        int y = contentTop() + 8;

        themeButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            // Picking a preset means "give me that palette": writing it while the
            // hand-picked colours stay behind produced a mix that still called
            // itself custom. The whole palette goes on, the marks are dropped.
            Theme.reset(Theme.get(Theme.nextId(cfg.theme)), cfg);
            cfg.save();
            rebuildWidgets();
        }).bounds(x0, y, contentWidth, rowH).build());
        y += step;

        addColorRow(x0, y, colW, rowH, "smtcraft.opt.accent", "accentColor",
                () -> cfg.accentColor, v -> cfg.accentColor = v);
        addColorRow(x1, y, colW, rowH, "smtcraft.opt.text_color", "textColor",
                () -> cfg.textColor, v -> cfg.textColor = v);
        y += step;
        addColorRow(x0, y, colW, rowH, "smtcraft.opt.dim_color", "dimTextColor",
                () -> cfg.dimTextColor, v -> cfg.dimTextColor = v);
        addColorRow(x1, y, colW, rowH, "smtcraft.opt.lyric_color", "lyricColor",
                () -> cfg.lyricColor, v -> cfg.lyricColor = v);
        y += step;
        addColorRow(x0, y, colW, rowH, "smtcraft.opt.karaoke_color", "karaokeColor",
                () -> cfg.karaokeColor, v -> cfg.karaokeColor = v);
        y += step;

        addRenderableWidget(Button.builder(Component.translatable("smtcraft.opt.theme_reset"), b -> {
            // an explicit reset is the one place that may overwrite custom colours
            Theme.reset(Theme.get(cfg.theme), cfg);
            cfg.save();
            rebuildWidgets();
        }).bounds(x0, y, contentWidth, rowH).build());
    }

    private void layoutFontsTab(int x0, int colW, int gap) {
        int step = step(9, 30);
        int rowH = rowH(step);
        int previewW = 108;
        int btnW = Math.max(90, contentWidth - previewW - gap);
        int y = contentTop();

        // the mode comes first: the four slots below only apply in "system" mode
        fontModeButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            cfg.lyricFontMode = nextFontMode(cfg.lyricFontMode);
            cfg.save();
            refresh();
        }).bounds(x0, y, contentWidth, rowH).build());
        y += step;

        addFontSlot("smtcraft.font_slot.latin", "latin", "Aa Bb 123", () -> cfg.fontLatin,
                v -> cfg.fontLatin = v, x0, y, btnW, rowH, x0 + btnW + gap, previewW);
        y += step;
        addFontSlot("smtcraft.font_slot.cjk", "cjk", "\u6C38\u548C\u4E5D\u5E74", () -> cfg.fontCjk,
                v -> cfg.fontCjk = v, x0, y, btnW, rowH, x0 + btnW + gap, previewW);
        y += step;
        addFontSlot("smtcraft.font_slot.japanese", "japanese", "\u3042\u30A2\u6F22\u5B57",
                () -> cfg.fontJapanese, v -> cfg.fontJapanese = v, x0, y, btnW, rowH,
                x0 + btnW + gap, previewW);
        y += step;
        addFontSlot("smtcraft.font_slot.other", "other", "\u03A9 \u0436 \u2211 \u01C4",
                () -> cfg.fontOther, v -> cfg.fontOther = v, x0, y, btnW, rowH,
                x0 + btnW + gap, previewW);
        y += step;

        int third = (contentWidth - 2 * gap) / 3;
        addRenderableWidget(Button.builder(Component.translatable("smtcraft.fonts.rebuild"), b -> {
            cfg.lyricFontMode = "system";
            cfg.save();
            FontPack.invalidate();
            if (mod != null) mod.applyFonts();
            rebuildWidgets();
        }).bounds(x0, y, third, rowH).build());
        addRenderableWidget(Button.builder(Component.translatable("smtcraft.fonts.clear"), b -> {
            cfg.fontLatin = "";
            cfg.fontCjk = "";
            cfg.fontJapanese = "";
            cfg.fontOther = "";
            if ("system".equals(cfg.lyricFontMode)) cfg.lyricFontMode = "default";
            cfg.save();
            FontPack.invalidate();
            rebuildWidgets();
        }).bounds(x0 + third + gap, y, third, rowH).build());
        addRenderableWidget(Button.builder(Component.translatable("smtcraft.done"), b -> onClose())
                .bounds(x0 + 2 * (third + gap), y, third, rowH).build());
    }

    /**
     * A language slot: the button shows the chosen file and opens the installed-font
     * list, and the space to its right previews the slot in its own font.
     */
    private void addFontSlot(String labelKey, String slot, String sample,
                             Supplier<String> getter, Consumer<String> setter,
                             int x, int y, int w, int h, int previewX, int previewW) {
        Button button = addRenderableWidget(Button.builder(Component.empty(), b -> {
            if (this.minecraft == null) return;
            this.minecraft.setScreenAndShow(new FontPickerScreen(this, labelKey, path -> {
                setter.accept(path == null ? "" : path);
                cfg.save();
                FontPack.invalidate();
                if ("system".equals(cfg.lyricFontMode) && mod != null) mod.applyFonts();
            }));
        }).bounds(x, y, w, h).build());
        slots.add(new SlotButton(button, labelKey, getter));
        // readiness is resolved once per screen build: a per-frame Files.isReadable
        // check four times a frame is pure waste
        boolean ready = FontPack.hasSlot(slot) && FontPack.isApplied(cfg);
        slotPreviews.add(new SlotPreview(slot, sample, previewX, y, previewW, h, ready));
    }

    /**
     * A colour row: read-only hex box plus a swatch button that opens the
     * {@link ColorPickerScreen}; the picker writes through the same setter. A caption
     * above the row names exactly which colour it edits.
     */
    private void addColorRow(int x, int y, int w, int h, String labelKey, String key,
                             Supplier<String> getter, Consumer<String> setter) {
        int btnW = 22;
        EditBox box = addRenderableWidget(new EditBox(this.font, x, y, w - btnW - 2, h,
                Component.translatable(labelKey)));
        box.setMaxLength(9);
        box.setValue(getter.get() == null ? "" : getter.get());
        box.setHint(Component.translatable("smtcraft.opt.color.hint"));
        box.setResponder(text -> {
            if (building) return;
            String trimmed = text.trim();
            if (!trimmed.matches("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")) return;
            setter.accept(trimmed);
            cfg.save();
        });

        Button pick = addRenderableWidget(Button.builder(Component.literal("\u25CF"), b -> {
            if (this.minecraft == null) return;
            this.minecraft.setScreenAndShow(new ColorPickerScreen(this, labelKey, getter.get(), value -> {
                setter.accept(value);
                box.setValue(value);
                cfg.markCustom(key);
                cfg.save();
            }));
        }).bounds(x + w - btnW, y, btnW, h).build());
        colorButtons.add(new ColorButton(pick, key, getter));
        captions.add(new Caption(x, y - 9, Component.translatable(labelKey)));
    }

    /** Persist shortly after the last change instead of on every drag frame (C5). */
    private void saveSoon() {
        savePending = true;
        saveAt = System.currentTimeMillis() + 400L;
    }

    /**
     * File name of a configured font path. The value comes from a config file a
     * user can hand-edit, so a string that is not a legal path must not throw
     * while the screen is being built (A6).
     */
    private static String fileName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        try {
            Path name = Path.of(trimmed).getFileName();
            return name == null ? trimmed : name.toString();
        } catch (java.nio.file.InvalidPathException notAPath) {
            return trimmed;
        }
    }

    /**
     * Translation key for a config problem category (R19-3). Written without a
     * {@code default} on purpose: a new {@link SmtcConfig.Problem} should stop the
     * compile, not quietly render as "save failed" (R20 doc note). {@code NONE} is
     * listed so the switch stays exhaustive; the footer never prints it.
     */
    private static String problemKey(SmtcConfig.Problem problem) {
        return switch (problem) {
            case RELOAD_FAILED -> "smtcraft.settings.save.reload";
            case PARSE_FAILED -> "smtcraft.settings.save.broken";
            case SAVE_FAILED, NO_PARENT, NONE -> "smtcraft.settings.save.failed";
        };
    }

    private static String nextAnchor(String current) {
        for (int i = 0; i < ANCHORS.length; i++) {
            if (ANCHORS[i].equals(current)) return ANCHORS[(i + 1) % ANCHORS.length];
        }
        return ANCHORS[0];
    }

    private static String nextFontMode(String current) {
        for (int i = 0; i < FONT_MODES.length; i++) {
            if (FONT_MODES[i].equals(current)) return FONT_MODES[(i + 1) % FONT_MODES.length];
        }
        return FONT_MODES[0];
    }

    private void addToggle(String key, BooleanSupplier get, Consumer<Boolean> set, int x, int y, int w, int h) {
        Button button = Button.builder(Component.empty(), b -> {
            set.accept(!get.getAsBoolean());
            cfg.save();
            refresh();
        }).bounds(x, y, w, h).build();
        toggles.add(new ToggleRow(button, Component.translatable(key), get));
        addRenderableWidget(button);
    }

    private void addSlider(int x, int y, int w, int h, double min, double max, double value,
                           DoubleConsumer setter, DoubleFunction<Component> label) {
        addRenderableWidget(new Slider(x, y, w, h, min, max, value, v -> {
            setter.accept(v);
            saveSoon();
        }, label));
    }

    /**
     * Provider orders the button cycles through.
     *
     * <p>Generated from {@link LyricProvider#KNOWN} instead of written out: the
     * hand-kept list drifted out of sync with the providers (R3-3), and adding a
     * source should not require remembering this table (C7). The default comes
     * first, then every permutation, then each provider on its own for the case
     * where two of the three are unreachable.</p>
     */
    private static final String[] SOURCE_ORDERS = buildOrders();

    private static String[] buildOrders() {
        List<String> orders = new ArrayList<>();
        orders.add(LyricProvider.DEFAULT_ORDER);
        // every ordered subset, not just the full permutation plus the single
        // sources: with a fourth provider the old table could not reach an order
        // that leaves one source out, which is exactly the fallback a user wants
        // when one provider is unreliable (C7 / R6-9)
        List<String> known = new ArrayList<>(LyricProvider.KNOWN);
        for (int size = 1; size <= known.size(); size++) {
            collectOrders(known, new ArrayList<>(), size, orders);
        }
        return orders.toArray(new String[0]);
    }

    private static void collectOrders(List<String> pool, List<String> prefix, int size,
                                      List<String> orders) {
        if (prefix.size() == size) {
            String order = String.join(",", prefix);
            if (!orders.contains(order)) orders.add(order);
            return;
        }
        for (String id : pool) {
            if (prefix.contains(id)) continue;
            prefix.add(id);
            collectOrders(pool, prefix, size, orders);
            prefix.remove(prefix.size() - 1);
        }
    }

    private static String nextSourceOrder(String current) {
        String value = current == null ? "" : current.trim();
        for (int i = 0; i < SOURCE_ORDERS.length; i++) {
            if (SOURCE_ORDERS[i].equalsIgnoreCase(value)) {
                return SOURCE_ORDERS[(i + 1) % SOURCE_ORDERS.length];
            }
        }
        return SOURCE_ORDERS[0];
    }

    private static Component sourceNames(String order) {
        Component joined = Component.empty();
        if (order == null || order.isBlank()) return joined;
        boolean first = true;
        for (String raw : order.split(",")) {
            String id = raw.trim();
            if (id.isEmpty()) continue;
            if (!first) joined = joined.copy().append(Component.literal(" \u203A "));
            joined = joined.copy().append(Component.translatable("smtcraft.source." + id));
            first = false;
        }
        return joined;
    }

    private void refresh() {
        for (ToggleRow row : toggles) {
            row.button.setMessage(row.label.copy()
                    .append(": ")
                    .append(Component.translatable(row.value.getAsBoolean() ? "smtcraft.on" : "smtcraft.off")));
        }
        if (anchorButton != null) {
            anchorButton.setMessage(Component.translatable("smtcraft.opt.anchor",
                    Component.translatable("smtcraft.anchor." + cfg.anchor.replace('-', '_'))));
        }
        if (fontModeButton != null) {
            fontModeButton.setMessage(Component.translatable("smtcraft.opt.font_mode",
                    Component.translatable("smtcraft.font." + (cfg.lyricFontMode == null ? "default" : cfg.lyricFontMode))));
        }
        if (sourceButton != null) {
            sourceButton.setMessage(Component.translatable("smtcraft.opt.lyric_source",
                    sourceNames(cfg.lyricOrder)));
        }
        if (clearButton != null) {
            boolean fresh = clearedFiles >= 0 && System.currentTimeMillis() - clearedAt < 4000L;
            clearButton.setMessage(fresh
                    ? Component.translatable(clearedOne
                            ? "smtcraft.opt.clear_current.done"
                            : "smtcraft.opt.clear_cache.done", clearedFiles)
                    : Component.translatable("smtcraft.opt.clear_cache"));
        }
        if (themeButton != null) {
            // A palette that no longer follows a preset must not claim to be one:
            // the moment any colour is hand-picked the theme reads as custom, and
            // picking a preset again (or 重置主题) puts the preset name back.
            Component name = cfg.anyCustom()
                    ? Component.translatable("smtcraft.theme.custom")
                    : Component.translatable("smtcraft.theme." + Theme.get(cfg.theme).id());
            themeButton.setMessage(Component.translatable("smtcraft.opt.theme", name));
        }
        for (ColorButton color : colorButtons) {
            String hex = color.getter.get();
            // a hand-picked colour is marked, because themes no longer touch it
            String mark = cfg.isCustom(color.key) ? "\u2726 " : "";
            color.button.setMessage(Component.literal(mark + (hex == null || hex.isEmpty() ? "\u25CF" : hex)));
        }
        for (SlotButton slot : slots) {
            String path = slot.getter.get();
            Component value = SmtcConfig.blank(path)
                    ? Component.translatable("smtcraft.fonts.none")
                    : Component.literal(fileName(path));
            slot.button.setMessage(Component.translatable(slot.labelKey).copy()
                    .append(": ")
                    .append(value));
        }
        for (TabButton button : tabButtons) {
            button.button().active = button.index() != this.tab;
        }
    }

    /**
     * Thin track in the right-edge gutter while the tab overflows.
     *
     * <p>The container works without it, but nothing on screen says there is more
     * below the fold, so nobody ever scrolls (R6-9). Two things follow from
     * comparing it to a real scrollbar: it lives in a gutter instead of on the
     * last two pixels of the content, and the thumb can be dragged — a handle that
     * looks draggable but ignores the mouse reads as a frozen screen (R7-5).</p>
     */
    private void drawScrollBar(GuiGraphicsExtractor g) {
        int span = barSpan();
        if (span <= 8) return;
        int x = barX();
        g.fill(x, barTop(), x + BAR_W, barBottom(), 0x33FFFFFF);
        int top = Math.round(barThumbTop(span));
        g.fill(x, top, x + BAR_W, top + barView(span), draggingBar ? 0xFFFFFFFF : 0xAAFFFFFF);
    }

    /** Track geometry, shared by the drawing and the hit test so they cannot drift. */
    private int barX() {
        return this.width - BAR_GUTTER;
    }

    private int barTop() {
        return contentTop();
    }

    private int barBottom() {
        return this.height - contentBottom();
    }

    private int barSpan() {
        return maxScroll <= 0 ? 0 : barBottom() - barTop();
    }

    private int barView(int span) {
        return Math.max(8, span * span / (span + maxScroll));
    }

    /** Top of the thumb in screen coordinates, fractional so the drag is smooth. */
    private float barThumbTop(int span) {
        int travel = Math.max(0, span - barView(span));
        return (float) (barTop() + travel * (scroll / (double) maxScroll));
    }

    private boolean overScrollBar(double mouseX, double mouseY) {
        int span = barSpan();
        if (span <= 8) return false;
        double x = barX();
        double top = barThumbTop(span);
        return mouseX >= x - 2 && mouseX <= x + BAR_W + 2
                && mouseY >= top - 2 && mouseY <= top + barView(span) + 2;
    }

    /** The whole bar, not just the thumb: clicking the empty track jumps a page. */
    private boolean overScrollBarTrack(double mouseX, double mouseY) {
        if (barSpan() <= 8) return false;
        double x = barX();
        return mouseX >= x - 2 && mouseX <= x + BAR_W + 2
                && mouseY >= barTop() - 2 && mouseY <= barBottom() + 2;
    }

    /** One page up or down, decided by which side of the thumb was clicked (R8-3). */
    private void pageScroll(double mouseY) {
        int span = barSpan();
        if (span <= 8) return;
        // one page is one viewport of content. barView() is thumb height on the
        // bar, which shrinks as the page grows, so using it here made a click
        // scroll less the longer the page was (R9-3).
        int page = Math.max(1, (barBottom() - barTop()) * 8 / 10);
        boolean below = mouseY > barThumbTop(span) + barView(span);
        int next = Math.max(0, Math.min(maxScroll, scroll + (below ? page : -page)));
        if (next != scroll) {
            scroll = next;
            applyScroll();
        }
    }

    /**
     * Puts the thumb under the pointer.
     *
     * <p>The pointer is held at the middle of the thumb, so grabbing it does not
     * jump the content by half a page.</p>
     */
    private void scrollToBar(double mouseY) {
        int span = barSpan();
        if (span <= 8) return;
        int view = barView(span);
        int travel = Math.max(1, span - view);
        double ratio = (mouseY - barTop() - view / 2.0d) / travel;
        int next = (int) Math.round(Math.max(0.0d, Math.min(1.0d, ratio)) * maxScroll);
        if (next != scroll) {
            scroll = next;
            applyScroll();
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && overScrollBar(event.x(), event.y())) {
            draggingBar = true;
            scrollToBar(event.y());
            return true;
        }
        if (event.button() == 0 && overScrollBarTrack(event.x(), event.y())) {
            pageScroll(event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingBar) {
            scrollToBar(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingBar) {
            draggingBar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        long now = System.currentTimeMillis();
        if (savePending && now >= saveAt) {
            savePending = false;
            cfg.save();
        }
        // the generated font pack can be rewritten while this screen is open, so
        // a preview's readiness is re-checked instead of frozen at init (C6)
        if (!slotPreviews.isEmpty() && now - slotRefreshAt >= 500L) {
            slotRefreshAt = now;
            boolean applied = FontPack.isApplied(cfg);
            for (int i = 0; i < slotPreviews.size(); i++) {
                SlotPreview preview = slotPreviews.get(i);
                slotPreviews.set(i, new SlotPreview(preview.slot, preview.sample, preview.x,
                        preview.y, preview.w, preview.h, FontPack.hasSlot(preview.slot) && applied));
            }
        }

        g.centeredText(this.font, this.title.getString(), this.width / 2, 6, 0xFFFFFFFF);

        for (Caption caption : captions) {
            if (caption.y < contentTop() - 4 || caption.y > this.height - contentBottom()) continue;
            g.text(this.font, caption.text, caption.x, caption.y, 0xFF9FB2C8);
        }

        // per-slot font preview: real glyphs of the chosen file, so the difference
        // between "微软雅黑" and "思源黑体" is visible before leaving the screen
        for (SlotPreview preview : slotPreviews) {
            boolean ready = preview.ready;
            Style style = ready
                    ? Style.EMPTY.withFont(new FontDescription.Resource(
                            Identifier.fromNamespaceAndPath("smtcraft", preview.slot)))
                    : Style.EMPTY;
            Component sample = Component.literal(preview.sample).withStyle(style);
            int sampleW = this.font.width(sample);
            int sx = preview.x + Math.max(0, (preview.w - sampleW) / 2);
            g.text(this.font, sample, sx, preview.y + Math.max(2, (preview.h - 8) / 2),
                    ready ? 0xFFCFE3FF : 0xFF66707C, true);
        }

        drawScrollBar(g);

        MediaBridge bridge = mod == null ? null : mod.bridge();
        String status;
        int color;
        if (bridge == null) {
            status = Component.translatable("smtcraft.settings.status.off").getString();
            color = 0xFFFF8080;
        } else {
            long since = bridge.millisSinceSample();
            String err = bridge.lastError();
            if (bridge.latest().hasTrack()) {
                status = Component.translatable("smtcraft.settings.status.track",
                        bridge.latest().title(), bridge.latest().app()).getString();
            } else {
                status = Component.translatable("smtcraft.settings.status.idle").getString();
            }
            color = 0xFFB8BCC4;
            if (since >= 0) status = status + "  (" + since + " ms)";
            if (err != null && !err.isEmpty()) {
                status = status + "  ! "
                        + Component.translatable("smtcraft.settings.status.bridge_error").getString();
                color = 0xFFFFB060;
            }
        }
        // The config writer keeps its own last failure and, until now, nothing read
        // it: a read-only config directory, a full disk, or an antivirus holding the
        // file through the atomic move all looked exactly like "the setting does not
        // stick", with the reason sitting in a log line nobody was told to open
        // (R17-2).
        // The category, not the message: the footer is one line of UI in the player's
        // language, and the message it used to print was a log string — an exception
        // class and an OS text in English, glued onto a Chinese sentence (R19-3).
        SmtcConfig.Problem saveProblem = cfg.lastProblem();
        if (saveProblem != SmtcConfig.Problem.NONE) {
            status = status + "  ! "
                    + Component.translatable(problemKey(saveProblem)).getString();
            color = 0xFFFFB060;
        }
        boolean roomy = this.height >= 200;
        g.text(this.font, status, contentLeft, this.height - (roomy ? 24 : 10), color);

        LyricService lyrics = mod == null ? null : mod.lyrics();
        String footer;
        if (this.tab == 3) {
            String fontProblem = FontPack.problem();
            footer = Component.translatable("smtcraft.fonts.status",
                    FontPack.isApplied(cfg) ? Component.translatable("smtcraft.fonts.ready")
                            : Component.translatable("smtcraft.fonts.pending")).getString();
            if (!fontProblem.isEmpty()) footer = footer + "  ! " + fontProblem;
        } else if (lyrics != null) {
            footer = Component.translatable("smtcraft.settings.lyric_status", lyrics.status()).getString();
        } else {
            footer = Component.translatable("smtcraft.settings.status.off").getString();
        }
        // a 192 px window cannot hold both lines without cutting a row (C1)
        if (roomy) g.text(this.font, footer, contentLeft, this.height - 13, 0xFF9FB2C8);
    }

    @Override
    public void onClose() {
        cfg.save();
        if (mod != null && mod.bridge() != null) mod.bridge().setConfig(cfg);
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }

    private record SlotPreview(String slot, String sample, int x, int y, int w, int h,
                               boolean ready) {
    }

    private record Caption(int x, int y, Component text) {
    }

    private record ToggleRow(Button button, Component label, BooleanSupplier value) {
    }

    private record TabButton(Button button, int index) {
    }

    private record ColorButton(Button button, String key, Supplier<String> getter) {
    }

    private record SlotButton(Button button, String labelKey, Supplier<String> getter) {
    }

    /** Slider that keeps its own 0..1 position but reports an absolute value. */
    private static final class Slider extends AbstractSliderButton {
        private final double min;
        private final double max;
        private final DoubleConsumer setter;
        private final DoubleFunction<Component> label;

        Slider(int x, int y, int w, int h, double min, double max, double value,
               DoubleConsumer setter, DoubleFunction<Component> label) {
            super(x, y, w, h, Component.empty(), max > min ? (value - min) / (max - min) : 0.0d);
            this.min = min;
            this.max = max;
            this.setter = setter;
            this.label = label;
            updateMessage();
        }

        private double actual() {
            return min + (max - min) * this.value;
        }

        @Override
        protected void updateMessage() {
            if (label == null) return;
            setMessage(label.apply(actual()));
        }

        @Override
        protected void applyValue() {
            if (setter != null) setter.accept(actual());
        }
    }
}
