package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Sketch;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Graph} on the tape: the curve in a box, and a line under it that reads out the sample under the
 * pointer.
 *
 * <p>The box is one {@link Node#picture picture}, rebuilt whenever its size changes, since a picture is in
 * pixels. The pointer's mark is the box's {@link Node#overlay overlay}, so moving it never redraws the curve.
 *
 * <p>x runs over the graph's fixed range. y is fitted to the samples, except that a few samples far beyond the
 * rest (the ones beside a pole) are let run off the box rather than flatten everything else.
 */
final class Plot {

    static final Length HEIGHT = Length.dp(200);

    /** Within this many pixels of the last one, a marker keeps its line and dot but not its label. */
    private static final double LABEL_SPACING = 36;

    private final Gui gui;
    private final Graph graph;
    private final Node figure;
    private final Node readout;
    private final Node node;
    private final double yLo;
    private final double yHi;
    private final String idle;
    private int hovered = -1;

    Plot(Gui gui, Graph graph) {
        this.gui = gui;
        this.graph = graph;
        double[] range = range(graph);
        this.yLo = range[0];
        this.yHi = range[1];

        long markers = graph.samples().stream().filter(Graph.Sample::marker).count();
        this.idle = graph.name() + " from " + Graph.FROM + " to " + Graph.TO
                + (markers == 0 ? "" : "  ·  " + markers + (markers == 1 ? " place" : " places")
                + " with no real number, marked");

        figure = gui.box().width(Length.FILL).height(HEIGHT).corner(Type.CORNER).textSize(Type.SMALL);
        readout = gui.text(idle).font(Type.MONO).textSize(Type.SMALL).textColor(gui.theme().color(Role.DIM));
        node = gui.column().width(Length.FILL).height(Length.AUTO).gap(Type.TIGHT).children(figure, readout);
        gui.onResizeUi(figure, layout -> figure.picture(draw(layout)));
    }

    Node node() {
        return node;
    }

    // ---- the frame ----

    /**
     * The y range: every sample on the curve, except that an end lying far beyond the bulk (more than twice the
     * bulk's own span past its 2nd/98th percentile) is cut back to the bulk, and the result padded by a tenth.
     */
    static double[] range(Graph graph) {
        double[] ys = graph.samples().stream().filter(Graph.Sample::onCurve)
                .mapToDouble(Graph.Sample::y).filter(Double::isFinite).sorted().toArray();
        if (ys.length == 0) return new double[]{-1, 1};
        double lo = ys[0];
        double hi = ys[ys.length - 1];
        double p2 = ys[(int) Math.floor(0.02 * (ys.length - 1))];
        double p98 = ys[(int) Math.ceil(0.98 * (ys.length - 1))];
        double bulk = p98 - p2;
        if (lo < p2 - 2 * bulk) lo = p2;
        if (hi > p98 + 2 * bulk) hi = p98;
        if (hi - lo < 1e-9) {
            lo -= 1;
            hi += 1;
        }
        double pad = (hi - lo) / 10;
        return new double[]{lo - pad, hi + pad};
    }

    /** A step of 1, 2 or 5 times a power of ten that puts about four lines across {@code span}. */
    static double step(double span) {
        double raw = span / 4;
        double ten = Math.pow(10, Math.floor(Math.log10(raw)));
        double m = raw / ten;
        return (m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10) * ten;
    }

    /** A tick value as a decimal with no noise: {@code 0.3}, not {@code 0.30000000000000004}. */
    static String decimal(double v, double step) {
        int places = Math.max(0, (int) -Math.floor(Math.log10(step)));
        BigDecimal d = BigDecimal.valueOf(v).setScale(places, java.math.RoundingMode.HALF_EVEN).stripTrailingZeros();
        return d.signum() == 0 ? "0" : d.toPlainString();
    }

    private double px(double x, double w) {
        return (x - Graph.FROM) / (Graph.TO - Graph.FROM) * w;
    }

    private double py(double y, double h) {
        double v = (yHi - y) / (yHi - yLo) * h;
        return Math.clamp(v, -h, 2 * h);   // off the box is clipped anyway; this keeps the numbers sane
    }

    // ---- drawing ----

    private dev.vexelray.gui.draw.Picture draw(NodeLayout layout) {
        double w = layout.rect().w();
        double h = layout.rect().h();
        if (w < 8 || h < 8) return null;
        double text = layout.textSizePx() > 0 ? layout.textSizePx() : 11;
        Color well = gui.theme().color(Role.WELL);
        Color grid = gui.theme().color(Role.LINE);
        Color axis = gui.theme().color(Role.EDGE);
        Color faint = gui.theme().color(Role.FAINT);
        Color curve = gui.theme().color(Role.ACCENT);
        Color mark = gui.theme().color(Role.INK);

        Sketch s = new Sketch().fill(0, 0, w, h, layout.cornerTopPx(), well);

        // y: horizontal lines with their values at the left edge.
        double step = step(yHi - yLo);
        for (double v = Math.ceil(yLo / step) * step; v <= yHi; v += step) {
            double y = py(v, h);
            s.line(0, y, w, y, 1, Math.abs(v) < step / 2 ? axis : grid);
            if (y < h - 2 * text) s.text(decimal(v, step), 4, y - 3, text, faint);   // clear of the x labels
        }
        // x: a line every five, labelled along the bottom.
        for (int v = Graph.FROM; v <= Graph.TO; v += 5) {
            double x = px(v, w);
            s.line(x, 0, x, h, 1, v == 0 ? axis : grid);
            String label = String.valueOf(v);
            double width = 0.6 * text * label.length();
            s.text(label, Math.clamp(x + 3, 2, w - width - 2), h - 4, text, faint);
        }

        // Places with no real number: a dashed line, a dot on the top edge, and the value's own name.
        double lastLabel = Double.NEGATIVE_INFINITY;
        for (Graph.Sample p : graph.samples()) {
            if (!p.marker()) continue;
            double x = px(p.x(), w);
            for (double y = 0; y < h; y += 8) s.line(x, y, x, Math.min(y + 4, h), 1, Color.withAlpha(mark, 0.5f));
            s.circle(x, 6, 3, mark);
            if (x - lastLabel >= LABEL_SPACING) {
                // On a chip of the background, so a curve running past it cannot swallow it.
                double width = 0.6 * text * p.value().length() + 8;
                double left = x + 6 + width <= w ? x + 6 : x - 6 - width;
                s.fill(left, 0, width, text + 8, 4, well);
                s.outline(left, 0, width, text + 8, 4, 1, Color.withAlpha(mark, 0.4f));
                s.text(p.value(), left + 4, text + 3, text, mark);
                lastLabel = x;
            }
        }

        // The curve: a segment between neighbours both on it, unless it jumps clean across the box (a pole
        // between two samples); a sample with no neighbour on the curve is a dot.
        List<Graph.Sample> all = graph.samples();
        List<double[]> dots = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Graph.Sample p = all.get(i);
            if (!p.onCurve()) continue;
            boolean joined = false;
            if (i > 0 && all.get(i - 1).onCurve() && !across(all.get(i - 1).y(), p.y())) {
                Graph.Sample q = all.get(i - 1);
                s.line(px(q.x(), w), py(q.y(), h), px(p.x(), w), py(p.y(), h), 2, curve);
                joined = true;
            }
            boolean next = i + 1 < all.size() && all.get(i + 1).onCurve() && !across(p.y(), all.get(i + 1).y());
            if (!joined && !next) dots.add(new double[]{px(p.x(), w), py(p.y(), h)});
        }
        for (double[] d : dots) s.circle(d[0], d[1], 2.5, curve);
        return s.picture();
    }

    private boolean across(double a, double b) {
        return (a > yHi && b < yLo) || (a < yLo && b > yHi);
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
        double sx = px(p.x(), w);
        Color ink = gui.theme().color(Role.INK);
        Sketch s = new Sketch().line(sx, 0, sx, h, 1, Color.withAlpha(ink, 0.35f));
        if (p.onCurve() && p.y() >= yLo && p.y() <= yHi) {
            double sy = py(p.y(), h);
            s.circle(sx, sy, 3.5, ink).ring(sx, sy, 6, 1.5, ink);
        }
        figure.overlay(s.picture());
        readout.text(graph.name() + " = " + p.xText() + "   →   " + (p.refused() ? "no value" : p.value()));
    }
}
