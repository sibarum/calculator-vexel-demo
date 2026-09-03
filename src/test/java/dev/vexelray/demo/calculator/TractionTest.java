package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.Test;
import sibarum.cott.Bindings;
import sibarum.cott.Cott;
import sibarum.cott.Notation;
import sibarum.cott.Parser;
import sibarum.cott.Rational;
import sibarum.cott.Term;

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
 * <p>The chart being pinned throughout: {@code k·0^g} is at {@code (k, g)} — walk {@code k} along the real
 * axis, then climb {@code g} rungs in the zero direction. So {@code (0, 0)} is erasure, {@code (1, 0)} is one,
 * {@code (1, 1)} is zero, and {@code (1, -1)} is ω.
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

    /** Assert the whole walk: {@code typed} lands at {@code (real, traction)}. */
    private static void assertAt(long real, long traction, String typed) {
        Traction.Value value = assertInstanceOf(Traction.Value.class, read(typed), typed);
        Traction.Point at = value.point().orElseThrow(() -> new AssertionError(typed + " has no position"));
        assertEquals(new Traction.Point(r(real), r(traction)), at, typed);
    }

    // ---------------------------------------------------------------- the four generators

    /**
     * The four generators, each one step from erasure along one of the two axes. They are the four cardinal
     * directions, and a chart that mixed any two of them up would show here first.
     */
    @Test
    void theFourGeneratorsAreOneStepFromErasure() {
        assertAt(1, 0, "0^0");    // +1, one step along the real axis
        assertAt(-1, 0, "0^w");   // -1, one step the other way
        assertAt(1, 1, "0^1");    //  0, one rung up
        assertAt(1, -1, "0^-1");  //  ω, one rung down
    }

    /** ω is zero's reciprocal, so it is the same walk downward that zero is upward. */
    @Test
    void omegaIsTheRungBelow() {
        assertAt(1, -1, "1/0");
        assertAt(1, -2, "w^2");
        assertAt(1, -2, "w*w");
    }

    /** Multiplication climbs the traction axis: {@code 0*0} is a rung up, not two copies. */
    @Test
    void multiplicationClimbsRungs() {
        assertAt(1, 2, "0*0");
        assertAt(1, 3, "0*0*0");
    }

    /** Addition at one rung adds the multipliers and stays a single point — the walk gets longer, not taller. */
    @Test
    void additionAtOneRungLengthensTheRealLeg() {
        assertAt(2, 1, "0+0");
        assertAt(2, 1, "2*0");
        assertAt(3, 1, "0+0+0");
    }

    // ---------------------------------------------------------------- the real axis, and the origin

    /** An ordinary numeral is a walk along the real axis and no climb at all. */
    @Test
    void aNumeralIsAWalkAlongTheRealAxisAlone() {
        assertAt(2, 0, "2");
        assertAt(5, 0, "2+3");
        Traction.Value two = assertInstanceOf(Traction.Value.class, read("2"));
        assertTrue(two.point().orElseThrow().isReal());
    }

    /** Fractions are on the real axis too — the coordinate is a rational, not a rung count. */
    @Test
    void theRealCoordinateIsARational() {
        Traction.Value third = assertInstanceOf(Traction.Value.class, read("1/3"));
        assertEquals(new Traction.Point(Rational.of(1, 3), Rational.ZERO), third.point().orElseThrow());
    }

    /** The origin is erasure, and it is the walk that goes nowhere. The number zero is a rung above it. */
    @Test
    void theOriginIsErasureAndNotZero() {
        assertEquals(new Traction.Point(Rational.ZERO, Rational.ZERO), Traction.Point.ERASURE);
        assertTrue(Traction.Point.ERASURE.isReal());
        assertAt(1, 1, "0^1");
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
        assertAt(-3, 0, "-6/2");
    }

    /** A twist that is not a sign is a diagonal — off both axes, so the walk cannot reach it. */
    @Test
    void aDiagonalIsRefusedAPosition() {
        Traction.Value half = assertInstanceOf(Traction.Value.class, read("0^((w+1)/2)"));
        Traction.Component only = half.components().get(0);
        assertEquals(Rational.of(1, 2), only.twist());
        assertEquals(Rational.of(1, 2), only.grade());
        assertFalse(only.isPlain());
        assertTrue(only.place().isEmpty(), "a diagonal is not a walk along the two axes");
        assertTrue(half.point().isEmpty());
    }

    // ---------------------------------------------------------------- sums of unlike rungs

    /**
     * A sum across rungs has nothing to add, so COTT leaves it standing and it is not one point. Both
     * components are still read in full — what is refused is a position, not the value.
     */
    @Test
    void aSumAcrossRungsIsReadButIsNotOnePoint() {
        Traction.Value mixed = assertInstanceOf(Traction.Value.class, read("3+0^2"));
        assertEquals(2, mixed.components().size());
        assertTrue(mixed.point().isEmpty(), "3+0^2 spans two rungs");

        assertEquals(new Traction.Point(r(3), r(0)), mixed.components().get(0).place().orElseThrow());
        assertEquals(new Traction.Point(r(1), r(2)), mixed.components().get(1).place().orElseThrow());
    }

    /** And with a multiplier on the far rung, which changes only that component's real leg. */
    @Test
    void eachComponentOfASumPlacesOnItsOwn() {
        Traction.Value mixed = assertInstanceOf(Traction.Value.class, read("3+2*0^2"));
        assertEquals(new Traction.Point(r(2), r(2)), mixed.components().get(1).place().orElseThrow());
        assertTrue(mixed.point().isEmpty());
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

    /** Away from the poles the sweep really does produce placeable points on the real axis. */
    @Test
    void anOrdinarySampleIsAPointOnTheRealAxis() {
        Bindings f = NONE.define("f(x) = 1/(x^2-1)");
        Traction.Value value = assertInstanceOf(Traction.Value.class, readIn(f, "f(2)"));
        Traction.Point at = value.point().orElseThrow();
        assertEquals(Rational.of(1, 3), at.real());
        assertTrue(at.isReal());
    }

    /** And a sample that lands on ω climbs off the real axis, which is the whole reason for the second one. */
    @Test
    void aSampleThatReachesOmegaLeavesTheRealAxis() {
        Bindings g = NONE.define("g(x) = 1/x");
        Traction.Value value = assertInstanceOf(Traction.Value.class, readIn(g, "g(0)"));
        Traction.Point at = value.point().orElseThrow();
        assertEquals(new Traction.Point(r(1), r(-1)), at, "1/0 is ω, a rung below the real axis");
        assertFalse(at.isReal());
    }
}
