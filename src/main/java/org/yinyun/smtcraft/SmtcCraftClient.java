package org.yinyun.smtcraft;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import java.util.logging.Logger;
import org.yinyun.smtcraft.media.MediaBridge;
import org.yinyun.smtcraft.smtc.LrcParser;
import org.yinyun.smtcraft.smtc.LyricService;

/**
 * 1.7.10 Forge 入口。
 *
 * <p>这个类刻意不碰 {@code net.minecraft.client.*}：{@code @SidedProxy} 让 Forge 在
 * 客户端加载 {@link ClientProxy}、在专用服务端加载 {@link CommonProxy}，因此同一个 jar
 * 装进服务端不会抛 {@code NoClassDefFoundError}。它只保管三样平台无关的东西——
 * 配置、媒体桥、歌词服务——真正的客户端装配在代理里。</p>
 */
@Mod(modid = SmtcCraftClient.MOD_ID, name = "SMTCraft", version = "@VERSION@",
        guiFactory = "org.yinyun.smtcraft.gui.ModConfigGuiFactory",
        acceptedMinecraftVersions = "[1.7.10]",
        acceptableRemoteVersions = "*")
public final class SmtcCraftClient {

    public static final String MOD_ID = "smtcraft";

    static final Logger LOG = Logger.getLogger(MOD_ID);

    @SidedProxy(clientSide = "org.yinyun.smtcraft.ClientProxy",
            serverSide = "org.yinyun.smtcraft.CommonProxy")
    public static CommonProxy proxy;

    private static SmtcCraftClient instance;

    private SmtcConfig config;
    private MediaBridge bridge;
    private LyricService lyrics;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        instance = this;
        if (proxy != null) proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (proxy != null) proxy.init(event);
    }

    public static SmtcCraftClient get() {
        return instance;
    }

    /**
     * 版本号来自 mcmod.info（构建时由 gradle.properties 展开）。
     *
     * <p>26.x 版从 {@code fabric.mod.json} 读；1.7.10 的对应物是 Forge 的
     * {@code ModMetadata}。读不到就返回空串——离线测试与开发环境下这很正常，
     * 更新检查会因此直接跳过比较。</p>
     */
    public static String version() {
        try {
            cpw.mods.fml.common.ModContainer container = Loader.instance().getIndexedModList().get(MOD_ID);
            return container == null || container.getMetadata() == null ? "" : container.getMetadata().version;
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

    /** 歌词工作线程；客户端 init 之后不为 null。 */
    public LyricService lyrics() {
        return lyrics;
    }

    /** 客户端组装完成后由 {@link ClientProxy} 交进来。 */
    void install(SmtcConfig config, MediaBridge bridge, LyricService lyrics) {
        this.config = config;
        this.bridge = bridge;
        this.lyrics = lyrics;
    }

    /** 切换"显示注音"：只重解析缓存里的歌词表，不联网。 */
    public void applyReadingMode() {
        LrcParser.setReadings(config != null && config.showReading);
        if (lyrics != null) lyrics.reparse();
    }

    /** 换字体：1.7.10 没有运行期字体注入点，客户端代理只记一次日志。 */
    public void applyFonts() {
        if (proxy != null) proxy.applyFonts();
    }
}
