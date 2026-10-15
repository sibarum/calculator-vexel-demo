package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.framework.shell.NamedWindow;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.input.KeyEvent;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.gui.icons.Icon;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.IconButton;
import dev.vexelray.gui.widget.Segment;
import dev.vexelray.gui.widget.StatusBar;
import sibarum.probe.Log;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Modifier;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The plot explorer: one plot from the tape, popped out into a window of its own, to be looked at closely.
 *
 * <h2>What it does that the tape's plot does not</h2>
 *
 * <p>The view moves. A drag pans, the wheel zooms about the pointer (Shift for x alone, Ctrl for y alone), a
 * Shift-drag draws a rectangle to zoom into, and a double-click goes back to where the plot started. Everything the
 * mouse does, the keyboard does too — see {@link #KEYS}. Ctrl-click pins a reading to the plot, so two places can be
 * compared, and the pins stay as the view moves.
 *
 * <p><b>The curve is sampled again as the view moves</b>, so zooming in finds detail rather than stretching the tape's
 * four hundred samples. The samples are evaluated by the same engine, in the modes the line was entered in
 * ({@link Model#resample}), on a thread of this window's own: the old samples are drawn in the new view at once, and
 * the new ones replace them when they arrive. A request superseded by a later one stops where it is.
 *
 * <h2>One window, declared</h2>
 *
 * <p>The framework declares every window by name before there is a device, so there is one explorer rather than one
 * per plot: popping out another plot replaces the one on show. Its placement and zoom are remembered under
 * {@link #DECLARED}'s name, as the settings window's are.
 *
 * <h2>Threads</h2>
 *
 * <p>Input arrives on workers and on the bus, sampling finishes on the sampler, and a resize lands on the GUI thread.
 * They all meet at this object's lock, which guards the view, the samples and the pins; a picture is drawn under it
 * so that the last one written is the one for the latest state.
 */
final class PlotExplorer implements AutoCloseable {

    /** The window, as {@link Recipes} declares it. A peer, so it can be put on another screen and kept there. */
    static final NamedWindow DECLARED = NamedWindow.of("plot", "Plot explorer", 960, 680);

    /** What the keyboard does, as the help button's tooltip says it. */
    static final String KEYS = """
            Drag: pan    Wheel: zoom (Shift: x only, Ctrl: y only)
            Shift-drag: zoom to a rectangle    Double-click or Home: reset
            Arrows: pan    + and -: zoom    Shift+Up/Down: zoom y
            Shift+Left/Right: step along the curve (Ctrl: ten at a time)
            Ctrl-click or Enter: pin a reading    Delete: clear the pins
            F: fit y    G: grid    P: points    M: places    E: equal axes""";

    private static final Log LOG = Log.of("calculator.explorer");

    /** How far one wheel notch or one press of + zooms. */
    private static final double ZOOM_STEP = 0.8;

    /** How far an arrow pans, as a share of the view. */
    private static final double PAN_STEP = 0.1;

    /** How far past the view the samples reach on each side, as a share of it, so a short pan needs none. */
    private static final double MARGIN = 0.25;

    /** A press that moved less than this many pixels in all was a click, not a drag. */
    private static final double CLICK_SLOP = 4;

    /** How closely the samples sit: pixels per sample across the view. */
    enum Density {
        COARSE("Coarse", 6), NORMAL("Normal", 3), FINE("Fine", 1.5);

        final String label;
        final double px;

        Density(String label, double px) {
            this.label = label;
            this.px = px;
        }
    }

    private final Window window;
    private final Gui gui;
    private final Model model;
    private final Node heading;
    private final Node canvas;
    private final StatusBar status;
    private final Button grid;
    private final Button points;
    private final Button places;
    private final Button equal;
    private final ExecutorService sampler = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plot-explorer-sampler");
        t.setDaemon(true);
        return t;
    });
    /** Bumped by every request for samples; a request whose number is no longer this one stops. */
    private final AtomicLong generation = new AtomicLong();

    // ---- guarded by this ----

    /** The line on show, or null before anything has been popped out. */
    private Doc.Entry entry;
    /** Its samples as last evaluated, and the grid they were taken on. */
    private Graph graph;
    private Graph.Grid sampled;
    private boolean sampling;
    private View view = new View(-10, 10, -10, 10);
    private Chart.Style style = new Chart.Style(true, false, true);
    private boolean equalAxes;
    private Density density = Density.NORMAL;
    /** The canvas's box, in pixels, and its label size and corner. Zero until it has been laid out. */
    private double w;
    private double h;
    private double text = 11;
    private double corner;
    /** The pointer over the canvas, in its pixels, or NaN when it is elsewhere. */
    private double pointerX = Double.NaN;
    private double pointerY = Double.NaN;
    /** The sample stepped to from the keyboard, by its x, or NaN when the pointer is the one reading. */
    private double traced = Double.NaN;
    private final List<Graph.Sample> pins = new ArrayList<>();
    /** A Shift-drag's rectangle, {@code {x0, y0, x1, y1}} in canvas pixels, or null. */
    private double[] band;
    private double travel;

    PlotExplorer(Window window, Model model) {
        this.window = window;
        this.gui = window.gui();
        this.model = model;

        heading = gui.text("Nothing to explore yet: pop a plot out of the tape with the button under it.")
                .width(Length.grow(1f)).font(Type.MONO).textSize(Type.HEADING)
                .textColor(gui.theme().color(Role.INK));

        IconButton zoomOut = tool(Icon.ZOOM_OUT, "Zoom out", "Zoom out (-)", Landmarks.EXPLORER_ZOOM_OUT,
                () -> zoom(1 / ZOOM_STEP));
        IconButton zoomIn = tool(Icon.ZOOM_IN, "Zoom in", "Zoom in (+)", Landmarks.EXPLORER_ZOOM_IN,
                () -> zoom(ZOOM_STEP));
        IconButton fit = tool(Icon.SCALE, "Fit y", "Fit y to the curve on show (F)", Landmarks.EXPLORER_FIT,
                this::fitY);
        IconButton home = tool(Icon.HOME, "Reset the view", "Reset the view (Home, or double-click)",
                Landmarks.EXPLORER_RESET, this::reset);
        IconButton help = tool(Icon.QUESTION, "Mouse and keys", KEYS, Landmarks.EXPLORER_HELP,
                () -> gui.focus(canvas()));

        Node top = gui.row().width(Length.FILL).height(Length.AUTO).gap(Type.TIGHT).alignItems(AlignItems.CENTER)
                .padding(Type.WIDE, Type.EDGE)
                .children(heading, zoomOut.node(), zoomIn.node(), fit.node(), home.node(), help.node());

        grid = chip("Grid", true, Landmarks.EXPLORER_GRID, on -> restyle(s -> new Chart.Style(on, s.points(), s.places())));
        points = chip("Points", false, Landmarks.EXPLORER_POINTS,
                on -> restyle(s -> new Chart.Style(s.grid(), on, s.places())));
        places = chip("Places", true, Landmarks.EXPLORER_PLACES,
                on -> restyle(s -> new Chart.Style(s.grid(), s.points(), on)));
        equal = chip("Equal axes", false, Landmarks.EXPLORER_EQUAL, this::equalAxes);

        Segment<Density> samples = new Segment<Density>(gui).undoable(false);
        for (Density d : Density.values()) samples.option(d.label, d);
        samples.show(Density.NORMAL).onChange(this::density);
        gui.landmark(Landmarks.EXPLORER_DENSITY, samples.node());

        Node options = gui.row().width(Length.FILL).height(Length.AUTO).gap(Type.GAP).alignItems(AlignItems.CENTER)
                .padding(Length.ZERO, Type.EDGE)
                .children(label("Show"), grid.node(), points.node(), places.node(), equal.node(),
                        gui.box().width(Length.grow(1f)).height(Length.ZERO),
                        label("Samples"), samples.node());

        Node c = gui.box().width(Length.FILL).height(Length.grow(1f)).corner(Type.CORNER).textSize(Type.SMALL);
        this.canvas = c;
        gui.landmark(Landmarks.EXPLORER_PLOT, c);
        gui.cursor(c, CursorShape.GRAB);
        gui.onResizeUi(c, this::laidOut);
        gui.onDrag(c, this::drag);
        gui.onKey(c, this::key);
        gui.onContextMenu(c, menu -> {
            boolean pinned;
            synchronized (this) {
                pinned = !pins.isEmpty();
            }
            menu.item(Icon.HOME, "Reset the view", this::reset)
                    .item(Icon.SCALE, "Fit y to the curve on show", this::fitY)
                    .item(Icon.ZOOM_IN, "Zoom in", () -> zoom(ZOOM_STEP))
                    .item(Icon.ZOOM_OUT, "Zoom out", () -> zoom(1 / ZOOM_STEP))
                    .separator()
                    .item(Icon.TRASH, "Clear the pinned readings", pinned, this::clearPins);
        });
        Node area = gui.column().width(Length.FILL).height(Length.grow(1f))
                .padding(Type.WIDE, Type.EDGE).children(c);

        status = new StatusBar(gui).font(Type.MONO)
                .slot("reading", StatusBar.Side.LEFT, "")
                .slot("samples", StatusBar.Side.RIGHT, "")
                .slot("view", StatusBar.Side.RIGHT, "");
        status.node().width(Length.FILL).height(Length.rem(1.875f)).background(gui.theme().color(Role.PANEL));
        gui.landmark(Landmarks.EXPLORER_READING, status.slot("reading"));
        gui.landmark(Landmarks.EXPLORER_SAMPLES, status.slot("samples"));

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(window.titleBar().node(), top, options, area, status.node());

        // No per-node hooks for the pointer's moves or the wheel, so both are read off this window's own bus, as the
        // tape's plots read theirs, and hit-tested against the canvas here.
        gui.bus().subscribe(InputTopics.INPUT, e -> {
            if (e instanceof InputEvent.PointerMoved m) pointer(m.x(), m.y());
            else if (e instanceof InputEvent.Scrolled s) wheel(s);
        });
        // Samples nobody can see are not worth the engine's time.
        window.onClosed(generation::incrementAndGet);
    }

    /** The sampler. The tree and the subscriptions on it are the framework's, and it closes them. */
    @Override
    public void close() {
        generation.incrementAndGet();
        sampler.shutdownNow();
    }

    // ---- what the tape asks for ----

    /**
     * Show {@code line}'s plot, as the tape drew it, in a view fitted to it, and bring the window up. Whatever was on
     * show before goes, pins and all. A line with no plot is ignored.
     */
    void open(Doc.Entry line) {
        if (line.graph() == null) return;
        synchronized (this) {
            entry = line;
            graph = line.graph();
            sampled = Graph.TAPE;
            sampling = false;
            pins.clear();
            traced = Double.NaN;
            band = null;
            view = settle(home(graph));
            heading.text(line.input() + "   over " + graph.name());
            draw();
        }
        window.titleBar().title("Plot: " + line.input());
        window.show();
        gui.focus(canvas);
        resample();
    }

    /** What is on show, for a test or a script: the view, and the line, if any. */
    synchronized View view() {
        return view;
    }

    synchronized Doc.Entry entry() {
        return entry;
    }

    /** The samples on show. Empty before anything has been popped out. */
    synchronized List<Graph.Sample> samples() {
        return graph == null ? List.of() : graph.samples();
    }

    // ---- the view ----

    /** Where a plot starts: the tape's x range, and y fitted to its samples as the tape fits them. */
    private static View home(Graph graph) {
        double[] y = Chart.range(graph);
        return new View(graph.from(), graph.to(), y[0], y[1]);
    }

    /** A view as it may be shown: with equal axes when they are on. Holding the lock. */
    private View settle(View v) {
        return equalAxes && w > 0 && h > 0 ? v.aspect(w, h) : v;
    }

    /** Move to {@code v}, draw, and ask for samples if the ones there are do not cover it. */
    private void move(View v) {
        synchronized (this) {
            view = settle(v);
            draw();
        }
        resample();
    }

    void reset() {
        View v;
        synchronized (this) {
            if (entry == null) return;
            v = home(entry.graph());
        }
        move(v);
    }

    /** Zoom about the middle of the view, or about the reading being stepped through when there is one. */
    void zoom(double factor) {
        View v;
        synchronized (this) {
            double x = Double.isNaN(traced) ? (view.xLo() + view.xHi()) / 2 : traced;
            double y = (view.yLo() + view.yHi()) / 2;
            v = view.zoomed(factor, factor, x, y);
        }
        move(v);
    }

    private void zoomY(double factor) {
        View v;
        synchronized (this) {
            if (equalAxes) {
                v = view.zoomed(factor, factor, (view.xLo() + view.xHi()) / 2, (view.yLo() + view.yHi()) / 2);
            } else {
                v = view.zoomed(1, factor, 0, (view.yLo() + view.yHi()) / 2);
            }
        }
        move(v);
    }

    private void pan(double across, double up) {
        View v;
        synchronized (this) {
            v = view.panned(across * view.width(), up * view.height());
        }
        move(v);
    }

    /** y fitted to the curve between the view's ends. Equal axes cannot hold with it, so they go off. */
    void fitY() {
        View v;
        synchronized (this) {
            if (graph == null) return;
            double[] y = Chart.range(graph.samples(), view.xLo(), view.xHi());
            equalAxes = false;
            v = view.withY(y[0], y[1]);
        }
        equal.show(false);
        move(v);
    }

    private void equalAxes(boolean on) {
        View v;
        synchronized (this) {
            equalAxes = on;
            v = view;
        }
        equal.show(on);
        move(v);
    }

    private void restyle(java.util.function.UnaryOperator<Chart.Style> change) {
        synchronized (this) {
            style = change.apply(style);
            draw();
        }
        gui.focus(canvas);
    }

    private void density(Density d) {
        synchronized (this) {
            density = d;
        }
        gui.focus(canvas);
        resample();
    }

    // ---- sampling ----

    /** The grid the view wants: past each end by {@link #MARGIN}, as fine as the density asks. Holding the lock. */
    private Graph.Grid wanted() {
        double margin = view.width() * MARGIN;
        int target = (int) Math.ceil(w / density.px * (1 + 2 * MARGIN));
        return Graph.Grid.covering(view.xLo() - margin, view.xHi() + margin, Math.min(target, Graph.Grid.MOST));
    }

    /**
     * Sample again if the samples there are do not reach both ends of the view, or are coarser or finer than the
     * density asks for. Off the caller's thread; any earlier request still running stops at its next sample.
     */
    private void resample() {
        Graph from;
        Graph.Grid grid;
        long ticket;
        synchronized (this) {
            if (graph == null || w <= 0) return;
            grid = wanted();
            boolean covers = sampled != null && sampled.step().compareTo(grid.step()) == 0
                    && sampled.lo() <= view.xLo() && sampled.hi() >= view.xHi();
            if (covers) return;
            ticket = generation.incrementAndGet();
            sampling = true;
            from = graph;
            sampled = null;   // so that a view change while this runs asks again rather than trusting it
            status.text("samples", "sampling " + grid.count());
        }
        sampler.execute(() -> {
            if (generation.get() != ticket) return;
            long start = System.nanoTime();
            Graph fresh;
            try {
                fresh = model.resample(from, grid, () -> generation.get() != ticket);
            } catch (RuntimeException e) {
                LOG.warn("could not sample {} again", from.name(), e);
                fresh = null;
            }
            if (fresh == null) return;
            LOG.debug("sampled {} at {} in {} ms", grid.count(), grid.step(), (System.nanoTime() - start) / 1_000_000);
            synchronized (this) {
                if (generation.get() != ticket) return;
                graph = fresh;
                sampled = grid;
                sampling = false;
                draw();
            }
            resample();   // the view may have moved on while these were being taken
        });
    }

    // ---- drawing, all of it holding the lock ----

    private void laidOut(NodeLayout layout) {
        synchronized (this) {
            w = layout.rect().w();
            h = layout.rect().h();
            text = layout.textSizePx() > 0 ? layout.textSizePx() : 11;
            corner = layout.cornerTopPx();
            view = settle(view);
            draw();
        }
        resample();
    }

    /** The curve, the marks over it, and the status line. */
    private void draw() {
        canvas.picture(graph == null
                ? new Sketch().fill(0, 0, Math.max(w, 1), Math.max(h, 1), corner, gui.theme().color(Role.WELL)).picture()
                : Chart.draw(view, graph.samples(), style, gui.theme(), w, h, text, corner));
        marks();
        status.text("view", "x " + span(view.xLo(), view.xHi()) + "   y " + span(view.yLo(), view.yHi()));
        if (!sampling && graph != null) {
            status.text("samples", graph.samples().size() + " samples, every " + sampled().step().toPlainString());
        }
    }

    private Graph.Grid sampled() {
        return sampled != null ? sampled : Graph.TAPE;
    }

    /** The overlay: the pins, the reading under the pointer or stepped to, and a Shift-drag's rectangle. */
    private void marks() {
        if (graph == null || w <= 0) {
            canvas.overlay(null);
            status.text("reading", "");
            return;
        }
        Color ink = gui.theme().color(Role.INK);
        Color accent = gui.theme().color(Role.ACCENT);
        Sketch s = new Sketch();
        for (Graph.Sample p : pins) mark(s, p, accent, true);

        Graph.Sample at = reading();
        if (at != null) {
            double x = view.px(at.x(), w);
            s.line(x, 0, x, h, 1, Color.withAlpha(ink, 0.35f));
            if (!Double.isNaN(pointerY) && Double.isNaN(traced)) {
                s.line(0, pointerY, w, pointerY, 1, Color.withAlpha(ink, 0.15f));
            }
            mark(s, at, ink, false);
            status.text("reading", describe(at));
        } else {
            status.text("reading", pins.isEmpty() ? "" : pins.size() + (pins.size() == 1 ? " reading" : " readings")
                    + " pinned");
        }
        if (band != null) {
            double x0 = Math.min(band[0], band[2]), y0 = Math.min(band[1], band[3]);
            double bw = Math.abs(band[2] - band[0]), bh = Math.abs(band[3] - band[1]);
            s.fill(x0, y0, bw, bh, Color.withAlpha(accent, 0.12f));
            s.outline(x0, y0, bw, bh, 1, accent);
        }
        Picture p = s.size() == 0 ? null : s.picture();
        canvas.overlay(p);
    }

    /** A sample marked on the plot: a ring on the curve, and for a pin its reading beside it. */
    private void mark(Sketch s, Graph.Sample p, Color color, boolean labelled) {
        if (!view.showsX(p.x())) return;
        double x = view.px(p.x(), w);
        boolean shown = p.onCurve() && p.y() >= view.yLo() && p.y() <= view.yHi();
        double y = shown ? view.py(p.y(), h) : 6;
        if (shown) s.circle(x, y, 3.5, color).ring(x, y, 6, 1.5, color);
        if (!labelled) return;
        String label = p.xText() + " → " + (p.refused() ? "no value" : p.value());
        double width = Chart.width(label, text) + 8;
        double left = x + 10 + width <= w ? x + 10 : x - 10 - width;
        double top = Math.clamp(y - text - 12, 0, Math.max(0, h - text - 8));
        s.fill(left, top, width, text + 8, 4, gui.theme().color(Role.WELL));
        s.outline(left, top, width, text + 8, 4, 1, color);
        s.text(label, left + 4, top + text + 3, text, gui.theme().color(Role.INK));
    }

    /** The sample being read: the one stepped to, or the one nearest the pointer, or none. */
    private Graph.Sample reading() {
        if (!Double.isNaN(traced)) return nearest(graph.samples(), traced);
        if (Double.isNaN(pointerX)) return null;
        return nearest(graph.samples(), view.x(pointerX, w));
    }

    /** The sample nearest {@code x}, by a search, since the samples are in increasing x. */
    static Graph.Sample nearest(List<Graph.Sample> samples, double x) {
        int lo = 0, hi = samples.size() - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (samples.get(mid).x() <= x) lo = mid;
            else hi = mid;
        }
        return Math.abs(samples.get(lo).x() - x) <= Math.abs(samples.get(hi).x() - x) ? samples.get(lo) : samples.get(hi);
    }

    /** A reading as the status line says it: the exact value, and its decimal where that says something more. */
    private String describe(Graph.Sample p) {
        String out = graph.name() + " = " + p.xText() + "   →   " + (p.refused() ? "no value" : p.value());
        if (p.onCurve()) {
            String y = shortDecimal(p.y());
            if (!y.equals(p.value())) out += "      y = " + y;
        }
        return out;
    }

    static String shortDecimal(double v) {
        if (v == 0) return "0";
        BigDecimal d = new BigDecimal(v).round(new MathContext(7)).stripTrailingZeros();
        return Math.abs(v) >= 1e9 || Math.abs(v) < 1e-6 ? d.toString() : d.toPlainString();
    }

    /** {@code lo to hi}, each to as many places as the span needs. */
    private static String span(double lo, double hi) {
        double step = Chart.step(hi - lo) / 100;
        return Chart.decimal(lo, step) + " to " + Chart.decimal(hi, step);
    }

    // ---- the mouse ----

    private void pointer(float x, float y) {
        NodeLayout layout = canvas.layout();
        boolean in = layout.present() && layout.visibleRect().contains(x, y);
        synchronized (this) {
            double px = in ? x - layout.rect().x() : Double.NaN;
            double py = in ? y - layout.rect().y() : Double.NaN;
            if (Double.isNaN(px) && Double.isNaN(pointerX)) return;
            pointerX = px;
            pointerY = py;
            if (in) traced = Double.NaN;   // the pointer takes the reading back from the keyboard
            marks();
        }
    }

    private void wheel(InputEvent.Scrolled e) {
        NodeLayout layout = canvas.layout();
        if (!layout.present() || !layout.visibleRect().contains(e.x(), e.y())) return;
        Set<Modifier> held = gui.modifiers().value();
        View v;
        synchronized (this) {
            if (graph == null || w <= 0) return;
            double x = view.x(e.x() - layout.rect().x(), w);
            double y = view.y(e.y() - layout.rect().y(), h);
            v = view;
            if (e.yOffset() != 0) {
                double f = Math.pow(ZOOM_STEP, e.yOffset());
                boolean xOnly = held.contains(Modifier.SHIFT) && !equalAxes;
                boolean yOnly = held.contains(Modifier.CONTROL) && !equalAxes;
                v = v.zoomed(yOnly ? 1 : f, xOnly ? 1 : f, x, y);
            }
            if (e.xOffset() != 0) v = v.panned(e.xOffset() * PAN_STEP * v.width(), 0);
        }
        move(v);
    }

    /**
     * A press on the canvas: a drag pans, a Shift-drag draws a rectangle to zoom into, a double-click resets, and a
     * Ctrl-click pins the reading under it.
     */
    private void drag(DragEvent e) {
        View next = null;
        synchronized (this) {
            if (graph == null || w <= 0) return;
            double px = e.x() - e.nodeX();
            double py = e.y() - e.nodeY();
            switch (e.phase()) {
                case START -> {
                    travel = 0;
                    band = e.has(Modifier.SHIFT) ? new double[]{px, py, px, py} : null;
                }
                case MOVE -> {
                    travel += Math.abs(e.dx()) + Math.abs(e.dy());
                    if (band != null) {
                        band[2] = px;
                        band[3] = py;
                        marks();
                    } else {
                        next = view.panned(-e.dx() / w * view.width(), e.dy() / h * view.height());
                    }
                }
                case END -> {
                    if (band != null) {
                        double[] b = band;
                        band = null;
                        if (Math.abs(b[2] - b[0]) >= CLICK_SLOP && Math.abs(b[3] - b[1]) >= CLICK_SLOP) {
                            next = new View(view.x(Math.min(b[0], b[2]), w), view.x(Math.max(b[0], b[2]), w),
                                    view.y(Math.max(b[1], b[3]), h), view.y(Math.min(b[1], b[3]), h));
                        } else {
                            marks();
                        }
                    } else if (travel < CLICK_SLOP && e.clicks() >= 2) {
                        next = home(entry.graph());
                    } else if (travel < CLICK_SLOP && e.has(Modifier.CONTROL)) {
                        pin(nearest(graph.samples(), view.x(px, w)));
                    }
                }
            }
        }
        if (e.phase() == DragEvent.Phase.START) gui.focus(canvas);
        if (next != null) move(next);
    }

    /** Pin {@code p}'s reading, or unpin it if it is pinned. Holding the lock. */
    private void pin(Graph.Sample p) {
        if (!pins.removeIf(q -> q.xText().equals(p.xText()))) pins.add(p);
        marks();
    }

    void clearPins() {
        synchronized (this) {
            pins.clear();
            marks();
        }
    }

    // ---- the keyboard ----

    private void key(KeyEvent k) {
        if (k.has(Modifier.ALT)) return;
        boolean shift = k.has(Modifier.SHIFT);
        boolean ctrl = k.has(Modifier.CONTROL);
        switch (k.key()) {
            case LEFT -> {
                if (shift) step(ctrl ? -10 : -1);
                else if (!ctrl) pan(-PAN_STEP, 0);
            }
            case RIGHT -> {
                if (shift) step(ctrl ? 10 : 1);
                else if (!ctrl) pan(PAN_STEP, 0);
            }
            case UP -> {
                if (shift) zoomY(ZOOM_STEP);
                else if (!ctrl) pan(0, PAN_STEP);
            }
            case DOWN -> {
                if (shift) zoomY(1 / ZOOM_STEP);
                else if (!ctrl) pan(0, -PAN_STEP);
            }
            case EQUAL, NUMPAD_ADD, PAGE_UP -> {
                if (!ctrl) zoom(ZOOM_STEP);   // Ctrl+= is the window's own zoom
            }
            case MINUS, NUMPAD_SUBTRACT, PAGE_DOWN -> {
                if (!ctrl) zoom(1 / ZOOM_STEP);
            }
            case HOME, R -> reset();
            case F -> fitY();
            case G -> toggle(grid);
            case P -> toggle(points);
            case M -> toggle(places);
            case E -> toggle(equal);
            case ENTER, NUMPAD_ENTER -> {
                synchronized (this) {
                    Graph.Sample at = graph == null ? null : reading();
                    if (at != null) pin(at);
                }
            }
            case DELETE, BACKSPACE -> clearPins();
            case ESCAPE -> {
                synchronized (this) {
                    traced = Double.NaN;
                    band = null;
                    marks();
                }
            }
            default -> { }
        }
    }

    /** Act as a click on a chip would, so the chip, the picture and the handler agree. */
    private static void toggle(Button chip) {
        chip.pressed(!chip.pressed());
    }

    /**
     * Step the reading {@code by} samples along the curve, starting from whichever is being read, and bring the view
     * along when it steps off the edge.
     */
    private void step(int by) {
        View next = null;
        synchronized (this) {
            if (graph == null) return;
            List<Graph.Sample> all = graph.samples();
            Graph.Sample from = reading();
            if (from == null) from = nearest(all, (view.xLo() + view.xHi()) / 2);
            int i = Math.clamp(all.indexOf(from) + by, 0, all.size() - 1);
            traced = all.get(i).x();
            if (!view.showsX(traced)) next = view.panned(traced - (view.xLo() + view.xHi()) / 2, 0);
            else marks();
        }
        if (next != null) move(next);
    }

    // ---- building ----

    private Node canvas() {
        return canvas;
    }

    private IconButton tool(Icon icon, String name, String tip, String landmark, Runnable action) {
        IconButton b = new IconButton(gui, icon, name).tip(tip).onPress(action);
        gui.landmark(landmark, b.node());
        return b;
    }

    private Button chip(String label, boolean on, String landmark, java.util.function.Consumer<Boolean> action) {
        Button b = new Button(gui, label).kind(Button.Kind.SECONDARY).toggle(true).undoable(false).show(on)
                .onToggle(action);
        gui.landmark(landmark, b.node());
        return b;
    }

    private Node label(String text) {
        return gui.text(text).font(Type.UI).textSize(Type.SMALL).textColor(gui.theme().color(Role.DIM));
    }
}
