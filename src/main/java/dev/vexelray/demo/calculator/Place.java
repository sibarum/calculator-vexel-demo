package dev.vexelray.demo.calculator;

import java.util.ArrayList;
import java.util.List;
import sibarum.cott.Rational;
import sibarum.cott.Render;
import sibarum.cott.Term;

/**
 * Where a value <b>sits</b>, for the values that have a place rather than a curve — {@link Plottable}'s
 * counterpart, and the answer to the thing that class refuses.
 *
 * <p>{@code Plottable} reads a term onto the <b>real line</b>, and the line is why it refuses: {@code ω} is not
 * a point of it, a zero is not distinguishable on it from the number zero, and a twist that is not a whole turn
 * is off it entirely. Every one of those refusals is a projection losing something, and this class is the
 * picture that does not lose it.
 *
 * <h2>Why the figure is a logarithmic spiral</h2>
 * Zero and ω add a dimension to the algebra the way {@code i} adds one to the reals, so the figure to draw a
 * value on is a <b>disc</b>: {@code 0} at the centre, {@code 1} a circle inside it, {@code ω} the edge. The
 * question is what the radial coordinate does between them, and the algebra answers it in three facts that a
 * ring of evenly spaced circles gets wrong and a logarithmic spiral gets right.
 *
 * <ul>
 *   <li><b>The grade is dense.</b> {@link Term.Xp#grade} is a {@link Rational}, not an integer, and the keypad
 *       reaches it: {@code 0^(1÷2)} is {@code pt(1, xp(1/2, 0, 0))} and {@code 0^(1÷3)} is a third. Anything
 *       that draws one figure per whole grade has nowhere to put those, and a mark at grade a half lands
 *       between two rings on no surface at all;
 *   <li><b>Grades add.</b> {@code 0^(2÷3)·0^(1÷3)} is {@code 0}. On a radius exponential in the grade,
 *       multiplying is <b>one similarity of the picture</b> — the same rotation and scaling wherever it is
 *       applied — because that is what adding to the parameter of a logarithmic spiral does. On evenly spaced
 *       rings, multiplying by {@code 0} is "move in one ring", which is not a motion of the plane at all;
 *   <li><b>Both ends are limits, not edges.</b> {@code 0, 0², 0³ …} converges into the centre and
 *       {@code ω, ω², ω³ …} runs away, and neither sequence stops. A spiral has room for all of both and needs
 *       no ceiling; a stack of rings needs one, and the ceiling is then an artefact of the figure rather than
 *       a fact about the algebra.
 * </ul>
 *
 * <p>And {@code r = e^(bθ)} is the only plane curve whose radius is exponential in its angle, while every value
 * in COTT is {@code 0^E}. Drawing a base-0 exponential algebra on the exponential curve is not an analogy.
 *
 * <h2>A base and a fibre, which is what a graded algebra is</h2>
 * The spiral fuses radius and angle into one parameter, so it holds <b>one</b> coordinate and not two. That is
 * not a shortage to be worked around — it is the shape of the object. A graded algebra is a <b>bundle</b>: the
 * grade group is the base, and the coefficient {@code k·e^(iπt)} lives in the fibre over it. So the spiral is
 * the base, carrying the grade and nothing else, and the tube swept along it is the fibre.
 *
 * <ul>
 *   <li><b>{@link Mark#turn}</b> — the base. {@code −g}, exactly, and nothing else is added to it. <b>One turn
 *       is one grade</b>, so the picture is the Riemann surface of the logarithm with the branch being the
 *       grade: {@code 0} is one turn in from {@code 1}, {@code 0²} is two, {@code ω} is one turn out. Because
 *       nothing else is in it, multiplying by {@code 0} shifts every value by exactly one turn, which is one
 *       rotation and one scaling — <b>a similarity of the whole picture</b>, which is the property the
 *       logarithmic spiral was chosen for;
 *   <li><b>{@link Mark#phase}</b> — round the fibre, and it is the <b>twist</b>. The twist closes at two and
 *       {@code 0^ω} is {@code −1}, so {@code π·t} is an angle that closes exactly when the twist does:
 *       {@code 1} at the crest, {@code −1} directly under it, {@code i} a quarter turn out, {@code −i} a
 *       quarter turn in;
 *   <li><b>{@link Mark#fibre}</b> — how far out in the fibre, and it is the <b>count</b>. The fibre is a disc
 *       rather than a circle, because the coefficient is a modulus as well as an argument: the count runs
 *       radially where the twist runs round.
 * </ul>
 *
 * <p>{@code Re} is not a coordinate and did not lose anything by not being one: it is
 * {@code magnitude·cos(π·t)}, which is what the three above already say together. It was always a projection,
 * and this is the picture that projection was of.
 *
 * <h2>Why the count cannot go on the grade's axis, which is where it was</h2>
 * It was, and the figure was wrong for a reason worth writing down, because the wrongness was not a badly
 * chosen constant.
 *
 * <p>COTT's order is <b>lexicographic</b>: every multiple of {@code 0} is below every positive real, however
 * many copies are taken, so the grade dominates the count <em>absolutely</em>. That is a non-Archimedean
 * order, and a single real coordinate is Archimedean. It cannot hold both, and the two ways of trying both
 * fail in the picture rather than in the abstract:
 *
 * <ul>
 *   <li>let the count contribute <b>linearly</b> in {@code log|k|} and multiplication stays a similarity, but
 *       the count <em>crosses</em> — a thousand copies of {@code 0} is drawn somewhere among the ordinary
 *       reals, which the algebra flatly denies;
 *   <li><b>bound</b> it and nothing crosses, but the count <em>converges</em>, so it piles up onto a fractional
 *       grade that has nothing to do with it. Measured: {@code 1000000·0} landed at turn {@code −0.650699} and
 *       {@code 0^(13÷20)} at {@code −0.650000}, a quarter of a degree apart. And multiplication by 2 stopped
 *       being a similarity, because a bounded map of {@code log|k|} is not additive: the same doubling shifted
 *       {@code ω→2ω} by {@code 0.060} of a turn and {@code 8ω→16ω} by {@code 0.043}.
 * </ul>
 *
 * <p>The fibre has none of that trouble, and it does not have it <b>by construction rather than by tuning</b>.
 * A count can never cross a grade because it cannot leave its fibre, and the fibres over consecutive grades are
 * disjoint exactly when {@link SpiralPlot}'s fibre is narrow enough not to touch the next coil — the condition
 * {@code ALPHA < tanh(ln(PITCH)/2)}, which was already in the renderer for what turns out to be the shallower
 * reason. So the non-Archimedean fact is drawn as a non-Archimedean picture: each grade carries a whole
 * bounded copy of the magnitude structure, and no amount of counting inside one reaches the next.
 *
 * <p>{@link #UNIT} is where one copy sits in its fibre — the middle, so that {@code 1÷2} is exactly as far the
 * other side of it as {@code 2} is this side, and so that a count running to zero or to infinity approaches the
 * fibre's centre line or its rim and reaches neither. <b>Every value the picture names has a count of one</b>
 * ({@code 1}, {@code 0}, {@code 0²}, {@code ω}, {@code −1} are all {@code pt(1, …)}), so the surface drawn at
 * {@link #UNIT} is the locus they all lie on, and a mark off it is a mark whose count is not one.
 *
 * <h2>What has no place</h2>
 * A <b>torsion</b> is refused by name. It closes at one where the twist closes at two, so it is a second phase
 * and would need a second tube; there is no third dimension left to sweep one along, and folding it into the
 * spiral's angle would be claiming a torsion is a grade, which it is not — a whole torsion is the identity and
 * a whole grade is not. Nothing the keypad builds reaches one: {@code 0^(ω÷2)} is {@code i}, {@code 0^(ω÷4)} is
 * a quarter twist, and the torsion slot stays zero throughout.
 *
 * <p>The <b>residue families</b> are refused, both of them, exactly as {@link Plottable} refuses them.
 * {@code 1^a} and {@code 0^a} are forms that remember an operand where a place is a point; and the two spell
 * themselves the way a literal power of 1 or 0 does, which is an open notation question rather than a drawing
 * one. So {@code 2−2} draws nothing while {@code 0·0} draws {@code 0²}, and the difference is which of the two
 * the engine actually produced.
 *
 * <p>An {@link Term.Atom} has no place either, so {@code π+ω} is refused whole rather than drawn as one of its
 * two terms. A standing {@code Plus} <em>is</em> drawn, as one mark per summand: a sum of unlike exponents is
 * left standing precisely because it is not one place, and showing the several places it is instead is the
 * honest picture of that.
 *
 * @param marks   where the value is, one mark per term of it, or empty when it was refused
 * @param refusal why there is nothing to place, or null when there is
 */
record Place(List<Mark> marks, String refusal) {

    /**
     * Where a count of one sits in its fibre, as a fraction of the fibre's radius.
     *
     * <p>The middle, and it has to be the middle for the map to be honest: the count runs from zero to
     * infinity and {@code 1} is nowhere special in that range except that it is the geometric centre of it, so
     * any other choice would make {@code 2} and {@code 1÷2} unequal distances from it.
     */
    private static final double UNIT = 0.5;

    /**
     * How many <em>e</em>-folds of count the fibre spends most of its radius over.
     *
     * <p>It sets how quickly the compression bites, and the useful end is the small counts, which are the ones
     * that occur, so it is set from those: at two, a doubling carries a mark a third of the way from the unit
     * locus to the rim, which is a displacement a reader can actually see. Ten copies reach four fifths of the
     * way and a thousand are pressed against it, which is the compression doing what it is for.
     */
    private static final double COUNT_SCALE = 2;

    /** A guard on the exponentials downstream rather than a claim about the algebra. */
    private static final double TURN_LIMIT = 512;

    /** Whether there is something to draw. */
    boolean ok() {
        return refusal == null && !marks.isEmpty();
    }

    /** The turn nearest the centre that has to be on the picture. */
    double innermost() {
        double least = 0;
        for (Mark mark : marks) {
            least = Math.min(least, mark.turn());
        }
        return least;
    }

    /** The turn furthest out that has to be on the picture. */
    double outermost() {
        double most = 0;
        for (Mark mark : marks) {
            most = Math.max(most, mark.turn());
        }
        return most;
    }

    /**
     * Whether {@code value} is somewhere the real line cannot hold it, and therefore whether pressing
     * {@code =} on it has a picture to offer at all.
     *
     * <p>This is the gate and it is deliberately narrow. An ordinary number is an ordinary number: {@code 2+2}
     * answers 4 and says nothing else, and {@code −1} is a real value that happens to be spelled {@code 0^ω},
     * so neither opens a window. A grade, a twist that is not a whole turn, or any torsion at all is what puts
     * a value off the line, and those are exactly the values whose picture this is.
     */
    static boolean offTheLine(Term value) {
        return switch (value) {
            case Term.Pt p -> !p.exp().grade().isZero()
                    || !(p.exp().twist().isZero() || p.exp().twist().isOne())
                    || !p.exp().torsion().isZero();
            case Term.Neg n -> offTheLine(n.of());
            case Term.Plus p -> p.args().stream().anyMatch(Place::offTheLine);
            default -> false;
        };
    }

    /** Read {@code value} as a place on the spiral, or say why it has none. */
    static Place read(Term value) {
        List<Mark> found = new ArrayList<>();
        try {
            collect(value, found, false);
        } catch (Unplaceable refused) {
            return new Place(List.of(), refused.getMessage());
        }
        return new Place(List.copyOf(found), null);
    }

    private static void collect(Term value, List<Mark> into, boolean negated) {
        switch (value) {
            case Term.Pt p -> into.add(mark(p, negated));
            case Term.Neg n -> collect(n.of(), into, !negated);
            // A standing sum is several places rather than one, and drawing all of them is what says why it is
            // standing. Nested sums cannot occur -- Plus is held flat -- but the recursion costs nothing.
            case Term.Plus p -> p.args().forEach(arg -> collect(arg, into, negated));
            case Term.Atom a -> throw new Unplaceable(a.name() + " has no place on the spiral, so a sum with "
                    + a.name() + " in it has none either");
            case Term.Wind w -> throw new Unplaceable("1^" + Render.show(w.of())
                    + " is the multiplicative residue -- a form that remembers an operand, not a place");
            case Term.AWind w -> throw new Unplaceable("0^" + Render.show(w.of())
                    + " is the additive residue -- a form that remembers an operand, not a place");
            default -> throw new Unplaceable(Render.show(value) + " did not reduce to a point, so it has no"
                    + " place to be drawn at");
        }
    }

    /**
     * One point, placed. See the class note for where each of the two coordinates comes from; this is that
     * table and nothing else.
     */
    private static Mark mark(Term.Pt p, boolean negated) {
        if (!p.exp().torsion().isZero()) {
            throw new Unplaceable("a torsion is a second phase and this figure has one tube, so "
                    + Render.show(p) + " has no place here");
        }
        Rational k = p.mult();
        Rational g = p.exp().grade();
        double count = Math.abs(value(k));
        double grade = value(g);
        if (count == 0) {
            throw new Unplaceable(Render.show(p) + " has no copies of anything, so there is nowhere to put it");
        }
        if (Math.abs(grade) > TURN_LIMIT) {
            throw new Unplaceable(Render.show(p) + " is " + Math.round(Math.abs(grade))
                    + " grades from 1, past the " + (int) TURN_LIMIT + " this picture reaches");
        }
        // Negating is a half turn -- 0^ω is -1 -- so a Neg and a sign on the multiplicity are the same fact
        // written twice, and both are folded into the twist rather than being kept as a sign anywhere.
        double twist = value(p.exp().twist()) + (negated != (value(k) < 0) ? 1 : 0);
        // The base is the grade and only the grade. That is what keeps multiplying by 0 a rigid motion of the
        // picture, and it is the whole reason the count is not welcome here.
        return new Mark(Render.show(negated ? new Term.Neg(p) : p),
                        -grade, wrap(Math.PI * twist), fibre(count),
                        rational(g), count);
    }

    /**
     * How far out in its fibre a count sits, as a fraction of the fibre's radius: {@link #UNIT} at one copy,
     * approaching the centre line as the count goes to zero and the rim as it goes to infinity, and reaching
     * neither. {@code tanh} of the log, which is the shortest thing that is monotone, bounded and symmetric
     * under {@code k ↔ 1÷k}.
     */
    private static double fibre(double count) {
        return UNIT * (1 + Math.tanh(Math.log(count) / COUNT_SCALE));
    }

    /** Into {@code [0, 2π)}, which is where a phase lives. */
    private static double wrap(double angle) {
        double turn = 2 * Math.PI;
        double at = angle % turn;
        return at < 0 ? at + turn : at;
    }

    private static double value(Rational r) {
        return r.numerator().doubleValue() / r.denominator().doubleValue();
    }

    /** A rational as the calculator spells one, for the readout to name a grade of a half rather than 0.5. */
    private static String rational(Rational r) {
        return r.toString().replace('/', '÷');
    }

    /**
     * One term of the value, placed.
     *
     * <p>The first two fields are the picture's and the last two are the algebra's. A renderer turns
     * {@code (turn, phase)} into a position and needs nothing else; {@code grade} and {@code count} are carried
     * so that the readout can say what the position <em>means</em> without re-deriving it from a pair of
     * angles it would have to invert an exponential to read.
     *
     * @param label the term as the calculator spells it, for the dot to be named by
     * @param turn  the base: how far along the spiral, in grades, outward positive — one turn is one grade
     * @param phase round the fibre, {@code 0} at the crest where the positive reals are, {@code π} underneath
     * @param fibre out across the fibre, as a fraction of its radius — {@link #UNIT} for a count of one
     * @param grade the grade as the calculator spells it
     * @param count how many copies, as a magnitude
     */
    record Mark(String label, double turn, double phase, double fibre, String grade, double count) {
    }

    /** A term with no place, carrying the sentence the status line shows. */
    private static final class Unplaceable extends RuntimeException {
        Unplaceable(String because) {
            super(because);
        }
    }
}
