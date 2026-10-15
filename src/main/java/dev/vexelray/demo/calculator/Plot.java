package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.IconButton;

import java.util.List;

/**
 * A {@link Graph} on the tape: the curve in a box, and a line under it that reads out the sample under the
 * pointer, with a button at its end that pops the plot out into the {@link PlotExplorer}. A double-click on the box
 * does the same.
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
    private final Node node;
    private final IconButton explore;
    private final String idle;
    private int hovered = -1;

    /** @param explore what popping this plot out does */
    Plot(Gui gui, Graph graph, Runnable explore) {
        this.gui = gui;
        this.graph = graph;
        double[] range = Chart.range(graph);
        this.view = new View(graph.from(), graph.to(), range[0], range[1]);

        long markers = graph.samples().stream().filter(Graph.Sample::marker).count();
        this.idle = graph.name() + " from " + Graph.FROM + " to " + Graph.TO
                + (markers == 0 ? "" : "  ·  " + markers + (markers == 1 ? " place" : " places")
                + " with no real number, marked");

        figure = gui.box().width(Length.FILL).height(HEIGHT).corner(Type.CORNER).textSize(Type.SMALL);
        readout = gui.text(idle).width(Length.grow(1f)).font(Type.MONO).textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.DIM));
        this.explore = new IconButton(gui, Icon.POP_OUT, "Open in the plot explorer")
                .size(Length.rem(1.25f), Length.rem(0.875f))
                .tip("Open in the plot explorer (or double-click the plot)")
                .onPress(explore);
        node = gui.column().width(Length.FILL).height(Length.AUTO).gap(Type.TIGHT).children(figure,
                gui.row().width(Length.FILL).gap(Type.GAP).alignItems(AlignItems.CENTER)
                        .children(readout, this.explore.node()));
        gui.onResizeUi(figure, layout -> figure.picture(Chart.draw(view, graph.samples(), Chart.Style.TAPE,
                gui.theme(), layout.rect().w(), layout.rect().h(),
                layout.textSizePx() > 0 ? layout.textSizePx() : 11, layout.cornerTopPx())));
        gui.onDoubleClick(figure, explore);
    }

    Node node() {
        return node;
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
                readout.text(idle);
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
        readout.text(graph.name() + " = " + p.xText() + "   →   " + (p.refused() ? "no value" : p.value()));
    }
}
