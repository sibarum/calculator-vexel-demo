package dev.vexelray.demo.calculator;

import sibarum.cott.SyntaxException;
import sibarum.cott.engine.ratio.T;
import sibarum.cott.parse.Functions;
import sibarum.cott.parse.Node;
import sibarum.cott.parse.Parse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * <b>The engine, and the whole of the seam onto it.</b>
 *
 * <p>One class, as it was when the seam went onto a fake and again when it went onto the traction carrier:
 * {@link #read} makes a {@link Reading} and {@link #walk} samples one. Everything in the application that
 * would touch the algebra touches this and nothing else, which is what has now let the model underneath it be
 * replaced three times without the view noticing.
 *
 * <h2>The model is {@code T}, and a value is a point in a plane</h2>
 *
 * <p>cott-engine's {@code docs/Traction-Model.md}: a traction is an <b>oriented projective rational</b>,
 * {@code T(p, q)}, which is two readings of one pair rather than two things —
 *
 * <pre>
 *  T(p, q) := p ÷ q = tan(θ)        θ = arg(q + pi)
 * </pre>
 *
 * <p>So the pair <em>is</em> the point {@code q + pi} in the plane, and the ratio it names is the tangent of
 * the angle that point stands at. The plot is the {@code (q, p)} plane, {@code q} across and {@code p} up.
 *
 * <p>This replaces the three-axis reading it inherited. {@code Place}, {@code Projector} and the
 * {@code (Re, Tr, Tr')} spine went with the carrier they projected: a nesting of {@code n·0^t} needed as many
 * as three numbers and the plot had to refuse a fourth, where a pair needs two and there is no fourth to
 * refuse. <b>Two axes is not a simplification of three, it is the model's own shape.</b>
 *
 * <h2>A value is a direction, and the picture is the circle of them</h2>
 *
 * <p>What this class reports is the pair the engine really answered. <b>Where that goes on the chart is
 * {@link Geometry}'s decision, and it draws the direction rather than the point</b> — every value on one
 * circle, at its own turn. The reason is a fact about the arithmetic, and it took a plot full of straight
 * lines to see it:
 *
 * <pre>
 *  T(a,b) · T(c,d) = T(ac, bd)          T(a,b) + T(c,d) = T(ad+bc, bd)
 * </pre>
 *
 * <p><b>The denominator of a result depends only on the denominators of its operands.</b> Every sample of a
 * walk shares one denominator ({@link #walk} says why it must), so <em>any</em> polynomial in the sampled name
 * has a constant {@code q} — and a chart of the pairs drew {@code x}, {@code x+1} and {@code x·x} as three
 * vertical lines, one per denominator, with {@code 1÷x} horizontal because a reciprocal swaps the coordinates.
 * The line {@code q = 4} is not a shape the function has; it is the set of rationals written over four.
 *
 * <p>The radius is bookkeeping and the angle is the value. {@code 1÷x} sampled over {@code k÷4} runs through
 * 127°, 117°, 104°, <b>90°</b>, 76°, 63°, 53° — smooth, total, and straight through the pole, which is the
 * whole claim the model makes. So the chart draws the turn, and the cost is stated rather than hidden:
 * {@code T(1,2)} and {@code T(2,4)} are two values here and they stand at one place on that circle, because
 * what separates them is which representative was written and not where the value is.
 *
 * <h2>What the plane shows that a number line cannot</h2>
 *
 * <p>The eight rows the model names are eight <em>places</em>, and four of them share a tangent:
 *
 * <pre>
 *   0 = T(0,1)  at  1          projects as  +0        ω = T(1,0)   at   i    projects as  +inf
 *  _0 = T(0,-1) at -1          projects as  -0       -ω = T(-1,0)  at  -i    projects as  -inf
 *   1 = T(1,1)  at  1+i                              _1 = T(1,-1)  at -1+i   projects as  -1
 *  -1 = T(-1,1) at  1-i                             -_1 = T(-1,-1) at -1-i   projects as   1
 * </pre>
 *
 * <p>{@code 0} and {@code -0} are both a zero tangent and they are half a turn apart; {@code 1} and
 * {@code -_1} project alike and stand in opposite quadrants. A chart drawn against the projection alone would
 * put each of those pairs in one place and lose the orientation the model is named for. The plane keeps them
 * apart because it draws the pair rather than the ratio, and {@link #projection} is reported <em>beside</em>
 * the point rather than instead of it.
 *
 * <h2>The client reads what the engine says and does not re-derive it</h2>
 *
 * <p>That rule survives the change of model and is why this file is short. Text becomes a term through
 * {@code Parse} and the grammar in {@code Traction.g4}; a term folds through {@code Node.fold}, whose
 * arithmetic is the model's table; a folded term hands back a {@code T} and the pair's coordinates are the
 * two numbers this passes on. Nothing here implements an operation.
 *
 * <p><b>The one exception has been closed.</b> {@link #projection} was the model table's projection column,
 * written here because {@code T} published none. The column is now {@code T.projection()} and what is left
 * here is the spelling of it, so there is no rule in this file that could disagree with the table when the
 * table moves -- only a formatter, which can only disagree with itself.
 *
 * <h2>The one rule inherited from the fake, and it still holds</h2>
 *
 * <p><b>A picture of a different expression than the one in the field is the one outcome that is not
 * allowed.</b> Nothing here falls back to a default scene: an expression that cannot be drawn produces no
 * curve and a {@link Reading#refusal()} saying why, and a sample that does not fold to a pair breaks the curve
 * rather than being interpolated across.
 */
final class Algebra {

    /**
     * What the plot opens with.
     *
     * <p>{@code 1÷x}, and it is the best single argument for the model. Sample the name at {@code k÷d} and the
     * reciprocal is {@code T(d, k)} — the point {@code k + di} — so the graph of the classic singularity is a
     * <b>straight horizontal line</b>, and the pole is the ordinary point on it where {@code q} reaches zero.
     * Nothing jumps, nothing is a hole, and the thing that breaks an ordinary plot is visibly the place where
     * the line crosses the {@code p} axis.
     */
    static final String DEFAULT_EXPRESSION = "1÷x";

    /**
     * What a call in an entry means: nothing, so far.
     *
     * <p>{@code Functions} is supplied by whoever is calculating rather than by the parser, and this client has
     * no table to supply. So {@code sin(x)} parses, stands, and is reported as standing. That is the honest
     * answer while the model has no real-valued calls in it — the previous seam folded them through the syntax
     * layer's {@code Real}, and folding a sine into a ratio here would be this class inventing arithmetic.
     */
    private static final Functions FUNCTIONS = Functions.NONE;

    /**
     * The names that are values rather than unknowns, and the whole of this client's notation.
     *
     * <p>The grammar has no constants in it: a run of letters is a name and what a name means is the caller's
     * to say, which is why {@code Parse} hands back a {@code Var} and stops. So a table is needed to type the
     * model at all, and <b>this is the smallest one that makes every row of it typable exactly as the model
     * writes it</b> — the other five rows already parse:
     *
     * <pre>
     *   0    T(0,1)   a number                    ω   T(1,0)    bound here, and {@code w} beside it
     *   1    T(1,1)   a number                  -ω   T(-1,0)    the negation of that
     *  -1    T(-1,1)  a negated number            _0  T(0,-1)   bound here
     *  _1    T(1,-1)  bound here                -_1  T(-1,-1)   the negation of that
     * </pre>
     *
     * <p>The negations come out right rather than by luck, and it is worth saying which fact is doing it:
     * {@code Folding.ORDINARY} turns the numerator, so {@code -_1} is {@code T(-1,-1)} and {@code -1} is
     * {@code T(-1,1)} — the table's own two positions. An underscore leads a name in the grammar, which is
     * what lets {@code _0} and {@code _1} be written the model's way instead of as coordinates.
     *
     * <p>{@code w} is bound beside {@code ω} for the reason the previous notation bound it: the glyph is not
     * on a keyboard. The cost is that {@code w} cannot be a variable, which was true of the last three
     * notations here too.
     *
     * <p>Substituted before the free names are counted, so a constant is a value and never an axis.
     */
    private static final Map<String, Node> CONSTANTS = Map.of(
            "ω", new Node.Lit(T.OMEGA),
            "w", new Node.Lit(T.OMEGA),
            "_0", new Node.Lit(T.of(0, -1)),
            "_1", new Node.Lit(T.of(1, -1)));

    /**
     * One of the model's named points: what it is called here, and which pair it is.
     *
     * @param name  as {@link #CONSTANTS} reads it, so the chart cannot label a place something a reader could
     *              not type back into the field
     * @param value the pair, whose {@link T#theta()} is where on the circle it stands
     */
    record Named(String name, T value) {

        /** Where this point stands on the circle, in radians. */
        double theta() {
            return value.theta();
        }
    }

    /**
     * The model's eight named points, in the order they stand around the circle.
     *
     * <p>They are the graduations. A circle of directions has no scale to graduate — every value is at a
     * turn and nothing is nearer or further — so what a reader needs marked is the turns that have names, and
     * the model names exactly these eight, at the eighth turns. Two of them are on each axis and the other
     * four are the diagonals.
     *
     * <p>This is the same list twice over on purpose: it labels the chart <em>and</em> it is what the field
     * accepts, so every mark on the picture is an entry a reader can type.
     */
    static final List<Named> NAMED = List.of(
            new Named("0", T.of(0, 1)),        //    0°   the point zero
            new Named("1", T.of(1, 1)),        //   45°
            new Named("ω", T.of(1, 0)),        //   90°   the quarter turn
            new Named("_1", T.of(1, -1)),      //  135°   distinct from -1, and projects as -1
            new Named("_0", T.of(0, -1)),      //  180°   distinct from 0, and projects as -0
            new Named("-_1", T.of(-1, -1)),    // -135°   distinct from 1, and projects as 1
            new Named("-ω", T.of(-1, 0)),      //  -90°
            new Named("-1", T.of(-1, 1)));     //  -45°

    /** How an expression wants to be drawn. Decided by how many names it leaves free. */
    enum Mode {
        /** No free names: one value, one marker. */
        POINT,
        /** One free name: a path through the plane. */
        CURVE,
        /** Two free names: recognised, and not one path. */
        SURFACE
    }

    /**
     * What reading an expression produced.
     *
     * <p>Stored on the {@link Scene} rather than recomputed per consumer, because the badge, the subtitle, the
     * error line and the geometry all read it and must not describe different expressions.
     *
     * @param mode       which of the three kinds this is
     * @param term       the expression as parsed, unfolded; what {@link #walk} substitutes into
     * @param variable   the free name a curve is drawn over, or {@code null} in the other modes
     * @param answer     the folded value as the model writes it — {@code T(p,q)} — for {@link Mode#POINT}
     * @param derivation the entry, what it folded to, and where that lands, for {@link Mode#POINT}
     * @param value      the pair a {@link Mode#POINT} entry folded to, or {@code null} where it did not fold
     * @param subtitle   the line under the field — always a description of <em>what is shown</em>
     * @param refusal    {@code null} when the expression was understood and drawn; otherwise what to tell the
     *                   user, in which case nothing is drawn
     */
    record Reading(Mode mode, Node term, String variable, String answer, List<String> derivation,
                   T value, String subtitle, String refusal) {

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

        /** Whether there is a marker to place: an entry that folded to one pair. */
        boolean drawsMarker() {
            return value != null;
        }

        /**
         * Where the value stands, as {@code (q, p)}.
         *
         * <p>Horizontal first, because the point is {@code q + pi} and the horizontal axis is the one the
         * angle is measured from. {@code T(0,0)} answers as {@link T#resolved()} does — the table reads it as
         * a value by {@code x ÷ x = 1}, and a plot is exactly the place that reads a pair as a value.
         */
        double[] coordinates() {
            T at = value.resolved();
            return new double[]{at.q().doubleValue(), at.p().doubleValue()};
        }

        /**
         * What the two world axes carry, in order: the input, and the value's turn.
         *
         * <p>The first is <b>the free name the expression actually left</b>, so an entry walked over
         * {@code t} says {@code t} — a fixed "x" would be the chart naming a variable the reader did not
         * write. In the other modes there is no input and it is "x" only as a placeholder, which nothing
         * draws.
         *
         * <p>The second is <b>{@code θ}</b>, and not a coordinate. It was {@code q} and {@code p} when the
         * chart drew the pair, and that chart was wrong: what is drawn is {@code arg(q + pi)}, one number out
         * of the pair and the whole of the value, where the radius is only which representative got written.
         * Naming this axis for a coordinate would be the plot claiming to show one.
         */
        String[] axisNames() {
            return new String[]{variable == null ? "x" : variable, "θ"};
        }
    }

    /**
     * Read an expression.
     *
     * <p>Everything a syntax error knows is the engine's to say: {@code Parse} raises {@code SyntaxException}
     * with a message written for a person and a character position, so it becomes the refusal verbatim rather
     * than being replaced by something this class invents and would have to keep in step.
     */
    static Reading read(String expression) {
        String entry = expression == null ? "" : expression.trim();
        if (entry.isEmpty()) {
            return refused(Mode.POINT, "type an expression", "nothing entered");
        }
        Node term;
        try {
            term = Parse.of(entry).substitute(CONSTANTS);
        } catch (SyntaxException e) {
            return refused(Mode.POINT, "not read", message(e));
        } catch (RuntimeException e) {
            // The engine is research code and an unfinished rule can throw. A window that dies on a keystroke
            // is worse than one that says it could not read something, and the entry is recoverable either way.
            return refused(Mode.POINT, "not read", message(e));
        }
        List<String> names = List.copyOf(names(term));
        return switch (names.size()) {
            case 0 -> point(term);
            case 1 -> curve(term, names.getFirst());
            case 2 -> refused(Mode.SURFACE, "two inputs over " + names,
                    "two free names sweep a family of points, and one name is as many as is walked — not drawn");
            default -> refused(Mode.SURFACE, names.size() + " free names",
                    "only one free name can be plotted; this has " + names);
        };
    }

    /** One value: what it folded to, and where that lands in the plane. */
    private static Reading point(Node term) {
        Node folded = fold(term);
        Optional<T> value = folded.literal();
        if (value.isEmpty()) {
            // Not a failure of this class or of the entry: no rule folds this, and the term standing IS the
            // answer. It is shown, and not placed, because it is not one pair.
            return new Reading(Mode.POINT, term, null, folded.show(), List.of(term.show(), "= " + folded.show()),
                    null, "stands — " + folded.show(),
                    "nothing folds this to a pair, so it has no point in the plane");
        }
        T at = value.get();
        String answer = folded.show();
        List<String> lines = List.of(
                term.show(),
                "= " + answer,
                "at  " + coordinates(at) + "    " + turn(at) + "    projects as " + projection(at));
        return new Reading(Mode.POINT, term, null, answer, lines, at,
                answer + "  at  " + coordinates(at) + "  ·  " + turn(at) + "  ·  " + projection(at), null);
    }

    private static Reading curve(Node term, String variable) {
        return new Reading(Mode.CURVE, term, variable, null, List.of(), null,
                "walked over " + variable + "  ·  θ = arg(q + pi)", null);
    }

    private static Reading refused(Mode mode, String subtitle, String refusal) {
        return new Reading(mode, null, null, null, List.of(), null, subtitle, refusal);
    }

    /**
     * The curve, as contiguous runs of interleaved {@code x, q, p}.
     *
     * <h2>The input is a parameter, not an axis</h2>
     *
     * <p>A sample contributes <em>where its value landed</em>, and the {@code x} it carries is for the probe to
     * quote rather than for the picture to use. The free name is walked over, not plotted against, so a curve
     * and a single value occupy the same two axes and mean the same thing by them.
     *
     * <h2>The walk steps on one denominator</h2>
     *
     * <p>Every sample is the name bound to {@code T(k, d)} for one {@code d} across the whole domain, and the
     * numerator is what steps. Two reasons, and a third that used to be the main one and is not:
     *
     * <ul>
     *   <li><b>The grid is exact.</b> There is no literal text to convert, so the old sampling trap cannot
     *       happen — {@code Node.of(k, d)} <em>is</em> the pair, where {@code "0.0"} was a string that parsed
     *       as {@code 0÷10} and missed every rule that fires on zero.
     *   <li><b>The power rule sees the same kind of exponent everywhere.</b> A power folds at a whole
     *       exponent, so in {@code 2^x} it is the sample's denominator that decides whether anything folds at
     *       all. One denominator makes that one answer for the whole walk instead of a different one every
     *       few steps.
     *   <li><s>Otherwise the samples scatter.</s> <b>True of the plane and not of the circle.</b> Nothing in
     *       this model reduces, so {@code 1.5} is {@code T(15,10)} where {@code 2} is {@code T(2,1)}, and
     *       plotted as <em>points</em> their reciprocals stand an order of magnitude apart. But sum, product
     *       and reciprocal are homogeneous in each operand — scaling a pair's coordinates by a positive factor
     *       scales the result's by one too — so the <em>direction</em> is the same whichever representative
     *       was written, and the chart draws directions. A walk in decimals would now land in the right
     *       places. The exception is the one the bullet above is about: an exponent is read as a whole number
     *       rather than as a ratio, so {@code T(2,1)} is a square and {@code T(4,2)} is a term that stands.
     * </ul>
     *
     * <p><b>Nothing is refined, and there is nothing to refine with.</b> The previous walk bisected an interval
     * until splitting stopped making progress. Between {@code T(k,d)} and {@code T(k+1,d)} there is no
     * midpoint at this denominator, and a sample taken at a finer one would stand somewhere else in the plane
     * for the reason above. Resolution here is the denominator and it is chosen once; a curve that wants more
     * of it wants more samples, which is the control that already exists.
     *
     * <p><b>Runs rather than one array, because a break is real.</b> A sample the model does not fold to a pair
     * has no point, and joining the samples either side of it would draw a segment through a place the engine
     * said nothing about. Such a sample breaks the run.
     *
     * <p>Pure and allocation-per-call, run on a worker whenever the expression or the domain changes, never per
     * frame.
     *
     * @param samples how many steps to spend across the domain; the denominator is chosen to give about this
     *                many, and the count is held under {@link #CEILING} of them
     */
    static List<double[]> walk(Reading reading, double x0, double x1, int samples) {
        if (!reading.drawsCurve() || x1 <= x0) {
            return List.of();
        }
        long d = denominator(x0, x1, Math.max(2, samples));
        long from = (long) Math.ceil(x0 * d);
        long to = (long) Math.floor(x1 * d);
        List<double[]> runs = new ArrayList<>();
        List<Double> run = new ArrayList<>();
        for (long k = from; k <= to; k++) {
            double[] at = place(reading, k, d);
            if (at == null) {
                close(runs, run);
                continue;
            }
            run.add(k / (double) d);
            run.add(at[0]);
            run.add(at[1]);
        }
        close(runs, run);
        return List.copyOf(runs);
    }

    /** As many steps across the domain as were asked for, held under a ceiling a buffer can carry. */
    private static long denominator(double x0, double x1, int samples) {
        double wanted = samples / (x1 - x0);
        long d = Math.max(1, Math.round(wanted));
        while ((x1 - x0) * d > CEILING) {
            d = Math.max(1, d / 2);
            if (d == 1) {
                break;
            }
        }
        return d;
    }

    /** How many samples one walk may take, whatever the domain and the step ask for between them. */
    private static final int CEILING = 4000;

    /**
     * Where one sample lands, or null where the model does not fold it to a pair.
     *
     * <p>The name is bound to the pair itself rather than to text: {@code Node.of(k, d)} is a literal at those
     * coordinates, so nothing about the sample passes back through the grammar and there is no spelling for it
     * to acquire on the way.
     */
    private static double[] place(Reading reading, long k, long d) {
        try {
            Node at = reading.term()
                    .substitute(Map.of(reading.variable(), Node.of(k, d)))
                    .resolve(FUNCTIONS)
                    .fold();
            Optional<T> value = at.literal();
            if (value.isEmpty()) {
                return null;
            }
            T pair = value.get().resolved();
            double q = pair.q().doubleValue();
            double p = pair.p().doubleValue();
            // A coordinate that has outgrown a double is a real coordinate the picture cannot hold. Breaking
            // the run says so; drawing it at infinity would put the curve somewhere the value is not.
            return Double.isFinite(q) && Double.isFinite(p) ? new double[]{q, p} : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void close(List<double[]> runs, List<Double> run) {
        // A run of one is kept. An isolated answer is a real value at a real place, and every such sample
        // arrives alone precisely because its neighbours did not fold -- which makes it the most informative
        // point on the chart, not the least. The caller draws a run of one as a marker; a stroke through one
        // point would draw nothing at all.
        if (!run.isEmpty()) {
            double[] out = new double[run.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = run.get(i);
            }
            runs.add(out);
        }
        run.clear();
    }

    /** Substituted with nothing, resolved, then folded — the three walks in the order they compose. */
    private static Node fold(Node term) {
        try {
            return term.evaluate(Map.of(), FUNCTIONS);
        } catch (RuntimeException e) {
            return term;
        }
    }

    /**
     * The free names in a term: every {@link Node.Var} in it, in the order they were written.
     *
     * <p>Read off the parse tree rather than asked of the engine, which is a change from the previous seam and
     * a smaller claim than it looks. {@code Variables} had to tell a variable from {@code π} or {@code e},
     * because the carrier held both under one atom; here the grammar has already decided — a name that is not
     * a call is a {@code Var} and nothing in this client binds any of them. The day one is bound, the binding
     * is what this consults, and it is still not the carrier's shape being read.
     */
    private static Set<String> names(Node term) {
        Set<String> out = new LinkedHashSet<>();
        collect(term, out);
        return out;
    }

    private static void collect(Node node, Set<String> into) {
        if (node instanceof Node.Var(String name)) {
            into.add(name);
        }
        node.children().forEach(child -> collect(child, into));
    }

    /**
     * How this client spells {@code T.projection()}.
     *
     * <p>The column itself is the type's now, and the reasons it is worth having moved are both reasons this
     * class could not have served: {@code T} answers the four points on the axes from their signs and divides
     * the coordinates exactly for everything else, where this divided the two doubles — so a pair whose
     * coordinates have outgrown a double projects to the number it names rather than to infinity over
     * infinity. And it reads {@code T(0,0)} as a value by {@code x ÷ x}, which is the table's rule and not
     * one a chart should be applying on its own.
     *
     * <p>What is left is spelling. {@code inf} rather than {@code ∞} for a reason that is not aesthetic: the
     * text atlas this application draws with has no {@code U+221E}, and a missing glyph draws as a box. The
     * signed zero is written out because it is the one distinction {@link #trim} would lose.
     */
    static String projection(T value) {
        return spell(value.projection());
    }

    /**
     * As {@link #projection(T)}, from a pair already read as doubles — which is what a sampled curve carries.
     *
     * <p>A sample arrives as two doubles rather than as a pair, so the division happens here; every sample
     * has already been through {@code T.resolved()} in {@link #place}, which is what the type does first.
     * One spelling for both, because the probe quotes this over a curve and the subtitle quotes it over a
     * value, and a chart whose bubble and whose caption disagreed about what something projects to would be
     * worse than one that offered neither.
     */
    static String projection(double p, double q) {
        return spell(p / q);
    }

    private static String spell(double r) {
        if (Double.isInfinite(r)) {
            return r > 0 ? "+inf" : "-inf";
        }
        if (r == 0) {
            // Negative zero is a distinct projection here, and it is the one case the formatter would lose.
            return Math.copySign(1, r) < 0 ? "-0" : "+0";
        }
        return trim(r);
    }

    /** Where the point stands, as the chart writes it: the coordinates of {@code q + pi}, horizontal first. */
    private static String coordinates(T value) {
        T at = value.resolved();
        return "(" + at.q() + ", " + at.p() + ")";
    }

    /**
     * The angle the point stands at, in degrees.
     *
     * <p>The reading that tells apart what the ratio cannot: {@code 0} and {@code -0} are both a zero tangent
     * and they are 0° and 180°. Degrees rather than radians because this is a readout for a person, and
     * whole-ish degrees because the interesting ones are the eighth turns the table lists.
     */
    private static String turn(T value) {
        return trim(Math.toDegrees(value.theta())) + "°";
    }

    /** As {@link #turn(T)}, from a pair already read as doubles. See {@link #projection(double, double)}. */
    static String turn(double p, double q) {
        return trim(Math.toDegrees(Math.atan2(p, q))) + "°";
    }

    /** Four decimals at most, and no trailing zeros: a readout quotes a number, it does not measure one. */
    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s.equals("-0") ? "0" : s;
    }

    private static String message(RuntimeException e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    private Algebra() {
    }
}
