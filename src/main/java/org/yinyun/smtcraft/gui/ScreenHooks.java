package org.yinyun.smtcraft.gui;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yinyun.smtcraft.util.Errors;

/**
 * Calls {@code super.extractRenderState} on whatever signature this game build declares.
 *
 * <p>26.1 up to 26.4-snapshot-4 pass the pointer and the partial tick with every frame
 * ({@code (extractor, int, int, float)}); 26.4-snapshot-5 dropped them and the extractor answers
 * {@link #mouseX} and friends instead. A screen cannot spell both {@code super} calls in one
 * source, so the call is resolved once with {@code findSpecial} - which is what {@code super}
 * compiles to - and reused every frame.
 */
public final class ScreenHooks {

    private static final Logger LOG = LoggerFactory.getLogger("smtcraft-gui");

    private static boolean ready;
    private static MethodHandle superState;
    private static boolean takesCoords;

    /**
     * The widget render hook, taken from the {@link Renderable} interface rather than from the
     * widget: our widgets are private nested classes, and a public lookup cannot name them.
     * Interface dispatch still reaches the right implementation. Only one of the two exists on
     * any given version.
     */
    private static MethodHandle widgetHook;
    private static MethodHandle widgetHookPlain;
    private static MethodHandle mouseX;
    private static MethodHandle mouseY;
    /**
     * Which half of {@code GuiGraphicsExtractor} each axis of the pointer came from. Two names,
     * not one: a version that gives {@code mouseX} a getter and leaves {@code mouseY} a field is
     * perfectly possible, and one shared flag would then answer "field" for both (AUDIT-35-7).
     */
    private static String mxRoute;
    private static String myRoute;
    private static String mxWhy = "";
    private static String myWhy = "";
    /** Combined answer for the startup log and the probe: {@code coords(field)}, {@code coords(method)}, ... */
    private static String coordsRoute = "coords-absent";
    private static String lastRoute;
    private static String lastWhy = "";
    private static boolean reported;

    private ScreenHooks() {
    }

    /**
     * Draws one widget on whatever render hook this build gives a {@code Renderable}.
     *
     * <p>26.1 up to 26.4-snapshot-8 pass the pointer and the partial tick with every widget
     * ({@code (extractor, int, int, float)}); the last 26.4 snapshots dropped them and the
     * extractor answers the pointer itself. A page that draws its own widgets - which it must,
     * now that the pose is scaled - cannot spell both calls in one source, so the hook is
     * resolved per widget class and cached: the lookup costs once, not once a frame.</p>
     */
    public static void drawWidget(Renderable widget, GuiGraphicsExtractor g, int mouseXPos, int mouseYPos,
            float partialTick) {
        try {
            if (widgetHook != null) {
                widgetHook.invoke(widget, g, mouseXPos, mouseYPos, partialTick);
            } else if (widgetHookPlain != null) {
                widgetHookPlain.invoke(widget, g);
            } else if (!reported) {
                reported = true;
                LOG.warn("no widget render hook on this game version; the pages will draw empty");
            }
        } catch (Throwable failed) {
            if (!reported) {
                reported = true;
                LOG.warn("cannot draw a widget: {}", Errors.describe(failed));
            }
        }
    }

    /** Pointer x carried by the frame, or {@code fallback} when the extractor does not know it. */
    public static int mouseX(GuiGraphicsExtractor g, int fallback) {
        prepare();
        return read(mouseX, g, fallback);
    }

    /** Pointer y carried by the frame, or {@code fallback} when the extractor does not know it. */
    public static int mouseY(GuiGraphicsExtractor g, int fallback) {
        prepare();
        return read(mouseY, g, fallback);
    }

    /**
     * Which render hook this game build declares, and whether the frame carries the pointer,
     * for the startup log and for bug reports. Without the second half a screen that silently
     * draws with (0,0) looks identical to one that works (AUDIT-28-6).
     */
    public static String mode() {
        prepare();
        if (superState == null) return "unavailable";
        String hook = takesCoords ? "extractRenderState(extractor,int,int,float)" : "extractRenderState(extractor)";
        return hook + "/" + coordsRoute;
    }

    /** True when neither axis arrives: the frame carries no pointer at all. */
    private static boolean coordsAny() {
        return mouseX != null || mouseY != null;
    }

    /**
     * One of three answers: both axes by the same route, one route per axis, or absent.
     *
     * <p>A half-present pointer names the axis that is missing (AUDIT-44-5). The earlier version
     * asked "are <em>both</em> handles here" and answered "coords-absent" otherwise, so a build
     * that gained a getter for {@code mouseX} while {@code mouseY} stayed a private field logged
     * "this build does not carry the pointer" while x followed the mouse and y sat at 0 - hover
     * lit only the top row, and the log pointed the reader away from the cause.
     */
    private static String coordsName() {
        if (!coordsAny()) return "coords-absent";
        if (mouseX != null && mouseY != null && String.valueOf(mxRoute).equals(String.valueOf(myRoute))) {
            return "coords(" + mxRoute + ")";
        }
        return "coords(mouseX=" + (mouseX == null ? "absent" : String.valueOf(mxRoute))
                + ",mouseY=" + (mouseY == null ? "absent" : String.valueOf(myRoute)) + ")";
    }

    private static int read(MethodHandle handle, GuiGraphicsExtractor g, int fallback) {
        if (handle == null) return fallback;
        try {
            return (int) handle.invoke(g);
        } catch (Throwable failed) {
            return fallback;
        }
    }

    private static synchronized void prepare() {
        if (ready) return;
        ready = true;
        MethodHandles.Lookup lookup;
        try {
            lookup = MethodHandles.privateLookupIn(Screen.class, MethodHandles.lookup());
        } catch (Throwable denied) {
            LOG.warn("cannot reach the screen render hook: {}", Errors.describe(denied));
            return;
        }
        try {
            superState = lookup.findSpecial(Screen.class, "extractRenderState",
                    MethodType.methodType(void.class, GuiGraphicsExtractor.class), Screen.class);
        } catch (Throwable withoutCoords) {
            try {
                superState = lookup.findSpecial(Screen.class, "extractRenderState",
                        MethodType.methodType(void.class, GuiGraphicsExtractor.class, int.class, int.class,
                                float.class), Screen.class);
                takesCoords = true;
            } catch (Throwable broken) {
                LOG.warn("cannot reach the screen render hook: {}", Errors.describe(broken));
                return;
            }
        }
        try {
            widgetHook = MethodHandles.publicLookup().findVirtual(Renderable.class, "extractRenderState",
                    MethodType.methodType(void.class, GuiGraphicsExtractor.class, int.class, int.class, float.class));
        } catch (Throwable withoutCoords) {
            widgetHook = null;
        }
        try {
            widgetHookPlain = MethodHandles.publicLookup().findVirtual(Renderable.class, "extractRenderState",
                    MethodType.methodType(void.class, GuiGraphicsExtractor.class));
        } catch (Throwable withCoords) {
            widgetHookPlain = null;
        }
        mouseX = accessor(GuiGraphicsExtractor.class, "mouseX");
        mxRoute = lastRoute;
        mxWhy = lastWhy;
        mouseY = accessor(GuiGraphicsExtractor.class, "mouseY");
        myRoute = lastRoute;
        myWhy = lastWhy;
        coordsRoute = coordsName();
        if (!coordsAny()) {
            // the reason belongs in the log: "absent" alone cannot tell "no such member on this
            // build" from "the module system refused the lookup" (AUDIT-35-7)
            LOG.info("this build does not carry the pointer in the frame ({}); screens fall back to 0,0"
                    + " [mouseX {}; mouseY {}]",
                    takesCoords ? "extractRenderState(extractor,int,int,float)" : "extractRenderState(extractor)",
                    mxWhy, myWhy);
        } else if (mouseX == null || mouseY == null) {
            // half a pointer is worse than none: one axis follows and the other stays at 0, so the
            // symptom is one hoverable row, not a dead pointer (AUDIT-44-5)
            LOG.warn("the pointer is only half here ({}); hover follows one axis only [mouseX {}; mouseY {}]",
                    coordsRoute, mxWhy, myWhy);
        } else if (!String.valueOf(mxRoute).equals(String.valueOf(myRoute))) {
            LOG.info("the pointer arrives by a different route on each axis: {}", coordsRoute);
        }
    }

    /**
     * Reads the pointer out of the extractor: public method first, then the declared field.
     *
     * <p>The field is the one that exists - both 26.1 and 26.3 declare
     * {@code private final int mouseX/mouseY} and no getter at all, so a method-only lookup
     * answers {@code null} on <em>every</em> build and the whole one-argument entry would draw
     * at (0,0): every hover highlight dead, clicks still working, which is the hardest kind of
     * bug to report (AUDIT-34-1). The field lives in another package, so the lookup has to be made
     * in the owner's own class rather than in {@code Screen}.
     */
    private static MethodHandle accessor(Class<?> owner, String name) {
        lastRoute = null;
        lastWhy = "";
        try {
            Method method = owner.getMethod(name);
            lastRoute = "method";
            return MethodHandles.publicLookup().unreflect(method);
        } catch (Throwable notAGetter) {
            // fall through: every build seen so far keeps it as a field
        }
        try {
            java.lang.reflect.Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            MethodHandle handle = MethodHandles.privateLookupIn(owner, MethodHandles.lookup())
                    .unreflectGetter(field);
            lastRoute = "field";
            return handle;
        } catch (Throwable absent) {
            lastWhy = Errors.describe(absent);
            return null;
        }
    }

    /**
     * What the pointer route is, in one of four shapes - {@code coords(method)} and
     * {@code coords(field)} when both axes agree, {@code coords(mouseX=…,mouseY=…)} when only
     * one axis resolved (the other says {@code absent}), and {@code coords-absent} when
     * neither did. The report named three for a while after the half-resolved shape was
     * added, which made the fourth look like a bug in the log (AUDIT-45-5).
     */
    public static String coordsMode() {
        prepare();
        return coordsRoute;
    }
}
