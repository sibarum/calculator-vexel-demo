package dev.vexelray.demo.calculator;

import sibarum.cott.algebra.Pair;
import sibarum.cott.calculator.CalculatorException;
import sibarum.cott.calculator.Result;
import sibarum.cott.notation.Expr;
import sibarum.cott.notation.SyntaxException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * An expression in one free name, evaluated across a fixed range of it. Pure: no GUI, no window.
 *
 * <p>Every sample is an exact rational, {@code k/20} from {@link #FROM} to {@link #TO}, so the engine sees the
 * same kind of number a person would type, and {@code 0} is always one of them — which is where {@code 1/x}
 * shows what this calculator is about.
 *
 * <p>Each sample is read on the real line. Where the value has a real number — a finite double, or a pair whose
 * algebra's meaning comes to one with no zero denominator and no imaginary part — that is its height. Where it has none, the sample is a
 * {@linkplain Sample#marker() marker} carrying the engine's own text for the value ({@code ω}, {@code 0ω},
 * {@code ∞}, a point off the line), to be drawn as a label rather than as a gap or a spike.
 *
 * @param name    the free name the curve is over
 * @param samples in increasing x; a sample the engine refused has a null {@code value}
 * @param refusal the engine's reason for the first sample it refused, or null if it refused none
 */
record Graph(String name, List<Sample> samples, String refusal) {

    static final int FROM = -10;
    static final int TO = 10;
    static final int PER_UNIT = 20;

    /**
     * One evaluation.
     *
     * @param x      the sample's position, as a number
     * @param xText  the same position written as a decimal, exactly
     * @param value  the engine's text for the answer, or null when it refused this one
     * @param y      the real reading, or NaN when there is none
     */
    record Sample(double x, String xText, String value, double y) {

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
    }

    /** Whether the engine refused every sample, so there is nothing to draw and {@link #refusal} says why. */
    boolean empty() {
        return samples.stream().allMatch(Sample::refused);
    }

    /**
     * Sample {@code expr} over {@code name}, which must be its only free name. {@code evaluate} answers a closed
     * expression in the calculator's current modes, or throws the engine's refusal.
     */
    static Graph of(Expr expr, String name, Function<Expr, Result.Value> evaluate) {
        List<Sample> out = new ArrayList<>();
        String refusal = null;
        BigInteger per = BigInteger.valueOf(PER_UNIT);
        for (int k = FROM * PER_UNIT; k <= TO * PER_UNIT; k++) {
            BigInteger n = BigInteger.valueOf(k);
            double x = (double) k / PER_UNIT;
            String xText = new BigDecimal(n).divide(new BigDecimal(per)).stripTrailingZeros().toPlainString();
            try {
                Result.Value v = evaluate.apply(bind(expr, name, literal(n, per)));
                out.add(new Sample(x, xText, v.text(), real(v.value())));
            } catch (CalculatorException | SyntaxException | ArithmeticException refused) {
                if (refusal == null) refusal = refused.getMessage();
                out.add(new Sample(x, xText, null, Double.NaN));
            }
        }
        return new Graph(name, out, refusal);
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
