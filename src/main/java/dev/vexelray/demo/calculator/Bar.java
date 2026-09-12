package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.TextField;

import java.util.ArrayList;
import java.util.List;

/**
 * The top-left cluster: {@code f =}, the expression, what kind of plot it is, and anything wrong with it.
 *
 * <h2>Everything here is derived, nothing is stored</h2>
 *
 * <p>The mode badge, the subtitle and the error line are three readings of one committed expression. None of
 * them is a field: {@link #show} takes a {@code Reading} and writes all four, so they cannot disagree with each
 * other or with the picture. That matters more than it looks — a badge saying {@code LINE} beside a surface is
 * not a cosmetic bug, it is the application lying about what is on the screen.
 *
 * <h2>Why the error line is removed rather than emptied</h2>
 *
 * <p>An empty text node still holds its line's height (that is deliberate in the framework — a value column
 * waiting for a number should not make the row jump when it arrives). Here the opposite is wanted: the subtitle
 * should sit directly under the field when nothing is wrong. So the error is {@code visible(false)}, which takes
 * it out of layout entirely while keeping its identity, its landmark and its handlers — the framework's own
 * idiom for showing one of several things, and the reason not to reach for remove-and-rebuild.
 */
final class Bar {

    /** The prototype's expression field is 238px wide. */
    private static final Length FIELD_WIDTH = Length.dp(238);

    /**
     * How many lines of a derivation are shown.
     *
     * <p>A cap rather than a scroller: this sits over the plot, and a derivation long enough to need scrolling
     * would cover the thing it is explaining. What does not fit is <em>counted</em> on the last line rather
     * than dropped silently, because a chain that appears to end early is a chain that appears to be wrong.
     */
    private static final int DERIVATION_LINES = 8;

    private final Gui gui;
    private final Node root;
    private final TextField field;
    private final Node mode;
    private final Node subtitle;
    private final Node error;
    private final Node crop;
    private final Node derivation;
    private final List<Node> derivationLines;

    Bar(Gui gui) {
        this.gui = gui;

        field = new TextField(gui, Algebra.DEFAULT_EXPRESSION);
        field.node()
                .width(FIELD_WIDTH)
                .font(Type.MONO)
                .textSize(Type.EXPRESSION)
                .textColor(gui.theme().color(Role.INK))
                // A TextField ships as a sunken, bordered, rounded well, which is right for a form and wrong
                // here: the prototype's expression sits on the plot with nothing but a rule under it. Undone
                // rather than configured, because the widget offers no "bare" mode -- and undoing it is three
                // ordinary prop writes on a handle the framework hands out for exactly this.
                .background(gui.theme().color(Role.NONE))
                .border(Length.dp(0), gui.theme().color(Role.NONE))
                .corner(Length.dp(0));
        gui.landmark(Landmarks.EXPR, field.node());

        mode = badge();
        subtitle = gui.text("").font(Type.MONO).textSize(Type.READOUT)
                .textColor(gui.theme().color(Look.QUIET));
        gui.landmark(Landmarks.STATUS, subtitle);

        error = gui.text("")
                .font(Type.MONO).textSize(Type.SMALL)
                .textColor(gui.theme().color(Look.ACCENT_BRIGHT))
                .background(gui.theme().color(Look.ACCENT_SURFACE))
                .padding(Length.dp(4), Length.dp(8))
                .visible(false);
        gui.landmark(Landmarks.ERROR, error);

        crop = cropNotice();

        // A fixed pool of single-line nodes rather than a rebuilt column: a text node holds one line, and
        // rebuilding the tree on every keystroke would throw away layout and landmarks to say the same thing.
        // Lines not in use are visible(false), which takes them out of layout entirely -- the same idiom the
        // error line uses, and for the same reason.
        derivationLines = new ArrayList<>(DERIVATION_LINES);
        for (int i = 0; i < DERIVATION_LINES; i++) {
            derivationLines.add(gui.text("")
                    .font(Type.MONO).textSize(Type.BADGE)
                    .textColor(gui.theme().color(Role.DIM))
                    .visible(false));
        }
        derivation = gui.column().width(Length.AUTO).height(Length.AUTO).gap(Type.TIGHT)
                .children(derivationLines.toArray(new Node[0]))
                .visible(false);
        gui.landmark(Landmarks.DERIVATION, derivation);

        Node prompt = gui.text("f =").font(Type.MONO).textSize(Type.SMALL)
                .textColor(gui.theme().color(Look.QUIET));

        // The underline is its own node rather than a border, because Node.border is all four edges and the
        // prototype wants one. A 1px box under the field is the smaller of the two workarounds -- the other
        // being a bordered box with three of its edges painted the background colour, which lies to anything
        // reading the tree. See framework-notes.md FN-17.
        Node rule = gui.box().width(Length.FILL).height(Type.RULE)
                .background(gui.theme().color(Look.LINE));
        Node underlined = gui.column().width(Length.AUTO).children(field.node(), rule);

        Node entry = gui.row().alignItems(AlignItems.CENTER).gap(Type.GAP)
                .children(prompt, underlined, mode);

        root = gui.column()
                .width(Length.AUTO).height(Length.AUTO)
                .gap(Type.GAP)
                .children(entry, crop, error, subtitle, derivation);
    }

    /**
     * The {@code AUTO LINE} pill.
     *
     * <p>Two runs in one row rather than one string, because they are two different colours and the framework's
     * text node carries one — {@code Span}s could do it in a single node, and a two-node row is the simpler
     * thing that does not need the offsets to stay in step with the text.
     */
    private Node badge() {
        Node auto = gui.text("AUTO").font(Type.MONO).textSize(Type.BADGE)
                .textColor(gui.theme().color(Role.FAINT));
        Node which = gui.text("").font(Type.MONO).textSize(Type.BADGE)
                .textColor(gui.theme().color(Look.ACCENT_LIGHT));
        Node pill = gui.row().alignItems(AlignItems.CENTER).gap(Type.TIGHT)
                .padding(Length.dp(3), Length.dp(7))
                .corner(Length.dp(4))
                .border(Type.RULE, gui.theme().color(Look.ACCENT_LINE))
                .children(auto, which);
        gui.landmark(Landmarks.MODE, which);
        return pill;
    }

    /** {@code CROP  drag the six axis handles} — shown only while crop mode is on. */
    private Node cropNotice() {
        Node label = gui.text("CROP").font(Type.MONO).textSize(Type.BADGE)
                .textColor(gui.theme().color(Look.ACCENT_BRIGHT));
        Node hint = gui.text("drag the six axis handles").font(Type.MONO).textSize(Type.BADGE)
                .textColor(gui.theme().color(Role.DIM));
        return gui.row().alignItems(AlignItems.CENTER).gap(Type.GAP)
                .padding(Length.dp(4), Length.dp(9))
                .corner(Length.dp(5))
                .border(Type.RULE, gui.theme().color(Look.ACCENT_LINE))
                .background(gui.theme().color(Look.ACCENT_SURFACE))
                .children(label, hint)
                .visible(false);
    }

    /** Write every derived line at once, so no two of them can describe different expressions. */
    void show(Algebra.Reading reading) {
        mode(reading);
        derivation(reading);
    }

    private void mode(Algebra.Reading reading) {
        gui.landmarkNode(Landmarks.MODE).ifPresent(n -> n.text(reading.mode().name()));
        subtitle.text(reading.subtitle());
        boolean wrong = reading.refusal() != null;
        error.visible(wrong);
        if (wrong) {
            error.text(reading.refusal());
        }
    }

    /**
     * The chain of equalities that produced the answer, one rewrite a line.
     *
     * <p>Shown for a single value and not for a curve, because a curve has a derivation per sample and there
     * is no such thing as "the" one. The block leaves the tree when there is nothing to show, so
     * {@code find derivation} is how a driver tells the two modes apart.
     *
     * <p>Each line carries the rule that licensed it and that rule's status. That is the engine's founding
     * commitment surfacing in the view: an answer resting on a CHOSEN rule is not the same kind of answer as
     * one resting on a proven one, and the difference is per-answer rather than something to look up.
     */
    private void derivation(Algebra.Reading reading) {
        List<String> lines = reading.derivation();
        boolean show = reading.mode() == Algebra.Mode.POINT && !lines.isEmpty();
        derivation.visible(show);
        if (!show) {
            derivationLines.forEach(n -> n.visible(false));
            return;
        }
        int shown = Math.min(lines.size(), DERIVATION_LINES);
        // The last slot says what it is hiding rather than letting the chain look as though it stopped there.
        boolean elided = lines.size() > DERIVATION_LINES;
        for (int i = 0; i < DERIVATION_LINES; i++) {
            Node line = derivationLines.get(i);
            if (i >= shown) {
                line.visible(false);
                continue;
            }
            boolean last = elided && i == DERIVATION_LINES - 1;
            line.text(last ? "… " + (lines.size() - DERIVATION_LINES + 1) + " more steps" : lines.get(i));
            line.visible(true);
        }
    }

    /** Crop mode is a view state rather than a reading, so it is set on its own. */
    void cropping(boolean on) {
        crop.visible(on);
    }

    Node node() {
        return root;
    }

    TextField field() {
        return field;
    }
}
