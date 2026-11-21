package org.yinyun.smtcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yinyun.smtcraft.smtc.LyricProvider;
import org.yinyun.smtcraft.util.Async;
import org.yinyun.smtcraft.util.Errors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plain Gson config at {@code config/smtcraft.json}. No Cloth Config, no YACL —
 * one file, editable in game through the settings screen and hot-reloaded from
 * disk while the game runs.
 */
public final class SmtcConfig {
    private static final Logger LOG = LoggerFactory.getLogger("smtcraft");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Throttle + mtime belong to one config object (B2: statics swallowed each other). */
    private transient volatile long lastPoll;
    private transient volatile long loadedMtime;
    /**
     * {@link #loadedMtime} value meaning "the file on disk is the one we just wrote,
     * but its stamp could not be read back". The field has to be one of two things —
     * the mtime of the file on disk, or an explicit "not known" — because the poll
     * treats it as authoritative. Leaving the previous stamp in place was a third
     * state, and it made the next poll read our own fresh write and copy it over any
     * edit the player made in that half second (R19-2).
     */
    private static final long MTIME_UNKNOWN = -1L;
    /** True while a background reload is in flight, so ticks cannot pile up reads. */
    private transient volatile boolean reloading;
    /**
     * Last persistence / parse problem, shown in the settings footer (B1). Written
     * on the lane that writes the file, the I/O lane (a failed reload) and the
     * shutdown finisher, read on the render thread by the settings screen — so it is
     * volatile like its two neighbours. A slightly stale diagnostic string would not
     * hurt anyone; three fields of the same class where two are volatile and the
     * third is bare is what the next change copies wrong (R18-2).
     */
    private transient volatile String lastSaveError = "";
    /**
     * What the footer shows. The raw text above is a log line — an exception class,
     * a path, an OS message — and it has no business inside a Chinese status bar:
     * it produced lines like "正在读取 … ! save failed: IOException: 拒绝访问。" while
     * the three {@code smtcraft.settings.save.*} keys sat unused (R19-3). The footer
     * now prints the category, the details stay in the log.
     */
    private transient volatile Problem lastProblem = Problem.NONE;

    /** Category of {@link #lastSaveError}; {@link #NONE} when the last access worked. */
    public enum Problem {
        NONE,
        /** The write itself failed (read-only directory, full disk, scanner holding it). */
        SAVE_FAILED,
        /** The file could not be read back for a hot reload. */
        RELOAD_FAILED,
        /** The configured path has no parent directory at all. */
        NO_PARENT,
        /**
         * The file was readable but its JSON did not parse, so it was kept as
         * {@code smtcraft.json.bad-<ms>} and the defaults were used instead. Not
         * {@code UNREADABLE}: since R20-1 moved the stat failure out of this branch,
         * the bytes were read fine — it is the parse that failed (R21-4).
         */
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

    /** top-left, top-right, bottom-left, bottom-right, center. */
    public String anchor = "top-left";
    public int offsetX = 8;
    public int offsetY = 8;
    public float scale = 1.0f;
    public int panelWidth = 300;
    public float backgroundOpacity = 0.62f;
    public boolean shadow = true;
    public String textColor = "#FFFFFF";
    public String dimTextColor = "#B8BCC4";
    public String accentColor = "#3FDC6E";

    /** Draw the cover as a rotating vinyl disc instead of a flat square. */
    public boolean vinyl = true;
    /** Vinyl rotation speed in degrees per second, only used when vinyl is on. */
    public float vinylSpinSpeed = 38.0f;

    /** Grow the panel until title / artist / album fit (capped, lyrics excluded). */
    public boolean adaptiveWidth = true;
    /** Without artwork the text block is centred instead of left aligned. */
    public boolean centerTextWithoutArtwork = false;

    /**
     * Shrink the whole panel when the game runs in a small window. The HUD is laid
     * out in GUI units, so a windowed client shows the same panel covering a much
     * larger share of the screen; this keeps that share close to a full-screen one.
     */
    public boolean autoScale = true;

    /** Rounded panel corners; 0 keeps the current square panel. */
    public int cornerRadius = 8;

    // --- audio -------------------------------------------------------------
    /** Silence vanilla background music while a SMTC track is playing. */
    public boolean muteGameMusic = true;

    // --- lyrics ------------------------------------------------------------
    public boolean showLyrics = true;
    /** Translation under every visible lyric row, foreign-language songs. */
    public boolean showTranslation = true;
    /** Karaoke wipe on the current line instead of a flat highlight. */
    public boolean karaoke = true;
    /** 1..4 visible lyric rows. */
    public int lyricLines = 1;
    /** Shift lyrics against the reported playback position, in ms. */
    public int lyricOffsetMs = 0;
    /** default | unicode | custom */
    public String lyricFontMode = "default";
    /** Font resource id used when lyricFontMode is custom, e.g. minecraft:uniform. */
    public String lyricFontId = "";
    /** Lyrics glyph scale, 1.0 = vanilla size. */
    public float lyricFontSize = 1.45f;
    /** Translation glyph scale, 1.0 = vanilla size. */
    public float translationFontSize = 1.20f;
    /** Colour of the not-yet-sung part of the current line. */
    public String lyricColor = "#FFFFFF";
    /** Colour of the already-sung part (karaoke sweep). */
    public String karaokeColor = "#3FDC6E";

    /**
     * Colour keys the player edited by hand (comma separated). A theme change only
     * writes the colours that are still the theme's own, so a hand-picked accent
     * survives switching themes (N10).
     */
    public String customColors = "";

    /** Music volume saved before the mod muted the game; {@code -1} when nothing is pending (N11). */
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

    /** True when any colour was picked by hand, so no preset describes the palette. */
    public boolean anyCustom() {
        return customColors != null && !customColors.isBlank();
    }

    public void clearCustom() {
        customColors = "";
    }

    /** Bold / italic styling for every lyric row. */
    public boolean lyricBold = true;
    public boolean lyricItalic = false;

    /** The syllable under the play head swells into a small hill. */
    public boolean karaokePeak = true;
    /** Extra glyph scale at the play head, 0.0..1.0. */
    public float karaokePeakAmount = 0.22f;
    /** How many design pixels around the play head feel the swell. */
    public int karaokePeakRange = 24;

    /** Slow the wipe down on every held syllable instead of a constant sweep. */
    public boolean lyricNoteEase = false;

    /**
     * Follow the real per-syllable timings when the lyric source has them
     * (NetEase yrc / enhanced LRC). Off falls back to the weighted estimate even
     * when syllable data exists.
     */
    public boolean wordTiming = true;

    /**
     * Show the phonetic transcript (romaji / kana reading) that some providers put in
     * the translation slot. Off by default: a reading is not a translation, and
     * painting it across the subtitle row reads as a bug.
     */
    public boolean showReading = false;

    /**
     * Lyric providers tried in this order, comma separated: {@code kugou},
     * {@code qq}, {@code netease}. Kugou leads: its KRC is the most reliable
     * syllable source for Japanese and mainland-popular tracks. QQ is the second
     * attempt and NetEase is the last resort, only reached when the first two have
     * nothing usable. The chain stops as soon as a sheet scores full marks.
     */
    public String lyricOrder = LyricProvider.DEFAULT_ORDER;

    /** Scroll lyrics that are wider than the panel instead of truncating them. */
    public boolean marqueeLyrics = true;
    /** Marquee for title / artist / album. */
    public boolean marqueeTrack = true;

    /**
     * Songs without a translation keep the second row for the next lyric line,
     * with a cross-fade while the rows move up.
     */
    public boolean dualLineWhenNoTranslation = true;

    /**
     * Per language font files (absolute path to a .ttf/.otf/.ttc). Empty means
     * "use the next slot / the vanilla font".
     */
    public String fontLatin = "";
    public String fontCjk = "";
    public String fontJapanese = "";
    public String fontOther = "";

    // --- theme -------------------------------------------------------------
    /** netease | midnight | aurora | sakura | sunset | mono */
    public String theme = "netease";

    // --- reading -----------------------------------------------------------
    /** Native status/timeline polling interval; property reads are cached separately. */
    public int pollIntervalMs = 150;
    /** Sessions that vanish for a moment while switching songs keep the old card. */
    public int swapGraceMs = 1200;
    /** Ignore browser sessions (YouTube in Edge, ...) so lyrics only follow music apps. */
    public boolean ignoreBrowserSessions = true;
    /** No timeline reported: count the play position from zero instead. */
    public boolean progressFromStart = true;
    /** Smooth the progress bar against lagging app timelines. */
    public boolean progressSmooth = true;
    public boolean debug = false;

    private transient Path path;
    /**
     * Bumped every time a save lands. A reload that started before one must not
     * copy its older snapshot over the newer state (R17-1): the two paths are
     * asynchronous, the write runs immediately on its lane while the hand-back
     * waits for the next client tick, so "reload first, save second" happens
     * whenever the stat of a poll overlaps a slider already on its way to disk.
     * A timestamp comparison cannot separate the two cases — two writes inside
     * the same file-system tick share their mtime — so this counts instead.
     * Atomic, not merely volatile: {@link #save()} bumps it on the merged lane and
     * {@link #saveNow()} on whatever thread called it (startup, the shutdown
     * finisher, tests), so two writers can be inside the same increment. A lost
     * bump makes an older snapshot look current and hands the R17-1 protection back
     * to the very race it was added for (R18-2).
     */
    private final transient AtomicInteger saveGeneration = new AtomicInteger();

    /**
     * Turns a stamp read into the value {@link #loadedMtime} may hold: the stamp itself,
     * or {@link #MTIME_UNKNOWN}. Every write that could not read its stamp back has to
     * end up here — a bare "leave the old value" reads to the next poll as "the file
     * moved on", which is how a save used to get reloaded over the player's edit
     * (R19-2, R20-1).
     */
    static long stampOrUnknown(long stamp) {
        return stamp > 0L ? stamp : MTIME_UNKNOWN;
    }

    /**
     * True when a save landed after the snapshot that produced {@code captured}.
     * Split out of the reload hand-back so the decision can be exercised without a
     * live bus, a client thread or a file system (R18-3).
     */
    static boolean staleSnapshot(int captured, int current) {
        return captured != current;
    }

    public static SmtcConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("smtcraft.json");
        SmtcConfig cfg = new SmtcConfig();
        cfg.path = file;
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                SmtcConfig read = GSON.fromJson(json, SmtcConfig.class);
                if (read != null) {
                    read.path = file;
                    read.normalise();
                    cfg = read;
                }
            } catch (Exception broken) {
                // A half-written or hand-edited file used to be replaced by the
                // defaults on the next line, which silently threw the player's
                // whole config away. Keep the broken file for inspection first.
                cfg = new SmtcConfig();
                cfg.path = file;
                cfg.lastSaveError = "config cannot be parsed: " + Errors.describe(broken);
                cfg.lastProblem = Problem.PARSE_FAILED;
                LOG.warn("config/smtcraft.json cannot be parsed ({}), keeping a copy and writing defaults",
                        Errors.describe(broken));
                try {
                    Path bad = file.resolveSibling("smtcraft.json.bad-" + System.currentTimeMillis());
                    Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // nothing else to do: the write below overwrites it
                }
            }
            // The stamp is not part of "can this file be parsed": a scanner holding the
            // file for a moment makes stat fail while the JSON in hand is perfectly
            // good, and sharing one catch with the parse used to rename that good file
            // to .bad-<ms> and start over from the defaults (R20-1). An unknown stamp is
            // already a state this class understands, and the next poll adopts it.
            long stamp = 0L;
            try {
                stamp = Files.getLastModifiedTime(file).toMillis();
            } catch (IOException | RuntimeException hidden) {
                LOG.debug("cannot read back the mtime of {}: {}", file, Errors.describe(hidden));
            }
            cfg.loadedMtime = stampOrUnknown(stamp);
        }
        cfg.save();
        return cfg;
    }

    /** Accessor for the settings footer. */
    public String lastSaveError() {
        return lastSaveError == null ? "" : lastSaveError;
    }

    /**
     * Keeps every numeric field inside the range the UI can express, so a hand
     * edited JSON cannot reach the renderer as {@code lyricLines = 99} or a
     * negative panel width (B3).
     */
    public void normalise() {
        normaliseOrder();
        scale = clamp(scale, 0.5f, 3.0f);
        panelWidth = (int) Math.round(clamp(panelWidth, 120, 470));
        backgroundOpacity = clamp(backgroundOpacity, 0.0f, 1.0f);
        cornerRadius = (int) Math.round(clamp(cornerRadius, 0, 24));
        offsetX = (int) Math.round(clamp(offsetX, -4096, 4096));
        offsetY = (int) Math.round(clamp(offsetY, -4096, 4096));
        lyricLines = (int) Math.round(clamp(lyricLines, 0, 4));
        lyricFontSize = clamp(lyricFontSize, 0.6f, 3.0f);
        translationFontSize = clamp(translationFontSize, 0.5f, 3.0f);
        lyricOffsetMs = (int) Math.round(clamp(lyricOffsetMs, -60_000, 60_000));
        karaokePeakAmount = clamp(karaokePeakAmount, 0.0f, 0.5f);
        karaokePeakRange = (int) Math.round(clamp(karaokePeakRange, 0, 64));
        vinylSpinSpeed = clamp(vinylSpinSpeed, 0.0f, 240.0f);
        pollIntervalMs = (int) Math.round(clamp(pollIntervalMs, 60, 5000));
        swapGraceMs = (int) Math.round(clamp(swapGraceMs, 0, 10_000));
        if (theme == null || theme.isBlank()) theme = "netease";
        if (anchor == null || anchor.isBlank()) anchor = "top-left";
        if (lyricFontMode == null || lyricFontMode.isBlank()) lyricFontMode = "default";
    }

    private static float clamp(float v, float min, float max) {
        if (Float.isNaN(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Keeps whatever sequence the player picked - a leading netease included - and
     * only drops unknown names and duplicates.
     */
    private void normaliseOrder() {
        if (lyricOrder == null) {
            lyricOrder = LyricProvider.DEFAULT_ORDER;
            return;
        }
        String[] parts = lyricOrder.toLowerCase(java.util.Locale.ROOT).split(",");
        StringBuilder out = new StringBuilder();
        for (String raw : parts) {
            String name = raw.trim();
            if (!LyricProvider.known(name)) continue;
            if (out.indexOf(name) >= 0) continue;
            if (out.length() > 0) out.append(',');
            out.append(name);
        }
        lyricOrder = out.length() == 0 ? LyricProvider.DEFAULT_ORDER : out.toString();
    }

    public SmtcConfig copy() {
        SmtcConfig copy = GSON.fromJson(GSON.toJson(this), SmtcConfig.class);
        if (copy == null) copy = new SmtcConfig();
        copy.path = path;
        return copy;
    }

    /**
     * Persist the settings. The snapshot is taken here — that is a field walk, not a
     * disk access, and every mutable field is only written from the client thread —
     * while the file work runs on the background lane. Dragging a slider used to
     * write a JSON file dozens of times per second on the client thread; now the
     * lane keeps only the newest snapshot, so a gesture costs one write.
     */
    public void save() {
        if (path == null) return;
        final String json = GSON.toJson(this);
        Async.latest("config save", () -> writeSafely(json));
    }

    /** Synchronous save, for shutdown and tests: returns once the file is on disk. */
    public void saveNow() {
        if (path == null) return;
        writeSafely(GSON.toJson(this));
    }

    private void writeSafely(String json) {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) {
            lastSaveError = "no parent directory for " + path;
            lastProblem = Problem.NO_PARENT;
            return;
        }
        Path tmp = null;
        try {
            Files.createDirectories(parent);
            // write next to the target, then move: a crash mid-write can no longer
            // leave a truncated file that the loader would read as garbage (B1)
            tmp = Files.createTempFile(parent, "smtcraft", ".json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
            // The move already succeeded above, so nothing from here on may report a
            // failure: reading the mtime back is a second disk access that fails on
            // its own (a concurrent delete, a scanner holding the path), and it used
            // to land in the catch below — the file was fine while the footer said
            // "save failed" (R18-2).
            long mtime = 0L;
            try {
                mtime = Files.getLastModifiedTime(path).toMillis();
            } catch (IOException | RuntimeException unreadable) {
                LOG.debug("cannot read back the mtime of {}: {}", path, Errors.describe(unreadable));
            }
            saveGeneration.incrementAndGet();
            loadedMtime = stampOrUnknown(mtime);
            lastProblem = Problem.NONE;
            lastSaveError = "";
        } catch (IOException | RuntimeException failed) {
            lastSaveError = "save failed: " + Errors.describe(failed);
            lastProblem = Problem.SAVE_FAILED;
            LOG.warn("cannot write {}: {}", path, Errors.describe(failed));
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /** Re-read the file when it changed on disk; throttled to once per 500 ms. */
    public SmtcConfig pollReload() {
        if (path == null) return this;
        long now = System.currentTimeMillis();
        if (now - lastPoll < 500L) return this;
        lastPoll = now;
        if (adoptUnknownStamp()) return this;
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            if (mtime == loadedMtime) return this;
            // read + parse first, and only remember the mtime once it worked: a
            // half-written file used to be marked as "seen" and never retried (A2)
            String json = Files.readString(path, StandardCharsets.UTF_8);
            SmtcConfig read = GSON.fromJson(json, SmtcConfig.class);
            if (read == null) return this;
            read.path = path;
            read.normalise();
            read.loadedMtime = mtime;
            read.lastPoll = now;
            return read;
        } catch (Exception retryLater) {
            // keep loadedMtime untouched so the next tick tries again
            lastSaveError = "config reload failed: " + Errors.describe(retryLater);
            lastProblem = Problem.RELOAD_FAILED;
        }
        return this;
    }

    /**
     * Background variant of {@link #pollReload()}: the throttled stat and the read
     * happen on the I/O lane and the parsed values are copied in on the client
     * thread, so a slow disk or an antivirus scan cannot stall a tick.
     */
    public void pollReloadAsync() {
        if (path == null || reloading) return;
        long now = System.currentTimeMillis();
        if (now - lastPoll < 500L) return;
        lastPoll = now;
        reloading = true;
        // The latch only covers stat + read. Keeping it set until the client callback
        // ran meant one rejected submission disabled hot reload until the game was
        // restarted; resetting it at the submission layer and before the hand-back
        // leaves no path where it can stay raised (R4-1).
        if (!Async.run("config poll", this::pollReloadOnce)) {
            reloading = false;
        }
    }

    private void pollReloadOnce() {
        final int gen = saveGeneration.get();
        if (adoptUnknownStamp()) {
            reloading = false;
            return;
        }
        long mtime;
        String json;
        try {
            mtime = Files.getLastModifiedTime(path).toMillis();
            json = mtime != loadedMtime ? Files.readString(path, StandardCharsets.UTF_8) : null;
        } catch (Exception retryLater) {
            lastSaveError = "config reload failed: " + Errors.describe(retryLater);
            lastProblem = Problem.RELOAD_FAILED;
            return;
        } finally {
            reloading = false;
        }
        if (json == null) return;
        final long seen = mtime;
        final String text = json;
        Async.onClient(() -> {
            // A save landed between the stat and this hand-back: the text in hand
            // is older than the file on disk, and copying it in would leave memory
            // behind while loadedMtime says "in sync", so no later poll ever reads
            // the file again — the user's change would be lost on the next save
            // (R17-1).
            if (staleSnapshot(gen, saveGeneration.get())) return;
            try {
                SmtcConfig read = GSON.fromJson(text, SmtcConfig.class);
                if (read == null) return;
                read.normalise();
                copyFrom(read);
                loadedMtime = seen;
            } catch (Exception bad) {
                lastSaveError = "config reload failed: " + Errors.describe(bad);
                lastProblem = Problem.RELOAD_FAILED;
            }
        });
    }

    /**
     * When the last save could not read the stamp back, the file on disk is known to
     * be ours but its mtime is not — so the next poll adopts the stamp and skips one
     * round instead of reading our own write and copying it over whatever the player
     * changed in the meantime (R19-2). Returns true when this round was skipped.
     */
    private boolean adoptUnknownStamp() {
        if (loadedMtime != MTIME_UNKNOWN) return false;
        try {
            loadedMtime = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException | RuntimeException stillHidden) {
            LOG.debug("cannot adopt the mtime of {} yet: {}", path, Errors.describe(stillHidden));
        }
        return true;
    }

    /** True when at least one language slot points at a font file. */
    public boolean hasSystemFonts() {
        return !blank(fontLatin) || !blank(fontCjk) || !blank(fontJapanese) || !blank(fontOther);
    }

    /** Identity of the current font selection, used to trigger a resource reload. */
    public String fontKey() {
        return nz(fontLatin) + '|' + nz(fontCjk) + '|' + nz(fontJapanese) + '|' + nz(fontOther);
    }

    /**
     * Copy every persisted setting from {@code other}, skipping the fields named
     * in {@code skip}. Reflection on purpose: the hand-written field-by-field
     * copy silently missed new options every time one was added (C2/E3).
     */
    public void copyFrom(SmtcConfig other, String... skip) {
        if (other == null || other == this) return;
        java.util.Set<String> ignored = java.util.Set.of(skip);
        for (java.lang.reflect.Field field : SmtcConfig.class.getFields()) {
            if (ignored.contains(field.getName())) continue;
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            if (java.lang.reflect.Modifier.isTransient(field.getModifiers())) continue;
            try {
                field.set(this, field.get(other));
            } catch (ReflectiveOperationException ignoredField) {
                LOG.debug("config copy skipped {}", field.getName());
            }
        }
    }

    public static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}
