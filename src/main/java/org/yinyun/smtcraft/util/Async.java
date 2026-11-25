package org.yinyun.smtcraft.util;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * The mod's background threads. Everything that touches a disk, a network socket
 * or a PNG decoder has to run here instead of on the client thread: a single
 * 64 KiB cover decode or an antivirus scan of a config write is enough to drop
 * frames, and the client thread also drives rendering, input and the tick loop.
 *
 * <p>Two lanes:</p>
 * <ul>
 *   <li>{@link #run} — ordinary one-shot work (pack writes, cache deletes,
 *       file flushes) on a small daemon pool;</li>
 *   <li>{@link #latest} — coalescing lane for state snapshots (the config): only
 *       the newest task survives, so dragging a slider cannot queue fifty writes.</li>
 * </ul>
 *
 * <p>{@link #onClient} hands a result back to the client thread. The mod installs
 * that executor during client start-up, so this class stays free of Minecraft
 * types and is usable from offline tests. Everything here is production code
 * except {@link #inlineClientForTests()}, the one member meant for tests.</p>
 */
public final class Async {
    // 日志统一走 java.util.logging：不新增依赖（见 AGENTS.md），也不必和 Forge 的 log4j 配置耦合；
    // 日志只是诊断，级别语义对齐即可
    private static final Logger LOG = Logger.getLogger("smtcraft-async");
    private static final int CORE = 2;
    private static final int MAX = 4;
    private static final int QUEUE = 128;

    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(
            CORE, MAX, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(QUEUE),
            Async::daemon,
            new ThreadPoolExecutor.AbortPolicy());

    private static final AtomicReference<Runnable> LATEST = new AtomicReference<Runnable>();
    /** True while the coalescing lane is running a task it already took off the queue. */
    private static volatile boolean executing;
    private static final Object SLOT = new Object();
    private static final Thread COALESCER;
    /**
     * Results produced before the client executor exists are parked here instead of
     * being run on the producing thread: touching {@code Minecraft} from an I/O
     * thread throws somewhere unrelated and randomly (AUDIT-32).
     */
    private static final java.util.Queue<Runnable> PARKED = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
    private static final int MAX_PARKED = 64;
    /** {@code null} until the client installs its executor. */
    private static volatile Executor client;

    static {
        COALESCER = new Thread(Async::coalesce, "smtcraft-latest");
        COALESCER.setDaemon(true);
        COALESCER.start();
    }

    private Async() {
    }

    /**
     * Installed by the client so background results can be applied on the client
     * thread. Passing {@code null} detaches it again: results produced from then on
     * are parked instead of quietly running on whatever thread produced them (AUDIT-32).
     */
    public static synchronized void setClientExecutor(Executor executor) {
        client = executor;
        if (executor == null) return;
        drainParked(executor);
    }

    private static void drainParked(Executor executor) {
        Runnable parked;
        while ((parked = PARKED.poll()) != null) deliver(executor, parked);
    }

    /** Scope handle whose {@link Scope#close()} declares no checked exception. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Offline tests only: run {@link #onClient} work immediately, for the duration of
     * one scope. Closing the scope puts the previous executor back — {@code null} in a
     * test process, which is the rule the process started with, not a pretend client
     * thread (R21-2: the earlier one-way version was a process-wide commitment and the
     * contract in its javadoc pointed at the wrong test).
     *
     * <p>Entering the scope is not a no-op: it installs an executor, and {@link
     * #setClientExecutor} drains whatever was already parked as a side effect. A test
     * that wants to observe parking therefore has to start from an empty queue, or a
     * result an earlier test left behind runs the moment the scope opens and the
     * failure points at the wrong assertion (AUDIT-22-2).</p>
     */
    public static Scope inlineClientForTests() {
        final Executor previous = client;
        setClientExecutor(Runnable::run);
        return () -> setClientExecutor(previous);   // null here means "park again", the original rule
    }

    /**
     * Runs {@code task} on the client thread. A result that cannot be delivered is
     * dropped with a warning: dropping one HUD refresh is always better than
     * applying it from the wrong thread.
     */
    public static void onClient(Runnable task) {
        if (task == null) return;
        Executor executor = client;
        if (executor == null) {
            if (PARKED.size() >= MAX_PARKED) {
                LOG.warning("client executor missing, dropped a background result");
                return;
            }
            PARKED.add(task);
            // setClientExecutor() may have drained the queue between the read above
            // and this add; in that case nobody else will collect the task (AUDIT-44)
            Executor arrived = client;
            if (arrived != null) drainParked(arrived);
            return;
        }
        deliver(executor, task);
    }

    private static void deliver(Executor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (Throwable unavailable) {
            LOG.warning("dropped a client-thread result: " + Errors.describe(unavailable));
        }
    }

    /**
     * Fire-and-forget background work; a failure is logged, never thrown at the caller.
     *
     * @return whether the task was accepted. Callers that raise a latch before
     *         submitting have to reset it when this returns {@code false}, otherwise a
     *         full queue silently disables their feature for the rest of the session (AUDIT-41).
     */
    public static boolean run(final String what, final Runnable task) {
        if (task == null) return false;
        try {
            IO.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        task.run();
                    } catch (Throwable failed) {
                        LOG.warning(what + " failed: " + Errors.describe(failed));
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException busy) {
            LOG.fine(what + " skipped: background queue is full");
            return false;
        }
    }

    /**
     * Coalescing lane: replaces whatever was still waiting. Used for the config,
     * where only the newest snapshot matters and every write is a full file.
     */
    public static void latest(String what, Runnable task) {
        if (task == null) return;
        LATEST.set(task);
        synchronized (SLOT) {
            SLOT.notifyAll();
        }
    }

    /**
     * Waits until the coalescing lane has nothing queued <em>and</em> is not in the
     * middle of a task. The queue is emptied before the task runs, so checking the
     * queue alone returned while a write was still in progress (AUDIT-47).
     */
    public static boolean awaitQuiet(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (LATEST.get() == null && !executing) return true;
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return LATEST.get() == null && !executing;
    }

    /**
     * Stops both lanes within {@code budgetMs}. The caller owns the outer shutdown
     * budget, so a hard-coded 3 s here could outlive it (AUDIT-81). The drain inside it
     * is {@link Budgets#ASYNC_DRAIN_MS} taken off the top rather than half the
     * budget (AUDIT-13-2).
     */
    public static void shutdown(long budgetMs) {
        // The drain is taken off the top, not split in half: that quiet wait is
        // the only thing that lets a queued config snapshot reach the disk, while
        // the I/O pool termination is already backed by shutdownNow(). Halving the
        // budget (the shape R10-2 removed from the lyric worker) handed the drain
        // 1.5 s instead of the 3 s it can use, and the snapshot it was waiting for
        // could be dropped by the very next line (AUDIT-13-2).
        long drain = Math.min(Budgets.ASYNC_DRAIN_MS, Math.max(0L, budgetMs));
        long wait = Math.max(0L, budgetMs - drain);
        awaitQuiet(drain);
        IO.shutdown();
        try {
            if (!IO.awaitTermination(wait, TimeUnit.MILLISECONDS)) IO.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            IO.shutdownNow();
        }
    }

    private static Thread daemon(Runnable runnable) {
        Thread thread = new Thread(runnable, "smtcraft-io");
        thread.setDaemon(true);
        return thread;
    }

    private static void coalesce() {
        while (true) {
            Runnable task;
            synchronized (SLOT) {
                task = LATEST.getAndSet(null);
                if (task == null) {
                    try {
                        SLOT.wait();
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                    continue;
                }
            }
            executing = true;
            try {
                task.run();
            } catch (Throwable failed) {
                LOG.warning("background snapshot failed: " + Errors.describe(failed));
            } finally {
                executing = false;
            }
        }
    }
}
