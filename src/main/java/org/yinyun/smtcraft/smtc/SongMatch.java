package org.yinyun.smtcraft.smtc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a provider search hit is really the track Windows is playing.
 *
 * <p>The old matcher added points for loose substring hits and accepted 55, so a
 * search for {@code Luna - Ghost} happily returned another artist's song with the
 * same word in the title. This one is stricter: the title has to be genuinely
 * similar (character-bigram Dice), the artist has to agree unless the length of
 * the track proves the match on its own, and a duration that is off by more than
 * twenty seconds rejects the hit outright.</p>
 */
public final class SongMatch {
    /** Returned by {@link #score} for a hit that must not be used at all. */
    public static final int REJECT = Integer.MIN_VALUE / 2;
    /** Minimum score accepted as the right song. */
    public static final int ACCEPT = 62;

    private SongMatch() {
    }

    /** Full-width to half-width, lower case, letters and digits only. */
    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);
            if (c == 0x3000) continue;
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** "Song (feat. X) [Live]" -> "Song". */
    public static String bare(String title) {
        if (title == null) return "";
        String s = title;
        int cut = s.length();
        for (String open : new String[] {"(", "（", "[", "【", "「"}) {
            int i = s.indexOf(open);
            if (i > 0 && i < cut) cut = i;
        }
        return s.substring(0, cut).trim();
    }

    /**
     * Edition noise that has to go before comparing titles. Matching used to be a
     * bare {@code indexOf}, so "Deliver" was cut at the "live" inside it. Whole
     * words only: no letter or digit directly before or after the keyword.
     */
    private static final Pattern NOISE_LATIN = Pattern.compile(
            "(?<![a-z0-9])(?:feat\\.?|ft\\.?|remix(?:ed)?|remaster(?:ed)?|version|ver\\.?|live|"
                    + "acoustic|instrumental|inst\\.?|karaoke|tv\\s?size|off\\s?vocal)(?![a-z0-9])",
            Pattern.CASE_INSENSITIVE);

    /** CJK keywords cannot use word boundaries, so they are listed separately. */
    private static final Pattern NOISE_CJK = Pattern.compile("伴奏|纯音乐|翻自|现场版");

    /** Separators left behind after a keyword was removed. */
    private static final Pattern NOISE_GAP = Pattern.compile("[\\s\\-–—·/]+");

    /** Title without edition noise, used for the similarity comparison. */
    public static String clean(String title) {
        String s = NOISE_LATIN.matcher(bare(title)).replaceAll(" ");
        s = NOISE_CJK.matcher(s).replaceAll(" ");
        s = NOISE_GAP.matcher(s).replaceAll(" ").trim();
        return s.isEmpty() ? bare(title) : s;
    }

    /** Dice coefficient over character bigrams, 0..1. */
    public static double sim(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        if (a.equals(b)) return 1.0;
        if (a.length() == 1 || b.length() == 1) return a.equals(b) ? 1.0 : 0.0;
        Map<String, Integer> grams = new HashMap<>();
        for (int i = 0; i + 1 < a.length(); i++) {
            grams.merge(a.substring(i, i + 2), 1, Integer::sum);
        }
        int common = 0;
        for (int i = 0; i + 1 < b.length(); i++) {
            String g = b.substring(i, i + 2);
            Integer n = grams.get(g);
            if (n != null && n > 0) {
                grams.put(g, n - 1);
                common++;
            }
        }
        return 2.0 * common / (a.length() + b.length() - 2);
    }

    /**
     * Bigram Dice plus an edit-distance reading. Players and catalogues disagree by
     * a single character all the time ("愛がやえに" vs the real title "愛がゆえに"),
     * and bigram Dice alone scores that 0.5 — a hard reject for a one-letter typo.
     * The edit reading only applies when the two strings are within two characters
     * of each other, so short titles cannot drift into unrelated long ones.
     */
    public static double fuzzy(String a, String b) {
        double dice = sim(a, b);
        if (a.isEmpty() || b.isEmpty()) return dice;
        if (Math.abs(a.length() - b.length()) > 2) return dice;
        int max = Math.max(a.length(), b.length());
        return Math.max(dice, 1.0 - distance(a, b) / (double) max);
    }

    /** Levenshtein distance, two rolling rows. */
    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }

    /** Every artist name in a provider string such as "A/B & C, feat. D". */
    public static List<String> artistTokens(String artists) {
        List<String> out = new ArrayList<>();
        if (artists == null) return out;
        for (String part : artists.split("[/&,、;，]")) {
            String token = norm(part);
            if (!token.isEmpty() && !token.equals("feat") && !token.equals("ft")) out.add(token);
        }
        return out;
    }

    /** Best similarity between the wanted artist and any name listed by the provider. */
    public static double artistSim(String want, String got) {
        List<String> wants = artistTokens(want);
        List<String> gots = artistTokens(got);
        if (wants.isEmpty()) return 0.5;   // unknown artist: neutral, not a free pass
        if (gots.isEmpty()) return 0.0;
        double best = 0.0;
        for (String w : wants) {
            for (String g : gots) {
                double s = fuzzy(w, g);
                if (g.contains(w) || w.contains(g)) s = Math.max(s, 0.85);
                best = Math.max(best, s);
            }
        }
        return best;
    }

    /** Search strings to try, most specific first. */
    public static List<String> queries(TrackInfo info) {
        Set<String> out = new LinkedHashSet<>();
        String title = clean(info.title());
        String artist = primaryArtist(info.artist());
        if (!title.isEmpty() && !artist.isEmpty()) out.add(title + " " + artist);
        if (!artist.isEmpty() && !title.isEmpty() && info.title().contains(" - ")) {
            out.add(info.title().replace(" - ", " "));
        }
        if (!title.isEmpty() && info.album() != null && !info.album().isBlank()) {
            out.add(title + " " + info.album().trim());
        }
        if (!title.isEmpty()) out.add(title);
        String full = info.title() == null ? "" : info.title().trim();
        if (!full.isEmpty()) out.add(full);
        return new ArrayList<>(out);
    }

    /** First artist of a multi-artist string. */
    public static String primaryArtist(String artists) {
        if (artists == null) return "";
        String s = artists;
        for (char sep : new char[] {'/', '&', ',', '、', ';', '，'}) {
            int i = s.indexOf(sep);
            if (i > 0) s = s.substring(0, i);
        }
        return s.trim();
    }

    /**
     * @return 0..100 when the hit may be used, {@link #REJECT} when it must not
     */
    public static int score(String title, String artists, long durationMs, TrackInfo info) {
        String wantFull = norm(info.title());
        String wantBare = norm(clean(info.title()));
        String gotFull = norm(title);
        String gotBare = norm(clean(title));
        if (wantBare.isEmpty() || gotBare.isEmpty()) return REJECT;

        double titleSim = Math.max(Math.max(fuzzy(wantFull, gotFull), fuzzy(wantBare, gotBare)),
                Math.max(fuzzy(wantBare, gotFull), fuzzy(wantFull, gotBare)));

        // Some players (and some providers) swap the two fields, so the artist column
        // can hold the title. Only a strong match on both sides counts as a swap.
        String gotArtists = norm(artists);
        double swapTitle = Math.max(fuzzy(wantBare, gotArtists), fuzzy(wantFull, gotArtists));
        double swapArtist = fuzzy(norm(primaryArtist(info.artist())), gotBare);
        boolean swapped = swapTitle >= 0.85 && swapArtist >= 0.85;

        // a title has to be recognisably the same string before anything else counts
        if (titleSim < 0.72) {
            if (!swapped) return REJECT;
            titleSim = swapTitle;
        }

        double artistSim = artistSim(info.artist(), artists);
        if (swapped) artistSim = Math.max(artistSim, swapArtist);
        long want = info.durationMs();
        boolean haveDuration = want > 0L && durationMs > 0L;
        long diff = haveDuration ? Math.abs(want - durationMs) : Long.MAX_VALUE;

        // A minute apart is a different recording; anything below that is only a
        // hint, because SMTC often reports an edited length (radio edit, fade-out,
        // a stream with a spoken intro), so a matching title + artist still wins.
        if (haveDuration && diff > 60_000L) return REJECT;
        boolean weakArtist = artistSim < 0.45;
        if (weakArtist && !(haveDuration && diff <= 3_000L && titleSim >= 0.85)) {
            return REJECT;
        }

        double value = 55.0 * titleSim + 25.0 * artistSim;
        if (haveDuration) {
            value += 20.0 * Math.max(0.0, 1.0 - diff / 30_000.0);
        } else {
            value += 8.0; // no length information: stay in the middle of the band
        }
        if (swapped) value -= 6.0;
        return (int) Math.round(Math.min(100.0, value));
    }
}
