package dev.vexelray.demo.calculator;

/**
 * Every name an automation script may write down, in one place.
 *
 * <p><b>A ref is minted per run; a landmark still means something tomorrow.</b> That is the whole distinction
 * (vexelray-gui/docs/automation.md §5), and it is why these are constants rather than string literals scattered
 * through the view: a landmark is a published contract, so renaming one breaks a script somebody else wrote, and
 * the compiler should be the thing that notices.
 *
 * <p>Several of these carry <em>state in their accessible name</em>, deliberately. {@code await <landmark> <text>}
 * is how a driver waits for the application to be ready, and it needs no application-specific hook because
 * readiness is declared where the read-model can already see it. So {@link #MODE}'s name is the detected mode
 * and {@link #READOUT}'s name is the camera numbers — a script asserts against them directly rather than against
 * a screenshot.
 */
final class Landmarks {

    /** The expression field. */
    static final String EXPR = "expr";

    /** The AUTO badge. <b>Its name is the detected mode</b>, so {@code await mode LINE} works. */
    static final String MODE = "mode";

    /** The subtitle under the expression: what kind of plot this is. */
    static final String STATUS = "status";

    /** The error line. Absent from the tree when there is nothing wrong, so {@code find error} says so. */
    static final String ERROR = "error";

    /** The node carrying the marched image. Orbit, pan and zoom are aimed here. */
    static final String VIEWPORT = "viewport";

    /** The az / el / zoom / samples strip. <b>Its name carries the numbers.</b> */
    static final String READOUT = "readout";

    /** The probe bubble, when one is showing. */
    static final String PROBE = "probe";

    /** The open panel, whichever it is. Its name is the panel's title. */
    static final String PANEL = "panel";

    /** A rail button, by the panel it opens: {@code rail.layers}, {@code rail.view}, … */
    static String rail(String key) {
        return "rail." + key;
    }

    private Landmarks() {
    }
}
