package org.yinyun.smtcraft.smtc;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The lyric sources the mod knows and the one place their default order lives.
 *
 * <p>That order used to be a literal in four files (config default, config
 * fallback, settings button and the service). Changing one of them silently left
 * the mod with a partial or empty order, which reads as "no lyrics at all" (AUDIT-33),
 * so every user of the order now points here.</p>
 */
public final class LyricProvider {
    public static final String KUGOU = "kugou";
    public static final String QQ = "qq";
    public static final String NETEASE = "netease";

    /**
     * Tried left to right; a song falls through to the next source only when the
     * one before it has nothing. Any order is legal - the settings button cycles
     * through alternatives - and unknown names are dropped on load.
     */
    public static final String DEFAULT_ORDER = "kugou,qq,netease";

    /** Every provider name, in no particular order. */
    public static final List<String> KNOWN = Collections.unmodifiableList(
            Arrays.asList(KUGOU, QQ, NETEASE));

    private LyricProvider() {
    }

    public static boolean known(String name) {
        return name != null && KNOWN.contains(name);
    }
}
