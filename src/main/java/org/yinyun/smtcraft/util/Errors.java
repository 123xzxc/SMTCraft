package org.yinyun.smtcraft.util;

/**
 * One description of a throwable for every log line in the mod.
 *
 * <p>This helper existed twice, byte for byte, in {@code SmtcBridge} and
 * {@code MprisBridge}. The duplication was harmless; what it caused was not: the
 * client wanted one log line during shutdown and ended up importing
 * {@code smtc.SmtcBridge}, the Windows/WinRT implementation — through the one
 * layer that this project built to avoid exactly that. The platform abstraction
 * only means something if callers reach it through the interface, so the helper
 * moved here, where both sides can use it without knowing about each other
 * (R15-3).</p>
 *
 * <p>Pure: no Minecraft, no platform, so the offline self test can assert it.</p>
 */
public final class Errors {
    private Errors() {
    }

    /**
     * Class name plus message, tolerating exceptions that carry no message at
     * all — {@code NullPointerException} from a {@code throw new NPE()} and
     * several JVM-generated ones do not, and {@code "... null"} in a log line is
     * worse than nothing.
     */
    public static String describe(Throwable t) {
        if (t == null) return "none";
        t = unwrap(t);
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }
    /**
     * Reflection hands back the real failure one layer down: every {@code Method.invoke}
     * wraps it in an {@code InvocationTargetException} whose own message is the inner
     * class name at best, and a static initializer that throws arrives as an
     * {@code ExceptionInInitializerError} whose message is {@code null}. Describing the
     * wrapper is how a log line ends up saying "InvocationTargetException" and nothing
     * else — which is exactly what the SDL wheel watch reported on 26.4.
     */
    private static Throwable unwrap(Throwable t) {
        Throwable current = t;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (current instanceof java.lang.reflect.InvocationTargetException wrapper
                    && wrapper.getTargetException() != null) {
                current = wrapper.getTargetException();
            } else if (current instanceof ExceptionInInitializerError init && init.getCause() != null) {
                current = init.getCause();
            } else {
                return current;
            }
        }
        return current;
    }

    /**
     * The same description, wrapped in an exception so it can cross a layer that
     * only declares {@code IllegalStateException}. One that already is one is
     * returned untouched — wrapping it again stacks a second identical message on
     * top of the first (R16-1).
     */
    public static IllegalStateException asState(Throwable t) {
        return t instanceof IllegalStateException state ? state
                : new IllegalStateException(describe(t), t);
    }
}
