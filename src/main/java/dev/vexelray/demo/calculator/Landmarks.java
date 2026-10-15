package dev.vexelray.demo.calculator;

import sibarum.cott.calculator.Modeset;

import java.util.Locale;

/**
 * Every name an automation script may write down, in one place. A landmark is a published contract, so these
 * are constants rather than literals scattered through the view.
 */
final class Landmarks {

    /** The column of past lines and their answers. */
    static final String TAPE = "tape";

    /** The line being typed. Enter evaluates it. */
    static final String ENTRY = "entry";

    /** Why the last line was refused; empty when it was not. */
    static final String ERROR = "error";

    /** The gear beside the entry; pressing it opens the settings window. */
    static final String SETTINGS = "button.settings";

    /**
     * The button under the plot on tape line {@code line}, counting the first as 1, that pops it out into the plot
     * explorer: {@code plot.3.explore}.
     */
    static String explore(int line) {
        return "plot." + line + ".explore";
    }

    // ---- the plot explorer's window, "plot" ----

    /** The plot itself: drag, wheel and keys act here. */
    static final String EXPLORER_PLOT = "explorer.plot";

    /** The status line's reading of the sample under the pointer or stepped to. */
    static final String EXPLORER_READING = "explorer.reading";

    /** How many samples are on show and how far apart, or {@code sampling n} while more are being taken. */
    static final String EXPLORER_SAMPLES = "explorer.samples";

    static final String EXPLORER_ZOOM_IN = "explorer.zoom_in";
    static final String EXPLORER_ZOOM_OUT = "explorer.zoom_out";
    static final String EXPLORER_FIT = "explorer.fit";
    static final String EXPLORER_RESET = "explorer.reset";
    static final String EXPLORER_HELP = "explorer.help";

    /** The chips: what is drawn besides the curve, and whether a unit is as long up as across. */
    static final String EXPLORER_GRID = "explorer.grid";
    static final String EXPLORER_POINTS = "explorer.points";
    static final String EXPLORER_PLACES = "explorer.places";
    static final String EXPLORER_EQUAL = "explorer.equal";

    /** How closely the curve is sampled. */
    static final String EXPLORER_DENSITY = "explorer.density";

    /**
     * The drop-down for one of cott-engine's modesets, in the settings window: {@code setting.number_type},
     * {@code setting.limits}. Derived from the modeset's name, since the window offers whatever modesets the
     * engine has.
     */
    static String setting(Modeset modeset) {
        return "setting." + modeset.name().toLowerCase(Locale.ROOT);
    }

    private Landmarks() {
    }
}
