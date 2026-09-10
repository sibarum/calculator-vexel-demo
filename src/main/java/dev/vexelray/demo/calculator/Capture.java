package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.style.Role;

import java.io.IOException;

/**
 * Headless PNGs of the chrome.
 *
 * <h2>The real tree, through the real wiring</h2>
 *
 * <p>Every scene here builds this application by calling {@link VexelApplication#tree}, which runs
 * {@code CONFIG} through {@code TREE} and stops — no window, no input backend and no window memory, so nothing
 * reached from here can write a placement. <b>This class used to build its own.</b> It did {@code new Gui()},
 * {@code gui.theme(Look.THEME)}, {@code gui.minSize(46em, 30em)} and its own {@code TitleBar} against
 * {@code WindowControls.NONE} — a second copy of what {@link CalculatorWiring#config} says, and exactly the
 * hazard {@code VexelApplication.toTree} records from the text editor: <i>"a capture that built its tree by a
 * second route would be a capture of a different application."</i>
 *
 * <p>Nothing was visibly wrong with the pictures, and that is the point rather than a reason not to have fixed
 * it. What the second route left out was the <b>zoom range</b>: the framework applies
 * {@code Appearance.ZoomRange} before the first widget, and a {@code Gui} built by hand keeps the library
 * default of {@code [0.25, 4]} instead of this application's {@code [0.5, 3]}. The ladder below fits inside
 * both, so today the two agree by coincidence. Narrow the application's range and the old code would have gone
 * on photographing zoom levels the application clamps away — a picture of a state no user can reach, with
 * nothing failing.
 *
 * <p>The clock is attached and never ticked, which is what a still picture wants: every animation sits at its
 * start value rather than somewhere arbitrary. The camera seam is the wiring's own
 * {@link CalculatorWiring.Camera}, which is already a no-op until {@code WINDOW} binds a {@code Motion} to it —
 * so the headless path needs no stand-in of its own, and the one that used to be here is gone.
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
     * What the wiring is handed. Nothing: a still frame has nothing a setting override could change, and this
     * application's flags all describe a session.
     */
    private static final String[] NO_ARGS = new String[0];

    /**
     * Zoom levels the {@code zoom} scene walks.
     *
     * <p>Every length in this UI resolves through zoom, so each file should be the previous one scaled. Anything
     * that holds its pixel size while the rest grow is still pinned to the device grid, which is the one thing
     * {@code Length} exists to prevent and the one thing a still picture can prove.
     *
     * <p>Clamped by the application's own {@code Appearance.ZoomRange} now that the tree comes from the wiring,
     * so a step outside it photographs the edge of the range rather than a level the application would refuse.
     */
    private static final float[] ZOOM_STEPS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f};

    static void run(String[] args) throws IOException {
        String scene = args.length >= 2 ? args[1] : "default";
        String out = args.length >= 3 ? args[2] : null;

        switch (scene) {
            case "zoom" -> zoomLadder();
            case "smallest" -> smallest(out);
            case "panels" -> everyPanel();
            default -> one(out == null ? "chrome-" + scene + ".png" : out, scene);
        }
    }

    /** One picture of the tree at the design size, with {@code panel} showing if it names one. */
    private static void one(String out, String panel) throws IOException {
        on((shell, wiring) -> {
            // A key naming no panel shuts it, which is Rail's documented answer and what makes an unrecognised
            // scene name a plain capture rather than an error.
            wiring.panels().rail().select(panel);
            shoot(shell, W, H, out);
            System.out.println("captured " + out);
        });
    }

    /** Every panel, one file each -- the visual record of the whole rail in one run. */
    private static void everyPanel() throws IOException {
        for (String key : new String[]{"layers", "domain", "crop", "color", "sample", "view", "help"}) {
            one("chrome-panel-" + key + ".png", key);
        }
    }

    /**
     * The whole UI at exactly {@code Gui.minSize}.
     *
     * <p><b>Photograph the minimum, not just the ordinary size.</b> A minimum chosen by eye stops being right
     * the first time a row is added to a panel, and nothing about the design-size picture would show it — the
     * failure is a clipped bottom row in a window nobody thought to open that small.
     */
    private static void smallest(String out) throws IOException {
        on((shell, wiring) -> {
            // Resolved against the same root em the layout uses, from the same two numbers the wiring declares
            // to the framework -- so this is the minimum the application actually has rather than a third copy
            // of it. See Calculator.MIN_W_EM.
            float em = shell.gui().rootEmPx();
            int w = Math.round(Calculator.MIN_W_EM * em);
            int h = Math.round(Calculator.MIN_H_EM * em);
            shoot(shell, w, h, out == null ? "chrome-smallest.png" : out);
            System.out.println("captured the minimum, " + w + "x" + h);
        });
    }

    /** One run, the same tree at each step of the ladder: the em check, as a strip of images. */
    private static void zoomLadder() throws IOException {
        on((shell, wiring) -> {
            for (float z : ZOOM_STEPS) {
                shell.gui().zoom(z);
                shoot(shell, W, H, "chrome-zoom-" + z + "x.png");
            }
            System.out.println("captured " + ZOOM_STEPS.length + " zoom levels");
        });
    }

    /**
     * Photograph {@code shell}'s tree at {@code w} by {@code h}.
     *
     * <p>The clear colour is read off the theme the framework applied rather than from a second reading of
     * {@code Look}: the editor's port records what two spellings of one colour cost, and one of them is always
     * the one that stops being right.
     */
    private static void shoot(Shell shell, int w, int h, String out) throws IOException {
        Gui gui = shell.gui();
        Color page = gui.theme().color(Role.PAGE);
        GuiApp.capture(gui, w, h, page.r(), page.g(), page.b(), out);
    }

    /**
     * Build this application as far as its tree, hand it to {@code shot}, and close it.
     *
     * <p>The caller owns the shutdown because {@code VexelApplication.tree} hands a {@code Shell} back rather
     * than doing it — a capture decides when it has finished with the tree, and this one photographs it seven
     * times over.
     */
    private static void on(Shot shot) throws IOException {
        CalculatorWiring wiring = new CalculatorWiring();
        Shell shell = VexelApplication.tree(wiring, NO_ARGS);
        try {
            shot.take(shell, wiring);
        } finally {
            shell.disposer().close();
        }
    }

    /** One scene, against a tree that exists for the length of the call. */
    private interface Shot {

        void take(Shell shell, CalculatorWiring wiring) throws IOException;
    }

    private Capture() {
    }
}
