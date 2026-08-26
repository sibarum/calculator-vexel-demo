package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.text.Document;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.text.TextLayout;
import java.util.List;
import sibarum.cott.Cott;
import sibarum.cott.Notation;
import sibarum.cott.Parser;
import sibarum.cott.Render;
import sibarum.cott.SyntaxException;
import sibarum.cott.Term;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;
import sibarum.tactroller.clipboard.Clipboard;
import sibarum.tactroller.clipboard.ClipboardException;

/**
 * A deceptively simple calculator on vexelray-gui: a display label over a flex grid of buttons.
 * The tree is built once through {@link Gui}/{@link Node} handles; click handlers run on worker
 * threads and mutate the display through its handle.
 *
 * <p>Run: {@code CalculatorApp} (windowed), {@code CalculatorApp --capture [out.png]} (headless), or
 * {@code CalculatorApp --capture-plot[=EXPRESSION]} (the plot, headless, with its cache counts), or
 * {@code CalculatorApp --capture-sdf[=EXPRESSION]} (the same expression compiled to a ray-marched shader),
 * or {@code CalculatorApp --march[=EXPRESSION]} (windowed, with that surface already marched in a preview).
 * Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class CalculatorApp {

    /**
     * Window and capture size, in the engine's logical coordinates. The GUI draws the frame now, so the
     * client area is the whole window and the height carries {@link #BAR_H} of the application's own title
     * bar on top of the 600 the keypad had beneath an OS one -- same keypad, same outer rect.
     */
    private static final int W = 420;
    /** The title bar's own height, in dp -- {@code TitleBar}'s, which is the Windows caption metric. */
    private static final int BAR_H = 32;
    private static final int H = 600 + BAR_H;

    /** The plot window's size, and the size --capture-plot photographs it at. */
    private static final int PLOT_W = 720;
    private static final int PLOT_H = 560 + BAR_H;

    /**
     * The multiplication key's label, and the sign it inserts. Taken from the engine rather than written
     * here, because the keypad and the printer disagreeing about it is exactly the failure
     * {@link Notation} exists to prevent — a display the calculator cannot read back.
     */
    private static final String TIMES = String.valueOf(Notation.TIMES);

    /** The keys that go into the field verbatim. Everything else is an operand — see {@code insertOperand}. */
    private static final java.util.Set<String> OPERATORS =
            java.util.Set.of("+", "−", TIMES, "÷", "^", ",", ")");

    // The colours live in Palette now: the plot window wears them too, and a plot drawn in its own scheme
    // would read as a different program that happened to open. These are this file's names for them.
    private static final Color BG = Palette.BG;
    private static final Color PANEL = Palette.PANEL;
    private static final Color PANEL_HOVER = Palette.PANEL_HOVER;
    private static final Color PANEL_PRESSED = Palette.PANEL_PRESSED;
    private static final Color LINE = Palette.LINE;
    private static final Color BTN_BLUE = Palette.BTN_BLUE;
    private static final Color BTN_BLUE_HOVER = Palette.BTN_BLUE_HOVER;
    private static final Color BTN_BLUE_PRESSED = Palette.BTN_BLUE_PRESSED;
    private static final Color INK = Palette.INK;
    private static final Color DIM = Palette.DIM;

    public static void main(String[] args) throws Exception {
        args = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);

        Gui gui = new Gui();
        // Two em more than the keypad needs on its own: the title bar sits inside the canvas now, so the
        // smallest layout has to hold it as well as the display and the keys.
        gui.minSize(Length.em(21), Length.em(32));
        Ui ui = buildUi(gui);
        Engine engine = ui.engine();
        zoomShortcuts(gui);

        if (args.length >= 1 && args[0].startsWith("--capture-plot")) {
            capturePlot(entryOf(args[0], "1÷(x^2−1)"), "plot.png", "plot-zoomed.png");
            return;
        }

        if (args.length >= 1 && args[0].startsWith("--capture-surface")) {
            // A saddle: the one picture that shows at a glance whether the projection and the painting order
            // are both right, since it rises on one axis exactly as it falls on the other.
            capturePlot(entryOf(args[0], "x^2−y^2"), "surface.png", "surface-turned.png");
            return;
        }

        if (args.length >= 1 && args[0].startsWith("--capture-sdf")) {
            // A pole ridge: the case that says whether the gradient normalisation is doing its job, since an
            // un-normalised implicit loses the surface exactly where the height runs away.
            captureSdf(entryOf(args[0], "1÷(x^2+y^2)"), "sdf.png");
            return;
        }

        if (args.length >= 1 && args[0].equals("--capture")) {
            // Exercise the residue vertical: 1 over 1 in an additive context keeps its winding.
            for (String k : new String[]{"x", "+", "(", "1", "÷", "1", ")", "="}) {
                engine.press(k);
            }
            GuiApp.capture(gui, W, H, 0.06f, 0.07f, 0.09f, args.length >= 2 ? args[1] : "calculator.png");
            System.out.println("captured");
            return;
        }

        // The demo, in one command: open windowed, plot this expression, and show it marched. Everything it
        // does is reachable by hand -- press =, then March -- and doing it from a flag only saves the typing.
        String march = args.length >= 1 && args[0].startsWith("--march")
                ? entryOf(args[0], "1÷(x^2+y^2)") : null;

        // A frame cap is the first argument ordinarily and the second after --march, so a marched launch can
        // still be run bounded -- which is the only way anything about the windowed path gets checked.
        int maxFrames = march != null
                ? (args.length >= 2 ? Integer.parseInt(args[1]) : 0)
                : (args.length > 0 ? Integer.parseInt(args[0]) : 0);
        // Placement is read before the window exists, so the calculator is created where it was left rather
        // than moved there after appearing -- and clamped on the way, because the desk may have changed shape
        // since. Every window here goes through the same three lines: config it, restore its state, watch it.
        WindowMemory memory = new WindowMemory(Settings.open(APP_NAME));
        try (Tactroller input = openInput();
             Clipboard clipboard = openClipboard(gui);
             GuiApp app = new GuiApp(memory.config("main", "Calculator", W, H)
                     .decorations(Decorations.CLIENT))) {
            attachInput(input, app, gui);
            // The window exists at last, so the chrome can be pointed at it. Until now the bar has been a
            // working bar against WindowControls.NONE -- which is also what --capture renders.
            ui.titleBar().controls(app.controls());
            if (memory.maximized("main")) {
                app.window().maximize();
            }
            // Watched with its tree, so the UI zoom is remembered too: Ctrl+= is the same kind of decision as
            // dragging the window bigger, and losing it on quit is the same loss. zoomShortcuts has already
            // set the range the restored factor is clamped into.
            memory.watch("main", app.window(), gui);
            // The history needs the app to open its window onto, so it is built here rather than in
            // buildUi -- and stays null under --capture, which never reaches this far.
            History history = new History(engine, app, memory);
            engine.history(history);
            zoomShortcuts(history.windowGui());
            // Every window the framework opens from here on gets an input backend of its own, attached at
            // creation and released with the window. The history predates this seam and still attaches its
            // own; the plot is a named window and lets the framework do it.
            app.input(CalculatorApp::attachWindowInput);
            // Each preview is built the first time its slot is used, and gets the same UI zoom shortcuts every
            // other window here has -- which is the only thing the calculator has to say about a window it did
            // not know it was going to open.
            Previews previews = new Previews(app, memory, CalculatorApp::zoomShortcuts);
            engine.plotter(previews);
            if (march != null) {
                // Safe before the loop starts: showing a preview only posts the window request, and the window
                // is created at the top of the first frame like any other.
                openMarched(previews, march);
            }
            TactrollerInputBridge bridge = input == null ? null : new TactrollerInputBridge(input, gui.bus());
            try {
                app.run(gui, maxFrames, () -> {
                    pump(bridge);
                    history.drain();
                    memory.poll();
                    // A marched viewport does its GPU work here and nowhere else: this hook runs on the thread
                    // that presents, and the device queue belongs to it.
                    previews.pump();
                });
            } finally {
                // The debounce has no next frame to fire on once the loop is over, so the last move of the
                // session is written here or not at all.
                memory.save();
            }
        }
        gui.close();
        System.out.println("clean shutdown");
    }

    /**
     * The frame handed to the GUI is now {@link WindowMemory}'s, but {@link Decorations#CLIENT} is still this
     * application's: it extends the client area over the whole window, so the {@code TitleBar} at the top of
     * the tree draws where the system caption was -- while dragging, snapping, Win+arrow,
     * double-click-to-maximize, the system menu and the maximize clamp all stay the window manager's. The GUI
     * only supplies geometry.
     *
     * <p><b>What is remembered, and what deliberately is not.</b> Each of the three windows remembers where it
     * was, how big, whether it was maximized, and what it was zoomed to. None of them remembers whether it was
     * <em>open</em>, which the framework offers and the text editor uses. That is not an oversight: a history
     * window reopened at launch would list nothing, because the tape is this session's, and a plot window
     * reopened at launch would have no expression to draw. Restoring a window to show emptiness is worse than
     * not restoring it -- there is nothing there to be where you left it.
     */
    private static final String APP_NAME = "calculator";

    /**
     * Headless proof that the plot draws: frame the tree once so the canvas has a size, paint into it, then
     * frame again and photograph the result. Two frames rather than one because the surface paints from a
     * worker off a laid-out canvas -- the same handshake a real window makes, minus the window.
     *
     * <p>{@code mvn compile exec:exec "-Dapp.args=--capture-plot"} writes {@code plot.png}; append
     * {@code =EXPRESSION} to plot something else. The default is {@code 1÷(x²−1)}, which is the whole point of
     * the technique in one picture: two poles, found by the arithmetic rather than by a solver, drawn as
     * painted columns instead of as lines through infinity.
     */
    private static void capturePlot(String entry, String first, String second) throws Exception {
        Term term = Parser.parse(Notation.normalize(entry));
        List<String> variables = Plottable.variablesIn(term);
        if (variables.isEmpty() || variables.size() > 2) {
            System.out.println("nothing to plot: " + variables.size() + " variables in " + entry);
            return;
        }
        Plottable plottable = Plottable.read(term, variables);
        if (!plottable.ok()) {
            System.out.println("nothing to plot: " + plottable.refusal());
            return;
        }
        // A real window memory, over the real settings: it is only ever read here, since a capture opens no
        // window to place and nothing on this path calls watch, poll or save.
        PlotWindow plot = new PlotWindow("capture", new WindowMemory(Settings.open(APP_NAME)));
        plot.headless(entry, term, plottable);
        GuiApp.capture(plot.gui(), PLOT_W, PLOT_H, 0.06f, 0.07f, 0.09f, first);
        plot.settle();
        GuiApp.capture(plot.gui(), PLOT_W, PLOT_H, 0.06f, 0.07f, 0.09f, first);
        // Two paints' worth: the layout watch draws the plot the moment the first frame gives the canvas a
        // size, and the handshake above draws it again to be certain before the photograph. The second is
        // entirely cache hits, which is why this line reads half-and-half.
        System.out.println("  framed     " + plot.cacheReport());
        // Then the half that would otherwise never be exercised without a pointer, and the numbers that say
        // whether the cache is doing what this whole design is for. On a curve: a zoom lands on a scale nothing
        // is cached at and pays for every column; the pan after it moves within that scale and should pay for
        // almost none; and going home returns to a scale already visited and should pay for nothing at all. On
        // a surface the middle step turns the picture instead of panning it, which is the stronger claim of the
        // two -- turning re-projects and re-sorts and must evaluate nothing whatsoever.
        plot.zoomTo(4);
        System.out.println("  zoomed in  " + plot.cacheReport());
        plot.panBy(0.4);
        System.out.println("  moved      " + plot.cacheReport());
        GuiApp.capture(plot.gui(), PLOT_W, PLOT_H, 0.06f, 0.07f, 0.09f, second);
        plot.goHome();
        System.out.println("  back home  " + plot.cacheReport());
        // And the half that needs a pointer. A capture has no input backend and so no pointer, but the hover
        // path can still be walked from its own end: point at the first marker, and photograph what it says.
        if (plot.hoverMark()) {
            GuiApp.capture(plot.gui(), PLOT_W, PLOT_H, 0.06f, 0.07f, 0.09f, "plot-landmark.png");
            System.out.println("  landmark   named");
        }
        plot.gui().close();
        System.out.println("captured " + entry);
    }

    /**
     * Open {@code entry} in a preview, already marched — what {@code --march} does.
     *
     * <p>Refusals are reported and then dropped rather than aborting the launch: the calculator is opening
     * either way, and an expression that cannot be marched is a reason to see the keypad, not to see nothing.
     */
    private static void openMarched(Previews previews, String entry) {
        try {
            Term term = Parser.parse(Notation.normalize(entry));
            List<String> variables = Plottable.variablesIn(term);
            if (variables.size() != 2) {
                System.out.println("nothing to march: " + entry + " needs two variables to be a surface");
                return;
            }
            Plottable plottable = Plottable.read(term, variables);
            if (!plottable.ok()) {
                System.out.println("nothing to march: " + plottable.refusal());
                return;
            }
            previews.showMarched(entry, term, plottable);
        } catch (RuntimeException e) {
            System.out.println("nothing to march: " + e.getMessage());
        }
    }

    /**
     * The same expression the plot would draw, drawn instead by a shader compiled from it.
     *
     * <p>{@code mvn compile exec:exec "-Dapp.args=--capture-sdf"} writes {@code sdf.png}; append
     * {@code =EXPRESSION} to choose one, in ASCII, for the same console-codepage reason the other captures
     * carry. Everything up to {@link Plottable} is shared with {@code --capture-plot} — the same parse, the
     * same refusals, the same one-or-two-variable rule — so an expression that will not plot will not march
     * either, and says so in the same words.
     */
    private static void captureSdf(String entry, String path) throws Exception {
        Term term = Parser.parse(Notation.normalize(entry));
        List<String> variables = Plottable.variablesIn(term);
        if (variables.isEmpty() || variables.size() > 2) {
            System.out.println("nothing to march: " + variables.size() + " variables in " + entry);
            return;
        }
        Plottable plottable = Plottable.read(term, variables);
        if (!plottable.ok()) {
            System.out.println("nothing to march: " + plottable.refusal());
            return;
        }
        SdfSurface surface = SdfSurface.of(plottable.expr(), variables);
        if (!surface.ok()) {
            System.out.println("nothing to march: " + surface.refusal());
            return;
        }
        System.out.println("marching " + entry);
        // One photograph per render style, because a style is compiled into the shader rather than switched at
        // runtime -- so "does Height look right" is a question about a different SPIR-V module, and a capture
        // that only ever built one of them would not be asking it.
        for (MarchStyle style : MarchStyle.values()) {
            String file = style == MarchStyle.LIT ? path
                    : path.replace(".png", "-" + style.label().toLowerCase() + ".png");
            System.out.println("  " + style.label());
            SdfPreview.capture(surface, style, file);
            System.out.println("  captured " + new java.io.File(file).getAbsolutePath());
        }
    }

    /** The expression a {@code --capture-*} flag names, or the default that flag stands for. */
    private static String entryOf(String arg, String fallback) {
        return arg.contains("=") ? arg.substring(arg.indexOf('=') + 1) : fallback;
    }

    private static void zoomShortcuts(Gui gui) {
        gui.zoomRange(0.5f, 3f, 1.25f);
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
    }

    private static Tactroller openInput() {
        try {
            Tactroller t = Tactroller.open();
            System.out.println("input: " + t.backendName());
            return t;
        } catch (BackendException e) {
            System.out.println("input unavailable (" + e.getMessage() + "); running without pointer input");
            return null;
        }
    }

    /** CLIENT space, density left at 1.0 — the engine's canvas is logical; see vexelray-gui-demo's attachInput. */
    private static void attachInput(Tactroller input, GuiApp app, Gui gui) {
        if (input == null) {
            return;
        }
        try {
            input.attach(NativeWindow.ofHwnd(app.windowHandle()));
            input.setCoordinateSpace(CoordinateSpace.CLIENT);
            holdPointerFor(gui, input);
        } catch (BackendException e) {
            System.out.println("input attach failed (" + e.getMessage() + "); pointer input disabled");
        }
    }

    /**
     * Carry out what {@code gui} asks of the pointer. The framework declares that a gesture wants the pointer
     * held and cannot reach an OS to arrange it; this is the application edge that can, and it is the same
     * shape as mapping a {@code CursorShape} onto a window's cursor.
     *
     * <p>{@link sibarum.tactroller.api.PointerLockMode#RECENTER} rather than {@code RAW}: it warps the cursor
     * back to the window's middle on every drain using ordinary cursor calls, so it needs no raw-input pump,
     * and its one drawback — the motion passes through the OS acceleration curve — is not a drawback for a
     * gesture that is <em>meant</em> to feel like the pointer, only unbounded. RAW is the right choice for a
     * camera in a game and the wrong one for a plot, where a drag should move the picture exactly as far as
     * the same drag would have moved the pointer.
     *
     * <p>A backend without pointer lock simply does not lock, and the drag still works — it can just run out of
     * desk at the window's edge, which is where it was before any of this.
     */
    private static void holdPointerFor(Gui gui, Tactroller input) {
        if (!input.supportsPointerLock()) {
            return;
        }
        gui.onPointerLock(hold -> {
            try {
                if (hold) {
                    input.lockPointer(sibarum.tactroller.api.PointerLockMode.RECENTER);
                } else {
                    input.unlockPointer();
                }
            } catch (BackendException e) {
                // The gesture is not worth failing over: without the lock it is an ordinary bounded drag.
            }
        });
    }

    /**
     * The {@link dev.vexelray.gui.core.app.WindowInput.Factory} the framework opens every non-main window
     * with: a second backend on that window's handle, bridged onto that window's bus, pumped once a frame and
     * closed with it. The framework cannot name the bridge itself — that is the layering rule — so it asks
     * here, once, and no window opened afterwards needs any input bookkeeping of its own.
     */
    private static dev.vexelray.gui.core.app.WindowInput attachWindowInput(dev.vexelray.os.NativeWindow window,
                                                                          Gui windowGui) {
        try {
            Tactroller opened = Tactroller.open();
            opened.attach(NativeWindow.ofHwnd(window.osHandle()));
            opened.setCoordinateSpace(CoordinateSpace.CLIENT);
            holdPointerFor(windowGui, opened);
            TactrollerInputBridge bridge = new TactrollerInputBridge(opened, windowGui.bus());
            return new dev.vexelray.gui.core.app.WindowInput() {
                @Override
                public void pump() {
                    CalculatorApp.pump(bridge);
                }

                @Override
                public void close() {
                    try {
                        opened.close();
                    } catch (Exception e) {
                        // best effort — the backend is going away with its window regardless
                    }
                }
            };
        } catch (BackendException e) {
            System.out.println("window input unavailable (" + e.getMessage() + "); that window takes no input");
            return dev.vexelray.gui.core.app.WindowInput.NONE;
        }
    }

    /**
     * OS clipboard for the display field's cut/copy/paste. Without this, {@link Gui} keeps its
     * default in-memory clipboard, so a copy never leaves the process — you can paste a result back
     * into the calculator but not into anything else.
     */
    private static Clipboard openClipboard(Gui gui) {
        try {
            Clipboard clip = Clipboard.open();
            gui.clipboard(new TextClipboard() {
                @Override
                public String get() {
                    try {
                        return clip.getText().orElse("");
                    } catch (ClipboardException e) {
                        return "";
                    }
                }

                @Override
                public void set(String text) {
                    try {
                        clip.setText(text);
                    } catch (ClipboardException e) {
                        // best effort — a transient clipboard failure just drops the copy
                    }
                }
            });
            System.out.println("clipboard: " + clip.backendName());
            return clip;
        } catch (ClipboardException e) {
            System.out.println("clipboard unavailable (" + e.getMessage() + "); cut/copy/paste use in-memory buffer");
            return null;
        }
    }

    private static void pump(TactrollerInputBridge bridge) {
        if (bridge == null) {
            return;
        }
        try {
            bridge.pump();
        } catch (BackendException e) {
            // Transient poll failure — drop this frame's input rather than tear down the loop.
        }
    }

    /**
     * The calculator as a window on somebody else's application: its own {@link Gui}, opened under a name on a
     * {@link GuiApp} that already exists and already has a frame loop.
     *
     * <h2>Why this is not main()</h2>
     * {@link CalculatorApp#main} is one arrangement of the calculator — the one where it is the program, owns
     * the loop, and everything else on the desk is something it opened. This is the other arrangement, where
     * MainFrame is the program and the calculator is one of the things <em>it</em> opens. Same tree, same
     * engine, same history and the same previews; what differs is who owns the window and who is ticking.
     *
     * <p>So it is a nested class rather than a file of its own. Everything it needs -- {@code buildUi}, the
     * {@code Engine}, the {@code History}, the sizes -- is private to {@link CalculatorApp} and stays that way,
     * and the two arrangements cannot drift apart because they are built out of the same parts.
     *
     * <p><b>The session outlives the window,</b> as the console's does: the tree belongs to this object, so
     * closing the calculator releases an OS window and leaves the tape, the entry and the zoom where they were.
     *
     * <p>All methods run on the frame loop.
     */
    public static final class Window {

        /** The name this window is opened, raised and remembered under. */
        private static final String KEY = "calculator";

        private final WindowMemory memory;
        private final Gui gui = new Gui();
        private final Ui ui;
        private final Engine engine;

        private History history;
        private AppWindow handle;

        /**
         * Build the calculator, without opening anything.
         *
         * @param memory where this window's placement, size and zoom are kept -- shared with whatever else is
         *               on this desk, because a window memory is one file and one key per window
         */
        public Window(WindowMemory memory) {
            this.memory = memory;
            // Two em more than the keypad needs on its own: the title bar sits inside the canvas, so the
            // smallest layout has to hold it as well as the display and the keys.
            gui.minSize(Length.em(21), Length.em(32));
            this.ui = buildUi(gui);
            this.engine = ui.engine();
            zoomShortcuts(gui);
        }

        /** This window's Gui, so the host can bind its clipboard and its shortcuts here too. */
        public Gui gui() {
            return gui;
        }

        /** Whether the window is up right now. */
        public boolean open() {
            return handle != null && handle.open();
        }

        /**
         * Open the calculator on {@code app}, or raise it if it is already up.
         *
         * <p>The history and the previews are wired on the first call rather than in the constructor, because
         * both of them open windows and neither can be given an application before there is one. After that
         * this is one {@code show()}: asking for the calculator has to mean <em>the</em> calculator.
         */
        public void show(GuiApp app) {
            if (history == null) {
                history = new History(engine, app, memory);
                engine.history(history);
                zoomShortcuts(history.windowGui());
                // Each preview is built the first time its slot is used, and gets the same zoom shortcuts every
                // other window here has -- the only thing the calculator says about a window it did not know it
                // was going to open.
                engine.plotter(new Previews(app, memory, CalculatorApp::zoomShortcuts));
            }
            if (handle == null) {
                handle = app.window(KEY, () -> WindowSpec
                        .of(memory.config(KEY, "Calculator", W, H).decorations(Decorations.CLIENT), gui)
                        .onCreated(this::onCreated)
                        .onClosed(this::onClosed));
            }
            handle.show();
        }

        /**
         * Frame loop, once per frame. The history's queue is drained here whether or not the window is open,
         * since an evaluation recorded just before a close still has to land somewhere.
         */
        public void tick() {
            if (history != null) {
                history.drain();
            }
        }

        /**
         * The window exists, and its input is already attached and pumping -- the host did that from the
         * factory it gave the framework. What is left is what only this window knows: which window its own
         * title bar commands, where it should be, and what it was zoomed to.
         */
        private void onCreated(dev.vexelray.os.NativeWindow created) {
            ui.titleBar().controls(WindowControls.of(created));
            if (memory.maximized(KEY)) {
                created.maximize();
            } else {
                memory.restoreBounds(KEY, created, W, H);
            }
            // Watched with its tree, so the UI zoom is remembered too: Ctrl+= is the same kind of decision as
            // dragging the window bigger, and losing it on quit is the same loss.
            memory.watch(KEY, created, gui);
        }

        /** The window is gone; the calculator is not. What was recorded last stands. */
        private void onClosed() {
            memory.forget(KEY);
            ui.titleBar().controls(WindowControls.NONE);
        }
    }

    /** What main() needs back from the tree: the calculator, and the chrome awaiting its window. */
    private record Ui(Engine engine, TitleBar titleBar) {
    }

    private static Ui buildUi(Gui gui) {
        // The display is an editable field, not a label: type the expression directly, or build it
        // from the keypad, or mix the two. Enter evaluates, exactly like the "=" key.
        TextField display = new TextField(gui, "");
        display.node()
                .width(Length.FILL).height(Length.rem(5))
                .background(PANEL).corner(Length.rem(0.75f)).border(Length.rem(0.1f), LINE)
                .lit(true).elevation(Length.rem(0.5f))
                .padding(Length.dp(16))
                .textSize(Length.rem(1.75f)).textColor(INK);

        Engine engine = new Engine(display);
        gui.focusable(display.node(), true);
        gui.focus(display.node());
        display.onSubmit(s -> engine.press("="));
        // Typing w yields omega. Substituting in onChange re-enters once and then terminates,
        // since the replacement contains no w. text() parks the caret at the end, so put it back —
        // w and ω are one char each, so every offset survives the substitution unchanged.
        display.onChange(s -> {
            if (s.indexOf('w') >= 0) {
                int at = display.caret();
                display.text(s.replace('w', 'ω'));
                display.caret(at);
            }
        });

        // The keypad: rows of flex-grown buttons, no hard-coded rects anywhere. Beyond digits: the
        // constants e/i/π, the wheel's ω (= 1/0), plotting variables x/y/z, ^ for n^x, and log(x, n)
        // for log base n (via the log/comma/paren keys).
        // There is one engine, so there is no engine key: COTT-ONE answers every expression the
        // keypad can build. The freed slot went back to the row.
        String[][] rows = {
                {"C", "DEL", "(", ")", "÷"},
                {"7", "8", "9", "^", TIMES},
                {"4", "5", "6", "log", "−"},
                {"1", "2", "3", ",", "+"},
                {"0", ".", "x", "y", "z"},
                {"e", "i", "π", "ω", "="},
        };
        Node pad = gui.column().width(Length.FILL).height(Length.FILL).gap(Length.rem(0.5f));
        for (String[] row : rows) {
            Node r = gui.row().width(Length.FILL).height(Length.grow(1)).gap(Length.rem(0.5f))
                    .alignItems(AlignItems.STRETCH);
            for (String label : row) {
                boolean accent = label.equals("=");
                boolean op = java.util.Set.of("÷", TIMES, "−", "+", "^", "log", ",", "(", ")", "C", "DEL")
                        .contains(label);
                Node b = key(gui, label,
                        accent ? Color.WHITE : (op ? DIM : INK),
                        accent ? BTN_BLUE : PANEL,
                        accent ? BTN_BLUE_HOVER : PANEL_HOVER,
                        accent ? BTN_BLUE_PRESSED : PANEL_PRESSED);
                if (accent) {
                    b.textSunken(true);
                }
                gui.onClick(b, () -> engine.press(label));
                r.append(b);
            }
            pad.append(r);
        }

        // A rejection is reported here rather than in the entry, so the expression survives it and
        // can be fixed and re-evaluated without retyping. A notification does not belong in the
        // field you type into -- that was the whole trouble with the old engine announcement.
        Node statusText = gui.text("")
                .width(Length.FILL).height(Length.rem(1.25f))
                .textSize(Length.rem(0.875f)).textColor(BTN_BLUE_HOVER)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)
                .scroll(false, false);
        engine.statusLabel(statusText);

        Node root = gui.column().width(Length.FILL).height(Length.FILL)
                .padding(Length.dp(16)).gap(Length.rem(0.75f))
                .children(display.node(), statusText, pad);
        // The window's own title bar: ordinary widgets, plus the two declarations that tell the window
        // manager which pixels are caption (DRAG on the strip, INTERACTIVE on each button). Bound to the
        // real window in main(); here it commands WindowControls.NONE, which is what --capture draws.
        TitleBar titleBar = new TitleBar(gui, WindowControls.NONE, "Calculator");
        gui.root().background(BG).children(titleBar.node(), root);
        return new Ui(engine, titleBar);
    }

    /**
     * The evaluation history as its own OS window on the shared frame loop, opened on the first
     * evaluation and never before -- there is nothing to show until then. Same shape as the text
     * editor's folder window: a second {@link Gui}, opened through {@link GuiApp#requestPopup},
     * with its own input backend attached to its own window handle and bridged onto its own bus.
     *
     * <p>Every entry records the input and the output. Clicking one puts the <em>input</em> back,
     * not the output -- a residue like {@code 1^t} re-reads as an ordinary power and so projects
     * rather than round-tripping, and an unreduced form may not re-parse at all.
     *
     * <p>All methods run on the main thread, driven from {@link History}'s drain.
     */
    private static final class HistoryWindow {
        /** One evaluation. */
        record Entry(String input, String output) { }

        private static final int LIMIT = 100;
        private static final int W = 320;
        private static final int H = 480 + BAR_H;

        private final java.util.function.Consumer<Entry> restore;
        private final WindowMemory memory;
        private final Gui gui = new Gui();
        private final Node list;
        private final TitleBar titleBar;
        private final java.util.ArrayDeque<Node> rows = new java.util.ArrayDeque<>();
        private Tactroller input;
        private TactrollerInputBridge bridge;
        private boolean shown;

        HistoryWindow(java.util.function.Consumer<Entry> restore, WindowMemory memory) {
            this.restore = restore;
            this.memory = memory;
            Node heading = gui.text("History")
                    .width(Length.FILL).height(Length.rem(1.5f))
                    .textSize(Length.rem(0.875f)).textColor(DIM)
                    .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE);
            // Newest at the bottom, and the list stays pinned there as it grows -- a calculator
            // tape. scrollLock detaches if you scroll up to read, and re-attaches at the edge.
            this.list = gui.column().width(Length.FILL).height(Length.grow(1))
                    .gap(Length.rem(0.375f))
                    .scroll(false, true)
                    .scrollLock(LayoutEnums.ScrollLock.BOTTOM);
            Node column = gui.column().width(Length.FILL).height(Length.FILL)
                    .padding(Length.dp(12)).gap(Length.dp(8))
                    .children(heading, list);
            // The popup draws its own frame too, so the two windows match. Its bar is bound in onCreated:
            // a popup's window does not exist until the main thread services the request, and it is that
            // window the buttons command -- not the main one app.controls() would hand over.
            this.titleBar = new TitleBar(gui, WindowControls.NONE, "History");
            gui.root().background(BG).children(titleBar.node(), column);
            // Zoom is per-window: the history scales independently of the keypad.
            zoomShortcuts(gui);
        }

        /** This window's Gui, so the app can bind shortcuts here too. */
        Gui gui() {
            return gui;
        }

        /** Append {@code e}, opening the window on the next frame if this is the first evaluation. */
        void add(GuiApp app, Entry e) {
            list.append(row(e));   // row() records the node in `rows` as it builds it
            while (rows.size() > LIMIT) {
                rows.removeFirst().remove();
            }
            if (!shown) {
                shown = true;
                app.requestPopup(memory.config("history", "History", W, H).decorations(Decorations.CLIENT),
                        gui, this::onCreated, this::onClosed);
            }
        }

        /**
         * One clickable entry: the engine, then the input, then the result, each on its own full-width
         * line. Every height is {@link Length#AUTO} so the card grows to hold whatever it contains --
         * a fixed height cannot work, since the result is rendered larger than the input, both scale
         * with zoom, and an expression is as long as it is.
         *
         * <p><b>Three stacked lines rather than a text column beside an engine tag.</b> A label always
         * wraps ({@code wrapsText} is unconditional for a non-editable node), but a label that is a
         * flex child of a <em>row</em> does not contribute its wrapped height to that row's auto
         * cross-axis -- the row measures one line and the rest spills past its border. Keeping every
         * label a child of a column, at the full content width, is what makes the wrap measurable.
         *
         * <p>Note also the explicit {@code scroll(false, false)}: overflow scrolling is on by default,
         * so a squeezed container grows its own scrollbar instead of reporting the overflow.
         */
        private Node row(Entry e) {
            Node expr = line(e.input(), 0.8125f, DIM, TextLayout.HAlign.LEFT);
            Node out = line(e.output(), 1f, INK, TextLayout.HAlign.LEFT);
            Node r = gui.column().width(Length.FILL).height(Length.AUTO)
                    .padding(Length.rem(0.625f), Length.rem(0.75f))
                    .gap(Length.rem(0.1875f))
                    .background(PANEL).corner(Length.rem(0.5f)).border(Length.rem(0.1f), LINE)
                    .lit(true).elevation(Length.rem(0.25f))
                    .scroll(false, false)
                    .children(expr, out);
            gui.onState(r, state -> r.background(switch (state) {
                case NORMAL -> PANEL;
                case HOVER -> PANEL_HOVER;
                case PRESSED -> PANEL_PRESSED;
            }));
            gui.onClick(r, () -> restore.accept(e));
            rows.addLast(r);
            return r;
        }

        /** One full-width line of an entry, sized to its own wrapped text. */
        private Node line(String text, float rem, Color colour, TextLayout.HAlign align) {
            return gui.text(text)
                    .width(Length.FILL).height(Length.AUTO)
                    .textSize(Length.rem(rem)).textColor(colour)
                    .align(align, TextLayout.VAlign.MIDDLE)
                    .scroll(false, false);
        }

        /**
         * The popup exists: attach its input and point its chrome at it. Runs on the main thread, before
         * the window is first presented, so the bar is never drawn commanding nothing.
         */
        private void onCreated(dev.vexelray.os.NativeWindow window) {
            attachInput(window.osHandle());
            titleBar.controls(WindowControls.of(window));
            // The config this window was created from is the one built the first time it opened; the bounds
            // worth restoring may be from later in the same session -- move the history, close it, evaluate
            // again. Correcting here, before the first frame, is what makes that not a visible jump.
            if (memory.maximized("history")) {
                window.maximize();
            } else {
                memory.restoreBounds("history", window, W, H);
            }
            memory.watch("history", window, gui);
        }

        /** Attach a second input backend to the popup's own window handle, feeding this Gui's bus. */
        private void attachInput(long hwnd) {
            try {
                input = Tactroller.open();
                input.attach(NativeWindow.ofHwnd(hwnd));
                input.setCoordinateSpace(CoordinateSpace.CLIENT);
                bridge = new TactrollerInputBridge(input, gui.bus());
            } catch (BackendException e) {
                System.out.println("history window input unavailable (" + e.getMessage() + ")");
                closeInput();
            }
        }

        private void onClosed() {
            closeInput();
            // The window this bar commanded is gone; the tree outlives it and is shown again on the next
            // evaluation, so the buttons go back to commanding nothing until onCreated rebinds them.
            titleBar.controls(WindowControls.NONE);
            memory.forget("history");   // its last recorded bounds are the ones to keep
            shown = false;
        }

        private void closeInput() {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception e) {
                    // best effort — the backend is going away regardless
                }
                input = null;
                bridge = null;
            }
        }

        /** Poll the popup's input, if the window is up — called once per frame with the main pump. */
        void pump() {
            if (bridge != null) {
                try {
                    bridge.pump();
                } catch (BackendException e) {
                    // Transient poll failure — drop this frame's input rather than tear down the loop.
                }
            }
        }
    }

    /**
     * The history's request queue. Evaluations arrive from keypad handlers on worker threads, and
     * history clicks arrive from the popup's own handlers -- but node creation and
     * {@link GuiApp#requestPopup} belong to the GUI thread, so both only enqueue and
     * {@link #drain()} services them from the frame loop.
     */
    private static final class History {
        private final Engine engine;
        private final GuiApp app;
        private final HistoryWindow window;
        private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> requests =
                new java.util.concurrent.ConcurrentLinkedQueue<>();

        History(Engine engine, GuiApp app, WindowMemory memory) {
            this.engine = engine;
            this.app = app;
            this.window = new HistoryWindow(this::restore, memory);
        }

        Gui windowGui() {
            return window.gui();
        }

        /** Worker thread: an expression was evaluated. */
        void record(String input, String output) {
            requests.add(() -> window.add(app, new HistoryWindow.Entry(input, output)));
        }

        /** Popup handler thread: an entry was clicked. */
        private void restore(HistoryWindow.Entry e) {
            requests.add(() -> engine.restore(e.input()));
        }

        /** GUI thread, once per frame. */
        void drain() {
            window.pump();
            Runnable r = requests.poll();
            if (r != null) {
                r.run();
            }
        }
    }

    /** One keypad button: lit, elevated, restyled per interaction state. */
    private static Node key(Gui gui, String label, Color fg, Color base, Color hover, Color pressed) {
        Node b = gui.text(label).width(Length.grow(1)).height(Length.FILL)
                .background(base).corner(Length.rem(0.625f)).border(Length.rem(0.1f), LINE)
                .textSize(Length.rem(1.25f)).textColor(fg)
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .lit(true).elevation(Length.rem(0.375f));
        gui.onState(b, state -> {
            b.background(switch (state) {
                case NORMAL -> base;
                case HOVER -> hover;
                case PRESSED -> pressed;
            });
            b.elevation(switch (state) {
                case NORMAL -> Length.rem(0.375f);
                case HOVER -> Length.rem(0.625f);
                case PRESSED -> Length.ZERO;
            });
        });
        return b;
    }

    /**
     * The keypad and entry controller. It owns the display field, the caret-aware key handling and
     * the status line; {@link Cott} owns the mathematics, and this class knows nothing about it
     * beyond handing over a string and getting one back.
     *
     * <p>Handlers arrive on worker threads, so all state transitions are synchronized; the only
     * output is the display handle, which is thread-safe by framework contract.
     */
    private static final class Engine {
        private final TextField display;
        private boolean justEvaluated;
        /** The line that reports a rejection. */
        private volatile Node statusLabel;
        /** Where evaluations are recorded, once there is a window loop to open onto. */
        private volatile History history;
        /** Where a plotted expression opens a window. Null until main() has a window loop to open onto. */
        private volatile Previews previews;

        Engine(TextField display) {
            this.display = display;
        }

        void statusLabel(Node status) {
            this.statusLabel = status;
        }

        /** Report a rejection, or clear the report. Never touches the entry. */
        private void status(String message) {
            Node s = statusLabel;
            if (s != null) {
                s.text(message);
            }
        }

        void history(History history) {
            this.history = history;
        }

        /** Where a plottable expression goes. Null under {@code --capture}, which has no window loop. */
        void plotter(Previews previews) {
            this.previews = previews;
        }

        /**
         * An evaluated expression, offered to the plotter. <b>How many variables it has is the whole
         * decision</b>, and it is taken on the term the user typed rather than on the reduced one — COTT
         * leaves {@code x÷x} standing as a residue whose variable has become part of a form, and the curve
         * being asked about is the one that was written down.
         *
         * <ul>
         *   <li><b>None</b> — a number was evaluated. There is nothing to plot and nothing to say about it,
         *       so nothing is said: an arithmetic result must not come with a notice attached.
         *   <li><b>One</b> — a curve, against that variable whatever it is called. x is the usual one and
         *       nothing here depends on it being x.
         *   <li><b>Two</b> — a surface, in three dimensions, over the two of them in the order they are named.
         *   <li><b>Three or more</b> — reported on the status line rather than half drawn by pinning the rest
         *       to zero, which would be a picture of a different expression. The count is where the line
         *       falls, and it falls there because there is no third dimension left to put a third variable on.
         * </ul>
         *
         * <p>Either way it opens a <b>preview of its own</b> — see {@link Previews}.
         */
        private void offerPlot(String entry, Term term) {
            Previews windows = previews;
            if (windows == null) {
                return;
            }
            List<String> variables = Plottable.variablesIn(term);
            if (variables.isEmpty()) {
                return;
            }
            if (variables.size() > 2) {
                status(variables.size() + " variables (" + String.join(", ", variables)
                        + ") -- the plotter takes one or two");
                return;
            }
            Plottable plottable = Plottable.read(term, variables);
            if (plottable.ok()) {
                windows.show(entry, term, plottable);
            } else {
                status(plottable.refusal());
            }
        }

        /** Put the entry back as it was, from a history click. */
        synchronized void restore(String entry) {
            display.text(entry);
            justEvaluated = false;
        }

        /**
         * A key press edits the field where the caret is, so the keypad and the keyboard are the
         * same editor: click into the middle of an expression and "9" lands there, not at the end.
         * Only the whole-entry keys (C, =, and DEL after a result) replace the content.
         */
        synchronized void press(String label) {
            // The field is the source of truth -- it may have been typed or clicked into since the
            // last keypad press, so read the document rather than tracking a shadow copy. One
            // snapshot for the whole press: text and caret have to come from the same version.
            Document doc = display.document().value();
            switch (label) {
                case "C" -> { display.text(""); justEvaluated = false; }
                case "DEL" -> {
                    if (justEvaluated) { display.text(""); justEvaluated = false; }
                    else { display.deleteBack(); }   // backspaces at the caret, or eats the selection
                }
                case "=" -> {
                    if (!doc.text().isEmpty()) {
                        String entry = doc.text();
                        try {
                            // Parsed here rather than through Cott.evaluate, which parses too: the term is
                            // what the plotter reads its variables out of, and re-parsing to get it would
                            // let the two disagree about what was typed.
                            Term term = Parser.parse(Notation.normalize(entry));
                            String result = Render.show(Cott.reduce(term));
                            status("");
                            display.text(result);
                            justEvaluated = true;
                            History h = history;
                            if (h != null) {
                                h.record(entry, result);
                            }
                            offerPlot(entry, term);
                        } catch (SyntaxException e) {
                            // A rejection is REPORTED, never written into the field: the entry stays
                            // put so the expression can be fixed and "=" pressed again without
                            // retyping it, which a message sitting in the display would prevent.
                            status(e.getMessage());
                        } catch (RuntimeException e) {
                            status("Error");   // an evaluator fault; the entry still survives it
                        }
                    }
                }
                // An operator goes in verbatim; anything else is an operand and may need a sign in front
                // of it. This is a set rather than a list of case labels because one of the operators is
                // the engine's TIMES, which is a value and not a compile-time constant -- and having the
                // glyph in exactly one place is the whole reason it is a value.
                default -> {
                    if (OPERATORS.contains(label)) {
                        display.insert(label);
                        justEvaluated = false;
                    } else {
                        insertOperand(label, doc);
                    }
                }
            }
        }

        /**
         * An operand token -- digit, ., (, log(, e, i, π, ω, x, y, z -- inserted at the caret, with
         * an implicit multiplication sign when it lands directly after something that ends an operand.
         */
        private void insertOperand(String label, Document doc) {
            String token = label.equals("log") ? "log(" : label;
            if (justEvaluated) {
                display.text("");
                doc = display.document().value();
            }
            // The character to the left of where the insert lands, which is selectionStart rather
            // than the caret: inserting over a selection replaces it.
            int at = doc.selectionStart();
            // The same two character sets the engine uses to put this sign back when a TYPED expression
            // is read, and to leave it out when a result is printed. One definition, in Notation, is
            // what keeps the keypad, the keyboard and the display agreeing.
            if (at > 0 && Notation.endsOperand(doc.text().charAt(at - 1)) && !Notation.digitLike(token)) {
                token = TIMES + token;   // implicit multiplication: 2π, xy, 3(x+1), ω(...)
            }
            display.insert(token);
            justEvaluated = false;
        }
    }
}
