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
 * <h2>The furniture is not here at all any more</h2>
 *
 * <p>The grid planes, the axes and their ticks were all strokes in the same union as the curve, and the
 * argument for it was a good one: <b>occlusion is correct without being managed</b>, because an axis behind the
 * curve is behind it by the ray hitting the curve first. No depth sort, no painter's order.
 *
 * <p>They are drawings on panels now ({@link Grid}), and the property survives the move — a panel tests the
 * depth the march writes, so the occlusion is still per pixel and still not managed by anything here. What the
 * move bought is that a mark is a <em>shape</em> rather than a tube: a grid line at the radius these wanted is
 * thinner than the march's own hit threshold, so it was found by the threshold rather than by its surface,
 * which is a dashed line at any resolution.
 *
 * <p>So what is left in the field is the curve and, in point mode, the marker — <b>the things the plot is of,
 * and nothing of the frame around them</b>. That is also what let the scene go back to one shading model: with
 * no furniture in the union there is nothing to tell apart from the curve, and the whole of {@code Lighting}
 * went with it.
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

    /** Half the length of one arm of the marker that shows where a single value lands. */
    private static final double MARKER_ARM = 0.1;

    /**
     * What a scene is made of, beyond the curve.
     *
     * <p>{@code ticks} and {@code labels} are the two annotations on the axes, and they are separate because
     * they answer different questions: the ticks say <em>where</em> along an axis a reader is, and the labels
     * say <em>which axis it is</em>. A plot that has been read once wants the second without the first, which
     * is the whole reason the pair is two flags rather than one.
     *
     * <p>Neither means anything with the axes switched off, and {@link Scene#effectiveFurniture()} is where
     * that is enforced rather than here: this record is what was <em>chosen</em>, and a card being off must
     * not forget the choices inside it.
     *
     * @param axes      whether the three axis lines are drawn at all
     * @param ticks     graduations along the axes, and the numbers beside them
     * @param labels    the axis names — {@code Re · Tr · Tr'} — drawn off the end of each axis
     * @param divisions squares across a grid plane
     */
    record Furniture(boolean axes, boolean ticks, boolean labels,
                     boolean gridXY, boolean gridXZ, boolean gridYZ, int divisions) {

        /**
         * All three planes, where it used to be the floor alone.
         *
         * <p>One face-mounted plane was as much as the marched grid could carry: three opaque cages around a
         * curve is a curve in a box, and the two the design left off were off for that reason. Drawn planes
         * through the origin are the coordinate system rather than a container — they are mostly transparent,
         * they are read through, and the curve passes visibly between them — so the three that were a cage are
         * the three that say where a point is.
         */
        static final Furniture DEFAULT = new Furniture(true, true, true, true, true, true, 6);

        // Named one-field changes, for the reason Scene.Draft gives at length: a seven-component record has a
        // seven-argument constructor, and a canonical call spelled out at every call site is how a field ends
        // up in the wrong slot. Five of these components are booleans standing in a row, so the compiler cannot
        // catch a swap and the picture that comes back is merely wrong rather than broken. The positional call
        // appears twice below and nowhere else.

        Furniture withAxes(boolean v) {
            return new Furniture(v, ticks, labels, gridXY, gridXZ, gridYZ, divisions);
        }

        Furniture withTicks(boolean v) {
            return new Furniture(axes, v, labels, gridXY, gridXZ, gridYZ, divisions);
        }

        Furniture withLabels(boolean v) {
            return new Furniture(axes, ticks, v, gridXY, gridXZ, gridYZ, divisions);
        }

        Furniture withGridXY(boolean v) {
            return new Furniture(axes, ticks, labels, v, gridXZ, gridYZ, divisions);
        }

        Furniture withGridXZ(boolean v) {
            return new Furniture(axes, ticks, labels, gridXY, v, gridYZ, divisions);
        }

        Furniture withGridYZ(boolean v) {
            return new Furniture(axes, ticks, labels, gridXY, gridXZ, v, divisions);
        }

        Furniture withDivisions(int v) {
            return new Furniture(axes, ticks, labels, gridXY, gridXZ, gridYZ, v);
        }
    }

    /**
     * A built scene: what to march, and where the curve's samples ended up.
     *
     * <p>The points come back because two things want them and neither should re-derive them. The march wants
     * strokes; the {@link Probe} wants to snap to a sample, in the same world coordinates the picture was built
     * in. Recomputing the mapping in the probe would be three lines and one refactor away from disagreeing with
     * the plot it is pointing at.
     *
     * <p>The furniture travels with them for the parts of it that are no longer strokes. The three grid planes are
     * drawings on panels rather than geometry (see {@link Grid}), so what used to leave here as cones now has
     * to leave as the numbers to draw them from — and it leaves from here, rather than being read off the Scene a
     * second time, so that the picture and the grid under it can never be built from two different versions.
     *
     * @param strokes   what goes in the buffer
     * @param curve     the curve's points, interleaved xyz, already mapped into the world box
     * @param furniture what the scene asked for, for the parts of it that are not strokes
     */
    record Built(List<Surface.Stroke> strokes, double[] curve, Furniture furniture, Marks marks) {
    }

    /**
     * Where the graduations fall along each axis, in the box's coordinates — {@code -BOX..BOX} for the domain
     * and {@code -BOX_H..BOX_H} for the outputs.
     *
     * <p>One list for the outputs rather than two, because there is one: a traction coordinate and its
     * reflection share a scale, so the same marks go on both output axes. Two lists that happened to be equal
     * would be two chances for them to stop being.
     *
     * <p><b>The values travel with the positions.</b> All three axes are graduated at the same values — they
     * are three coordinates of one value space sharing one {@link #span} — so there is a single {@code values}
     * array, and {@code domain[i]} and {@code output[i]} are both where {@code values[i]} falls, on an axis
     * scaled to {@code BOX} and to {@code BOX_H} respectively. Carrying them rather than letting the overlay
     * re-derive them is the same rule this class applies to the mapping itself: {@link Labels} writes the
     * number beside a mark, and a number computed from anything but the mark's own value is a number for a
     * different axis. It used to be exactly that — the input domain, from before the input stopped being an
     * axis at all.
     *
     * @param values what each mark stands for, ascending, zero excluded
     * @param step   the 1-2-5 step those values came out at, so every number on every axis is written to the
     *               same number of decimals
     */
    record Marks(double[] domain, double[] output, double[] values, double step) {

        static final Marks NONE = new Marks(new double[0], new double[0], new double[0], 1);
    }

    /**
     * The whole scene for a reading.
     *
     * <h2>The three axes are the same in every mode, and that is the point</h2>
     *
     * <p>They are the value's own coordinates, {@code (Re, Tr, Tr')}. A curve no longer spends one of them on
     * its input: the input is walked over and each sample contributes only where its value landed, so a curve
     * and a marker are drawn in one space and a reader compares them directly. See {@code Algebra.walk}.
     *
     * @param samples the initial step count for the walk; the caller clamps this against the buffer ceiling
     */
    static Built of(Algebra.Reading reading, double x0, double x1, int samples,
                    Furniture furniture, double radius, Ramp ramp) {
        List<double[]> runs = Algebra.walk(reading, x0, x1, samples);
        double span = span(runs, reading);

        // The axes and their ticks are not built here any more -- they are drawn on the panels, like the grid,
        // so what this contributes to them is where their graduations go. What is left in the field is the
        // curve and, in point mode, the marker: the things the plot is OF rather than the frame around it.
        Marks marks = furniture.axes() ? marks(furniture.ticks(), span) : Marks.NONE;

        List<Surface.Stroke> scene = new ArrayList<>();
        List<double[]> placed = new ArrayList<>(runs.size());
        for (double[] run : runs) {
            // A run of one is an isolated answer, and it gets a marker for the reason a single value does:
            // a stroke through one point draws nothing, and the value is no less real for having no
            // neighbour. It is passed unmapped, because marker does its own mapping.
            if (run.length == 3) {
                marker(scene, run, span, radius, ramp);
            }
            placed.add(world(run, span));
        }
        // One stroke per run, so a break in the curve is a break in the picture. See Algebra.walk.
        for (double[] run : placed) {
            if (run.length >= 6) {
                scene.add(curve(run, radius, ramp));
            }
        }
        if (reading.drawsMarker()) {
            marker(scene, reading.place().toDoubles(), span, radius, ramp);
        }
        return new Built(List.copyOf(scene), joined(placed), furniture, marks);
    }

    /**
     * Half the extent the output axes have to cover, shared between them.
     *
     * <p><b>One scale for both, not one each.</b> The two output axes are two coordinates of the same value
     * space and the chart's symmetries are its point — {@code -a} is a reflection, {@code 1/a} is another —
     * so scaling them independently would stretch the square the theory is drawn on into a rectangle and the
     * reflections would stop looking like reflections.
     *
     * <p>Never zero: a value sitting at the origin of both output axes would otherwise divide by it, and a
     * curve along {@code Re = Tr = 0} is a legitimate thing to ask for.
     */
    private static double span(List<double[]> runs, Algebra.Reading reading) {
        double most = 0;
        for (double[] run : runs) {
            for (double c : run) {
                most = Math.max(most, Math.abs(c));
            }
        }
        if (reading.drawsMarker()) {
            for (double c : reading.place().toDoubles()) {
                most = Math.max(most, Math.abs(c));
            }
        }
        return most > 0 ? most : 1;
    }

    /**
     * The sampled points mapped into the world box, which is where everything else reads them.
     *
     * <p>All three axes are mapped from {@link #span}, because all three are outputs now and none of them is
     * bounded: a coordinate on the traction axis is an order of vanishing and {@code 0^-6} is six units out.
     * The fake's outputs were a sine and a cosine and could be scaled by a constant; these cannot, and a
     * constant would have put most curves outside the box.
     *
     * <p>Exactly the mapping {@link #marker} uses, and that is load-bearing rather than a coincidence: a
     * curve and a marker are the same three coordinates, so a curve through a value's place must land on the
     * marker for that value.
     */
    private static double[] world(double[] xyz, double span) {
        double[] out = new double[xyz.length];
        for (int i = 0; i < xyz.length / 3; i++) {
            out[i * 3] = map(xyz[i * 3], -span, span, -BOX, BOX);
            out[i * 3 + 1] = xyz[i * 3 + 1] / span * BOX_H;
            out[i * 3 + 2] = xyz[i * 3 + 2] / span * BOX_H;
        }
        return out;
    }

    /** Every run end to end, for the {@link Probe}, which wants samples rather than strokes. */
    private static double[] joined(List<double[]> runs) {
        int n = 0;
        for (double[] run : runs) {
            n += run.length;
        }
        double[] out = new double[n];
        int at = 0;
        for (double[] run : runs) {
            System.arraycopy(run, 0, out, at, run.length);
            at += run.length;
        }
        return out;
    }

    /**
     * Where a single value lands: three short segments crossing at the point.
     *
     * <p>A cross rather than a blob, because the whole content of this mode is <em>which coordinates</em> the
     * value has, and a cross states them — each arm runs along the axis it is a reading of, so the point can
     * be read off the axes by eye instead of being judged by eye against a sphere.
     *
     * <p>Coordinates fill the axes in order and a missing third is the origin of its axis, which is what a
     * two-coordinate value is: it has no third reading, so it sits on the plane where the third is nothing.
     */
    private static void marker(List<Surface.Stroke> into, double[] at, double span, double radius, Ramp ramp) {
        double x = map(coordinate(at, 0), -span, span, -BOX, BOX);
        double y = coordinate(at, 1) / span * BOX_H;
        double z = coordinate(at, 2) / span * BOX_H;
        double arm = MARKER_ARM;
        Surface.Rgb colour = ramp.scene(1);
        into.add(line(x - arm * (BOX / BOX_H), y, z, x + arm * (BOX / BOX_H), y, z, radius, colour));
        into.add(line(x, y - arm, z, x, y + arm, z, radius, colour));
        into.add(line(x, y, z - arm, x, y, z + arm, radius, colour));
    }

    private static double coordinate(double[] at, int i) {
        return i < at.length ? at[i] : 0;
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
     * Where the graduations fall, in the box's own coordinates, and what they stand for.
     *
     * <p>The axes and their ticks are drawn on panels now ({@link Grid}) rather than marched, so what used to
     * leave this class as strokes leaves it as positions. They are computed here and not there for the reason
     * {@link Built} gives about the furniture generally: the mapping from a value to a place in the box is this
     * class's, and a second implementation of it in the thing that draws the marks would be three lines and one
     * refactor away from disagreeing with the curve they are meant to measure. {@link Labels} reads the values
     * back off the result for the same reason, and that is new — it used to compute its own.
     *
     * <h2>One range for all three axes, because the input is not one of them</h2>
     *
     * <p>This took the domain as two arguments when a curve was plotted <em>against</em> its input and the
     * first axis was that input. It is not, since the algebra landed: all three axes are coordinates of the
     * value, so all three are ticked over the range the values actually reached — {@code -span..span} — and
     * the only difference between them is that the first is scaled to {@link #BOX} and the other two to
     * {@link #BOX_H}. The box is an internal frame and its coordinates are never shown, so a mark at "1"
     * meaning the edge of the picture would be a mark measuring nothing.
     */
    private static Marks marks(boolean ticks, double span) {
        if (!ticks) {
            return Marks.NONE;
        }
        double[] values = Ticks.between(-span, span);
        double[] domain = new double[values.length];
        double[] output = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            domain[i] = map(values[i], -span, span, -BOX, BOX);
            output[i] = values[i] / span * BOX_H;
        }
        return new Marks(domain, output, values, Ticks.step(2 * span, Ticks.TARGET));
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
