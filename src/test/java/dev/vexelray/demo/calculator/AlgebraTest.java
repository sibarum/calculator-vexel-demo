package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam onto the algebra.
 *
 * <p>These are about <em>what the application is told</em>, not about whether the theory is right — cott-engine
 * has 228 tests for that and duplicating them here would be two statements of one thing, and the pair of them
 * would disagree the first time the theory moved. So what is pinned here is the reading: which mode an entry
 * lands in, that a refusal is reported rather than swallowed, and that a break in a curve is a break.
 */
class AlgebraTest {

    private static Algebra.Reading read(String entry) {
        return Algebra.read(entry);
    }

    @Test
    @DisplayName("how many names an expression leaves free is what decides the mode")
    void theModeFollowsTheFreeNames() {
        assertSame(Algebra.Mode.POINT, read("2+2").mode());
        assertSame(Algebra.Mode.CURVE, read("0^x").mode());
        assertSame(Algebra.Mode.SURFACE, read("x+y").mode());
    }

    @Test
    @DisplayName("a value with no free names is answered, placed, and shown its derivation")
    void aClosedExpressionIsAPlacedPoint() {
        Algebra.Reading reading = read("2+2");

        assertNull(reading.refusal());
        assertEquals("4", reading.answer());
        assertTrue(reading.drawsMarker());
        assertNotNull(reading.place());
        assertFalse(reading.derivation().isEmpty(), "the steps that got there are the point of this mode");
    }

    /**
     * The four points are four places. This is the one piece of theory worth restating here, because it is the
     * distinction the whole chart exists to show and a projection that lost it would still look plausible.
     */
    @Test
    @DisplayName("the point zero and one do not land in the same place")
    void theFourPointsAreFourPlaces() {
        List<double[]> places = List.of(
                read("1").place().toDoubles(),
                read("0").place().toDoubles(),
                read("w").place().toDoubles(),
                read("-1").place().toDoubles());
        for (int i = 0; i < places.size(); i++) {
            for (int j = i + 1; j < places.size(); j++) {
                assertNotEquals(java.util.Arrays.toString(places.get(i)),
                        java.util.Arrays.toString(places.get(j)),
                        "two of the four points landed together");
            }
        }
    }

    @Test
    @DisplayName("a curve is sampled over its one free name, whatever that name is")
    void aCurveIsSampledOverItsVariable() {
        Algebra.Reading reading = read("0^t");

        assertEquals("t", reading.variable());
        List<double[]> runs = Algebra.curve(reading, -3, 3, 64);
        assertEquals(1, runs.size(), "0^t settles everywhere, so it is one unbroken run");
        assertEquals(64 * 3, runs.getFirst().length);
    }

    /**
     * The engine answers 1/0 as omega, a value with a place, so the thing that puts a hole in an ordinary plot
     * does not put one here. Worth pinning: it is the most visible consequence of the algebra being this one.
     */
    @Test
    @DisplayName("dividing by zero is a point on the chart, not a hole in the curve")
    void divisionByZeroDoesNotBreakTheCurve() {
        List<double[]> runs = Algebra.curve(read("1÷x"), -2, 2, 65);

        assertEquals(1, runs.size(), "a pole broke the curve, but omega is a value and has a place");
        assertEquals(65 * 3, runs.getFirst().length);
    }

    /**
     * {@code x+0} is the additive sum the rules do not fold — a term that stands at every sample — so nothing
     * along it is placed and there is nothing to draw. The curve is empty rather than a straight line through
     * the samples that happened to survive.
     */
    @Test
    @DisplayName("an expression that stands everywhere draws nothing at all")
    void aTermThatStandsIsNotDrawn() {
        assertTrue(Algebra.curve(read("x+0"), -3, 3, 32).isEmpty());
    }

    /**
     * {@code 2^x} answers for a natural power and stands otherwise — {@code 2^0} included, since {@code x^0}
     * is a 4-cycle that reaches nothing else, and {@code 2^-1} since a multiplicity takes no negative power.
     * Over {@code [-3, 3]} at whole steps that is four standing samples and then three answers, so the curve
     * has to begin where the answers begin rather than at the edge of the domain.
     */
    @Test
    @DisplayName("a curve begins where the answers begin, not at the edge of the domain")
    void aLeadingGapIsNotDrawn() {
        List<double[]> runs = Algebra.curve(read("2^x"), -3, 3, 7);

        assertEquals(1, runs.size());
        assertEquals(3 * 3, runs.getFirst().length, "only the three samples that answered");
        assertEquals(1, runs.getFirst()[0], 1e-9, "and the first of them is at x = 1");
    }

    /**
     * At half steps the same expression answers only at the whole ones, so no two answered samples are
     * neighbours. A stroke through one point draws nothing, so emitting those runs would leave invisible
     * artefacts in the buffer for somebody to find later; they are dropped instead.
     *
     * <p>A gap strictly <em>inside</em> a run is the case this splitting exists for, and no entry the engine
     * answers today produces one — what it answers is either everything, a tail, or isolated points. The
     * splitting is kept because the alternative is a line drawn through samples the engine declined, and that
     * is the one thing this seam may not do.
     */
    @Test
    @DisplayName("isolated answers are dropped rather than drawn as strokes of one point")
    void isolatedSamplesAreNotStrokes() {
        List<double[]> runs = Algebra.curve(read("2^x"), -3, 3, 13);

        assertTrue(runs.isEmpty(), "no two answered samples are neighbours here, so there is no segment");
    }

    @Test
    @DisplayName("a syntax error is the engine's own message, not one invented here")
    void aSyntaxErrorIsPassedThrough() {
        Algebra.Reading reading = read("&&&");

        assertNotNull(reading.refusal());
        assertTrue(reading.refusal().contains("&"), reading.refusal());
        assertFalse(reading.drawsCurve());
        assertFalse(reading.drawsMarker());
    }

    /**
     * The engine names the character only when the entry starts with it; {@code 2 & 3} comes back as a bare
     * "Error". That is worth a test of its own, because what matters at this seam is that the entry is refused
     * and nothing is drawn — which must not depend on how good the message happened to be.
     */
    @Test
    @DisplayName("an entry is refused even where the engine's message says little")
    void aTerseErrorIsStillARefusal() {
        Algebra.Reading reading = read("2 & 3");

        assertNotNull(reading.refusal());
        assertFalse(reading.drawsCurve());
        assertFalse(reading.drawsMarker());
    }

    @Test
    @DisplayName("an empty field asks for an expression rather than drawing one")
    void anEmptyEntryIsRefusedQuietly() {
        assertNotNull(read("").refusal());
        assertNotNull(read("   ").refusal());
        assertFalse(read("").drawsCurve());
    }

    /**
     * The sampling literal has to be exact. "0.0" parses as the coordinate 0/10, which is not the rational
     * zero, and the rules that fire on zero do not fire on it -- so 0^x at the origin answered 0^(0/10) and
     * placed at erasure instead of answering 1. One wrong sample, in the middle of the domain.
     */
    @Test
    @DisplayName("the origin samples as an exact zero, so 0^0 is 1 and not erasure")
    void theOriginIsSampledExactly() {
        // A domain symmetric about zero with an odd sample count puts a sample exactly on the origin.
        List<double[]> runs = Algebra.curve(read("0^x"), -1, 1, 3);
        assertEquals(1, runs.size());

        double[] run = runs.getFirst();
        // The middle sample: x = 0, and 0^0 is 1, which is the place (1, 0).
        assertEquals(0, run[3], 1e-9, "the middle sample should be the origin");
        assertEquals(1, run[4], 1e-9, "0^0 is 1, so the real coordinate is 1");
        assertEquals(0, run[5], 1e-9, "and its traction coordinate is absent");
    }

    @Test
    @DisplayName("the axes are named for what they carry, which differs between the modes")
    void theAxesAreNamedForWhatTheyCarry() {
        assertArrayEqualsNamed(new String[]{"x", "Re", "Tr"}, read("0^x").axisNames());
        assertArrayEqualsNamed(new String[]{"t", "Re", "Tr"}, read("0^t").axisNames());
        assertArrayEqualsNamed(new String[]{"Re", "Tr", "Tr'"}, read("2+2").axisNames());
    }

    private static void assertArrayEqualsNamed(String[] expected, String[] actual) {
        assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
    }
}
