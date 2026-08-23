package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.GuiApp;
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
import sibarum.cott.SyntaxException;
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
 * <p>Run: {@code CalculatorApp} (windowed), {@code CalculatorApp --capture [out.png]} (headless).
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

    private static final Color BG = Color.rgb(0x11141b);
    private static final Color PANEL = Color.rgb(0x1b2130);
    private static final Color PANEL_HOVER = Color.rgb(0x232a3d);
    private static final Color PANEL_PRESSED = Color.rgb(0x151a26);
    private static final Color LINE = Color.rgb(0x2b3346);
    private static final Color BTN_BLUE = Color.rgb(0x2668b3);
    private static final Color BTN_BLUE_HOVER = Color.rgb(0x2f78c9);
    private static final Color BTN_BLUE_PRESSED = Color.rgb(0x1d548f);
    private static final Color INK = Color.rgb(0xeef2f8);
    private static final Color DIM = Color.rgb(0x93a0b4);

    public static void main(String[] args) throws Exception {
        args = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);

        Gui gui = new Gui();
        // Two em more than the keypad needs on its own: the title bar sits inside the canvas now, so the
        // smallest layout has to hold it as well as the display and the keys.
        gui.minSize(Length.em(21), Length.em(32));
        Ui ui = buildUi(gui);
        Engine engine = ui.engine();
        zoomShortcuts(gui);

        if (args.length >= 1 && args[0].equals("--capture")) {
            // Exercise the residue vertical: 1 over 1 in an additive context keeps its winding.
            for (String k : new String[]{"x", "+", "(", "1", "÷", "1", ")", "="}) {
                engine.press(k);
            }
            GuiApp.capture(gui, W, H, 0.06f, 0.07f, 0.09f, args.length >= 2 ? args[1] : "calculator.png");
            System.out.println("captured");
            return;
        }

        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        try (Tactroller input = openInput();
             Clipboard clipboard = openClipboard(gui);
             GuiApp app = new GuiApp(mainWindow())) {
            attachInput(input, app);
            // The window exists at last, so the chrome can be pointed at it. Until now the bar has been a
            // working bar against WindowControls.NONE -- which is also what --capture renders.
            ui.titleBar().controls(app.controls());
            // The history needs the app to open its window onto, so it is built here rather than in
            // buildUi -- and stays null under --capture, which never reaches this far.
            History history = new History(engine, app);
            engine.history(history);
            zoomShortcuts(history.windowGui());
            TactrollerInputBridge bridge = input == null ? null : new TactrollerInputBridge(input, gui.bus());
            app.run(gui, maxFrames, () -> {
                pump(bridge);
                history.drain();
            });
        }
        gui.close();
        System.out.println("clean shutdown");
    }

    /**
     * The main window: the calculator's size, and the frame handed to the GUI. {@link Decorations#CLIENT}
     * extends the client area over the whole window, so the {@code TitleBar} at the top of the tree draws
     * where the system caption was -- while dragging, snapping, Win+arrow, double-click-to-maximize, the
     * system menu and the maximize clamp all stay the window manager's. The GUI only supplies geometry.
     */
    private static WindowConfig mainWindow() {
        return WindowConfig.of("Calculator", W, H).decorations(Decorations.CLIENT);
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
    private static void attachInput(Tactroller input, GuiApp app) {
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
                {"7", "8", "9", "^", "×"},
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
                boolean op = java.util.Set.of("÷", "×", "−", "+", "^", "log", ",", "(", ")", "C", "DEL")
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

        private final java.util.function.Consumer<Entry> restore;
        private final Gui gui = new Gui();
        private final Node list;
        private final TitleBar titleBar;
        private final java.util.ArrayDeque<Node> rows = new java.util.ArrayDeque<>();
        private Tactroller input;
        private TactrollerInputBridge bridge;
        private boolean shown;

        HistoryWindow(java.util.function.Consumer<Entry> restore) {
            this.restore = restore;
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
                app.requestPopup(WindowConfig.of("History", 320, 480 + BAR_H).decorations(Decorations.CLIENT),
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
            titleBar.controls(new PopupControls(window));
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
     * {@link WindowControls} over a popup's own window, for the title bar it draws. The main window's
     * come from {@code GuiApp.controls()}; a popup's the application holds itself, since the window it
     * commands is the one handed to {@code onCreated}.
     *
     * <p>Close is a <em>request</em>, exactly as the system close button would have been: the frame loop
     * observes it, tears the popup down in its own order and runs {@code onClosed}. Destroying the window
     * here would pull resources out from under a frame in flight.
     */
    private record PopupControls(dev.vexelray.os.NativeWindow window) implements WindowControls {

        
        public void minimize() {
            window.minimize();
        }

        
        public void toggleMaximize() {
            if (window.isMaximized()) {
                window.restore();
            } else {
                window.maximize();
            }
        }

        
        public boolean maximized() {
            return window.isMaximized();
        }

        
        public void close() {
            window.requestClose();
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

        History(Engine engine, GuiApp app) {
            this.engine = engine;
            this.app = app;
            this.window = new HistoryWindow(this::restore);
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
                            String result = Cott.evaluate(entry);
                            status("");
                            display.text(result);
                            justEvaluated = true;
                            History h = history;
                            if (h != null) {
                                h.record(entry, result);
                            }
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
                case "+", "−", "×", "÷", "^", ",", ")" -> { display.insert(label); justEvaluated = false; }
                default -> insertOperand(label, doc);
            }
        }

        /**
         * An operand token -- digit, ., (, log(, e, i, π, ω, x, y, z -- inserted at the caret, with
         * an implicit × when it lands directly after something that ends an operand.
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
            // The same two character sets the engine uses to put this × back when a TYPED expression
            // is read, and to leave it out when a result is printed. One definition, in Notation, is
            // what keeps the keypad, the keyboard and the display agreeing.
            if (at > 0 && Notation.endsOperand(doc.text().charAt(at - 1)) && !Notation.digitLike(token)) {
                token = "×" + token;   // implicit multiplication: 2π, xy, 3(x+1), ω(...)
            }
            display.insert(token);
            justEvaluated = false;
        }
    }
}
