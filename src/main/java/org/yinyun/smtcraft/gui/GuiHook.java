package org.yinyun.smtcraft.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.yinyun.smtcraft.SmtcCraftClient;

/**
 * 打开设置页的唯一入口。
 *
 * <p>Fabric 版走的是 Mod Menu 的 {@code ModMenuApi}；1.8.9 里对应的东西是 Forge 的
 * {@code IModGuiFactory}（见 {@link ModConfigGuiFactory}）。两条路都要落到同一个页面，
 * 所以打开动作收在这里，别在别处再 new 一次。</p>
 */
public final class GuiHook {

    private GuiHook() {}

    /** 从当前屏幕打开设置页，关掉时回到它。 */
    public static void openConfig() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        GuiScreen parent = mc.currentScreen instanceof SettingsScreen ? null : mc.currentScreen;
        mc.displayGuiScreen(new SettingsScreen(SmtcCraftClient.get(), parent));
    }

    /** 设置页是否正开着。 */
    public static boolean isOpen() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.currentScreen instanceof SettingsScreen;
    }
}
