package org.yinyun.smtcraft.render;

import net.minecraft.client.gui.FontRenderer;

/** HUD 共用的小工具：颜色、文字度量、圆角矩形。 */
public final class Draw {
    /** 从来不携带真实音乐时间轴的进程。 */
    private static final String[] BROWSERS = {
            "msedge", "chrome", "chromium", "firefox", "opera", "brave", "vivaldi", "safari",
            "iexplore", "yandex", "waterfox", "thorium", "arc", "whale", "maxthon",
            "360se", "360chrome", "qqbrowser", "sogouexplorer", "ucbrowser", "librewolf",
            "zen", "floorp", "palemoon"
    };

    /**
     * 名字以浏览器词头开头、但其实是播放器的进程（Opera Music、Whale Player、
     * ArcSoft Player、Zen Music），不能被判成浏览器。
     */
    private static final String[] MUSIC_APPS = {
            "vutronmusic", "cloudmusic", "kugou", "kuwo", "qqmusic", "spotify", "foobar",
            "musicbee", "aimp", "vlc", "potplayer", "itunes", "tidal", "deezer", "yesplaymusic",
            "lxmusic", "listen1", "musicplayer", "mediamonkey", "groove", "sonic"
    };

    /** 允许跟在浏览器名后面的尾巴：有这些才算浏览器，{@code arc} 不会吞掉 {@code arcsoft}。 */
    private static final String[] BROWSER_SUFFIXES = {
            "", "32", "64", "x64", "x86", "beta", "dev", "canary", "stable", "esr", "gx", "portable",
            "browser", "app", "exe", "helper", "update", "utility", "gpu", "renderer"
    };

    private Draw() {}

    public static int color(String hex, int fallback) {
        if (hex == null) return fallback;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 3) {
                int r = Integer.parseInt(s.substring(0, 1), 16) * 17;
                int g = Integer.parseInt(s.substring(1, 2), 16) * 17;
                int b = Integer.parseInt(s.substring(2, 3), 16) * 17;
                return 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            if (s.length() == 6) {
                return 0xFF000000 | Integer.parseInt(s, 16);
            }
            if (s.length() == 8) {
                long v = Long.parseLong(s, 16);
                return (int) v;
            }
        } catch (NumberFormatException ignored) {
            // 落到 fallback
        }
        return fallback;
    }

    public static int argb(int alpha, int rgb) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }

    public static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** 在已有 alpha 上再乘一次，让已经淡掉的色还能继续淡。 */
    public static int scaleAlpha(int color, float factor) {
        int a = (color >>> 24) & 0xFF;
        return (Math.max(0, Math.min(255, Math.round(a * factor))) << 24) | (color & 0xFFFFFF);
    }

    /** 逐通道插值（含 alpha）：0 给 {@code a}，1 给 {@code b}。 */
    public static int lerp(int a, int b, float t) {
        float k = t <= 0.0f ? 0.0f : Math.min(t, 1.0f);
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ca = (a >>> shift) & 0xFF;
            int cb = (b >>> shift) & 0xFF;
            int c = Math.round(ca + (cb - ca) * k);
            out |= (Math.max(0, Math.min(255, c)) << shift);
        }
        return out;
    }

    public static boolean isBrowserApp(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        String s = appName(raw).toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return false;
        String norm = letters(s);
        if (norm.isEmpty()) return false;
        // 已知播放器优先于黑名单："OperaMusic" 不是 Opera
        for (String music : MUSIC_APPS) {
            if (norm.contains(music)) return false;
        }
        for (String b : BROWSERS) {
            if (!norm.startsWith(b)) continue;
            String rest = norm.substring(b.length());
            for (String suffix : BROWSER_SUFFIXES) {
                if (rest.equals(suffix)) return true;
            }
        }
        return false;
    }

    /** 只留小写字母与数字，这样 "msedge.exe" 与 "MS-Edge" 比得起来。 */
    private static String letters(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    /** 用省略号截断，长标题不会溢出面板。 */
    public static String fit(FontRenderer font, String text, int maxWidth) {
        if (text == null) return "";
        if (maxWidth <= 0) return "";
        if (Text.width(font, text, false) <= maxWidth) return text;
        String ellipsis = "\u2026";
        int budget = maxWidth - Text.width(font, ellipsis, false);
        if (budget <= 0) return ellipsis;
        // 在码点边界上二分：逐前缀量宽度是 O(n^2)，还会把代理对劈成两半
        int[] cps = text.codePoints().toArray();
        int low = 0;
        int high = cps.length;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (Text.width(font, new String(cps, 0, mid), false) <= budget) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return new String(cps, 0, low) + ellipsis;
    }

    /**
     * 毫秒转 {@code m:ss} / {@code h:mm:ss}。HUD 每帧调两次，所以不走 {@code String.format}：
     * 一次 {@code format} 要新建 {@code Formatter}，而且按默认语言环境取数字——阿拉伯语等区域
     * 的 {@code %d} 会输出本地数字，游戏字体里没有那些字形，进度读秒就成了空白。
     */
    public static String duration(long ms) {
        if (ms <= 0L) return "0:00";
        long total = ms / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        StringBuilder text = new StringBuilder(9);
        if (h > 0L) {
            text.append(h).append(':');
            if (m < 10L) text.append('0');
        }
        text.append(m).append(':');
        if (s < 10L) text.append('0');
        return text.append(s).toString();
    }

    /** "VutronMusic.exe" -> "VutronMusic"，"msedge!App" -> "msedge"。 */
    public static String appName(String raw) {
        if (raw == null) return "";
        String s = raw;
        int bang = s.indexOf('!');
        if (bang > 0) s = s.substring(0, bang);
        if (s.toLowerCase().endsWith(".exe")) s = s.substring(0, s.length() - 4);
        return s;
    }

    // --- 形状 --------------------------------------------------------------

    /** 圆角实心矩形（半径按短边一半上限收敛）。 */
    public static void fillRound(Canvas c, int x, int y, int w, int h, int radius, int color) {
        c.fillRound(x, y, w, h, radius, color);
    }

    /** 1 像素圆角描边，逐行画两端，这样拐角跟着填充一起弯。 */
    public static void outlineRound(Canvas c, int x, int y, int w, int h, int radius, int color) {
        c.outlineRound(x, y, w, h, radius, color);
    }

}
