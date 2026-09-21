package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.NodeLayout;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Modifier;

import java.util.Set;

/**
 * Pan and zoom on the viewport.
 *
 * <h2>There is no orbit any more, and that is the model's doing</h2>
 *
 * <p>A value is a point in a plane and the plot draws that plane square on. Both readings a viewer takes off
 * it — where a point stands, and the turn {@code arg(q + pi)} it stands at — are measured <em>in</em> the
 * plane, so tilting the camera foreshortens the only thing on screen. A hand that used to turn a box now
 * slides a sheet.
 *
 * <p>The throw went with it. Inertia was there to make a turned box feel like a turned object; a panned sheet
 * that keeps sliding after the hand stops is a picture that will not stay where it was put, which is the
 * complaint the fling floor existed to answer in the first place. {@code Motion.turn} and {@code Motion.fling}
 * are still there and still tested — the VIEW panel's presets and the spin reach the same camera — so what has
 * gone is the gesture, not the capability.
 *
 * <h2>One of the two goes through the framework; the other cannot</h2>
 *
 * <p>Dragging is {@code Gui.onDrag}, which gives pointer capture for free — the gesture continues off the node
 * and off the window, which is the difference between a pan you can carry to the edge of the screen and one
 * that stops at the panel edge. Declaring {@code CursorShape.GRAB} is what makes the pointer say so, and the
 * framework's precedence rule then does the rest: a grab in progress outranks a grabbable thing, which
 * outranks a clickable one.
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
 * <h2>The modifier is still read where a second gesture would need it</h2>
 *
 * <p>{@code DragEvent} carries no modifiers, so a drag that meant two things had to ask {@code Gui.modifiers()}
 * — the coalesced state the dispatcher maintains — rather than latch a flag on key-down. A latched flag is how
 * a modifier gets stuck: release the key while the pointer is captured outside the window and the key-up is
 * delivered somewhere else, leaving the application panning forever. There is one gesture now, so nothing is
 * asked; the note stays because the next second gesture will want it and this is where it was learned.
 */
final class Gestures {

    private Gestures() {
    }

    /**
     * How much of the pointer's travel a drag spends — half of it, as of now.
     *
     * <p><b>One number for both drags</b>, because they are one gesture with two targets: a hand that panned
     * at one rate and slid the turn axis at another would be a hand that behaves differently depending on a
     * key, which is the thing a modifier should never do to the <em>feel</em> of a gesture. It is here rather
     * than in {@link Motion} or in the window arithmetic for the same reason: this is a property of the hand,
     * and both of the things it steers already know how to move by a given amount.
     *
     * <p>Halved rather than damped: the pan is the same pan it was, at half the rate, so nothing downstream
     * of it has to know. What it costs is reach — the pointer runs out of desk twice as fast, which is the
     * argument for holding the pointer for the length of a drag rather than letting the window's edge end it.
     */
    private static final double HAND = 0.5;

    /**
     * What the hand can do to the turn axis, as opposed to what it can do to the camera.
     *
     * <p>Two gestures on one modifier, and the modifier is the whole of why this is a second interface rather
     * than two more calls on {@link Motion}: the camera and the window are different things being moved —
     * one is where the eye stands, the other is which arc of the circle the box is drawn from — and a hand
     * that could reach the second without asking would be a hand that changes the picture's meaning by
     * accident. Ctrl says which.
     */
    interface Axis {

        /** Magnify the turn axis about its own centre, in wheel notches. */
        void magnify(double notches);

        /** Move the centre of the window, as a fraction of the viewport's height. */
        void slide(double fraction);
    }

    /**
     * Wire the viewport.
     *
     * @param viewport the node carrying the marched image; drags on it and wheels over it steer the camera
     * @param axis     the turn axis's own window, which the same two gestures steer with Ctrl held
     */
    static void install(Gui gui, Node viewport, Motion motion, Axis axis) {
        gui.cursor(viewport, CursorShape.GRAB);

        // The pointer is held for the length of a drag, which is the declaration this viewport has always
        // qualified for and never made. Both of its drags are displacements -- a pan and a slide of the turn
        // window, neither of which ever asks where the pointer IS -- and that is exactly the promise
        // dragLocksPointer asks for: "a handler that differences x() instead of reading its dx() reads
        // nothing at all once locked". Both read dx/dy, and did before this line.
        //
        // It matters more at HAND than it did at full travel: half the rate is twice the desk, and the
        // window's edge was already the end of a gesture that has no natural one. Locked, the cursor stops
        // travelling and the motion keeps arriving.
        //
        // The other side of the seam is the framework's, as of today: Shell owns a PointerLock and installs
        // the sink the dispatcher has been firing into nothing. Nothing is needed here to tune it -- it locks
        // in RAW mode, which does not warp, so the cursor comes back exactly where it went, and it waits for
        // two pixels of real device travel, so a click on the viewport never blinks the cursor. If this
        // machine turns out to have no raw-input plumbing, shell.pointerLock().mode(RECENTER) is the knob and
        // it belongs at the wiring, not here.
        gui.dragLocksPointer(viewport, true);

        // Every drag is a pan, with or without SHIFT -- SHIFT+drag meant pan and still does, so a user who
        // learned it does not find it dead. START and END carry no delta and there is no state to keep
        // between them. CTRL is the one that means something else: it slides the turn window instead, which
        // is the gesture that takes a reader from the zero rule to the omega one without moving the eye.
        gui.onDrag(viewport, e -> {
            if (e.phase() != DragEvent.Phase.MOVE) {
                return;
            }
            if (held(gui, Modifier.CONTROL)) {
                axis.slide(HAND * e.dy() / Math.max(1, height(viewport)));
            } else {
                motion.pan(HAND * e.dx(), HAND * e.dy());
            }
        });

        gui.bus().subscribe(InputTopics.INPUT, event -> {
            if (event instanceof InputEvent.Scrolled s && over(viewport, s.x(), s.y())) {
                if (held(gui, Modifier.CONTROL)) {
                    axis.magnify(s.yOffset());
                } else {
                    motion.zoom(s.yOffset());
                }
            }
        });
    }

    /**
     * Whether a modifier is down right now.
     *
     * <p>Asked of the {@code Gui} rather than read off the event, because neither a {@code DragEvent} nor a
     * {@code Scrolled} carries modifiers — the note above about a stuck modifier is the risk that comes with
     * it, and it is bounded here: the worst a stuck Ctrl can do is magnify an axis a user can put back with
     * one control.
     */
    private static boolean held(Gui gui, Modifier modifier) {
        Set<Modifier> now = gui.modifiers().value();
        return now != null && now.contains(modifier);
    }

    private static double height(Node node) {
        NodeLayout box = node.layout();
        return box == null ? 1 : box.rect().h();
    }

    // The throw lived here: a smoothed pointer velocity at the moment of release, so a flung orbit left the
    // hand at exactly the speed the hand had. It measured the input rather than the camera, which is why it
    // was here and not in Motion, and that is the part worth remembering if inertia comes back for the pan.
    // Motion.fling is still there and still tested; nothing calls it from a gesture.

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
