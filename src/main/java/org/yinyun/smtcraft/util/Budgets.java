package org.yinyun.smtcraft.util;

/**
 * The shutdown numbers, in one place.
 *
 * <p>Two constants that described the same budget were picked independently and
 * contradicted each other within one round of edits: the media bridge promised
 * itself a 10.5 s wait, the client gave the whole sequence 8 s, and the bridge —
 * being the last step — got the leftovers (AUDIT-81). The next round showed the same
 * arithmetic failing one level down, with a literal {@code 2_000L} in the client
 * and a third of the budget left outside the sum (R9-1, R9-4).</p>
 *
 * <p>So the sequence is written as one sum here, in evaluation order: draining
 * the lanes, one bridge round, the writes that must still reach the disk,
 * stopping the lanes, and the lyric worker's two bounded waits. Anything that
 * is not in the sum will eventually be forgotten by it — the last literal
 * ({@code Async.shutdown}'s 3 s) was, and it came out of the final step
 * (AUDIT-10-1). Nothing in this file is a number that only one call site knows.</p>
 *
 * <p>Pure: no Minecraft, no I/O, so the offline self test can assert the sum.</p>
 */
public final class Budgets {
    private Budgets() {
    }

    /**
     * One bridge round, worst case. Linux: {@code ListNames} 2 s, probe 0.6 s,
     * {@code Position} and {@code Identity} 0.5 s each, cover connect 1.5 s and a
     * 5 s read deadline — about 10.2 s. Windows needs about 0.9 s.
     */
    public static final long BRIDGE_ROUND_MS = 10_500L;

    /** Shared with {@code MediaBridge#CLOSE_GRACE_MS}; see that field's javadoc. */
    public static final long CLOSE_GRACE_MS = BRIDGE_ROUND_MS;

    /** Draining the coalescing lane before the bridge starts waiting. */
    public static final long DRAIN_MS = 2_000L;

    /**
     * Kept back for the config write and the offset flush after the bridge. A
     * reserve, not a limit: {@code saveNow()} and {@code flushOffsets()} are not
     * bounded by it, so a genuinely stuck disk borrows the time from the steps
     * after them. Nothing to fix — a write that is still in progress cannot be
     * cancelled without losing it (R12-1 note).
     */
    public static final long WRITE_RESERVE_MS = 1_500L;

    /**
     * The drain half of {@link #ASYNC_SHUTDOWN_MS}. It was the last literal with
     * a shutdown meaning outside this file, and it disagreed with
     * {@link #DRAIN_MS} (2 000 vs 1 500) for what looks like the same thing —
     * two numbers for one idea is how the earlier rounds lost track of the sum
     * (AUDIT-13-2).
     */
    public static final long ASYNC_DRAIN_MS = 1_500L;

    /**
     * Stopping the two background lanes: the coalescing drain plus the I/O pool.
     * It was the last literal left in the client, and being outside the sum it
     * was silently taken from the step after it — the lyric worker got 250 ms
     * for its join in the worst case, which is exactly the case that comment
     * describes as needing a second (AUDIT-10-1).
     */
    public static final long ASYNC_SHUTDOWN_MS = 3_000L;

    /** {@code LyricService#close}: waiting for the provider call in flight. */
    public static final long LYRICS_JOIN_MS = 2_000L;

    /** {@code LyricService#close}: the probe pool after {@code shutdownNow()}. */
    public static final long LYRICS_DRAIN_MS = 1_500L;

    /**
     * {@code LyricService#close} waits twice: one worker join plus the probe pool
     * drain. It used to sit outside the budget entirely, which put the real worst
     * case at about 19 s against a 14.5 s budget (AUDIT-91). The two parts are named
     * because splitting the total in half handed the join 1.75 s instead of the
     * 2 s its own javadoc promises, on every normal exit (AUDIT-10-2).
     */
    public static final long LYRICS_CLOSE_MS = LYRICS_JOIN_MS + LYRICS_DRAIN_MS;

    /**
     * Every wait derived from this class goes through a floor of one millisecond:
     * {@code Thread.join(0)} waits <em>forever</em>, so a budget that ran out
     * would turn the fastest exit path into a hang (AUDIT-92).
     */
    public static final long MIN_WAIT_MS = 1L;

    /**
     * How much of {@link #DRAIN_MS} is still owed after a drain already ran for
     * {@code spentMs}. The early drain before the config write and the {@code DRAIN}
     * step share one budget; a boolean saying "the drain ran" made the slow path pay
     * twice, so the step asks what is left instead of whether it happened (AUDIT-19-1).
     * Never negative — a drain that overran owes nothing.
     */
    public static long remainingDrain(long spentMs) {
        return Math.max(0L, DRAIN_MS - Math.max(0L, spentMs));
    }

    /** Everything in the sequence that is not the bridge itself. */
    public static final long SHUTDOWN_MARGIN_MS =
            DRAIN_MS + WRITE_RESERVE_MS + ASYNC_SHUTDOWN_MS + LYRICS_CLOSE_MS;

    /** Whole shutdown sequence: bridge plus every other step, not a round number. */
    public static final long SHUTDOWN_MS = BRIDGE_ROUND_MS + SHUTDOWN_MARGIN_MS;

    /**
     * Applied on top of {@link #SHUTDOWN_MS} before the watchdog halts the
     * process. The watchdog has to outlive the sequence so that the normal path
     * wins the race and interrupts it; a second or two covers scheduling noise
     * without making an honest exit wait for it (AUDIT-13-1). Deliberately
     * <em>not</em> part of the sum: the sum says how long the steps may take, this
     * says how long after that the process stops being trusted.
     */
    public static final long WATCHDOG_GRACE_MS = 1_500L;

    /**
     * Ceiling for {@link #WATCHDOG_GRACE_MS}. The point of a grace is that an
     * honest exit does not notice it; a grace that drifts up to the whole budget
     * would add twenty seconds to every slow exit while still passing a
     * "greater than zero, smaller than the budget" assertion (AUDIT-14-3). The self
     * test checks the ceiling, so this is the side that can actually fail.
     */
    public static final long WATCHDOG_GRACE_MAX_MS = 3_000L;
}
