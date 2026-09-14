package dev.vexelray.demo.calculator;

import sibarum.cott.Bindings;
import sibarum.cott.Cott;
import sibarum.cott.Render;
import sibarum.cott.SyntaxException;
import sibarum.cott.Variables;
import sibarum.cott.engine.base.expr.IExpr;
import sibarum.cott.engine.derivation.Derivation;
import sibarum.cott.engine.derivation.Step;
import sibarum.cott.engine.projection.Place;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <b>The engine, and the whole of the seam onto it.</b> This is what replaced {@code Canned}.
 *
 * <p>One class, the way the fake was one class: {@link #read} makes a {@link Reading} and {@link #curve}
 * samples one. Everything in the application that would touch the algebra touches this and nothing else, which
 * is what let the fake be deleted in a single move and is worth keeping now that the thing behind it is real.
 *
 * <h2>The client reads what the engine says and does not re-derive it</h2>
 *
 * <p>That is cott-engine's own recorded lesson, from the bridge that died with the previous calculator: a
 * client that read the carrier structurally "encoded the abandoned theory in its shape, not just its imports",
 * and had to be rewritten when the carrier moved. The carrier is going to move again. So nothing here
 * pattern-matches a traction — where a value lands comes back from {@code Place}, and which names are free
 * comes back from {@code Variables}, both published by the engine.
 *
 * <h2>What the three modes are, and why there is no fourth</h2>
 *
 * <p>A value's place needs at least two coordinates and sometimes three, so three axes is what there is. The
 * input does not take one of them — it is walked over, not plotted against — which is why a curve and a
 * single value are drawn in the same space and mean the same thing by it:
 *
 * <ul>
 *   <li><b>{@link Mode#POINT}</b> — no free names. The expression is one value, so it gets a marker where it
 *       lands and the derivation that got it there.
 *   <li><b>{@link Mode#CURVE}</b> — one free name, walked from the middle outward and drawn as the path its
 *       value traces through {@code (Re, Tr, Tr')}. See {@link #walk}.
 *   <li><b>{@link Mode#SURFACE}</b> — two free names. Two inputs and a two-coordinate value is four axes, so
 *       this is <em>recognised and refused</em> rather than drawn flat. It keeps its name because the badge
 *       should say what the expression is, not what happens to be drawable.
 * </ul>
 *
 * <h2>The one rule inherited from the fake, and it still holds</h2>
 *
 * <p><b>A picture of a different expression than the one in the field is the one outcome that is not
 * allowed.</b> Nothing here falls back to a default scene: an expression that cannot be drawn produces no
 * curve and a {@link Reading#refusal()} saying why, and a sample that has no place breaks the curve rather
 * than being interpolated across.
 */
final class Algebra {

    /**
     * What the plot opens with.
     *
     * <p>{@code 0^x} rather than the prototype's {@code e^(i*w*x/2)}: this is the traction axis itself, the
     * thing the theory is about, and every sample of it is a value the engine answers exactly. It also shows
     * the two facts a reader should meet first — it is {@code 1} at {@code x = 0} and the point zero at
     * {@code x = 1}, so the curve crosses from the real axis onto the traction axis in view.
     */
    static final String DEFAULT_EXPRESSION = "0^x";

    /** How an expression wants to be drawn. Decided by how many names it leaves free. */
    enum Mode {
        /** No free names: one value, one marker. */
        POINT,
        /** One free name: a path through {@code (x, a, b)}. */
        CURVE,
        /** Two free names: recognised, and not drawable in three axes. */
        SURFACE
    }

    /**
     * What reading an expression produced.
     *
     * <p>Stored on the {@link Scene} rather than recomputed per consumer, because the badge, the subtitle, the
     * error line and the geometry all read it and must not describe different expressions.
     *
     * @param mode       which of the three kinds this is
     * @param term       the expression parsed, expanded and settled; what {@link #curve} samples
     * @param variable   the free name a curve is drawn over, or {@code null} in the other modes
     * @param answer     the settled value as the engine renders it, for {@link Mode#POINT}
     * @param derivation every rewrite from the entry to the answer, one line each, for {@link Mode#POINT}
     * @param place      where a {@link Mode#POINT} value lands, or {@code null}
     * @param subtitle   the line under the field — always a description of <em>what is shown</em>
     * @param refusal    {@code null} when the expression was understood and drawn; otherwise what to tell the
     *                   user, in which case nothing is drawn
     */
    record Reading(Mode mode, IExpr term, String variable, String answer, List<String> derivation,
                   Place place, String subtitle, String refusal) {

        Reading {
            derivation = List.copyOf(derivation);
        }

        boolean understood() {
            return refusal == null;
        }

        /** Whether there is a curve to build. The other modes draw the furniture and a marker, or nothing. */
        boolean drawsCurve() {
            return mode == Mode.CURVE && refusal == null;
        }

        /** Whether there is a marker to place. */
        boolean drawsMarker() {
            return mode == Mode.POINT && place != null && place.withinVolume();
        }

        /**
         * What the three world axes carry, in order.
         *
         * <p>The same three in every mode, because the input is walked over rather than plotted against: a
         * curve and a single value are both drawn in the value's own coordinates, and the third is the
         * exponent's own traction part, which is why it is {@code Tr'} rather than a second name.
         *
         * <p>The second name is <b>Tr</b> and not <b>Im</b>. The vertical output axis is the traction axis, an
         * order of vanishing; the reading under which it would be an imaginary part is the phase, and nothing
         * in this engine wires it. Naming it Im would be the plot asserting a component the value has not got.
         */
        String[] axisNames() {
            return new String[]{"Re", "Tr", "Tr'"};
        }
    }

    /**
     * Read an expression.
     *
     * <p>Everything a syntax error knows is the engine's to say: {@code SyntaxException} already carries a
     * message written for a person ({@code "'&' not in COTT"}), so it becomes the refusal verbatim rather than
     * being replaced by something this class invents and would have to keep in step.
     */
    static Reading read(String expression) {
        String entry = expression == null ? "" : expression.trim();
        if (entry.isEmpty()) {
            return refused(Mode.POINT, "type an expression", "nothing entered");
        }
        Derivation derivation;
        try {
            derivation = Cott.derive(entry);
        } catch (SyntaxException e) {
            return refused(Mode.POINT, "not read", message(e));
        } catch (RuntimeException e) {
            // The engine is research code and an unfinished rule can throw. A window that dies on a keystroke
            // is worse than one that says it could not read something, and the entry is recoverable either way.
            return refused(Mode.POINT, "not read", message(e));
        }
        IExpr term = derivation.to();
        List<String> names = List.copyOf(Variables.of(term));
        return switch (names.size()) {
            case 0 -> point(derivation, term);
            case 1 -> curve(term, names.getFirst());
            case 2 -> refused(Mode.SURFACE, "two inputs over " + names,
                    "two free names sweep a surface, and only the real line is walked so far — not drawn");
            default -> refused(Mode.SURFACE, names.size() + " free names",
                    "only one free name can be plotted; this has " + names);
        };
    }

    /** One value: where it lands, and how it got there. */
    private static Reading point(Derivation derivation, IExpr term) {
        String answer = Render.show(term);
        List<String> lines = lines(derivation);
        Optional<Place> place = Place.of(term);
        if (place.isEmpty()) {
            // Not a failure of this class or of the entry: the theory has no answer, and the term standing IS
            // the answer. It is shown, and not placed, because it is not one point.
            return new Reading(Mode.POINT, term, null, answer, lines, null,
                    "stands — " + answer, "no rule settles this, so it has no place on the chart");
        }
        Place at = place.get();
        if (!at.withinVolume()) {
            return new Reading(Mode.POINT, term, null, answer, lines, at,
                    answer + " — " + at.dimension() + " coordinates",
                    "this value needs " + at.dimension() + " axes; three is as many as there are");
        }
        return new Reading(Mode.POINT, term, null, answer, lines, at,
                answer + "  at  " + coordinates(at), null);
    }

    private static Reading curve(IExpr term, String variable) {
        return new Reading(Mode.CURVE, term, variable, null, List.of(), null,
                "walked over " + variable + "  ·  (Re, Tr, Tr')", null);
    }

    private static Reading refused(Mode mode, String subtitle, String refusal) {
        return new Reading(mode, null, null, null, List.of(), null, subtitle, refusal);
    }

    /**
     * The curve, as contiguous runs of interleaved value coordinates {@code a, b, c}.
     *
     * <h2>The input is a parameter, not an axis</h2>
     *
     * <p>A sample contributes <em>where its value landed</em> and nothing else. The free name is walked over,
     * not plotted against, so a curve and a single value occupy the same three axes and mean the same thing by
     * them. That is what makes the other modes possible later: a mode is a choice of which path through the
     * input to walk, and the picture it draws is always the value's own coordinates.
     *
     * <p><b>The walk starts in the middle and steps outward both ways</b>, rather than sweeping from one end.
     * The budget is therefore spent nearest the starting point first, so an expression that goes pathological
     * far out still gets drawn faithfully near the middle. Each direction is its own run; they share the
     * starting sample, so a curve continuous through it is drawn as one unbroken line.
     *
     * <h2>Subdividing on convergence, not on a threshold</h2>
     *
     * <p><b>A long chord has three causes and only one of them is under-sampling.</b> Refining until every gap
     * is under a limit would not terminate on ordinary input: {@code 0^x} jumps by exactly 1 at the origin at
     * every depth, because {@code 0^0 = 1} lands at {@code (1, 0)} while its neighbours approach {@code (0, 0)},
     * which is erasure and not in the type at all. {@code x^2} plateaus at 2.236 against {@code (0, 2)}, the
     * order of vanishing. {@code 1÷x} gets <em>worse</em> as it is refined, the chord growing past 1000 while
     * the interval halves. None of those are resolution problems and none of them can be subdivided away.
     *
     * <p>So the test is whether splitting an interval makes <em>progress</em>. On a smooth stretch each half
     * chord is about half the whole; at a jump one half keeps essentially the whole gap, and under divergence
     * it exceeds it. When the longer half fails to beat {@link #PROGRESS} of the whole, the curve is broken
     * there and the far sample begins a new run — the honest picture, since the engine really did say the
     * value is somewhere else.
     *
     * <p>{@link #DEPTH} and a total sample budget are the backstop under that, for entries like
     * {@code tan(1÷x)} whose jumps stay bounded and would otherwise refine forever.
     *
     * <p><b>Runs rather than one array, because a break is real.</b> A sample whose value the theory does not
     * settle has no place, and joining the samples either side of it would draw a segment through a region the
     * engine said nothing about. Such a sample breaks the run and is never subdivided toward — there is
     * nothing between two points that do not exist.
     *
     * <p>Pure and allocation-per-call, run on a worker whenever the expression or the domain changes, never per
     * frame.
     *
     * @param samples the initial step count per direction; the walk may spend up to {@link #BUDGET} times this
     *                many in total refining, and no more
     */
    static List<double[]> walk(Reading reading, double x0, double x1, int samples) {
        if (!reading.drawsCurve() || x1 <= x0) {
            return List.of();
        }
        Walk walk = new Walk(reading, Math.max(2, samples));
        // The starting point, clamped into the domain rather than skipped when the domain excludes it. Zero
        // is the reading of "(0, 0)" that the real line projection is named for; a mode that walks some other
        // path through the input is the seam this leaves open, and it changes only these two calls.
        double start = Math.min(x1, Math.max(x0, ORIGIN));
        walk.outward(start, x1);
        walk.outward(start, x0);
        return walk.runs();
    }

    /** Where the walk begins before stepping away in each direction. */
    private static final double ORIGIN = 0;

    /** How much of the whole chord the longer half must beat for a split to count as progress. */
    private static final double PROGRESS = 0.95;

    /** How far a single interval may be bisected before the walk gives up and breaks the curve. */
    private static final int DEPTH = 12;

    /** Total samples allowed, as a multiple of the requested step count. The failsafe. */
    private static final int BUDGET = 8;

    /** A gap smaller than this fraction of the largest coordinate seen so far is close enough. */
    private static final double TOLERANCE = 0.02;

    /** Below this the tolerance stops shrinking, so a curve sitting at the origin still terminates. */
    private static final double FLOOR = 1e-9;

    /**
     * One walk over one reading, and the mutable state it needs.
     *
     * <p>A class rather than a fold because three things vary together as it goes: what is left of the budget,
     * the largest coordinate seen so far (which sets the tolerance, so that a curve reaching 1000 is not
     * sampled to the same absolute precision as one reaching 1), and the run being accumulated.
     */
    private static final class Walk {

        private final Reading reading;
        private final int steps;
        private final List<double[]> runs = new ArrayList<>();
        private final List<Double> run = new ArrayList<>();
        private int budget;
        private double extent;

        Walk(Reading reading, int steps) {
            this.reading = reading;
            this.steps = steps;
            this.budget = steps * BUDGET;
        }

        /**
         * Step from the starting point out to one end, refining each gap that is too long to accept.
         *
         * <p><b>The grid is walked before anything is refined</b>, because the tolerance is relative to the
         * largest coordinate seen and that is not known until it has been. Refining as it went measured the
         * first few steps against an extent of almost nothing, found them enormous by comparison, and spent
         * the budget subdividing the flattest part of the curve.
         */
        void outward(double from, double to) {
            double step = (to - from) / steps;
            if (step == 0 || !Double.isFinite(step)) {
                return;
            }
            double[] xs = new double[steps + 1];
            double[][] grid = new double[steps + 1][];
            for (int i = 0; i <= steps; i++) {
                xs[i] = from + step * i;
                grid[i] = place(xs[i]);
            }
            double[] previous = null;
            for (int i = 0; i <= steps; i++) {
                double[] at = grid[i];
                if (at == null) {
                    // No place here, so nothing to bridge to and nothing between: break rather than refine.
                    close();
                    previous = null;
                    continue;
                }
                if (previous != null && !bridge(xs[i - 1], previous, xs[i], at, 0)) {
                    close();
                }
                emit(at);
                previous = at;
            }
            close();
        }

        /**
         * Fill the gap between two placed samples, or report that it cannot be filled.
         *
         * <p>Returns false where the curve should be broken instead: the split made no progress, the depth or
         * the budget ran out, or the midpoint has no place. Points found on the way are emitted as they are
         * proven, so a gap that breaks halfway through still draws the half that was good.
         */
        private boolean bridge(double a, double[] from, double b, double[] to, int depth) {
            double whole = gap(from, to);
            if (whole <= tolerance()) {
                return true;
            }
            if (depth >= DEPTH || budget <= 0) {
                return false;
            }
            double middle = (a + b) / 2;
            if (middle == a || middle == b) {
                return false;
            }
            double[] at = place(middle);
            if (at == null) {
                return false;
            }
            // The convergence test. On a smooth stretch both halves are about half the whole; at a jump one
            // half keeps it all, and under divergence it grows past it. Either way, refining is not working.
            if (Math.max(gap(from, at), gap(at, to)) >= whole * PROGRESS) {
                return false;
            }
            if (!bridge(a, from, middle, at, depth + 1)) {
                return false;
            }
            emit(at);
            return bridge(middle, at, b, to, depth + 1);
        }

        /**
         * Where one sample lands, padded to three, or null where it has no place in a volume.
         *
         * <p>Two coordinates and three are both drawn, which they were not when the input held an axis: the
         * third is the innermost exponent, and an exponent with no traction in it is the absence marker, so
         * padding says the axis is unoccupied rather than inventing a position.
         */
        private double[] place(double x) {
            if (budget <= 0) {
                return null;
            }
            budget--;
            try {
                Bindings at = Bindings.EMPTY.define(reading.variable() + " = " + literal(x));
                Optional<Place> place = Place.of(Cott.reduce(at.expand(reading.term())));
                if (place.isEmpty() || !place.get().withinVolume()) {
                    return null;
                }
                double[] found = place.get().toDoubles();
                double[] out = new double[3];
                System.arraycopy(found, 0, out, 0, found.length);
                for (double c : out) {
                    if (!Double.isFinite(c)) {
                        return null;
                    }
                    extent = Math.max(extent, Math.abs(c));
                }
                return out;
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** Relative to the largest coordinate seen, so one scale of curve is not sampled like another. */
        private double tolerance() {
            return Math.max(FLOOR, extent * TOLERANCE);
        }

        private static double gap(double[] from, double[] to) {
            double dx = from[0] - to[0];
            double dy = from[1] - to[1];
            double dz = from[2] - to[2];
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        private void emit(double[] at) {
            run.add(at[0]);
            run.add(at[1]);
            run.add(at[2]);
        }

        private void close() {
            // A run of one point is kept, which it was not when the input held an axis. An isolated answer is
            // a real value at a real place -- 0^0 = 1 at the origin of 0^x, omega at the pole of 1/x, the
            // order of vanishing at the bottom of x^2 -- and every one of those is a discontinuity, so every
            // one arrives alone. Dropping them deleted the most informative sample on the chart. The caller
            // draws a run of one as a marker; a stroke of one point would still draw nothing.
            if (run.size() >= 3) {
                double[] out = new double[run.size()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = run.get(i);
                }
                runs.add(out);
            }
            run.clear();
        }

        List<double[]> runs() {
            return List.copyOf(runs);
        }
    }

    /**
     * A sample value as a literal the parser reads exactly.
     *
     * <p><b>Trailing zeros are stripped, and that is not cosmetic.</b> Coordinates in this engine are never
     * reduced, so {@code "0.0"} parses as {@code 0/10} — a literal whose numerator is zero but which is not
     * the rational zero, and the rules that fire on zero do not fire on it. Sampling {@code 0^x} at the origin
     * answered {@code 0^(0/10)}, which places at {@code (0,0)} — erasure, which is not a member of the type at
     * all — where the answer is {@code 0^0 = 1} at {@code (1,0)}. One sample in the middle of the domain, in
     * the wrong place, on the one axis crossing a reader would be looking at.
     */
    private static String literal(double v) {
        return new BigDecimal(Double.toString(v)).stripTrailingZeros().toPlainString();
    }

    /** The coordinates as the chart writes them. */
    private static String coordinates(Place place) {
        List<String> parts = new ArrayList<>();
        place.coordinates().forEach(c -> parts.add(Render.show(c)));
        return "(" + String.join(", ", parts) + ")";
    }

    /**
     * A derivation as lines, each a whole term and the rule that licensed it.
     *
     * <p>Rendered through {@link Render} rather than through {@code Derivation.toString}, which prints the raw
     * records — the engine's own debugging view, and not something to put in front of a person.
     */
    private static List<String> lines(Derivation derivation) {
        List<String> out = new ArrayList<>();
        out.add(Render.show(derivation.from()));
        for (Step step : derivation.steps()) {
            out.add("= " + Render.show(step.after()) + "    " + step.rule().name()
                    + "  [" + step.rule().reference() + ", " + step.rule().status() + "]");
        }
        return out;
    }

    private static String message(RuntimeException e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private Algebra() {
    }
}
