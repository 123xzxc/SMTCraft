package org.yinyun.smtcraft;

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
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventBus;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
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
 * 1.8.9 的 Forge 没有那层抽象，而入口类又不能在服务端被加载（它一碰
 * {@code Minecraft} 专用服务端就炸），于是这里按 Forge 的老规矩用 {@code @SidedProxy}
 * 把客户端实现分出来。</p>
 */
@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    /** LWJGL 一格滚轮是 120。 */
    private static final int WHEEL_PER_NOTCH = 120;

    /** 一次 tick 里最多补做多少个跨线程回调，剩下的留给下一 tick，免得卡帧。 */
    private static final int CLIENT_QUEUE_PER_TICK = 64;

    // 关停步骤的编号。1.8.0 走到进程退出时模组的类加载器已经关了，那一步再 new 任何类都会
    // NoClassDefFoundError，所以步骤作为编号传进 stepNow，动作写在已加载的 ClientProxy 里。
    private static final int STEP_MUSIC = 1;
    private static final int STEP_CONFIG = 2;
    private static final int STEP_HUD = 3;
    private static final int STEP_DRAIN = 4;
    private static final int STEP_BRIDGE = 5;
    private static final int STEP_FLUSH = 6;
    private static final int STEP_LANES = 7;
    private static final int STEP_LYRICS = 8;

    /** 看门狗在 init 就建好：退出时再 new 会撞上已经关掉的类加载器。 */
    private final Runnable shutdownWatchdog = new ShutdownWatchdog();

    private final MusicMuter musicMuter = new MusicMuter();
    /**
     * 后台线程把结果交回客户端线程的队列，由 tick 抽干。
     *
     * <p>26.x 用 {@code Minecraft.execute()} 投递。这里不依赖版本相关的投递入口
     * （1.8.9 与 1.8.9 各叫各的名字，1.8.9 是 {@code addScheduledTask}），自己排队、
     * 在客户端 tick 里抽干 —— 语义一样，且不受映射与版本差异影响。</p>
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
        shutdownLogDir = dataDir == null ? null : new File(dataDir, "logs");
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
        registerFmlBus();
        armShutdownHook();

        SmtcCraftClient.LOG.info("SMTCraft " + SmtcCraftClient.version() + " on Forge 1.8.9: reader="
                + MediaBridges.tool() + ", keys=" + Keys.mode());
    }

    @Override
    public void applyFonts() {
        // 1.8.9 的字形同样来自 FontRenderer 的贴图，没有 JSON font provider，
        // 所以"换系统字体"在这里只能记进配置：说一次，不假装生效。
        if (fontsLogged) return;
        fontsLogged = true;
        Minecraft mc = Minecraft.getMinecraft();
        SmtcConfig config = SmtcCraftClient.get() == null ? null : SmtcCraftClient.get().config();
        if (mc == null || config == null || !config.hasSystemFonts()) return;
        FontPack.apply(mc, config);
        SmtcCraftClient.LOG.info("system fonts are recorded but not applied: " + FontPack.problem());
    }

    /**
     * 1.8.0 的 {@code FMLCommonHandler} 自己 {@code new} 了一个独立的 {@code EventBus}，
     * {@code TickEvent.ClientTickEvent} 只发到那里——不注册这个总线，HUD 不会更新、热键也不响应
     * （模组看起来"只显示等待播放"）。1.8.9 起两个总线合并成同一个，{@code bus()} 因此才被标
     * {@code deprecated}，这时再注册一遍会让每个 {@code @SubscribeEvent} 挂两次（tick 跑两遍）。
     * 所以按"是不是同一个对象"决定，两个版本都对。
     */
    @SuppressWarnings("deprecation")
    private void registerFmlBus() {
        EventBus fml = FMLCommonHandler.instance().bus();
        if (fml != MinecraftForge.EVENT_BUS) fml.register(this);
    }

    private static KeyBinding register(String key, int code) {
        KeyBinding binding = new KeyBinding(key, code, "key.categories.smtcraft.main");
        ClientRegistry.registerKeyBinding(binding);
        return binding;
    }

    /** 整个进程退出时跑一次关停序列；Minecraft 自己的 shutdown 里音频设备已经在拆。 */
    private void armShutdownHook() {
        warmShutdownPath();
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
     * 退出阶段模组的类加载器已经关了。关停路径上凡是"第一次用到才加载"的类都要在 init 期定义好，
     * 否则那一步会以 NoClassDefFoundError 静默跳过（实测 1.8.0 上就是这样：配置没写盘、
     * 桥进程留着、音量没还原，控制台只留一行 NoClassDefFoundError: ClientProxy$3）。
     *
     * <p>这里只负责"定义"，不触发静态初始化：{@code initialize=false}。</p>
     */
    private static void warmShutdownPath() {
        ClassLoader loader = ClientProxy.class.getClassLoader();
        String[] names = {
            "java.io.File",
            "java.io.FileOutputStream",
            "org.yinyun.smtcraft.util.Watchdog",
            "org.yinyun.smtcraft.util.Budgets",
            "org.yinyun.smtcraft.util.ShutdownPlan",
            "org.yinyun.smtcraft.util.Async",
            "org.yinyun.smtcraft.util.Errors",
        };
        for (String name : names) {
            try {
                Class.forName(name, false, loader);
            } catch (Throwable ignored) {
                // 取不到就照旧走：那一步自己会记失败
            }
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
                Budgets.SHUTDOWN_MS + Budgets.WATCHDOG_GRACE_MS, shutdownWatchdog);
        try {
            stepNow("restore the music volume", STEP_MUSIC, 0L);
            // 先抽干再写盘：排队的快照比内存旧，先写盘会被它盖回去。
            long drainStart = System.currentTimeMillis();
            try {
                Async.awaitQuiet(budget(deadline, Budgets.DRAIN_MS));
            } catch (Throwable failed) {
                trace("shutdown: early drain failed (" + Errors.describe(failed) + ")");
            }
            final long drainedMs = System.currentTimeMillis() - drainStart;
            // 这里与计划表的 WRITE 步各写一次，是有意的两次：排队的快照比内存旧，抽干后必须立刻
            // 落盘才不会被它盖回去；WRITE 步是计划表里的契约，删掉任一处都会让顺序重新变得含糊
            stepNow("write the config", STEP_CONFIG, 0L);
            for (String name : ShutdownPlan.steps()) {
                runStep(name, deadline, drainedMs);
            }
            stepNow("release the artwork texture", STEP_HUD, 0L);
        } finally {
            watchdog.interrupt();
            trace("shutdown: finished in " + (System.currentTimeMillis() - started) + " ms");
        }
    }

    private void runStep(String name, long deadline, long drainedMs) {
        if (ShutdownPlan.DRAIN.equals(name)) {
            long left = Budgets.remainingDrain(drainedMs);
            if (left <= 0L) return;
            stepNow(name, STEP_DRAIN, left);
        } else if (ShutdownPlan.BRIDGE.equals(name)) {
            stepNow(name, STEP_BRIDGE, budget(deadline, Budgets.CLOSE_GRACE_MS));
        } else if (ShutdownPlan.WRITE.equals(name)) {
            stepNow(name, STEP_CONFIG, 0L);
        } else if (ShutdownPlan.FLUSH.equals(name)) {
            stepNow(name, STEP_FLUSH, 0L);
        } else if (ShutdownPlan.LANES.equals(name)) {
            stepNow(name, STEP_LANES, budget(deadline, Budgets.ASYNC_SHUTDOWN_MS));
        } else if (ShutdownPlan.LYRICS.equals(name)) {
            stepNow(name, STEP_LYRICS, budget(deadline, Budgets.LYRICS_CLOSE_MS));
        }
    }

    /**
     * 走一步关停。步骤按编号分派、动作写在本类里：这一步不许新建类（匿名类也不行），
     * 因为进程退出阶段模组的类加载器已经关了（见 {@link #warmShutdownPath()}）。
     */
    private void stepNow(String what, int step, long arg) {
        SmtcCraftClient mod = SmtcCraftClient.get();
        try {
            switch (step) {
                case STEP_MUSIC:
                    musicMuter.release();
                    break;
                case STEP_CONFIG:
                    if (mod != null) mod.config().saveNow();
                    break;
                case STEP_HUD:
                    if (hud != null) hud.close();
                    break;
                case STEP_DRAIN:
                    Async.awaitQuiet(arg);
                    break;
                case STEP_BRIDGE: {
                    MediaBridge bridge = mod == null ? null : mod.bridge();
                    if (bridge != null) bridge.close(arg);
                    break;
                }
                case STEP_FLUSH: {
                    LyricService lyrics = mod == null ? null : mod.lyrics();
                    if (lyrics != null) lyrics.flushOffsets();
                    break;
                }
                case STEP_LANES:
                    Async.shutdown(arg);
                    break;
                case STEP_LYRICS: {
                    LyricService lyrics = mod == null ? null : mod.lyrics();
                    if (lyrics != null) lyrics.close(arg);
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable failed) {
            trace("shutdown: " + what + " failed (" + Errors.describe(failed) + ")");
        }
    }

    /** 关停自查文件所在目录：{@code init} 期按 {@code mcDataDir} 算好，退出期不再碰 MC 类型。 */
    private static File shutdownLogDir;

    private static final long SHUTDOWN_LOG_MAX_BYTES = 64L * 1024L;

    /**
     * 退出阶段的输出既要绕开 JUL，也要绕开 {@code System.err}：Forge 1.8 把两者都接进了 log4j
     * （日志里的 {@code [STDOUT]} 前缀就是证据），关停时 appender 已经关了。实测正常退出：配置
     * 确实写盘（文件时间戳与 {@code Stopping!} 同秒），控制台一行没留。所以追加到
     * {@code logs/smtcraft-shutdown.log}，关停自查就看这个文件。
     *
     * <p>目录跟着 {@code mcDataDir} 走（第三方启动器会改工作目录，按 {@code user.dir} 拼会让自查
     * 找错地方）；退出期只用 init 期算好的静态字段，不碰 Minecraft 类型。文件超过
     * {@link #SHUTDOWN_LOG_MAX_BYTES} 就在这次退出时重开，免得无限增长。</p>
     */
    private static void trace(String line) {
        java.io.FileOutputStream out = null;
        try {
            java.io.File dir = shutdownLogDir == null ? new java.io.File("logs") : shutdownLogDir;
            dir.mkdirs();
            java.io.File file = new java.io.File(dir, "smtcraft-shutdown.log");
            // 只追加不截断会随版本一直长；超过上限就这一次退出重开一个
            out = new java.io.FileOutputStream(file, file.length() <= SHUTDOWN_LOG_MAX_BYTES);
            out.write((line + "\n").getBytes("UTF-8"));
        } catch (Throwable ignored) {
            // 连文件都写不了就只剩内存里的状态，别再抛
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                    // 关不掉也无所谓，进程马上就没了
                }
            }
        }
        try {
            SmtcCraftClient.LOG.fine(line);
        } catch (Throwable ignored) {
            // 日志系统已经关了，文件那份就是全部
        }
    }

    private static long budget(long deadline, long cap) {
        long left = Math.max(0L, deadline - System.currentTimeMillis());
        return Math.max(Budgets.MIN_WAIT_MS, Math.min(cap, left));
    }

    /**
     * 卡住时把进程打死的看门狗。init 阶段就实例化（见 {@code shutdownWatchdog}），
     * 所以退出时它已经是加载好的类；run 里只用 JDK 的类型。
     */
    private static final class ShutdownWatchdog implements Runnable {
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
    }

    /** HUD 的绘制入口：1.8.9 在 HUD 画完文字之后发一次 {@code Post(TEXT)}。 */
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
     * 1.8.9 的默认字体贴图同样只有 ASCII 页：
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
            SmtcCraftClient.LOG.info("enabled the vanilla Unicode font (config: autoUnicodeFont): 1.8.9 has no CJK glyphs without it");
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
        boolean toggleDown = toggleKey != null && toggleKey.isKeyDown();
        if (toggleDown && !toggleWasDown) {
            mod.config().enabled = !mod.config().enabled;
            mod.config().save();
            if (hud != null) {
                hud.notice(StatCollector.translateToLocal(mod.config().enabled
                        ? "smtcraft.msg.enabled" : "smtcraft.msg.disabled"));
            }
        }
        toggleWasDown = toggleDown;

        boolean settingsDown = settingsKey != null && settingsKey.isKeyDown();
        if (settingsDown && !settingsWasDown) GuiHook.openConfig();
        settingsWasDown = settingsDown;

        boolean held = offsetKey != null && offsetKey.isKeyDown();
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
