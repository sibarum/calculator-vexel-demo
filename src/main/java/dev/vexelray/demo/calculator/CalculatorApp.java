package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.WindowRegion;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.core.text.Document;
import dev.vexelray.gui.widget.Ramp;
import dev.vexelray.gui.widget.Tabs;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.text.TextLayout;
import java.util.List;
import sibarum.cott.Bindings;
import sibarum.cott.Cott;
import sibarum.cott.Notation;
import sibarum.cott.Parser;
import sibarum.cott.Real;
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
 * <p>Run: {@code CalculatorApp} (windowed), {@code CalculatorApp --march[=EXPRESSION]} (windowed, with that
 * expression already plotted), or {@code CalculatorApp --capture[=SCENE[=SUBJECT]]} (headless). There is one
 * capture mode and it holds one application; the scenes are listed in {@link Capture}, which also says why
 * there used to be five of these and what went wrong with having five. Needs
 * {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class CalculatorApp {

    /**
     * Window and capture size, in the engine's logical coordinates. The GUI draws the frame, so the client area
     * is the whole window and the height carries {@link #BAR_H} of the application's own title bar on top of
     * the keypad's own — 640 of it since the tab strip arrived, where it was 600 for six rows and a display.
     */
    static final int W = 420;
    /** The title bar's own height, in dp -- {@code TitleBar}'s, which is the Windows caption metric. */
    static final int BAR_H = 32;
    static final int H = 640 + BAR_H;

    /**
     * The smallest the calculator may be, in em, and it is asked of two different things.
     *
     * <p>{@code Gui.minSize} is the smallest <b>canvas the UI is laid out on</b>: below it the layout keeps
     * running at the minimum and the window shows part of it, rather than six rows of keys each absorbing a
     * sixth of the deficit until they have no height left. {@code WindowConfig.minSize} is the smallest the
     * <b>window manager will let the drag reach</b>, so the case above stops arising in the first place.
     *
     * <p>Both from these two numbers, which is the point of having them here: a window that stops at one size
     * and a layout that gives up at another would be a gap of exactly the width of the disagreement, and the
     * bug would only show at the edge of a drag. The window's copy is read at zoom 1 -- see {@link #smallest} --
     * while the layout's grows with the zoom, which is the right way round: a zoomed UI really does need more
     * room, and the window manager cannot be told so after the window was created.
     */
    static final float MIN_EM_W = 21f;
    static final float MIN_EM_H = 34f;

    /**
     * {@code em} as a whole number of pixels at zoom 1 — how a {@code Gui} minimum is said to a
     * {@code WindowConfig}, which takes pixels and is settled once, when the window is created.
     */
    static int smallest(Gui gui, float em) {
        return Math.round(em * gui.rootEmPx());
    }

    /** The plot window's size, and the size the plotting scenes photograph it at. */
    static final int PLOT_W = 720;
    static final int PLOT_H = 560 + BAR_H;

    /**
     * The multiplication key's label, and the sign it inserts. Taken from the engine rather than written
     * here, because the keypad and the printer disagreeing about it is exactly the failure
     * {@link Notation} exists to prevent — a display the calculator cannot read back.
     */
    static final String TIMES = String.valueOf(Notation.TIMES);

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

        // Every headless photograph, in one mode over one application -- see Capture, which says at length
        // why there used to be five of these and why one of them could not draw a name this session had
        // defined. Checked before anything below is built, because Capture builds its own world: the same
        // one this method goes on to build, and handing it a half-made one is how the five drifted apart.
        if (args.length >= 1 && args[0].startsWith("--capture")) {
            String flag = args[0];
            if (flag.equals("--capture")) {
                Capture.run(null);
            } else if (flag.startsWith("--capture=")) {
                Capture.run(flag.substring("--capture=".length()));
            } else {
                // --capture-plot and its three siblings are scenes now rather than flags. Worth saying so
                // rather than silently running every scene, since the old spellings are in the README and
                // in muscle memory.
                System.out.println(flag + " is gone: the captures are one mode over one application now.");
                System.out.println("try --capture, or --capture=SCENE[=SUBJECT] with scenes: "
                        + Capture.names());
            }
            return;
        }

        Gui gui = new Gui();
        // The title bar sits inside the canvas now, so the smallest layout has to hold it as well as the
        // display, the tab strip and six rows of keys. See MIN_EM_W on why there is one pair of numbers.
        gui.minSize(Length.em(MIN_EM_W), Length.em(MIN_EM_H));
        // The clock, attached before the UI is built, because a widget that animates is handed its timing at
        // construction rather than asking for it later. The capture attaches one to every tree it builds; if
        // this path did not, the world a photograph is taken of would differ from the running program in
        // exactly the dimension the photographs are now for.
        KronoGui krono = KronoGui.attach(gui);
        Ui ui = buildUi(gui, Motion.of(krono));
        Engine engine = ui.engine();
        zoomShortcuts(gui);

        // A shortcut and nothing more: open windowed with this expression already plotted, which is what typing
        // it and pressing = does. A two-variable expression is marched either way -- that is the ordinary
        // behaviour of the plot now, not something this flag turns on.
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
                     .decorations(Decorations.CLIENT)
                     .minSize(smallest(gui, MIN_EM_W), smallest(gui, MIN_EM_H)))) {
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
            // The session's names, and the window that edits them. Built here for the same reason the history
            // is: it needs an application to open onto, and there is none until now.
            Definitions definitions = new Definitions(memory, engine::bindings);
            definitions.attach(app);
            zoomShortcuts(definitions.gui());
            engine.definitions(definitions);
            if (march != null) {
                // Safe before the loop starts: showing a preview only posts the window request, and the window
                // is created at the top of the first frame like any other.
                openMarched(engine, march);
            }
            TactrollerInputBridge bridge = input == null ? null : new TactrollerInputBridge(input, gui.bus());
            FpsProbe probe = new FpsProbe(krono.kron(), app::postWake, () -> gui.root().opacity(1f), gui.handlers());
            if (maxFrames <= 0) {
                // Render on demand: block until the kernel says a frame is due. Only on an uncapped run --
                // a frame cap is a script, and blocking would make N frames of a still window take forever.
                app.pacing(() -> krono.kron().sleepTimeout().nanos());
                app.idleRefresh(200_000_000L)   // 5 Hz floor while focused: a missed wake is late, never lost
                   .maxFrameRate(16_666_666L);  // 60 Hz ceiling while animating
            }
            try {
                app.run(gui, maxFrames, () -> {
                    krono.tick();
                    pump(bridge);
                    history.drain();
                    definitions.drain();
                    memory.poll();
                    // A marched viewport does its GPU work here and nowhere else: this hook runs on the thread
                    // that presents, and the device queue belongs to it.
                    previews.pump();
                    probe.sample();
                });
            } finally {
                probe.report("calculator, idle");
                // The debounce has no next frame to fire on once the loop is over, so the last move of the
                // session is written here or not at all.
                memory.save();
            }
        }
        krono.close();   // the clock outlives the window but not the process: closed with the GUI it drove
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
    static final String APP_NAME = "calculator";

    /**
     * Open {@code entry} in a preview, already marched — what {@code --march} does.
     *
     * <p>Refusals are reported and then dropped rather than aborting the launch: the calculator is opening
     * either way, and an expression that cannot be marched is a reason to see the keypad, not to see nothing.
     */
    private static void openMarched(Engine engine, String entry) {
        // Type it and press =, which is all this flag ever claimed to be. It used to parse the line itself
        // and hand the result to Previews, which meant --march=f(x) could not open a name this session had
        // defined -- the engine expands the session's names before parsing and this did not. That is the
        // same divergence Capture was written to end, and it was here too.
        engine.enter(entry);
        engine.press("=");
        // Refusals are reported and then dropped rather than aborting the launch: the calculator is opening
        // either way, and an expression that cannot be drawn is a reason to see the keypad, not to see
        // nothing. The engine has already put its own words on the status line; this is for the console.
        String refused = engine.reported();
        if (!refused.isEmpty()) {
            System.out.println("nothing to march: " + refused);
        }
    }

    /** The expression the {@code --march} flag names, or the default it stands for. */
    private static String entryOf(String arg, String fallback) {
        return arg.contains("=") ? arg.substring(arg.indexOf('=') + 1) : fallback;
    }

    static void zoomShortcuts(Gui gui) {
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
        /** This window's clock, ticked from {@link #tick}. See the note where main() attaches its own. */
        private final KronoGui krono;
        private final Ui ui;
        private final Engine engine;

        private History history;
        /** The preview windows, kept because they have per-frame GPU work -- see {@link #tick}. */
        private Previews previews;
        /**
         * The session's names. Built with this object rather than on the first {@link #show}, because a
         * definition is a fact about the session and not about a window -- and its edits are queued, so it is
         * kept here to be drained from {@link #tick}.
         */
        private final Definitions definitions;
        private AppWindow handle;

        /**
         * Build the calculator, without opening anything.
         *
         * @param memory where this window's placement, size and zoom are kept -- shared with whatever else is
         *               on this desk, because a window memory is one file and one key per window
         */
        public Window(WindowMemory memory) {
            this.memory = memory;
            // The title bar sits inside the canvas, so the smallest layout has to hold it as well as the
            // display, the tab strip and six rows of keys. See MIN_EM_W on why there is one pair of numbers.
            gui.minSize(Length.em(MIN_EM_W), Length.em(MIN_EM_H));
            this.krono = KronoGui.attach(gui);
            this.ui = buildUi(gui, Motion.of(krono));
            this.engine = ui.engine();
            zoomShortcuts(gui);
            // Built here and not on the first show(), unlike the history and the previews. Those need an
            // application before they can do anything at all; this one only needs an application to be *seen*,
            // and `calc "k = 3"` at a prompt is a definition whether or not the keypad has ever been opened.
            this.definitions = new Definitions(memory, engine::bindings);
            zoomShortcuts(definitions.gui());
            engine.definitions(definitions);
        }

        /** This window's Gui, so the host can bind its clipboard and its shortcuts here too. */
        public Gui gui() {
            return gui;
        }

        /**
         * What this session has named. Public so that a shell hosting the calculator can read an expression in
         * the same vocabulary the keypad does — a {@code calc} that agreed with the keys only until somebody
         * defined something would be worse than not having one.
         */
        public Bindings bindings() {
            return engine.bindings();
        }

        /**
         * Show the definitions window. Public because a host's menu is a fair second way in — the first is the
         * keypad's own tab strip — and because the names are what its prompt reads expressions in too.
         *
         * <p>Silent until the calculator has been opened once, which is when the window learns what application
         * to appear on. The definitions themselves exist from the start.
         */
        public void openDefinitions() {
            engine.openDefinitions();
        }

        /**
         * Name something from outside the keypad — a shell prompt — and get back the definition as it will be
         * listed. Exactly the act typing it into the display performs, through the same door, so the window
         * comes up here too.
         *
         * @throws SyntaxException with the reason, when the line is not a definition
         */
        public String define(String line) {
            Definitions.Made made = definitions.request(line);
            if (made.refusal() != null) {
                throw new SyntaxException(made.refusal());
            }
            return made.definition().source();
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
                previews = new Previews(app, memory, CalculatorApp::zoomShortcuts);
                engine.plotter(previews);
                // The definitions were built with this object; what they were missing is somewhere to appear.
                definitions.attach(app);
            }
            if (handle == null) {
                handle = app.window(KEY, () -> WindowSpec
                        .of(memory.config(KEY, "Calculator", W, H).decorations(Decorations.CLIENT)
                                .minSize(smallest(gui, MIN_EM_W), smallest(gui, MIN_EM_H)), gui)
                        .onCreated(this::onCreated)
                        .onClosed(this::onClosed));
            }
            handle.show();
        }

        /**
         * Frame loop, once per frame. The history's queue is drained here whether or not the window is open,
         * since an evaluation recorded just before a close still has to land somewhere.
         *
         * <p>The previews are pumped here for a stronger reason than tidiness: a marched viewport submits to the
         * device queue and waits, so it does its GPU work on the thread that presents and nowhere else. Hosted in
         * an application that owns the frame loop, this method <em>is</em> that hook -- the standalone entry point
         * calls {@code previews.pump()} from its own {@code beforeFrame} and the two have to agree, or a plot
         * opened from here comes up marched and never marches: the box view draws as ordinary canvas nodes and is
         * fine, while the viewport beside it stays the colour of an empty box.
         */
        public void tick() {
            krono.tick();
            if (history != null) {
                history.drain();
            }
            if (previews != null) {
                previews.pump();
            }
            definitions.drain();
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

    /**
     * What main() needs back from the tree: the calculator, the chrome awaiting its window, and the pads —
     * which are here for the capture, since a photograph has no pointer to click a tab with, and a pad nobody
     * can photograph is a pad nobody checks.
     */
    record Ui(Engine engine, TitleBar titleBar, Tabs pads) {
    }

    /**
     * The arithmetic pad. Beyond digits: the constants e/i/π, the wheel's ω (= 1/0), plotting variables x/y/z,
     * {@code ^} for n^x, and {@code log(x, n)} for COTT's base-0 exponent reading (via the log/comma/paren keys).
     *
     * <p>There is one engine, so there is no engine key: COTT-ONE answers every expression the keypad can build.
     * The freed slot went back to the row.
     */
    private static final String[][] NUMBER_KEYS = {
            {"C", "DEL", "(", ")", "÷"},
            {"7", "8", "9", "^", TIMES},
            {"4", "5", "6", "log", "−"},
            {"1", "2", "3", ",", "+"},
            {"0", ".", "x", "y", "z"},
            {"e", "i", "π", "ω", "="},
    };

    /**
     * The circular pad. Every label is the token it types, which is why the inverses read {@code asin} rather
     * than {@code sin⁻¹}: the atlas has no superscript minus (its charset stops at U+206F), and a label that
     * cannot be typed back is a label that teaches the wrong thing about what is in the entry.
     *
     * <p><b>rad and deg construct an angle</b> rather than switching a mode — {@code sin(deg(90))} is 1 — so an
     * expression carries which measure it was written in and a tape of old results cannot be misread later. See
     * {@link Real}.
     *
     * <p>The bottom-right corner is {@code =}, in the cell it occupies on the number pad, because a pad you
     * cannot evaluate from is a pad you have to leave to finish the expression it was building — and the corner
     * is where the hand already goes for it. It used to hold Euler's formula as a template; π moved up into the
     * slot that left, which keeps it in the right-hand column it was already in. A template that types itself is
     * a definition, and {@link Definitions} is where one goes.
     */
    private static final String[][] TRIG_KEYS = {
            {"sin", "asin", "sec", "asec"},
            {"cos", "acos", "csc", "acsc"},
            {"tan", "atan", "cot", "acot"},
            {"sinh", "cosh", "tanh", "atan2"},
            {"asinh", "acosh", "atanh", "π"},
            {"rad", "deg", "i", "="},
    };

    /** The keys that are operators rather than operands — drawn dimmer, because they join rather than mean. */
    private static final java.util.Set<String> DIMMED =
            java.util.Set.of("÷", TIMES, "−", "+", "^", "log", ",", "(", ")", "C", "DEL");

    /** One pad: rows of flex-grown buttons over {@code labels}, every one of them wired to the engine. */
    private static Node pad(Gui gui, Engine engine, String[][] labels, Length textSize) {
        Node pad = gui.column().width(Length.FILL).height(Length.FILL).gap(Length.rem(0.5f));
        for (String[] row : labels) {
            Node r = gui.row().width(Length.FILL).height(Length.grow(1)).gap(Length.rem(0.5f))
                    .alignItems(AlignItems.STRETCH);
            for (String label : row) {
                boolean accent = label.equals("=");
                // A call is an operator in the same sense the others are: it is something done TO an operand,
                // and reading the two apart at a glance is what the two inks are for.
                boolean op = DIMMED.contains(label) || Real.of(label) != null;
                Node b = key(gui, label,
                        accent ? Color.WHITE : (op ? DIM : INK),
                        accent ? BTN_BLUE : PANEL,
                        accent ? BTN_BLUE_HOVER : PANEL_HOVER,
                        accent ? BTN_BLUE_PRESSED : PANEL_PRESSED);
                b.textSize(textSize);
                if (accent) {
                    b.textSunken(true);
                }
                gui.onClick(b, () -> engine.press(label));
                r.append(b);
            }
            pad.append(r);
        }
        return pad;
    }


    static Ui buildUi(Gui gui, Motion motion) {
        // The display is an editable field, not a label: type the expression directly, or build it
        // from the keypad, or mix the two. Enter evaluates, exactly like the "=" key.
        TextField display = new TextField(gui, "");
        display.node()
                .width(Length.FILL).height(Length.rem(5))
                .background(PANEL).corner(Length.rem(0.75f)).border(Length.rem(0.1f), LINE)
                .lit(true).elevation(Length.rem(0.5f))
                .padding(Length.dp(16))
                .textSize(Length.rem(1.75f)).textColor(INK);

        Engine engine = new Engine(display, motion);
        gui.focusable(display.node(), true);
        gui.focus(display.node());
        display.onSubmit(s -> engine.press("="));
        // Typing w yields omega. Substituting in onChange re-enters once and then terminates,
        // since the replacement contains no w. The substitution parks the caret at the end, so put it back —
        // w and ω are one char each, so every offset survives the substitution unchanged.
        //
        // Select-and-insert rather than text(), for the reason Engine.put gives: text() resets the undo
        // history, and this fires on an ordinary keystroke. A field that forgot everything the moment a w was
        // typed is an undo that works until the first ω, which is a worse failure than the extra entry this
        // leaves (one Ctrl+Z takes the ω back to the w, a second takes the w away).
        display.onChange(s -> {
            if (s.indexOf('w') >= 0) {
                int at = display.caret();
                display.select(0, s.length());
                display.insert(s.replace('w', 'ω'));
                display.caret(at);
            }
        });

        // The keypads: rows of flex-grown buttons, no hard-coded rects anywhere, in the framework's own tab
        // panel. Which is worth saying because the first version of this was a hand-rolled strip of two
        // buttons swapping two hidden columns -- forty lines that Tabs already had, and had better: its
        // headers are focusable so Tab reaches them and Left/Right walk the bar, it carries a context menu,
        // and it hides pages rather than removing them for the reason its own doc gives (a registration is
        // keyed by node id and released when a node leaves the tree, so a removed page comes back inert).
        Tabs pads = new Tabs(gui)
                // Panes, not documents. There is nothing behind a pad and no way to ask for one back, so the
                // Close a tab bar offers by default is an action whose only outcome is a worse window.
                .closable(false)
                // And they are drawn as keys -- see padTab. A default tab is a document tab: a slab of chrome
                // with rounded shoulders sitting on a content panel, which is right over a page of text and
                // wrong over a keypad, where it reads as a band of a different program.
                .skin(CalculatorApp::padTab);
        // No surface under either half: the keys float on the window background, so a bar and a page panel
        // behind them would be the only slabs in the window. Spaced by the gap the keys are spaced by, which
        // is what makes the top row read as part of the grid rather than as a strip above it.
        pads.bar().background(Color.TRANSPARENT).gap(Length.rem(0.5f)).height(Length.rem(2.5f));
        // The pages carry BG rather than nothing, which is the same pixels -- BG is what is behind them --
        // and not the same thing. Tabs paints a content surface precisely so that a transition has a known
        // backdrop to blend against: src-over leaves (1-a)(1-b) of whatever is behind two half-faded pages,
        // and with no surface of its own that term resolves to whatever the application happened to put
        // there. Transparent was right while the swap was a cut and wrong the moment it became a slide.
        pads.pages().background(BG).corner(Length.ZERO);
        // One pad leaves and the other arrives; they do not dissolve into each other, they travel. The ramp
        // is LINEAR because a page in flight has no place to arrive at -- Tabs.slide eases its own travel
        // inside the transition and takes the fade underneath it straight, which is the same division
        // Motion.announce makes for the status line.
        Ramp slide = motion.transition();
        if (slide != null) {
            pads.transition(Tabs.slide(slide));
        }   // and with no clock, no transition is installed: the panel flips, exactly as it did before
        // The space between the bar and the first row of keys, put on the panel's own column rather than as
        // padding on the pages: padding is symmetric, and the copy of it under the bottom row would be height
        // the window has to find at its smallest size for the sake of a gap nothing sits in.
        pads.node().gap(Length.rem(0.5f));
        pads.add("⁘", pad(gui, engine, NUMBER_KEYS, Length.rem(1.25f)));
        pads.add("θ", pad(gui, engine, TRIG_KEYS, Length.rem(0.9375f)));

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
                .children(display.node(), statusText, pads.node());

        // Undo/redo for the whole window, not just for the field the caret is in.
        //
        // The field already binds these chords for itself, and that binding is the right one in an editor,
        // where the thing being undone is the thing you are typing into. Here there is one line of text and a
        // grid of buttons that write into it, so an undo that stops working the moment Tab moves the focus to
        // a key would stop working exactly when the user was driving the calculator by mouse. A GLOBAL claim
        // is how the framework says that: while it applies the chord reaches nothing else, the focused node's
        // own handler included, so there is one door and no question of which of the two answered.
        //
        // Gui.history(node, history, scope) would bind the same three chords to the same stack; these go
        // through Engine because a calculator's undo has to put back more than the text -- see Engine.undo.
        gui.claim(root, Shortcut.of(Key.Z, Modifier.CONTROL), ClaimScope.GLOBAL, engine::undo);
        gui.claim(root, Shortcut.of(Key.Z, Modifier.CONTROL, Modifier.SHIFT), ClaimScope.GLOBAL, engine::redo);
        gui.claim(root, Shortcut.of(Key.Y, Modifier.CONTROL), ClaimScope.GLOBAL, engine::redo);
        // The window's own title bar: ordinary widgets, plus the two declarations that tell the window
        // manager which pixels are caption (DRAG on the strip, INTERACTIVE on each button). Bound to the
        // real window in main(); here it commands WindowControls.NONE, which is what --capture draws.
        TitleBar titleBar = new TitleBar(gui, WindowControls.NONE, "Calculator");
        // The way in to the session's names, in the caption rather than on the tab bar -- which is where it
        // belongs and not merely where there was room. It opens a WINDOW; the tabs beside it swap a pad. Two
        // different kinds of thing reading as one row of buttons was the tell that the hand-rolled strip had
        // taken on a job that was not a tab bar's.
        titleBar.addLeading(names(gui, engine));
        gui.root().background(BG).children(titleBar.node(), root);
        return new Ui(engine, titleBar, pads);
    }

    /**
     * The caption button that opens {@link Definitions}: {@code ℱ}, the function symbol from the letterlike
     * block, for the place functions are named.
     *
     * <p>{@code ℱ} and the tabs' {@code ⁘} and {@code θ} come from a short list, because the text atlas carries
     * Latin, Greek, Cyrillic, punctuation, currency and the letterlike block and <b>nothing else</b> — of the
     * mathematical operators it has exactly one, the minus sign. So the obvious icons here (a script f, a 2x2
     * box, a sine wave) are all missing glyphs, and a missing glyph renders as a box. Every one was checked
     * against {@code primary.json} before it was used.
     *
     * <p>{@link WindowRegion#INTERACTIVE} is not optional and {@code TitleBar.addLeading} says so: the caption
     * is a drag surface to the window manager, so without the declaration a click here starts moving the window
     * instead of landing on the button.
     */
    private static Node names(Gui gui, Engine engine) {
        Node b = gui.text("ℱ").width(Length.rem(2f)).height(Length.FILL)
                .textSize(Length.rem(1f)).textColor(DIM)
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .background(Color.TRANSPARENT)
                .windowRegion(WindowRegion.INTERACTIVE);
        gui.onState(b, state -> b.background(switch (state) {
            case NORMAL -> Color.TRANSPARENT;
            case HOVER -> PANEL_HOVER;
            case PRESSED -> PANEL_PRESSED;
        }));
        gui.onClick(b, engine::openDefinitions);
        return b;
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
            // Silent with no application to open onto, which is the capture -- the same rule
            // Definitions.show keeps. The tape still fills either way, so a headless run can photograph
            // this window without one.
            if (!shown && app != null) {
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
    static final class History {
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

        /**
         * GUI thread, once per frame. Drains <em>everything</em> queued, not one request.
         *
         * <p>One per frame was survivable only while the loop redrew unconditionally, because the next
         * frame was always a few milliseconds away. A loop that parks when nothing is happening has no
         * next frame to rely on: it runs this, leaves the rest of the backlog sitting there, and then
         * asks whether anything needs drawing — and nothing here can tell it yes. The queue is emptied
         * or the queue is stuck.
         */
        void drain() {
            window.pump();
            for (Runnable r; (r = requests.poll()) != null; ) {
                r.run();
            }
        }
    }

    /**
     * A tab, drawn as a key — which is the whole of making the bar belong to the keypad rather than sit above it.
     *
     * <p>Same silhouette as {@link #key}: fully rounded, the same hairline border, lit, and floating on the same
     * elevation that lifts under the pointer and drops flat when pressed. And the same two inks the keypad
     * already uses to mean two things — a selected pad wears the accent the {@code =} key wears, because that is
     * what this keypad's blue already means, while an idle one is a panel like every other key.
     *
     * <p>The default tab silhouette — a chrome slab with rounded shoulders and a flat seat, sitting on a content
     * panel — is right over a page of text and reads as a band of a different program over a grid of keys. This
     * is the same widget saying the same thing in the vocabulary of what surrounds it.
     */
    private static void padTab(Node header, boolean selected, InteractionState state) {
        header.padding(Length.ZERO, Length.rem(1.125f))
                .textSize(Length.rem(1.125f))
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .corner(Length.rem(0.625f)).border(Length.rem(0.1f), LINE)
                .textColor(selected ? Color.WHITE : DIM)
                .textSunken(selected)
                .lit(true)
                .background(selected
                        ? switch (state) {
                            case NORMAL -> BTN_BLUE;
                            case HOVER -> BTN_BLUE_HOVER;
                            case PRESSED -> BTN_BLUE_PRESSED;
                        }
                        : switch (state) {
                            case NORMAL -> PANEL;
                            case HOVER -> PANEL_HOVER;
                            case PRESSED -> PANEL_PRESSED;
                        })
                .elevation(switch (state) {
                    case NORMAL -> Length.rem(0.375f);
                    case HOVER -> Length.rem(0.625f);
                    case PRESSED -> Length.ZERO;
                });
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
    static final class Engine {
        private final TextField display;
        /** How this application says what happened. Never null; {@link Motion#none()} where there is no clock. */
        private final Motion motion;
        private boolean justEvaluated;
        /** The line that reports a rejection. */
        private volatile Node statusLabel;
        /** Where evaluations are recorded, once there is a window loop to open onto. */
        private volatile History history;
        /** Where a plotted expression opens a window. Null until main() has a window loop to open onto. */
        private volatile Previews previews;
        /** The session's names, kept here because this is what reads an expression. Never null. */
        private volatile Bindings session = Bindings.EMPTY;
        /** The window that edits them. Null under {@code --capture}, which has no window loop. */
        private volatile Definitions definitions;

        Engine(TextField display, Motion motion) {
            this.display = display;
            this.motion = motion;
        }

        void statusLabel(Node status) {
            this.statusLabel = status;
        }

        /**
         * Report something, or clear the report. Never touches the entry.
         *
         * <p>A non-empty message makes the line <b>arrive</b> rather than merely appear -- see
         * {@link Motion#announce}. Without that, reporting the same thing twice rewrote an identical string
         * and nothing moved, so pressing = twice on one bad expression looked like the second press had been
         * dropped. Reporting is the case where saying it again is the ordinary thing, so it has to be the case
         * that reads.
         */
        private void status(String message) {
            status(message, BTN_BLUE_HOVER);
        }

        /**
         * Report in a given ink.
         *
         * <p>The colour is set on every report rather than only when it changes, because {@code textColor} has
         * no identity value -- architecture.md's rule for what may live in the visual-transform layer, and this
         * does not. Whatever sets one owns putting it back, and the only thing here that knows the resting
         * value is this method, so this method says it every time rather than leaving a refusal's red on the
         * next ordinary notice.
         */
        private void status(String message, Color ink) {
            Node s = statusLabel;
            if (s != null) {
                s.text(message).textColor(ink);
                if (!message.isEmpty()) {
                    motion.announce(s);
                }
            }
            reported = message;
        }

        /**
         * Report a rejection: the message on the line, and a ring around the entry it is about.
         *
         * <p>Every refusal in this class goes through here rather than through {@link #status}, which is what
         * keeps the mark and the words together -- a rejection reported without one would be a rejection the
         * eye has to go looking for, and the entry it concerns is the thing about to be corrected.
         */
        private void refuse(String message) {
            status(message, Palette.REFUSED);
            motion.refused(display.node());
        }

        /** The last thing the status line was told, for a capture that has no screen to read it off. */
        private volatile String reported = "";

        String reported() {
            return reported;
        }

        /** What is in the display right now — the same read a capture would take off the glass. */
        String shown() {
            return display.document().value().text();
        }

        void history(History history) {
            this.history = history;
        }

        /** Where a plottable expression goes. Null under {@code --capture}, which has no window loop. */
        void plotter(Previews previews) {
            this.previews = previews;
        }

        /**
         * The window that names things, and the names it currently holds.
         *
         * <p>The engine keeps its own copy rather than asking the window each time, so that an evaluation on a
         * worker thread reads one settled vocabulary: {@link Notation#normalize} and {@link Parser#parse} have
         * to be given the <em>same</em> one, since the first decides where a word begins and the second reads
         * what is there, and a definition landing between the two calls would leave them disagreeing.
         */
        void definitions(Definitions definitions) {
            this.definitions = definitions;
            this.session = definitions.bindings();
        }

        /** A definition was made or forgotten. Called on the frame loop, read on a worker. */
        void bindings(Bindings session) {
            this.session = session;
        }

        /** The names in play, for anything else that has to read an expression the way this does. */
        Bindings bindings() {
            return session;
        }

        /** Show the definitions. What the strip's last button does. */
        void openDefinitions() {
            Definitions open = definitions;
            if (open != null) {
                open.show();
            }
        }

        /**
         * An evaluated expression, offered to the plotter. <b>How many variables it has is the whole
         * decision</b>, and it is taken on the term the user typed rather than on the reduced one — COTT
         * leaves {@code x÷x} standing as a residue whose variable has become part of a form, and the curve
         * being asked about is the one that was written down.
         *
         * <ul>
         *   <li><b>None</b> — a number was evaluated, and the question becomes what <em>kind</em> of number.
         *       One on the real line says nothing at all, because an arithmetic result must not come with a
         *       notice attached; one off it — a zero, an infinity, a twist that is not a whole turn — has a
         *       <b>place</b> rather than a curve, and the place is drawn. See {@link #offerPlace}.
         *   <li><b>One</b> — a curve, against that variable whatever it is called. x is the usual one and
         *       nothing here depends on it being x.
         *   <li><b>Two</b> — a surface, in three dimensions, over the two of them in the order they are named.
         *   <li><b>Three or more</b> — reported on the status line rather than half drawn by pinning the rest
         *       to zero, which would be a picture of a different expression. The count is where the line
         *       falls, and it falls there because there is no third dimension left to put a third variable on.
         * </ul>
         *
         * <p>Either way it opens a <b>preview of its own</b> — see {@link Previews}.
         *
         * @param value the reduced term. Only the no-variable branch reads it, and it has to: what
         *              {@code 2÷0} is cannot be seen in {@code 2÷0}, only in the {@code 2ω} it came to.
         */
        private void offerPlot(String entry, Term term, Term value) {
            Previews windows = previews;
            if (windows == null) {
                return;
            }
            List<String> variables = Plottable.variablesIn(term);
            if (variables.isEmpty()) {
                offerPlace(entry, value, windows);
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

        /**
         * An expression with no variable in it, which used to be the end of the matter and is not any more.
         *
         * <p><b>The gate is {@link Place#offTheLine}, and it is narrow on purpose.</b> Ordinary arithmetic is
         * still silent — {@code 2+2} answers 4, {@code 5÷2} answers 5÷2, and {@code 0^ω} answers −1, which is a
         * real number however it was spelled. What opens a window is a value the real line cannot hold: a
         * grade, a torsion, or a twist that is not a whole turn. Those are exactly the answers that used to be
         * correct on the display and undrawable everywhere else, and {@link Place} is the picture of them.
         *
         * <p>A refusal is reported here where an ordinary result is not, and the asymmetry is the point: a
         * value that is off the line and still has no place is a value with something to say about it, and the
         * sentence saying it is more use than a silence that looks identical to arithmetic.
         */
        private void offerPlace(String entry, Term value, Previews windows) {
            if (!Place.offTheLine(value)) {
                return;
            }
            Place place = Place.read(value);
            if (place.ok()) {
                windows.show(entry, place);
            } else {
                status(place.refusal());
            }
        }

        /**
         * Name something: {@code k = 3}, or {@code f(x) = x^2+1}.
         *
         * <p>The entry is cleared only when the definition was accepted, which is the rule every rejection in
         * this class follows — a line that did not parse stays where it is so it can be corrected, and the
         * reason appears on the status line beside it rather than in the field being corrected.
         *
         * <p>On success the definitions window comes up. That is {@link Definitions}' doing rather than this
         * method's, and it is deliberate: a definition made from the keypad, from that window's own entry and
         * from a shell prompt are the same act, so they go through one door and the same thing happens.
         */
        synchronized void define(String line) {
            Definitions names = definitions;
            if (names == null) {
                refuse("there is nowhere to keep a definition here");
                return;
            }
            Definitions.Made made = names.request(line);
            if (made.refusal() != null) {
                refuse(made.refusal());
                return;
            }
            put("");
            status("defined " + made.definition().head());
            justEvaluated = false;
        }

        /**
         * Put the entry back as it was, from a history click or a definition.
         *
         * <p>Washed, because this is the one way the entry changes without the user having typed into it --
         * and the click that did it was in a <em>different window</em>, which is to say somewhere the eye is
         * not. See {@link Motion#changed}.
         */
        synchronized void restore(String entry) {
            enter(entry);
            // The report went with the line it was about. A refusal left standing over a replaced entry says
            // something false about what is now in the field -- which typing does NOT do, because a typed
            // correction is an answer to the report and wants it still there to be answered.
            status("");
            motion.changed(display.node());
        }

        /**
         * Put a line in the entry, as typing it would be -- and unmarked, because the hand that typed it
         * knows it did.
         *
         * <p>Split from {@link #restore} when that grew a wash. The two were one method while neither said
         * anything, and they are not one act: a line the user typed and a line that arrived from another
         * window differ in exactly whether anyone needs telling.
         */
        synchronized void enter(String entry) {
            put(entry);
            justEvaluated = false;
        }

        /**
         * Replace the whole entry, as an <b>undoable</b> step.
         *
         * <p>{@link TextField#text(String)} is the wrong door for every one of this class's whole-entry
         * replacements, and its own contract says so: it resets the history. That is right for a field handed
         * new content it has no past with -- a document loaded over the top -- and exactly wrong here, where
         * every replacement is something the user just did to a line they were writing. Going through it left
         * the calculator with an undo that covered typing and covered nothing else: C, =, DEL over a result, a
         * click in the history window and a definition each silently emptied the stack, which are the five
         * moments a calculator's Ctrl+Z is for.
         *
         * <p>So the swap is spelled the way a completion popup spells one -- select the line, then insert over
         * it -- which is the framework's recording path and lands as one entry. An empty replacement is a
         * deletion instead, because {@link TextField#insert} takes no empty string.
         */
        private void put(String text) {
            String current = display.document().value().text();
            if (current.isEmpty() && text.isEmpty()) {
                return;   // nothing to replace and nothing to put there: not an edit, so not an undo entry
            }
            display.select(0, current.length());
            if (text.isEmpty()) {
                display.deleteBack();   // eats the selection, which is the whole line
            } else {
                display.insert(text);
            }
        }

        /**
         * Undo one step of the entry, and forget that a result is showing.
         *
         * <p>Claimed at {@link ClaimScope#GLOBAL} in {@link #buildUi} rather than left to the field's own
         * Ctrl+Z, for the two reasons the keypad has and a text field cannot: the chord has to work while the
         * focus is on a key rather than in the display, and {@code justEvaluated} is state of this class that
         * the field knows nothing about. Leaving it set across an undo means the restored expression is one the
         * next digit wipes -- the flag says a result is on the glass when what is on the glass is the line it
         * came from.
         */
        synchronized void undo() {
            if (display.history().undo()) {
                justEvaluated = false;
                status("");   // the report went with the line it was about; see restore(String)
            }
        }

        /** Redo one undone step. The same reasoning as {@link #undo()}, in the other direction. */
        synchronized void redo() {
            if (display.history().redo()) {
                justEvaluated = false;
                status("");
            }
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
                case "C" -> { put(""); justEvaluated = false; }
                case "DEL" -> {
                    if (justEvaluated) { put(""); justEvaluated = false; }
                    else { display.deleteBack(); }   // backspaces at the caret, or eats the selection
                }
                case "=" -> {
                    // A line with an = in it is a DEFINITION and not an expression, and there is no ambiguity
                    // to weigh: COTT's grammar has no = in it at all, so such a line was a syntax error until
                    // now. The keypad's = key never types one either -- it evaluates -- so the only way to get
                    // one into the entry is to mean it.
                    if (doc.text().indexOf('=') >= 0) {
                        define(doc.text());
                        return;
                    }
                    if (!doc.text().isEmpty()) {
                        String entry = doc.text();
                        // One reading of the session for the whole press. See definitions(Definitions).
                        Bindings names = session;
                        try {
                            // Parsed here rather than through Cott.evaluate, which parses too: the term is
                            // what the plotter reads its variables out of, and re-parsing to get it would
                            // let the two disagree about what was typed.
                            //
                            // Expanded before either use, so a defined name is the expression it stands for
                            // everywhere -- which is what makes plotting f(x) draw f's body rather than an
                            // opaque symbol with no curve.
                            Term term = names.expand(Parser.parse(Notation.normalize(entry, names), names));
                            // Both the written term and what it came to are kept, because the plotter needs
                            // both and they answer different questions: which variables were written down,
                            // and -- when none were -- what kind of value this turned out to be.
                            Term value = Cott.reduce(term);
                            String result = Render.show(value);
                            status("");
                            put(result);   // undoable: Ctrl+Z is how the expression comes back
                            // The press that most needs saying so is the one that changes nothing on screen:
                            // an expression that reduces to itself, or a second = on a result already
                            // showing. Both used to be indistinguishable from a dropped click.
                            motion.acknowledged(display.node());
                            justEvaluated = true;
                            History h = history;
                            if (h != null) {
                                h.record(entry, result);
                            }
                            offerPlot(entry, term, value);
                        } catch (SyntaxException e) {
                            // A rejection is REPORTED, never written into the field: the entry stays
                            // put so the expression can be fixed and "=" pressed again without
                            // retyping it, which a message sitting in the display would prevent.
                            refuse(e.getMessage());
                        } catch (RuntimeException e) {
                            refuse("Error");   // an evaluator fault; the entry still survives it
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
            // A call key types the name and its opening bracket, so the caret lands where the argument goes.
            // log is here with the rest of them: it is spelled like a call and typed like one, and only what it
            // returns makes it different.
            String token = label.equals("log") || Real.of(label) != null ? label + "(" : label;
            if (justEvaluated) {
                // Typing over a result: select it rather than clearing it, so the removal and the insertion
                // that follows are ONE edit and one Ctrl+Z brings the result back. Clearing first works and
                // undoes wrong -- the first Ctrl+Z would leave an empty field, which is a state the user was
                // never shown. Selecting all also puts the insert at offset 0, which is what suppresses the
                // implicit multiplication sign below: this token starts a new expression.
                display.select(0, doc.text().length());
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
