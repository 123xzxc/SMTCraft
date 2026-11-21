package org.yinyun.smtcraft.render;

import org.yinyun.smtcraft.SmtcConfig;

import java.util.List;

/** Colour themes: picking one writes its palette into the config. */
public final class Theme {
    public record Preset(String id, String accent, String text, String dim, float opacity) {}

    public static final List<Preset> PRESETS = List.of(
            new Preset("netease", "#EC4141", "#FFFFFF", "#C6CBD4", 0.62f),
            new Preset("midnight", "#7FB4FF", "#EAF2FF", "#94A4BE", 0.66f),
            new Preset("aurora", "#7CF7C2", "#F1FFFA", "#9CC6B5", 0.58f),
            new Preset("sakura", "#FFA7C4", "#FFF4F8", "#D5A6B8", 0.60f),
            new Preset("sunset", "#FFB05C", "#FFF6EC", "#D0AE8C", 0.60f),
            new Preset("mono", "#E8E8E8", "#FFFFFF", "#A9A9A9", 0.55f));

    private Theme() {}

    public static Preset get(String id) {
        if (id != null) {
            for (Preset p : PRESETS) {
                if (p.id().equalsIgnoreCase(id)) return p;
            }
        }
        return PRESETS.get(0);
    }

    public static String nextId(String id) {
        for (int i = 0; i < PRESETS.size(); i++) {
            if (PRESETS.get(i).id().equalsIgnoreCase(id == null ? "" : id)) {
                return PRESETS.get((i + 1) % PRESETS.size()).id();
            }
        }
        return PRESETS.get(0).id();
    }

    /**
     * Writes the palette, lyrics colours included, but leaves colours the player
     * picked by hand alone: silently overwriting them lost the customisation with
     * no way back (N10).
     */
    public static void apply(Preset preset, SmtcConfig cfg) {
        if (preset == null || cfg == null) return;
        cfg.theme = preset.id();
        if (!cfg.isCustom("accentColor")) cfg.accentColor = preset.accent();
        if (!cfg.isCustom("textColor")) cfg.textColor = preset.text();
        if (!cfg.isCustom("dimTextColor")) cfg.dimTextColor = preset.dim();
        if (!cfg.isCustom("lyricColor")) cfg.lyricColor = preset.text();
        if (!cfg.isCustom("karaokeColor")) cfg.karaokeColor = preset.accent();
        if (!cfg.isCustom("backgroundOpacity")) cfg.backgroundOpacity = preset.opacity();
    }

    /** Force a full palette onto the config, dropping the player's custom colours. */
    public static void reset(Preset preset, SmtcConfig cfg) {
        if (cfg == null) return;
        cfg.clearCustom();
        apply(preset, cfg);
    }
}
