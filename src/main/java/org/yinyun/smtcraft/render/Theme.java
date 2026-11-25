package org.yinyun.smtcraft.render;

import org.yinyun.smtcraft.SmtcConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 配色主题：选一个就把它的色板写进配置。 */
public final class Theme {

    /** 一套预设配色。1.8.9 的编译目标没有 record，改成不可变普通类并保留原访问器名。 */
    public static final class Preset {
        private final String id;
        private final String accent;
        private final String text;
        private final String dim;
        private final float opacity;

        Preset(String id, String accent, String text, String dim, float opacity) {
            this.id = id;
            this.accent = accent;
            this.text = text;
            this.dim = dim;
            this.opacity = opacity;
        }

        public String id() { return id; }
        public String accent() { return accent; }
        public String text() { return text; }
        public String dim() { return dim; }
        public float opacity() { return opacity; }
    }

    public static final List<Preset> PRESETS = Collections.unmodifiableList(Arrays.asList(
            new Preset("netease", "#EC4141", "#FFFFFF", "#C6CBD4", 0.62f),
            new Preset("midnight", "#7FB4FF", "#EAF2FF", "#94A4BE", 0.66f),
            new Preset("aurora", "#7CF7C2", "#F1FFFA", "#9CC6B5", 0.58f),
            new Preset("sakura", "#FFA7C4", "#FFF4F8", "#D5A6B8", 0.60f),
            new Preset("sunset", "#FFB05C", "#FFF6EC", "#D0AE8C", 0.60f),
            new Preset("mono", "#E8E8E8", "#FFFFFF", "#A9A9A9", 0.55f)));

    private Theme() {}

    public static Preset get(String id) {
        if (id != null) {
            for (Preset p : PRESETS) {
                if (p.id.equalsIgnoreCase(id)) return p;
            }
        }
        return PRESETS.get(0);
    }

    public static String nextId(String id) {
        for (int i = 0; i < PRESETS.size(); i++) {
            if (PRESETS.get(i).id.equalsIgnoreCase(id == null ? "" : id)) {
                return PRESETS.get((i + 1) % PRESETS.size()).id;
            }
        }
        return PRESETS.get(0).id;
    }

    /** 主题预设的名字，设置页直接用（1.8.9 没有 Component，语言文件按 id 拼键）。 */
    public static String labelKey(Preset preset) {
        return "smtcraft.theme." + (preset == null ? "netease" : preset.id);
    }

    /** 给设置页列出所有 id，顺序与 {@link #PRESETS} 一致。 */
    public static List<String> ids() {
        List<String> out = new ArrayList<String>(PRESETS.size());
        for (Preset p : PRESETS) out.add(p.id);
        return out;
    }

    /**
     * 写入色板（含歌词颜色），但不碰玩家手挑过的颜色：硬覆盖会把自定义配色
     * 连同退路一起抹掉（N10）。
     */
    public static void apply(Preset preset, SmtcConfig cfg) {
        if (preset == null || cfg == null) return;
        cfg.theme = preset.id;
        if (!cfg.isCustom("accentColor")) cfg.accentColor = preset.accent;
        if (!cfg.isCustom("textColor")) cfg.textColor = preset.text;
        if (!cfg.isCustom("dimTextColor")) cfg.dimTextColor = preset.dim;
        if (!cfg.isCustom("lyricColor")) cfg.lyricColor = preset.text;
        if (!cfg.isCustom("karaokeColor")) cfg.karaokeColor = preset.accent;
        if (!cfg.isCustom("translationColor")) cfg.translationColor = preset.text;
        if (!cfg.isCustom("candidateColor")) cfg.candidateColor = preset.dim;
        if (!cfg.isCustom("backgroundOpacity")) cfg.backgroundOpacity = preset.opacity;
    }

    /** 强行套上整套色板，丢掉玩家的自定义颜色。 */
    public static void reset(Preset preset, SmtcConfig cfg) {
        if (cfg == null) return;
        cfg.clearCustom();
        apply(preset, cfg);
    }
}
