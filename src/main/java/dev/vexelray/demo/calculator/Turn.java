package dev.vexelray.demo.calculator;

import java.util.Arrays;
import java.util.OptionalDouble;

/**
 * A direction held as an integer tangent, and the Cayley–Dickson tower built on it.
 *
 * <p>A turn is a pair {@code (p, q)} read as {@code p/q = tan θ}. The quotient is never formed, because forming
 * it would reduce, and {@code (2,2)} reducing to {@code (1,1)} is exactly the normalisation this representation
 * exists to avoid. {@code (2,2)} and {@code (1,1)} are the same <em>direction</em> and different <em>turns</em>,
 * and the difference is kept.
 *
 * <h2>What is and is not normalised</h2>
 *
 * Exactly one rule fires: <b>{@code 0/0 → 1/1}</b>, an indeterminate tangent becoming the diagonal, which is
 * the erasure identity ({@code x + z/z = x + 1}) read as a direction. It holds as an <em>invariant</em> — every
 * turn satisfies it however it was produced, including one that arithmetic drove to {@code (0,0)}.
 *
 * <p>Nothing else is touched. Signs are left alone, magnitude is never forced to one, and no common divisor is
 * ever taken — a GCD is a division per operation, and the whole point of an integer tangent is that composing
 * directions costs four multiplies and two adds.
 *
 * <h2>Overflow</h2>
 *
 * Components are {@code long}, so they can overflow, and the answer is to shed low bits rather than to widen.
 * Every operation is attempted exactly; on overflow both operands have <em>every</em> component halved and the
 * operation is retried, until it fits. Halving {@code p} and {@code q} together preserves the direction and
 * costs magnitude, which is the right trade for a chart: the tangent is what is being read, and the low bits of
 * a number near {@code 2^63} are not information anyone is plotting. The shift is toward zero so a sign never
 * flips.
 *
 * <h2>Why the pair is the construction</h2>
 *
 * Angle addition on tangents is {@code tan(θ₁+θ₂) = (t₁+t₂)/(1−t₁t₂)}, which on pairs is
 * {@code (p₁q₂ + p₂q₁, q₁q₂ − p₁p₂)} — and that is complex multiplication. So the pair <em>is</em>
 * Cayley–Dickson level one, {@link #multiply} is composition of turns, and no trigonometry appears anywhere.
 * {@link #toCartesian} is a relabelling rather than a conversion: no {@code sqrt}, no {@code atan2}.
 *
 * <p>Level 0 is a scalar, level 1 the pair, level 2 quaternion-shaped, level 3 octonion-shaped. Above level 1
 * this uses the {@code (ac − d b*, a* d + c b)} doubling, which gives {@code i·j = −k} — a valid quaternion
 * algebra, the mirror of Hamilton's. Level 1 is unaffected either way, complex multiplication being
 * commutative, and nothing here consumes a level above 1 yet.
 */
public final class Turn {

    /** Components in Cayley–Dickson order, real part first. Length is a power of two. */
    private final long[] c;

    /** Applies the one rule, so no other constructor or operation has to remember to. */
    private Turn(long[] components) {
        if (components.length == 2 && components[0] == 0 && components[1] == 0) {
            components[0] = 1;
            components[1] = 1;
        }
        this.c = components;
    }

    // ---------------------------------------------------------------- building

    /**
     * The turn whose tangent is {@code p/q}, in the order it is spoken.
     *
     * <p>{@code (0, 0)} becomes {@code (1, 1)}, here and everywhere else.
     */
    public static Turn tan(long p, long q) {
        return new Turn(new long[] {q, p});
    }

    /** A level-0 turn: one component, no direction, just a magnitude. The base of the tower. */
    public static Turn scalar(long value) {
        return new Turn(new long[] {value});
    }

    /**
     * A turn from raw Cayley–Dickson components, real part first.
     *
     * @throws IllegalArgumentException unless the count is a power of two
     */
    public static Turn of(long... components) {
        int n = components.length;
        if (n == 0 || (n & (n - 1)) != 0) {
            throw new IllegalArgumentException("component count must be a power of two, got " + n);
        }
        return new Turn(components.clone());
    }

    /**
     * This turn doubled: {@code (this, 0)}, one level up the tower.
     *
     * <p>The embedding is faithful — a doubled turn adds and multiplies exactly as it did a level down.
     */
    public Turn doubled() {
        long[] out = new long[c.length * 2];
        System.arraycopy(c, 0, out, 0, c.length);
        return new Turn(out);
    }

    // ---------------------------------------------------------------- reading

    /** How many doublings up from a scalar: 0 a scalar, 1 the {@code (p, q)} pair, 2 quaternion-shaped. */
    public int level() {
        return Integer.numberOfTrailingZeros(c.length);
    }

    public int size() {
        return c.length;
    }

    public long component(int index) {
        return c[index];
    }

    /** The tangent's numerator — component 1. */
    public long p() {
        requireDirection("p");
        return c[1];
    }

    /** The tangent's denominator — component 0, the real part. */
    public long q() {
        requireDirection("q");
        return c[0];
    }

    /**
     * {@code p/q} as a number, when there is one.
     *
     * <p>Empty when {@code q} is zero — a vertical direction, whose tangent is ω rather than a ratio. That is a
     * real direction and not an error, so it is reported rather than thrown.
     *
     * <p>A reading and never a representation. Dividing reduces, so {@code (2,2)} and {@code (1,1)} both answer
     * {@code 1} here while staying different turns. Use {@link #sameDirection} to compare directions without
     * leaving the integers.
     */
    public OptionalDouble tangent() {
        requireDirection("tangent");
        return c[0] == 0 ? OptionalDouble.empty() : OptionalDouble.of((double) c[1] / (double) c[0]);
    }

    /**
     * Whether two turns point the same way, decided exactly.
     *
     * <p>{@code p₁q₂ == p₂q₁}, so no division and no rounding — the comparison {@link #tangent} cannot make
     * honestly. Turns of different magnitude along one ray are the same direction.
     */
    public boolean sameDirection(Turn other) {
        requireDirection("sameDirection");
        other.requireDirection("sameDirection");
        // Compared at 128 bits, so a cross product that does not fit in a long is still compared exactly:
        // the low half is the ordinary product, the high half is what it carried.
        return c[1] * other.c[0] == other.c[1] * c[0]
                && Math.multiplyHigh(c[1], other.c[0]) == Math.multiplyHigh(other.c[1], c[0]);
    }

    /**
     * The Cartesian components, {@code x} first.
     *
     * <p>A relabelling, not a conversion: the stored components already are the axes, {@code q} along {@code x}
     * and {@code p} along {@code y}, so a direction of tangent {@code p/q} needs no trigonometry to place. The
     * magnitude is whatever the components carry — nothing here forces it to one.
     */
    public long[] toCartesian() {
        return c.clone();
    }

    // ---------------------------------------------------------------- arithmetic

    /** Componentwise, retried on narrower operands if it overflows. */
    public Turn add(Turn other) {
        requireSameLevel(other);
        long[] x = c;
        long[] y = other.c;
        while (true) {
            try {
                long[] out = new long[x.length];
                for (int i = 0; i < x.length; i++) {
                    out[i] = Math.addExact(x[i], y[i]);
                }
                return new Turn(out);
            } catch (ArithmeticException overflow) {
                if (!canHalve(x) && !canHalve(y)) {
                    throw overflow;
                }
                x = halved(x);
                y = halved(y);
            }
        }
    }

    public Turn subtract(Turn other) {
        return add(other.negate());
    }

    /**
     * Every component negated.
     *
     * <p>Retried narrower in the one case that can overflow: {@code Long.MIN_VALUE} has no positive
     * counterpart, so a component sitting on it sheds a bit rather than wrapping to itself.
     */
    public Turn negate() {
        long[] x = c;
        while (true) {
            try {
                long[] out = new long[x.length];
                for (int i = 0; i < x.length; i++) {
                    out[i] = Math.negateExact(x[i]);
                }
                return new Turn(out);
            } catch (ArithmeticException overflow) {
                if (!canHalve(x)) {
                    throw overflow;
                }
                x = halved(x);
            }
        }
    }

    /**
     * The Cayley–Dickson product, retried on narrower operands if it overflows.
     *
     * <p>At level 1 this is complex multiplication, which is angle addition on tangents — composing two turns.
     */
    public Turn multiply(Turn other) {
        requireSameLevel(other);
        long[] x = c;
        long[] y = other.c;
        while (true) {
            try {
                long[] out = new long[x.length];
                product(x, 0, y, 0, x.length, out, 0);
                return new Turn(out);
            } catch (ArithmeticException overflow) {
                if (!canHalve(x) && !canHalve(y)) {
                    throw overflow;
                }
                x = halved(x);
                y = halved(y);
            }
        }
    }

    /** Negate every component but the real one. */
    public Turn conjugate() {
        return new Turn(conjugated(c, 0, c.length));
    }

    // ---------------------------------------------------------------- overflow policy

    /**
     * Every component halved toward zero.
     *
     * <p>Halving {@code p} and {@code q} together is the point: the direction survives, the magnitude does not.
     *
     * <p>Written as a division because it must truncate toward zero, and the two shortcuts that look cheaper
     * both break. A bare {@code >>} rounds toward negative infinity, which pins {@code -1} at {@code -1} so the
     * retry loop cannot make progress. Negating first and shifting back is worse: {@code -Long.MIN_VALUE}
     * overflows to {@code Long.MIN_VALUE}, and the shift then lands on a <em>positive</em> number — a silent
     * sign flip in the one component that cannot be negated. {@code / 2} is exact on every {@code long},
     * {@code Long.MIN_VALUE} included, and the compiler emits the shift anyway.
     */
    private static long[] halved(long[] v) {
        long[] out = new long[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = v[i] / 2;
        }
        return out;
    }

    /** Whether halving would actually shed anything, so the retry loop cannot spin. */
    private static boolean canHalve(long[] v) {
        for (long x : v) {
            if (x > 1 || x < -1) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the recursion

    private static void product(long[] x, int xo, long[] y, int yo, int n, long[] out, int oo) {
        if (n == 1) {
            out[oo] = Math.multiplyExact(x[xo], y[yo]);
            return;
        }
        int h = n / 2;
        long[] ac = new long[h];
        long[] db = new long[h];
        long[] ad = new long[h];
        long[] cb = new long[h];

        long[] aStar = conjugated(x, xo, h);
        long[] bStar = conjugated(x, xo + h, h);

        product(x, xo, y, yo, h, ac, 0);            // a c
        product(y, yo + h, bStar, 0, h, db, 0);     // d b*
        product(aStar, 0, y, yo + h, h, ad, 0);     // a* d
        product(y, yo, x, xo + h, h, cb, 0);        // c b

        for (int i = 0; i < h; i++) {
            out[oo + i] = Math.subtractExact(ac[i], db[i]);
            out[oo + h + i] = Math.addExact(ad[i], cb[i]);
        }
    }

    private static long[] conjugated(long[] x, int o, int n) {
        long[] r = new long[n];
        r[0] = x[o];
        for (int i = 1; i < n; i++) {
            r[i] = -x[o + i];
        }
        return r;
    }

    private void requireSameLevel(Turn other) {
        if (other.c.length != c.length) {
            throw new IllegalArgumentException(
                    "level " + level() + " and level " + other.level() + " do not compose; double one first");
        }
    }

    private void requireDirection(String what) {
        if (c.length < 2) {
            throw new IllegalStateException(
                    what + " needs a direction, and a level-0 turn is a magnitude alone; double it first");
        }
    }

    // ---------------------------------------------------------------- identity

    @Override
    public boolean equals(Object o) {
        return o instanceof Turn t && Arrays.equals(c, t.c);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(c);
    }

    /** {@code p/q} shape at level 1, component list above it. */
    @Override
    public String toString() {
        return c.length == 2 ? c[1] + "/" + c[0] : Arrays.toString(c);
    }
}
