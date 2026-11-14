package dev.vexelray.demo.calculator;

import sibarum.atchung.Topic;
import sibarum.cott.calculator.Modeset;

/**
 * Every message this application's parts send each other, each record beside its topic.
 *
 * <p>Two directions. The {@link Screen} asks {@link Evaluate}, on the job lane, to enter a line or change a mode, because
 * an evaluation can take as long as the limits allow and the view's time is the frame's; the job answers, and the
 * screen draws the {@link Model}'s new document. And a chord, a claim or a menu item says what the person asked for
 * as an {@link Act}, which the screen hears in the frame and carries out, since since 0.2 a declared chord sends a
 * message rather than running code (vexelray-framework's {@code docs/threading.md}, T7.5).
 */
final class Messages {

    private Messages() {
    }

    /** Evaluate this line and put it on the tape. */
    record Enter(String line) {
        static final Topic<Enter> TOPIC = Topic.edge(Enter.class);
    }

    /** What became of an {@link Enter}: {@code taken}, or refused with the reason in the document's error. */
    record Entered(String line, boolean taken) {
        static final Topic<Entered> TOPIC = Topic.edge(Entered.class);
    }

    /** Run the next lines in the {@code index}th mode of {@code modeset}. */
    record SetMode(Modeset modeset, int index) {
        static final Topic<SetMode> TOPIC = Topic.edge(SetMode.class);
    }

    /** A {@link SetMode} done (or found already in force). */
    record ModeSet(Modeset modeset, int index) {
        static final Topic<ModeSet> TOPIC = Topic.edge(ModeSet.class);
    }

    /**
     * Something the person asked for by a chord or a menu: {@code action}, in the window keyed {@code window}, with
     * {@code n} where the action counts (which line Ctrl+number inserts).
     */
    record Act(Action action, String window, int n) {
        static final Topic<Act> TOPIC = Topic.edge(Act.class);

        Act(Action action, String window) {
            this(action, window, 0);
        }
    }

    enum Action {
        /** Up and Down in the entry. */
        OLDER, NEWER,
        /** Ctrl+number and Ctrl+Shift+number in the entry. */
        INSERT_VALUE, INSERT_TYPED,
        /** The gear, pressed from the keyboard. */
        SETTINGS,
        /** A window's own zoom. */
        ZOOM_IN, ZOOM_OUT, ZOOM_RESET,
        /** The plot explorer's context menu. */
        EXPLORE_RESET, EXPLORE_FIT, EXPLORE_ZOOM_IN, EXPLORE_ZOOM_OUT, EXPLORE_CLEAR_PINS
    }
}
