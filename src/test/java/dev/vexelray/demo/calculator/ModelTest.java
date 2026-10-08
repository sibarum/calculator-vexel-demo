package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.Test;
import sibarum.cott.calculator.Arithmetic;
import sibarum.cott.calculator.Limits;
import sibarum.cott.calculator.Mode;
import sibarum.cott.calculator.Modeset;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The state model over cott-engine. No GUI, no window, no device. */
class ModelTest {

    @Test
    void aLineGoesOnTheTapeWithItsAnswer() {
        Model model = new Model();
        assertTrue(model.enter("1/0"));
        Doc.Entry e = model.doc().tape().getFirst();
        assertEquals("1/0", e.input());
        assertEquals("= ω", e.answer());
    }

    @Test
    void escapesAreExpanded() {
        Model model = new Model();
        assertTrue(model.enter("\\o"));
        assertEquals("= ω", model.doc().tape().getFirst().answer());
    }

    @Test
    void definitionsCarryToLaterLines() {
        Model model = new Model();
        assertTrue(model.enter("x = 2"));
        assertTrue(model.enter("x + 1"));
        assertEquals("= 3", model.doc().tape().get(1).answer());
    }

    @Test
    void aRefusedLineLeavesTheTapeAloneAndSaysWhy() {
        Model model = new Model();
        assertFalse(model.enter("1 +"));
        assertTrue(model.doc().tape().isEmpty());
        assertFalse(model.doc().error().isEmpty());
        assertTrue(model.enter("1 + 1"));
        assertEquals("", model.doc().error());
    }

    @Test
    void blankLinesAreIgnored() {
        Model model = new Model();
        assertFalse(model.enter("   "));
        assertTrue(model.doc().tape().isEmpty());
        assertEquals("", model.doc().error());
    }

    @Test
    void theArithmeticCanBeChanged() {
        Model model = new Model();
        Mode first = model.doc().mode(Modeset.ARITHMETIC);
        model.set(Arithmetic.IEEE_FLOATING_POINT);
        assertNotEquals(first, model.doc().mode(Modeset.ARITHMETIC));
        assertEquals(Arithmetic.IEEE_FLOATING_POINT, model.doc().mode(Modeset.ARITHMETIC));
    }

    @Test
    void everyModesetStartsWhereTheEngineDoes() {
        Model model = new Model();
        for (Modeset m : Modeset.values()) assertEquals(m.initial(), model.doc().mode(m), m.label());
    }

    @Test
    void theLimitsCanBeChangedAndBoundTheDescent() {
        Model model = new Model();
        assertEquals(Limits.STANDARD, model.doc().mode(Modeset.LIMITS));
        assertTrue(model.enter("cos(1/500)"));
        model.set(Limits.SHALLOW);
        assertEquals(Limits.SHALLOW, model.doc().mode(Modeset.LIMITS));
        assertEquals(Modeset.ARITHMETIC.initial(), model.doc().mode(Modeset.ARITHMETIC));
        assertFalse(model.enter("cos(1/500)"));
        assertTrue(model.doc().error().contains("360"), model.doc().error());
    }

    @Test
    void aCosineCarriesItsCertificate() {
        Model model = new Model();
        assertTrue(model.enter("cos(1/6)"));
        assertTrue(model.doc().tape().getLast().readings().contains("cos(1/6) depth"),
                model.doc().tape().getLast().readings());
    }

    @Test
    void everyChangeReachesTheListener() {
        Model model = new Model();
        AtomicInteger seen = new AtomicInteger();
        model.onChange(doc -> seen.incrementAndGet());
        model.enter("2+2");
        model.set(Arithmetic.IEEE_FLOATING_POINT);
        model.set(Arithmetic.IEEE_FLOATING_POINT);   // already in force: nothing changed, nobody told
        assertEquals(2, seen.get());
    }
}
