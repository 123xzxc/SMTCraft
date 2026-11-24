package org.yinyun.smtcraft.util;

/**
 * 让页面保持"设计时的尺寸"的那一个倍率。
 *
 * <p>页面与 HUD 按 GUI 缩放 2 排版。缩放 4 时每个逻辑像素在玻璃上宽一倍，同样的排版
 * 就占掉两倍屏幕；缩放 1 时反过来只剩一半。乘上 {@code 2 / guiScale} 就把每个缩放档
 * 都拉回缩放 2 的占位。这里是纯计算的一半：不碰任何游戏类型，自测可以离线驱动。</p>
 */
public final class UiScale {

    /** 页面是按这个 GUI 缩放假画的。 */
    public static final int BASE = 2;

    /** 再小就不可读了。 */
    public static final float MIN = 0.4f;

    /** 缩放 1 会要到 2 倍；再大只会让页面无处可放。 */
    public static final float MAX = 2.0f;

    private UiScale() {}

    /** 某个 GUI 缩放对应的倍率；缩放值不合理时给 1。 */
    public static float factor(int guiScale) {
        if (guiScale <= 0) return 1.0f;
        float raw = BASE / (float) guiScale;
        return Math.max(MIN, Math.min(MAX, raw));
    }

    /** 屏幕长度换算成设计像素。 */
    public static int design(int length, float factor) {
        if (!(factor > 0.0f) || Float.isNaN(factor) || Float.isInfinite(factor)) return length;
        return Math.round(length / factor);
    }

    /**
     * 一格滚轮在设计像素里走多远。
     *
     * <p>玻璃上应当移动 14 像素，所以设计空间的步长是 {@code 14 / ui}：不除的话缩放 1 时
     * 一格走 28 像素、缩放 4 时只走 7 像素，界面越小列表跑得越快，正好反了。
     * 一格不会缩成零：小数滚动也至少移动一个设计像素。</p>
     */
    public static int scrollStep(double scrollY, float factor) {
        if (scrollY == 0.0d) return 0;
        if (!(factor > 0.0f) || Float.isNaN(factor) || Float.isInfinite(factor)) {
            int plain = (int) Math.round(scrollY * 14.0d);
            return plain != 0 ? plain : (scrollY > 0.0d ? 1 : -1);
        }
        int step = (int) Math.round(scrollY * 14.0d / factor);
        return step != 0 ? step : (scrollY > 0.0d ? 1 : -1);
    }
}
