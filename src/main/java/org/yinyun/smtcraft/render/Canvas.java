package org.yinyun.smtcraft.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import org.lwjgl.opengl.GL11;

/**
 * 1.7.10 的绘制底板。
 *
 * <p>26.x 用 {@code GuiGraphics} 携带一个矩阵栈，1.7.10 只有固定管线的 GL 矩阵，
 * 而 {@code glScissor} 又不走矩阵——它要的是物理像素。所以这里把「设计坐标 →
 * GUI 坐标 → 物理像素」的换算收在一处：上层照旧按设计坐标排版，裁剪矩形由这里
 * 换算出屏幕像素，否则面板一缩放，歌词带的裁剪框就和文字对不上。</p>
 *
 * <p>只有平移与等比缩放会被记进 {@link #scissor} 的换算；旋转过的空间（封面唱片）
 * 不参与裁剪，那里也不需要。</p>
 */
public final class Canvas {

    private final int guiW;
    private final int guiH;
    private final int factor;
    private final int displayH;
    private final FontRenderer font;

    /** 3 个 float 一组：tx、ty、scale。深度上限 64 层，超了直接丢栈。 */
    private final float[] stack = new float[192];
    private int depth;
    private float tx;
    private float ty;
    private float scale = 1.0f;
    private boolean rotated;
    private boolean scissoring;

    private Canvas(Minecraft mc, ScaledResolution sr) {
        this.guiW = sr.getScaledWidth();
        this.guiH = sr.getScaledHeight();
        this.factor = sr.getScaleFactor();
        this.displayH = mc.displayHeight;
        this.font = mc.fontRenderer;
    }

    public static Canvas of(Minecraft mc) {
        return new Canvas(mc, new ScaledResolution(mc, mc.displayWidth, mc.displayHeight));
    }

    public int width() { return guiW; }
    public int height() { return guiH; }
    public FontRenderer font() { return font; }

    public void push() {
        if (depth * 3 + 3 > stack.length) return;
        stack[depth * 3] = tx;
        stack[depth * 3 + 1] = ty;
        stack[depth * 3 + 2] = scale;
        depth++;
        GL11.glPushMatrix();
    }

    public void pop() {
        if (depth <= 0) return;
        depth--;
        tx = stack[depth * 3];
        ty = stack[depth * 3 + 1];
        scale = stack[depth * 3 + 2];
        GL11.glPopMatrix();
        rotated = false;
    }

    public void translate(float x, float y) {
        tx += x * scale;
        ty += y * scale;
        GL11.glTranslatef(x, y, 0.0f);
    }

    public void scale(float k) {
        scale(k, k);
    }

    public void scale(float sx, float sy) {
        // 平移量是屏幕空间的常量：等比缩小时它自己不该跟着缩，否则锚点会跑偏
        scale *= sx;
        GL11.glScalef(sx, sy, 1.0f);
    }

    /** 旋转只给封面唱片用；旋转之后裁剪换算失效，所以顺手关掉裁剪。 */
    public void rotateDeg(float deg) {
        rotated = true;
        GL11.glRotatef(deg, 0.0f, 0.0f, 1.0f);
    }

    public void fill(int x0, int y0, int x1, int y1, int argb) {
        if (x1 < x0) { int t = x0; x0 = x1; x1 = t; }
        if (y1 < y0) { int t = y0; y0 = y1; y1 = t; }
        if (x1 <= x0 || y1 <= y0) return;
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        if (a <= 0.0f) return;
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(r, g, b, a);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorRGBA_F(r, g, b, a);
        t.addVertex(x0, y1, 0.0D);
        t.addVertex(x1, y1, 0.0D);
        t.addVertex(x1, y0, 0.0D);
        t.addVertex(x0, y0, 0.0D);
        t.draw();
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        // 原版 drawRect 也是画完就把贴图开关收回：FontRenderer 自己只 bind 贴图、
        // 不开 GL_TEXTURE_2D，留着关掉状态会让整屏文字变成一个个实心方块
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /**
     * 实心圆角矩形：整条形状一次 {@code startDrawingQuads} 提交。
     *
     * <p>逐行调用 {@link #fill} 时每一行都要关一次贴图、开一次贴图；HUD 面板每帧只有
     * 几百次这样的状态切换，在低端机和高 UI 缩放下会被放大成帧时间抖动。</p>
     */
    public void fillRound(int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0) return;
        if (!beginFill(argb)) return;
        int rad = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        Tessellator t = Tessellator.instance;
        for (int row = 0; row < h; row++) {
            int inset = inset(rad, row, h);
            quad(t, x + inset, y + row, x + w - inset, y + row + 1);
        }
        t.draw();
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** 1 像素圆角描边，逐行画两端，拐角跟着填充一起弯；同样只提交一次。 */
    public void outlineRound(int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0) return;
        if (!beginFill(argb)) return;
        int rad = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        Tessellator t = Tessellator.instance;
        for (int row = 0; row < h; row++) {
            int inset = inset(rad, row, h);
            int left = x + inset;
            int right = x + w - inset;
            if (left >= right) continue;
            if (row == 0 || row == h - 1) {
                quad(t, left, y + row, right, y + row + 1);
            } else {
                quad(t, left, y + row, left + 1, y + row + 1);
                quad(t, right - 1, y + row, right, y + row + 1);
            }
        }
        t.draw();
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** 开始一次不贴图的批量填充；颜色全透明时返回 false，调用方连 {@code draw()} 都不用做。 */
    private boolean beginFill(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        if (a <= 0.0f) return false;
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(r, g, b, a);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorRGBA_F(r, g, b, a);
        return true;
    }

    private static void quad(Tessellator t, int x0, int y0, int x1, int y1) {
        if (x1 <= x0 || y1 <= y0) return;
        t.addVertex(x0, y1, 0.0D);
        t.addVertex(x1, y1, 0.0D);
        t.addVertex(x1, y0, 0.0D);
        t.addVertex(x0, y0, 0.0D);
    }

    /** 圆角矩形里某一行的水平内缩量。 */
    private static int inset(int radius, int row, int height) {
        if (radius <= 0) return 0;
        double centre = radius - 0.5;
        double dy;
        if (row < radius) {
            dy = centre - row;
        } else if (row >= height - radius) {
            dy = row - (height - radius - 0.5);
        } else {
            return 0;
        }
        if (dy <= 0.0) return 0;
        double dx = Math.sqrt(Math.max(0.0, (double) radius * radius - dy * dy));
        return (int) Math.round(radius - dx);
    }

    /** 贴一整张表：{@code tex} 是 GL 纹理号，UV 用 0..1 的相对值。 */
    public void blit(int tex, int x0, int y0, int x1, int y1) {
        if (tex <= 0 || x1 <= x0 || y1 <= y0) return;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.setColorRGBA_F(1.0f, 1.0f, 1.0f, 1.0f);
        t.addVertexWithUV(x0, y1, 0.0D, 0.0D, 1.0D);
        t.addVertexWithUV(x1, y1, 0.0D, 1.0D, 1.0D);
        t.addVertexWithUV(x1, y0, 0.0D, 1.0D, 0.0D);
        t.addVertexWithUV(x0, y0, 0.0D, 0.0D, 0.0D);
        t.draw();
        // 同样把贴图开关还回去，否则紧跟着 drawString 的字形会画成方块
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /**
     * 按设计坐标裁剪。矩形会先随当前平移/缩放换算成屏幕 GUI 坐标，再按 GUI 缩放
     * 换算成物理像素——1.7.10 的 {@code glScissor} 只认物理像素。
     */
    public void scissor(int x0, int y0, int x1, int y1) {
        if (rotated) return;
        float sx0 = tx + x0 * scale;
        float sy0 = ty + y0 * scale;
        float sx1 = tx + x1 * scale;
        float sy1 = ty + y1 * scale;
        int px = (int) Math.floor(sx0 * factor);
        int pw = Math.max(0, (int) Math.ceil((sx1 - sx0) * factor));
        int ph = Math.max(0, (int) Math.ceil((sy1 - sy0) * factor));
        int py = displayH - (int) Math.ceil(sy1 * factor);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(px, py, pw, ph);
        scissoring = true;
    }

    public void scissorOff() {
        if (!scissoring) return;
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        scissoring = false;
    }

    /** 一帧结束时把 GL 状态还给游戏，否则后面的 HUD 元素会继承我们的混合设置。 */
    public void restore() {
        scissorOff();
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        // 原版 GUI 一直是开着 alpha test 的：关掉之后字形的透明像素会被当成实色
        GL11.glEnable(GL11.GL_ALPHA_TEST);
    }
}
