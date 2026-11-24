package org.yinyun.smtcraft.smtc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A parsed, time-ordered lyric sheet for one song. */
public final class LyricTrack {
    public static final LyricTrack EMPTY =
            new LyricTrack(Collections.<LyricLine>emptyList(), "", "");

    private final List<LyricLine> lines;
    private final String source;
    private final String label;
    private final boolean translated;
    /** Sorted translations, so {@link #translationNear} can binary search (A26). */
    private final long[] trStarts;
    private final String[] trTexts;

    public LyricTrack(List<LyricLine> lines, String source, String label) {
        // Java 8 stand-in for List.copyOf: immutable snapshot of the filtered rows
        this.lines = Collections.unmodifiableList(
                new ArrayList<LyricLine>(dropEchoTranslations(lines)));
        this.source = source == null ? "" : source;
        this.label = label == null ? "" : label;
        int count = 0;
        for (LyricLine line : this.lines) {
            if (line.hasTranslation()) count++;
        }
        this.translated = count > 0;
        this.trStarts = new long[count];
        this.trTexts = new String[count];
        int i = 0;
        for (LyricLine line : this.lines) {
            if (!line.hasTranslation()) continue;
            trStarts[i] = line.startMs();
            trTexts[i] = line.translation();
            i++;
        }
    }

    /**
     * A few providers ship a translation block that is shifted by one row, so the
     * "translation" under a lyric row is simply that row's own text again. Only the
     * same row is compared: an earlier version also dropped any translation that
     * happened to repeat <em>another</em> line, which threw away the perfectly
     * valid translation of a repeating chorus (A13).
     */
    private static List<LyricLine> dropEchoTranslations(List<LyricLine> in) {
        List<LyricLine> out = new ArrayList<LyricLine>(in.size());
        for (LyricLine line : in) {
            if (line.hasTranslation()
                    && SongMatch.norm(line.translation()).equals(SongMatch.norm(line.text()))) {
                out.add(new LyricLine(line.startMs(), line.endMs(), line.text(), "", line.words()));
            } else {
                out.add(line);
            }
        }
        return out;
    }

    public static LyricTrack empty(String reason) {
        return new LyricTrack(Collections.<LyricLine>emptyList(), reason == null ? "" : reason, "");
    }

    public boolean isEmpty() { return lines.isEmpty(); }
    public int size() { return lines.size(); }
    public List<LyricLine> lines() { return lines; }
    public String source() { return source; }
    public String label() { return label; }
    public boolean hasTranslations() { return translated; }

    /** Clamped access; null when the index is out of range. */
    public LyricLine line(int index) {
        if (index < 0 || index >= lines.size()) return null;
        return lines.get(index);
    }

    /**
     * Fill in the translations this sheet is missing from {@code other}, matching
     * lines by start time. Used when the syllable provider has no translation but
     * another provider does: the karaoke timing comes from one, the meaning from
     * the other.
     */
    public LyricTrack withTranslation(LyricTrack other) {
        if (other == null || other.isEmpty()) return this;
        List<LyricLine> merged = new ArrayList<LyricLine>(lines.size());
        boolean any = false;
        for (LyricLine line : lines) {
            if (line.hasTranslation()) {
                merged.add(line);
                continue;
            }
            String text = LrcParser.cleanTranslation(
                    other.translationNear(line.startMs(), 1200L), line.text());
            if (text.isEmpty()) {
                merged.add(line);
            } else {
                merged.add(new LyricLine(line.startMs(), line.endMs(), line.text(), text,
                        line.words()));
                any = true;
            }
        }
        return any ? new LyricTrack(merged, source + "+" + other.source(), label) : this;
    }

    /** Translation of the line whose start is closest to {@code startMs}. */
    public String translationNear(long startMs, long toleranceMs) {
        if (trStarts.length == 0) return "";
        int lo = 0;
        int hi = trStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (trStarts[mid] < startMs) lo = mid + 1;
            else hi = mid;
        }
        long bestDiff = Long.MAX_VALUE;
        String best = "";
        for (int i = Math.max(0, lo - 1); i <= Math.min(trStarts.length - 1, lo); i++) {
            long diff = Math.abs(trStarts[i] - startMs);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = trTexts[i];
            }
        }
        return bestDiff <= toleranceMs ? LrcParser.cleanTranslation(best, null) : "";
    }

    /** Index of the last line that started at or before {@code positionMs}; -1 before the first. */
    public int indexAt(long positionMs) {
        int lo = 0;
        int hi = lines.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startMs() <= positionMs) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** 0..1 fill of the line at {@code positionMs}, -1 when there is no line. */
    public float progressAt(long positionMs) {
        int i = indexAt(positionMs);
        if (i < 0) return -1.0f;
        LyricLine line = lines.get(i);
        long span = line.endMs() - line.startMs();
        if (span <= 0L) return 1.0f;
        float p = (positionMs - line.startMs()) / (float) span;
        if (p < 0.0f) return 0.0f;
        return Math.min(p, 1.0f);
    }
}
