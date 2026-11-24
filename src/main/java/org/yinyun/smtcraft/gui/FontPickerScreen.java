package org.yinyun.smtcraft.gui;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.yinyun.smtcraft.render.Canvas;
import org.yinyun.smtcraft.render.Draw;
import org.yinyun.smtcraft.render.Text;
import org.yinyun.smtcraft.util.Errors;

/**
 * 系统字体列表页：把本机装的 ttf/otf/ttc/otc 摆成一张可滚动的表，点一行就把路径交出去。
 *
 * <p>1.7.10 没有 font provider，路径只会被写进配置等下一版去用，所以顶部那行说明照实写，
 * 不能装作选中立刻生效。列表只画可见的几行：Windows 的 Fonts 目录有几百个文件，
 * 全部画一遍等于每帧给 GL 递几百个四边形。</p>
 */
public final class FontPickerScreen extends ScaledScreen {

    private static final Logger LOG = Logger.getLogger("smtcraft");
    private static final int PITCH = 18;
    private static final int ROW_H = 16;
    private static final int INK = 0xFFFFFFFF;
    private static final int SUBTLE = 0xFFB8BCC4;

    /** 一次进程只扫一遍；字体目录在运行期不会自己长出新文件。 */
    private static List<Path> cache;

    private final ScaledScreen parent;
    private final String labelKey;
    private final java.util.function.Consumer<String> onPick;
    private final boolean followScale;
    private final List<Path> matches = new ArrayList<Path>();

    private FilterField filter;
    private Controls.Push clear;
    private Controls.Push cancel;
    /** 过滤词另存一份：initGui 会重建输入框，重建后要能接着用。 */
    private String query = "";
    private int left;
    /** 面板宽，跟 GuiScreen 的 width 区分开，免得读代码时分不清哪个是屏幕。 */
    private int panelW;
    private int listTop;
    private int listBottom;
    private int listW;
    private int buttonY;
    private int scroll;
    private int maxScroll;
    /** 按住滚动条拇指时为 true；窗口尺寸变过就复位，否则页面会卡在拖动态。 */
    private boolean draggingBar;

    public FontPickerScreen(ScaledScreen parent, String labelKey,
                            java.util.function.Consumer<String> onPick, boolean followScale) {
        this.parent = parent;
        this.labelKey = labelKey;
        this.onPick = onPick;
        this.followScale = followScale;
    }

    /** 本机所有字体文件，按文件名排序；扫不到的目录只贡献零行。 */
    public static List<Path> fonts() {
        List<Path> found = cache;
        if (found != null) return found;
        List<Path> list = new ArrayList<Path>();
        List<Path> dirs = directories();
        for (int i = 0; i < dirs.size(); i++) {
            collect(dirs.get(i), 2, list);
        }
        Collections.sort(list, (a, b) -> name(a).compareTo(name(b)));
        found = Collections.unmodifiableList(list);
        cache = found;
        return found;
    }

    /** 两层目录足够：字体要么平铺在 Fonts 下，要么在 linux 的 type1/truetype 一层里。 */
    private static void collect(Path dir, int depth, List<Path> out) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                if (Files.isDirectory(file)) {
                    if (depth > 1) collect(file, depth - 1, out);
                    continue;
                }
                if (!isFont(file)) continue;
                if (Files.isReadable(file)) out.add(file);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "font scan failed at " + dir + ": " + Errors.describe(e));
        } catch (RuntimeException e) {
            // 受保护的目录会抛 AccessDenied，个别文件系统还会在遍历中途抛出生路径异常
            LOG.log(Level.WARNING, "font scan failed at " + dir + ": " + Errors.describe(e));
        }
    }

    private static boolean isFont(Path file) {
        String name = name(file);
        return name.endsWith(".ttf") || name.endsWith(".otf")
                || name.endsWith(".ttc") || name.endsWith(".otc");
    }

    private static String name(Path file) {
        Path fileName = file.getFileName();
        return fileName == null ? "" : fileName.toString().toLowerCase(Locale.ROOT);
    }

    /** 行上显示原始大小写：扫目录时的小写副本只用于匹配和排序。 */
    private static String display(Path file) {
        Path fileName = file.getFileName();
        return fileName == null ? file.toString() : fileName.toString();
    }

    private static List<Path> directories() {
        List<Path> dirs = new ArrayList<Path>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", "");
        if (os.startsWith("windows")) {
            String windir = System.getenv("WINDIR");
            add(dirs, windir == null || windir.isEmpty() ? "C:/Windows" : windir, "Fonts");
            add(dirs, System.getenv("LOCALAPPDATA"), "Microsoft", "Windows", "Fonts");
        } else if (os.startsWith("mac")) {
            add(dirs, "/System/Library/Fonts");
            add(dirs, "/Library/Fonts");
            add(dirs, home, "Library", "Fonts");
        } else {
            add(dirs, "/usr/share/fonts");
            add(dirs, "/usr/local/share/fonts");
            add(dirs, home, ".local", "share", "fonts");
            add(dirs, home, ".fonts");
        }
        return dirs;
    }

    /** 环境变量坏掉只该少一个目录，不该把整页带崩。 */
    private static void add(List<Path> dirs, String root, String... rest) {
        if (root == null || root.isEmpty()) return;
        try {
            dirs.add(Paths.get(root, rest));
        } catch (InvalidPathException e) {
            LOG.log(Level.FINE, "font dir skipped: " + Errors.describe(e));
        }
    }

    @Override
    public void initGui() {
        refreshUi(followScale);
        panelW = Math.max(176, Math.min(vw() - 24, 340));
        left = (vw() - panelW) / 2;
        buttonY = vh() - 24;
        listTop = 60;
        listBottom = Math.max(listTop + PITCH, buttonY - 26);
        listW = panelW - 8;
        draggingBar = false;

        filter = new FilterField(left, 38, panelW, 18);
        clear = new Controls.Push(left + panelW - 156, buttonY, 100, 18,
                () -> Controls.text("smtcraft.fontpicker.clear"), () -> pick(""));
        cancel = new Controls.Push(left + panelW - 50, buttonY, 50, 18,
                () -> Controls.text("smtcraft.picker.cancel"), this::back);

        refill();
    }

    private void refill() {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        matches.clear();
        List<Path> all = fonts();
        for (int i = 0; i < all.size(); i++) {
            Path file = all.get(i);
            if (needle.isEmpty() || name(file).contains(needle)) matches.add(file);
        }
        scroll = 0;
        clampScroll();
    }

    private void clampScroll() {
        int total = matches.size() * PITCH;
        int view = listBottom - listTop;
        maxScroll = Math.max(0, total - view);
        if (scroll > maxScroll) scroll = maxScroll;
        if (scroll < 0) scroll = 0;
    }

    private void pick(String path) {
        if (onPick != null) onPick.accept(path);
        back();
    }

    private void back() {
        if (mc != null) mc.displayGuiScreen(parent);
    }

    @Override
    protected boolean onClick(int mouseX, int mouseY, int button) {
        if (button != 0) return false;
        if (filter != null && filter.click(mouseX, mouseY, button)) return true;
        if (clear != null && clear.click(mouseX, mouseY, button)) return true;
        if (cancel != null && cancel.click(mouseX, mouseY, button)) return true;
        if (inBar(mouseX, mouseY)) {
            draggingBar = true;
            scrollTo(mouseY);
            return true;
        }
        int index = rowAt(mouseX, mouseY);
        if (index >= 0) {
            pick(matches.get(index).toAbsolutePath().toString());
            return true;
        }
        return false;
    }

    @Override
    protected boolean onDrag(int mouseX, int mouseY, int button) {
        if (!draggingBar) return false;
        scrollTo(mouseY);
        return true;
    }

    @Override
    protected boolean onRelease(int mouseX, int mouseY, int button) {
        if (!draggingBar) return false;
        draggingBar = false;
        return true;
    }

    @Override
    protected void onWheel(int mouseX, int mouseY, int dir) {
        if (maxScroll <= 0) return;
        int next = scroll - dir * PITCH * 3;
        next = Math.max(0, Math.min(maxScroll, next));
        if (next == scroll) return;
        scroll = next;
    }

    @Override
    protected boolean onKey(char typedChar, int keyCode) {
        if (keyCode == 1) {
            back();   // ESC 只回父页，不选任何字体
            return true;
        }
        return filter != null && filter.key(typedChar, keyCode);
    }

    private boolean inBar(int mouseX, int mouseY) {
        int x = left + panelW - 8;
        return maxScroll > 0 && mouseX >= x && mouseX < x + 8
                && mouseY >= listTop && mouseY < listBottom;
    }

    private int barHeight() {
        int view = listBottom - listTop;
        int total = Math.max(1, matches.size() * PITCH);
        return Math.min(view, Math.max(18, view * view / total));
    }

    private void scrollTo(int mouseY) {
        int view = listBottom - listTop;
        int travel = view - barHeight();
        if (travel <= 0) return;
        int offset = mouseY - listTop - barHeight() / 2;
        offset = Math.max(0, Math.min(travel, offset));
        scroll = (int) Math.round((double) offset * maxScroll / travel);
    }

    /** 指针落在哪一行；不在列表里或落在空行上给 -1。 */
    private int rowAt(int mouseX, int mouseY) {
        if (mouseX < left || mouseX >= left + listW) return -1;
        if (mouseY < listTop || mouseY >= listBottom) return -1;
        int index = (mouseY - listTop + scroll) / PITCH;
        return index >= 0 && index < matches.size() ? index : -1;
    }

    @Override
    protected void drawFrame(Canvas c, int mouseX, int mouseY) {
        Text.drawCentered(c.font(), Controls.text("smtcraft.fontpicker.title"), vw() / 2, 4, INK,
                true, false, false);
        drawHint(c);
        if (filter != null) filter.draw(c, mouseX, mouseY);
        drawList(c, mouseX, mouseY);
        drawBar(c);
        drawStatus(c);
        if (clear != null) clear.draw(c, mouseX, mouseY);
        if (cancel != null) cancel.draw(c, mouseX, mouseY);
    }

    /** 第一页就交代清楚：这里选的路径 1.7.10 用不上，只会进配置。 */
    private void drawHint(Canvas c) {
        Draw.fillRound(c, left, 16, panelW, 18, 3, 0x30FFB74D);
        String hint = Draw.fit(c.font(), Controls.text("smtcraft.fonts.unsupported"), panelW - 8);
        Text.draw(c.font(), hint, left + 4, 16 + (18 - Text.HEIGHT) / 2, 0xFFFFE0B2, false, false,
                false);
    }

    private void drawList(Canvas c, int mouseX, int mouseY) {
        int view = listBottom - listTop;
        c.scissor(left, listTop, left + listW, listBottom);
        try {
            if (matches.isEmpty()) {
                String empty = Draw.fit(c.font(), Controls.text("smtcraft.fonts.none"), listW - 8);
                Text.drawCentered(c.font(), empty, left + listW / 2, listTop + 4, SUBTLE,
                        false, false, false);
            }
            int first = scroll / PITCH;
            int offset = scroll % PITCH;
            int count = view / PITCH + 2;
            for (int i = 0; i < count; i++) {
                int index = first + i;
                if (index >= matches.size()) break;
                int y = listTop + i * PITCH - offset;
                boolean hover = mouseX >= left && mouseX < left + listW
                        && mouseY >= y && mouseY < y + ROW_H;
                Draw.fillRound(c, left, y, listW, ROW_H, 2, hover ? 0x38FFFFFF : 0x14FFFFFF);
                String shown = Draw.fit(c.font(), display(matches.get(index)), listW - 8);
                Text.draw(c.font(), shown, left + 4, y + (ROW_H - Text.HEIGHT) / 2,
                        hover ? INK : 0xE6FFFFFF, false, false, false);
            }
        } finally {
            // 裁剪必须无条件关掉：中途抛异常会把整个页面之后的绘制都锁在这条缝里
            c.scissorOff();
        }
    }

    private void drawBar(Canvas c) {
        int view = listBottom - listTop;
        int x = left + panelW - 4;
        Draw.fillRound(c, x, listTop, 4, view, 2, 0x18FFFFFF);
        if (maxScroll <= 0) return;
        int barH = barHeight();
        int travel = view - barH;
        int y = listTop + (travel <= 0 ? 0 : (int) Math.round((double) travel * scroll / maxScroll));
        Draw.fillRound(c, x, y, 4, barH, 2, draggingBar ? INK : 0xCCFFFFFF);
    }

    private void drawStatus(Canvas c) {
        int y = Math.min(listBottom + 6, buttonY - 12);
        String count = matches.size() + "/" + fonts().size();
        String label = Draw.fit(c.font(), Controls.text(labelKey),
                panelW - 8 - Text.width(c.font(), count, false));
        Text.draw(c.font(), label, left, y, SUBTLE, false, false, false);
        Text.draw(c.font(), count, left + panelW - Text.width(c.font(), count, false), y,
                0xFF9FB2C8, false, false, false);
    }

    /** 过滤框：逐键就重排列表，没有重建控件的步骤，所以焦点和光标不会掉。 */
    private final class FilterField extends Controls.Control {
        private final StringBuilder buffer = new StringBuilder(query);
        private boolean focused;

        FilterField(int x, int y, int w, int h) {
            super(x, y, w, h);
        }

        @Override
        public void draw(Canvas c, int mouseX, int mouseY) {
            boolean hover = hits(mouseX, mouseY);
            Draw.fillRound(c, x, y, w, h, 3, focused ? 0x50000000 : (hover ? 0x30FFFFFF : 0x1EFFFFFF));
            Draw.outlineRound(c, x, y, w, h, 3, focused ? accent : 0x30FFFFFF);
            String text = buffer.length() == 0
                    ? Controls.text("smtcraft.fontpicker.search") : buffer.toString();
            int color = buffer.length() == 0 ? 0xFF8A93A0 : INK;
            String shown = Draw.fit(c.font(), text, w - 8);
            int ty = y + (h - Text.HEIGHT) / 2;
            Text.draw(c.font(), shown, x + 4, ty, color, false, false, false);
            if (focused) {
                int caret = x + 4 + (buffer.length() == 0 ? 0 : Text.width(c.font(), shown, false)) + 1;
                Draw.fillRound(c, caret, y + 4, 1, h - 8, 0, INK);
            }
        }

        @Override
        public boolean click(int mouseX, int mouseY, int button) {
            if (!hits(mouseX, mouseY)) {
                focused = false;
                return false;
            }
            focused = true;
            return true;
        }

        @Override
        public boolean key(char typedChar, int keyCode) {
            if (!focused) return false;
            if (keyCode == 14) {                       // 退格
                if (buffer.length() > 0) {
                    buffer.setLength(buffer.length() - 1);
                    apply();
                }
                return true;
            }
            if (keyCode == 28) {                       // 回车：收起光标
                focused = false;
                return true;
            }
            if (typedChar >= 32 && typedChar != 127 && buffer.length() < 64) {
                buffer.append(typedChar);
                apply();
                return true;
            }
            return true;                               // 编辑中把剩下的键全吃掉
        }

        private void apply() {
            String next = buffer.toString();
            if (next.equals(query)) return;
            query = next;
            refill();
        }
    }
}
