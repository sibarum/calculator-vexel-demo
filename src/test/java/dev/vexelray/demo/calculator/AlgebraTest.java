package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    @DisplayName("a curve is walked over its one free name, whatever that name is")
    void aCurveIsWalkedOverItsVariable() {
        Algebra.Reading reading = read("0^t");

        assertEquals("t", reading.variable());
        List<double[]> runs = Algebra.walk(reading, -3, 3, 64);
        assertFalse(runs.isEmpty());
        // 0^t is the traction axis itself: every sample but the origin lands at (0, t), so the walk is two
        // strokes running away from the origin along Tr, one per direction.
        assertEquals(2, strokes(runs).size());
        for (double[] stroke : strokes(runs)) {
            for (int i = 0; i < stroke.length / 3; i++) {
                assertEquals(0, stroke[i * 3], 1e-9, "0^t has no real part away from the origin");
            }
        }
    }

    /**
     * The engine answers 1/0 as omega, a value with a place, so the thing that puts a hole in an ordinary plot
     * does not put one here. It is drawn, and it is drawn <em>alone</em>: its neighbours run off to ±1000 while
     * omega sits at (0, -1), so the stroke breaks either side of it and the value gets a marker of its own.
     */
    @Test
    @DisplayName("dividing by zero is a point on the chart, not a hole and not a spike")
    void divisionByZeroIsItsOwnPoint() {
        List<double[]> runs = Algebra.walk(read("1÷x"), -2, 2, 65);

        assertTrue(has(runs, 0, -1, 0), "omega is a value with a place and has to be drawn");
        for (double[] one : isolated(runs)) {
            assertArrayEquals(new double[]{0, -1, 0}, one, 1e-9,
                    "the only isolated point should be the pole itself");
        }
    }

    /**
     * <b>The failsafe, on an entry nobody would call pathological.</b> Refining until every gap is short
     * cannot terminate here: the chord either side of the pole grows as the interval halves, past 1000 while
     * the step goes to nothing. The walk has to notice that and stop rather than subdivide.
     */
    @Test
    @DisplayName("a diverging neighbourhood stops the walk instead of refining it forever")
    void divergenceStopsRatherThanRefining() {
        List<double[]> runs = Algebra.walk(read("1÷x"), -2, 2, 65);

        assertTrue(points(runs) <= 65 * 8, "the walk spent more than its budget: " + points(runs));
    }

    /** The other half of the failsafe: bounded but endless oscillation, which is the case it was written for. */
    @Test
    @DisplayName("an entry that oscillates without limit still terminates inside its budget")
    void anOscillatingEntryTerminates() {
        List<double[]> runs = Algebra.walk(read("tan(1÷x)"), -1, 1, 32);

        assertFalse(runs.isEmpty(), "tan(1÷x) does answer, so something should be drawn");
        assertTrue(points(runs) <= 32 * 8, "the walk spent more than its budget: " + points(runs));
    }

    /**
     * {@code x+π} stands at every sample — π is not a rational, so the sum is not the additive pair and
     * nothing along the curve is placed. The curve is empty rather than a straight line through the samples
     * that happened to survive.
     *
     * <p><b>Not {@code x+0}, which used to be this example and is now a line.</b> {@code Place} reads a
     * standing sum as {@code n + 0^t}, so {@code -3+0} places at {@code (-3, 1)} rather than standing. An
     * expression that stands because of an irrational is the durable case: it holds for every binding, where
     * a sum of unlike traction parts like {@code 0^x+0^2} only stands while the grid misses {@code x = 0}.
     */
    @Test
    @DisplayName("an expression that stands everywhere draws nothing at all")
    void aTermThatStandsIsNotDrawn() {
        assertTrue(Algebra.walk(read("x+π"), -3, 3, 32).isEmpty());
    }

    /**
     * {@code 2^x} answers for a natural power and stands otherwise — {@code 2^0} included, since {@code x^0}
     * is a 4-cycle that reaches nothing else, and {@code 2^-1} since a multiplicity takes no negative power.
     * So its answers are isolated: no two of them are neighbours at any step size.
     *
     * <p><b>They are kept, which they were not before.</b> When the input held an axis a run of one point was
     * dropped, because a stroke through one point draws nothing. Now that a sample contributes only where its
     * value landed, an isolated answer is an ordinary value at an ordinary place and gets a marker.
     */
    @Test
    @DisplayName("an isolated answer is kept as a point rather than dropped for having no neighbour")
    void isolatedAnswersAreKeptAsPoints() {
        List<double[]> runs = Algebra.walk(read("2^x"), -3, 3, 7);

        assertFalse(runs.isEmpty(), "2^3 answers, so there is something to draw");
        assertEquals(runs.size(), isolated(runs).size(), "every run here should be a lone point");
        assertTrue(has(runs, 8, 0, 0), "2^3 is 8, which places at (8, 0)");
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
        // The walk starts at the origin, so this sample is taken whatever the step count.
        assertTrue(has(Algebra.walk(read("0^x"), -1, 1, 3), 1, 0, 0),
                "0^0 is 1, which places at (1, 0) — not erasure and not the origin of the chart");
    }

    /**
     * <b>The case that decides the subdivision rule.</b> As x approaches zero {@code 0^x} approaches
     * {@code (0, 0)}, which is erasure and not in the type; at zero the answer is {@code 0^0 = 1} at
     * {@code (1, 0)}. The gap is exactly 1 at every depth, so refining cannot close it. The walk has to break
     * the stroke and leave the origin standing alone, rather than either subdividing forever or drawing a
     * line through a region the engine never claimed.
     */
    @Test
    @DisplayName("a jump that cannot be subdivided away breaks the stroke instead")
    void anUnbridgeableJumpBreaksTheStroke() {
        List<double[]> runs = Algebra.walk(read("0^x"), -3, 3, 64);

        assertTrue(has(runs, 1, 0, 0), "0^0 = 1 has to be drawn");
        for (double[] stroke : strokes(runs)) {
            for (int i = 0; i < stroke.length / 3; i++) {
                assertNotEquals(1.0, stroke[i * 3],
                        "the origin was joined to the traction axis by a stroke that crosses nothing");
            }
        }
    }

    @Test
    @DisplayName("the axes carry the value's own coordinates, the same three in every mode")
    void theAxesAreNamedForWhatTheyCarry() {
        assertArrayEqualsNamed(new String[]{"Re", "Tr", "Tr'"}, read("0^x").axisNames());
        assertArrayEqualsNamed(new String[]{"Re", "Tr", "Tr'"}, read("0^t").axisNames());
        assertArrayEqualsNamed(new String[]{"Re", "Tr", "Tr'"}, read("2+2").axisNames());
    }

    private static void assertArrayEqualsNamed(String[] expected, String[] actual) {
        assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
    }

    // ------------------------------------------------------------------ reading a walk

    /** The runs holding more than one point: the ones drawn as strokes. */
    private static List<double[]> strokes(List<double[]> runs) {
        return runs.stream().filter(r -> r.length > 3).toList();
    }

    /** The runs holding exactly one point: the ones drawn as markers. */
    private static List<double[]> isolated(List<double[]> runs) {
        return runs.stream().filter(r -> r.length == 3).toList();
    }

    private static int points(List<double[]> runs) {
        return runs.stream().mapToInt(r -> r.length / 3).sum();
    }

    /** Whether any sample anywhere in the walk landed at these coordinates. */
    private static boolean has(List<double[]> runs, double a, double b, double c) {
        for (double[] run : runs) {
            for (int i = 0; i < run.length / 3; i++) {
                if (Math.abs(run[i * 3] - a) < 1e-9
                        && Math.abs(run[i * 3 + 1] - b) < 1e-9
                        && Math.abs(run[i * 3 + 2] - c) < 1e-9) {
                    return true;
                }
            }
        }
        return false;
    }
}
