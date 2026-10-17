package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;

import java.math.BigDecimal;
import java.util.List;

/**
 * Samples drawn into a box: the grid with its values, the places with no real number, and the curve. One picture,
 * in pixels, for a {@link View} of plot space; shared by the {@link Plot} on the tape and the {@link PlotExplorer},
 * so the two draw a curve the same way and a fix to one is a fix to both.
 *
 * <p>Pure apart from the theme it reads its colours from: no node, no window, so a picture can be made off the GUI
 * thread and handed to a node as a prop.
 */
final class Chart {

    /** Within this many pixels of the last one, a marker keeps its line and dot but not its label. */
    private static final double LABEL_SPACING = 36;

    /**
     * What to draw besides the curve.
     *
     * @param grid   lines at every tick, not only the axes
     * @param points a dot on every sample on the curve, so the sampling shows
     * @param places the places with no real number: a dashed line and the value's own name
     */
    record Style(boolean grid, boolean points, boolean places) {

        static final Style TAPE = new Style(true, false, true);
    }

    private Chart() {
    }

    // ---- the frame ----

    /**
     * A y range for {@code samples}: every one on the curve, except that an end lying far beyond the bulk (more than
     * twice the bulk's own span past its 2nd/98th percentile) is cut back to the bulk, and the result padded by a
     * tenth. Only samples from {@code xLo} to {@code xHi} count.
     */
    static double[] range(List<Graph.Sample> samples, double xLo, double xHi) {
        double[] ys = samples.stream().filter(Graph.Sample::onCurve).filter(s -> s.x() >= xLo && s.x() <= xHi)
                .flatMap(s -> s.terms().values().stream()).mapToDouble(Double::doubleValue)
                .filter(Double::isFinite).sorted().toArray();
        if (ys.length == 0) return new double[]{-1, 1};
        double lo = ys[0];
        double hi = ys[ys.length - 1];
        double p2 = ys[(int) Math.floor(0.02 * (ys.length - 1))];
        double p98 = ys[(int) Math.ceil(0.98 * (ys.length - 1))];
        double bulk = p98 - p2;
        if (lo < p2 - 2 * bulk) lo = p2;
        if (hi > p98 + 2 * bulk) hi = p98;
        if (hi - lo < 1e-9 * Math.max(1, Math.abs(hi))) {
            double half = Math.max(1, Math.abs(hi) / 10);
            lo -= half;
            hi += half;
        }
        double pad = (hi - lo) / 10;
        return new double[]{lo - pad, hi + pad};
    }

    /** {@link #range(List, double, double)} over every sample. */
    static double[] range(Graph graph) {
        return range(graph.samples(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
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

    /** About how wide {@code text} is at {@code size} pixels in the atlas's faces. An estimate, for keeping clear. */
    static double width(String text, double size) {
        return 0.6 * size * text.length();
    }

    // ---- drawing ----

    /**
     * The picture of {@code samples} in {@code view}, in a box {@code w × h} pixels with its top corners rounded by
     * {@code corner}. {@code text} is the label size in pixels. Null when the box is too small to draw in.
     */
    static Picture draw(View view, List<Graph.Sample> samples, Style style, Theme theme,
                        double w, double h, double text, double corner) {
        if (w < 8 || h < 8) return null;
        Color well = theme.color(Role.WELL);
        Color grid = theme.color(Role.LINE);
        Color axis = theme.color(Role.EDGE);
        Color faint = theme.color(Role.FAINT);
        Color mark = theme.color(Role.INK);

        Sketch s = new Sketch().fill(0, 0, w, h, corner, well);

        // y: horizontal lines with their values at the left edge.
        double yStep = step(view.height());
        for (long i = (long) Math.ceil(view.yLo() / yStep); i * yStep <= view.yHi(); i++) {
            double v = i * yStep;
            double y = view.py(v, h);
            if (i == 0 || style.grid()) s.line(0, y, w, y, 1, i == 0 ? axis : grid);
            if (y < h - 2 * text) s.text(decimal(v, yStep), 4, y - 3, text, faint);   // clear of the x labels
        }
        // x: vertical lines, labelled along the bottom.
        double xStep = step(view.width());
        for (long i = (long) Math.ceil(view.xLo() / xStep); i * xStep <= view.xHi(); i++) {
            double v = i * xStep;
            double x = view.px(v, w);
            if (i == 0 || style.grid()) s.line(x, 0, x, h, 1, i == 0 ? axis : grid);
            String label = decimal(v, xStep);
            s.text(label, Math.clamp(x + 3, 2, Math.max(2, w - width(label, text) - 2)), h - 4, text, faint);
        }

        if (style.places()) places(s, view, samples, w, h, text, well, mark);
        for (Graph.Curve c : Graph.curves(samples)) curve(s, view, c.samples(), style, w, h, color(c.order(), theme));
        return s.picture();
    }

    /** The colour of a curve of multiples of {@code order}: the accent for plain numbers. */
    static Color color(Graph.Order order, Theme theme) {
        return theme.color(switch (order) {
            case ONE -> Role.ACCENT;
            case ZERO -> Look.ZERO_CURVE;
            case OMEGA -> Look.OMEGA_CURVE;
        });
    }

    /** Places with no real number: a dashed line, a dot on the top edge, and the value's own name. */
    private static void places(Sketch s, View view, List<Graph.Sample> samples, double w, double h, double text,
                               Color well, Color mark) {
        double lastLabel = Double.NEGATIVE_INFINITY;
        Color dash = Color.withAlpha(mark, 0.5f);
        Color edge = Color.withAlpha(mark, 0.4f);
        for (Graph.Sample p : samples) {
            if (!p.marker() || !view.showsX(p.x())) continue;
            double x = view.px(p.x(), w);
            for (double y = 0; y < h; y += 8) s.line(x, y, x, Math.min(y + 4, h), 1, dash);
            s.circle(x, 6, 3, mark);
            if (x - lastLabel >= LABEL_SPACING) {
                // On a chip of the background, so a curve running past it cannot swallow it.
                double width = width(p.value(), text) + 8;
                double left = x + 6 + width <= w ? x + 6 : x - 6 - width;
                s.fill(left, 0, width, text + 8, 4, well);
                s.outline(left, 0, width, text + 8, 4, 1, edge);
                s.text(p.value(), left + 4, text + 3, text, mark);
                lastLabel = x;
            }
        }
    }

    /**
     * One {@linkplain Graph#curves curve}, in its order's {@link #color}: a run of segments between neighbours both on
     * it, broken where one jumps clean across the box (a pole between two samples); a sample with no such neighbour
     * is a dot.
     */
    private static void curve(Sketch s, View view, List<Graph.Sample> all, Style style, double w, double h,
                              Color color) {
        int n = all.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        int run = 0;
        for (int i = 0; i < n; i++) {
            Graph.Sample p = all.get(i);
            boolean joins = run > 0 && p.onCurve() && !across(view, all.get(i - 1).y(), p.y());
            if (!joins) {
                flush(s, xs, ys, run, color);
                run = 0;
            }
            if (p.onCurve()) {
                xs[run] = view.px(p.x(), w);
                ys[run] = py(view, p.y(), h);
                run++;
            }
        }
        flush(s, xs, ys, run, color);

        if (style.points()) {
            for (Graph.Sample p : all) {
                if (p.onCurve() && view.showsX(p.x()) && p.y() >= view.yLo() && p.y() <= view.yHi()) {
                    s.circle(view.px(p.x(), w), view.py(p.y(), h), 2, color);
                }
            }
        }
    }

    private static void flush(Sketch s, double[] xs, double[] ys, int run, Color color) {
        if (run == 1) {
            s.circle(xs[0], ys[0], 2.5, color);
        } else if (run > 1) {
            s.polyline(java.util.Arrays.copyOf(xs, run), java.util.Arrays.copyOf(ys, run), 2, color);
        }
    }

    /** Off the box is clipped anyway; this keeps the numbers sane. */
    private static double py(View view, double y, double h) {
        return Math.clamp(view.py(y, h), -h, 2 * h);
    }

    private static boolean across(View view, double a, double b) {
        return (a > view.yHi() && b < view.yLo()) || (a < view.yLo() && b > view.yHi());
    }
}
