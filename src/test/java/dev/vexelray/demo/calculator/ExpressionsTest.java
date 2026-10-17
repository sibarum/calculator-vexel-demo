package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.Test;
import sibarum.cott.algebra.Pair;
import sibarum.cott.algebra.TractionAlgebra;
import sibarum.cott.calculator.Calculator;
import sibarum.cott.calculator.Result;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A value written as an expression, read through the plain writing. */
class ExpressionsTest {

    private static String written(Calculator c, String line) {
        Result.Value v = (Result.Value) c.enter(line);
        return Expressions.of(v.value(), v.constructed(), Expressions.TEXT);
    }

    private static String written(String line) {
        return written(new Calculator(), line);
    }

    @Test
    void aSumOfOrdersIsWrittenAsPowersOfZero() {
        assertEquals("6·0 + 9 + 0^{2}", written("(0+3)^2"));
        assertEquals("ω + 1", written("ω + 1"));
        assertEquals("0", written("0"));
        assertEquals("−0", written("-0"));
        assertEquals("3·0", written("3·0"));
        assertEquals("ω^{2}", written("ω·ω"));
    }

    @Test
    void aQuotientIsAFraction() {
        assertEquals("{1}/{1}·ω", written("1/0"));
        assertEquals("{6}/{3}", written("6/3"));
        assertEquals("−{1}/{2·0}", written("(-1/2)/0"));
        assertEquals("{1}/{1 + 0}", written("1/(1 + 0)"));
    }

    @Test
    void aComplexNumberIsItsRealPartPlusI() {
        assertEquals("4 + 3i", written("4 + 3i"));
        assertEquals("4 − 3i", written("4 - 3i"));
        assertEquals("i", written("i"));
        assertEquals("{9}/{2} + 3i", written("1/2 + (4 + 3i)"));
    }

    @Test
    void aSumIsFencedAsAFactor() {
        Object onePlusZero = new Pair<>(TractionAlgebra.S, BigInteger.ONE, new Pair<>(TractionAlgebra.Z, BigInteger.ONE,
                BigInteger.ONE));
        assertEquals("(1 + 0)·ω", Expressions.of(new Pair<>(TractionAlgebra.Z, onePlusZero, BigInteger.valueOf(-1)),
                false, Expressions.TEXT));
    }

    @Test
    void aLineWrittenWithConstructorsIsAnsweredInThem() {
        assertEquals("Q(2, 3)", written("Q(2, 3)"));
        assertEquals("S(Z(1, 0), Z(1, 1))", written("Z(1, 0) + (Z(1, 0) - Z(1, 0))"));
    }
}
