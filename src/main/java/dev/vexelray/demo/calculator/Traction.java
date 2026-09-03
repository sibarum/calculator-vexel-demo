package dev.vexelray.demo.calculator;

import sibarum.cott.Rational;
import sibarum.cott.Term;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a reduced COTT term says about where a value sits in traction space — and, just as often, that it does
 * not say.
 *
 * <p>This is the whole of the bridge between the evaluator and any plot: one pure function, {@link #read},
 * from the term {@code Cott.reduce} hands back to a reading a renderer can act on. It deliberately stops short
 * of choosing coordinates. Placing a point is a decision about the theory; reporting what the term
 * <em>contains</em> is not, and the two are separated here so that a change of projection never has to
 * re-derive the reading, and a change of evaluator never has to re-derive the projection.
 *
 * <h2>The three readings</h2>
 *
 * A reduced term is exactly one of:
 *
 * <ul>
 *   <li>{@link Value} — a sum of {@link Component}s, each {@code k·0^E}. Everything the theory calls a value.
 *   <li>{@link Partial} — erasure is on show ({@code z−z} or {@code z/z}). Not a failure and not a value:
 *       an erasure is a term that has been <em>partially</em> evaluated, so it is not in simplest terms and
 *       has no business being given a position.
 *   <li>{@link Stuck} — no applicable rule fired. {@code Cott.reduce} is total and returns such a term
 *       unchanged, which is a feature of the evaluator rather than a fault, and it is common at exactly the
 *       places a plot is most interested in: {@code 1/(x²−1)} at {@code x = 0} reduces to {@code 1/(0^2−1)}
 *       and stops, because a sum of unlike exponents is left standing.
 * </ul>
 *
 * <h2>The three spellings of negative</h2>
 *
 * The evaluator has more than one way to be negative, and a reading that did not fold them together would put
 * one value in two places. A typed {@code -1} parses to {@code neg(pt(1, xp(0,0,0)))}; {@code 0^ω} reduces to
 * {@code pt(1, xp(0,1,0))}, carrying the sign in the twist; and arithmetic produces negative multipliers
 * directly ({@code pt(-4/3, xp(0,0,0))}). All three are the same point, so {@link #read} normalises the first
 * two onto the third — {@code Neg} is pushed into the multiplier, and a twist of exactly one is the factor
 * {@code 0^ω = −1}, so it too becomes a sign. A twist that is neither zero nor one is a genuine diagonal and
 * is left alone; folding it would be a lie rather than a normalisation.
 *
 * @see #read(Term)
 */
public sealed interface Traction {

    /**
     * One {@code k·0^E} summand, with the exponent's triple laid out.
     *
     * <p>{@code grade}, {@code twist} and {@code torsion} are the three slots of the COTT exponent. Twist
     * closes at two and torsion at one, which the evaluator enforces at construction, so the values here are
     * always the representatives in {@code [0, 2)} and {@code [0, 1)}.
     *
     * @param mult    how many copies — the {@code k}. Addition on the traction axis makes multipliers
     *                ({@code 0+0 = 2·0}); multiplication makes rungs ({@code 0*0 = 0^2}).
     * @param grade   the rational power of zero. {@code 1} is zero itself, {@code −1} is ω.
     * @param twist   the ω-component of the exponent, closing at two. Zero and one are the two real signs and
     *                are folded into {@code mult} by {@link #read}; anything else is a diagonal.
     * @param torsion the root-of-residue-zero component, closing at one. Never folded.
     */
    record Component(Rational mult, Rational grade, Rational twist, Rational torsion) {

        /** No twist and no torsion: {@code k·0^g} for a plain rational {@code g}. */
        public boolean isPlain() {
            return twist.isZero() && torsion.isZero();
        }

        /** A plain component of grade zero — {@code k·0^0 = k}, a point on the real axis. */
        public boolean isReal() {
            return isPlain() && grade.isZero();
        }

        /**
         * Where this component sits, when it sits anywhere.
         *
         * <p>A grade-zero component is the real part: {@code k·0^0 = k}, at {@code (k, 0)}. A component of
         * grade {@code g} with multiplier one is a pure traction value, at {@code (0, g)} — {@code 0^1} has no
         * real part at all, which is what keeps it off the real axis and distinct from {@code 1 + 0^1}.
         *
         * <p>Empty in the two cases the chart has no room for: a component with twist or torsion, which is a
         * diagonal rather than a position on either axis; and a multiplier other than one on a traction rung,
         * as {@code 2·0} has. That last one is still open — see {@link Value#point()}.
         */
        public Optional<Point> place() {
            if (!isPlain()) {
                return Optional.empty();
            }
            if (grade.isZero()) {
                return Optional.of(new Point(mult, Rational.ZERO));
            }
            return mult.isOne() ? Optional.of(new Point(Rational.ZERO, grade)) : Optional.empty();
        }
    }

    /**
     * A point in 2D traction space: how far along the real axis, and how many rungs up the traction axis.
     *
     * <p><b>Ones add, zeros multiply.</b> The real coordinate is reached additively — {@code 1+1} is two steps
     * out — and the traction coordinate multiplicatively, {@code 0·0 = 0^2} being one rung up. So the two
     * numbers are not two lengths in the same units: the second counts powers of zero, and moving along it is
     * done by multiplying rather than by adding.
     *
     * <p>The origin is {@link #ERASURE}, {@code (0, 0)} — erasure, and not the number zero, which is at
     * {@code (0, 1)}, one rung up. A value never lands on the origin: having neither a real part nor a traction
     * part is what erasure <em>is</em>, and that reads as {@link Partial} rather than as a {@link Value}.
     *
     * @param real     the real coordinate — the additive part, {@code Re}
     * @param traction the traction coordinate — the grade of the power of zero, {@code Tr}
     */
    record Point(Rational real, Rational traction) {

        /** The traction origin. Not the number zero, and not any value. */
        public static final Point ERASURE = new Point(Rational.ZERO, Rational.ZERO);

        /** On the real axis: no rungs climbed. */
        public boolean isReal() {
            return traction.isZero();
        }

        /** On the traction axis: no real part, as {@code 0^1} and ω both have none. */
        public boolean isPureTraction() {
            return real.isZero();
        }
    }

    /**
     * A sum of components. The list is in the order the evaluator produced it and is never empty.
     *
     * <p>Being a {@code Value} does not make a term plottable in two dimensions — see {@link #canonical()}.
     */
    record Value(List<Component> components) implements Traction {

        public Value {
            components = List.copyOf(components);
            if (components.isEmpty()) {
                throw new IllegalArgumentException("a value has at least one component");
            }
        }

        /**
         * This value as a point, when it is in the form {@code Re + 0^Tr}.
         *
         * <p>That form is the whole of what the chart holds, and it covers what ordinary arithmetic actually
         * produces: a numeral is {@code (Re, 0)}, a power of zero is {@code (0, Tr)}, and the two together are
         * {@code (Re, Tr)}. Grade-zero components are summed into the real part, since they <em>are</em> the
         * real part.
         *
         * <p>Empty in three cases, different in kind:
         *
         * <ul>
         *   <li>a component with twist or torsion — a diagonal, off both axes;
         *   <li>more than one traction rung, as {@code 0^2 + 0^3} has. There is no single {@code Tr} to
         *       report, and the value is a formal sum rather than a position;
         *   <li>a multiplier other than one on a traction rung, as {@code 2·0} has. <b>This one is open.</b>
         *       Every combination law tried so far either made zero an additive identity or sent {@code 1+0}
         *       and {@code 2·0} to the same point, and the engine keeps them distinct, so the reading refuses
         *       rather than picks.
         * </ul>
         *
         * The components are all still there to be read either way. What is refused is a position, not the
         * value.
         */
        public Optional<Point> point() {
            Rational real = Rational.ZERO;
            Rational traction = null;
            for (Component component : components) {
                if (!component.isPlain()) {
                    return Optional.empty();
                }
                if (component.grade().isZero()) {
                    real = real.add(component.mult());
                } else if (traction != null || !component.mult().isOne()) {
                    return Optional.empty();
                } else {
                    traction = component.grade();
                }
            }
            return Optional.of(new Point(real, traction == null ? Rational.ZERO : traction));
        }
    }

    /**
     * Erasure is on show, so the term is partially evaluated rather than simplest.
     *
     * @param term the term as reduced, erasure and all — an erasure carries the operand it discharged, which
     *             is what makes it reversible, so nothing is thrown away by keeping it whole
     */
    record Partial(Term term) implements Traction {
    }

    /**
     * The evaluator had no applicable rule and returned the term unchanged.
     *
     * @param term   the term as it stands
     * @param reason what stopped the reading, in the vocabulary of the theory rather than of Java
     */
    record Stuck(Term term, String reason) implements Traction {
    }

    /**
     * Read a reduced term.
     *
     * <p>Total: every term gets one of the three readings, and none of them is an exception. Pass the output of
     * {@code Cott.reduce} — reading an unreduced term is not wrong so much as premature, since the components
     * it reports would be the ones the evaluator had not finished with.
     */
    static Traction read(Term term) {
        List<Component> into = new ArrayList<>();
        Traction refusal = collect(term, Rational.ONE, into);
        return refusal != null ? refusal : new Value(into);
    }

    /**
     * Accumulate {@code term}'s components, each scaled by {@code sign}, into {@code into}.
     *
     * @return {@code null} when the whole term was components, or the reading that refuses it
     */
    private static Traction collect(Term term, Rational sign, List<Component> into) {
        return switch (term) {
            case Term.Pt pt -> {
                Term.Xp e = pt.exp();
                Rational mult = pt.mult().multiply(sign);
                // 0^ω = −1, so a twist of exactly one is a sign rather than a direction. Zero and one are the
                // only twists that are; the rest are diagonals and keep their slot.
                if (e.twist().isOne()) {
                    into.add(new Component(mult.negate(), e.grade(), Rational.ZERO, e.torsion()));
                } else {
                    into.add(new Component(mult, e.grade(), e.twist(), e.torsion()));
                }
                yield null;
            }
            case Term.Neg neg -> collect(neg.of(), sign.negate(), into);
            case Term.Plus plus -> {
                for (Term.Val arg : plus.args()) {
                    Traction refusal = collect(arg, sign, into);
                    if (refusal != null) {
                        yield refusal;
                    }
                }
                yield null;
            }
            case Term.Wind w -> new Partial(w);
            case Term.AWind w -> new Partial(w);
            case Term.Approx a -> new Stuck(a, "an approximation exits the type");
            case Term.Atom atom -> new Stuck(atom, "an atom has no point reading: " + atom.name());
            default -> new Stuck(term, "no rule applied, so the theory has no value here");
        };
    }
}
