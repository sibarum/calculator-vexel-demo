package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.cott.engine.ratio.T;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam onto the algebra.
 *
 * <p>These are about <em>what the application is told</em>, not about whether the model is right — cott-engine
 * has its own tests for that, one per line of {@code docs/Traction-Model.md}, and duplicating them here would
 * be two statements of one thing that would disagree the first time the model moved. So what is pinned here is
 * the reading: which mode an entry lands in, that a refusal is reported rather than swallowed, that a sample
 * the model does not fold breaks the curve rather than being drawn through, and the two facts about the
 * <em>picture</em> that the model makes true and nothing else would — the eight named points are eight places,
 * and the pole of {@code 1÷x} is an ordinary point on a straight line.
 */
class AlgebraTest {

    private static Algebra.Reading read(String entry) {
        return Algebra.read(entry);
    }

    @Test
    @DisplayName("how many names an expression leaves free is what decides the mode")
    void theModeFollowsTheFreeNames() {
        assertSame(Algebra.Mode.POINT, read("2+2").mode());
        assertSame(Algebra.Mode.CURVE, read("1÷x").mode());
        assertSame(Algebra.Mode.SURFACE, read("x+y").mode());
    }

    @Test
    @DisplayName("a value with no free names is answered, placed, and shown how it folded")
    void aClosedExpressionIsAPlacedPoint() {
        Algebra.Reading reading = read("2+2");

        assertEquals(null, reading.refusal());
        assertEquals("T(4,1)", reading.answer());
        assertTrue(reading.drawsMarker());
        assertArrayEquals2(new double[]{1, 4}, reading.coordinates());
        assertFalse(reading.derivation().isEmpty(), "how it folded is the point of this mode");
    }

    /**
     * <b>The test the model exists for.</b> Four of the eight rows share a tangent with another —
     * {@code 0} with {@code -0}, {@code 1} with {@code -_1}, {@code -1} with {@code _1} — so a chart drawn
     * against the projection would put each of those pairs in one place. The plane draws the pair, so the
     * eight stand in eight places, and this is what would still look perfectly plausible if it broke.
     *
     * <p>It is also the test that every row can be <em>typed</em>, the way the model writes it: the numbers
     * parse, the underscores are names bound in {@code Algebra}, and the minus signs are the grammar's.
     */
    @Test
    @DisplayName("the eight named points of the model are eight places in the plane")
    void theEightNamedPointsAreEightPlaces() {
        List<String> rows = List.of("0", "_0", "1", "-1", "_1", "-_1", "w", "-w");
        List<String> places = new ArrayList<>();
        for (String row : rows) {
            Algebra.Reading reading = read(row);
            assertTrue(reading.drawsMarker(), row + " did not fold to a pair");
            places.add(Arrays.toString(reading.coordinates()));
        }
        for (int i = 0; i < rows.size(); i++) {
            for (int j = i + 1; j < rows.size(); j++) {
                assertFalse(places.get(i).equals(places.get(j)),
                        rows.get(i) + " and " + rows.get(j) + " landed together, at " + places.get(i));
            }
        }
    }

    /** The coordinates the model's table gives those rows, so a place is right and not merely distinct. */
    @Test
    @DisplayName("each named point stands where the model's table puts it")
    void theNamedPointsStandWhereTheTableSaysTheyDo() {
        assertArrayEquals2(new double[]{1, 0}, read("0").coordinates());     // T(0,1)   at  1
        assertArrayEquals2(new double[]{-1, 0}, read("_0").coordinates());   // T(0,-1)  at -1
        assertArrayEquals2(new double[]{1, 1}, read("1").coordinates());     // T(1,1)   at  1+i
        assertArrayEquals2(new double[]{1, -1}, read("-1").coordinates());   // T(-1,1)  at  1-i
        assertArrayEquals2(new double[]{-1, 1}, read("_1").coordinates());   // T(1,-1)  at -1+i
        assertArrayEquals2(new double[]{-1, -1}, read("-_1").coordinates()); // T(-1,-1) at -1-i
        assertArrayEquals2(new double[]{0, 1}, read("w").coordinates());     // T(1,0)   at  i
        assertArrayEquals2(new double[]{0, -1}, read("-w").coordinates());   // T(-1,0)  at -i
    }

    /**
     * The projection column, and the two rows it separates that nothing else does: {@code 0} and {@code -0}
     * project to the two signed zeroes, and the quarter turns to the two infinities.
     */
    @Test
    @DisplayName("the projection is the model's table, signed zeroes and all")
    void theProjectionIsTheTable() {
        assertEquals("+0", Algebra.projection(T.of(0, 1)));
        assertEquals("-0", Algebra.projection(T.of(0, -1)));
        assertEquals("+inf", Algebra.projection(T.of(1, 0)));
        assertEquals("-inf", Algebra.projection(T.of(-1, 0)));
        assertEquals("1", Algebra.projection(T.of(1, 1)));
        assertEquals("-1", Algebra.projection(T.of(1, -1)), "_1 projects as -1, as the table says");
        assertEquals("1", Algebra.projection(T.of(-1, -1)), "-_1 projects as 1, as the table says");
        assertEquals("NaN", Algebra.projection(T.of(0, 0)),
                "0w names no number; x÷x = 1 is a reading and the type no longer applies it");
    }

    /**
     * The origin folds and is not drawn. It has no direction, and this chart is a circle of directions, so it
     * is reported beside the entry rather than placed at an angle it does not stand at.
     */
    @Test
    @DisplayName("0w is an answer with no place on the circle")
    void theOriginIsReportedAndNotPlaced() {
        Algebra.Reading reading = read("0·ω");
        assertTrue(reading.understood(), "0·ω folds, so it is not a refusal");
        assertEquals("T(0,0)", reading.answer());
        assertFalse(reading.drawsMarker(), "the origin stands at no angle, so nothing is placed");
    }

    /**
     * <b>The other test the model exists for.</b> Sample the name at {@code k÷d} and {@code 1÷x} is
     * {@code T(d, k)}, the point {@code k + di} — so the classic singularity is a straight horizontal line and
     * the pole is the ordinary point on it where {@code q} reaches zero. No hole, no spike, and no break: one
     * unbroken run straight through the place an ordinary plot cannot draw.
     */
    @Test
    @DisplayName("the pole of 1÷x is an ordinary point on a straight line")
    void thePoleIsAnOrdinaryPointOnALine() {
        List<double[]> runs = Algebra.walk(read("1÷x"), -2, 2, 64);

        assertEquals(1, runs.size(), "the line is unbroken, so there is one run");
        double[] run = runs.getFirst();
        double p = run[2];
        boolean throughTheAxis = false;
        for (int i = 0; i < run.length / 3; i++) {
            assertEquals(p, run[i * 3 + 2], 1e-9, "every sample of 1÷x stands at the same height");
            throughTheAxis |= Math.abs(run[i * 3 + 1]) < 1e-9;
        }
        assertTrue(throughTheAxis, "the pole is the sample whose q is zero, and it has to be on the line");
    }

    /**
     * Nothing in this model reduces, so where a sample lands depends on how its input was spelled: 1.5 is
     * {@code T(15,10)} and 2 is {@code T(2,1)}, and their reciprocals stand an order of magnitude apart. The
     * walk therefore steps one denominator across the whole domain — which is what makes the line above a line
     * rather than a scatter.
     */
    @Test
    @DisplayName("every sample of a walk is taken at one denominator")
    void theWalkStepsOneDenominator() {
        List<double[]> runs = Algebra.walk(read("x"), -2, 2, 64);

        assertFalse(runs.isEmpty());
        double q = runs.getFirst()[1];
        for (double[] run : runs) {
            for (int i = 0; i < run.length / 3; i++) {
                assertEquals(q, run[i * 3 + 1], 1e-9,
                        "x itself is T(k, d): every sample stands on the same vertical line");
            }
        }
    }

    /**
     * A power folds at a whole exponent and stands otherwise, so {@code 2^x} answers on one side of the domain
     * and nothing on the other. The run covers what folded and stops where the folding does, rather than
     * bridging a stretch the model said nothing about.
     */
    @Test
    @DisplayName("a sample that does not fold breaks the run rather than being drawn through")
    void aSampleThatDoesNotFoldBreaksTheRun() {
        // Six steps across six units is one sample per unit, so the exponents are whole and the power rule
        // can reach them at all: T(k,1)^n for k from 0 up, and nothing below zero.
        List<double[]> runs = Algebra.walk(read("2^x"), -3, 3, 6);

        assertEquals(1, runs.size(), "the answers are contiguous, so they are one run");
        assertEquals(4, runs.getFirst().length / 3, "2^0 through 2^3 answer; the negative powers stand");
    }

    @Test
    @DisplayName("an expression that stands everywhere draws nothing at all")
    void aTermThatStandsIsNotDrawn() {
        // Nothing resolves calls in this client, so sin(x) is a term with a name applied to an argument, and
        // it stands at every sample. That is the honest answer while the model has no real-valued calls.
        assertTrue(Algebra.walk(read("sin(x)"), -3, 3, 32).isEmpty());
    }

    @Test
    @DisplayName("an entry that does not fold to a pair is shown standing, and placed nowhere")
    void aStandingValueIsShownAndNotPlaced() {
        Algebra.Reading reading = read("2^(1÷2)");

        assertFalse(reading.drawsMarker(), "a term that is not one pair has no point in the plane");
        assertNotNull(reading.refusal());
        assertTrue(reading.subtitle().startsWith("stands"), reading.subtitle());
    }

    @Test
    @DisplayName("a syntax error is the engine's own message, not one invented here")
    void aSyntaxErrorIsPassedThrough() {
        Algebra.Reading reading = read("&&&");

        assertNotNull(reading.refusal());
        assertFalse(reading.drawsCurve());
        assertFalse(reading.drawsMarker());
    }

    @Test
    @DisplayName("an entry the grammar rejects part-way through is still refused")
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
     * The input axis is named for the name the entry actually left free, and the other is the turn — never a
     * coordinate. What is drawn up the chart is {@code arg(q + pi)}, one number out of the pair and the whole
     * of the value; the radius the pair has is which representative got written.
     */
    @Test
    @DisplayName("the axes are the free name and the turn")
    void theAxesAreNamedForWhatTheyCarry() {
        assertArrayEqualsNamed(new String[]{"x", "θ"}, read("1÷x").axisNames());
        assertArrayEqualsNamed(new String[]{"t", "θ"}, read("1÷t").axisNames());
        assertArrayEqualsNamed(new String[]{"x", "θ"}, read("2+2").axisNames(),
                "a value has no input, so the placeholder stands and nothing draws it");
    }

    @Test
    @DisplayName("the default expression is a curve, which is what the plot opens on")
    void theDefaultEntryDraws() {
        Algebra.Reading reading = read(Algebra.DEFAULT_EXPRESSION);

        assertSame(Algebra.Mode.CURVE, reading.mode());
        assertFalse(Algebra.walk(reading, -6, 6, 420).isEmpty());
    }

    private static void assertArrayEqualsNamed(String[] expected, String[] actual) {
        assertEquals(Arrays.toString(expected), Arrays.toString(actual));
    }

    private static void assertArrayEqualsNamed(String[] expected, String[] actual, String message) {
        assertEquals(Arrays.toString(expected), Arrays.toString(actual), message);
    }

    private static void assertArrayEquals2(double[] expected, double[] actual) {
        assertEquals(Arrays.toString(expected), Arrays.toString(actual));
    }
}
