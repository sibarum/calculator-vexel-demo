package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The explorer's view of plot space: the moves it can make and the pixels it maps to. No GUI. */
class ViewTest {

    private static final View UNIT = new View(-10, 10, -5, 5);

    @Test
    void plotSpaceMapsToPixelsAndBack() {
        assertEquals(0, UNIT.px(-10, 200));
        assertEquals(100, UNIT.px(0, 200));
        assertEquals(0, UNIT.py(5, 100));
        assertEquals(100, UNIT.py(-5, 100));
        assertEquals(2.5, UNIT.x(UNIT.px(2.5, 300), 300), 1e-12);
        assertEquals(-1.25, UNIT.y(UNIT.py(-1.25, 80), 80), 1e-12);
    }

    @Test
    void zoomingKeepsThePointUnderThePointer() {
        View v = UNIT.zoomed(0.5, 0.25, 4, 1);
        assertEquals(10, v.width(), 1e-12);
        assertEquals(2.5, v.height(), 1e-12);
        assertEquals(UNIT.px(4, 200), v.px(4, 200), 1e-9);
        assertEquals(UNIT.py(1, 100), v.py(1, 100), 1e-9);
    }

    @Test
    void aSpanStopsAtItsLimits() {
        View v = UNIT;
        for (int i = 0; i < 200; i++) v = v.zoomed(0.1, 1, 0, 0);
        assertEquals(View.NARROWEST, v.width(), 1e-15);
        for (int i = 0; i < 200; i++) v = v.zoomed(10, 1, 0, 0);
        assertEquals(View.WIDEST, v.width(), 1);
    }

    @Test
    void equalAxesKeepTheMiddle() {
        View v = new View(0, 40, 9, 11).aspect(400, 100);
        assertEquals(10, v.height(), 1e-12);
        assertEquals(10, (v.yLo() + v.yHi()) / 2, 1e-12);
    }

    @Test
    void panningMovesBothEnds() {
        View v = UNIT.panned(1, -2);
        assertEquals(-9, v.xLo());
        assertEquals(11, v.xHi());
        assertEquals(-7, v.yLo());
    }

    @Test
    void aViewHasSomeSize() {
        assertThrows(IllegalArgumentException.class, () -> new View(1, 1, 0, 1));
    }
}
