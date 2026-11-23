package org.yinyun.smtcraft;

import com.mojang.blaze3d.platform.InputConstants;
import org.yinyun.smtcraft.audio.MusicMuter;
import org.yinyun.smtcraft.font.FontPack;
import org.yinyun.smtcraft.gui.SettingsScreen;
import org.yinyun.smtcraft.render.NowPlayingHud;
import org.yinyun.smtcraft.smtc.LyricService;
import org.yinyun.smtcraft.util.Errors;
import org.yinyun.smtcraft.util.Keys;
import org.yinyun.smtcraft.util.Screens;
import org.yinyun.smtcraft.util.WheelWatch;
import org.yinyun.smtcraft.gui.ScreenHooks;
import org.yinyun.smtcraft.gui.Widgets;
import org.yinyun.smtcraft.render.CoverTexture;
import org.yinyun.smtcraft.media.MediaBridge;
import org.yinyun.smtcraft.media.MediaBridges;
import org.yinyun.smtcraft.smtc.TrackInfo;
import org.yinyun.smtcraft.util.Async;
import org.yinyun.smtcraft.util.Budgets;
import org.yinyun.smtcraft.util.ShutdownPlan;
import org.yinyun.smtcraft.util.UpdateCheck;
import org.yinyun.smtcraft.util.Watchdog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client entrypoint: config, the native SMTC bridge, the lyric worker, the HUD
 * element and the vanilla-music mute.
 *
 * <p>Nothing here touches the server; the mod is {@code environment: client}
 * and never sends a packet.</p>
 */
public final class SmtcCraftClient implements ClientModInitializer {
    public static final String MOD_ID = "smtcraft";
    /**
     * Total time the shutdown sequence may take. Derived from the bridge round
     * instead of being picked on its own: 8 s looked generous until the bridge
     * constant became 10.5 s, after which the bridge could never receive the wait
     * it promised itself (AUDIT-81).
     */
    private static final long SHUTDOWN_BUDGET_MS = Budgets.SHUTDOWN_MS;
    /**
     * Exit code when the watchdog has to end the process. Zero would say "clean
     * exit" to the launcher and to any script that ran us, while this path means
     * the sequence did not finish in time.
     */
    private static final int HALT_EXIT_UNFINISHED = 1;

    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static SmtcCraftClient instance;

    /** The live client, or {@code null} while the game is starting or shutting down. */
    public static SmtcCraftClient instance() {
        return instance;
    }

    private SmtcConfig config;
    private MediaBridge bridge;
    private LyricService lyrics;
    private final MusicMuter musicMuter = new MusicMuter();
    /**
     * The stopping event is fired once per process in a normal exit, but not only
     * there: a dev environment reload and some launchers dispatch it twice. While
     * the sequence ran inline that was just a duplicated wait; now it starts a
     * thread, so a second dispatch would run {@code bridge.close()} twice at the
     * same time (AUDIT-12-2).
     *
     * <p>One-shot on purpose: the latch is never reset, so a process that somehow
     * survives the first event (a reload in the same JVM, a launcher that retries
     * the stop event) will not close the bridge and the lyric worker a second
     * time. Supporting that would mean a real state machine
     * ({@code IDLE → CLOSING → CLOSED} with a reset after {@code CLOSING}), not a
     * boolean — until then the repeated event is logged and dropped (AUDIT-13-3).</p>
     */
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private KeyMapping toggleKey;
    private KeyMapping settingsKey;
    private KeyMapping offsetKey;
    private NowPlayingHud hud;
    /** True once the wheel watch is in place; on GLFW it can only be installed after the window exists. */
    private boolean wheelWatch;
    /** Install attempts, so a backend that never becomes ready does not cost a lookup per tick. */
    private int wheelTries;
    /** The startup log reaches the modifier numbers only once the game is past GLFW init. */
    private boolean keysLogged;
    private boolean screensLogged;
    /** The release check is started once, on the first tick, and never again. */
    private boolean updateKicked;
    private boolean updateLogged;
    /** About ten seconds of ticks: enough for a slow world load, few enough to stop trying. */
    private static final int WHEEL_TRIES = 200;
    private double wheelSteps;
    /** Last seen "offset key plus Ctrl", so the reset fires on the press and not every tick. */
    private boolean offsetCombo;
    /** Ticks to wait after start-up before the first resource-pack reload. */
    private int fontDelay = 60;
    /** Re-attempt spacing for a pack that is not applied yet (C4). */
    private int fontRetry = 0;
    /**
     * Key of the selection being applied, and how many times it was attempted. Reloads
     * already spent on a pack that refuses to apply; each one freezes a frame or two.
     */
    private String fontTried = "";
    private int fontTries;
    /** Attempts per selection: a pack that is not loadable yet is retried, not forgotten (C4). */
    private static final int MAX_FONT_TRIES = 4;
    /** Tracks world joins, which reload resources and give a failed selection another go. */
    private boolean fontInWorld;
    /**
     * Key numbers are asked for by name and read off the running build ({@link Keys#key}).
     * 26.1 declares 341/345 for Ctrl - GLFW key codes - while 26.3 declares 224/228 for
     * the same constants, because it indexes the SDL keyboard-state buffer by scancode. A number compiled into this class would be right on one version and
     * silently a different key on the rest, which is exactly what R28-1 caught. These
     * fallbacks only matter on a build that stopped declaring the field.
     */
    private static final int FALLBACK_LCONTROL = 341;
    private static final int FALLBACK_RCONTROL = 345;

    public static SmtcCraftClient get() {
        return instance;
    }

    /**
     * The version this jar declares, as Fabric resolved it out of {@code fabric.mod.json}.
     * Empty when the container cannot be read, which only happens under a plain test runner.
     */
    public static String version() {
        try {
            return FabricLoader.getInstance().getModContainer("smtcraft")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("");
        } catch (Throwable failed) {
            return "";
        }
    }

    public SmtcConfig config() {
        return config;
    }

    public MediaBridge bridge() {
        return bridge;
    }

    /** Lyric worker; never null after client init. */
    public LyricService lyrics() {
        return lyrics;
    }

    public MusicMuter musicMuter() {
        return musicMuter;
    }

    /** Apply the "show reading" switch: re-parse the cached sheet, no network needed. */
    public void applyReadingMode() {
        org.yinyun.smtcraft.smtc.LrcParser.setReadings(config != null && config.showReading);
        if (lyrics != null) lyrics.reparse();
    }

    /**
     * (Re)writes the resource pack that serves the per-language system fonts and
     * reloads resources. Client thread only - it ends in a full reload.
     */
    public void applyFonts() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || config == null) return;
        if (!config.hasSystemFonts()) {
            FontPack.invalidate();
            return;
        }
        FontPack.invalidate();
        FontPack.apply(mc, config);
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        // every background result is applied through this executor, so no worker
        // thread ever touches the HUD, the renderer or the config fields
        Async.setClientExecutor(runnable -> Minecraft.getInstance().execute(runnable));

        config = SmtcConfig.load();
        musicMuter.restorePending(config);
        bridge = MediaBridges.create(config);
        bridge.start();

        lyrics = new LyricService();
        lyrics.start();

        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.smtcraft.toggle",
                InputConstants.getKey("key.keyboard.f8").getValue(),
                category));

        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.smtcraft.settings",
                InputConstants.getKey("key.keyboard.f9").getValue(),
                category));

        offsetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.smtcraft.offset",
                InputConstants.getKey("key.keyboard.f7").getValue(),
                category));

        // One line says which of the four 26.1-26.4 differences this game build uses;
        // without it a crash report from a snapshot says nothing about the version.
        // Mod Menu reads one answer and never asks again, so the look starts here rather
        // than on the first tick: by the time the mod list opens, the reply is in. It is the
        // only network call the mod makes, so the config can turn it off (AUDIT-46-6).
        if (config.checkUpdates) {
            UpdateCheck.request(version());
        }
        LOG.info("game api: cover={}, screen={}, widget={}, key={}, wheel={}",
                CoverTexture.mode(), ScreenHooks.mode(), Widgets.mode(), Keys.mode(), WheelWatch.mode());
        // The modifier numbers differ per version (AUDIT-28-1) and the log carries the ones in use,
        // but reading them initialises com.mojang.blaze3d.platform.InputConstants, which on 26.1
        // touches GLFW during its static init — fatal here, see the note below. They are logged
        // from the first tick instead, when the window is long since up.
        hud = new NowPlayingHud(this);
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MOD_ID, "now_playing"), hud);
        // The scroll watch waits for the first tick instead of installing here: it needs the
        // window, reading the window initialises com.mojang.blaze3d.platform.Window, and that
        // class touches GLFW - which 26.1 does not survive (GLX._initGlfw checks the GLFW error
        // queue before initialising it, so any earlier GLFW contact kills startup).
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            if (!shuttingDown.compareAndSet(false, true)) {
                LOG.warn("shutdown: already running, ignoring the repeated stop event");
                return;
            }
            // The watchdog is what makes "no thread outlives the process" safe.
            // Checking the elapsed time after the sequence returns cannot cover
            // the case the check exists for — a native call that never returns
            // never reaches that check (AUDIT-13-1). This thread is independent of the
            // finisher's control flow: it fires whether the sequence returns,
            // throws, or hangs, and the finisher interrupts it on the normal path
            // so an honest exit never waits those extra seconds.
            long limit = SHUTDOWN_BUDGET_MS + Budgets.WATCHDOG_GRACE_MS;
            Thread watchdog = Watchdog.arm("smtcraft-watchdog", limit, () -> {
                // The message is best effort; stopping the process is not. Every
                // line above the halt can throw — a launcher that swapped
                // System.err for its own stream and then closed it, or a logging
                // framework that already stopped its appenders — and an exception
                // in the second statement would skip the only statement this
                // thread exists for (AUDIT-14-2).
                try {
                    // halt() runs no shutdown hooks, so the logging framework may
                    // still hold the reason in its buffer; stderr is unbuffered and
                    // goes first for that reason. It is the only record the user is
                    // guaranteed to see.
                    System.err.println("smtcraft: shutdown still running after " + limit
                            + " ms, halting the process");
                    System.err.flush();
                    LOG.warn("shutdown: still running after {} ms, halting", limit);
                } catch (Throwable ignored) {
                    // nothing to report through, and nothing worth reporting
                } finally {
                    // Non-zero: this path means the sequence did not finish in
                    // time, and halt(0) would report that as a clean exit to the
                    // launcher, the crash tracker and any script running us
                    // (AUDIT-14-2).
                    Runtime.getRuntime().halt(HALT_EXIT_UNFINISHED);
                }
            });
            // Only the latch is allowed to come before this line: release() writes
            // the sound option through SoundEngine while the game is tearing the
            // audio device down, which is exactly the kind of call that can block,
            // and a watchdog armed after it would be no watchdog at all — the
            // finisher would never start (AUDIT-14-1).
            // The volume restore sits outside the sequence, so it carries its own
            // catch: every step inside goes through step() and cannot take the
            // rest of the shutdown with it, and this one — which touches
            // SoundEngine, the client instance and the config — must not either.
            // Unguarded, a throw here would skip finisher.start() entirely and
            // leave the watchdog to halt after 22 s with nothing written (AUDIT-15-2).
            try {
                musicMuter.release();
            } catch (Throwable failed) {
                LOG.warn("shutdown: could not restore the music volume ({})", Errors.describe(failed));
            }
            // The sequence itself is serial and can take the whole shutdown budget.
            // It used to run right here, on the client thread, which pumps no
            // messages while it waits: Windows greys the window out after five
            // seconds without a pump, so every round that made one wait longer
            // also made the exit look frozen (AUDIT-11-1). It now runs on a non-daemon
            // thread — the JVM still waits for it before the process goes away,
            // so "no thread outlives the process" keeps holding, but the window
            // does not have to.
            //
            // That premise is "the JVM exits because main returned". System.exit
            // and Runtime.exit run the shutdown hooks and then terminate every
            // thread without waiting, so on a crash or a forced quit this thread
            // can be cut off before "write the config" and "flush the offset
            // table" (AUDIT-14-4). Nothing can be done about the cut-off itself, which
            // is why finishShutdown writes the config once before it starts
            // waiting on anything.
            Thread finisher = new Thread(() -> {
                try {
                    finishShutdown();
                } finally {
                    watchdog.interrupt();
                }
            }, "smtcraft-shutdown");
            finisher.setDaemon(false);
            finisher.start();
        });
    }

    /**
     * The shutdown sequence itself, off the client thread.
     *
     * <p>Anything the background lanes still hold has to reach the disk before
     * the process can go away. Drain first, then write the current state: a
     * queued snapshot is older than what is in memory, so writing it last would
     * undo the newest change.</p>
     *
     * <p>One budget for the whole sequence: each step is reasonable on its own,
     * but 2 s plus a bridge round plus the lyric worker added up to more than ten
     * seconds on Linux, which reads as "closing hangs". Every step gets what is
     * left of the budget instead (AUDIT-76).</p>
     *
     * <p>Two properties hold for everything in here, and any new step has to be
     * checked against both (R15-1, R15-2):</p>
     * <ol>
     *   <li><b>Order.</b> Drain before writing, and write the in-memory state
     *       after the drain — a queued snapshot is older than what is in memory.
     *       The bridge goes first among the waits so it can keep the write
     *       reserve. The watchdog is armed before anything that can block.</li>
     *   <li><b>Isolation.</b> A step that fails only skips itself: every action
     *       runs through {@link #step}, and the two that cannot (the early write
     *       and the volume restore, which happen before the sequence) carry their
     *       own catch for the same reason.</li>
     * </ol>
     */
    private void finishShutdown() {
        long started = System.currentTimeMillis();
        // Now that the client thread no longer blocks here, "the process is still
        // up" is invisible unless it is said out loud: the window is gone and the
        // only other evidence is a task manager entry (AUDIT-12-1).
        LOG.info("shutdown: stopping the bridge, the background lanes and the lyric worker");
        long deadline = started + SHUTDOWN_BUDGET_MS;
        // Hardening for R14-4: a Runtime.exit path can cut this thread off at any
        // point, so the state that is hardest to rebuild gets written before the
        // first long wait rather than after the last one. The later "write the
        // config" step still runs — it picks up anything the steps below change.
        // But not before the drain: an I/O lane may be moving an older snapshot
        // into place right now, and both writers end with ATOMIC_MOVE, so the slower
        // one wins the file. Writing first would let a stale state land after the
        // early write and stay there if the process is cut off before the third step
        // (AUDIT-15-1).
        long drainStart = System.currentTimeMillis();
        boolean quiet = false;
        try {
            quiet = Async.awaitQuiet(Math.min(Budgets.DRAIN_MS,
                    Math.max(Budgets.MIN_WAIT_MS, remaining(deadline))));
        } catch (Throwable failed) {
            LOG.warn("shutdown: the early drain failed ({})", Errors.describe(failed));
        }
        // What has to travel into the sequence is how much of the line was spent, not
        // whether the queue ended up quiet: a timed-out drain returns false having
        // already burned its whole DRAIN_MS, and treating that as "not done yet" made
        // the first step pay for it a second time — the R18-1 leak survived on exactly
        // the slow path it was written for (AUDIT-19-1).
        final long drainedMs = System.currentTimeMillis() - drainStart;
        final boolean drained = quiet;
        try {
            config.saveNow();
        } catch (Throwable failed) {
            LOG.warn("shutdown: the early config write failed ({})", Errors.describe(failed));
        }
        try {
            finishShutdownSteps(started, drained, drainedMs);
        } finally {
            long took = System.currentTimeMillis() - started;
            LOG.info("shutdown: finished in {} ms", took);
            if (took >= SHUTDOWN_BUDGET_MS) {
                // Reaching this line already proves the sequence returned, so the
                // watchdog gets interrupted by the caller before it can fire — the
                // note is all that is left of the overrun. Halting here would be
                // the R13-1 mistake again: a check on the path that only the
                // healthy case travels.
                LOG.warn("shutdown: overran the {} ms budget ({} ms), but finished",
                        SHUTDOWN_BUDGET_MS, took);
            }
        }
    }

    private void finishShutdownSteps(long started, boolean drained, long drainedMs) {
        long deadline = started + SHUTDOWN_BUDGET_MS;
        // One action per step name, then a walk over {@link ShutdownPlan#steps()}.
        // The order is data now, so "is the config written after the drain" is a
        // self-test rather than something a reviewer has to notice (AUDIT-16-3).
        Map<String, Runnable> actions = new LinkedHashMap<>();
        actions.put(ShutdownPlan.DRAIN, () -> {
            // The early write drains before it writes (AUDIT-15-1), so this step is the
            // second chance at a line Budgets registers once: it may spend only what
            // the early attempt did not (R18-1, tightened by R19-1). A quiet queue means
            // nothing is left to wait for; a timed-out attempt means the line is already
            // spent and re-waiting would silently take 2 000 ms from the bridge. The
            // step keeps its name and its place — the order assertions hang off it.
            if (drained) return;
            long left = Budgets.remainingDrain(drainedMs);
            if (left >= Budgets.MIN_WAIT_MS) {
                Async.awaitQuiet(Math.min(left, remaining(deadline)));
            }
        });
        // The bridge goes first among the waits and keeps a reserve for the
        // writes: it is the only step that can legitimately block for ten
        // seconds, and leaving it last handed it whatever the earlier steps had
        // not already spent (AUDIT-81).
        actions.put(ShutdownPlan.BRIDGE, () -> {
            if (bridge != null) {
                bridge.close(Math.max(Budgets.MIN_WAIT_MS,
                        remaining(deadline) - Budgets.WRITE_RESERVE_MS));
            }
        });
        actions.put(ShutdownPlan.WRITE, () -> config.saveNow());
        actions.put(ShutdownPlan.FLUSH, () -> {
            if (lyrics != null) lyrics.flushOffsets();
        });
        actions.put(ShutdownPlan.LANES,
                () -> Async.shutdown(budget(deadline, Budgets.ASYNC_SHUTDOWN_MS)));
        // The interrupt and the queue drain are unconditional; only the two
        // bounded waits inside close() come out of the budget (R8-2, corrected
        // by R9-1 — the earlier comment claimed close() never blocks).
        actions.put(ShutdownPlan.LYRICS, () -> {
            if (lyrics != null) lyrics.close(budget(deadline, Budgets.LYRICS_CLOSE_MS));
        });
        if (actions.size() != ShutdownPlan.steps().size()) {
            // The loop below only walks the plan, so an action keyed by a name the
            // plan does not contain would sit in the map forever and never run —
            // the mirror image of a planned step with no action, which at least
            // warns (AUDIT-17-3).
            LOG.warn("shutdown: {} actions for {} planned steps",
                    actions.size(), ShutdownPlan.steps().size());
        }
        for (String name : ShutdownPlan.steps()) {
            Runnable action = actions.get(name);
            if (action == null) {
                // A name in the plan with no action is a hole in the sequence,
                // not a reason to skip the remaining steps silently.
                LOG.warn("shutdown: the plan names a step with no action ({})", name);
                continue;
            }
            step(name, action);
        }
    }

    /**
     * Runs one shutdown step, recording a failure instead of letting it skip the
     * rest. A bridge that refuses to stop must not take the config write with it:
     * before this, an unchecked exception anywhere in the sequence silently
     * skipped every step after it, and the config and the offset table stayed at
     * their previous values (AUDIT-12-1).
     */
    private static void step(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable failed) {
            LOG.warn("shutdown: {} failed ({})", what, Errors.describe(failed));
        }
    }

    /**
     * The offset key plus the mouse wheel moves the offset of the song being
     * played, 50 ms per notch; scrolling up advances the lyrics, down delays them.
     * Ctrl plus a press puts it back to the sheet's own timing. Per song, because a
     * sheet's timing error belongs to its uploader rather than to the player. The
     * key itself is an ordinary key mapping, so Options can rebind it.
     */
    private void nudgeLyricOffset(double steps) {
        TrackInfo track = bridge == null ? TrackInfo.EMPTY : bridge.latest();
        if (lyrics == null || !track.hasTrack()) return;
        long value = lyrics.adjustOffset(track, Math.round(-steps * 50.0));
        notice(value);
    }

    /** What is left of the shutdown budget. */
    private static long remaining(long deadline) {
        return Math.max(0L, deadline - System.currentTimeMillis());
    }

    /** {@code cap}, or what is left of the shutdown budget when that is smaller. */
    private static long budget(long deadline, long cap) {
        return Math.min(cap, remaining(deadline));
    }

    /**
     * Ctrl, through the same shim the other keys use. The SDL modifier query that used to
     * live here was a hard reference into {@code org.lwjgl.sdl}, which 26.1/26.2 do not
     * ship — the entrypoint class failed to load on those versions before it ever ran.
     */
    private static boolean controlDown() {
        return Keys.isKeyDown(Keys.key("KEY_LCONTROL", FALLBACK_LCONTROL))
                || Keys.isKeyDown(Keys.key("KEY_RCONTROL", FALLBACK_RCONTROL));
    }

    /**
     * True while the offset key is held. Only the mapping is consulted: it carries the player's
     * binding, so rebinding the key in 选项 → 控制 moves the mod with it. An earlier version also
     * OR-ed in the raw F7 state, which made F7 keep working after it had been rebound away and
     * made the combo fire on the wrong key - the mapping is the single source of truth.
     */
    private boolean offsetHeld() {
        return offsetKey != null && offsetKey.isDown();
    }

    /** The offset key as the player sees it, for the startup line. */
    private String offsetName() {
        return offsetKey == null ? "?" : offsetKey.getTranslatedKeyMessage().getString();
    }

    /**
     * Ctrl plus a press of the offset key: back to the sheet's own timing. The
     * modifier state comes from SDL rather than from two hard-coded GLFW key
     * numbers, so a rebind or a different backend cannot silently break it (C9).
     */
    private void resetLyricOffset() {
        TrackInfo track = bridge == null ? TrackInfo.EMPTY : bridge.latest();
        if (lyrics == null || !track.hasTrack()) return;
        notice(lyrics.resetOffset(track));
    }

    private void notice(long value) {
        if (hud != null) {
            hud.notice(Component.translatable("smtcraft.msg.offset", value).getString());
        }
    }

    /**
     * Watches mouse-wheel notches from the side: while the offset key is held they are
     * accumulated here and applied on the next tick. Observing instead of consuming keeps
     * the wheel working for the rest of the game. Which backend serves this is decided by
     * {@link WheelWatch}; on GLFW the window has to exist first, so this retries.
     */
    private void installScrollWatch() {
        if (wheelWatch) return;
        // Nothing here may touch the input backend before the game has a window: on 26.1
        // loading the GLFW class loads its DLL, and a GLFW call before glfwInit makes
        // GLX._initGlfw refuse to start at all ("GLFW error before init: [0x10001]").
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null || client.getWindow().handle() == 0L) return;
        wheelWatch = WheelWatch.install(
                this::offsetHeld,
                steps -> {
                    synchronized (this) {
                        wheelSteps += steps;
                    }
                });
        if (wheelWatch) {
            LOG.info("mouse wheel watch: {}", WheelWatch.route());
        }
    }

    private void onEndTick(Minecraft mc) {
        if (bridge == null) return;
        // Reading the modifier key codes initialises InputConstants, which on 26.1 loads GLFW
        // while GLX._initGlfw has not run yet — the game then dies with
        // "GLFW error before init: [0x10001]". A tick is always after that point.
        if (!keysLogged) {
            keysLogged = true;
            LOG.info("keys: offset={}, LCONTROL={}, RCONTROL={}", offsetName(),
                    Keys.key("KEY_LCONTROL", FALLBACK_LCONTROL),
                    Keys.key("KEY_RCONTROL", FALLBACK_RCONTROL));
        }
        // "hide while a screen is open" is the one option whose whole behaviour hangs on a
        // reflective read: on a build where neither route resolves it silently stops working,
        // and nothing else in the log would say so (AUDIT-34-4).
        if (!screensLogged) {
            screensLogged = true;
            String route = Screens.probe();
            if ("unavailable".equals(route)) {
                LOG.warn("screens: unavailable (no Minecraft.screen field and no Gui.screen());"
                        + " the hide-while-open option does nothing on this build");
            } else {
                LOG.info("screens: {}", route);
            }
        }
        // A newer release is only news if something looks for it; the look is one request on a
        // daemon thread, so the tick does not wait for the network. The Mod Menu badge and the
        // about tab then read whatever answer arrives (AUDIT-45).
        if (!updateKicked) {
            updateKicked = true;
            if (config.checkUpdates) {
                UpdateCheck.request(version());
            }
        }
        if (!updateLogged && UpdateCheck.state().known()) {
            updateLogged = true;
            UpdateCheck.State answer = UpdateCheck.state();
            LOG.info("update: latest={}, available={}, note={}", answer.latest(),
                    answer.available(), answer.note().isEmpty() ? "none" : answer.note());
        }
        // GLFW only hands out a window handle once the window exists, and on 26.4 the SDL
        // event watch refused the first attempt outright, so a failed install is retried for
        // a while instead of being written off: ten seconds covers a slow world load.
        if (!wheelWatch && wheelTries < WHEEL_TRIES) {
            wheelTries++;
            installScrollWatch();
            if (!wheelWatch && wheelTries == WHEEL_TRIES) {
                LOG.warn("mouse wheel watch never installed ({}): {}",
                        WheelWatch.mode(), WheelWatch.reason());
                // A log line is not where a player looks: without the wheel the offset
                // keys look broken rather than absent (AUDIT-28-5).
                if (hud != null) hud.notice(Component.translatable("smtcraft.msg.wheel_off").getString());
            }
        } else if (wheelWatch) {
            // the game registers its own scroll callback on window setup; ours has to win
            WheelWatch.keepAlive();
        }
        // Stage timing: a stalled tick has to name its own culprit, otherwise the
        // "the game freezes for a moment when a song starts" reports are unactionable.
        long tStart = System.nanoTime();

        // the stat and the read happen on the I/O lane; the parsed values are copied
        // in on this thread by the callback, so a slow disk cannot stall the tick
        config.pollReloadAsync();
        bridge.setConfig(config);
        long tReload = System.nanoTime();

        TrackInfo track = bridge.latest();
        if (lyrics != null && config.showLyrics) {
            lyrics.setOrder(config.lyricOrder);
            org.yinyun.smtcraft.smtc.LrcParser.setReadings(config.showReading);
            lyrics.request(track);
            lyrics.recheck(track);
        }
        long tLyrics = System.nanoTime();
        musicMuter.tick(config, track);
        long tMute = System.nanoTime();

        while (toggleKey != null && toggleKey.consumeClick()) {
            config.enabled = !config.enabled;
            config.save();
            if (config.debug) {
                org.slf4j.LoggerFactory.getLogger("smtcraft")
                        .info("HUD {}", config.enabled ? "enabled" : "disabled");
            }
        }

        // A rising edge rather than consumeClick(): the click flag is set by the game's own
        // key dispatch, so depending on it made Ctrl+F7 a no-op whenever the modifier could
        // not be read. Holding the pair resets once, not once per tick.
        boolean offsetCombo = offsetHeld() && controlDown();
        if (offsetCombo && !this.offsetCombo) resetLyricOffset();
        this.offsetCombo = offsetCombo;

        double steps;
        synchronized (this) {
            steps = wheelSteps;
            wheelSteps = 0.0;
        }
        if (steps != 0.0) nudgeLyricOffset(steps);

        while (settingsKey != null && settingsKey.consumeClick()) {
            mc.setScreenAndShow(new SettingsScreen(this));
        }

        // the chosen system fonts need a resource reload, which is not safe during
        // client start-up, so wait a second of ticks first; afterwards a selection
        // that is not applied yet is retried instead of being forgotten (C4)
        // Every apply ends in reloadResourcePacks(), and a resource reload tears
        // down the sound engine too, so an unbounded retry here is audible as a
        // stutter every few seconds. One selection is tried at most twice, and a
        // selection that keeps failing is left alone until the key changes.
        boolean inWorld = mc.level != null;
        if (inWorld != fontInWorld) {
            fontInWorld = inWorld;
            // Entering (or leaving) a world reloads resources, so the pack state may
            // have changed under us; a selection that ran out of attempts gets a new
            // set instead of staying unapplied for the rest of the session (C4).
            if (inWorld) {
                fontTries = 0;
                fontRetry = 0;
            }
        }
        if (fontDelay > 0) {
            if (--fontDelay == 0 && config.hasSystemFonts()) applyFonts();
        } else if (config.hasSystemFonts() && !FontPack.isApplied(config)) {
            String key = config.fontKey();
            if (!key.equals(fontTried)) {
                fontTried = key;
                fontTries = 0;
                fontRetry = 0;
            }
            if (fontTries < MAX_FONT_TRIES && --fontRetry <= 0) {
                fontRetry = 100;
                fontTries++;
                applyFonts();
            }
        } else {
            fontRetry = 0;
            fontTried = "";
        }

        long tEnd = System.nanoTime();
        long totalMs = (tEnd - tStart) / 1_000_000L;
        if (totalMs >= 80L) {
            org.slf4j.LoggerFactory.getLogger(MOD_ID).warn(
                    "slow client tick: total={} ms (config={} ms, lyrics={} ms, mute={} ms, keys/fonts={} ms)",
                    totalMs,
                    (tReload - tStart) / 1_000_000L,
                    (tLyrics - tReload) / 1_000_000L,
                    (tMute - tLyrics) / 1_000_000L,
                    (tEnd - tMute) / 1_000_000L);
        }
    }
}
