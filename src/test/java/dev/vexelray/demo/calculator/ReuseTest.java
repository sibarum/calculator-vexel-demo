package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Putting past lines back in the entry, as pure functions over text. No GUI. */
class ReuseTest {

    private static final List<String> PAST = List.of("1+1", "x^2", "x^2", "1/3");
    private static final int UP = 1, DOWN = -1;

    @Test
    void upStartsAtTheNewestAndGoesBack() {
        assertEquals(3, Reuse.recall(PAST, -1, UP, ""));
        assertEquals(2, Reuse.recall(PAST, 3, UP, "1/3"));
    }

    @Test
    void aRepeatedLineTakesOnePress() {
        assertEquals(0, Reuse.recall(PAST, 2, UP, "x^2"));
        assertEquals(3, Reuse.recall(PAST, 1, DOWN, "x^2"));
    }

    @Test
    void upSkipsWhatTheEntryAlreadySays() {
        assertEquals(2, Reuse.recall(PAST, -1, UP, "1/3"));
    }

    @Test
    void theOldestStaysAndDownPastTheNewestIsTheDraft() {
        assertEquals(0, Reuse.recall(PAST, 0, UP, "1+1"));
        assertEquals(-1, Reuse.recall(PAST, 3, DOWN, "1/3"));
        assertEquals(-1, Reuse.recall(PAST, -1, DOWN, "draft"));
    }

    @Test
    void nothingTypedYetIsNowhereToGo() {
        assertEquals(-1, Reuse.recall(List.of(), -1, UP, ""));
    }

    @Test
    void aLoneValueGoesInBare() {
        assertEquals("x+1", Reuse.join("", 0, "x+1"));
        assertEquals("2·7", Reuse.join("2·", 2, "7"));
        assertEquals("2·ω", Reuse.join("2·", 2, "ω"));
    }

    @Test
    void anythingMoreIsBracketedBesideOtherText() {
        assertEquals("2·(x+1)", Reuse.join("2·", 2, "x+1"));
        assertEquals("2^(1/3)", Reuse.join("2^", 2, "1/3"));
        assertEquals("(1/3)+1", Reuse.join("+1", 0, "1/3"));
        assertEquals("x-(-2)", Reuse.join("x-", 2, "-2"));
    }
}
