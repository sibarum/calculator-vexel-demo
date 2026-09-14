package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.draw.Picture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The axis annotations, and the one rule that ties them to the axes.
 *
 * <p>{@link Labels} draws into an overlay that knows nothing about the geometry, so "is this axis annotated"
 * has to be answered in one place or the plot grows text with nothing under it. These are the tests for that
 * place.
 */
class FurnitureTest {

    /** A scene with the furniture set to something, and nothing else disturbed. */
    private static Scene sceneWith(Geometry.Furniture furniture) {
        return Scene.with(Scene.initial(), w -> w.furniture(f -> furniture));
    }

    /** The camera {@link March} builds, at the opening view: an eye orbiting the origin, looking back at it. */
    private static Lens lens() {
        double yaw = Math.toRadians(38);
        double pitch = Math.toRadians(26);
        double distance = 7.0;
        return new Lens(
                -distance * Math.cos(pitch) * Math.sin(yaw),
                distance * Math.sin(pitch),
                -distance * Math.cos(pitch) * Math.cos(yaw),
                yaw, pitch, 640.0 / 400.0, 2.5);
    }

    /** A laid-out box, which is all {@link Labels#of} reads of a node. */
    private static NodeLayout box() {
        Rect rect = new Rect(0, 0, 640, 400);
        return new NodeLayout(true, rect, rect, rect, 0, 0, 0, 0, 640, 400, false, false, 0, null);
    }

    /** What the geometry builds for a scene, which is where the graduations come from. */
    private static Geometry.Built built(Scene scene) {
        return Geometry.of(scene.reading(), scene.x0(), scene.x1(), scene.samples(),
                scene.effectiveFurniture(), scene.lineWidth(), scene.ramp());
    }

    /** How many marks the overlay puts out for a scene. */
    private static int marks(Scene scene) {
        Geometry.Built built = built(scene);
        Picture picture = Labels.of(lens(), box(), scene.reading(), built.furniture(), built.marks());
        return picture.marks().size();
    }

    @Test
    @DisplayName("the overlay stops drawing the axis names when the switch is off")
    void theOverlayHonoursTheSwitch() {
        // The assertion the panel tests cannot make: those show the flag reaching the scene, this shows the
        // scene reaching the picture. Three names and no more -- the tick numbers are unchanged, which is what
        // makes the difference attributable to the labels rather than to the whole overlay going quiet.
        Scene named = Scene.initial();
        Scene unnamed = sceneWith(Geometry.Furniture.DEFAULT.withLabels(false));

        assertTrue(named.reading().drawsCurve(), "the premise: the opening expression draws a curve");
        assertEquals(3, marks(named) - marks(unnamed),
                "turning the axis names off should remove exactly the three names");
    }

    @Test
    @DisplayName("an axis that is not drawn takes the whole overlay with it")
    void theOverlayGoesQuietWithTheAxes() {
        Scene off = sceneWith(Geometry.Furniture.DEFAULT.withAxes(false));

        assertEquals(0, marks(off),
                "the overlay drew over a plot that has no axes to annotate");
    }

    @Test
    @DisplayName("a single value gets numbered axes too, where only a curve used to")
    void aPointModeSceneIsNumbered() {
        Model model = new Model();
        model.submit("2+2");
        Scene point = model.scene();

        assertFalse(point.reading().drawsCurve(), "the premise: this is a value, not a curve");
        assertTrue(point.reading().understood(), "the premise: and it is understood");
        assertEquals(3 + 3 * built(point).marks().values().length, marks(point),
                "a single value's axes went ungraduated");
    }

    @Test
    @DisplayName("a refused expression gets no numbers, because there is no scale to state")
    void aRefusalIsNotNumbered() {
        Model model = new Model();
        model.submit("&&&");
        Scene refused = model.scene();

        assertFalse(refused.reading().understood(), "the premise: this is refused");
        // The axis names still draw -- they say which axis is which, which is true of an empty box too.
        assertEquals(3, marks(refused), "an empty plot was given a scale");
    }

    @Test
    @DisplayName("every axis is numbered, not just the first")
    void allThreeAxesAreNumbered() {
        Scene scene = Scene.initial();
        int graduations = built(scene).marks().values().length;
        assertTrue(graduations > 0, "the premise: the default scene has marks to number");

        // Three names plus a number per mark per axis. Anything less than three numbers a mark is an axis
        // going ungraduated, which is the state all of this was in: one axis lettered, two bare.
        assertEquals(3 + 3 * graduations, marks(scene),
                "the overlay did not number all three axes");
    }

    @Test
    @DisplayName("the numbers say what the marks stand for, not what the input domain was")
    void numbersComeFromTheMarksRatherThanTheDomain() {
        // 0^(2*x) reaches twice as far as it is walked, so the values the axes are graduated at and the input
        // range have to differ -- which is the case the old overlay got wrong, and the case that cannot be
        // seen with the default expression because there the two coincide.
        Model model = new Model();
        model.change(s -> Scene.with(s, w -> w.x0(-6).x1(6)));
        model.submit("0^(2*x)");
        Scene stretched = model.scene();

        double[] values = built(stretched).marks().values();
        assertTrue(values.length > 0, "the premise: this expression is drawn and graduated");
        assertTrue(Math.abs(values[values.length - 1]) > 6,
                "the premise: its values reach past its domain, so the two cannot be confused -- reached "
                        + values[values.length - 1]);

        // The overlay writes one number per mark per axis and takes them from the marks, so the count follows
        // the marks rather than the domain. Ticks.between(-6, 6) would give six; this gives however many the
        // values reached.
        assertEquals(3 + 3 * values.length, marks(stretched),
                "the overlay numbered something other than the marks the geometry made");
    }

    @Test
    @DisplayName("a wither changes one component and carries the other six")
    void withersCarryTheRest() {
        Geometry.Furniture f = Geometry.Furniture.DEFAULT.withLabels(false);

        assertFalse(f.labels(), "the component asked for did not change");
        assertTrue(f.axes(), "axes was carried away by a labels change");
        assertTrue(f.ticks(), "ticks was carried away by a labels change");
        assertTrue(f.gridXY() && f.gridXZ() && f.gridYZ(), "a grid plane was carried away");
        assertEquals(Geometry.Furniture.DEFAULT.divisions(), f.divisions(), "divisions was carried away");
    }

    @Test
    @DisplayName("labels and ticks are independent of each other")
    void annotationsAreSeparate() {
        Geometry.Furniture named = Geometry.Furniture.DEFAULT.withTicks(false);
        assertTrue(named.labels(), "turning the graduations off took the axis names with them");

        Geometry.Furniture graduated = Geometry.Furniture.DEFAULT.withLabels(false);
        assertTrue(graduated.ticks(), "turning the axis names off took the graduations with them");
    }

    @Test
    @DisplayName("an axis that is not drawn carries neither its names nor its numbers")
    void annotationsNeedTheirAxes() {
        // The failure this is about: the overlay used to read `ticks` straight off the scene and draw the axis
        // names unconditionally, so switching the axes off left both floating over the plot.
        Scene off = sceneWith(Geometry.Furniture.DEFAULT.withAxes(false));

        assertFalse(off.effectiveFurniture().axes());
        assertFalse(off.effectiveFurniture().ticks(), "tick numbers survived the axis they graduate");
        assertFalse(off.effectiveFurniture().labels(), "axis names survived the axis they name");

        // And the choice itself is remembered rather than cleared, so turning the axes back on brings back the
        // annotations that were on before -- the same rule the layer cards follow.
        assertTrue(off.furniture().ticks(), "the choice was cleared rather than overridden");
        assertTrue(off.furniture().labels(), "the choice was cleared rather than overridden");
    }

    @Test
    @DisplayName("the Axes layer card gates the annotations too, not just the axis lines")
    void theCardGatesTheAnnotations() {
        Scene on = Scene.initial();
        assertTrue(on.effectiveFurniture().labels(), "the default scene should be labelled");

        Scene carded = Scene.with(on, w -> w.cards(new Scene.Cards(
                on.cards().line(), on.cards().surface(), on.cards().volume(),
                false, on.cards().grid(),
                on.cards().lineOpen(), on.cards().axesOpen(), on.cards().gridOpen())));

        assertFalse(carded.effectiveFurniture().labels(), "the card left the axis names behind");
        assertFalse(carded.effectiveFurniture().ticks(), "the card left the tick numbers behind");
    }

    @Test
    @DisplayName("the grid switches are unaffected by the axis switch, and the other way round")
    void gridAndAxesAreIndependent() {
        Scene noAxes = sceneWith(Geometry.Furniture.DEFAULT.withAxes(false));
        assertTrue(noAxes.effectiveFurniture().gridXY(), "the grid went with the axes");

        Scene noGrid = sceneWith(Geometry.Furniture.DEFAULT
                .withGridXY(false).withGridXZ(false).withGridYZ(false));
        assertTrue(noGrid.effectiveFurniture().labels(), "the axis names went with the grid");
    }
}
