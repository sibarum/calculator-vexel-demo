package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
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
            // Exercise the COTT-OP vertical: 0 over 0 in an additive context leaves the residue 1.
            for (String k : new String[]{"x", "+", "0", "÷", "0", "="}) {
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
        // since the replacement contains no w. text() puts the caret at the end, which is where
        // it already is while typing left to right.
        display.onChange(s -> {
            if (s.indexOf('w') >= 0) {
                display.text(s.replace('w', 'ω'));
            }
        });

        // The keypad: rows of flex-grown buttons, no hard-coded rects anywhere. Beyond digits: the
        // constants e/i/π, the wheel's ω (= 1/0), plotting variables x/y/z, ^ for n^x, and log(x, n)
        // for log base n (via the log/comma/paren keys).
        // "alg" cycles the engine: COTT-OP (the operational core, the default) then SymEngine
        // then the legacy graded carrier. The first two run through maude-wrapper.
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
                .children(display.node(), pad);
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

        /** The engines, in the order the "alg" key cycles them. */
        private static final String[] MODES = {"COTT-OP", "sym", "graded"};

        private final TextField display;
        private boolean justEvaluated;
        /** Index into {@link #MODES}. COTT-OP -- the operational core -- is the default. */
        private int mode;

        Engine(TextField display) {
            this.display = display;
        }

        synchronized void press(String label) {
            // The field is the source of truth -- it may have been typed into directly since the
            // last keypad press, so read it rather than tracking a shadow copy.
            String entry = display.text();
            switch (label) {
                case "C" -> { entry = ""; justEvaluated = false; }
                case "alg" -> {
                    mode = (mode + 1) % MODES.length;
                    justEvaluated = false;
                    display.text("alg: " + MODES[mode]);
                    return;
                }
                case "DEL" -> {
                    if (justEvaluated) { entry = ""; justEvaluated = false; }
                    else if (!entry.isEmpty()) { entry = entry.substring(0, entry.length() - 1); }
                }
                case "=" -> {
                    if (!entry.isEmpty()) {
                        entry = switch (mode) {
                            case 0 -> CottOp.evaluate(entry);
                            case 1 -> evaluate(entry);
                            default -> Cott.evaluate(entry);
                        };
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
            display.text(entry);
        }

        /**
         * Typed ASCII to the keypad's glyphs. A keyboard cannot reach ×, ÷ or −, so the editable
         * display accepts *, / and - for them, and w for omega.
         */
        static String normalize(String s) {
            return s.replace('*', '×').replace('/', '÷').replace('-', '−').replace('w', 'ω');
        }

        /** Digits and the dot continue a number rather than starting a new operand. */
        private static boolean isDigitLike(String token) {
            char c = token.charAt(0);
            return (c >= '0' && c <= '9') || c == '.';
        }

        private static String evaluate(String displayForm) {
            try {
                return prettify(sibarum.symengine.Expr.parse(toSymEngine(normalize(displayForm))).str());
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
                String term = new Parser(Engine.normalize(displayForm)).parse();
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
        private static final String RAT = "(-?\\d+(?:/\\d+)?)";

        private static String pretty(String term) {
            // A formal sum has no definite answer, so it comes back unreduced. Render it as a sum
            // of its prettified parts rather than dumping the raw Maude term.
            if (term.startsWith("gadd(") && term.endsWith(")")) {
                List<String> parts = topLevelArgs(term.substring(5, term.length() - 1));
                if (parts.size() > 1) {
                    return String.join(" + ", parts.stream().map(Cott::pretty).toList());
                }
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^gp\\((-?\\d+), " + RAT + ", " + RAT + ", " + RAT + "\\)$").matcher(term);
            if (!m.matches()) {
                return term;
            }
            String mult = m.group(1);
            String named = name(m.group(2), m.group(3), m.group(4));
            String body = named != null
                    ? named
                    : "0^(" + exponent(m.group(2), m.group(3), m.group(4)) + ")";
            return mult.equals("1") ? body : "[" + mult + "]·" + body;
        }

        /** Split an argument list on its top-level commas, ignoring commas inside nested parens. */
        private static List<String> topLevelArgs(String s) {
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
                plus(s).append("0/").append(denominator(tor));
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

        private static final class SyntaxException extends RuntimeException {
            SyntaxException(String message) {
                super(message);
            }
        }

        /** An exact rational, kept in lowest terms with a positive denominator. */
        private record Rat(long num, long den) {
            static final Rat ZERO = new Rat(0, 1);
            static final Rat ONE = new Rat(1, 1);

            Rat {
                if (den == 0) {
                    throw new SyntaxException("Error");
                }
                long g = gcd(Math.abs(num), Math.abs(den));
                if (g != 0) {
                    num /= g;
                    den /= g;
                }
                if (den < 0) {
                    num = -num;
                    den = -den;
                }
            }

            private static long gcd(long a, long b) {
                return b == 0 ? a : gcd(b, a % b);
            }

            boolean isZero() {
                return num == 0;
            }

            /** Reduce into the half-open interval from 0 to k. */
            Rat mod(long k) {
                long f = Math.floorDiv(num, den * k);
                return new Rat(num - f * den * k, den);
            }

            Rat add(Rat o) {
                return new Rat(num * o.den + o.num * den, den * o.den);
            }

            Rat neg() {
                return new Rat(-num, den);
            }

            Rat mul(Rat o) {
                return new Rat(num * o.num, den * o.den);
            }

            Rat div(Rat o) {
                if (o.isZero()) {
                    throw new SyntaxException("Error");
                }
                return new Rat(num * o.den, den * o.num);
            }

            /** Maude's Rat literal form. */
            @Override
            public String toString() {
                return den == 1 ? String.valueOf(num) : num + "/" + den;
            }
        }

        /**
         * An exponent with three exact-rational parts: grade n, twist m closing at 2, and
         * torsion t closing at 1. The torsion holds the fractions of the residue zero -- 0/d
         * is torsion 1/d, and d copies of it sum back to zero.
         */
        private record ExpVal(Rat n, Rat m, Rat t) {
            ExpVal {
                m = m.mod(2);
                t = t.mod(1);
            }

            static final ExpVal ZERO = new ExpVal(Rat.ZERO, Rat.ZERO, Rat.ZERO);

            boolean isZero() {
                return n.isZero() && m.isZero() && t.isZero();
            }

            /** True when this is a plain scalar, so it may be a factor or a divisor. */
            boolean isScalar() {
                return m.isZero() && t.isZero();
            }

            ExpVal add(ExpVal o) {
                return new ExpVal(n.add(o.n), m.add(o.m), t.add(o.t));
            }

            ExpVal neg() {
                return new ExpVal(n.neg(), m.neg(), t.neg());
            }

            /** Partial: twist times twist would be ω squared, the next floor of the tower. */
            ExpVal mul(ExpVal o) {
                if (isScalar()) {
                    return new ExpVal(n.mul(o.n), n.mul(o.m), n.mul(o.t));
                }
                if (o.isScalar()) {
                    return o.mul(this);
                }
                throw new SyntaxException("ω² not in COTT");
            }

            /**
             * Dividing the residue zero by k does not give zero -- it gives the torsion 1/k,
             * the k-th root. Anything else divides componentwise.
             */
            ExpVal div(ExpVal o) {
                if (!o.isScalar()) {
                    throw new SyntaxException("÷ω not in COTT");
                }
                if (isZero()) {
                    return new ExpVal(Rat.ZERO, Rat.ZERO, Rat.ONE.div(o.n));
                }
                return new ExpVal(n.div(o.n), m.div(o.n), t.div(o.n));
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

            /**
             * An exponent is n + mω with n and m rational. Parenthesised exponents take a full
             * expression, so {@code 0^(ω÷2)} is the half twist; a bare exponent is a single
             * signed factor, so {@code 0^ω÷2} stays (0^ω)÷2 as precedence demands.
             *
             * <p>A twist-free exponent goes through {@code gpow}, which keeps the multiplicity.
             * Anything with an ω component routes through COTT's own power law, which is defined
             * on points rather than multiplicity-carrying terms.
             */
            private String power(String a) {
                ExpVal e;
                if (p < s.length() && peek() == '(') {
                    next();
                    e = expExpr();
                    expect(')');
                } else {
                    e = expFactorSigned();
                }
                if (e.isScalar()) {
                    return "gpow(" + a + ", " + e.n() + ")";
                }
                return "gr(pt(" + a + ") ^ xp(" + e.n() + ", " + e.m() + ", " + e.t() + "))";
            }

            private ExpVal expExpr() {
                ExpVal a = expTerm();
                while (p < s.length() && (peek() == '+' || peek() == '−')) {
                    char op = next();
                    ExpVal b = expTerm();
                    a = op == '+' ? a.add(b) : a.add(b.neg());
                }
                return a;
            }

            private ExpVal expTerm() {
                ExpVal a = expFactorSigned();
                while (p < s.length() && (peek() == '×' || peek() == '÷')) {
                    char op = next();
                    ExpVal b = expFactorSigned();
                    a = op == '×' ? a.mul(b) : a.div(b);
                }
                return a;
            }

            private ExpVal expFactorSigned() {
                if (p < s.length() && peek() == '−') {
                    next();
                    return expFactorSigned().neg();
                }
                if (p >= s.length()) {
                    throw new SyntaxException("Error");
                }
                char c = peek();
                if (c == '(') {
                    next();
                    ExpVal e = expExpr();
                    expect(')');
                    return e;
                }
                if (c == 'ω') {
                    next();
                    return new ExpVal(Rat.ZERO, Rat.ONE, Rat.ZERO);
                }
                if (c >= '0' && c <= '9') {
                    return new ExpVal(new Rat(Long.parseLong(integer()), 1), Rat.ZERO, Rat.ZERO);
                }
                throw new SyntaxException("'" + c + "' not in an exponent");
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
                    return "gp(1, -1, 0, 0)";
                }
                if (c == 'i') {   // i = 0^(ω/2), the half twist
                    next();
                    return "gp(1, 0, 1/2, 0)";
                }
                if (c >= '0' && c <= '9') {
                    String n = integer();
                    // The literal 0 is COTT's circle point 0 (grade 1), not the integer zero —
                    // there is no multiplicity 0. Other integers are multiplicities at grade 0.
                    return Long.parseLong(n) == 0 ? "gp(1, 1, 0, 0)" : "gp(" + n + ", 0, 0, 0)";
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
                return "gmul(gp(1, 0, 1, 0), " + term + ")";
            }

            private char peek() {
                return s.charAt(p);
            }

            private char next() {
                return s.charAt(p++);
            }
        }
    }

    /**
     * The COTT-OP engine: the operational core, where identities belong to operations and each
     * is a family that keeps its winding number. Numerals are OPAQUE atoms -- the twelve rules
     * need them distinguishable, not computable -- so {@code 2/2} gives {@code 1^2} while
     * {@code 2×3} has no defined answer and comes back unreduced. That is the theory's own rule
     * rather than a shortcoming: a term with no definite answer is not rewritten.
     */
    private static final class CottOp {
        private static sibarum.maude.MaudeSession session;

        private static synchronized sibarum.maude.MaudeSession maude() throws java.io.IOException {
            if (session == null) {
                sibarum.maude.MaudeSession m = sibarum.maude.MaudeSession.start();
                try (java.io.InputStream in = sibarum.maude.MaudeSession.class
                        .getResourceAsStream("/cott-op.maude")) {
                    m.load(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
                session = m;
            }
            return session;
        }

        static String evaluate(String displayForm) {
            try {
                String term = new OpParser(Engine.normalize(displayForm)).parse();
                return show(maude().reduce("COTT-OP", term).term());
            } catch (Cott.SyntaxException e) {
                return e.getMessage();
            } catch (sibarum.maude.MaudeException e) {
                return "Error";
            } catch (Throwable t) {
                return "COTT unavailable";
            }
        }

        // ---------------------------------------------------------------- display

        /** Render a reduced COTT-OP term back into readable notation. */
        private static String show(String t) {
            t = t.trim();
            int paren = t.indexOf('(');
            if (paren < 0) {
                return leaf(t);
            }
            String head = t.substring(0, paren);
            List<String> a = Cott.topLevelArgs(t.substring(paren + 1, t.length() - 1));
            return switch (head) {
                case "num" -> a.get(0);
                case "wind" -> "1^" + tight(a.get(0));
                case "awind" -> "0^" + tight(a.get(0));
                case "neg" -> "-" + tight(a.get(0));
                case "inv" -> "1/" + tight(a.get(0));
                case "pow" -> tight(a.get(0)) + "^" + tight(a.get(1));
                case "approx" -> "≈" + tight(a.get(0));
                case "times" -> join(a, "×");
                case "plus" -> join(a, "+");
                default -> t;
            };
        }

        /** The constants, including the two erasures, which have no value to print. */
        private static String leaf(String c) {
            return switch (c) {
                case "zero" -> "0";
                case "one" -> "1";
                case "minusone" -> "-1";
                case "omega" -> "ω";
                case "idTimes", "idPlus" -> "_";
                default -> c;   // x, y, z, or anything unrecognised
            };
        }

        private static String join(List<String> args, String op) {
            return String.join(op, args.stream().map(CottOp::tight).toList());
        }

        /** Parenthesise a rendered subterm when it could bind loosely than its parent. */
        private static String tight(String term) {
            String s = show(term);
            return s.contains("+") || s.contains("×") ? "(" + s + ")" : s;
        }

        // ---------------------------------------------------------------- parsing

        /**
         * Display expression to a COTT-OP term. Everything is a Val, so the exponent is an
         * ordinary expression rather than a rational -- no separate exponent grammar is needed.
         */
        private static final class OpParser {
            private final String s;
            private int p;

            OpParser(String s) {
                this.s = s;
            }

            String parse() {
                String e = expr();
                if (p < s.length()) {
                    throw new Cott.SyntaxException("Error");
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
                                  : "times(" + a + ", inv(" + b + "))";
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
                    throw new Cott.SyntaxException("Error");
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
                        throw new Cott.SyntaxException("Error");
                    }
                    return e;
                }
                if (c == 'ω') {
                    next();
                    return "omega";
                }
                if (c == 'x' || c == 'y' || c == 'z') {
                    next();
                    return String.valueOf(c);
                }
                if (c >= '0' && c <= '9') {
                    StringBuilder d = new StringBuilder();
                    while (p < s.length() && peek() >= '0' && peek() <= '9') {
                        d.append(next());
                    }
                    // 0 and 1 are the named boundary values; every other numeral is an atom
                    return switch (d.toString()) {
                        case "0" -> "zero";
                        case "1" -> "one";
                        default -> "num(" + d + ")";
                    };
                }
                throw new Cott.SyntaxException("'" + c + "' not in COTT-OP");
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
