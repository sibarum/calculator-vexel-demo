package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.CoordinateSpace;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;
import sibarum.tactroller.api.NativeWindow;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;

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
            // Exercise the whole COTT vertical before the shot: 0^w must render as -1.
            for (String k : new String[]{"alg", "0", "^", "ω", "="}) {
                engine.press(k);
            }
            GuiApp.capture(gui, W, H, 0.06f, 0.07f, 0.09f, args.length >= 2 ? args[1] : "calculator.png");
            System.out.println("captured");
            return;
        }

        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        try (Tactroller input = openInput();
             GuiApp app = new GuiApp("Calculator", W, H)) {
            attachInput(input, app);
            TactrollerInputBridge bridge = input == null ? null : new TactrollerInputBridge(input, gui.bus());
            app.run(gui, maxFrames, () -> pump(bridge));
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
        Node display = gui.text("0")
                .width(Length.FILL).height(Length.rem(5))
                .background(PANEL).corner(Length.rem(0.75f)).border(Length.rem(0.1f), LINE)
                .lit(true).elevation(Length.rem(0.5f))
                .padding(Length.dp(16))
                .textSize(Length.rem(1.75f)).textColor(INK)
                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE);

        Engine engine = new Engine(display);

        // The keypad: rows of flex-grown buttons, no hard-coded rects anywhere. Beyond digits: the
        // constants e/i/π, the wheel's ω (= 1/0), plotting variables x/y/z, ^ for n^x, and log(x, n)
        // for log base n (via the log/comma/paren keys).
        // "alg" toggles the engine: SymEngine (classical CAS) <-> COTT (the wheel-algebra torus,
        // reduced by the bundled Maude interpreter through maude-wrapper).
        String[][] rows = {
                {"C", "DEL", "(", ")", "alg", "÷"},
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
                boolean op = java.util.Set.of("÷", "×", "−", "+", "^", "log", ",", "(", ")", "C", "DEL", "alg")
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

        Node root = gui.column().width(Length.FILL).height(Length.FILL)
                .padding(Length.dp(16)).gap(Length.rem(0.75f))
                .children(display, pad);
        gui.root().background(BG).children(root);
        return engine;
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
     * The symbolic expression engine, backed by SymEngine through symengine-panama. The entry is an
     * expression string in display form (π, ω, ×, ^, ...); "=" translates it to SymEngine syntax,
     * parses (which auto-simplifies), and prettifies the canonical result back.
     *
     * <p>Wheel algebra falls out of SymEngine's extended arithmetic: 1/0 is complex infinity
     * ({@code zoo}, shown as ω) and 0/0 is undefined ({@code nan}, shown as ⊥), with the wheel
     * identities ω+a=ω, 0·ω=⊥, 1/ω=0 holding under simplification.
     *
     * <p>Handlers arrive on worker threads, so all state transitions are synchronized; the only
     * output is the display handle, which is thread-safe by framework contract.
     */
    private static final class Engine {
        /** Entry characters that end an operand — a following operand token implies multiplication. */
        private static final String OPERAND_TAIL = "0123456789.)eiπωxyz";

        private final Node display;
        private String entry = "";
        private boolean justEvaluated;
        /** false = SymEngine (classical CAS); true = COTT (wheel-algebra torus via Maude). */
        private boolean cott;

        Engine(Node display) {
            this.display = display;
        }

        synchronized void press(String label) {
            switch (label) {
                case "C" -> { entry = ""; justEvaluated = false; }
                case "alg" -> {
                    cott = !cott;
                    entry = "";
                    justEvaluated = false;
                    display.text(cott ? "alg: COTT" : "alg: sym");
                    return;
                }
                case "DEL" -> {
                    if (justEvaluated) { entry = ""; justEvaluated = false; }
                    else if (!entry.isEmpty()) { entry = entry.substring(0, entry.length() - 1); }
                }
                case "=" -> {
                    if (!entry.isEmpty()) {
                        entry = cott ? Cott.evaluate(entry) : evaluate(entry);
                        justEvaluated = true;
                    }
                }
                case "+", "−", "×", "÷", "^", ",", ")" -> { entry += label; justEvaluated = false; }
                default -> { // an operand token: digit, ., (, log(, e, i, π, ω, x, y, z
                    String token = label.equals("log") ? "log(" : label;
                    if (justEvaluated) { entry = ""; }
                    if (!entry.isEmpty() && OPERAND_TAIL.indexOf(entry.charAt(entry.length() - 1)) >= 0
                            && !isDigitLike(token) ) {
                        entry += "×";   // implicit multiplication: 2π, xy, 3(x+1), ω(...)
                    }
                    entry += token;
                    justEvaluated = false;
                }
            }
            display.text(entry.isEmpty() ? "0" : entry);
        }

        /** Digits and the dot continue a number rather than starting a new operand. */
        private static boolean isDigitLike(String token) {
            char c = token.charAt(0);
            return (c >= '0' && c <= '9') || c == '.';
        }

        private static String evaluate(String displayForm) {
            try {
                return prettify(sibarum.symengine.Expr.parse(toSymEngine(displayForm)).str());
            } catch (sibarum.symengine.SymEngineException e) {
                return "Error";
            } catch (Throwable t) {   // native library missing/unloadable
                return "CAS unavailable";
            }
        }

        private static String toSymEngine(String s) {
            return s.replace("×", "*").replace("÷", "/").replace("−", "-").replace("^", "**")
                    .replace("π", "pi").replace("ω", "zoo")
                    .replaceAll("\\be\\b", "E").replaceAll("\\bi\\b", "I");
        }

        private static String prettify(String s) {
            // The wheel bottom prints as its definition 0/0 (the font has no ⊥ glyph); it re-parses to nan.
            return s.replace("**", "^")
                    .replaceAll("\\bzoo\\b", "ω").replaceAll("\\bnan\\b", "0/0")
                    .replaceAll("\\bpi\\b", "π")
                    .replaceAll("\\bE\\b", "e").replaceAll("\\bI\\b", "i");
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
    private static final class Cott {
        private static sibarum.maude.MaudeSession session;

        private static synchronized sibarum.maude.MaudeSession maude() throws java.io.IOException {
            if (session == null) {
                sibarum.maude.MaudeSession m = sibarum.maude.MaudeSession.start();
                try (java.io.InputStream in = sibarum.maude.MaudeSession.class
                        .getResourceAsStream("/cott.maude")) {
                    m.load(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
                session = m;
            }
            return session;
        }

        static String evaluate(String displayForm) {
            try {
                String term = new Parser(displayForm).parse();
                String result = maude().reduce("COTT-GRADED", term).term();
                return pretty(result);
            } catch (SyntaxException e) {
                return e.getMessage();
            } catch (sibarum.maude.MaudeException e) {
                return "Error";   // maude rejected the term
            } catch (Throwable t) {   // maude missing or unloadable
                return "COTT unavailable";
            }
        }

        /**
         * gp(m, g, t) -> a readable point. Grades and twists are rationals. The five named
         * points come out by name -- 0^0 = 1, 0^1 = 0, 0^-1 = ω, 0^ω = -1, 0^(ω/2) = i --
         * and anything else prints as [m]·0^(g+tω). A non-gp result (a formal sum, a stuck
         * power) prints as the honest Maude normal form rather than being faked.
         */
        private static String pretty(String term) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^gp\\((-?\\d+), (-?\\d+(?:/\\d+)?), (-?\\d+(?:/\\d+)?)\\)$").matcher(term);
            if (!m.matches()) {
                return term;
            }
            String mult = m.group(1);
            String named = name(m.group(2), m.group(3));
            String body = named != null ? named : "0^(" + exponent(m.group(2), m.group(3)) + ")";
            return mult.equals("1") ? body : "[" + mult + "]·" + body;
        }

        /** The points that have names; null for everything else. */
        private static String name(String g, String t) {
            if (t.equals("0")) {
                return switch (g) {
                    case "0" -> "1";
                    case "1" -> "0";
                    case "-1" -> "ω";
                    default -> null;
                };
            }
            if (g.equals("0")) {
                return switch (t) {
                    case "1" -> "-1";
                    case "1/2" -> "i";
                    default -> null;
                };
            }
            return null;
        }

        /** The exponent g + tω, with zero parts and unit coefficients dropped. */
        private static String exponent(String g, String t) {
            if (t.equals("0")) {
                return g;
            }
            String tw = t.equals("1") ? "ω" : t + "ω";
            return g.equals("0") ? tw : g + "+" + tw;
        }

        private static final class SyntaxException extends RuntimeException {
            SyntaxException(String message) {
                super(message);
            }
        }

        /** Display expression -> COTT-GRADED term. Precedence: ^ over × ÷ over + −. */
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
                    a = "gadd(" + a + ", " + (op == '+' ? b : neg(b)) + ")";
                }
                return a;
            }

            private String term() {
                String a = factor();
                while (p < s.length() && (peek() == '×' || peek() == '÷')) {
                    char op = next();
                    String b = factor();
                    a = "gmul(" + a + ", " + (op == '×' ? b : "gpow(" + b + ", -1)") + ")";
                }
                return a;
            }

            private String factor() {
                String a = primary();
                if (p < s.length() && peek() == '^') {
                    next();
                    a = power(a);
                }
                return a;
            }

            /** Exponent forms: n, ω (grade -> twist), (n), or (p÷q) rational. */
            private String power(String a) {
                if (p < s.length() && peek() == 'ω') {
                    next();
                    return "gr(pt(" + a + ") ^ xp(0, 1))";
                }
                if (p < s.length() && peek() == '(') {
                    next();
                    String num = integer();
                    if (p < s.length() && peek() == '÷') {
                        next();
                        String den = integer();
                        expect(')');
                        return "gpow(" + a + ", " + num + "/" + den + ")";
                    }
                    expect(')');
                    return "gpow(" + a + ", " + num + ")";
                }
                return "gpow(" + a + ", " + integer() + ")";
            }

            private void expect(char c) {
                if (p >= s.length() || next() != c) {
                    throw new SyntaxException("Error");
                }
            }

            private String primary() {
                if (p >= s.length()) {
                    throw new SyntaxException("Error");
                }
                char c = peek();
                if (c == '−') {   // unary minus: multiply by -1
                    next();
                    return neg(primary());
                }
                if (c == '(') {
                    next();
                    String e = expr();
                    if (p >= s.length() || next() != ')') {
                        throw new SyntaxException("Error");
                    }
                    return e;
                }
                if (c == 'ω') {
                    next();
                    return "gp(1, -1, 0)";
                }
                if (c >= '0' && c <= '9') {
                    String n = integer();
                    // The literal 0 is COTT's circle point 0 (grade 1), not the integer zero —
                    // there is no multiplicity 0. Other integers are multiplicities at grade 0.
                    return Long.parseLong(n) == 0 ? "gp(1, 1, 0)" : "gp(" + n + ", 0, 0)";
                }
                throw new SyntaxException("'" + c + "' not in COTT");
            }

            /** An integer literal, optional leading − (for exponents and literals). */
            private String integer() {
                StringBuilder n = new StringBuilder();
                if (p < s.length() && peek() == '−') {
                    next();
                    n.append('-');
                }
                if (p >= s.length() || peek() < '0' || peek() > '9') {
                    throw new SyntaxException("Error");
                }
                while (p < s.length() && peek() >= '0' && peek() <= '9') {
                    n.append(next());
                }
                return n.toString();
            }

            private static String neg(String term) {
                return "gmul(gp(1, 0, 1), " + term + ")";
            }

            private char peek() {
                return s.charAt(p);
            }

            private char next() {
                return s.charAt(p++);
            }
        }
    }

    private CalculatorApp() {
    }
}
