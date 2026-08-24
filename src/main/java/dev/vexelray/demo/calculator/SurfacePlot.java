package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.plot.Camera;
import dev.vexelray.gui.plot.Cell;
import dev.vexelray.gui.plot.Enclosure;
import dev.vexelray.gui.plot.Expr;
import dev.vexelray.gui.plot.Framing;
import dev.vexelray.gui.plot.Interval;
import dev.vexelray.gui.plot.Span;
import dev.vexelray.gui.plot.Volume;
import dev.vexelray.text.TextLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * A surface of two variables, drawn in three dimensions — {@link PlotSurface}'s counterpart, and the same
 * argument one axis further on.
 *
 * <h2>A reliable surface is drawn out of boxes too</h2>
 * The curve plot's claim is that a column of x classifies to a vertical span, and a vertical span is a box, so a
 * reliable plot has nothing diagonal in it. The generalisation is exact rather than analogous: a <b>cell</b> of
 * {@code (x, y)} classifies to a span of z, and a cell crossed with a span of z is an <b>axis-aligned box in
 * space</b>. Every square of the grid below is one such box — flat where the surface is tame, tall where it is
 * steep, the full height of the volume where the arithmetic could not bound it, which is the same honest answer
 * a painted column gives on a curve.
 *
 * <h2>What is claimed, and what is not</h2>
 * A box in space projects to a hexagon, and what is drawn is that hexagon's <b>screen-space bounding
 * rectangle</b> — a conservative cover. So the claim this renderer makes is per cell and it is a real one:
 *
 * <blockquote>the rectangle drawn for a cell contains every point of the surface above that cell</blockquote>
 *
 * and it is deliberately <em>weaker</em> than the curve plot's, which is per pixel. Two things follow and are
 * worth stating rather than discovering:
 * <ul>
 *   <li><b>the cover is loose.</b> A bounding rectangle is up to twice the hexagon it covers, so the surface
 *       reads a little chunky. That is the same trade the fill makes — over-cover rather than under-draw;
 *   <li><b>the painting order is presentation, not proof.</b> Cells are drawn far to near by
 *       {@link Camera#depthKey}, which is the ordering every heightmap renderer uses and is a statement about
 *       surfaces standing over a grid rather than a theorem about boxes in general.
 * </ul>
 *
 * <h2>How it is shaded</h2>
 * By its own calculus. Every cell carries the five partial derivatives of the surface there — first and second,
 * exact, from {@code Expr.derivative} — and {@link Sheen} turns them into a cavity term from the curvature and a
 * Fresnel term from the angle to the eye. Both are effects a triangle renderer finds hard for the same reason: a
 * mesh estimates its normals and its curvature from neighbouring vertices, while this has them analytically.
 *
 * <p>The derivatives are the one thing here evaluated <b>at a point</b> rather than over a cell, which in this
 * module is worth saying out loud. It is shading only: the box that is drawn is still the proven enclosure over
 * the whole cell, and nothing the shading computes can move an edge of it, remove a cell or invent one. See
 * {@link #slopeAt} for why a point is also the <em>better</em> answer here.
 *
 * <h2>Turning it costs no arithmetic</h2>
 * An enclosure belongs to a cell of the domain and knows nothing about where the eye is, so orbiting re-projects,
 * re-sorts and re-shades while <b>asking the arithmetic nothing</b> — the cache reports every cell as a hit
 * across a turn. Only a change of the floor's extent — the one domain transform there is — evaluates anything,
 * and a scale visited before is answered from the cache. Between gestures the picture is a tree of pooled nodes
 * that simply sits there: no timer, no per-frame work, nothing scheduled. The surface is retained while it is
 * idle because there is nothing for it to do.
 *
 * <h2>There is no pan, deliberately</h2>
 * A curve is explored by sliding its window along x, and a surface has no such direction once it has been
 * turned: "left" on the screen is a different way through the domain at every yaw. So the floor stays centred
 * on the origin and the gestures divide cleanly — the drag turns the picture, the zoom widens or narrows the
 * window, and neither has to guess what the other meant.
 */
final class SurfacePlot {

    /** How many cells across the floor. Each one is a node, so this is the picture's whole budget. */
    private static final int CELLS = 40;

    /** One notch of zoom, matching the curve plot's: a root of two, so two notches are a doubling. */
    private static final double SCALE_STEP = Math.sqrt(2);

    /** How far the zoom may travel from the framing pass's floor, in notches. */
    private static final int ZOOM_LIMIT = 20;

    /** How many scales of cached cells to keep. */
    private static final int CACHED_SCALES = 6;

    /** How much of the canvas the projected volume fills, leaving room for it to swing as it turns. */
    private static final double FIT = 0.86;

    /** A cell thinner than this is drawn this thick, so a flat surface is a surface rather than nothing. */
    private static final float MIN_CELL_DP = 2f;

    /** A drag across the whole canvas turns the picture this far. */
    private static final double DRAG_YAW = Math.PI;
    private static final double DRAG_PITCH = Math.PI / 2;

    /** One press of an arrow key, in radians. */
    private static final double KEY_TURN = Math.toRadians(6);

    /** The height legend down the right-hand edge: how many bands, and how wide, in dp. */
    private static final int LEGEND_BANDS = 24;
    private static final float LEGEND_W = 13;
    private static final float LEGEND_INSET = 10;
    private static final float LEGEND_LABEL_W = 52;
    private static final float LEGEND_LABEL_H = 13;

    /** How far the far side of the volume is dimmed toward the ground, at most. */
    private static final float DEPTH_DIM = 0.45f;

    private final Gui gui;
    private final Node canvas;
    private final Node cellLayer;
    private final Node legendLayer;
    private final List<Node> cellPool = new ArrayList<>();
    private final List<Node> legendPool = new ArrayList<>();
    private final List<Node> legendLabels = new ArrayList<>();

    private final Consumer<String> readout;

    /** Cached samples, by floor scale and then by packed cell index — {@link PlotSurface}'s cache, squared. */
    private final Map<Long, Map<Long, Sample>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Map<Long, Sample>> eldest) {
            return size() > CACHED_SCALES;
        }
    };

    private final AtomicInteger revision = new AtomicInteger();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final Object painting = new Object();

    // --- the plot state, guarded by this -------------------------------------------------------------

    private Expr expr;
    /** The five partials the shading needs, differentiated once per expression rather than once per cell. */
    private Expr[] partials;
    private String across = "x";
    private String into = "y";
    /** The volume the framing pass chose: what "reset" goes back to, and what the zoom notches scale. */
    private Volume home = Volume.about(Framing.DEFAULT_HALF_WIDTH, Framing.DEFAULT_HALF_HEIGHT);
    private int zoom;
    private double zLo;
    private double zHi;
    private Camera camera = Camera.DEFAULT;
    private float dragX;
    private float dragY;

    SurfacePlot(Gui gui, Consumer<String> readout) {
        this.gui = gui;
        this.readout = readout;
        this.canvas = gui.box()
                .width(Length.FILL).height(Length.grow(1))
                .background(Palette.PLOT_BG)
                .corner(Length.rem(0.5f))
                .scroll(false, false);
        this.cellLayer = layer();
        this.legendLayer = layer();
        canvas.children(cellLayer, legendLayer);
        gui.onDrag(canvas, this::drag);
        reset();
        gui.onResize(canvas, box -> invalidate());
    }

    Node node() {
        return canvas;
    }

    // --- what is plotted ------------------------------------------------------------------------------

    /** Plot {@code plottable} as a surface, framed automatically, on a worker. */
    void show(Plottable plottable) {
        gui.async(() -> showNow(plottable));
    }

    /** {@link #show} without the worker: the framing pass runs on the caller. */
    void showNow(Plottable plottable) {
        Volume framed = Framing.automatic(plottable.expr(), plottable.across(), plottable.into());
        synchronized (cache) {
            cache.clear();
        }
        Expr[] derivatives = differentiate(plottable);
        synchronized (this) {
            this.expr = plottable.expr();
            this.partials = derivatives;
            this.across = plottable.across();
            this.into = plottable.into();
            this.home = framed;
            this.camera = Camera.DEFAULT;
            reset();
        }
        invalidate();
    }

    /**
     * The five partials {@link Sheen} shades with, or null when this expression cannot supply all of them.
     * Differentiated <b>once per expression</b> — the trees are the expensive part to build and the cheap part
     * to evaluate, and building them per cell would be doing the same work sixteen hundred times.
     *
     * <p>An expression carrying a node that cannot differentiate itself simply gets no shading, the same way it
     * gets no extrema: the surface falls back to its height ramp, which is what it looked like before any of
     * this existed. That is the open interface's cost, paid where it is visible rather than guessed around.
     */
    private static Expr[] differentiate(Plottable plottable) {
        String x = plottable.across();
        String y = plottable.into();
        Expr[] found = new Expr[5];
        java.util.Optional<Expr> dx = plottable.expr().derivative(x);
        java.util.Optional<Expr> dy = plottable.expr().derivative(y);
        if (dx.isEmpty() || dy.isEmpty()) {
            return null;
        }
        found[0] = dx.get();
        found[1] = dy.get();
        java.util.Optional<Expr> dxx = dx.get().derivative(x);
        java.util.Optional<Expr> dyy = dy.get().derivative(y);
        java.util.Optional<Expr> dxy = dx.get().derivative(y);
        if (dxx.isEmpty() || dyy.isEmpty() || dxy.isEmpty()) {
            return null;
        }
        found[2] = dxx.get();
        found[3] = dyy.get();
        found[4] = dxy.get();
        return found;
    }

    /** Paint on the calling thread and return once the picture is up — the headless capture's handshake. */
    void settle() {
        paint(revision.incrementAndGet());
    }

    // --- the transforms -------------------------------------------------------------------------------

    /** Back to the framed volume and the three-quarter view. */
    synchronized void reset() {
        zoom = 0;
        zLo = home.zLo();
        zHi = home.zHi();
        camera = Camera.DEFAULT;
    }

    /** Re-fit the height to whatever the surface does across the floor now on show. */
    void fitVertically() {
        gui.async(() -> {
            Expr e;
            Volume v;
            String a;
            String b;
            synchronized (this) {
                e = expr;
                v = volume();
                a = across;
                b = into;
            }
            if (e == null) {
                return;
            }
            Volume fitted = Framing.refit(e, v, a, b);
            synchronized (this) {
                zLo = fitted.zLo();
                zHi = fitted.zHi();
            }
            invalidate();
        });
    }

    /** Narrow or widen the floor by {@code notches} — the only thing that costs arithmetic. */
    synchronized void zoom(int notches) {
        zoom = Math.max(-ZOOM_LIMIT, Math.min(ZOOM_LIMIT, zoom + notches));
    }

    /** Turn the picture. The drag, the arrow keys, and nothing the arithmetic ever hears about. */
    synchronized void turn(double yaw, double pitch) {
        camera = camera.turned(yaw, pitch);
    }

    /** One arrow key's worth of turn. */
    void nudge(double yawSteps, double pitchSteps) {
        turn(yawSteps * KEY_TURN, pitchSteps * KEY_TURN);
        invalidate();
    }

    /** A wheel notch over the canvas widens or narrows the floor. Reports whether it was over the plot. */
    boolean wheel(double notches, int x, int y) {
        Rect rect = canvas.layout().rect();
        if (rect.w() <= 0 || !rect.contains(x, y)) {
            return false;
        }
        zoom((int) Math.signum(notches) * Math.max(1, (int) Math.abs(notches)));
        invalidate();
        return true;
    }

    /** A drag turns the picture: across the canvas is yaw, up and down it is pitch. */
    private void drag(DragEvent e) {
        synchronized (this) {
            if (e.phase() == DragEvent.Phase.START) {
                dragX = e.x();
                dragY = e.y();
                return;
            }
            // Rightward drag turns the yaw up, which is also what the right arrow key does. The two used to
            // disagree -- the drag negated and the key did not -- so pushing the picture one way with the mouse
            // and the other way with the keyboard were the same gesture with opposite results.
            double dYaw = (e.x() - dragX) / Math.max(1f, e.nodeW()) * DRAG_YAW;
            double dPitch = (e.y() - dragY) / Math.max(1f, e.nodeH()) * DRAG_PITCH;
            camera = camera.turned(dYaw, dPitch);
            dragX = e.x();
            dragY = e.y();
        }
        invalidate();
    }

    // --- geometry -------------------------------------------------------------------------------------

    private float[] size() {
        Rect content = canvas.layout().content();
        float dpi = Math.max(0.01f, gui.dpi().value());
        return new float[]{content.w() / dpi, content.h() / dpi};
    }

    /**
     * The floor's half-width at the current zoom — a pure function of the notch count, which is what makes a
     * scale reproducible and therefore cacheable, exactly as the curve plot's width is.
     */
    private synchronized double half() {
        return home.xWidth() / 2 * Math.pow(SCALE_STEP, -zoom);
    }

    private synchronized Volume volume() {
        double h = half();
        return new Volume(-h, h, -h, h, zLo, zHi);
    }

    /**
     * The plot-space size of one cell. Every use goes through here rather than through the volume's width
     * divided by the count, for the reason the curve plot gives: two expressions that agree mathematically and
     * land an ulp apart would turn every redraw into a total cache miss.
     */
    private synchronized double unit() {
        return half() * 2 / CELLS;
    }

    // --- rendering ------------------------------------------------------------------------------------

    /** Schedule a repaint. The latest request wins; earlier ones drop their work rather than paint it stale. */
    void invalidate() {
        int mine = revision.incrementAndGet();
        gui.async(() -> paint(mine));
    }

    private void paint(int mine) {
        float[] size = size();
        if (size[0] < 2 || size[1] < 2) {
            return;                                  // not laid out yet; the next invalidate will find it
        }
        Expr e;
        Volume v;
        Camera eye;
        String a;
        String b;
        double u;
        synchronized (this) {
            e = expr;
            v = volume();
            eye = camera;
            a = across;
            b = into;
            u = unit();
        }
        if (e == null) {
            return;
        }
        List<Patch> patches = new ArrayList<>(CELLS * CELLS);
        for (int i = 0; i < CELLS; i++) {
            long ix = i - CELLS / 2L;
            for (int j = 0; j < CELLS; j++) {
                long iy = j - CELLS / 2L;
                Patch patch = patch(e, v, eye, a, b, u, ix, iy);
                if (patch != null) {
                    patches.add(patch);
                }
            }
            if (revision.get() != mine) {
                return;                              // overtaken mid-evaluation: whoever overtook us will draw
            }
        }
        // Far to near. The order is the whole of the occlusion, and it is a property of the floor rather than
        // of the boxes standing on it -- see Camera.depthKey.
        patches.sort(Comparator.comparingDouble(Patch::depth).reversed());
        synchronized (painting) {
            if (revision.get() != mine) {
                return;
            }
            gui.batch(() -> {
                drawPatches(patches, eye, size);
                drawLegend(v, size);
            });
        }
        readout.accept(bounds(v, eye));
    }

    /**
     * One cell, from its enclosure to the rectangle that covers it. Null when there is nothing there — an
     * undefined cell, or a surface that has left the volume entirely at this point.
     */
    private Patch patch(Expr e, Volume v, Camera eye, String a, String b, double u, long ix, long iy) {
        Sample sample = sample(e, u, a, b, ix, iy);
        Extent extent = new Extent();
        Span.of(sample.height(), v.zLo(), v.zHi()).emitTo(0, extent);
        if (extent.blank) {
            return null;
        }
        double zTop = v.zAt(extent.top);
        double zBottom = v.zAt(extent.bottom);
        double x0 = ix * u;
        double x1 = (ix + 1) * u;
        double y0 = iy * u;
        double y1 = (iy + 1) * u;
        // Normalised into the unit box the camera projects, so the camera never learns what a volume is.
        double nx0 = (x0 - v.xLo()) / v.xWidth() - 0.5;
        double nx1 = (x1 - v.xLo()) / v.xWidth() - 0.5;
        double ny0 = (y0 - v.yLo()) / v.yDepth() - 0.5;
        double ny1 = (y1 - v.yLo()) / v.yDepth() - 0.5;
        double nzLo = (zBottom - v.zLo()) / v.zHeight() - 0.5;
        double nzHi = (zTop - v.zLo()) / v.zHeight() - 0.5;
        double minU = Double.MAX_VALUE;
        double maxU = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            Camera.Point p = eye.project((corner & 1) == 0 ? nx0 : nx1,
                                         (corner & 2) == 0 ? ny0 : ny1,
                                         (corner & 4) == 0 ? nzLo : nzHi);
            minU = Math.min(minU, p.u());
            maxU = Math.max(maxU, p.u());
            minV = Math.min(minV, p.v());
            maxV = Math.max(maxV, p.v());
        }
        double midX = (nx0 + nx1) / 2;
        double midY = (ny0 + ny1) / 2;
        // The height the colour reads, as a fraction of the volume: the middle of the enclosure, so a tall
        // uncertain cell is coloured by where it is rather than by how unsure it is.
        double heat = 1 - (extent.top + extent.bottom) / 2;
        return new Patch(minU, maxU, minV, maxV, eye.depthKey(midX, midY), heat, extent.filled,
                         normalised(sample.slope(), v));
    }

    /**
     * The cell's raw derivatives, scaled into the volume's normalised cube. A partial is a ratio of two lengths,
     * so it scales by the ratio of the two axes' extents — which is why this cannot be cached with the sample:
     * the floor and the height move independently, and <b>Fit</b> changes one without touching the other.
     */
    private static Sheen.Slope normalised(double[] raw, Volume v) {
        if (raw == null) {
            return null;
        }
        double sx = v.xWidth() / v.zHeight();
        double sy = v.yDepth() / v.zHeight();
        return new Sheen.Slope(raw[0] * sx, raw[1] * sy,
                               raw[2] * sx * v.xWidth(), raw[3] * sy * v.yDepth(), raw[4] * sx * v.yDepth());
    }

    /** The cached sample for one cell — its height, and the derivatives that shade it. */
    private Sample sample(Expr e, double u, String a, String b, long ix, long iy) {
        Map<Long, Sample> scale;
        synchronized (cache) {
            scale = cache.computeIfAbsent(Double.doubleToLongBits(u), key -> new ConcurrentHashMap<>());
        }
        long key = (ix & 0xFFFFFFFFL) << 32 | (iy & 0xFFFFFFFFL);
        Sample known = scale.get(key);
        if (known != null) {
            hits.increment();
            return known;
        }
        misses.increment();
        Cell cell = Volume.cellAt(ix, iy, u, a, b);
        Sample computed = new Sample(e.enclose(cell), slopeAt(u, a, b, ix, iy));
        scale.put(key, computed);
        return computed;
    }

    /**
     * The five partials at the <b>centre</b> of a cell, raw — not yet scaled into the volume's normalised space,
     * because that scaling depends on the height on show and this is cached against the cell alone.
     *
     * <p><b>This is the one place in either renderer that evaluates at a point.</b> It is worth being loud about
     * that, since the module's whole identity is that point sampling cannot be made honest. Two things make it
     * legitimate here and neither is a loophole. It is used for <em>shading only</em>: the box that gets drawn
     * is still the proven enclosure over the whole cell, and nothing computed here can move an edge of it or
     * make a cell appear where the arithmetic said there was nothing. And the alternative is worse rather than
     * better — enclosing a derivative over the cell gives a range, and near a fold that range is enormous, so
     * its midpoint would be a number with no relationship to the surface. A point where the surface is smooth
     * is the honest thing to shade by; a point where it is not comes back non-finite, and
     * {@link Sheen.Slope#usable} drops the shading for that cell rather than inventing it.
     */
    private double[] slopeAt(double u, String a, String b, long ix, long iy) {
        Expr[] derivatives;
        synchronized (this) {
            derivatives = partials;
        }
        if (derivatives == null) {
            return null;
        }
        double x = (ix + 0.5) * u;
        double y = (iy + 0.5) * u;
        Cell at = Cell.of(a, Interval.at(x), b, Interval.at(y));
        double[] raw = new double[derivatives.length];
        for (int i = 0; i < derivatives.length; i++) {
            Double value = midpoint(derivatives[i].enclose(at));
            if (value == null) {
                return null;                     // a pole in a derivative: this cell keeps its flat colour
            }
            raw[i] = value;
        }
        return raw;
    }

    /** A point enclosure read back as a number, through the sink rather than by asking its type. */
    private static Double midpoint(Enclosure enclosure) {
        Double[] read = new Double[1];
        enclosure.emitTo(new Enclosure.Sink() {
            @Override
            public void bounded(java.math.BigDecimal lo, java.math.BigDecimal hi) {
                double m = lo.add(hi).doubleValue() / 2;
                read[0] = Double.isFinite(m) ? m : null;
            }

            @Override
            public void unbounded() {
                // no number here
            }

            @Override
            public void undefined() {
                // nor here
            }
        });
        return read[0];
    }

    /** One cell's answers: the height the box is built from, and the raw derivatives the shading needs. */
    private record Sample(Enclosure height, double[] slope) {
    }

    /** The projected patches, back to front, one pooled box each. */
    private void drawPatches(List<Patch> patches, Camera eye, float[] size) {
        double[] view = eye.viewDirection();
        double[] reach = eye.reach();
        double scale = FIT * Math.min(size[0] / (2 * Math.max(1e-9, reach[0])),
                                      size[1] / (2 * Math.max(1e-9, reach[1])));
        float midX = size[0] / 2;
        float midY = size[1] / 2;
        double farthest = patches.isEmpty() ? 1 : patches.get(0).depth();
        double nearest = patches.isEmpty() ? 0 : patches.get(patches.size() - 1).depth();
        double span = Math.max(1e-9, farthest - nearest);
        int drawn = 0;
        for (Patch p : patches) {
            float x = (float) (midX + p.minU() * scale);
            float y = (float) (midY - p.maxV() * scale);          // screen y grows downward, v upward
            float w = (float) Math.max(MIN_CELL_DP, (p.maxU() - p.minU()) * scale);
            float h = (float) Math.max(MIN_CELL_DP, (p.maxV() - p.minV()) * scale);
            if (x > size[0] || y > size[1] || x + w < 0 || y + h < 0) {
                continue;                                          // wholly off the canvas
            }
            float distance = (float) ((p.depth() - nearest) / span);
            pooled(cellPool, cellLayer, drawn++)
                    .visible(true)
                    .background(p.filled()
                            ? Palette.SURFACE_POLE
                            : shade(Sheen.shade(heatColour(p.heat()), p.slope(), view), distance))
                    .size(Length.dp(w), Length.dp(h))
                    .floatAt(Length.dp(x), Length.dp(y));
        }
        hideFrom(cellPool, drawn);
    }

    /**
     * The height legend: a ramp down the right-hand edge with the volume's floor, middle and ceiling on it.
     *
     * <p>It is the one piece of furniture here, and it is here because there is no other way to read a number
     * off this picture. A curve plot has gridlines; a surface cannot, because a line between two points of a
     * projected grid is diagonal and this renderer has nothing diagonal in it. So the height axis is given
     * separately, in screen space, where it costs one box per band.
     */
    private void drawLegend(Volume v, float[] size) {
        float top = LEGEND_INSET;
        float height = Math.max(LEGEND_BANDS, size[1] - 2 * LEGEND_INSET - LEGEND_LABEL_H);
        float band = height / LEGEND_BANDS;
        float x = size[0] - LEGEND_INSET - LEGEND_W;
        for (int i = 0; i < LEGEND_BANDS; i++) {
            pooled(legendPool, legendLayer, i)
                    .visible(true)
                    .background(heatColour(1 - (i + 0.5f) / LEGEND_BANDS))
                    .size(Length.dp(LEGEND_W), Length.dp(band + 1))
                    .floatAt(Length.dp(x), Length.dp(top + i * band));
        }
        float labelX = x - LEGEND_LABEL_W - 4;
        legendLabel(0, format(v.zHi()), labelX, top - LEGEND_LABEL_H / 2);
        legendLabel(1, format((v.zHi() + v.zLo()) / 2), labelX, top + height / 2 - LEGEND_LABEL_H / 2);
        legendLabel(2, format(v.zLo()), labelX, top + height - LEGEND_LABEL_H / 2);
    }

    private void legendLabel(int index, String text, float x, float y) {
        while (legendLabels.size() <= index) {
            Node made = gui.text("").hitInert(true).scroll(false, false);
            legendLayer.append(made);
            legendLabels.add(made);
        }
        legendLabels.get(index)
                .visible(true)
                .text(text)
                .size(Length.dp(LEGEND_LABEL_W), Length.dp(LEGEND_LABEL_H))
                .textSize(Length.rem(0.6875f)).textColor(Palette.DIM)
                .align(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE)
                .floatAt(Length.dp(x), Length.dp(y));
    }

    /** The height ramp: cool at the floor, warm at the ceiling. {@code heat} runs 0 at the bottom to 1 at the top. */
    private static Color heatColour(double heat) {
        double t = Math.max(0, Math.min(1, heat));
        return t < 0.5
                ? mix(Palette.LOW, Palette.MID, t * 2)
                : mix(Palette.MID, Palette.HIGH, (t - 0.5) * 2);
    }

    /** Toward the plot's own ground with distance, so the far side of the surface recedes rather than crowds. */
    private static Color shade(Color colour, float distance) {
        return mix(colour, Palette.PLOT_BG, Math.max(0, Math.min(1, distance)) * DEPTH_DIM);
    }

    private static Color mix(Color from, Color to, double t) {
        float f = (float) t;
        return Color.rgba(from.r() + (to.r() - from.r()) * f,
                          from.g() + (to.g() - from.g()) * f,
                          from.b() + (to.b() - from.b()) * f,
                          from.a() + (to.a() - from.a()) * f);
    }

    /** What the cache saved since this was last asked, as "hits/asks". Read by {@code --capture-surface}. */
    String cacheReport() {
        long hit = hits.sumThenReset();
        long asked = hit + misses.sumThenReset();
        return hit + "/" + asked + " cached";
    }

    private String bounds(Volume v, Camera eye) {
        return across + ", " + into + ": [" + format(v.xLo()) + ", " + format(v.xHi()) + "]"
                + "    z: [" + format(v.zLo()) + ", " + format(v.zHi()) + "]"
                + "    turned " + Math.round(Math.toDegrees(eye.yaw())) % 360
                + ", tilted " + Math.round(Math.toDegrees(eye.pitch()));
    }

    /** As many decimals as the number needs and no more. */
    private static String format(double value) {
        if (value == 0) {
            return "0";
        }
        double magnitude = Math.abs(value);
        if (magnitude >= 1e5 || magnitude < 1e-3) {
            return String.format("%.1e", value);
        }
        String s = String.format("%.3f", value);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    // --- the node pools -------------------------------------------------------------------------------

    private Node pooled(List<Node> pool, Node layer, int index) {
        while (pool.size() <= index) {
            Node made = gui.box();
            made.hitInert(true).scroll(false, false);
            layer.append(made);
            pool.add(made);
        }
        return pool.get(index);
    }

    private static void hideFrom(List<Node> pool, int index) {
        for (int i = index; i < pool.size(); i++) {
            pool.get(i).visible(false);
        }
    }

    private Node layer() {
        return gui.box()
                .width(Length.percent(100)).height(Length.percent(100))
                .floatAt(Length.ZERO, Length.ZERO)
                .hitInert(true)
                .scroll(false, false);
    }

    /** One cell, projected: the rectangle that covers it, how far away it is, and how to colour it. */
    private record Patch(double minU, double maxU, double minV, double maxV,
                         double depth, double heat, boolean filled, Sheen.Slope slope) {
    }

    /**
     * Reads a classified cell back out through the sink — the same inversion the curve renderer performs, and
     * for the same reason: the set of answers stays open while the set of operations stays closed.
     */
    private static final class Extent implements Span.Sink {

        private double top;
        private double bottom;
        private boolean blank = true;
        private boolean filled;

        @Override
        public void curve(int column, double top, double bottom) {
            this.top = top;
            this.bottom = bottom;
            this.blank = false;
        }

        @Override
        public void fill(int column) {
            // The honest answer, and the one that makes a pole in a surface legible: the expression leaves
            // every bound somewhere over this cell, so the cell is a column of the whole volume.
            this.top = 0;
            this.bottom = 1;
            this.blank = false;
            this.filled = true;
        }

        @Override
        public void blank(int column) {
            this.blank = true;
        }
    }
}
