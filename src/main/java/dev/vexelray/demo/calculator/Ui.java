package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.ScrollLock;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.widget.Tooltip;
import dev.vexelray.gui.core.text.TextFaces;
import dev.vexelray.gui.typeset.Box;
import dev.vexelray.gui.typeset.FaceKeys;
import dev.vexelray.gui.typeset.Profile;
import dev.vexelray.gui.typeset.Recipes;
import dev.vexelray.gui.typeset.Typeset;
import dev.vexelray.gui.typeset.TypesetBlock;
import dev.vexelray.gui.core.input.InputTopics;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The tree: a tape of past lines filling the window, the reason the last line was refused under it, and an
 * entry with a settings gear beside it at the bottom. The settings themselves are in {@link SettingsWindow}.
 *
 * <p>The tape only grows, so {@link #show} appends the entries it has not drawn yet rather than rebuilding the
 * column; {@code shown} is a count of nodes rather than a value anything is derived from.
 *
 * <p>Putting past lines back in the entry is {@link Reuse}'s; this only tells it about each line it draws.
 */
final class Ui {

    /** The settings gear's side: about the entry's height, so the row does not grow for it. */
    private static final Length GEAR = Length.rem(1.75f);

    private final Gui gui;
    private final Node tape;
    private final Node error;
    private final Tooltip tips;
    private volatile Runnable settings = () -> { };
    private volatile Consumer<Doc.Entry> explore = e -> { };
    private final List<Plot> plots = new CopyOnWriteArrayList<>();
    private final Reuse reuse;
    private int shown;

    // The math typesetter a tape answer is written in, or null where the atlas it measures with is missing; then the
    // answer is its text, as before.
    private final Profile math = Profile.math();
    private final Typeset typeset;

    Ui(Gui gui, Model model, TitleBar titleBar, KronoGui krono) {
        this.gui = gui;
        this.typeset = typesetter(math);

        tape = gui.column()
                .width(Length.FILL).height(Length.grow(1f))
                .gap(Type.WIDE)
                .padding(Type.WIDE, Type.TAPE_EDGE)
                .scroll(false, true)
                .scrollLock(ScrollLock.BOTTOM);
        gui.landmark(Landmarks.TAPE, tape);

        error = gui.text("")
                .font(Type.UI)
                .textSize(Type.SMALL)
                .textColor(gui.theme().color(Role.DANGER));
        gui.landmark(Landmarks.ERROR, error);

        TextField entry = new TextField(gui);
        entry.node().width(Length.grow(1f)).font(Type.MONO).textSize(Type.LABEL);
        gui.landmark(Landmarks.ENTRY, entry.node());

        tips = new Tooltip(gui);
        reuse = new Reuse(gui, model, entry, tips, krono);
        // A refused line stays where it is, to be fixed; a taken one is cleared with replace rather than text,
        // so Ctrl+Z brings it back.
        entry.onSubmit(line -> {
            if (model.enter(line)) {
                reuse.submitted();
                entry.replace("");
                tape.scrollToEdge();
            }
        });

        Node gear = gear();
        gui.landmark(Landmarks.SETTINGS, gear);
        gui.shortcut(Key.COMMA, this::openSettings, Modifier.CONTROL);

        Node bottom = gui.column()
                .width(Length.FILL).height(Length.AUTO)
                .gap(Type.TIGHT)
                .padding(Type.WIDE, Type.EDGE)
                .background(gui.theme().color(Role.PANEL))
                .children(error,
                        gui.row().width(Length.FILL).gap(Type.GAP).alignItems(AlignItems.CENTER)
                                .children(entry.node(), gear));

        gui.root().direction(Direction.COLUMN)
                .background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), tape, bottom);
        gui.focus(entry.node());

        // The framework has no per-node pointer-move hook, so the plots read moves off the input bus and each
        // hit-tests its own box. Kept for the life of the window.
        gui.bus().subscribe(InputTopics.INPUT, e -> {
            if (e instanceof InputEvent.PointerMoved m) {
                for (Plot p : plots) p.pointer(m.x(), m.y());
            }
        });
    }

    /**
     * One line of the tape: what was typed, dim; the answer; its plot, if it has one; and its other readings, small.
     * {@code index} is its place on the tape, from 0.
     */
    private Node line(Doc.Entry e, int index) {
        Node input = chip(gui.text(e.input()).font(Type.MONO).textSize(Type.LABEL)
                .textColor(gui.theme().color(Role.DIM)));
        // Stretched, so long text wraps; the wash is therefore a row's width rather than the text's. Hugging the
        // text would need a width capped at the row's, which the layout cannot say.
        Node node = gui.column().width(Length.FILL).height(Length.AUTO).gap(Type.TIGHT).children(input);
        Node value = null;
        // A plotted line's answer is the expression back again; worth showing only when substitution changed it.
        boolean echo = e.graph() != null && e.answer().replace(" ", "").equals(e.input().replace(" ", ""));
        if (!echo) {
            Node answer = chip(e.value() != null && typeset != null ? typeset(e) : gui.text(e.answer())
                    .font(Type.MONO).textSize(Type.HEADING).textColor(gui.theme().color(Role.INK)));
            node.append(answer);
            // A value reads "= 1/3"; anything else (a refusal, an expression left standing) is not a value to reuse.
            if (e.answer().startsWith("= ")) value = answer;
        }
        reuse.row(node, input, e.input(), value, value == null ? null : e.answer().substring(2));
        if (e.graph() != null) {
            Plot plot = new Plot(gui, e.graph(), () -> explore.accept(e));
            gui.landmark(Landmarks.explore(index + 1), plot.exploreButton());
            plots.add(plot);
            node.append(plot.node().padding(Length.ZERO, Type.WASH));
        }
        if (!e.readings().isEmpty()) {
            node.append(gui.text(e.readings()).font(Type.MONO).textSize(Type.SMALL)
                    .textColor(gui.theme().color(Role.FAINT)).padding(Length.ZERO, Type.WASH));
        }
        return node;
    }

    /**
     * A line's text with room round it for {@link Reuse}'s hover wash, and the panel's corners. Given to every
     * input and answer, reusable or not, so the tape's rhythm does not depend on which lines are; the tape's
     * {@link Type#TAPE_EDGE} gives the sideways room back, so the text itself sits where it always did.
     */
    /**
     * A value's answer typeset as an expression, {@code = 6·0 + 9 + 0²}, at the size the answer's text was. In a row
     * of its own, since the block's children float against its box and a chip's padding belongs outside it.
     */
    private Node typeset(Doc.Entry e) {
        Box answer = Box.row(Recipes.relation("="), Expressions.of(e.value(), e.constructed(), Expressions.boxes(math)))
                .withSize(ANSWER_SIZE);
        return gui.row().width(Length.FILL).height(Length.AUTO).alignItems(AlignItems.CENTER)
                .children(new TypesetBlock(gui, typeset, answer).node());
    }

    /** A tape answer's size against the root em the typesetter solves at: {@link Type#HEADING}'s. */
    private static final double ANSWER_SIZE = 1.125;

    /** The typesetter, or null where it cannot measure: the answers are then their text. */
    private static Typeset typesetter(Profile math) {
        try {
            return new Typeset(TextFaces.standard(), math, FaceKeys.single());
        } catch (RuntimeException noAtlas) {
            return null;
        }
    }

    private static Node chip(Node text) {
        return text.padding(Type.WASH_Y, Type.WASH).corner(Type.CORNER);
    }

    /** Write everything derived from the document. */
    synchronized void show(Doc doc) {
        for (; shown < doc.tape().size(); shown++) {
            tape.append(line(doc.tape().get(shown), shown));
        }
        error.text(doc.error());
    }

    /**
     * What the gear does. Handed in rather than built here because the settings window is a window, and the
     * tree exists a phase before there are any; until then the gear does nothing, which no user can see.
     */
    void onSettings(Runnable action) {
        settings = action == null ? () -> { } : action;
    }

    /**
     * What popping a plot out does, handed the line it is on: the plot explorer, which is a window, so it is handed in
     * for the reason {@link #onSettings} gives.
     */
    void onExplore(Consumer<Doc.Entry> action) {
        explore = action == null ? e -> { } : action;
    }

    private void openSettings() {
        settings.run();
    }

    /**
     * The settings button: a gear, drawn, because the atlas font has no glyph for one. Square and as tall as the
     * entry's line, hover-shaded like the title bar's buttons, and a button to the keyboard too — it takes focus,
     * and Enter or Space presses it. Ctrl+, presses it from anywhere.
     */
    private Node gear() {
        Node b = gui.box()
                .size(GEAR, GEAR)
                .corner(Type.CORNER)
                .background(gui.theme().color(Role.NONE))
                .scroll(false, false);
        gui.onResizeUi(b, layout -> b.picture(gearPicture(layout.rect().w(), layout.rect().h(),
                gui.theme().color(Role.DIM))));
        gui.onClick(b, this::openSettings);
        gui.onState(b, state -> b.background(
                gui.theme().color(state == InteractionState.NORMAL ? Role.NONE : Role.RAISED)));
        gui.focusable(b, true);
        gui.claim(b, Shortcut.of(Key.ENTER), ClaimScope.FOCUSED, this::openSettings);
        gui.claim(b, Shortcut.of(Key.SPACE), ClaimScope.FOCUSED, this::openSettings);
        gui.cursor(b, CursorShape.POINTER);
        tips.attach(b, "Settings (Ctrl+,)");
        return b;
    }

    /** A gear centred in a {@code w × h} box: a ring for the body and eight teeth round it. In pixels. */
    private static Picture gearPicture(double w, double h, Color ink) {
        double s = Math.min(w, h);
        if (s < 6) return null;
        double cx = w / 2, cy = h / 2;
        double body = s * 0.24;
        double band = s * 0.11;
        double tooth = s * 0.12;
        Sketch k = new Sketch().ring(cx, cy, body, band, ink);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            double c = Math.cos(a), sn = Math.sin(a);
            k.line(cx + c * body, cy + sn * body,
                    cx + c * (body + tooth * 0.55), cy + sn * (body + tooth * 0.55), tooth, ink);
        }
        return k.picture();
    }
}
