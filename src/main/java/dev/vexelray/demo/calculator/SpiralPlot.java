package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.plot.Camera;
import dev.vexelray.text.TextLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The spiral, drawn: where a value that is off the real line actually sits. The fourth renderer, and the one
 * that draws a <b>place</b> rather than a function — {@link Place} is its reading, as {@link Plottable} is the
 * other three's.
 *
 * <p>Why the figure is a logarithmic spiral is set out in {@link Place} and is not repeated here. What this
 * class owns is that it is a <b>tube swept along one</b>, and the two consequences of that which are geometry
 * rather than algebra.
 *
 * <h2>The tube is self-similar, and that is what makes multiplication a motion</h2>
 * The tube's radius is a fixed fraction {@link #ALPHA} of the spiral's radius <em>there</em>, so it shrinks as
 * the coil winds in. That is not a nicety. A logarithmic spiral is self-similar — scaling it is the same as
 * turning it — and a tube of constant thickness swept along one is not, which would break the property the
 * figure was chosen for: multiplying by {@code 0} adds one to every {@code turn}, which is a rotation of one
 * turn and a scaling of {@link #PITCH}, and that is a similarity of the <b>whole drawing</b> only if the
 * drawing is similar to itself. With a constant tube it would be a similarity of the centre line and a lie
 * about everything around it.
 *
 * <p>The tube may not be so fat that consecutive coils touch, and how fat it may be follows from the pitch:
 * successive turns are at radii {@code ρ} and {@code ρ·PITCH}, so the tubes clear each other when
 * {@code ALPHA < tanh(ln(PITCH)/2)}. {@link #ALPHA} is comfortably under that, because two coils that nearly
 * touch read as one thick coil and the gap is what says these are separate grades.
 *
 * <h2>It is a chart, and it is not an enclosure</h2>
 * Worth saying, because everything else here that draws boxes is making a claim with them.
 * {@link SurfacePlot}'s box is the proven range of an expression over a cell, and it is a box because that is
 * what an interval is. <b>Nothing here is evaluated and nothing here is bounded.</b> The spiral is a coordinate
 * system — a fixed surface, known in closed form, the same for every expression — so the boxes are a sampling
 * of a shape rather than evidence about a function, and the picture may be as smooth as the sampling makes it
 * without over-claiming anything, because there is no claim.
 *
 * <p>It is drawn out of axis-aligned boxes all the same, for the plain reason: this renderer has nothing
 * diagonal in it either. A patch of the surface projects to a quadrilateral and what is drawn is that
 * quadrilateral's screen-space bounding rectangle, painted far to near.
 *
 * <h2>Sampled by arc length, which is what keeps it affordable</h2>
 * Stepping along the spiral in equal increments of {@code turn} would put the same number of patches on the
 * outermost coil and on one a hundredth its size. The step is therefore inversely proportional to the local
 * radius — equal arc, not equal angle — capped at {@link #MAX_STEP} so that an inner coil is still a curve.
 * A four-turn picture costs about two and a half thousand quads however deep it goes, against a window's
 * budget of thirty thousand, and that is why there is no ceiling on the grade here: {@code 0^24} is drawn by
 * framing the coils around it, not by drawing the twenty-three between it and 1.
 *
 * <h2>Turning it costs no arithmetic, for a stronger reason than the surface's</h2>
 * {@link SurfacePlot} orbits without evaluating because its enclosures are cached per cell. This orbits
 * without evaluating because <b>there was never anything to evaluate</b>: the geometry is a spiral and the
 * value is two numbers on it, both settled by {@link Place} before the first frame. There is no cache here
 * because there is nothing a cache would hold.
 */
final class SpiralPlot {

    /** What the radius is multiplied by over one turn — one grade. The spiral's pitch, and its whole shape. */
    private static final double PITCH = 2.2;

    /** {@code ln(PITCH)}: the radius is {@code e^(K·turn)}, which is the definition of the curve. */
    private static final double K = Math.log(PITCH);

    /**
     * The fibre's radius as a fraction of the spiral's radius there.
     *
     * <p><b>This constant is doing algebra, not styling.</b> It has to stay under {@code tanh(K/2)} — at this
     * pitch, {@code 0.37} — which is the condition for the fibres over consecutive grades to be
     * <b>disjoint</b>. That disjointness is what makes the picture non-Archimedean the way the algebra is: a
     * count lives in its fibre and can never leave it, so no amount of counting inside grade {@code n} reaches
     * grade {@code n−1}, which is exactly the statement that every multiple of {@code 0} is below every
     * positive real. See {@link Place} for what went wrong when the count was on the base instead.
     *
     * <p>It is also, incidentally, what stops two coils reading as one — which is the reason it was first
     * written down, and the shallower of the two.
     */
    private static final double ALPHA = 0.23;

    /**
     * Where a count of one sits across the fibre, as a fraction of {@link #ALPHA} — and therefore where the
     * <b>surface</b> is drawn, since that is the locus worth drawing: every value the picture names has a count
     * of one. A mark inside it has fewer than one copy and a mark outside it has more.
     *
     * <p>Must agree with {@code Place.UNIT}, which is the same number seen from the reading's end.
     */
    private static final double UNIT = 0.5;

    /** Samples round the tube. It is a small circle on the screen and does not need many. */
    private static final int TUBE = 20;

    /** Patches along the outermost coil. Inner coils get fewer, in proportion to their radius. */
    private static final int OUTER_STEPS = 64;

    /** The coarsest step along the spiral, as a fraction of a turn, however small the coil has become. */
    private static final double MAX_STEP = 1.0 / 18;

    /** Turns on show by default, and the range {@code +} and {@code −} may take that to. */
    private static final int TURNS = 4;
    private static final int MIN_TURNS = 2;
    private static final int MAX_TURNS = 9;

    /** How far past the outermost and innermost marks the coil is drawn, so nothing sits on the cut end. */
    private static final double MARGIN = 0.3;

    /** How much of the canvas the projected spiral fills, leaving room for it to swing as it turns. */
    private static final double FIT = 0.82;

    /** A patch thinner than this is drawn this thick, so a coil seen edge-on stays a surface. */
    private static final float MIN_PATCH_DP = 3f;

    /** A full drag across the canvas turns the picture this far. Matching {@link SurfacePlot}'s. */
    private static final double DRAG_YAW = Math.PI;
    private static final double DRAG_PITCH = Math.PI / 2;

    /** One arrow key's worth of turn. */
    private static final double KEY_TURN = Math.toRadians(6);

    /** How far toward the ground the farthest patch is dimmed. */
    private static final float DEPTH_DIM = 0.45f;

    /** How much of a patch's brightness comes from where it is round the tube, so a tube reads as one. */
    private static final double TUBE_LIGHT = 0.3;

    /** Over how many turns either side of {@code 1} the colour ramp spends most of itself. */
    private static final double RAMP_TURNS = 1.2;

    /** A mark's dot and the halo under it, in dp. */
    private static final float DOT = 9f;
    private static final float HALO = 15f;

    /** A grade crossing's bead, and a bead of the phase circle. */
    private static final float BEAD = 3.5f;

    /** A label's height, and how wide one character of it is taken to be — estimated, as the tooltip's is. */
    private static final float LABEL_H = 15;
    private static final float LABEL_CH = 7.2f;
    private static final float LABEL_PAD = 10;

    /**
     * Where a label goes: a <b>direction</b> from the geometry and a <b>distance</b> from the screen.
     *
     * <p>Each answers a different failure. A fixed nudge in screen space moves every label the same way and
     * leaves the ones round a fibre in a heap, so the direction has to come from the fibre's own radius. But a
     * distance in fibre radii varies with how wide the fibre is <em>there</em>, and on a self-similar coil that
     * is a factor of {@link #PITCH} per turn — so an inner label would sit on its dot and an outer one an inch
     * away. {@link #LABEL_OUT} is a probe used only for its direction; {@link #LABEL_REACH} is the travel.
     */
    private static final double LABEL_OUT = 0.6;
    private static final float LABEL_REACH = 24;

    /** How many beads a count's spoke across the fibre is drawn with. */
    private static final int SPOKE = 4;

    /** How far apart two crossing names must land before the inner of the two is dropped. */
    private static final float LABEL_CLEAR = 26;

    private final Gui gui;
    private final Node canvas;
    private final Node patchLayer;
    private final Node markLayer;
    private final List<Node> patchPool = new ArrayList<>();
    private final List<Node> markPool = new ArrayList<>();
    private final List<Node> labelPool = new ArrayList<>();

    private final Consumer<String> readout;
    private final AtomicInteger revision = new AtomicInteger();
    private final Object painting = new Object();

    private List<Place.Mark> marks = List.of();
    /** The innermost and outermost turns the value reaches, which the framing is built out from. */
    private double inner;
    private double outer;
    /** How many turns are drawn. */
    private int turns = TURNS;
    private Camera camera = Camera.DEFAULT;

    SpiralPlot(Gui gui, Consumer<String> readout) {
        this.gui = gui;
        this.readout = readout;
        this.canvas = gui.box()
                .width(Length.FILL).height(Length.grow(1))
                .background(Palette.PLOT_BG)
                .corner(Length.rem(0.5f))
                .scroll(false, false);
        this.patchLayer = layer();
        this.markLayer = layer();
        // The marks over the surface rather than in it. A dot occluded by the coil in front of it would be
        // hidden exactly when the picture has been turned to look at it, which is the one time it is being
        // asked for -- the same reason a curve's landmarks are a layer and not a stripe.
        canvas.children(patchLayer, markLayer);
        gui.onDrag(canvas, this::drag);
        gui.dragLocksPointer(canvas, true);
        gui.onResize(canvas, box -> invalidate());
    }

    Node node() {
        return canvas;
    }

    // --- what is drawn --------------------------------------------------------------------------------

    /** Draw {@code place} on a worker. */
    void show(Place place) {
        gui.async(() -> showNow(place));
    }

    /** {@link #show} without the worker: everything happens on the caller. */
    void showNow(Place place) {
        synchronized (this) {
            this.marks = place.marks();
            this.inner = place.innermost();
            this.outer = place.outermost();
            reset();
        }
        invalidate();
    }

    /** Paint on the calling thread and return once the picture is up — the headless capture's handshake. */
    void settle() {
        paint(revision.incrementAndGet());
    }

    // --- the transforms -------------------------------------------------------------------------------

    /** Back to the default depth of coil and the three-quarter view. */
    synchronized void reset() {
        this.turns = Math.max(TURNS, needed());
        this.camera = Camera.DEFAULT;
    }

    /**
     * Down to the coils the value actually needs, and no more — what <b>Fit</b> does here.
     *
     * <p>The correspondence with what Fit does elsewhere is exact: on a curve it re-runs the framing policy so
     * the window holds the curve and nothing spare, and on a spiral there is one number that means the same
     * thing, which is how many turns are on screen.
     */
    synchronized void fitVertically() {
        this.turns = needed();
    }

    /** A notch adds a turn to the coil, or takes one off — never fewer than the value needs. */
    synchronized void zoom(int notches) {
        this.turns = Math.max(needed(), Math.min(MAX_TURNS, turns + notches));
    }

    synchronized void turn(double yaw, double pitch) {
        camera = camera.turned(yaw, pitch);
    }

    synchronized Camera camera() {
        return camera;
    }

    synchronized void camera(Camera eye) {
        this.camera = eye;
    }

    /** An arrow key's turn, in the same direction the surface plot's takes it. */
    void nudge(double yawSteps, double pitchSteps) {
        turn(-yawSteps * KEY_TURN, pitchSteps * KEY_TURN);
        invalidate();
    }

    /** The wheel lengthens the coil, since there is nothing here to zoom into. True when it was over us. */
    boolean wheel(double notches, int x, int y) {
        Rect rect = canvas.layout().rect();
        float dpi = Math.max(0.01f, gui.dpi().value());
        float px = x / dpi;
        float py = y / dpi;
        if (px < rect.x() || py < rect.y() || px > rect.x() + rect.w() || py > rect.y() + rect.h()) {
            return false;
        }
        zoom(notches > 0 ? 1 : -1);
        invalidate();
        return true;
    }

    void invalidate() {
        int mine = revision.incrementAndGet();
        gui.async(() -> paint(mine));
    }

    /** The fewest whole turns that hold every mark and the crossing at 1, with a margin at each end. */
    private synchronized int needed() {
        return Math.max(MIN_TURNS, (int) Math.ceil(outer - inner + 2 * MARGIN));
    }

    /**
     * The outermost turn drawn, which is what the whole picture is scaled to.
     *
     * <p><b>Framing runs from the outside in</b>, and it has to. The outermost coil fills the canvas and every
     * coil after it is a known fraction of the one before, so the outer end is the only end that can be an
     * anchor — pinning the <em>inner</em> end instead and letting the picture grow outward would leave the
     * scale depending on how many turns happen to be on show, and pressing {@code +} would shrink everything
     * a reader was already looking at.
     *
     * <p>So it sits just outside the outermost mark and the coil winds inward from there for as many turns as
     * are asked for. {@link #needed} guarantees that is far enough to reach the innermost mark. It is also why
     * there is no ceiling on the grade here: {@code 0^24} is drawn by winding down to it, not by drawing the
     * twenty-three coils between it and 1 at a scale where none of them is visible.
     */
    private static double top(double outer) {
        return outer + MARGIN;
    }

    private void drag(DragEvent e) {
        double dYaw = e.dx() / Math.max(1f, e.nodeW()) * DRAG_YAW;
        double dPitch = e.dy() / Math.max(1f, e.nodeH()) * DRAG_PITCH;
        // Dragging left turns the near side left, which means turning the picture the other way -- the same
        // handedness the surface plot settled on, and the two must not disagree in one window.
        turn(-dYaw, dPitch);
        invalidate();
    }

    // --- the geometry ---------------------------------------------------------------------------------

    /**
     * A point of the coil, in the normalised cube {@link Camera#project} takes.
     *
     * <p>Measured <b>relative to the outermost turn drawn</b>, so the exponential is always of something at or
     * below zero and a value twenty grades in costs no more precision than one grade in does. That is what
     * lets the grade go as deep as the algebra does.
     *
     * <p>The phase is measured from the <b>crest</b>: {@code 0} on top, where the positive reals and the whole
     * grade sequence run, and {@code π} directly underneath, where their negatives do. {@code i} is a quarter
     * turn to the outside and {@code −i} a quarter turn to the inside, which is arbitrary between the two and
     * fixed here so the two are never confused.
     */
    private static double[] at(double turn, double phase, double top, double fibre) {
        double radius = Math.exp(K * (turn - top));
        double across = ALPHA * radius * fibre;
        double around = 2 * Math.PI * turn;
        double out = radius + across * Math.sin(phase);
        double scale = 0.5 / (1 + ALPHA);
        return new double[]{out * Math.cos(around) * scale,
                            out * Math.sin(around) * scale,
                            across * Math.cos(phase) * scale};
    }

    /** The drawn surface: {@link #at} at the unit locus, where every named value lives. */
    private static double[] at(double turn, double phase, double top) {
        return at(turn, phase, top, UNIT);
    }

    /**
     * Where along the spiral the patches begin and end, sampled by arc rather than by angle.
     *
     * <p>The step is inversely proportional to the local radius, so every patch covers about the same distance
     * on the screen however far in it is, and capped at {@link #MAX_STEP} so that a coil small enough for the
     * proportional step to exceed a whole turn is still drawn as a curve rather than as a chord.
     */
    private static double[] stops(double bottom, double top) {
        List<Double> found = new ArrayList<>();
        double at = top;
        while (at > bottom) {
            found.add(at);
            at -= Math.min(MAX_STEP, Math.exp(-K * (at - top)) / OUTER_STEPS);
        }
        found.add(bottom);
        double[] out = new double[found.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = found.get(i);
        }
        return out;
    }

    // --- painting -------------------------------------------------------------------------------------

    private void paint(int mine) {
        float[] size = size();
        if (size[0] < 2 || size[1] < 2) {
            return;                                  // not laid out yet; the next invalidate will find it
        }
        List<Place.Mark> shown;
        Camera eye;
        int coils;
        double top;
        synchronized (this) {
            shown = marks;
            eye = camera;
            coils = turns;
            top = top(outer);
        }
        double bottom = top - coils;
        double[] stops = stops(bottom, top);
        List<Patch> patches = new ArrayList<>((stops.length - 1) * TUBE);
        for (int i = 0; i + 1 < stops.length; i++) {
            for (int j = 0; j < TUBE; j++) {
                patches.add(quad(eye, stops[i], stops[i + 1], j, top, bottom));
            }
            if (revision.get() != mine) {
                return;                              // overtaken; whoever overtook us will draw
            }
        }
        // Far to near. Unlike a heightmap this is not a set of prisms over disjoint floor squares, so the
        // floor's own ordering will not do it -- a coil passes behind itself. The patch's own projected depth
        // is the ordering, which is available because Camera.project carries it.
        patches.sort(Comparator.comparingDouble(Patch::depth).reversed());
        synchronized (painting) {
            if (revision.get() != mine) {
                return;
            }
            Frame frame = Frame.around(patches, size);
            gui.batch(() -> {
                drawPatches(patches, size, frame);
                drawFurniture(shown, eye, top, bottom, frame);
            });
        }
        readout.accept(describe(shown, coils));
    }

    /** One patch of the coil, projected: the rectangle that covers it, how far away it is, how to colour it. */
    private static Patch quad(Camera eye, double s0, double s1, int j, double top, double bottom) {
        double p0 = j * 2 * Math.PI / TUBE;
        double p1 = (j + 1) * 2 * Math.PI / TUBE;
        double minU = Double.MAX_VALUE;
        double maxU = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            double[] p = at((corner & 1) == 0 ? s0 : s1, (corner & 2) == 0 ? p0 : p1, top);
            Camera.Point q = eye.project(p[0], p[1], p[2]);
            minU = Math.min(minU, q.u());
            maxU = Math.max(maxU, q.u());
            minV = Math.min(minV, q.v());
            maxV = Math.max(maxV, q.v());
        }
        double midS = (s0 + s1) / 2;
        double midP = (p0 + p1) / 2;
        double[] middle = at(midS, midP, top);
        Camera.Point centre = eye.project(middle[0], middle[1], middle[2]);
        // The ramp is the magnitude, measured from 1 rather than from the ends of what happens to be drawn.
        //
        // That distinction is the whole of whether the colour means anything. Keyed to the drawn span it is a
        // position in the frame, so 0^24 -- twenty-five turns of it -- comes out uniformly at the warm end
        // while 2÷0 comes out spread across the whole ramp, and the same colour says different things in the
        // two pictures. Keyed to the grade it is a fact about the value: teal at 1, cooling inward toward the
        // zeros and warming outward toward the infinities, whatever else is on screen.
        double heat = 0.5 + 0.5 * Math.tanh(midS / RAMP_TURNS);
        double lit = 1 - TUBE_LIGHT + TUBE_LIGHT * Math.cos(midP);
        return new Patch(minU, maxU, minV, maxV, centre.depth(), heat, lit);
    }

    private void drawPatches(List<Patch> patches, float[] size, Frame frame) {
        double farthest = patches.isEmpty() ? 1 : patches.get(0).depth();
        double nearest = patches.isEmpty() ? 0 : patches.get(patches.size() - 1).depth();
        double span = Math.max(1e-9, farthest - nearest);
        int drawn = 0;
        for (Patch p : patches) {
            float x = frame.x(p.minU());
            float y = frame.y(p.maxV());                          // screen y grows downward, v upward
            float w = (float) Math.max(MIN_PATCH_DP, (p.maxU() - p.minU()) * frame.scale());
            float h = (float) Math.max(MIN_PATCH_DP, (p.maxV() - p.minV()) * frame.scale());
            if (x > size[0] || y > size[1] || x + w < 0 || y + h < 0) {
                continue;
            }
            float distance = (float) ((p.depth() - nearest) / span);
            pooled(patchPool, patchLayer, drawn++)
                    .visible(true)
                    .background(dim(shade(ramp(p.heat()), p.lit()), distance))
                    .size(Length.dp(w), Length.dp(h))
                    .floatAt(Length.dp(x), Length.dp(y));
        }
        hideFrom(patchPool, drawn);
    }

    /**
     * Everything drawn over the coil rather than into it: the grade crossings, the value's own marks, and the
     * phase circle when there is a phase to read.
     *
     * <p>They share one pass because they share the one property that matters: none of them is occluded. See
     * the class note on why that is right rather than convenient.
     */
    private void drawFurniture(List<Place.Mark> shown, Camera eye, double top, double bottom, Frame frame) {
        Dots dots = new Dots(frame, top);
        // The whole grades, named where the coil crosses them, all on the crest.
        //
        // They are all on ONE RAY as well, and that is not a coincidence to be designed around -- it is the
        // figure saying what it is for. A turn is a grade, so every whole grade is at the same angle and the
        // sequence 1, 0, 0^2, 0^3 marches straight in along a line, each at PITCH times the last. The picture
        // is a ruler and that ray is its scale.
        //
        // Which is exactly why they are walked from the OUTSIDE IN: the same convergence that makes the ray
        // readable brings the inner names within a few dp of each other, and the outer ones are the legible
        // ones, so they get first claim on the space. See Dots.crossing.
        for (int n = (int) Math.floor(top); n >= Math.ceil(bottom); n--) {
            if (standing(shown, n)) {
                continue;
            }
            // A value on this crossing's own spoke -- same grade, same phase, a count that is not one -- is the
            // case where both names want the same patch of screen, because a label's direction comes from the
            // fibre's radius and the two of them are on one radius. So this one goes the other way, inward,
            // and both read: the crossing names the unit locus and the value names where the count took it.
            dots.crossing(eye, n, sharing(shown, n));
        }
        for (Place.Mark mark : shown) {
            // Each of the two fibre coordinates gets its own piece of furniture, and only when it has moved
            // the mark. A phase is hard to read off a dot alone -- a quarter turn round a fibre seen at three
            // quarters is a small displacement -- so the unit circle it turned along is beaded, which gives
            // the eye the zero to measure against. A count is beaded as the spoke it travelled out along.
            if (Math.abs(mark.phase()) > 1e-9) {
                for (int j = 0; j < TUBE; j++) {
                    dots.bead(eye, mark.turn(), j * 2 * Math.PI / TUBE, UNIT);
                }
            }
            if (Math.abs(mark.fibre() - UNIT) > 1e-9) {
                dots.spoke(eye, mark);
            }
            dots.value(eye, mark);
        }
        dots.done();
    }

    /**
     * Whether a mark is standing exactly on the crossing at grade {@code n}, in which case the crossing is
     * left out and the value's own mark speaks for the place.
     *
     * <p>Not tidying. {@code ω} is one turn out on the crest, which is precisely where the crossing named
     * {@code ω} is, so drawing both puts a dot on a dot and a word across a word — and what a reader takes
     * from that is that the two are near each other rather than that they are the same place.
     */
    private static boolean standing(List<Place.Mark> shown, double crossing) {
        for (Place.Mark mark : shown) {
            // All three coordinates, and the third one is the point of this test now. While the count lived on
            // the base, a value with two copies had a different turn from the crossing and this could not
            // confuse them; with the count in the fibre, 2ω sits at exactly ω's turn and exactly ω's phase and
            // is emphatically not at ω -- it is further out in the same fibre, which is the one thing the
            // picture is trying to show. Dropping ω because 2ω shares two of its three coordinates removed the
            // landmark that the mark was there to be read against.
            if (Math.abs(mark.turn() - crossing) < 1e-9
                    && Math.abs(mark.phase()) < 1e-9
                    && Math.abs(mark.fibre() - UNIT) < 1e-9) {
                return true;
            }
        }
        return false;
    }

    /** Whether a mark is on this crossing's own spoke: its grade and its phase, with a count that is not one. */
    private static boolean sharing(List<Place.Mark> shown, double crossing) {
        for (Place.Mark mark : shown) {
            if (Math.abs(mark.turn() - crossing) < 1e-9 && Math.abs(mark.phase()) < 1e-9) {
                return true;
            }
        }
        return false;
    }

    /** What the coil is called where it crosses grade {@code n}: {@code 1}, then {@code 0ⁿ} in, {@code ωⁿ} out. */
    private static String crossingName(int n) {
        if (n == 0) {
            return "1";
        }
        String base = n < 0 ? "0" : "ω";
        return Math.abs(n) == 1 ? base : base + "^" + Math.abs(n);
    }

    /** Where the furniture goes, and the running index into the two pools that hold it. */
    private final class Dots {

        private final Frame frame;
        private final double top;
        private int marked;
        private int labelled;
        /** Where the last crossing put its name, so the next one in can tell whether there is room. */
        private float lastLabelX = Float.NEGATIVE_INFINITY;
        private float lastLabelY = Float.NEGATIVE_INFINITY;

        Dots(Frame frame, double top) {
            this.frame = frame;
            this.top = top;
        }

        private float[] screen(Camera eye, double turn, double phase, double fibre) {
            double[] on = at(turn, phase, top, fibre);
            Camera.Point q = eye.project(on[0], on[1], on[2]);
            return new float[]{frame.x(q.u()), frame.y(q.v())};
        }

        void bead(Camera eye, double turn, double phase, double fibre) {
            float[] at = screen(eye, turn, phase, fibre);
            box(marked++, BEAD, Palette.AXIS, at[0], at[1]);
        }

        /**
         * One whole grade, dotted and — if there is room for it — named.
         *
         * <p>The dot is always drawn and the name is not, which is the right way round. Coming in from the
         * outside the crossings converge geometrically, so past a few turns their names would overlap into
         * something unreadable that also hides the coil; and a name is the part that can be dropped, because
         * the ray is a scale and a reader who can see {@code 0} and {@code 0^2} on it can count the rest of
         * the beads inward. What is never dropped is a value's own label — see {@link #value}.
         */
        void crossing(Camera eye, int n, boolean inward) {
            float[] at = screen(eye, n, 0, UNIT);
            box(marked++, DOT * 0.7f, Palette.MARK_HALO, at[0], at[1]);
            box(marked++, DOT * 0.45f, n == 0 ? Palette.MARK_CROSSING
                                     : n < 0 ? Palette.LOW : Palette.SPIRAL_OMEGA, at[0], at[1]);
            float[] name = beside(eye, n, 0, UNIT, at, inward ? -LABEL_OUT : LABEL_OUT);
            if (Math.hypot(name[0] - lastLabelX, name[1] - lastLabelY) < LABEL_CLEAR) {
                return;
            }
            lastLabelX = name[0];
            lastLabelY = name[1];
            label(crossingName(n), Palette.INK, name);
        }

        /**
         * The spoke from the unit locus out to where a count actually is, beaded.
         *
         * <p>It is the count's displacement drawn as a displacement, which is the whole of what changed here.
         * A mark off the coil's surface would otherwise read as a mark adrift beside the coil; the spoke says
         * it is <em>in the fibre over that grade</em>, and that it got there by counting.
         */
        void spoke(Camera eye, Place.Mark mark) {
            for (int i = 1; i < SPOKE; i++) {
                double along = UNIT + (mark.fibre() - UNIT) * i / SPOKE;
                bead(eye, mark.turn(), mark.phase(), along);
            }
        }

        void value(Camera eye, Place.Mark mark) {
            float[] at = screen(eye, mark.turn(), mark.phase(), mark.fibre());
            box(marked++, HALO, Palette.MARK_HALO, at[0], at[1]);
            box(marked++, DOT, Palette.MARK_TURNING, at[0], at[1]);
            label(mark.label(), Palette.INK,
                  beside(eye, mark.turn(), mark.phase(), mark.fibre(), at, LABEL_OUT));
        }

        /** {@link #LABEL_REACH} from {@code at}, along the fibre's radius — outward, or inward for a negative. */
        private float[] beside(Camera eye, double turn, double phase, double fibre, float[] at, double out) {
            float[] probe = screen(eye, turn, phase, fibre + out);
            double dx = probe[0] - at[0];
            double dy = probe[1] - at[1];
            double length = Math.hypot(dx, dy);
            // Edge-on, the normal projects to nothing and there is no direction to be had. Upward is as good
            // as any and better than a division by zero.
            return length < 1e-3
                    ? new float[]{at[0], at[1] - LABEL_REACH}
                    : new float[]{(float) (at[0] + dx / length * LABEL_REACH),
                                  (float) (at[1] + dy / length * LABEL_REACH)};
        }

        private void box(int index, float diameter, Color colour, float cx, float cy) {
            pooled(markPool, markLayer, index)
                    .visible(true)
                    .background(colour)
                    .corner(Length.dp(diameter / 2))
                    .size(Length.dp(diameter), Length.dp(diameter))
                    .floatAt(Length.dp(cx - diameter / 2), Length.dp(cy - diameter / 2));
        }

        private void label(String text, Color colour, float[] at) {
            while (labelPool.size() <= labelled) {
                Node made = gui.text("").hitInert(true).scroll(false, false);
                markLayer.append(made);
                labelPool.add(made);
            }
            float w = text.length() * LABEL_CH + LABEL_PAD;
            labelPool.get(labelled++)
                    .visible(true)
                    .text(text)
                    .size(Length.dp(w), Length.dp(LABEL_H))
                    // A ground under the text, because a name lands wherever the geometry puts it and much of
                    // where it can land is on the coil itself.
                    .background(Palette.MARK_HALO).corner(Length.dp(3))
                    .textSize(Length.rem(0.6875f)).textColor(colour)
                    .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                    .floatAt(Length.dp(at[0] - w / 2), Length.dp(at[1] - LABEL_H / 2));
        }

        void done() {
            hideFrom(markPool, marked);
            hideFrom(labelPool, labelled);
        }
    }

    /**
     * What the status line says: the <b>algebra</b>, because the picture is already the geometry.
     *
     * <p>So it names the grade and the count rather than a turn and an angle, which is the way round that
     * makes the two useful together — a reader who wants to know where a mark is looks at it, and a reader who
     * wants to know what put it there reads this.
     */
    private static String describe(List<Place.Mark> shown, int coils) {
        StringBuilder said = new StringBuilder();
        for (int i = 0; i < shown.size(); i++) {
            Place.Mark mark = shown.get(i);
            said.append(i == 0 ? "" : "    ").append(mark.label()).append(": grade ").append(mark.grade());
            if (mark.count() != 1) {
                said.append(", ").append(number(mark.count())).append(" copies");
            }
            said.append(", ").append(Math.abs(mark.phase()) < 1e-9
                    ? "no phase"
                    : turns(mark.phase()) + " of phase");
        }
        return said.append("    ").append(coils).append(coils == 1 ? " turn shown" : " turns shown").toString();
    }

    /** An angle as the fraction of a turn it is, since the phases that occur are quarters and halves. */
    private static String turns(double angle) {
        double of = angle / (2 * Math.PI);
        long quarters = Math.round(of * 4);
        return Math.abs(of * 4 - quarters) < 1e-6
                ? switch ((int) (((quarters % 4) + 4) % 4)) {
                    case 0 -> "none";
                    case 1 -> "a quarter";
                    case 2 -> "half";
                    default -> "three quarters";
                  }
                : trim(String.format("%.4f", of)) + " of a turn";
    }

    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : trim(String.format("%.4f", value));
    }

    private static String trim(String s) {
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    // --- the palette ----------------------------------------------------------------------------------

    /**
     * The magnitude ramp: {@code heat} is 0 at the innermost coil and 1 at the outermost, so it runs from what
     * a zero is drawn in to what an infinity is. The far end is the pole's own red — see {@link Palette}.
     */
    private static Color ramp(double heat) {
        double t = Math.max(0, Math.min(1, heat));
        return t < 0.5
                ? mix(Palette.LOW, Palette.MID, t * 2)
                : mix(Palette.MID, Palette.SPIRAL_OMEGA, (t - 0.5) * 2);
    }

    /** The tube's own light: brightest along the crest, where the reals are. */
    private static Color shade(Color colour, double lit) {
        float f = (float) Math.max(0, Math.min(1.2, lit));
        return Color.rgba(colour.r() * f, colour.g() * f, colour.b() * f, colour.a());
    }

    private static Color dim(Color colour, float distance) {
        return mix(colour, Palette.PLOT_BG, Math.max(0, Math.min(1, distance)) * DEPTH_DIM);
    }

    private static Color mix(Color from, Color to, double t) {
        float f = (float) t;
        return Color.rgba(from.r() + (to.r() - from.r()) * f,
                          from.g() + (to.g() - from.g()) * f,
                          from.b() + (to.b() - from.b()) * f,
                          from.a() + (to.a() - from.a()) * f);
    }

    // --- the canvas -----------------------------------------------------------------------------------

    /**
     * How projected coordinates land on the canvas: a scale and a centre, both measured from the <b>coil that
     * was actually built</b>.
     *
     * <p>{@link SurfacePlot} divides by {@link Camera#reach} and centres on the canvas, which is right there: a
     * surface fills its volume by construction, since the volume is fitted to it, and the volume is centred on
     * the origin. A coil is neither. It is flat — {@link #ALPHA} of a radius tall against a whole radius wide,
     * so a cube-sized denominator throws away most of the canvas — and it is <b>lopsided</b>, because a
     * logarithmic spiral is not centred on its own pole: the outermost turn is {@link #PITCH} times the one
     * inside it, so the drawn figure sits well off to one side of the point it winds down to.
     *
     * <p>Measuring the patches instead settles both at once and costs nothing, since they have all been
     * projected already by the time this is asked for.
     */
    private record Frame(double scale, float cx, float cy) {

        static Frame around(List<Patch> patches, float[] size) {
            double minU = Double.MAX_VALUE;
            double maxU = -Double.MAX_VALUE;
            double minV = Double.MAX_VALUE;
            double maxV = -Double.MAX_VALUE;
            for (Patch p : patches) {
                minU = Math.min(minU, p.minU());
                maxU = Math.max(maxU, p.maxU());
                minV = Math.min(minV, p.minV());
                maxV = Math.max(maxV, p.maxV());
            }
            if (patches.isEmpty()) {
                return new Frame(1, size[0] / 2, size[1] / 2);
            }
            double scale = FIT * Math.min(size[0] / Math.max(1e-9, maxU - minU),
                                          size[1] / Math.max(1e-9, maxV - minV));
            return new Frame(scale,
                             (float) (size[0] / 2 - (minU + maxU) / 2 * scale),
                             (float) (size[1] / 2 + (minV + maxV) / 2 * scale));
        }

        float x(double u) {
            return (float) (cx + u * scale);
        }

        float y(double v) {
            return (float) (cy - v * scale);
        }
    }

    private float[] size() {
        Rect content = canvas.layout().content();
        float dpi = Math.max(0.01f, gui.dpi().value());
        return new float[]{content.w() / dpi, content.h() / dpi};
    }

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

    /** One patch of the coil, projected: the rectangle that covers it, how far away it is, and how it is lit. */
    private record Patch(double minU, double maxU, double minV, double maxV,
                         double depth, double heat, double lit) {
    }
}
