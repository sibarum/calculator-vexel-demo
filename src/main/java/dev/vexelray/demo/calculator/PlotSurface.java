package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.plot.Enclosure;
import dev.vexelray.gui.plot.Expr;
import dev.vexelray.gui.plot.Frame;
import dev.vexelray.gui.plot.Framing;
import dev.vexelray.gui.plot.Interval;
import dev.vexelray.gui.plot.Span;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * The plot itself: the canvas the curve is drawn on, the cache behind it, and the gestures that move the
 * window. Everything that knows a plot is made of pixels lives here, and everything it knows about mathematics
 * arrives as an {@link Expr} it never looks inside.
 *
 * <h2>A reliable plot is drawn out of boxes</h2>
 * The framework's node vocabulary is {@code {BOX, TEXT}}, and the design note for the plot module recorded a
 * renderer as blocked on a node that could draw a diagonal line. It is not, and the reason is the technique
 * rather than a workaround: point sampling needs a polyline because it joins samples it never evaluated
 * between, while evaluating over a <em>column</em> produces a <em>vertical span</em> — which is a box, one
 * pixel wide. Every column of this plot is one box: short where the curve is flat, tall where it is steep,
 * full-height where the arithmetic could not bound it. There is nothing diagonal in a reliable plot.
 *
 * <h2>The cache is what makes it explorable</h2>
 * Enclosures are computed in plot space and know nothing of the frame, so:
 * <ul>
 *   <li><b>moving in y</b> — panning or zooming vertically — recomputes <em>nothing</em>. The cached
 *       enclosures are reclassified against the new frame, which is clipping and no arithmetic at all;
 *   <li><b>moving in x</b> recomputes only the columns that came into view. A drag of forty pixels evaluates
 *       forty columns, not the whole window;
 *   <li><b>zooming out and back in</b> recomputes nothing either, as long as it lands on a scale visited
 *       before — which it does, because {@link #SCALE_STEP} makes the set of reachable scales discrete.
 * </ul>
 * That last point is why the x window is quantised. A column has an identity — "the column of width {@code u}
 * whose index is {@code n}" — and two renders that ask for the same identity must get the same interval, or
 * the cache would be answering about a column a hair away from the one being drawn. So {@link #columnAt}
 * computes the interval from the index alone, never from the frame's edge, and the frame's left edge is
 * snapped to a multiple of {@code u} before anything is drawn. A pan is then a whole number of columns and
 * every column that stayed on screen keeps its answer.
 *
 * <h2>And why there is no flicker</h2>
 * A render runs on a worker, assembles every column, and applies the whole picture in one {@link Gui#batch} —
 * so no frame can catch the plot half-redrawn. The nodes are <b>pooled</b> and never destroyed between
 * renders: a column that is still a column keeps its identity and only changes its geometry, and a column
 * that is not needed is hidden rather than removed. The old picture stays up, intact, until the new one
 * replaces it whole.
 */
final class PlotSurface {

    /** One column per device-independent pixel: the finest the frame's own arithmetic can distinguish. */
    private static final float COLUMN_DP = 1f;

    /** A curve thinner than this is drawn this thick, so a flat stretch is a line rather than nothing. */
    private static final float MIN_CURVE_DP = 1.5f;

    /** One notch of zoom. A root of two, so two notches are exactly a doubling. */
    private static final double SCALE_STEP = Math.sqrt(2);

    /** How far the zoom may travel from the framing pass's window, in notches. */
    private static final int ZOOM_LIMIT = 40;

    /** How many scales of cached columns to keep. Enough that zooming out and back in is free. */
    private static final int CACHED_SCALES = 8;

    /** Ceilings on the drawn furniture, so a degenerate frame cannot ask for ten thousand gridlines. */
    private static final int MAX_GRID_LINES = 60;
    private static final int TICKS_ACROSS = 8;

    /** A keyboard pan, as a fraction of the window. */
    private static final double KEY_PAN = 0.15;

    /** The box a tick label is drawn in, in dp — also the margin the two axes keep out of each other's way. */
    private static final float LABEL_W = 44;
    private static final float LABEL_H = 14;

    private final Gui gui;
    private final Node canvas;
    private final Node gridLayer;
    private final Node curveLayer;
    private final Node labelLayer;
    private final List<Node> gridPool = new ArrayList<>();
    private final List<Node> curvePool = new ArrayList<>();
    private final List<Node> labelPool = new ArrayList<>();

    /** Told the frame's extent after every render, for the window's readout. */
    private final Consumer<String> readout;

    /**
     * Cached enclosures, by scale and then by column index. The outer map is insertion-ordered and evicts the
     * least recently used scale, which is the right axis to evict on: a scale is a whole picture's worth of
     * work, and the one being left behind is the one nobody is looking at.
     */
    private final Map<Long, Map<Long, Enclosure>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Map<Long, Enclosure>> eldest) {
            return size() > CACHED_SCALES;
        }
    };

    /** Every render is numbered; a render whose number is stale drops its work rather than painting it. */
    private final AtomicInteger revision = new AtomicInteger();

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    /** Holds the layout subscription open for as long as the surface exists. */
    @SuppressWarnings("unused")
    private final sibarum.atchung.Subscription geometry;

    /** The canvas size the picture currently on screen was drawn for. Written by a painter, read by the GUI. */
    private volatile float paintedW;
    private volatile float paintedH;

    private final Object painting = new Object();

    // --- the plot state, guarded by this -------------------------------------------------------------

    private Expr expr;
    private String variable = "x";
    /** The window the framing pass chose: what "reset" goes back to, and what the zoom notches scale. */
    private Frame home = Frame.about(Framing.DEFAULT_HALF_WIDTH, Framing.DEFAULT_HALF_HEIGHT);
    private int zoom;
    private double xLo;
    private double yLo;
    private double yHi;
    /** The pointer's last position during a drag, in client pixels. */
    private float dragX;
    private float dragY;

    PlotSurface(Gui gui, Consumer<String> readout) {
        this.gui = gui;
        this.readout = readout;
        this.canvas = gui.box()
                .width(Length.FILL).height(Length.grow(1))
                .background(Palette.PLOT_BG)
                .corner(Length.rem(0.5f))
                // No border and no padding, deliberately: a floating child is placed from the border-box
                // origin but sized against the content box, so any inset between the two would slide the
                // layers off the canvas by exactly that much. Without one the two boxes are the same box.
                .scroll(false, false);
        // Three layers, appended in painting order: a float draws above every sibling before it, so the grid
        // cannot end up over the curve however the pools happen to grow. All three are pointer-transparent,
        // which leaves the canvas itself the target of every press and wheel.
        this.gridLayer = layer();
        this.curveLayer = layer();
        this.labelLayer = layer();
        canvas.children(gridLayer, curveLayer, labelLayer);
        gui.onDrag(canvas, this::drag);
        reset();   // a frame with no extent is not constructible, so there is one from the start
        // A paint needs a canvas that has a size, and a canvas has no size until the tree has been laid out
        // once -- which does not happen until the window exists and draws its first frame. So the surface
        // watches the layout rather than being told when to draw: whenever the canvas ends up a different
        // size from the one the picture on screen was drawn for, it repaints. That covers the first
        // appearance, every window resize, and every change of UI zoom, with no caller having to guess the
        // moment. The state is coalesced and commits only on change, so this costs a size comparison per
        // changed layout and nothing at all per frame.
        this.geometry = gui.layout().onCommit(snapshot -> repaintIfResized());
    }

    /** The canvas ended up a different size from the one the picture was drawn for: draw it again. */
    private void repaintIfResized() {
        float[] size = size();
        if (size[0] < 2 || size[1] < 2) {
            return;                      // still no canvas to draw on
        }
        if (Math.abs(size[0] - paintedW) < 0.5f && Math.abs(size[1] - paintedH) < 0.5f) {
            return;                      // this is the picture already up -- and this is what stops a loop,
        }                                // since painting is itself a layout change
        invalidate();
    }

    Node node() {
        return canvas;
    }

    // --- what is plotted ------------------------------------------------------------------------------

    /**
     * Plot {@code plottable}, framed automatically. The framing pass runs here rather than on the GUI thread —
     * it evaluates a few hundred columns, and a window opening is not a reason to drop a frame.
     */
    void show(Plottable plottable) {
        gui.async(() -> showNow(plottable));
    }

    /** {@link #show} without the worker: the framing pass runs on the caller. */
    void showNow(Plottable plottable) {
        Frame framed = Framing.automatic(plottable.expr());
        synchronized (cache) {
            cache.clear();                   // a new expression: every cached column was about another curve
        }
        synchronized (this) {
            this.expr = plottable.expr();
            this.variable = plottable.variable();
            this.home = framed;
            reset();
        }
        invalidate();
    }

    /**
     * Paint on the calling thread and return once the picture is up — the headless capture's handshake, and
     * nothing a running window needs. The canvas has to have been laid out at least once first, which is why
     * a capture frames twice: once to give the canvas a size, once to photograph what was drawn into it.
     *
     * <p>It paints unconditionally, and the tempting optimisation is wrong: "the revision on screen is the
     * current revision" does <em>not</em> mean the picture is current, because the transforms do not touch the
     * revision — {@link #invalidate} does, and a caller that transforms and then settles has never called it.
     * Short-circuiting on that made {@code --capture-plot} report {@code 0/0} for its zoom and pan, which is
     * what a phase that never drew anything looks like.
     */
    void settle() {
        paint(revision.incrementAndGet());
    }

    /** The expression's own name for its axis — the variable it was read as a function of. */
    synchronized String variable() {
        return variable;
    }

    // --- the transforms -------------------------------------------------------------------------------

    /** Back to the window the framing pass chose. */
    synchronized void reset() {
        zoom = 0;
        xLo = home.xLo();
        yLo = home.yLo();
        yHi = home.yHi();
    }

    /** Re-fit the vertical extent to whatever the curve does across the x window now on screen. */
    void fitVertically() {
        gui.async(() -> {
            Expr e;
            Frame f;
            synchronized (this) {
                e = expr;
                f = frame();
            }
            if (e == null) {
                return;
            }
            Frame fitted = Framing.refit(e, f);
            synchronized (this) {
                yLo = fitted.yLo();
                yHi = fitted.yHi();
            }
            invalidate();
        });
    }

    /** Zoom by {@code notches} about the middle of the canvas — what the +/− buttons do. */
    void zoom(int notches) {
        zoomAbout(notches, 0.5, 0.5);
    }

    /**
     * Zoom by {@code notches} keeping the plot point at {@code (atX, atY)} — fractions of the canvas — where
     * it is. Both axes move together: the aspect the framing pass chose is a statement about the curve, and
     * quietly changing it while the user zooms would make the same curve look like a different one.
     */
    synchronized void zoomAbout(int notches, double atX, double atY) {
        int wanted = Math.max(-ZOOM_LIMIT, Math.min(ZOOM_LIMIT, zoom + notches));
        if (wanted == zoom) {
            return;
        }
        int columns = columns(size());
        Frame before = frame();
        double anchorX = before.xAt(atX);
        double anchorY = before.yAt(atY);
        double factor = Math.pow(SCALE_STEP, zoom - wanted);   // in narrows, out widens
        zoom = wanted;
        xLo = anchorX - atX * width();
        double height = before.height() * factor;
        yHi = anchorY + atY * height;
        yLo = yHi - height;
        snapToColumns(columns);
    }

    /** Move the window by a fraction of itself — the arrow keys. */
    synchronized void nudge(double fractionX, double fractionY) {
        int columns = columns(size());
        Frame f = frame();
        xLo += fractionX * f.width();
        double dy = fractionY * f.height();
        yLo += dy;
        yHi += dy;
        snapToColumns(columns);
    }

    /**
     * A drag pans the window. The x pan lands on a whole number of columns — that is what keeps every column
     * that stayed on screen answerable from the cache — and at one column per pixel the quantisation is the
     * pointer's own resolution, so nothing about the drag feels stepped.
     */
    private void drag(DragEvent e) {
        synchronized (this) {
            if (e.phase() == DragEvent.Phase.START) {
                dragX = e.x();
                dragY = e.y();
                return;
            }
            int columns = columns(size());
            Frame f = frame();
            // The drag arrives in device pixels and the node's own rect comes with it, so the plot units a
            // pixel is worth need nothing but those two.
            double perPixelX = f.width() / Math.max(1f, e.nodeW());
            double perPixelY = f.height() / Math.max(1f, e.nodeH());
            xLo -= (e.x() - dragX) * perPixelX;
            double dy = (e.y() - dragY) * perPixelY;      // screen y grows downward, plot y upward
            yLo += dy;
            yHi += dy;
            dragX = e.x();
            dragY = e.y();
            snapToColumns(columns);
        }
        invalidate();
    }

    /** A wheel notch over the canvas zooms about the pointer. Reports whether the wheel was over the plot. */
    boolean wheel(double notches, int x, int y) {
        Rect rect = canvas.layout().rect();
        if (rect.w() <= 0 || !rect.contains(x, y)) {
            return false;
        }
        zoomAbout((int) Math.signum(notches) * Math.max(1, (int) Math.abs(notches)),
                  (x - rect.x()) / rect.w(), (y - rect.y()) / rect.h());
        invalidate();
        return true;
    }

    /** The arrow keys, bound by the window. */
    void pan(double fractionX, double fractionY) {
        nudge(fractionX * KEY_PAN, fractionY * KEY_PAN);
        invalidate();
    }

    // --- geometry -------------------------------------------------------------------------------------

    /** The canvas, in device-independent pixels — the space every {@link Length#dp} placement below is in. */
    private float[] size() {
        Rect content = canvas.layout().content();
        float dpi = Math.max(0.01f, gui.dpi().value());
        return new float[]{content.w() / dpi, content.h() / dpi};
    }

    private static int columns(float[] size) {
        return Math.max(1, (int) (size[0] / COLUMN_DP));
    }

    /**
     * The width of the x window at the current zoom. A <b>pure function of the notch count</b>, which is what
     * makes a scale reproducible and therefore cacheable: returning to notch 3 returns to the same column
     * grid, not to one a rounding error away from it.
     *
     * <p>The notch count runs the way the {@code +} button does — positive is zoomed <em>in</em>, so it
     * narrows the window.
     */
    private synchronized double width() {
        return home.width() * Math.pow(SCALE_STEP, -zoom);
    }

    private synchronized Frame frame() {
        double w = width();
        return new Frame(xLo, xLo + w, yLo, yHi);
    }

    /**
     * Put the left edge on the column grid. Every column's interval is a multiple of {@code u} away from the
     * origin, so this is what lets a pan reuse the columns that did not move — and it is invisible, since one
     * column is one pixel.
     */
    private synchronized void snapToColumns(int columns) {
        double u = unit(columns);
        xLo = Math.rint(xLo / u) * u;
    }

    /**
     * The plot-space width of one column. Every use of it goes through here rather than through
     * {@code frame().width() / columns}, and that is not fussiness: the frame's width is a subtraction of two
     * edges and lands an ulp away from this, which would make the snap and the cache key disagree and turn
     * every redraw into a total miss.
     */
    private synchronized double unit(int columns) {
        return width() / columns;
    }

    /**
     * The x-interval of the column with index {@code index} at scale {@code u}: {@code [n·u, (n+1)·u]},
     * computed from the index and nothing else.
     *
     * <p>That is the whole soundness argument for the cache. An enclosure is only a true statement about the
     * column it was computed over, so a cached one may only be reused for a column that <em>is</em> that
     * column — not one an ulp away because the frame's left edge has moved since. Deriving the interval from
     * the index makes the identity exact.
     */
    private static Interval columnAt(long index, double u) {
        return new Interval(BigDecimal.valueOf(index * u), BigDecimal.valueOf((index + 1) * u));
    }

    // --- rendering ------------------------------------------------------------------------------------

    /** Schedule a repaint. The latest request wins; earlier ones drop their work rather than paint it stale. */
    void invalidate() {
        int mine = revision.incrementAndGet();
        gui.async(() -> paint(mine));
    }

    private void paint(int mine) {
        Expr e;
        Frame f;
        int columns;
        float[] size = size();
        if (size[0] < 2 || size[1] < 2) {
            return;                                  // not laid out yet; the next invalidate will find it
        }
        columns = columns(size);
        double u;
        long first;
        synchronized (this) {
            e = expr;
            u = unit(columns);
            first = Math.round(xLo / u);
            // The frame that is drawn is built from the column grid, not from xLo. They agree to within half a
            // column, but "within half a column" is exactly the sort of agreement that puts the gridlines a
            // hair off the curve; taking both from the same integer makes them the same thing.
            f = new Frame(first * u, (first + columns) * u, yLo, yHi);
        }
        if (e == null) {
            return;
        }
        Span[] spans = new Span[columns];
        for (int i = 0; i < columns; i++) {
            spans[i] = Span.of(enclosure(e, u, first + i), f);
            if (revision.get() != mine) {
                return;                              // overtaken mid-evaluation: whoever overtook us will draw
            }
        }
        synchronized (painting) {
            if (revision.get() != mine) {
                return;
            }
            // One batch for the whole picture: a frame cannot land between the grid and the curve, so there
            // is no state in which the plot is half of one window and half of another.
            gui.batch(() -> {
                drawColumns(spans, size[1]);
                drawGrid(f, size);
            });
            // Recorded only once a picture is actually up. A paint that declined -- no expression yet, no
            // canvas yet, overtaken -- must leave this alone, or the layout watch would conclude the size it
            // never drew at is the size on screen and never ask again.
            paintedW = size[0];
            paintedH = size[1];
        }
        readout.accept(bounds(f));
    }

    /**
     * What the cache saved since this was last asked, as "hits/asks" — read by {@code --capture-plot}, which
     * is the only way the claim this class makes about exploring being cheap can be checked rather than
     * asserted. Reading resets the counters, so each phase of a capture reports its own work.
     */
    String cacheReport() {
        long hit = hits.sumThenReset();
        long asked = hit + misses.sumThenReset();
        return hit + "/" + asked + " cached";
    }

    /** The cached enclosure for one column, computed and remembered on the first ask. */
    private Enclosure enclosure(Expr e, double u, long index) {
        Map<Long, Enclosure> scale;
        synchronized (cache) {
            scale = cache.computeIfAbsent(Double.doubleToLongBits(u), key -> new ConcurrentHashMap<>());
        }
        Enclosure known = scale.get(index);
        if (known != null) {
            hits.increment();
            return known;
        }
        misses.increment();
        Enclosure computed = e.enclose(columnAt(index, u));
        scale.put(index, computed);
        return computed;
    }

    /**
     * The curve, as one box per column, through the {@link Span.Sink} the plot module hands its answers to.
     * The three methods are the three things a column can be, and this class implements all three exactly
     * once — which is the point of the sink: a fourth kind of answer would be a compile error here rather
     * than a silently unhandled case.
     */
    private void drawColumns(Span[] spans, float heightDp) {
        Span.Sink sink = new Span.Sink() {
            @Override
            public void curve(int column, double top, double bottom) {
                float y = (float) (top * heightDp);
                float h = (float) ((bottom - top) * heightDp);
                place(column, y, Math.max(h, MIN_CURVE_DP), Palette.CURVE);
            }

            @Override
            public void fill(int column) {
                // The honest answer, and the one that makes tan(1/x) legible: the expression leaves every
                // bound somewhere inside this pixel, so the pixel is the answer.
                place(column, 0, heightDp, Palette.POLE);
            }

            @Override
            public void blank(int column) {
                pooled(curvePool, curveLayer, column).visible(false);
            }

            private void place(int column, float y, float height, Color colour) {
                pooled(curvePool, curveLayer, column)
                        .visible(true)
                        .background(colour)
                        .size(Length.dp(COLUMN_DP), Length.dp(height))
                        .floatAt(Length.dp(column * COLUMN_DP), Length.dp(y));
            }
        };
        for (int i = 0; i < spans.length; i++) {
            spans[i].emitTo(i, sink);
        }
        hideFrom(curvePool, spans.length);
    }

    /** Gridlines, the two axes, and a label on each line. */
    private void drawGrid(Frame f, float[] size) {
        int lines = 0;
        int labels = 0;
        double xStep = Framing.tickStep(f.width(), TICKS_ACROSS);
        double yStep = Framing.tickStep(f.height(), TICKS_ACROSS);
        for (double x = Math.ceil(f.xLo() / xStep) * xStep; x <= f.xHi() && lines < MAX_GRID_LINES; x += xStep) {
            float at = (float) (f.width() == 0 ? 0 : (x - f.xLo()) / f.width() * size[0]);
            boolean axis = Math.abs(x) < xStep / 2;
            pooled(gridPool, gridLayer, lines++)
                    .visible(true)
                    .background(axis ? Palette.AXIS : Palette.GRID)
                    .size(Length.dp(1), Length.dp(size[1]))
                    .floatAt(Length.dp(at), Length.dp(0));
            // The last label before the right edge would be clamped back inside and land on top of the one
            // before it, so it is left out: a gridline with no number reads fine, two numbers on top of each
            // other do not.
            if (at < size[0] - LABEL_W) {
                labels = label(labels, format(x, xStep), at + 3, size[1] - LABEL_H - 2);
            }
        }
        for (double y = Math.ceil(f.yLo() / yStep) * yStep; y <= f.yHi() && lines < MAX_GRID_LINES; y += yStep) {
            float at = (float) (f.fractionOf(y) * size[1]);
            boolean axis = Math.abs(y) < yStep / 2;
            pooled(gridPool, gridLayer, lines++)
                    .visible(true)
                    .background(axis ? Palette.AXIS : Palette.GRID)
                    .size(Length.dp(size[0]), Length.dp(1))
                    .floatAt(Length.dp(0), Length.dp(at));
            if (at < size[1] - LABEL_H * 2) {           // the strip along the bottom belongs to the x labels
                labels = label(labels, format(y, yStep), 4, at + 2);
            }
        }
        hideFrom(gridPool, lines);
        hideFrom(labelPool, labels);
    }

    private int label(int index, String text, float x, float y) {
        if (index >= MAX_GRID_LINES) {
            return index;
        }
        pooled(labelPool, labelLayer, index)
                .visible(true)
                .text(text)
                .size(Length.dp(LABEL_W), Length.dp(LABEL_H))
                .textSize(Length.rem(0.6875f)).textColor(Palette.DIM)
                .align(dev.vexelray.text.TextLayout.HAlign.LEFT, dev.vexelray.text.TextLayout.VAlign.MIDDLE)
                .floatAt(Length.dp(x), Length.dp(y));
        return index + 1;
    }

    /** As many decimals as the step needs and no more: a gridline every 0.25 is not labelled "0.250000". */
    private static String format(double value, double step) {
        double shown = Math.abs(value) < step / 2 ? 0 : value;
        int decimals = Math.max(0, Math.min(6, (int) Math.ceil(-Math.log10(step))));
        if (Math.abs(shown) >= 1e5 || (shown != 0 && Math.abs(shown) < 1e-4)) {
            return String.format("%.1e", shown);
        }
        return String.format("%." + decimals + "f", shown);
    }

    private String bounds(Frame f) {
        double xStep = Framing.tickStep(f.width(), TICKS_ACROSS);
        double yStep = Framing.tickStep(f.height(), TICKS_ACROSS);
        // Plain brackets and no set-membership sign: the MSDF atlas this application draws with has no glyph
        // for one, and a readout that renders as a box is worse than a readout that reads like a range.
        return variable() + ": [" + format(f.xLo(), xStep) + ", " + format(f.xHi(), xStep) + "]"
                + "    y: [" + format(f.yLo(), yStep) + ", " + format(f.yHi(), yStep) + "]";
    }

    // --- the node pools -------------------------------------------------------------------------------

    /**
     * The node for slot {@code index} of a pool, made on first use and kept forever after. Pooling is what
     * makes a redraw a change of geometry rather than a rebuild of the tree: the reconciler sees the same
     * nodes it saw last time, and nothing blinks out of existence between two pictures of the same curve.
     */
    private Node pooled(List<Node> pool, Node layer, int index) {
        while (pool.size() <= index) {
            Node made = pool == labelPool ? gui.text("") : gui.box();
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

    /** A full-size, pointer-transparent overlay: one of the three things drawn on the canvas. */
    private Node layer() {
        return gui.box()
                .width(Length.percent(100)).height(Length.percent(100))
                .floatAt(Length.ZERO, Length.ZERO)
                .hitInert(true)
                .scroll(false, false);
    }
}
