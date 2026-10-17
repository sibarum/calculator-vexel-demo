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
        return at(g.samples(), x);
    }

    private static Graph.Sample at(List<Graph.Sample> samples, String x) {
        return samples.stream().filter(s -> s.xText().equals(x)).findFirst().orElseThrow();
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
        Graph g = plotted("(x - x)/(x - x)");
        Graph.Sample two = at(g, "2");
        assertTrue(two.marker());
        assertEquals("0/0", two.value(), "a computed 0 over a computed 0 is a multiple of nothing");
    }

    /** A sampled {@code 0} is the number, not a written {@code 0}, so {@code x/x} there is {@code 0/0}. */
    @Test
    void xOverXAtZeroIsMarked() {
        Graph g = plotted("x/x");
        Graph.Sample zero = at(g, "0");
        assertEquals("0/0", zero.value());
        assertTrue(zero.marker());
        assertEquals(1.0, at(g, "2").y());
    }

    /** {@code (0 + x)^2 = x^2 + 2·0·x + 0^2}: the residual is kept, and each order is a curve of its own. */
    @Test
    void aWrittenZeroStaysAsAResidualAndEachOrderIsACurve() {
        Graph g = plotted("(0+x)^2");
        assertEquals("S(Z(6, 1), S(9, Z(1, 2)))", at(g, "3").value());
        assertEquals(9.0, at(g, "3").y());
        assertEquals(Graph.Order.ONE, at(g, "3").order());
        assertEquals("Z(1, 2)", at(g, "0").value(), "the sampled 0 is the number, so only the written 0 is left");
        List<Graph.Curve> curves = Graph.curves(g.samples());
        assertEquals(List.of(Graph.Order.ONE, Graph.Order.ZERO, new Graph.Order(2)),
                curves.stream().map(Graph.Curve::order).toList());
        assertEquals(0.0, at(curves.get(0).samples(), "0").y());
        assertEquals(0.0, at(curves.get(1).samples(), "0").y());
        assertEquals(1.0, at(curves.get(2).samples(), "3").y());
    }

    /** {@code (3x + 0 + ω)^2 = ω^2 + 6x·ω + 9x^2 + 2 + 6x·0 + 0^2}: five orders, five curves, none of them marked. */
    @Test
    void aSquareOfThreeOrdersIsFiveCurves() {
        Graph g = plotted("(3x+0+1/0)^2");
        assertTrue(g.samples().stream().noneMatch(Graph.Sample::marker));
        List<Graph.Curve> curves = Graph.curves(g.samples());
        assertEquals(List.of(0, 1, -1, 2, -2), curves.stream().map(c -> c.order().power()).toList());
        double[] atTwo = {38, 12, 12, 1, 1};
        double[] atZero = {2, 0, 0, 1, 1};
        for (int i = 0; i < curves.size(); i++) {
            assertEquals(atTwo[i], at(curves.get(i).samples(), "2").y(), 1e-9, curves.get(i).order().multiple());
            assertEquals(atZero[i], at(curves.get(i).samples(), "0").y(), 1e-9, curves.get(i).order().multiple());
        }
    }

    /** {@code 1/0} is {@code ω·1}: on the curve at 1, as a multiple of ω, where the rest are plain numbers. */
    @Test
    void aMultipleOfOmegaStandsAtItsMultiple() {
        Graph g = plotted("1/x");
        Graph.Sample zero = at(g, "0");
        assertTrue(zero.onCurve());
        assertEquals("1/0", zero.value(), "the number 0 sampled, so the Q of a zero denominator");
        assertEquals(1.0, zero.y());
        assertEquals(Graph.Order.OMEGA, zero.order());
        assertEquals(0.5, at(g, "2").y());
        assertEquals(Graph.Order.ONE, at(g, "2").order());
    }

    @Test
    void xOverZeroIsACurveOfMultiplesOfOmega() {
        Graph g = plotted("x/0");
        assertEquals(3.0, at(g, "3").y());
        // (p/q)/0 is p/(q·0) = p/Z(q, 1): the fraction keeps its denominator, so it stands at p/q.
        assertEquals("-1/Z(2, 1)", at(g, "-0.5").value());
        assertEquals(-0.5, at(g, "-0.5").y());
        assertEquals(Graph.Order.OMEGA, at(g, "-0.5").order());
        assertEquals(0.0, at(g, "0").y(), "the number 0 absorbs, so the curve passes through 0");
        assertTrue(g.samples().stream().filter(Graph.Sample::onCurve)
                .allMatch(s -> s.order().equals(Graph.Order.OMEGA)));
    }

    /** Where a later curve stands on an earlier one it is dashed: {@code ω·6x} on {@code 0·6x}, {@code ω²} on {@code 0²}. */
    @Test
    void aCurveOnAnEarlierOneIsDashed() {
        List<Graph.Curve> curves = Graph.curves(plotted("(3x+0+1/0)^2").samples());
        int n = curves.getFirst().samples().size();
        for (int k = 0; k < curves.size(); k++) {
            boolean[] dashed = Chart.coinciding(curves, k);
            int count = 0;
            for (boolean d : dashed) if (d) count++;
            int power = curves.get(k).order().power();
            assertEquals(power < 0 ? n : 0, count, curves.get(k).order().multiple());
        }
    }

    /** {@code (2x + 3)(5x + 7)(x + 0)} is a cubic plus {@code 0} times a quadratic: two curves, one at each order. */
    @Test
    void eachOrderIsACurveOfItsOwn() {
        Graph g = plotted("(2x+3)(5x+7)(x+0)");
        List<Graph.Curve> curves = Graph.curves(g.samples());
        assertEquals(List.of(Graph.Order.ONE, Graph.Order.ZERO), curves.stream().map(Graph.Curve::order).toList());
        assertEquals(10 * 8 + 29 * 4 + 21 * 2, at(curves.get(0).samples(), "2").y(), 1e-9);
        assertEquals(10 * 4 + 29 * 2 + 21, at(curves.get(1).samples(), "2").y(), 1e-9);
        assertEquals(0.0, at(curves.get(0).samples(), "0").y(), "42·0 is 0 at order one");
        assertEquals(21.0, at(curves.get(1).samples(), "0").y());
    }

    /** {@code 1/x} at {@code 0} is {@code ω}, off the curve at order one rather than a {@code 0} on it. */
    @Test
    void aLowerOrderTakesASampleOffTheCurve() {
        List<Graph.Curve> curves = Graph.curves(plotted("1/x").samples());
        assertEquals(List.of(Graph.Order.ONE, Graph.Order.OMEGA), curves.stream().map(Graph.Curve::order).toList());
        assertTrue(Double.isNaN(at(curves.get(0).samples(), "0").y()));
        assertEquals(1.0, at(curves.get(1).samples(), "0").y());
    }

    /** {@code ω·0} is {@code 0} at any order, so a zero of an amber curve is on it rather than breaking it. */
    @Test
    void aZeroTakesTheOrderOfTheCurveItIsOn() {
        Graph g = plotted("(2x+3)(5x+7)/(0x)");
        for (String x : List.of("-1.5", "-1.4")) {
            assertEquals(0.0, at(g, x).y());
            assertEquals(Graph.Order.OMEGA, at(g, x).order(), x);
        }
        assertEquals(Graph.Order.ONE, at(plotted("x - x"), "2").order(), "with no curve of an order, it is one");
    }

    @Test
    void zeroAndOmegaWrittenInZAreCurvesOfTheirOrder() {
        Graph zero = plotted("Z(1, 1)x");
        assertEquals(3.0, at(zero, "3").y());
        assertEquals(0.5, at(zero, "0.5").y());
        assertEquals(Graph.Order.ZERO, at(zero, "3").order());
        Graph omega = plotted("x/Z(1, 1)");
        assertEquals(-2.0, at(omega, "-2").y());
        assertEquals(Graph.Order.OMEGA, at(omega, "-2").order());
        Graph square = plotted("Z(1, 2)x");
        assertEquals(3.0, at(square, "3").y());
        assertEquals(new Graph.Order(2), at(square, "3").order());
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
        model.enter("2^(x/2)");
        Doc.Entry e = model.doc().tape().getFirst();
        assertNull(e.graph());
        assertTrue(e.readings().startsWith("nothing to plot"), e.readings());
    }

    @Test
    void aPowerInAnglesIsMarkedWhereItHasNoRealValue() {
        Model model = new Model();
        model.enter("x^(1/2)");
        Graph g = model.doc().tape().getFirst().graph();
        assertTrue(at(g, "-1").marker(), "(-1)^(1/2) is i, an L");
        assertTrue(at(g, "0").marker(), "0^(1/2) is a zero magnitude turned half a turn");
        assertTrue(at(g, "1").marker());
        assertTrue(at(g, "2").refused(), "2 has no exact turn");
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
