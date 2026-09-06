package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.surface.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * The scene, as strokes: sampled points in, {@link Surface.Stroke}s out.
 *
 * <p>Pure, and pure on purpose — this is the expensive half of the renderer and it runs on a worker. Nothing
 * about the camera reaches it, which is the property that makes an orbit cost six floats.
 *
 * <h2>A stroke is the right node, not a polyline substitute</h2>
 *
 * <p>{@link Surface.Stroke} carries per-vertex radius, per-vertex curvature and <b>per-vertex colour that
 * gradients between neighbours</b>. So the width control and the colour ramp are properties of the geometry
 * rather than things a renderer has to apply afterwards, and the ramp is smooth along the curve for free.
 *
 * <p>Its guarantee is the one a plot needs: <em>every vertex named lies on the centre line of the rendered
 * shape, at every curvature</em>, because the corner control point is solved for rather than used as a Bézier
 * handle. A rounded polyline that merely passes <em>near</em> its vertices would put the curve somewhere other
 * than where the samples say it is, which is the one thing a plot may not do.
 *
 * <h2>Joints are sharp, and that is a cost decision</h2>
 *
 * <p>A stroke costs about {@code vertices × (1 + segmentsPerCorner)} cones, and corners at zero curvature emit
 * none of them. Consecutive samples on a dense curve are close together, so the tapered round cone between two
 * neighbours already reads as a smooth tube: sharp joints make a 500-sample curve 500 cones instead of 4,500,
 * and nothing about the picture is worse for it.
 */
final class Geometry {

    /** Half-extent of the world box the plot is built into, along x. */
    private static final double BOX = 1.8;

    /** And along the two output axes. Flatter than it is wide, which is what a curve in a box looks like. */
    private static final double BOX_H = 1.0;

    /**
     * The curve's radius in <b>world units</b>.
     *
     * <p>The prototype's Width control is in pixels, and a marched tube has no pixels — its apparent thickness
     * is a consequence of the camera. So the control maps onto a world radius, and "3px" becomes a number that
     * looks like the design at the default zoom rather than a promise about screen measurement. Worth knowing
     * before the Width slider is wired: it will not be a pixel width and should not claim to be.
     */
    private static final double RADIUS = 0.035;

    /**
     * The scene for a reading.
     *
     * @param samples how many points to sample the curve at; the caller clamps this against the buffer ceiling
     */
    static List<Surface.Stroke> of(Canned.Reading reading, double omega, double x0, double x1, int samples) {
        double[] xyz = Canned.curve(reading, omega, x0, x1, samples);
        return List.of(curve(xyz, x0, x1));
    }

    /**
     * One stroke through the sampled points, coloured along its length.
     *
     * <p><b>Colour is all-or-nothing on a stroke</b> — the record refuses a mix, because a gradient between a
     * colour and an absence has no meaning — so every vertex is painted here rather than only the ones that
     * matter.
     */
    private static Surface.Stroke curve(double[] xyz, double x0, double x1) {
        int n = xyz.length / 3;
        List<Surface.Stroke.Vertex> vs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0 : i / (double) (n - 1);
            double x = map(xyz[i * 3], x0, x1, -BOX, BOX);
            double y = xyz[i * 3 + 1] * BOX_H;
            double z = xyz[i * 3 + 2] * BOX_H;
            // Sharp: see the class note. The two ends have nothing to turn through, so their curvature is
            // ignored either way.
            vs.add(new Surface.Stroke.Vertex(x, y, z, RADIUS, 0).painted(ramp(t)));
        }
        // segmentsPerCorner must be even and at least 2 even when every corner is sharp -- the record validates
        // it regardless of whether any corner will use it.
        return new Surface.Stroke(vs, 2);
    }

    /**
     * The colour ramp along the curve: the accent, walked from its surface tint to its brightest.
     *
     * <p>Interpolated in Oklab rather than in sRGB, which is the rule that comes with the framework's colour
     * type: a blend through raw sRGB passes through a muddy middle, and a ramp is exactly where that shows.
     */
    private static Surface.Rgb ramp(double t) {
        Color c = Oklab.of(Look.ACCENT_SURFACE.of(Look.PALETTE))
                .mix(Oklab.of(Look.ACCENT_BRIGHT.of(Look.PALETTE)), t)
                .toColor();
        var rgb = Look.scene(c);
        return new Surface.Rgb(rgb.r(), rgb.g(), rgb.b());
    }

    private static double map(double v, double lo, double hi, double toLo, double toHi) {
        double t = hi == lo ? 0 : (v - lo) / (hi - lo);
        return toLo + (toHi - toLo) * t;
    }

    private Geometry() {
    }
}
