package org.yinyun.smtcraft.render;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/**
 * 文字度量与小段绘制。
 *
 * <p>26.x 用 {@code Component} 携带字体与样式，1.8.9 的 {@code FontRenderer}
 * 只认 {@code \u00a7} 格式码，而且 {@code getStringWidth} 不会把粗体多出来的那
 * 一像素算进去。逐字卡拉 OK 是逐字形绘制的，宽度差一像素就会让整行逐字偏移，
 * 所以宽度与绘制必须走同一个函数。</p>
 */
public final class Text {

    /** 1.8.9 字形行高，写死成常量以免依赖不同映射下的字段名。 */
    public static final int HEIGHT = 9;
    private static final String BOLD = "\u00a7l";
    private static final String ITALIC = "\u00a7o";

    private Text() {}

    /** 把样式码拼在文字前面，这样一段文字就能带粗体/斜体而不动矩阵。 */
    public static String styled(String text, boolean bold, boolean italic) {
        if (text == null || text.isEmpty()) return "";
        if (!bold && !italic) return text;
        return (bold ? BOLD : "") + (italic ? ITALIC : "") + text;
    }

    public static int width(FontRenderer font, String text, boolean bold) {
        if (font == null || text == null || text.isEmpty()) return 0;
        int w = font.getStringWidth(text);
        return bold ? w + 1 : w;
    }

    /**
     * @param argb 透明度 0 时整段不画：1.8.9 会把 alpha 为 0 的颜色改成不透明，
     *             淡入淡出的最后几帧会因此突然变亮
     */
    public static void draw(FontRenderer font, String text, int x, int y, int argb, boolean bold,
                            boolean italic, boolean shadow) {
        if (font == null || text == null || text.isEmpty()) return;
        if ((argb >>> 24) == 0) return;
        String s = styled(text, bold, italic);
        // FontRenderer 只 bind 字体贴图，不负责打开贴图开关 / alpha test：
        // 上游任何一次「关贴图画矩形」漏了恢复，这里就会把每个字形画成实心方块。
        // 1.8.9 的开关状态由 GlStateManager 缓存，直接调 GL11 只会让缓存与实际不一致，
        // 所以这里的每一次 enable 都必须走 GlStateManager。
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        if (shadow) {
            font.drawStringWithShadow(s, x, y, argb);
        } else {
            font.drawString(s, x, y, argb);
        }
        // 字形页的过滤参数由调用方按缩放按需切换，这里回报刚绑定的是哪一页（见 FontSmoothing）
        FontSmoothing.note();
    }

    public static void drawCentered(FontRenderer font, String text, int centerX, int y, int argb,
                                    boolean bold, boolean italic, boolean shadow) {
        int w = width(font, text, bold);
        draw(font, text, centerX - w / 2, y, argb, bold, italic, shadow);
    }
}
