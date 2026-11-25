package org.yinyun.smtcraft.util;

import net.minecraft.client.Minecraft;

/**
 * 面板缩放与 GUI 缩放的换算。
 *
 * <p>面板是按"GUI 缩放为 2"这个基准设计的。GUI 缩放改成 4，同一块面板会占掉
 * 两倍的屏幕；改成 1 又只剩一半。所以跟随 GUI 缩放时，用当前缩放除以 2 去抵消
 * 这个变化（AUDIT-36-1）。</p>
 */
public final class GameScale {

    private static final float BASE = 2.0f;

    /**
     * 上一次算出的结果与它的输入。HUD 每帧都要问一次倍率，而窗口尺寸/GUI 缩放一秒最多变几次，
     * 没必要每帧重算——这里只是把纯函数的结果按输入缓存起来（单线程使用：渲染线程与客户端 tick）。
     */
    private static int cachedWidth = -1;
    private static int cachedHeight = -1;
    private static int cachedGui = -1;
    private static int[] cached;

    private GameScale() {}

    public static float factor(boolean followGuiScale) {
        if (!followGuiScale) return 1.0f;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return 1.0f;
        int f = Math.max(1, resolution(mc)[2]);
        return f / BASE;
    }


    /**
     * 自己算 {@code ScaledResolution}：1.8.0 的 {@code ScaledResolution} 只有
     * {@code (Minecraft, int, int)} 这一个构造器，1.8.9 又只有 {@code (Minecraft)}；
     * 直接 new 哪一个都会在另一个版本上 {@code NoSuchMethodError}
     * （1.8.0 + Forge 11.14.4.1577 上实测崩在 HUD 渲染）。
     *
     * <p>算法照抄原版：缩放倍数是能让画面不低于 320×240 的最大整数，{@code guiScale}
     * 为 0 表示"自动"，按无限大算。</p>
     *
     * <p>返回值是<b>共享的缓存数组</b>：调用方只读，不要改它，也不要留到下一次调用之后
     * （下次输入变化时同一个数组会被覆写）。</p>
     *
     * @return {@code {scaledWidth, scaledHeight, scaleFactor}}
     */
    public static int[] resolution(Minecraft mc) {
        int width = Math.max(1, mc.displayWidth);
        int height = Math.max(1, mc.displayHeight);
        int gui = mc.gameSettings == null ? 0 : mc.gameSettings.guiScale;
        if (cached != null && width == cachedWidth && height == cachedHeight && gui == cachedGui) {
            return cached;
        }
        if (gui <= 0) gui = 1000;
        int scale = 1;
        while (scale < gui && width / (scale + 1) >= 320 && height / (scale + 1) >= 240) scale++;
        if (cached == null) cached = new int[3];
        cached[0] = (int) Math.ceil(width / (double) scale);
        cached[1] = (int) Math.ceil(height / (double) scale);
        cached[2] = scale;
        cachedWidth = width;
        cachedHeight = height;
        cachedGui = gui;
        return cached;
    }
}
