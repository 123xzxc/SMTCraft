package org.yinyun.smtcraft.render;

import net.minecraft.client.gui.FontRenderer;
import org.lwjgl.opengl.GL11;

/**
 * 文字度量与小段绘制。
 *
 * <p>26.x 用 {@code Component} 携带字体与样式，1.7.10 的 {@code FontRenderer}
 * 只认 {@code \u00a7} 格式码，而且 {@code getStringWidth} 不会把粗体多出来的那
 * 一像素算进去。逐字卡拉 OK 是逐字形绘制的，宽度差一像素就会让整行逐字偏移，
 * 所以宽度与绘制必须走同一个函数。</p>
 */
public final class Text {

    /** 1.7.10 字形行高，写死成常量以免依赖不同映射下的字段名。 */
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
     * @param argb 透明度 0 时整段不画：1.7.10 会把 alpha 为 0 的颜色改成不透明，
     *             淡入淡出的最后几帧会因此突然变亮
     */
    public static void draw(FontRenderer font, String text, int x, int y, int argb, boolean bold,
                            boolean italic, boolean shadow) {
        if (font == null || text == null || text.isEmpty()) return;
        if ((argb >>> 24) == 0) return;
        String s = styled(text, bold, italic);
        // FontRenderer 只 bind 字体贴图，不负责打开 GL_TEXTURE_2D / alpha test：
        // 上游任何一次「关贴图画矩形」漏了恢复，这里就会把每个字形画成实心方块
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (shadow) {
            font.drawStringWithShadow(s, x, y, argb);
        } else {
            font.drawString(s, x, y, argb);
        }
    }

    public static void drawCentered(FontRenderer font, String text, int centerX, int y, int argb,
                                    boolean bold, boolean italic, boolean shadow) {
        int w = width(font, text, bold);
        draw(font, text, centerX - w / 2, y, argb, bold, italic, shadow);
    }
}
