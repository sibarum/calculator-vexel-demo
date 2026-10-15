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
        assertEquals("1/0", zero.value());
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

    /** The engine takes only whole-number exponents, so 2^x has values at x = -10, -9, … 10 and nowhere else. */
    @Test
    void aRefusedSampleIsAGap() {
        Graph g = plotted("2^x");
        assertNotNull(g);
        assertEquals(21, g.samples().stream().filter(Graph.Sample::onCurve).count());
        assertEquals(0.5, at(g, "-1").y());
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
        model.set(sibarum.cott.algebra.NumberType.IEEE);
        model.enter("1/x");
        Graph g = model.doc().tape().getFirst().graph();
        assertTrue(at(g, "0").marker());
        assertEquals("∞", at(g, "0").value());
    }

    @Test
    void everyNumberTypePutsARealCurveOnTheAxis() {
        Model model = new Model();
        for (sibarum.cott.algebra.NumberType a : sibarum.cott.algebra.NumberType.values()) {
            model.set(a);
            model.enter("x^2 - 1");
            Graph g = model.doc().tape().getLast().graph();
            assertNotNull(g, a.label());
            assertEquals(8.0, at(g, "3").y(), a.label());
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
        double[] pole = Chart.range(plotted("1/x"));
        assertTrue(pole[1] < 10, "the samples beside 0 should run off the box, not set its height");
        double[] parabola = Chart.range(plotted("x^2"));
        assertTrue(parabola[1] >= 100, "the ends of a parabola are the curve, not outliers");
    }

    @Test
    void aGridStepsInOnesTwosAndFivesAndKeepsZero() {
        assertEquals(new java.math.BigDecimal("0.05"), Graph.Grid.niceStep(0.03));
        assertEquals(new java.math.BigDecimal("0.1"), Graph.Grid.niceStep(0.1));
        assertEquals(new java.math.BigDecimal("2E+2"), Graph.Grid.niceStep(150));
        Graph.Grid g = Graph.Grid.covering(-1.03, 0.97, 21);
        assertEquals(new java.math.BigDecimal("0.1"), g.step());
        assertTrue(g.lo() <= -1.03 && g.hi() >= 0.97);
        boolean zero = false;
        for (int k = 0; k < g.count(); k++) zero |= g.at(k).signum() == 0;
        assertTrue(zero, "0 is on any grid that reaches it");
    }

    @Test
    void sampledAgainTheCurveIsTheSameWhereTheGridsMeet() {
        Model model = new Model();
        model.enter("x^2 - 1");
        Graph tape = model.doc().tape().getLast().graph();
        Graph fine = model.resample(tape, Graph.Grid.covering(2, 4, 201), () -> false);
        assertEquals(8.0, at(fine, "3").y());
        assertEquals(at(tape, "2.5").value(), at(fine, "2.5").value());
        assertEquals(2.0, fine.from());
    }

    @Test
    void aPlotIsSampledAgainInTheModesItWasDrawnIn() {
        Model model = new Model();
        model.enter("1/x");
        Graph tape = model.doc().tape().getLast().graph();
        model.set(sibarum.cott.algebra.NumberType.IEEE);
        Graph again = model.resample(tape, Graph.Grid.covering(-1, 1, 21), () -> false);
        assertEquals("1/0", at(again, "0").value(), "still the Integer reading, not IEEE's infinity");
        assertEquals(sibarum.cott.algebra.NumberType.IEEE,
                model.doc().mode(sibarum.cott.calculator.Modeset.NUMBER_TYPE), "and the setting is left as it was");
        model.enter("1/0");
        assertEquals("= ∞", model.doc().tape().getLast().answer());
    }

    @Test
    void aStaleRequestStops() {
        Model model = new Model();
        model.enter("x");
        Graph tape = model.doc().tape().getLast().graph();
        assertNull(model.resample(tape, Graph.Grid.covering(0, 1, 11), () -> true));
    }

    @Test
    void theNearestSampleIsFoundBySearch() {
        Graph g = plotted("x");
        assertEquals("0.05", PlotExplorer.nearest(g.samples(), 0.06).xText());
        assertEquals("-10", PlotExplorer.nearest(g.samples(), -50).xText());
        assertEquals("10", PlotExplorer.nearest(g.samples(), 50).xText());
    }

    @Test
    void ticksAreCleanDecimals() {
        assertEquals(0.5, Chart.step(2.2));
        assertEquals("0.3", Chart.decimal(0.1 + 0.2, 0.1));
        assertEquals("-20", Chart.decimal(-20.000000001, 10));
    }
}
