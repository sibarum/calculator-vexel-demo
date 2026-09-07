package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.NodeLayout;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Modifier;

/**
 * Orbit, pan and zoom on the viewport.
 *
 * <h2>Two of the three go through the framework; one cannot</h2>
 *
 * <p>Dragging is {@code Gui.onDrag}, which gives pointer capture for free — the gesture continues off the node
 * and off the window, which is the difference between an orbit you can throw and one that stops at the panel
 * edge. Declaring {@code CursorShape.GRAB} is what makes the pointer say so, and the framework's precedence
 * rule then does the rest: a grab in progress outranks a grabbable thing, which outranks a clickable one.
 *
 * <p><b>The wheel has no such seam.</b> {@code InputDispatcher} routes a {@code Scrolled} edge to the nearest
 * <em>scrollable</em> ancestor and drops it otherwise, so a notch over a canvas that does not scroll goes
 * nowhere at all and reports nothing. The way in is to subscribe to the raw input topic and hit-test the node's
 * own published rect — the same coordinates the dispatcher tests, so nothing is converted. See
 * {@code docs/framework-notes.md} FN-1, which asks for {@code Gui.onWheel}.
 *
 * <h2>Why inline delivery is safe here</h2>
 *
 * <p>The subscription is inline, so the handler runs on the thread that published — the GUI thread, since the
 * bridge pumps input from the frame loop. That is fine because the handler does one thing: read a rect from the
 * layout read-model and write two volatile fields on {@link March}. It touches no GPU resource; the march
 * itself still happens later in the same frame, from the loop. And <b>no {@code wakeForInput} is owed</b>,
 * because a real wheel notch arrived as an OS event, which is one of the three things that already wakes a
 * parked loop.
 *
 * <h2>Shift is read from the event, not remembered</h2>
 *
 * <p>{@code DragEvent} carries no modifiers, so pan-versus-orbit is decided by {@code Gui.modifiers()} — the
 * coalesced state the dispatcher maintains — rather than by latching a flag on key-down. A latched flag is how
 * a modifier gets stuck: release the key while the pointer is captured outside the window and the key-up is
 * delivered somewhere else, leaving the application panning forever.
 */
final class Gestures {

    /** Radians of orbit per pixel dragged. A full turn is about a third of the window's width. */
    private static final double ORBIT_PER_PX = 0.008;

    /**
     * How much of the release speed is the newest sample.
     *
     * <p>A throw is judged from the last instant of the drag, and the last instant is one pointer event — which
     * on a hand that is slowing down is noise. Smoothing over roughly the last three events is enough to be
     * stable and short enough that a deliberate stop before releasing still reads as a stop.
     */
    private static final double SMOOTHING = 0.4;

    /**
     * A gap this long between the last motion and the release means the hand stopped first.
     *
     * <p>Holding still for a moment and then letting go is how a user says "leave it exactly there", and it is
     * the one gesture an inertia model must not argue with.
     */
    private static final long STILL_NANOS = 70_000_000L;

    private Gestures() {
    }

    /**
     * Wire the viewport.
     *
     * @param viewport the node carrying the marched image; drags on it and wheels over it steer the camera
     */
    static void install(Gui gui, Node viewport, Motion motion) {
        gui.cursor(viewport, CursorShape.GRAB);

        // The drag's own state. Confined to this closure and touched only from the pointer's thread, which is
        // what lets it be plain fields rather than anything synchronized.
        Throw thrown = new Throw();

        gui.onDrag(viewport, e -> {
            switch (e.phase()) {
                case START -> thrown.begin();
                case MOVE -> {
                    if (gui.modifiers().value().contains(Modifier.SHIFT)) {
                        motion.pan(e.dx(), e.dy());
                        // A pan is not thrown, and a drag that ends in a pan must not fling the orbit it was
                        // doing beforehand. Forgetting the speed is how that is said.
                        thrown.begin();
                    } else {
                        // Dragging right turns the plot to the right, which means the camera goes the other
                        // way. Getting this backwards is the single most common way an orbit feels wrong, and
                        // it feels wrong immediately rather than subtly.
                        double dYaw = -e.dx() * ORBIT_PER_PX;
                        double dPitch = e.dy() * ORBIT_PER_PX;
                        motion.turn(dYaw, dPitch);
                        thrown.moved(dYaw, dPitch);
                    }
                }
                case END -> thrown.release(motion);
            }
        });

        gui.bus().subscribe(InputTopics.INPUT, event -> {
            if (event instanceof InputEvent.Scrolled s && over(viewport, s.x(), s.y())) {
                motion.zoom(s.yOffset());
            }
        });
    }

    /**
     * How fast the orbit was going when the hand let go, in radians a second.
     *
     * <p>Measured here rather than in {@link Motion} because a velocity is a fact about the input: it is the
     * pointer's, in the pointer's units, sampled at the pointer's rate. {@code DragEvent} carries a delta and
     * no timestamp, so the clock is the wall clock — and that is right, since what is being measured is a hand,
     * not logical time.
     */
    private static final class Throw {

        private double yawRate;
        private double pitchRate;
        private long last;

        void begin() {
            yawRate = 0;
            pitchRate = 0;
            last = 0;
        }

        void moved(double dYaw, double dPitch) {
            long now = System.nanoTime();
            long previous = last;
            last = now;
            if (previous == 0) {
                return;      // the first move has no interval to divide by
            }
            double seconds = (now - previous) / 1e9;
            if (seconds <= 0) {
                return;      // two events inside one clock tick; the next one will carry both
            }
            yawRate += SMOOTHING * (dYaw / seconds - yawRate);
            pitchRate += SMOOTHING * (dPitch / seconds - pitchRate);
        }

        void release(Motion motion) {
            boolean still = last == 0 || System.nanoTime() - last > STILL_NANOS;
            if (!still) {
                motion.fling(yawRate, pitchRate);
            }
            begin();
        }
    }

    /**
     * Whether a point is inside the viewport as it is currently drawn.
     *
     * <p>{@code visibleRect} rather than {@code rect}, so a notch over a panel that happens to overlap the
     * viewport's box does not also zoom the plot underneath it. This is the hand-rolled half of FN-1: the
     * dispatcher would have done the same walk, and every application that wants a wheel writes it again.
     */
    private static boolean over(Node node, int x, int y) {
        NodeLayout box = node.layout();
        if (box == null) {
            return false;
        }
        var r = box.visibleRect();
        return x >= r.x() && x < r.x() + r.w() && y >= r.y() && y < r.y() + r.h();
    }
}
