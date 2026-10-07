package dev.vexelray.demo.calculator;

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

    /** The button naming the current arithmetic; pressing it moves to the next. */
    static final String MODE = "button.mode";

    /** The button naming the current recursion limits; pressing it moves to the next. */
    static final String LIMITS = "button.limits";

    private Landmarks() {
    }
}
