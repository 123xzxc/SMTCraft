package org.yinyun.smtcraft.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Lists the fonts installed on this machine so a language slot can point at one.
 *
 * <p>Windows keeps system faces in {@code %WINDIR%\Fonts} and per-user faces in
 * {@code %LOCALAPPDATA%\Microsoft\Windows\Fonts}; both are scanned, once per
 * session, and filtered by the search box.</p>
 */
public final class FontPickerScreen extends ScaledScreen {
    private static final int PER_PAGE = 10;
    private static List<Path> cache;

    private final Screen parent;
    private final String labelKey;
    private final Consumer<String> onPick;
    /** Whether this picker follows the game's GUI scale, like the page that opened it. */
    private final boolean followScale;

    private final List<Path> matches = new ArrayList<>();
    private int page;
    private EditBox search;
    /** Fixed row buttons: filtering relabels them instead of rebuilding the screen. */
    private final Button[] rows = new Button[PER_PAGE];
    /** Survives rebuildWidgets(), which recreates the box with an empty value. */
    private String query = "";
    /** The search box keeps the keyboard after a rebuild, or typing stops after one key. */
    private boolean searchFocused;

    public FontPickerScreen(Screen parent, String labelKey, Consumer<String> onPick, boolean followScale) {
        super(Component.translatable("smtcraft.fontpicker.title"));
        this.followScale = followScale;
        this.parent = parent;
        this.labelKey = labelKey;
        this.onPick = onPick;
    }

    /** Every font file this machine exposes, sorted by name. Cached per session. */
    public static List<Path> fonts() {
        if (cache != null) return cache;
        List<Path> found = new ArrayList<>();
        for (Path dir : directories()) {
            try (var stream = Files.newDirectoryStream(dir)) {
                for (Path file : stream) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!(name.endsWith(".ttf") || name.endsWith(".otf")
                            || name.endsWith(".ttc") || name.endsWith(".otc"))) continue;
                    if (Files.isReadable(file)) found.add(file);
                }
            } catch (IOException | RuntimeException ignored) {
                // a protected or missing directory simply contributes nothing
            }
        }
        found.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        cache = List.copyOf(found);
        return cache;
    }

    private static List<Path> directories() {
        List<Path> dirs = new ArrayList<>();
        String windir = System.getenv("WINDIR");
        if (windir == null || windir.isEmpty()) windir = "C:\\Windows";
        add(dirs, windir, "Fonts");
        add(dirs, System.getenv("LOCALAPPDATA"), "Microsoft", "Windows", "Fonts");
        return dirs;
    }

    /** A malformed environment variable must not take the screen down with it. */
    private static void add(List<Path> dirs, String root, String... rest) {
        if (root == null || root.isEmpty()) return;
        try {
            dirs.add(Path.of(root, rest));
        } catch (java.nio.file.InvalidPathException ignored) {
            // unusable location, contributes nothing
        }
    }

    @Override
    protected void init() {
        refreshUi(followScale);
        int w = Math.min(vw() - 24, 340);
        int left = (vw() - w) / 2;

        search = addRenderableWidget(new EditBox(this.font, left, 30, w, 18,
                Component.translatable("smtcraft.fontpicker.search")));
        search.setMaxLength(64);
        search.setHint(Component.translatable("smtcraft.fontpicker.search"));
        // init() runs again after every rebuild, so the text has to be put back
        // before the responder is installed (setValue would otherwise re-enter).
        if (!query.isEmpty()) search.setValue(query);
        search.setResponder(v -> {
            if (v.equals(query)) return;
            query = v;
            page = 0;
            refill();
            refreshRows();     // no rebuildWidgets(): the box keeps focus and the cursor
        });
        if (searchFocused) {
            searchFocused = false;
            setInitialFocus(search);
        }

        refill();

        int y = 54;
        for (int i = 0; i < PER_PAGE; i++) {
            final int slot = i;
            rows[i] = addRenderableWidget(Button.builder(Component.empty(), b -> pick(slot))
                    .bounds(left, y, w, 16).build());
            y += 17;
        }
        refreshRows();

        addRenderableWidget(Button.builder(Component.literal("\u25C0"), b -> {
            if (page > 0) {
                page--;
                refreshRows();
            }
        }).bounds(left, y + 2, 30, 16).build());
        addRenderableWidget(Button.builder(Component.literal("\u25B6"), b -> {
            if ((page + 1) * PER_PAGE < matches.size()) {
                page++;
                refreshRows();
            }
        }).bounds(left + 34, y + 2, 30, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("smtcraft.fontpicker.clear"), b -> {
            onPick.accept("");
            if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
        }).bounds(left + w - 110, y + 2, 110, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("smtcraft.picker.cancel"),
                        b -> {
                            if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
                        })
                .bounds(left + w - 50, vh() - 26, 50, 16).build());
    }

    /** Relabel the fixed row buttons for the current page; hidden when out of range. */
    private void refreshRows() {
        int base = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE; i++) {
            Button row = rows[i];
            if (row == null) continue;
            int index = base + i;
            if (index < matches.size()) {
                row.setMessage(Component.literal(matches.get(index).getFileName().toString()));
                Widgets.visible(row, true);
                row.active = true;
            } else {
                row.setMessage(Component.empty());
                Widgets.visible(row, false);
                row.active = false;
            }
        }
    }

    private void pick(int slot) {
        int index = page * PER_PAGE + slot;
        if (index < 0 || index >= matches.size()) return;
        onPick.accept(matches.get(index).toAbsolutePath().toString());
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }

    private void refill() {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        matches.clear();
        for (Path file : fonts()) {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || name.contains(needle)) matches.add(file);
        }
    }

    @Override
    protected void drawFrame(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.centeredText(this.font, this.title.getString(), vw() / 2, 10, 0xFFFFFFFF);
        g.centeredText(this.font, Component.translatable(labelKey).getString(),
                vw() / 2, vh() - 40, 0xFFB8BCC4);
        String count = matches.size() + " / " + fonts().size() + "   page " + (page + 1)
                + "/" + Math.max(1, (matches.size() + PER_PAGE - 1) / PER_PAGE);
        int w = Math.min(vw() - 24, 340);
        g.text(this.font, count, (vw() - w) / 2, vh() - 14, 0xFF9FB2C8);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }
}
