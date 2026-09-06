package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.automation.Automation;
import dev.vexelray.gui.automation.AutomationServer;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.app.WindowInput;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.os.Decorations;
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
 * The plot viewport: type an expression, look at it.
 *
 * <p>What lives in this class is the <b>application edge</b> — the part a client of vexelray-gui has to write
 * for itself, and therefore the part this project exists to demonstrate. Opening an input backend and settling
 * its coordinate space, installing a clipboard, remembering where the window was, attaching a clock, wiring the
 * frame loop and its wakes, and deciding what closing the window means. Every one of those is a decision the
 * framework deliberately does not take on an application's behalf.
 *
 * <p>Everything above it is in {@link Ui}; everything the application <em>knows</em> is in {@code Model}. This
 * class holds no state of its own.
 *
 * <pre>
 * Calculator                      the window, interactively
 * Calculator &lt;frames&gt;             run a fixed number of frames and quit (a script, not a session)
 * Calculator --capture &lt;scene&gt; [out.png]   headless PNG; see {@link Capture}
 * </pre>
 *
 * <p>Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class Calculator {

    /** The application's own name, which is what its settings directory is called. */
    static final String APP = "vexel-calculator";

    /** Window size on a first run, in the engine's logical coordinates. The prototype declares 1180x720. */
    static final int W = 1180;
    static final int H = 720;

    public static void main(String[] args) throws Exception {
        args = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);

        if (args.length >= 1 && args[0].equals("--capture")) {
            Capture.run(args);
            return;
        }
        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;

        Gui gui = new Gui();
        // The look is a preference, so it is applied before anything is built: a role resolves at the moment a
        // widget writes a prop, so a theme set afterwards reaches the renderer's own chrome and nothing else.
        gui.theme(Look.THEME);
        // The smallest window this UI is still coherent in -- a floor, not the design size. Below this the rail
        // plus a 284px panel leaves nothing for the plot, which is the thing the window is for.
        gui.minSize(Length.em(46), Length.em(30));
        // The frame clock, attached before the UI is built because a widget that animates is handed its timing
        // at construction. Ticked once per presented frame from the run loop below.
        KronoGui krono = KronoGui.attach(gui);

        Ui ui = new Ui(gui, krono);
        zoomShortcuts(gui);

        // Placement is read before the window exists, so the window is *created* where it was left rather than
        // appearing and then moving -- and clamped on the way, because the desk may have changed shape.
        WindowMemory memory = new WindowMemory(Settings.open(APP));

        try (Tactroller input = openInput();
             GuiApp app = new GuiApp(memory.config("main", "Calculator", W, H)
                     // The GUI draws the frame. The window keeps every window-manager behaviour it had --
                     // dragging, snapping, Win+arrow, double-click-to-maximize -- and gains a title bar made
                     // of the same widgets as the rest of the UI.
                     .decorations(Decorations.CLIENT));
             Clipboard clipboard = openClipboard(gui);
             AutomationServer server = automation(gui, app)) {

            attachInput(input, gui, app);
            ui.titleBar().controls(app.controls());   // the window exists now; point the chrome at it

            // The plot. Built here rather than in Ui because a render target and a storage buffer come from the
            // application's device, and the device does not exist until GuiApp does -- which is also the whole
            // reason GuiApp.viewport and GuiApp.storage exist rather than the device being public.
            March march = new March(app);
            march.showIn(ui.viewport());
            var reading = Canned.read(Canned.DEFAULT_EXPRESSION);
            march.geometry(Geometry.of(reading, 4, -6, 6, Integer.getInteger("plot.samples", 420),
                    Geometry.Furniture.DEFAULT));

            // Auto-orbit. A GLOBAL claim rather than a handler, which is how this framework does preemption:
            // the focused expression field outranks it by claiming Space at FOCUSED scope, so typing a space
            // into an expression types a space. Nothing here has to know the field exists.
            java.util.concurrent.atomic.AtomicBoolean spin = new java.util.concurrent.atomic.AtomicBoolean();
            gui.shortcut(Key.SPACE, () -> spin.set(!spin.get()));
            if (memory.maximized("main")) {
                app.window().maximize();
            }
            // Watched with its tree, so the UI zoom is remembered too: Ctrl+= is the same kind of decision as
            // dragging the window bigger, and losing it on quit is the same loss.
            memory.watch("main", app.window(), gui);
            app.input(Calculator::windowInput);

            TactrollerInputBridge bridge = input == null ? null : new TactrollerInputBridge(input, gui.bus());

            if (maxFrames <= 0) {
                // Render on demand: park until something says a frame is due. Every deadline this application
                // holds goes in one supplier -- the clock knows about animations, but it does not know that the
                // window placement is 700ms from being written, and a parked loop has no next frame on which to
                // find out.
                app.pacing(() -> Math.min(krono.kron().sleepTimeout().nanos(), memory.nanosUntilSettle()));
                app.idleRefresh(200_000_000L)      // 5 Hz floor while focused: a missed wake is late, never lost
                        .maxFrameRate(16_666_666L);   // 60 Hz ceiling while animating
                // And the wakes, without which the parking above is a hang rather than a saving: a worker's
                // mutation and a timeline's tick are not OS input, so each has to nudge the message queue.
                gui.onWork(app::postWake);
                krono.kron().onWork(app::postWake);
            }

            try {
                // Input first, then the clock: the tick returns with its batch complete, so anything an
                // animation posts this frame is on the bus before Gui.frame reconciles it -- the frame that
                // presents a value is the frame that computed it.
                app.run(gui, maxFrames, () -> {
                    pump(bridge);
                    krono.tick();
                    if (spin.get()) {
                        march.turn(Math.toRadians(0.6), 0);
                    }
                    // After the clock, because a camera animation settles on the tick and the frame that
                    // presents a value should be the frame that computed it. Before the tree is drawn, because
                    // renderInto's contract is that the image is ready when it returns.
                    march.frame();
                    // The labels are authored against the same camera the march was just given and against the
                    // node's measured box, in the same frame, on the GUI thread. A frame's lag would be visible
                    // as text sliding across the plot behind the geometry it names.
                    Labels.show(ui.viewport(), Labels.of(march.lens(), ui.viewport().layout(), -6, 6, true));
                    ui.readout().show(march.yaw(), march.pitch(), 1.0, march.cones());
                    memory.poll();
                });
            } finally {
                // The debounce has no next frame to fire on once the loop is over, so the last move of the
                // session is written here or not at all.
                memory.save();
            }
        }
        krono.close();   // the clock outlives the window but not the process
        gui.close();
    }

    /**
     * Ctrl+= / Ctrl+- / Ctrl+0. Registered here rather than in the framework because which chord zooms — or
     * whether zooming exists at all — is an application decision. {@code gui.shortcut} is an ordinary
     * {@code GLOBAL} claim, so a focused element that wants these chords can outrank it.
     */
    private static void zoomShortcuts(Gui gui) {
        gui.zoomRange(0.5f, 3f, 1.25f);
        gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.MINUS, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.DIGIT_0, gui::resetZoom, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_ADD, gui::zoomIn, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_SUBTRACT, gui::zoomOut, Modifier.CONTROL);
        gui.shortcut(Key.NUMPAD_0, gui::resetZoom, Modifier.CONTROL);
    }

    /**
     * The driving socket, when {@code -Dautomation} asks for it.
     *
     * <p>Off unless requested, and loopback-only when it is: this hands anyone who can reach it full control of
     * the application's input, so it is a debugging instrument and not a service (automation.md §5). Returns
     * {@code null} — which try-with-resources accepts — when it is off or cannot bind, because a calculator
     * that will not start because a debugging port was busy is a worse outcome than one nobody can drive.
     */
    private static AutomationServer automation(Gui gui, GuiApp app) {
        String want = System.getProperty("automation", "off");
        if (want.isBlank() || want.equals("off") || want.equals("false")) {
            return null;
        }
        try {
            int port = want.equals("on") || want.equals("true")
                    ? AutomationServer.DEFAULT_PORT
                    : Integer.parseInt(want);
            AutomationServer server = AutomationServer.start(new Automation(gui, app.controls()), port);
            System.out.println("automation: localhost:" + server.port());
            return server;
        } catch (java.io.IOException | NumberFormatException e) {
            System.out.println("automation unavailable (" + e.getMessage() + "); running undriven");
            return null;
        }
    }

    /**
     * Open tactroller. Returns {@code null} (input disabled) when no backend is present, so the window still
     * renders in CI — a window nobody can click is a degraded window rather than a failed launch.
     */
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

    /**
     * Attach input to the window and settle the coordinate space.
     *
     * <p><b>{@code CLIENT}, and density deliberately left at 1.0.</b> Both follow from one fact: the engine's
     * window and {@code Canvas} are in <em>logical</em> coordinates, not framebuffer pixels. On a scaled display
     * that has two consequences, and getting either wrong is visible immediately — {@code FRAMEBUFFER}
     * coordinates are {@code CLIENT × contentScale}, so every press would land past its target; and the OS is
     * already scaling a logical window's output, so feeding {@code contentScale()} into {@code Gui.dpi} scales
     * the content a second time. The framework's density support is correct and tested and cannot be switched
     * on until the engine reports a real framebuffer extent (architecture.md E4).
     */
    private static void attachInput(Tactroller input, Gui gui, GuiApp app) {
        if (input == null) {
            return;
        }
        try {
            input.attach(NativeWindow.ofHwnd(app.windowHandle()));
            input.setCoordinateSpace(CoordinateSpace.CLIENT);
        } catch (BackendException e) {
            System.out.println("input attach failed (" + e.getMessage() + "); pointer input disabled");
        }
    }

    /**
     * Open the OS clipboard and install it, so the expression can be pasted in — which, with no keypad, is one
     * of the two ways an expression ever arrives. Returns {@code null}, leaving the GUI's in-memory default in
     * place, when no backend is present.
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
                        // Best effort -- a transient clipboard failure just drops the copy.
                    }
                }
            });
            return clip;
        } catch (ClipboardException e) {
            System.out.println("clipboard unavailable (" + e.getMessage() + "); paste uses a buffer");
            return null;
        }
    }

    /**
     * Input for a window the framework opened on its own — a dialog, a named window: its own backend, attached
     * to that window's handle, bridged onto that window's bus, pumped by the frame loop. The main window is
     * still wired by hand above because it exists before the app does.
     */
    private static WindowInput windowInput(dev.vexelray.os.NativeWindow window, Gui windowGui) {
        Tactroller backend;
        try {
            backend = Tactroller.open();
            backend.attach(NativeWindow.ofHwnd(window.osHandle()));
            backend.setCoordinateSpace(CoordinateSpace.CLIENT);
        } catch (BackendException e) {
            return WindowInput.NONE;
        }
        TactrollerInputBridge bridge = new TactrollerInputBridge(backend, windowGui.bus());
        return new WindowInput() {

            @Override
            public void pump() {
                Calculator.pump(bridge);
            }

            @Override
            public void close() {
                backend.close();
            }
        };
    }

    /** Snapshot input onto the bus for this frame; a transient backend poll failure just skips the frame. */
    static void pump(TactrollerInputBridge bridge) {
        if (bridge == null) {
            return;
        }
        try {
            bridge.pump();
        } catch (BackendException e) {
            // Transient poll failure -- drop this frame's input rather than tear down the loop.
        }
    }

    /** The clear colour behind the tree: the same role the root paints, so the frame is never a second opinion. */
    static Color page() {
        return Look.THEME.color(Role.PAGE);
    }

    private Calculator() {
    }
}
