package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.surface.Surface;

import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * Half-extent of the world square the plane is drawn into, on both axes.
     *
     * <p><b>One number, where there were two.</b> The box used to be wider than it was tall, on the argument
     * that a curve in a box looks like that — true of a graph plotted against its input, and false here. The
     * two axes are {@code q} and {@code p} of one point {@code q + pi}, and the plot's whole content is where
     * that point stands: the angle {@code θ = arg(q + pi)} is the value's own reading, and a box that scaled
     * the axes differently would shear every angle in the picture. A quarter turn would not look like one.
     *
     * <p>It is the number the wider of the two axes had, so the plane is as wide as the box was and the picture
     * did not shrink on becoming square.
     */
    static final double BOX = 1.8;

    /**
     * How far up the box a half turn reaches: the vertical axis runs {@code -π..π} onto {@code -BOX..BOX}.
     *
     * <p><b>The vertical axis is the turn, and it is bounded</b> — which is the property everything else here
     * rests on. A value is a direction, so there is no value further up than half a turn and none that can
     * run off the top. No expression needs its range fitted, cropped or rescaled, ever; the axis that used to
     * be unbounded cannot be.
     */
    static final double TURN = Math.PI;

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
     * @param axes      whether the two axis lines are drawn at all
     * @param ticks     graduations along the axes, and the numbers beside them
     * @param labels    the axis names — {@code q · p} — drawn off the end of each axis
     * @param divisions squares across a grid plane
     */
    record Furniture(boolean axes, boolean ticks, boolean labels,
                     boolean gridXY, boolean gridXZ, boolean gridYZ, int divisions) {

        /**
         * The axes, the circle and the named turns — and no grid plane at all.
         *
         * <p>All three planes were on when a value needed three axes. Two of them now stand out of the plane
         * the plot is in and cross the picture at right angles to everything in it. The third, the one the
         * plot <em>is</em> in, is a worse problem than the other two: a square grid at six divisions is the
         * picture of a scale, and this chart has none. Every value is on one circle at its own turn, so a
         * reader counting squares out from the origin would be counting something that is not there.
         *
         * <p>All three flags are kept and the LAYERS panel still offers them — a layer a user can switch on is
         * not the same thing as a layer the model needs, and the plane behind the circle is a fair thing to
         * want to see. It is the default that changed.
         */
        static final Furniture DEFAULT = new Furniture(true, true, true, false, false, false, 6);

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
     * <p><b>The samples travel twice, in two spaces, and that is not a duplication.</b> {@code curve} is where
     * the march and the probe's projection need them — the world box — and {@code samples} is what the probe
     * has to <em>say</em>: the parameter it was walked at and the pair it landed on, in the model's own
     * numbers. The probe used to quote the world box's coordinates, which are an internal frame nobody should
     * ever see, and interpolate the parameter from a sample's index across the joined runs, which is wrong
     * wherever a run broke. Both go away by carrying what the walk already knew.
     *
     * @param strokes   what goes in the buffer
     * @param curve     the curve's points, interleaved xyz, already mapped into the world box
     * @param samples   the same points as the walk gave them, interleaved {@code x, q, p}, unmapped
     * @param furniture what the scene asked for, for the parts of it that are not strokes
     */
    record Built(List<Surface.Stroke> strokes, double[] curve, double[] samples,
                 Furniture furniture, Marks marks) {
    }

    /**
     * The graduations, which are two different kinds of thing because the two axes are.
     *
     * <h2>Round numbers across, named turns up</h2>
     *
     * <p>The horizontal axis is the input: an ordinary real interval, so it is graduated the ordinary way, at
     * round numbers chosen 1-2-5 by {@link Ticks} over whatever domain is being walked.
     *
     * <p>The vertical axis is the value's turn, and <b>it is graduated by name rather than by number</b>. It
     * is bounded at a half turn either way and it is the same axis for every expression, so there is nothing
     * to fit and no scale to choose — what a reader needs marked is the turns that mean something, and the
     * model names exactly eight of them at the eighth turns. The names come from {@link Algebra#NAMED}, which
     * is also what the field accepts, so <b>every label on the vertical axis is an entry that can be typed
     * back in</b>.
     *
     * <p>Both families carry their positions in the box's coordinates beside what they stand for, so
     * {@link Labels} writes a number where {@link Grid} drew its mark and neither recomputes the other's
     * mapping.
     *
     * @param across    where each input mark falls, {@code -BOX..BOX}
     * @param inputs    what each of those stands for, in the walked domain
     * @param step      the 1-2-5 step the inputs came out at, so they are written to a common precision
     * @param up        where each named turn falls, {@code -BOX..BOX}
     * @param turns     what each of those is called
     */
    record Marks(double[] across, double[] inputs, double step, double[] up, String[] turns) {

        static final Marks NONE =
                new Marks(new double[0], new double[0], 1, new double[0], new String[0]);
    }

    /**
     * The whole scene for a reading.
     *
     * <h2>The input is an axis, and the other axis is the turn</h2>
     *
     * <p>A value is {@code T(p,q)} and what it <em>is</em> is the direction of {@code q + pi} — the distance
     * of a pair from the origin is which representative got written rather than anything about the value. So
     * the value contributes <b>one</b> number to the picture, its turn, and the second axis is free for the
     * thing a graph is of: the input it was walked at.
     *
     * <p><b>Both of the pictures before this one are corrections behind it, and each was visible on screen.</b>
     * Drawing the pair itself put every simple entry on a straight line — {@code x}, {@code x+1} and
     * {@code x·x} vertical, {@code 1÷x} horizontal — because a result's denominator depends only on its
     * operands' denominators, so a polynomial in a sampled name has a constant {@code q}; the line was the
     * denominator and not the function. Drawing only the direction fixed that and lost something worse: with
     * the input nowhere, <em>every</em> expression is an arc of one circle, and {@code 2·x^2} came out a
     * semicircle, which is not what a parabola looks like under any reading.
     *
     * <p>So: <b>{@code x} across, {@code θ} up.</b> What the circle bought is kept, because the vertical axis
     * <em>is</em> that circle, cut at the half turn and stood on end:
     *
     * <ul>
     *   <li>the eight named points are eight heights, so {@code 0} and {@code _0}, {@code 1} and {@code -_1}
     *       are still told apart — the orientation survives;
     *   <li>the axis is bounded at {@code ±π} whatever the expression, so nothing is ever cropped, rescaled or
     *       fitted, and two expressions are compared against the same marks;
     *   <li>{@code 1÷x} is one continuous falling curve through {@code ω} at the origin, where an ordinary
     *       plot has two branches and an asymptote. That is the model's whole claim, drawn.
     * </ul>
     *
     * <p>A run from the walk is interleaved {@code x, q, p}: the first is now the horizontal position and the
     * other two are read as one turn.
     *
     * @param samples the initial step count for the walk; the caller clamps this against the buffer ceiling
     */
    static Built of(Algebra.Reading reading, double x0, double x1, int samples,
                    Furniture furniture, double radius, Ramp ramp) {
        List<double[]> runs = Algebra.walk(reading, x0, x1, samples);

        // The axes and their graduations are drawn on the panels, like the grid, so what this contributes to
        // them is where the marks go. What is left in the field is the curve and, in point mode, the rule:
        // the things the plot is OF rather than the frame around it.
        Marks marks = furniture.axes() && furniture.ticks() ? marks(x0, x1) : Marks.NONE;

        List<Surface.Stroke> scene = new ArrayList<>();
        List<double[]> placed = new ArrayList<>(runs.size());
        for (double[] run : runs) {
            // A run of one is an isolated answer, and it gets a marker for the reason a single value does:
            // a stroke through one point draws nothing, and the value is no less real for having no
            // neighbour.
            if (run.length == 3) {
                marker(scene, at(run[0], x0, x1), turn(run[1], run[2]), radius, ramp);
            }
            placed.add(world(run, x0, x1));
        }
        // One stroke per run, so a break in the curve is a break in the picture. See Algebra.walk. Split
        // again here for the one break that is the CHART's and not the walk's: the half turn.
        for (double[] run : placed) {
            for (double[] piece : unwrapped(run)) {
                if (piece.length >= 6) {
                    scene.add(curve(piece, radius, ramp));
                }
            }
        }
        if (reading.drawsMarker()) {
            rule(scene, turn(reading.coordinates()[0], reading.coordinates()[1]), radius, ramp);
        }
        return new Built(List.copyOf(scene), joined(placed), joined(runs), furniture, marks);
    }

    /**
     * The sampled points placed in the box, which is where everything else reads them.
     *
     * <p>The input across, the turn up, and zero into the screen — the plot is a plane and depth is not a
     * spare axis here, it is where the plane is.
     *
     * <p>Exactly the placement {@link #marker} uses, and that is load-bearing rather than a coincidence: a
     * curve and a marker are the same reading of the same sample, so a curve through a value must land on
     * the marker for that value.
     *
     * @param run interleaved {@code x, q, p}
     */
    private static double[] world(double[] run, double x0, double x1) {
        double[] out = new double[run.length];
        for (int i = 0; i < run.length / 3; i++) {
            out[i * 3] = at(run[i * 3], x0, x1);
            out[i * 3 + 1] = turn(run[i * 3 + 1], run[i * 3 + 2]);
            out[i * 3 + 2] = 0;
        }
        return out;
    }

    /** Where an input stands across the box: the walked domain onto {@code -BOX..BOX}. */
    private static double at(double x, double x0, double x1) {
        double t = x1 == x0 ? 0.5 : (x - x0) / (x1 - x0);
        return -BOX + 2 * BOX * t;
    }

    /**
     * How high a pair stands: its turn, {@code -π..π} onto {@code -BOX..BOX}.
     *
     * <p>{@code atan2} rather than the ratio, which is the whole of why this axis is bounded — and it is what
     * keeps the two ends of the model apart, since {@code ω} and {@code -ω} are a quarter turn either way
     * where the ratio has nothing to say about either.
     *
     * <p>The origin cannot arrive here: {@code T(0,0)} is the one pair with no direction and it is read as a
     * value before it is placed — {@code Algebra} applies {@code T.resolved()}, the table's {@code x ÷ x = 1},
     * which puts it at {@code T(1,1)} and the eighth turn. {@code atan2(0, 0)} is zero rather than an error in
     * any case, so nothing here divides by nothing.
     */
    private static double turn(double q, double p) {
        return Math.atan2(p, q) / TURN * BOX;
    }

    /**
     * One placed run, cut wherever it crosses the half turn.
     *
     * <p>The vertical axis is the circle of turns cut open at {@code ±π}, so a value moving smoothly past the
     * half turn comes back at the other end of the axis. That is a break in the <b>picture</b> and not in the
     * value — unlike every other break here, which is the walk reporting that the model said nothing — and
     * joining across it would draw a near-vertical line the whole height of the plot through turns the
     * expression never took.
     *
     * <p>A step of more than half the axis is the test. Nothing genuine can move that far between two samples,
     * because that would be more than a half turn in one step; a wrap always does, because it is exactly the
     * whole axis less however far the value really moved.
     *
     * <p>Cut here rather than in the walk, and separately from {@code placed}, because the probe's samples
     * have to stay index-parallel with the world points it projects.
     */
    private static List<double[]> unwrapped(double[] run) {
        List<double[]> pieces = new ArrayList<>();
        int from = 0;
        for (int i = 1; i < run.length / 3; i++) {
            if (Math.abs(run[i * 3 + 1] - run[(i - 1) * 3 + 1]) > BOX) {
                pieces.add(Arrays.copyOfRange(run, from * 3, i * 3));
                from = i;
            }
        }
        pieces.add(Arrays.copyOfRange(run, from * 3, run.length));
        return pieces;
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
     * Where a single value lands: two short segments crossing at the point.
     *
     * <p>A cross rather than a blob, because the whole content of this mode is <em>which coordinates</em> the
     * value has, and a cross states them — each arm runs along the axis it is a reading of, so the point can
     * be read off the axes by eye instead of being judged by eye against a sphere.
     *
     * <p>Two arms where there were three, and the arms are equal.
     *
     * @param x where the sample stands across the box
     * @param y how high its turn is
     */
    private static void marker(List<Surface.Stroke> into, double x, double y, double radius, Ramp ramp) {
        double arm = MARKER_ARM;
        Surface.Rgb colour = ramp.scene(1);
        into.add(line(x - arm, y, 0, x + arm, y, 0, radius, colour));
        into.add(line(x, y - arm, 0, x, y + arm, 0, radius, colour));
    }

    /**
     * A single value: a rule straight across the plot at its turn.
     *
     * <p>Not a marker, because <b>a value with no free name has no input</b> and a marker would have to stand
     * at some horizontal position, which would be a claim about an {@code x} the expression does not have. A
     * rule says the one true thing — this value is at this turn — and says it against the same named
     * graduations a curve is read against, so {@code 2+2} and the curve it might have come from can be
     * compared by eye.
     */
    private static void rule(List<Surface.Stroke> into, double y, double radius, Ramp ramp) {
        into.add(line(-BOX, y, 0, BOX, y, 0, radius, ramp.scene(1)));
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
     * <h2>The domain across, and the same eight turns every time</h2>
     *
     * <p>The horizontal axis is graduated from the domain, which is what the reader chose to walk, in round
     * numbers. <b>It takes the domain as two arguments again</b>, which it did once before and stopped doing
     * when a curve's first axis stopped being its input; the input is an axis again, so the domain is what
     * measures it again.
     *
     * <p>The vertical axis is graduated by {@link Algebra#NAMED}, constantly — the turns do not depend on the
     * expression, which is what lets two expressions be compared by where each crosses the same marks. Zero
     * carries no input mark ({@code Ticks.between} excludes it) because the vertical axis is drawn there;
     * the turn zero is marked and named, because the value zero is a fact and not an origin.
     */
    private static Marks marks(double x0, double x1) {
        double[] inputs = Ticks.between(x0, x1);
        double[] across = new double[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            across[i] = at(inputs[i], x0, x1);
        }
        int n = Algebra.NAMED.size();
        double[] up = new double[n];
        String[] turns = new String[n];
        for (int i = 0; i < n; i++) {
            up[i] = Algebra.NAMED.get(i).theta() / TURN * BOX;
            turns[i] = Algebra.NAMED.get(i).name();
        }
        return new Marks(across, inputs, Ticks.step(x1 - x0, Ticks.TARGET), up, turns);
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

    // The affine remap that put a value on the first axis went with that axis. Both axes now scale from one
    // span through the origin, which is a multiplication, and a general lo..hi mapping standing unused beside
    // it is an invitation to reintroduce a second scale.

    private Geometry() {
    }
}
