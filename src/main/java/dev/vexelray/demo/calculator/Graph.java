package dev.vexelray.demo.calculator;

import sibarum.cott.algebra.Pair;
import sibarum.cott.algebra.TractionAlgebra;
import sibarum.cott.calculator.CalculatorException;
import sibarum.cott.calculator.Mode;
import sibarum.cott.calculator.Modeset;
import sibarum.cott.calculator.Result;
import sibarum.cott.notation.Expr;
import sibarum.cott.notation.SyntaxException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * An expression in one free name, evaluated across a fixed range of it. Pure: no GUI, no window.
 *
 * <p>Every sample is an exact rational, a whole multiple of the {@link Grid}'s step — {@code k/20} from
 * {@link #FROM} to {@link #TO} on the tape — so the engine sees the same kind of number a person would type, and
 * {@code 0} is one of them wherever it is in range — which is where {@code 1/x} shows what this calculator is about.
 *
 * <p>A graph keeps what it was drawn from: the expression, already substituted, and the modes it was evaluated in.
 * That is what lets the plot explorer sample it again over another range and get the same curve, whatever the
 * settings have been changed to since.
 *
 * <p>Each sample is read on the real line. Where the value has a real number — a finite double, or a pair whose
 * algebra's meaning comes to one with no zero denominator and no imaginary part — that is its height. Where it has none, the sample is a
 * {@linkplain Sample#marker() marker} carrying the engine's own text for the value ({@code ω}, {@code 0ω},
 * {@code ∞}, a point off the line), to be drawn as a label rather than as a gap or a spike.
 *
 * <p>A multiple of zero or of {@code ω} is read as its multiple, with its {@linkplain Order order} kept beside it:
 * {@code Z(3, 1)} is {@code 0·3} and stands at 3, and {@code Q(3, 0)} and {@code Z(3, −1)} are {@code ω·3} and stand
 * at 3 too, so {@code x/0} is a curve rather than a row of markers, told from the plain numbers by its colour.
 *
 * @param name    the free name the curve is over
 * @param samples in increasing x; a sample the engine refused has a null {@code value}
 * @param refusal the engine's reason for the first sample it refused, or null if it refused none
 * @param expr    the expression, with only {@code name} free
 * @param modes   the mode of each modeset it was evaluated in
 */
record Graph(String name, List<Sample> samples, String refusal, Expr expr, Map<Modeset, Mode> modes) {

    static final int FROM = -10;
    static final int TO = 10;
    static final int PER_UNIT = 20;

    /** The tape's samples: {@code k/20} from {@link #FROM} to {@link #TO}. */
    static final Grid TAPE = new Grid(BigDecimal.valueOf(FROM), new BigDecimal("0.05"), (TO - FROM) * PER_UNIT + 1);

    /**
     * Where to sample: {@code count} points, {@code from + k·step}. Exact, so every position is a decimal the engine
     * reads as a rational with no rounding.
     */
    record Grid(BigDecimal from, BigDecimal step, int count) {

        /** The most samples one grid may ask for, however wide the range or fine the step. */
        static final int MOST = 4000;

        Grid {
            if (step.signum() <= 0) throw new IllegalArgumentException("a grid steps forward");
            count = Math.clamp(count, 1, MOST);
        }

        /**
         * A grid covering {@code lo} to {@code hi} in steps of 1, 2 or 5 times a power of ten, as near as that
         * comes to {@code target} samples, starting on a multiple of the step so that {@code 0} is on it when it is
         * in range, and so that two grids of one step share their samples.
         */
        static Grid covering(double lo, double hi, int target) {
            BigDecimal step = niceStep((hi - lo) / Math.max(1, target - 1));
            BigDecimal first = BigDecimal.valueOf(lo).divide(step, 0, java.math.RoundingMode.FLOOR).multiply(step);
            BigDecimal last = BigDecimal.valueOf(hi).divide(step, 0, java.math.RoundingMode.CEILING).multiply(step);
            int count = last.subtract(first).divide(step, 0, java.math.RoundingMode.CEILING).intValueExact() + 1;
            return new Grid(first, step, count);
        }

        /** 1, 2 or 5 times a power of ten: the one at or just above {@code raw}. */
        static BigDecimal niceStep(double raw) {
            if (!(raw > 0) || !Double.isFinite(raw)) raw = 1;
            int exponent = (int) Math.floor(Math.log10(raw));
            double m = raw / Math.pow(10, exponent);
            int digit = m <= 1 ? 1 : m <= 2 ? 2 : m <= 5 ? 5 : 10;
            return BigDecimal.valueOf(digit).scaleByPowerOfTen(exponent).stripTrailingZeros();
        }

        /** The {@code k}th position. */
        BigDecimal at(int k) {
            return from.add(step.multiply(BigDecimal.valueOf(k)));
        }

        double lo() {
            return from.doubleValue();
        }

        double hi() {
            return at(count - 1).doubleValue();
        }
    }

    /**
     * The power of zero a reading is a multiple of: {@code 1·n}, {@code 0·n}, or {@code ω·n}, which is
     * {@code 0^−1·n}. The {@code q} of a {@code Z(p, q)}.
     */
    enum Order {
        ONE, ZERO, OMEGA
    }

    /**
     * One evaluation.
     *
     * @param x      the sample's position, as a number
     * @param xText  the same position written as a decimal, exactly
     * @param value  the engine's text for the answer, or null when it refused this one
     * @param y      the real reading, or NaN when there is none; for a multiple of zero or {@code ω}, the multiple
     * @param order  what {@code y} is a multiple of
     */
    record Sample(double x, String xText, String value, double y, Order order) {

        Sample(double x, String xText, String value, double y) {
            this(x, xText, value, y, Order.ONE);
        }

        boolean refused() {
            return value == null;
        }

        /** Evaluated, but with no real number to put on the y axis. */
        boolean marker() {
            return value != null && Double.isNaN(y);
        }

        boolean onCurve() {
            return value != null && !Double.isNaN(y);
        }
    }

    Graph {
        samples = List.copyOf(samples);
        modes = Map.copyOf(modes);
    }

    /** Whether the engine refused every sample, so there is nothing to draw and {@link #refusal} says why. */
    boolean empty() {
        return samples.stream().allMatch(Sample::refused);
    }

    /** The first sample's position: where the samples start. */
    double from() {
        return samples.getFirst().x();
    }

    /** The last sample's position. */
    double to() {
        return samples.getLast().x();
    }

    /**
     * Sample {@code expr} over {@code name}, which must be its only free name, across the tape's range.
     * {@code evaluate} answers a closed expression in {@code modes}, or throws the engine's refusal.
     */
    static Graph of(Expr expr, String name, Map<Modeset, Mode> modes, Function<Expr, Result.Value> evaluate) {
        return over(expr, name, modes, TAPE, evaluate, () -> false);
    }

    /**
     * The same expression sampled again on {@code grid}, as {@code evaluate} answers it. {@code stale} is asked
     * between samples, and once it says the answer is no longer wanted this stops and returns null.
     */
    Graph over(Grid grid, Function<Expr, Result.Value> evaluate, BooleanSupplier stale) {
        return over(expr, name, modes, grid, evaluate, stale);
    }

    private static Graph over(Expr expr, String name, Map<Modeset, Mode> modes, Grid grid,
                              Function<Expr, Result.Value> evaluate, BooleanSupplier stale) {
        List<Sample> out = new ArrayList<>(grid.count());
        String refusal = null;
        for (int k = 0; k < grid.count(); k++) {
            if (stale.getAsBoolean()) return null;
            BigDecimal at = grid.at(k);
            double x = at.doubleValue();
            String xText = at.signum() == 0 ? "0" : at.stripTrailingZeros().toPlainString();
            try {
                Result.Value v = evaluate.apply(bind(expr, name, literal(at)));
                Reading r = reading(v.value());
                out.add(new Sample(x, xText, v.shown(), r.y(), r.order()));
            } catch (CalculatorException | SyntaxException | ArithmeticException refused) {
                if (refusal == null) refusal = refused.getMessage();
                out.add(new Sample(x, xText, null, Double.NaN));
            }
        }
        return new Graph(name, orderless(out), refusal, expr, modes);
    }

    /**
     * Each {@code 0} with no order of its own given the order of the curve it lies on: the nearest sample before it on
     * the curve with one, or else the nearest after it, or else order one. So the zeros of {@code ω·(x + 1)} are on
     * the amber curve rather than breaking it.
     */
    private static List<Sample> orderless(List<Sample> samples) {
        List<Sample> out = new ArrayList<>(samples);
        for (int k = 0; k < out.size(); k++) {
            Sample s = out.get(k);
            if (s.order() != null) continue;
            Order order = neighbour(out, k, -1);
            if (order == null) order = neighbour(out, k, 1);
            out.set(k, new Sample(s.x(), s.xText(), s.value(), s.y(), order == null ? Order.ONE : order));
        }
        return out;
    }

    /** The order of the nearest sample on the curve with one, stepping from {@code k} by {@code step}. */
    private static Order neighbour(List<Sample> samples, int k, int step) {
        for (int i = k + step; i >= 0 && i < samples.size(); i += step) {
            Sample s = samples.get(i);
            if (!s.onCurve()) return null;
            if (s.order() != null) return s.order();
        }
        return null;
    }

    /** A decimal as the rational it is: {@code 0.35} is {@code 7/20}. */
    private static Expr literal(BigDecimal v) {
        BigInteger n = v.unscaledValue();
        BigInteger d = BigInteger.ONE;
        if (v.scale() > 0) d = BigInteger.TEN.pow(v.scale());
        else n = n.multiply(BigInteger.TEN.pow(-v.scale()));
        return literal(n, d);
    }

    /** {@code n/d} in lowest terms, written as the parser would have built it. */
    private static Expr literal(BigInteger n, BigInteger d) {
        BigInteger g = n.gcd(d);
        BigInteger num = n.abs().divide(g);
        BigInteger den = d.divide(g);
        Expr magnitude = den.equals(BigInteger.ONE)
                ? new Expr.Num(num)
                : new Expr.Div(new Expr.Num(num), new Expr.Num(den));
        return n.signum() < 0 ? new Expr.Neg(magnitude) : magnitude;
    }

    private record Reading(double y, Order order) {
    }

    /**
     * A value on the real line, with the power of zero it is a multiple of: its {@linkplain #series series} in
     * {@code 0}, read at its leading order, the one that outweighs the rest. {@code (0 + 3)^2 = 9 + 6·0 + 0^2} stands at
     * 9, {@code ω + 1} at {@code ω·1}, and {@code (−1/2)/0 = Q(−1, Z(2, 1))} at {@code ω·(−1/2)}. A leading order other
     * than 0, 1 or −1 has no colour yet, and is marked, as a value with no series is. A value whose every coefficient
     * is {@code 0} is {@code 0} at any order, so it has none of its own (a null order), and takes its neighbours'.
     */
    private static Reading reading(Object v) {
        TreeMap<Integer, Double> s = series(v);
        if (s == null) return new Reading(Double.NaN, Order.ONE);
        s.values().removeIf(c -> c == 0);
        if (s.isEmpty()) return new Reading(0, null);
        Map.Entry<Integer, Double> lead = s.firstEntry();
        return switch (lead.getKey()) {
            case 0 -> new Reading(lead.getValue(), Order.ONE);
            case 1 -> new Reading(lead.getValue(), Order.ZERO);
            case -1 -> new Reading(lead.getValue(), Order.OMEGA);
            default -> new Reading(Double.NaN, Order.ONE);
        };
    }

    /**
     * A value as a sum of real multiples of powers of {@code 0}, order to coefficient, or null where it is not one. A
     * number is of order 0; {@code Z(p, q)} is {@code p} moved up {@code q} orders; {@code S}, {@code D} and {@code P}
     * are the sum, difference and product; {@code C} is its real part where the imaginary part vanishes. A {@code Q}
     * divides by a denominator of one order, and by the number {@code 0} as by {@code Z(1, 1)}, so {@code Q(p, 0)} is
     * {@code ω·p}, but {@code Q(0, 0)} is none. Over a denominator of several orders it keeps only the leading term.
     */
    private static TreeMap<Integer, Double> series(Object v) {
        if (!(v instanceof Pair<?> pair)) {
            double x = real(v);
            return Double.isNaN(x) ? null : new TreeMap<>(Map.of(0, x));
        }
        TreeMap<Integer, Double> p = series(pair.p()), q = series(pair.q());
        if (p == null || q == null) return null;
        return switch (pair.algebra()) {
            case Z -> {
                double order = real(pair.q());
                if (order != Math.rint(order) || Math.abs(order) > 1 << 16) yield null;
                yield shifted(p, (int) order, 1);
            }
            case S -> plus(p, q, 1);
            case D -> plus(q, p, -1);
            case P -> times(p, q);
            case C -> zero(p) ? q : null;
            case Q -> {
                q.values().removeIf(c -> c == 0);
                if (q.isEmpty()) yield zero(p) ? null : shifted(p, -1, 1);
                p.values().removeIf(c -> c == 0);
                if (q.size() > 1 && !p.isEmpty()) p = new TreeMap<>(Map.of(p.firstKey(), p.firstEntry().getValue()));
                yield shifted(p, -q.firstKey(), q.firstEntry().getValue());
            }
            case E, B -> null;
        };
    }

    private static boolean zero(Map<Integer, Double> s) {
        return s.values().stream().allMatch(c -> c == 0);
    }

    private static TreeMap<Integer, Double> shifted(Map<Integer, Double> s, int by, double divisor) {
        TreeMap<Integer, Double> out = new TreeMap<>();
        s.forEach((order, c) -> out.put(order + by, c / divisor));
        return out;
    }

    private static TreeMap<Integer, Double> plus(Map<Integer, Double> a, Map<Integer, Double> b, int sign) {
        TreeMap<Integer, Double> out = new TreeMap<>(a);
        b.forEach((order, c) -> out.merge(order, sign * c, Double::sum));
        return out;
    }

    private static TreeMap<Integer, Double> times(Map<Integer, Double> a, Map<Integer, Double> b) {
        TreeMap<Integer, Double> out = new TreeMap<>();
        a.forEach((m, c) -> b.forEach((n, d) -> out.merge(m + n, c * d, Double::sum)));
        return out;
    }

    /**
     * The real number a value is, or NaN where it is not one. A number of the number type is itself; a pair is
     * read as its algebra means it, coordinates first: {@code C} is {@code q + ip}, so it is real where {@code p}
     * is zero; {@code D} is {@code q − p}, {@code S} is {@code p + q}, {@code Q} is {@code p / q} where {@code q}
     * is not zero, and {@code P} is {@code p · q}.
     */
    private static double real(Object v) {
        return switch (v) {
            case Double d -> Double.isFinite(d) ? d : Double.NaN;
            case BigInteger n -> n.doubleValue();
            case BigDecimal d -> d.doubleValue();
            case Pair<?> pair -> {
                double p = real(pair.p()), q = real(pair.q());
                yield switch (pair.algebra()) {
                    case C -> p == 0 ? q : Double.NaN;
                    case D -> q - p;
                    case S -> p + q;
                    case Q -> q == 0 ? Double.NaN : quotient(pair, p, q);
                    case P -> p * q;
                    case Z, E, B -> Double.NaN;
                };
            }
            default -> Double.NaN;
        };
    }

    /** {@code p / q}, divided once to a double's precision where both are Integers rather than rounded twice. */
    private static double quotient(Pair<?> pair, double p, double q) {
        if (pair.p() instanceof BigInteger n && pair.q() instanceof BigInteger d)
            return new BigDecimal(n).divide(new BigDecimal(d), MathContext.DECIMAL64).doubleValue();
        return p / q;
    }

    // ---- the expression ----

    /** The free names in an expression, in the order they first appear. */
    static Set<String> free(Expr e) {
        Set<String> out = new LinkedHashSet<>();
        collect(e, out);
        return out;
    }

    private static void collect(Expr e, Set<String> out) {
        switch (e) {
            case Expr.Var v -> out.add(v.name());
            case Expr.Neg n -> collect(n.operand(), out);
            case Expr.Add a -> { collect(a.left(), out); collect(a.right(), out); }
            case Expr.Sub s -> { collect(s.left(), out); collect(s.right(), out); }
            case Expr.Mul m -> { collect(m.left(), out); collect(m.right(), out); }
            case Expr.Div d -> { collect(d.left(), out); collect(d.right(), out); }
            case Expr.Pow p -> { collect(p.base(), out); collect(p.exponent(), out); }
            case Expr.Call c -> c.args().forEach(a -> collect(a, out));
            case Expr.Construct c -> { collect(c.p(), out); collect(c.q(), out); }
            case Expr.Named n -> { }
            case Expr.Num n -> { }
            case Expr.Decimal d -> { }
            case Expr.Omega o -> { }
        }
    }

    private static Expr bind(Expr e, String name, Expr value) {
        return switch (e) {
            case Expr.Var v -> v.name().equals(name) ? value : v;
            case Expr.Neg n -> new Expr.Neg(bind(n.operand(), name, value));
            case Expr.Add a -> new Expr.Add(bind(a.left(), name, value), bind(a.right(), name, value));
            case Expr.Sub s -> new Expr.Sub(bind(s.left(), name, value), bind(s.right(), name, value));
            case Expr.Mul m -> new Expr.Mul(bind(m.left(), name, value), bind(m.right(), name, value), m.implicit());
            case Expr.Div d -> new Expr.Div(bind(d.left(), name, value), bind(d.right(), name, value));
            case Expr.Pow p -> new Expr.Pow(bind(p.base(), name, value), bind(p.exponent(), name, value));
            case Expr.Call c -> new Expr.Call(c.name(), c.args().stream().map(a -> bind(a, name, value)).toList());
            case Expr.Construct c -> new Expr.Construct(c.algebra(), bind(c.p(), name, value), bind(c.q(), name, value));
            case Expr.Named n -> n;
            case Expr.Num n -> n;
            case Expr.Decimal d -> d;
            case Expr.Omega o -> o;
        };
    }
}
