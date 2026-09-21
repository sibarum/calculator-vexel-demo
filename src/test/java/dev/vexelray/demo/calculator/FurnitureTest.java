package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.draw.Picture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
                scene.effectiveFurniture(), scene.lineWidth(), scene.ramp(), scene.window());
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
        // scene reaching the picture. Two names and no more -- the tick numbers are unchanged, which is what
        // makes the difference attributable to the labels rather than to the whole overlay going quiet.
        Scene named = Scene.initial();
        Scene unnamed = sceneWith(Geometry.Furniture.DEFAULT.withLabels(false));

        assertTrue(named.reading().drawsCurve(), "the premise: the opening expression draws a curve");
        assertEquals(2, marks(named) - marks(unnamed),
                "turning the axis names off should remove exactly the two names");
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
        assertTrue(written(point).containsAll(graduations(point)),
                "a single value's axes went ungraduated");
    }

    /** Numbers along the input axis plus names up the turn axis: every mark the overlay owes a scene. */
    private static List<String> graduations(Scene scene) {
        Geometry.Marks marks = built(scene).marks();
        List<String> owed = new ArrayList<>(List.of(marks.turns()));
        for (double input : marks.inputs()) {
            owed.add(Ticks.label(input, marks.step()));
        }
        return owed;
    }

    /**
     * What the overlay actually wrote.
     *
     * <p>The tests below count marks where they can and read them where they cannot, and the ruler on the
     * turn axis is why: how many of its rungs fit is a question about pixels — the overlay drops one that
     * would land on top of the number above it — so a count is a statement about the camera and the box,
     * where "every name is written" is a statement about the chart.
     */
    private static List<String> written(Scene scene) {
        Geometry.Built built = built(scene);
        Picture picture = Labels.of(lens(), box(), scene.reading(), built.furniture(), built.marks());
        List<String> out = new ArrayList<>();
        for (Picture.Mark mark : picture.marks()) {
            if (mark instanceof Picture.Text text) {
                out.add(text.text());
            }
        }
        return out;
    }

    @Test
    @DisplayName("a refused expression gets no numbers, because there is no scale to state")
    void aRefusalIsNotNumbered() {
        Model model = new Model();
        model.submit("&&&");
        Scene refused = model.scene();

        assertFalse(refused.reading().understood(), "the premise: this is refused");
        // The axis names still draw -- they say which axis is which, which is true of an empty box too.
        assertEquals(2, marks(refused), "an empty plot was given a scale");
    }

    @Test
    @DisplayName("the turn axis carries a name at every turn the model names")
    void everyNamedTurnIsLabelled() {
        Scene scene = Scene.initial();
        assertEquals(Algebra.NAMED.size(), built(scene).marks().turns().length,
                "the premise: the turn axis is graduated by the named turns");

        // A number per input mark and a name per turn. Fewer is a graduation going unwritten, and on the
        // turn axis the names are the landmarks -- the ruler between them yields to them rather than the
        // other way round, so a missing name is a missing landmark and not a crowded column.
        assertTrue(written(scene).containsAll(graduations(scene)),
                "the overlay did not write every graduation");
    }

    @Test
    @DisplayName("magnifying the turn axis puts the ruler on it, and the names keep their places")
    void theRulerArrivesWithTheMagnification() {
        Scene whole = Scene.initial();
        Scene close = Scene.with(whole, w -> w.window(t -> t.centredOn(0).magnifiedBy(1000)));

        // The fineprint: at a thousand times, the arc around the zero rule is graduated in thousandths, and
        // those graduations are written where a reader can see them.
        assertTrue(written(close).contains("1÷1000"), "the magnified axis was left without its ruler");
        assertFalse(written(whole).contains("1÷1000"), "the whole circle has no room for a thousandth");

        // And the names that are still in view are still written, because a rung never displaces one.
        assertTrue(written(close).containsAll(List.of(built(close).marks().turns())),
                "a rung crowded out a named turn");

        // The input axis is untouched by any of it: this is the vertical axis's own window.
        assertArrayEquals(built(whole).marks().inputs(), built(close).marks().inputs(), 1e-12);
    }

    /**
     * <b>The turn axis does not follow the data and the input axis does</b>, which is the split the two kinds
     * of graduation exist for. A value is a direction, so the vertical axis is the same for every expression
     * and two of them can be compared against the same eight marks — where the horizontal axis measures the
     * domain the reader chose to walk, and has to follow it.
     */
    @Test
    @DisplayName("the turns are the same for every expression; the input marks follow the domain")
    void onlyTheInputAxisFollowsTheData() {
        Model wide = new Model();
        wide.change(s -> Scene.with(s, w -> w.x0(-6).x1(6)));
        wide.submit("x*x");
        Model narrow = new Model();
        narrow.change(s -> Scene.with(s, w -> w.x0(-0.5).x1(0.5)));
        narrow.submit("1÷x");

        assertArrayEquals(built(wide.scene()).marks().up(), built(narrow.scene()).marks().up(), 1e-12,
                "two expressions were graduated at different turns");
        assertNotEquals(built(wide.scene()).marks().inputs()[0], built(narrow.scene()).marks().inputs()[0],
                "the input axis is numbered the same over two different domains");
    }

    /** Every name on the turn axis is an entry the field accepts, and it stands at the turn it is written at. */
    @Test
    @DisplayName("a name on the turn axis is an expression, and it means the turn it is written at")
    void everyLabelIsTypeable() {
        Geometry.Marks marks = built(Scene.initial()).marks();

        for (int i = 0; i < marks.turns().length; i++) {
            Algebra.Reading back = Algebra.read(marks.turns()[i]);
            assertTrue(back.drawsMarker(), marks.turns()[i] + " is a label the field would not read");
            double[] at = back.coordinates();
            // The mark's position is the turn mapped into the box; read it back the same way round.
            assertEquals(Math.atan2(at[1], at[0]) / Geometry.TURN * Geometry.BOX, marks.up()[i], 1e-12,
                    marks.turns()[i] + " is written at a height it does not stand at");
        }
    }

    @Test
    @DisplayName("a wither changes one component and carries the other six")
    void withersCarryTheRest() {
        Geometry.Furniture f = Geometry.Furniture.DEFAULT.withLabels(false);

        assertFalse(f.labels(), "the component asked for did not change");
        assertTrue(f.axes(), "axes was carried away by a labels change");
        assertTrue(f.ticks(), "ticks was carried away by a labels change");
        // Against the default's own planes rather than against false: what is being tested is that a wither
        // carries what it did not touch, and writing the default's value into the assertion instead makes
        // this a test of which planes open switched on -- which is a decision, and has changed once.
        Geometry.Furniture was = Geometry.Furniture.DEFAULT;
        assertEquals(was.gridXY(), f.gridXY(), "a grid plane changed under a labels change");
        assertEquals(was.gridXZ(), f.gridXZ(), "a grid plane changed under a labels change");
        assertEquals(was.gridYZ(), f.gridYZ(), "a grid plane changed under a labels change");
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
        // Switched on explicitly, because the default is off now: the claim is that the two switches are
        // independent, not that either of them starts in a particular position.
        Scene noAxes = sceneWith(Geometry.Furniture.DEFAULT.withGridXY(true).withAxes(false));
        assertTrue(noAxes.effectiveFurniture().gridXY(), "the grid went with the axes");

        Scene noGrid = sceneWith(Geometry.Furniture.DEFAULT
                .withGridXY(false).withGridXZ(false).withGridYZ(false));
        assertTrue(noGrid.effectiveFurniture().labels(), "the axis names went with the grid");
    }
}
