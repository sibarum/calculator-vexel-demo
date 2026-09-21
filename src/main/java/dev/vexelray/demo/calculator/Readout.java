package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.style.Role;

import java.util.Locale;

/**
 * The camera readout in the bottom-right corner: {@code az 38  el 26  zoom 1.0  46²}.
 *
 * <h2>It is hit-inert, and that is the whole of its interaction design</h2>
 *
 * <p>The strip sits over the plot in the corner a user drags towards. If it were an ordinary node it would be
 * the pointer's target there, and an orbit begun in that corner would do nothing — a dead patch of canvas with
 * no visible cause. {@code hitInert} makes hit-testing pass straight through it, so it is drawn and never
 * pointed at, which is exactly what the prototype's {@code pointer-events: none} says.
 *
 * <h2>The numbers are the accessible name, deliberately</h2>
 *
 * <p>An automation script asserting a camera state should read the numbers rather than photograph them, and the
 * framework already publishes every node's name into {@code SemanticSnapshot}. So the whole strip's text is
 * kept on one landmark ({@link Landmarks#READOUT}), and {@code await readout az 90} is how a driver waits for an
 * orbit to finish. That is the "declare readiness where the read-model can see it" rule from automation.md §5,
 * and it costs nothing here because the text exists anyway.
 */
final class Readout {

    private final Gui gui;
    private final Node root;
    private final Node text;

    Readout(Gui gui) {
        this.gui = gui;
        text = gui.text("")
                .font(Type.MONO)
                .textSize(Type.READOUT)
                .textColor(gui.theme().color(Role.FAINT));
        root = gui.row()
                .width(Length.AUTO).height(Length.AUTO)
                .alignItems(AlignItems.CENTER)
                .children(text)
                .hitInert(true);
        gui.landmark(Landmarks.READOUT, text);
    }

    /**
     * Write the strip.
     *
     * <p>Degrees rather than radians, and rounded to whole ones: this is a readout for a person steering a
     * camera by hand, and the third decimal of an azimuth is noise that changes every frame of an orbit — which
     * would also make the landmark's name useless to wait on.
     */
    void show(double yawRadians, double pitchRadians, double zoom, int samples, Turns.Window window) {
        // The turn axis's own magnification, and only when there is one. It is not the camera's zoom and must
        // not be read as it: the camera moves the eye, and this narrows the arc of the circle the box is
        // showing, which is the only one of the two that can reach the fineprint. A reader who has magnified
        // the axis and then orbited away has no other way to find out how deep they are.
        String turn = window.magnification() > 1.0001
                ? String.format(Locale.ROOT, "    turn ×%s", magnification(window.magnification()))
                : "";
        text.text(String.format(Locale.ROOT, "az %.0f    el %.0f    zoom %.2f    %d²%s",
                normalise(Math.toDegrees(yawRadians)), Math.toDegrees(pitchRadians), zoom, samples, turn));
    }

    /** A magnification as a person would say it: {@code 40} up close, {@code 1e6} once it stops being a number. */
    private static String magnification(double times) {
        if (times >= 1e5) {
            return String.format(Locale.ROOT, "1e%.0f", Math.log10(times));
        }
        return times >= 100 ? String.format(Locale.ROOT, "%.0f", times)
                : String.format(Locale.ROOT, "%.1f", times);
    }

    /** Azimuth reads as a compass bearing, so it wraps into {@code [0, 360)} rather than running to -700. */
    private static double normalise(double degrees) {
        double d = degrees % 360;
        return d < 0 ? d + 360 : d;
    }

    Node node() {
        return root;
    }
}
