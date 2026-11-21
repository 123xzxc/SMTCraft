package org.yinyun.smtcraft.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads keyboard state and keyboard codes through whichever {@code InputConstants} this game
 * build ships.
 *
 * <p>Two things change across 26.1 - 26.4. The method: 26.1 and 26.2 take the window as the first
 * argument, 26.3 dropped it again, so the call is resolved once by reflection instead of being
 * baked into the bytecode. And the numbering: {@code InputConstants.KEY_F7} is 296 on 26.1 (a
 * GLFW key code) and 64 on 26.3 (an SDL scancode), because 26.3 reads the SDL keyboard-state
 * buffer instead of asking GLFW.</p>
 *
 * <p>{@link #key} therefore answers with the value <em>this</em> build declares, read off the
 * class file by name. Hard-coding either numbering is what made Ctrl do nothing on every version
 * but one (R28-1): the mod passed GLFW numbers to a build that indexes scancodes, which is not an
 * error, only a different key.</p>
 */
public final class Keys {

    private static final Logger LOG = LoggerFactory.getLogger("smtcraft-keys");

    private static final Map<String, Integer> CODES = new ConcurrentHashMap<>();

    private static boolean ready;
    private static MethodHandle keyDown;
    private static MethodHandle windowOf;
    private static boolean wantsWindow;

    private Keys() {
    }

    /** True while the given key code is held, on every supported game version. */
    public static boolean isKeyDown(int key) {
        prepare();
        if (keyDown == null) return false;
        try {
            if (wantsWindow) return (boolean) keyDown.invoke(window(), key);
            return (boolean) keyDown.invoke(key);
        } catch (Throwable failed) {
            LOG.debug("cannot read the key state: {}", Errors.describe(failed));
            return false;
        }
    }

    /**
     * The value this build gives the {@code InputConstants} constant called {@code name}, or
     * {@code fallback} when the build does not declare it.
     *
     * <p>Read through reflection on purpose: the field is {@code static final int}, so a compiled
     * reference would freeze the number of the version the jar was built against - the exact
     * mistake R28-1 caught.</p>
     */
    public static int key(String name, int fallback) {
        Integer cached = CODES.get(name);
        if (cached != null) return cached;
        int value = fallback;
        try {
            value = constants().getField(name).getInt(null);
        } catch (Throwable missing) {
            LOG.debug("this build has no InputConstants.{} ({})", name, Errors.describe(missing));
        }
        CODES.put(name, value);
        return value;
    }

    /**
     * True when the numbers handed to {@link #isKeyDown} are SDL scancodes rather than GLFW key
     * codes. Only diagnostics read this; {@link #key} already answers in the local numbering.
     */
    public static boolean scancodes() {
        prepare();
        return keyDown != null && !wantsWindow;
    }

    /** Which overload this game build uses, for the startup log and for bug reports. */
    public static String mode() {
        prepare();
        if (keyDown == null) return "unavailable";
        return wantsWindow ? "isKeyDown(Window,int)" : (scancodes() ? "isKeyDown(int)/scancodes" : "isKeyDown(int)");
    }

    private static Object window() throws Throwable {
        MethodHandle get = windowOf;
        if (get == null) {
            Object minecraft = Minecraft.getInstance();
            if (minecraft == null) throw new IllegalStateException("the client is not there yet");
            get = MethodHandles.publicLookup().unreflect(minecraft.getClass().getMethod("getWindow"));
            windowOf = get;
        }
        Object window = get.invoke(Minecraft.getInstance());
        if (window == null) throw new IllegalStateException("the window is not there yet");
        return window;
    }

    private static synchronized void prepare() {
        if (ready) return;
        ready = true;
        try {
            MethodHandle one = MethodHandles.lookup().findStatic(constants(), "isKeyDown",
                    MethodType.methodType(boolean.class, int.class));
            keyDown = one;
            return;
        } catch (Throwable newer) {
            // 26.1 and 26.2 want the window as well
        }
        try {
            MethodHandle two = MethodHandles.lookup().findStatic(constants(), "isKeyDown",
                    MethodType.methodType(boolean.class, Class.forName("com.mojang.blaze3d.platform.Window"),
                            int.class));
            keyDown = two;
            wantsWindow = true;
        } catch (Throwable older) {
            LOG.warn("no usable InputConstants.isKeyDown on this version ({})", Errors.describe(older));
        }
    }

    private static Class<?> constants() throws ClassNotFoundException {
        return Class.forName("com.mojang.blaze3d.platform.InputConstants");
    }
}
