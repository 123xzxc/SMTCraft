package org.yinyun.smtcraft.render;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small colour / text / shape helpers shared by the HUD. */
public final class Draw {
    /** Processes whose sessions never carry a real music timeline. */
    private static final String[] BROWSERS = {
            "msedge", "chrome", "chromium", "firefox", "opera", "brave", "vivaldi", "safari",
            "iexplore", "yandex", "waterfox", "thorium", "arc", "whale", "maxthon",
            "360se", "360chrome", "qqbrowser", "sogouexplorer", "ucbrowser", "librewolf",
            "zen", "floorp", "palemoon"
    };

    /**
     * Players that must never be treated as a browser, even when their name starts
     * with a browser token (Opera Music, Whale Player, ArcSoft Player, Zen Music).
     */
    private static final String[] MUSIC_APPS = {
            "vutronmusic", "cloudmusic", "kugou", "kuwo", "qqmusic", "spotify", "foobar",
            "musicbee", "aimp", "vlc", "potplayer", "itunes", "tidal", "deezer", "yesplaymusic",
            "lxmusic", "listen1", "musicplayer", "mediamonkey", "groove", "sonic"
    };

    /** Trailing words that turn {@code chrome} into {@code chrome}, but {@code arc} not into {@code arcsoft}. */
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
            // fall through to the fallback colour
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

    /** Multiply the existing alpha, so a faded colour can be faded further. */
    public static int scaleAlpha(int color, float factor) {
        int a = (color >>> 24) & 0xFF;
        return (Math.max(0, Math.min(255, Math.round(a * factor))) << 24) | (color & 0xFFFFFF);
    }

    /** Per-channel blend, alpha included: 0 gives {@code a}, 1 gives {@code b}. */
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
        // A known music player wins over the blacklist: "OperaMusic" is not Opera.
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

    /** Lower-case letters and digits only, so "msedge.exe" and "MS-Edge" compare equal. */
    private static String letters(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    /** Truncate with an ellipsis so a long title never spills out of the panel. */
    public static String fit(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (text == null) return "";
        if (maxWidth <= 0) return "";
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "\u2026";
        int budget = maxWidth - font.width(ellipsis);
        if (budget <= 0) return ellipsis;
        // Binary search on a code-point boundary: measuring every prefix would be
        // O(n^2) and could split a surrogate pair in half.
        int[] cps = text.codePoints().toArray();
        int low = 0;
        int high = cps.length;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (font.width(new String(cps, 0, mid)) <= budget) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return new String(cps, 0, low) + ellipsis;
    }

    public static String duration(long ms) {
        if (ms <= 0L) return "0:00";
        long total = ms / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long s = total % 60L;
        return h > 0L ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    /** "VutronMusic.exe" -> "VutronMusic", "msedge!App" -> "msedge". */
    public static String appName(String raw) {
        if (raw == null) return "";
        String s = raw;
        int bang = s.indexOf('!');
        if (bang > 0) s = s.substring(0, bang);
        if (s.toLowerCase().endsWith(".exe")) s = s.substring(0, s.length() - 4);
        return s;
    }

    // --- shapes ------------------------------------------------------------

    /** Filled rectangle with rounded corners (radius clamped to half a side). */
    public static void fillRound(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius,
                                 int color) {
        if (w <= 0 || h <= 0) return;
        int rad = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (rad == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        for (int row = 0; row < h; row++) {
            int inset = inset(rad, row, h);
            if (x + inset >= x + w - inset) continue;
            g.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
        }
    }

    /** 1 px rounded border, drawn as row ends so the corners curve with the fill. */
    public static void outlineRound(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius,
                                    int color) {
        if (w <= 0 || h <= 0) return;
        int rad = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        if (rad == 0) {
            g.fill(x, y, x + w, y + 1, color);
            g.fill(x, y + h - 1, x + w, y + h, color);
            return;
        }
        for (int row = 0; row < h; row++) {
            int inset = inset(rad, row, h);
            int left = x + inset;
            int right = x + w - inset;
            if (left >= right) continue;
            if (row == 0 || row == h - 1) {
                g.fill(left, y + row, right, y + row + 1, color);
            } else {
                g.fill(left, y + row, left + 1, y + row + 1, color);
                g.fill(right - 1, y + row, right, y + row + 1, color);
            }
        }
    }

    /** Horizontal inset of one row inside a rounded rectangle. */
    private static int inset(int radius, int row, int height) {
        double centre = radius - 0.5;
        double dy;
        if (row < radius) {
            dy = centre - row;
        } else if (row >= height - radius) {
            dy = row - (height - radius - 0.5);
        } else {
            return 0;
        }
        if (dy <= 0.0) return 0;
        double dx = Math.sqrt(Math.max(0.0, (double) radius * radius - dy * dy));
        return (int) Math.round(radius - dx);
    }
}
