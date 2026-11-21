package org.yinyun.smtcraft.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Minimal ARGB colour picker: swatches, hex field and three channel sliders. */
public final class ColorPickerScreen extends Screen {
    private static final String[] SWATCHES = {
            "#FFFFFFFF", "#FFB8BCC4", "#FF7F8A99", "#FF000000",
            "#FFE4405F", "#FFFF7A45", "#FFFFC845", "#FF57C785",
            "#FF3FA9F5", "#FF6C6CF0", "#FFB45CF0", "#FFFF5FA2",
            "#FF1ED760", "#FF19B5FE", "#FFF5F5F5", "#FF2B2F38"};

    private final Screen parent;
    private final String labelKey;
    private final Consumer<String> onPick;
    private final int initial;

    private EditBox hexBox;
    private ChannelSlider rSlider;
    private ChannelSlider gSlider;
    private ChannelSlider bSlider;
    private Button preview;
    /** Current colour, alpha channel included. */
    private int rgb;
    private boolean building;
    private int gridX;
    private int gridY;
    private int gridCell = 22;
    private int gridPerRow = 8;

    public ColorPickerScreen(Screen parent, String labelKey, String initialHex, Consumer<String> onPick) {
        super(Component.translatable(labelKey));
        this.parent = parent;
        this.labelKey = labelKey;
        this.onPick = onPick;
        Integer start = parse(initialHex);
        this.initial = start == null ? 0xFFFFFFFF : start;
        this.rgb = this.initial;
    }

    /**
     * @return the parsed colour including its alpha channel, or {@code null} when the
     *         text is not yet a colour (mid-typing). Callers must not guess a colour.
     */
    private static Integer parse(String hex) {
        if (hex == null) return null;
        String value = hex.trim();
        if (value.startsWith("#")) value = value.substring(1);
        try {
            if (value.length() == 3) {
                int r = Integer.parseInt(value.substring(0, 1), 16) * 17;
                int g = Integer.parseInt(value.substring(1, 2), 16) * 17;
                int b = Integer.parseInt(value.substring(2, 3), 16) * 17;
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            if (value.length() == 6) return 0xFF000000 | Integer.parseInt(value, 16);
            if (value.length() == 8) return (int) Long.parseLong(value, 16);
        } catch (NumberFormatException | StringIndexOutOfBoundsException ignored) {
            return null;
        }
        return null;
    }

    private static String format(int argb) {
        return String.format("#%08X", argb);
    }

    @Override
    protected void init() {
        building = true;
        int w = Math.min(this.width - 40, 260);
        int left = (this.width - w) / 2;
        int y = 56;

        hexBox = addRenderableWidget(new EditBox(this.font, left, y, w, 20, Component.translatable(labelKey)));
        hexBox.setMaxLength(9);
        hexBox.setValue(format(rgb));
        hexBox.setResponder(text -> {
            if (building) return;
            Integer parsed = parse(text);
            if (parsed == null) return;   // mid-typing: keep the current colour
            rgb = parsed;
            pushToSliders();
            updatePreview();
        });
        y += 26;

        rSlider = addRenderableWidget(new ChannelSlider(left, y, w, 20, "R", (rgb >> 16) & 0xFF));
        y += 24;
        gSlider = addRenderableWidget(new ChannelSlider(left, y, w, 20, "G", (rgb >> 8) & 0xFF));
        y += 24;
        bSlider = addRenderableWidget(new ChannelSlider(left, y, w, 20, "B", rgb & 0xFF));
        y += 28;

        // swatch grid, 8 per row, 22px cells
        gridPerRow = 8;
        gridCell = 22;
        gridX = (this.width - gridPerRow * gridCell) / 2;
        gridY = y;
        for (int i = 0; i < SWATCHES.length; i++) {
            final String hex = SWATCHES[i];
            int cx = gridX + (i % gridPerRow) * gridCell;
            int cy = gridY + (i / gridPerRow) * gridCell;
            addRenderableWidget(Button.builder(Component.literal(" "), b -> {
                Integer picked = parse(hex);
                if (picked == null) return;
                rgb = picked;
                syncAll();
            }).bounds(cx, cy, gridCell - 2, gridCell - 2).build());
        }
        y += ((SWATCHES.length + gridPerRow - 1) / gridPerRow) * gridCell + 6;

        preview = addRenderableWidget(Button.builder(Component.empty(), b -> {
        }).bounds(left, y, w, 16).build());
        y += 24;

        int half = (w - 6) / 2;
        addRenderableWidget(Button.builder(Component.translatable("smtcraft.picker.cancel"), b -> close(false))
                .bounds(left, y, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("smtcraft.picker.ok"), b -> close(true))
                .bounds(left + half + 6, y, half, 20).build());

        updatePreview();
        building = false;
    }

    private void pushToSliders() {
        boolean was = building;
        building = true;
        if (rSlider != null) rSlider.setChannel((rgb >> 16) & 0xFF);
        if (gSlider != null) gSlider.setChannel((rgb >> 8) & 0xFF);
        if (bSlider != null) bSlider.setChannel(rgb & 0xFF);
        building = was;
    }

    private void syncAll() {
        boolean was = building;
        building = true;
        if (hexBox != null) hexBox.setValue(format(rgb));
        buildChannels();
        updatePreview();
        building = was;
    }

    private void buildChannels() {
        if (rSlider != null) rSlider.setChannel((rgb >> 16) & 0xFF);
        if (gSlider != null) gSlider.setChannel((rgb >> 8) & 0xFF);
        if (bSlider != null) bSlider.setChannel(rgb & 0xFF);
    }

    private void onChannel(int channel, int value) {
        if (building) return;
        int mask = 0xFF << channel;
        rgb = (rgb & ~mask) | ((value & 0xFF) << channel);
        boolean was = building;
        building = true;
        if (hexBox != null) hexBox.setValue(format(rgb));
        building = was;
        updatePreview();
    }

    private void updatePreview() {
        if (preview == null) return;
        preview.setMessage(Component.literal("  " + format(rgb)));
    }

    private void close(boolean save) {
        if (save && onPick != null) onPick.accept(format(rgb));
        if (this.minecraft != null) this.minecraft.setScreenAndShow(parent);
    }

    /** 26.1 up to 26.4-snapshot-4 hand the pointer along with the frame. */
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        draw(g, mouseX, mouseY, partialTick);
    }

    /** 26.4-snapshot-5 and later hand over the extractor, which carries the pointer itself. */
    public void extractRenderState(GuiGraphicsExtractor g) {
        draw(g, ScreenHooks.mouseX(g, 0), ScreenHooks.mouseY(g, 0), ScreenHooks.partialTicks(g, 0.0f));
    }

    private void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ScreenHooks.drawBackground(this, g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, Component.translatable(labelKey).getString(), this.width / 2, 20, 0xFFFFFFFF);
        g.centeredText(this.font, Component.translatable("smtcraft.picker.hint").getString(),
                this.width / 2, 38, 0xFF9FB2C8);
        for (int i = 0; i < SWATCHES.length; i++) {
            Integer swatch = parse(SWATCHES[i]);
            int colour = swatch == null ? 0xFF808080 : swatch;
            int cx = gridX + (i % gridPerRow) * gridCell;
            int cy = gridY + (i / gridPerRow) * gridCell;
            g.fill(cx + 1, cy + 1, cx + gridCell - 3, cy + gridCell - 3, colour);
        }
        if (preview != null) {
            int x = preview.getX();
            int y = preview.getY();
            int w = preview.getWidth();
            int h = preview.getHeight();
            g.fill(x, y, x + w, y + h, rgb);
            g.fill(x - 1, y - 1, x + w + 1, y, 0xFFFFFFFF);
            g.fill(x - 1, y + h, x + w + 1, y + h + 1, 0xFFFFFFFF);
        }
    }

    @Override
    public void onClose() {
        close(false);
    }

    /** One channel slider; value travels through {@link #onChannel}. */
    private final class ChannelSlider extends AbstractSliderButton {
        private final int channel;
        private int current;

        ChannelSlider(int x, int y, int w, int h, String name, int value) {
            super(x, y, w, h, Component.literal(name), value / 255.0d);
            this.channel = name.equals("R") ? 16 : name.equals("G") ? 8 : 0;
            this.current = value;
            updateMessage();
        }

        void setChannel(int value) {
            this.current = value;
            this.value = value / 255.0d;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal((channel == 16 ? "R " : channel == 8 ? "G " : "B ") + current));
        }

        @Override
        protected void applyValue() {
            int v = (int) Math.round(this.value * 255.0d);
            if (v != current) {
                current = v;
                onChannel(channel, v);
            }
        }
    }
}
