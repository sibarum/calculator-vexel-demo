package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.Test;
import sibarum.cott.Bindings;
import sibarum.cott.Cott;
import sibarum.cott.Notation;
import sibarum.cott.Parser;
import sibarum.cott.Rational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a term sits in traction space.
 *
 * <p>Every expression here is written the way it would be typed into the calculator and run through the same
 * three steps the {@code =} key runs, so what is pinned is the reading of what COTT <em>actually</em> produces
 * rather than of a term hand-built to be convenient.
 *
 * <p>The chart being pinned throughout: a value is {@code Re + 0^Tr}, at {@code (Re, Tr)}. <b>Ones add</b>
 * along the real axis, <b>zeros multiply</b> up the traction axis. So {@code (1, 0)} is one, {@code (0, 1)} is
 * zero, {@code (0, -1)} is ω, {@code (0, 0)} is erasure — and {@code (1, 1)} is {@code 1 + 0^1}, which is the
 * whole reason zero itself must have no real part.
 */
class TractionTest {

    private static final Bindings NONE = Bindings.EMPTY;

    /** Type an expression, evaluate it, read it — the whole path, as the keypad walks it. */
    private static Traction read(String typed) {
        return readIn(NONE, typed);
    }

    private static Traction readIn(Bindings names, String typed) {
        return Traction.read(Cott.reduce(names.expand(Parser.parse(Notation.normalize(typed, names), names))));
    }

    private static Rational r(long n) {
        return Rational.of(n);
    }

    /** Assert {@code typed} lands at {@code (Re, Tr)}. */
    private static void assertAt(long re, long tr, String typed) {
        Traction.Value value = assertInstanceOf(Traction.Value.class, read(typed), typed);
        Traction.Point at = value.point().orElseThrow(() -> new AssertionError(typed + " has no position"));
        assertEquals(new Traction.Point(r(re), r(tr)), at, typed);
    }

    /** Assert {@code typed} is read in full but given no position. */
    private static Traction.Value assertUnplaced(String typed) {
        Traction.Value value = assertInstanceOf(Traction.Value.class, read(typed), typed);
        assertTrue(value.point().isEmpty(), typed + " should have no position");
        return value;
    }

    // ---------------------------------------------------------------- the four generators

    /**
     * The four generators, each one step from erasure. The two real ones sit on the real axis with no traction
     * part; the two traction ones sit on the traction axis with no real part. A chart that mixed any two of
     * them up would show here first.
     */
    @Test
    void theFourGeneratorsAreOneStepFromErasure() {
        assertAt(1, 0, "0^0");    // +1, one step out along the real axis
        assertAt(-1, 0, "0^w");   // -1, one step the other way
        assertAt(0, 1, "0^1");    //  0, one rung up and no real part
        assertAt(0, -1, "0^-1");  //  ω, one rung down
    }

    /** ω is zero's reciprocal, so it is the same climb downward that zero is upward. */
    @Test
    void omegaIsTheRungBelow() {
        assertAt(0, -1, "1/0");
        assertAt(0, -2, "w^2");
        assertAt(0, -2, "w*w");
    }

    /** Zeros multiply: {@code 0*0} is a rung up, not two copies. */
    @Test
    void zerosMultiplyUpTheTractionAxis() {
        assertAt(0, 2, "0*0");
        assertAt(0, 3, "0*0*0");
    }

    /** Ones add: along the real axis, addition is just distance. */
    @Test
    void onesAddAlongTheRealAxis() {
        assertAt(2, 0, "2");
        assertAt(5, 0, "2+3");
        assertAt(-3, 0, "-6/2");
        assertTrue(((Traction.Value) read("2")).point().orElseThrow().isReal());
    }

    /** The real coordinate is a rational, not a rung count. */
    @Test
    void theRealCoordinateIsARational() {
        assertEquals(new Traction.Point(Rational.of(1, 3), Rational.ZERO),
                ((Traction.Value) read("1/3")).point().orElseThrow());
    }

    // ---------------------------------------------------------------- the canonical form

    /**
     * The form the chart holds, and the case that forces zero off the real axis. If {@code 0^1} had a real
     * part of one, it would sit exactly where {@code 1 + 0^1} sits.
     */
    @Test
    void aRealPartAndATractionPartTogether() {
        assertAt(1, 1, "1+0^1");
        assertAt(2, 1, "2+0^1");
        assertAt(3, 2, "3+0^2");
        assertAt(1, -1, "1+w");
    }

    /** And the two really are different points, which is the whole content of the ruling. */
    @Test
    void zeroAndOnePlusZeroAreDifferentPoints() {
        Traction.Point zero = ((Traction.Value) read("0^1")).point().orElseThrow();
        Traction.Point onePlusZero = ((Traction.Value) read("1+0^1")).point().orElseThrow();
        assertEquals(new Traction.Point(r(0), r(1)), zero);
        assertEquals(new Traction.Point(r(1), r(1)), onePlusZero);
        assertTrue(zero.isPureTraction());
        assertFalse(onePlusZero.isPureTraction());
    }

    /** The origin is erasure, and no value lands there — having neither part is what erasure is. */
    @Test
    void theOriginIsErasureAndNoValueReachesIt() {
        assertEquals(new Traction.Point(Rational.ZERO, Rational.ZERO), Traction.Point.ERASURE);
        assertInstanceOf(Traction.Partial.class, read("1-1"));
        assertInstanceOf(Traction.Partial.class, read("0w"));
    }

    // ---------------------------------------------------------------- the three spellings of negative

    /**
     * The reading that would otherwise put one point in two places.
     *
     * <p>A typed {@code -1} is a {@code Neg} wrapper; {@code 0^ω} carries the sign in the exponent's twist;
     * dividing produces a negative multiplier outright. One value, so one point.
     */
    @Test
    void allThreeSpellingsOfNegativeLandOnOnePoint() {
        assertEquals(read("-1"), read("0^w"), "Neg and a twist of one are the same point");
        assertAt(-1, 0, "-1");
        assertAt(-1, 0, "0^w");
    }

    /** A twist that is not a sign is a diagonal — off both axes, so the chart has no room for it. */
    @Test
    void aDiagonalIsRefusedAPosition() {
        Traction.Value half = assertUnplaced("0^((w+1)/2)");
        Traction.Component only = half.components().get(0);
        assertEquals(Rational.of(1, 2), only.twist());
        assertEquals(Rational.of(1, 2), only.grade());
        assertFalse(only.isPlain());
        assertTrue(only.place().isEmpty());
    }

    // ---------------------------------------------------------------- what the chart still refuses

    /**
     * A multiplier on a traction rung. {@code 0+0} reduces to {@code 2·0}, which is not {@code Re + 0^Tr} for
     * any {@code Re} and {@code Tr} — and it must not be folded onto the real axis, because that is exactly
     * what sends it to the same place as {@code 1+0}. Still open, so still refused.
     */
    @Test
    void aTractionMultiplierIsReadButHasNoPositionYet() {
        Traction.Value doubled = assertUnplaced("0+0");
        Traction.Component only = doubled.components().get(0);
        assertEquals(r(2), only.mult());
        assertEquals(r(1), only.grade());
        assertTrue(only.isPlain(), "nothing wrong with the value — only with placing it");

        assertUnplaced("2*0");
        assertUnplaced("3+2*0^2");
    }

    /** More than one rung: there is no single {@code Tr} to report. */
    @Test
    void aSumAcrossTwoRungsHasNoSingleTraction() {
        assertEquals(2, assertUnplaced("0^2+0^3").components().size());
        assertEquals(3, assertUnplaced("1+0^1+0^2").components().size());
    }

    // ---------------------------------------------------------------- erasure and stuck terms

    /** Erasure is partial evaluation, not a value — and it keeps the operand it discharged. */
    @Test
    void erasureReadsAsPartialRatherThanAsAValue() {
        assertInstanceOf(Traction.Partial.class, read("1-1"), "additive erasure");
        assertInstanceOf(Traction.Partial.class, read("0w"), "multiplicative erasure");
        assertInstanceOf(Traction.Partial.class, read("2+(2-2)"), "erasure anywhere in a sum");
    }

    /** The corrected identities: erasure interacts with the operation it belongs to, and only that one. */
    @Test
    void theResidueIdentitiesSurviveTheReading() {
        assertAt(3, 0, "2+0w");
        assertAt(2, 0, "2*0w");
    }

    /**
     * The case a plot meets constantly. {@code 1/(x²−1)} at {@code x = 0} is {@code 1/(0^2−1)}, and a sum of
     * unlike exponents is deliberately left standing, so there is no value to place.
     */
    @Test
    void aStuckTermIsReportedRatherThanGuessedAt() {
        Traction.Stuck stuck = assertInstanceOf(Traction.Stuck.class, read("1/(0^2-1)"));
        assertFalse(stuck.reason().isBlank());
    }

    /** A free variable has no point reading either, and says which one it was. */
    @Test
    void anUnboundVariableIsStuckAndNamesItself() {
        Traction.Stuck stuck = assertInstanceOf(Traction.Stuck.class, read("x"));
        assertTrue(stuck.reason().contains("x"), stuck.reason());
    }

    /** A product that never expanded is stuck too, which is where symbolic coefficients currently land. */
    @Test
    void anUnexpandedProductIsStuck() {
        assertInstanceOf(Traction.Stuck.class, read("x*0^1"));
        assertInstanceOf(Traction.Stuck.class, read("(1+0)*(1+0)"));
    }

    // ---------------------------------------------------------------- sampling, which is what a plot does

    /** Every reading is total, so a sweep along the real line never throws — it only ever refuses. */
    @Test
    void samplingAcrossAPoleNeverThrows() {
        Bindings f = NONE.define("f(x) = 1/(x^2-1)");
        for (int n = -30; n <= 30; n++) {
            String at = "f(" + n + "/10)";
            Traction reading = readIn(f, at);
            assertTrue(reading instanceof Traction.Value
                            || reading instanceof Traction.Stuck
                            || reading instanceof Traction.Partial,
                    at + " read as " + reading);
        }
    }

    /** Away from the poles the sweep produces placeable points on the real axis. */
    @Test
    void anOrdinarySampleIsAPointOnTheRealAxis() {
        Bindings f = NONE.define("f(x) = 1/(x^2-1)");
        Traction.Value value = assertInstanceOf(Traction.Value.class, readIn(f, "f(2)"));
        Traction.Point at = value.point().orElseThrow();
        assertEquals(Rational.of(1, 3), at.real());
        assertTrue(at.isReal());
    }

    /** And a sample that reaches ω leaves the real axis, which is the whole reason for the second one. */
    @Test
    void aSampleThatReachesOmegaLeavesTheRealAxis() {
        Bindings g = NONE.define("g(x) = 1/x");
        Traction.Value value = assertInstanceOf(Traction.Value.class, readIn(g, "g(0)"));
        assertEquals(new Traction.Point(r(0), r(-1)), value.point().orElseThrow(), "1/0 is ω, one rung down");
        assertFalse(value.point().orElseThrow().isReal());
    }
}
