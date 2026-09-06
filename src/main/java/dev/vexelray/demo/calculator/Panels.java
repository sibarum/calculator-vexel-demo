package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InteractionState;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.widget.Inspector;
import dev.vexelray.gui.widget.Property;
import dev.vexelray.gui.widget.Rail;
import dev.vexelray.gui.widget.Tooltip;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The icon rail and the six panels behind it.
 *
 * <h2>This is almost entirely the framework's</h2>
 *
 * <p>{@link Rail} is the rail — and it is a rail rather than {@link dev.vexelray.gui.widget.Tabs} turned on its
 * side, which matters here: <em>none selected</em> is a legitimate state, so clicking the open icon puts the
 * panel away and gives the canvas back. That is the whole reason the prototype's chrome floats over the plot
 * instead of docking beside it.
 *
 * <p>{@link Inspector} lays every panel out from {@link Property} declarations, so the rows, the sections, the
 * aligned value column and the folding layer cards are all had rather than built. The application contributes
 * <em>what is settable</em> and nothing about how a row looks. Two things are worth noticing about that: there
 * is no switch anywhere in this file over what kind a property is, and the panel is not the schema — a second
 * reader of these settings (a preset dump, a transcript of what the user changed) would read the same
 * declarations.
 *
 * <h2>What the application still writes</h2>
 *
 * <p>The icons ({@link Icons}, because there is no icon vocabulary — FN-5), and the action rows: {@code Iso ·
 * Top · Front · Side · Fit · Reset} has no framework component and neither does a plain button, so
 * {@link #actions} is five lines of styling per row that {@code Toolbar} should own one day (FN-3).
 *
 * <h2>The opacity slider is not in the card header</h2>
 *
 * <p>The prototype puts it there, beside the badge. {@link Inspector.Card} offers title, switch, badge and fold
 * and no header slot, and {@code node()} is a handle to style rather than to restructure. So it is the first row
 * of each card's body instead — <b>a real behaviour change</b>, because it is then hidden when the card is
 * folded, and the reason FN-2 asks for {@code Card.headerControl(Node)}.
 */
final class Panels {

    /** What the VIEW panel's buttons need from the camera. Kept narrow so the panel cannot reach further. */
    interface Viewpoint {

        void look(double yawDegrees, double pitchDegrees);

        void reset();
    }

    private final Gui gui;
    private final Model model;
    private final Rail rail;
    private final Tooltip tips;
    private final List<Inspector> inspectors = new ArrayList<>();
    private final Node row;

    Panels(Gui gui, Model model, Viewpoint camera) {
        this.gui = gui;
        this.model = model;
        this.tips = new Tooltip(gui);
        this.rail = new Rail(gui)
                .panelWidth(Length.dp(284))
                .titles(tips);

        rail.item("layers", tile("layers", Icons::layers), "LAYERS", this::layers);
        rail.item("domain", tile("domain", Icons::domain), "DOMAIN & CROP", this::domain);
        rail.item("crop", tile("crop", Icons::crop), "CROP", this::crop);
        rail.item("color", tile("color", Icons::colour), "COLOR", this::colour);
        rail.item("sample", tile("sample", Icons::sampling), "SAMPLING & READOUT", this::sampling);
        rail.item("view", tile("view", Icons::view), "VIEW", (g, into) -> view(into, camera));
        rail.item("help", tile("help", Icons::keys), "CONTROLS", this::help);
        rail.action(tile("reset", Icons::crosshair), camera::reset);
        gui.landmark(Landmarks.PANEL, rail.panel());

        row = gui.row().width(Length.AUTO).height(Length.AUTO)
                .gap(Type.GAP)
                .alignItems(AlignItems.CENTER)
                .children(rail.node(), rail.panel());

        // A solid surface inside the viewport's subtree has to stop a drag reaching the viewport, and the only
        // way to say that is to register a handler that does nothing. See framework-notes.md FN-9.
        gui.onDrag(rail.node(), e -> { });
        gui.onDrag(rail.panel(), e -> { });

        // The panels are a view of the model, so anything that changes it elsewhere -- a key, a preset, the
        // expression -- brings them up to date. Property's getters-not-values contract is what makes this one
        // line rather than a push per control.
        model.onChange(s -> inspectors.forEach(Inspector::refresh));
    }

    /**
     * An icon, landmarked so a driver can reach the button it sits in.
     *
     * <p>{@link Rail} keeps its tiles private -- it hands out the bar and the panel and nothing between -- so
     * there is no handle to name the "layers button" with. The icon <em>is</em> the application's node, though,
     * and it lives inside the tile, so a landmark on it resolves to a box whose centre is the tile's. That is a
     * workaround for {@code docs/framework-notes.md} FN-23 rather than the right answer: it works because this
     * application happens to supply the icon, and a rail whose tiles were built for it would be undrivable.
     */
    private Node tile(String key, java.util.function.Function<Color, dev.vexelray.gui.draw.Picture> mark) {
        Node node = icon(mark);
        node.role("button");
        gui.landmark(Landmarks.rail(key), node);
        return node;
    }

    /**
     * A rail tile's contents: a box carrying one {@link Picture}.
     *
     * <p>{@link Rail#item} asks for a {@link Node} rather than an icon type, and says why — the widget module
     * has no icon set and should not grow one. So the application supplies whatever a node can be, and here
     * that is a box with a picture on it, sized to the mark's own unit square.
     */
    private Node icon(java.util.function.Function<Color, dev.vexelray.gui.draw.Picture> mark) {
        Node box = gui.box().size(Length.dp((float) Icons.SIZE), Length.dp((float) Icons.SIZE));
        box.picture(mark.apply(gui.theme().color(Look.QUIET)));
        return box;
    }

    /**
     * The rail and its panel, side by side.
     *
     * <p>{@code Rail.node()} is the icon column alone and {@code Rail.panel()} is a separate node the caller
     * places beside it -- which is right, because a rail and a panel are two surfaces with a gap between them
     * and only the application knows which edge they are against. Putting them in one row here is the
     * application making that decision once.
     */
    Node node() {
        return row;
    }

    Rail rail() {
        return rail;
    }

    // --------------------------------------------------------------- panels

    private void layers(Gui g, Node into) {
        Inspector panel = inspector(into);
        card(panel, "line", "Line",
                s -> s.cards().line(), (s, on) -> cards(s, c -> new Scene.Cards(on, c.surface(), c.volume(),
                        c.axes(), c.grid(), c.lineOpen(), c.axesOpen(), c.gridOpen())))
                .add(Property.range("", "Opacity", 0, 100, 1, () -> 100, v -> { }),
                        Property.choice("", "Material", List.of(
                                        new Property.Option<>("Solid", "solid"),
                                        new Property.Option<>("Emissive", "emissive"),
                                        new Property.Option<>("Additive", "additive")),
                                () -> "solid", v -> { }),
                        Property.range("", "Width", 0.01, 0.09, 0.005,
                                () -> scene().lineWidth(), v -> edit(s -> with(s, w -> w.lineWidth(v)))));

        card(panel, "surface", "Surface",
                s -> s.cards().surface(), (s, on) -> cards(s, c -> new Scene.Cards(c.line(), on, c.volume(),
                        c.axes(), c.grid(), c.lineOpen(), c.axesOpen(), c.gridOpen())));

        card(panel, "volume", "Volume",
                s -> s.cards().volume(), (s, on) -> cards(s, c -> new Scene.Cards(c.line(), c.surface(), on,
                        c.axes(), c.grid(), c.lineOpen(), c.axesOpen(), c.gridOpen())));

        card(panel, "axes", "Axes",
                s -> s.cards().axes(), (s, on) -> cards(s, c -> new Scene.Cards(c.line(), c.surface(), c.volume(),
                        on, c.grid(), c.lineOpen(), c.axesOpen(), c.gridOpen())))
                .add(Property.flag("", "Tick marks", () -> scene().furniture().ticks(),
                        v -> edit(s -> with(s, w -> w.furniture(f -> new Geometry.Furniture(f.axes(), v,
                                f.gridXY(), f.gridXZ(), f.gridYZ(), f.divisions()))))));

        card(panel, "grid", "Grid",
                s -> s.cards().grid(), (s, on) -> cards(s, c -> new Scene.Cards(c.line(), c.surface(), c.volume(),
                        c.axes(), on, c.lineOpen(), c.axesOpen(), c.gridOpen())))
                .add(plane("XY plane", f -> f.gridXY(), (f, v) -> new Geometry.Furniture(f.axes(), f.ticks(),
                                v, f.gridXZ(), f.gridYZ(), f.divisions())),
                        plane("XZ plane", f -> f.gridXZ(), (f, v) -> new Geometry.Furniture(f.axes(), f.ticks(),
                                f.gridXY(), v, f.gridYZ(), f.divisions())),
                        plane("YZ plane", f -> f.gridYZ(), (f, v) -> new Geometry.Furniture(f.axes(), f.ticks(),
                                f.gridXY(), f.gridXZ(), v, f.divisions())),
                        Property.range("", "Divisions", 2, 16, 1,
                                () -> scene().furniture().divisions(),
                                v -> edit(s -> with(s, w -> w.furniture(f -> new Geometry.Furniture(f.axes(),
                                        f.ticks(), f.gridXY(), f.gridXZ(), f.gridYZ(), (int) v))))));

        into.append(note(g, "Layers are independent. A surface can also draw as lines; a volume as a surface."));
    }

    private void domain(Gui g, Node into) {
        inspector(into).add(
                Property.range("PARAMETERS", "ω", 0.25, 8, 0.25,
                        () -> scene().omega(), v -> edit(s -> with(s, w -> w.omega(v)))),
                Property.flag("RENDER VOLUME", "Crop handles",
                        () -> scene().cropping(), v -> edit(s -> with(s, w -> w.cropping(v)))),
                Property.number("X DOMAIN", "min", 0.5, -1000, 1000,
                        () -> scene().x0(), v -> edit(s -> with(s, w -> w.x0(Math.min(v, s.x1() - 0.5))))),
                Property.number("X DOMAIN", "max", 0.5, -1000, 1000,
                        () -> scene().x1(), v -> edit(s -> with(s, w -> w.x1(Math.max(v, s.x0() + 0.5))))));
        into.append(actions(g,
                action("Symmetric ±3", () -> edit(s -> with(s, w -> w.x0(-3).x1(3)))),
                action("±2π", () -> edit(s -> with(s, w -> w.x0(-2 * Math.PI).x1(2 * Math.PI)))),
                action("Unit box", () -> edit(s -> with(s, w -> w.x0(-1).x1(1))))));
    }

    private void crop(Gui g, Node into) {
        inspector(into).add(Property.flag("CROP", "Show the handles",
                () -> scene().cropping(), v -> edit(s -> with(s, w -> w.cropping(v)))));
        into.append(note(g, "Crop is not built yet: the handles have nowhere to drag the render volume to."));
    }

    private void colour(Gui g, Node into) {
        List<Property.Swatch<Ramp>> maps = new ArrayList<>();
        for (Ramp r : Ramp.values()) {
            maps.add(new Property.Swatch<>(r.label(), r, r.stops()));
        }
        inspector(into).add(
                Property.choice("MAPPING", "Colour by", List.of(
                                new Property.Option<>("Height", "height"),
                                new Property.Option<>("Position", "uv")),
                        () -> "height", v -> { }),
                Property.swatches("", "", maps,
                        () -> scene().ramp(), v -> edit(s -> with(s, w -> w.ramp(v)))));
    }

    private void sampling(Gui g, Node into) {
        inspector(into).add(
                Property.range("RESOLUTION", "Grid", 12, 1200, 4,
                        () -> scene().samples(), v -> edit(s -> with(s, w -> w.samples((int) v)))),
                Property.choice("READOUT", "Numbers", List.of(
                                new Property.Option<>("Rational", "rational"),
                                new Property.Option<>("Decimal", "decimal")),
                        () -> "decimal", v -> { }));
    }

    private void view(Node into, Viewpoint camera) {
        inspector(into).add(Property.flag("CAMERA", "Auto-orbit",
                () -> scene().spinning(), v -> edit(s -> with(s, w -> w.spinning(v)))));
        into.append(actions(gui,
                action("Iso", () -> camera.look(38, 26)),
                action("Top", () -> camera.look(0, 75)),
                action("Front", () -> camera.look(0, 0)),
                action("Side", () -> camera.look(90, 0)),
                action("Reset", camera::reset)));
    }

    private void help(Gui g, Node into) {
        String[][] rows = {
                {"drag", "Orbit — azimuth free, elevation clamped to ±75°"},
                {"shift + drag", "Pan the plot"},
                {"scroll", "Zoom about the center"},
                {"R / F", "Reset view · fit to frame"},
                {"T / S", "Top view · front view"},
                {"space", "Toggle auto-orbit"},
                {"C", "Crop mode"},
                {"enter", "Commit the expression in the field"}};
        Node list = g.column().width(Length.grow(1f)).padding(Type.TIGHT, Type.WIDE).gap(Type.TIGHT);
        for (String[] row : rows) {
            Node key = g.text(row[0]).font(Type.MONO).textSize(Type.READOUT)
                    .width(Length.dp(96))
                    .textColor(g.theme().color(Look.ACCENT_LIGHT));
            Node what = g.text(row[1]).textSize(Type.LABEL).width(Length.grow(1f)).wordWrap(true)
                    .textColor(g.theme().color(Role.DIM));
            list.append(g.row().width(Length.grow(1f)).gap(Type.GAP).children(key, what));
        }
        into.append(list);
    }

    // ---------------------------------------------------------------- pieces

    /** A card, with the layer's AUTO badge kept in step with what the expression asked for. */
    private Inspector.Card card(Inspector panel, String key, String title,
                                java.util.function.Predicate<Scene> on, java.util.function.BiConsumer<Scene, Boolean> set) {
        Inspector.Card card = panel.card(title, scene().isAuto(key) ? "AUTO" : "",
                () -> on.test(scene()), v -> set.accept(scene(), v));
        model.onChange(s -> card.badge(s.isAuto(key) ? "AUTO" : ""));
        return card;
    }

    private Property plane(String name, java.util.function.Predicate<Geometry.Furniture> get,
                           java.util.function.BiFunction<Geometry.Furniture, Boolean, Geometry.Furniture> set) {
        return Property.flag("", name, () -> get.test(scene().furniture()),
                v -> edit(s -> with(s, w -> w.furniture(f -> set.apply(f, v)))));
    }

    private Inspector inspector(Node into) {
        Inspector panel = new Inspector(gui);
        inspectors.add(panel);
        into.append(panel.node());
        return panel;
    }

    /** The dashed footnote the prototype puts at the bottom of a panel. */
    private Node note(Gui g, String text) {
        return g.text(text)
                .width(Length.grow(1f)).wordWrap(true)
                .margin(Type.GAP)
                .padding(Length.dp(8), Length.dp(9))
                .corner(Length.dp(6))
                .border(Type.RULE, g.theme().color(Look.LINE))
                .textSize(Type.READOUT)
                .textColor(g.theme().color(Role.FAINT));
    }

    private record Act(String label, Runnable run) {
    }

    private static Act action(String label, Runnable run) {
        return new Act(label, run);
    }

    /**
     * A wrapping row of outline buttons.
     *
     * <p>Hand-built, because the framework has neither a {@code Button} nor the {@code Toolbar} its own todo
     * §4.5 asks for. Five lines each, and five chances to forget the focus ring — which is exactly the argument
     * for the component existing (FN-3).
     */
    private Node actions(Gui g, Act... acts) {
        Node row = g.row().width(Length.grow(1f)).gap(Type.TIGHT)
                .padding(Type.GAP, Type.WIDE)
                .alignItems(AlignItems.CENTER);
        Color line = g.theme().color(Look.ACCENT_LINE);
        for (Act a : acts) {
            Node button = g.text(a.label()).role("button")
                    .padding(Length.dp(5), Length.dp(10))
                    .corner(Length.dp(6))
                    .border(Type.RULE, line)
                    .textSize(Type.SMALL)
                    .textColor(g.theme().color(Look.ACCENT_LIGHT));
            g.focusable(button, true);
            g.cursor(button, CursorShape.POINTER);
            g.onClick(button, a.run());
            g.onState(button, st -> button.background(st == InteractionState.HOVER
                    ? g.theme().color(Look.ACCENT_WASH)
                    : g.theme().color(Role.NONE)));
            row.append(button);
        }
        return row;
    }

    // ------------------------------------------------------------ shorthand

    private Scene scene() {
        return model.scene();
    }

    private void edit(java.util.function.UnaryOperator<Scene> change) {
        model.change(change);
    }

    private static Scene with(Scene s, Consumer<Scene.Draft> change) {
        return Scene.with(s, change);
    }

    /** Toggle auto-orbit. Static so the Space claim can reach it without holding a panel. */
    static Scene spinning(Scene s, boolean on) {
        return Scene.with(s, w -> w.spinning(on));
    }

    /** Toggle crop mode, for the C claim and the crop rail button alike. */
    static Scene cropping(Scene s, boolean on) {
        return Scene.with(s, w -> w.cropping(on));
    }

    private void cards(Scene s, java.util.function.UnaryOperator<Scene.Cards> change) {
        edit(cur -> with(cur, w -> w.cards(change.apply(cur.cards()))));
    }
}
