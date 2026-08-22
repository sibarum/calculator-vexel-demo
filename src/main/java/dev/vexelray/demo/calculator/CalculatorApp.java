package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.TextClipboard;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.text.Document;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.text.TextLayout;
import java.util.List;
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

    /** Window and capture size, in the engine's logical coordinates. */
    private static final int W = 420;
    private static final int H = 600;

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
        gui.minSize(Length.em(21), Length.em(30));
        Engine engine = buildUi(gui);
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
             GuiApp app = new GuiApp("Calculator", W, H)) {
            attachInput(input, app);
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

    private static Engine buildUi(Gui gui) {
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
        gui.root().background(BG).children(root);
        return engine;
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
            gui.root().background(BG).children(column);
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
                app.requestPopup("History", 320, 480, gui, this::attachInput, this::onClosed);
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
     * the status line; {@link Cott} owns the mathematics.
     *
     * <p>Handlers arrive on worker threads, so all state transitions are synchronized; the only
     * output is the display handle, which is thread-safe by framework contract.
     */
    private static final class Engine {
        /**
         * An evaluation: either a value to put in the display, or a message to report. Keeping them
         * apart is what lets the entry survive a rejection -- a message in the field you type into
         * has to be deleted by hand, and cannot be evaluated again.
         */
        record Eval(String text, boolean ok) {
            static Eval ok(String text) {
                return new Eval(text, true);
            }

            static Eval err(String message) {
                return new Eval(message, false);
            }
        }

        /** Entry characters that end an operand — a following operand token implies multiplication. */
        private static final String OPERAND_TAIL = "0123456789.)eiπωxyz";

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
                        Eval result = Cott.evaluate(entry);
                        if (result.ok()) {
                            status("");
                            display.text(result.text());
                            justEvaluated = true;
                            History h = history;
                            if (h != null) {
                                h.record(entry, result.text());
                            }
                        } else {
                            // The entry stays put, so the expression can be fixed and "=" pressed
                            // again without retyping it.
                            status(result.text());
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
            if (at > 0 && OPERAND_TAIL.indexOf(doc.text().charAt(at - 1)) >= 0 && !isDigitLike(token)) {
                token = "×" + token;   // implicit multiplication: 2π, xy, 3(x+1), ω(...)
            }
            display.insert(token);
            justEvaluated = false;
        }

        /**
         * Typed ASCII to the keypad's glyphs. A keyboard cannot reach ×, ÷ or −, so the editable
         * display accepts *, / and - for them, and w for omega.
         */
        static String normalize(String s) {
            // Whitespace is dropped, not tolerated token by token: the parser has no notion of it,
            // so "1 + 1" used to be a syntax error, and a formal sum comes back joined with
            // spaces -- which made the calculator unable to re-read its own output.
            return adjacency(s.replaceAll("\\s+", "")
                    .replace('*', '×').replace('/', '÷').replace('-', '−').replace('w', 'ω'));
        }

        /** Operand tokens begin with these, so one following an operand means multiplication. */
        private static final String OPERAND_HEAD = "0123456789.(eiπωxyzl";

        /**
         * Make juxtaposition multiply: 2ω, 3(x+1), xy. The keypad has always inserted this × as you
         * press (see {@code insertOperand}), but a typed expression never got it -- so 2ω was a
         * syntax error, and SymEngine read xy as one symbol named "xy" rather than a product. Doing
         * it in normalize rather than in the parser keeps typed input and the keypad agreeing,
         * which is the invariant that matters.
         */
        private static String adjacency(String s) {
            StringBuilder out = new StringBuilder(s.length() + 8);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (i > 0 && OPERAND_TAIL.indexOf(s.charAt(i - 1)) >= 0 && OPERAND_HEAD.indexOf(c) >= 0
                        // ... except mid-numeral, where the digits belong to one operand: 12, 1.5
                        && !(numeral(s.charAt(i - 1)) && numeral(c))) {
                    out.append('×');
                }
                out.append(c);
            }
            return out.toString();
        }

        private static boolean numeral(char c) {
            return (c >= '0' && c <= '9') || c == '.';
        }

        /** Digits and the dot continue a number rather than starting a new operand. */
        private static boolean isDigitLike(String token) {
            char c = token.charAt(0);
            return (c >= '0' && c <= '9') || c == '.';
        }

    }

    /**
     * The COTT engine: the display expression is compiled to a COTT-GRADED term, reduced by the
     * bundled Maude interpreter (maude-wrapper), and the canonical {@code gp(m, g, t)} form is
     * shown as [m]·0^(g+tω), naming the five known points. Integer literals are
     * multiplicities ({@code 2} = [2]·1), ω is grade −1, ÷ is multiplication by the inverse,
     * − is multiplication by −1 (a grade shift of 2). The classical constants and variables
     * (e, i, π, x, y, z, log) have no COTT meaning and report as such.
     */
    /**
     * The engine. One theory, no modes: COTT-ONE, reduced by the bundled Maude interpreter.
     *
     * <p>It replaces the three that came before it — the operational core, the graded carrier and
     * the SymEngine wheel — each of which held a piece the others lacked, so an expression needed
     * whichever one happened to hold the piece it wanted. The merged theory keeps the <em>finer</em>
     * reading wherever two of them disagreed: {@code 1÷1} is {@code 1^1}, not 1, and {@code 0×ω} is
     * {@code 0÷0}, not 1. {@code ≈} projects to the coarse answer when that is what you want.
     *
     * <p>A numeral is a point — {@code pt(k, xp(g,t,r))} is k copies of 0^(g + tω + r) — so
     * {@code 2×3} is 6, {@code 2÷0} is 2ω and {@code 1+1} is 2, none of which the operational core
     * could say. π and e are declared primitives with no exponential form and do not reduce; i does,
     * and comes out as {@code 0^(ω/2)}.
     */
    private static final class Cott {
        private static sibarum.maude.MaudeSession session;

        private static synchronized sibarum.maude.MaudeSession maude() throws java.io.IOException {
            if (session == null) {
                sibarum.maude.MaudeSession m = sibarum.maude.MaudeSession.start();
                try (java.io.InputStream in = sibarum.maude.MaudeSession.class
                        .getResourceAsStream("/cott-one.maude")) {
                    m.load(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
                session = m;
            }
            return session;
        }

        static Engine.Eval evaluate(String displayForm) {
            try {
                String term = new Parser(Engine.normalize(displayForm)).parse();
                return Engine.Eval.ok(show(maude().reduce("COTT-ONE", term).term()));
            } catch (SyntaxException e) {
                return Engine.Eval.err(e.getMessage());
            } catch (sibarum.maude.MaudeException e) {
                return Engine.Eval.err("Error");   // maude rejected the term
            } catch (Throwable t) {   // maude missing or unloadable
                return Engine.Eval.err("COTT unavailable");
            }
        }

        // ---------------------------------------------------------------- display

        /** A rational as Maude prints it. */
        private static final String RAT = "(-?\\d+(?:/\\d+)?)";
        private static final java.util.regex.Pattern XP = java.util.regex.Pattern
                .compile("^xp\\(" + RAT + ", " + RAT + ", " + RAT + "\\)$");

        /** Render a reduced COTT-ONE term back into readable notation. */
        private static String show(String t) {
            t = t.trim();
            int paren = t.indexOf('(');
            if (paren < 0) {
                return leaf(t);
            }
            String head = t.substring(0, paren);
            List<String> a = topLevelArgs(t.substring(paren + 1, t.length() - 1));
            return switch (head) {
                case "pt" -> point(a.get(0), a.get(1));
                // lg and logb return an EXPONENT, so it renders as one: log of -1 to base 0 is ω.
                case "xp" -> exponentOf(t);
                case "wind" -> "1^" + arg(a.get(0), ATOM);
                case "awind" -> "0^" + arg(a.get(0), ATOM);
                case "neg" -> "-" + arg(a.get(0), ATOM);
                case "inv" -> "1÷" + arg(a.get(0), ATOM);
                case "pow" -> arg(a.get(0), ATOM) + "^" + arg(a.get(1), ATOM);
                case "approx" -> "≈" + arg(a.get(0), ATOM);
                case "div" -> arg(a.get(0), POW) + "÷" + arg(a.get(1), POW);
                case "lg" -> "log(" + show(a.get(0)) + ", 0)";
                case "logb" -> "log(" + show(a.get(1)) + ", " + show(a.get(0)) + ")";
                case "times" -> join(a, "×", POW);
                case "plus" -> join(a, "+", MUL);
                default -> t;
            };
        }

        /**
         * {@code pt(k, xp(g,t,r))} — k copies of 0^(g + tω + r). A zero exponent makes it the plain
         * number k; a multiplicity of one makes it the bare point, named if it has a name.
         */
        private static String point(String mult, String exp) {
            java.util.regex.Matcher m = XP.matcher(exp.trim());
            if (!m.matches()) {
                return "pt(" + mult + ", " + show(exp) + ")";   // shouldn't happen; don't fake it
            }
            String g = m.group(1);
            String tw = m.group(2);
            String tor = m.group(3);
            if (g.equals("0") && tw.equals("0") && tor.equals("0")) {
                return mult;   // k copies of 1 IS the number k
            }
            String named = name(g, tw, tor);
            String body = named != null ? named : "0^(" + exponent(g, tw, tor) + ")";
            return switch (mult) {
                case "1" -> body;
                case "-1" -> "-" + body;
                default -> mult + "×" + body;   // 2×ω rather than [2]·ω, so it re-parses
            };
        }

        /** An {@code xp} term standing on its own — a logarithm's result, which IS an exponent. */
        private static String exponentOf(String t) {
            java.util.regex.Matcher m = XP.matcher(t.trim());
            return m.matches() ? exponent(m.group(1), m.group(2), m.group(3)) : t;
        }

        /** The constants. π and e print as themselves; they have no exponential form. */
        private static String leaf(String c) {
            return switch (c) {
                case "zero" -> "0";
                case "one" -> "1";
                case "minusone" -> "-1";
                case "omega" -> "ω";
                case "iu" -> "i";
                case "pin" -> "π";
                case "ee" -> "e";
                default -> c;   // x, y, z, n, or anything unrecognised
            };
        }

        /** The points that have names; null for everything else. */
        private static String name(String g, String tw, String tor) {
            if (!tor.equals("0")) {
                return null;   // a root of the residue zero has no classical name
            }
            if (tw.equals("0")) {
                return switch (g) {
                    case "0" -> "1";
                    case "1" -> "0";
                    case "-1" -> "ω";
                    default -> null;
                };
            }
            if (g.equals("0")) {
                return switch (tw) {
                    case "1" -> "-1";
                    case "1/2" -> "i";
                    default -> null;
                };
            }
            return null;
        }

        /** The exponent g + twω + the torsion, with zero parts and unit coefficients dropped. */
        private static String exponent(String g, String tw, String tor) {
            StringBuilder s = new StringBuilder();
            if (!g.equals("0")) {
                s.append(g);
            }
            if (!tw.equals("0")) {
                plus(s).append(tw.equals("1") ? "ω" : tw + "ω");
            }
            if (!tor.equals("0")) {
                // torsion 1/d is the d-th root of the residue zero, written 0/d
                plus(s).append("0÷").append(denominator(tor));
            }
            return s.isEmpty() ? "0" : s.toString();
        }

        private static StringBuilder plus(StringBuilder s) {
            if (!s.isEmpty()) {
                s.append('+');
            }
            return s;
        }

        /** Torsion p/q prints as its root order; a bare p means order 1. */
        private static String denominator(String rat) {
            int slash = rat.indexOf('/');
            return slash < 0 ? "1" : rat.substring(slash + 1);
        }

        /** Split an argument list on its top-level commas, ignoring commas inside nested parens. */
        static List<String> topLevelArgs(String s) {
            List<String> parts = new java.util.ArrayList<>();
            int depth = 0;
            int start = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(s.substring(start, i).trim());
                    start = i + 1;
                }
            }
            parts.add(s.substring(start).trim());
            return parts;
        }

        // How tightly a rendered form binds, mirroring the parser: ^ takes primary() operands on
        // both sides, × and ÷ take factor(), + takes term(). Decided from the term's HEAD, never by
        // scanning the rendered string — that is what used to turn ω^(1÷2) into ω^1÷2.
        private static final int ADD = 1;
        private static final int MUL = 2;
        private static final int POW = 3;
        private static final int ATOM = 4;

        private static int precedence(String t) {
            int paren = t.indexOf('(');
            if (paren < 0) {
                return ATOM;
            }
            return switch (t.substring(0, paren)) {
                case "plus" -> ADD;
                case "times", "div" -> MUL;
                // a pt renders as a bare number, a name, or k×body — the last of those is a product
                case "pt" -> {
                    String s = show(t);
                    yield s.indexOf('×') >= 0 ? MUL : (s.indexOf('^') >= 0 ? POW : ATOM);
                }
                case "xp" -> show(t).indexOf('+') >= 0 ? ADD : ATOM;
                default -> POW;   // pow, wind, awind, neg, inv, approx, lg, logb
            };
        }

        /** Render {@code term} for a position that needs at least {@code min} binding tightness. */
        private static String arg(String term, int min) {
            String s = show(term);
            return precedence(term) < min ? "(" + s + ")" : s;
        }

        private static String join(List<String> args, String op, int min) {
            return String.join(op, args.stream().map(v -> arg(v, min)).toList());
        }

        private static final class SyntaxException extends RuntimeException {
            SyntaxException(String message) {
                super(message);
            }
        }

        // ---------------------------------------------------------------- parsing

        /**
         * Display expression to a COTT-ONE term. Precedence: ^ over × ÷ over + −.
         *
         * <p>0, 1, ω, -1 and i are emitted as the NAMED constants rather than as {@code pt} forms,
         * because every residue rule matches on those names literally — {@code times(A, wind(one))}
         * cannot fire on {@code wind(pt(1, xp(0,0,0)))}. Any other numeral becomes a multiplicity.
         */
        private static final class Parser {
            private final String s;
            private int p;

            Parser(String s) {
                this.s = s;
            }

            String parse() {
                String e = expr();
                if (p < s.length()) {
                    throw new SyntaxException("Error");
                }
                return e;
            }

            private String expr() {
                String a = term();
                while (p < s.length() && (peek() == '+' || peek() == '−')) {
                    char op = next();
                    String b = term();
                    a = op == '+' ? "plus(" + a + ", " + b + ")"
                                  : "plus(" + a + ", neg(" + b + "))";
                }
                return a;
            }

            private String term() {
                String a = factor();
                while (p < s.length() && (peek() == '×' || peek() == '÷')) {
                    char op = next();
                    String b = factor();
                    a = op == '×' ? "times(" + a + ", " + b + ")"
                                  : "div(" + a + ", " + b + ")";
                }
                return a;
            }

            private String factor() {
                String a = primary();
                if (p < s.length() && peek() == '^') {
                    next();
                    a = "pow(" + a + ", " + primary() + ")";
                }
                return a;
            }

            private String primary() {
                if (p >= s.length()) {
                    throw new SyntaxException("Error");
                }
                char c = peek();
                if (c == '−') {
                    next();
                    return "neg(" + primary() + ")";
                }
                if (c == '(') {
                    next();
                    String e = expr();
                    if (p >= s.length() || next() != ')') {
                        throw new SyntaxException("Error");
                    }
                    return e;
                }
                if (c == 'l') {
                    return logCall();
                }
                String named = switch (c) {
                    case 'ω' -> "omega";
                    case 'i' -> "iu";
                    case 'π' -> "pin";
                    case 'e' -> "ee";
                    case 'x' -> "x";
                    case 'y' -> "y";
                    case 'z' -> "z";
                    default -> null;
                };
                if (named != null) {
                    next();
                    return named;
                }
                if (numeral(c)) {
                    return number();
                }
                throw new SyntaxException("'" + c + "' not in COTT");
            }

            /** {@code log(x, b)} — the log of x to base b, which is COTT-ONE's logb. */
            private String logCall() {
                expect("log");
                if (p >= s.length() || next() != '(') {
                    throw new SyntaxException("Error");
                }
                String of = expr();
                if (p >= s.length() || next() != ',') {
                    throw new SyntaxException("log needs a base");
                }
                String base = expr();
                if (p >= s.length() || next() != ')') {
                    throw new SyntaxException("Error");
                }
                return "logb(" + base + ", " + of + ")";
            }

            private void expect(String word) {
                if (!s.startsWith(word, p)) {
                    throw new SyntaxException("Error");
                }
                p += word.length();
            }

            /**
             * A numeral, decimals included, as an exact multiplicity — 2.5 is 5/2, not a float.
             * 0 and 1 come back as the named constants; everything else is k copies of the unit.
             */
            private String number() {
                StringBuilder d = new StringBuilder();
                while (p < s.length() && numeral(peek())) {
                    d.append(next());
                }
                String text = d.toString();
                if (text.equals("0")) {
                    return "zero";
                }
                if (text.equals("1")) {
                    return "one";
                }
                int dot = text.indexOf('.');
                if (dot < 0) {
                    return "pt(" + text + ", xp(0, 0, 0))";
                }
                String digits = text.replace(".", "");
                if (digits.isEmpty() || text.indexOf('.', dot + 1) >= 0) {
                    throw new SyntaxException("Error");
                }
                long scale = 1;
                for (int k = text.length() - dot - 1; k > 0; k--) {
                    scale *= 10;
                }
                return "pt(" + Long.parseLong(digits) + "/" + scale + ", xp(0, 0, 0))";
            }

            private static boolean numeral(char c) {
                return (c >= '0' && c <= '9') || c == '.';
            }

            private char peek() {
                return s.charAt(p);
            }

            private char next() {
                return s.charAt(p++);
            }
        }
    }
}
