/*
 * Portions of this file are ported from Lyricify-Lyrics-Helper
 * (https://github.com/WXRIW/Lyricify-Lyrics-Helper),
 * Copyright 2023 XY Wang, WXRIW, licensed under the Apache License 2.0.
 * Modified for Java / Minecraft. See THIRD_PARTY_NOTICES.md.
 */
package org.yinyun.smtcraft.smtc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsers for the two other syllable-synced formats:
 *
 * <ul>
 *   <li>QRC (QQ Music): {@code [lineStart,lineDur]word(start,dur)word(start,dur)…}
 *       with absolute millisecond starts per word;</li>
 *   <li>KRC (Kugou): {@code [lineStart,lineDur]<start,dur,0>word<start,dur,0>word…}
 *       with starts relative to the line.</li>
 * </ul>
 *
 * <p>Both already carry word-level timings, so every line becomes a
 * {@link LyricLine} with {@link LyricLine.Word} entries and the karaoke sweep can
 * follow the singer exactly. Lines that carry no word tags at all are kept as
 * plain line-level lyrics instead of being dropped (A9).</p>
 */
public final class SyllableParsers {
    private static final Pattern QRC_WORD =
            Pattern.compile("(.*?)\\((\\d+),(\\d+)\\)", Pattern.DOTALL);
    /** QRC line header {@code [start,duration]}. */
    private static final Pattern QRC_HEAD = Pattern.compile("^\\[(\\d+),(\\d+)]");
    private static final Pattern TIME =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET =
            Pattern.compile("\\[offset:\\s*(-?\\d+)]", Pattern.CASE_INSENSITIVE);

    private SyllableParsers() {}

    // ---------------------------------------------------------------- QRC

    public static LyricTrack parseQrc(String qrc, String transQrc, String source, String label) {
        if (qrc == null || qrc.trim().isEmpty()) return LyricTrack.empty("no lyrics");
        Texts trans = qrcLineTexts(transQrc);
        List<LyricLine> out = new ArrayList<LyricLine>();
        for (String raw : qrc.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            LyricLine parsed = qrcLine(line);
            if (parsed == null) continue;
            if (LrcParser.isCredit(parsed.text(), parsed.startMs())) continue;
            out.add(new LyricLine(parsed.startMs(), parsed.endMs(), parsed.text(),
                    LrcParser.cleanTranslation(trans.nearest(parsed.startMs(), 900L), parsed.text()),
                    parsed.words()));
        }
        if (out.isEmpty()) return LyricTrack.empty("no lyrics");
        out.sort(Comparator.comparingLong(LyricLine::startMs));
        return new LyricTrack(out, source, label);
    }

    /**
     * One QRC line: read the {@code [start,dur]} header, then cut at every word
     * tag. A line without a single word tag is still a lyric line - the header
     * timestamps are enough for a line-level row.
     */
    private static LyricLine qrcLine(String line) {
        Matcher head = QRC_HEAD.matcher(line);
        if (!head.find()) return null;
        long lineStart = parseLong(head.group(1));
        long lineDur = parseLong(head.group(2));
        String body = line.substring(head.end());
        StringBuilder text = new StringBuilder();
        List<LyricLine.Word> words = new ArrayList<LyricLine.Word>();
        Matcher m = QRC_WORD.matcher(body);
        int cursor = 0;
        while (m.find()) {
            int from = text.length();
            text.append(m.group(1));
            int to = text.length();
            long start = parseLong(m.group(2));
            long dur = parseLong(m.group(3));
            if (to > from) {
                words.add(new LyricLine.Word(start, start + Math.max(1L, dur), from, to));
            }
            cursor = m.end();
        }
        if (cursor < body.length()) text.append(body, cursor, body.length());
        String plain = text.toString().trim();
        if (plain.isEmpty()) return null;
        long start = words.isEmpty() ? lineStart : words.get(0).startMs();
        long end = lineStart + (lineDur > 0L ? lineDur : 1400L);
        if (!words.isEmpty()) end = Math.max(end, words.get(words.size() - 1).endMs());
        if (end <= start) end = start + 1400L;
        return new LyricLine(start, end, plain, "", words);
    }

    /** Line text of a translation sheet (QRC or LRC), keyed by line start. */
    private static Texts qrcLineTexts(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Texts.EMPTY;
        if (!raw.contains("(")) return lrcTexts(raw);
        Map<Long, String> map = new HashMap<Long, String>();
        for (String line : raw.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            LyricLine parsed = qrcLine(t);
            if (parsed == null) continue;
            if (!map.containsKey(parsed.startMs())) map.put(parsed.startMs(), parsed.text().trim());
        }
        return Texts.of(map);
    }

    // ---------------------------------------------------------------- LRC

    private static Texts lrcTexts(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Texts.EMPTY;
        Map<Long, String> map = new HashMap<Long, String>();
        long offset = offsetOf(raw);
        for (String line : raw.split("\\r?\\n")) {
            Matcher m = TIME.matcher(line);
            long stamp = -1L;
            int from = -1;
            while (m.find()) {
                long min = parseLong(m.group(1));
                long sec = parseLong(m.group(2));
                long frac = 0L;
                String f = m.group(3);
                if (f != null) {
                    frac = parseLong(f);
                    if (f.length() == 2) frac *= 10L;
                    else if (f.length() == 1) frac *= 100L;
                }
                stamp = min * 60000L + sec * 1000L + frac;
                from = m.end();
            }
            if (stamp < 0L) continue;
            String text = from >= 0 && from < line.length() ? line.substring(from).trim() : "";
            if (!text.isEmpty() && !map.containsKey(stamp + offset)) {
                map.put(stamp + offset, text);
            }
        }
        return Texts.of(map);
    }

    /** {@code [offset:+250]} shifts every timestamp; LrcParser honours it too (A11). */
    private static long offsetOf(String raw) {
        Matcher m = OFFSET.matcher(raw);
        if (!m.find()) return 0L;
        return parseLong(m.group(1));
    }

    // ---------------------------------------------------------------- KRC

    public static LyricTrack parseKrc(String krc, String source, String label) {
        return parseKrc(krc, null, source, label);
    }

    /**
     * @param transLrc extra translation sheet (plain LRC), used when the KRC file
     *                 itself carries no usable {@code [language:…]} block
     */
    public static LyricTrack parseKrc(String krc, String transLrc, String source, String label) {
        if (krc == null || krc.trim().isEmpty()) return LyricTrack.empty("no lyrics");
        Texts extra = lrcTexts(transLrc);

        // Timed lines first, in file order: the translation block is aligned by
        // lyric line ordinal, so blank lines have to be counted too (A10).
        List<LyricLine> timed = new ArrayList<LyricLine>();
        for (String raw : krc.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.length() < 3 || line.charAt(0) != '[') continue;
            if (!Character.isDigit(line.charAt(1))) continue;    // skip [ti:], [language:…]
            LyricLine parsed = krcLine(line);
            if (parsed != null) {
                timed.add(parsed);
                continue;
            }
            long[] head = krcHead(line);
            if (head != null) {
                // keep the slot so the ordinal stays aligned with the translation list
                timed.add(new LyricLine(head[0], head[0] + Math.max(1L, head[1]), "", "",
                        Collections.<LyricLine.Word>emptyList()));
            }
        }
        if (timed.isEmpty()) return LyricTrack.empty("no lyrics");

        List<String> trans = krcTranslations(krc);
        Texts aligned = alignTranslations(timed, trans);

        List<LyricLine> out = new ArrayList<LyricLine>(timed.size());
        for (LyricLine line : timed) {
            if (line.text().trim().isEmpty()) continue;
            if (LrcParser.isCredit(line.text(), line.startMs())) continue;
            String text = aligned.nearest(line.startMs(), 120L);
            if (text.isEmpty()) text = extra.nearest(line.startMs(), 900L);
            text = LrcParser.cleanTranslation(text, line.text());
            out.add(new LyricLine(line.startMs(), line.endMs(), line.text(), text, line.words()));
        }
        if (out.isEmpty()) return LyricTrack.empty("no lyrics");
        out.sort(Comparator.comparingLong(LyricLine::startMs));
        return new LyricTrack(out, source, label);
    }

    /**
     * Kugou writes one translation row per lyric row, credits included. When the
     * block is longer than the lyric list the extra rows are at the head, so the
     * index is shifted instead of trusting the raw ordinal.
     */
    private static Texts alignTranslations(List<LyricLine> timed, List<String> trans) {
        if (trans.isEmpty() || timed.isEmpty()) return Texts.EMPTY;
        // The block carries no timestamps, so it can only be aligned by position. The
        // head shift is not always positive (a trailing credit row in the sheet, a
        // credit row missing in the block), so try a window of shifts and keep the one
        // that reproduces the sheet's blank rows best and translates the most lines.
        int limit = Math.max(4, Math.abs(trans.size() - timed.size()));
        int bestShift = 0;
        int bestScore = Integer.MIN_VALUE;
        for (int shift = -limit; shift <= limit; shift++) {
            int score = 0;
            for (int i = 0; i < timed.size(); i++) {
                int ti = i + shift;
                if (ti < 0 || ti >= trans.size()) {
                    score -= 2;
                    continue;
                }
                String text = trans.get(ti);
                boolean transBlank = text == null || text.trim().isEmpty();
                boolean lineBlank = timed.get(i).text().trim().isEmpty();
                score += transBlank == lineBlank ? 2 : -1;
                if (!transBlank) score += 1;
            }
            if (score > bestScore) {
                bestScore = score;
                bestShift = shift;
            }
        }
        Map<Long, String> map = new HashMap<Long, String>();
        for (int i = 0; i < timed.size(); i++) {
            int ti = i + bestShift;
            if (ti < 0 || ti >= trans.size()) continue;
            String text = trans.get(ti);
            if (text == null || text.trim().isEmpty()) continue;
            if (!map.containsKey(timed.get(i).startMs())) {
                map.put(timed.get(i).startMs(), text.trim());
            }
        }
        return Texts.of(map);
    }

    private static long[] krcHead(String line) {
        int close = line.indexOf(']');
        if (close < 0) return null;
        String[] head = line.substring(1, close).split(",");
        if (head.length == 0) return null;
        long start = parseLong(head[0]);
        long dur = head.length > 1 ? parseLong(head[1]) : 0L;
        return new long[] {start, dur};
    }

    private static LyricLine krcLine(String line) {
        int close = line.indexOf(']');
        if (close < 0) return null;
        String[] head = line.substring(1, close).split(",");
        long lineStart = parseLong(head[0]);
        long lineDur = head.length > 1 ? parseLong(head[1]) : 0L;
        String body = line.substring(close + 1);

        StringBuilder text = new StringBuilder();
        List<LyricLine.Word> words = new ArrayList<LyricLine.Word>();
        int p = 0;
        while (p < body.length()) {
            int open = body.indexOf('<', p);
            if (open < 0) {
                text.append(body, p, body.length());
                break;
            }
            if (open > p) text.append(body, p, open);
            int gt = body.indexOf('>', open);
            if (gt < 0) break;
            String[] stamp = body.substring(open + 1, gt).split(",");
            long start = parseLong(stamp[0]);
            long dur = stamp.length > 1 ? parseLong(stamp[1]) : 0L;
            int nextOpen = body.indexOf('<', gt + 1);
            // the word text runs until the next '<start,dur,0>' group
            String chunk = body.substring(gt + 1, nextOpen < 0 ? body.length() : nextOpen);
            int from = text.length();
            text.append(chunk);
            int to = text.length();
            if (to > from) {
                words.add(new LyricLine.Word(lineStart + start,
                        lineStart + start + Math.max(1L, dur), from, to));
            }
            if (nextOpen < 0) break;
            p = nextOpen;
        }
        String plain = text.toString().trim();
        if (plain.isEmpty()) return null;
        long end = lineStart + (lineDur > 0L ? lineDur : 1400L);
        if (!words.isEmpty()) end = Math.max(end, words.get(words.size() - 1).endMs());
        if (end <= lineStart) end = lineStart + 1400L;
        return new LyricLine(lineStart, end, plain, "", words);
    }

    /**
     * KRC keeps its translation in a base64 JSON line of its own:
     * {@code [language:eyJjb250ZW50IjpbeyJseXJpY0NvbnRlbnQiOltbIuS4i+mdol0s...}]}.
     */
    private static List<String> krcTranslations(String krc) {
        List<String> out = new ArrayList<String>();
        int at = krc.indexOf("[language:");
        if (at < 0) return out;
        int from = at + "[language:".length();
        int to = krc.indexOf(']', from);
        if (to <= from) return out;
        try {
            String json = new String(Base64.getDecoder().decode(krc.substring(from, to).trim()),
                    StandardCharsets.UTF_8);
            // Gson 2.2.4 (what 1.7.10 ships) has no static parseString, only this
            JsonElement root = new JsonParser().parse(json);
            if (!root.isJsonObject()) return out;
            JsonArray content = root.getAsJsonObject().getAsJsonArray("content");
            if (content == null || content.size() == 0) return out;
            JsonObject first = content.get(0).getAsJsonObject();
            JsonArray lines = first.getAsJsonArray("lyricContent");
            if (lines == null) return out;
            for (JsonElement el : lines) {
                StringBuilder line = new StringBuilder();
                if (el.isJsonArray()) {
                    for (JsonElement part : el.getAsJsonArray()) {
                        if (part.isJsonPrimitive()) line.append(part.getAsString());
                    }
                }
                String text = line.toString().trim();
                out.add("//".equals(text) ? "" : text);
            }
        } catch (Exception ignored) {
            return out;
        }
        return out;
    }

    // ---------------------------------------------------------------- shared

    /**
     * Sorted timestamp table with a binary {@code nearest}: the old linear scan
     * walked the whole translation sheet for every lyric line (A25).
     */
    private static final class Texts {
        static final Texts EMPTY = new Texts(new long[0], new String[0]);

        private final long[] keys;
        private final String[] values;

        Texts(long[] keys, String[] values) {
            this.keys = keys;
            this.values = values;
        }

        static Texts of(Map<Long, String> map) {
            if (map == null || map.isEmpty()) return EMPTY;
            TreeMap<Long, String> sorted = new TreeMap<Long, String>(map);
            long[] keys = new long[sorted.size()];
            String[] values = new String[sorted.size()];
            int i = 0;
            for (Map.Entry<Long, String> e : sorted.entrySet()) {
                keys[i] = e.getKey();
                values[i] = e.getValue();
                i++;
            }
            return new Texts(keys, values);
        }

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

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
