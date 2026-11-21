package org.yinyun.smtcraft.smtc;

import java.util.List;

/**
 * One timed lyric line, optionally with its translation and with word-level
 * (per-syllable) timings.
 *
 * <p>{@code endMs} is derived by the parser: the next line's start, capped by a
 * length estimate so a long instrumental gap does not stretch one line's karaoke
 * wipe over thirty seconds.</p>
 *
 * <p>{@code words} is empty for plain LRC. When the source provides enhanced
 * LRC ({@code <mm:ss.xx>} tags) or NetEase {@code yrc}, every entry maps a
 * {@link Word} onto a character range of {@link #text()} with real start/end
 * milliseconds, so the karaoke sweep follows the actual singing instead of a
 * per-glyph guess.</p>
 */
public record LyricLine(long startMs, long endMs, String text, String translation,
                        List<Word> words) {

    public LyricLine {
        if (text == null) text = "";
        if (translation == null) translation = "";
        words = words == null ? List.of() : List.copyOf(words);
        if (endMs <= startMs) endMs = startMs + 1L;
    }

    public LyricLine(long startMs, long endMs, String text, String translation) {
        this(startMs, endMs, text, translation, List.of());
    }

    public boolean hasTranslation() {
        return !translation.isBlank();
    }

    /** True when this line carries real per-syllable timings. */
    public boolean hasWords() {
        return !words.isEmpty();
    }

    public long lengthMs() {
        return endMs - startMs;
    }

    /**
     * A sung syllable: {@code [from, to)} are UTF-16 indices into the line text,
     * so a renderer can decide per glyph whether it is already sung.
     */
    public record Word(long startMs, long endMs, int from, int to) {
        public long lengthMs() {
            return Math.max(1L, endMs - startMs);
        }
    }
}
