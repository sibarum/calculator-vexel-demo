package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;

import java.util.ArrayList;
import java.util.List;


/**
 * The axis names and tick numbers, drawn over the marched image.
 *
 * <h2>Text is the one thing the march cannot draw</h2>
 *
 * <p>A distance field has no glyphs, so everything else in the plot is geometry in the buffer and the labels
 * are not. They are a {@link Picture} on the same node — {@code PICTURE} draws over the image and under the
 * border, which is exactly where an application's marks belong — so the plot is one node carrying two props
 * and no second layer, no second pass, and nothing to keep in the right order.
 *
 * <h2>Which is why {@link Lens} has to be right</h2>
 *
 * <p>These are positioned by projecting world points with a Java transcription of the shader's camera. If the
 * two ever disagree the labels slide off their axes as the plot turns — a failure that looks like a rendering
 * bug and is a units bug. {@code LensTest} round-trips the transcription against the shader's own expression;
 * this class simply trusts it.
 *
 * <h2>Depth-sorted by refusal, not by ordering</h2>
 *
 * <p>A label whose anchor is behind the camera has no image, and {@link Lens#project} answers {@code null}
 * rather than a mirrored coordinate. That is the whole of the hidden-surface handling here and it is enough,
 * because a label is small: one drawn <em>through</em> the curve is a cosmetic overlap, while one drawn on the
 * wrong side of the screen is a lie about which axis it names.
 */
final class Labels {

    /** How far off the end of an axis its name sits, as a fraction of the box. */
    private static final double NAME_OUT = 1.16;

    /**
     * How far a tick number sits off its own axis, in world units.
     *
     * <p>Outward along the ray through the mark, so a name sits off the circle at its own turn and the eight
     * of them are as far apart as the turns are. Far enough to clear the tick that crosses the circle, close
     * enough to read as belonging to it.
     */
    private static final double NAME_OFF = 0.17;

    private Labels() {
    }

    /**
     * Build the overlay for one frame.
     *
     * @param box       the node's laid-out rect — the picture is authored in that box's pixels, because the
     *                  renderer resolves no units of its own
     * @param furniture what the scene asked for, <b>after</b> {@link Scene#effectiveFurniture()} has applied
     *                  the layer switches — the whole record rather than the two flags this reads, so that a
     *                  third annotation does not mean a third parameter, and so that there is no call site at
     *                  which the overlay could be handed a different answer than the geometry got
     * @param marks     where {@link Geometry} put the graduations and what it graduated them at. <b>The scene's
     *                  domain is deliberately not a parameter any more.</b> This used to number the first axis
     *                  from {@code x0..x1}, which was right while a curve was plotted against its input and
     *                  became wrong the moment the algebra made all three axes coordinates of the value: the
     *                  numbers then measured the input while the marks beside them measured {@code Re}, and the
     *                  two agreed only for an expression whose values happen to reach as far as its domain
     *                  does — {@code 0^x} over a symmetric range, which is the default
     * @return the picture, or {@code null} if the node has not been laid out yet
     */
    static Picture of(Lens lens, NodeLayout box, Algebra.Reading reading,
                      Geometry.Furniture furniture, Geometry.Marks marks) {
        if (box == null) {
            return null;
        }
        double w = box.rect().w();
        double h = box.rect().h();
        Sketch sketch = new Sketch();

        Color name = Look.QUIET.of(Look.PALETTE);
        Color figure = Look.PALETTE.text(2);

        // The axes are named by what they carry: the input across, the value's turn up. Read off the reading
        // rather than fixed here, so the name and the geometry cannot come to describe different axes -- and
        // the name of the input axis is the free name the expression actually left, not a fixed "x".
        //
        // "theta", not "Im" and not "p". What is drawn is arg(q + pi), one number out of the pair, and it is
        // the whole value: the radius the pair has is which representative was written. A label naming a
        // coordinate would be the plot claiming to show a coordinate, which is exactly the mistake the first
        // two versions of this chart made.
        if (furniture.labels()) {
            String[] axes = reading.axisNames();
            sketch.tag("axis-name");
            put(sketch, lens, w, h, Geometry.BOX * NAME_OUT, 0, 0, axes[0], name, Type.SMALL_PX);
            put(sketch, lens, w, h, 0, Geometry.BOX * NAME_OUT, 0, axes[1], name, Type.SMALL_PX);
        }

        // Numbers on all three axes, at exactly the positions Geometry put the marks -- one source, so a
        // number cannot sit beside a mark that is somewhere else, and no axis is graduated in a scale the
        // other two are not.
        //
        // The guard was drawsCurve() and is understood(), and the widening is the point. It excluded POINT
        // mode because the numbers came from the input domain, which measures nothing a single value is
        // showing; now they come from the marks, and the marks are the value's own coordinates in every mode.
        // A value standing in the box is a thing a reader wants to read off an axis at least as much as a
        // curve is.
        //
        // A refusal still gets none, and that is not the old guard surviving by accident: nothing is plotted,
        // so span falls back to 1, and numbering the axes -1..1 would be offering a scale for an empty box.
        if (!furniture.ticks() || !reading.understood()) {
            return sketch.picture();
        }
        sketch.tag("tick");
        double[] across = marks.across();
        double[] inputs = marks.inputs();
        for (int i = 0; i < inputs.length; i++) {
            // The input, under its own mark. A number here measures the domain, which is what the horizontal
            // axis carries again.
            put(sketch, lens, w, h, across[i], -NAME_OFF, 0,
                    Ticks.label(inputs[i], marks.step()), figure, Type.TICK_PX);
        }
        // The turn axis is numbered in one column down the left-hand edge, and the two families that fill it
        // are placed in order of what a reader cannot do without. A name goes down whatever else is there: it
        // is a landmark, the field accepts it, and there are at most eight. A rung goes down only where the
        // column has room for it in PIXELS -- which is the one thing that cannot be decided when the marks
        // are generated, because how far apart two heights are on screen depends on the camera, and the
        // camera is not something the worker that built them is allowed to know.
        List<Mark> column = new ArrayList<>();
        for (int i = 0; i < marks.up().length; i++) {
            column.add(new Mark(marks.up()[i], marks.turns()[i], 0));
        }
        for (Turns.Rung rung : marks.ladder()) {
            // Inside the box only. Out on the sheet the grid is fading towards nothing and a number is the
            // one thing on it that would still be legible, which would leave the picture labelled where it
            // is faintest.
            if (Math.abs(rung.at()) <= Geometry.BOX) {
                column.add(new Mark(rung.at(), rung.name(), 1 + rung.rank()));
            }
        }
        column.sort((a, b) -> Integer.compare(a.priority(), b.priority()));
        List<Double> taken = new ArrayList<>();
        for (Mark mark : column) {
            Lens.Point p = lens.project(-Geometry.BOX - NAME_OFF, mark.at(), 0);
            if (p == null) {
                continue;
            }
            double v = p.v() * h;
            boolean room = true;
            for (double already : taken) {
                room &= Math.abs(already - v) >= Type.TICK_PX * 1.3;
            }
            if (!room) {
                continue;
            }
            taken.add(v);
            put(sketch, lens, w, h, -Geometry.BOX - NAME_OFF, mark.at(), 0, mark.name(), figure, Type.TICK_PX);
        }
        return sketch.picture();
    }

    /** One entry in the column of numbers down the turn axis, and how badly it wants to be there. */
    private record Mark(double at, String name, int priority) {
    }


    /**
     * Place one run of text at a world point.
     *
     * <p>Centred horizontally on its anchor by estimating the advance, which is the one piece of arithmetic here
     * that is not exact: there is no public way to measure a string that is not in the tree, so a picture sizing
     * its own text has to approximate. Mono at these sizes is close enough to {@code 0.6em} per character that
     * the error is under a pixel, and a tick number is two or three characters.
     */
    private static void put(Sketch sketch, Lens lens, double w, double h,
                            double x, double y, double z, String text, Color color, double sizePx) {
        Lens.Point p = lens.project(x, y, z);
        if (p == null) {
            return;
        }
        double advance = text.length() * sizePx * 0.6;
        sketch.text(text, p.u() * w - advance / 2, p.v() * h + sizePx * 0.35, sizePx, color);
    }

    /** Install the overlay on a node and keep it current. */
    static void show(Node node, Picture picture) {
        if (picture != null) {
            node.picture(picture);
        }
    }
}
