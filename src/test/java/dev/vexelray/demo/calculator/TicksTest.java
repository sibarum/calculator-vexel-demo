package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicksTest {

    @Test
    @DisplayName("the default domain gets whole numbers, which is the case that started this")
    void wholeNumbersOverTheDefaultDomain() {
        // Nine even divisions of [-6, 6] gives -5.33, -2.67, 2.67, 5.33. That is what this replaced. The step
        // rounds *up* to the next nice value, which is the choice that keeps the marks few and round rather
        // than many and round -- six labelled marks on an axis is a scale, twelve is a ruler.
        assertArrayEquals(new double[]{-6, -4, -2, 2, 4, 6}, Ticks.between(-6, 6), 1e-9);
    }

    @Test
    @DisplayName("zero is left out — the axes already cross there")
    void zeroIsExcluded() {
        assertTrue(Arrays.stream(Ticks.between(-3, 3)).noneMatch(v -> v == 0));
        assertTrue(Arrays.stream(Ticks.between(-0.4, 0.4)).noneMatch(v -> v == 0));
    }

    @Test
    @DisplayName("every mark is inside the domain, at every scale")
    void marksStayInside() {
        for (double span : new double[]{0.003, 0.5, 1, 7, 12, 250, 1e6}) {
            double[] ticks = Ticks.between(-span, span);
            for (double t : ticks) {
                assertTrue(t >= -span && t <= span, "tick " + t + " escaped [" + -span + ", " + span + "]");
            }
        }
    }

    @Test
    @DisplayName("an asymmetric domain works too — the marks are round, not centred")
    void asymmetricDomain() {
        assertArrayEquals(new double[]{2, 4, 6, 8}, Ticks.between(0.3, 9.4), 1e-9);
    }

    @Test
    @DisplayName("the step is always 1, 2 or 5 times a power of ten")
    void stepIsOneTwoOrFive() {
        for (double span = 0.001; span < 1e5; span *= 1.37) {
            double step = Ticks.step(span, 9);
            double mantissa = step / Math.pow(10, Math.floor(Math.log10(step)));
            assertTrue(Math.abs(mantissa - 1) < 1e-9 || Math.abs(mantissa - 2) < 1e-9
                            || Math.abs(mantissa - 5) < 1e-9 || Math.abs(mantissa - 10) < 1e-9,
                    "step " + step + " for span " + span + " has mantissa " + mantissa);
        }
    }

    @Test
    @DisplayName("an empty or inverted domain has no marks rather than throwing")
    void degenerateDomains() {
        assertEquals(0, Ticks.between(1, 1).length);
        assertEquals(0, Ticks.between(5, -5).length);
    }

    @Test
    @DisplayName("labels share their decimals across an axis, so a row of figures lines up")
    void labelsAreConsistent() {
        assertEquals("1", Ticks.label(1, 1));
        assertEquals("-5", Ticks.label(-5, 1));
        assertEquals("0.5", Ticks.label(0.5, 0.5));
        assertEquals("1.0", Ticks.label(1, 0.5), "a whole number on a half-step axis keeps its decimal");
        assertEquals("0.25", Ticks.label(0.25, 0.25));
        assertEquals("0", Ticks.label(-0.0, 1), "negative zero is zero");
    }
}
