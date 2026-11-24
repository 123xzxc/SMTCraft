package org.yinyun.smtcraft.font;

import net.minecraft.client.Minecraft;
import org.yinyun.smtcraft.SmtcConfig;

/**
 * 字体包：Fabric 版会在 {@code resourcepacks/smtcraft_fonts/} 里生成一个带 ttf provider
 * 的资源包再重载资源，让 HUD 用上系统里任意一款字体。
 *
 * <p>1.7.10 没有 font provider 这套东西——字形来自 {@code FontRenderer} 的张集贴图，
 * 换字体只能换贴图或换 unicode 标志，没有"运行期声明一款 ttf"的入口。所以这里退化成
 * 一个诚实的空实现：接口全部保留（设置页与自测都依赖这些名字），{@link #supported()}
 * 答 false，{@link #problem()} 给出原因，设置页据此把字体槽画成不可用。</p>
 */
public final class FontPack {

    /** 曾经的资源包名，保留给设置页显示路径。 */
    public static final String PACK_NAME = "smtcraft_fonts";
    /** 曾经生成的字体 id。 */
    public static final String FONT_ID = "smtcraft:systemfont";

    private static final String[] SLOTS = {"latin", "cjk", "japanese", "other"};

    private static volatile String problem = "1.7.10 has no runtime font providers";
    private static volatile String appliedKey = "";

    private FontPack() {}

    /** 这一版的客户端能不能用自选字体。 */
    public static boolean supported() {
        return false;
    }

    /** 最近一次失败的原因；没有失败时为空串。 */
    public static String problem() {
        return problem;
    }

    /** 某个槽位自己的字体 id，例如 {@code smtcraft:latin}。 */
    public static String slotFontId(String slot) {
        return "smtcraft:" + slot;
    }

    /** 该槽位是否已有可用字体；这一版永远是 false。 */
    public static boolean hasSlot(String slot) {
        return false;
    }

    /** 配置里的字体是否已经生效。 */
    public static boolean isApplied(SmtcConfig cfg) {
        return false;
    }

    /** 配置是否还需要应用一次；永远是 false，免得客户端每帧重试。 */
    public static boolean needsApply(SmtcConfig cfg) {
        return false;
    }

    /** 尝试应用字体；这一版直接返回 false 并记下原因。 */
    public static boolean apply(Minecraft mc, SmtcConfig cfg) {
        problem = "1.7.10 has no runtime font providers";
        appliedKey = "";
        return false;
    }

    /** 是否正在写字体包；永远 false。 */
    public static boolean busy() {
        return false;
    }

    /** 忘掉已应用的字体选择。 */
    public static void invalidate() {
        appliedKey = "";
    }

    /** 四个槽位名，设置页按这个顺序排版。 */
    public static String[] slots() {
        return SLOTS.clone();
    }
}
