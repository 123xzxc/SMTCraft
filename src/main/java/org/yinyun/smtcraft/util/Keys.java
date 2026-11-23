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
 * argument, 26.3 dropped it again, so the call is resolved by reflection instead of being baked
 * into the bytecode. And the numbering: {@code InputConstants.KEY_F7} is 296 on 26.1 (a GLFW key
 * code) and 64 on 26.3 (an SDL scancode), because 26.3 reads the SDL keyboard-state buffer
 * instead of asking GLFW.</p>
 *
 * <p>{@link #key} therefore answers with the value <em>this</em> build declares, read off the
 * class file by name. Hard-coding either numbering is what made Ctrl do nothing on every version
 * but one (AUDIT-28-1): the mod passed GLFW numbers to a build that indexes scancodes, which is not an
 * error, only a different key.</p>
 *
 * <p>One 26.1 rule shapes what this class may do on the start-up path: nothing there may
 * initialise {@code org.lwjgl.glfw.GLFW}, because initialising that class loads {@code glfw.dll}
 * and 26.1 checks the GLFW error queue <em>before</em> it calls {@code glfwInit}. Reading a field
 * of {@code InputConstants} is safe - its static initialiser only creates the "unknown" key - but
 * turning a key into its <em>display name</em> is not: {@code InputConstants$Type.lambda$static$0}
 * ends in {@code GLFW.glfwGetKeyName} (AUDIT-30-3). The startup line that prints the offset key
 * therefore runs from the first tick, not from the entrypoint.</p>
 */
public final class Keys {

    private static final Logger LOG = LoggerFactory.getLogger("smtcraft-keys");

    private static final Map<String, Integer> CODES = new ConcurrentHashMap<>();

    /** Latched once {@link #prepare()} has an answer, and never before it has one (AUDIT-30-6). */
    private static boolean prepared;
    private static MethodHandle keyDown;
    private static MethodHandle windowOf;
    private static boolean wantsWindow;
    /** Latched once the window overload has been tried, so a failure warns once (AUDIT-30-4). */
    private static boolean windowTried;
    private static boolean windowFailed;

    private Keys() {
    }

    /** True while the given key code is held, on every supported game version. */
    public static boolean isKeyDown(int key) {
        prepare();
        if (wantsWindow) resolveWindowOverload();
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
     *
     * <p>A miss is not remembered (AUDIT-30-5): caching the fallback would keep answering it even
     * after the field became readable, and a caller cannot tell a remembered miss from a hit.</p>
     */
    public static int key(String name, int fallback) {
        Integer cached = CODES.get(name);
        if (cached != null) return cached;
        try {
            int value = constants().getField(name).getInt(null);
            CODES.put(name, value);
            return value;
        } catch (Throwable missing) {
            LOG.debug("this build has no InputConstants.{} ({})", name, Errors.describe(missing));
            return fallback;
        }
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
        if (keyDown == null) {
            if (!wantsWindow) return "unavailable";
            // "deferred" means the lookup has not happened yet; once it has and failed, the
            // answer is that this build has no usable overload (AUDIT-30-2).
            return windowFailed ? "unavailable" : "isKeyDown(Window,int)/deferred";
        }
        return wantsWindow ? "isKeyDown(Window,int)" : (scancodes() ? "isKeyDown(int)/scancodes" : "isKeyDown(int)");
    }

    /**
     * Resolves every route this build has and answers {@link #mode()}.
     *
     * <p>For {@code ShimProbe} only: it is the one caller allowed to force the window overload
     * outside of a key press, which is what turns "this build has a usable overload" into a claim
     * the probe can fail on (AUDIT-30-2). Never call it from {@code onInitializeClient}.</p>
     */
    public static String resolveForProbe() {
        prepare();
        if (wantsWindow) resolveWindowOverload();
        return mode();
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
        if (prepared) return;
        try {
            keyDown = MethodHandles.lookup().findStatic(constants(), "isKeyDown",
                    MethodType.methodType(boolean.class, int.class));
        } catch (Throwable newer) {
            // 26.1 and 26.2 take the window as well - see resolveWindowOverload.
            wantsWindow = true;
        }
        prepared = true;
    }

    /**
     * Resolves the {@code isKeyDown(Window, int)} overload on its first use.
     *
     * <p>Deferred so the entrypoint stays free of lookups into client classes, and because a key
     * is only read while the player holds one - long after the window exists. The lookup itself
     * is not what loads {@code glfw.dll}: {@code Window}'s static initialiser only builds a
     * logger ({@code javap} on 26.1). What does load it is {@code Class.forName} on
     * {@code org.lwjgl.glfw.GLFW} with the default {@code initialise = true}, and any call that
     * runs {@code GLFW.glfwGetKeyName} - see the class comment.</p>
     *
     * <p>{@code Window} is loaded without initialising it anyway: a method handle needs the type,
     * not its static state, and on a classpath that lacks the LWJGL jars the initialiser is what
     * fails (it reaches {@code GLFWErrorCallbackI}).</p>
     */
    private static synchronized void resolveWindowOverload() {
        if (!wantsWindow || windowTried) return;
        windowTried = true;
        try {
            keyDown = MethodHandles.lookup().findStatic(constants(), "isKeyDown",
                    MethodType.methodType(boolean.class,
                            Class.forName("com.mojang.blaze3d.platform.Window", false,
                                    Keys.class.getClassLoader()), int.class));
        } catch (Throwable older) {
            windowFailed = true;
            LOG.warn("no usable InputConstants.isKeyDown on this version ({})", Errors.describe(older));
        }
    }

    private static Class<?> constants() throws ClassNotFoundException {
        return Class.forName("com.mojang.blaze3d.platform.InputConstants");
    }
}
