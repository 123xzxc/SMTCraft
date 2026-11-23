package org.yinyun.smtcraft.util;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * The game-facing half of {@link UiScale}: asks the open window for its GUI scale.
 *
 * <p>Kept apart from the arithmetic so that the self test - which compiles a handful of
 * classes against the client jar and runs them headless - can exercise the numbers without
 * a window. Every caller on the render path goes through here rather than reading
 * {@code Minecraft.getInstance().getWindow()} itself, so the "no window yet" case is
 * answered in one place.</p>
 */
public final class GameScale {

    private GameScale() {
    }

    /** The GUI scale of the open window, or {@code 0} when there is no window yet. */
    public static int guiScale() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return 0;
        Window window = minecraft.getWindow();
        if (window == null) return 0;
        return window.getGuiScale();
    }

    /** The multiplier our pages should be drawn with; {@code 1} when the player turned it off. */
    public static float factor(boolean follow) {
        if (!follow) return 1.0f;
        return UiScale.factor(guiScale());
    }
}
