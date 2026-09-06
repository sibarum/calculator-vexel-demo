package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.surface.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * The scene, as strokes: sampled points and a few settings in, {@link Surface.Stroke}s out.
 *
 * <p>Pure, and pure on purpose — this is the expensive half of the renderer and it runs on a worker. Nothing
 * about the camera reaches it, which is the property that makes an orbit cost six floats.
 *
 * <p><b>The curve's radius is in world units, not pixels.</b> The prototype's Width control says "px" and a
 * marched tube has none — its apparent thickness is a consequence of the camera. So the control maps onto a
 * world radius that looks like the design at the default framing, and it is not a promise about screen
 * measurement.
 *
 * <h2>The per-vertex colour here is currently inert, and deliberately kept</h2>
 *
 * <p><b>{@code ConeField} carries no colour.</b> A cone in the buffer is eight floats of geometry and
 * {@code compose} passes no albedo function, so every hit is shaded with the scene's single albedo and the
 * {@code .painted(...)} calls below reach nothing. They are left in because they are correct, cost one call
 * each, and the day the buffer carries colour the whole plot lights up with no change here. The colour that
 * <em>is</em> visible today is set in {@link March#recolour}. See {@code docs/framework-notes.md} FN-25.
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
 * <h2>The furniture is geometry too, and that is the payoff</h2>
 *
 * <p>The axes, the ticks and the grid planes are strokes in the same union as the curve, so they are marched
 * together and <b>occlusion is correct without being managed</b>: a grid line behind the curve is behind it
 * because the ray hit the curve first. No depth sort, no painter's order, no per-cell bounding box. A drawn
 * plot has to solve that; a marched one never has it.
 *
 * <h2>Joints are sharp, and that is a cost decision</h2>
 *
 * <p>A stroke costs about {@code vertices × (1 + segmentsPerCorner)} cones, and corners at zero curvature emit
 * none of them. Consecutive samples on a dense curve are close together, so the tapered round cone between two
 * neighbours already reads as a smooth tube: sharp joints make a 500-sample curve 500 cones instead of 4,500,
 * and nothing about the picture is worse for it.
 */
final class Geometry {

    /** Half-extent of the world box the plot is built into, along the input axis. */
    static final double BOX = 1.8;

    /** And along the two output axes. Flatter than it is wide, which is what a curve in a box looks like. */
    static final double BOX_H = 1.0;

    /**
     * The curve's radius in <b>world units</b>.
     *
     * <p>The prototype's Width control is in pixels, and a marched tube has no pixels — its apparent thickness
     * is a consequence of the camera. So the control maps onto a world radius, and "3px" becomes a number that
     * looks like the design at the default framing rather than a promise about screen measurement. Worth
     * knowing before the Width slider is wired: it will not be a pixel width and should not claim to be.
     */

    /** An axis is thinner than the curve and thicker than the grid, which is the whole of its visual job. */
    private static final double AXIS_RADIUS = 0.014;

    /**
     * A grid line.
     *
     * <p>Not much thinner than an axis, and it cannot usefully be: the march's hit threshold is
     * {@code hitEpsilon + hitEpsilonSlope × distance}, about {@code 0.015} at seven units, so a tube much below
     * that is found by the <em>threshold</em> rather than by its own surface and stops getting thinner. Grid
     * lines are separated from axes by colour instead, which is what the prototype does anyway.
     */
    private static final double GRID_RADIUS = 0.008;

    /** How far a tick sticks out from its axis. */
    private static final double TICK = 0.06;

    /** What a scene is made of, beyond the curve. */
    record Furniture(boolean axes, boolean ticks, boolean gridXY, boolean gridXZ, boolean gridYZ, int divisions) {

        static final Furniture DEFAULT = new Furniture(true, true, false, true, false, 6);
    }

    /**
     * A built scene: what to march, and where the curve's samples ended up.
     *
     * <p>The points come back because two things want them and neither should re-derive them. The march wants
     * strokes; the {@link Probe} wants to snap to a sample, in the same world coordinates the picture was built
     * in. Recomputing the mapping in the probe would be three lines and one refactor away from disagreeing with
     * the plot it is pointing at.
     *
     * @param strokes what goes in the buffer
     * @param curve   the curve's points, interleaved xyz, already mapped into the world box
     */
    record Built(List<Surface.Stroke> strokes, double[] curve) {
    }

    /**
     * The whole scene for a reading.
     *
     * @param samples how many points to sample the curve at; the caller clamps this against the buffer ceiling
     */
    static Built of(Canned.Reading reading, double omega, double x0, double x1, int samples,
                    Furniture furniture, double radius, Ramp ramp) {
        List<Surface.Stroke> scene = new ArrayList<>();
        grid(scene, furniture);
        if (furniture.axes()) {
            axes(scene, furniture.ticks(), x0, x1);
        }
        double[] world = world(Canned.curve(reading, omega, x0, x1, samples), x0, x1);
        scene.add(curve(world, radius, ramp));
        return new Built(List.copyOf(scene), world);
    }

    /** The sampled points mapped from the domain into the world box, which is where everything else reads them. */
    private static double[] world(double[] xyz, double x0, double x1) {
        double[] out = new double[xyz.length];
        for (int i = 0; i < xyz.length / 3; i++) {
            out[i * 3] = map(xyz[i * 3], x0, x1, -BOX, BOX);
            out[i * 3 + 1] = xyz[i * 3 + 1] * BOX_H;
            out[i * 3 + 2] = xyz[i * 3 + 2] * BOX_H;
        }
        return out;
    }

    // ------------------------------------------------------------------ curve

    /**
     * One stroke through the sampled points, coloured along its length.
     *
     * <p><b>Colour is all-or-nothing on a stroke</b> — the record refuses a mix, because a gradient between a
     * colour and an absence has no meaning — so every vertex is painted here rather than only the ones that
     * matter.
     */
    private static Surface.Stroke curve(double[] world, double radius, Ramp ramp) {
        int n = world.length / 3;
        List<Surface.Stroke.Vertex> vs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double t = n == 1 ? 0 : i / (double) (n - 1);
            double x = world[i * 3];
            double y = world[i * 3 + 1];
            double z = world[i * 3 + 2];
            // Sharp: see the class note. The two ends have nothing to turn through, so their curvature is
            // ignored either way.
            vs.add(new Surface.Stroke.Vertex(x, y, z, radius, 0).painted(ramp.scene(t)));
        }
        // segmentsPerCorner must be even and at least 2 even when every corner is sharp -- the record validates
        // it regardless of whether any corner will use it.
        return new Surface.Stroke(vs, 2);
    }

    // The colour ramp moved to Ramp, because the COLOR panel picks between four of them and a ramp is now a
    // value rather than a function. Its Oklab interpolation and the reason for it went with it.

    // -------------------------------------------------------------- furniture

    /**
     * Three axes through the origin, each reaching the faces of the box, with ticks along them.
     *
     * <p>Tick positions come from {@link Ticks}, which is also what {@link Labels} reads -- so a mark and the
     * number beside it cannot describe different places. Two lists of positions that have to agree is exactly
     * the kind of duplication that survives review and then drifts one refactor later.
     */
    private static void axes(List<Surface.Stroke> into, boolean ticks, double domainLo, double domainHi) {
        Surface.Rgb colour = colour(Look.QUIET);
        into.add(line(-BOX, 0, 0, BOX, 0, 0, AXIS_RADIUS, colour));
        into.add(line(0, -BOX_H, 0, 0, BOX_H, 0, AXIS_RADIUS, colour));
        into.add(line(0, 0, -BOX_H, 0, 0, BOX_H, AXIS_RADIUS, colour));
        if (!ticks) {
            return;
        }
        for (double v : Ticks.between(domainLo, domainHi)) {
            double at = map(v, domainLo, domainHi, -BOX, BOX);
            into.add(line(at, -TICK, 0, at, TICK, 0, GRID_RADIUS, colour));
        }
        // The output axes are the box, not the domain, so they are ticked over their own range.
        for (double v : Ticks.between(-1, 1)) {
            double at = v * BOX_H;
            into.add(line(-TICK, at, 0, TICK, at, 0, GRID_RADIUS, colour));
            into.add(line(0, -TICK, at, 0, TICK, at, GRID_RADIUS, colour));
        }
    }

    /**
     * Grid planes on the far faces of the box.
     *
     * <p>On the faces rather than through the origin, because a plane through the middle of the volume is
     * something the curve has to be read <em>through</em>, and one on the back wall is something it is read
     * <em>against</em>. Which face is "far" does not change with the camera here — that would be a per-frame
     * decision, and the geometry is deliberately camera-independent.
     */
    private static void grid(List<Surface.Stroke> into, Furniture f) {
        Surface.Rgb colour = colour(p -> p.surface(1));
        int n = Math.max(2, f.divisions());
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            double x = -BOX + 2 * BOX * t;
            double y = -BOX_H + 2 * BOX_H * t;
            double z = -BOX_H + 2 * BOX_H * t;
            if (f.gridXY()) {          // the back wall: constant z
                into.add(line(x, -BOX_H, -BOX_H, x, BOX_H, -BOX_H, GRID_RADIUS, colour));
                into.add(line(-BOX, y, -BOX_H, BOX, y, -BOX_H, GRID_RADIUS, colour));
            }
            if (f.gridXZ()) {          // the floor: constant y
                into.add(line(x, -BOX_H, -BOX_H, x, -BOX_H, BOX_H, GRID_RADIUS, colour));
                into.add(line(-BOX, -BOX_H, z, BOX, -BOX_H, z, GRID_RADIUS, colour));
            }
            if (f.gridYZ()) {          // the side wall: constant x
                into.add(line(-BOX, y, -BOX_H, -BOX, y, BOX_H, GRID_RADIUS, colour));
                into.add(line(-BOX, -BOX_H, z, -BOX, BOX_H, z, GRID_RADIUS, colour));
            }
        }
    }

    // ----------------------------------------------------------------- shared

    private static Surface.Stroke line(double ax, double ay, double az,
                                       double bx, double by, double bz, double radius, Surface.Rgb colour) {
        return new Surface.Stroke(List.of(
                new Surface.Stroke.Vertex(ax, ay, az, radius, 0).painted(colour),
                new Surface.Stroke.Vertex(bx, by, bz, radius, 0).painted(colour)), 2);
    }

    private static Surface.Rgb colour(Role role) {
        var rgb = Look.scene(role);
        return new Surface.Rgb(rgb.r(), rgb.g(), rgb.b());
    }

    private static double map(double v, double lo, double hi, double toLo, double toHi) {
        double t = hi == lo ? 0 : (v - lo) / (hi - lo);
        return toLo + (toHi - toLo) * t;
    }

    private Geometry() {
    }
}
