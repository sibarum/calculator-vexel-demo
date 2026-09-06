package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;

import java.io.IOException;

/**
 * Headless PNGs of the chrome.
 *
 * <h2>What a capture can and cannot show, which is not a choice</h2>
 *
 * <p>{@link GuiApp#capture} is {@code static} and builds its <b>own</b> {@code VulkanInstance} and
 * {@code VulkanDevice} for the occasion. A {@code SampledColorTarget} comes from a {@code GuiApp}
 * <b>instance</b>, on that application's device, and a target from a different device yields a descriptor set
 * this pipeline cannot bind. So a capture of a tree carrying the marched viewport draws the framework's
 * placeholder texture instead of the scene.
 *
 * <p>It does not fail. It produces a picture that is <b>correct about the chrome and silently wrong about the
 * content</b>, which is the failure mode worth naming rather than discovering. Hence: every file written here
 * says {@code chrome} in its name, the plot is photographed through the automation socket's {@code shot}
 * against a running window, and {@code docs/framework-notes.md} FN-14 carries the ask.
 *
 * <h2>Two frames, not one</h2>
 *
 * <p>{@code GuiApp.capture} already renders twice for this reason and it is worth knowing why, because anything
 * derived from a measured box has it: the observer that reacts to a layout fires <em>inside</em> that layout,
 * and the mutation it posts is applied by the next drain. A still image wants the settled state rather than the
 * instant before it.
 */
final class Capture {

    /** The prototype's declared size. */
    private static final int W = Calculator.W;
    private static final int H = Calculator.H;

    /**
     * Zoom levels the {@code zoom} scene walks.
     *
     * <p>Every length in this UI resolves through zoom, so each file should be the previous one scaled. Anything
     * that holds its pixel size while the rest grow is still pinned to the device grid, which is the one thing
     * {@code Length} exists to prevent and the one thing a still picture can prove.
     */
    private static final float[] ZOOM_STEPS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f};

    static void run(String[] args) throws IOException {
        String scene = args.length >= 2 ? args[1] : "default";
        String out = args.length >= 3 ? args[2] : null;

        switch (scene) {
            case "zoom" -> zoomLadder();
            case "smallest" -> smallest(out);
            default -> one(scene, out == null ? "chrome-" + scene + ".png" : out);
        }
    }

    /** One picture of the tree at the design size. */
    private static void one(String scene, String out) throws IOException {
        Gui gui = build();
        Color page = Calculator.page();
        GuiApp.capture(gui, W, H, page.r(), page.g(), page.b(), out);
        System.out.println("captured " + out);
        gui.close();
    }

    /**
     * The whole UI at exactly {@code Gui.minSize}.
     *
     * <p><b>Photograph the minimum, not just the ordinary size.</b> A minimum chosen by eye stops being right
     * the first time a row is added to a panel, and nothing about the design-size picture would show it — the
     * failure is a clipped bottom row in a window nobody thought to open that small.
     */
    private static void smallest(String out) throws IOException {
        Gui gui = build();
        // Resolved against the same root em the layout uses, so this is the minimum the application actually
        // declared rather than a number repeated here and left to drift.
        int w = Math.round(46 * gui.rootEmPx());
        int h = Math.round(30 * gui.rootEmPx());
        Color page = Calculator.page();
        GuiApp.capture(gui, w, h, page.r(), page.g(), page.b(), out == null ? "chrome-smallest.png" : out);
        System.out.println("captured the minimum, " + w + "x" + h);
        gui.close();
    }

    /** One run, the same tree at each step of the ladder: the em check, as a strip of images. */
    private static void zoomLadder() throws IOException {
        Gui gui = build();
        Color page = Calculator.page();
        for (float z : ZOOM_STEPS) {
            gui.zoom(z);
            GuiApp.capture(gui, W, H, page.r(), page.g(), page.b(), "chrome-zoom-" + z + "x.png");
        }
        System.out.println("captured " + ZOOM_STEPS.length + " zoom levels");
        gui.close();
    }

    /**
     * The same tree the windowed run builds, on the same code path.
     *
     * <p>A capture that assembled its own approximation of the UI would photograph something nobody ships. The
     * clock is attached and never ticked, which is exactly what a still picture wants: every animation sits at
     * its start value rather than somewhere arbitrary.
     */
    private static Gui build() {
        Gui gui = new Gui();
        gui.theme(Look.THEME);
        gui.minSize(Length.em(46), Length.em(30));
        KronoGui krono = KronoGui.attach(gui);
        new Ui(gui, krono);
        return gui;
    }

    private Capture() {
    }
}
