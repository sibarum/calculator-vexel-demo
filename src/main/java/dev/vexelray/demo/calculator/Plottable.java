package dev.vexelray.demo.calculator;

import dev.vexelray.gui.plot.Expr;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.TreeSet;
import sibarum.cott.Rational;
import sibarum.cott.Real;
import sibarum.cott.Term;

/**
 * The bridge between the two mathematics the calculator now holds: a COTT {@link Term}, read as a function of
 * one or two variables on the <b>real line</b>, so that {@code vexelray-gui-plot} can enclose it.
 *
 * <p>It is a translation and not a projection, and the difference is the whole of this class. COTT is a wheel:
 * {@code ω} is a point, {@code i} is a point, {@code 0÷0} is a residue that remembers which operand it came
 * from. The real line has none of those. So every term either has a real reading or it does not, and the ones
 * that do not are <b>refused by name</b> rather than approximated into something plottable — a plot of
 * {@code x + ω} that quietly dropped the ω would be a picture of a different expression.
 *
 * <p>Every one of those refusals is this projection losing something, and {@link Place} is where the lost
 * thing is drawn instead. The two are complementary and neither is a fallback for the other: a term with a
 * variable in it is a <b>function</b> and wants a curve, and one without is a <b>value</b> and wants a place.
 * What a variable and an {@code ω} in the same expression should look like is a real gap and is neither
 * class's yet.
 *
 * <h2>What a point is worth on the real line</h2>
 * Every numeral in COTT is {@code k} copies of {@code 0^(g + tω + r)}, so the reading is a small table:
 * <ul>
 *   <li>grade {@code 0} is 1, so {@code Pt(k, xp(0,0,0))} is {@code k} — every ordinary number;
 *   <li>grade above zero is 0, so {@code 0} itself and every multiple of it read as zero;
 *   <li>grade below zero is ω — <b>refused</b>, since infinity is not a value a <em>line</em> can hold. It is
 *       a place on {@link Place}'s torus, and {@code 2÷0} draws there;
 *   <li>a twist of 1 is {@code 0^ω = −1}, so it negates. Any other twist is off the real line ({@code 1/2} is
 *       {@code i}) and is <b>refused</b>, as is any torsion at all.
 * </ul>
 *
 * <h2>Three deliberate refusals worth stating</h2>
 * <b>{@code log} is not the logarithm.</b> COTT's {@code log(x, b)} is the base-0 exponent reading — the thing
 * that makes {@code lg(0^E) = E} — and on ordinary numbers it does not reduce at all ({@code log(8, 2)} comes
 * back as itself). Mapping it onto the natural logarithm the plot module offers would draw a curve the
 * calculator does not agree with, so it is refused instead. That refusal is a real gap in what can be plotted,
 * and it is a notation question rather than a plotting one.
 *
 * <p><b>The inverse circular functions have a value and no curve.</b> {@link Real}'s catalogue is bigger than
 * the plot module's set of nodes, and most of the difference is bridged by <em>building</em> one out of the
 * others — {@code sec} is a quotient of {@link Expr.Cos}, the hyperbolics are expressions in {@link Expr.Exp},
 * and their inverses are logarithms of something algebraic (see {@code call}). Nothing in
 * {@code Add/Mul/Div/Power/Exp/Log/Sin/Cos/Tan} composes into {@code arcsin}, so those are refused by name.
 * Closing that gap means new {@link Expr} nodes with sound enclosures and derivatives of their own, in the
 * module rather than here.
 *
 * <p><b>The residue families are refused too.</b> {@code 1^a} and {@code 0^a} are the forms the whole theory
 * exists to keep, and a residue is not a number on a line. Note that they are almost never reached from here
 * anyway: the term being translated is the one the user <em>typed</em>, before reduction, so {@code x÷x} is
 * still a division and plots as the constant 1 it is everywhere except the origin — which is exactly where the
 * enclosure algebra paints a pole, without being told to.
 *
 * <h2>One variable or two, and the difference is only in the binding</h2>
 * A term with one free variable is a curve and a term with two is a surface, but the translation below does not
 * branch on which: it maps every name in {@link #variables} to an {@link Expr.Param} and leaves the arity to
 * whoever draws it. That is the plot module's own arrangement showing through — a parameter answers whatever
 * region binds its name, so a curve is a surface whose cell happens to have one axis, and neither this class nor
 * the arithmetic below it has a two-variable case to keep in agreement with the one-variable one.
 *
 * @param variables the free variables the expression is a function of, in order, or empty when it was refused
 * @param expr      the expression, ready to enclose, or null when it was refused
 * @param refusal   why there is nothing to plot, or null when there is
 */
record Plottable(List<String> variables, Expr expr, String refusal) {

    /**
     * The one place the translation is not exact. A rational exponent that does not terminate in decimal —
     * {@code x^(1/3)} — is rounded to this precision, because {@code Expr.Power} needs a constant exponent and a
     * {@code BigDecimal} cannot hold a third. It is worth naming and not worth refusing: the perturbation is
     * around 1e-34, twenty-two orders of magnitude finer than the 1e-12 margin the module's own transcendentals
     * are widened by, so it cannot be the reason a plot is wrong.
     */
    private static final MathContext EXPONENT = MathContext.DECIMAL128;

    /** The constants that are constants: everything else opaque in a term is a variable. */
    private static final List<String> CONSTANTS = List.of("π", "e");

    /** The two twists that are {@code i} and {@code −i} — the complex numbers a keypad actually produces. */
    private static final Rational HALF_TURN = Rational.of(1, 2);
    private static final Rational THREE_HALF_TURNS = Rational.of(3, 2);

    /** Whether there is something to plot. */
    boolean ok() {
        return expr != null;
    }

    /** Whether this is a surface rather than a curve — the only question a renderer has to ask about arity. */
    boolean isSurface() {
        return variables.size() == 2;
    }

    /** The axis running left to right: the first variable, whatever it is called. */
    String across() {
        return variables.get(0);
    }

    /** The axis running into the picture. Only a surface has one. */
    String into() {
        return variables.get(1);
    }

    /**
     * Every variable in {@code term}, in order — the question "how many free variables is this?" answered
     * before anything is translated, because the answer decides whether a plot is even meaningful.
     *
     * <p>An {@link Term.Atom} is COTT's opaque symbol, and it is opaque for two different reasons: π and e have
     * no base-0 exponential form, while x, y and z have no value at all. Only the second kind is a variable, so
     * the constants are named here and everything else that is opaque is free.
     */
    static List<String> variablesIn(Term term) {
        TreeSet<String> found = new TreeSet<>();
        collect(term, found);
        return List.copyOf(found);
    }

    /** Read {@code term} as a real function of {@code variables}, or say why it cannot be read as one. */
    static Plottable read(Term term, List<String> variables) {
        if (variables.isEmpty() || variables.size() > 2) {
            throw new IllegalArgumentException("a plot is of one variable or two, not " + variables.size());
        }
        try {
            return new Plottable(List.copyOf(variables), translate(term, variables), null);
        } catch (Unreadable refused) {
            return new Plottable(List.of(), null, refused.getMessage());
        }
    }

    private static void collect(Term term, TreeSet<String> into) {
        switch (term) {
            case Term.Atom a -> {
                if (!CONSTANTS.contains(a.name())) {
                    into.add(a.name());
                }
            }
            case Term.Pt ignored -> { }
            case Term.Xp ignored -> { }
            case Term.Plus p -> p.args().forEach(arg -> collect(arg, into));
            case Term.Times t -> t.args().forEach(arg -> collect(arg, into));
            case Term.Neg n -> collect(n.of(), into);
            case Term.Inv i -> collect(i.of(), into);
            case Term.Div d -> { collect(d.of(), into); collect(d.by(), into); }
            case Term.Pow p -> { collect(p.base(), into); collect(p.exponent(), into); }
            case Term.Wind w -> collect(w.of(), into);
            case Term.AWind w -> collect(w.of(), into);
            case Term.Approx a -> collect(a.of(), into);
            case Term.Lg l -> collect(l.of(), into);
            case Term.Logb l -> { collect(l.base(), into); collect(l.of(), into); }
            case Term.Call c -> c.args().forEach(arg -> collect(arg, into));
        }
    }

    private static Expr translate(Term term, List<String> variables) {
        return switch (term) {
            case Term.Pt p -> point(p);
            case Term.Atom a -> atom(a, variables);
            case Term.Plus p -> fold(p.args(), variables, Expr.Add::new);
            case Term.Times t -> fold(t.args(), variables, Expr.Mul::new);
            case Term.Neg n -> new Expr.Sub(zero(), translate(n.of(), variables));
            case Term.Inv i -> new Expr.Div(one(), translate(i.of(), variables));
            case Term.Div d -> new Expr.Div(translate(d.of(), variables), translate(d.by(), variables));
            case Term.Pow p -> power(p, variables);
            // The residue families, and the projection that blurs them. Each one is a form that remembers an
            // operand, which is precisely what a point on a line cannot do.
            case Term.Wind w -> throw new Unreadable("1^" + w.of() + " is a residue, not a point on a line");
            case Term.AWind w -> throw new Unreadable("0^" + w.of() + " is a residue, not a point on a line");
            case Term.Approx a -> throw new Unreadable("an approximation has no curve to draw");
            case Term.Lg l -> throw new Unreadable(LOG_REFUSAL);
            case Term.Logb l -> throw new Unreadable(LOG_REFUSAL);
            case Term.Call c -> call(c, variables);
            case Term.Xp x -> throw new Unreadable("an exponent is not a value, so there is nothing to plot");
        };
    }

    private static final String LOG_REFUSAL =
            "log here returns COTT's exponent, not a logarithm -- there is no curve to draw";

    /**
     * A call from {@link Real}'s catalogue, as an expression the plot module can enclose.
     *
     * <p>Three of them are nodes there already — {@code sin}, {@code cos}, {@code tan} — and most of the rest
     * are <b>built out of those</b> rather than added to the module. That is a deliberate limit on how far this
     * bridge reaches: a new {@link Expr} node has to carry a sound interval enclosure and a derivative, and the
     * module's zero-dependency pom is not the place to acquire nine of them on a keypad's say-so. A quotient of
     * two existing nodes is already sound, and its poles fall out of the same divisor-straddles-zero test every
     * other pole here does — {@code sec} draws its poles without being told they are there.
     *
     * <p>What cannot be built that way is refused <b>by name</b>, as everything else in this class is. The
     * inverse circular functions are the gap: nothing in {@code Add/Mul/Div/Power/Exp/Log/Sin/Cos/Tan} composes
     * into {@code arcsin}, so a key that reaches one produces a number and does not produce a curve, and the
     * status line says which.
     */
    private static Expr call(Term.Call c, List<String> variables) {
        Real fn = Real.of(c.name());
        if (fn == null) {
            throw new Unreadable(c.name() + " is not a function this can draw");
        }
        if (c.args().size() != fn.arity()) {
            throw new Unreadable(c.name() + " takes " + fn.arity() + " here, not " + c.args().size());
        }
        Expr a = translate(c.args().get(0), variables);
        return switch (fn) {
            case SIN -> new Expr.Sin(a);
            case COS -> new Expr.Cos(a);
            case TAN -> new Expr.Tan(a);
            // The reciprocals. cot is cos÷sin rather than 1÷tan for the reason Expr.Tan's own derivative is
            // written as a quotient: one divisor, and its zeros are exactly the poles.
            case SEC -> new Expr.Div(one(), new Expr.Cos(a));
            case CSC -> new Expr.Div(one(), new Expr.Sin(a));
            case COT -> new Expr.Div(new Expr.Cos(a), new Expr.Sin(a));
            // The hyperbolics, out of the exponential. sinh comes out exact -- both halves are increasing in x,
            // so interval arithmetic on the sum is the true range -- while cosh and tanh come out sound and a
            // little wide, which is the ordinary price of writing a function as an expression in its argument.
            case SINH -> half(new Expr.Sub(exp(a), exp(negated(a))));
            case COSH -> half(new Expr.Add(exp(a), exp(negated(a))));
            case TANH -> new Expr.Div(new Expr.Sub(exp(a), exp(negated(a))),
                    new Expr.Add(exp(a), exp(negated(a))));
            // And their inverses, which ARE logarithms of something algebraic.
            case ASINH -> new Expr.Log(new Expr.Add(a, root(new Expr.Add(square(a), one()))));
            case ACOSH -> new Expr.Log(new Expr.Add(a, root(new Expr.Sub(square(a), one()))));
            case ATANH -> half(new Expr.Log(new Expr.Div(new Expr.Add(one(), a), new Expr.Sub(one(), a))));
            // Angle constructors: the radian is the native measure, so one of them is the identity.
            case RAD -> a;
            case DEG -> new Expr.Mul(a, new Expr.Const(BigDecimal.valueOf(Math.PI / 180)));
            default -> throw new Unreadable(c.name()
                    + " has a value here but no curve -- the inverse circular functions are not plottable yet");
        };
    }

    private static Expr exp(Expr of) {
        return new Expr.Exp(of);
    }

    private static Expr negated(Expr of) {
        return new Expr.Sub(zero(), of);
    }

    private static Expr half(Expr of) {
        return new Expr.Div(of, new Expr.Const(BigDecimal.valueOf(2)));
    }

    private static Expr square(Expr of) {
        return new Expr.Power(of, new Expr.Const(BigDecimal.valueOf(2)));
    }

    private static Expr root(Expr of) {
        return new Expr.Power(of, new Expr.Const(BigDecimal.valueOf(0.5)));
    }

    /**
     * {@code k} copies of {@code 0^(g + tω + r)}, read on the real line. The exact multiplicity is kept exact:
     * a coefficient of 5/2 becomes a division the interval arithmetic performs with its own outward rounding,
     * rather than a decimal that has already lost something before the sound arithmetic ever sees it.
     */
    private static Expr point(Term.Pt p) {
        if (!p.exp().torsion().isZero()) {
            throw new Unreadable("a torsion is a root of the residue zero and is not on the real line");
        }
        boolean negated = p.exp().twist().isOne();
        if (!negated && !p.exp().twist().isZero()) {
            // Every twist that is not a whole turn is off the real axis, and a HALF turn is i itself -- which
            // is what an expression like e^(iπx) has in it. Saying so is the difference between a message about
            // the carrier the theory happens to use and a message about what was typed: "a twist of 1÷2" is
            // true and useless, and the reader has an i on the screen in front of them.
            Rational twist = p.exp().twist();
            throw new Unreadable(twist.equals(HALF_TURN) || twist.equals(THREE_HALF_TURNS)
                    ? "this is complex-valued -- there is an i in it -- so there is no real curve to draw"
                    : "a twist of " + twist + " is off the real line, so there is no curve to draw");
        }
        int grade = p.exp().grade().signum();
        if (grade < 0) {
            throw new Unreadable("ω is not a value the real line holds");
        }
        Expr magnitude = grade > 0 ? zero() : rational(p.mult().numerator(), p.mult().denominator());
        return negated ? new Expr.Sub(zero(), magnitude) : magnitude;
    }

    private static Expr atom(Term.Atom a, List<String> variables) {
        if (a.name().equals("π")) {
            return new Expr.Const(Math.PI);
        }
        if (a.name().equals("e")) {
            return new Expr.Const(Math.E);
        }
        if (variables.contains(a.name())) {
            // The name travels into the expression rather than being flattened to "the variable": a cell binds
            // by name, and a surface whose two axes had the same name would be a curve drawn along a diagonal.
            return new Expr.Param(a.name());
        }
        throw new Unreadable(a.name() + " is a third variable; the plotter takes one or two");
    }

    /**
     * A power. Three readings, and which one applies is decided by where the variable is:
     * <ul>
     *   <li>a constant exponent is {@link Expr.Power}, which is what the module is built for — integer powers
     *       exactly, roots with their domain handled;
     *   <li>a constant base with a varying exponent is rewritten as {@code exp(x·ln b)}, because
     *       {@code Expr.Power} has nothing sound to say about an exponent that moves and would paint the whole
     *       column rather than draw {@code 2^x};
     *   <li>both varying — {@code x^x} — is refused. The arithmetic would answer Unbounded everywhere, which is
     *       sound and would fill the window with solid colour; saying so is more use than drawing it.
     * </ul>
     */
    private static Expr power(Term.Pow p, List<String> variables) {
        List<String> inExponent = variablesIn(p.exponent());
        if (inExponent.isEmpty()) {
            return new Expr.Power(translate(p.base(), variables), constantExponent(p.exponent(), variables));
        }
        if (p.base() instanceof Term.Atom a && a.name().equals("e")) {
            return new Expr.Exp(translate(p.exponent(), variables));
        }
        if (variablesIn(p.base()).isEmpty()) {
            double base = value(translate(p.base(), variables));
            if (!(base > 0)) {
                throw new Unreadable("a base of " + base + " raised to a varying power has no real curve");
            }
            return new Expr.Exp(new Expr.Mul(new Expr.Const(Math.log(base)), translate(p.exponent(), variables)));
        }
        throw new Unreadable("a power whose base and exponent both vary has no bound to draw");
    }

    /** The exponent slot, which {@link Expr.Power} needs as one number rather than as an expression. */
    private static Expr.Const constantExponent(Term exponent, List<String> variables) {
        Expr translated = translate(exponent, variables);
        if (translated instanceof Expr.Const c) {
            return c;
        }
        // A rational the exact route above left as a division, or a negation of one: fold it, accepting the
        // rounding named on EXPONENT.
        return new Expr.Const(BigDecimal.valueOf(value(translated)));
    }

    /** A rational coefficient, kept exact by leaving the division to the sound arithmetic. */
    private static Expr rational(java.math.BigInteger numerator, java.math.BigInteger denominator) {
        Expr top = new Expr.Const(new BigDecimal(numerator));
        return denominator.equals(java.math.BigInteger.ONE)
                ? top
                : new Expr.Div(top, new Expr.Const(new BigDecimal(denominator)));
    }

    /** The value of an expression that has no variable in it — enclosing it over a point column is enough. */
    private static double value(Expr constant) {
        Value read = new Value();
        constant.enclose(dev.vexelray.gui.plot.Interval.at(0)).emitTo(read);
        if (read.middle == null) {
            throw new Unreadable("a constant in this expression has no finite value");
        }
        return read.middle;
    }

    private static Expr fold(List<Term.Val> args, List<String> variables,
                             java.util.function.BinaryOperator<Expr> join) {
        Expr folded = null;
        for (Term.Val arg : args) {
            Expr next = translate(arg, variables);
            folded = folded == null ? next : join.apply(folded, next);
        }
        if (folded == null) {
            throw new Unreadable("an empty sum or product has nothing to plot");
        }
        return folded;
    }

    private static Expr zero() {
        return new Expr.Const(BigDecimal.ZERO);
    }

    private static Expr one() {
        return new Expr.Const(BigDecimal.ONE);
    }

    /** Reads a constant enclosure back out as a number, through the sink rather than by asking its type. */
    private static final class Value implements dev.vexelray.gui.plot.Enclosure.Sink {
        private Double middle;

        @Override
        public void bounded(BigDecimal lo, BigDecimal hi) {
            middle = lo.add(hi).divide(BigDecimal.valueOf(2), EXPONENT).doubleValue();
        }

        @Override
        public void unbounded() {
            // stays null: there is no number here
        }

        @Override
        public void undefined() {
            // likewise
        }
    }

    /** A term with no real reading, carrying the sentence the status line shows. */
    private static final class Unreadable extends RuntimeException {
        Unreadable(String because) {
            super(because);
        }
    }
}
