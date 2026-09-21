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
     * How far outside the box the furniture is drawn, as a multiple of {@link #BOX}.
     *
     * <p>The grid sheet reaches this far in every direction and fades out on the way, which is what makes it
     * read as a plane the plot is standing on rather than a mat the plot is standing in the middle of. The
     * graduations are generated that far as well — a sheet whose lines stopped at the box edge would fade
     * out with nothing in it, which is the same picture as not extending it at all.
     *
     * <p><b>Measured against the camera rather than chosen.</b> A pinhole at focal 2.5 sees about
     * {@code d/2.5} world units either side of the axis at depth {@code d}, and the far plane is 14 — so the
     * widest view this application can show is about three and a third boxes from the middle to a corner.
     * The sheet reaches past that and its fade is well under way by then, which is the picture the fade is
     * for: a plane running out of the frame, not a mat with an edge.
     *
     * <p>It is here rather than in {@link Grid} because both the marks and the sheet are generated against
     * it, and two numbers would be a grid whose lines run out before its fade does.
     */
    static final double REACH = 4.5;

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
         * The axes, the named turns, the ruler between them — and the plane the plot is in.
         *
         * <p><b>The plot's own plane is back on, and the reason it was off is the reason it is on.</b> It was
         * switched off with this argument: "a square grid at six divisions is the picture of a scale, and this
         * chart has none. Every value is on one circle at its own turn, so a reader counting squares out from
         * the origin would be counting something that is not there." That was right about the lattice and it
         * is no longer true of the lines: {@link Grid} rules them at the graduations now — the inputs across,
         * the named turns and {@link Turns}'s log ruler up — so counting lines counts values and inputs, which
         * are exactly the things that are there.
         *
         * <p>The other two stay off. They stand out of the plane the plot is in and cross the picture at right
         * angles to everything in it, they carry no graduations of their own, and a reader who wants to see
         * the plane edge-on can still ask for one.
         */
        static final Furniture DEFAULT = new Furniture(true, true, true, true, false, false, 6);

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
     * <h2>And the ruler under the names, which is new</h2>
     *
     * <p>The eight names are landmarks and they are not a scale: between {@code 0} and {@code 1} lies every
     * value of magnitude under one, and the axis said nothing about where. {@link #ladder} is that
     * fineprint — the log ruler {@link Turns} prints for whatever arc the window is showing — and it is a
     * third family rather than more entries in {@link #up} because the two are drawn differently and for
     * different reasons: a name is a rule right across the plot, a rung is a graduation beside the axis.
     *
     * @param across    where each input mark falls, {@code -BOX..BOX}
     * @param inputs    what each of those stands for, in the walked domain
     * @param step      the 1-2-5 step the inputs came out at, so they are written to a common precision
     * @param pitch     the same step as a distance in the box, which is what carries the grid on past the
     *                  walked domain — derived here rather than differenced out of {@code across}, which
     *                  says nothing at all when the domain is narrow enough to hold one mark
     * @param up        where each named turn falls, in box units — beyond {@code ±BOX} out on the sheet
     * @param turns     what each of those is called
     * @param ladder    the graduations between the names, already placed in the box
     */
    record Marks(double[] across, double[] inputs, double step, double pitch, double[] up, String[] turns,
                 List<Turns.Rung> ladder) {

        static final Marks NONE =
                new Marks(new double[0], new double[0], 1, 0, new double[0], new String[0], List.of());
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
                    Furniture furniture, double radius, Ramp ramp, Turns.Window window) {
        List<double[]> runs = Algebra.walk(reading, x0, x1, samples);

        // The axes and their graduations are drawn on the panels, like the grid, so what this contributes to
        // them is where the marks go. What is left in the field is the curve and, in point mode, the rule:
        // the things the plot is OF rather than the frame around it.
        Marks marks = furniture.axes() && furniture.ticks() ? marks(x0, x1, window) : Marks.NONE;

        List<Surface.Stroke> scene = new ArrayList<>();
        List<double[]> placed = new ArrayList<>(runs.size());
        List<double[]> drawn = new ArrayList<>(runs.size());
        for (double[] run : runs) {
            // A run of one is an isolated answer, and it gets a marker for the reason a single value does:
            // a stroke through one point draws nothing, and the value is no less real for having no
            // neighbour.
            if (run.length == 3) {
                double y = turn(window, run[1], run[2]);
                if (Math.abs(y) <= BOX) {
                    marker(scene, at(run[0], x0, x1), y, radius, ramp);
                }
            }
            // Twice, in two densities, and they are not the same list. The probe snaps to SAMPLES -- points
            // the engine actually answered -- and quotes them from an index-parallel array, so nothing may be
            // inserted into that one. The stroke wants the path BETWEEN them, which is mediants.
            placed.add(world(run, x0, x1, window));
            drawn.add(mediants(run, x0, x1, window));
        }
        // One stroke per run, so a break in the curve is a break in the picture. See Algebra.walk. Split
        // again here for the one break that is the CHART's and not the walk's: the half turn -- and then
        // again for the break that is the WINDOW's, where the curve leaves the arc being shown.
        for (double[] run : drawn) {
            for (double[] piece : unwrapped(run, window)) {
                for (double[] inside : clipped(piece)) {
                    if (inside.length >= 6) {
                        // Simplified on the way into the buffer and nowhere else: what the march is handed is
                        // the fewest chords that stand for this path within CHORD, which is 25 for the default
                        // entry against the 840 the walk and its mediants produced. See Chords for the
                        // measurement that made this the first thing to fix, and for what it costs the
                        // mediant claim.
                        scene.add(curve(Chords.of(inside, CHORD), radius, ramp));
                    }
                }
            }
        }
        if (reading.drawsMarker()) {
            double y = turn(window, reading.coordinates()[0], reading.coordinates()[1]);
            if (Math.abs(y) <= BOX) {
                rule(scene, y, radius, ramp);
            }
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
    private static double[] world(double[] run, double x0, double x1, Turns.Window window) {
        double[] out = new double[run.length];
        for (int i = 0; i < run.length / 3; i++) {
            out[i * 3] = at(run[i * 3], x0, x1);
            out[i * 3 + 1] = turn(window, run[i * 3 + 1], run[i * 3 + 2]);
            out[i * 3 + 2] = 0;
        }
        return out;
    }

    /**
     * The same run with the path <em>between</em> its samples filled in, at mediants.
     *
     * <h2>A straight segment between two turns is the wrong curve, and it is wrong by the model's own rule</h2>
     *
     * <p>A stroke joins its vertices with straight lines, so between two samples the drawn path is linear in
     * {@code θ}. Between {@code ω} at 90° and {@code 1} at 45° that puts the halfway point at 67.5°, whose
     * tangent is {@code 1 + √2} — <b>an irrational, which {@code T} cannot hold</b>. Every interior point of
     * every segment was a claim the type is unable to make.
     *
     * <p>The model already says what lies between two pairs: {@code ⊕}, the mediant, which is coordinate-wise
     * addition. {@code T(1,1) ⊕ T(1,0) = T(2,1)}, so the halfway value is {@code 2} at 63.4°, every
     * intermediate is a rational, and repeated mediants are the Stern-Brocot construction. Interpolating the
     * pair and then taking its direction is the same move a rational Bézier makes in homogeneous coordinates,
     * and the same one a rasteriser makes to be perspective-correct: interpolate before projecting, never
     * after.
     *
     * <p><b>For {@code 1÷x} this is exact rather than close.</b> Sampled at {@code T(k,d)} the value is
     * {@code T(d,k)}, so the pair is linear in {@code k}, so the chord through the pairs <em>is</em> the path
     * and the mediant of two samples is the value the engine answers between them. Where the pair path bends,
     * {@code x·x} for instance, it is an approximation — but a nearer one than the angular chord, and one that
     * only ever names values the type has.
     *
     * <p><b>It reads the representative, and that is the point.</b> The mediant is an operation on pairs and
     * not on directions: {@code T(1,1) ⊕ T(1,0)} and {@code T(1,1) ⊕ T(2,0)} are different turns from the same
     * two values. Farey and Stern-Brocot avoid that by reducing; nothing here reduces, so the subdivision uses
     * information the direction alone has thrown away, which is exactly what makes it exact above. The drawn
     * path therefore depends on which representatives the walk produced. That is a property of the model
     * rather than a defect of the chart.
     *
     * <h2>Every segment carries its mediant; deeper only where it shows</h2>
     *
     * <p>The first bisection is unconditional, so <b>every drawn segment passes through the mediant of the two
     * samples it joins</b> — which is the claim, and it would not hold under a purely adaptive rule, since at
     * the default sampling the angular error is a fraction of a degree and every test would decline. Below
     * that first split it is a flatness test against the thing being corrected: keep splitting while the
     * mediant sits more than {@link #CHORD} from where a straight segment would put it. So an ordinary walk
     * costs one extra vertex per sample and the rest is spent where the samples are sparse or the curve turns
     * fastest, which is where the old segments were visibly wrong.
     *
     * @param run interleaved {@code x, q, p}, as the walk gave it
     * @return interleaved world {@code x, y, z}, with the mediants in place
     */
    private static double[] mediants(double[] run, double x0, double x1, Turns.Window window) {
        List<Double> out = new ArrayList<>(run.length);
        int n = run.length / 3;
        if (n == 0) {
            return new double[0];
        }
        emit(out, at(run[0], x0, x1), turn(window, run[1], run[2]));
        for (int i = 1; i < n; i++) {
            between(out, run[(i - 1) * 3], run[(i - 1) * 3 + 1], run[(i - 1) * 3 + 2],
                    run[i * 3], run[i * 3 + 1], run[i * 3 + 2], x0, x1, window, 0);
            emit(out, at(run[i * 3], x0, x1), turn(window, run[i * 3 + 1], run[i * 3 + 2]));
        }
        double[] made = new double[out.size()];
        for (int i = 0; i < made.length; i++) {
            made[i] = out.get(i);
        }
        return made;
    }

    /** The mediants strictly between two samples, in order, down to where the segment goes flat. */
    private static void between(List<Double> into, double ax, double aq, double ap,
                                double bx, double bq, double bp, double x0, double x1,
                                Turns.Window window, int depth) {
        double mq = aq + bq;
        double mp = ap + bp;
        if (mq == 0 && mp == 0) {
            // Two samples exactly half a turn apart. Their pairs cancel, so the chord between them runs
            // through the origin and has no direction there -- there is no mediant to draw and the run is
            // about to be cut at the half turn anyway. See unwrapped.
            return;
        }
        double mediant = theta(mq, mp);
        double straight = (theta(aq, ap) + theta(bq, bp)) / 2;
        // Depth zero always splits: the mediant of two samples is the thing this method exists to draw, and a
        // flatness test would decline it at every ordinary sampling.
        //
        // MEASURED IN TURNS, NOT IN THE BOX, which is a change the window forced and an improvement anyway.
        // The test used to be a distance on the drawn picture, and under magnification the same fraction of a
        // degree is a whole box -- so every segment would fail it, every segment would split to DEPTH, and a
        // walk of 420 samples would arrive at the cone buffer as 26,000 vertices for a curve no better drawn.
        // In turns it is the same test it always was at the whole circle, and the same cost at every zoom.
        if (depth > 0 && (depth >= DEPTH || Math.abs(mediant - straight) <= CHORD / BOX * TURN)) {
            return;
        }
        double mx = (ax + bx) / 2;
        // Halved back to a representative of the same size as its neighbours, so the next mediant down is
        // weighted evenly between them rather than dragged toward whichever side was summed last.
        double hq = mq / 2;
        double hp = mp / 2;
        between(into, ax, aq, ap, mx, hq, hp, x0, x1, window, depth + 1);
        emit(into, at(mx, x0, x1), window.at(mediant));
        between(into, mx, hq, hp, bx, bq, bp, x0, x1, window, depth + 1);
    }

    private static void emit(List<Double> into, double x, double y) {
        into.add(x);
        into.add(y);
        into.add(0.0);
    }

    /** How far the drawn path may sit from the mediant before the segment is split, in world units. */
    private static final double CHORD = 0.003;

    /** How far one segment may be bisected. Six is 63 mediants, which is more than a segment can need. */
    private static final int DEPTH = 6;

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
     * <p>The origin cannot arrive here: {@code T(0,0)} is the one pair with no direction, and {@code Algebra}
     * declines it rather than placing it — a sample of it breaks the run, and a value that folds to it is
     * reported without a marker. It used to be read as a value instead, by the table's {@code x ÷ x = 1},
     * which put it at {@code T(1,1)} and the eighth turn; the type no longer applies that reading and a
     * chart is not where it should be decided. {@code atan2(0, 0)} is zero rather than an error in any case,
     * so nothing here divides by nothing.
     */
    private static double turn(Turns.Window window, double q, double p) {
        return window.at(theta(q, p));
    }

    /**
     * The turn itself, in radians, before the window has said where in the box it stands.
     *
     * <p>Split out from {@link #turn} because two callers want the angle and not the height: the flatness
     * test in {@link #between}, which is about how far the curve bends and not about how far it is drawn,
     * and the cut in {@link #unwrapped}, which is looking for a jump of a whole circle.
     */
    private static double theta(double q, double p) {
        return Math.atan2(p, q);
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
    private static List<double[]> unwrapped(double[] run, Turns.Window window) {
        // Half the axis is half a turn at the whole circle and a great deal more than the axis under
        // magnification -- so the threshold is written as the half turn it has always been, converted into
        // whatever the window makes of it, rather than as the box's own half height. A genuine step cannot
        // exceed it at any zoom, because it would be more than a half turn in one step.
        double half = window.at(window.centre() + TURN / 2) - window.at(window.centre());
        List<double[]> pieces = new ArrayList<>();
        int from = 0;
        for (int i = 1; i < run.length / 3; i++) {
            if (Math.abs(run[i * 3 + 1] - run[(i - 1) * 3 + 1]) > half) {
                pieces.add(Arrays.copyOfRange(run, from * 3, i * 3));
                from = i;
            }
        }
        pieces.add(Arrays.copyOfRange(run, from * 3, run.length));
        return pieces;
    }

    /**
     * One placed run, with everything off the sheet cut away and the crossings put on the edge.
     *
     * <p><b>Only magnification makes this necessary, and then it is not optional.</b> At the whole circle no
     * point can be outside the box, so every run passes through untouched. A window a hundredth of the
     * circle wide puts a value a quarter turn away a hundred boxes off the top, and a stroke that long is
     * cones spent on something nobody can see — a thousandth of the circle is a thousand boxes, and the
     * buffer is not free.
     *
     * <p>Cut rather than clamped, and interpolated rather than dropped. Clamping would flatten everything
     * above the sheet onto its edge and draw a horizontal line the expression never took; dropping a segment
     * because one end is off the sheet would stop the curve short of an edge it plainly runs through, and
     * would lose entirely the segment that crosses the whole picture with both ends outside it — which is
     * what a steep curve looks like once the window is narrow.
     */
    private static List<double[]> clipped(double[] run) {
        double edge = REACH * BOX;
        List<double[]> pieces = new ArrayList<>();
        List<Double> piece = new ArrayList<>();
        for (int i = 1; i < run.length / 3; i++) {
            double[] cut = segment(run[(i - 1) * 3], run[(i - 1) * 3 + 1], run[i * 3], run[i * 3 + 1], edge);
            if (cut == null) {
                pieces.add(flat(piece));
                piece = new ArrayList<>();
                continue;
            }
            if (piece.isEmpty()) {
                emit(piece, cut[0], cut[1]);
            }
            emit(piece, cut[2], cut[3]);
            if (cut[3] != run[i * 3 + 1]) {
                pieces.add(flat(piece));           // it left the sheet here; anything further is a new piece
                piece = new ArrayList<>();
            }
        }
        pieces.add(flat(piece));
        return pieces;
    }

    /**
     * One segment against the sheet: the part of it inside, or {@code null} if none of it is.
     *
     * @return {@code ax, ay, bx, by} of the surviving part
     */
    private static double[] segment(double ax, double ay, double bx, double by, double edge) {
        if (ay > edge && by > edge || ay < -edge && by < -edge) {
            return null;
        }
        double t0 = 0;
        double t1 = 1;
        double dy = by - ay;
        if (dy != 0) {
            double toTop = (edge - ay) / dy;
            double toFloor = (-edge - ay) / dy;
            double lo = Math.min(toTop, toFloor);
            double hi = Math.max(toTop, toFloor);
            t0 = Math.max(t0, lo);
            t1 = Math.min(t1, hi);
        }
        if (t0 > t1) {
            return null;
        }
        return new double[]{ax + t0 * (bx - ax), ay + t0 * dy, ax + t1 * (bx - ax), ay + t1 * dy};
    }

    private static double[] flat(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
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
        // ALONG THE PATH, not along the vertex list, which is a difference that did not exist until the
        // vertices stopped being evenly spread. The ramp used to be i/(n-1): with a mediant between every
        // sample that was uniform enough to pass for distance, and after Chords it is not — a straight run
        // that collapses to two vertices would take the same share of the gradient as a bend that kept
        // thirty. The colour is inert today (ConeField carries none; see the class note and FN-25), so this
        // is a trap being disarmed rather than a bug being fixed: the day the buffer carries colour, the
        // ramp is already a property of the curve rather than of how it happened to be sampled.
        double[] along = new double[n];
        for (int i = 1; i < n; i++) {
            along[i] = along[i - 1] + Math.hypot(world[i * 3] - world[(i - 1) * 3],
                    world[i * 3 + 1] - world[(i - 1) * 3 + 1]);
        }
        double length = along[n - 1];
        for (int i = 0; i < n; i++) {
            double t = length <= 0 ? 0 : along[i] / length;
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
    private static Marks marks(double x0, double x1, Turns.Window window) {
        double[] inputs = Ticks.between(x0, x1);
        double[] across = new double[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            across[i] = at(inputs[i], x0, x1);
        }
        // The named turns that are on the sheet. All eight always were, because the box was the whole
        // circle; under magnification most of them are somewhere else, and a rule drawn for one that is
        // fifty boxes above the top is a rule nobody will ever see, carrying a label the overlay would
        // project off the screen.
        List<Double> up = new ArrayList<>();
        List<String> turns = new ArrayList<>();
        for (Algebra.Named named : Algebra.NAMED) {
            double y = window.at(named.theta());
            if (Math.abs(y) <= REACH * BOX) {
                up.add(y);
                turns.add(named.name());
            }
        }
        double[] heights = new double[up.size()];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = up.get(i);
        }
        double step = Ticks.step(x1 - x0, Ticks.TARGET);
        return new Marks(across, inputs, step, x1 == x0 ? 0 : 2 * BOX * step / (x1 - x0), heights,
                turns.toArray(new String[0]), Turns.ladder(window, REACH * BOX, RUNG_GAP));
    }

    /**
     * How close two graduations on the turn axis may stand, in box units.
     *
     * <p>Chosen against the label rather than against the line: the rungs are numbered, at tick size, and
     * two numbers a fortieth of the box apart overlap. It is the one number in the ruler that is a taste
     * rather than a consequence, and it is here rather than in {@link Turns} because it is a fact about how
     * big this plot is drawn.
     */
    private static final double RUNG_GAP = 0.075;

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
