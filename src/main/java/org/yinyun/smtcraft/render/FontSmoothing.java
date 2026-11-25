package org.yinyun.smtcraft.render;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * 让位图字形在非整数缩放下平滑采样。
 *
 * <p>1.8.x 的字形是 8 px 位图图集，贴图默认 {@code GL_NEAREST}：面板按 1.76 这种设备缩放画时，
 * 最近邻会跳过纹素行、把竖笔画采成双线（标题顶行像被削掉）。把字形页临时改成线性过滤就不会掉行，
 * 代价是小字略微变柔——比分档缩放好：滑杆仍然是无级的。设备缩放本身就贴着整数时（1:1 采样），
 * 线性与最近邻结果相同，所以那条路径根本不用进这里。</p>
 *
 * <p>放大走 {@code MAG_FILTER}，缩小走 {@code MIN_FILTER}——只改前者的话，"面板比设计尺寸
 * 还小"这一半情况（例如 {@code scale 0.5} × GUI 缩放 2 ≈ 0.88 设备像素）仍然在用最近邻，
 * 掉行照旧。两个参数一起改，两个方向都平稳。</p>
 *
 * <p>过滤参数是<b>贴图对象</b>的状态，不是绘制状态：改完当帧必须改回去，否则菜单、聊天框的字
 * 也会被双线性放大糊掉。字体页要到 {@code FontRenderer} 真的绑定它才拿得到 id，所以靠
 * {@link Text#draw} 每画完一段回报一次当前绑定（{@link #note()}）。</p>
 */
public final class FontSmoothing {

    /** 一帧里最多记这么多页：中英混排通常只碰到 2–6 页，超了说明变化太频繁，不再扩大开销。 */
    private static final int MAX_PAGES = 128;
    private static final Set<Integer> TOUCHED = new HashSet<Integer>();
    private static int previous = -1;
    private static boolean active;

    private FontSmoothing() {}

    /** 开始一段需要平滑采样的绘制；{@code smooth} 为假时整段都不碰贴图参数。 */
    public static void begin(boolean smooth) {
        if (!smooth || active) return;
        TOUCHED.clear();
        previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        active = true;
    }

    /** 画完一段文字后调用：把刚绑定的那一页改成线性过滤（第一次遇到时才写参数）。 */
    public static void note() {
        if (!active) return;
        int id = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (id == 0 || TOUCHED.size() >= MAX_PAGES || TOUCHED.contains(Integer.valueOf(id))) return;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        // 缩小方向走 MIN_FILTER：字形页没有 mipmap，线性过滤等于双线性插值，不会取到不存在的层
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        TOUCHED.add(Integer.valueOf(id));
    }

    /** 结束：改过的页恢复最近邻，绑定还原成进入时的贴图（{@code GlStateManager} 的缓存一并更新）。 */
    public static void end() {
        if (!active) return;
        active = false;
        for (Integer id : TOUCHED) {
            GlStateManager.bindTexture(id.intValue());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        }
        TOUCHED.clear();
        if (previous > 0) GlStateManager.bindTexture(previous);
        previous = -1;
    }
}
