package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
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
 */
final class Probe {

    /** How near the pointer has to be, in pixels, before a sample is worth pointing at. */
    private static final double REACH = 44;

    /** Where the bubble sits relative to the sample, so the pointer is never on top of the text. */
    private static final double OFFSET_X = 14;
    private static final double OFFSET_Y = -10;

    private final Gui gui;
    private final Node root;
    private final Node label;
    private final Node value;
    private final Node note;

    /** The curve in world space, as the last geometry build left it. Read on the pointer thread. */
    private volatile double[] curve = new double[0];

    /** The domain those samples span, for reporting x as the user's number rather than the box's. */
    private volatile double domainLo = -6;
    private volatile double domainHi = 6;

    Probe(Gui gui) {
        this.gui = gui;
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
                .visible(false);
        gui.landmark(Landmarks.PROBE, root);
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
            root.visible(false);
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
            root.visible(false);
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
        root.visible(true);
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
