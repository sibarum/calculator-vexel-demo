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

    private Labels() {
    }

    /**
     * Build the overlay for one frame.
     *
     * @param box    the node's laid-out rect — the picture is authored in that box's pixels, because the
     *               renderer resolves no units of its own
     * @param domain the input range the x axis spans, for the numbers
     * @return the picture, or {@code null} if the node has not been laid out yet
     */
    static Picture of(Lens lens, NodeLayout box, double domainLo, double domainHi, boolean ticks) {
        if (box == null) {
            return null;
        }
        double w = box.rect().w();
        double h = box.rect().h();
        Sketch sketch = new Sketch();

        Color name = Look.QUIET.of(Look.PALETTE);
        Color figure = Look.PALETTE.text(2);

        sketch.tag("axis-name");
        put(sketch, lens, w, h, Geometry.BOX * NAME_OUT, 0, 0, "x", name, Type.SMALL_PX);
        put(sketch, lens, w, h, 0, Geometry.BOX_H * NAME_OUT, 0, "Re", name, Type.SMALL_PX);
        put(sketch, lens, w, h, 0, 0, Geometry.BOX_H * NAME_OUT, "Im", name, Type.SMALL_PX);

        if (!ticks) {
            return sketch.picture();
        }
        sketch.tag("tick");
        // The same positions Geometry put the marks at -- one source, so a number cannot sit beside a mark that
        // is somewhere else.
        double[] values = Ticks.between(domainLo, domainHi);
        double step = Ticks.step(domainHi - domainLo, 9);
        for (double value : values) {
            // The label says what the *domain* is there, not where the geometry was put: the world box is an
            // internal frame and a reader should never see its coordinates.
            double at = -Geometry.BOX + 2 * Geometry.BOX * ((value - domainLo) / (domainHi - domainLo));
            put(sketch, lens, w, h, at, -0.16, 0, Ticks.label(value, step), figure, Type.FIGURE_PX);
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
