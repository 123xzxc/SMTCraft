package org.yinyun.smtcraft;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import java.io.File;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.yinyun.smtcraft.audio.MusicMuter;
import org.yinyun.smtcraft.font.FontPack;
import org.yinyun.smtcraft.gui.GuiHook;
import org.yinyun.smtcraft.media.MediaBridge;
import org.yinyun.smtcraft.media.MediaBridges;
import org.yinyun.smtcraft.render.Canvas;
import org.yinyun.smtcraft.render.NowPlayingHud;
import org.yinyun.smtcraft.smtc.LrcParser;
import org.yinyun.smtcraft.smtc.LyricService;
import org.yinyun.smtcraft.smtc.TrackInfo;
import org.yinyun.smtcraft.util.Async;
import org.yinyun.smtcraft.util.Budgets;
import org.yinyun.smtcraft.util.Errors;
import org.yinyun.smtcraft.util.Keys;
import org.yinyun.smtcraft.util.ShutdownPlan;
import org.yinyun.smtcraft.util.Watchdog;

/**
 * 客户端一侧的全部装配：配置、媒体桥、歌词服务、按键、HUD 挂钩与关停序列。
 *
 * <p>26.x 版把所有东西写在 {@code ClientModInitializer.onInitializeClient()} 里；
 * 1.7.10 的 Forge 没有那层抽象，而入口类又不能在服务端被加载（它一碰
 * {@code Minecraft} 专用服务端就炸），于是这里按 Forge 的老规矩用 {@code @SidedProxy}
 * 把客户端实现分出来。</p>
 */
@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    /** LWJGL 一格滚轮是 120。 */
    private static final int WHEEL_PER_NOTCH = 120;

    /** 一次 tick 里最多补做多少个跨线程回调，剩下的留给下一 tick，免得卡帧。 */
    private static final int CLIENT_QUEUE_PER_TICK = 64;

    private final MusicMuter musicMuter = new MusicMuter();
    /**
     * 后台线程把结果交回客户端线程的队列，由 tick 抽干。
     *
     * <p>26.x 用 {@code Minecraft.execute()} 投递；1.7.10 里对应的方法是 SRG 名
     * （{@code func_152344_a}），不同构建上名字会变，所以自己排队自己抽干 ——
     * 语义一样，且不依赖映射。</p>
     */
    private final Queue<Runnable> clientQueue = new ConcurrentLinkedQueue<Runnable>();

    private NowPlayingHud hud;
    private KeyBinding toggleKey;
    private KeyBinding settingsKey;
    private KeyBinding offsetKey;
    private boolean toggleWasDown;
    private boolean settingsWasDown;
    private boolean offsetCombo;
    private double wheelSteps;
    private boolean updateKicked;
    private boolean fontsLogged;
    private boolean fontChecked;
    private volatile boolean shuttingDown;

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        // 配置要到 init 阶段才读：那里已经能确定游戏目录，而且 preInit 时
        // Minecraft.getMinecraft() 可能还是 null（专服）—— 这里保持空。
    }

    @Override
    public void init(FMLInitializationEvent event) {
        final SmtcCraftClient mod = SmtcCraftClient.get();
        Minecraft mc = Minecraft.getMinecraft();
        if (mod == null || mc == null) {
            SmtcCraftClient.LOG.warning("client init without a mod instance or a game; HUD stays off");
            return;
        }
        Async.setClientExecutor(new Executor() {
            @Override
            public void execute(Runnable task) {
                if (task != null) clientQueue.add(task);
            }
        });

        // 配置、桥脚本、歌词缓存统一落在游戏目录下：第三方启动器会改工作目录，按 user.dir 拼
        // 会让设置、脚本、歌词各跑一处，换启动器看起来就像配置丢了
        File dataDir = Minecraft.getMinecraft().mcDataDir;
        File configDir = dataDir == null ? null : new File(dataDir, "config");
        SmtcConfig config = SmtcConfig.load(configDir == null
                ? new File("config/smtcraft/smtcraft.json")
                : new File(configDir, "smtcraft/smtcraft.json"));
        MediaBridge bridge = MediaBridges.create(config, configDir);
        LyricService lyrics = configDir == null
                ? new LyricService()
                : new LyricService(new File(configDir, "smtcraft/lyrics").toPath());
        mod.install(config, bridge, lyrics);

        try {
            musicMuter.restorePending(config);
        } catch (Throwable failed) {
            SmtcCraftClient.LOG.log(Level.WARNING, "could not restore the music volume", failed);
        }
        bridge.start();
        lyrics.start();

        toggleKey = register("key.smtcraft.toggle", Keyboard.KEY_F8);
        settingsKey = register("key.smtcraft.settings", Keyboard.KEY_F9);
        offsetKey = register("key.smtcraft.offset", Keyboard.KEY_F7);

        hud = new NowPlayingHud(mod);
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        armShutdownHook();

        SmtcCraftClient.LOG.info("SMTCraft " + SmtcCraftClient.version() + " on Forge 1.7.10: reader="
                + MediaBridges.tool() + ", keys=" + Keys.mode());
    }

    @Override
    public void applyFonts() {
        // 1.7.10 的字形来自 FontRenderer 的贴图，没有 JSON font provider，
        // 所以"换系统字体"在这里只能记进配置：说一次，不假装生效。
        if (fontsLogged) return;
        fontsLogged = true;
        Minecraft mc = Minecraft.getMinecraft();
        SmtcConfig config = SmtcCraftClient.get() == null ? null : SmtcCraftClient.get().config();
        if (mc == null || config == null || !config.hasSystemFonts()) return;
        FontPack.apply(mc, config);
        SmtcCraftClient.LOG.info("system fonts are recorded but not applied: " + FontPack.problem());
    }

    private static KeyBinding register(String key, int code) {
        KeyBinding binding = new KeyBinding(key, code, "key.categories.smtcraft.main");
        ClientRegistry.registerKeyBinding(binding);
        return binding;
    }

    /** 整个进程退出时跑一次关停序列；Minecraft 自己的 shutdown 里音频设备已经在拆。 */
    private void armShutdownHook() {
        Thread hook = new Thread(new Runnable() {
            @Override
            public void run() {
                finishShutdown();
            }
        }, "smtcraft-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (Throwable failed) {
            SmtcCraftClient.LOG.log(Level.WARNING, "shutdown hook refused: " + Errors.describe(failed));
        }
    }

    /**
     * 关停：先安静后台通道，再按 {@link ShutdownPlan} 的顺序走完。
     *
     * <p>顺序与预算都在别处定义（{@code util/ShutdownPlan}、{@code util/Budgets}），
     * 这里只负责"每一步拿剩下的预算"和"一步失败不带走后面的"。看门狗在第一次阻塞
     * 之前就起来：它要覆盖的正是"某一步永不返回"那种情况。</p>
     */
    private void finishShutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        final SmtcCraftClient mod = SmtcCraftClient.get();
        if (mod == null) return;
        long started = System.currentTimeMillis();
        final long deadline = started + Budgets.SHUTDOWN_MS;
        Thread watchdog = Watchdog.arm("smtcraft-watchdog",
                Budgets.SHUTDOWN_MS + Budgets.WATCHDOG_GRACE_MS, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            System.err.println("smtcraft: shutdown still running after "
                                    + Budgets.SHUTDOWN_MS + " ms, halting the process");
                            System.err.flush();
                        } catch (Throwable ignored) {
                            // 没有能报信的流了
                        } finally {
                            Runtime.getRuntime().halt(1);
                        }
                    }
                });
        try {
            step("restore the music volume", new Runnable() {
                @Override
                public void run() {
                    musicMuter.release();
                }
            });
            // 先抽干再写盘：排队的快照比内存旧，先写盘会被它盖回去。
            long drainStart = System.currentTimeMillis();
            try {
                Async.awaitQuiet(budget(deadline, Budgets.DRAIN_MS));
            } catch (Throwable failed) {
                SmtcCraftClient.LOG.warning("shutdown: early drain failed (" + Errors.describe(failed) + ")");
            }
            final long drainedMs = System.currentTimeMillis() - drainStart;
            step("write the config", new Runnable() {
                @Override
                public void run() {
                    mod.config().saveNow();
                }
            });
            for (String name : ShutdownPlan.steps()) {
                runStep(name, deadline, drainedMs);
            }
            step("release the artwork texture", new Runnable() {
                @Override
                public void run() {
                    if (hud != null) hud.close();
                }
            });
        } finally {
            watchdog.interrupt();
            SmtcCraftClient.LOG.info("shutdown: finished in " + (System.currentTimeMillis() - started) + " ms");
        }
    }

    private void runStep(String name, long deadline, long drainedMs) {
        SmtcCraftClient mod = SmtcCraftClient.get();
        if (mod == null) return;
        if (ShutdownPlan.DRAIN.equals(name)) {
            final long left = Budgets.remainingDrain(drainedMs);
            if (left <= 0L) return;
            step(name, new Runnable() {
                @Override
                public void run() {
                    Async.awaitQuiet(left);
                }
            });
        } else if (ShutdownPlan.BRIDGE.equals(name)) {
            final long slice = budget(deadline, Budgets.CLOSE_GRACE_MS);
            final MediaBridge bridge = mod.bridge();
            if (bridge == null) return;
            step(name, new Runnable() {
                @Override
                public void run() {
                    bridge.close(slice);
                }
            });
        } else if (ShutdownPlan.WRITE.equals(name)) {
            step(name, new Runnable() {
                @Override
                public void run() {
                    mod.config().saveNow();
                }
            });
        } else if (ShutdownPlan.FLUSH.equals(name)) {
            final LyricService lyrics = mod.lyrics();
            if (lyrics == null) return;
            step(name, new Runnable() {
                @Override
                public void run() {
                    lyrics.flushOffsets();
                }
            });
        } else if (ShutdownPlan.LANES.equals(name)) {
            final long slice = budget(deadline, Budgets.ASYNC_SHUTDOWN_MS);
            step(name, new Runnable() {
                @Override
                public void run() {
                    Async.shutdown(slice);
                }
            });
        } else if (ShutdownPlan.LYRICS.equals(name)) {
            final long slice = budget(deadline, Budgets.LYRICS_CLOSE_MS);
            final LyricService lyrics = mod.lyrics();
            if (lyrics == null) return;
            step(name, new Runnable() {
                @Override
                public void run() {
                    lyrics.close(slice);
                }
            });
        }
    }

    private static void step(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable failed) {
            SmtcCraftClient.LOG.warning("shutdown: " + what + " failed (" + Errors.describe(failed) + ")");
        }
    }

    private static long budget(long deadline, long cap) {
        long left = Math.max(0L, deadline - System.currentTimeMillis());
        return Math.max(Budgets.MIN_WAIT_MS, Math.min(cap, left));
    }

    /** HUD 的绘制入口：1.7.10 在 HUD 画完文字之后发一次 {@code Post(TEXT)}。 */
    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        if (shuttingDown || hud == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        Canvas c = Canvas.of(mc);
        try {
            hud.render(c);
        } catch (Throwable failed) {
            // 一帧画砸了不该让整个 HUD 消失：记一次，下一帧继续
            SmtcCraftClient.LOG.log(Level.WARNING, "HUD frame failed (" + Errors.describe(failed) + ")");
        } finally {
            try {
                c.restore();
            } catch (Throwable ignored) {
                // GL 上下文已经在拆了
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (shuttingDown) return;
        SmtcCraftClient mod = SmtcCraftClient.get();
        // 队列先抽干：装配完成前投进来的界面回调也要按 tick 跑掉，不能压在队列里
        drainClientQueue();
        if (mod == null || mod.bridge() == null) return;
        if (!fontChecked) {
            fontChecked = true;
            ensureUnicodeFont(mod.config());
        }
        if (!updateKicked) {
            updateKicked = true;
            if (mod.config().checkUpdates) org.yinyun.smtcraft.util.UpdateCheck.request(SmtcCraftClient.version());
        }
        long tStart = System.nanoTime();
        mod.config().poll();
        mod.bridge().setConfig(mod.config());
        TrackInfo track = mod.bridge().latest();
        if (mod.config().showLyrics) {
            mod.lyrics().setOrder(mod.config().lyricOrder);
            LrcParser.setReadings(mod.config().showReading);
            mod.lyrics().request(track);
            mod.lyrics().recheck(track);
        }
        musicMuter.tick(mod.config(), track);
        handleKeys(mod, track);
        long totalMs = (System.nanoTime() - tStart) / 1000000L;
        if (totalMs >= 80L) {
            SmtcCraftClient.LOG.warning("slow client tick: " + totalMs + " ms");
        }
    }

    /**
     * 1.7.10 的默认字体贴图只有 ASCII 页：
     * 中文歌名、日文歌词在没开 unicode 字体时整片是方块和杂符。
     * 只改 gameSettings 字段不够，还得 refreshResources 让 FontRenderer 重新按全字库建页。
     */
    private void ensureUnicodeFont(SmtcConfig cfg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) return;
        if (!cfg.autoUnicodeFont || mc.gameSettings.forceUnicodeFont) return;
        try {
            mc.gameSettings.forceUnicodeFont = true;
            mc.gameSettings.saveOptions();
            mc.refreshResources();
            SmtcCraftClient.LOG.info("enabled the vanilla Unicode font (config: autoUnicodeFont): 1.7.10 has no CJK glyphs without it");
        } catch (Throwable failed) {
            SmtcCraftClient.LOG.log(Level.WARNING, "could not enable the Unicode font (" + Errors.describe(failed) + ")");
        }
    }

    private void drainClientQueue() {
        for (int i = 0; i < CLIENT_QUEUE_PER_TICK; i++) {
            Runnable task = clientQueue.poll();
            if (task == null) return;
            try {
                task.run();
            } catch (Throwable failed) {
                SmtcCraftClient.LOG.log(Level.WARNING, "queued client task failed (" + Errors.describe(failed) + ")");
            }
        }
    }

    /** 按键：开关、设置页、以及"延迟键 + 滚轮"的微调。 */
    private void handleKeys(SmtcCraftClient mod, TrackInfo track) {
        boolean toggleDown = toggleKey != null && toggleKey.getIsKeyPressed();
        if (toggleDown && !toggleWasDown) {
            mod.config().enabled = !mod.config().enabled;
            mod.config().save();
            if (hud != null) {
                hud.notice(StatCollector.translateToLocal(mod.config().enabled
                        ? "smtcraft.msg.enabled" : "smtcraft.msg.disabled"));
            }
        }
        toggleWasDown = toggleDown;

        boolean settingsDown = settingsKey != null && settingsKey.getIsKeyPressed();
        if (settingsDown && !settingsWasDown) GuiHook.openConfig();
        settingsWasDown = settingsDown;

        boolean held = offsetKey != null && offsetKey.getIsKeyPressed();
        int wheel = Mouse.getDWheel();
        if (held && wheel != 0) {
            synchronized (this) {
                wheelSteps += (double) wheel / (double) WHEEL_PER_NOTCH;
            }
        }
        double steps;
        synchronized (this) {
            steps = wheelSteps;
            wheelSteps = 0.0d;
        }
        if (steps != 0.0d && mod.lyrics() != null && track != null && track.hasTrack()) {
            long value = mod.lyrics().adjustOffset(track, Math.round(-steps * 50.0d));
            if (hud != null) hud.notice(StatCollector.translateToLocalFormatted("smtcraft.msg.offset", value));
        }
        boolean combo = held && controlDown();
        if (combo && !offsetCombo && mod.lyrics() != null && track != null && track.hasTrack()) {
            if (hud != null) hud.notice(StatCollector.translateToLocalFormatted("smtcraft.msg.offset",
                    mod.lyrics().resetOffset(track)));
        }
        offsetCombo = combo;
    }

    private static boolean controlDown() {
        return Keys.isKeyDown(Keys.key("KEY_LCONTROL", Keyboard.KEY_LCONTROL))
                || Keys.isKeyDown(Keys.key("KEY_RCONTROL", Keyboard.KEY_RCONTROL));
    }
}
