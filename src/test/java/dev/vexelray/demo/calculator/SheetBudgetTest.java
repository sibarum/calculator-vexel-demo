package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Canvas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grid sheet against the buffer it is drawn into.
 *
 * <h2>Why this is a test and not a comment</h2>
 *
 * <p>{@code PanelTechnique} is handed a ceiling in floats and <b>refuses a batch that does not fit</b> —
 * which is the right behaviour and an invisible one: the grid would simply stop being drawn, on some scenes
 * and not others, with nothing in the picture to say why. The sheet is no longer "a few dozen strokes": every
 * line is cut into pieces so it can fade, and how many lines there are depends on the domain, the window and
 * the ruler's own density. So the ceiling is checked here, against the worst scene the controls can ask for,
 * rather than trusted.
 *
 * <p>23 floats a vertex and six vertices a quad is {@code Canvas}'s own arithmetic, and
 * {@link Canvas#vertexCount()} is what the technique measures, so the count below is the real one rather than
 * an estimate of it.
 */
class SheetBudgetTest {

    /** What a vertex costs in the canvas's buffer, as {@code PanelTechnique} counts it. */
    private static final int FLOATS_PER_VERTEX = 23;

    @Test
    @DisplayName("the densest sheet the controls can ask for still fits in the buffer")
    void theSheetFitsItsBuffer() {
        int worst = 0;
        String where = "";
        // The two things that multiply lines: how finely the input axis is graduated (a narrow domain puts
        // its marks closer together in the box, and they carry on to the sheet's edge at that pitch) and how
        // deep the turn window is (the ruler fills the whole sheet once the box is inside one decade).
        for (double span : new double[]{12, 1, 0.35, 0.1, 0.02}) {
            for (double magnification : new double[]{1, 10, 1000, 1e6, 1e11}) {
                Scene scene = Scene.with(Scene.initial(), w -> w
                        .x0(-span / 2).x1(span / 2)
                        .window(t -> Turns.Window.WHOLE.magnifiedBy(magnification)));
                int floats = draw(scene);
                if (floats > worst) {
                    worst = floats;
                    where = "domain " + span + ", magnification " + magnification;
                }
            }
        }
        assertTrue(worst <= Grid.XY.floats(),
                "the sheet overran its buffer at " + where + ": " + worst + " floats of " + Grid.XY.floats());
    }

    /** How much vertex data one scene's sheet produces. */
    private static int draw(Scene scene) {
        Geometry.Built built = Geometry.of(scene.reading(), scene.x0(), scene.x1(), scene.samples(),
                scene.effectiveFurniture(), scene.lineWidth(), scene.ramp(), scene.window());
        Canvas canvas = new Canvas(Grid.XY.width(), Grid.XY.height()).begin();
        Grid.XY.draw(canvas, built.furniture(), built.marks());
        return canvas.vertexCount() * FLOATS_PER_VERTEX;
    }
}
