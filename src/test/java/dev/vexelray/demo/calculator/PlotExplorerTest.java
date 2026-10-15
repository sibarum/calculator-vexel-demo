package dev.vexelray.demo.calculator;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plot explorer as the application builds it, headlessly: the tree is there with no window, so what popping a plot
 * out does to the view can be read without a device.
 */
class PlotExplorerTest {

    private CalculatorAppWiring wiring;
    private Shell shell;

    @BeforeEach
    void build() {
        wiring = new CalculatorAppWiring();
        shell = VexelApplication.tree(wiring, new String[0]);
    }

    @AfterEach
    void close() {
        shell.disposer().close();
    }

    private Doc.Entry entered(String line) {
        wiring.model().enter(line);
        return wiring.model().doc().tape().getLast();
    }

    @Test
    void nothingIsOnShowUntilAPlotIsPoppedOut() {
        assertNotNull(wiring.plotExplorer());
        assertNull(wiring.plotExplorer().entry());
        assertTrue(wiring.plotExplorer().samples().isEmpty());
    }

    @Test
    void aPoppedOutPlotStartsWhereTheTapeDrewIt() {
        Doc.Entry e = entered("x^2 - 1");
        PlotExplorer explorer = wiring.plotExplorer();
        explorer.open(e);
        assertSame(e, explorer.entry());
        View v = explorer.view();
        assertEquals(Graph.FROM, v.xLo());
        assertEquals(Graph.TO, v.xHi());
        double[] y = Chart.range(e.graph());
        assertEquals(y[0], v.yLo());
        assertEquals(y[1], v.yHi());
    }

    @Test
    void zoomingAndResettingComeBackToTheStart() {
        PlotExplorer explorer = wiring.plotExplorer();
        explorer.open(entered("1/x"));
        View start = explorer.view();
        explorer.zoom(0.5);
        assertEquals(start.width() / 2, explorer.view().width(), 1e-9);
        explorer.reset();
        assertEquals(start, explorer.view());
    }

    @Test
    void aLineWithNoPlotIsIgnored() {
        PlotExplorer explorer = wiring.plotExplorer();
        explorer.open(entered("1 + 1"));
        assertNull(explorer.entry());
    }

    @Test
    void anotherPlotReplacesTheOneOnShow() {
        PlotExplorer explorer = wiring.plotExplorer();
        explorer.open(entered("x"));
        Doc.Entry second = entered("cos(t)");
        explorer.open(second);
        assertSame(second, explorer.entry());
        assertSame(second.graph().samples(), explorer.samples());
    }
}
