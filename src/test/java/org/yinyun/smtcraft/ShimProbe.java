package org.yinyun.smtcraft;

import org.yinyun.smtcraft.gui.ScreenHooks;
import org.yinyun.smtcraft.gui.Widgets;
import org.yinyun.smtcraft.render.CoverTexture;
import org.yinyun.smtcraft.util.Keys;
import org.yinyun.smtcraft.util.WheelWatch;

/**
 * Prints which route every compatibility shim resolved to, and fails when one that has to work on
 * the compile target resolved to nothing.
 *
 * <p>{@code SelfTest} runs without the game on its classpath on purpose, so none of the five
 * shims - the only code that differs between 26.1 and 26.4 - was reachable from any assertion.
 * A shim that quietly stops resolving is a downgrade nothing reports: the cover just does not
 * appear, the keys just do not answer (R28-9). This probe is compiled against the same client jar
 * {@code build.sh} uses and run by {@code test.sh}.</p>
 */
public final class ShimProbe {

    private static int failed;

    private ShimProbe() {
    }

    public static void main(String[] args) {
        String f7 = Integer.toString(Keys.key("KEY_F7", -1));
        String control = Integer.toString(Keys.key("KEY_LCONTROL", -1));
        System.out.println("shim cover=" + CoverTexture.mode()
                + ", screen=" + ScreenHooks.mode()
                + ", widget=" + Widgets.mode()
                + ", key=" + Keys.mode()
                + ", wheel=" + WheelWatch.mode()
                + ", F7=" + f7 + ", LCONTROL=" + control);
        demand("the cover texture API", CoverTexture.available());
        demand("the screen render hook", !"unavailable".equals(ScreenHooks.mode()));
        demand("widget visibility", !"unavailable".equals(Widgets.mode()));
        demand("the key codes", Keys.key("KEY_F7", -1) > 0 && Keys.key("KEY_LCONTROL", -1) > 0);
        // Which wheel backend the classpath carries is a property of the build, not of the
        // mod: without the game's natives "unavailable" is a legitimate answer. Not resolving
        // at all is not.
        demand("the wheel backend", !"unresolved".equals(WheelWatch.mode()));
        if (failed > 0) {
            System.out.println(failed + " compatibilit" + (failed == 1 ? "y" : "ies") + " did not resolve");
            System.exit(1);
        }
    }

    private static void demand(String what, boolean ok) {
        if (!ok) {
            failed++;
            System.out.println("FAIL " + what + " did not resolve");
        }
    }
}
