package org.yinyun.smtcraft;

import org.yinyun.smtcraft.smtc.Crypto;
import org.yinyun.smtcraft.smtc.LrcParser;
import org.yinyun.smtcraft.smtc.LyricLine;
import org.yinyun.smtcraft.smtc.LyricTrack;
import org.yinyun.smtcraft.smtc.ProgressSmoother;
import org.yinyun.smtcraft.smtc.SamplerGate;
import org.yinyun.smtcraft.smtc.SongMatch;
import org.yinyun.smtcraft.smtc.SyllableParsers;
import org.yinyun.smtcraft.smtc.TrackInfo;

import org.yinyun.smtcraft.mpris.DbusSymbols;
import org.yinyun.smtcraft.render.LyricClock;
import org.yinyun.smtcraft.render.LyricScroll;
import org.yinyun.smtcraft.mpris.MprisArt;
import org.yinyun.smtcraft.mpris.MprisTrack;
import org.yinyun.smtcraft.mpris.Wire;

import org.yinyun.smtcraft.util.Async;
import org.yinyun.smtcraft.util.Budgets;
import org.yinyun.smtcraft.util.HudLayout;
import org.yinyun.smtcraft.util.UiScale;
import org.yinyun.smtcraft.util.UpdateCheck;
import org.yinyun.smtcraft.util.Watchdog;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;

/**
 * Offline regression tests for the pure parts of the mod: lyric parsing, song
 * matching and the QRC decoder. No Minecraft classes and no network, so this runs
 * anywhere a JDK is available (see test.sh).
 */
public final class SelfTest {
    /**
     * The largest change one frame makes, over {@code seconds} of clock at 0.05 s steps.
     *
     * <p>The scrolling functions are continuous by construction: they hold, then travel, then
     * hold again, and the return leg passes through the same positions on the way back. A step
     * larger than the travel of one frame means the clock itself was cut (R51-1).</p>
     */
    private static float worstStep(double seconds, boolean cycling) {
        float worst = 0.0f;
        float previous = cycling
                ? HudLayout.scrollCycle(200.0f, 0.0d, HudLayout.MARQUEE_SPEED,
                        HudLayout.MARQUEE_PAUSE)
                : HudLayout.verticalScroll(0.0d, 300, 100);
        for (double t = 0.05d; t < seconds; t += 0.05d) {
            float now = cycling
                    ? HudLayout.scrollCycle(200.0f, t, HudLayout.MARQUEE_SPEED,
                            HudLayout.MARQUEE_PAUSE)
                    : HudLayout.verticalScroll(t, 300, 100);
            worst = Math.max(worst, Math.abs(now - previous));
            previous = now;
        }
        return worst;
    }

    /** Public upstream constant, kept here so the test does not need the private field. */
    private static final byte[] QQ_KEY =
            "!@#)(*$%123ZXC!@!@#)(NHL".getBytes(StandardCharsets.US_ASCII);

    private static int checks;
    private static int failures;

    /** Runs every check, newest last. */
    public static void main(String[] args) throws Exception {
        yrcKeepsWordTiming();
        yrcFallsBackToPlainLrc();
        multiStampLineShiftsWords();
        creditLinesAreDropped();
        translationTolerance();
        krcTranslationsAlign();
        qrcLinesSurviveWithoutWords();
        qrcDecoderRoundTrip();
        songMatchAcceptsAndRejects();
        songMatchSeparatesEditions();
        editionGateHandlesLazyCatalogues();
        backgroundLanesUseTheirOwnThreads();
        configChangeWakesSamplerOnlyWhenRead();
        mprisMetadataParsing();
        mprisArtworkPaths();
        wireDecodesMetadata();
        wireEntryWithoutStringKey();
        percentDecodeKeepsUtf8();
        dbusSymbolTableMatchesPrototypes();
        shutdownBudgetsAgree();
        watchdogFiresAndCanBeCancelled();
        errorsDescribeWithoutMessages();
        errorsWrapWithoutPrintingNull();
        shutdownPlanKeepsItsOrder();
        savesBumpTheReloadGeneration();
        configProblemsAreClassified();
        configClampsMatchTheSliders();
        lyricPositionNeverRewinds();
        smootherHoldsThroughAStall();
        smootherIgnoresAStallWhenLearning();
        smootherLearnsASlowPublisher();
        smootherRejectsAMismatchedStep();
        followOffsetPinsTheSungEdge();
        uiScaleFollowsTheGuiScale();
        hudLayoutArrangesTheTwoBlocks();
        releaseCheckReadsTagsAndOrdersThem();
        updateCheckFallsBackPastTheApi();

        System.out.println();
        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    /**
     * The bridge is handed the config object every tick. Only values the sampler
     * actually reads may cut its interval short, otherwise the 150 ms poll turns
     * into a 20 Hz one and the WinRT churn is audible in the game audio.
     */
    private static void configChangeWakesSamplerOnlyWhenRead() {
        SmtcConfig base = new SmtcConfig();
        SmtcConfig same = new SmtcConfig();
        check("identical config keeps the cadence",
                !SamplerGate.readsDifferently(base, same));
        SmtcConfig faster = new SmtcConfig();
        faster.pollIntervalMs = base.pollIntervalMs + 50;
        check("poll rate change wakes the sampler", SamplerGate.readsDifferently(base, faster));
        SmtcConfig art = new SmtcConfig();
        art.showArtwork = !base.showArtwork;
        check("artwork switch wakes the sampler", SamplerGate.readsDifferently(base, art));
        SmtcConfig cosmetic = new SmtcConfig();
        cosmetic.cornerRadius = base.cornerRadius + 2;
        cosmetic.offsetX = base.offsetX + 10;
        cosmetic.lyricFontSize = 2.0f;
        check("cosmetic change leaves the cadence alone",
                !SamplerGate.readsDifferently(base, cosmetic));
    }

    private static void yrcKeepsWordTiming() {
        String yrc = "[1000,2000](1000,500,0)\u3042(1500,500,0)\u306a";
        LyricTrack track = LrcParser.parseRich(yrc, null, null, "test", "test");
        LyricLine line = track.line(0);
        check("yrc line exists", line != null);
        if (line == null) return;
        check("yrc words counted", line.words().size() == 2);
        check("yrc word start", line.words().get(0).startMs() == 1000L);
        check("yrc line end", line.endMs() == 3000L);
    }

    private static void yrcFallsBackToPlainLrc() {
        LyricTrack track = LrcParser.parseRich("not a yrc sheet", "[00:01.00]hello", null, "t", "t");
        check("lrc fallback line count", track.size() == 1);
        check("lrc fallback text", track.size() == 1 && "hello".equals(track.line(0).text()));
    }

    private static void multiStampLineShiftsWords() {
        String yrc = "[1000,1000](1000,500,0)a\n[5000,1000](5000,500,0)a";
        LyricTrack track = LrcParser.parseRich(yrc, null, null, "t", "t");
        check("two yrc rows", track.size() == 2);
        check("second row keeps its own word time",
                track.size() == 2 && track.line(1).words().get(0).startMs() == 5000L);
    }

    private static void creditLinesAreDropped() {
        check("credit line recognised", LrcParser.isCredit("song - artist", 100L));
        check("vocal line kept", !LrcParser.isCredit("\u3053\u3093\u306b\u3061\u306f", 4000L));
        check("late line is never a credit", !LrcParser.isCredit("credits: someone", 60000L));
    }

    private static void translationTolerance() {
        LyricTrack track = LrcParser.parseRich(null,
                "[00:01.00]one\n[00:05.00]two",
                "[00:01.05]uno\n[00:05.00]dos", "t", "t");
        check("translation within 400 ms is used",
                track.size() == 2 && "uno".equals(track.line(0).translation()));
    }

    private static void krcTranslationsAlign() {
        String krc = "[1000,800]<1000,400,0>one\n"
                + "[2000,800]<2000,400,0>two\n"
                + "[3000,0]\n"
                + "[4000,800]<4000,400,0>three\n";
        LyricTrack track = SyllableParsers.parseKrc(krc, null, "kugou", "test");
        check("krc rows parsed", track.size() >= 3);
        check("krc keeps words", track.size() > 0 && track.line(0).hasWords());
    }

    private static void qrcLinesSurviveWithoutWords() {
        String qrc = "[1000,2000]plain line\n[3000,1000]another";
        LyricTrack track = SyllableParsers.parseQrc(qrc, null, "qq", "test");
        check("qrc keeps line-level rows", track.size() == 2);
        check("qrc text preserved", track.size() == 2 && "plain line".equals(track.line(0).text()));
    }

    /** Encrypt a lyric sheet the way QQ does, then let the decoder read it back. */
    private static void qrcDecoderRoundTrip() throws Exception {
        String payload = "{\"code\":0,\"lyric\":\"[1000,2000]hello\"}";
        byte[] deflated = pad(deflate(payload.getBytes(StandardCharsets.UTF_8)));
        Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(QQ_KEY, "DESede"));
        byte[] encrypted = cipher.doFinal(deflated);
        String hex = hex(encrypted);
        check("looksEncrypted on a hex blob", Crypto.looksEncrypted(hex));
        check("qrc decoder round trip", payload.equals(Crypto.qrc(hex)));
        check("plain lyrics are not mistaken for a blob", !Crypto.looksEncrypted("[00:01.00]hello"));
    }

    private static void songMatchAcceptsAndRejects() {
        TrackInfo same = new TrackInfo(true, true, "\u5b64\u52c7\u8005", "\u9648\u5955\u8fc5",
                "", "test", "playing", 0L, 0L, 0L, 1.0f, 0L);
        int accept = SongMatch.score("\u5b64\u52c7\u8005", "\u9648\u5955\u8fc5", 257000L, same);
        check("same song accepted", accept >= SongMatch.ACCEPT);

        TrackInfo other = new TrackInfo(true, true, "Hello", "Adele", "", "test", "playing",
                0L, 0L, 0L, 1.0f, 0L);
        int reject = SongMatch.score("Rise", "Katy Perry", 200000L, other);
        check("different song rejected", reject < SongMatch.ACCEPT);
    }


    /**
     * "Euphoria (feat. Tsugumi Nagahara) [US40 Remix]" once took Kugou's plain
     * "Euphoria": {@link SongMatch#bare} cut the brackets and {@link SongMatch#clean}
     * deleted every edition word, so the two titles compared as the same string and
     * the sheet of another cut was used. Same title, different recording - the
     * timing is wrong everywhere and the words may not even match.
     */
    private static void songMatchSeparatesEditions() {
        TrackInfo remix = new TrackInfo(true, true, "Euphoria (feat. Tsugumi Nagahara) [US40 Remix]",
                "Cloudy.exe", "", "test", "playing", 0L, 0L, 0L, 1.0f, 0L);
        check("the plain cut is not the remix",
                SongMatch.score("Euphoria", "Cloudy.exe", 200000L, remix) < SongMatch.ACCEPT);
        check("the same remix is accepted",
                SongMatch.score("Euphoria (US40 Remix)", "Cloudy.exe", 200000L, remix)
                        >= SongMatch.ACCEPT);
        check("another remix is not the same remix",
                SongMatch.score("Euphoria (Cloudy.exe Remix)", "Cloudy.exe", 200000L, remix)
                        < SongMatch.ACCEPT);

        TrackInfo plain = new TrackInfo(true, true, "Euphoria", "Cloudy.exe", "", "test", "playing",
                0L, 0L, 0L, 1.0f, 0L);
        check("the plain cut is accepted for itself",
                SongMatch.score("Euphoria", "Cloudy.exe", 200000L, plain) >= SongMatch.ACCEPT);
        check("the remix is not the plain cut",
                SongMatch.score("Euphoria (US40 Remix)", "Cloudy.exe", 200000L, plain)
                        < SongMatch.ACCEPT);
        check("a live take is another recording",
                SongMatch.score("Euphoria - Live", "Cloudy.exe", 200000L, plain) < SongMatch.ACCEPT);
        check("an instrumental is another recording",
                SongMatch.score("Euphoria 伴奏", "Cloudy.exe", 200000L, plain) < SongMatch.ACCEPT);
        check("one edition mark on the playing track",
                SongMatch.editionMap(remix.title()).keySet().equals(java.util.Set.of("remix")));
        check("a guest singer moves the score, not the verdict",
                SongMatch.score("Euphoria (feat. Someone Else) [US40 Remix]", "Cloudy.exe",
                        200000L, remix)
                        < SongMatch.score("Euphoria (feat. Tsugumi Nagahara) [US40 Remix]",
                        "Cloudy.exe", 200000L, remix));
    }


    /**
     * The edition gate only earns its keep on a lazy catalogue: "Euphoria (Remix)" names
     * the kind but not the cut, and that spelling is the common one. It used to be a
     * wildcard (88 points, ACCEPT is 62); now it is another recording. Also pins the
     * query order (the untouched title has to be asked first) and the dotted I that used
     * to eat a mark through a lowercased index (R33-1, R33-2, R33-4).
     */
    private static void editionGateHandlesLazyCatalogues() {
        TrackInfo remix = new TrackInfo(true, true, "Euphoria (feat. Tsugumi Nagahara) [US40 Remix]",
                "Cloudy.exe", "", "test", "playing", 0L, 0L, 0L, 1.0f, 0L);
        check("a nameless remix is not the named remix",
                SongMatch.score("Euphoria (Remix)", "Cloudy.exe", 200000L, remix)
                        < SongMatch.ACCEPT);

        TrackInfo nameless = new TrackInfo(true, true, "Euphoria (Remix)", "Cloudy.exe", "", "test",
                "playing", 0L, 0L, 0L, 1.0f, 0L);
        check("two nameless remixes cannot be told apart",
                SongMatch.score("Euphoria (Remix)", "Cloudy.exe", 200000L, nameless)
                        >= SongMatch.ACCEPT);

        // the other direction: the playing title is the one without a name, and the
        // catalogue offers a named cut. Without the mismatch line this scores 88.
        check("a nameless playing title does not claim a named cut",
                SongMatch.score("Euphoria (US40 Remix)", "Cloudy.exe", 200000L, nameless)
                        < SongMatch.ACCEPT);

        check("a dotted I keeps its edition mark",
                SongMatch.sameEdition("\u0130stanbul - Live", "Istanbul (Live)"));
        check("a dotted I keeps the kind it carries",
                SongMatch.editionMap("\u0130stanbul - Live").containsKey("live"));

        List<String> asked = SongMatch.queries(new TrackInfo(true, true,
                "Euphoria (feat. Tsugumi Nagahara) [US40 Remix]", "Cloudy.exe", "Some Album",
                "test", "playing", 0L, 0L, 0L, 1.0f, 0L));
        check("the untouched title is asked first",
                !asked.isEmpty()
                        && asked.get(0).equals("Euphoria (feat. Tsugumi Nagahara) [US40 Remix]"));
        // full title, title + artist, title + album, title alone - the dash form is
        // skipped because this title has no " - " in it
        check("the edition-less forms still follow", asked.size() >= 4);
    }

    /** DESede/ECB/NoPadding needs a multiple of eight bytes; QQ pads with zeros too. */
    private static byte[] pad(byte[] data) {
        int missing = (8 - data.length % 8) % 8;
        if (missing == 0) return data;
        byte[] padded = new byte[data.length + missing];
        System.arraycopy(data, 0, padded, 0, data.length);
        return padded;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            int n = deflater.deflate(buffer);
            out.write(buffer, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * The mod claims to keep disk and network work off the client thread; these two
     * lane checks are what that claim rests on.
     */
    private static void backgroundLanesUseTheirOwnThreads() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<String> where = new AtomicReference<>("");
        Async.run("selftest", () -> {
            where.set(Thread.currentThread().getName());
            ran.countDown();
        });
        check("background lane runs the task", ran.await(3, TimeUnit.SECONDS));
        check("background lane is not the calling thread",
                where.get().startsWith("smtcraft-") && !where.get().equals(Thread.currentThread().getName()));
        check("background lane threads are daemons",
                AsyncThreads.areDaemons());

        // the coalescing lane must keep the newest snapshot and drop the rest
        AtomicInteger last = new AtomicInteger(-1);
        for (int i = 0; i < 8; i++) {
            final int value = i;
            Async.latest("selftest", () -> last.set(value));
        }
        check("coalescing lane drains", Async.awaitQuiet(3_000L));
        Thread.sleep(50L);
        check("coalescing lane keeps the newest snapshot", last.get() == 7);

        // A result produced before the client installs its executor must wait for
        // it: running it inline on the worker used to touch Minecraft state from a
        // background thread (AUDIT-32).
        Async.setClientExecutor(null);
        AtomicInteger applied = new AtomicInteger();
        Async.onClient(applied::incrementAndGet);
        check("client result waits for an executor", applied.get() == 0);
        try (Async.Scope scope = Async.inlineClientForTests()) {
            check("parked result is handed over once the executor exists", applied.get() == 1);
        }
        // Closing the scope has to put the previous rule back (null here), otherwise
        // every later test would silently run its client work inline (AUDIT-21-2).
        Async.onClient(applied::incrementAndGet);
        check("the inline scope restores the parking rule", applied.get() == 1);

        check("empty submit is refused", !Async.run("selftest", null));

        // awaitQuiet used to look at the queue only, which the coalescing lane empties
        // before it runs the task, so it returned while a write was still going (AUDIT-47)
        AtomicBoolean written = new AtomicBoolean();
        Async.latest("selftest", () -> {
            try {
                Thread.sleep(150L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            written.set(true);
        });
        check("awaitQuiet waits for a running task", Async.awaitQuiet(3_000L) && written.get());
    }

    /** Peek at the lane threads without exposing them from the production class. */
    private static final class AsyncThreads {
        static boolean areDaemons() {
            Thread[] threads = new Thread[Thread.activeCount() + 8];
            int n = Thread.enumerate(threads);
            boolean seen = false;
            for (int i = 0; i < n; i++) {
                Thread thread = threads[i];
                if (!thread.getName().startsWith("smtcraft-")) continue;
                seen = true;
                if (!thread.isDaemon()) return false;
            }
            return seen;
        }
    }

    /**
     * MPRIS metadata is loosely typed in the field: artist is an array by spec
     * but a plain string from some players, length is microseconds and often
     * missing. The conversion has to survive all of that and still produce the
     * same track the Windows path would.
     */
    private static void mprisMetadataParsing() {
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("xesam:title", "Unveil");
        meta.put("xesam:artist", java.util.List.of("*Luna", "\u306d\u3093\u306d"));
        meta.put("xesam:album", "Unveil");
        meta.put("mpris:length", 201_000_000L);
        meta.put("mpris:artUrl", "file:///home/me/covers/a%20b.png");
        java.util.Map<String, Object> player = new java.util.LinkedHashMap<>();
        player.put("PlaybackStatus", "Playing");
        player.put("Metadata", meta);
        player.put("Rate", 1.0d);

        MprisTrack.Snapshot snap = MprisTrack.of("org.mpris.MediaPlayer2.vlc.instance12",
                "", player, 1_500L);
        check("mpris title", "Unveil".equals(snap.title()));
        check("mpris artists joined", "*Luna, \u306d\u3093\u306d".equals(snap.artist()));
        check("mpris length microseconds to ms", snap.lengthMs() == 201_000L);
        check("mpris playing", snap.playing());
        check("mpris position kept", snap.positionMs() == 1_500L);
        check("mpris app falls back to bus suffix", "vlc".equals(snap.app()));
        check("mpris art url", snap.artUrl().endsWith("a%20b.png"));
        check("mpris artists from string", "A, B".equals(MprisTrack.artists("A, B")));
        check("mpris unknown position stays negative",
                MprisTrack.of("org.mpris.MediaPlayer2.mpv", "mpv", player, -1L).positionMs() == -1L);
        check("mpris stops counting when stopped",
                !MprisTrack.of("org.mpris.MediaPlayer2.mpv", "mpv",
                        java.util.Map.of("PlaybackStatus", "Paused"), 0L).playing());

        MprisTrack.Snapshot idle = MprisTrack.of("org.mpris.MediaPlayer2.spotify", "Spotify",
                java.util.Map.of("PlaybackStatus", "Paused", "Metadata",
                        java.util.Map.of("xesam:title", "Other")), 0L);
        check("mpris playing outranks paused", MprisTrack.rank(snap) > MprisTrack.rank(idle));
        check("mpris identity wins over suffix", "Spotify".equals(idle.app()));
        check("mpris short name", "vlc".equals(
                MprisTrack.shortName("org.mpris.MediaPlayer2.vlc.instance12")));
        check("mpris same track", MprisTrack.sameTrack(snap, MprisTrack.of(
                "org.mpris.MediaPlayer2.vlc.instance12", "", player, 9_000L)));
        check("mpris different name is a different track", !MprisTrack.sameTrack(snap, idle));
    }

    /** file:// URLs are percent-encoded and may legally contain a literal '+'. */
    private static void mprisArtworkPaths() {
        java.nio.file.Path plain = MprisArt.filePath("file:///home/me/a%20b.png");
        check("mpris file url decoded", plain != null
                && "/home/me/a b.png".equals(plain.toString().replace('\\', '/')));
        java.nio.file.Path plus = MprisArt.filePath("file:///tmp/x+y.png");
        check("mpris plus is not a space", plus != null && plus.toString().endsWith("x+y.png"));
        check("mpris bare path rejected", MprisArt.filePath("file:relative.png") == null);
        check("mpris missing art is empty", MprisArt.fetch("").length == 0);
        check("mpris hash is stable",
                MprisArt.hash(new byte[] {1, 2, 3}).equals(MprisArt.hash(new byte[] {1, 2, 3})));
        check("mpris hash is non-empty", !MprisArt.hash(new byte[] {1, 2, 3}).isEmpty());
    }

    /**
     * The reply shapes the bus actually hands back: {@code GetAll} is an array of
     * dict entries whose values are variants, and {@code Metadata} inside is
     * another such array. The old decoder had no dict-entry branch, so the map
     * arrived as a list of nulls and every Linux player looked like it published
     * no title at all (AUDIT-51).
     */
    private static void wireDecodesMetadata() {
        Seg metadata = array(List.of(
                entry("xesam:title", variant(str("Unveil"))),
                entry("xesam:artist", variant(array(List.of(str("*Luna"), str("ねんね"))))),
                entry("mpris:length", variant(num(201_000_000L))),
                entry("mpris:artUrl", variant(str("file:///tmp/a.png")))));
        Seg message = array(List.of(
                entry("PlaybackStatus", variant(str("Playing"))),
                entry("Metadata", variant(metadata)),
                entry("Rate", variant(new Seg(Wire.T_DOUBLE, "", 0L, 1.0d, false, List.of())))));

        java.util.Map<String, Object> props =
                org.yinyun.smtcraft.mpris.Wire.properties(new Fake(List.of(message)));
        check("wire dict entry decoded", props.containsKey("Metadata"));
        check("wire nested metadata is a map", props.get("Metadata") instanceof java.util.Map);
        check("wire playback status", "Playing".equals(props.get("PlaybackStatus")));

        java.util.Map<String, Object> player = new java.util.LinkedHashMap<>(props);
        MprisTrack.Snapshot snap = MprisTrack.of("org.mpris.MediaPlayer2.spotify", "Spotify", player, -1L);
        check("wire title reaches the track", "Unveil".equals(snap.title()));
        check("wire artists reaches the track", "*Luna, ねんね".equals(snap.artist()));
        check("wire length reaches the track", snap.lengthMs() == 201_000L);
        check("wire art url reaches the track", snap.artUrl().endsWith("a.png"));
        check("wire playing", snap.playing());

        java.util.List<String> names = org.yinyun.smtcraft.mpris.Wire.strings(new Fake(List.of(
                array(List.of(str("org.freedesktop.DBus"), str("org.mpris.MediaPlayer2.vlc"))))));
        check("wire string array", names.size() == 2 && names.get(1).endsWith("vlc"));
    }

    /** A non-string dict key must stay addressable and be named after its own type. */
    private static void wireEntryWithoutStringKey() {
        Seg odd = new Seg(Wire.T_DICT_ENTRY, "", 0L, 0.0d, false, List.of(
                new Seg(Wire.T_INT32, "", 7L, 0.0d, false, List.of()),
                variant(str("odd"))));
        java.util.Map<String, Object> decoded = org.yinyun.smtcraft.mpris.Wire.properties(
                new Fake(List.of(array(List.of(odd)))));
        check("wire entry with non-string key keeps the payload",
                "odd".equals(decoded.get("#" + Wire.T_INT32)));

        // two keys of the same non-string type used to collapse into one entry and
        // drop the earlier value without a word (AUDIT-74)
        Seg first = new Seg(Wire.T_DICT_ENTRY, "", 0L, 0.0d, false, List.of(
                new Seg(Wire.T_INT32, "", 1L, 0.0d, false, List.of()),
                variant(str("one"))));
        Seg second = new Seg(Wire.T_DICT_ENTRY, "", 0L, 0.0d, false, List.of(
                new Seg(Wire.T_INT32, "", 2L, 0.0d, false, List.of()),
                variant(str("two"))));
        java.util.Map<String, Object> both = org.yinyun.smtcraft.mpris.Wire.properties(
                new Fake(List.of(array(List.of(first, second)))));
        check("two same-type non-string keys both survive",
                both.size() == 2 && both.containsValue("one") && both.containsValue("two"));
    }

    /** A Chinese cover path only resolves if the escapes are decoded as one run. */
    private static void percentDecodeKeepsUtf8() {
        java.nio.file.Path captured = MprisArt.filePath("file:///tmp/%E4%B8%AD%E6%96%87.png");
        check("percent decode keeps multibyte characters", captured != null
                && captured.getFileName().toString().equals("中文.png"));
        check("mpris hash is sixteen hex characters",
                MprisArt.hash(new byte[] {9, 8, 7, 6}).matches("[0-9a-f]{16}"));
    }

    /**
     * R23-3: every libdbus entry point carries two declarations — the descriptor
     * handed to the linker and the header prototype written as Java types — and
     * {@link DbusSymbols} compares them the moment it initialises, which now happens
     * on Windows as well. Two lines inside one file can still be wrong together, so
     * the C declarations also live in a plain text file that this test parses as a
     * third opinion.
     */
    private static void dbusSymbolTableMatchesPrototypes() {
        List<DbusSymbols.Sym> table;
        try {
            // Initialising the table is itself a check; a wrong pair throws here and
            // would otherwise abort the whole run with a stack trace (AUDIT-23-3).
            table = DbusSymbols.all();
        } catch (Throwable broken) {
            // The failure arrives as ExceptionInInitializerError, whose own message is
            // null: the description lives one level down.
            Throwable cause = broken.getCause() == null ? broken : broken.getCause();
            System.out.println("  ! " + cause);
            check("the libdbus table initialises", false);
            return;
        }
        check("seventeen libdbus entry points are declared", table.size() == 17);
        java.util.Set<String> names = new java.util.HashSet<>();
        boolean named = true;
        for (DbusSymbols.Sym sym : table) {
            if (sym.name() == null || sym.name().isBlank() || !names.add(sym.name())) named = false;
        }
        check("every entry point has its own name", named);
        boolean shapes = true;
        try {
            DbusSymbols.checkShapes();
        } catch (RuntimeException broken) {
            System.out.println("  ! " + broken.getMessage());
            shapes = false;
        }
        check("descriptors and prototypes agree", shapes);

        java.nio.file.Path path = prototypeFile();
        List<String> lines = readPrototypes(path);
        if (lines == null) {
            check("the C prototypes are readable (" + path + ")", false);
            return;
        }
        java.util.Map<String, String> header = prototypeShapes(lines);
        check("the prototype file declares the same entry points", header.size() == table.size());
        java.util.List<String> wrong = new java.util.ArrayList<>();
        for (DbusSymbols.Sym sym : table) {
            String declared = header.get(sym.name());
            String expected = handleShape(sym.name(), sym.type());
            if (declared == null || !declared.equals(expected)) {
                wrong.add(sym.name() + ": header=" + declared + " table=" + expected);
            }
        }
        for (String bad : wrong) System.out.println("  ! " + bad);
        check("the table matches the C header text", wrong.isEmpty());
    }

    /** The prototype file, read from the property test.sh and build.gradle pass in. */
    private static java.nio.file.Path prototypeFile() {
        String value = System.getProperty("smtcraft.protos", "");
        try {
            return java.nio.file.Path.of(value);
        } catch (RuntimeException bad) {
            return null;
        }
    }

    private static List<String> readPrototypes(java.nio.file.Path path) {
        if (path == null || !java.nio.file.Files.isReadable(path)) return null;
        try {
            return java.nio.file.Files.readAllLines(path);
        } catch (java.io.IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Parses every declaration line into name -> shape, comments and blanks skipped. */
    private static java.util.Map<String, String> prototypeShapes(List<String> lines) {
        java.util.Map<String, String> shapes = new java.util.HashMap<>();
        for (String line : lines) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) continue;
            String shape = headerShape(text);
            int colon = shape.indexOf(':');
            if (colon < 0) {
                System.out.println("  ! unparsable prototype: " + text);
                continue;
            }
            shapes.put(shape.substring(0, colon), shape);
        }
        return shapes;
    }

    /** "void dbus_error_init(DBusError *)" -> "dbus_error_init:void(ptr)". */
    private static String headerShape(String declaration) {
        int open = declaration.indexOf('(');
        int close = declaration.lastIndexOf(')');
        if (open < 0 || close < open) return "";
        String head = declaration.substring(0, open).trim();
        int space = head.lastIndexOf(' ');
        if (space < 0) return "";
        String returns = head.substring(0, space).trim();
        String name = head.substring(space + 1).trim();
        // "DBusConnection *dbus_bus_get_private": the star is glued to the name.
        while (name.startsWith("*")) {
            name = name.substring(1).trim();
            returns = returns + " *";
        }
        java.util.List<String> params = new java.util.ArrayList<>();
        String inside = declaration.substring(open + 1, close).trim();
        if (!inside.isEmpty() && !inside.equals("void")) {
            for (String part : inside.split(",")) {
                String one = part.trim();
                if (!one.isEmpty()) params.add(headerType(one));
            }
        }
        return name + ":" + headerType(returns) + "(" + String.join(",", params) + ")";
    }

    /** A C type as one of the four carriers the table uses. */
    private static String headerType(String c) {
        String text = c.trim();
        if (text.contains("*")) return "ptr";
        // A parameter may carry its own name: "dbus_bool_t exit_on_disconnect".
        int space = text.indexOf(' ');
        if (space > 0) text = text.substring(0, space);
        switch (text) {
            case "DBusBusType":
                return "int";
            case "void": return "void";
            case "int":
            case "dbus_bool_t": return "int";
            case "long": return "long";
            case "double": return "double";
            default: return "?" + text;
        }
    }

    /** The same shape string, built from the handle type the table declares. */
    private static String handleShape(String name, java.lang.invoke.MethodType type) {
        java.util.List<String> params = new java.util.ArrayList<>();
        for (Class<?> param : type.parameterArray()) params.add(javaType(param));
        return name + ":" + javaType(type.returnType()) + "(" + String.join(",", params) + ")";
    }

    private static String javaType(Class<?> type) {
        if (type == void.class) return "void";
        if (type == int.class) return "int";
        if (type == long.class) return "long";
        if (type == double.class) return "double";
        if (type == java.lang.foreign.MemorySegment.class) return "ptr";
        return "?" + type.getSimpleName();
    }

    // --- fake D-Bus cursor -------------------------------------------------

    private static final class Seg {
        final int type;
        final String text;
        final long integral;
        final double real;
        final boolean bool;
        final List<Seg> kids;

        Seg(int type, String text, long integral, double real, boolean bool, List<Seg> kids) {
            this.type = type;
            this.text = text;
            this.integral = integral;
            this.real = real;
            this.bool = bool;
            this.kids = kids;
        }
    }

    private static Seg str(String value) {
        return new Seg(Wire.T_STRING, value, 0L, 0.0d, false, List.of());
    }

    private static Seg num(long value) {
        return new Seg(Wire.T_INT64, "", value, 0.0d, false, List.of());
    }

    private static Seg array(List<Seg> items) {
        return new Seg(Wire.T_ARRAY, "", 0L, 0.0d, false, items);
    }

    private static Seg variant(Seg child) {
        return new Seg(Wire.T_VARIANT, "", 0L, 0.0d, false, List.of(child));
    }

    private static Seg entry(String key, Seg value) {
        return new Seg(Wire.T_DICT_ENTRY, "", 0L, 0.0d, false, List.of(str(key), value));
    }

    private static final class Fake implements Wire.Cursor {
        private final List<Seg> siblings;
        private int index;

        Fake(List<Seg> siblings) {
            this.siblings = siblings;
        }

        private Seg current() {
            return siblings.get(index);
        }

        @Override public int type() {
            return index < siblings.size() ? current().type : Wire.DONE;
        }

        @Override public Wire.Cursor recurse() {
            if (index >= siblings.size()) return Wire.Cursor.none();
            List<Seg> kids = current().kids;
            return kids.isEmpty() ? Wire.Cursor.none() : new Fake(kids);
        }

        @Override public boolean next() {
            index++;
            return index < siblings.size();
        }

        @Override public String text() { return index < siblings.size() ? current().text : ""; }

        @Override public long integral() { return index < siblings.size() ? current().integral : 0L; }

        @Override public double real() { return index < siblings.size() ? current().real : 0.0d; }

        @Override public boolean bool() { return index < siblings.size() && current().bool; }
    }

    /**
     * R8-1: the shutdown budget and the bridge wait were chosen separately and
     * ended up contradicting each other. One pure constant now feeds both; this
     * keeps a later edit from quietly breaking the relation again.
     */
    private static void shutdownBudgetsAgree() {
        check("shutdown budget exceeds one bridge round",
                Budgets.SHUTDOWN_MS > Budgets.BRIDGE_ROUND_MS);
        check("shutdown budget covers drain + one bridge round + the writes",
                Budgets.SHUTDOWN_MS
                        >= Budgets.DRAIN_MS + Budgets.BRIDGE_ROUND_MS + Budgets.WRITE_RESERVE_MS);
        check("shutdown margin covers the drain and the writes",
                Budgets.SHUTDOWN_MARGIN_MS >= Budgets.DRAIN_MS + Budgets.WRITE_RESERVE_MS);
        check("every derived wait has a non-zero floor", Budgets.MIN_WAIT_MS >= 1L);
        check("lyric close fits inside the shutdown budget",
                Budgets.LYRICS_CLOSE_MS
                        <= Budgets.SHUTDOWN_MS - Budgets.BRIDGE_ROUND_MS - Budgets.DRAIN_MS - Budgets.WRITE_RESERVE_MS);
        check("bridge round covers the MPRIS worst case", Budgets.BRIDGE_ROUND_MS >= 10_000L);
        // R10-1: the sum has to be checkable as a sum. Asserting a few of its
        // terms let 3 s of Async.shutdown sit outside it, and the assertion
        // stayed green while the total was 3 s short of what the steps can spend.
        check("the margin is exactly the sum of its parts",
                Budgets.SHUTDOWN_MARGIN_MS
                        == Budgets.DRAIN_MS + Budgets.WRITE_RESERVE_MS
                         + Budgets.ASYNC_SHUTDOWN_MS + Budgets.LYRICS_CLOSE_MS);
        check("shutdown budget covers every step",
                Budgets.SHUTDOWN_MS
                        >= Budgets.DRAIN_MS + Budgets.BRIDGE_ROUND_MS + Budgets.WRITE_RESERVE_MS
                         + Budgets.ASYNC_SHUTDOWN_MS + Budgets.LYRICS_CLOSE_MS);
        check("the lyric close is the join plus the drain",
                Budgets.LYRICS_CLOSE_MS == Budgets.LYRICS_JOIN_MS + Budgets.LYRICS_DRAIN_MS);
        // R10-3: this used to compare Budgets.CLOSE_GRACE_MS with
        // Budgets.BRIDGE_ROUND_MS, two names for one compile-time constant —
        // no edit could ever make it fail. It now checks the interface field
        // against the source it is supposed to alias, labelled for what it is:
        // a wiring check, not an invariant. Editing MediaBridge back to a
        // literal (3_000L, the pre-R7-1 value) turns this red.
        check("MediaBridge takes its wait from Budgets",
                org.yinyun.smtcraft.media.MediaBridge.CLOSE_GRACE_MS == Budgets.BRIDGE_ROUND_MS);
        // R12-3: the parameterless overload was the home of the literal that
        // R10-1 spent a round chasing. Leaving it behind would let the next
        // caller reopen the same hole, so the signature itself is the assertion.
        boolean bareShutdown = true;
        try {
            Async.class.getMethod("shutdown");
            bareShutdown = false;
        } catch (NoSuchMethodException expected) {
            // only shutdown(long budgetMs) is left, as intended
        }
        check("Async.shutdown requires an explicit budget", bareShutdown);
        // R13-2: the drain that lane gets is now a named constant instead of a
        // literal that disagreed with DRAIN_MS, and it has to fit inside the step
        // it belongs to — a drain larger than the lane budget would starve the
        // awaitTermination right after it.
        check("the async drain fits inside the lane budget",
                Budgets.ASYNC_DRAIN_MS > 0L && Budgets.ASYNC_DRAIN_MS <= Budgets.ASYNC_SHUTDOWN_MS);
        // R13-1: the watchdog is the only thing that can end a process whose
        // sequence never returns, so it has to sit above the whole budget — and
        // small enough that an honest exit never waits for it.
        // R14-3: "greater than zero" and "smaller than the budget" both pass for
        // a grace that quietly grew to half the budget, which is the direction
        // this constant can drift. The ceiling is the side that has to fail.
        check("the watchdog outlives the sequence and stays a grace",
                Budgets.WATCHDOG_GRACE_MS > 0L
                        && Budgets.WATCHDOG_GRACE_MS <= Budgets.WATCHDOG_GRACE_MAX_MS);
        check("the grace ceiling is a grace, not a second budget",
                Budgets.WATCHDOG_GRACE_MAX_MS < Budgets.SHUTDOWN_MS / 2L
                        && Budgets.WATCHDOG_GRACE_MAX_MS >= Budgets.WATCHDOG_GRACE_MS);
        // R19-1: the early drain and the DRAIN step share one line. Asking "how much is
        // left" keeps the slow path honest; asking "did it run" made a timed-out attempt
        // pay twice. Zero and overrun both owe nothing.
        check("the drain left after an early attempt is what the step may spend",
                Budgets.remainingDrain(0L) == Budgets.DRAIN_MS
                        && Budgets.remainingDrain(Budgets.DRAIN_MS / 2L)
                        == Budgets.DRAIN_MS - Budgets.DRAIN_MS / 2L
                        && Budgets.remainingDrain(Budgets.DRAIN_MS) == 0L
                        && Budgets.remainingDrain(Budgets.DRAIN_MS * 2L) == 0L
                        && Budgets.remainingDrain(-5L) == Budgets.DRAIN_MS);
    }

    /**
     * R13-1 as behaviour rather than shape: the deadline has to expire on its own
     * thread while the watched code is still stuck, and the normal path has to be
     * able to call it off. Testing the numbers alone would have passed for the
     * whole twelfth round, when the check sat in a {@code finally} that a hang
     * never reaches.
     */
    private static void watchdogFiresAndCanBeCancelled() {
        AtomicBoolean fired = new AtomicBoolean();
        Thread armed = Watchdog.arm("smtcraft-watchdog-test", 60L, () -> fired.set(true));
        check("the watchdog thread is a daemon", armed.isDaemon());
        // R23-4: this used to be the tail of shutdownBudgetsAgree(), so a reader of
        // main() could not see it run, and it waited three seconds for a sixty
        // millisecond limit. It is a test of its own now and it polls the effect.
        check("the watchdog fires once the limit passes", waitFor(fired, 500L));

        AtomicBoolean cancelled = new AtomicBoolean();
        Thread idle = Watchdog.arm("smtcraft-watchdog-cancel", 10_000L, () -> cancelled.set(true));
        idle.interrupt();
        joinQuietly(idle, 500L);
        check("interrupting the watchdog cancels it", !cancelled.get() && !idle.isAlive());
    }

    /** Waits for a flag to flip, for at most that long. */
    private static boolean waitFor(AtomicBoolean flag, long millis) {
        long giveUpAt = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < giveUpAt) {
            if (flag.get()) return true;
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return flag.get();
            }
        }
        return flag.get();
    }

    private static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * R15-3: one description for every log line. It used to exist twice (in the
     * Windows and the Linux bridge) and the client reached for the Windows one —
     * through the abstraction it was supposed to depend on. Behaviour, not
     * location: a message-less throwable must not print "null".
     */
    private static void errorsDescribeWithoutMessages() {
        check("describe keeps the class name when there is no message",
                "IllegalStateException".equals(
                        org.yinyun.smtcraft.util.Errors.describe(new IllegalStateException())));
        check("describe keeps class and message when there is one",
                "IllegalStateException: boom".equals(
                        org.yinyun.smtcraft.util.Errors.describe(new IllegalStateException("boom"))));
        check("describe drops a blank message instead of printing it",
                "IllegalStateException".equals(
                        org.yinyun.smtcraft.util.Errors.describe(new IllegalStateException("   "))));
        check("describe tolerates a null throwable",
                "none".equals(org.yinyun.smtcraft.util.Errors.describe(null)));
        java.lang.reflect.InvocationTargetException wrapped =
                new java.lang.reflect.InvocationTargetException(new IllegalStateException("denied"));
        check("describe unwraps a reflection failure down to its cause",
                "IllegalStateException: denied".equals(
                        org.yinyun.smtcraft.util.Errors.describe(wrapped)));
        check("describe unwraps a failed static initializer down to its cause",
                "IllegalStateException: denied".equals(
                        org.yinyun.smtcraft.util.Errors.describe(
                                new ExceptionInInitializerError(new IllegalStateException("denied")))));
    }

    /**
     * R16-1: the other direction of R15-3. Extracting the helper was half the fix;
     * the hand-written copies have to go too, one of them fed the status line of the
     * settings screen, where "dbus: IOException: null" is neither locatable nor
     * searchable. A message-less exception is the only input that distinguishes the
     * two implementations, so that is the input the check uses.
     */
    private static void errorsWrapWithoutPrintingNull() {
        IllegalStateException wrapped =
                org.yinyun.smtcraft.util.Errors.asState(new RuntimeException());
        check("asState never prints a null message",
                wrapped.getMessage() != null && wrapped.getMessage().indexOf("null") < 0);
        check("asState keeps the class name of the cause",
                "RuntimeException".equals(wrapped.getMessage()));
        IllegalStateException original = new IllegalStateException("already");
        check("asState returns an IllegalStateException untouched",
                original == org.yinyun.smtcraft.util.Errors.asState(original));
    }

    /**
     * R16-3: the two properties that finishShutdown states in its javadoc were true
     * and unguarded — and two of the four violations this project has found in that
     * method were a reordering (R14-1 moved the watchdog, R15-1 moved a write). The
     * sequence is a list now, so the order can be asserted without Minecraft.
     */
    private static void shutdownPlanKeepsItsOrder() {
        java.util.List<String> plan = org.yinyun.smtcraft.util.ShutdownPlan.steps();
        java.util.Set<String> unique = new java.util.HashSet<>(plan);
        check("the shutdown plan has no duplicate step", unique.size() == plan.size());
        check("the config is written after the drain",
                plan.indexOf(org.yinyun.smtcraft.util.ShutdownPlan.WRITE)
                        > plan.indexOf(org.yinyun.smtcraft.util.ShutdownPlan.DRAIN));
        check("the drain is the first step",
                plan.indexOf(org.yinyun.smtcraft.util.ShutdownPlan.DRAIN) == 0);
        check("the bridge keeps its write reserve by going second",
                plan.indexOf(org.yinyun.smtcraft.util.ShutdownPlan.BRIDGE) == 1);
        check("the offset table is flushed after the config is written",
                plan.indexOf(org.yinyun.smtcraft.util.ShutdownPlan.FLUSH)
                        > plan.indexOf(org.yinyun.smtcraft.util.ShutdownPlan.WRITE));
        check("the lyric worker is closed last",
                plan.indexOf(org.yinyun.smtcraft.util.ShutdownPlan.LYRICS) == plan.size() - 1);
    }

    /**
     * R17-1/R18-3: the reload hand-back drops its snapshot when a save landed after
     * it. The decision used to live only inside a callback on the client thread, so
     * the test could assert its shadow — that the counter grows — and nothing about
     * the branch itself. It is a pure function now, so both outcomes are asserted,
     * and the counter is only checked for accounting and for being atomic (a bare
     * int loses a bump when the lane and saveNow() increment at once, R18-2).
     */
    private static void savesBumpTheReloadGeneration() {
        check("an older snapshot is dropped", SmtcConfig.staleSnapshot(3, 4));
        check("a current snapshot is kept", !SmtcConfig.staleSnapshot(4, 4));
        // Not an ordering: a captured 5 against a current 1 is stale too, and testing
        // only "captured < current" would let a magnitude comparison pass.
        check("staleness is equality, not ordering",
                SmtcConfig.staleSnapshot(5, 1) && SmtcConfig.staleSnapshot(0, -1));
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("build/selftest-config");
            java.nio.file.Files.createDirectories(dir);
            SmtcConfig cfg = new SmtcConfig();
            java.lang.reflect.Field pathField = SmtcConfig.class.getDeclaredField("path");
            pathField.setAccessible(true);
            pathField.set(cfg, dir.resolve("smtcraft.json"));
            java.lang.reflect.Field genField = SmtcConfig.class.getDeclaredField("saveGeneration");
            genField.setAccessible(true);
            Object counter = genField.get(cfg);
            check("the generation counter is an AtomicInteger, not a bare int (AUDIT-18-2)",
                    counter instanceof java.util.concurrent.atomic.AtomicInteger);
            int before = ((java.util.concurrent.atomic.AtomicInteger) counter).get();
            cfg.saveNow();
            int after = ((java.util.concurrent.atomic.AtomicInteger) counter).get();
            check("a save bumps the reload generation", after == before + 1);
            check("the captured generation is now stale", SmtcConfig.staleSnapshot(before, after));
        } catch (ReflectiveOperationException | java.io.IOException failed) {
            check("the reload generation can be inspected (" + failed + ")", false);
        }
    }

    /**
     * R19-2/R19-3: two properties of the config footer. A failed write must leave a
     * category behind (the footer prints that, never the raw English message), and a
     * save whose mtime could not be read must leave loadedMtime at the unknown sentinel so the
     * next poll adopts the stamp instead of reading our own write back over the
     * player's unsaved edits.
     */
    private static void configProblemsAreClassified() {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("build/selftest-config");
            java.nio.file.Files.createDirectories(dir);
            // A regular file where a directory has to be: createDirectories fails, so
            // the write fails before any temp file matters.
            java.nio.file.Path blocker = dir.resolve("not-a-directory");
            java.nio.file.Files.writeString(blocker, "x");
            SmtcConfig broken = new SmtcConfig();
            java.lang.reflect.Field pathField = SmtcConfig.class.getDeclaredField("path");
            pathField.setAccessible(true);
            pathField.set(broken, blocker.resolve("smtcraft.json"));
            // R20-1: a stamp read that failed must become an explicit "unknown", never
            // a leftover value; load() and writeSafely() both decide it here.
            check("an unread stamp becomes an explicit unknown",
                    SmtcConfig.stampOrUnknown(0L) < 0L
                            && SmtcConfig.stampOrUnknown(-7L) == SmtcConfig.stampOrUnknown(0L)
                            && SmtcConfig.stampOrUnknown(1_700_000_000_000L) == 1_700_000_000_000L);
            check("a fresh config has no problem", broken.lastProblem() == SmtcConfig.Problem.NONE);
            broken.saveNow();
            check("a failed write is classified",
                    broken.lastProblem() == SmtcConfig.Problem.SAVE_FAILED);
            check("the raw message is still available for the log",
                    broken.lastSaveError().startsWith("save failed:"));

            java.nio.file.Path file = dir.resolve("smtcraft.json");
            pathField.set(broken, file);
            broken.saveNow();
            check("a successful write clears the category",
                    broken.lastProblem() == SmtcConfig.Problem.NONE);
            check("and clears the message", broken.lastSaveError().isEmpty());

            java.lang.reflect.Field mtimeField = SmtcConfig.class.getDeclaredField("loadedMtime");
            mtimeField.setAccessible(true);
            mtimeField.set(broken, -1L);
            broken.panelWidth = 321;
            broken.saveNow();   // writes 321, so the file is the newer state
            // Pretend that save could not read the stamp back: the file is ours, the
            // stamp is unknown. The poll must adopt the stamp, not reload the file.
            mtimeField.set(broken, -1L);
            broken.panelWidth = 299;   // unsaved edit, must survive the poll
            broken.pollReload();
            check("an unknown stamp is adopted instead of reloaded",
                    (Long) mtimeField.get(broken) > 0L);
            // R20-2: "did the edit survive" was true whichever way the sentinel went,
            // because the synchronous poll hands back another object and never copies
            // into the receiver. To test the part it can prove, force a real reload
            // (a stamp that is not the sentinel, so the round is not skipped) and look
            // at both objects: the receiver keeps the edit, the result carries the file.
            java.lang.reflect.Field syncPollField = SmtcConfig.class.getDeclaredField("lastPoll");
            syncPollField.setAccessible(true);
            mtimeField.set(broken, 1L);
            syncPollField.set(broken, 0L);
            broken.panelWidth = 299;
            SmtcConfig reloaded = broken.pollReload();
            check("the synchronous poll hands back a separate config",
                    reloaded != broken && reloaded.panelWidth == 321);
            check("the synchronous poll leaves the receiver untouched", broken.panelWidth == 299);

            // The asynchronous path is where a self-reload would actually land: the read
            // happens on the I/O lane and copyFrom runs later on the client thread, on
            // top of whatever the player changed meanwhile. Skipping the round is the
            // part that matters, adopting the stamp alone is not enough.
            try (Async.Scope scope = Async.inlineClientForTests()) {
                java.lang.reflect.Field pollField = SmtcConfig.class.getDeclaredField("lastPoll");
                pollField.setAccessible(true);
                java.lang.reflect.Field busyField = SmtcConfig.class.getDeclaredField("reloading");
                busyField.setAccessible(true);
                mtimeField.set(broken, -1L);
                pollField.set(broken, 0L);
                broken.panelWidth = 297;
                broken.pollReloadAsync();
                // awaitQuiet only covers the coalescing lane (AUDIT-44); the poll itself runs
                // on the I/O pool, so wait for its own latch instead of guessing a sleep.
                long pollDeadline = System.currentTimeMillis() + 2_000L;
                while ((Boolean) busyField.get(broken) && System.currentTimeMillis() < pollDeadline) {
                    try {
                        Thread.sleep(2L);
                    } catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                check("the async round adopts the stamp as well", (Long) mtimeField.get(broken) > 0L);
                check("the hand-back does not overwrite the unsaved edit", broken.panelWidth == 297);
            }
        } catch (ReflectiveOperationException | java.io.IOException failed) {
            check("the config problem categories can be inspected (" + failed + ")", false);
        }
    }

    /**
     * The karaoke fill must never step back on a stalled sample (lyric rewind fix).
     * The clock is the last guard in the chain, so it is tested on its own: what it
     * has to survive is a raw position that drops a few hundred ms and comes back.
     */
    private static void lyricPositionNeverRewinds() {
        LyricClock clock = new LyricClock();
        long now = 20_000L;
        check("the clock starts from the raw value", clock.advance(60_000L, "a", now) == 60_000L);
        check("a step forward is drawn at once", clock.advance(60_100L, "a", now + 16L) == 60_100L);
        check("a stalled sample is held, not drawn", clock.advance(59_400L, "a", now + 32L) == 60_100L);
        check("the held value does not drift", clock.advance(59_700L, "a", now + 100L) == 60_100L);
        check("a dip that comes back is invisible", clock.advance(60_120L, "a", now + 128L) == 60_120L);
        check("a real seek is followed at once",
                clock.advance(58_620L, "a", now + 160L) == 58_620L);
        check("a small dip is held first", clock.advance(58_520L, "a", now + 176L) == 58_620L);
        check("a small dip that persists is accepted",
                clock.advance(58_520L, "a", now + 176L + LyricClock.HOLD_MS) == 58_520L);
        check("a new track starts at its own value",
                clock.advance(5L, "b", now + 800L) == 5L);
        clock.reset();
        check("a reset drops the guard", clock.advance(1L, "b", now + 900L) == 1L);
        check("the hold window stays shorter than a seek",
                LyricClock.HOLD_MS < LyricClock.SEEK_BACK_MS);
    }

    /**
     * A paused or buffering sample used to reset the smoother to the raw step, which
     * is behind the position already on screen, and the next playing sample carried
     * on from there: a visible rewind on every stall (lyric rewind fix).
     */
    private static void smootherHoldsThroughAStall() {
        ProgressSmoother smoother = new ProgressSmoother();
        long t = 50_000L;
        long first = smoother.presented(10_000L, true, 1.0f, "k", t);
        check("the first sample is drawn as it is", first == 10_000L);
        // every step below has one arithmetically exact answer: since = now - changedAt,
        // out = rawPos + since * rate, then clamped. Direction-only assertions let a
        // halved rate stay green (AUDIT-25-4), so each value is spelled out.
        long grown = smoother.presented(10_400L, true, 1.0f, "k", t + 500L);
        check("a step forward is followed", grown == 10_400L);
        // between steps the clock runs ahead of the last raw value: that gap is
        // exactly what the reset used to throw away when a sample said pause
        long ahead = smoother.presented(10_400L, true, 1.0f, "k", t + 900L);
        check("the position keeps running between steps", ahead == 10_800L);
        long paused = smoother.presented(10_400L, false, 1.0f, "k", t + 1_000L);
        check("a paused sample does not rewind", paused == 10_800L);
        check("a stalled sample does not rewind either",
                smoother.presented(10_400L, false, 1.0f, "k", t + 1_500L) == paused);
        check("a resume carries on from what was shown",
                smoother.presented(10_400L, true, 1.0f, "k", t + 1_600L) == 10_800L);
        check("a backward seek still lands",
                smoother.presented(3_000L, true, 1.0f, "k", t + 1_800L) == 3_000L);
        check("a new track is taken as it is",
                smoother.presented(0L, true, 1.0f, "other", t + 1_900L) == 0L);
    }

    /**
     * A freeze looks like a very slow publisher: the sample after a four second stall
     * closes a four second gap, and learning that raised the extrapolation cap from
     * 1.5 s to 4.4 s - the cap exists to survive a stall, not to grow on one (AUDIT-25-3).
     */
    private static void smootherIgnoresAStallWhenLearning() {
        ProgressSmoother smoother = new ProgressSmoother();
        long t = 50_000L;
        check("the stall run starts on the raw value",
                smoother.presented(10_000L, true, 1.0f, "k", t) == 10_000L);
        check("a normal beat is accepted",
                smoother.presented(10_400L, true, 1.0f, "k", t + 500L) == 10_400L);
        check("the sample after a four second stall is followed",
                smoother.presented(11_000L, true, 1.0f, "k", t + 4_500L) == 11_000L);
        check("an extrapolation lands one beat later",
                smoother.presented(11_000L, true, 1.0f, "k", t + 6_000L) == 12_500L);
        // 1 500 ms past the anchor, capped by the 500 ms beat + 400 ms, not by 4 400
        check("the stall is not mistaken for the beat",
                smoother.presented(11_000L, true, 1.0f, "k", t + 8_000L) == 12_500L);
    }

    /**
     * A player that publishes every four seconds is not seeking: the jump matches the
     * wall clock. It used to fail the "gap <= 2.5 s" learning rule, so the cap stayed
     * at 1.5 s and the picture fell 2.4 s behind just before each update (AUDIT-26-3).
     */
    private static void smootherLearnsASlowPublisher() {
        ProgressSmoother smoother = new ProgressSmoother();
        long t = 50_000L;
        check("a slow run starts on the raw value",
                smoother.presented(10_000L, true, 1.0f, "k", t) == 10_000L);
        check("a four second step is not a seek",
                smoother.presented(14_000L, true, 1.0f, "k", t + 4_000L) == 14_000L);
        // one gap is a guess, so the cap is still the 1.5 s floor: three seconds of
        // idle would read 17 500 if a single gap were enough
        check("one beat is not enough to learn",
                smoother.presented(14_000L, true, 1.0f, "k", t + 7_500L) == 15_500L);
        check("the second equal beat is followed",
                smoother.presented(18_000L, true, 1.0f, "k", t + 8_000L) == 18_000L);
        // interval = 4 000 now, so the cap is 4 400 and three seconds count in full
        check("the learned period carries the position",
                smoother.presented(18_000L, true, 1.0f, "k", t + 11_000L) == 21_000L);
    }

    /**
     * The learning rule has two halves. The gap alone says "something happened every
     * two seconds"; the distance says whether that something was the audio. A frozen
     * app or a position quantised to whole half seconds can pass the first half only,
     * and the period it would teach is wrong (AUDIT-26-4).
     */
    private static void smootherRejectsAMismatchedStep() {
        ProgressSmoother smoother = new ProgressSmoother();
        long t = 70_000L;
        check("a quantised run starts on the raw value",
                smoother.presented(20_000L, true, 1.0f, "k", t) == 20_000L);
        check("a gap that does not match the step is followed",
                smoother.presented(20_200L, true, 1.0f, "k", t + 2_000L) == 20_200L);
        check("and so is the next one",
                smoother.presented(20_400L, true, 1.0f, "k", t + 4_000L) == 20_400L);
        // had the two second gap been learned, the cap would be 2 400 and this
        // sample would read 22 400 instead of the 1 500 ms floor below
        check("the mismatch never becomes the period",
                smoother.presented(20_400L, true, 1.0f, "k", t + 6_000L) == 21_900L);
    }

    /** One formula, two callers: the sung edge is pinned to HEAD_ANCHOR, then clamped. */
    private static void followOffsetPinsTheSungEdge() {
        float box = 100.0f;
        float wide = 300.0f;
        check("a line that fits is not scrolled",
                LyricScroll.followOffset(50.0f, box, box) == 0.0f);
        check("the pin sits where the head is drawn",
                LyricScroll.followOffset(box * LyricScroll.HEAD_ANCHOR, wide, box) == 0.0f);
        check("a sung half scrolls by half the width minus the pin",
                LyricScroll.followOffset(150.0f, wide, box)
                        == 150.0f - box * LyricScroll.HEAD_ANCHOR);
        check("the tail stops at the end of the line",
                LyricScroll.followOffset(wide, wide, box) == wide - box);
        check("an empty box is not scrolled",
                LyricScroll.followOffset(wide, wide, 0.0f) == 0.0f);
    }
    /**
     * The corner radius and the spin speed each have one range: the constants, which
     * {@code normalise()} clamps to and the settings sliders are built from. Nothing asserted
     * the clamps at all, so a range that drifted from its slider would only show up as a
     * value the panel cannot reproduce (AUDIT-27-2).
     */
    private static void configClampsMatchTheSliders() {
        check("the corner range is ordered", SmtcConfig.MIN_CORNER_RADIUS < SmtcConfig.MAX_CORNER_RADIUS);
        check("the spin range is ordered", SmtcConfig.MIN_VINYL_SPIN < SmtcConfig.MAX_VINYL_SPIN);
        SmtcConfig edges = new SmtcConfig();
        edges.borderOpacity = 4.0f;
        edges.normalise();
        check("a border opacity above the range stops at 1", edges.borderOpacity == 1.0f);
        edges.borderOpacity = -2.0f;
        edges.normalise();
        check("a border opacity below the range stops at 0", edges.borderOpacity == 0.0f);
        // the two new colour fields are read straight by the renderer: a blank default
        // would paint the translation with whatever Draw.color() falls back to
        SmtcConfig palette = new SmtcConfig();
        check("the translation has a colour of its own",
                palette.translationColor != null && palette.translationColor.matches("#[0-9A-Fa-f]{6}"));
        check("the candidate line has a colour of its own",
                palette.candidateColor != null && palette.candidateColor.matches("#[0-9A-Fa-f]{6}"));
        check("the header is drawn by default", !palette.lyricsOnly);
        check("the flat cover is rounded by default", palette.artRound);
        palette.artRadius = SmtcConfig.MAX_ART_RADIUS + 40;
        palette.normalise();
        check("a cover radius above the range stops at the top",
                palette.artRadius == SmtcConfig.MAX_ART_RADIUS);
        palette.artRadius = -3;
        palette.normalise();
        check("a cover radius below the range stops at 0", palette.artRadius == 0);
        SmtcConfig high = new SmtcConfig();
        high.cornerRadius = SmtcConfig.MAX_CORNER_RADIUS + 40;
        high.vinylSpinSpeed = SmtcConfig.MAX_VINYL_SPIN + 300.0f;
        high.normalise();
        check("a corner above the range stops at the top",
                high.cornerRadius == SmtcConfig.MAX_CORNER_RADIUS);
        check("a spin above the range stops at the top", high.vinylSpinSpeed == SmtcConfig.MAX_VINYL_SPIN);
        SmtcConfig low = new SmtcConfig();
        low.cornerRadius = SmtcConfig.MIN_CORNER_RADIUS - 9;
        low.vinylSpinSpeed = SmtcConfig.MIN_VINYL_SPIN - 12.0f;
        low.normalise();
        check("a corner below the range stops at the bottom",
                low.cornerRadius == SmtcConfig.MIN_CORNER_RADIUS);
        check("a spin below the range stops at the bottom", low.vinylSpinSpeed == SmtcConfig.MIN_VINYL_SPIN);
    }

    /**
     * The pages are laid out for GUI scale 2 and scaled from there.
     *
     * <p>What matters is the viewport, not the multiplier: at every scale the design width
     * has to come out the same, because that is what decides whether fourteen rows still
     * fit. The multiplier on its own can be right while the page is still laid out in
     * screen pixels, which is exactly the bug this is here to catch.</p>
     */
    private static void uiScaleFollowsTheGuiScale() {
        check("scale 2 is the design size", UiScale.factor(2) == 1.0f);
        check("scale 4 halves the multiplier", UiScale.factor(4) == 0.5f);
        check("scale 1 doubles the page", UiScale.factor(1) == UiScale.MAX);
        check("an unknown scale leaves the page alone", UiScale.factor(0) == 1.0f);
        int designWidth = 1920 / UiScale.BASE;
        boolean sameViewport = true;
        for (int scale = 1; scale <= 4; scale++) {
            int screenWidth = 1920 / scale;
            if (UiScale.design(screenWidth, UiScale.factor(scale)) != designWidth) sameViewport = false;
        }
        check("a 1920 px window designs to the same width at every scale", sameViewport);
        // the height decides the row pitch: at scale 4 a 1080 px window is 270 logical px,
        // which is what squeezed fourteen rows into a 10 px pitch
        check("a 1080 px window at scale 4 designs to the scale-2 height",
                UiScale.design(1080 / 4, UiScale.factor(4)) == 1080 / UiScale.BASE);
        check("a nonsense factor is ignored", UiScale.design(320, 0.0f) == 320);
        check("the floor keeps the text readable", UiScale.factor(9) == UiScale.MIN);
        // one notch is 14 px on the glass whatever the scale: the design step has to move the
        // other way, so the amount the reader sees does not depend on the interface size
        check("a notch travels 14 screen px at the design scale", UiScale.scrollStep(-1.0d, 1.0f) == -14);
        check("a doubled page scrolls half as far in design pixels", UiScale.scrollStep(-1.0d, 2.0f) == -7);
        check("a halved page scrolls twice as far in design pixels", UiScale.scrollStep(-1.0d, 0.5f) == -28);
        check("a fractional notch still moves", UiScale.scrollStep(0.05d, 2.0f) == 1);
        check("no notch does not move", UiScale.scrollStep(0.0d, 1.0f) == 0);
    }

    /**
     * The side-by-side panel is two blocks placed inside one panel, so the arithmetic that decides
     * where they sit is the whole feature. Every case is checked as a rectangle list rather
     * than as "the panel looks right": a stack that silently overlaps, or a side-by-side
     * panel that hangs the shorter block from the top edge, is exactly what a screenshot
     * hides.
     */
    private static void hudLayoutArrangesTheTwoBlocks() {
        check("side is chosen by its name", HudLayout.side("side") && HudLayout.side(" SIDE "));
        check("a stacked panel is not side by side",
                !HudLayout.side("stack") && !HudLayout.side(null));
        check("the switch writes back the stored name", HudLayout.SIDE.equals(HudLayout.name(true))
                && HudLayout.STACK.equals(HudLayout.name(false)));

        // 200x58 track block, 200x40 lyric band
        int[] stacked = HudLayout.arrange(false, true, 200, 58, 200, 40);
        check("a stacked panel is the sum of the two heights",
                stacked[4] == 200 && stacked[5] == 98);
        check("the track block sits on top", stacked[0] == 0 && stacked[1] == 0
                && stacked[2] == 0 && stacked[3] == 58);
        int[] swapped = HudLayout.arrange(false, false, 200, 58, 200, 40);
        check("the lyric band can take the top slot", swapped[3] == 0 && swapped[1] == 40);

        int[] side = HudLayout.arrange(true, true, 200, 58, 200, 40);
        check("a side-by-side panel is as wide as both blocks and the gap",
                side[4] == 200 + HudLayout.GAP + 200);
        check("a side-by-side panel is as tall as the taller block", side[5] == 58);
        check("the track block keeps the left column",
                side[0] == 0 && side[2] == 200 + HudLayout.GAP);
        check("the shorter block is centred, not hung from the top", side[3] == (58 - 40) / 2);
        int[] right = HudLayout.arrange(true, false, 200, 58, 200, 40);
        check("the track block can keep the right column",
                right[0] == 200 + HudLayout.GAP && right[2] == 0);

        // no lyrics: there is nothing to put beside the track block
        int[] noBand = HudLayout.arrange(true, true, 200, 58, 200, 0);
        check("a panel with no lyric band stays stacked", noBand[4] == 200 && noBand[5] == 58
                && noBand[2] == 0 && noBand[3] == 58);
        // lyrics only: the track block has no height
        int[] bandOnly = HudLayout.arrange(true, true, 200, 0, 200, 40);
        check("a lyrics-only panel is just the band", bandOnly[4] == 200 && bandOnly[5] == 40);

        SmtcConfig cfg = new SmtcConfig();
        cfg.hudLayout = "diagonally";
        cfg.normalise();
        check("a layout that does not exist falls back to the stack",
                HudLayout.STACK.equals(cfg.hudLayout));
        cfg.hudLayout = " SIDE ";
        cfg.normalise();
        check("a layout name is normalised", HudLayout.SIDE.equals(cfg.hudLayout));
        cfg.hudLayout = " vertical ";
        cfg.normalise();
        check("the vertical layout survives normalising",
                HudLayout.VERTICAL.equals(cfg.hudLayout) && HudLayout.vertical(cfg.hudLayout));
        cfg.hudLayout = "VERTICAL";
        cfg.normalise();
        check("the vertical name is case blind", HudLayout.VERTICAL.equals(cfg.hudLayout));
        check("only the vertical name makes a column panel",
                !HudLayout.vertical("side") && !HudLayout.vertical(HudLayout.STACK)
                        && !HudLayout.vertical(null));
        check("a panel name is cleaned to one of three",
                HudLayout.VERTICAL.equals(HudLayout.clean(" vertical "))
                        && HudLayout.SIDE.equals(HudLayout.clean("Side"))
                        && HudLayout.STACK.equals(HudLayout.clean("diagonally"))
                        && HudLayout.STACK.equals(HudLayout.clean(null)));
        check("turning the vertical panel off goes back to the stack",
                HudLayout.VERTICAL.equals(
                        HudLayout.vertical(HudLayout.VERTICAL) ? HudLayout.VERTICAL : HudLayout.STACK)
                        && HudLayout.STACK.equals(HudLayout.clean(HudLayout.STACK)));
        check("the track block leads by default", new SmtcConfig().trackFirst);

        // A side-by-side column hugs the seam rather than centring inside itself: the edge it
        // hugs is the one next to the other block, and that direction follows the switches,
        // not the window (m11885).
        check("the right column hugs its left edge",
                HudLayout.seamAlign(true, true) == HudLayout.LEFT);
        check("the left column hugs its right edge",
                HudLayout.seamAlign(true, false) == HudLayout.RIGHT);
        check("a stacked panel keeps its text centred",
                HudLayout.seamAlign(false, true) == HudLayout.CENTRE
                        && HudLayout.seamAlign(false, false) == HudLayout.CENTRE);
        // a 200 design-pixel column with 8 px padding has 184 px of room
        check("a left-hugging run starts at the padding",
                HudLayout.textLeft(HudLayout.LEFT, 60, 200, 8) == 8);
        check("a right-hugging run ends at the padding",
                HudLayout.textLeft(HudLayout.RIGHT, 60, 200, 8) == 200 - 8 - 60);
        check("a centred run keeps the middle",
                HudLayout.textLeft(HudLayout.CENTRE, 60, 200, 8) == 8 + (184 - 60) / 2);
        check("a run wider than the column keeps the padding",
                HudLayout.textLeft(HudLayout.RIGHT, 400, 200, 8) == 8);

        // Vertical columns read right to left, so the line being sung sits one column in from
        // the right edge, the line already read sits on the right, and the next line waits on
        // the left (m11944).
        check("a vertical column is a glyph box plus room",
                HudLayout.columnWidth(1.0f) == 13);
        check("a vertical column scales with the text",
                HudLayout.columnWidth(2.0f) > HudLayout.columnWidth(1.0f));
        check("a column step leaves a gap between columns",
                HudLayout.columnStep(1.0f) == HudLayout.columnWidth(1.0f) + 3);
        // lyric columns sit closer than track columns: two lines of one song, not two rows
        // of a table (m12141, m12197)
        check("lyric columns sit closer than track columns",
                HudLayout.lyricStep(1.0f) == HudLayout.columnWidth(1.0f) + 1
                        && HudLayout.lyricStep(1.0f) < HudLayout.columnStep(1.0f));
        check("a lyric step scales with the text",
                HudLayout.lyricStep(2.0f) > HudLayout.lyricStep(1.0f)
                        && HudLayout.lyricStep(Float.NaN) == HudLayout.lyricStep(1.0f));
        // a column cannot marquee sideways, so a run taller than the strip walks instead
        check("a run that fits does not scroll",
                HudLayout.verticalScroll(5.0f, 80, 100) == 0
                        && HudLayout.verticalScroll(0.0f, 100, 100) == 0);
        check("a long run rests before it starts",
                HudLayout.verticalScroll(0.0f, 300, 100) == 0
                        && HudLayout.verticalScroll(HudLayout.MARQUEE_PAUSE * 0.5f, 300, 100) == 0);
        check("a long run reaches its last glyph at the end of the trip",
                HudLayout.verticalScroll(
                        HudLayout.MARQUEE_PAUSE + 200.0f / HudLayout.MARQUEE_SPEED, 300, 100) == 200);
        check("a long run never scrolls past its own length",
                HudLayout.verticalScroll(4.0f, 300, 100) <= 200
                        && HudLayout.verticalScroll(30.0f, 300, 100) <= 200);
        // one pace for every scroller: the column walks as fast as the marquee (m12491). A run
        // that starts at 1.25 s is still holding at the old 1.2 s rest and only just moving.
        check("the column walks at the marquee's pace",
                HudLayout.verticalScroll(1.25f, 300, 100) == 0
                        && HudLayout.verticalScroll(HudLayout.MARQUEE_PAUSE
                                + 200.0f / HudLayout.MARQUEE_SPEED, 300, 100) == 200
                        && HudLayout.MARQUEE_SPEED > 22.0f);
        // one way only: once the tail is in view it stays in view, horizontally and vertically
        check("a long run stays at its tail instead of coming back",
                HudLayout.verticalScroll(600.0f, 300, 100) == 200
                        && HudLayout.verticalScroll(1200.0f, 300, 100) == 200);
        check("a marquee stays at its tail instead of coming back",
                HudLayout.scrollOver(200.0f, 600.0f, 26.0f, 1.3f) == 200.0f
                        && HudLayout.scrollOver(200.0f, 900.0f, 26.0f, 1.3f) == 200.0f
                        && HudLayout.scrollOver(0.0f, 900.0f, 26.0f, 1.3f) == 0.0f);
        // ... and a title walks back: only the lyrics are one way (m12414)
        check("a title walks down and comes back",
                HudLayout.verticalScrollCycle(30.0f, 300, 100) >= 0
                        && HudLayout.verticalScrollCycle(400.0f, 300, 100) >= 0
                        && HudLayout.verticalScrollCycle(
                                HudLayout.MARQUEE_PAUSE + 200.0f / HudLayout.MARQUEE_SPEED, 300, 100)
                                == 200);
        float cycleTravel = 200.0f / 26.0f;
        float cyclePeriod = 2.0f * (cycleTravel + 1.3f);
        float onTheWayBack = HudLayout.scrollCycle(200.0f, cyclePeriod - cycleTravel * 0.5f, 26.0f,
                1.3f);
        check("a title comes back to its first glyph",
                HudLayout.scrollCycle(200.0f, cyclePeriod, 26.0f, 1.3f) == 0.0f
                        && HudLayout.scrollCycle(200.0f, 1.3f + cycleTravel, 26.0f, 1.3f) == 200.0f
                        && onTheWayBack > 0.0f && onTheWayBack < 200.0f);
        check("a title that fits still does not move",
                HudLayout.scrollCycle(0.0f, 50.0f, 26.0f, 1.3f) == 0.0f
                        && HudLayout.verticalScrollCycle(50.0f, 100, 100) == 0);
        // both walks are the same curve (R49-5): a tenth of the way in, the eased travel is
        // behind the straight line, and both ends still land exactly on their stop
        float tenthWay = HudLayout.scrollCycle(200.0f, 1.3f + cycleTravel * 0.1f, 26.0f, 1.3f);
        float tenthOneWay = HudLayout.scrollOver(200.0f, 1.3f + cycleTravel * 0.1f, 26.0f, 1.3f);
        check("the two walks share one curve",
                tenthWay > 0.0f && tenthWay < 20.0f
                        && tenthOneWay > 0.0f && tenthOneWay < 20.0f
                        && Math.abs(tenthWay - tenthOneWay) < 0.5f);
        // The cycling walk must be continuous in its own clock. Wrapping the clock first, on a
        // constant that is not a whole number of cycles, sent every column back to its first
        // glyph once an hour - up to 159 px in one frame, different for every column (R51-1).
        // Two hours of samples at 0.05 s. The ceiling is one frame of travel at the current
        // speed, not a number borrowed from today's constant: a flat 2.0 px sat 2.57 % above
        // the measured 1.95 px, so making the marquee a little faster failed a test whose
        // message talks about the clock (R52-4).
        float still = 1.6f * HudLayout.MARQUEE_SPEED * 0.05f;
        // The vertical walk answers in whole pixels, so one frame can round up by one on top
        // of the travel: the bound for that path carries the rounding, the cycling path does
        // not answer in pixels at all and is held to the travel itself (R52-4).
        check("a title never jumps back on the hour", worstStep(7200.0d, true) <= still);
        check("a long run never jumps back on the hour", worstStep(7200.0d, false) <= still + 1.0f);
        // ...and the ceiling still has to be tight enough, or it stops being a test: the jump
        // it guards is measured in tens of pixels, while a frame of travel is about two, so
        // the measured worst step must sit close under the bound (R52-4).
        check("the jump bound is one frame of travel, not a borrowed number",
                still >= 1.5f && still <= 3.6f && worstStep(7200.0d, true) >= still * 0.5f);
        // the vertical height slider has to reach 0 for "auto", which leaves the pixels just
        // above it meaning nothing on their own: they snap to the shortest height the panel can
        // use instead of printing a number the panel then ignores (R49-4)
        check("the height slider snaps to a height the panel can use",
                SmtcConfig.verticalHeightOf(0.0d) == 0
                        && SmtcConfig.verticalHeightOf(0.4d) == 0
                        && SmtcConfig.verticalHeightOf(50.0d) == SmtcConfig.MIN_VERTICAL_H
                        && SmtcConfig.verticalHeightOf(96.0d) == 96
                        && SmtcConfig.verticalHeightOf(900.0d) == SmtcConfig.MAX_VERTICAL_H);
        check("a broken scale still gives a column",
                HudLayout.columnWidth(0.0f) == HudLayout.columnWidth(1.0f)
                        && HudLayout.columnWidth(Float.NaN) == HudLayout.columnWidth(1.0f));
        int sungColumn = HudLayout.columnLeft(0, 400, 8, 1.0f);
        int previousColumn = HudLayout.columnLeft(-1, 400, 8, 1.0f);
        int nextColumn = HudLayout.columnLeft(1, 400, 8, 1.0f);
        check("the line being sung is one column in from the right",
                sungColumn == 400 - 8 - HudLayout.columnWidth(1.0f) - HudLayout.columnStep(1.0f));
        check("columns run from right to left",
                previousColumn > sungColumn && sungColumn > nextColumn);
        check("a column that falls off the left edge is negative",
                HudLayout.columnLeft(40, 200, 8, 1.0f) < 0);
    }


    /**
     * The release check is mostly string work, and each part of it decides whether a player
     * is told about a version they already have: the tag loses its "v", the JSON scrape takes
     * the first value, and the comparison follows Fabric's own reading of a four-part version
     * - the date suffix sorts *above* the plain version, so a same-day rebuild is not "older".
     */
    private static void releaseCheckReadsTagsAndOrdersThem() {
        check("a v prefix is stripped from a tag", "1.0.3".equals(UpdateCheck.cleanTag(" v1.0.3 ")));
        check("a plain tag is left alone", "1.0.2.20261123".equals(UpdateCheck.cleanTag("1.0.2.20261123")));
        String body = "{\"tag_name\":\"v1.0.3\",\"name\":\"1.0.3\","
                + "\"html_url\":\"https://example.invalid/releases/v1.0.3\"}";
        check("the tag is read out of a release body", "v1.0.3".equals(UpdateCheck.tagOf(body)));
        check("the release page is read out of the same body",
                "https://example.invalid/releases/v1.0.3".equals(UpdateCheck.releasePageOf(body)));
        check("a body without a tag answers empty", UpdateCheck.tagOf("{\"message\":\"Not Found\"}").isEmpty());
        check("a newer release is news", UpdateCheck.newer("1.0.2", "1.0.3"));
        check("the date suffix sorts above the plain version",
                UpdateCheck.newer("1.0.2", "1.0.2.20261123"));
        check("the same version is not news", !UpdateCheck.newer("1.0.3", "v1.0.3"));
        check("an older release is not news", !UpdateCheck.newer("1.0.3", "1.0.2.20261123"));
        check("an unreadable current version is not news", !UpdateCheck.newer("", "1.0.3"));
        check("four parts compare", UpdateCheck.compare("1.0.3.20261123", "1.0.3") > 0);
        check("ten is bigger than nine", UpdateCheck.compare("1.0.10", "1.0.9") > 0);
        check("nothing is known before the first answer", !UpdateCheck.state().known());
        check("nothing is available before the first answer", !UpdateCheck.state().available());
    }

    /**
     * The REST endpoint is rationed per address - sixty calls an hour for everyone behind
     * the same address - so the check has two more ways to learn the newest tag: the site
     * feed, whose first entry ends with the tag, and the redirect of the "latest" page.
     * Both scrape the same three facts, and both answer empty rather than guessing.
     */
    private static void updateCheckFallsBackPastTheApi() {
        String feed = "<feed><entry>"
                + "<id>tag:github.com,2008:Repository/1466447196/v1.0.2</id>"
                + "<link rel=\"alternate\" type=\"text/html\""
                + " href=\"https://github.com/123xzxc/SMTCraft/releases/tag/v1.0.2\"/>"
                + "</entry><entry>"
                + "<id>tag:github.com,2008:Repository/1466447196/v1.0.1</id>"
                + "</entry></feed>";
        check("the feed names its first tag", "v1.0.2".equals(UpdateCheck.atomTag(feed)));
        check("the feed links its first release",
                "https://github.com/123xzxc/SMTCraft/releases/tag/v1.0.2".equals(UpdateCheck.atomLink(feed)));
        check("a feed without entries answers empty", UpdateCheck.atomTag("<feed></feed>").isEmpty());
        check("a feed without a link answers empty", UpdateCheck.atomLink("<feed><entry>x</entry></feed>").isEmpty());
        check("the redirect names the tag", "v1.0.3".equals(UpdateCheck.tagInLocation(
                "https://github.com/123xzxc/SMTCraft/releases/tag/v1.0.3")));
        check("a query after the tag is dropped", "v1.0.3".equals(UpdateCheck.tagInLocation(
                "https://github.com/123xzxc/SMTCraft/releases/tag/v1.0.3?expanded=true")));
        check("a page that is not a release answers empty",
                UpdateCheck.tagInLocation("https://github.com/login").isEmpty());
        check("an empty redirect answers empty", UpdateCheck.tagInLocation("").isEmpty());

        UpdateCheck.State newer = new UpdateCheck.State("1.0.0", "1.0.1", "u", "", 7L);
        UpdateCheck.State same = new UpdateCheck.State("1.0.2.20261123", "1.0.1", "u", "", 7L);
        UpdateCheck.State unknown = new UpdateCheck.State("1.0.2.20261123", "", "", "", 0L);
        UpdateCheck.State refused = new UpdateCheck.State("1.0.2.20261123", "", "",
                "api: http 403 (rations used up)", 7L);
        check("a newer tag asks for the release badge",
                UpdateCheck.BADGE_AVAILABLE.equals(UpdateCheck.badgeKey(newer, false)));
        check("a failed look repeats the reason, it does not claim to be current",
                UpdateCheck.BADGE_FAILED.equals(UpdateCheck.badgeKey(refused, false)));
        check("a look in flight says it is still looking",
                UpdateCheck.BADGE_CHECKING.equals(UpdateCheck.badgeKey(unknown, false))
                        && UpdateCheck.BADGE_CHECKING.equals(UpdateCheck.badgeKey(same, true)));
        check("a finished look with nothing newer says it is current",
                UpdateCheck.BADGE_CURRENT.equals(UpdateCheck.badgeKey(same, false)));
        long began = System.currentTimeMillis();
        check("a wait with no time left answers the empty state at once",
                UpdateCheck.awaitAnswer(0L).known() == false
                        && System.currentTimeMillis() - began < 400L);
        check("a negative wait does not hang", UpdateCheck.awaitAnswer(-5L).known() == false);
        check("a pre-release is older than its release",
                UpdateCheck.compare("1.0.2-beta", "1.0.2") < 0
                        && !UpdateCheck.newer("1.0.2", "1.0.2-beta"));
        check("build metadata does not order two releases",
                UpdateCheck.compare("1.0.2+build5", "1.0.2") == 0
                        && !UpdateCheck.newer("1.0.2", "1.0.2+build5"));
        check("a release outranks the beta before it",
                UpdateCheck.newer("1.0.2-beta", "1.0.3"));
        check("a dated release outranks a beta of a later base",
                UpdateCheck.newer("1.0.2.20261123", "1.0.3-beta"));
        check("a version that cannot be read says so instead of claiming to be current",
                UpdateCheck.BADGE_UNKNOWN.equals(UpdateCheck.badgeKey(
                        new UpdateCheck.State("", "1.0.9", "u", "", 7L), false)));
        check("a failed look names its reason by key, not by diagnostic",
                UpdateCheck.REASON_RATE.equals(UpdateCheck.reasonKey(refused))
                        && UpdateCheck.BADGE_FAILED.equals(UpdateCheck.badgeKey(refused, false)));
        check("a switch that was never turned on is not a look in flight",
                UpdateCheck.BADGE_OFF.equals(UpdateCheck.badgeKey(unknown, false, false))
                        && UpdateCheck.BADGE_OFF.equals(UpdateCheck.badgeKey(newer, false, false))
                        && UpdateCheck.BADGE_AVAILABLE.equals(UpdateCheck.badgeKey(newer, false, true)));
        check("the reason is data on the answer, not a word in its diagnostic",
                UpdateCheck.REASON_TLS.equals(UpdateCheck.reasonKey(
                        new UpdateCheck.State("1.0.2.20261123", "", "", "look failed",
                                7L, UpdateCheck.Reason.TLS))));
        check("a certificate that refused the connection is its own reason",
                UpdateCheck.Reason.TLS == UpdateCheck.classify(
                        "javax.net.ssl.SSLHandshakeException: PKIX path building failed"));
        check("a silent network is not a rationed one",
                UpdateCheck.Reason.OFFLINE == UpdateCheck.classify(
                        "java.net.UnknownHostException: github.com")
                        && UpdateCheck.Reason.OFFLINE == UpdateCheck.classify("connect timed out"));
        check("a page without a tag is not a network failure",
                UpdateCheck.Reason.OTHER == UpdateCheck.classify("no entry in the feed"));
        // the scheme is a property of the code, so it is asserted here; the shell guard only
        // refuses a scheme assembled out of a small literal (AUDIT-47-5)
        check("every endpoint of the check is https",
                UpdateCheck.API.startsWith("https://") && UpdateCheck.HOME.startsWith("https://")
                        && UpdateCheck.ISSUES.startsWith("https://")
                        && UpdateCheck.RELEASES.startsWith("https://")
                        && UpdateCheck.ATOM.startsWith("https://")
                        && UpdateCheck.LATEST.startsWith("https://")
                        && UpdateCheck.API.endsWith("/releases/latest"));
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        } else {
            System.out.println("ok   " + what);
        }
    }

    private SelfTest() {}
}
