package org.yinyun.smtcraft.gui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.gui.components.AbstractWidget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yinyun.smtcraft.util.Errors;

/**
 * Hides and shows widgets across the 26.1 - 26.4 range.
 *
 * <p>Up to 26.3 the flag is the public field {@code AbstractWidget.visible}; 26.4-snapshot-2
 * turned it protected and added {@code setVisible(boolean)}. Reading it by reflection keeps one
 * source tree compiling against both, and the setter is preferred whenever it exists.
 */
public final class Widgets {

    private static final Logger LOG = LoggerFactory.getLogger("smtcraft-gui");

    private static boolean ready;
    private static Method setVisible;
    private static Field visibleField;

    private Widgets() {
    }

    /** Same meaning as assigning {@code widget.visible = value} on 26.1 - 26.3. */
    public static void visible(AbstractWidget widget, boolean value) {
        if (widget == null) return;
        prepare();
        try {
            if (setVisible != null) {
                setVisible.invoke(widget, value);
            } else if (visibleField != null) {
                visibleField.setBoolean(widget, value);
            }
        } catch (Throwable failed) {
            LOG.debug("cannot change the visibility of a widget: {}", Errors.describe(failed));
        }
    }

    /** How this game build hides a widget, for the startup log and for bug reports. */
    public static String mode() {
        prepare();
        if (setVisible != null) return "setVisible";
        if (visibleField != null) return "visible field";
        return "unavailable";
    }

    private static synchronized void prepare() {
        if (ready) return;
        ready = true;
        try {
            setVisible = AbstractWidget.class.getMethod("setVisible", boolean.class);
            return;
        } catch (NoSuchMethodException beforeSnap2) {
            // 26.3 and earlier expose the field instead
        }
        try {
            visibleField = AbstractWidget.class.getField("visible");
        } catch (Throwable none) {
            LOG.warn("cannot hide widgets on this version ({})", Errors.describe(none));
        }
    }
}
