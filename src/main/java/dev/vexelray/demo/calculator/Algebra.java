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
 * <p>A value's place needs at least two coordinates and sometimes three, and an input axis needs one more. So
 * the count is forced rather than chosen:
 *
 * <ul>
 *   <li><b>{@link Mode#POINT}</b> — no free names. The expression is one value, so it gets a marker where it
 *       lands and the derivation that got it there.
 *   <li><b>{@link Mode#CURVE}</b> — one free name, plotted as {@code (x, a, b)}.
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
         * <p>A curve is drawn over its input, so the first axis is that input and the other two are the
         * value's coordinates. A single value has no input, so all three are its coordinates — and the third
         * is the exponent's own traction part, which is why it is {@code Tr'} rather than a second name.
         *
         * <p>The second name is <b>Tr</b> and not <b>Im</b>. The vertical output axis is the traction axis, an
         * order of vanishing; the reading under which it would be an imaginary part is the phase, and nothing
         * in this engine wires it. Naming it Im would be the plot asserting a component the value has not got.
         */
        String[] axisNames() {
            return mode == Mode.CURVE
                    ? new String[]{variable == null ? "x" : variable, "Re", "Tr"}
                    : new String[]{"Re", "Tr", "Tr'"};
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
                    "two free names and a value of its own needs four axes — not drawn");
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
                "curve over " + variable + "  ·  (" + variable + ", a, b)", null);
    }

    private static Reading refused(Mode mode, String subtitle, String refusal) {
        return new Reading(mode, null, null, null, List.of(), null, subtitle, refusal);
    }

    /**
     * The curve, as contiguous runs of interleaved {@code x, a, b}.
     *
     * <p><b>Runs rather than one array, because a break is real.</b> A sample whose value the theory does not
     * settle has no place, and joining the samples either side of it would draw a segment through a region the
     * engine said nothing about. Most expressions break nowhere — division by zero is {@code w}, a value with
     * a place, so the thing that puts a hole in an ordinary plot does not put one here.
     *
     * <p>A three-coordinate value is a break too, for the same reason and not a different one: {@code (x, a,
     * b, c)} does not fit three axes, and dropping {@code c} would put the sample somewhere it is not.
     *
     * <p>Pure and allocation-per-call, run on a worker whenever the expression or the domain changes, never per
     * frame: about 30 ms for 420 samples, which is a worker's business and not a frame's.
     *
     * @param samples how many points; the caller has already clamped this to the buffer's ceiling
     */
    static List<double[]> curve(Reading reading, double x0, double x1, int samples) {
        if (!reading.drawsCurve()) {
            return List.of();
        }
        int n = Math.max(2, samples);
        List<double[]> runs = new ArrayList<>();
        List<Double> run = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double x = x0 + (x1 - x0) * (i / (double) (n - 1));
            double[] at = placed(reading, x);
            if (at == null) {
                closeRun(runs, run);
                continue;
            }
            run.add(x);
            run.add(at[0]);
            run.add(at[1]);
        }
        closeRun(runs, run);
        return List.copyOf(runs);
    }

    /** Where one sample lands, or null where it has no place in two coordinates. */
    private static double[] placed(Reading reading, double x) {
        try {
            Bindings at = Bindings.EMPTY.define(reading.variable() + " = " + literal(x));
            Optional<Place> place = Place.of(Cott.reduce(at.expand(reading.term())));
            if (place.isEmpty() || place.get().dimension() != 2) {
                return null;
            }
            return place.get().toDoubles();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void closeRun(List<double[]> runs, List<Double> run) {
        // A single point is not a segment and a stroke of one draws nothing, so it is dropped rather than
        // emitted as a run that renders as an invisible artefact somebody would later go looking for.
        if (run.size() >= 6) {
            double[] out = new double[run.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = run.get(i);
            }
            runs.add(out);
        }
        run.clear();
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
