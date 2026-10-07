package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plotting from the tape's side: which lines get a curve, and what the samples say. No GUI. */
class GraphTest {

    private static Graph plotted(String... lines) {
        Model model = new Model();
        for (String line : lines) model.enter(line);
        List<Doc.Entry> tape = model.doc().tape();
        return tape.getLast().graph();
    }

    private static Graph.Sample at(Graph g, String x) {
        return g.samples().stream().filter(s -> s.xText().equals(x)).findFirst().orElseThrow();
    }

    @Test
    void oneFreeNameIsPlottedOverIt() {
        Graph g = plotted("x^2 - 1");
        assertNotNull(g);
        assertEquals("x", g.name());
        assertEquals(401, g.samples().size());
        assertEquals(8.0, at(g, "3").y());
        assertEquals(-0.75, at(g, "0.5").y());
    }

    @Test
    void whereThereIsNoRealNumberTheValueIsMarked() {
        Graph g = plotted("1/x");
        Graph.Sample zero = at(g, "0");
        assertTrue(zero.marker());
        assertEquals("ω", zero.value());
        assertEquals(0.5, at(g, "2").y());
    }

    @Test
    void aDefinedNameIsNotFree() {
        assertNull(plotted("x = 2", "x + 1"));
        assertNotNull(plotted("a = 3", "a·t"));
    }

    @Test
    void twoFreeNamesAreNotPlotted() {
        Model model = new Model();
        model.enter("x + y");
        Doc.Entry e = model.doc().tape().getFirst();
        assertNull(e.graph());
        assertTrue(e.readings().contains("nothing to plot"));
    }

    /** The engine takes only whole-number exponents, so 2^x has values at x = 0, 1, … 10 and nowhere else. */
    @Test
    void aRefusedSampleIsAGap() {
        Graph g = plotted("2^x");
        assertNotNull(g);
        assertEquals(11, g.samples().stream().filter(Graph.Sample::onCurve).count());
        assertEquals(1024.0, at(g, "10").y());
        assertTrue(at(g, "0.5").refused());
    }

    @Test
    void allRefusedIsNoPlot() {
        Model model = new Model();
        model.enter("x^(1/2)");
        Doc.Entry e = model.doc().tape().getFirst();
        assertNull(e.graph());
        assertTrue(e.readings().startsWith("nothing to plot"), e.readings());
    }

    @Test
    void ieeeMarksInfinityToo() {
        Model model = new Model();
        while (!model.doc().arithmetic().startsWith("IEEE")) model.nextArithmetic();
        model.enter("1/x");
        Graph g = model.doc().tape().getFirst().graph();
        assertTrue(at(g, "0").marker());
        assertEquals("∞", at(g, "0").value());
    }

    @Test
    void everyArithmeticPutsARealCurveOnTheAxis() {
        Model model = new Model();
        for (int i = 0; i < sibarum.cott.calculator.Arithmetic.values().length; i++) {
            model.enter("x^2 - 1");
            Graph g = model.doc().tape().getLast().graph();
            assertNotNull(g, model.doc().arithmetic());
            assertEquals(8.0, at(g, "3").y(), model.doc().arithmetic());
            model.nextArithmetic();
        }
    }

    @Test
    void cosineIsPlottedOnTheUnitCircle() {
        Graph g = plotted("cos(x)");
        assertNotNull(g);
        assertEquals(1.0, at(g, "1").y(), 1e-12);
        assertEquals(0.0, at(g, "0.25").y(), 1e-12);
        assertEquals(-1.0, at(g, "0.5").y(), 1e-12);
    }

    @Test
    void theFrameIgnoresThePoleButKeepsAParabola() {
        double[] pole = Plot.range(plotted("1/x"));
        assertTrue(pole[1] < 10, "the samples beside 0 should run off the box, not set its height");
        double[] parabola = Plot.range(plotted("x^2"));
        assertTrue(parabola[1] >= 100, "the ends of a parabola are the curve, not outliers");
    }

    @Test
    void ticksAreCleanDecimals() {
        assertEquals(0.5, Plot.step(2.2));
        assertEquals("0.3", Plot.decimal(0.1 + 0.2, 0.1));
        assertEquals("-20", Plot.decimal(-20.000000001, 10));
    }
}
