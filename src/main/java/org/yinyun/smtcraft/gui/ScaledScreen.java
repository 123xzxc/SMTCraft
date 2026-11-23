package org.yinyun.smtcraft.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.yinyun.smtcraft.util.GameScale;

/**
 * A screen laid out in the pixels it was designed for, whatever the game's GUI scale is.
 *
 * <p>Our pages were drawn for GUI scale 2. At scale 4 the logical window is half as big in
 * each direction, so a tab that needs fourteen rows collapses into a 10 px pitch and reads
 * as one dense block; at scale 1 everything is half the size it was designed at. The fix is
 * to lay the page out in design pixels ({@link #vw()} by {@link #vh()}), scale the pose by
 * {@link #ui()} while drawing, and hand every pointer event back in design pixels.</p>
 *
 * <p>The backdrop is left to the game: it covers the whole window and is painted one stratum
 * before this method runs, so scaling it would only leave the margins outside the scaled
 * rectangle unpainted. The widgets, on the other hand, are painted here, because the base
 * class would paint them in screen pixels and ignore the scale entirely.</p>
 *
 * <p>Everything this class gets right, it gets right by <em>not</em> calling the base render -
 * so each thing the base would have done after us has to be thought through separately. One
 * such thing is still waiting: {@code g.extractDeferredElements(x, y, partialTick)} runs after
 * this override returns, i.e. after the pose has been popped, and it draws in screen pixels.
 * A tooltip or a cursor submitted from here would therefore be recorded in design pixels and
 * painted at 1/ui times the distance from where it belongs. Nothing submits one today; if a
 * page ever does, multiply the coordinates by {@link #ui()} before submitting (AUDIT-35-6).</p>
 */
public abstract class ScaledScreen extends Screen {

    private float ui = 1.0f;

    protected ScaledScreen(Component title) {
        super(title);
    }

    /**
     * Re-reads the multiplier. Call it from {@code init()} before laying anything out: a
     * page whose factor changed after its widgets were built keeps the old geometry.
     */
    protected final void refreshUi(boolean follow) {
        ui = GameScale.factor(follow);
    }

    /** The multiplier this page is drawn with; {@code 1} when it follows the toggle off. */
    protected final float ui() {
        return ui;
    }

    /** Viewport width in design pixels. */
    protected final int vw() {
        return Math.max(1, Math.round(this.width / ui));
    }

    /** Viewport height in design pixels. */
    protected final int vh() {
        return Math.max(1, Math.round(this.height / ui));
    }

    /** A screen coordinate expressed in design pixels. */
    protected final int toDesign(double value) {
        return (int) Math.round(value / ui);
    }

    private MouseButtonEvent toDesign(MouseButtonEvent event) {
        if (ui == 1.0f) return event;
        return new MouseButtonEvent(event.x() / ui, event.y() / ui, event.buttonInfo());
    }

    /** 26.1 up to 26.4-snapshot-4 hand the pointer along with the frame. */
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        frame(g, mouseX, mouseY, partialTick);
    }

    /** 26.4-snapshot-5 and later hand over the extractor, which carries the pointer itself. */
    public void extractRenderState(GuiGraphicsExtractor g) {
        // no partial tick on this signature: no build puts one inside the extractor (it comes
        // with the four-argument call), and a handle that can never resolve is a handle that
        // will be misread one day as "this version has one" (AUDIT-34-2)
        frame(g, ScreenHooks.mouseX(g, 0), ScreenHooks.mouseY(g, 0), 0.0f);
    }

    private void frame(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // The game paints the backdrop itself, one stratum earlier, and the blur it starts
        // there can only run once a frame - so this method must not call extractBackground
        // again (it throws "Can only blur once per frame") and must not call the base render
        // either, because the base would draw the widgets in screen pixels behind our back.
        // What is left for us is the widgets and the page, all of it in design pixels.
        boolean scaled = ui != 1.0f;
        if (scaled) g.pose().pushMatrix().scale(ui, ui);
        // the matrix is pushed outside the try so the pop always runs: an exception in a page
        // that skipped it would leave the stack one deep, and every later frame would scale on
        // top of that - the page grows a little each frame and the game never complains (AUDIT-35-5)
        try {
            int mx = toDesign(mouseX);
            int my = toDesign(mouseY);
            for (GuiEventListener child : children()) {
                if (child instanceof Renderable renderable) {
                    ScreenHooks.drawWidget(renderable, g, mx, my, partialTick);
                }
            }
            drawFrame(g, mx, my, partialTick);
            // page chrome - title, scrollbar, status lines - after the rows and the decoration
            drawOverlay(g, mx, my, partialTick);
        } finally {
            if (scaled) g.pose().popMatrix();
        }
    }

    /** Draws everything but the backdrop, in design pixels. */
    protected abstract void drawFrame(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick);

    /** Draws the page chrome, in design pixels, after the content clip has ended. */
    protected void drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
    }

    /**
     * Why this page does not clip its rows with {@code enableScissor}, even though the
     * extractor offers one: the scissor rectangle is pushed through the current pose and then
     * compared, in the same coordinates, by {@code containsPointInScissor}. A page drawn with a
     * scaled pose therefore has a scissor in logical pixels and a pointer in design pixels, and
     * every widget decides it is not hovered - the highlight and the tooltips die. Rows are
     * kept off the header by hiding the one that straddles the edge instead (see
     * {@code SettingsScreen.applyScroll}); the pages have at most a couple of dozen rows, so
     * that costs nothing and survives every game version.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        MouseButtonEvent e = toDesign(event);
        if (onClick(e, doubleClick)) return true;
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        MouseButtonEvent e = toDesign(event);
        if (onDrag(e, dragX / ui, dragY / ui)) return true;
        return super.mouseDragged(e, dragX / ui, dragY / ui);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        MouseButtonEvent e = toDesign(event);
        if (onRelease(e)) return true;
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return super.mouseScrolled(mouseX / ui, mouseY / ui, scrollX, scrollY);
    }

    /** Design-space click hook; {@code true} when the page handled it itself. */
    protected boolean onClick(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    /** Design-space drag hook; {@code true} when the page handled it itself. */
    protected boolean onDrag(MouseButtonEvent event, double dragX, double dragY) {
        return false;
    }

    /** Design-space release hook; {@code true} when the page handled it itself. */
    protected boolean onRelease(MouseButtonEvent event) {
        return false;
    }
}
