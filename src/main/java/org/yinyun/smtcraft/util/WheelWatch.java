package org.yinyun.smtcraft.util;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches mouse-wheel notches without consuming them, on both input backends.
 *
 * <p>26.3 moved the window layer from GLFW to SDL, and 26.1/26.2 do not ship
 * {@code org.lwjgl.sdl} at all, so a single hard reference to either one makes the mod
 * unloadable on the other ({@code NoClassDefFoundError: org/lwjgl/sdl/SDL_EventFilterI}
 * while the entrypoint class was still being verified). Everything here therefore goes
 * through {@code Class.forName} and {@code java.lang.reflect.Proxy}: the class loads on
 * every version, and only the branch that the running game can actually support is built.</p>
 *
 * <ul>
 *   <li>SDL (26.3+): an event watch registered with {@code SDL_AddEventWatch} — the
 *       callback returns true, so the game still sees the wheel. The call reports success
 *       through its return value and can also refuse by returning false, so both outcomes
 *       are checked before the route is called live (AUDIT-28-2). There is deliberately no
 *       fallback to {@code SDL_SetEventFilter}: SDL3 has one global filter, and taking it
 *       would displace whatever the game registered, permanently and invisibly (AUDIT-28-4).</li>
 *   <li>GLFW (26.1/26.2): the scroll callback is replaced, and every notch in both axes is
 *       handed straight back to {@code MouseHandler.onScroll}, which is what the callback
 *       would have called anyway. The replacement is re-asserted from {@link #keepAlive()}
 *       because the game installs its own callback during window setup, and an install that
 *       lands before that would otherwise be silently dropped.</li>
 * </ul>
 *
 * <p>A failed attempt is not final: both backends can be unready at client start-up, so the
 * caller retries, and the reason is kept for the report it ends with.</p>
 */
public final class WheelWatch {

    private static final Logger LOG = LoggerFactory.getLogger("smtcraft-wheel");

    /** Receives one notch: positive is a scroll up. */
    public interface Notch {
        void turned(double steps);
    }

    private static boolean resolved;
    private static String mode = "unresolved";
    /** Which route is live once installed: {@code sdl-watch} or {@code glfw}. */
    private static String route = "";
    /** Kept in a field on purpose: the native side holds a raw pointer to it. */
    private static Object installed;
    /** Why the last attempt did not take; the caller prints it when it gives up. */
    private static String reason = "not attempted yet";
    /** Failed attempts; only the first is logged, the last one is the caller's warning. */
    private static int failures;
    /** Forwarding attempts that the game's handler rejected; only the first is logged. */
    private static int forwards;
    private static Object glfwCallback;
    private static long glfwWindow;
    private static int keepAliveCalls;
    /** Re-registrations that threw after a live install; only the first one is logged (AUDIT-31-3). */
    private static int keepAliveFailures;

    private WheelWatch() {
    }

    /**
     * What {@link #resolve()} decided - {@code "sdl"}, {@code "glfw"}, {@code "unavailable"} - or
     * {@code "unresolved"} while no attempt has been made this session.
     *
     * <p>Whether the watch is <em>installed</em> is not answered here: that is the return value of
     * {@link #install(BooleanSupplier, Notch)}, and a route that resolves cleanly can still fail to
     * install (no window yet, no event loop). {@code unresolved} means exactly one thing - nobody
     * has asked yet - which is the state a bug report cannot otherwise tell apart from "this build
     * has no backend". Resolving the route is safe at any point -
     * {@link #resolve()} only calls {@code Class.forName(..., false, loader)}, which runs no
     * static initialiser and loads no native library - so the probe does exactly that through
     * {@link #resolveForProbe()}. What is <em>not</em> safe in the entrypoint is
     * {@link #installGlfw}: it initialises {@code org.lwjgl.glfw.GLFW} on purpose, which loads
     * {@code glfw.dll}, and 26.1 then reports {@code GLFW error before init: [0x10001]} because
     * {@code GLX._initGlfw} inspects the error queue before its own {@code glfwInit} (AUDIT-31-1).
     */
    public static synchronized String mode() {
        return mode;
    }

    /** Which route ended up live, for the startup log and bug reports. */
    public static synchronized String route() {
        return route;
    }

    /** Why the last install attempt failed, for the "never installed" warning. */
    public static synchronized String reason() {
        return reason;
    }

    /**
     * Resolves the backend choice and answers {@link #mode()}, without installing anything.
     *
     * <p>For {@code ShimProbe} only. "Which backend does this class path offer" was the one
     * compatibility decision with no automated check at all: the probe used to forbid resolving
     * it, on the belief that resolving loads GLFW - which {@link #resolve()} contradicts by using
     * {@code Class.forName(..., false, loader)} (AUDIT-31-1). Installing is the part that needs a
     * window and the native library; that half stays on the instances.</p>
     */
    public static synchronized String resolveForProbe() {
        if (!resolved) resolve();
        return mode;
    }

    /**
     * Tries to install the watch. Returns true once a notch source is in place; false means
     * "not yet", and the caller may try again on a later tick.
     */
    public static synchronized boolean install(BooleanSupplier armed, Notch notch) {
        if (installed != null) return true;
        if (!resolved) resolve();
        boolean ok = switch (mode) {
            case "sdl" -> installSdl(armed, notch);
            case "glfw" -> installGlfw(armed, notch);
            default -> false;
        };
        if (!ok && failures++ == 0) LOG.info("mouse wheel watch not ready yet ({})", reason);
        return ok;
    }

    /**
     * Per-tick upkeep. Only GLFW needs it: {@code MouseHandler.setup} registers the game's own
     * scroll callback, and on 26.1/26.2 that happened after the first install attempt often
     * enough that the watch was replaced without a word — F7 plus the wheel then did nothing
     * and nothing said why.
     */
    public static synchronized void keepAlive() {
        if (installed == null || !"glfw".equals(route)) return;
        // once a second is plenty for a callback that only moves on window setup
        if (++keepAliveCalls % 20 != 0) return;
        try {
            // initialise = false does not keep the library unloaded: a reflective static call
            // initialises the class anyway, and GLFW's initialiser is what loads glfw.dll. It is
            // harmless here only because installGlfw already did that - which is exactly why this
            // runs after a live install and never before one (AUDIT-31-2).
            ClassLoader loader = WheelWatch.class.getClassLoader();
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW", false, loader);
            Class<?> callbackType = Class.forName("org.lwjgl.glfw.GLFWScrollCallbackI", false, loader);
            glfw.getMethod("glfwSetScrollCallback", long.class, callbackType)
                    .invoke(null, glfwWindow, glfwCallback);
        } catch (Throwable failed) {
            reason = "glfwSetScrollCallback: " + Errors.describe(failed);
            // reason's only reader is the "never installed" warning, and that one cannot fire once
            // installed is set: a callback that can no longer be put back would otherwise be
            // silent everywhere - the player sees "F7 plus the wheel does nothing" and the log
            // says nothing (AUDIT-31-3)
            if (keepAliveFailures++ == 0) {
                LOG.warn("the game replaced the scroll callback and it could not be put back ({})",
                        Errors.describe(failed));
            }
        }
    }

    private static void resolve() {
        resolved = true;
        // the interface is what the proxy implements; SDL_EventFilter is the pointer carrier
        if (loadable("org.lwjgl.sdl.SDLEvents") && loadable("org.lwjgl.sdl.SDL_EventFilterI")) {
            mode = "sdl";
        } else if (loadable("org.lwjgl.glfw.GLFW") && loadable("org.lwjgl.glfw.GLFWScrollCallbackI")) {
            mode = "glfw";
        } else {
            mode = "unavailable";
            reason = "neither lwjgl-sdl nor lwjgl-glfw is on the classpath";
        }
    }

    private static boolean loadable(String name) {
        try {
            Class.forName(name, false, WheelWatch.class.getClassLoader());
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    private static boolean installSdl(BooleanSupplier armed, Notch notch) {
        Class<?> events;
        Class<?> filterType;
        Class<?> event;
        int wheelEvent;
        Method type;
        Method wheel;
        try {
            events = Class.forName("org.lwjgl.sdl.SDLEvents");
            filterType = Class.forName("org.lwjgl.sdl.SDL_EventFilterI");
            event = Class.forName("org.lwjgl.sdl.SDL_Event");
            wheelEvent = events.getField("SDL_EVENT_MOUSE_WHEEL").getInt(null);
            type = event.getMethod("ntype", long.class);
            wheel = event.getMethod("nwheel", long.class);
        } catch (Throwable missing) {
            reason = "the SDL event API is not what this build expects: " + Errors.describe(missing);
            return false;
        }

        InvocationHandler handler = (proxy, method, args) -> {
            if (!"invoke".equals(method.getName()) || args == null || args.length < 2) {
                return reply(proxy, method, args);
            }
            try {
                if (armed.getAsBoolean() && ((Integer) type.invoke(null, args[1])) == wheelEvent) {
                    Object data = wheel.invoke(null, args[1]);
                    float dy = (Float) data.getClass().getMethod("y").invoke(data);
                    if (dy != 0.0f) notch.turned(dy);
                }
            } catch (Throwable ignored) {
                // a broken notch must never break the game's own event pump
            }
            return Boolean.TRUE;
        };
        Object filter;
        try {
            filter = Proxy.newProxyInstance(
                    WheelWatch.class.getClassLoader(), new Class<?>[] {filterType}, handler);
        } catch (Throwable failed) {
            reason = "the SDL filter proxy could not be built: " + Errors.describe(failed);
            return false;
        }

        try {
            Object added = events.getMethod("SDL_AddEventWatch", filterType, long.class)
                    .invoke(null, filter, 0L);
            // SDL answers false when it refuses, without throwing: treating that as success
            // left the route marked live with no event ever arriving (AUDIT-28-2)
            if (Boolean.FALSE.equals(added)) {
                reason = "SDL_AddEventWatch returned false";
                return false;
            }
            installed = filter;
            route = "sdl-watch";
            LOG.debug("mouse wheel watch: SDL event watch");
            return true;
        } catch (Throwable watchFailed) {
            // a build whose event subsystem is not up yet refuses the watch; the caller
            // retries, and this is the line that ends up in a bug report
            reason = "SDL_AddEventWatch: " + Errors.describe(watchFailed);
            return false;
        }
    }

    private static boolean installGlfw(BooleanSupplier armed, Notch notch) {
        try {
            // The window comes first, on purpose. Loading the GLFW class loads its native
            // library, and doing that before the game calls glfwInit leaves 26.1 refusing to
            // start: GLX._initGlfw checks the error queue *before* it initialises anything, so
            // a stale "GLFW library is not initialized" from our early call becomes
            // "GLFW error before init: [0x10001]" and the launcher never gets a window.
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                reason = "Minecraft.getInstance() is null";
                return false;
            }
            if (minecraft.getWindow() == null) {
                reason = "the window does not exist yet";
                return false;
            }
            if (minecraft.mouseHandler == null) {
                reason = "the mouse handler does not exist yet";
                return false;
            }
            long handle = minecraft.getWindow().handle();
            if (handle == 0L) {
                reason = "the window handle is not ready yet";
                return false;
            }
            Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
            Class<?> callbackType = Class.forName("org.lwjgl.glfw.GLFWScrollCallbackI");

            Method onScroll = minecraft.mouseHandler.getClass()
                    .getDeclaredMethod("onScroll", long.class, double.class, double.class);
            onScroll.setAccessible(true);

            InvocationHandler handler = (proxy, method, args) -> {
                if ("invoke".equals(method.getName()) && args != null && args.length == 3) {
                    double dy = (Double) args[2];
                    // Our notch is taken first, and it must never cost the game its scroll - the
                    // same rule as the SDL side, where a broken notch is swallowed inside the event
                    // pump. The forwarding below is then unconditional, armed or not (AUDIT-31-4).
                    try {
                        if (armed.getAsBoolean() && dy != 0.0) notch.turned(dy);
                    } catch (Throwable ignored) {
                        // see above: our arithmetic is the expendable half
                    }
                    try {
                        onScroll.invoke(minecraft.mouseHandler, args[0], args[1], args[2]);
                    } catch (Throwable failed) {
                        // a lost scroll is bad, a crash is worse, and a silent one is worst:
                        // say it once so a report can name the reason
                        if (forwards++ == 0) {
                            LOG.warn("the game's own scroll handler rejected a notch ({})",
                                    Errors.describe(failed));
                        }
                    }
                    return null;
                }
                return reply(proxy, method, args);
            };
            Object callback = Proxy.newProxyInstance(
                    WheelWatch.class.getClassLoader(), new Class<?>[] {callbackType}, handler);
            glfw.getMethod("glfwSetScrollCallback", long.class, callbackType).invoke(null, handle, callback);
            installed = callback;
            glfwCallback = callback;
            glfwWindow = handle;
            route = "glfw";
            LOG.debug("mouse wheel watch: GLFW scroll callback (forwarding)");
            return true;
        } catch (Throwable failed) {
            reason = Errors.describe(failed);
            return false;
        }
    }

    /**
     * Answers everything on the proxy that is not {@code invoke} itself.
     *
     * <p>The one method that matters is {@code getDescriptor()}: a LWJGL callback interface
     * extends {@code CallbackI} and carries a {@code default} descriptor accessor that the
     * native bridge calls to learn the callback's signature. Returning {@code null} there —
     * which is what a proxy handler does by default — makes the bridge throw
     * {@code NullPointerException} from inside {@code SDL_AddEventWatch}, and the caller only
     * sees an {@code InvocationTargetException} with no message. That is exactly how the SDL
     * watch "failed to install" on 26.4 while the same code worked when the interface was
     * implemented by a real lambda.</p>
     */
    private static Object reply(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) return defaultOf(method);
        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
        return null;
    }

    /** Object methods on a proxy do not reach the game, so answer them here. */
    private static Object defaultOf(Method method) {
        return switch (method.getName()) {
            case "toString" -> "smtcraft wheel watch";
            case "hashCode" -> System.identityHashCode(method);
            case "equals" -> Boolean.FALSE;
            default -> null;
        };
    }
}
