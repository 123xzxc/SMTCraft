package org.yinyun.smtcraft.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.yinyun.smtcraft.render.Canvas;
import org.yinyun.smtcraft.render.Draw;
import org.yinyun.smtcraft.render.Text;
import org.yinyun.smtcraft.util.GameScale;

/**
 * ARGB 取色页：十六进制输入、R/G/B(/A) 四条通道滑条、一组常用色块，加一条"原值 / 新值"对照。
 *
 * <p>1.8.9 没有可用的控件列表：{@code GuiButton} 只认固定 id 与固定贴图，{@code GuiTextField}
 * 又自带一套屏幕像素坐标，两者都撑不起设计像素的排版。所以排版、命中与绘制都留在本页——
 * 控件从 {@link Controls} 取，形状只走 {@link Canvas} 与 {@link Draw}。</p>
 */
public final class ColorPickerScreen extends ScaledScreen {

    /** 常用色块：白灰黑三档，一层饱和色，最后两个深底色。 */
    private static final String[] SWATCHES = {
            "#FFFFFFFF", "#FFB8BCC4", "#FF7F8A99", "#FF000000",
            "#FFE4405F", "#FFFF7A45", "#FFFFC845", "#FF57C785",
            "#FF3FA9F5", "#FF6C6CF0", "#FFB45CF0", "#FFFF5FA2",
            "#FF1ED760", "#FF19B5FE", "#FFF5F5F5", "#FF2B2F38"};

    private static final int ROW_H = 18;
    private static final int STEP = 22;
    private static final int INK = 0xFFFFFFFF;
    private static final int SUBTLE = 0xFF9FB2C8;
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int PER_ROW = 8;

    private final ScaledScreen parent;
    private final String labelKey;
    private final java.util.function.Consumer<String> onPick;
    private final boolean allowAlpha;
    /** 打开本页时的颜色，只用于左侧对照；滑条怎么拖都不动它。 */
    private final int original;

    private final List<Controls.Slider> sliders = new ArrayList<Controls.Slider>();
    private final List<Controls.Push> buttons = new ArrayList<Controls.Push>();
    private HexField hex;
    /** 当前颜色，含 alpha。 */
    private int argb;
    private int left;
    private int contentW;
    private int previewY;
    private int previewH;
    private int gridX;
    private int gridY;
    private int cell;
    private boolean gridShown;

    public ColorPickerScreen(ScaledScreen parent, String labelKey, String initialHex,
                             java.util.function.Consumer<String> onPick, boolean allowAlpha) {
        this.parent = parent;
        this.labelKey = labelKey;
        this.onPick = onPick;
        this.allowAlpha = allowAlpha;
        Integer parsed = parse(initialHex);
        this.original = parsed == null ? 0xFFFFFFFF : parsed;
        this.argb = clamp(this.original);
    }

    /**
     * @return 含 alpha 的颜色；文字还不是一个颜色（比如正打到一半）时返回 {@code null}，
     *         调用方不许猜一个颜色出来
     */
    static Integer parse(String hex) {
        if (hex == null) return null;
        String value = hex.trim();
        if (value.startsWith("#")) value = value.substring(1);
        try {
            if (value.length() == 3) {
                int r = Integer.parseInt(value.substring(0, 1), 16) * 17;
                int g = Integer.parseInt(value.substring(1, 2), 16) * 17;
                int b = Integer.parseInt(value.substring(2, 3), 16) * 17;
                return Integer.valueOf(0xFF000000 | (r << 16) | (g << 8) | b);
            }
            if (value.length() == 6) return Integer.valueOf(0xFF000000 | Integer.parseInt(value, 16));
            if (value.length() == 8) return Integer.valueOf((int) Long.parseLong(value, 16));
        } catch (NumberFormatException e) {
            return null;
        } catch (StringIndexOutOfBoundsException e) {
            return null;
        }
        return null;
    }

    static String format(int value) {
        return String.format(Locale.ROOT, "#%08X", value);
    }

    @Override
    public void initGui() {
        // 取色页没有自己的"跟随缩放"开关，就拿父页实际算出的倍率反推：父页跟着 GUI 时两者相等，
        // 这里也按 GUI 缩放排；父页是 1:1，这里也保持 1:1（AUDIT-36-1 的延续）
        refreshUi(parent == null || parent.ui() == GameScale.factor(true));

        contentW = Math.max(140, Math.min(vw() - 32, 260));
        left = (vw() - contentW) / 2;
        sliders.clear();
        buttons.clear();

        int y = 38;
        hex = new HexField(left, y, contentW, ROW_H);
        y += ROW_H + 4;
        addChannel(16, "R", y);
        y += STEP;
        addChannel(8, "G", y);
        y += STEP;
        addChannel(0, "B", y);
        y += STEP;
        if (allowAlpha) {
            addChannel(24, "A", y);
            y += STEP;
        }

        int buttonY = vh() - 26;
        int limit = buttonY - 6;
        previewH = 20;
        previewY = y + 2;
        if (previewY + previewH > limit) previewY = Math.max(34, limit - previewH);
        cell = 20;
        int rows = (SWATCHES.length + PER_ROW - 1) / PER_ROW;
        gridX = (vw() - PER_ROW * cell) / 2;
        gridY = previewY + previewH + 6;
        // 窗口矮到放不下色块时宁可不画：压在 OK/Cancel 上比少一组色块更糟
        gridShown = gridY + rows * cell <= limit;

        int half = (contentW - 6) / 2;
        buttons.add(new Controls.Push(left, buttonY, half, 20,
                () -> Controls.text("smtcraft.picker.ok"), () -> close(true)));
        buttons.add(new Controls.Push(left + half + 6, buttonY, half, 20,
                () -> Controls.text("smtcraft.picker.cancel"), () -> close(false)));
    }

    /** 一条通道滑条：值经 {@link #setChannel(int, int)} 写回颜色。 */
    private void addChannel(final int shift, String label, int y) {
        Controls.Slider slider = new Controls.Slider(left, y, contentW, ROW_H, label, 0.0d, 255.0d,
                () -> (double) channel(shift),
                value -> setChannel(shift, (int) Math.round(value)),
                value -> Integer.toString((int) Math.round(value)));
        slider.accent = ACCENT;
        sliders.add(slider);
    }

    private int channel(int shift) {
        return (argb >>> shift) & 0xFF;
    }

    private void setChannel(int shift, int value) {
        int v = Math.max(0, Math.min(255, value));
        int mask = 0xFF << shift;
        int next = clamp((argb & ~mask) | (v << shift));
        if (next == argb) return;
        argb = next;
        syncHex();
    }

    private int clamp(int value) {
        return allowAlpha ? value : (0xFF000000 | (value & 0xFFFFFF));
    }

    /** 把当前颜色写进输入框；正在编辑的文字不覆盖，否则每拖一格滑条就把半个色号吃掉。 */
    private void syncHex() {
        if (hex != null) hex.setValue(format(argb));
    }

    private void close(boolean apply) {
        if (apply && onPick != null) onPick.accept(format(argb));
        back();
    }

    private void back() {
        if (mc != null) mc.displayGuiScreen(parent);
    }

    @Override
    protected boolean onClick(int mouseX, int mouseY, int button) {
        if (button != 0) return false;
        if (hex != null && hex.click(mouseX, mouseY, button)) return true;
        for (int i = 0; i < sliders.size(); i++) {
            if (sliders.get(i).click(mouseX, mouseY, button)) return true;
        }
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).click(mouseX, mouseY, button)) return true;
        }
        return clickSwatch(mouseX, mouseY);
    }

    @Override
    protected boolean onDrag(int mouseX, int mouseY, int button) {
        if (button != 0) return false;
        for (int i = 0; i < sliders.size(); i++) {
            if (sliders.get(i).drag(mouseX, mouseY, button)) return true;
        }
        return false;
    }

    @Override
    protected boolean onRelease(int mouseX, int mouseY, int button) {
        for (int i = 0; i < sliders.size(); i++) {
            if (sliders.get(i).release(mouseX, mouseY, button)) return true;
        }
        return false;
    }

    /** 滚轮微调：指针压在哪条滑条上就动哪一条，一格是范围的百分之一（约 2.5）。 */
    @Override
    protected void onWheel(int mouseX, int mouseY, int dir) {
        for (int i = 0; i < sliders.size(); i++) {
            if (sliders.get(i).wheel(mouseX, mouseY, dir)) return;
        }
    }

    @Override
    protected boolean onKey(char typedChar, int keyCode) {
        if (keyCode == 1) {
            back();   // ESC 等同 Cancel：只回父页，不写回配置
            return true;
        }
        return hex != null && hex.key(typedChar, keyCode);
    }

    @Override
    protected void drawFrame(Canvas c, int mouseX, int mouseY) {
        Text.drawCentered(c.font(), Controls.text(labelKey), vw() / 2, 8, INK, true, false, false);
        Text.drawCentered(c.font(), Controls.text("smtcraft.picker.hint"), vw() / 2, 20, SUBTLE,
                false, false, false);
        if (hex != null) hex.draw(c, mouseX, mouseY);
        for (int i = 0; i < sliders.size(); i++) {
            sliders.get(i).draw(c, mouseX, mouseY);
        }
        drawCompare(c);
        drawSwatches(c, mouseX, mouseY);
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).draw(c, mouseX, mouseY);
        }
    }

    /** 原值在左、新值在右；两块都垫棋盘格，半透明的 alpha 才看得出来。 */
    private void drawCompare(Canvas c) {
        int gap = 6;
        int half = (contentW - gap) / 2;
        colorBox(c, left, previewY, half, previewH, original);
        colorBox(c, left + half + gap, previewY, half, previewH, argb);
    }

    private void colorBox(Canvas c, int x, int y, int w, int h, int color) {
        checker(c, x, y, w, h);
        Draw.fillRound(c, x, y, w, h, 3, color);
        Draw.outlineRound(c, x, y, w, h, 3, 0x66FFFFFF);
        String label = Draw.fit(c.font(), format(color), w - 8);
        Text.drawCentered(c.font(), label, x + w / 2, y + (h - Text.HEIGHT) / 2, inkOn(color),
                false, false, false);
    }

    private static void checker(Canvas c, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, 0xFF2A2E34);
        for (int row = 0; row * 6 < h; row++) {
            for (int col = 0; col * 6 < w; col++) {
                if (((row + col) & 1) != 0) continue;
                c.fill(x + col * 6, y + row * 6, Math.min(x + w, x + col * 6 + 6),
                        Math.min(y + h, y + row * 6 + 6), 0xFF3A3F46);
            }
        }
    }

    /** 色块上的字要能读：浅色配深字，深色和半透明都配白字。 */
    private static int inkOn(int color) {
        int alpha = (color >>> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        return alpha < 160 || lum < 140 ? 0xFFFFFFFF : 0xFF101418;
    }

    private void drawSwatches(Canvas c, int mouseX, int mouseY) {
        if (!gridShown) return;
        int size = cell - 2;
        for (int i = 0; i < SWATCHES.length; i++) {
            int cx = gridX + (i % PER_ROW) * cell;
            int cy = gridY + (i / PER_ROW) * cell;
            Integer value = parse(SWATCHES[i]);
            int color = value == null ? 0xFF808080 : value.intValue();
            Draw.fillRound(c, cx, cy, size, size, 2, color);
            boolean hover = mouseX >= cx && mouseX < cx + size && mouseY >= cy && mouseY < cy + size;
            Draw.outlineRound(c, cx, cy, size, size, 2, hover ? 0xFFFFFFFF : 0x40FFFFFF);
        }
    }

    private boolean clickSwatch(int mouseX, int mouseY) {
        if (!gridShown) return false;
        // 整数除法向零截断：网格左/上方的空白（最多 cell-1 像素）商也是 0，会误命中第 0 列/行
        if (mouseX < gridX || mouseY < gridY) return false;
        int col = (mouseX - gridX) / cell;
        int row = (mouseY - gridY) / cell;
        if (col < 0 || col >= PER_ROW || row < 0) return false;
        if (mouseX - gridX - col * cell >= cell - 2) return false;   // 落在格子之间的缝上
        int index = row * PER_ROW + col;
        if (index >= SWATCHES.length) return false;
        Integer picked = parse(SWATCHES[index]);
        if (picked == null) return false;
        argb = clamp(picked.intValue());
        syncHex();
        return true;
    }

    /**
     * 十六进制输入框：只吃本页的按键，回车或点到别处才解析。
     *
     * <p>输入到一半的文本必然是非法颜色，逐键解析会把颜色一路打回默认值，所以
     * 解析放在提交时，非法输入退回上一次合法的颜色。</p>
     */
    private final class HexField extends Controls.Control {
        private final StringBuilder buffer = new StringBuilder();
        private boolean focused;

        HexField(int x, int y, int w, int h) {
            super(x, y, w, h);
            buffer.append(format(argb));
        }

        void setValue(String value) {
            if (focused) return;
            buffer.setLength(0);
            buffer.append(value);
        }

        @Override
        public void draw(Canvas c, int mouseX, int mouseY) {
            boolean hover = hits(mouseX, mouseY);
            Draw.fillRound(c, x, y, w, h, 3, focused ? 0x50000000 : (hover ? 0x30FFFFFF : 0x1EFFFFFF));
            Draw.outlineRound(c, x, y, w, h, 3, focused ? ACCENT : 0x30FFFFFF);
            String shown = Draw.fit(c.font(), buffer.toString(), w - 8);
            Text.draw(c.font(), shown, x + 4, y + (h - Text.HEIGHT) / 2, INK, false, false, false);
            if (focused) {
                int caret = x + 4 + Text.width(c.font(), shown, false) + 1;
                Draw.fillRound(c, caret, y + 4, 1, h - 8, 0, INK);
            }
        }

        @Override
        public boolean click(int mouseX, int mouseY, int button) {
            if (!hits(mouseX, mouseY)) {
                if (focused) commit();
                return false;
            }
            focused = true;
            return true;
        }

        @Override
        public boolean key(char typedChar, int keyCode) {
            if (!focused) return false;
            if (keyCode == 14) {                       // 退格
                if (buffer.length() > 0) buffer.setLength(buffer.length() - 1);
                return true;
            }
            if (keyCode == 28) {                       // 回车
                commit();
                return true;
            }
            if (typedChar >= 32 && typedChar != 127 && buffer.length() < 9) {
                buffer.append(Character.toUpperCase(typedChar));
                return true;
            }
            return true;                               // 编辑中把剩下的键全吃掉
        }

        /** 解析并落库：不是颜色就退回上一次合法的值，框里的字也跟着复原。 */
        void commit() {
            focused = false;
            Integer parsed = parse(buffer.toString());
            if (parsed != null) argb = clamp(parsed.intValue());
            buffer.setLength(0);
            buffer.append(format(argb));
        }
    }
}
