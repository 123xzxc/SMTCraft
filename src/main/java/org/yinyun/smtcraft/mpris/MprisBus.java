package org.yinyun.smtcraft.mpris;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yinyun.smtcraft.util.Errors;

/**
 * Minimal read-only binding to the session bus, through {@code libdbus-1}.
 *
 * <p>The mod ships no JNI and no D-Bus client library, so the handful of libdbus
 * entry points MPRIS reading needs are called through the FFM API. Only three
 * shapes are ever requested: a list of bus names, a property map
 * ({@code a{sv}}) and a single property. Writes and signals are deliberately
 * absent — the mod never controls a player.</p>
 *
 * <p>An instance owns one private session-bus connection and one confined arena,
 * so it must be created, used and closed on the same thread. Every call uses a
 * short-lived arena of its own, which keeps the strings libdbus hands back from
 * accumulating for as long as the connection lives.</p>
 */
public final class MprisBus implements AutoCloseable {

    private static final int DBUS_BUS_SESSION = 0;
    /** Per-call reply timeout; callers may pass a smaller one to fit a budget. */
    public static final int TIMEOUT_MS = 2_000;
    private static final int ITER_BYTES = 128;
    private static final int T_STRING = Wire.T_STRING;

    /** Which value a reply carries, so the caller can ask for the right type. */
    @FunctionalInterface
    private interface Reply<T> {
        T read(MemorySegment message, Arena arena) throws Throwable;
    }

    /** Loaded-once state: the library handles, or the reason they are missing. */
    private static final class Lib {
        static final String error;
        static final MethodHandle busGetPrivate;
        static final MethodHandle setExitOnDisconnect;
        static final MethodHandle connectionClose;
        static final MethodHandle connectionUnref;
        static final MethodHandle errorInit;
        static final MethodHandle errorIsSet;
        static final MethodHandle errorFree;
        static final MethodHandle newMethodCall;
        static final MethodHandle sendWithReply;
        static final MethodHandle messageUnref;
        static final MethodHandle iterInit;
        static final MethodHandle iterInitAppend;
        static final MethodHandle iterAppendBasic;
        static final MethodHandle iterArgType;
        static final MethodHandle iterGetBasic;
        static final MethodHandle iterNext;
        static final MethodHandle iterRecurse;

        static {
            MethodHandle getPrivate = null;
            MethodHandle exit = null;
            MethodHandle close = null;
            MethodHandle unref = null;
            MethodHandle eInit = null;
            MethodHandle eSet = null;
            MethodHandle eFree = null;
            MethodHandle newCall = null;
            MethodHandle send = null;
            MethodHandle mUnref = null;
            MethodHandle iInit = null;
            MethodHandle iAppend = null;
            MethodHandle iBasic = null;
            MethodHandle iType = null;
            MethodHandle iGet = null;
            MethodHandle iNext = null;
            MethodHandle iRecurse = null;
            String why = null;
            Linker linker = Linker.nativeLinker();
            SymbolLookup lib = null;
            String[] candidates = {"libdbus-1.so.3", "libdbus-1.so", "libdbus-1.so.0"};
            for (String candidate : candidates) {
                try {
                    lib = SymbolLookup.libraryLookup(candidate, Arena.global());
                    break;
                } catch (Throwable ignored) {
                    // try the next soname; distributions differ
                }
            }
            if (lib == null) {
                why = "libdbus-1 is not installed (package: dbus / libdbus-1-3)";
            } else if (!nativeAccess()) {
                // JEP 472: without this flag every restricted downcall throws and
                // the message would otherwise read like a missing library
                why = "JVM started without --enable-native-access=ALL-UNNAMED, so libdbus-1"
                        + " cannot be called (add -J--enable-native-access=ALL-UNNAMED to the"
                        + " launcher JVM arguments)";
            } else {
                try {
                    final SymbolLookup lookup = lib;
                    // Descriptors and header prototypes live in DbusSymbols, one entry
                    // per symbol, and are cross-checked there on class initialisation —
                    // which runs on Windows too, unlike anything inside this branch
                    // (R23-3).
                    getPrivate = down(linker, lookup, "dbus_bus_get_private");
                    exit = down(linker, lookup, "dbus_connection_set_exit_on_disconnect");
                    close = down(linker, lookup, "dbus_connection_close");
                    unref = down(linker, lookup, "dbus_connection_unref");
                    eInit = down(linker, lookup, "dbus_error_init");
                    eSet = down(linker, lookup, "dbus_error_is_set");
                    eFree = down(linker, lookup, "dbus_error_free");
                    newCall = down(linker, lookup, "dbus_message_new_method_call");
                    send = down(linker, lookup, "dbus_connection_send_with_reply_and_block");
                    mUnref = down(linker, lookup, "dbus_message_unref");
                    iInit = down(linker, lookup, "dbus_message_iter_init");
                    iAppend = down(linker, lookup, "dbus_message_iter_init_append");
                    iBasic = down(linker, lookup, "dbus_message_iter_append_basic");
                    iType = down(linker, lookup, "dbus_message_iter_get_arg_type");
                    iGet = down(linker, lookup, "dbus_message_iter_get_basic");
                    iNext = down(linker, lookup, "dbus_message_iter_next");
                    iRecurse = down(linker, lookup, "dbus_message_iter_recurse");
                } catch (IllegalArgumentException wrong) {
                    // the literal in down() is not in the table: a typo here, not a
                    // distribution problem (R24-2)
                    why = "MprisBus asks for a name DbusSymbols does not declare: "
                            + Errors.describe(wrong);
                } catch (UnsatisfiedLinkError missing) {
                    why = "libdbus-1 is loaded but lacks this symbol: " + missing.getMessage();
                } catch (IllegalStateException shape) {
                    why = "libdbus-1 has a symbol whose shape differs from the table: "
                            + Errors.describe(shape);
                } catch (IllegalCallerException denied) {
                    why = "JVM started without --enable-native-access=ALL-UNNAMED, so libdbus-1"
                            + " cannot be called (add -J--enable-native-access=ALL-UNNAMED to the"
                            + " launcher JVM arguments)";
                } catch (Throwable other) {
                    why = "libdbus-1 failed to initialise: " + Errors.describe(other);
                }
            }
            error = why;
            busGetPrivate = getPrivate;
            setExitOnDisconnect = exit;
            connectionClose = close;
            connectionUnref = unref;
            errorInit = eInit;
            errorIsSet = eSet;
            errorFree = eFree;
            newMethodCall = newCall;
            sendWithReply = send;
            messageUnref = mUnref;
            iterInit = iInit;
            iterInitAppend = iAppend;
            iterAppendBasic = iBasic;
            iterArgType = iType;
            iterGetBasic = iGet;
            iterNext = iNext;
            iterRecurse = iRecurse;
        }

        /**
         * Whether FFM restricted methods are permitted in this JVM. Found by
         * calling one: {@code MemorySegment.ofAddress} is on the restricted list
         * (JEP 472) and throws {@link IllegalCallerException} when the launcher
         * did not pass {@code --enable-native-access}.
         */
        private static boolean nativeAccess() {
            try {
                MemorySegment.ofAddress(0L);
                return true;
            } catch (IllegalCallerException denied) {
                return false;
            }
            // No catch-all on purpose (R24-3): anything else that escapes here is a
            // real failure, and calling it "native access granted" only moved the
            // report to the symbol loop, which then said "a symbol is missing".
        }

        /**
         * Links one entry point, taking its descriptor from {@link DbusSymbols} and
         * comparing the handle it produces with the prototype declared beside it.
         *
         * <p>Both halves are needed. The table is cross-checked when
         * {@link DbusSymbols} initialises, which happens with or without libdbus
         * (R23-3); this comparison then catches a name whose descriptor and prototype
         * disagree at the point the linker actually builds the handle.</p>
         */
        private static MethodHandle down(Linker linker, SymbolLookup lib, String name) {
            DbusSymbols.Sym sym = DbusSymbols.get(name);
            MethodHandle handle = linker.downcallHandle(lib.find(name).orElseThrow(
                    () -> new UnsatisfiedLinkError(name)), sym.descriptor());
            if (!handle.type().equals(sym.type())) {
                throw new IllegalStateException(name + " is " + handle.type()
                        + ", the header says " + sym.type());
            }
            return handle;
        }
    }

    /** Empty when libdbus could be loaded, otherwise why it could not. */
    public static String loadError() {
        return Lib.error == null ? "" : Lib.error;
    }

    public static boolean available() {
        return Lib.error == null;
    }

    private final Arena arena;
    private final MemorySegment error;
    private MemorySegment connection = MemorySegment.NULL;
    /** Last failure of the current round, whatever kind it was (R7-3). */
    private String lastError = "";
    /**
     * First failure of the round that came from the connection, not the peer.
     *
     * <p>The two mean opposite things to the sampler: a dead bus has to be
     * reopened, a player answering {@code UnknownProperty} is healthy. Keeping
     * them apart matters because {@code exchange} used to clear {@link #lastError}
     * on every call, so the first failure in a round vanished as soon as a later
     * call succeeded (R7-3).</p>
     */
    private String lastFatal = "";

    public MprisBus() {
        if (Lib.error != null) throw new IllegalStateException(Lib.error);
        this.arena = Arena.ofConfined();
        this.error = arena.allocate(64L, 8L);
        try {
            Lib.errorInit.invokeExact(error);
            MemorySegment conn = (MemorySegment) Lib.busGetPrivate.invokeExact(DBUS_BUS_SESSION, error);
            if (conn.equals(MemorySegment.NULL)) {
                String why = readError();
                arena.close();
                throw new IllegalStateException("cannot open the session bus"
                        + (why.isEmpty() ? "" : ": " + why));
            }
            connection = conn;
            // a bus that goes away must not take the game down with it
            Lib.setExitOnDisconnect.invokeExact(connection, 0);
        } catch (Throwable t) {
            if (!arena.scope().isAlive()) throw asState(t);
            try {
                arena.close();
            } catch (Throwable ignored) {
                // already unwinding
            }
            throw asState(t);
        }
    }

    public String lastError() {
        return lastError;
    }

    /** Whether this round failed because the bus misbehaved, not because of an answer. */
    public boolean transportFailed() {
        return !lastFatal.isEmpty();
    }

    /**
     * Starts a new round: clears both error slots.
     *
     * <p>Called once per round by the sampler instead of on every call, so a
     * failure early in the round survives the reads that follow it (R7-3).</p>
     */
    public void clearError() {
        lastError = "";
        lastFatal = "";
    }

    /** Every name on the session bus; empty on failure. */
    public List<String> listNames() {
        List<String> names = exchange("org.freedesktop.DBus", "/org/freedesktop/DBus",
                "org.freedesktop.DBus", "ListNames", List.of(), TIMEOUT_MS,
                (message, call) -> stringArray(message, call));
        return names == null ? List.of() : names;
    }

    /** {@code org.freedesktop.DBus.Properties.GetAll} for one interface. */
    public Map<String, Object> getAll(String busName, String iface) {
        return getAll(busName, iface, TIMEOUT_MS);
    }

    /**
     * {@code GetAll} with an explicit reply timeout.
     *
     * <p>The sampler passes what is left of its probe budget, so one player that
     * stopped answering cannot consume the whole round (R6-2).</p>
     */
    public Map<String, Object> getAll(String busName, String iface, int timeoutMs) {
        Map<String, Object> all = exchange(busName, "/org/mpris/MediaPlayer2",
                "org.freedesktop.DBus.Properties", "GetAll", List.of(iface), timeoutMs,
                (message, call) -> variantMap(message, call));
        return all == null ? Map.of() : all;
    }

    /** One property, unwrapped from its variant; null when the reply is empty. */
    public Object getProperty(String busName, String iface, String property) {
        return getProperty(busName, iface, property, TIMEOUT_MS);
    }

    /** One property with an explicit reply timeout (R6-2). */
    public Object getProperty(String busName, String iface, String property, int timeoutMs) {
        return exchange(busName, "/org/mpris/MediaPlayer2",
                "org.freedesktop.DBus.Properties", "Get", List.of(iface, property), timeoutMs,
                (message, call) -> firstVariant(message, call));
    }

    @Override
    public void close() {
        MemorySegment conn = connection;
        connection = MemorySegment.NULL;
        if (!conn.equals(MemorySegment.NULL)) {
            try {
                Lib.connectionClose.invokeExact(conn);
            } catch (Throwable ignored) {
                // already disconnected
            }
            try {
                Lib.connectionUnref.invokeExact(conn);
            } catch (Throwable ignored) {
                // nothing left to release
            }
        }
        try {
            arena.close();
        } catch (Throwable ignored) {
            // closed twice
        }
    }

    // --- plumbing ----------------------------------------------------------

    private <T> T exchange(String busName, String path, String iface, String member,
                           List<String> args, int timeoutMs, Reply<T> reply) {
        if (connection.equals(MemorySegment.NULL)) {
            fail("bus is closed", true);
            return null;
        }
        try (Arena call = Arena.ofConfined()) {
            Lib.errorInit.invokeExact(error);
            MemorySegment message = (MemorySegment) Lib.newMethodCall.invokeExact(
                    utf8(call, busName), utf8(call, path), utf8(call, iface), utf8(call, member));
            if (message.equals(MemorySegment.NULL)) {
                fail("cannot build " + member, false);
                return null;
            }
            try {
                appendArgs(message, args, call);
                int timeout = timeoutMs > 0 ? timeoutMs : TIMEOUT_MS;
                MemorySegment answer = (MemorySegment) Lib.sendWithReply.invokeExact(
                        connection, message, timeout, error);
                if (answer.equals(MemorySegment.NULL)) {
                    String reason = readError();
                    fail(reason, !peerAnswered(reason));
                    return null;
                }
                try {
                    return reply.read(answer, call);
                } finally {
                    Lib.messageUnref.invokeExact(answer);
                }
            } finally {
                Lib.messageUnref.invokeExact(message);
            }
        } catch (IllegalCallerException e) {
            fail("restricted FFM call refused: the launcher must pass"
                    + " --enable-native-access=ALL-UNNAMED", false);
            return null;
        } catch (Throwable t) {
            fail(Errors.describe(t), true);
            return null;
        }
    }

    private void appendArgs(MemorySegment message, List<String> args, Arena call) throws Throwable {
        if (args.isEmpty()) return;
        MemorySegment iter = call.allocate(ITER_BYTES, 8L);
        Lib.iterInitAppend.invokeExact(message, iter);
        for (String arg : args) {
            MemorySegment ref = call.allocate(ValueLayout.ADDRESS);
            ref.set(ValueLayout.ADDRESS, 0L, utf8(call, arg));
            if ((int) Lib.iterAppendBasic.invokeExact(iter, T_STRING, ref) == 0) {
                throw new IllegalStateException("cannot append argument");
            }
        }
    }

    private List<String> stringArray(MemorySegment message, Arena call) {
        return Wire.strings(cursor(message, call));
    }

    private Map<String, Object> variantMap(MemorySegment message, Arena call) {
        return Wire.properties(cursor(message, call));
    }

    private Object firstVariant(MemorySegment message, Arena call) {
        return Wire.single(cursor(message, call));
    }

    /** Cursor over a reply body; {@link Wire.Cursor#none()} if it will not start. */
    private static Wire.Cursor cursor(MemorySegment message, Arena call) {
        MemorySegment iter = call.allocate(ITER_BYTES, 8L);
        try {
            if ((int) Lib.iterInit.invokeExact(message, iter) == 0) return Wire.Cursor.none();
        } catch (Throwable t) {
            return Wire.Cursor.none();
        }
        return new SegCursor(iter, call);
    }

    /** {@link Wire.Cursor} over a live {@code DBusMessageIter}. */
    private static final class SegCursor implements Wire.Cursor {
        private final MemorySegment iter;
        private final Arena arena;

        SegCursor(MemorySegment iter, Arena arena) {
            this.iter = iter;
            this.arena = arena;
        }

        @Override
        public int type() {
            try {
                return (int) Lib.iterArgType.invokeExact(iter);
            } catch (Throwable t) {
                return Wire.DONE;
            }
        }

        @Override
        public Wire.Cursor recurse() {
            MemorySegment sub = arena.allocate(ITER_BYTES, 8L);
            try {
                Lib.iterRecurse.invokeExact(iter, sub);
            } catch (Throwable t) {
                return Wire.Cursor.none();
            }
            return new SegCursor(sub, arena);
        }

        @Override
        public boolean next() {
            try {
                return (int) Lib.iterNext.invokeExact(iter) != 0;
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public String text() {
            MemorySegment holder = arena.allocate(ValueLayout.ADDRESS);
            try {
                Lib.iterGetBasic.invokeExact(iter, holder);
            } catch (Throwable t) {
                return "";
            }
            MemorySegment pointer = holder.get(ValueLayout.ADDRESS, 0L);
            if (pointer.equals(MemorySegment.NULL)) return "";
            // libdbus keeps the bytes alive for as long as the reply is open
            return pointer.reinterpret(Long.MAX_VALUE).getString(0L);
        }

        @Override
        public long integral() {
            int type = type();
            // a 32-bit getter writes four bytes: giving it an eight-byte holder
            // would let libdbus scribble past the value
            if (type == Wire.T_INT32 || type == Wire.T_UINT32) {
                MemorySegment word = arena.allocate(ValueLayout.JAVA_INT);
                try {
                    Lib.iterGetBasic.invokeExact(iter, word);
                } catch (Throwable t) {
                    return 0L;
                }
                return word.get(ValueLayout.JAVA_INT, 0L);
            }
            MemorySegment word = arena.allocate(ValueLayout.JAVA_LONG);
            try {
                Lib.iterGetBasic.invokeExact(iter, word);
            } catch (Throwable t) {
                return 0L;
            }
            return word.get(ValueLayout.JAVA_LONG, 0L);
        }

        @Override
        public double real() {
            MemorySegment word = arena.allocate(ValueLayout.JAVA_DOUBLE);
            try {
                Lib.iterGetBasic.invokeExact(iter, word);
            } catch (Throwable t) {
                return 0.0d;
            }
            return word.get(ValueLayout.JAVA_DOUBLE, 0L);
        }

        @Override
        public boolean bool() {
            MemorySegment word = arena.allocate(ValueLayout.JAVA_INT);
            try {
                Lib.iterGetBasic.invokeExact(iter, word);
            } catch (Throwable t) {
                return false;
            }
            return word.get(ValueLayout.JAVA_INT, 0L) != 0;
        }
    }

    private void fail(String reason, boolean transport) {
        lastError = reason == null ? "" : reason;
        if (transport && lastFatal.isEmpty()) lastFatal = lastError;
    }

    /**
     * Whether a D-Bus error came from the peer rather than from the connection.
     *
     * <p>{@code UnknownProperty} means the player answered and simply publishes no
     * such property, which is normal for {@code Position} on players that only
     * expose it inside {@code GetAll}. Counting those as transport failures made
     * the sampler drop and reopen the bus every five rounds. A timeout or a
     * closed connection is the opposite case and has to count.</p>
     */
    private static boolean peerAnswered(String reason) {
        if (reason == null || reason.isEmpty()) return false;
        return reason.contains("UnknownProperty") || reason.contains("UnknownInterface")
                || reason.contains("UnknownMethod") || reason.contains("UnknownObject")
                || reason.contains("InvalidArgs");
    }

    private String readError() {
        try {
            if ((int) Lib.errorIsSet.invokeExact(error) == 0) return "";
            MemorySegment namePointer = error.get(ValueLayout.ADDRESS, 0L);
            MemorySegment messagePointer = error.get(ValueLayout.ADDRESS, 8L);
            String name = namePointer.equals(MemorySegment.NULL) ? ""
                    : namePointer.reinterpret(Long.MAX_VALUE).getString(0L);
            String message = messagePointer.equals(MemorySegment.NULL) ? ""
                    : messagePointer.reinterpret(Long.MAX_VALUE).getString(0L);
            Lib.errorFree.invokeExact(error);
            String text = (name + " " + message).trim();
            return text.isEmpty() ? "" : text;
        } catch (Throwable t) {
            return "";
        }
    }

    private static MemorySegment utf8(Arena arena, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment segment = arena.allocate(bytes.length + 1L);
        MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0L, bytes.length);
        segment.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
        return segment;
    }

    /** Delegates, so "never print null" has exactly one implementation. */
    private static IllegalStateException asState(Throwable t) {
        return Errors.asState(t);
    }
}
