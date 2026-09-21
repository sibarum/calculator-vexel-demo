package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnsTest {

    /** The sheet reaches this far out of the box, and the ruler is generated that far. */
    private static final double LIMIT = 3 * Geometry.BOX;

    private static final double GAP = 0.06;

    @Test
    @DisplayName("the whole circle is the picture the chart has always drawn")
    void wholeIsTheIdentity() {
        Turns.Window whole = Turns.Window.WHOLE;

        assertEquals(1, whole.magnification(), 1e-12);
        // From just inside the cut: the two ends of the axis are one turn, and the half turn belongs to the
        // top of it. Everything between is the mapping this chart has always used.
        for (double theta = -Math.PI + 1e-9; theta <= Math.PI; theta += Math.PI / 32) {
            assertEquals(theta / Geometry.TURN * Geometry.BOX, whole.at(theta), 1e-12,
                    "the unmagnified axis moved");
        }
    }

    @Test
    @DisplayName("a height and a turn are the same fact, read either way round")
    void theMapRoundTrips() {
        Turns.Window w = new Turns.Window(0.4, Math.PI / 1000);

        for (double at = -Geometry.BOX; at <= Geometry.BOX; at += Geometry.BOX / 16) {
            assertEquals(at, w.at(w.turnAt(at)), 1e-12, "a height did not survive the trip through a turn");
        }
    }

    @Test
    @DisplayName("the difference is taken the short way round, so the cut is not a cliff")
    void theCutIsNotACliff() {
        Turns.Window w = new Turns.Window(Math.PI - 0.01, Math.PI / 100);

        // A turn just the other side of the half turn is just the other side of the centre, not a box away.
        assertEquals(Geometry.BOX * (0.02 / (Math.PI / 100)), w.at(-Math.PI + 0.01), 1e-9);
    }

    @Test
    @DisplayName("every rung on the ruler is an entry the field accepts, standing where it is written")
    void everyRungIsTypeable() {
        for (Turns.Window w : List.of(Turns.Window.WHOLE,
                new Turns.Window(0, Math.PI / 100),
                new Turns.Window(Math.PI / 4, Math.PI / 10_000),
                new Turns.Window(-2.8, Math.PI / 1000),
                new Turns.Window(2.0, Math.PI / 30))) {
            List<Turns.Rung> ladder = Turns.ladder(w, LIMIT, GAP);
            assertFalse(ladder.isEmpty(), "a window with room in it was given no graduations at all");
            for (Turns.Rung rung : ladder) {
                Algebra.Reading back = Algebra.read(rung.name());
                assertTrue(back.drawsMarker(), rung.name() + " is a label the field would not read");
                double[] at = back.coordinates();
                assertEquals(rung.theta(), Math.atan2(at[1], at[0]), 1e-9,
                        rung.name() + " is written at a turn it does not stand at");
            }
        }
    }

    @Test
    @DisplayName("the reversed half is spelled from _1, because a minus sign would move it")
    void theReversedHalfIsNotAMinusSign() {
        // The four quarters at one magnitude, as the ruler writes them. This is the table the class note
        // argues for, and it is here so that a change to the spelling has to be a change to a test.
        assertEquals(Math.atan(10), turnOf("10"), 1e-12);
        assertEquals(-Math.atan(10), turnOf("-10"), 1e-12);
        assertEquals(Math.PI - Math.atan(10), turnOf("_1·10"), 1e-12);
        assertEquals(-(Math.PI - Math.atan(10)), turnOf("-_1·10"), 1e-12);

        // And the thing that makes the third and fourth necessary: the minus sign does not reach that half.
        assertEquals(-Math.atan(0.1), turnOf("-1÷10"), 1e-12);
        assertEquals(Math.PI - Math.atan(0.1), turnOf("_1÷10"), 1e-12);
    }

    @Test
    @DisplayName("nothing crowds anything, including the eight names that were there first")
    void nothingCrowds() {
        for (Turns.Window w : List.of(Turns.Window.WHOLE,
                new Turns.Window(0, Math.PI / 2),
                new Turns.Window(Math.PI / 2, Math.PI / 5000),
                new Turns.Window(-1.0, Math.PI / 7))) {
            List<Turns.Rung> ladder = Turns.ladder(w, LIMIT, GAP);
            for (int i = 1; i < ladder.size(); i++) {
                assertTrue(ladder.get(i).at() - ladder.get(i - 1).at() >= GAP - 1e-12,
                        ladder.get(i).name() + " stands on top of " + ladder.get(i - 1).name());
            }
            for (Turns.Rung rung : ladder) {
                for (Algebra.Named named : Algebra.NAMED) {
                    double named_ = w.at(named.theta());
                    assertTrue(Math.abs(named_ - rung.at()) >= GAP - 1e-12 || Math.abs(named_) > LIMIT,
                            rung.name() + " crowds the rule at " + named.name());
                }
            }
        }
    }

    @Test
    @DisplayName("the ruler stays on the sheet, and does not draw the circle a second time")
    void theRulerStaysOnTheSheet() {
        for (Turns.Rung rung : Turns.ladder(Turns.Window.WHOLE, LIMIT, GAP)) {
            assertTrue(Math.abs(rung.at()) <= Geometry.BOX,
                    rung.name() + " is off the end of an axis that is a whole circle");
        }
    }

    @Test
    @DisplayName("magnifying is what puts the fineprint on the axis")
    void magnifyingDeepensTheRuler() {
        // At the whole circle there is no room near the zero rule for a thousandth, and at a thousand times
        // there is. That is the entire feature, as one assertion.
        assertFalse(names(Turns.Window.WHOLE).contains("1÷1000"));
        assertTrue(names(Turns.Window.WHOLE.magnifiedBy(1000)).contains("1÷1000"));

        // A decade at a time, as the window closes on the zero rule -- and a decade arrives a magnification
        // or so after it first fits on the sheet, because a rung that close to a named rule is crowded out
        // by it rather than drawn on top of it.
        assertTrue(names(Turns.Window.WHOLE.magnifiedBy(100)).contains("1÷100"));
        assertTrue(names(Turns.Window.WHOLE.magnifiedBy(100)).contains("5÷100"));

        // And a window narrower than a mantissa step is still graduated -- by the fill ladder, since there is
        // no k × 10ⁿ anywhere inside it. This is the case that says the log ruler alone is not enough.
        Turns.Window sliver = new Turns.Window(Math.atan(0.3555), 2e-4);
        assertFalse(Turns.ladder(sliver, LIMIT, GAP).isEmpty(), "a sliver between two mantissas has no ruler");
    }

    @Test
    @DisplayName("the reciprocal arc is the same ruler, which is what 1÷x does to the picture")
    void omegaHasTheSameRulerAsZero() {
        List<String> near = names(new Turns.Window(Math.PI / 2, Math.PI / 1000));

        assertTrue(near.contains("1000"), "the arc below ω is not graduated in decades");
        assertTrue(near.contains("_1·1000"), "the reversed arc above ω is not graduated in decades");
    }

    @Test
    @DisplayName("a window cannot be magnified past what a double can resolve")
    void magnificationIsBounded() {
        Turns.Window deep = Turns.Window.WHOLE.magnifiedBy(1e30);

        assertEquals(Turns.FINEST, deep.span(), 0);
        assertTrue(deep.magnification() > 1e11, "the floor on the span is also a ceiling on the zoom");
        assertEquals(Math.PI, Turns.Window.WHOLE.magnifiedBy(0.001).span(), 1e-12,
                "zooming out past the circle would draw it twice");
    }

    private static List<String> names(Turns.Window w) {
        return Turns.ladder(w, LIMIT, GAP).stream().map(Turns.Rung::name).toList();
    }

    private static double turnOf(String entry) {
        double[] at = Algebra.read(entry).coordinates();
        return Math.atan2(at[1], at[0]);
    }
}
