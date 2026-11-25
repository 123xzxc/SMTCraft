package org.yinyun.smtcraft.smtc;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.yinyun.smtcraft.util.Async;

/**
 * Per-song lyric offset in milliseconds, persisted next to the lyric cache.
 *
 * <p>A timing error belongs to the sheet, not to the player: one uploader stamps
 * the whole song 400 ms early, the next one is exact. A single global offset can
 * never be right for a whole library, so every nudge is stored against the song
 * key. {@code 清除缓存} removes the file together with the sheets it belongs to.</p>
 *
 * <p>Scrolling the wheel fires a nudge per notch, and the old code rewrote the
 * whole table each time; writes are therefore coalesced (a dirty flag plus a
 * one-second flusher) and the table is bounded, least recently nudged entry first.</p>
 */
public final class SongOffsets {
    private static final Gson GSON = new Gson();
    private static final TypeToken<Map<String, Long>> TYPE = new TypeToken<Map<String, Long>>() { };
    private static final long LIMIT = 60000L;
    /** Enough for a whole library's worth of hand-tuned songs, small enough to stay cheap. */
    private static final int MAX_ENTRIES = 512;
    /** Writes are coalesced: a scroll gesture is one write, not one per notch. */
    private static final long MIN_WRITE_GAP_MS = 1000L;
    private static final int FORCE_AFTER = 16;

    private static final ScheduledExecutorService FLUSHER =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "smtcraft-offsets");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private final Path file;
    /**
     * Read every frame by the HUD, written only when the user nudges a song: a
     * concurrent map keeps the read path free of the monitor that the flusher and
     * the wheel handler need.
     */
    private final Map<String, Long> values = new ConcurrentHashMap<String, Long>();
    /**
     * Last nudge per key, in epoch milliseconds. {@code ConcurrentHashMap} does not
     * iterate in insertion order, so "the oldest goes" has to be recorded rather than
     * inferred from the iterator; entries restored from the file start at 0 and are
     * therefore evicted before anything touched in this session.
     */
    private final Map<String, Long> lastUsed = new ConcurrentHashMap<String, Long>();
    private boolean dirty;
    private int pending;
    private long lastWriteAt;

    public SongOffsets(Path cacheDir) {
        this.file = cacheDir.resolve("offsets.json");
        load();
        FLUSHER.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                flush();
            }
        }, 1L, 1L, TimeUnit.SECONDS);
    }

    private void load() {
        try {
            if (!Files.isRegularFile(file)) return;
            // Java 8: Files.readString does not exist, so decode the bytes by hand
            byte[] bytes = Files.readAllBytes(file);
            Map<String, Long> read =
                    GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), TYPE.getType());
            if (read != null) {
                values.putAll(read);
                for (String key : read.keySet()) lastUsed.put(key, 0L);
            }
        } catch (Exception ignored) {
            // an unreadable file just means "no adjustment has been made yet"
        }
    }

    public long get(String key) {
        if (key == null) return 0L;
        Long value = values.get(key);
        return value == null ? 0L : value;
    }

    /** Adds {@code delta} milliseconds to the song's offset and returns the result. */
    public synchronized long adjust(String key, long delta) {
        if (key == null) return 0L;
        return store(key, clamp(get(key) + delta));
    }

    /** Sets the song's offset outright; zero forgets the entry. */
    public synchronized long set(String key, long value) {
        if (key == null) return 0L;
        return store(key, clamp(value));
    }

    private long store(final String key, long value) {
        if (value == 0L) {
            values.remove(key);
            lastUsed.remove(key);
        } else {
            values.put(key, value);
            lastUsed.put(key, System.currentTimeMillis());
        }
        prune();
        dirty = true;
        pending++;
        long now = System.currentTimeMillis();
        if (pending >= FORCE_AFTER || now - lastWriteAt >= MIN_WRITE_GAP_MS) {
            lastWriteAt = now;
            // the write itself is a file access: keep it off the client thread
            Async.run("offset table", new Runnable() {
                @Override
                public void run() {
                    flush();
                }
            });
        }
        return value;
    }

    /**
     * Keeps the table bounded by dropping the least recently nudged entry, one at a
     * time. Called only from {@link #store}, which both callers reach under the
     * monitor, so the scan sees a table no other writer is mutating.
     */
    private void prune() {
        while (values.size() > MAX_ENTRIES) {
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            for (String key : values.keySet()) {
                Long stamp = lastUsed.get(key);
                long at = stamp == null ? 0L : stamp;
                if (oldest == null || at < oldestAt) {
                    oldest = key;
                    oldestAt = at;
                }
            }
            if (oldest == null) return;
            values.remove(oldest);
            lastUsed.remove(oldest);
        }
    }

    /** Write the table if anything changed since the last write (background lane). */
    public synchronized void flush() {
        if (!dirty) return;
        dirty = false;
        pending = 0;
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) return;
        // snapshot under the monitor: the JSON writer must not walk a map that a
        // later nudge is still touching
        Map<String, Long> snapshot = new HashMap<String, Long>(values);
        Path temp = parent.resolve(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            // Java 8: no Files.writeString; write the UTF-8 bytes directly
            byte[] json = GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
            Files.write(temp, json);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // a read-only config directory only costs persistence, not correctness
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                // nothing sensible to do
            }
        }
    }

    private static long clamp(long value) {
        return Math.max(-LIMIT, Math.min(LIMIT, value));
    }

    /** Forget every adjustment, including the file on disk. */
    public synchronized void clear() {
        values.clear();
        lastUsed.clear();
        dirty = false;
        pending = 0;
        Async.run("offset clear", new Runnable() {
            @Override
            public void run() {
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    // nothing sensible to do
                }
            }
        });
    }
}
