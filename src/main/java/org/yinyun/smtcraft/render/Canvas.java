package org.yinyun.smtcraft.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.yinyun.smtcraft.util.GameScale;

/**
 * 1.8.9 的绘制底板。
 *
 * <p>26.x 用 {@code GuiGraphics} 携带一个矩阵栈，1.8.9 只有一场 {@code GlStateManager}
 * 加固定管线的矩阵，
 * 而 {@code glScissor} 又不走矩阵——它要的是物理像素。所以这里把「设计坐标 →
 * GUI 坐标 → 物理像素」的换算收在一处：上层照旧按设计坐标排版，裁剪矩形由这里
 * 换算出屏幕像素，否则面板一缩放，歌词带的裁剪框就和文字对不上。</p>
 *
 * <p>只有平移与等比缩放会被记进 {@link #scissor} 的换算；旋转过的空间（封面唱片）
 * 不参与裁剪，那里也不需要。</p>
 */
public final class Canvas {

    private int guiW;
    private int guiH;
    private int factor;
    private int displayH;
    private FontRenderer font;

    /** 每帧复用同一个实例：HUD 一帧一个 Canvas 的话，60 fps 下每秒几十个对象纯属浪费。 */
    private static Canvas reusable;
    /** 上一次绘制链结束时裁剪还开着（异常路径），下一次 {@link #of} 开头补一次关闭。 */
    private static boolean scissorLeaked;
    /** 当前这批四边形用的颜色：{@code beginFill} 设、{@link #quad} 取。 */
    private float cr = 1.0f;
    private float cg = 1.0f;
    private float cb = 1.0f;
    private float ca = 1.0f;

    /** 3 个 float 一组：tx、ty、scale。深度上限 64 层，超了直接丢栈。 */
    private final float[] stack = new float[192];
    private int depth;
    private float tx;
    private float ty;
    private float scale = 1.0f;
    private boolean rotated;
    private boolean scissoring;

    private Canvas() {}

    /**
     * 取这一帧的绘制底板。
     *
     * <p>返回的是<b>复用的同一个实例</b>，每次调用都会把它复位：上层可以在一个绘制链里
     * 放手用，但不要同时持有两个（例如先取一个给界面、再取一个给 HUD 交叉使用）。1.8.x 的
     * 界面与 HUD 是先后两趟绘制，不重叠，所以这条约束在现有调用点上成立。</p>
     */
    public static Canvas of(Minecraft mc) {
        // 不用 ScaledResolution：它的构造器在 1.8.0 与 1.8.9 之间不一样，见 GameScale#resolution
        Canvas c = reusable;
        if (c == null) {
            c = new Canvas();
            reusable = c;
        }
        int[] r = GameScale.resolution(mc);
        c.guiW = r[0];
        c.guiH = r[1];
        c.factor = r[2];
        c.displayH = mc.displayHeight;
        c.font = mc.fontRendererObj;
        c.depth = 0;
        c.tx = 0.0f;
        c.ty = 0.0f;
        c.scale = 1.0f;
        c.rotated = false;
        c.cr = 1.0f;
        c.cg = 1.0f;
        c.cb = 1.0f;
        c.ca = 1.0f;
        if (scissorLeaked) {
            // 上一帧在裁剪里抛了异常：不补这一步，整块画面都会被上一帧的矩形切掉
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            scissorLeaked = false;
        }
        c.scissoring = false;
        return c;
    }

    public int width() { return guiW; }
    public int height() { return guiH; }

    /** 一个 GUI 像素等于几个设备像素（缩放不跟 GUI 走时，面板靠它对齐整像素）。 */
    public int pixelScale() { return factor; }
    public FontRenderer font() { return font; }

    public void push() {
        if (depth * 3 + 3 > stack.length) return;
        stack[depth * 3] = tx;
        stack[depth * 3 + 1] = ty;
        stack[depth * 3 + 2] = scale;
        depth++;
        GlStateManager.pushMatrix();
    }

    public void pop() {
        if (depth <= 0) return;
        depth--;
        tx = stack[depth * 3];
        ty = stack[depth * 3 + 1];
        scale = stack[depth * 3 + 2];
        GlStateManager.popMatrix();
        rotated = false;
    }

    public void translate(float x, float y) {
        tx += x * scale;
        ty += y * scale;
        GlStateManager.translate(x, y, 0.0f);
    }

    public void scale(float k) {
        scale(k, k);
    }

    public void scale(float sx, float sy) {
        // 平移量是屏幕空间的常量：等比缩小时它自己不该跟着缩，否则锚点会跑偏
        scale *= sx;
        GlStateManager.scale(sx, sy, 1.0f);
    }

    /** 旋转只给封面唱片用；旋转之后裁剪换算失效，所以顺手关掉裁剪。 */
    public void rotateDeg(float deg) {
        rotated = true;
        GlStateManager.rotate(deg, 0.0f, 0.0f, 1.0f);
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
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.color(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        vertex(x0, y1);
        vertex(x1, y1);
        vertex(x1, y0);
        vertex(x0, y0);
        GL11.glEnd();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        // 原版 drawRect 也是画完就把贴图开关收回：FontRenderer 自己只 bind 贴图、
        // 不开 GL_TEXTURE_2D，留着关掉状态会让整屏文字变成一个个实心方块。
        // 1.8.9 的状态缓存挂在 GlStateManager 上，所以这里必须走它——直接 glEnable
        // 不会更新缓存，FontRenderer 之后就会以为贴图还开着，字形画成全白方块。
        GlStateManager.enableTexture2D();
    }

    /**
     * 实心圆角矩形：整条形状一次 {@code glBegin} 提交。
     *
     * <p>逐行调用 {@link #fill} 时每一行都要关一次贴图、开一次贴图；HUD 面板每帧只有
     * 几百次这样的状态切换，在低端机和高 UI 缩放下会被放大成帧时间抖动。</p>
     */
    public void fillRound(int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0) return;
        if (!beginFill(argb)) return;
        int rad = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        for (int row = 0; row < h; row++) {
            int inset = inset(rad, row, h);
            quad(x + inset, y + row, x + w - inset, y + row + 1);
        }
        GL11.glEnd();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.enableTexture2D();
    }

    /** 1 像素圆角描边，逐行画两端，拐角跟着填充一起弯；同样只提交一次。 */
    public void outlineRound(int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0) return;
        if (!beginFill(argb)) return;
        int rad = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        for (int row = 0; row < h; row++) {
            int inset = inset(rad, row, h);
            int left = x + inset;
            int right = x + w - inset;
            if (left >= right) continue;
            if (row == 0 || row == h - 1) {
                quad(left, y + row, right, y + row + 1);
            } else {
                quad(left, y + row, left + 1, y + row + 1);
                quad(right - 1, y + row, right, y + row + 1);
            }
        }
        GL11.glEnd();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.enableTexture2D();
    }

    /** 开始一次不贴图的批量填充；颜色全透明时返回 false，调用方连 {@code draw()} 都不用做。 */
    private boolean beginFill(int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        if (a <= 0.0f) return false;
        cr = ((argb >> 16) & 0xFF) / 255.0f;
        cg = ((argb >> 8) & 0xFF) / 255.0f;
        cb = (argb & 0xFF) / 255.0f;
        ca = a;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.color(cr, cg, cb, ca);
        GL11.glBegin(GL11.GL_QUADS);
        return true;
    }

    /**
     * 写一个顶点。颜色由 {@code beginFill} 一次设好：固定管线里颜色是顶点状态，
     * 不必每个顶点重写。
     *
     * <p>用 {@code glBegin} 而不是 {@code Tessellator}：顶点格式常量
     * （{@code DefaultVertexFormats.POSITION_COLOR}）在 1.8.0 上没有对应的 srg 字段，
     * 只存在于 1.8.8/1.8.9，跨版本必炸 {@code NoSuchFieldError}；GL11 的立即模式
     * 两者名字完全一样。</p>
     */
    private void vertex(double x, double y) {
        GL11.glVertex2d(x, y);
    }

    private void quad(int x0, int y0, int x1, int y1) {
        if (x1 <= x0 || y1 <= y0) return;
        vertex(x0, y1);
        vertex(x1, y1);
        vertex(x1, y0);
        vertex(x0, y0);
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
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(tex);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glBegin(GL11.GL_QUADS);
        texVertex(x0, y1, 0.0D, 1.0D);
        texVertex(x1, y1, 1.0D, 1.0D);
        texVertex(x1, y0, 1.0D, 0.0D);
        texVertex(x0, y0, 0.0D, 0.0D);
        GL11.glEnd();
        // 同样把贴图开关还回去，否则紧跟着 drawString 的字形会画成方块
        GlStateManager.enableTexture2D();
    }

    /** 带 UV 的顶点：贴图坐标必须紧挨着它自己的顶点写，否则上一张图会被拖过来。 */
    private void texVertex(double x, double y, double u, double v) {
        GL11.glTexCoord2d(u, v);
        GL11.glVertex2d(x, y);
    }

    /**
     * 按设计坐标裁剪。矩形会先随当前平移/缩放换算成屏幕 GUI 坐标，再按 GUI 缩放
     * 换算成物理像素——1.8.9 的 {@code glScissor} 照样不认矩阵，只认物理像素。
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
        // 直到 scissorOff/restore 为止都算"开着"：中途抛异常时下一帧靠这个标志补一次关闭
        scissorLeaked = true;
    }

    public void scissorOff() {
        if (!scissoring) return;
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        scissoring = false;
        scissorLeaked = false;
    }

    /** 一帧结束时把 GL 状态还给游戏，否则后面的 HUD 元素会继承我们的混合设置。 */
    public void restore() {
        scissorOff();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        // 原版 GUI 一直是开着 alpha test 的：关掉之后字形的透明像素会被当成实色
        GlStateManager.enableAlpha();
    }
}
