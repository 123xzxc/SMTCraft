package org.yinyun.smtcraft.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import net.minecraft.util.StatCollector;
import org.yinyun.smtcraft.render.Canvas;
import org.yinyun.smtcraft.render.Draw;
import org.yinyun.smtcraft.render.Text;

/**
 * 设置页自带的控件集：1.8.9 的 {@code GuiButton} 只认固定 id 与固定贴图，撑不起这种
 * "一行一个开关 / 一个滑条 / 一个色块"的排版，所以整页的命中测试与绘制都在这里。
 *
 * <p>控件坐标是设计像素，由页面排版时给定；{@link ScaledScreen} 已经把指针换算过了。</p>
 */
public final class Controls {

    /** 一行控件的高度（设计像素），五个页签共用同一套行距。 */
    public static final int ROW_H = 20;

    private static final int DISABLED = 0x55FFFFFF;
    private static final int LABEL = 0xFFFFFFFF;

    private Controls() {}

    /** 取本地化文本；键不存在时原样返回键名，方便发现漏翻。 */
    public static String text(String key) {
        if (key == null || key.isEmpty()) return "";
        String s = StatCollector.translateToLocal(key);
        return s == null || s.isEmpty() ? key : s;
    }

    /** 带参数的本地化文本。 */
    public static String text(String key, Object... args) {
        if (key == null || key.isEmpty()) return "";
        String s = StatCollector.translateToLocalFormatted(key, args);
        return s == null || s.isEmpty() ? key : s;
    }

    /** 一个可命中、可绘制的控件。 */
    public abstract static class Control {
        public int x;
        public int y;
        public int w;
        public int h;
        public boolean visible = true;
        public boolean enabled = true;
        /** 强调色，页面从配置里取。 */
        public int accent = 0xFF4FC3F7;

        protected Control(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean hits(double mx, double my) {
            return visible && enabled
                    && mx >= x && my >= y && mx < x + w && my < y + h;
        }

        /** 左侧标签文字；没有标签的控件返回空串。 */
        protected String labelText() {
            return "";
        }

        protected int labelColor(boolean hover) {
            return enabled ? LABEL : DISABLED;
        }

        protected void rowBackground(Canvas c, int mx, int my) {
            boolean hover = hits(mx, my);
            Draw.fillRound(c, x, y, w, h, 3, hover ? 0x30FFFFFF : 0x18FFFFFF);
        }

        protected void drawLabel(Canvas c) {
            String label = labelText();
            if (label.isEmpty()) return;
            int max = Math.max(8, w - 8);
            String shown = Draw.fit(c.font(), label, max);
            Text.draw(c.font(), shown, x + 4, y + (h - Text.HEIGHT) / 2, labelColor(false),
                    false, false, false);
        }

        public void draw(Canvas c, int mx, int my) {
            if (!visible) return;
            rowBackground(c, mx, my);
            drawLabel(c);
        }

        public boolean click(int mx, int my, int button) {
            return false;
        }

        public boolean drag(int mx, int my, int button) {
            return false;
        }

        public boolean release(int mx, int my, int button) {
            return false;
        }

        public boolean wheel(int mx, int my, int dir) {
            return false;
        }

        public boolean key(char typedChar, int keyCode) {
            return false;
        }
    }

    /** 页签按钮：文字居中，选中时下面一条强调色。 */
    public static final class Tab extends Control {
        private final String labelKey;
        private final int index;
        private final Consumer<Integer> onPick;
        public boolean active;

        public Tab(int x, int y, int w, int h, String labelKey, int index, boolean active,
                   Consumer<Integer> onPick) {
            super(x, y, w, h);
            this.labelKey = labelKey;
            this.index = index;
            this.active = active;
            this.onPick = onPick;
        }

        @Override
        public void draw(Canvas c, int mx, int my) {
            if (!visible) return;
            boolean hover = hits(mx, my);
            if (active || hover) {
                Draw.fillRound(c, x, y, w, h, 3, active ? Draw.withAlpha(accent, 0.30f) : 0x20FFFFFF);
            }
            String label = Draw.fit(c.font(), text(labelKey), Math.max(8, w - 6));
            int color = active ? accent : (hover ? LABEL : 0xCCFFFFFF);
            Text.drawCentered(c.font(), label, x + w / 2, y + (h - Text.HEIGHT) / 2, color,
                    active, false, false);
        }

        @Override
        public boolean click(int mx, int my, int button) {
            if (!hits(mx, my)) return false;
            if (onPick != null) onPick.accept(index);
            return true;
        }
    }

    /** 开关行：左边标签，右边一颗胶囊。 */
    public static final class Toggle extends Control {
        private final String labelKey;
        private final BooleanSupplier get;
        private final Consumer<Boolean> set;
        private final boolean inverted;

        public Toggle(int x, int y, int w, int h, String labelKey, BooleanSupplier get,
                      Consumer<Boolean> set, boolean inverted) {
            super(x, y, w, h);
            this.labelKey = labelKey;
            this.get = get;
            this.set = set;
            this.inverted = inverted;
        }

        private boolean on() {
            boolean v = get != null && get.getAsBoolean();
            return inverted ? !v : v;
        }

        @Override
        protected String labelText() {
            return text(labelKey);
        }

        @Override
        public void draw(Canvas c, int mx, int my) {
            if (!visible) return;
            rowBackground(c, mx, my);
            drawLabel(c);
            boolean on = on();
            int pillW = Math.min(22, Math.max(12, w / 5));
            int pillH = 10;
            int px = x + w - pillW - 4;
            int py = y + (h - pillH) / 2;
            Draw.fillRound(c, px, py, pillW, pillH, pillH / 2,
                    on ? Draw.withAlpha(accent, 0.85f) : 0x40FFFFFF);
            int knobW = knobWidth(pillW);
            int kx = on ? px + pillW - knobW - 1 : px + 1;
            Draw.fillRound(c, kx, py + 1, knobW, pillH - 2, (pillH - 2) / 2, 0xFFFFFFFF);
        }

        private static int knobWidth(int pillW) {
            return Math.max(4, pillW / 2 - 1);
        }

        @Override
        public boolean click(int mx, int my, int button) {
            if (!hits(mx, my)) return false;
            if (set != null) set.accept(!on());
            return true;
        }
    }

    /** 单选行：右边显示当前值，点一下换下一个。 */
    public static final class Choice extends Control {
        private final String labelKey;
        private final Supplier<String> valueText;
        private final Runnable onNext;

        public Choice(int x, int y, int w, int h, String labelKey, Supplier<String> valueText,
                      Runnable onNext) {
            super(x, y, w, h);
            this.labelKey = labelKey;
            this.valueText = valueText;
            this.onNext = onNext;
        }

        @Override
        protected String labelText() {
            return text(labelKey);
        }

        @Override
        public void draw(Canvas c, int mx, int my) {
            if (!visible) return;
            rowBackground(c, mx, my);
            String value = valueText == null ? "" : valueText.get();
            int valueW = Math.min(w / 2, Text.width(c.font(), value, false) + 8);
            String label = Draw.fit(c.font(), text(labelKey), Math.max(8, w - valueW - 8));
            Text.draw(c.font(), label, x + 4, y + (h - Text.HEIGHT) / 2, labelColor(false),
                    false, false, false);
            String shown = Draw.fit(c.font(), value, Math.max(8, valueW - 4));
            Text.draw(c.font(), shown, x + w - Text.width(c.font(), shown, false) - 4,
                    y + (h - Text.HEIGHT) / 2, enabled ? accent : DISABLED, false, false, false);
        }

        @Override
        public boolean click(int mx, int my, int button) {
            if (!hits(mx, my)) return false;
            if (onNext != null) onNext.run();
            return true;
        }
    }

    /** 滑条行：标签、右侧数值、下方一条轨道。 */
    public static final class Slider extends Control {
        private final String labelKey;
        private final double min;
        private final double max;
        private final DoubleSupplier get;
        private final DoubleConsumer set;
        private final DoubleFunction<String> format;
        private boolean dragging;

        public Slider(int x, int y, int w, int h, String labelKey, double min, double max,
                      DoubleSupplier get, DoubleConsumer set, DoubleFunction<String> format) {
            super(x, y, w, h);
            this.labelKey = labelKey;
            this.min = min;
            this.max = max;
            this.get = get;
            this.set = set;
            this.format = format;
        }

        private double value() {
            return get == null ? min : get.getAsDouble();
        }

        private void apply(int mx) {
            if (set == null || w <= 0) return;
            double t = (mx - x) / (double) w;
            t = Math.max(0.0d, Math.min(1.0d, t));
            set.accept(min + (max - min) * t);
        }

        @Override
        protected String labelText() {
            return text(labelKey);
        }

        @Override
        public void draw(Canvas c, int mx, int my) {
            if (!visible) return;
            rowBackground(c, mx, my);
            String label = Draw.fit(c.font(), text(labelKey), Math.max(8, w - 60));
            Text.draw(c.font(), label, x + 4, y + 2, labelColor(false), false, false, false);
            String value = format == null ? "" : format.apply(value());
            Text.draw(c.font(), value, x + w - Text.width(c.font(), value, false) - 4, y + 2,
                    enabled ? accent : DISABLED, false, false, false);
            int track = Math.max(1, h - 13);
            int barY = y + 9 + track / 2;
            Draw.fillRound(c, x + 4, barY, w - 8, 2, 1, 0x30FFFFFF);
            double span = max - min;
            double t = span == 0.0d ? 0.0d : (value() - min) / span;
            t = Math.max(0.0d, Math.min(1.0d, t));
            int fill = (int) Math.round((w - 8) * t);
            if (fill > 0) {
                Draw.fillRound(c, x + 4, barY, fill, 2, 1, Draw.withAlpha(accent, 0.85f));
            }
            Draw.fillRound(c, x + 4 + fill - 2, barY - 3, 4, 8, 2, 0xFFFFFFFF);
        }

        @Override
        public boolean click(int mx, int my, int button) {
            if (!hits(mx, my)) return false;
            dragging = true;
            apply(mx);
            return true;
        }

        @Override
        public boolean drag(int mx, int my, int button) {
            if (!dragging) return false;
            apply(mx);
            return true;
        }

        @Override
        public boolean release(int mx, int my, int button) {
            if (!dragging) return false;
            dragging = false;
            return true;
        }

        /** 滚轮一格 = 范围的 1/100，按着 Shift 时十倍（细调靠它，粗调靠拖）。 */
        @Override
        public boolean wheel(int mx, int my, int dir) {
            if (!hits(mx, my) || set == null) return false;
            double step = (max - min) / 100.0d;
            if (org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LSHIFT)
                    || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RSHIFT)) {
                step *= 10.0d;
            }
            set.accept(Math.max(min, Math.min(max, value() + step * dir)));
            return true;
        }
    }

    /** 文本框行：路径之类的字符串，点击后吃掉键盘。 */
    public static final class Field extends Control {
        private final String labelKey;
        private final Supplier<String> get;
        private final Consumer<String> set;
        public boolean focused;
        private StringBuilder buffer;

        public Field(int x, int y, int w, int h, String labelKey, Supplier<String> get,
                     Consumer<String> set) {
            super(x, y, w, h);
            this.labelKey = labelKey;
            this.get = get;
            this.set = set;
        }

        @Override
        protected String labelText() {
            return text(labelKey);
        }

        private String current() {
            if (buffer != null) return buffer.toString();
            return get == null ? "" : get.get();
        }

        @Override
        public void draw(Canvas c, int mx, int my) {
            if (!visible) return;
            rowBackground(c, mx, my);
            drawLabel(c);
            int boxW = Math.min(w - 4, Math.max(30, w / 2));
            int boxX = x + w - boxW - 4;
            Draw.fillRound(c, boxX, y + 2, boxW, h - 4, 2, 0x40000000);
            String value = current();
            String shown = Draw.fit(c.font(), value, Math.max(8, boxW - 6));
            int color = enabled ? (focused ? accent : LABEL) : DISABLED;
            Text.draw(c.font(), shown, boxX + 3, y + (h - Text.HEIGHT) / 2, color, false, false, false);
            if (focused) {
                int caret = boxX + 3 + Text.width(c.font(), shown, false) + 1;
                Draw.fillRound(c, caret, y + 5, 1, h - 10, 0, accent);
            }
        }

        public String value() {
            return current();
        }

        @Override
        public boolean click(int mx, int my, int button) {
            if (!hits(mx, my)) {
                if (focused) commit();
                return false;
            }
            focused = true;
            buffer = new StringBuilder(current());
            return true;
        }

        @Override
        public boolean key(char typedChar, int keyCode) {
            if (!focused) return false;
            if (keyCode == 28 || keyCode == 1) {
                commit();
                return true;
            }
            if (keyCode == 14) {
                if (buffer.length() > 0) buffer.setLength(buffer.length() - 1);
                return true;
            }
            if (typedChar >= 32 && typedChar != 127 && buffer.length() < 512) {
                buffer.append(typedChar);
                return true;
            }
            return true;
        }

        /** 把编辑中的文本写回配置。 */
        public void commit() {
            focused = false;
            if (buffer == null) return;
            String value = buffer.toString();
            buffer = null;
            if (set != null) set.accept(value);
        }
    }

    /** 普通按钮行：整行可点，文字居中。 */
    public static final class Push extends Control {
        private final Supplier<String> label;
        private final Runnable onPress;

        public Push(int x, int y, int w, int h, Supplier<String> label, Runnable onPress) {
            super(x, y, w, h);
            this.label = label;
            this.onPress = onPress;
        }

        @Override
        public void draw(Canvas c, int mx, int my) {
            if (!visible) return;
            boolean hover = hits(mx, my);
            Draw.fillRound(c, x, y, w, h, 3, hover ? Draw.withAlpha(accent, 0.35f) : 0x28FFFFFF);
            String shown = Draw.fit(c.font(), label == null ? "" : label.get(), Math.max(8, w - 6));
            Text.drawCentered(c.font(), shown, x + w / 2, y + (h - Text.HEIGHT) / 2,
                    enabled ? LABEL : DISABLED, false, false, false);
        }

        @Override
        public boolean click(int mx, int my, int button) {
            if (!hits(mx, my)) return false;
            if (onPress != null) onPress.run();
            return true;
        }
    }

    /** 颜色行：左边标签，右边一块色板。 */
    public static final class Swatch extends Control {
        private final String labelKey;
        private final Supplier<String> hex;
        private final Runnable onPress;

        public Swatch(int x, int y, int w, int h, String labelKey, Supplier<String> hex,
                      Runnable onPress) {
            super(x, y, w, h);
            this.labelKey = labelKey;
            this.hex = hex;
            this.onPress = onPress;
        }

        @Override
        protected String labelText() {
            return text(labelKey);
        }

        @Override
        public void draw(Canvas c, int mx, int my) {
            if (!visible) return;
            rowBackground(c, mx, my);
            drawLabel(c);
            int size = Math.min(h - 6, 12);
            int sx = x + w - size - 4;
            int sy = y + (h - size) / 2;
            String raw = hex == null ? "" : hex.get();
            int color = Draw.color(raw, 0xFFFFFFFF);
            Draw.fillRound(c, sx, sy, size, size, 2, color);
            Draw.outlineRound(c, sx, sy, size, size, 2, 0x60FFFFFF);
        }

        @Override
        public boolean click(int mx, int my, int button) {
            if (!hits(mx, my)) return false;
            if (onPress != null) onPress.run();
            return true;
        }
    }
}
