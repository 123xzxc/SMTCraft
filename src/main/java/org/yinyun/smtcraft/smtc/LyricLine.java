package org.yinyun.smtcraft.smtc;

import java.util.ArrayList;
import java.util.Collections;
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
public final class LyricLine {
    private final long startMs;
    private final long endMs;
    private final String text;
    private final String translation;
    private final List<Word> words;

    public LyricLine(long startMs, long endMs, String text, String translation,
                     List<Word> words) {
        if (text == null) text = "";
        if (translation == null) translation = "";
        this.startMs = startMs;
        this.endMs = endMs <= startMs ? startMs + 1L : endMs;
        this.text = text;
        this.translation = translation;
        // unmodifiable copy: the sheet is read from the render thread while the
        // service may still be building it (Java 8 has no List.copyOf)
        this.words = words == null
                ? Collections.<Word>emptyList()
                : Collections.unmodifiableList(new ArrayList<Word>(words));
    }

    public LyricLine(long startMs, long endMs, String text, String translation) {
        this(startMs, endMs, text, translation, Collections.<Word>emptyList());
    }

    public long startMs() {
        return startMs;
    }

    public long endMs() {
        return endMs;
    }

    public String text() {
        return text;
    }

    public String translation() {
        return translation;
    }

    public List<Word> words() {
        return words;
    }

    public boolean hasTranslation() {
        return !translation.trim().isEmpty();
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
    public static final class Word {
        private final long startMs;
        private final long endMs;
        private final int from;
        private final int to;

        public Word(long startMs, long endMs, int from, int to) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.from = from;
            this.to = to;
        }

        public long startMs() {
            return startMs;
        }

        public long endMs() {
            return endMs;
        }

        public int from() {
            return from;
        }

        public int to() {
            return to;
        }

        public long lengthMs() {
            return Math.max(1L, endMs - startMs);
        }
    }
}
