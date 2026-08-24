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
import dev.vexelray.gui.plot.Landmark;
import dev.vexelray.gui.plot.Landmarks;
import dev.vexelray.gui.plot.Span;
import dev.vexelray.text.TextLayout;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import sibarum.cott.Term;

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

    // --- landmarks --------------------------------------------------------------------------------------

    /** A landmark's dot, in dp. A box with a corner radius of half its size is a circle. */
    private static final float MARK_DP = 9;

    /** How close two markers may sit before the second is left out. See {@link #drawMarks}. */
    private static final float COLLAPSE_DP = MARK_DP;

    /** How near the pointer has to come, in dp, for a landmark to name itself. */
    private static final float HOVER_DP = 14;

    /**
     * How much wider than the visible window landmarks are found over. Panning within what has already been
     * searched costs nothing, which is the same bargain the column cache strikes and for the same reason: a
     * search is a whole window's worth of work and a drag is forty pixels of it.
     */
    private static final double SEARCH_MARGIN = 1.0;

    /** A pole's marker sits on the x-axis, or this far down the canvas when the axis is off screen. */
    private static final double POLE_MARK_FRACTION = 0.5;

    /** The tooltip's geometry, in dp. Its width is estimated from the text, since nothing here can measure it. */
    private static final float TIP_LINE_H = 15;
    private static final float TIP_PAD = 7;
    private static final float TIP_CHAR_W = 6.2f;
    private static final float TIP_MIN_W = 96;
    private static final float TIP_MAX_W = 340;

    private final Gui gui;
    private final Node canvas;
    private final Node gridLayer;
    private final Node curveLayer;
    private final Node markLayer;
    private final Node labelLayer;
    private final Node tip;
    private final List<Node> gridPool = new ArrayList<>();
    private final List<Node> curvePool = new ArrayList<>();
    private final List<Node> markPool = new ArrayList<>();
    private final List<Node> labelPool = new ArrayList<>();
    private final List<Node> tipLines = new ArrayList<>();

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

    private final Object painting = new Object();

    // --- the plot state, guarded by this -------------------------------------------------------------

    private Expr expr;
    private String variable = "x";
    /** The term as it was typed. Only {@link Influence} reads it, and only to name what carries a value. */
    private Term typed;
    /** What the last search found, and the x window it was searched over. */
    private List<Landmark> landmarks = List.of();
    private double searchedLo;
    private double searchedHi;
    /** Where the drawn markers ended up, in dp within the canvas — the hover test's only input. */
    private final List<Placed> placed = new ArrayList<>();
    /** What the last search declined to draw, carried between paints so a pan does not lose the notice. */
    private String notice = "";
    /** The landmark the tooltip is currently about, so a pointer moving within one does not rebuild it. */
    private volatile Landmark tipFor;
    /** What it says, kept so a reposition does not recompute the influence reading. */
    private volatile List<String> tipText = List.of();
    /** The window the framing pass chose: what "reset" goes back to, and what the zoom notches scale. */
    private Frame home = Frame.about(Framing.DEFAULT_HALF_WIDTH, Framing.DEFAULT_HALF_HEIGHT);
    private int zoom;
    private double xLo;
    private double yLo;
    private double yHi;

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
        this.markLayer = layer();
        this.labelLayer = layer();
        this.tip = tooltip();
        canvas.children(gridLayer, curveLayer, markLayer, labelLayer, tip);
        gui.onDrag(canvas, this::drag);
        // A pan is a displacement too, so the same bargain: the pointer stops at no edge and comes back where
        // it was let go. Panning a long way used to mean letting go and re-grabbing.
        gui.dragLocksPointer(canvas, true);
        reset();   // a frame with no extent is not constructible, so there is one from the start
        // A paint needs a canvas that has a size, and a canvas has no size until the tree has been laid out --
        // which does not happen until the window exists and draws its first frame. So the surface is told when
        // its box arrives rather than guessing: the first call is the plot's cue to draw at all, and every
        // later one is a window resize or a change of UI zoom. Nothing else has to know when a plot may paint.
        gui.onResize(canvas, box -> invalidate());
    }

    Node node() {
        return canvas;
    }

    // --- what is plotted ------------------------------------------------------------------------------

    /**
     * Plot {@code plottable}, framed automatically. The framing pass runs here rather than on the GUI thread —
     * it evaluates a few hundred columns, and a window opening is not a reason to drop a frame.
     */
    void show(Plottable plottable, Term typed) {
        gui.async(() -> showNow(plottable, typed));
    }

    /** {@link #show} without the worker: the framing pass runs on the caller. */
    void showNow(Plottable plottable, Term typed) {
        Frame framed = Framing.automatic(plottable.expr());
        synchronized (cache) {
            cache.clear();                   // a new expression: every cached column was about another curve
        }
        synchronized (this) {
            this.expr = plottable.expr();
            this.variable = plottable.across();
            this.typed = typed;
            this.home = framed;
            this.landmarks = List.of();
            this.searchedLo = 0;
            this.searchedHi = 0;             // an empty search window: the next paint will look again
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
        // Letting go is not a pan: see the same guard on the surface's drag. The panning has already happened
        // one MOVE at a time, and letting the release carry a delta of its own means it carries whatever the
        // moves did not -- nothing when they are continuous, and the whole excursion when they are not.
        if (e.phase() != DragEvent.Phase.MOVE) {
            return;
        }
        synchronized (this) {
            if (e.dx() == 0 && e.dy() == 0) {
                return;
            }
            int columns = columns(size());
            Frame f = frame();
            // The drag arrives in device pixels and the node's own rect comes with it, so the plot units a
            // pixel is worth need nothing but those two.
            double perPixelX = f.width() / Math.max(1f, e.nodeW());
            double perPixelY = f.height() / Math.max(1f, e.nodeH());
            // The event's own motion rather than a difference of positions: a pan is a displacement, the
            // canvas holds the pointer for the gesture, and a held pointer has no position worth differencing.
            xLo -= e.dx() * perPixelX;
            double dy = e.dy() * perPixelY;               // screen y grows downward, plot y upward
            yLo += dy;
            yHi += dy;
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
        // Before the batch, because it evaluates: the search runs on this worker, and only when the window has
        // moved off what was last searched. A pan within the searched margin is free.
        String notice = searchAround(e, f);
        List<Landmark> marks;
        synchronized (this) {
            marks = landmarks;
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
                drawMarks(marks, f, size);
                hideTip();                    // the picture moved out from under the pointer
            });
        }
        readout.accept(notice.isEmpty() ? bounds(f) : bounds(f) + "    " + notice);
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

    // --- landmarks ------------------------------------------------------------------------------------

    /**
     * Find the landmarks around {@code f}, if the window has left what was last searched. Returns the notice
     * naming whatever the finder declined to draw.
     *
     * <p>The search is done over a window {@link #SEARCH_MARGIN} wider than the visible one on each side, which
     * is the same bargain the column cache strikes: a search costs a whole window's worth of arithmetic and a
     * drag is forty pixels of it, so paying for the neighbourhood once buys every pan inside it. A zoom is the
     * other case — the same margin at a tighter scale resolves features the wider search stepped over — so a
     * searched window that has become much larger than the visible one is re-run even though it still contains
     * it.
     */
    private String searchAround(Expr e, Frame f) {
        double lo;
        double hi;

        synchronized (this) {
            boolean covered = f.xLo() >= searchedLo && f.xHi() <= searchedHi;
            boolean stale = searchedHi - searchedLo > f.width() * (1 + 2 * SEARCH_MARGIN) * 2;
            if (covered && !stale) {
                return notice;
            }
            double margin = f.width() * SEARCH_MARGIN;
            lo = f.xLo() - margin;
            hi = f.xHi() + margin;

        }
        Landmarks.Survey survey;
        try {
            survey = Landmarks.survey(e, variable(), lo, hi);
        } catch (RuntimeException fault) {
            // A search is an extra, not the picture. Whatever it could not do, the curve underneath is still
            // right, so the markers are dropped rather than the paint.
            synchronized (this) {
                landmarks = List.of();
                searchedLo = lo;
                searchedHi = hi;
                notice = "";
            }
            return "";
        }
        synchronized (this) {
            landmarks = survey.found();
            searchedLo = lo;
            searchedHi = hi;
            notice = survey.notice();
            return notice;
        }
    }

    /**
     * The markers: one small circle per landmark, unlabelled.
     *
     * <p>Unlabelled is the decision. A picture with a number beside every feature stops being a picture of a
     * curve and becomes a table drawn over one, and the numbers are a pointer-move away — see {@link #hover}.
     *
     * <p>Where two landmarks land on the same spot — {@code x²} has a root and a minimum at the origin — only
     * the first by {@link #PRECEDENCE} is drawn, because a dot can only be one colour. Both are still
     * <em>remembered</em>, so the tooltip names them both: the collapse is about what can be drawn, not about
     * what is true.
     */
    private void drawMarks(List<Landmark> marks, Frame f, float[] size) {
        List<Placed> put = new ArrayList<>(marks.size());
        int drawn = 0;
        for (Landmark mark : marks.stream().sorted(PRECEDENCE).toList()) {
            if (mark.x() < f.xLo() || mark.x() > f.xHi()) {
                continue;
            }
            double fraction = mark.kind().hasHeight() ? f.fractionOf(mark.y()) : poleFraction(f);
            if (fraction < 0 || fraction > 1) {
                continue;                                   // above the ceiling or below the floor of the window
            }
            float px = (float) ((mark.x() - f.xLo()) / f.width() * size[0]);
            float py = (float) (fraction * size[1]);
            boolean covered = false;
            for (Placed already : put) {
                covered |= Math.hypot(already.x() - px, already.y() - py) < COLLAPSE_DP;
            }
            put.add(new Placed(px, py, mark));
            if (covered) {
                continue;
            }
            pooled(markPool, markLayer, drawn++)
                    .visible(true)
                    .background(colourOf(mark.kind()))
                    // The border is a halo rather than an outline: a dot the same brightness as the curve it
                    // sits on needs a dark ring to be a dot at all.
                    .border(Length.dp(1.5f), Palette.MARK_HALO)
                    .corner(Length.dp(MARK_DP / 2))
                    .size(Length.dp(MARK_DP), Length.dp(MARK_DP))
                    .floatAt(Length.dp(px - MARK_DP / 2), Length.dp(py - MARK_DP / 2));
        }
        hideFrom(markPool, drawn);
        synchronized (placed) {
            placed.clear();
            placed.addAll(put);
        }
    }

    /** A pole has no height, so its marker stands on the x-axis, or mid-window when the axis is off screen. */
    private static double poleFraction(Frame f) {
        return f.yLo() <= 0 && f.yHi() >= 0 ? f.fractionOf(0) : POLE_MARK_FRACTION;
    }

    /**
     * Which of two landmarks gets the dot when they collide. A discontinuity outranks everything (it is the one
     * that changes what the curve <em>is</em> there), then a turning point, then a crossing, then a change of
     * bend — roughly, the order in which a reader would want to be told.
     */
    private static final List<Landmark.Kind> RANK = List.of(
            Landmark.Kind.POLE, Landmark.Kind.MINIMUM, Landmark.Kind.MAXIMUM,
            Landmark.Kind.ROOT, Landmark.Kind.Y_INTERCEPT, Landmark.Kind.INFLECTION);

    private static final Comparator<Landmark> PRECEDENCE =
            Comparator.<Landmark>comparingInt(l -> RANK.indexOf(l.kind())).thenComparingDouble(Landmark::x);

    private static Color colourOf(Landmark.Kind kind) {
        return switch (kind) {
            case ROOT, Y_INTERCEPT -> Palette.MARK_CROSSING;
            case MINIMUM, MAXIMUM -> Palette.MARK_TURNING;
            case INFLECTION -> Palette.MARK_BEND;
            case POLE -> Palette.MARK_POLE;
        };
    }

    // --- the tooltip ----------------------------------------------------------------------------------

    /**
     * The pointer moved: name whatever is under it, or put the tooltip away. Reports whether it landed on a
     * landmark, so the window can leave the pointer alone when it did not.
     *
     * <p>Arrives from the device event rather than through a node handler, for the same reason the wheel does:
     * the markers are drawn into a pointer-transparent layer so that presses and drags reach the canvas
     * underneath, which means nothing in the tree is going to be told it was hovered.
     */
    boolean hover(int x, int y) {
        Rect rect = canvas.layout().rect();
        float dpi = Math.max(0.01f, gui.dpi().value());
        if (rect.w() <= 0 || rect.h() <= 0 || !rect.contains(x, y)) {
            hideTip();
            return false;
        }
        float lx = (x - rect.x()) / dpi;
        float ly = (y - rect.y()) / dpi;
        List<Placed> near = new ArrayList<>();
        synchronized (placed) {
            for (Placed candidate : placed) {
                if (Math.hypot(candidate.x() - lx, candidate.y() - ly) <= HOVER_DP) {
                    near.add(candidate);
                }
            }
        }
        if (near.isEmpty()) {
            hideTip();
            return false;
        }
        near.sort(Comparator.comparingDouble(p -> Math.hypot(p.x() - lx, p.y() - ly)));
        showTip(near, lx, ly, rect.w() / dpi, rect.h() / dpi);
        return true;
    }

    /** Fill the tooltip in and put it beside the pointer, flipped back inside the canvas when it would overrun. */
    private void showTip(List<Placed> near, float atX, float atY, float widthDp, float heightDp) {
        Landmark nearest = near.get(0).landmark();
        if (tipFor != nearest) {
            // Only when the landmark changes: the influence reading evaluates the expression a few times, and
            // a pointer wandering the fourteen pixels around one dot has not asked a new question.
            tipText = describe(near);
            tipFor = nearest;
        }
        List<String> text = tipText;
        if (text.isEmpty()) {
            hideTip();
            return;
        }
        int widest = 0;
        for (String line : text) {
            widest = Math.max(widest, line.length());
        }
        // Nothing here can measure a string, so the box is sized from the character count and a generous
        // per-character width. Erring wide costs a little empty space; erring narrow would cut a number in
        // half, which is the one thing a tooltip must not do.
        float w = Math.max(TIP_MIN_W, Math.min(TIP_MAX_W, widest * TIP_CHAR_W + 2 * TIP_PAD));
        float h = text.size() * TIP_LINE_H + 2 * TIP_PAD;
        float wanted = atX + HOVER_DP + w > widthDp ? atX - HOVER_DP - w : atX + HOVER_DP;
        float above = atY + HOVER_DP + h > heightDp ? atY - HOVER_DP - h : atY + HOVER_DP;
        float px = Math.max(0, Math.min(wanted, widthDp - w));
        float py = Math.max(0, Math.min(above, heightDp - h));
        gui.batch(() -> {
            for (int i = 0; i < tipLines.size(); i++) {
                String line = i < text.size() ? text.get(i) : "";
                tipLines.get(i).text(line).visible(!line.isEmpty());
            }
            tip.size(Length.dp(w), Length.dp(h))
                    .floatAt(Length.dp(px), Length.dp(py))
                    .visible(true);
        });
    }

    /**
     * Point at the first marker on screen and open its tooltip. The headless capture's stand-in for a pointer,
     * and the only way the hover path can be photographed — a capture has no input backend, so there is nobody
     * to move a mouse. It goes through {@link #hover} rather than around it, so what it exercises is the same
     * code a real pointer runs, right down to the device-pixel conversion.
     */
    boolean hoverFirstMark() {
        Rect rect = canvas.layout().rect();
        float dpi = Math.max(0.01f, gui.dpi().value());
        Placed first;
        synchronized (placed) {
            if (placed.isEmpty()) {
                return false;
            }
            first = placed.get(0);
        }
        return hover(Math.round(rect.x() + first.x() * dpi), Math.round(rect.y() + first.y() * dpi));
    }

    private void hideTip() {
        if (tipFor == null) {
            return;
        }
        tipFor = null;
        tip.visible(false);
    }

    /**
     * What the tooltip says: what it is, where it is, and what is carrying the value there.
     *
     * <p>All the landmarks under the pointer are named, not only the nearest — the dot may be standing in for
     * several, and "root, inflection" is the whole of what is interesting about the origin of a cubic.
     */
    private List<String> describe(List<Placed> near) {
        List<String> kinds = new ArrayList<>();
        for (Placed p : near) {
            String label = p.landmark().kind().label();
            if (!kinds.contains(label)) {
                kinds.add(label);
            }
        }
        Landmark nearest = near.get(0).landmark();
        List<String> lines = new ArrayList<>(3);
        lines.add(String.join(", ", kinds));
        lines.add(nearest.kind().hasHeight()
                ? variable() + " = " + compact(nearest.x()) + "    y = " + compact(nearest.y())
                : variable() + " = " + compact(nearest.x()) + "    y is unbounded here");
        Term term;
        String axis;
        synchronized (this) {
            term = typed;
            axis = variable;
        }
        if (term != null) {
            Influence influence = Influence.at(term, List.of(axis), nearest.x());
            if (influence != null) {
                lines.add(influence.line());
            }
        }
        return List.copyOf(lines);
    }

    /**
     * A number as a reader wants it: four significant decimals at most, and no trailing zeros. A landmark is
     * pinned far below this, so what is shown is a rounding of an exact answer rather than the limit of one.
     */
    private static String compact(double v) {
        if (v == 0) {
            return "0";
        }
        double magnitude = Math.abs(v);
        if (magnitude >= 1e6 || magnitude < 1e-4) {
            return String.format("%.3e", v);
        }
        String s = String.format("%.4f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s.equals("-0") ? "0" : s;
    }

    /** The tooltip's tree, built once: a panel and three lines, hidden until something is hovered. */
    private Node tooltip() {
        Node panel = gui.column()
                .background(Palette.TIP_BG)
                .border(Length.rem(0.1f), Palette.LINE)
                .corner(Length.rem(0.4f))
                .padding(Length.dp(TIP_PAD))
                .size(Length.dp(TIP_MIN_W), Length.dp(3 * TIP_LINE_H + 2 * TIP_PAD))
                .floatAt(Length.ZERO, Length.ZERO)
                .visible(false)
                .hitInert(true)
                .scroll(false, false);
        for (int i = 0; i < 3; i++) {
            Node line = gui.text("")
                    .width(Length.FILL).height(Length.dp(TIP_LINE_H))
                    .textSize(Length.rem(i == 0 ? 0.8125f : 0.75f))
                    .textColor(i == 0 ? Palette.INK : Palette.DIM)
                    .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)
                    .hitInert(true)
                    .scroll(false, false);
            panel.append(line);
            tipLines.add(line);
        }
        return panel;
    }

    /** A marker as it was drawn: where it went, in dp within the canvas, and what it stands for. */
    private record Placed(float x, float y, Landmark landmark) {
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
