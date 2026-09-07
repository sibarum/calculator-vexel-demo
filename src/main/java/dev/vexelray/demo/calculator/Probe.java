package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import sibarum.kronometer.Cell;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Interp;
import sibarum.kronometer.anim.Ease;
import sibarum.tactroller.api.InputEvent;

import java.util.Locale;

/**
 * The readout that follows the pointer to the nearest sampled point on the curve.
 *
 * <h2>Why this is not {@code widget.Tooltip}</h2>
 *
 * <p>{@code Tooltip} is explicitly the other thing: anchored to a control's box, armed by a delay, and — its
 * own javadoc — <em>"once shown it never moves"</em>. Every one of those is right for help text and wrong for a
 * data probe, which has to track the pointer, appear at once, and change its contents continuously. So this is
 * application-built, and it is {@code docs/framework-notes.md} FN-4: a pointer-anchored readout is a real
 * component and this is the second application that would have wanted one.
 *
 * <h2>Hit-inert, which is the whole of its interaction design</h2>
 *
 * <p>The bubble appears under the pointer. If it were an ordinary node it would immediately become the
 * pointer's target, so the hover that produced it would end, so it would hide, so the hover would return — a
 * flicker at the frame rate, and one whose cause is not obvious from looking at it. {@code hitInert} makes
 * hit-testing pass straight through, so the probe cannot interfere with the gesture that summoned it. It also
 * means an orbit begun on top of the bubble still orbits.
 *
 * <h2>Snapped, not interpolated</h2>
 *
 * <p>It reports <b>a sample</b>, not the curve at the pointer. A plot draws through points it evaluated, and a
 * readout that interpolated between them would be quoting a number nothing computed — which for a calculator is
 * the wrong kind of wrong. So the probe finds the nearest sample in screen space and says exactly what is
 * there, or shows nothing at all.
 *
 * <h2>It fades, and its visibility is a function of that fade</h2>
 *
 * <p>The prototype's bubble crosses {@code .09s} of opacity, and the reason it is not a plain {@code visible}
 * toggle is that this thing appears and disappears constantly — it is driven by <em>where the pointer is</em>,
 * so sweeping across a curve at a shallow angle flickers it on and off several times a second. A fade turns
 * that into something the eye reads as one object moving, and an interrupted fade reverses from wherever it
 * got to rather than restarting, which is what {@code retarget} on a bound cell gives for nothing.
 *
 * <p><b>{@code visible} is derived from the opacity rather than set beside it.</b> Two writers of "is the probe
 * showing" is how a fade-out ends with a node that is transparent and still there — invisible to the eye, and
 * still an element with a name for anything reading the tree, which is exactly what an {@code await probe}
 * would then hang on. Deriving it means there is one answer and it cannot disagree with itself.
 */
final class Probe {

    /** How near the pointer has to be, in pixels, before a sample is worth pointing at. */
    private static final double REACH = 44;

    /** Where the bubble sits relative to the sample, so the pointer is never on top of the text. */
    private static final double OFFSET_X = 14;
    private static final double OFFSET_Y = -10;

    /** The prototype's {@code .09s}. Linear, because §11's rule is fade linear and travel eased. */
    private static final Dur FADE = Dur.ms(90);

    private final Gui gui;
    private final KronoGui krono;
    private final Node root;
    private final Node label;
    private final Node value;
    private final Node note;

    /**
     * How far in the bubble is, from 0 to 1. The one answer to "is the probe showing".
     *
     * <p>A cell rather than a flag and a timer: retargeting it mid-fade continues from where it actually is, so
     * a pointer that leaves the curve and comes straight back does not restart the fade from nothing.
     */
    private final Cell<Double> fade;

    /**
     * The opacity last written to the node.
     *
     * <p>A bound cell lands <em>every frame</em>, settled or not, so a sink that writes unconditionally writes
     * a prop sixty times a second for the life of the window and keeps a render-on-demand loop from ever
     * settling. Comparing first is the same rule {@code March.view} follows for the camera, and it is needed
     * for the same reason: the binding is a delivery, not a change.
     */
    private double shown = -1;

    /** Whether the probe has been asked to be showing. Written from the pointer thread, read from it too. */
    private volatile boolean showing;

    /** The curve in world space, as the last geometry build left it. Read on the pointer thread. */
    private volatile double[] curve = new double[0];

    /** The domain those samples span, for reporting x as the user's number rather than the box's. */
    private volatile double domainLo = -6;
    private volatile double domainHi = 6;

    Probe(Gui gui, KronoGui krono) {
        this.gui = gui;
        this.krono = krono;
        label = line(Type.READOUT, Role.FAINT);
        value = line(Type.VALUE, Role.INK);
        note = line(Type.FIGURE, Role.FAINT);

        root = gui.box()
                .direction(Direction.COLUMN)
                .width(Length.AUTO).height(Length.AUTO)
                .padding(Length.dp(7), Length.dp(9))
                .corner(Length.dp(6))
                // The prototype's bubble is rgba(22,24,38,.95) -- *darker* than the page, not lifted off it. A
                // readout floating over a plot has to stay out of the way, and a raised surface reads as another
                // panel; a sunken one reads as a note.
                .background(gui.theme().color(Role.WELL))
                .border(Type.RULE, gui.theme().color(Look.ACCENT_LINE))
                .elevation(gui.theme().elevation(dev.vexelray.gui.core.style.Relief.FLOATING))
                .children(label, value, note)
                .hitInert(true)
                .opacity(0)
                .visible(false);
        gui.landmark(Landmarks.PROBE, root);

        fade = krono.bound("probe.fade", 0.0, root, (node, value) -> land(node, value));
    }

    /**
     * Put the fade on the node — opacity, and the visibility that follows from it.
     *
     * <p>Hidden the moment the opacity reaches zero, which is the frame it becomes invisible anyway, so nothing
     * is seen to vanish. {@code retarget} delivers its endpoint exactly, so that frame is reached rather than
     * approached.
     */
    private void land(Node node, double opacity) {
        if (opacity == shown) {
            return;
        }
        shown = opacity;
        node.opacity((float) opacity);
        node.visible(opacity > 0);
    }

    /**
     * Ask for the bubble to be in or out.
     *
     * <p>Gated on the intent rather than fired per pointer event, and that is not only tidiness: driving the
     * cell afresh on every {@code PointerMoved} would leave it perpetually mid-curve, so the frame loop would
     * be told there is an animation in flight the whole time the pointer is anywhere near the plot. Asking only
     * when the answer changes means the fade is running exactly when it is running.
     */
    private void reveal(boolean on) {
        if (on == showing) {
            return;
        }
        showing = on;
        krono.retarget(fade, on ? 1.0 : 0.0, FADE, Ease.LINEAR, Interp.DOUBLE);
    }

    private Node line(Length size, Role role) {
        return gui.text("").font(Type.MONO).textSize(size).wordWrap(false)
                .textColor(gui.theme().color(role));
    }

    Node node() {
        return root;
    }

    /** The samples to snap to, handed over whenever the geometry is rebuilt. */
    void samples(double[] worldXyz, double lo, double hi) {
        this.curve = worldXyz;
        this.domainLo = lo;
        this.domainHi = hi;
    }

    /**
     * Follow the pointer.
     *
     * <p>Subscribed inline to the raw input topic, for the same reason the wheel is (see {@link Gestures}):
     * there is no per-node pointer-move hook, and the handler does nothing but read the read-model and write
     * node props, both of which are safe from any thread.
     */
    void install(Node viewport, java.util.function.Supplier<Lens> lens) {
        gui.bus().subscribe(InputTopics.INPUT, event -> {
            if (event instanceof InputEvent.PointerMoved m) {
                follow(viewport, lens.get(), m.x(), m.y());
            }
        });
    }

    private void follow(Node viewport, Lens lens, int px, int py) {
        NodeLayout box = viewport.layout();
        double[] points = curve;
        if (box == null || points.length < 3) {
            return;
        }
        var r = box.visibleRect();
        if (px < r.x() || px >= r.x() + r.w() || py < r.y() || py >= r.y() + r.h()) {
            reveal(false);
            return;
        }
        // Pointer coordinates are the window's; the projection's are the viewport's own box.
        double localX = px - box.rect().x();
        double localY = py - box.rect().y();

        int best = -1;
        double bestD2 = REACH * REACH;
        double bestU = 0;
        double bestV = 0;
        for (int i = 0; i < points.length / 3; i++) {
            Lens.Point p = lens.project(points[i * 3], points[i * 3 + 1], points[i * 3 + 2]);
            if (p == null) {
                continue;
            }
            double sx = p.u() * box.rect().w();
            double sy = p.v() * box.rect().h();
            double d2 = (sx - localX) * (sx - localX) + (sy - localY) * (sy - localY);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = i;
                bestU = sx;
                bestV = sy;
            }
        }
        if (best < 0) {
            reveal(false);
            return;
        }
        show(best, points, bestU, bestV);
    }

    private void show(int index, double[] points, double sx, double sy) {
        // The world box is an internal frame; a reader should only ever see the domain.
        double t = index / (double) Math.max(1, points.length / 3 - 1);
        double x = domainLo + (domainHi - domainLo) * t;

        label.text(String.format(Locale.ROOT, "x  %s", trim(x)));
        value.text(String.format(Locale.ROOT, "%s  %s i", trim(points[index * 3 + 1]), trim(points[index * 3 + 2])));
        note.text("sample " + index);

        root.floatAt(Length.dp((float) (sx + OFFSET_X)), Length.dp((float) (sy + OFFSET_Y)));
        reveal(true);
    }

    /** Four decimals, and no trailing zeros: a probe quotes a sample, not a measurement. */
    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s.equals("-0") ? "0" : s;
    }
}
