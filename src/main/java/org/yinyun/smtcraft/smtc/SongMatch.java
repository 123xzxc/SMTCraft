package org.yinyun.smtcraft.smtc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        String full = info.title() == null ? "" : info.title().trim();
        // The untouched title goes first: it is the only form that still carries the
        // edition ("[US40 Remix]"), and the edition-less forms are exactly what answers
        // with every other cut of the song. It used to sit last, behind a cap of 24, so
        // on a song with an album it was never even asked (AUDIT-33-2).
        if (!full.isEmpty()) out.add(full);
        if (!title.isEmpty() && !artist.isEmpty()) out.add(title + " " + artist);
        if (!artist.isEmpty() && !title.isEmpty() && full.contains(" - ")) {
            out.add(full.replace(" - ", " "));
        }
        if (!title.isEmpty() && info.album() != null && !info.album().isBlank()) {
            out.add(title + " " + info.album().trim());
        }
        if (!title.isEmpty()) out.add(title);
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


    // ----------------------------------------------------------- edition marks

    /**
     * One way of naming a different recording of the same song.
     *
     * <p>{@code latin} spellings only count inside a bracket segment or after the
     * last " - ": "Cover Me" is a title, "(Cover)" is a recording. {@code cjk}
     * spellings have no such ambiguity and count anywhere ("夜曲 伴奏" is how the
     * instrumental is written in the wild).</p>
     */
    private record Kind(String name, Pattern latin, Pattern cjk) {
    }

    private static Pattern latin(String body) {
        return Pattern.compile("(?<![a-z0-9])(?:" + body + ")(?![a-z0-9])",
                Pattern.CASE_INSENSITIVE);
    }

    private static Pattern cjk(String body) {
        return Pattern.compile(body, Pattern.CASE_INSENSITIVE);
    }

    private static final List<Kind> KINDS = List.of(
            new Kind("remix", latin("remix(?:ed)?|re-?mix"), cjk("混音版|remix版")),
            new Kind("live", latin("live"), cjk("现场版")),
            new Kind("instrumental", latin("instrumental|inst\\.?|off\\s?vocal"),
                    cjk("伴奏|纯音乐")),
            new Kind("karaoke", latin("karaoke"), cjk("卡拉\\s?ok")),
            new Kind("acoustic", latin("acoustic|unplugged"), cjk("不插电")),
            new Kind("speed", latin("sped\\s?up|speed\\s?up|nightcore"), cjk("加速版")),
            new Kind("slowed", latin("slowed|reverb"), cjk("慢速版")),
            new Kind("tvsize", latin("tv\\s?size|tv\\s?ver(?:sion)?"), cjk("电视版|tv版")),
            new Kind("cover", latin("cover"), cjk("翻唱|翻自")),
            new Kind("demo", latin("demo"), cjk("小样")),
            new Kind("extended", latin("extended"), cjk("加长版")),
            new Kind("piano", latin("piano\\s?ver(?:sion)?"), cjk("钢琴版")),
            new Kind("orchestral", latin("orchestral|orchestra"), cjk("管弦")),
            new Kind("8d", latin("8d\\s?audio"), cjk("八度空间")));

    /** Bracket segments, ASCII and full width: editions live in these. */
    private static final Pattern SEGMENT = Pattern.compile(
            "\\(([^)]*)\\)|（([^）]*)）|\\[([^\\]]*)\\]|【([^】]*)】|「([^」]*)」");

    /**
     * Index of the last " - " in {@code title}, found on the original string.
     *
     * <p>The search used to run on {@code title.toLowerCase()} and take a substring of
     * the original: {@code İ} (U+0130) lowercases to two chars, so every index after it
     * shifts by one - "İstanbul - Live" silently lost its live mark, and a second dotted
     * I could push {@code substring} past the end on the lyric matching path (AUDIT-33-4).</p>
     */
    private static int lastDash(String title) {
        for (int i = title.length() - 3; i > 0; i--) {
            if (title.regionMatches(true, i, " - ", 0, 3)) return i;
        }
        return -1;
    }

    /** The text inside every bracket pair of a title. */
    private static List<String> segments(String title) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = SEGMENT.matcher(title);
        while (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) out.add(m.group(g));
            }
        }
        return out;
    }

    /**
     * The edition marks a title declares, kind name to descriptor: "US40 Remix"
     * yields {@code remix -> "us40"}. The descriptor is what tells two remixes
     * apart; an empty one means "some remix, name not given".
     *
     * <p>{@code remastered} is deliberately absent from {@link #KINDS}: a remaster keeps
     * the same words and only shifts a few frames, and it is the catalogue's default
     * suffix on half the back catalogue, so making it a kind would reject most correct
     * hits. The 60 s duration veto covers the rest; {@link #clean} still deletes the word
     * so it does not hurt the title score (AUDIT-33-5).</p>
     */
    public static Map<String, String> editionMap(String title) {
        Map<String, String> out = new LinkedHashMap<>();
        if (title == null || title.isBlank()) return out;
        List<String> places = new ArrayList<>(segments(title));
        int dash = lastDash(title);
        if (dash > 0) places.add(title.substring(dash + 3));
        for (String place : places) {
            for (Kind kind : KINDS) {
                java.util.regex.Matcher m = kind.latin().matcher(place);
                if (!m.find()) continue;
                String descriptor = norm(place.substring(0, m.start()) + place.substring(m.end()));
                out.putIfAbsent(kind.name(), descriptor);
            }
        }
        for (Kind kind : KINDS) {
            if (out.containsKey(kind.name())) continue;
            java.util.regex.Matcher m = kind.cjk().matcher(title);
            if (m.find()) out.putIfAbsent(kind.name(), "");
        }
        return out;
    }

    /**
     * Whether the hit is the same recording as the playing track.
     *
     * <p>Kugou answered "Euphoria" while the player was playing
     * "Euphoria (feat. Tsugumi Nagahara) [US40 Remix]": {@link #bare} cut the
     * brackets and {@link #clean} deleted every edition word, so both sides
     * compared as "euphoria" and the score never asked. Same title, different
     * recording - the sheet is for another cut, its timing is wrong everywhere and
     * its lyrics may not even be the same words. Marks therefore have to match
     * exactly, and two marks of the same kind have to carry the same descriptor
     * (two different remixes are two different recordings).</p>
     */
    public static boolean sameEdition(String wanted, String hit) {
        Map<String, String> a = editionMap(wanted);
        Map<String, String> b = editionMap(hit);
        if (!a.keySet().equals(b.keySet())) return false;
        for (Map.Entry<String, String> e : a.entrySet()) {
            String mine = e.getValue();
            String other = b.get(e.getKey());
            // "Euphoria (Remix)" names the kind but not the cut, so there is nothing to
            // compare. Treating that as "same recording" made the gate a no-op exactly
            // where catalogues are laziest - the nameless spelling is the common one, so
            // a bare remix scored 88 and passed. Two nameless marks are still the same
            // (two "(Remix)" rows cannot be told apart); one named and one nameless is
            // not verifiable and counts as another recording. A penalty was the other
            // option, but the number that would meet ACCEPT (88 - x < 62) is a coin flip
            // and a wrong sheet costs more than a missing one.
            if (mine == null || other == null) continue;   // caller skipped the set check
            boolean namedHere = !mine.isEmpty();
            if (namedHere != !other.isEmpty()) return false;
            if (!namedHere) continue;                      // both name only the kind
            if (fuzzy(mine, other) < 0.6) return false;
        }
        return true;
    }

    /** Names after feat./ft., which change who sings rather than which words are sung. */
    public static List<String> features(String title) {
        List<String> out = new ArrayList<>();
        if (title == null) return out;
        java.util.regex.Matcher m = Pattern.compile(
                "(?i)(?:feat\\.?|ft\\.?|featuring)\\s*([^)\\]）】」\\-–—]+)").matcher(title);
        while (m.find()) {
            String name = norm(m.group(1));
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    /**
     * 0 when nothing can be said (one side lists no guest), 1 when the guests agree,
     * -1 when both list guests and they are different people. A different guest is a
     * different vocal take, but providers translate and abbreviate these names, so
     * the caller only takes points off instead of rejecting.
     */
    public static int featureVerdict(String wanted, String hit) {
        List<String> a = features(wanted);
        List<String> b = features(hit);
        if (a.isEmpty() || b.isEmpty()) return 0;
        double best = 0.0;
        for (String x : a) {
            for (String y : b) best = Math.max(best, fuzzy(x, y));
        }
        return best >= 0.6 ? 1 : -1;
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

        // A hit for another edition of the same title is another recording: its
        // sheet is timed against a different cut. Reject before the score is built,
        // otherwise "Euphoria" keeps beating nothing at all for a US40 Remix.
        if (!sameEdition(info.title(), title)) return REJECT;

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
        // A different guest singer is a different vocal take, not a different song:
        // take points off so a same-guest hit wins, but keep the sheet reachable.
        if (featureVerdict(info.title(), title) < 0) value -= 8.0;
        return (int) Math.round(Math.min(100.0, value));
    }
}
