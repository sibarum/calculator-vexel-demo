package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.LayoutEnums.Direction;
import dev.vexelray.gui.core.layout.LayoutEnums.Justify;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.krono.KronoGui;
import dev.vexelray.gui.widget.TitleBar;

/**
 * The tree. Builds it, holds the handles the application needs afterwards, and owns nothing else.
 *
 * <h2>The overlays are children of the viewport, and that is a decision</h2>
 *
 * <p>The prototype floats its chrome over a full-bleed canvas with {@code pointer-events: none} on the
 * containers and {@code auto} on the controls inside them, so a drag that misses a control still orbits the
 * plot. {@link Node#hitInert} cannot express that — it covers a node <em>and its whole subtree</em>, with no way
 * back out on a descendant.
 *
 * <p>The framework's own dispatch gives the same result without needing one. Hit-testing returns the deepest
 * node under the pointer whether or not it has a handler, and pointer events then bubble leaf→root — so an
 * overlay parented to <b>the viewport</b> passes anything it does not handle up to the viewport, which orbits.
 * Parenting, rather than a property. The one thing that will cost is noted where it bites: a solid surface
 * inside that subtree (the panel, when it arrives) has to stop the bubble with an empty drag handler, because
 * there is no way to say "this surface is opaque to the pointer". See {@code docs/framework-notes.md}, FN-9.
 *
 * <h2>Why the viewport is a box and not a node kind</h2>
 *
 * <p>It carries the marched scene as an {@code IMAGE} prop and its labels as a {@code PICTURE} prop. Both are
 * props on an ordinary box, so the thing sizes by flex, and its corner, border, clip and opacity apply to the
 * scene for free. A third node kind would have had to earn each of those back one at a time.
 */
final class Ui {

    private final Gui gui;
    private final TitleBar titleBar;
    private final Node viewport;
    private final Bar bar;
    private final Readout readout;

    Ui(Gui gui, KronoGui krono) {
        this.gui = gui;

        // The window's own chrome. Handed no controls yet -- the window does not exist until GuiApp is
        // constructed, and a bar bound to a window that is not there would be a set of buttons that do nothing.
        titleBar = new TitleBar(gui, WindowControls.NONE, "Calculator");

        viewport = gui.box()
                .width(Length.FILL).height(Length.grow(1f))
                .background(gui.theme().color(Role.PAGE))
                .role("viewport");
        gui.landmark(Landmarks.VIEWPORT, viewport);

        bar = new Bar(gui);
        readout = new Readout(gui);

        // Top-left is the float's own origin, so this is just the prototype's two insets. A floating child
        // takes no space from its siblings, adds nothing to the parent's overflow, and is hit before every
        // sibling that precedes it -- the whole overlay primitive, with no layer of its own.
        bar.node().floatAt(Type.EDGE_X, Type.EDGE_Y);
        viewport.append(bar.node());

        viewport.append(corner(readout.node()));

        gui.root().direction(Direction.COLUMN).background(gui.theme().color(Role.PAGE))
                .children(titleBar.node(), viewport);

        bar.show(Canned.read(Canned.DEFAULT_EXPRESSION));
        readout.show(Math.toRadians(38), Math.toRadians(26), 1.0, 46);
    }

    /**
     * Put a node in the bottom-right corner, inset.
     *
     * <p>{@code floatAt} anchors to the parent's top-left, so the other three corners look like they need a
     * measured box. They do not: a float is <b>clamped to stay inside its parent</b> — that is what lets a menu
     * near an edge slide in rather than crop — so {@code percent(100)} resolves past the edge and clamps to
     * flush, on both axes, at any size and with nothing to keep in step.
     *
     * <p>The inset then has to come from somewhere the clamp can see, which is why this is a layer rather than
     * a margin: a {@code FILL} float is the parent box out of flow, and its <em>padding</em> is the inset. It is
     * {@code hitInert}, so hit-testing passes through the whole layer and the viewport underneath is still the
     * pointer's target — which is both required (an orbit begun in that corner must work) and free, since
     * nothing in this corner is meant to be clicked.
     */
    private Node corner(Node content) {
        return gui.row()
                .width(Length.FILL).height(Length.FILL)
                .padding(Type.EDGE_Y, Type.EDGE_X)
                .justify(Justify.END)
                .alignItems(AlignItems.END)
                .hitInert(true)
                .floatAt(Length.dp(0), Length.dp(0))
                .children(content);
    }

    TitleBar titleBar() {
        return titleBar;
    }

    Node viewport() {
        return viewport;
    }

    Bar bar() {
        return bar;
    }

    Readout readout() {
        return readout;
    }
}
