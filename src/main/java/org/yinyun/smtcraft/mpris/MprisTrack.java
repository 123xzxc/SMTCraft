package org.yinyun.smtcraft.mpris;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One MPRIS player's state, converted to plain Java values.
 *
 * <p>Everything here is pure: {@link MprisBus} only produces nested
 * maps/lists/strings/longs, and the parsing rules of the spec live here so they
 * can be exercised without a session bus. MPRIS metadata is loosely typed in
 * practice — {@code xesam:artist} is an array by spec but a string from some
 * players, {@code mpris:length} is microseconds and sometimes missing — so the
 * readers below never throw on a wrong shape.</p>
 */
public final class MprisTrack {

    public static final String PREFIX = "org.mpris.MediaPlayer2.";
    public static final String ROOT_IFACE = "org.mpris.MediaPlayer2";
    public static final String PLAYER_IFACE = "org.mpris.MediaPlayer2.Player";

    private MprisTrack() {}

    /** Immutable view of one player after one round. */
    public record Snapshot(String name, String app, boolean playing, String status,
                           String title, String artist, String album, long lengthMs,
                           long positionMs, double rate, String artUrl) {

        public boolean hasTrack() {
            return !title.isEmpty();
        }

        /** Identity of the track, the same way {@code SmtcBridge} builds it. */
        public String key() {
            return title + '\u0001' + artist + '\u0001' + album + '\u0001' + app;
        }
    }

    /**
     * Build a snapshot from the {@code org.mpris.MediaPlayer2.Player} properties.
     *
     * @param name     bus name, e.g. {@code org.mpris.MediaPlayer2.vlc.instance12}
     * @param identity {@code Identity} of the root interface, may be blank
     * @param player   flattened GetAll() result, may be empty
     * @param position play position in milliseconds, or a negative value when the
     *                 player exposes no timeline at all
     */
    public static Snapshot of(String name, String identity, Map<String, Object> player, long positionMs) {
        Map<String, Object> all = player == null ? Map.of() : player;
        String status = text(all.get("PlaybackStatus"), "Stopped");
        boolean playing = "Playing".equalsIgnoreCase(status);
        Map<String, Object> meta = map(all.get("Metadata"));
        String title = text(meta.get("xesam:title"), "");
        String artist = artists(meta.get("xesam:artist"));
        if (artist.isEmpty()) artist = artists(meta.get("xesam:albumArtist"));
        String album = text(meta.get("xesam:album"), "");
        long lengthMs = micros(meta.get("mpris:length"));
        String artUrl = text(meta.get("mpris:artUrl"), "");
        double rate = number(all.get("Rate"), 1.0d);
        String app = identity == null || identity.isBlank() ? shortName(name) : identity.trim();
        return new Snapshot(name, app, playing, status, title, artist, album,
                Math.max(0L, lengthMs), positionMs, rate <= 0.0d ? 1.0d : rate, artUrl);
    }

    /**
     * Preference between two players: the one that is actually playing wins,
     * then the one that has a track at all.
     */
    public static int rank(Snapshot snapshot) {
        if (snapshot == null) return Integer.MIN_VALUE;
        int score = snapshot.playing() ? 100 : 0;
        if (snapshot.hasTrack()) score += 10;
        if (!"Stopped".equalsIgnoreCase(snapshot.status())) score += 1;
        return score;
    }

    /** {@code org.mpris.MediaPlayer2.vlc.instance12} becomes {@code vlc}. */
    public static String shortName(String busName) {
        if (busName == null || busName.isBlank()) return "";
        String s = busName.startsWith(PREFIX) ? busName.substring(PREFIX.length()) : busName;
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return s;
    }

    /** Joins an artist array, tolerating a bare string or a list of anything. */
    public static String artists(Object raw) {
        if (raw == null) return "";
        if (raw instanceof String s) return s.trim();
        if (raw instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                String one = item == null ? "" : String.valueOf(item).trim();
                if (one.isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(one);
            }
            return sb.toString();
        }
        return String.valueOf(raw).trim();
    }

    /** MPRIS carries microseconds; the mod speaks milliseconds. */
    public static long micros(Object raw) {
        if (raw instanceof Number n) {
            long value = n.longValue();
            return value <= 0L ? 0L : value / 1000L;
        }
        if (raw instanceof String s) {
            try {
                return Math.max(0L, Long.parseLong(s.trim()) / 1000L);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    public static long integer(Object raw, long fallback) {
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static double number(Object raw, double fallback) {
        if (raw instanceof Number n) return n.doubleValue();
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static String text(Object raw, String fallback) {
        if (raw == null) return fallback;
        String s = String.valueOf(raw);
        return s.isBlank() ? fallback : s;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** True when both snapshots describe the same track (used to reset the clock). */
    public static boolean sameTrack(Snapshot a, Snapshot b) {
        if (a == null || b == null) return a == b;
        return a.title().equals(b.title()) && a.artist().equals(b.artist())
                && a.album().equals(b.album()) && a.name().equals(b.name());
    }

    /** Lower-case bus suffix, what the browser filter looks at. */
    public static String filterName(String busName) {
        return shortName(busName).toLowerCase(Locale.ROOT);
    }
}
