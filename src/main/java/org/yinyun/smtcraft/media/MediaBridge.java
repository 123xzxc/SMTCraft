package org.yinyun.smtcraft.media;

import org.yinyun.smtcraft.SmtcConfig;
import org.yinyun.smtcraft.util.Budgets;
import org.yinyun.smtcraft.smtc.TrackInfo;

/**
 * One platform's "what is playing" reader.
 *
 * <p>Windows talks to the System Media Transport Controls through WinRT, Linux
 * reads the MPRIS2 interface off the session bus. Both hand the HUD the same
 * snapshot, so the renderer, the lyric service and the settings page never have
 * to know which platform they run on.</p>
 */
public interface MediaBridge extends AutoCloseable {

    /**
     * How long {@code close()} waits for the sampler thread.
     *
     * <p>A round is not one probe budget plus two reads. On MPRIS it is five
     * blocking stages — {@code ListNames} (2 s), the probe budget (0.6 s),
     * {@code Position} and {@code Identity} (0.5 s each) — plus a cover download
     * (1.5 s connect plus a 5 s read deadline): about 10.2 s worst case, so 3 s
     * covered 3.4× less than one round and an exit could leave a live sampler
     * behind (R7-1). Windows needs about 0.9 s, so the ceiling only matters there
     * in a pathological round.</p>
     *
     * <p>The wait is no longer about a shared native arena: each bridge closes
     * its own bus and arena inside the worker's {@code finally} block. It is
     * about the thread actually finishing, so nothing is still downloading a
     * cover or writing {@code latest} into a process that believes it exited.</p>
     */
    long CLOSE_GRACE_MS = Budgets.CLOSE_GRACE_MS;

    /** Start the sampler thread. Idempotent. */
    void start();

    /** Hand over the live config object (called once per client tick). */
    void setConfig(SmtcConfig config);

    /** Ask the sampler to read again right away instead of after its interval. */
    void reconfigure();

    TrackInfo latest();

    byte[] artwork();

    String artworkHash();

    boolean running();

    String lastError();

    /** Rounds the sampler has completed (any result), for the settings page. */
    long sampleCount();

    /** Milliseconds since the last round, or {@code -1} when none happened yet. */
    long millisSinceSample();

    @Override
    void close();

    /**
     * Closes, waiting at most {@code budgetMs} for the sampler thread.
     *
     * <p>The shutdown sequence has one budget for all of its steps, so a single
     * bridge may not spend the whole {@link #CLOSE_GRACE_MS} and starve the ones
     * after it (R7-6).</p>
     */
    void close(long budgetMs);
}
