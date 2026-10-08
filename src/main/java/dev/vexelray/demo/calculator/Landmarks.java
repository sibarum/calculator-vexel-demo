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
     * The drop-down for one of cott-engine's modesets, in the settings window: {@code setting.arithmetic},
     * {@code setting.limits}. Derived from the modeset's name, since the window offers whatever modesets the
     * engine has.
     */
    static String setting(Modeset modeset) {
        return "setting." + modeset.name().toLowerCase(Locale.ROOT);
    }

    private Landmarks() {
    }
}
