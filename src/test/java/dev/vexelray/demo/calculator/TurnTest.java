package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tangent pair, its one rule, and what it does when it runs out of bits.
 *
 * <p>Three claims run through all of this. Composing turns is <b>exact</b> while it fits — every expectation
 * below is an integer written longhand, never a tolerance. <b>Nothing normalises</b> except
 * {@code 0/0 → 1/1}, so magnitude and sign survive. And on overflow the <b>direction survives and the
 * magnitude does not</b>, which is the trade being deliberately made.
 */
class TurnTest {

    // ---------------------------------------------------------------- the one rule

    /** An indeterminate tangent becomes the diagonal. */
    @Test
    void zeroOverZeroBecomesOneOverOne() {
        Turn resolved = Turn.tan(0, 0);
        assertEquals(1, resolved.p());
        assertEquals(1, resolved.q());
        assertEquals(OptionalDouble.of(1), resolved.tangent());
    }

    /**
     * And it holds however the turn was produced, not just through {@link Turn#tan}. Arithmetic that lands on
     * {@code (0,0)} lands on {@code (1,1)} — the rule is an invariant, not a constructor's courtesy.
     */
    @Test
    void theRuleIsAnInvariantAndNotJustAConstructorsCourtesy() {
        assertEquals(Turn.tan(1, 1), Turn.of(0, 0), "raw components");
        assertEquals(Turn.tan(1, 1), Turn.tan(3, 4).subtract(Turn.tan(3, 4)), "driven there by subtraction");
        assertEquals(Turn.tan(1, 1), Turn.tan(0, 0).negate().negate());
    }

    /**
     * Nothing else is normalised. {@code 2/2} keeps its magnitude rather than reducing — same direction,
     * different turn, and no common divisor is ever taken.
     */
    @Test
    void noGcdIsEverTaken() {
        Turn two = Turn.tan(2, 2);
        assertEquals(2, two.p());
        assertEquals(2, two.q());
        assertNotEquals(Turn.tan(1, 1), two, "same direction, different turn");
        assertTrue(two.sameDirection(Turn.tan(1, 1)), "but it knows they point the same way");
        assertEquals(Turn.tan(6, 8), Turn.tan(6, 8), "6/8 is not 3/4");
        assertNotEquals(Turn.tan(3, 4), Turn.tan(6, 8));
        assertTrue(Turn.tan(3, 4).sameDirection(Turn.tan(6, 8)));
    }

    /** Signs are left exactly as given — nothing is moved into a canonical half. */
    @Test
    void signsAreLeftAlone() {
        Turn negBoth = Turn.tan(-3, -4);
        assertEquals(-3, negBoth.p());
        assertEquals(-4, negBoth.q());
        assertNotEquals(Turn.tan(3, 4), negBoth, "opposite ray, not the same turn");
        assertTrue(negBoth.sameDirection(Turn.tan(3, 4)), "collinear, though");
        assertFalse(Turn.tan(3, -4).sameDirection(Turn.tan(3, 4)));
    }

    /** A vertical direction has tangent ω, reported as absent rather than thrown. */
    @Test
    void aVerticalDirectionHasNoRatio() {
        assertEquals(OptionalDouble.empty(), Turn.tan(1, 0).tangent());
        assertEquals(1, Turn.tan(1, 0).p());
        assertEquals(0, Turn.tan(1, 0).q());
    }

    // ---------------------------------------------------------------- angle addition

    /**
     * The point of the representation: multiplying turns adds their angles, exactly.
     *
     * <p>{@code arctan ½ + arctan ⅓ = 45°}. The pair arithmetic gets there in integers with nothing to round.
     */
    @Test
    void multiplyingAddsTheAngles() {
        Turn sum = Turn.tan(1, 2).multiply(Turn.tan(1, 3));
        assertEquals(5, sum.p(), "p₁q₂ + p₂q₁ = 1·3 + 1·2");
        assertEquals(5, sum.q(), "q₁q₂ − p₁p₂ = 2·3 − 1·1");
        assertEquals(OptionalDouble.of(1), sum.tangent());
    }

    /** Two 45° turns make a right angle, whose tangent is ω. */
    @Test
    void twoDiagonalsMakeAVertical() {
        Turn quarter = Turn.tan(1, 1).multiply(Turn.tan(1, 1));
        assertEquals(2, quarter.p());
        assertEquals(0, quarter.q());
        assertEquals(OptionalDouble.empty(), quarter.tangent(), "90° has tangent ω");
    }

    /** A turn times its conjugate cancels the angle and keeps the magnitude. */
    @Test
    void aTurnAndItsConjugateCancelTheAngle() {
        Turn back = Turn.tan(3, 4).multiply(Turn.tan(3, 4).conjugate());
        assertEquals(0, back.p(), "the angle is gone");
        assertEquals(25, back.q(), "the magnitude is not — 3² + 4²");
    }

    /** Addition is componentwise and stays out of the angle's way. */
    @Test
    void additionIsComponentwise() {
        Turn sum = Turn.tan(1, 2).add(Turn.tan(3, 4));
        assertEquals(4, sum.p());
        assertEquals(6, sum.q());
    }

    // ---------------------------------------------------------------- cartesian

    /** A relabelling: no trigonometry, and the magnitude is not forced to one. */
    @Test
    void toCartesianIsExactAndKeepsTheMagnitude() {
        assertArrayEquals(new long[] {4, 3}, Turn.tan(3, 4).toCartesian(), "q along x, p along y");
        assertArrayEquals(new long[] {8, 6}, Turn.tan(6, 8).toCartesian(), "twice out, same way");
    }

    // ---------------------------------------------------------------- overflow

    /**
     * The policy, on the case where the answer is knowable anyway: two huge 45° turns still compose to a right
     * angle. The {@code q} component is exactly zero whatever bits were shed, so the direction is provably
     * intact after the shifting.
     */
    @Test
    void overflowShedsMagnitudeAndKeepsTheDirection() {
        long huge = 1L << 40;
        Turn quarter = Turn.tan(huge, huge).multiply(Turn.tan(huge, huge));
        assertEquals(0, quarter.q(), "still a right angle");
        assertTrue(quarter.p() > 0, "and still pointing the same way along it");
        assertEquals(OptionalDouble.empty(), quarter.tangent());
    }

    /**
     * And on a case where it is not exactly knowable: doubling an angle of {@code arctan ½} should give
     * {@code tan 2θ = 4/3}, and it does to well inside a rounding error, having shed bits to get there.
     */
    @Test
    void overflowKeepsTheDirectionToWithinRounding() {
        long huge = 1L << 40;
        Turn doubled = Turn.tan(huge, 2 * huge).multiply(Turn.tan(huge, 2 * huge));
        double tangent = doubled.tangent().orElseThrow();
        assertEquals(4.0 / 3.0, tangent, 1e-9, "tan 2θ where tan θ = ½");
    }

    /** Addition overflows too, and answers the same way. */
    @Test
    void additionAlsoShedsRatherThanWrapping() {
        Turn sum = Turn.tan(Long.MAX_VALUE, Long.MAX_VALUE).add(Turn.tan(Long.MAX_VALUE, Long.MAX_VALUE));
        assertTrue(sum.p() > 0, "no wrap to a negative");
        assertTrue(sum.sameDirection(Turn.tan(1, 1)), "still the diagonal");
    }

    /** The one negation that cannot be done in place sheds a bit instead of wrapping to itself. */
    @Test
    void negatingTheMostNegativeLongShedsRatherThanWrapping() {
        Turn negated = Turn.tan(Long.MIN_VALUE, Long.MIN_VALUE).negate();
        assertTrue(negated.p() > 0, "Long.MIN_VALUE negated must not stay negative");
        assertTrue(negated.sameDirection(Turn.tan(1, 1)));
    }

    /** Direction comparison is exact even when the cross product does not fit in a long. */
    @Test
    void sameDirectionIsExactBeyondSixtyFourBits() {
        long huge = 1L << 40;
        assertTrue(Turn.tan(huge, huge).sameDirection(Turn.tan(huge * 2, huge * 2)));
        assertFalse(Turn.tan(huge, huge).sameDirection(Turn.tan(huge * 2, huge * 2 + 1)));
    }

    // ---------------------------------------------------------------- the tower

    @Test
    void levelsCountDoublingsFromAScalar() {
        assertEquals(0, Turn.scalar(1).level());
        assertEquals(1, Turn.tan(1, 2).level());
        assertEquals(2, Turn.tan(1, 2).doubled().level());
    }

    /** A level-0 turn is a magnitude alone, and says so rather than reaching past its components. */
    @Test
    void aScalarHasNoDirectionAndSaysSo() {
        Turn scalar = Turn.scalar(3);
        assertThrows(IllegalStateException.class, scalar::p);
        assertThrows(IllegalStateException.class, scalar::q);
        assertThrows(IllegalStateException.class, scalar::tangent);
    }

    /** Doubling embeds faithfully: the product is the same one level up. */
    @Test
    void doublingPreservesTheProduct() {
        Turn a = Turn.tan(1, 2);
        Turn b = Turn.tan(1, 3);
        assertEquals(a.multiply(b).doubled(), a.doubled().multiply(b.doubled()));
    }

    /**
     * Level 2 stops commuting, which is the construction working. The convention here is
     * {@code i·j = −k} — a valid quaternion algebra, the mirror of Hamilton's — and this pins it so that a
     * later change of convention is a failing test rather than a surprise.
     */
    @Test
    void levelTwoStopsCommutingWithTheMirrorConvention() {
        Turn i = Turn.of(0, 1, 0, 0);
        Turn j = Turn.of(0, 0, 1, 0);
        assertEquals(i.multiply(j), j.multiply(i).negate(), "they anticommute");
        assertArrayEquals(new long[] {0, 0, 0, -1}, i.multiply(j).toCartesian(), "i·j = −k, not +k");
        assertArrayEquals(new long[] {-1, 0, 0, 0}, i.multiply(i).toCartesian(), "i² = −1");
    }

    /** Levels do not silently mix — the caller is told to double one rather than guessed at. */
    @Test
    void mismatchedLevelsRefuseToCompose() {
        Turn pair = Turn.tan(1, 2);
        assertThrows(IllegalArgumentException.class, () -> pair.add(pair.doubled()));
        assertThrows(IllegalArgumentException.class, () -> pair.multiply(pair.doubled()));
    }

    /** Raw components must be a power of two, or the recursion has no floor. */
    @Test
    void componentCountMustBeAPowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> Turn.of(1, 2, 3));
    }
}
