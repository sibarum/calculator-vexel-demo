package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;


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
     * <p>One distance for all three, and each is pushed along a different axis so that a number stays in a
     * coordinate plane rather than floating in the middle of the box: the {@code Re} numbers drop below their
     * axis in {@code -y}, and both output axes' numbers step aside in {@code -x}. Zero carries no mark
     * ({@link Ticks#between} excludes it), which is what keeps the three families from meeting at the origin.
     */
    private static final double TICK_OUT = 0.16;

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

        // The axes are named by what they carry, which is not the same in both modes: a curve's first axis is
        // its input and a single value's is the first of its own coordinates. Read off the reading rather than
        // fixed here, so the name and the geometry cannot come to describe different axes.
        //
        // "Tr", not "Im". The vertical output axis is the TRACTION axis -- an order of vanishing -- and the
        // complex reading that made it an imaginary part is not wired in this engine. A label naming a part
        // the value does not have is the plot claiming something the algebra never said.
        if (furniture.labels()) {
            String[] axes = reading.axisNames();
            sketch.tag("axis-name");
            put(sketch, lens, w, h, Geometry.BOX * NAME_OUT, 0, 0, axes[0], name, Type.SMALL_PX);
            put(sketch, lens, w, h, 0, Geometry.BOX_H * NAME_OUT, 0, axes[1], name, Type.SMALL_PX);
            put(sketch, lens, w, h, 0, 0, Geometry.BOX_H * NAME_OUT, axes[2], name, Type.SMALL_PX);
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
        double[] values = marks.values();
        double[] domain = marks.domain();
        double[] output = marks.output();
        for (int i = 0; i < values.length; i++) {
            // One number written once and placed three times. The label says what the *value* is there, not
            // where the geometry put it: the world box is an internal frame and a reader should never see its
            // coordinates.
            String text = Ticks.label(values[i], marks.step());
            put(sketch, lens, w, h, domain[i], -TICK_OUT, 0, text, figure, Type.TICK_PX);
            put(sketch, lens, w, h, -TICK_OUT, output[i], 0, text, figure, Type.TICK_PX);
            put(sketch, lens, w, h, -TICK_OUT, 0, output[i], text, figure, Type.TICK_PX);
        }
        return sketch.picture();
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
