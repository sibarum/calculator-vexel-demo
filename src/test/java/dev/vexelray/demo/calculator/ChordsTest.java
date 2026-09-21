package dev.vexelray.demo.calculator;

import dev.vexelray.surface.Surface;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The simplifier, and the promise it makes to the picture.
 *
 * <p>Two levels, because the thing being claimed is at the second one. The first few tests pin the algorithm;
 * {@link #theDrawnCurveStillPassesThroughEveryAnswer} pins what it means for the plot — <b>every value the
 * engine answered is still on the drawn curve, to within the tolerance the subdivision already used</b>. That
 * is the whole of what was given up for the frame time, so it is the thing that has to be measured rather
 * than argued.
 */
class ChordsTest {

    private static final double TOLERANCE = 0.003;

    @Test
    @DisplayName("a straight run is two chords' worth of nothing, however finely it was sampled")
    void aStraightRunCollapses() {
        double[] line = new double[300];
        for (int i = 0; i < 100; i++) {
            line[i * 3] = -1 + i * 0.02;
            line[i * 3 + 1] = 0.5 * (-1 + i * 0.02);        // exactly on one line
        }

        assertEquals(2, Chords.of(line, TOLERANCE).length / 3, "a straight run kept more than its two ends");
    }

    @Test
    @DisplayName("a corner survives, wherever it is and however alone")
    void aCornerSurvives() {
        double[] path = {-1, 0, 0, 0, 0.5, 0, 1, 0, 0};

        assertEquals(3, Chords.of(path, TOLERANCE).length / 3, "the one point that was not on the chord went");
        assertArrayEquals(path, Chords.of(path, TOLERANCE), 0);
        // And the same corner, under the tolerance, does not.
        assertEquals(2, Chords.of(new double[]{-1, 0, 0, 0, 0.001, 0, 1, 0, 0}, TOLERANCE).length / 3);
    }

    @Test
    @DisplayName("the ends are never dropped — a curve that ends short is a curve that lies")
    void theEndsAreKept() {
        double[] path = new double[150];
        for (int i = 0; i < 50; i++) {
            path[i * 3] = i * 0.04;
            path[i * 3 + 1] = Math.sin(i * 0.3);
        }
        double[] kept = Chords.of(path, TOLERANCE);

        assertEquals(path[0], kept[0], 0);
        assertEquals(path[1], kept[1], 0);
        assertEquals(path[path.length - 3], kept[kept.length - 3], 0);
        assertEquals(path[path.length - 2], kept[kept.length - 2], 0);
    }

    @Test
    @DisplayName("nothing dropped is further than the tolerance from what is drawn")
    void nothingStraysFurtherThanTheTolerance() {
        double[] path = new double[3 * 400];
        for (int i = 0; i < 400; i++) {
            double t = i / 399.0;
            path[i * 3] = -1.8 + 3.6 * t;
            // Something with flats, bends and a near-cusp in it, so the bound is tested where it is tight.
            path[i * 3 + 1] = Math.sin(6 * t) * 0.6 + Math.exp(-40 * (t - 0.5) * (t - 0.5)) * 0.9;
        }
        for (double tolerance : new double[]{0.0003, 0.003, 0.03}) {
            double[] kept = Chords.of(path, tolerance);

            assertTrue(Chords.deviation(path, kept) <= tolerance,
                    "the drawn path strays " + Chords.deviation(path, kept) + " at tolerance " + tolerance);
            assertTrue(kept.length < path.length, "nothing was simplified at tolerance " + tolerance);
        }
    }

    @Test
    @DisplayName("simplifying twice is simplifying once")
    void itSettles() {
        double[] path = new double[3 * 200];
        for (int i = 0; i < 200; i++) {
            path[i * 3] = i * 0.01;
            path[i * 3 + 1] = Math.cos(i * 0.05);
        }
        double[] once = Chords.of(path, TOLERANCE);

        assertArrayEquals(once, Chords.of(once, TOLERANCE), 0);
    }

    @Test
    @DisplayName("a path with nothing to drop comes back as it went in")
    void shortPathsPassThrough() {
        double[] two = {0, 0, 0, 1, 1, 0};

        assertArrayEquals(two, Chords.of(two, TOLERANCE), 0);
        assertArrayEquals(new double[0], Chords.of(new double[0], TOLERANCE), 0);
    }

    @Test
    @DisplayName("the drawn curve still passes through every answer the engine gave")
    void theDrawnCurveStillPassesThroughEveryAnswer() {
        Geometry.Built built = Geometry.of(Algebra.read("1÷x"), -6, 6, 420,
                Geometry.Furniture.DEFAULT, 0.045, Ramp.BLURPLE, Turns.Window.WHOLE);

        // What the march is handed. 840 before this existed -- a mediant between every pair of 420 samples.
        int cones = 0;
        for (Surface.Stroke stroke : built.strokes()) {
            cones += Math.max(0, stroke.through().size() - 1);
        }
        assertTrue(cones < 60, "the default entry still arrives as " + cones + " cones");

        // And the promise: Built.curve is the walk's own samples, placed and NOT simplified -- the array the
        // probe snaps to. Every one of them is still on the drawn curve.
        double[] drawn = drawn(built);
        assertTrue(Chords.deviation(built.curve(), drawn) <= TOLERANCE + 1e-9,
                "a sampled value stands " + Chords.deviation(built.curve(), drawn) + " off the drawn curve");
    }

    /** Every stroke's vertices, end to end, as the simplifier left them. */
    private static double[] drawn(Geometry.Built built) {
        int n = 0;
        for (Surface.Stroke stroke : built.strokes()) {
            n += stroke.through().size();
        }
        double[] out = new double[n * 3];
        int at = 0;
        for (Surface.Stroke stroke : built.strokes()) {
            for (Surface.Stroke.Vertex v : stroke.through()) {
                out[at++] = v.x();
                out[at++] = v.y();
                out[at++] = v.z();
            }
        }
        return out;
    }
}
