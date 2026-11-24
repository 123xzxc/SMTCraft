package org.yinyun.smtcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.yinyun.smtcraft.smtc.LyricProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * 纯 Gson 配置，落在 {@code config/smtcraft.json}。1.7.10 下没有 Fabric 的配置目录，
 * 路径由宿主模组用 {@code mcDataDir} 拼出来后传进来（AUDIT-17-1）。
 *
 * <p>新增一个字段要同时改三处，漏了会静默丢值：这里的字段声明、{@link #copyFrom}
 * 的逐字段拷贝（热重载按 mtime 比对后走它）、{@link #normalise} 的范围夹紧。
 */
public final class SmtcConfig {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 文件时间戳未知时的哨兵值：轮询把它当作"刚写过"，不会拿旧快照覆盖新保存（AUDIT-19-2）。 */
    private static final long MTIME_UNKNOWN = -1L;
    /** 轮询节流，属于单个配置对象，不放静态（B2）。 */
    private transient volatile long lastPoll;
    private transient volatile long loadedMtime = MTIME_UNKNOWN;
    private transient volatile boolean reloading;
    private transient volatile String lastSaveError = "";
    private transient volatile Problem lastProblem = Problem.NONE;
    /** 每落盘一次自增；早于保存启动的重载不许覆盖更新的状态（AUDIT-17-1）。 */
    private final transient java.util.concurrent.atomic.AtomicInteger saveGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    /** {@link #lastSaveError} 的类别，设置页只显示类别，细节留在日志里（AUDIT-19-3）。 */
    public enum Problem {
        NONE,
        SAVE_FAILED,
        RELOAD_FAILED,
        NO_PARENT,
        PARSE_FAILED
    }

    public Problem lastProblem() {
        return lastProblem;
    }

    // --- HUD ---------------------------------------------------------------
    public boolean enabled = true;
    public boolean showArtwork = true;
    public boolean showAlbum = true;
    public boolean showProgress = true;
    public boolean showSourceApp = true;
    public boolean hideWhenNoSession = true;
    public boolean hideWhenPaused = false;
    /** 首次进游戏时自动打开原版 unicode 字体：1.7.10 默认字体页只有 ASCII，中日韩全是乱码。 */
    public boolean autoUnicodeFont = true;
    /** 只画歌词带：没有封面、标题、歌手、进度条和时间戳。 */
    public boolean lyricsOnly = false;
    /** 打开任何界面（背包、设置、聊天、暂停）时隐藏面板。 */
    public boolean hideWhenScreenOpen = false;
    /** top-left, top-right, bottom-left, bottom-right, center。 */
    public String anchor = "top-left";
    public int offsetX = 8;
    public int offsetY = 8;
    public float scale = 1.0f;
    /** 面板按 GUI scale 2 设计，跟随缩放时整块按比例还原（AUDIT-36-1）。 */
    public boolean followGuiScale = true;
    public boolean checkUpdates = true;
    public int panelWidth = 300;
    public float backgroundOpacity = 0.62f;
    public float borderOpacity = 0.10f;
    public boolean shadow = true;
    public String textColor = "#FFFFFF";
    public String dimTextColor = "#B8BCC4";
    public String accentColor = "#3FDC6E";
    /** 封面画成旋转的黑胶碟而不是方块。 */
    public boolean vinyl = true;
    /** 黑胶转速，度/秒，只在 vinyl 打开时用。 */
    public float vinylSpinSpeed = 38.0f;
    public static final float MIN_VINYL_SPIN = 0.0f;
    public static final float MAX_VINYL_SPIN = 240.0f;
    public boolean adaptiveWidth = true;
    public boolean centerTextWithoutArtwork = false;
    public boolean autoScale = true;
    public int cornerRadius = 8;
    public static final int MIN_CORNER_RADIUS = 0;
    public static final int MAX_CORNER_RADIUS = 24;
    public boolean artRound = true;
    public int artRadius = 4;
    public static final int MIN_ART_RADIUS = 0;
    public static final int MAX_ART_RADIUS = 12;
    /** 面板宽度区间：设置页的滑条与 {@link #normalise()} 的夹紧都取这两个值，别再写第二份。 */
    public static final int MIN_PANEL_WIDTH = 160;
    public static final int MAX_PANEL_WIDTH = 470;

    // --- audio -------------------------------------------------------------
    public boolean muteGameMusic = true;

    // --- lyrics ------------------------------------------------------------
    public boolean showLyrics = true;
    public boolean showTranslation = true;
    public boolean karaoke = true;
    /** 1..4 行可见歌词。 */
    public int lyricLines = 1;
    public int lyricOffsetMs = 0;
    /** default | unicode | custom */
    public String lyricFontMode = "default";
    public String lyricFontId = "";
    public float lyricFontSize = 1.45f;
    public float translationFontSize = 1.20f;
    public String lyricColor = "#FFFFFF";
    public String karaokeColor = "#3FDC6E";
    public String translationColor = "#FFFFFF";
    public String candidateColor = "#B8BCC4";
    /** 玩家人手改过的颜色键（逗号分隔），换主题时跳过它们（N10）。 */
    public String customColors = "";
    /** 模组静音前玩家的音量，{@code -1} 表示没有待恢复的值（N11）。 */
    public double mutedVolume = -1.0d;

    public boolean isCustom(String key) {
        if (key == null || customColors == null || customColors.isEmpty()) return false;
        for (String part : customColors.split(",")) {
            if (part.trim().equals(key)) return true;
        }
        return false;
    }

    public void markCustom(String key) {
        if (key == null || key.isEmpty()) return;
        if (isCustom(key)) return;
        customColors = customColors == null || customColors.isEmpty() ? key : customColors + "," + key;
    }

    /** 有任何一个颜色被人手改过，就说明没有预设能描述当前配色。 */
    public boolean anyCustom() {
        return customColors != null && !customColors.trim().isEmpty();
    }

    public void clearCustom() {
        customColors = "";
    }

    public boolean lyricBold = true;
    public boolean lyricItalic = false;
    public boolean karaokePeak = true;
    public float karaokePeakAmount = 0.22f;
    public int karaokePeakRange = 24;
    public boolean lyricNoteEase = false;
    public boolean wordTiming = true;
    public boolean showReading = false;
    /** 歌词源尝试顺序，逗号分隔：kugou, qq, netease。 */
    public String lyricOrder = LyricProvider.DEFAULT_ORDER;
    public boolean marqueeLyrics = true;
    public boolean marqueeTrack = true;
    public boolean dualLineWhenNoTranslation = true;
    /**
     * 每种语言的字体文件绝对路径（.ttf/.otf/.ttc）。1.7.10 没有 JSON font provider，
     * 这四个槽位只用来在 ascii / unicode 字体之间切换，路径本身不再注入（移植说明）。
     */
    public String fontLatin = "";
    public String fontCjk = "";
    public String fontJapanese = "";
    public String fontOther = "";

    // --- theme -------------------------------------------------------------
    /** netease | midnight | aurora | sakura | sunset | mono */
    public String theme = "netease";

    // --- reading -----------------------------------------------------------
    /** 轮询间隔下限：再快就是拿 CPU 换不到更新的时间戳。 */
    public static final int MIN_POLL_MS = 100;

    /** 轮询间隔上限：5 秒已经慢到歌词对不上，2 秒是停滞的边界。 */
    public static final int MAX_POLL_MS = 2000;

    public int pollIntervalMs = 150;
    public int swapGraceMs = 1200;
    public boolean ignoreBrowserSessions = true;
    public boolean progressFromStart = true;
    public boolean progressSmooth = true;
    public boolean debug = false;

    private transient File path;

    private SmtcConfig() {
    }

    /** 从磁盘读配置；文件不存在或解析失败时返回默认值（坏文件另存为 {@code .bad-<ms>}）。 */
    public static SmtcConfig load(File file) {
        SmtcConfig cfg = new SmtcConfig();
        cfg.path = file;
        if (file == null) {
            cfg.lastProblem = Problem.NO_PARENT;
            return cfg;
        }
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            cfg.lastProblem = Problem.NO_PARENT;
            cfg.lastSaveError = "cannot create " + dir.getAbsolutePath();
            return cfg;
        }
        if (!file.isFile()) {
            cfg.saveNow();
            return cfg;
        }
        Reader reader = null;
        try {
            reader = new InputStreamReader(new FileInputStream(file), UTF8);
            SmtcConfig parsed = GSON.fromJson(reader, SmtcConfig.class);
            if (parsed != null) {
                parsed.path = file;
                parsed.loadedMtime = file.lastModified();
                parsed.normalise();
                return parsed;
            }
            cfg.lastProblem = Problem.PARSE_FAILED;
        } catch (Throwable t) {
            cfg.lastProblem = Problem.PARSE_FAILED;
            cfg.lastSaveError = org.yinyun.smtcraft.util.Errors.describe(t);
            // 配置读取失败不写日志：1.7.10 的解析失败会经 lastSaveError 显示在设置页页脚，
            // 玩家在出现问题的下一帧就能看到，比只进 latest.log 更容易发现。
            cfg.quarantine(file);
        } finally {
            close(reader);
        }
        cfg.normalise();
        return cfg;
    }

    /** 解析失败的原文件另存一份，免得玩家第一次保存就把损坏的内容盖掉。 */
    private void quarantine(File file) {
        try {
            File bad = new File(file.getParentFile(), file.getName() + ".bad-" + System.currentTimeMillis());
            java.nio.file.Files.copy(file.toPath(), bad.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
            // 另存失败不影响用默认值继续跑
        }
    }

    public File path() {
        return path;
    }

    public String lastSaveError() {
        return lastSaveError;
    }

    /** 把越界的数值收进各自区间；界面滑条和文件手改都走这一条路（D3/D4）。 */
    public void normalise() {
        scale = clamp(scale, 0.25f, 3.0f);
        backgroundOpacity = clamp(backgroundOpacity, 0.0f, 1.0f);
        borderOpacity = clamp(borderOpacity, 0.0f, 1.0f);
        cornerRadius = (int) clamp(cornerRadius, MIN_CORNER_RADIUS, MAX_CORNER_RADIUS);
        artRadius = (int) clamp(artRadius, MIN_ART_RADIUS, MAX_ART_RADIUS);
        vinylSpinSpeed = clamp(vinylSpinSpeed, MIN_VINYL_SPIN, MAX_VINYL_SPIN);
        lyricFontSize = clamp(lyricFontSize, 0.6f, 2.0f);
        translationFontSize = clamp(translationFontSize, 0.6f, 2.0f);
        karaokePeakAmount = clamp(karaokePeakAmount, 0.0f, 1.0f);
        karaokePeakRange = (int) clamp(karaokePeakRange, 0, 64);
        lyricLines = (int) clamp(lyricLines, 1, 4);
        lyricOffsetMs = (int) clamp(lyricOffsetMs, -10000, 10000);
        pollIntervalMs = (int) clamp(pollIntervalMs, MIN_POLL_MS, MAX_POLL_MS);
        swapGraceMs = (int) clamp(swapGraceMs, 0, 5000);
        panelWidth = (int) clamp(panelWidth, MIN_PANEL_WIDTH, MAX_PANEL_WIDTH);
        offsetX = (int) clamp(offsetX, 0, 4000);
        offsetY = (int) clamp(offsetY, 0, 4000);
        if (anchor == null || anchor.isEmpty()) anchor = "top-left";
        if (lyricOrder == null || lyricOrder.trim().isEmpty()) lyricOrder = LyricProvider.DEFAULT_ORDER;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public void saveNow() {
        if (path == null) {
            lastProblem = Problem.NO_PARENT;
            return;
        }
        int generation = saveGeneration.incrementAndGet();
        File tmp = new File(path.getParentFile(), path.getName() + ".tmp");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
            GSON.toJson(this, writer);
            writer.close();
            writer = null;
            java.nio.file.Files.move(tmp.toPath(), path.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            long stamp = path.lastModified();
            // 时间戳读不回来时必须是"未知"，不能留着上一版的旧值（AUDIT-19-2）
            loadedMtime = stamp > 0L ? stamp : MTIME_UNKNOWN;
            lastSaveError = "";
            lastProblem = Problem.NONE;
            if (generation == saveGeneration.get()) {
                // 保存全部落定，早先的重载快照已过期
                reloading = false;
            }
        } catch (Throwable t) {
            lastProblem = Problem.SAVE_FAILED;
            // 失败原因只经 lastSaveError 暴露给设置页页脚；Errors 只负责把异常变成一行文字，
            // 这里没有日志通道可用（1.7.10 的日志器要在客户端代理里拿到）。
            lastSaveError = org.yinyun.smtcraft.util.Errors.describe(t);
        } finally {
            close(writer);
        }
    }

    /** 界面里改完一格就调用；写盘很小，直接在调用线程落盘，避免跨线程状态。 */
    public void save() {
        saveNow();
    }

    /**
     * 热重载：文件时间戳变了就从磁盘读回来。调用方是客户端 tick，
     * 因此只有在 {@link #pollIntervalMs} 到了之后才真的去 stat（AUDIT-17-1）。
     */
    public void poll() {
        if (path == null || reloading) return;
        long now = System.currentTimeMillis();
        if (now - lastPoll < Math.max(MIN_POLL_MS, pollIntervalMs)) return;
        lastPoll = now;
        long stamp;
        try {
            if (!path.isFile()) return;
            stamp = path.lastModified();
        } catch (Throwable t) {
            lastProblem = Problem.RELOAD_FAILED;
            lastSaveError = org.yinyun.smtcraft.util.Errors.describe(t);
            return;
        }
        if (stamp <= 0L || stamp == loadedMtime) return;
        int generation = saveGeneration.get();
        SmtcConfig fresh = load(path);
        if (generation != saveGeneration.get()) {
            // 重载期间又落过一次盘，旧快照不许覆盖（AUDIT-17-1）
            loadedMtime = stamp;
            return;
        }
        if (fresh.lastProblem == Problem.PARSE_FAILED) {
            lastProblem = Problem.PARSE_FAILED;
            loadedMtime = stamp;
            return;
        }
        copyFrom(fresh);
        loadedMtime = fresh.path != null ? fresh.loadedMtime : stamp;
        lastProblem = Problem.NONE;
        lastSaveError = "";
    }

    /** 用磁盘上的新值覆盖自身，保留两条写盘用的内部状态。 */
    private void copyFrom(SmtcConfig other) {
        enabled = other.enabled;
        showArtwork = other.showArtwork;
        showAlbum = other.showAlbum;
        showProgress = other.showProgress;
        showSourceApp = other.showSourceApp;
        hideWhenNoSession = other.hideWhenNoSession;
        hideWhenPaused = other.hideWhenPaused;
        autoUnicodeFont = other.autoUnicodeFont;
        lyricsOnly = other.lyricsOnly;
        hideWhenScreenOpen = other.hideWhenScreenOpen;
        anchor = other.anchor;
        offsetX = other.offsetX;
        offsetY = other.offsetY;
        scale = other.scale;
        followGuiScale = other.followGuiScale;
        checkUpdates = other.checkUpdates;
        panelWidth = other.panelWidth;
        backgroundOpacity = other.backgroundOpacity;
        borderOpacity = other.borderOpacity;
        shadow = other.shadow;
        textColor = other.textColor;
        dimTextColor = other.dimTextColor;
        accentColor = other.accentColor;
        vinyl = other.vinyl;
        vinylSpinSpeed = other.vinylSpinSpeed;
        adaptiveWidth = other.adaptiveWidth;
        centerTextWithoutArtwork = other.centerTextWithoutArtwork;
        autoScale = other.autoScale;
        cornerRadius = other.cornerRadius;
        artRound = other.artRound;
        artRadius = other.artRadius;
        muteGameMusic = other.muteGameMusic;
        showLyrics = other.showLyrics;
        showTranslation = other.showTranslation;
        karaoke = other.karaoke;
        lyricLines = other.lyricLines;
        lyricOffsetMs = other.lyricOffsetMs;
        lyricFontMode = other.lyricFontMode;
        lyricFontId = other.lyricFontId;
        lyricFontSize = other.lyricFontSize;
        translationFontSize = other.translationFontSize;
        lyricColor = other.lyricColor;
        karaokeColor = other.karaokeColor;
        translationColor = other.translationColor;
        candidateColor = other.candidateColor;
        customColors = other.customColors;
        mutedVolume = other.mutedVolume;
        lyricBold = other.lyricBold;
        lyricItalic = other.lyricItalic;
        karaokePeak = other.karaokePeak;
        karaokePeakAmount = other.karaokePeakAmount;
        karaokePeakRange = other.karaokePeakRange;
        lyricNoteEase = other.lyricNoteEase;
        wordTiming = other.wordTiming;
        showReading = other.showReading;
        lyricOrder = other.lyricOrder;
        marqueeLyrics = other.marqueeLyrics;
        marqueeTrack = other.marqueeTrack;
        dualLineWhenNoTranslation = other.dualLineWhenNoTranslation;
        fontLatin = other.fontLatin;
        fontCjk = other.fontCjk;
        fontJapanese = other.fontJapanese;
        fontOther = other.fontOther;
        theme = other.theme;
        pollIntervalMs = other.pollIntervalMs;
        swapGraceMs = other.swapGraceMs;
        ignoreBrowserSessions = other.ignoreBrowserSessions;
        progressFromStart = other.progressFromStart;
        progressSmooth = other.progressSmooth;
        debug = other.debug;
    }

    /** 四个字体槽位里是否有一个填了路径。 */
    public boolean hasSystemFonts() {
        return notEmpty(fontLatin) || notEmpty(fontCjk) || notEmpty(fontJapanese) || notEmpty(fontOther);
    }

    /**
     * 字体切换的键：1.7.10 只有 ascii / unicode 两套，所以非 ascii 字符存在性决定键，
     * 路径部分只用来让缓存失效（移植说明）。
     */
    public String fontKey() {
        boolean nonAscii = notEmpty(fontCjk) || notEmpty(fontJapanese) || notEmpty(fontOther);
        return (nonAscii ? "unicode" : "ascii") + '\u0001' + fontLatin + '\u0001' + fontCjk
                + '\u0001' + fontJapanese + '\u0001' + fontOther;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // 关掉就不会再用了
        }
    }
}
