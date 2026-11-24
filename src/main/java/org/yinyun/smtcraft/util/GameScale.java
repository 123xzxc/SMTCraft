package org.yinyun.smtcraft.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

/**
 * 面板缩放与 GUI 缩放的换算。
 *
 * <p>面板是按"GUI 缩放为 2"这个基准设计的。GUI 缩放改成 4，同一块面板会占掉
 * 两倍的屏幕；改成 1 又只剩一半。所以跟随 GUI 缩放时，用当前缩放除以 2 去抵消
 * 这个变化（AUDIT-36-1）。</p>
 */
public final class GameScale {

    private static final float BASE = 2.0f;

    private GameScale() {}

    public static float factor(boolean followGuiScale) {
        if (!followGuiScale) return 1.0f;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return 1.0f;
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int f = Math.max(1, sr.getScaleFactor());
        return f / BASE;
    }
}
