package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.text.TextMetrics;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.IconButton;

import java.util.List;

/**
 * A {@link Graph} on the tape: the curve in a box, and a line under it that reads out the sample under the
 * pointer, with a button at its end that pops the plot out into the {@link PlotExplorer}. A double-click on the box
 * does the same. The readout is always one line, cut to fit, so that what it says never moves the tape.
 *
 * <p>The box is one {@link Node#picture picture}, drawn by {@link Chart} and rebuilt whenever its size changes,
 * since a picture is in pixels. The pointer's mark is the box's {@link Node#overlay overlay}, so moving it never
 * redraws the curve.
 *
 * <p>x runs over the graph's fixed range. y is fitted to the samples, except that a few samples far beyond the
 * rest (the ones beside a pole) are let run off the box rather than flatten everything else.
 */
final class Plot {

    static final Length HEIGHT = Length.dp(200);

    private final Gui gui;
    private final Graph graph;
    private final View view;
    private final Node figure;
    private final Node readout;
    private final Node line;
    private final Node node;
    private final IconButton explore;
    private final String idle;
    private int hovered = -1;

    // The readout's line is one line high whatever it says, so that hovering never changes the tape's height. On a
    // tape held to its bottom a taller readout moves the plot under a still pointer, onto a sample whose readout
    // has another height, and the plot shakes. The text is cut to the line, as many characters as fit.
    /** The line's height, in em of its own text size, until a layout says what one line really is. */
    private static final float LINE_EM = 1.25f;
    private String shown;
    private float lineEm = LINE_EM;
    private int fits = Integer.MAX_VALUE;

    /** @param explore what popping this plot out does */
    Plot(Gui gui, Graph graph, Runnable explore) {
        this.gui = gui;
        this.graph = graph;
        double[] range = Chart.range(graph);
        this.view = new View(graph.from(), graph.to(), range[0], range[1]);

        long markers = graph.samples().stream().filter(Graph.Sample::marker).count();
        this.idle = graph.name() + " from " + Graph.FROM + " to " + Graph.TO
                + (markers == 0 ? "" : "  ·  " + markers + (markers == 1 ? " place" : " places")
                + " with no number, marked")
                + legend(graph);

        figure = gui.box().width(Length.FILL).height(HEIGHT).corner(Type.CORNER).textSize(Type.SMALL);
        readout = gui.text(idle).width(Length.FILL).font(Type.MONO).textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.DIM));
        line = gui.box().width(Length.grow(1f)).height(Length.em(LINE_EM)).textSize(Type.SMALL).clip(true)
                .children(readout);
        shown = idle;
        this.explore = new IconButton(gui, Icon.POP_OUT, "Open in the plot explorer")
                .size(Length.rem(1.25f), Length.rem(0.875f))
                .tip("Open in the plot explorer (or double-click the plot)")
                .onPress(explore);
        node = gui.column().width(Length.FILL).height(Length.AUTO).gap(Type.TIGHT).children(figure,
                gui.row().width(Length.FILL).gap(Type.GAP).alignItems(AlignItems.CENTER)
                        .children(line, this.explore.node()));
        gui.onResizeUi(line, layout -> fit());
        gui.onResizeUi(readout, layout -> fit());
        gui.onResizeUi(figure, layout -> figure.picture(Chart.draw(view, graph.samples(), Chart.Style.TAPE,
                gui.theme(), layout.rect().w(), layout.rect().h(),
                layout.textSizePx() > 0 ? layout.textSizePx() : 11, layout.cornerTopPx())));
        gui.onDoubleClick(figure, explore);
    }

    Node node() {
        return node;
    }

    /**
     * Each curve but the plain one, and its colour: {@code   ·  0·n in green  ·  ω²·n in red}, and the imaginary
     * parts where there are any.
     */
    private static String legend(Graph graph) {
        StringBuilder out = new StringBuilder();
        for (Graph.Curve c : Graph.curves(graph.samples())) {
            if (c.order().power() == 0) continue;
            out.append("  ·  ").append(c.order().multiple()).append(" in ").append(Look.curveName(c.order().power()));
        }
        if (Graph.imaginary(graph.samples()) != null) out.append("  ·  imaginary part in ").append(Look.IMAGINARY_NAME);
        return out.toString();
    }

    /** The pop-out button, for a landmark. */
    Node exploreButton() {
        return explore.node();
    }

    // ---- the pointer ----

    /** The pointer is at {@code (x, y)} in window pixels: mark the nearest sample if it is over the box. */
    synchronized void pointer(float x, float y) {
        NodeLayout layout = figure.layout();
        if (!layout.present() || !layout.visibleRect().contains(x, y)) {
            if (hovered != -1) {
                hovered = -1;
                figure.overlay(null);
                show(idle);
            }
            return;
        }
        double w = layout.rect().w();
        double h = layout.rect().h();
        List<Graph.Sample> all = graph.samples();
        int i = (int) Math.round((x - layout.rect().x()) / w * (all.size() - 1));
        i = Math.clamp(i, 0, all.size() - 1);
        if (i == hovered) return;
        hovered = i;

        Graph.Sample p = all.get(i);
        double sx = view.px(p.x(), w);
        Color ink = gui.theme().color(Role.INK);
        Sketch s = new Sketch().line(sx, 0, sx, h, 1, Color.withAlpha(ink, 0.35f));
        if (p.onCurve() && p.y() >= view.yLo() && p.y() <= view.yHi()) {
            double sy = view.py(p.y(), h);
            s.circle(sx, sy, 3.5, ink).ring(sx, sy, 6, 1.5, ink);
        }
        figure.overlay(s.picture());
        show(graph.name() + " = " + p.xText() + "   →   " + (p.refused() ? "no value" : p.value()));
    }

    // ---- the readout's line ----

    /** Put {@code text} on the readout, cut to the line. */
    private synchronized void show(String text) {
        shown = text;
        readout.text(cut(text, fits));
    }

    /**
     * The line or its text is laid out at a new size: work out from the readout's own text how high one line is
     * and how many characters fit across the line, and size and cut to that. The font is monospaced, but a symbol
     * it lacks comes from another face, so the widest step on the line is taken as every character's. Text that
     * still runs over wraps out of sight below the line, and that resizes the readout, which measures again.
     */
    private synchronized void fit() {
        NodeLayout layout = line.layout();
        NodeLayout text = readout.layout();
        if (!layout.present() || !text.present() || text.text() == null || text.textSizePx() <= 0) return;
        TextMetrics.VisualLine first = text.text().lines().getFirst();
        float em = first.height() / text.textSizePx();
        if (em > 0 && Math.abs(em - lineEm) > 0.01f) {
            lineEm = em;
            line.height(Length.em(em));
        }
        float step = 0;
        float[] xs = first.xs();
        for (int k = 1; k < xs.length; k++) step = Math.max(step, xs[k] - xs[k - 1]);
        int now = step > 0 ? Math.max(1, (int) (layout.content().w() / step)) : fits;
        if (now != fits) {
            fits = now;
            readout.text(cut(shown, fits));
        }
    }

    /** {@code text} in at most {@code fits} characters, ending in an ellipsis where some were left off. */
    static String cut(String text, int fits) {
        int n = text.codePointCount(0, text.length());
        if (n <= fits) return text;
        return text.substring(0, text.offsetByCodePoints(0, Math.max(0, fits - 1))) + "…";
    }
}
