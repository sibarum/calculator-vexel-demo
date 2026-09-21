package dev.vexelray.demo.calculator;

import java.util.ArrayList;
import java.util.List;

/**
 * The vertical axis: which part of the circle is on screen, and where the graduations on it fall.
 *
 * <h2>Why the turn axis needs a window at all</h2>
 *
 * <p>The axis is bounded — {@code -π..π} onto {@code -BOX..BOX} — and that is the property the whole chart
 * rests on, so nothing here fits, crops or rescales anything. What it adds is <b>which arc of the circle the
 * box is showing</b>, which is a different question from how tall the box is: at {@link Window#WHOLE} it is
 * the entire circle and the picture is exactly what it was, and at a tenth of it the same box shows a tenth
 * of the turns at ten times the size.
 *
 * <p><b>This is the vertical counterpart of the domain controls</b>, and that is the argument for doing it
 * here rather than with the camera. The horizontal axis has always been windowed — {@code x0..x1} say which
 * inputs the box shows — and the vertical one was the only axis with no such control, so reading anything
 * finer than the picture meant flying the camera at it. A camera cannot do this job: the eye would have to
 * stand a hundred thousand times closer to read three decades, at which point the world coordinates near the
 * pivot are {@code 1e-6} apart and every float in the march, the panel and the projection is out of
 * significant digits. A window is arithmetic on {@code θ} before anything is placed, so the box stays the
 * same size and the numbers stay the size they always were.
 *
 * <h2>Why the graduations are a log ruler</h2>
 *
 * <p>A value is {@code v = tan θ}, so the turn axis is a {@code tan} scale and decades of {@code v} pile up
 * geometrically at the named points: {@code 1÷10} stands 0.057 box units off the {@code 0} rule,
 * {@code 1÷100} stands 0.0057 off it, {@code 1÷1000} a seventh of a pixel. That is not a defect to be
 * corrected — it is what an oriented projective value <em>is</em>, and the {@code 0} and {@code ω} rules are
 * the two ends of the same ruler — but it does mean the axis has fineprint, and fineprint wants a ruler
 * printed on it rather than eight names and a lot of blank.
 *
 * <p>So: the ordinary log ruler, {@code k × 10ⁿ}, {@code k} in {@code 1..9}, placed by {@code atan} and
 * repeated into all four quarters of the circle. Between {@code 0} and {@code 1} it counts down —
 * {@code 1÷10}, {@code 1÷100} — and between {@code 1} and {@code ω} it counts up, which is the same ruler
 * read through a reciprocal, because {@code 1÷x} is the map that swaps those two arcs.
 *
 * <h2>Every rung is an entry, including the reversed ones</h2>
 *
 * <p>The rule the eight names already follow: <b>a label on this axis is something that can be typed back
 * into the field</b>. An ordinary rung is easy — {@code 3÷100} parses to {@code T(3,100)} and lands exactly
 * where it is written. The reversed half of the circle is the interesting case, because {@code -3÷100} is
 * <em>not</em> it: {@code Folding.ORDINARY} turns the numerator, so a minus sign moves a value to the other
 * ordinary quarter and never to the reversed one. The reversed rungs are written from {@code _1}, which is
 * the model's own name for the reversed unit: {@code _1·30} is {@code T(30,-1)} and {@code _1÷10} is
 * {@code T(1,-10)}, which are where the ruler between {@code ω} and {@code _0} actually stands.
 *
 * <p>{@link #ladder} is the whole of that, and {@code TurnsTest} parses every rung it produces back through
 * {@link Algebra} and checks it lands on its own height. A label that cannot be typed back is a bug here and
 * not a cosmetic one.
 */
final class Turns {

    /** A whole circle, in radians. Half of it is the axis's own reach either way. */
    private static final double CIRCLE = 2 * Math.PI;

    /**
     * Which arc of the circle the box shows.
     *
     * <p>Two numbers rather than a low and a high, because the pair a reader manipulates is "where am I" and
     * "how far in am I" — magnifying moves one of them and leaves the other alone, where a low/high pair has
     * to move both and can be handed a crossed-over interval. It is also what makes {@link #WHOLE} the
     * identity rather than a special case: at {@code span = π} the map below is the one the chart has always
     * used.
     *
     * @param centre which turn is at the middle of the box, in radians
     * @param span   how far either way the box reaches, in radians — {@code π} is the whole circle
     */
    record Window(double centre, double span) {

        /** The whole circle, cut at the half turn: what the plot opens on, and what it did before this existed. */
        static final Window WHOLE = new Window(0, Math.PI);

        /**
         * How much finer the axis reads than it does at {@link #WHOLE}.
         *
         * <p>The number a magnification control is in, and the number worth quoting in a readout: at 1 the
         * box is a circle, at 1000 it is a thousandth of one.
         */
        double magnification() {
            return Math.PI / span;
        }

        /**
         * Where a turn stands in the box, in the box's own coordinates.
         *
         * <p><b>Unbounded on purpose.</b> At {@link #WHOLE} nothing can come back outside {@code ±BOX}, which
         * is the old promise and it survives; under magnification a turn outside the window answers a height
         * outside the box, and the callers that draw off the edge — the grid sheet, which fades out there —
         * want that number rather than a clamp. What must not happen is a <em>wrap</em>: the difference is
         * taken the short way round, so a turn just past the cut reads just past the edge and not a whole box
         * away from where it stands.
         */
        double at(double theta) {
            return wrap(theta - centre) / span * Geometry.BOX;
        }

        /** The turn at a height in the box: {@link #at} read backwards, for a gesture that points at one. */
        double turnAt(double height) {
            return wrap(centre + height / Geometry.BOX * span);
        }

        /** This window magnified about its own centre, clamped to the circle and to what a double can resolve. */
        Window magnifiedBy(double factor) {
            return new Window(centre, Math.clamp(span / factor, FINEST, Math.PI));
        }

        /** This window moved to sit on {@code theta}, at the same magnification. */
        Window centredOn(double theta) {
            return new Window(wrap(theta), span);
        }

        /** Whether a turn is inside the box at all. */
        boolean holds(double theta) {
            return Math.abs(wrap(theta - centre)) <= span;
        }
    }

    /**
     * The finest window that is worth offering, in radians.
     *
     * <p>A limit of the arithmetic rather than a taste: {@code θ} is a double, and two turns closer together
     * than about {@code 1e-16} radians are one number. Stopping at {@code 1e-12} leaves four orders of margin,
     * which is where the rungs are still exactly placed rather than nearly — and it is twelve decades of
     * fineprint, which is more than the sampling can put a curve through anyway.
     */
    static final double FINEST = 1e-12;

    /**
     * One graduation on the turn axis.
     *
     * @param theta where it stands on the circle, in radians
     * @param at    the same, as a height in the box — {@link Window#at} of {@link #theta}
     * @param name  what it is called, which is an expression the field accepts
     * @param rank  how important it is: {@code 0} a decade, {@code 1} a half, {@code 2} the rest
     */
    record Rung(double theta, double at, String name, int rank) {
    }

    /**
     * The ruler, for one window.
     *
     * <p><b>Placed by importance and not by position</b>, which is the only part of this with a choice in it.
     * A ruler that emits everything and lets the drawing sort it out draws a black band wherever the scale is
     * dense; one that picks a step and emits every multiple of it has to pick a step, and there is no step
     * that is right at both ends of a {@code tan} scale. So the candidates are offered decades first, then
     * halves, then the rest, and one is kept only where nothing is already within {@code gap} of it. The
     * coarse ruler therefore always survives and the fine one fills in wherever there is room — which is what
     * a log ruler looks like when it is printed properly, and it needs no step at all.
     *
     * <p>The eight named turns are placed first and are <em>not</em> returned: they are already drawn, as
     * rules right across, and what they do here is claim their space so no rung crowds one.
     *
     * @param window what the box is showing
     * @param limit  how far outside the box to keep placing, in box units — the sheet fades out there, and a
     *               ruler that stopped at the box edge would fade out with nothing in it
     * @param gap    the closest two graduations may stand, in box units
     */
    static List<Rung> ladder(Window window, double limit, double gap) {
        List<Double> taken = new ArrayList<>();
        for (Algebra.Named named : Algebra.NAMED) {
            double at = window.at(named.theta());
            if (Math.abs(at) <= limit) {
                taken.add(at);
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Half half : Half.values()) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                decades(candidates, half, sign);
                between(candidates, window, limit, half, sign);
            }
        }
        candidates.sort((a, b) -> Integer.compare(a.rank(), b.rank()));
        List<Rung> out = new ArrayList<>();
        for (Candidate candidate : candidates) {
            place(out, taken, window, limit, gap, candidate);
        }
        out.sort((a, b) -> Double.compare(a.at(), b.at()));
        return List.copyOf(out);
    }

    /**
     * A graduation that has been offered but not yet given room.
     *
     * <p>The magnitude is {@code mantissa × 10^decade} and it is kept in those two integers all the way to
     * the label, which is what makes a rung's name exact: a name is written from the pair, and the turn is
     * {@code atan2} of the same pair, so the two cannot disagree by a rounding.
     */
    private record Candidate(long mantissa, int decade, Half half, int sign, int rank) {

        /** The turn, taken from the pair the name will be written from rather than from a divided double. */
        double theta() {
            double p = decade >= 0 ? mantissa * Math.pow(10, decade) : mantissa;
            double q = decade >= 0 ? 1 : Math.pow(10, -decade);
            return sign * half.turn(p, q);
        }
    }

    /**
     * The log ruler: {@code k × 10ⁿ} for every decade, offered decades first.
     *
     * <p>This is the ruler that is right when the box spans decades, which is most of the time — the whole
     * circle spans all of them.
     */
    private static void decades(List<Candidate> into, Half half, int sign) {
        for (int decade = DECADES; decade >= -DECADES; decade--) {
            for (int k : DIGITS) {
                // k × 10⁰ = 1 is the named point at the eighth turn. It is drawn as a rule and has already
                // claimed its space, so the ruler does not offer it a second time.
                if (k != 1 || decade != 0) {
                    into.add(new Candidate(k, decade, half, sign, rank(k)));
                }
            }
        }
    }

    /**
     * The ruler under the ruler: a 1-2-5 ladder across whatever magnitudes are actually on the sheet.
     *
     * <p><b>Nine rungs a decade runs out.</b> Magnify past about a ninth of a decade and the box can sit
     * between two mantissas — {@code 0.352} to {@code 0.359} has no {@code k × 10ⁿ} in it at all — and an
     * axis with no graduations on it is exactly the thing this was built to fix. A log scale is locally
     * linear, so what belongs there is the ordinary linear ruler {@link Ticks} already draws across the input
     * axis, at a step chosen 1-2-5 for the magnitudes in view.
     *
     * <p>Offered last, so it fills in behind the decades rather than displacing them: when the box spans
     * decades these candidates are mostly crowded out, which is correct, because a linear step is the wrong
     * ruler for a range that spans decades and the right one for a range that does not.
     */
    private static void between(List<Candidate> into, Window window, double limit, Half half, int sign) {
        double reach = Math.min(Math.PI, window.span() * limit / Geometry.BOX);
        // A visible arc within an eighth of the circle is short enough that a family arc meets it in one
        // piece, which is what lets the range below be two numbers. Anything wider is spanning decades and
        // the log ruler is serving it already.
        if (reach >= Math.PI / 8) {
            return;
        }
        for (int turn = -1; turn <= 1; turn++) {
            double lo = window.centre() + turn * CIRCLE - reach;
            double hi = window.centre() + turn * CIRCLE + reach;
            double[] arc = half.arc(sign);
            double from = Math.max(lo, arc[0]);
            double to = Math.min(hi, arc[1]);
            if (from >= to) {
                continue;
            }
            ladder(into, half, sign, half.magnitude(from, sign), half.magnitude(to, sign));
        }
    }

    /** A 1-2-5 ladder across a magnitude range, in exact integer mantissas. */
    private static void ladder(List<Candidate> into, Half half, int sign, double a, double b) {
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        if (!(hi > lo) || hi <= 0 || Double.isInfinite(hi)) {
            return;
        }
        double raw = (hi - lo) / Ticks.TARGET;
        int decade = (int) Math.floor(Math.log10(raw));
        double normalised = raw / Math.pow(10, decade);
        int step = normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 5 ? 5 : 10;
        if (step == 10) {
            step = 1;
            decade++;
        }
        double unit = step * Math.pow(10, decade);
        long first = (long) Math.ceil(lo / unit);
        long last = (long) Math.floor(hi / unit);
        if (last - first > 4L * Ticks.TARGET) {
            return;                                 // the range is not what it was taken for; the log ruler has it
        }
        for (long n = first; n <= last; n++) {
            long mantissa = n * step;
            int e = decade;
            if (mantissa <= 0) {
                continue;
            }
            while (mantissa % 10 == 0) {            // 350×10⁻³ is 35×10⁻², and the shorter name is the one to write
                mantissa /= 10;
                e++;
            }
            if (Long.toString(mantissa).length() <= NAME_DIGITS) {
                into.add(new Candidate(mantissa, e, half, sign, FILL));
            }
        }
    }

    /** One candidate, kept if there is room for it. */
    private static void place(List<Rung> out, List<Double> taken, Window window, double limit, double gap,
                              Candidate candidate) {
        double theta = candidate.theta();
        // The whole circle is at most one box either way at WHOLE and more than that under magnification, so
        // this one test is also what stops the ruler drawing the circle a second time above the top: a turn
        // that is off the sheet is off the sheet whether it is off the end of the axis or round the back of
        // it.
        double at = window.at(theta);
        if (Math.abs(at) > limit) {
            return;
        }
        // Wider apart the further out it stands. Inside the box a graduation is something to read, and the
        // gap is the one that keeps two numbers from overlapping; outside it the sheet is on its way to
        // transparent and the ruler is texture, so the same density would be a few thousand quads spent
        // where nothing is legible. Proportional rather than stepped, so there is no ring in the picture
        // where the ruler visibly changes its mind.
        double room = gap * (1 + Math.max(0, Math.abs(at) - Geometry.BOX) / Geometry.BOX);
        for (double already : taken) {
            if (Math.abs(already - at) < room) {
                return;
            }
        }
        taken.add(at);
        out.add(new Rung(theta, at, name(candidate), Math.min(candidate.rank(), FILL - 1)));
    }

    /**
     * Which half of the circle a rung is in — and it is a half rather than a quadrant, because each of these
     * carries the whole ruler: the magnitude runs {@code 0..∞} across a quarter turn either side of the
     * eighth-turn name.
     */
    private enum Half {

        /** The ordinary half: {@code θ = atan(v)}, running {@code 0 → 1 → ω} as the magnitude grows. */
        ORDINARY,

        /**
         * The reversed half: {@code θ = π - atan(v)}, running {@code _0 → _1 → ω} the other way.
         *
         * <p>These are the values the model keeps apart from their projections — {@code _1} projects as
         * {@code -1} and is not it — so the ruler here is spelled from {@code _1} rather than with a minus
         * sign. See the class note: a minus sign would move the rung to the other ordinary quarter.
         */
        REVERSED;

        /**
         * The turn a pair stands at in this half, before the sign picks which quarter of the two.
         *
         * <p>{@code atan2} of the pair rather than {@code atan} of the ratio, because that is what
         * {@link Algebra} does with the same two integers when the label is typed back in, and a rung that
         * missed its own name by a rounding would be a mark standing next to the number it is not at.
         */
        double turn(double p, double q) {
            double ordinary = Math.atan2(p, q);
            return this == ORDINARY ? ordinary : Math.PI - ordinary;
        }

        /** The arc of the circle this half and sign cover, as one unwrapped interval. */
        double[] arc(int sign) {
            double lo = this == ORDINARY ? 0 : Math.PI / 2;
            double hi = this == ORDINARY ? Math.PI / 2 : Math.PI;
            return sign > 0 ? new double[]{lo, hi} : new double[]{-hi, -lo};
        }

        /** {@link #turn} read backwards: which magnitude stands at this turn. */
        double magnitude(double theta, int sign) {
            double ordinary = this == ORDINARY ? sign * theta : Math.PI - sign * theta;
            return Math.tan(Math.clamp(ordinary, 0, Math.PI / 2 - 1e-13));
        }
    }

    /** How far out the ruler is generated. Twelve decades is {@link #FINEST} said in the other unit. */
    private static final int DECADES = 12;

    /** The mantissas of a log ruler, offered in this order so the decades and the halves claim space first. */
    private static final int[] DIGITS = {1, 5, 2, 3, 4, 6, 7, 8, 9};

    /** The rank a fill candidate is offered at: after every rung of the log ruler, whatever its mantissa. */
    private static final int FILL = 3;

    /** How long a mantissa may get before its name stops being a label and starts being a number. */
    private static final int NAME_DIGITS = 5;

    private static int rank(int k) {
        return k == 1 ? 0 : k == 5 ? 1 : 2;
    }

    /**
     * What a rung is called: an expression that parses to a pair standing at exactly that turn.
     *
     * <p>Four shapes, and each of them is checked by a test rather than argued for here:
     *
     * <ul>
     *   <li>{@code 30}, {@code 3÷100} — the ordinary half, a rational written the way the field takes one;
     *   <li>{@code -30}, {@code -3÷100} — the same, negated, which is the other ordinary quarter because
     *       {@code Folding.ORDINARY} turns the numerator;
     *   <li>{@code _1·30}, {@code _1÷10} — the reversed half, built from the reversed unit, because
     *       multiplying {@code T(1,-1)} by a magnitude keeps the reversed representative;
     *   <li>{@code -_1·30} — the fourth quarter, both at once. Unary minus binds tighter than {@code ·}, so
     *       this reads as {@code (-_1)·30} and lands where it is written.
     * </ul>
     *
     * <p>{@code _1·1÷10} would be correct for a magnitude under one and it is not what is written: a rung
     * whose mantissa is {@code 1} folds into {@code _1÷10}, which is the same pair and two characters shorter,
     * and the labels on this axis are read at tick size.
     */
    private static String name(Candidate candidate) {
        String minus = candidate.sign() < 0 ? "-" : "";
        long mantissa = candidate.mantissa();
        int decade = candidate.decade();
        if (candidate.half() == Half.ORDINARY) {
            return minus + magnitude(mantissa, decade);
        }
        if (mantissa == 1 && decade < 0) {
            return minus + "_1÷" + power(-decade);
        }
        return minus + "_1·" + magnitude(mantissa, decade);
    }

    /** {@code m × 10ⁿ} as the field would take it: an integer above one, a rational below. */
    private static String magnitude(long mantissa, int decade) {
        if (decade >= 0) {
            return mantissa + "0".repeat(decade);
        }
        return mantissa + "÷" + power(-decade);
    }

    private static String power(int zeros) {
        return "1" + "0".repeat(zeros);
    }

    /** An angle brought into {@code (-π, π]}, so a difference is always the short way round. */
    static double wrap(double radians) {
        double a = Math.IEEEremainder(radians, CIRCLE);
        return a <= -Math.PI ? a + CIRCLE : a;
    }

    private Turns() {
    }
}
