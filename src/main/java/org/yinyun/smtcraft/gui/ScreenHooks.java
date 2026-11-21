package org.yinyun.smtcraft.gui;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    private static MethodHandle mouseX;
    private static MethodHandle mouseY;
    private static MethodHandle partialTicks;
    private static boolean reported;

    private ScreenHooks() {
    }

    /** Draws the backdrop and the widgets of the screen, exactly like a {@code super} call. */
    public static void drawBackground(Screen screen, GuiGraphicsExtractor g, int mouseXPos, int mouseYPos,
            float partialTick) {
        prepare();
        if (superState == null) return;
        try {
            if (takesCoords) {
                superState.invoke(screen, g, mouseXPos, mouseYPos, partialTick);
            } else {
                superState.invoke(screen, g);
            }
        } catch (Throwable failed) {
            if (!reported) {
                reported = true;
                LOG.warn("cannot draw the screen backdrop: {}", Errors.describe(failed));
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

    /** Partial tick carried by the frame, or {@code fallback} when the extractor does not know it. */
    public static float partialTicks(GuiGraphicsExtractor g, float fallback) {
        prepare();
        if (partialTicks == null) return fallback;
        try {
            return (float) partialTicks.invoke(g);
        } catch (Throwable failed) {
            return fallback;
        }
    }

    /**
     * Which render hook this game build declares, and whether the frame carries the pointer,
     * for the startup log and for bug reports. Without the second half a screen that silently
     * draws with (0,0) looks identical to one that works (R28-6).
     */
    public static String mode() {
        prepare();
        if (superState == null) return "unavailable";
        String hook = takesCoords ? "extractRenderState(extractor,int,int,float)" : "extractRenderState(extractor)";
        return coordsPresent() ? hook + "/coords" : hook + "/coords-absent";
    }

    private static boolean coordsPresent() {
        return mouseX != null && mouseY != null;
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
        mouseX = accessor(GuiGraphicsExtractor.class, "mouseX");
        mouseY = accessor(GuiGraphicsExtractor.class, "mouseY");
        partialTicks = accessor(GuiGraphicsExtractor.class, "partialTicks");
        if (!coordsPresent()) {
            LOG.info("this build does not carry the pointer in the frame ({}); screens fall back to 0,0",
                    takesCoords ? "extractRenderState(extractor,int,int,float)" : "extractRenderState(extractor)");
        }
    }

    private static MethodHandle accessor(Class<?> owner, String name) {
        try {
            Method method = owner.getMethod(name);
            return MethodHandles.publicLookup().unreflect(method);
        } catch (Throwable absent) {
            return null;
        }
    }
}
