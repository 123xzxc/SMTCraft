package org.yinyun.smtcraft.gui;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiScreen;
import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.SmtcCraftClient;
import org.yinyun.smtcraft.font.FontPack;
import org.yinyun.smtcraft.media.MediaBridge;
import org.yinyun.smtcraft.render.Canvas;
import org.yinyun.smtcraft.render.Draw;
import org.yinyun.smtcraft.render.Text;
import org.yinyun.smtcraft.render.Theme;
import org.yinyun.smtcraft.smtc.LyricProvider;
import org.yinyun.smtcraft.smtc.LyricService;
import org.yinyun.smtcraft.util.UiScale;
import org.yinyun.smtcraft.util.UpdateCheck;

/**
 * 设置页：显示 / 歌词 / 主题 / 字体 / 关于 五个页签，一张控件表。
 *
 * <p>Fabric 版把控件交给原版的 widget 列表，1.8.9 的 {@code GuiScreen} 没有那套东西，
 * 也不该再走 {@code super.drawScreen}。所以整页改成"自己排、自己画、自己命中"：
 * 控件存在 {@link #controls} 里，位置是设计像素，指针由 {@link ScaledScreen} 换算回来。</p>
 *
 * <p>每个控件直接读写 {@link SmtcConfig}，所以 HUD 下一帧就是新值，磁盘上的 JSON 仍然是
 * 唯一权威。滑条拖动期间不逐帧写盘（{@link #saveSoon()}），否则一次拖动就是几十次
 * 文件替换。</p>
 */
public final class SettingsScreen extends ScaledScreen {

    // --- 常量 ---------------------------------------------------------------

    /** 五种锚点，顺序即按钮的循环顺序。 */
    private static final String[] ANCHORS = {
            "top-left", "top-right", "bottom-left", "bottom-right", "center"};
    /** 四种字体模式；1.8.9 只有前两种真的换字形，后两种保留配置语义。 */
    private static final String[] FONT_MODES = {"default", "unicode", "custom", "system"};
    /** 页签：语言键与内部名。 */
    private static final String[][] TABS = {
            {"smtcraft.tab.display", "display"},
            {"smtcraft.tab.lyrics", "lyrics"},
            {"smtcraft.tab.theme", "theme"},
            {"smtcraft.tab.fonts", "fonts"},
            {"smtcraft.tab.about", "about"}};
    /** 页签个数：页签行按它切分宽度。 */
    private static final int TAB = 5;
    /** 行距下限：再挤，9 像素高的字会粘成一整块灰。 */
    private static final int MIN_STEP = 22;
    /** 内容窗口的上边界，落在页签行下面。 */
    private static final int CONTENT_TOP = 40;
    /** 内容窗口与页脚之间留的高度。 */
    private static final int CONTENT_BOTTOM_MARGIN = 40;
    /**
     * 滚动条留在右侧留白里，不压在最宽控件的右端。
     *
     * <p>原版把滚动条画在内容最后两个像素上，宽控件（整行开关）的右端正好被它盖住，
     * 看上去像控件缺了一角，所以这里让它单独占一条沟。</p>
     */
    private static final int BAR_GUTTER = 6;
    /** 滚动条宽度，设计像素。 */
    private static final int BAR_W = 3;
    /** "刚清理完"提示的存在时间。 */
    private static final long CLEAR_HINT_MS = 4000L;
    /** 字体预览重新确认可用性的间隔（C6）。 */
    private static final long SLOT_REFRESH_MS = 500L;

    // --- 状态 ---------------------------------------------------------------

    private final SmtcCraftClient mod;
    private final SmtcConfig cfg;
    /** 关掉时回到的屏幕；null 表示直接回游戏。 */
    private final GuiScreen parent;
    /** 页面标题，构造时取一次：语言表在运行期不会变。 */
    private final String title;

    /** 当前页签的全部控件，顺序即绘制与命中顺序。 */
    private final List<Controls.Control> controls = new ArrayList<Controls.Control>();
    /** 颜色行的说明文字，滚轮时跟着行一起动。 */
    private final List<Caption> captions = new ArrayList<Caption>();
    /** 字体槽位右侧的预览；滚轮时跟着行一起动。 */
    private final List<SlotPreview> slotPreviews = new ArrayList<SlotPreview>();
    /** 颜色行：色板控件之外还要把十六进制写出来，所以单独记一份。 */
    private final List<ColorRow> colorRows = new ArrayList<ColorRow>();
    /** 排版时的原始 y，滚轮改的是控件当前 y，两者都留着才能往回滚（C1）。 */
    private final Map<Controls.Control, Integer> baseY =
            new IdentityHashMap<Controls.Control, Integer>();

    private int tab;
    private int contentLeft;
    private int contentWidth;
    /** 排版期间为 true：控件的 setter 据此忽略"回填"造成的写入。 */
    private boolean building;
    /** 滑条拖动期间的写盘合并（C5）。 */
    private boolean savePending;
    private long saveAt;
    /** 上次重新确认字体预览的时间（C6）。 */
    private long slotRefreshAt;
    /** 上一次清理的结果：-1 表示没有可显示的结果。 */
    private volatile int clearedFiles = -1;
    private volatile long clearedAt;
    /** 上一次清理是不是只丢了屏幕上那一首。 */
    private volatile boolean clearedOne;
    /** 最近一次把链接塞进剪贴板的时间；0 表示没发生过。 */
    private long linkCopiedAt;
    /** 关于页里那行"已复制"提示的基线。 */
    private int linkNoteY;
    /** "注意"那行说明；复制提示出现时让位，否则两行只差 8 px 会压字。 */
    private Caption aboutNote;
    private int scroll;
    private int maxScroll;
    /** 拖动滚动条拇指期间为 true（AUDIT-75）。 */
    private boolean draggingBar;

    /**
     * Forge 的配置入口用无参构造反射实例化本页（{@code IModGuiFactory.mainConfigGuiClass()}），
     * 所以这里必须留一个 public 无参构造器，不能只有带参的那两个。
     */
    public SettingsScreen() {
        this(SmtcCraftClient.get(), null);
    }

    public SettingsScreen(SmtcCraftClient mod) {
        this(mod, null);
    }

    /**
     * FML 的模组列表里点“Config”时，{@code GuiModList} 是按 {@code GuiScreen} 一个参数的
     * 构造器反射创建本页的，缺了它就抛 {@code NoSuchMethodException}，点一下按钮整个客户端黑屏报错。
     */
    public SettingsScreen(GuiScreen parent) {
        this(SmtcCraftClient.get(), parent);
    }

    /**
     * @param mod    客户端主类，可为 null（只读预览）
     * @param parent 关屏后回到的屏幕，可为 null（回游戏）
     */
    public SettingsScreen(SmtcCraftClient mod, GuiScreen parent) {
        this.mod = mod;
        // 1.8.9 这一版的 SmtcConfig 构造器是私有的，没有 mod 时只能借 load(null) 拿一份
        // 默认值：它不会创建文件，但会把 lastProblem 记成 NO_PARENT，正好如实显示出来
        this.cfg = mod != null && mod.config() != null ? mod.config() : SmtcConfig.load(null);
        this.parent = parent;
        this.title = Controls.text("smtcraft.settings.title");
    }

    // --- 建页 ---------------------------------------------------------------

    /**
     * 重排整页。窗口尺寸变化、换页签、改"跟随界面缩放"都走这里。
     *
     * <p>1.8.9 没有 {@code rebuildWidgets()}，所以本方法自己清表再排一遍；它会被同一
     * 实例反复调用，因此每个列表、每个拖动状态都必须在这里归零（AUDIT-84）。</p>
     */
    @Override
    public void initGui() {
        building = true;
        controls.clear();
        captions.clear();
        slotPreviews.clear();
        colorRows.clear();
        baseY.clear();
        scroll = 0;
        maxScroll = 0;
        draggingBar = false;

        // 玩家设的 GUI 缩放只在这里解析一次：下面每个长度都是"这一页被画出来时的像素"，
        // 出去时由底座整体缩放（AUDIT-36-1）
        refreshUi(cfg.followGuiScale);
        contentWidth = Math.min(vw() - 24, 340);
        contentLeft = (vw() - contentWidth) / 2;

        final int gap = 6;
        final int colW = (contentWidth - gap) / 2;
        final int x0 = contentLeft;
        final int x1 = contentLeft + colW + gap;

        // --- 页签行 ---------------------------------------------------------
        final int tabW = (contentWidth - (TAB - 1) * gap) / TAB;
        final int tabY = 20;
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            controls.add(new Controls.Tab(contentLeft + i * (tabW + gap), tabY, tabW, 18,
                    TABS[i][0], index, index == tab, picked -> {
                        tab = picked.intValue();
                        initGui();
                    }));
        }

        switch (tab) {
            case 1:
                layoutLyricsTab(x0, x1, colW, gap);
                break;
            case 2:
                layoutThemeTab(x0, x1, colW, gap);
                break;
            case 3:
                layoutFontsTab(x0, colW, gap);
                break;
            case 4:
                layoutAboutTab(x0, colW, gap);
                break;
            default:
                layoutDisplayTab(x0, x1, colW, gap);
                break;
        }

        // 强调色来自配置，色块与胶囊都跟着它，玩家换主题时整页立刻换色
        int accent = parseColor(cfg.accentColor);
        for (Controls.Control k : controls) {
            k.accent = accent;
        }

        captureLayout();
        building = false;
    }

    /**
     * 一行的高度与行距。
     *
     * <p>这里不再为了"把所有行塞进窗口"而压缩行距：窗口不够时压到 10 像素，行与行只差
     * 三像素，读起来就是一整块灰。行距保持可读，多出来的部分交给滚动——本页本来就有
     * 滚动偏移、滚动条和滚轮。</p>
     */
    private int step(int rows, int max) {
        int available = Math.max(0, vh() - contentTop() - contentBottom());
        int fit = available / Math.max(1, rows);
        return Math.min(max, Math.max(MIN_STEP, fit));
    }

    /**
     * 记下每行排在哪里、整页溢出多少。
     *
     * <p>行是自上而下摆的，这也是窗口一矮就丢掉最后几个控件的原因：没有东西可移动。
     * 记住基线以后，滚轮只移动内容，页签行和页脚原地不动（C1）。</p>
     */
    private void captureLayout() {
        baseY.clear();
        int top = contentTop();
        int lowest = top;
        for (Controls.Control k : controls) {
            if (k.y < top) continue;
            baseY.put(k, Integer.valueOf(k.y));
            lowest = Math.max(lowest, k.y + k.h);
        }
        for (Caption caption : captions) {
            lowest = Math.max(lowest, caption.y + Text.HEIGHT);
        }
        maxScroll = Math.max(0, lowest + 8 - (vh() - contentBottom()));
        scroll = 0;
        applyScroll();
    }

    /** 把当前滚动偏移重新套到每个可滚动控件上。 */
    private void applyScroll() {
        int top = contentTop();
        int bottom = vh() - contentBottom();
        for (Map.Entry<Controls.Control, Integer> entry : baseY.entrySet()) {
            Controls.Control k = entry.getKey();
            int y = entry.getValue().intValue() - scroll;
            k.y = y;
            // 只显示完整的行：跨在边上的行会盖住页签行或页脚。这里不再借用裁剪去救，
            // 半行照样算"没显示"，与原件一致
            k.visible = y >= top - 1 && y + k.h <= bottom + 1;
        }
    }

    /** 内容从页签行下面开始；窗口矮时让出一点。 */
    private int contentTop() {
        return vh() >= 200 ? CONTENT_TOP : 32;
    }

    /** 内容到页脚为止；矮窗口不再留那么宽。 */
    private int contentBottom() {
        return vh() >= 200 ? CONTENT_BOTTOM_MARGIN : 6;
    }

    /** 页脚第一条状态行的基线。 */
    private int statusTop() {
        return vh() - (vh() >= 200 ? 32 : 10);
    }

    /** 一行控件的高度：行距减三，最多 20。 */
    private int rowH(int step) {
        return Math.min(20, Math.max(9, step - 3));
    }

    // --- 显示页 -------------------------------------------------------------

    /** 显示页：开关、滑条、锚点与清理按钮。 */
    private void layoutDisplayTab(int x0, int x1, int colW, int gap) {
        int step = step(19, 26);
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
        addToggle("smtcraft.opt.hide_menu", () -> cfg.hideWhenScreenOpen,
                v -> cfg.hideWhenScreenOpen = v, x0, y, contentWidth, rowH);
        y += step;
        addToggle("smtcraft.opt.mute_music", () -> cfg.muteGameMusic, v -> cfg.muteGameMusic = v, x0, y, colW, rowH);
        addToggle("smtcraft.opt.shadow", () -> cfg.shadow, v -> cfg.shadow = v, x1, y, colW, rowH);
        y += step;
        addToggle("smtcraft.opt.auto_scale", () -> cfg.autoScale,
                v -> cfg.autoScale = v, x0, y, contentWidth, rowH);
        y += step;
        addToggle("smtcraft.opt.lyrics_only", () -> cfg.lyricsOnly,
                v -> cfg.lyricsOnly = v, x0, y, contentWidth, rowH);
        y += step;
        // 重排一遍，新倍率立刻生效：只改值的话，用旧倍率摆出来的这一页要等玩家关掉再
        // 打开才变，读起来就是"这个开关没用"（AUDIT-36-2）
        addToggle("smtcraft.opt.follow_scale", () -> cfg.followGuiScale, v -> {
            cfg.followGuiScale = v;
            initGui();
        }, x0, y, contentWidth, rowH);
        y += step;
        addSlider(x0, y, colW, rowH, "smtcraft.opt.scale", 0.5d, 3.0d, () -> cfg.scale,
                v -> cfg.scale = (float) v, v -> String.format("%.2fx", v));
        addSlider(x1, y, colW, rowH, "smtcraft.opt.opacity", 0.0d, 1.0d, () -> cfg.backgroundOpacity,
                v -> cfg.backgroundOpacity = (float) v, v -> Math.round(v * 100) + "%");
        y += step;
        // 边框只有 1 像素宽，要和 0% 一眼分得开就得占满一整行
        addSlider(x0, y, contentWidth, rowH, "smtcraft.opt.border_opacity", 0.0d, 1.0d,
                () -> cfg.borderOpacity,
                v -> cfg.borderOpacity = (float) v, v -> Math.round(v * 100) + "%");
        y += step;
        addSlider(x0, y, colW, rowH, "smtcraft.opt.width", SmtcConfig.MIN_PANEL_WIDTH,
                SmtcConfig.MAX_PANEL_WIDTH, () -> cfg.panelWidth,
                v -> cfg.panelWidth = (int) Math.round(v), v -> String.valueOf((int) Math.round(v)));
        addSlider(x1, y, colW, rowH, "smtcraft.opt.radius",
                SmtcConfig.MIN_CORNER_RADIUS, SmtcConfig.MAX_CORNER_RADIUS, () -> cfg.cornerRadius,
                v -> cfg.cornerRadius = (int) Math.round(v), v -> String.valueOf((int) Math.round(v)));
        y += step;
        addSlider(x0, y, colW, rowH, "smtcraft.opt.offset_x", 0d, 400d, () -> cfg.offsetX,
                v -> cfg.offsetX = (int) Math.round(v), v -> String.valueOf((int) Math.round(v)));
        addSlider(x1, y, colW, rowH, "smtcraft.opt.offset_y", 0d, 400d, () -> cfg.offsetY,
                v -> cfg.offsetY = (int) Math.round(v), v -> String.valueOf((int) Math.round(v)));
        y += step;
        addSlider(x0, y, colW, rowH, "smtcraft.opt.vinyl_speed",
                SmtcConfig.MIN_VINYL_SPIN, SmtcConfig.MAX_VINYL_SPIN, () -> cfg.vinylSpinSpeed, v -> {
                    cfg.vinylSpinSpeed = (float) v;
                    // 转速只在唱片模式下看得见，拖动它本身就是想看到唱片
                    if (!cfg.vinyl) cfg.vinyl = true;
                }, v -> Math.round(v) + "\u00B0/s");
        addSlider(x1, y, colW, rowH, "smtcraft.opt.interval", SmtcConfig.MIN_POLL_MS, SmtcConfig.MAX_POLL_MS,
                () -> cfg.pollIntervalMs, v -> {
            cfg.pollIntervalMs = (int) Math.round(v);
            if (mod != null && mod.bridge() != null) mod.bridge().reconfigure();
        }, v -> String.valueOf((int) Math.round(v)));
        y += step;
        addSlider(x0, y, colW, rowH, "smtcraft.opt.swap_grace", 0d, 3000d, () -> cfg.swapGraceMs,
                v -> cfg.swapGraceMs = (int) Math.round(v), v -> String.valueOf((int) Math.round(v)));
        // 封面自己的圆角紧挨着面板圆角：方角封面像贴上去的，而唱片模式没有角可圆
        addSlider(x1, y, colW, rowH, "smtcraft.opt.art_radius",
                SmtcConfig.MIN_ART_RADIUS, SmtcConfig.MAX_ART_RADIUS, () -> cfg.artRadius,
                v -> cfg.artRadius = (int) Math.round(v), v -> String.valueOf((int) Math.round(v)));

        y += step;
        // 半径滑条当开关不好用（0 很难拖准），方角封面单独给一颗开关，挨着锚点按钮
        addToggle("smtcraft.opt.art_round", () -> cfg.artRound, v -> cfg.artRound = v, x0, y, colW, rowH);
        addChoice(x1, y, colW, rowH, "smtcraft.opt.anchor",
                () -> Controls.text("smtcraft.anchor."
                        + (cfg.anchor == null ? "top-left" : cfg.anchor).replace('-', '_')),
                () -> {
                    cfg.anchor = nextAnchor(cfg.anchor);
                    cfg.save();
                });
        y += step;

        // 清理这一首 / 清理全部 / 恢复默认 / 完成，共用一行
        int quarter = (contentWidth - 3 * gap) / 4;
        addPush(x0, y, quarter, rowH, () -> Controls.text("smtcraft.opt.clear_current"), () -> {
            if (mod != null && mod.lyrics() != null) {
                // 删除跑在 I/O 线程，计数从那边回来；这里只改字段，标签自己在下一帧读出来
                mod.lyrics().clearCurrentAsync(removed -> {
                    clearedFiles = removed.booleanValue() ? 1 : 0;
                    clearedOne = true;
                    clearedAt = System.currentTimeMillis();
                });
            }
            clearedFiles = -1;
            clearedOne = true;
            clearedAt = System.currentTimeMillis();
        });
        addPush(x0 + quarter + gap, y, quarter, rowH, () -> {
            boolean fresh = clearedFiles >= 0 && System.currentTimeMillis() - clearedAt < CLEAR_HINT_MS;
            if (!fresh) return Controls.text("smtcraft.opt.clear_cache");
            return Controls.text(clearedOne ? "smtcraft.opt.clear_current.done"
                    : "smtcraft.opt.clear_cache.done", Integer.valueOf(clearedFiles));
        }, () -> {
            // 1.8.9 的封面贴图缓存挂在客户端实例上，契约里没有出口，这里只清歌词缓存：
            // 贴图缓存有自己的容量上限，重进世界也会自己重建
            if (mod != null && mod.lyrics() != null) {
                mod.lyrics().clearCacheAsync(removed -> {
                    clearedFiles = removed;
                    clearedOne = false;
                    clearedAt = System.currentTimeMillis();
                });
            }
            clearedFiles = -1;
            clearedOne = false;
            clearedAt = System.currentTimeMillis();
        });
        addPush(x0 + 2 * (quarter + gap), y, quarter, rowH,
                () -> Controls.text("smtcraft.opt.reset"), () -> {
                    resetDefaults();
                    cfg.save();
                    FontPack.invalidate();
                    initGui();
                });
        addPush(x0 + 3 * (quarter + gap), y, quarter, rowH,
                () -> Controls.text("smtcraft.done"), this::done);
    }

    // --- 歌词页 -------------------------------------------------------------

    /** 歌词页：显示与逐字效果。 */
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
            // 注音是从缓存里的歌词表重新解析出来的，不联网也能立刻生效
            if (mod != null) mod.applyReadingMode();
        }, x0, y, colW, rowH);
        y += step;

        // 歌词源单独占一行：以前和行数滑条挤在一起，结果被滑条盖住
        addChoice(x0, y, colW, rowH, "smtcraft.opt.lyric_source",
                () -> sourceNames(cfg.lyricOrder), () -> {
                    cfg.lyricOrder = nextSourceOrder(cfg.lyricOrder);
                    cfg.save();
                });
        addSlider(x1, y, colW, rowH, "smtcraft.opt.lyric_lines", 1d, 4d, () -> cfg.lyricLines,
                v -> cfg.lyricLines = (int) Math.round(v), v -> String.valueOf((int) Math.round(v)));
        y += step;
        addSlider(x0, y, colW / 2 - 3, rowH, "smtcraft.opt.lyric_size", 0.6d, 2.0d,
                () -> cfg.lyricFontSize,
                v -> cfg.lyricFontSize = (float) v, v -> String.format("%.2fx", v));
        addSlider(x0 + colW / 2 + 3, y, colW / 2 - 3, rowH, "smtcraft.opt.trans_size", 0.6d, 2.0d,
                () -> cfg.translationFontSize,
                v -> cfg.translationFontSize = (float) v, v -> String.format("%.2fx", v));
        addSlider(x1, y, colW, rowH, "smtcraft.opt.peak_amount", 0d, 0.8d,
                () -> cfg.karaokePeakAmount,
                v -> cfg.karaokePeakAmount = (float) v, v -> String.format("%.2f", v));
        y += step;
        addSlider(x0, y, colW, rowH, "smtcraft.opt.peak_range", 4d, 80d, () -> cfg.karaokePeakRange,
                v -> cfg.karaokePeakRange = (int) Math.round(v), v -> String.valueOf((int) Math.round(v)));
        addSlider(x1, y, colW, rowH, "smtcraft.opt.lyric_offset", -3000d, 3000d,
                () -> cfg.lyricOffsetMs,
                v -> cfg.lyricOffsetMs = (int) Math.round(v), v -> String.valueOf((int) Math.round(v)));
    }

    // --- 主题页 -------------------------------------------------------------

    /** 主题页：预设、七个颜色、恢复主题默认色。 */
    private void layoutThemeTab(int x0, int x1, int colW, int gap) {
        // 颜色行上面带一行说明，所以行距要比别的页宽
        int step = step(9, 34);
        int rowH = 16;
        int y = contentTop() + 8;

        // 选预设的意思是"给我那一套配色"：只写一半会调出一锅仍然自称"自定义"的杂色，
        // 所以整块色板一起写下去（Theme.apply 会跳过玩家手挑过的颜色）
        addChoice(x0, y, contentWidth, rowH, "smtcraft.opt.theme",
                () -> cfg.anyCustom()
                        ? Controls.text("smtcraft.theme.custom")
                        : Controls.text(Theme.labelKey(Theme.get(cfg.theme))),
                this::nextTheme);
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
        addColorRow(x0, y, colW, rowH, "smtcraft.opt.translation_color", "translationColor",
                () -> cfg.translationColor, v -> cfg.translationColor = v);
        addColorRow(x1, y, colW, rowH, "smtcraft.opt.candidate_color", "candidateColor",
                () -> cfg.candidateColor, v -> cfg.candidateColor = v);
        y += step;

        // 明确的"重置"是唯一允许覆盖手挑颜色的地方
        addPush(x0, y, contentWidth, rowH, () -> Controls.text("smtcraft.opt.theme_reset"), () -> {
            Theme.reset(Theme.get(cfg.theme), cfg);
            cfg.save();
            initGui();
        });
    }

    /** 循环到下一套预设，整块色板写进配置。 */
    private void nextTheme() {
        List<Theme.Preset> presets = Theme.PRESETS;
        int index = 0;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id().equalsIgnoreCase(cfg.theme == null ? "" : cfg.theme)) {
                index = i;
                break;
            }
        }
        Theme.apply(presets.get((index + 1) % presets.size()), cfg);
        saveSoon();
        initGui();
    }

    // --- 字体页 -------------------------------------------------------------

    /**
     * 字体页：模式与四个语言槽位。
     *
     * <p>1.8.9 没有运行期字体声明，{@link FontPack#supported()} 答 false，所以四个槽位
     * 照常画出来但置灰，旁边写清楚原因；点它什么也不做。槽位路径仍然写回配置，等哪天
     * 有了入口不用再改这一页。</p>
     */
    private void layoutFontsTab(int x0, int colW, int gap) {
        int step = step(9, 30);
        int rowH = rowH(step);
        int previewW = 108;
        int btnW = Math.max(90, contentWidth - previewW - gap);
        int y = contentTop();

        // 模式排在最前：下面四个槽位只在 system 模式下有意义
        addChoice(x0, y, contentWidth, rowH, "smtcraft.opt.font_mode",
                () -> Controls.text("smtcraft.font."
                        + (cfg.lyricFontMode == null ? "default" : cfg.lyricFontMode)),
                () -> {
                    cfg.lyricFontMode = nextFontMode(cfg.lyricFontMode);
                    cfg.save();
                });
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

        // 原件里的字体 id 输入框：这一版没有运行期字体，框跟着槽位一起置灰
        Controls.Field idBox = new Controls.Field(x0, y, contentWidth, rowH, "smtcraft.fonts.id",
                () -> cfg.lyricFontId == null ? "" : cfg.lyricFontId, v -> {
                    // 建页时会用配置里的值回填一次，那不是玩家改的，不该触发写盘
                    if (building) return;
                    cfg.lyricFontId = v;
                    saveSoon();
                });
        idBox.enabled = FontPack.supported();
        controls.add(idBox);
        y += step;

        if (!FontPack.supported()) {
            captions.add(new Caption(x0, y, Controls.text("smtcraft.fonts.unsupported")));
            y += 12;
        }

        int third = (contentWidth - 2 * gap) / 3;
        addPush(x0, y, third, rowH, () -> Controls.text("smtcraft.fonts.rebuild"), () -> {
            cfg.lyricFontMode = "system";
            cfg.save();
            FontPack.invalidate();
            if (mod != null) mod.applyFonts();
            initGui();
        });
        addPush(x0 + third + gap, y, third, rowH, () -> Controls.text("smtcraft.fonts.clear"), () -> {
            cfg.fontLatin = "";
            cfg.fontCjk = "";
            cfg.fontJapanese = "";
            cfg.fontOther = "";
            if ("system".equals(cfg.lyricFontMode)) cfg.lyricFontMode = "default";
            cfg.save();
            FontPack.invalidate();
            initGui();
        });
        addPush(x0 + 2 * (third + gap), y, third, rowH,
                () -> Controls.text("smtcraft.done"), this::done);
    }

    // --- 关于页 -------------------------------------------------------------

    /**
     * 关于页：这个 jar 是什么、项目在哪、以及一颗查新版本的按钮。
     *
     * <p>链接不再走原版的 {@code ConfirmLinkScreen}：1.8.9 没有那个类，直接交给系统
     * 浏览器；打不开（无桌面环境、被策略拦住、地址不合法）就塞进剪贴板并在页脚说明。</p>
     */
    private void layoutAboutTab(int x0, int colW, int gap) {
        int step = step(7, 30);
        int rowH = rowH(step);
        int y = contentTop();

        captions.add(new Caption(x0, y - 9,
                Controls.text("smtcraft.about.version", SmtcCraftClient.version())));
        y += step;

        addPush(x0, y, contentWidth, rowH, this::updateLabel, () -> {
            UpdateCheck.State state = UpdateCheck.state();
            if (!cfg.checkUpdates) {
                // 开关是关的，所以没有请求在飞；玩家下一个动作就是点这颗按钮，于是它
                // 直接把开关打开，而不是背着开关偷偷发请求（AUDIT-47-2）
                cfg.checkUpdates = true;
                cfg.save();
                UpdateCheck.request(SmtcCraftClient.version(), true);
                return;
            }
            // 拿到更新的答案时这颗按钮就是"去下载"；别的答案都是"再查一次"
            if (state.available()) {
                openPage(state.url());
            } else {
                UpdateCheck.request(SmtcCraftClient.version(), true);
            }
        });
        y += step;

        // FAQ 一直暗示的那个开关：关掉就一个请求也不发，而不是让角标背地里去问（AUDIT-46-6）
        addToggle("smtcraft.opt.check_updates", () -> cfg.checkUpdates, v -> {
            cfg.checkUpdates = v;
            if (v) UpdateCheck.request(SmtcCraftClient.version(), true);
        }, x0, y, contentWidth, rowH);
        y += step;

        int third = (contentWidth - 2 * gap) / 3;
        addPush(x0, y, third, rowH, () -> Controls.text("smtcraft.about.releases"),
                () -> openPage(UpdateCheck.RELEASES));
        addPush(x0 + third + gap, y, third, rowH, () -> Controls.text("smtcraft.about.issues"),
                () -> openPage(UpdateCheck.ISSUES));
        addPush(x0 + 2 * (third + gap), y, third, rowH, () -> Controls.text("smtcraft.about.home"),
                () -> openPage(UpdateCheck.HOME));
        y += step;

        // "已复制"提示落在这一行，默认不画
        linkNoteY = y;
        aboutNote = new Caption(x0, y + 8, Controls.text("smtcraft.about.note"));
        captions.add(aboutNote);
    }

    /** 把地址交给系统浏览器；打不开就把地址塞进剪贴板，页面上留一行提示。 */
    private void openPage(String url) {
        if (url == null || url.trim().isEmpty()) return;
        boolean opened = false;
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(new URI(url));
                    opened = true;
                }
            }
        } catch (Throwable failed) {
            // 没有桌面环境、没有默认浏览器、地址不合法，都在这里落地
            opened = false;
        }
        if (!opened) {
            setClipboardString(url);
            linkCopiedAt = System.currentTimeMillis();
        }
    }

    /**
     * 查新按钮上写什么：上次看到的结果，以及点下去会做什么。
     *
     * <p>只写"检查更新"的按钮把答案藏起来了，而答案来自另一条线程、比这一页晚到
     * （AUDIT-45）。判断只有一个实现：Mod Menu 的角标问同一个函数，两边不会在强制
     * 查询期间互相矛盾（AUDIT-47-1、AUDIT-47-3）。</p>
     */
    private String updateLabel() {
        UpdateCheck.State state = UpdateCheck.state();
        String key = UpdateCheck.badgeKey(state, UpdateCheck.running(), cfg.checkUpdates);
        if (UpdateCheck.BADGE_AVAILABLE.equals(key)) {
            return Controls.text("smtcraft.about.available", state.latest());
        }
        if (UpdateCheck.BADGE_FAILED.equals(key)) {
            // 一句短原因；完整诊断（每条线路与它的状态码）留在日志里，报 bug 时再引
            return Controls.text("smtcraft.about.failed",
                    Controls.text(UpdateCheck.reasonKey(state)));
        }
        if (UpdateCheck.BADGE_CURRENT.equals(key)) {
            return Controls.text("smtcraft.about.latest", state.current());
        }
        // "检查中"、"已关闭"、"版本读不出来" 三种都不带参数
        return Controls.text(key);
    }

    // --- 控件工厂 -----------------------------------------------------------

    /** 一个语言槽位：按钮写"槽位名: 文件名"，右边的空白预览该字体。 */
    private void addFontSlot(String labelKey, String slot, String sample,
                             Supplier<String> getter, Consumer<String> setter,
                             int x, int y, int w, int h, int previewX, int previewW) {
        Controls.Push row = new Controls.Push(x, y, w, h, () -> Controls.text(labelKey) + ": "
                + (blank(getter.get()) ? Controls.text("smtcraft.fonts.none") : fileName(getter.get())),
                () -> {
                    if (mc == null || !FontPack.supported()) return;
                    mc.displayGuiScreen(new FontPickerScreen(this, labelKey, path -> {
                        setter.accept(path == null ? "" : path);
                        cfg.save();
                        FontPack.invalidate();
                        if ("system".equals(cfg.lyricFontMode) && mod != null) mod.applyFonts();
                    }, cfg.followGuiScale));
                });
        row.enabled = FontPack.supported();
        controls.add(row);
        // 可用性在这次的排版里确认一次就够：每帧四次 Files.isReadable 是纯浪费
        boolean ready = FontPack.hasSlot(slot) && FontPack.isApplied(cfg);
        slotPreviews.add(new SlotPreview(slot, sample, previewX, y, previewW, h, ready));
    }

    /**
     * 一行颜色：色板 + 十六进制，点开取色页。
     *
     * <p>1.8.9 的控件集里这一行是 {@link Controls.Swatch}，没有内嵌文本框；手输十六
     * 进制挪进取色页（那边有 hex 框），所以这一行只负责显示当前值和打开取色页。</p>
     */
    private void addColorRow(int x, int y, int w, int h, String labelKey, String key,
                             Supplier<String> getter, Consumer<String> setter) {
        Controls.Swatch swatch = new Controls.Swatch(x, y, w, h, labelKey, getter, () -> {
            if (mc == null) return;
            mc.displayGuiScreen(new ColorPickerScreen(this, labelKey, getter.get(), value -> {
                setter.accept(value);
                cfg.markCustom(key);
                saveSoon();
            }, true));
        });
        controls.add(swatch);
        colorRows.add(new ColorRow(swatch, key, getter));
        captions.add(new Caption(x, y - 9, Controls.text(labelKey)));
    }

    /**
     * 一个颜色行上的十六进制文字与"手挑过"的星号。
     *
     * <p>色板只画一块颜色，玩家看不出具体值；原件那一行里还有个只读的 hex 框，这里用
     * 文字把它补回来，位置在色板左边。</p>
     */
    private void drawColorValues(Canvas c, int top, int bottom) {
        for (ColorRow row : colorRows) {
            Controls.Control k = row.control;
            if (!k.visible || k.y + k.h < top || k.y > bottom) continue;
            String hex = row.getter.get();
            String shown = (cfg.isCustom(row.key) ? "\u2726 " : "")
                    + (hex == null || hex.isEmpty() ? "\u25CF" : hex);
            int textW = Text.width(c.font(), shown, false);
            Text.draw(c.font(), shown, k.x + k.w - 20 - textW,
                    k.y + (k.h - Text.HEIGHT) / 2, 0xFFCFE3FF, false, false, false);
        }
    }

    /** 一颗开关；点击后立刻落盘，玩家的下一次动作可能就是直接退出。 */
    private Controls.Toggle addToggle(String key, BooleanSupplier get, Consumer<Boolean> set,
                                      int x, int y, int w, int h) {
        return addToggle(key, get, set, x, y, w, h, false);
    }

    /**
     * 同一行，屏幕上两个词调个头。
     *
     * <p>只换显示、不换写入：{@link Controls.Toggle} 的取反作用在"显示值"上，点击时写回
     * 的仍是供应商报的那个值，所以本页不用它（用了也只会让开关点不动）。参数留着，
     * 免得以后换控件时再改一遍每个调用点。</p>
     */
    private Controls.Toggle addToggle(String key, BooleanSupplier get, Consumer<Boolean> set,
                                      int x, int y, int w, int h, boolean inverted) {
        Controls.Toggle row = new Controls.Toggle(x, y, w, h, key, get, v -> {
            set.accept(v);
            cfg.save();
        }, inverted);
        controls.add(row);
        return row;
    }

    /** 一根滑条；拖动期间只记一次待写盘（C5）。 */
    private Controls.Slider addSlider(int x, int y, int w, int h, String labelKey,
                                      double min, double max, DoubleSupplier get,
                                      DoubleConsumer set, DoubleFunction<String> format) {
        Controls.Slider row = new Controls.Slider(x, y, w, h, labelKey, min, max, get, v -> {
            set.accept(v);
            saveSoon();
        }, format);
        controls.add(row);
        return row;
    }

    /** 一行"点一下换下一个"。 */
    private Controls.Choice addChoice(int x, int y, int w, int h, String labelKey,
                                      Supplier<String> value, Runnable onNext) {
        Controls.Choice row = new Controls.Choice(x, y, w, h, labelKey, value, onNext);
        controls.add(row);
        return row;
    }

    /** 一颗普通按钮。 */
    private Controls.Push addPush(int x, int y, int w, int h, Supplier<String> label,
                                 Runnable onPress) {
        Controls.Push row = new Controls.Push(x, y, w, h, label, onPress);
        controls.add(row);
        return row;
    }

    // --- 取值与换算 ---------------------------------------------------------

    /**
     * 配置里的颜色文本当 ARGB 解析。值是玩家可编辑的文本："#RGB"、"#RRGGBB"、
     * "#AARRGGBB"、空的、胡写的，都必须画出点什么，而不是在绘制时抛异常。
     */
    static int parseColor(String hex) {
        if (hex == null) return 0xFF808080;
        String text = hex.trim();
        if (text.startsWith("#")) text = text.substring(1);
        try {
            if (text.length() == 3) {
                int r = Integer.parseInt(text.substring(0, 1), 16) * 17;
                int g = Integer.parseInt(text.substring(1, 2), 16) * 17;
                int b = Integer.parseInt(text.substring(2, 3), 16) * 17;
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            if (text.length() == 6) return 0xFF000000 | Integer.parseInt(text, 16);
            if (text.length() == 8) return (int) Long.parseLong(text, 16);
        } catch (NumberFormatException notAColour) {
            return 0xFF808080;
        }
        return 0xFF808080;
    }

    /** 字符串是不是空的；1.8.9 的目标语言级别里没有 isBlank。 */
    private static boolean blank(String text) {
        return text == null || text.trim().isEmpty();
    }

    /**
     * 配置里那条字体路径的文件名。值来自玩家能手改的配置文件，一个不合法的路径不许
     * 在建页时抛出去（A6）。
     */
    private static String fileName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) return "";
        try {
            Path name = Paths.get(trimmed).getFileName();
            return name == null ? trimmed : name.toString();
        } catch (InvalidPathException notAPath) {
            return trimmed;
        }
    }

    /**
     * 配置问题类别的语言键（AUDIT-19-3）。故意不写 default：新加一个
     * {@link SmtcConfig.Problem} 应当让编译失败，而不是悄悄显示成"保存失败"。
     * {@code NONE} 也列出来，让 switch 保持穷尽；页脚从不打印它。
     */
    private static String problemKey(SmtcConfig.Problem problem) {
        switch (problem) {
            case RELOAD_FAILED:
                return "smtcraft.settings.save.reload";
            case PARSE_FAILED:
                return "smtcraft.settings.save.broken";
            case SAVE_FAILED:
            case NO_PARENT:
            case NONE:
            default:
                return "smtcraft.settings.save.failed";
        }
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

    /**
     * 按钮循环的歌词源顺序。
     *
     * <p>由 {@link LyricProvider#KNOWN} 生成而不是手写：手列的表会和源悄悄脱节
     * （AUDIT-33），加一个源也不该要求记得改这张表（C7）。默认顺序排第一，然后是每个
     * 有序子集，最后是单个源——两个源都不可达时，玩家要的正是"只用第三个"。</p>
     */
    private static final String[] SOURCE_ORDERS = buildOrders();

    private static String[] buildOrders() {
        List<String> orders = new ArrayList<String>();
        orders.add(LyricProvider.DEFAULT_ORDER);
        List<String> known = new ArrayList<String>(LyricProvider.KNOWN);
        for (int size = 1; size <= known.size(); size++) {
            collectOrders(known, new ArrayList<String>(), size, orders);
        }
        return orders.toArray(new String[orders.size()]);
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

    /** "kugou,qq" 读成"酷狗 › QQ"，顺序本身就是信息。 */
    private static String sourceNames(String order) {
        if (blank(order)) return "";
        StringBuilder joined = new StringBuilder();
        for (String raw : order.split(",")) {
            String id = raw.trim();
            if (id.isEmpty()) continue;
            if (joined.length() > 0) joined.append(" \u203A ");
            joined.append(Controls.text("smtcraft.source." + id));
        }
        return joined.toString();
    }

    /**
     * 恢复出厂值，但保留锚点。
     *
     * <p>Fabric 版用 {@code new SmtcConfig()} 造一份默认值再逐字段拷回；这一版的
     * {@code SmtcConfig} 构造器是私有的，也没有带排除项的拷贝重载，所以默认值只能写在
     * 这里。每加一个配置字段，这里也要跟着加一行——漏了就是"重置"把上一版的值留下。</p>
     */
    private void resetDefaults() {
        cfg.enabled = true;
        cfg.showArtwork = true;
        cfg.showAlbum = true;
        cfg.showProgress = true;
        cfg.showSourceApp = true;
        cfg.hideWhenNoSession = true;
        cfg.hideWhenPaused = false;
        cfg.lyricsOnly = false;
        cfg.hideWhenScreenOpen = false;
        cfg.offsetX = 8;
        cfg.offsetY = 8;
        cfg.scale = 1.0f;
        cfg.followGuiScale = true;
        cfg.checkUpdates = true;
        cfg.panelWidth = 300;
        cfg.backgroundOpacity = 0.62f;
        cfg.borderOpacity = 0.10f;
        cfg.shadow = true;
        cfg.textColor = "#FFFFFF";
        cfg.dimTextColor = "#B8BCC4";
        cfg.accentColor = "#3FDC6E";
        cfg.vinyl = true;
        cfg.vinylSpinSpeed = 38.0f;
        cfg.adaptiveWidth = true;
        cfg.centerTextWithoutArtwork = false;
        cfg.autoScale = true;
        cfg.cornerRadius = 8;
        cfg.artRound = true;
        cfg.artRadius = 4;
        cfg.muteGameMusic = true;
        cfg.showLyrics = true;
        cfg.showTranslation = true;
        cfg.karaoke = true;
        cfg.lyricLines = 1;
        cfg.lyricOffsetMs = 0;
        cfg.lyricFontMode = "default";
        cfg.lyricFontId = "";
        cfg.lyricFontSize = 1.45f;
        cfg.translationFontSize = 1.20f;
        cfg.lyricColor = "#FFFFFF";
        cfg.karaokeColor = "#3FDC6E";
        cfg.translationColor = "#FFFFFF";
        cfg.candidateColor = "#B8BCC4";
        cfg.customColors = "";
        cfg.mutedVolume = -1.0d;
        cfg.lyricBold = true;
        cfg.lyricItalic = false;
        cfg.karaokePeak = true;
        cfg.karaokePeakAmount = 0.22f;
        cfg.karaokePeakRange = 24;
        cfg.lyricNoteEase = false;
        cfg.wordTiming = true;
        cfg.showReading = false;
        cfg.lyricOrder = LyricProvider.DEFAULT_ORDER;
        cfg.marqueeLyrics = true;
        cfg.marqueeTrack = true;
        cfg.dualLineWhenNoTranslation = true;
        cfg.fontLatin = "";
        cfg.fontCjk = "";
        cfg.fontJapanese = "";
        cfg.fontOther = "";
        cfg.theme = "netease";
        cfg.pollIntervalMs = 150;
        cfg.swapGraceMs = 1200;
        cfg.ignoreBrowserSessions = true;
        cfg.progressFromStart = true;
        cfg.progressSmooth = true;
        cfg.autoUnicodeFont = true;
        cfg.debug = false;
    }

    /** 滑动时把写盘合并到停下之后（C5）。 */
    private void saveSoon() {
        savePending = true;
        saveAt = System.currentTimeMillis() + 400L;
    }

    /** 立刻落盘；按钮和关屏用它，滑条不用。 */
    private void saveNow() {
        savePending = false;
        cfg.saveNow();
    }

    // --- 滚动条 -------------------------------------------------------------

    /**
     * 页签溢出时，右侧留白里的一条细轨。
     *
     * <p>没有它，屏幕上没有任何东西说"下面还有"，于是没人会滚（AUDIT-69）。和真正的
     * 滚动条比，它住在留白里而不是内容最后两像素上，拇指可以拖——看起来能拖却不理鼠标，
     * 读起来就是界面卡死了（AUDIT-75）。</p>
     */
    private void drawScrollBar(Canvas c) {
        int span = barSpan();
        if (span <= 8) return;
        int x = barX();
        Draw.fillRound(c, x, barTop(), BAR_W, barBottom() - barTop(), 0, 0x33FFFFFF);
        int top = Math.round(barThumbTop(span));
        Draw.fillRound(c, x, top, BAR_W, barView(span), 0,
                draggingBar ? 0xFFFFFFFF : 0xAAFFFFFF);
    }

    /** 轨道几何，绘制与命中共用一份，免得两边飘。 */
    private int barX() {
        return vw() - BAR_GUTTER;
    }

    private int barTop() {
        return contentTop();
    }

    private int barBottom() {
        return vh() - contentBottom();
    }

    private int barSpan() {
        return maxScroll <= 0 ? 0 : barBottom() - barTop();
    }

    private int barView(int span) {
        return Math.max(8, span * span / (span + maxScroll));
    }

    /** 拇指顶端，屏幕坐标；保留小数，拖动才顺。 */
    private float barThumbTop(int span) {
        int travel = Math.max(0, span - barView(span));
        return (float) (barTop() + travel * (scroll / (double) maxScroll));
    }

    private boolean overScrollBar(int mouseX, int mouseY) {
        int span = barSpan();
        if (span <= 8) return false;
        int x = barX();
        float top = barThumbTop(span);
        return mouseX >= x - 2 && mouseX <= x + BAR_W + 2
                && mouseY >= top - 2 && mouseY <= top + barView(span) + 2;
    }

    /** 整条轨道，不只是拇指：点空白处翻一页。 */
    private boolean overScrollBarTrack(int mouseX, int mouseY) {
        if (barSpan() <= 8) return false;
        int x = barX();
        return mouseX >= x - 2 && mouseX <= x + BAR_W + 2
                && mouseY >= barTop() - 2 && mouseY <= barBottom() + 2;
    }

    /** 上翻还是下翻，由点在拇指的哪一侧决定（AUDIT-83）。 */
    private void pageScroll(int mouseY) {
        int span = barSpan();
        if (span <= 8) return;
        // 一页就是一屏内容。barView() 是拇指高度，页面越长它越短，用它当步长会让
        // 页面越长一次点得越少（AUDIT-93）
        int page = Math.max(1, (barBottom() - barTop()) * 8 / 10);
        boolean below = mouseY > barThumbTop(span) + barView(span);
        int next = Math.max(0, Math.min(maxScroll, scroll + (below ? page : -page)));
        if (next != scroll) {
            scroll = next;
            applyScroll();
        }
    }

    /** 把拇指挪到指针下面；指针按在拇指中段，抓起来不会跳半页。 */
    private void scrollToBar(int mouseY) {
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

    // --- 输入 ---------------------------------------------------------------

    @Override
    protected boolean onClick(int mouseX, int mouseY, int button) {
        if (button == 0 && overScrollBar(mouseX, mouseY)) {
            draggingBar = true;
            scrollToBar(mouseY);
            return true;
        }
        if (button == 0 && overScrollBarTrack(mouseX, mouseY)) {
            pageScroll(mouseY);
            return true;
        }
        // 先把还在编辑的文本框提交掉再派发点击：控件按页面顺序命中，开关/滑条/按钮都排在
        // 文本框前面，Field#click 收不到这次点击就永远不 commit()——focused 留着，之后键盘被
        // 隐形文本框吃掉、第一次 Esc 只换来一次 commit（N-7）
        commitFocus();
        for (Controls.Control k : controls) {
            if (k.click(mouseX, mouseY, button)) return true;
        }
        return false;
    }

    /** 提交所有仍在编辑的文本框；点击别处、关页面都走这一处。 */
    private void commitFocus() {
        for (Controls.Control k : controls) {
            if (k instanceof Controls.Field) {
                Controls.Field field = (Controls.Field) k;
                if (field.focused) field.commit();
            }
        }
    }

    @Override
    protected boolean onDrag(int mouseX, int mouseY, int button) {
        if (draggingBar) {
            scrollToBar(mouseY);
            return true;
        }
        for (Controls.Control k : controls) {
            if (k.drag(mouseX, mouseY, button)) return true;
        }
        return false;
    }

    @Override
    protected boolean onRelease(int mouseX, int mouseY, int button) {
        if (draggingBar) {
            draggingBar = false;
            return true;
        }
        for (Controls.Control k : controls) {
            if (k.release(mouseX, mouseY, button)) return true;
        }
        return false;
    }

    @Override
    protected void onWheel(int mouseX, int mouseY, int dir) {
        if (dir == 0) return;
        // 先给控件：滑条吃滚轮，指针在它上面时不该整页乱跑
        for (Controls.Control k : controls) {
            if (k.visible && k.wheel(mouseX, mouseY, dir)) return;
        }
        if (maxScroll <= 0) return;
        int dy = UiScale.scrollStep(dir, ui());
        int next = Math.max(0, Math.min(maxScroll, scroll - dy));
        if (next != scroll) {
            scroll = next;
            applyScroll();
        }
    }

    @Override
    protected boolean onKey(char typedChar, int keyCode) {
        for (Controls.Control k : controls) {
            if (k.key(typedChar, keyCode)) return true;
        }
        // Esc 回上一屏：1.8.9 的 keyTyped 只会关掉本屏，回到 Mod 列表这一步得自己做；
        // 没有上一屏时 done() 自己走 displayGuiScreen(null)
        if (keyCode == 1 && mc != null) {
            done();
            return true;
        }
        return false;
    }

    /**
     * 关掉这一页，回到打开它的那一屏。
     *
     * <p>没有上一屏时（Forge 的配置入口、或从游戏里直接按键打开）必须显式传 null：
     * 1.8.9 的 {@code displayGuiScreen(null)} 才是"回游戏"，不能指望字段里恰好是 null
     * 就没事——有些调用方会传进来一个已经关掉的屏幕。</p>
     */
    private void done() {
        if (mc == null) return;
        if (parent != null) {
            mc.displayGuiScreen(parent);
        } else {
            mc.displayGuiScreen((GuiScreen) null);
        }
    }

    /**
     * 关屏：合并掉的写盘在这里补上，并把配置交给采集线程。
     *
     * <p>1.8.9 的 {@code displayGuiScreen} 在切换前调用本方法，所以不论是点"完成"、
     * 按 Esc 还是被别的界面顶掉，配置都落得下去。</p>
     */
    @Override
    public void onGuiClosed() {
        // 还在编辑的文本框先落回配置，否则玩家打的最后几个字符随页面一起消失
        commitFocus();
        saveNow();
        if (mod != null && mod.bridge() != null) mod.bridge().setConfig(cfg);
    }

    // --- 绘制 ---------------------------------------------------------------

    /**
     * 每帧：待写盘检查、字体预览复检、内容窗口裁剪、控件与说明文字。
     *
     * <p>页签行在内容窗口上面，先画且不进裁剪；内容用 {@link Canvas#scissor} 裁在窗口里，
     * 半行不会盖住页脚。</p>
     */
    @Override
    protected void drawFrame(Canvas c, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        if (savePending && now >= saveAt) {
            savePending = false;
            cfg.saveNow();
        }

        // 生成的字体包可能在页面开着的时候被重写，所以预览的可用性要复检而不是在
        // 建页时冻住（C6）
        if (!slotPreviews.isEmpty() && now - slotRefreshAt >= SLOT_REFRESH_MS) {
            slotRefreshAt = now;
            boolean applied = FontPack.isApplied(cfg);
            for (int i = 0; i < slotPreviews.size(); i++) {
                SlotPreview preview = slotPreviews.get(i);
                preview.ready = FontPack.hasSlot(preview.slot) && applied;
            }
        }

        int top = contentTop();
        int bottom = vh() - contentBottom();

        for (Controls.Control k : controls) {
            if (k.y < top) k.draw(c, mouseX, mouseY);
        }

        c.scissor(0, top, vw(), bottom);
        for (Controls.Control k : controls) {
            if (k.y >= top) k.draw(c, mouseX, mouseY);
        }
        boolean linkCopied = tab == 4 && linkCopiedAt > 0L && now - linkCopiedAt < CLEAR_HINT_MS;
        for (Caption caption : captions) {
            int y = caption.y - scroll;
            if (y - 9 < top || y > bottom) continue;
            if (linkCopied && caption == aboutNote) continue;
            Text.draw(c.font(), caption.text, caption.x, y, 0xFF9FB2C8, false, false, false);
        }
        drawColorValues(c, top, bottom);
        drawSlotPreviews(c, top, bottom);
        if (linkCopied) {
            Text.draw(c.font(), Controls.text("smtcraft.about.link_copied"), contentLeft,
                    linkNoteY + 8 - scroll, 0xFF8FE3A0, false, false, false);
        }
        c.scissorOff();
    }

    /**
     * 每个槽位的预览：有可用字体时画该字体的真实字形，没有就画灰的样张。
     *
     * <p>1.8.9 没有 {@code FontDescription}，换不了字体，所以这里退回普通字形 + 颜色
     * 区分；槽位名仍然传下去，等有了入口不用改绘制。</p>
     */
    private void drawSlotPreviews(Canvas c, int top, int bottom) {
        for (SlotPreview preview : slotPreviews) {
            int y = preview.y - scroll;
            if (y + preview.h < top || y > bottom) continue;
            int sampleW = Text.width(c.font(), preview.sample, false);
            int sx = preview.x + Math.max(0, (preview.w - sampleW) / 2);
            Text.draw(c.font(), preview.sample, sx, y + Math.max(2, (preview.h - Text.HEIGHT) / 2),
                    preview.ready ? 0xFFCFE3FF : 0xFF66707C, false, false, false);
        }
    }

    /**
     * 行以外的东西：标题、滚动条、两条状态行。
     *
     * <p>它们画在内容裁剪之外——画在里面时，右侧留白里的滚动条和页签上面的标题都被裁掉，
     * 于是窗口化之后整页一条滚动条也看不见。</p>
     */
    @Override
    protected void drawOverlay(Canvas c, int mouseX, int mouseY) {
        Text.drawCentered(c.font(), title, vw() / 2, 6, 0xFFFFFFFF, false, false, false);
        drawScrollBar(c);
        drawStatus(c);
    }

    /** 内容窗口下面那两条状态行。 */
    private void drawStatus(Canvas c) {
        MediaBridge bridge = mod == null ? null : mod.bridge();
        String status;
        int color;
        if (bridge == null) {
            status = Controls.text("smtcraft.settings.status.off");
            color = 0xFFFF8080;
        } else {
            long since = bridge.millisSinceSample();
            String err = bridge.lastError();
            if (bridge.latest().hasTrack()) {
                status = Controls.text("smtcraft.settings.status.track",
                        bridge.latest().title(), bridge.latest().app());
            } else {
                status = Controls.text("smtcraft.settings.status.idle");
            }
            color = 0xFFB8BCC4;
            if (since >= 0) status = status + "  (" + since + " ms)";
            if (err != null && !err.trim().isEmpty()) {
                status = status + "  ! " + Controls.text("smtcraft.settings.status.bridge_error");
                color = 0xFFFFB060;
            }
        }
        // 配置写入一直留着自己的失败原因，却没人读过：只读的配置目录、写满的盘、一直
        // 按着文件的杀毒软件，看起来都只是"设置不生效"，原因躺在没人会打开的日志里
        // （AUDIT-17-2）。这里显示类别，不显示原始消息（AUDIT-19-3）。
        SmtcConfig.Problem saveProblem = cfg.lastProblem();
        if (saveProblem != SmtcConfig.Problem.NONE) {
            status = status + "  ! " + Controls.text(problemKey(saveProblem));
            color = 0xFFFFB060;
        }
        // 原始诊断（异常类名 + 系统文案）只在真的失败时补一句，玩家报问题时能对上日志
        String saveError = cfg.lastSaveError();
        if (saveError != null && !saveError.trim().isEmpty()) {
            status = status + "  (" + Draw.fit(c.font(), saveError.trim(), 120) + ")";
        }
        Text.draw(c.font(), status, contentLeft, statusTop(), color, false, false, false);

        boolean roomy = vh() >= 200;
        if (!roomy) return;
        String footer;
        if (tab == 3) {
            String fontProblem = FontPack.problem();
            footer = Controls.text("smtcraft.fonts.status",
                    FontPack.isApplied(cfg) ? Controls.text("smtcraft.fonts.ready")
                            : Controls.text("smtcraft.fonts.pending"));
            if (fontProblem != null && !fontProblem.trim().isEmpty()) {
                footer = footer + "  ! " + fontProblem;
            }
        } else if (tab == 4) {
            // 查新的答案来自另一条线程，比这一页晚到；页脚复述一遍，页签切走也不丢
            footer = updateLabel();
        } else {
            LyricService lyrics = mod == null ? null : mod.lyrics();
            footer = lyrics != null
                    ? Controls.text("smtcraft.settings.lyric_status", lyrics.status())
                    : Controls.text("smtcraft.settings.status.off");
        }
        // 清理结果也在页脚留一句：按钮上的提示会随滚动移出视野
        if (clearedFiles >= 0 && System.currentTimeMillis() - clearedAt < CLEAR_HINT_MS) {
            footer = footer + "  " + Controls.text(clearedOne
                    ? "smtcraft.opt.clear_current.done" : "smtcraft.opt.clear_cache.done",
                    Integer.valueOf(clearedFiles));
        }
        Text.draw(c.font(), footer, contentLeft, vh() - 20, 0xFF9FB2C8, false, false, false);
    }

    // --- 小结构 -------------------------------------------------------------

    /** 一行说明文字：坐标是排版基线，绘制时再减去滚动偏移。 */
    private static final class Caption {
        final int x;
        final int y;
        final String text;

        Caption(int x, int y, String text) {
            this.x = x;
            this.y = y;
            this.text = text;
        }
    }

    /** 一个字体槽位的预览：槽位名决定字形来源，可用性每 500 ms 复检一次。 */
    private static final class SlotPreview {
        final String slot;
        final String sample;
        final int x;
        final int y;
        final int w;
        final int h;
        boolean ready;

        SlotPreview(String slot, String sample, int x, int y, int w, int h, boolean ready) {
            this.slot = slot;
            this.sample = sample;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.ready = ready;
        }
    }

    /** 一行颜色：色板控件、它在配置里的字段名、以及当前值的来源。 */
    private static final class ColorRow {
        final Controls.Control control;
        final String key;
        final Supplier<String> getter;

        ColorRow(Controls.Control control, String key, Supplier<String> getter) {
            this.control = control;
            this.key = key;
            this.getter = getter;
        }
    }
}
