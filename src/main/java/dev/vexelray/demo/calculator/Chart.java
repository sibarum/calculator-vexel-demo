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
                .flatMap(s -> s.series().values().stream()).mapToDouble(Double::doubleValue)
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
        List<Graph.Curve> curves = Graph.curves(samples);
        for (int k = 0; k < curves.size(); k++) {
            Graph.Curve c = curves.get(k);
            curve(s, view, c.samples(), coinciding(curves, k), style, w, h, color(c.order(), theme));
        }
        return s.picture();
    }

    /**
     * For each sample, whether curve {@code k} stands where an earlier curve does, so that drawn solid it would hide
     * that one: {@code (3x + 0 + ω)^2} has {@code 6x} at both {@code 0} and {@code ω}.
     */
    static boolean[] coinciding(List<Graph.Curve> curves, int k) {
        List<Graph.Sample> mine = curves.get(k).samples();
        boolean[] out = new boolean[mine.size()];
        for (int i = 0; i < mine.size(); i++) {
            Graph.Sample p = mine.get(i);
            if (!p.onCurve()) continue;
            for (int j = 0; j < k && !out[i]; j++) {
                Graph.Sample q = curves.get(j).samples().get(i);
                out[i] = q.onCurve() && Math.abs(q.y() - p.y()) <= 1e-9 * Math.max(1, Math.abs(p.y()));
            }
        }
        return out;
    }

    /** The colour of a curve of multiples of {@code order}: the accent for plain numbers. */
    static Color color(Graph.Order order, Theme theme) {
        return theme.color(Look.curve(order.power()));
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
     * is a dot. A segment between two samples both {@code coinciding} with an earlier curve is dashed, so that curve's
     * colour shows through the gaps.
     */
    private static void curve(Sketch s, View view, List<Graph.Sample> all, boolean[] coinciding, Style style,
                              double w, double h, Color color) {
        int n = all.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        int run = 0;
        boolean linked = false;   // whether the run's first point ends a dashed segment, so it is no lone dot
        double phase = 0;
        for (int i = 0; i < n; i++) {
            Graph.Sample p = all.get(i);
            boolean joins = run > 0 && p.onCurve() && !across(view, all.get(i - 1).y(), p.y());
            if (!joins) {
                flush(s, xs, ys, run, linked, color);
                run = 0;
                linked = false;
                phase = 0;
            }
            if (!p.onCurve()) continue;
            double x = view.px(p.x(), w);
            double y = py(view, p.y(), h);
            if (joins && coinciding[i - 1] && coinciding[i]) {
                phase = dashed(s, xs[run - 1], ys[run - 1], x, y, phase, color);
                if (run > 1) flush(s, xs, ys, run, true, color);
                run = 0;
                linked = true;
            } else {
                phase = 0;
            }
            xs[run] = x;
            ys[run] = y;
            run++;
        }
        flush(s, xs, ys, run, linked, color);

        if (style.points()) {
            for (Graph.Sample p : all) {
                if (p.onCurve() && view.showsX(p.x()) && p.y() >= view.yLo() && p.y() <= view.yHi()) {
                    s.circle(view.px(p.x(), w), view.py(p.y(), h), 2, color);
                }
            }
        }
    }

    /** A dash and a gap, in pixels, for a curve drawn over one it coincides with. */
    private static final double DASH = 6;
    private static final double GAP = 5;

    /**
     * The segment from {@code (x0, y0)} to {@code (x1, y1)} dashed, carrying on from {@code phase} pixels into the
     * dash-and-gap cycle so that dashes run evenly across segments; returns the phase at its end.
     */
    private static double dashed(Sketch s, double x0, double y0, double x1, double y1, double phase, Color color) {
        double length = Math.hypot(x1 - x0, y1 - y0);
        double cycle = DASH + GAP;
        double at = 0;
        while (at < length) {
            double into = (phase + at) % cycle;
            double next = Math.min(length, at + (into < DASH ? DASH - into : cycle - into));
            if (into < DASH) {
                double t0 = at / length;
                double t1 = next / length;
                s.line(x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0, x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1, 2, color);
            }
            at = next;
        }
        return (phase + length) % cycle;
    }

    /** A run of points: a polyline, or a dot where it is one point that no dashed segment reaches. */
    private static void flush(Sketch s, double[] xs, double[] ys, int run, boolean linked, Color color) {
        if (run == 1 && !linked) {
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
