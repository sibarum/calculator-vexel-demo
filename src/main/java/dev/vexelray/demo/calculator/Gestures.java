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

    private Gestures() {
    }

    /**
     * Wire the viewport.
     *
     * @param viewport the node carrying the marched image; drags on it and wheels over it steer the camera
     */
    static void install(Gui gui, Node viewport, March march) {
        gui.cursor(viewport, CursorShape.GRAB);

        gui.onDrag(viewport, e -> {
            if (e.phase() != DragEvent.Phase.MOVE) {
                return;
            }
            if (gui.modifiers().value().contains(Modifier.SHIFT)) {
                march.pan(e.dx(), e.dy());
            } else {
                // Dragging right turns the plot to the right, which means the camera goes the other way. Getting
                // this backwards is the single most common way an orbit feels wrong, and it feels wrong
                // immediately rather than subtly.
                march.turn(-e.dx() * ORBIT_PER_PX, e.dy() * ORBIT_PER_PX);
            }
        });

        gui.bus().subscribe(InputTopics.INPUT, event -> {
            if (event instanceof InputEvent.Scrolled s && over(viewport, s.x(), s.y())) {
                march.zoom(s.yOffset());
            }
        });
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
