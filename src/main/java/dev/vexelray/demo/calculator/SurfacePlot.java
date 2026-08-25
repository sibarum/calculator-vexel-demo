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
 * <h2>Two layers, because they say two different things</h2>
 * Boxes alone read as blocky, and the fix is not to draw finer boxes — it is to stop asking one layer to be
 * both the evidence and the picture.
 *
 * <ul>
 *   <li>the <b>proof</b> is the enclosure over each cell, drawn as a <em>hollow outline</em>. An enclosure is a
 *       claim about a range, so its edges are what it has to say; a filled box would be claiming the surface is
 *       everywhere inside it. Where the arithmetic is tight the outline is a thin sliver and disappears into
 *       the surface; where it is loose — a steep cell, a fold — it stands visibly taller than the surface
 *       threading through it, which is exactly where a reader should be looking;
 *   <li>the <b>picture</b> is a smooth surface bilinearly interpolated between the cell lattice's corners,
 *       {@link #SUB} pieces per cell per axis, shaded by {@link Sheen}. Interpolating is joining up points that
 *       were evaluated — the very thing the module refuses to do on its own — and it is safe here for one
 *       reason and only one: <b>it is drawn inside the outline that contains it.</b> The enclosure covers every
 *       value the surface takes on the cell, the interpolation's own corner heights are among them, so the
 *       smooth layer can never draw outside the honest one.
 * </ul>
 *
 * <p>It is also much cheaper than one fine layer would have been. An enclosure costs interval arithmetic over
 * {@code BigDecimal} and an interpolation costs four multiplies, so the proof stays coarse while the picture
 * gets nine times the resolution for one height and one gradient per lattice corner.
 *
 * <h2>What is claimed, and what is not</h2>
 * A box in space projects to a hexagon, and what is drawn is that hexagon's <b>screen-space bounding
 * rectangle</b> — a conservative cover. So the claim this renderer makes is per cell and it is a real one:
 *
 * <blockquote>the outline drawn for a cell contains every point of the surface above that cell</blockquote>
 *
 * and it is deliberately <em>weaker</em> than the curve plot's, which is per pixel. Two things follow and are
 * worth stating rather than discovering:
 * <ul>
 *   <li><b>the cover is loose.</b> A bounding rectangle is up to twice the hexagon it covers. That is the same
 *       trade the fill makes — over-cover rather than under-draw;
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

    /** How many cells across the floor. One enclosure each, and the enclosures are the expensive part. */
    private static final int CELLS = 40;

    /**
     * How many pieces each proven cell is subdivided into, per axis, for the drawn surface.
     *
     * <p>This is the whole reason two layers is cheaper than one fine one. An <b>enclosure</b> costs interval
     * arithmetic over {@code BigDecimal}; an <b>interpolation</b> costs four multiplies. So the proof stays at
     * {@link #CELLS} and the picture is drawn at {@code CELLS × SUB}, which buys most of the smoothness for
     * almost none of the cost — the only new evaluation is one height and one gradient per lattice corner,
     * shared between the four cells that meet there.
     */
    private static final int SUB = 3;

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
    private final Node ghostLayer;
    private final Node legendLayer;
    private final List<Node> cellPool = new ArrayList<>();
    private final List<Node> ghostPool = new ArrayList<>();
    private final List<Node> legendPool = new ArrayList<>();
    private final List<Node> legendLabels = new ArrayList<>();

    /** Heights and gradients at the cell lattice's corners, by scale then by packed corner index. */
    private final Map<Long, Map<Long, Corner>> corners = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Map<Long, Corner>> eldest) {
            return size() > CACHED_SCALES;
        }
    };

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

    SurfacePlot(Gui gui, Consumer<String> readout) {
        this.gui = gui;
        this.readout = readout;
        this.canvas = gui.box()
                .width(Length.FILL).height(Length.grow(1))
                .background(Palette.PLOT_BG)
                .corner(Length.rem(0.5f))
                .scroll(false, false);
        this.cellLayer = layer();
        this.ghostLayer = layer();
        this.legendLayer = layer();
        // The proof over the picture, not under it: the enclosure boxes are what is known and the interpolated
        // surface is what is drawn, so the outlines have to stay visible across it.
        canvas.children(cellLayer, ghostLayer, legendLayer);
        gui.onDrag(canvas, this::drag);
        // Turning has no natural end, so the window's edge should not be one. Holding the pointer for the
        // gesture is what lets a drag roll the surface over as many times as it likes on a finite desk.
        gui.dragLocksPointer(canvas, true);
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
        synchronized (corners) {
            corners.clear();
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
        // Letting go is not a movement. The turning has already happened, one MOVE at a time; treating the
        // release as one more of them makes the release carry whatever distance the moves did not, which is
        // nothing at all when they arrive continuously and the entire gesture when something upstream stopped
        // delivering them. A gesture that ends with a lurch is worse than one that ends a few pixels short.
        if (e.phase() != DragEvent.Phase.MOVE) {
            return;
        }
        synchronized (this) {
            // The event's own motion, not the difference of two positions. Turning is a displacement and never
            // asks where the pointer is, which is what lets the canvas hold the pointer for the gesture -- and
            // a held pointer is warped back to one spot every frame, so its position stops moving while the
            // motion goes on being real. Differencing positions would read a locked drag as no drag at all.
            //
            // Rightward turns the yaw up, which is also what the right arrow key does.
            double dYaw = e.dx() / Math.max(1f, e.nodeW()) * DRAG_YAW;
            double dPitch = e.dy() / Math.max(1f, e.nodeH()) * DRAG_PITCH;
            if (dYaw == 0 && dPitch == 0) {
                return;
            }
            camera = camera.turned(dYaw, dPitch);
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
        List<Patch> patches = new ArrayList<>(CELLS * CELLS * (1 + SUB * SUB));
        for (int i = 0; i < CELLS; i++) {
            long ix = i - CELLS / 2L;
            for (int j = 0; j < CELLS; j++) {
                patch(patches, e, v, eye, a, b, u, ix, j - CELLS / 2L);
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
    private void patch(List<Patch> into, Expr e, Volume v, Camera eye, String a, String b,
                       double u, long ix, long iy) {
        Sample sample = sample(e, u, a, b, ix, iy);
        Extent extent = new Extent();
        Span.of(sample.height(), v.zLo(), v.zHi()).emitTo(0, extent);
        if (extent.blank) {
            return;
        }
        double x0 = ix * u;
        double x1 = (ix + 1) * u;
        double y0 = iy * u;
        double y1 = (iy + 1) * u;
        if (extent.filled) {
            // Nothing to interpolate through a place the arithmetic could not bound, and nothing to outline
            // either: the painted column IS the statement, and it is already the whole height of the volume.
            add(into, box(v, eye, x0, x1, y0, y1, v.zLo(), v.zHi(), null, true, false));
            return;
        }
        // The proof, as an outline: where the surface provably is, drawn over the picture of where it goes.
        add(into, box(v, eye, x0, x1, y0, y1, v.zAt(extent.bottom), v.zAt(extent.top), null, false, true));
        surfaceOver(into, v, eye, u, ix, iy,
                    corner(e, u, a, b, ix, iy), corner(e, u, a, b, ix + 1, iy),
                    corner(e, u, a, b, ix, iy + 1), corner(e, u, a, b, ix + 1, iy + 1));
    }

    private static void add(List<Patch> into, Patch patch) {
        if (patch != null) {
            into.add(patch);
        }
    }

    /**
     * One axis-aligned box of plot space, projected: the screen rectangle that covers it, how far away it is,
     * and how to colour it. Null when it lies outside the volume's height entirely.
     *
     * <p>The one place a box becomes a rectangle, shared by the proof and the picture so that the two cannot
     * drift apart about where a piece of space lands on the screen.
     */
    private static Patch box(Volume v, Camera eye, double x0, double x1, double y0, double y1,
                             double zBottom, double zTop, Sheen.Slope slope, boolean filled, boolean ghost) {
        double lo = Math.max(zBottom, v.zLo());
        double hi = Math.min(zTop, v.zHi());
        if (hi < lo) {
            return null;
        }
        // Normalised into the unit box the camera projects, so the camera never learns what a volume is.
        double nx0 = (x0 - v.xLo()) / v.xWidth() - 0.5;
        double nx1 = (x1 - v.xLo()) / v.xWidth() - 0.5;
        double ny0 = (y0 - v.yLo()) / v.yDepth() - 0.5;
        double ny1 = (y1 - v.yLo()) / v.yDepth() - 0.5;
        double nzLo = (lo - v.zLo()) / v.zHeight() - 0.5;
        double nzHi = (hi - v.zLo()) / v.zHeight() - 0.5;
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
        // The height the colour reads, as a fraction of the volume: the middle of the box, so a tall uncertain
        // one is coloured by where it is rather than by how unsure it is.
        double heat = 1 - clamp01(v.fractionOf((lo + hi) / 2));
        return new Patch(minU, maxU, minV, maxV, eye.depthKey((nx0 + nx1) / 2, (ny0 + ny1) / 2),
                         heat, filled, slope, ghost);
    }

    private static double clamp01(double t) {
        return t < 0 ? 0 : (t > 1 ? 1 : t);
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

    /**
     * The surface at one corner of the cell lattice: how high it is there and which way it leans.
     *
     * <p>Corners rather than centres, because these are what the drawn surface is <em>interpolated between</em>,
     * and a corner is shared by the four cells that meet at it — so a lattice of {@code (CELLS+1)²} of them
     * covers a grid of {@code CELLS²} cells at a quarter of the evaluations, and the surface comes out
     * continuous across every cell boundary rather than agreeing only approximately.
     *
     * <p>Interpolating the <b>gradient</b> as well as the height is what makes the shading continuous too. It is
     * the same idea as shading a mesh from vertex normals, except that these normals are the surface's own
     * analytic ones rather than an average of whatever faces happened to meet there — so there is no
     * tessellation for the light to trace.
     */
    private record Corner(double z, double gx, double gy, boolean real) {
    }

    /** The lattice corner at {@code (ix, iy)}, computed and remembered on the first ask. */
    private Corner corner(Expr e, double u, String a, String b, long ix, long iy) {
        Map<Long, Corner> scale;
        synchronized (corners) {
            scale = corners.computeIfAbsent(Double.doubleToLongBits(u), key -> new ConcurrentHashMap<>());
        }
        long key = (ix & 0xFFFFFFFFL) << 32 | (iy & 0xFFFFFFFFL);
        Corner known = scale.get(key);
        if (known != null) {
            return known;
        }
        Cell at = Cell.of(a, Interval.at(ix * u), b, Interval.at(iy * u));
        Double z = midpoint(e.enclose(at));
        Expr[] derivatives;
        synchronized (this) {
            derivatives = partials;
        }
        Double gx = derivatives == null ? null : midpoint(derivatives[0].enclose(at));
        Double gy = derivatives == null ? null : midpoint(derivatives[1].enclose(at));
        Corner computed = z == null
                ? new Corner(0, 0, 0, false)
                : new Corner(z, gx == null ? 0 : gx, gy == null ? 0 : gy, true);
        scale.put(key, computed);
        return computed;
    }

    /**
     * The drawn surface over one cell: {@code SUB × SUB} pieces, bilinearly interpolated between the cell's
     * four lattice corners.
     *
     * <p>Nothing here is proven and nothing here pretends to be. The enclosure over this cell is drawn
     * separately as an outline and it is the statement about where the surface actually is; this is the
     * illustration inside it, and it is only ever <em>within</em> the box the proof drew, because the enclosure
     * contains every value the surface takes on the cell and the interpolation's own corner heights are among
     * them. So the smooth layer cannot draw outside the honest one — which is the whole reason it is safe to
     * draw at all.
     */
    private void surfaceOver(List<Patch> into, Volume v, Camera eye, double u, long ix, long iy,
                             Corner c00, Corner c10, Corner c01, Corner c11) {
        if (!c00.real() || !c10.real() || !c01.real() || !c11.real()) {
            return;                                  // a corner with no height leaves nothing to interpolate
        }
        double step = u / SUB;
        for (int p = 0; p < SUB; p++) {
            for (int q = 0; q < SUB; q++) {
                double s0 = (double) p / SUB;
                double s1 = (double) (p + 1) / SUB;
                double t0 = (double) q / SUB;
                double t1 = (double) (q + 1) / SUB;
                // The piece's own four heights, so it covers the interpolated patch rather than hovering at a
                // single height and leaving gaps wherever the surface is steep.
                double z00 = lerp2(c00.z(), c10.z(), c01.z(), c11.z(), s0, t0);
                double z10 = lerp2(c00.z(), c10.z(), c01.z(), c11.z(), s1, t0);
                double z01 = lerp2(c00.z(), c10.z(), c01.z(), c11.z(), s0, t1);
                double z11 = lerp2(c00.z(), c10.z(), c01.z(), c11.z(), s1, t1);
                double zLo = Math.min(Math.min(z00, z10), Math.min(z01, z11));
                double zHi = Math.max(Math.max(z00, z10), Math.max(z01, z11));
                if (zHi < v.zLo() || zLo > v.zHi()) {
                    continue;                        // this piece is outside the volume on show
                }
                double sm = (s0 + s1) / 2;
                double tm = (t0 + t1) / 2;
                double gx = lerp2(c00.gx(), c10.gx(), c01.gx(), c11.gx(), sm, tm);
                double gy = lerp2(c00.gy(), c10.gy(), c01.gy(), c11.gy(), sm, tm);
                add(into, box(v, eye,
                        ix * u + p * step, ix * u + (p + 1) * step,
                        iy * u + q * step, iy * u + (q + 1) * step,
                        zLo, zHi, normalisedGradient(gx, gy, v), false, false));
            }
        }
    }

    /**
     * A gradient scaled into the volume's normalised cube, with no curvature. The interpolated surface shades
     * from its first derivatives only: the cavity term wants the second, and second derivatives interpolated
     * between corners are a difference of differences — noisy where the surface is interesting and worth
     * nothing where it is not. Curvature stays where it is exact, on the proven cells.
     */
    private static Sheen.Slope normalisedGradient(double gx, double gy, Volume v) {
        if (!Double.isFinite(gx) || !Double.isFinite(gy)) {
            return null;
        }
        return new Sheen.Slope(gx * v.xWidth() / v.zHeight(), gy * v.yDepth() / v.zHeight(), 0, 0, 0);
    }

    /** Bilinear interpolation between four corner values. */
    private static double lerp2(double v00, double v10, double v01, double v11, double s, double t) {
        return (v00 * (1 - s) + v10 * s) * (1 - t) + (v01 * (1 - s) + v11 * s) * t;
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
        int ghosts = 0;
        for (Patch p : patches) {
            float x = (float) (midX + p.minU() * scale);
            float y = (float) (midY - p.maxV() * scale);          // screen y grows downward, v upward
            float w = (float) Math.max(MIN_CELL_DP, (p.maxU() - p.minU()) * scale);
            float h = (float) Math.max(MIN_CELL_DP, (p.maxV() - p.minV()) * scale);
            if (x > size[0] || y > size[1] || x + w < 0 || y + h < 0) {
                continue;                                          // wholly off the canvas
            }
            if (p.ghost()) {
                // Outlined and hollow. What the proof has to say is where its edges are -- an enclosure is a
                // claim about a range, and a filled box would say the surface is everywhere in it.
                pooled(ghostPool, ghostLayer, ghosts++)
                        .visible(true)
                        .background(Color.rgba(0, 0, 0, 0))
                        .border(Length.dp(1), Palette.PROOF_EDGE)
                        .size(Length.dp(w), Length.dp(h))
                        .floatAt(Length.dp(x), Length.dp(y));
                continue;
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
        hideFrom(ghostPool, ghosts);
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
                         double depth, double heat, boolean filled, Sheen.Slope slope, boolean ghost) {
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
