package dev.vexelray.demo.calculator;

import java.util.Locale;

/**
 * Where the marks on an axis go, and what they are called.
 *
 * <h2>Round numbers, not even divisions</h2>
 *
 * <p>Dividing a domain into a fixed number of equal parts is the obvious thing and it is wrong: over
 * {@code [-6, 6]} with nine marks it produces {@code -5.33, -2.67, 2.67, 5.33}, which are exact, useless, and
 * make the axis look like it is measuring something arbitrary. An axis is read by counting along it, so its
 * marks have to land on numbers a reader would have chosen — and then <em>however many of those fit</em> is the
 * count, rather than the other way round.
 *
 * <p>The step is the smallest of {@code 1, 2, 5} times a power of ten that keeps the mark count near the target.
 * That is the standard choice and it is standard because those three are the multiples people subdivide by:
 * halves, fifths and tenths of a decade.
 *
 * <p><b>This is a re-implementation of {@code Framing.tickStep} in {@code vexelray-gui-plot}</b>, which the
 * calculator does not depend on — that module's other half is an evaluator this project does not have yet, and
 * its {@code Camera} is orthographic where the march is a pinhole. Worth knowing so the duplication is a
 * decision rather than an oversight: when the algebra lands and the dependency comes back, this goes.
 */
final class Ticks {

    /** About this many marks on an axis. More is a ruler; fewer is not a scale. */
    private static final int TARGET = 9;

    /**
     * Round values inside {@code [lo, hi]}, ascending, excluding zero.
     *
     * <p>Zero is left out because the axes cross there: a mark on the origin is three marks on top of each
     * other, and a label there names a point every axis already passes through.
     */
    static double[] between(double lo, double hi) {
        if (!(hi > lo)) {
            return new double[0];
        }
        double step = step(hi - lo, TARGET);
        int first = (int) Math.ceil(lo / step);
        int last = (int) Math.floor(hi / step);
        int n = 0;
        for (int i = first; i <= last; i++) {
            if (i != 0) {
                n++;
            }
        }
        double[] out = new double[n];
        int at = 0;
        for (int i = first; i <= last; i++) {
            if (i != 0) {
                out[at++] = i * step;
            }
        }
        return out;
    }

    /** The 1-2-5 step that puts about {@code target} marks across {@code span}. */
    static double step(double span, int target) {
        double raw = span / Math.max(1, target);
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double normalised = raw / magnitude;
        double choice = normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 5 ? 5 : 10;
        return choice * magnitude;
    }

    /**
     * A tick's label.
     *
     * <p>Formatted from the step rather than from the value, so every mark on one axis has the same number of
     * decimals: {@code 0.5, 1, 1.5} reads as three arbitrary numbers where {@code 0.5, 1.0, 1.5} reads as a
     * scale. Trailing zeros are what makes a column of figures a column.
     */
    static String label(double value, double step) {
        String s = String.format(Locale.ROOT, "%." + decimals(step) + "f", value);
        return s.equals("-0") ? "0" : s;
    }

    /**
     * How many decimals it takes to write {@code step} exactly.
     *
     * <p>Counted by trying, rather than derived from {@code -log10(step)}, because that formula is only right
     * for steps that are themselves powers of ten: it gives one decimal for {@code 0.25} and prints
     * {@code "0.3"}, which is a tick labelled with a number it is not at. Six is the ceiling, because past that
     * a plot axis is not the thing that needs fixing.
     */
    private static int decimals(double step) {
        double scaled = Math.abs(step);
        for (int d = 0; d < 6; d++) {
            if (Math.abs(scaled - Math.round(scaled)) < 1e-9) {
                return d;
            }
            scaled *= 10;
        }
        return 6;
    }

    private Ticks() {
    }
}
