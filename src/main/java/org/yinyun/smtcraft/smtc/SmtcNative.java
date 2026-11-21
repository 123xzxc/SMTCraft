package org.yinyun.smtcraft.smtc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Reads Windows SMTC (the media overlay every modern player feeds) straight from
 * the JVM through the Foreign Function &amp; Memory API.
 *
 * <p>No PowerShell, no helper process, no JNI, no JNA: only {@code combase.dll} /
 * {@code shcore.dll} exports and vtable dispatch on the interface pointers
 * Windows returns. Everything here must run on one thread, after
 * {@link WinRt#joinMta()} — that thread owns the apartment for its lifetime.</p>
 */
public final class SmtcNative {
    private static final String MANAGER_CLASS =
            "Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager";
    private static final WinRt.Guid IID_STATICS =
            WinRt.Guid.parse("2050C4EE-11A0-57DE-AED7-C97C70338245");
    private static final WinRt.Guid IID_STREAM =
            WinRt.Guid.parse("0000000C-0000-0000-C000-000000000046");

    /*
     * Slot numbers below are the WinRT vtable layout (IInspectable occupies 0..5):
     *   session            6 SourceAppUserModelId, 7 TryGetMediaPropertiesAsync,
     *                      8 GetTimelineProperties, 9 GetPlaybackInfo
     *   manager            6 GetCurrentSession, 7 GetSessions
     *   media properties   6 Title, 7 Subtitle, 8 AlbumArtist, 9 Artist,
     *                     10 AlbumTitle, 15 Thumbnail
     *   timeline           6 StartTime, 7 EndTime, 8 MinSeekTime, 9 MaxSeekTime,
     *                     10 Position (all TimeSpan = 100 ns ticks)
     *   playback info      6 Controls, 7 PlaybackStatus, 10 PlaybackRate, 11 IsShuffleActive
     *
     * The async operation objects are the one exception: Windows hands back an
     * IAsyncOperation<T> whose GetResults answers on slot 8 (measured on this
     * machine against the manager, the media properties and the thumbnail
     * stream, all three consistent). Never touch slot 13: this object's vtable
     * ends well before it and reading that pointer crashes the JVM outright.
     */
    private static final int ASYNC_GET_RESULTS_FAST = 8;

    /*
     * Every slot above is measured against the 26H1 WinRT metadata. If a future
     * Windows ships a binding whose vtable is shifted (an extra interface on
     * IInspectable, a reordered getter), -Dsmtc.vtable.pivot=N moves every call
     * by N without a rebuild; the PlaybackStatus range check below turns a bad
     * guess into a log line instead of a silently wrong HUD.
     */
    private static final int PIVOT = Integer.getInteger("smtc.vtable.pivot", 0);

    static int slot(int base) {
        return base + PIVOT;
    }

    private static final int MAX_ART_BYTES = 6 * 1024 * 1024;
    /** Longest one async call may block the sampling thread. */
    private static final long ASYNC_TIMEOUT_MS = 300L;
    /** Inside the watch window the poll slows down after this long. */
    private static final long ART_WATCH_FAST_MS = 3_000L;
    /** How long a new track keeps watching for a late cover before settling down. */
    private static final long ART_WATCH_MS = 12_000L;
    /**
     * Nothing is read for this long after a track change: Windows still answers with
     * the previous thumbnail right after the session flips, and a wrong cover shown
     * for a moment looks worse than no cover at all.
     */
    private static final long ART_GRACE_MS = 600L;
    /** Poll spacing inside the watch window: a cover that arrives late lands at once. */
    private static final long ART_WATCH_POLL_MS = 250L;
    /** Once settled, how often the (unchanged) thumbnail is re-read. */
    private static final long ART_IDLE_POLL_MS = 8_000L;

    /** One snapshot of the media overlay. */
    public record Sample(String app, String title, String subtitle, String artist, String album,
                         String status, boolean playing, long positionMs, long startMs, long endMs,
                         float rate, String artKey, byte[] art) {
        public static final Sample EMPTY =
                new Sample("", "", "", "", "", "unknown", false, 0L, 0L, 0L, 1.0f, "", null);
    }

    /*
     * No session-wide arena: temporaries live in the per-call arena opened by the
     * method that needs them, so nothing accumulates between polls. The COM
     * pointers themselves (manager/statics) are reference counted by Windows and
     * released through their vtable, not by the arena.
     */
    private MemorySegment manager = MemorySegment.NULL;
    private MemorySegment statics = MemorySegment.NULL;
    private String lastError = "";
    private String lastArtKey = "";
    /** A new track re-reads its cover for a moment: Windows can serve the old one first. */
    private boolean artSettled = true;
    private long artSettledAt;
    private long artNextPollAt;
    /** Consecutive reads that produced no image at all; drives the back-off (N14). */
    private int artMisses;
    private String lastArtHash = "";
    /** Hash of the cover that was on screen before the current track started. */
    private String previousArtHash = "";

    public String lastError() {
        return lastError;
    }

    public boolean isOpen() {
        return !WinRt.isNull(manager);
    }

    /** Activate the session manager. Call on the sampling thread. */
    public boolean open() {
        if (!WinRt.available()) {
            lastError = "combase.dll WinRT exports unavailable";
            return false;
        }
        try (Arena scratch = Arena.ofConfined()) {
            WinRt.joinMta();
            if (WinRt.isNull(statics)) {
                statics = WinRt.activationFactory(scratch, MANAGER_CLASS, IID_STATICS);
            }
            if (WinRt.isNull(statics)) {
                lastError = "RoGetActivationFactory failed for " + MANAGER_CLASS;
                return false;
            }
            MemorySegment out = scratch.allocate(ValueLayout.ADDRESS);
            out.set(ValueLayout.ADDRESS, 0L, MemorySegment.NULL);
            int hr = WinRt.call1(statics, 6, out);
            if (hr < 0) {
                lastError = "RequestAsync 0x" + Integer.toHexString(hr);
                return false;
            }
            MemorySegment op = out.get(ValueLayout.ADDRESS, 0L);
            MemorySegment result = await(op, scratch);
            WinRt.release(op);
            if (WinRt.isNull(result)) {
                lastError = "RequestAsync never completed";
                return false;
            }
            manager = result;
            lastError = "";
            return true;
        } catch (Throwable t) {
            lastError = SmtcBridge.describe(t);
            manager = MemorySegment.NULL;
            return false;
        }
    }

    public void close() {
        try {
            if (!WinRt.isNull(manager)) WinRt.release(manager);
            if (!WinRt.isNull(statics)) WinRt.release(statics);
        } catch (Throwable ignored) {
            // teardown is best effort
        }
        manager = MemorySegment.NULL;
        statics = MemorySegment.NULL;
        // Nothing else to tear down: every temporary already died with its own
        // arena, and the next open() simply activates the manager again.
    }

    /** Final teardown, called once when the game shuts down. */
    public void shutdown() {
        close();
    }

    /**
     * @param wantArt whether a cover should be downloaded for a new track key
     */
    public Sample sample(boolean wantArt) {
        if (WinRt.isNull(manager) && !open()) return Sample.EMPTY;
        // One confined arena per round: every out-pointer, string buffer and the
        // 64 KiB artwork buffer used to be carved out of the shared session arena
        // and never came back. A 4 Hz poll leaked tens of bytes per round for the
        // whole session; with artwork it leaked megabytes.
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment session = invokeOut(manager, 6, scratch);
            if (WinRt.isNull(session)) {
                lastError = "";
                return Sample.EMPTY;
            }
            String app = readString(session, 6, scratch);

            String title = "";
            String subtitle = "";
            String artist = "";
            String album = "";
            byte[] art = null;
            String artKeyOut = "";

            MemorySegment props = MemorySegment.NULL;
            try {
                MemorySegment op = invokeOut(session, 7, scratch);
                if (!WinRt.isNull(op)) {
                    props = await(op, scratch);
                    WinRt.release(op);
                }
                if (!WinRt.isNull(props)) {
                    title = readString(props, 6, scratch);
                    subtitle = readString(props, 7, scratch);
                    String albumArtist = readString(props, 8, scratch);
                    artist = readString(props, 9, scratch);
                    if (artist.isEmpty()) artist = albumArtist;
                    album = readString(props, 10, scratch);
                    String key = title + '\u0001' + artist + '\u0001' + album;
                    long artNow = System.currentTimeMillis();
                    if (!key.equals(lastArtKey)) {
                        // Windows keeps serving the previous thumbnail for a moment
                        // after the session flips, and players publish the new one a
                        // little later still. Reading once per key therefore froze the
                        // old cover on the new song; keep watching instead.
                        previousArtHash = lastArtHash;
                        lastArtKey = key;
                        artSettled = false;
                        artSettledAt = artNow;
                        artNextPollAt = 0L;
                        artMisses = 0;
                        lastArtHash = "";
                    }
                    boolean watching = artNow - artSettledAt < ART_WATCH_MS;
                    boolean grace = artNow - artSettledAt < ART_GRACE_MS;
                    if (wantArt && !grace && (watching || artNow >= artNextPollAt)) {
                        art = readArtwork(props, scratch);
                        if (art == null || art.length == 0) {
                            artMisses++;
                        } else if (art.length > 0) {
                            String hash = hashOf(art);
                            if (watching && hash.equals(previousArtHash)) {
                                // Windows is still handing out the thumbnail of the track
                                // we just left (players republish it a moment later): keep
                                // polling rather than freezing the old cover onto the new
                                // song. After the watch window the same image is accepted,
                                // because two tracks can share one album cover.
                                art = null;
                            } else if (!hash.equals(lastArtHash)) {
                                // a different image: this is the new track's cover, even
                                // when the app was slow to publish it
                                lastArtHash = hash;
                                artKeyOut = key;
                            } else {
                                art = null;
                            }
                        }
                        // in the watch window poll eagerly so a late cover lands at
                        // once; afterwards a slow poll still catches one that shows up
                        // minutes later (some players only load it when the window is
                        // focused) without streaming 64 KiB every frame
                        long poll = watching
                                ? (artNow - artSettledAt < ART_WATCH_FAST_MS
                                        ? ART_WATCH_POLL_MS : ART_WATCH_POLL_MS * 2L)
                                : ART_IDLE_POLL_MS;
                        // a track that simply has no cover would otherwise cost
                        // 30 streamed reads per song
                        if (artMisses >= 3) poll = Math.max(poll, ART_IDLE_POLL_MS);
                        artNextPollAt = artNow + poll;
                        if (!watching) artSettled = true;
                    }
                    if (!wantArt) {
                        lastArtKey = "";
                        artSettled = true;
                        lastArtHash = "";
                        previousArtHash = "";
                    }
                }
            } finally {
                WinRt.release(props);
            }

            MemorySegment info = invokeOut(session, 9, scratch);
            // PlaybackStatus is a 4-byte enum: reading it as int64 picked up
            // whatever the caller's out-buffer had above the value.
            int statusCode = !WinRt.isNull(info) ? readInt32(info, 7, scratch) : -1;
            if (statusCode < -1 || statusCode > 6) {
                if (lastError.isEmpty()) {
                    lastError = "unexpected PlaybackStatus " + statusCode + " (vtable pivot "
                            + PIVOT + "?)";
                }
                statusCode = -1;
            }
            boolean playing = statusCode == 4;
            float rate = 1.0f;
            if (!WinRt.isNull(info)) {
                rate = readDoubleRef(info, 10, scratch);
                WinRt.release(info);
            }

            MemorySegment timeline = invokeOut(session, 8, scratch);
            long start = 0L;
            long end = 0L;
            long position = 0L;
            if (!WinRt.isNull(timeline)) {
                start = readLong(timeline, 6, scratch) / 10_000L;
                end = readLong(timeline, 7, scratch) / 10_000L;
                position = readLong(timeline, 10, scratch) / 10_000L;
                WinRt.release(timeline);
            }
            WinRt.release(session);

            return new Sample(app, title, subtitle, artist, album, statusName(statusCode),
                    playing, position, start, end, rate, artKeyOut, art);
        } catch (Throwable t) {
            lastError = SmtcBridge.describe(t);
            return Sample.EMPTY;
        }
    }

    // --- git / COM helpers -------------------------------------------------

    private MemorySegment invokeOut(MemorySegment object, int slot, Arena scratch) {
        if (WinRt.isNull(object)) return MemorySegment.NULL;
        MemorySegment out = scratch.allocate(ValueLayout.ADDRESS);
        out.set(ValueLayout.ADDRESS, 0L, MemorySegment.NULL);
        int hr = WinRt.call1(object, SmtcNative.slot(slot), out);
        if (hr < 0) return MemorySegment.NULL;
        return out.get(ValueLayout.ADDRESS, 0L);
    }

    /**
     * Poll an IAsyncOperation until it hands over its result, for at most
     * {@link #ASYNC_TIMEOUT_MS}. A round can wait three times (properties,
     * thumbnail stream, artwork), so this also caps how long one sample blocks.
     */
    private MemorySegment await(MemorySegment op, Arena scratch) {
        if (WinRt.isNull(op)) return MemorySegment.NULL;
        long deadline = System.nanoTime() + ASYNC_TIMEOUT_MS * 1_000_000L;
        int attempt = 0;
        while (true) {
            MemorySegment result = tryResults(op, ASYNC_GET_RESULTS_FAST, scratch);
            if (!WinRt.isNull(result)) return result;
            if (System.nanoTime() >= deadline) return MemorySegment.NULL;
            try {
                Thread.sleep(attempt++ < 8 ? 1L : 2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return MemorySegment.NULL;
            }
        }
    }

    private MemorySegment tryResults(MemorySegment op, int slot, Arena scratch) {
        MemorySegment out = scratch.allocate(ValueLayout.ADDRESS);
        out.set(ValueLayout.ADDRESS, 0L, MemorySegment.NULL);
        int hr = WinRt.call1(op, slot, out);
        MemorySegment value = out.get(ValueLayout.ADDRESS, 0L);
        if (hr != 0) return MemorySegment.NULL;
        return value;
    }

    private String readString(MemorySegment object, int slot, Arena scratch) {
        MemorySegment out = scratch.allocate(ValueLayout.ADDRESS);
        out.set(ValueLayout.ADDRESS, 0L, MemorySegment.NULL);
        int hr = WinRt.call1(object, SmtcNative.slot(slot), out);
        if (hr < 0) return "";
        MemorySegment hstr = out.get(ValueLayout.ADDRESS, 0L);
        if (WinRt.isNull(hstr)) return "";
        return WinRt.readAndFree(hstr, scratch);
    }

    private long readLong(MemorySegment object, int slot, Arena scratch) {
        MemorySegment out = scratch.allocate(ValueLayout.JAVA_LONG);
        out.set(ValueLayout.JAVA_LONG, 0L, 0L);
        if (WinRt.call1(object, SmtcNative.slot(slot), out) < 0) return 0L;
        return out.get(ValueLayout.JAVA_LONG, 0L);
    }

    /** Read a 4-byte enum/int out-parameter (PlaybackStatus and friends). */
    private int readInt32(MemorySegment object, int slot, Arena scratch) {
        MemorySegment out = scratch.allocate(ValueLayout.JAVA_INT);
        out.set(ValueLayout.JAVA_INT, 0L, 0);
        if (WinRt.call1(object, SmtcNative.slot(slot), out) < 0) return -1;
        return out.get(ValueLayout.JAVA_INT, 0L);
    }

    /** IReference&lt;double&gt;::get_Value */
    private float readDoubleRef(MemorySegment object, int slot, Arena scratch) {
        MemorySegment out = scratch.allocate(ValueLayout.ADDRESS);
        out.set(ValueLayout.ADDRESS, 0L, MemorySegment.NULL);
        if (WinRt.call1(object, SmtcNative.slot(slot), out) < 0) return 1.0f;
        MemorySegment ref = out.get(ValueLayout.ADDRESS, 0L);
        if (WinRt.isNull(ref)) return 1.0f;
        MemorySegment value = scratch.allocate(ValueLayout.JAVA_DOUBLE);
        value.set(ValueLayout.JAVA_DOUBLE, 0L, 1.0d);
        int hr = WinRt.call1(ref, 6, value);
        WinRt.release(ref);
        if (hr < 0) return 1.0f;
        float f = (float) value.get(ValueLayout.JAVA_DOUBLE, 0L);
        return f <= 0.0f ? 1.0f : f;
    }

    private byte[] readArtwork(MemorySegment props, Arena scratch) {
        MemorySegment ref = invokeOut(props, 15, scratch);
        if (WinRt.isNull(ref)) return null;
        MemorySegment stream = MemorySegment.NULL;
        try {
            MemorySegment op = invokeOut(ref, 6, scratch);
            if (WinRt.isNull(op)) return null;
            MemorySegment ras = await(op, scratch);
            WinRt.release(op);
            if (WinRt.isNull(ras)) return null;
            try {
                stream = WinRt.streamOverRandomAccess(ras, IID_STREAM, scratch);
            } finally {
                WinRt.release(ras);
            }
            if (WinRt.isNull(stream)) return null;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(64 * 1024);
            MemorySegment buffer = scratch.allocate(64 * 1024);
            MemorySegment read = scratch.allocate(ValueLayout.JAVA_INT);
            while (bytes.size() < MAX_ART_BYTES) {
                read.set(ValueLayout.JAVA_INT, 0L, 0);
                int hr = WinRt.streamRead(stream, buffer, 64 * 1024, read);
                int n = read.get(ValueLayout.JAVA_INT, 0L);
                if (hr < 0 || n <= 0) break;
                byte[] chunk = new byte[n];
                MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0L, chunk, 0, n);
                bytes.write(chunk);
            }
            byte[] data = bytes.toByteArray();
            // A player may hand over a zeroed or non-image buffer; the HUD can
            // only decode real images, so reject anything that is not one here
            // instead of showing a magenta square.
            return looksLikeImage(data) ? data : null;
        } catch (Throwable t) {
            lastError = SmtcBridge.describe(t);
            return null;
        } finally {
            WinRt.release(ref);
            WinRt.release(stream);
        }
    }

    /** PNG/JPEG/GIF/BMP/WEBP magic check. */
    private static boolean looksLikeImage(byte[] data) {
        if (data == null || data.length < 4) return false;
        int b0 = data[0] & 0xFF;
        int b1 = data[1] & 0xFF;
        int b2 = data[2] & 0xFF;
        int b3 = data[3] & 0xFF;
        boolean png = b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47;
        boolean jpeg = b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF;
        boolean gif = b0 == 0x47 && b1 == 0x49 && b2 == 0x46;
        boolean bmp = b0 == 0x42 && b1 == 0x4D;
        boolean webp = data.length >= 12 && b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46
                && data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50;
        return png || jpeg || gif || bmp || webp;
    }

    /** Stable short hash so the texture cache can key on it. */
    public static String hashOf(byte[] data) {
        if (data == null || data.length == 0) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            return HexFormat.of().formatHex(java.util.Arrays.copyOf(digest, 8));
        } catch (Throwable t) {
            return Integer.toHexString(java.util.Arrays.hashCode(data));
        }
    }

    private static String statusName(int code) {
        return switch (code) {
            case 0 -> "closed";
            case 1 -> "opened";
            case 2 -> "changing";
            case 3 -> "stopped";
            case 4 -> "playing";
            case 5 -> "paused";
            default -> "unknown";
        };
    }
}
