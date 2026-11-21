package org.yinyun.smtcraft.util;

/**
 * A deadline that does not depend on the code it is watching.
 *
 * <p>The shutdown sequence was given a watchdog in the twelfth round, but the
 * check ran in a {@code finally} block around the sequence: it could only fire
 * when the sequence <em>returned</em>, which is precisely the case that does not
 * need a watchdog. The one it was written for — a native call that never comes
 * back — never reaches a {@code finally} at all (R13-1).</p>
 *
 * <p>So the deadline lives on its own daemon thread: it fires whether the watched
 * sequence returns, throws or hangs, and the normal path interrupts it so an
 * honest exit is not delayed. Daemon on purpose — this thread must never be a
 * reason for the JVM to stay up. Pure enough to test: the action is a
 * parameter, so the offline self test can assert both "fires" and "cancelled"
 * without halting the test runner.</p>
 */
public final class Watchdog {
    private Watchdog() {
    }

    /**
     * Arms a daemon thread that runs {@code onFire} once {@code limitMs} has
     * passed. Interrupt the returned thread to cancel it.
     *
     * @param name    thread name, so a stack dump says what expired
     * @param limitMs how long to wait; clamped to at least one millisecond so a
     *                zero never turns into "fire immediately"
     * @param onFire  what to do on expiry — normally a message and a halt
     */
    public static Thread arm(String name, long limitMs, Runnable onFire) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(Math.max(Budgets.MIN_WAIT_MS, limitMs));
                onFire.run();
            } catch (InterruptedException cancelled) {
                // the watched sequence finished in time; nothing left to do
                Thread.currentThread().interrupt();
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
