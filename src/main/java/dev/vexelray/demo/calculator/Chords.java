package dev.vexelray.demo.calculator;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The fewest chords that stand for a drawn path, within a tolerance.
 *
 * <h2>Why a plot that has just computed its geometry then throws most of it away</h2>
 *
 * <p>The walk answers at every sample and {@link Geometry#mediants} puts a point between every pair of them,
 * so a 420-sample curve arrives here as 840 vertices and leaves the buffer as 840 cones. <b>Measured, that is
 * where the frame goes</b>: a marched frame with the default entry costs 31.7 ms and the same frame with the
 * cones gone — every panel, the whole grid sheet and the labels still drawn — costs 1.39 ms. The march is
 * ~95% of it, and its floor is a scan over groups of cones per step per pixel, so the cone count is very
 * nearly a linear multiplier on the whole cost.
 *
 * <p>And almost none of those cones carry information. Decimated at the tolerance the subdivision already
 * uses, the same curves need <b>25 to 36</b> vertices instead of 840 — {@code 1÷x} needs 25 — because the
 * places a plot bends are few and the places it runs straight are most of it. That is the entire justification
 * for this class: the geometry was never the picture, it was the sampling.
 *
 * <h2>What it does to the mediant claim, which was deliberate</h2>
 *
 * <p>{@code Geometry.between} splits every segment unconditionally at depth zero so that <i>"every drawn
 * segment passes through the mediant of the two samples it joins"</i> — the mediant being a value the type
 * has, where the midpoint of a chord in {@code θ} is generally not. Dropping vertices weakens that to
 * <b>within {@link Geometry#CHORD} of every mediant</b>.
 *
 * <p>Which is the rule the code already ran on, one level down: below the first split, {@code between} stops
 * subdividing exactly when the mediant sits within {@code CHORD} of the straight chord. So this applies one
 * tolerance uniformly rather than introducing one — and it is the same number, so a change to it moves both
 * the subdivision and the decimation together and they cannot come to disagree about how straight is straight.
 * Ruled by James, 2026-09-21, against a tighter tolerance and against keeping every first mediant.
 *
 * <p><b>The probe is untouched.</b> {@code Built.samples} and {@code Built.curve} are the walk's own points and
 * do not come through here; only the drawn path does. Every value the engine answered is still snappable, and
 * the readout still quotes the pair the walk produced, however few cones ended up being marched.
 *
 * <h2>Douglas-Peucker, and why the ordinary one is the right one</h2>
 *
 * <p>Keep the two ends; find the point furthest from the line between them; if it is further than the
 * tolerance, keep it and recurse into both halves. What it guarantees is the property a plot needs — <b>no
 * dropped point is further than the tolerance from the path that is drawn</b> — as opposed to the cheaper
 * sliding-window forms, which bound the error per step and let it accumulate along a run.
 *
 * <p>Iterative rather than recursive, because the depth is the number of kept vertices in the worst case and
 * this runs on a worker thread whose stack is not this class's to spend.
 */
final class Chords {

    /**
     * The path, with every vertex that could be dropped dropped.
     *
     * <p>Interleaved {@code x, y, z} in, the same out. {@code z} rides along untouched — the plot is a plane
     * and every point in it has {@code z = 0}, so the distance below is measured in the two axes that exist.
     *
     * @param path      interleaved {@code x, y, z}, as the mediants left it
     * @param tolerance how far the drawn path may sit from a point it drops, in the box's units
     */
    static double[] of(double[] path, double tolerance) {
        int n = path.length / 3;
        if (n < 3) {
            return path;
        }
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        Deque<int[]> spans = new ArrayDeque<>();
        spans.push(new int[]{0, n - 1});
        while (!spans.isEmpty()) {
            int[] span = spans.pop();
            int from = span[0];
            int to = span[1];
            if (to <= from + 1) {
                continue;
            }
            int at = -1;
            double worst = tolerance;
            for (int i = from + 1; i < to; i++) {
                double d = distance(path, i, from, to);
                if (d > worst) {
                    worst = d;
                    at = i;
                }
            }
            if (at < 0) {
                continue;
            }
            keep[at] = true;
            spans.push(new int[]{from, at});
            spans.push(new int[]{at, to});
        }
        int kept = 0;
        for (boolean b : keep) {
            if (b) {
                kept++;
            }
        }
        if (kept == n) {
            return path;
        }
        double[] out = new double[kept * 3];
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                System.arraycopy(path, i * 3, out, w * 3, 3);
                w++;
            }
        }
        return out;
    }

    /**
     * How far vertex {@code i} stands from the chord between {@code from} and {@code to}.
     *
     * <p>The perpendicular distance, and the degenerate case is not an edge case here: a run can revisit a
     * point — a curve that leaves the window and comes back is clipped to the same edge twice — so a chord of
     * no length has to answer the distance to the point rather than divide by it.
     */
    private static double distance(double[] path, int i, int from, int to) {
        double ax = path[from * 3];
        double ay = path[from * 3 + 1];
        double vx = path[to * 3] - ax;
        double vy = path[to * 3 + 1] - ay;
        double px = path[i * 3] - ax;
        double py = path[i * 3 + 1] - ay;
        double length = Math.hypot(vx, vy);
        if (length < 1e-12) {
            return Math.hypot(px, py);
        }
        return Math.abs(px * vy - py * vx) / length;
    }

    /**
     * How far the simplified path strays from the one it stands for: the worst distance from any point of
     * {@code path} to the nearest point of {@code simplified}.
     *
     * <p>The contract as a number, which is what makes it worth having beside the algorithm rather than in
     * the test: what {@link #of} promises is a bound on exactly this, and a promise nothing computes is a
     * comment. Against every segment rather than against the corresponding one, because a correspondence is
     * what the simplification threw away — and at these sizes the whole comparison is a few tens of
     * thousands of multiplies, run once per scene on a worker.
     */
    static double deviation(double[] path, double[] simplified) {
        int n = path.length / 3;
        int m = simplified.length / 3;
        if (n == 0 || m < 2) {
            return 0;
        }
        double worst = 0;
        for (int i = 0; i < n; i++) {
            double nearest = Double.MAX_VALUE;
            for (int j = 0; j + 1 < m; j++) {
                nearest = Math.min(nearest, toSegment(path[i * 3], path[i * 3 + 1],
                        simplified[j * 3], simplified[j * 3 + 1],
                        simplified[(j + 1) * 3], simplified[(j + 1) * 3 + 1]));
            }
            worst = Math.max(worst, nearest);
        }
        return worst;
    }

    /** Distance from a point to a segment — clamped to the segment, unlike {@link #distance} to its line. */
    private static double toSegment(double px, double py, double ax, double ay, double bx, double by) {
        double vx = bx - ax;
        double vy = by - ay;
        double squared = vx * vx + vy * vy;
        double t = squared < 1e-24 ? 0 : Math.clamp(((px - ax) * vx + (py - ay) * vy) / squared, 0, 1);
        return Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
    }

    private Chords() {
    }
}
