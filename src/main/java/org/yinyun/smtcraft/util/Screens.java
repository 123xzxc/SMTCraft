package org.yinyun.smtcraft.util;

import net.minecraft.client.Minecraft;

/**
 * 游戏当前是否停在某个界面上。
 *
 * <p>Fabric 版要跨 26.1–26.4 用反射找 {@code Minecraft.screen} 或 {@code Gui.screen()}；
 * 1.8.9 只有 {@code currentScreen} 一个字段，读不到就答"没有界面"——把面板藏起来
 * 比画在界面后面更亏。</p>
 */
public final class Screens {

    private Screens() {}

    /** 有任意界面开着（背包、暂停、设置、聊天……）时为 true。 */
    public static boolean menuOpen() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.currentScreen != null;
    }

    /** 本版读界面的方式，供启动日志与反馈用。 */
    public static String mode() {
        return "currentScreen";
    }

    /** 探一次并把 {@link #mode()} 给出来。 */
    public static String probe() {
        return mode();
    }
}
