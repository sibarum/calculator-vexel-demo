package dev.vexelray.demo.calculator;

import dev.vexelray.framework.api.FrameStage;
import dev.vexelray.framework.automation.Driver;
import dev.vexelray.framework.shell.AppInfo;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.Wiring;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.Length;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This application's own wiring: what it builds, and in which phase.
 *
 * <p>What is <em>not</em> here is the point. Opening an input backend and settling its coordinate space,
 * installing a clipboard, remembering where the window was, attaching a clock, wiring the frame loop and its
 * wakes and its pacing, parsing the command line, and closing everything in the right order — all of that used
 * to be in {@link Calculator} and is now the framework's. This file is only the part that is actually about a
 * plot viewport.
 *
 * <p>Written by hand, deliberately: this is the file the annotation processor will be made to generate, so its
 * shape is being settled against real code first. Each method below is one {@code Phase}, and the phase a
 * component belongs to is decided by what it needs — which is exactly the inference the processor will do from
 * constructor parameters.
 */
final class CalculatorWiring implements Wiring {

    private static final AppInfo INFO =
            new AppInfo(Calculator.APP, "Calculator", Calculator.W, Calculator.H);

    private Model model;
    private Camera camera;
    private Ui ui;
    private March march;
    private Motion motion;

    @Override
    public AppInfo info() {
        return INFO;
    }

    /**
     * The look, and the smallest window this UI is still coherent in — a floor, not the design size. Below this
     * the rail plus a 284px panel leaves nothing for the plot, which is the thing the window is for.
     *
     * <p>Both are values, so they are settled before there is a {@code Gui} to apply them to. The framework
     * applies them at the moment it creates one, which is what used to be a comment about roles resolving when
     * a widget writes a prop.
     */
    @Override
    public void config(Shell shell) {
        shell.appearance(Appearance.of(Look.THEME, Length.em(46), Length.em(30)));
    }

    /**
     * The one authoritative {@code Scene}, and the camera seam.
     *
     * <p>{@link Camera} is here rather than in {@link #window} because the panels that name its buttons are
     * built in {@code TREE}, before a window exists — while {@link Motion}, which actually moves the view,
     * cannot exist until {@link March} does, and {@code March} needs the device. The framework does not remove
     * that inversion; it explains it. A component in {@code TREE} asking for {@code Motion} directly is a
     * backwards dependency, and once the processor exists it is a compile error naming both phases instead of
     * something an author has to notice.
     */
    @Override
    public void model(Shell shell) {
        model = new Model();
        camera = new Camera();
    }

    /**
     * The tree. Needs the {@code Gui} and the clock, both of which exist by now, and no window.
     *
     * <p>The title bar is the framework's — chrome placement belongs to whoever owns the window, so that the
     * screenshot instrument in it means the same thing in every window. This application places the node and
     * supplies every colour in it, and no longer constructs it or hands it controls.
     */
    @Override
    public void tree(Shell shell) {
        ui = new Ui(shell.gui(), shell.krono(), model, camera, shell.titleBar());
        zoomShortcuts(shell.gui());
        keys(shell.gui(), model, camera, ui);
    }

    /**
     * The plot, and everything that moves it.
     *
     * <p>Phase {@code WINDOW} because a render target and a storage buffer come from the application's device,
     * and the device does not exist until {@code GuiApp} does — which is also the whole reason
     * {@code GuiApp.viewport} and {@code GuiApp.storage} exist rather than the device being public.
     */
    @Override
    public void window(Shell shell) {
        march = new March(shell.app());
        march.showIn(ui.viewport());
        motion = new Motion(shell.krono().kron(), shell.krono().frames(), shell.krono().animator(), march);
        camera.on(motion);
    }

    /**
     * Everything that needed the window: the geometry pipeline, the
     * gestures, the per-frame work, and the driving socket. The chrome is the framework's to point at controls.
     */
    @Override
    public void attach(Shell shell) {
        Gui gui = shell.gui();

        // Auto-orbit is a flag in the Scene and a curve on the camera, and this is the one place the two meet.
        // An edge rather than a level: the spin is *started* by switching it on, and re-anchoring the curve on
        // every unrelated scene change -- a keystroke in the expression, a slider -- would be harmless to look
        // at and wrong to write, because it says the spin depends on the sample count.
        AtomicBoolean spinning = new AtomicBoolean(model.scene().spinning());

        // Geometry is rebuilt whenever the scene changes, on the committing thread -- which is a worker,
        // because every control's handler is. The GUI thread never samples anything; it takes a float[].
        model.onChange(s -> {
            Geometry.Built built = Geometry.of(s.reading(), s.omega(), s.x0(), s.x1(),
                    s.samples(), s.effectiveFurniture(), s.lineWidth(), s.ramp());
            march.geometry(built);
            ui.probe().samples(built.curve(), s.x0(), s.x1());
            march.recolour(s.ramp());
            ui.bar().show(s.reading());
            ui.bar().cropping(s.cropping());
            if (spinning.getAndSet(s.spinning()) != s.spinning()) {
                motion.spinning(s.spinning());
            }
        });
        // And once at startup, for the scene nobody has changed yet.
        Scene start = model.scene();
        Geometry.Built first = Geometry.of(start.reading(), start.omega(), start.x0(), start.x1(),
                start.samples(), start.effectiveFurniture(), start.lineWidth(), start.ramp());
        march.geometry(first);
        ui.probe().samples(first.curve(), start.x0(), start.x1());
        ui.probe().install(ui.viewport(), march::lens);

        Gestures.install(gui, ui.viewport(), motion);

        // The application's own per-frame work, in the one stage an application should be writing hooks in.
        // APP runs after CLOCK, which is the ordering this used to state longhand: a camera animation settles
        // on the tick, and the frame that presents a value should be the frame that computed it. It is still
        // before the tree is drawn, because renderInto's contract is that the image is ready when it returns.
        shell.hooks().add(FrameStage.APP, this::frame);

        // The driving socket, off unless -Dautomation or --automation asks for it. Was thirty lines here,
        // near-identically to three other applications; now it is the framework's, in a module of its own so a
        // binary that never wants to be driven does not link a listening socket.
        shell.disposer().register(Driver.open(shell));
    }

    /**
     * One frame of this application's own work.
     *
     * <p>Was the body of the {@code beforeFrame} lambda, minus the three lines that were the framework's: the
     * input pump, the clock tick and the window-memory poll are stages now.
     */
    private void frame() {
        Scene now = model.scene();
        march.frame();
        // The labels are authored against the same camera the march was just given and against the node's
        // measured box, in the same frame, on the GUI thread. A frame's lag would be visible as text sliding
        // across the plot behind the geometry it names.
        Labels.show(ui.viewport(), Labels.of(march.lens(), ui.viewport().layout(),
                now.x0(), now.x1(), now.effectiveFurniture().ticks()));
        Motion.View eye = motion.now();
        ui.readout().show(eye.yaw(), eye.pitch(), eye.zoom(), march.cones());
    }

    /**
     * The camera's presets, as the VIEW panel and the rail's crosshair see them.
     *
     * <p>Late-bound because {@link March} cannot exist until {@code GuiApp} does — a render target comes from
     * the application's device — while the tree that names these buttons is built before the window. A seam
     * rather than a forward reference, and narrow enough that a panel cannot reach past it into the renderer.
     */
    static final class Camera implements Panels.Viewpoint {

        private volatile Motion motion;

        void on(Motion motion) {
            this.motion = motion;
        }

        @Override
        public void look(double yawDegrees, double pitchDegrees) {
            Motion m = motion;
            if (m != null) {
                m.look(yawDegrees, pitchDegrees);
            }
        }

        @Override
        public void reset() {
            Motion m = motion;
            if (m != null) {
                m.reset();
            }
        }
    }

    /**
     * Every single-key control, as a {@code GLOBAL} claim.
     *
     * <p>Claims rather than handlers, which is how the GUI does preemption: the expression field outranks these
     * by claiming the same key at {@code FOCUSED} scope, so typing {@code r} into an expression types an
     * {@code r} rather than resetting the view. Nothing here has to know the field exists, and the field needs
     * no list of keys to avoid.
     */
    private static void keys(Gui gui, Model model, Camera camera, Ui ui) {
        gui.shortcut(Key.SPACE, () -> model.change(s -> Panels.spinning(s, !s.spinning())));
        gui.shortcut(Key.R, camera::reset);
        gui.shortcut(Key.T, () -> camera.look(0, 75));
        gui.shortcut(Key.S, () -> camera.look(0, 0));
        gui.shortcut(Key.C, () -> model.change(s -> Panels.cropping(s, !s.cropping())));
    }

    /**
     * Ctrl+= / Ctrl+- / Ctrl+0, and the numpad's three as well.
     *
     * <p>An application decision rather than the framework's: which chord zooms, or whether zooming exists at
     * all, is not something a framework should be choosing. <b>How far the zoom goes is</b>, and it no longer
     * says so here — this method used to open with {@code gui.zoomRange(0.5f, 3f, 1.25f)}, which was the same
     * three numbers as four other places on this stack and is now {@code Appearance.ZoomRange}, applied by the
     * framework before the first widget. What is left below is only the part that was ever a decision.
     */
    private static void zoomShortcuts(Gui gui) {
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_ADD, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_SUBTRACT, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_0, gui::resetZoom, Modifier.CONTROL);
    }

}
