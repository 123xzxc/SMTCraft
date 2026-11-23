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
 * appear, the keys just do not answer (AUDIT-28-9). This probe is compiled against the same client jar
 * {@code build.sh} uses and run by {@code test.sh}.</p>
 *
 * <p>What it does <em>not</em> prove: that a scroll event reaches the HUD, or that a held key is
 * seen inside a running game. Those need a window and an event loop. The probe covers the static
 * half - which route resolves on this build, and whether the numbering handed out agrees with it;
 * the live half is read off {@code logs/latest.log} on the three test instances (AUDIT-30-1).</p>
 */
public final class ShimProbe {

    private static int failed;

    private ShimProbe() {
    }

    public static void main(String[] args) {
        // Forced on purpose: a probe that only asks "has it been tried yet" passes on every
        // build, including one where neither overload exists (AUDIT-30-2).
        String keyRoute = Keys.resolveForProbe();
        // Safe to force: resolving only reads the class path (Class.forName(..., false, ...)).
        // Installing is the half that needs a window, and that stays on the instances (AUDIT-31-1).
        String wheelRoute = WheelWatch.resolveForProbe();
        int control = Keys.key("KEY_LCONTROL", -1);
        int rightControl = Keys.key("KEY_RCONTROL", -1);
        System.out.println("shim cover=" + CoverTexture.mode()
                + ", screen=" + ScreenHooks.mode()
                + ", widget=" + Widgets.mode()
                + ", key=" + keyRoute
                + ", wheel=" + wheelRoute
                + ", LCONTROL=" + control + ", RCONTROL=" + rightControl);
        demand("the cover texture API", CoverTexture.available());
        demand("the screen render hook", !"unavailable".equals(ScreenHooks.mode()));
        // The pointer comes out of the extractor's private field on every build seen so far; a
        // method-only lookup answers null everywhere and draws the whole one-argument entry at
        // (0,0), which reads as "hover is broken" rather than "this shim is broken" (AUDIT-34-1).
        String coords = ScreenHooks.coordsMode();
        System.out.println("screen coords=" + coords);
        // Three shapes are supported and all three must pass (AUDIT-44-4): both axes by the same
        // route, one route per axis ("coords(mouseX=field,mouseY=method)"), and a half-present
        // pointer, where one axis never arrives. The old assertion listed only the first two, so a
        // build that answers per axis - the very shape AUDIT-35-7 added - would have failed here.
        demand("the pointer of the frame (" + coords + ")",
                coords.matches("coords\\((field|method|mouseX=(field|method|absent),mouseY=(field|method|absent))\\)"));
        boolean pointerField;
        try {
            Class.forName("net.minecraft.client.gui.GuiGraphicsExtractor")
                    .getDeclaredField("mouseX");
            pointerField = true;
        } catch (Throwable absent) {
            pointerField = false;
        }
        demand("a pointer field on the extractor", pointerField);
        demand("widget visibility", !"unavailable".equals(Widgets.mode()));
        // Only the modifiers are read raw: the offset key comes from its KeyMapping, so
        // there is no constant left for the probe to check.
        demand("the key overload", keyRoute.startsWith("isKeyDown(") && !keyRoute.endsWith("/deferred"));
        demand("the key codes", control > 0 && rightControl > 0);
        // The numbering has to agree with the overload. On this pair the two differ in kind: the
        // SDL scancode (224/228) fits a byte, the GLFW key code (341/345) does not. That is a
        // statement about these two constants, not about key codes in general - GLFW's A-Z are
        // 65-90 and fit as well - so do not lift the rule to another key (AUDIT-31-6). A build that
        // answers "scancodes" while handing out 341 is the mismatch R28-1 shipped, and this is the
        // only place that can notice it offline.
        demand("the numbering that goes with the resolved overload",
                (control < 256) == keyRoute.endsWith("/scancodes"));
        // A miss must not be remembered as the answer (AUDIT-30-5): the second call asks for a
        // different fallback and has to get that one.
        demand("a key fallback that is not remembered",
                Keys.key("SMTCRAFT_NO_SUCH_FIELD", 7) == 7 && Keys.key("SMTCRAFT_NO_SUCH_FIELD", 9) == 9);
        // Which backend this class path offers differs between 26.1 and 26.4 and used to have no
        // automated check at all: the probe forbade resolving it, on the belief that resolving
        // loads GLFW - which resolve() contradicts by using forName(..., false, ...) (AUDIT-31-1).
        // Whether a notch actually arrives still needs a window and stays on the instances.
        demand("the wheel backend", "glfw".equals(wheelRoute) || "sdl".equals(wheelRoute));
        // Which one is possible is a property of the client jar, not of this host: 26.1 and 26.2
        // ship GLFW only, 26.3 moved to SDL. Without the caller saying which, "resolved to
        // something" is the whole assertion, and a 26.1 target answered "sdl" for a round because
        // the class path carried the newest version's lwjgl-sdl (AUDIT-32-1). Every other caller leaves
        // the property unset, so this demand only exists when test.sh passes it.
        String wheelShould = System.getProperty("smtcraft.wheel", "");
        if (!wheelShould.isEmpty()) {
            boolean agrees = wheelShould.equals(wheelRoute);
            if (!agrees && "glfw".equals(wheelShould)) {
                System.out.println("this compile target cannot load org.lwjgl.sdl;"
                        + " the class path is lying about the target");
            }
            demand("the wheel backend that this target can have (" + wheelShould + ")", agrees);
        }
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
