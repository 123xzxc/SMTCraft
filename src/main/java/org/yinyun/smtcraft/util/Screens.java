package org.yinyun.smtcraft.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;

/**
 * Where the game keeps the screen it is currently showing.
 *
 * <p>26.1 exposes {@code Minecraft.screen} as a public field; 26.2 moved the whole screen
 * stack into {@code Minecraft.gui} and kept a {@code Gui.screen()} getter instead. Both are
 * read here by reflection so the single jar compiles against 26.1 and still runs on 26.4.
 * A failed read answers "no menu": hiding the panel by accident is worse than drawing it
 * behind one.
 */
public final class Screens {
    private static Method viaGui;
    private static Field viaField;
    private static boolean resolved;
    private static String mode = "unresolved";

    private Screens() {}

    /** True when the game is showing any screen (inventory, pause, settings, chat...). */
    public static boolean menuOpen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return false;
        resolve(mc);
        try {
            if (viaGui != null) return viaGui.invoke(mc.gui) != null;
            if (viaField != null) return viaField.get(mc) != null;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return false;
        }
        return false;
    }

    /** What {@link #resolve} found: {@code gui.screen()}, {@code minecraft.screen} or {@code unavailable}. */
    public static synchronized String mode() {
        return mode;
    }

    /**
     * Resolves the route (if this game can be reached at all) and answers it - the reader that
     * {@link #mode} needs. A silent {@code unavailable} turns {@code hideWhenScreenOpen} into a
     * dead switch, so the startup log prints this and uses WARN for that one answer (AUDIT-34-4).
     */
    public static synchronized String probe() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) resolve(mc);
        return mode;
    }

    private static synchronized void resolve(Minecraft mc) {
        if (resolved) return;
        // both attempts happen before the latch closes: setting it first meant one unlucky
        // read (a Gui field that was not there yet, say) froze the answer to "unavailable"
        // for the rest of the session (AUDIT-34-4)
        String found = "unavailable";
        try {
            Object gui = mc.gui;
            if (gui != null) {
                viaGui = gui.getClass().getMethod("screen");
                found = "gui.screen()";
            }
        } catch (NoSuchMethodException ignored) {
            // 26.1: the getter does not exist yet, the field below is the way in
        } catch (RuntimeException ignored) {
            // an unreadable Gui falls through to the field as well
        }
        if (viaGui == null) {
            try {
                viaField = Minecraft.class.getField("screen");
                found = "minecraft.screen";
            } catch (NoSuchFieldException | RuntimeException missing) {
                found = "unavailable";
            }
        }
        resolved = true;
        mode = found;
    }
}
