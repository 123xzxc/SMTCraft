package org.yinyun.smtcraft.gui;

import java.io.IOException;

import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;
import org.yinyun.smtcraft.render.Canvas;
import org.yinyun.smtcraft.render.Draw;
import org.yinyun.smtcraft.util.GameScale;

/**
 * 按"设计像素"排版的一层屏幕底座：页面写的是设计像素，指针事件换算回设计像素。
 *
 * <p>页面是按 GUI 缩放 2 画的。缩放 4 时同样的逻辑像素在玻璃上宽一倍，十四行的页签挤成一块；
 * 缩放 1 时又只剩一半。所以排版坐标用 {@link #vw()} / {@link #vh()}，绘制时整体乘以
 * {@link #ui()}，点击时再除回去。</p>
 *
 * <p>1.8.9 的 {@code GuiScreen} 没有控件列表可用，也不再走 {@code super.drawScreen}：
 * 背景自己铺，控件由页面在 {@link #drawFrame} 里画。</p>
 */
public abstract class ScaledScreen extends GuiScreen {

    private float ui = 1.0f;

    protected ScaledScreen() {}

    /** 重读倍率。要在 {@link #initGui()} 里、排版之前调用一次。 */
    protected final void refreshUi(boolean follow) {
        ui = GameScale.factor(follow);
    }

    /** 本页当前的绘制倍率；不跟随 GUI 缩放时为 1。 */
    protected final float ui() {
        return ui;
    }

    /** 设计像素下的视口宽。 */
    protected final int vw() {
        return Math.max(1, Math.round(this.width / ui));
    }

    /** 设计像素下的视口高。 */
    protected final int vh() {
        return Math.max(1, Math.round(this.height / ui));
    }

    /** 屏幕坐标换算成设计像素。 */
    protected final int toDesign(double value) {
        return (int) Math.round(value / ui);
    }

    /** 设计要求每帧都要重画，暂停游戏会让音乐与歌词一起冻住，所以不暂停。 */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        // 背景铺在整块窗口上，且必须在缩放之前：缩放之后铺只会留下外面一圈没画
        drawDefaultBackground();
        Canvas c = Canvas.of(mc);
        int mx = toDesign(mouseX);
        int my = toDesign(mouseY);
        c.push();
        if (ui != 1.0f) c.scale(ui);
        try {
            drawFrame(c, mx, my);
            drawOverlay(c, mx, my);
        } finally {
            // 矩阵必须无条件弹出：页面里抛异常时栈会越积越深，之后每帧都在上一层缩放上再缩
            c.pop();
            c.restore();
        }
    }

    /** 画除背景之外的一切，坐标是设计像素。 */
    protected abstract void drawFrame(Canvas c, int mouseX, int mouseY);

    /** 画页面边框之外的东西（标题、滚动条、状态行），在设计像素里。 */
    protected void drawOverlay(Canvas c, int mouseX, int mouseY) {
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (onClick(toDesign(mouseX), toDesign(mouseY), button)) return;
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long heldMs) {
        if (onDrag(toDesign(mouseX), toDesign(mouseY), button)) return;
        super.mouseClickMove(mouseX, mouseY, button, heldMs);
    }

    /**
     * 松开鼠标。1.8.9 的 {@code GuiScreen} 没有 1.7.10 那个把"移动"和"抬起"合在一起的
     * {@code mouseMovedOrUp}，只剩 {@code mouseReleased}：移动不再走这里，所以 {@code which}
     * 一定是真正的按键号，不用再判 -1。
     */
    @Override
    protected void mouseReleased(int mouseX, int mouseY, int which) {
        if (onRelease(toDesign(mouseX), toDesign(mouseY), which)) return;
        super.mouseReleased(mouseX, mouseY, which);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0 || mc == null) return;
        int x = Mouse.getEventX() * this.width / mc.displayWidth;
        int y = this.height - Mouse.getEventY() * this.height / mc.displayHeight - 1;
        onWheel(toDesign(x), toDesign(y), d > 0 ? 1 : -1);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (onKey(typedChar, keyCode)) return;
        super.keyTyped(typedChar, keyCode);
    }

    /** 设计像素下的点击；返回 true 表示本页已处理。 */
    protected boolean onClick(int mouseX, int mouseY, int button) {
        return false;
    }

    /** 设计像素下的拖动；返回 true 表示本页已处理。 */
    protected boolean onDrag(int mouseX, int mouseY, int button) {
        return false;
    }

    /** 设计像素下的松开；返回 true 表示本页已处理。 */
    protected boolean onRelease(int mouseX, int mouseY, int button) {
        return false;
    }

    /** 一格滚轮；{@code dir} 为 1（下）或 -1（上）。 */
    protected void onWheel(int mouseX, int mouseY, int dir) {
    }

    /** 键盘输入；返回 true 表示本页已处理（文本框吞掉字符）。 */
    protected boolean onKey(char typedChar, int keyCode) {
        return false;
    }

    /** 画一条 1 像素的横向分隔线，设计像素。 */
    protected static void hline(Canvas c, int x, int y, int w, int argb) {
        Draw.fillRound(c, x, y, Math.max(1, w), 1, 0, argb);
    }
}
