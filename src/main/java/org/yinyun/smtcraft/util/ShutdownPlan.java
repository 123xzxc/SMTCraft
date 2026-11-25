package org.yinyun.smtcraft.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The shutdown sequence as data rather than as control flow (AUDIT-16-3).
 *
 * <p>{@code finishShutdown} states two properties in its javadoc: the order
 * (drain before writing, the bridge before the writes so it keeps their reserve,
 * the watchdog before anything that can block) and the isolation (a failing step
 * skips only itself). Both were true, and both were held up by nothing but
 * somebody reading the code — while two of the four violations found so far
 * (R14-1, R15-1) were exactly a reordering.</p>
 *
 * <p>So the six steps live here as names. The client builds one action per name
 * and walks this list, and the offline self test asserts the order on the list
 * alone — no Minecraft, no threads. Renaming a step breaks the compile;
 * reordering the list fails a check.</p>
 */
public final class ShutdownPlan {
    /** Quiet the background lanes first: a queued write is older than memory. */
    public static final String DRAIN = "drain the background lanes";
    /** The only step that can legitimately block for ten seconds. */
    public static final String BRIDGE = "close the media bridge";
    /** After the drain, never before it (AUDIT-15-1). */
    public static final String WRITE = "write the config";
    public static final String FLUSH = "flush the offset table";
    public static final String LANES = "stop the background lanes";
    /** Last: its two waits are the most forgiving of a small remainder. */
    public static final String LYRICS = "close the lyric worker";

    // unmodifiableList 保留同一份“不可改”契约；顺序即求值顺序，1.8.9 上没有不可变工厂
    private static final List<String> STEPS = Collections.unmodifiableList(
            Arrays.asList(DRAIN, BRIDGE, WRITE, FLUSH, LANES, LYRICS));

    private ShutdownPlan() {
    }

    /** The steps, in the order they must run. Immutable. */
    public static List<String> steps() {
        return STEPS;
    }
}
