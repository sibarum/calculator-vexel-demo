package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.ScrollLock;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.gui.core.input.InputTopics;
import sibarum.tactroller.api.InputEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The tree: a tape of past lines filling the window, the reason the last line was refused under it, and an
 * entry with the arithmetic's and the recursion limits' buttons at the bottom.
 *
 * <p>The tape only grows, so {@link #show} appends the entries it has not drawn yet rather than rebuilding the
 * column; {@code shown} is the one thing this class remembers, and it is a count of nodes rather than a value
 * anything is derived from.
 */
final class Ui {

    private final Gui gui;
    private final Node tape;
    private final Node error;
    private final Button mode;
    private final Button limits;
    private final List<Plot> plots = new CopyOnWriteArrayList<>();
    private int shown;

    Ui(Gui gui, Model model, TitleBar titleBar) {
        this.gui = gui;

        tape = gui.column()
                .width(Length.FILL).height(Length.grow(1f))
                .gap(Type.WIDE)
                .padding(Type.WIDE, Type.EDGE)
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
        // A refused line stays where it is, to be fixed; a taken one is cleared with replace rather than text,
        // so Ctrl+Z brings it back.
        entry.onSubmit(line -> {
            if (model.enter(line)) {
                entry.replace("");
                tape.scrollToEdge();
            }
        });
        gui.landmark(Landmarks.ENTRY, entry.node());

        // Back to the entry afterwards: the button is a setting for the next line, not somewhere to stay.
        mode = new Button(gui, "").onPress(() -> {
            model.nextArithmetic();
            gui.focus(entry.node());
        });
        gui.landmark(Landmarks.MODE, mode.node());

        limits = new Button(gui, "").onPress(() -> {
            model.nextLimits();
            gui.focus(entry.node());
        });
        gui.landmark(Landmarks.LIMITS, limits.node());

        Node bottom = gui.column()
                .width(Length.FILL).height(Length.AUTO)
                .gap(Type.TIGHT)
                .padding(Type.WIDE, Type.EDGE)
                .background(gui.theme().color(Role.PANEL))
                .children(error,
                        gui.row().width(Length.FILL).gap(Type.GAP).alignItems(AlignItems.CENTER)
                                .children(entry.node(), mode.node(), limits.node()));

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

    /** One line of the tape: what was typed, dim; the answer; and its other readings, small. */
    private Node line(Doc.Entry e) {
        Node node = gui.column().width(Length.FILL).height(Length.AUTO).gap(Type.TIGHT).children(
                gui.text(e.input()).font(Type.MONO).textSize(Type.LABEL)
                        .textColor(gui.theme().color(Role.DIM)));
        // A plotted line's answer is the expression back again; worth showing only when substitution changed it.
        boolean echo = e.graph() != null && e.answer().replace(" ", "").equals(e.input().replace(" ", ""));
        if (!echo) {
            node.append(gui.text(e.answer()).font(Type.MONO).textSize(Type.HEADING)
                    .textColor(gui.theme().color(Role.INK)));
        }
        if (e.graph() != null) {
            Plot plot = new Plot(gui, e.graph());
            plots.add(plot);
            node.append(plot.node());
        }
        if (!e.readings().isEmpty()) {
            node.append(gui.text(e.readings()).font(Type.MONO).textSize(Type.SMALL)
                    .textColor(gui.theme().color(Role.FAINT)));
        }
        return node;
    }

    /** Write everything derived from the document. */
    synchronized void show(Doc doc) {
        for (; shown < doc.tape().size(); shown++) {
            tape.append(line(doc.tape().get(shown)));
        }
        error.text(doc.error());
        mode.label(doc.arithmetic());
        limits.label(doc.limits());
    }
}
