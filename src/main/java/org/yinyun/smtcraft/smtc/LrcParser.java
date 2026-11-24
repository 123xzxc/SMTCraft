/*
 * Portions of this file are ported from Lyricify-Lyrics-Helper
 * (https://github.com/WXRIW/Lyricify-Lyrics-Helper),
 * Copyright 2023 XY Wang, WXRIW, licensed under the Apache License 2.0.
 * Modified for Java / Minecraft. See THIRD_PARTY_NOTICES.md.
 */
package org.yinyun.smtcraft.smtc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC / YRC -&gt; {@link LyricTrack}.
 *
 * <p>Handles the NetEase dialect: several timestamps on one line, 2 or 3 digit
 * milliseconds, {@code [offset:...]} and credit lines such as
 * {@code [00:00.164] 作曲 : Guiano} which are dropped instead of being sung.</p>
 *
 * <p>Two kinds of word-level timing are understood and turned into
 * {@link LyricLine.Word} lists:</p>
 * <ul>
 *   <li>enhanced LRC: {@code [00:12.00]<00:12.00>あ<00:12.40>な<00:12.80>た} —
 *       the inline tag belongs to the text that follows it;</li>
 *   <li>NetEase {@code yrc}: {@code [18920,3000](18920,340,0)あ(19260,300,0)な}
 *       — the line header carries start and duration, every {@code (start,dur,…)}
 *       tuple carries one syllable.</li>
 * </ul>
 */
public final class LrcParser {
    private static final Pattern TIME =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    /** Inline word tag of enhanced LRC: {@code <mm:ss.xx>}. */
    private static final Pattern WORD_TAG =
            Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern META =
            Pattern.compile("^\\[(ti|ar|al|by|offset|length|re|ve|kana|language):[^]]*]$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFSET =
            Pattern.compile("\\[offset:\\s*(-?\\d+)\\]", Pattern.CASE_INSENSITIVE);
    /** One yrc line: {@code [start,duration]body}. */
    private static final Pattern YRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    /** Provider watermark inside a translation block. */
    private static final Pattern NOTICE = Pattern.compile(
            "(以下)?歌词翻译由|翻譯由.{0,12}提供|翻译由.{0,12}提供|歌词由.{0,10}提供|由.{0,8}大模型提供");
    private static final Pattern CREDIT = Pattern.compile(
            "^(作词|作詞|作曲|编曲|編曲|调声|調聲|混音|母带|母帶|制作人|制作|監製|监制|吉他|贝斯|鼓|和声|和聲"
                    + "|词|曲|词曲|詞曲|演唱者|演唱|作词人|作曲人"
                    + "|录音|錄音|插画|插圖|动画|動畫|企划|企劃|翻译|翻譯|演唱|配唱|出品|发行|發行"
                    + "|版权|版權|著作权|著作權|本作品|未经许可|未經許可)\\s*[:：]");
    /**
     * NetEase and Kugou like to open a sheet with legal boilerplate that has no colon
     * after the keyword ("版权归属北京…有限公司"), so it escapes the pattern above.
     */
    private static final Pattern CREDIT_LOOSE = Pattern.compile(
            "版权归|版權歸|联合出品|聯合出品|录音作品|錄音作品|著作权|著作權|本作品|未经许可|未經許可"
                    + "|作品名称|作品名稱|出品方|发行方|發行方|唱片公司|制作公司|製作公司"
                    + "|版权管理方|版權管理方|词版权|詞版權|曲版权|曲版權|出品监制|出品監製");

    private LrcParser() {}

    public static LyricTrack parse(String lrc, String translated, String source, String label) {
        return parseRich(null, lrc, translated, source, label);
    }

    /**
     * Prefers {@code yrc} (real per-syllable timings) and falls back to the plain
     * LRC text, which may itself carry enhanced {@code <mm:ss.xx>} tags.
     */
    public static LyricTrack parseRich(String yrc, String lrc, String translated, String source,
                                       String label) {
        Trans trans = translationMap(translated);
        // A yrc block that yields nothing (wrong dialect, encrypted leftovers) must
        // not hide the plain LRC that came with it (A7).
        List<LyricLine> out = new ArrayList<LyricLine>();
        if (yrc != null && !yrc.trim().isEmpty()) out = yrcLines(yrc, trans);
        if (out.isEmpty() && lrc != null && !lrc.trim().isEmpty()) out = lrcLines(lrc, trans);
        if (out.isEmpty()) return LyricTrack.empty("no lyrics");
        out.sort(Comparator.comparingLong(LyricLine::startMs));
        return new LyricTrack(out, source, label);
    }

    // ---------------------------------------------------------------- LRC

    private static List<LyricLine> lrcLines(String lrc, Trans trans) {
        List<Timed> main = timed(lrc);
        List<LyricLine> out = new ArrayList<LyricLine>(main.size());
        for (int i = 0; i < main.size(); i++) {
            Timed t = main.get(i);
            String text = t.text;
            if (text.isEmpty()) continue;
            if (text.startsWith("[") && META.matcher(text).matches()) continue;
            if (isCredit(text, t.startMs)) continue;

            long next = i + 1 < main.size() ? main.get(i + 1).startMs : Long.MAX_VALUE;
            long end = next == Long.MAX_VALUE ? t.startMs + estimateMs(text) : next;
            if (t.words.isEmpty()) {
                long cap = t.startMs + estimateMs(text);
                if (end > cap) end = cap;
            } else {
                // real syllable data wins over the estimate, but never crosses the next line
                long lastWord = t.words.get(t.words.size() - 1).endMs();
                if (lastWord > end) end = lastWord;
            }
            List<LyricLine.Word> words = closeWords(t.words, end);
            out.add(new LyricLine(t.startMs, end, text,
                    cleanTranslation(trans.lookup(t.startMs), text), words));
        }
        return out;
    }

    /**
     * Word ends are only known once the line end is known: the last syllable of an
     * enhanced line has no following tag, so it runs until the line stops.
     */
    private static List<LyricLine.Word> closeWords(List<LyricLine.Word> words, long lineEnd) {
        if (words.isEmpty()) return words;
        List<LyricLine.Word> out = new ArrayList<LyricLine.Word>(words.size());
        for (int i = 0; i < words.size(); i++) {
            LyricLine.Word w = words.get(i);
            long end = w.endMs();
            if (end <= w.startMs()) {
                end = i + 1 < words.size() ? words.get(i + 1).startMs() : lineEnd;
            }
            if (end <= w.startMs()) end = w.startMs() + 180L;
            out.add(new LyricLine.Word(w.startMs(), end, w.from(), w.to()));
        }
        return out;
    }

    // ---------------------------------------------------------------- YRC

    private static List<LyricLine> yrcLines(String yrc, Trans trans) {
        List<LyricLine> out = new ArrayList<LyricLine>();
        for (String rawLine : yrc.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher m = YRC_LINE.matcher(line);
            if (!m.matches()) continue;
            long lineStart = parseLong(m.group(1));
            long lineDur = parseLong(m.group(2));
            String body = m.group(3);
            StringBuilder text = new StringBuilder();
            List<LyricLine.Word> words = new ArrayList<LyricLine.Word>();
            int p = 0;
            while (p < body.length()) {
                int open = body.indexOf('(', p);
                if (open < 0) {
                    text.append(body, p, body.length());
                    break;
                }
                if (open > p) text.append(body, p, open);
                int close = body.indexOf(')', open);
                if (close < 0) break;
                String[] parts = body.substring(open + 1, close).split(",");
                long ws = parts.length > 0 ? parseLong(parts[0].trim()) : 0L;
                long wd = parts.length > 1 ? parseLong(parts[1].trim()) : 0L;
                // some rips store offsets relative to the line, the spec says absolute
                if (ws < lineStart) ws += lineStart;
                int nextOpen = body.indexOf('(', close + 1);
                String wordText = nextOpen < 0 ? body.substring(close + 1) : body.substring(close + 1, nextOpen);
                int from = text.length();
                text.append(wordText);
                int to = text.length();
                int drawTo = stripTrailingSpace(text, from, to);
                if (to > from) {
                    words.add(new LyricLine.Word(ws, wd > 0L ? ws + wd : ws + 200L, from, drawTo));
                }
                if (nextOpen < 0) break;
                p = nextOpen;
            }
            String plain = text.toString();
            if (plain.trim().isEmpty() || isCredit(plain, lineStart)) continue;
            long end = lineDur > 0L ? lineStart + lineDur : lineStart + 1400L;
            if (!words.isEmpty()) {
                long last = words.get(words.size() - 1).endMs();
                if (last > end) end = last;
            }
            out.add(new LyricLine(lineStart, end, plain,
                    cleanTranslation(trans.nearest(lineStart, 900L), plain), words));
        }
        return out;
    }

    private static int stripTrailingSpace(StringBuilder sb, int from, int to) {
        int end = to;
        while (end > from && Character.isWhitespace(sb.charAt(end - 1))) end--;
        return end;
    }

    // ---------------------------------------------------------------- shared

    /**
     * Providers sometimes ship a phonetic transcript (romaji / kana reading) where the
     * translation belongs. It is dropped by default — a reading is not a translation —
     * but the settings screen can keep it instead ("second line shows the reading").
     */
    private static volatile boolean readings = false;

    /** True when the reading must be shown instead of discarded. */
    public static void setReadings(boolean value) {
        readings = value;
    }

    public static String cleanTranslation(String translation, String original) {
        if (translation == null) return "";
        String text = translation.trim();
        if (text.isEmpty() || "//".equals(text) || "／／".equals(text)) return "";
        // Kugou and QQ sprinkle their own watermark into the translation block
        // ("以下歌词翻译由文曲大模型提供"); it is an advert, not a lyric row.
        if (NOTICE.matcher(text).find()) return "";
        if (original != null && !original.trim().isEmpty() && text.equals(original.trim())) return "";
        if (!readings && isReading(text, original)) return "";
        return text;
    }

    /**
     * Some providers put a phonetic transcript where the translation belongs: QQ's
     * {@code contentts} and Kugou's {@code [language:]} carry romaji for Japanese
     * songs, and some sources repeat a kana reading of a kanji line. Neither is a
     * translation, so both are rejected here instead of being shown as a subtitle.
     */
    private static boolean isReading(String text, String original) {
        if (original == null || original.trim().isEmpty()) return false;
        if (hasKana(original) && isRomanization(text)) return true;
        return hasHan(original) && isKanaOnly(text);
    }

    /**
     * {@code ra to bi ta tsu no wa mo u ko wa}: latin letters, lowercase, plus the
     * punctuation providers leave inside a romaji line - Japanese commas, the
     * long-vowel mark of a held syllable ("so ー to"), even arrows. Anything that
     * looks like an actual word in another script disqualifies it.
     */
    private static boolean isRomanization(String text) {
        int latin = 0;
        int upper = 0;
        int foreign = 0;
        int han = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            // ー 〜 ～ → and friends are how KRC writes a stretched syllable
            if (cp == 0x30FC || cp == 0x301C || cp == 0xFF5E || cp == 0x2192) continue;
            if (Character.isLetter(cp)) {
                if (cp > 0x024F) {
                    foreign++;
                    if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)) han++;
                } else {
                    latin++;
                    if (Character.isUpperCase(cp)) upper++;
                }
            } else if (Character.isDigit(cp) || Character.isWhitespace(cp) || isPunctuation(cp)) {
                continue;
            } else {
                return false;
            }
        }
        // a translated line is written in the reader's own script; a romaji line
        // with one stray kanji or kana ("suna ni 踊lu", "so ーto") is still romaji
        boolean latinate = latin >= 2 * foreign;
        if (!latinate) return false;
        if (latin < 3) return false;
        // an English sentence capitalises its words; romaji keeps them lower case
        return upper <= Math.max(1, latin / 4);
    }

    /** Punctuation and symbols never make a line a translation on their own. */
    private static boolean isPunctuation(int cp) {
        switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
            case Character.MATH_SYMBOL:
            case Character.MODIFIER_SYMBOL:
            case Character.OTHER_SYMBOL:
                return true;
            default:
                return false;
        }
    }

    /** Kana only (no kanji, no latin): a reading, not a translation. */
    private static boolean isKanaOnly(String text) {
        int kana = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || "、。，．！？!?…ー〜～・".indexOf(cp) >= 0) continue;
            boolean isKana = (cp >= 0x3040 && cp <= 0x30FF) || (cp >= 0x31F0 && cp <= 0x31FF);
            if (!isKana) return false;
            kana++;
        }
        return kana >= 2;
    }

    private static boolean hasKana(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 0x3040 && cp <= 0x30FF) || (cp >= 0x31F0 && cp <= 0x31FF)) return true;
        }
        return false;
    }

    private static boolean hasHan(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)) return true;
        }
        return false;
    }

    /**
     * Translation table: exact hits for LRC (timestamps line up) and a sorted key
     * array for {@link #nearest}, which used to scan every entry per lyric line —
     * O(lines x translations) on a 3000 line sheet (A25).
     */
    private static final class Trans {
        /** How far a translation row may sit from its lyric row and still belong to it. */
        static final long LINE_TOLERANCE_MS = 400L;
        static final Trans EMPTY =
                new Trans(Collections.<Long, String>emptyMap(), new long[0], new String[0]);

        private final Map<Long, String> exact;
        private final long[] keys;
        private final String[] values;

        Trans(Map<Long, String> exact, long[] keys, String[] values) {
            this.exact = exact;
            this.keys = keys;
            this.values = values;
        }

        String at(long startMs) {
            String hit = exact.get(startMs);
            return hit == null ? "" : hit;
        }

        /**
         * Translation for a lyric row: the exact timestamp when the two sheets agree,
         * otherwise the closest entry within {@link #LINE_TOLERANCE_MS}. Providers
         * routinely offset the translation table by a few hundred milliseconds, and an
         * exact-only lookup silently dropped those translations.
         */
        String lookup(long startMs) {
            String exactHit = exact.get(startMs);
            return exactHit != null ? exactHit : nearest(startMs, LINE_TOLERANCE_MS);
        }

        /** Closest entry within {@code toleranceMs}, or "". */
        String nearest(long startMs, long toleranceMs) {
            if (keys.length == 0) return "";
            int lo = 0;
            int hi = keys.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (keys[mid] < startMs) lo = mid + 1;
                else hi = mid;
            }
            long bestDiff = Long.MAX_VALUE;
            String best = "";
            for (int i = Math.max(0, lo - 1); i <= Math.min(keys.length - 1, lo); i++) {
                long diff = Math.abs(keys[i] - startMs);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = values[i];
                }
            }
            return bestDiff <= toleranceMs ? best : "";
        }
    }

    /** True for a credit row such as {@code 作词 : Guiano} at the head of a sheet. */
    public static boolean isCredit(String text, long startMs) {
        if (startMs >= 25000L || text.isEmpty()) return false;
        // "歌名 - 歌手" is how several rips open a sheet, at offset zero
        if (startMs < 500L && text.contains(" - ")) return true;
        if (startMs < 15000L && CREDIT.matcher(text).find()) return true;
        return CREDIT_LOOSE.matcher(text).find();
    }

    /** Parsed string width of one line, rough but good enough to bound a karaoke wipe. */
    private static long estimateMs(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            n += c < 0x2E80 ? 1 : 2;      // latin ~1, CJK/kana ~2
        }
        long ms = 120L * Math.max(1, n);
        if (ms < 1400L) ms = 1400L;
        if (ms > 9000L) ms = 9000L;
        return ms;
    }

    private static Trans translationMap(String translated) {
        Map<Long, String> map = new HashMap<Long, String>();
        for (Timed t : timed(translated)) {
            if (t.text.isEmpty() || META.matcher(t.text).matches()) continue;
            if (!map.containsKey(t.startMs)) map.put(t.startMs, t.text);
        }
        if (map.isEmpty()) return Trans.EMPTY;
        long[] keys = new long[map.size()];
        String[] values = new String[map.size()];
        int i = 0;
        for (long key : new TreeSet<Long>(map.keySet())) {
            keys[i] = key;
            values[i] = map.get(key);
            i++;
        }
        return new Trans(map, keys, values);
    }

    /** One parsed row before line ends and translations are attached. */
    private static final class Timed {
        private final long startMs;
        private final String text;
        private final List<LyricLine.Word> words;

        Timed(long startMs, String text, List<LyricLine.Word> words) {
            this.startMs = startMs;
            this.text = text;
            this.words = words;
        }

        long startMs() {
            return startMs;
        }
    }

    private static List<Timed> timed(String raw) {
        List<Timed> out = new ArrayList<Timed>();
        if (raw == null || raw.trim().isEmpty()) return out;
        long offset = 0L;
        Matcher off = OFFSET.matcher(raw);
        if (off.find()) {
            try {
                offset = Long.parseLong(off.group(1));
            } catch (NumberFormatException ignored) {
                offset = 0L;
            }
        }
        for (String rawLine : raw.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher m = TIME.matcher(line);
            List<Long> stamps = new ArrayList<Long>(2);
            int textFrom = -1;
            while (m.find()) {
                stamps.add(ms(m.group(1), m.group(2), m.group(3)));
                textFrom = m.end();
            }
            if (stamps.isEmpty()) continue;
            String body = textFrom >= 0 && textFrom < line.length()
                    ? line.substring(textFrom).trim()
                    : "";
            String text = body;
            List<LyricLine.Word> words = Collections.<LyricLine.Word>emptyList();
            if (WORD_TAG.matcher(body).find()) {
                StringBuilder stripped = new StringBuilder(body.length());
                List<LyricLine.Word> pending = new ArrayList<LyricLine.Word>();
                Matcher w = WORD_TAG.matcher(body);
                int cursor = 0;
                long stamp = -1L;
                int from = 0;
                while (w.find()) {
                    stripped.append(body, cursor, w.start());
                    if (stamp >= 0L) {
                        pending.add(new LyricLine.Word(stamp, -1L, from, stripped.length()));
                    }
                    stamp = ms(w.group(1), w.group(2), w.group(3)) + offset;
                    cursor = w.end();
                    from = stripped.length();
                }
                stripped.append(body, cursor, body.length());
                if (stamp >= 0L) {
                    pending.add(new LyricLine.Word(stamp, -1L, from, stripped.length()));
                }
                text = stripped.toString();
                words = pending;
            }
            // A repeated chorus carries several timestamps on one line; every copy
            // needs its own word times shifted by the same delta, otherwise the
            // karaoke wipe on the second chorus runs at the first one's pace (A8).
            long first = stamps.get(0);
            for (long st : stamps) {
                List<LyricLine.Word> shifted = words;
                if (!words.isEmpty() && st != first) {
                    long delta = st - first;
                    shifted = new ArrayList<LyricLine.Word>(words.size());
                    for (LyricLine.Word w : words) {
                        long wEnd = w.endMs() > w.startMs() ? w.endMs() + delta : w.endMs();
                        shifted.add(new LyricLine.Word(w.startMs() + delta, wEnd, w.from(), w.to()));
                    }
                }
                out.add(new Timed(st + offset, text, shifted));
            }
        }
        out.sort(Comparator.comparingLong(Timed::startMs));
        return out;
    }

    private static long ms(String min, String sec, String frac) {
        long m = parseLong(min);
        long s = parseLong(sec);
        long f = 0L;
        if (frac != null) {
            f = parseLong(frac);
            if (frac.length() == 2) f *= 10L;
            else if (frac.length() == 1) f *= 100L;
        }
        return m * 60000L + s * 1000L + f;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
