package org.yinyun.smtcraft.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asks GitHub what the newest published release is, without holding up the game.
 *
 * <p>The check is one {@code GET} against the public releases endpoint; it never
 * sends anything about the player and it never installs anything. A first call
 * starts a daemon thread and returns at once, so the entrypoint, the settings
 * screen and the Mod Menu badge can all ask for the answer while it is still in
 * flight. {@link #state()} is then the last answer that arrived, good or bad,
 * until {@link #STALE_MS} has passed.</p>
 *
 * <p>Nothing here touches Minecraft: the version comparison, the tag cleanup and
 * the JSON scrape are plain functions so the self-test can exercise them without
 * a client.</p>
 */
public final class UpdateCheck {

    /** Owner and repository of the published builds. */
    public static final String REPO = "123xzxc/SMTCraft";

    /** The project page, which Mod Menu also shows as the website. */
    public static final String HOME = "https://github.com/" + REPO;

    /** Where bug reports go. */
    public static final String ISSUES = HOME + "/issues";

    /** Where the builds are announced. */
    public static final String RELEASES = HOME + "/releases";

    /** The endpoint that names the newest release. */
    public static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    /**
     * The same answer without the API: the feed lists every release newest first, and
     * unlike the REST endpoint it is not rationed per address. Unauthenticated calls to
     * {@link #API} are limited to sixty an hour for the whole address, and a shared
     * address has usually spent them.
     */
    public static final String ATOM = HOME + "/releases.atom";

    /** The same answer again: this page answers with a redirect to the newest tag. */
    public static final String LATEST = HOME + "/releases/latest";

    /** The badge answer when a newer release exists: its argument is the tag. */
    public static final String BADGE_AVAILABLE = "smtcraft.update.available";

    /** The badge answer when the last look ended in an error: its argument is the reason. */
    public static final String BADGE_FAILED = "smtcraft.about.failed";

    /** The badge answer while the first reply is still on its way. */
    public static final String BADGE_CHECKING = "smtcraft.about.checking";

    /** The badge answer when the install is at least as new as the last release. */
    public static final String BADGE_CURRENT = "smtcraft.update.current";

    /**
     * The badge answer when the running version cannot be read at all, so there is nothing
     * to compare against. "Up to date" would be the one claim the code cannot make there.
     */
    public static final String BADGE_UNKNOWN = "smtcraft.about.unknown";

    /**
     * The badge answer when the player turned the switch off. Without it the empty state of
     * a look that never ran reads exactly like a look still in flight, so the page and the
     * Mod Menu badge both said "checking" forever (AUDIT-47-1).
     */
    public static final String BADGE_OFF = "smtcraft.about.off";

    /** The short reason a player reads: GitHub is rationing this address. */
    public static final String REASON_RATE = "smtcraft.about.reason.rate";

    /** The short reason for a look that never reached GitHub. */
    public static final String REASON_OFFLINE = "smtcraft.about.reason.offline";

    /** The short reason for a look that a certificate or a proxy stopped. */
    public static final String REASON_TLS = "smtcraft.about.reason.tls";

    /** The short reason for everything else, including a page without a tag. */
    public static final String REASON_OTHER = "smtcraft.about.reason.other";

    /**
     * Why a look failed, carried as data instead of being read back out of the diagnostic.
     *
     * <p>Until AUDIT-47-4 the reason was a substring search over {@link State#note()}, which
     * is prose written for a bug report: rewording one verb silently moved a failure to
     * another reason, and a certificate error - the most common kind behind a company
     * proxy - had no word at all. The look now names the reason where it catches it.</p>
     */
    public enum Reason {
        /** GitHub is rationing this address (403, 429). */
        RATE,
        /** Nothing answered: DNS, refusal, timeout. */
        OFFLINE,
        /** Something answered but the connection could not be trusted. */
        TLS,
        /** Everything else, including a 404 or a page without a tag. */
        OTHER
    }

    /** How long a finished answer stays good before another look. */
    public static final long STALE_MS = 6L * 60L * 60L * 1000L;

    /**
     * Which badge answer a state calls for, for a build whose switch is on.
     *
     * @param state   what the last look found
     * @param running true while another look is in flight
     * @return one of the {@code BADGE_} constants
     */
    public static String badgeKey(State state, boolean running) {
        return badgeKey(state, running, true);
    }

    /**
     * Which of the six badge answers a state calls for.
     *
     * <p>Six, not two: a look that ended in an error is not evidence that the install
     * is current, a jar whose own version cannot be read has nothing to compare with, and
     * a switch that was never turned on is not a look in flight - saying "checking" there
     * (which is what the empty state used to fall through to) told the player it was stuck.
     * The caller decides the argument - the tag, the reason, or the running version.</p>
     *
     * @param state   what the last look found
     * @param running true while another look is in flight
     * @param enabled whether the player left the switch on
     * @return one of the six {@code BADGE_} constants
     */
    public static String badgeKey(State state, boolean running, boolean enabled) {
        if (!enabled) {
            return BADGE_OFF;
        }
        if (state.available()) {
            return BADGE_AVAILABLE;
        }
        if (state.failed()) {
            return BADGE_FAILED;
        }
        if (state.known() && state.current().isBlank()) {
            return BADGE_UNKNOWN;
        }
        if (!state.known() || running) {
            return BADGE_CHECKING;
        }
        return BADGE_CURRENT;
    }

    /**
     * The short key a player reads instead of the raw diagnostic.
     *
     * <p>{@link State#note()} is written for a bug report: it names each route and its
     * status. Pasting it into a label made a Chinese client show
     * {@code 检查失败：feed: http 403 (rations used up); ...}. The screens translate one of
     * these three instead, and the raw string still goes to the log (AUDIT-46-5).</p>
     *
     * @param state the look that failed, may be null
     * @return one of the three {@code REASON_} keys
     */
    public static String reasonKey(State state) {
        if (state == null) {
            return REASON_OTHER;
        }
        Reason reason = state.reason();
        // a state built without a reason (a hand-made fixture, an answer from before this
        // field existed) still gets an answer; it is only the fallback, not the mechanism
        return reasonKey(reason == null ? classify(state.note()) : reason);
    }

    /** The language key a look's reason is shown under. */
    public static String reasonKey(Reason reason) {
        if (reason == null) {
            return REASON_OTHER;
        }
        return switch (reason) {
            case RATE -> REASON_RATE;
            case OFFLINE -> REASON_OFFLINE;
            case TLS -> REASON_TLS;
            case OTHER -> REASON_OTHER;
        };
    }

    /**
     * Reads a diagnostic or a thrown exception as one of the four reasons.
     *
     * <p>Only used where no exception is at hand to classify directly - the three routes
     * answer "http 403" and "no entry" as statuses, not as throwables.</p>
     *
     * @param text a diagnostic line or {@code String.valueOf(throwable)}, may be null
     * @return the reason that text points at
     */
    public static Reason classify(String text) {
        String note = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (note.contains("pkix") || note.contains("ssl") || note.contains("certificate")
                || note.contains("handshake") || note.contains("cert path")) {
            return Reason.TLS;
        }
        if (note.contains("403") || note.contains("429") || note.contains("rations")) {
            return Reason.RATE;
        }
        if (note.contains("unknownhost") || note.contains("refused") || note.contains("timed out")
                || note.contains("timeout") || note.contains("connect")
                || note.contains("interrupted") || note.contains("unresolved")
                || note.contains("offline") || note.contains("unreachable")) {
            return Reason.OFFLINE;
        }
        return Reason.OTHER;
    }

    /**
     * What the last look found.
     *
     * @param current the version that was looking, as the jar reports it
     * @param latest  the tag of the newest published release, without a leading v
     * @param url     the page to open for that release
     * @param note    why the look failed, empty when it did not
     * @param at      when the answer arrived, in epoch milliseconds, zero before the first
     * @param reason  why the look failed, or null when it did not (or when the state was
     *                built by hand and {@link #reasonKey} has to read the note instead)
     */
    public record State(String current, String latest, String url, String note, long at, Reason reason) {

        /** An answer that names no reason; {@link #reasonKey} then classifies the note. */
        public State(String current, String latest, String url, String note, long at) {
            this(current, latest, url, note, at, null);
        }

        /** True when {@link #latest} is a later version than {@link #current}. */
        public boolean available() {
            return !latest.isEmpty() && newer(current, latest);
        }

        /** True when the last look ended in an error rather than an answer. */
        public boolean failed() {
            return !note.isEmpty();
        }

        /** True once any answer, good or bad, has arrived. */
        public boolean known() {
            return at > 0L;
        }
    }

    private static final State EMPTY = new State("", "", "", "", 0L);
    private static final AtomicReference<State> STATE = new AtomicReference<>(EMPTY);
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    /**
     * The two clients every look shares. Each of them keeps a selector thread of its own,
     * so building them per look left up to three threads behind until the next collection
     * (AUDIT-46-4). One pair for the process is enough; they differ only in whether the
     * third route's redirect is followed, because that route reads the {@code Location}.
     */
    private static final HttpClient FOLLOWING = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final HttpClient QUIET = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private UpdateCheck() {
    }

    /** The last answer, or an empty state before the first one arrived. */
    public static State state() {
        return STATE.get();
    }

    /** True while a look is in flight, so a button can say it is working. */
    public static boolean running() {
        return RUNNING.get();
    }

    /** Looks again unless an answer is newer than {@link #STALE_MS}. */
    public static void request(String current) {
        request(current, false);
    }

    /**
     * Waits a little for the first answer.
     *
     * <p>Mod Menu asks a mod once, on a worker thread, and draws the badge from that
     * one answer; a look that answers "still looking" is then never asked again. So a
     * caller that cannot try twice waits here for the reply instead.</p>
     *
     * @param millis how long to wait at most, in milliseconds
     * @return the state known now, which is still empty when the wait ran out
     */
    public static State awaitAnswer(long millis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, millis);
        State now = STATE.get();
        while (!now.known() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            now = STATE.get();
        }
        return now;
    }

    /**
     * Looks for the newest release in the background.
     *
     * @param current the version this jar runs, empty when it cannot be read
     * @param force   true to ignore a fresh answer and look again now
     */
    public static synchronized void request(String current, boolean force) {
        String mine = current == null ? "" : current.trim();
        State now = STATE.get();
        if (!force && now.known() && now.current().equals(mine)
                && System.currentTimeMillis() - now.at() < STALE_MS) {
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> fetch(mine), "smtcraft-update");
        worker.setDaemon(true);
        worker.start();
    }

    private static void fetch(String current) {
        String latest = "";
        String url = "";
        StringBuilder refused = new StringBuilder();
        // Named at the point of failure: a 403 is a status the loop can see, a certificate
        // error is a throwable only the catch can see, and reading either back out of the
        // note afterwards is how the two used to blur together (AUDIT-47-4).
        Reason reason = null;
        try {
            HttpClient http = FOLLOWING;
            // The feed is asked first: one small document, the tag and the page in the
            // first entry, and - unlike the REST endpoint - no rations per address. The
            // REST endpoint is limited to sixty anonymous calls an hour for everyone
            // behind the same address, which is what made the first release of this
            // check report "http 403" to players (AUDIT-45-7).
            HttpResponse<String> atom = http.send(get(URI.create(ATOM), current, "application/atom+xml"),
                    HttpResponse.BodyHandlers.ofString());
            if (atom.statusCode() == 200) {
                latest = cleanTag(atomTag(atom.body()));
                url = atomLink(atom.body());
                if (latest.isEmpty()) {
                    refused.append("feed: no entry; ");
                }
            } else {
                refused.append("feed: http ").append(atom.statusCode()).append("; ");
            }
            // Next the REST endpoint, which is what a project without a feed would need.
            if (latest.isEmpty()) {
                HttpResponse<String> api = http.send(get(URI.create(API), current, "application/vnd.github+json"),
                        HttpResponse.BodyHandlers.ofString());
                if (api.statusCode() == 200) {
                    latest = cleanTag(tagOf(api.body()));
                    url = releasePageOf(api.body());
                    if (latest.isEmpty()) {
                        refused.append("api: no tag_name; ");
                    }
                } else {
                    refused.append("api: ").append(describeStatus(api.statusCode(), api.body())).append("; ");
                }
            }
            // Last resort: the page that redirects to the newest tag.
            if (latest.isEmpty()) {
                HttpClient quiet = QUIET;
                HttpResponse<String> redirect = quiet.send(get(URI.create(LATEST), current, "text/html"),
                        HttpResponse.BodyHandlers.ofString());
                String location = redirect.headers().firstValue("location").orElse("");
                latest = cleanTag(tagInLocation(location));
                url = location.isEmpty() ? "" : location;
                if (latest.isEmpty()) {
                    refused.append("latest: http ").append(redirect.statusCode()).append("; ");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            refused.append("interrupted");
            reason = Reason.OFFLINE;
        } catch (Throwable failed) {
            String described = Errors.describe(failed);
            refused.append(described);
            reason = classify(described);
        }
        // The answer is published first and the latch released in a finally: a caller that
        // sees running=false then always sees the state that ended the look, and a Throwable
        // escaping STATE.set no longer leaves the latch set forever - which used to freeze
        // the badge on "checking" for the rest of the session (AUDIT-46-3).
        try {
            String note = latest.isEmpty() ? trimNote(refused.toString()) : "";
            // a status-only failure (403, 404, a page without a tag) has no throwable to
            // classify, so the note it produced is read once, here
            Reason why = note.isEmpty() ? null : reason == null ? classify(note) : reason;
            STATE.set(new State(current, latest, url.isEmpty() ? RELEASES : url, note,
                    System.currentTimeMillis(), why));
        } finally {
            RUNNING.set(false);
        }
    }

    private static HttpRequest get(URI uri, String current, String accept) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", accept)
                .header("User-Agent", "SMTCraft-" + (current.isEmpty() ? "unknown" : current))
                .GET()
                .build();
    }

    /** "http 403 (rationed)" instead of a bare number when the body says why. */
    private static String describeStatus(int status, String body) {
        String text = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (status == 403 && text.contains("rate limit")) {
            return "http 403 (rations used up)";
        }
        if (status == 404) {
            return "http 404 (no such release)";
        }
        return "http " + status;
    }

    private static String trimNote(String text) {
        String note = text.trim();
        while (note.endsWith(";")) {
            note = note.substring(0, note.length() - 1).trim();
        }
        return note.length() > 160 ? note.substring(0, 160) : note;
    }

    /**
     * The tag of the first entry in a releases feed, or "". The entry id ends with the
     * tag: {@code tag:github.com,2008:Repository/1466447196/v1.0.1}.
     */
    public static String atomTag(String xml) {
        String entry = firstEntry(xml);
        if (entry.isEmpty()) {
            return "";
        }
        String id = between(entry, "<id>", "</id>");
        int slash = id.lastIndexOf('/');
        return slash < 0 ? "" : id.substring(slash + 1).trim();
    }

    /** The page of the first entry in a releases feed, or "". */
    public static String atomLink(String xml) {
        String entry = firstEntry(xml);
        if (entry.isEmpty()) {
            return "";
        }
        int at = entry.indexOf("/releases/tag/");
        if (at < 0) {
            return "";
        }
        int start = entry.lastIndexOf('"', at);
        int end = entry.indexOf('"', at);
        if (start < 0 || end < 0 || end <= start) {
            return "";
        }
        return entry.substring(start + 1, end).trim();
    }

    /** The tag a "releases/latest" redirect points at, or "". */
    public static String tagInLocation(String location) {
        String text = location == null ? "" : location;
        int at = text.indexOf("/releases/tag/");
        if (at < 0) {
            return "";
        }
        String rest = text.substring(at + "/releases/tag/".length());
        int cut = rest.length();
        for (char stop : new char[] {'?', '#', '/'}) {
            int found = rest.indexOf(stop);
            if (found >= 0 && found < cut) {
                cut = found;
            }
        }
        return rest.substring(0, cut).trim();
    }

    private static String firstEntry(String xml) {
        if (xml == null) {
            return "";
        }
        int open = xml.indexOf("<entry>");
        if (open < 0) {
            return "";
        }
        int close = xml.indexOf("</entry>", open);
        return close < 0 ? "" : xml.substring(open, close);
    }

    private static String between(String text, String open, String close) {
        int at = text.indexOf(open);
        if (at < 0) {
            return "";
        }
        int end = text.indexOf(close, at + open.length());
        return end < 0 ? "" : text.substring(at + open.length(), end).trim();
    }

    /** "v1.0.3" or " 1.0.3 " to "1.0.3": the comparison wants the bare number. */
    public static String cleanTag(String tag) {
        String text = tag == null ? "" : tag.trim();
        while (!text.isEmpty() && (text.charAt(0) == 'v' || text.charAt(0) == 'V')) {
            text = text.substring(1).trim();
        }
        return text;
    }

    /** The first "tag_name" value in a release body, or "". */
    public static String tagOf(String json) {
        return valueOf(json, "\"tag_name\"");
    }

    /** The first "html_url" value in a release body, or "". */
    public static String releasePageOf(String json) {
        return valueOf(json, "\"html_url\"");
    }

    private static String valueOf(String json, String key) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        int at = json.indexOf(key);
        if (at < 0) {
            return "";
        }
        int colon = json.indexOf(':', at + key.length());
        if (colon < 0) {
            return "";
        }
        int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                out.append(json.charAt(++i));
            } else if (c == '"') {
                return out.toString().trim();
            } else {
                out.append(c);
            }
        }
        return "";
    }

    /** True when {@code latest} names a later build than {@code current}. */
    public static boolean newer(String current, String latest) {
        String mine = cleanTag(current);
        String theirs = cleanTag(latest);
        if (mine.isEmpty() || theirs.isEmpty()) {
            return false;
        }
        return compare(theirs, mine) > 0;
    }

    /**
     * Orders two version strings the way Fabric does: the number before a hyphen decides
     * first (dots split the parts, an all-digit part compares as a number, a missing part
     * counts as zero), then what follows the hyphen decides as a pre-release, and anything
     * after a plus is build metadata that never changes the order.
     *
     * <p>The earlier reading folded {@code -} and {@code +} into dots, so {@code 1.0.2-beta}
     * and {@code 1.0.2+build5} both sorted <em>above</em> {@code 1.0.2} - every player of a
     * release build would have been told a beta was newer (AUDIT-46-1). Semver has it the
     * other way round: a pre-release is older than its release, and build metadata is equal.</p>
     */
    public static int compare(String left, String right) {
        String mine = cleanTag(left).trim().toLowerCase(Locale.ROOT);
        String theirs = cleanTag(right).trim().toLowerCase(Locale.ROOT);
        int order = compareNumbers(base(mine), base(theirs));
        return order != 0 ? order : comparePreRelease(preRelease(mine), preRelease(theirs));
    }

    /** The release number: everything before a plus and a hyphen is metadata. */
    private static String base(String version) {
        String text = version;
        int plus = text.indexOf('+');
        if (plus >= 0) {
            text = text.substring(0, plus);
        }
        int dash = text.indexOf('-');
        return dash < 0 ? text : text.substring(0, dash);
    }

    /** What follows the first hyphen, up to a plus; empty for a finished release. */
    private static String preRelease(String version) {
        int plus = version.indexOf('+');
        String text = plus < 0 ? version : version.substring(0, plus);
        int dash = text.indexOf('-');
        return dash < 0 ? "" : text.substring(dash + 1);
    }

    private static int compareNumbers(String left, String right) {
        String[] mine = parts(left);
        String[] theirs = parts(right);
        int count = Math.max(mine.length, theirs.length);
        for (int i = 0; i < count; i++) {
            String a = i < mine.length ? mine[i] : "0";
            String b = i < theirs.length ? theirs[i] : "0";
            long na = number(a);
            long nb = number(b);
            int order = na >= 0L && nb >= 0L ? Long.compare(na, nb) : a.compareToIgnoreCase(b);
            if (order != 0) {
                return order < 0 ? -1 : 1;
            }
        }
        return 0;
    }

    /** A finished release outranks its own pre-releases; two of them order dot by dot. */
    private static int comparePreRelease(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return left.isEmpty() && right.isEmpty() ? 0 : left.isEmpty() ? 1 : -1;
        }
        return compareNumbers(left, right);
    }

    private static String[] parts(String version) {
        String text = version == null ? "" : version.trim();
        if (text.isEmpty()) {
            return new String[] {""};
        }
        return text.split("\\.");
    }

    private static long number(String part) {
        if (part.isEmpty() || part.length() > 18) {
            return -1L;
        }
        long value = 0L;
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < '0' || c > '9') {
                return -1L;
            }
            value = value * 10L + (c - '0');
        }
        return value;
    }
}
