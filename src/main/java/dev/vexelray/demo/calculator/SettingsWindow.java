package dev.vexelray.demo.calculator;

import dev.vexelray.framework.shell.NamedWindow;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Select;
import sibarum.cott.algebra.NumberType;
import sibarum.cott.calculator.Mode;
import sibarum.cott.calculator.Modeset;

import java.util.EnumMap;
import java.util.Map;

/**
 * The settings window: one drop-down per cott-engine {@link Modeset}, each with a few lines saying what it
 * changes.
 *
 * <p><b>The controls are not written out one by one.</b> cott-engine describes its settings as modesets, each
 * with its modes, and says a front end can offer every one of them without knowing any; so this walks
 * {@link Modeset#values()} and a modeset the engine adds later turns up here with no change but a paragraph in
 * {@link #about}. A setting that is the application's own rather than the engine's would be a section added
 * beside them, reading its value from {@link Doc} the same way.
 *
 * <p>A named {@link Window}, so asking for it twice raises the one already open, and its tree outlives the
 * window: closing and reopening shows the same controls rather than building new ones. It stands as a satellite of
 * the main window, above it and minimized with it, because it belongs to it.
 *
 * <p><b>The window and its tree are the framework's.</b> This class builds into the {@code Gui} it is handed and
 * places the title bar it is handed; the bus of its own, the look, the mark, the placement and zoom remembered under
 * {@link #KEY} and the controls in the bar are all the framework's, which is why nothing here takes the
 * {@code GuiApp} (framework notes, FN-1 and FN-2).
 */
final class SettingsWindow implements AutoCloseable {

    /** The window, as {@link Recipes} declares it: its name is where its placement is remembered. */
    static final NamedWindow DECLARED = NamedWindow.of("settings", "Settings", 500, 640).satellite();

    private final Gui gui;
    private final Map<Modeset, Select<Mode>> selects = new EnumMap<>(Modeset.class);
    private final Map<Modeset, Node> details = new EnumMap<>(Modeset.class);

    /**
     * The tree, which needs no window: built beside the main window's, and shown in its own window when the gear
     * asks. Whatever the two windows have to agree about goes through the {@link Model}.
     */
    SettingsWindow(Window window, Model model) {
        this.gui = window.gui();

        Node body = gui.column()
                .width(Length.FILL).height(Length.grow(1f))
                .gap(Type.EDGE)
                .padding(Type.WIDE, Type.EDGE)
                .scroll(false, true)
                .children(prose("Each setting applies to the lines entered after it is changed. Lines already on "
                        + "the tape keep the answers they were given, and definitions are kept across every change."));

        for (Modeset modeset : Modeset.values()) {
            Select<Mode> select = new Select<Mode>(gui, SettingsWindow::label).options(modeset.modes());
            // Back to the main window's entry is the user's move, not this one's: several settings are often
            // changed together, so the window stays up until it is closed.
            select.onCommit(chosen -> {
                if (!chosen.isEmpty()) model.set(chosen.iterator().next());
            });
            gui.landmark(Landmarks.setting(modeset), select.node());
            selects.put(modeset, select);

            Node detail = gui.text("").font(Type.UI).textSize(Type.PROSE)
                    .textColor(gui.theme().color(Role.DIM));
            details.put(modeset, detail);

            body.append(gui.column().width(Length.FILL).height(Length.AUTO).gap(Type.TIGHT).children(
                    gui.text(modeset.label()).font(Type.UI).textSize(Type.SECTION)
                            .textColor(gui.theme().color(Role.INK)),
                    select.node(),
                    detail,
                    prose(about(modeset))));
        }

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(window.titleBar().node(), body);
    }

    /** The drop-downs' subscriptions. The tree is the framework's, and it closes it. */
    @Override
    public void close() {
        selects.values().forEach(Select::close);
    }

    /** Write everything derived from the document: which mode each drop-down shows, and what that mode means. */
    void show(Doc doc) {
        selects.forEach((modeset, select) -> {
            Mode mode = doc.mode(modeset);
            // Not while it is open: the user is moving through the options, and the document still holds the
            // value they opened it on.
            if (!select.isOpen()) select.show(mode);
            details.get(modeset).text(detail(mode));
        });
    }

    private Node prose(String text) {
        return gui.text(text).width(Length.FILL).font(Type.UI).textSize(Type.PROSE)
                .textColor(gui.theme().color(Role.FAINT));
    }

    /**
     * What a modeset is for, in a sentence or three. cott-engine names its modesets but does not describe them,
     * so the words are this application's; a modeset with none written yet shows its drop-down and nothing under.
     */
    private static String about(Modeset modeset) {
        return switch (modeset) {
            case NUMBER_TYPE -> "The numbers at the bottom of every value. A pair such as Q(1, 2) or C(3, 4) is "
                    + "a pair of these, and the algebra its constructor names says what + and * do with it. "
                    + "Definitions are kept as written, so a definition made under one number type is read "
                    + "afresh under another.";
            case SIZE_LIMIT -> "How large one of those numbers may be. An Integer over the limit is refused, "
                    + "since rounding it would change the pair it is in; a Decimal is rounded to the limit's "
                    + "digits, and the answer says that it was. IEEE 64-bit is a double whatever the limit.";
            case FORM -> "Which way a sum and a product nest when they meet: Q(1, 2) + C(3, 4) is a C of Q's, "
                    + "a sum of products, or a Q of C's, a product of sums. Both hold the same value and pay "
                    + "for division in different places.";
            case LIMITS -> "How far a recursion may go before it is stopped: the mediant descent behind cos and "
                    + "sin. Each bound is a count of steps rather than a time, so a line gives the same answer on "
                    + "every machine. Deeper limits give tighter answers and can take far longer; a line that "
                    + "needs more than the limits allow is refused rather than cut short.";
            default -> "";
        };
    }

    /** What is particular to the chosen mode, beyond its name. Empty where the name says it all. */
    private static String detail(Mode mode) {
        if (!(mode instanceof NumberType t)) return "";
        return switch (t) {
            case INTEGER -> "Exact: 1/2 is the pair Q(1, 2), never reduced. Decimals are refused, so write 1/2. "
                    + "cos, sin and e^x are dialed to a bracket.";
            case DECIMAL -> "Decimals like 0.5 are values, and a division can bring an expression down to one "
                    + "number. cos, sin and e^x are refused here.";
            case IEEE -> "Every number is a double: 1/0 is infinity and 0/0 is NaN. With e = e, cos and sin count "
                    + "in radians.";
        };
    }

    /**
     * A mode's name as the drop-down shows it. cott-engine's limits are named with {@code ≤}, which the atlas
     * font has no glyph for, so it is spelled out rather than drawn as an empty box.
     */
    private static String label(Mode mode) {
        return mode.label().replace("≤", "<=");
    }
}
