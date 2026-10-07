package dev.vexelray.demo.calculator;

import sibarum.cott.calculator.Arithmetic;
import sibarum.cott.calculator.CalculatorException;
import sibarum.cott.calculator.Limits;
import sibarum.cott.calculator.Result;
import sibarum.cott.notation.Expr;
import sibarum.cott.traction.CC;
import sibarum.cott.traction.T;
import sibarum.cott.traction.T2;
import sibarum.cott.traction.TC;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An expression in one free name, evaluated across a fixed range of it. Pure: no GUI, no window.
 *
 * <p>Every sample is an exact rational, {@code k/20} from {@link #FROM} to {@link #TO}, so the engine sees the
 * same kind of number a person would type, and {@code 0} is always one of them — which is where {@code 1/x}
 * shows what this calculator is about.
 *
 * <p>Each sample is read on the real line. Where the value has a real number — a ratio whose denominator is not
 * zero, a finite double, a point on the real axis — that is its height. Where it has none, the sample is a
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

    /** Sample {@code expr} over {@code name}, which must be its only free name, within these recursion limits. */
    static Graph of(Expr expr, String name, Arithmetic arithmetic, Limits limits) {
        List<Sample> out = new ArrayList<>();
        String refusal = null;
        BigInteger per = BigInteger.valueOf(PER_UNIT);
        for (int k = FROM * PER_UNIT; k <= TO * PER_UNIT; k++) {
            BigInteger n = BigInteger.valueOf(k);
            double x = (double) k / PER_UNIT;
            String xText = new BigDecimal(n).divide(new BigDecimal(per)).stripTrailingZeros().toPlainString();
            try {
                Result.Value v = arithmetic.evaluate(bind(expr, name, literal(n, per)), limits);
                out.add(new Sample(x, xText, v.text(), real(v)));
            } catch (CalculatorException | ArithmeticException refused) {
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

    /** The real number a value is, or NaN where it is not one. */
    private static double real(Result.Value v) {
        return switch (v) {
            case Result.RatioValue r -> ratio(r.flat());
            case Result.IeeeValue d -> Double.isFinite(d.value()) ? d.value() : Double.NaN;
            case Result.PointValue p -> pointOnTheLine(p.point());
            case Result.ComplexRatioValue c -> pointOnTheLine(c.ratio().rationalize());
            case Result.BicomplexValue b -> bicomplexOnTheLine(b.point());
            case Result.BicomplexRatioValue b ->
                    zero(b.point().p()) ? pointOnTheLine(b.point().q().rationalize()) : Double.NaN;
        };
    }

    private static double ratio(T t) {
        if (t.q().signum() == 0) return Double.NaN;
        return new BigDecimal(t.p()).divide(new BigDecimal(t.q()), MathContext.DECIMAL64).doubleValue();
    }

    /** {@code B + A·i} is on the real line when both coordinates are ratios and {@code A} is zero. */
    private static double pointOnTheLine(T2 point) {
        T im = point.p();
        if (im.q().signum() == 0 || point.q().q().signum() == 0 || im.p().signum() != 0) return Double.NaN;
        return ratio(point.q());
    }

    /**
     * {@code B + A·j} with Gaussian-integer coordinates is on the real line when {@code A} is zero and {@code B}
     * is real. A Gaussian integer {@code T(p, q)} is {@code q + p·i}.
     */
    private static double bicomplexOnTheLine(CC point) {
        T a = point.p(), b = point.q();
        if (a.p().signum() != 0 || a.q().signum() != 0 || b.p().signum() != 0) return Double.NaN;
        return b.q().doubleValue();
    }

    /** A ratio of Gaussian integers that is the number zero: it rationalizes to the origin over a nonzero norm. */
    private static boolean zero(TC ratio) {
        T2 point = ratio.rationalize();
        return point.p().q().signum() != 0 && point.p().p().signum() == 0 && point.q().p().signum() == 0;
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
            case Expr.Num n -> n;
            case Expr.Decimal d -> d;
            case Expr.Omega o -> o;
        };
    }
}
