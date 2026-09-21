package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.cott.engine.ratio.T;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is drawn <em>between</em> two samples.
 *
 * <p>A stroke joins its vertices with straight lines, so whatever the geometry does not say, the renderer
 * decides by interpolating — and in a chart whose vertical axis is an angle, that decision is a claim about
 * values. These are the tests for the claim: the path between two samples passes through their <b>mediant</b>,
 * which is the model's own {@code ⊕}, and not through the average of their two turns, which is a value the
 * type cannot hold.
 *
 * <p>Read off the built geometry rather than from a renderer, because the vertices are the whole of what the
 * renderer is told. If the mediant is a vertex, it is on the curve.
 */
class CurveTest {

    /** Where a pair stands on the vertical axis, in the box's own coordinates, as {@code Geometry} puts it. */
    private static double height(long p, long q) {
        return Math.atan2(p, q) / Geometry.TURN * Geometry.BOX;
    }

    private static Geometry.Built built(String entry, double x0, double x1, int samples) {
        return Geometry.of(Algebra.read(entry), x0, x1, samples,
                Geometry.Furniture.DEFAULT, 0.045, Ramp.BLURPLE, Turns.Window.WHOLE);
    }

    /** Every vertex of every stroke, as heights, in order. */
    private static double[] heights(Geometry.Built built) {
        int n = 0;
        for (var stroke : built.strokes()) {
            n += stroke.through().size();
        }
        double[] out = new double[n];
        int at = 0;
        for (var stroke : built.strokes()) {
            for (var v : stroke.through()) {
                out[at++] = v.y();
            }
        }
        return out;
    }

    private static boolean has(double[] values, double wanted) {
        for (double v : values) {
            if (Math.abs(v - wanted) < 1e-9) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>The case the whole change is for.</b> Walk {@code 1÷x} coarsely enough that the samples are whole
     * numbers: {@code x = 0} answers {@code ω} at a quarter turn and {@code x = 1} answers {@code 1} at an
     * eighth, and what is drawn between those two is the thing under test.
     *
     * <p>The mediant is {@code T(1,0) ⊕ T(1,1) = T(2,1)}, which is {@code 2} — and {@code 2} is what the
     * engine really answers at {@code x = 1÷2}, so here the mediant is the exact value and not an
     * approximation to it. A straight segment would instead pass through 67.5°, whose tangent is
     * {@code 1 + √2}: irrational, and not a member of the type at all.
     *
     * <p>The domain reaches past {@code x = 1} so that {@code T(2,1)} is not itself a sample. The walk picks
     * its own denominator from the domain and the step count — two steps across two units is one per unit —
     * and a test that assumed otherwise would pass on a value the engine had computed rather than on one the
     * renderer had drawn.
     */
    @Test
    @DisplayName("the path between two samples goes through their mediant, not through the turn between them")
    void theCurvePassesThroughTheMediant() {
        Geometry.Built built = built("1÷x", 0, 2, 2);
        double[] drawn = heights(built);

        double mediant = height(2, 1);                                  // T(1,0) + T(1,1) = T(2,1), 63.43
        double average = (height(1, 0) + height(1, 1)) / 2;             // where a straight segment goes, 67.5

        assertTrue(sampled(built, 1, 0), "the premise: omega is a sample");
        assertTrue(sampled(built, 1, 1), "the premise: one is a sample");
        assertFalse(sampled(built, 2, 1), "the premise: the mediant is NOT a sample, so drawing it is a choice");

        assertTrue(has(drawn, mediant), "the mediant is not a vertex, so the curve does not pass through it");
        assertNotEquals(average, mediant, 1e-9, "the premise: the two readings differ here");
        // Negative: the straight segment sat four degrees ABOVE the mediant, reading 67.5 where the value is
        // 63.43. That is the error being removed, and its sign says a chord across a turn overstates it.
        assertEquals(-4.07, Math.toDegrees((mediant - average) / Geometry.BOX * Geometry.TURN), 0.01,
                "the premise: the two readings are four degrees apart");
    }

    /** Whether the walk itself answered this pair, as opposed to the renderer having drawn it. */
    private static boolean sampled(Geometry.Built built, long p, long q) {
        double[] samples = built.samples();
        for (int i = 0; i < samples.length / 3; i++) {
            if (Math.abs(samples[i * 3 + 1] - q) < 1e-9 && Math.abs(samples[i * 3 + 2] - p) < 1e-9) {
                return true;
            }
        }
        return false;
    }

    /** And the mediant really is the value, which is what makes this exact rather than merely principled. */
    @Test
    @DisplayName("for 1÷x the mediant is the answer the engine gives between the samples")
    void theMediantIsTheAnswerForAReciprocal() {
        T atHalf = Algebra.read("1÷x").term()
                .substitute(java.util.Map.of("x", sibarum.cott.parse.Node.of(1, 2)))
                .fold().literal().orElseThrow();

        assertEquals("T(2,1)", "T(" + atHalf.p() + "," + atHalf.q() + ")",
                "1÷(1÷2) should be 2, and T(1,0) + T(1,1) is T(2,1)");
    }

    /**
     * What arrives at the buffer, which is no longer what the subdivision produced.
     *
     * <p><b>This test used to assert the opposite and it was right to.</b> It read
     * {@code vertices >= 2 * samples - 1} — "every segment should carry its mediant" — because the first
     * bisection is unconditional and that was the claim: the drawn path goes through a value the type has.
     *
     * <p>It still does, to within the flatness tolerance the subdivision itself uses, and {@link Chords} is
     * where that argument is written out. What changed is that the redundant vertices are dropped on the way
     * into the buffer, because the march's cost is very nearly linear in the cone count and this curve was
     * spending 840 of them to say what 25 say. The bound worth pinning here is therefore the new one — the
     * geometry is a small fraction of the sampling — and the <em>accuracy</em> bound it is traded against
     * lives in {@code ChordsTest.theDrawnCurveStillPassesThroughEveryAnswer}, which is the test that would go
     * red if simplifying ever moved the curve off the values.
     */
    @Test
    @DisplayName("an ordinary curve reaches the buffer as a fraction of its sampling")
    void theDrawnGeometryIsAFractionOfTheSampling() {
        Geometry.Built built = built("1÷x", -6, 6, 420);
        int samples = built.samples().length / 3;
        int vertices = heights(built).length;

        assertTrue(samples > 400, "the premise: this is an ordinary walk -- " + samples);
        assertTrue(vertices >= 2, "a curve needs two ends -- " + vertices);
        assertTrue(vertices < samples / 4, "the drawn curve is not simplified: " + vertices + " for " + samples);
        assertTrue(vertices < March.MAX_CONES, "a curve may not exceed the buffer on its own");
    }

    /**
     * The probe's samples are what the engine answered and must stay exactly that. The mediants are the
     * renderer's reading of what lies between them, so putting them in the array the probe snaps to would
     * have it quote values as though they had been computed.
     */
    @Test
    @DisplayName("the mediants are drawn but never reported as samples")
    void theProbeStillSeesOnlyRealSamples() {
        Geometry.Built built = built("1÷x", 0, 2, 2);
        int samples = built.samples().length / 3;

        assertEquals(3, samples, "the premise: three samples over this domain at this step");
        assertEquals(built.samples().length, built.curve().length,
                "the probe's two arrays must stay index-parallel, so nothing may be inserted into either");
        assertTrue(heights(built).length > samples,
                "the premise: the drawn path carries more points than the walk answered");
        assertFalse(sampled(built, 2, 1), "a mediant reached the array the probe quotes from");
    }

    /**
     * A sample that lands on the origin breaks the run, which is the curve's half of the answer a single
     * value already gets — {@code T(0,0)} has no direction, and this chart is a circle of directions.
     *
     * <p>{@code x·ω} is the case in its simplest form: it folds at every sample, to {@code T(q=0, p=x)}, so
     * every sample but one stands at a quarter turn — above for {@code x > 0} and below for {@code x < 0} —
     * and exactly one, at {@code x = 0}, is the origin. The break therefore falls in the middle of the
     * domain with placed samples either side of it, which is what makes it a break rather than an end.
     *
     * <p>Asserted on the walk's own samples and on the stroke count, because those are the two things the
     * decline has to reach: the origin must not be <em>reported</em> as a point the engine answered, and the
     * curve must not be <em>drawn</em> straight through the gap where it was declined. A single stroke here
     * would be the renderer joining {@code x = -1} to {@code x = 1} across a value that has no place on the
     * circle, which is precisely the claim the break exists to refuse.
     */
    @Test
    @DisplayName("a sample at the origin breaks the curve rather than being drawn through")
    void theOriginBreaksTheRun() {
        T atZero = Algebra.read("x·ω").term()
                .substitute(java.util.Map.of("x", sibarum.cott.parse.Node.of(0, 1)))
                .fold().literal().orElseThrow();
        assertTrue(atZero.isZeroOmega(), "the premise: x·ω folds to the origin at x = 0");

        Geometry.Built built = built("x·ω", -2, 2, 5);

        assertTrue(sampled(built, -1, 0), "the premise: the sample below the origin is placed");
        assertTrue(sampled(built, 1, 0), "the premise: the sample above the origin is placed");

        // Both of these catch the decline being removed, and they catch different halves of it: without it
        // the walk answers five samples rather than four, and the renderer draws ONE stroke rather than two
        // -- a single line straight across the value with no place on the circle.
        assertEquals(4, built.samples().length / 3, "the origin was reported as a sample the engine answered");
        assertFalse(sampled(built, 0, 0), "the origin reached the array the probe quotes from");
        assertEquals(2, built.strokes().size(),
                "the origin should break the run in two, not be joined across");
    }
}
