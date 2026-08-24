package dev.vexelray.demo.calculator;

import dev.vexelray.gui.plot.Expr;
import dev.vexelray.gui.plot.Interval;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import sibarum.cott.Render;
import sibarum.cott.Term;

/**
 * What is carrying the value here — the third line of a landmark's tooltip, and the only part of the plot that
 * answers a question about the <em>expression</em> rather than about the curve.
 *
 * <h2>The reading, and why this one</h2>
 * A landmark's height is a sum of terms, and the useful thing to know about it is which of them the total is
 * made of. So: take the expression's top-level additive terms, evaluate each at the landmark's x, and give each
 * one's magnitude as a share of the total magnitude. {@code x³ − 3x} at its minimum is 60% {@code x³} against
 * 40% {@code −3x}; a root is where the shares are two halves of a cancellation, which is a more useful thing to
 * be told at a crossing than "the value is zero".
 *
 * <p>Where the top level is not a sum — {@code 1÷(x²−1)} is a division — the descent goes into the operand that
 * carries the variable until it finds one, and says which subexpression it is talking about. Next to an
 * asymptote that is exactly the reading you want: the denominator's terms cancelling is <em>why</em> there is an
 * asymptote there.
 *
 * <h2>It is computed on the term that was typed</h2>
 * Not on the translated {@link Expr}. That is the whole reason this class is in the demo rather than in
 * {@code vexelray-gui-plot}: the plot module's expressions have no names a reader would recognise, while a COTT
 * {@link Term} renders back as {@code x³} and {@code −3x} through the same printer the display uses. An
 * influence reading whose terms did not look like what was typed would be answering about something else.
 *
 * <h2>What it declines to say</h2>
 * If no sum is reachable — {@code x³}, or a quotient with the variable on both sides of the bar — there is no
 * line. Inventing a decomposition for a single term would be filling the space rather than saying something,
 * and a tooltip with two true lines reads better than one with three of which one is padding.
 */
record Influence(String subject, List<Share> shares) {

    /** How many terms are named. Beyond this the tail is summarised rather than listed. */
    private static final int SHOWN = 3;

    /** A term whose share rounds below this is not worth a reader's attention. */
    private static final double NEGLIGIBLE = 0.005;

    /** One term of the sum, and how much of the total magnitude it accounts for. */
    record Share(String term, double fraction) {
    }

    /**
     * The influence reading for {@code term} at {@code x}, or null when there is nothing worth saying.
     *
     * @param term      the expression as it was typed, before reduction
     * @param variables its free variables, as {@link Plottable} counted them
     * @param x         where on the curve to read
     */
    static Influence at(Term term, List<String> variables, double x) {
        Term.Plus sum = nearestSum(term, variables);
        if (sum == null) {
            return null;
        }
        List<Term.Val> parts = sum.args();
        List<Double> magnitudes = new ArrayList<>(parts.size());
        double total = 0;
        for (Term.Val part : parts) {
            Double value = valueAt(part, variables, x);
            if (value == null) {
                return null;             // a term with no finite value here: the shares would not add up
            }
            double magnitude = Math.abs(value);
            magnitudes.add(magnitude);
            total += magnitude;
        }
        if (!(total > 0) || !Double.isFinite(total)) {
            return null;                 // every term is zero: there is nothing for them to be shares of
        }
        List<Share> shares = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            double fraction = magnitudes.get(i) / total;
            if (fraction >= NEGLIGIBLE) {
                shares.add(new Share(Render.show(parts.get(i)), fraction));
            }
        }
        if (shares.size() < 2) {
            return null;                 // one term carrying all of it is not a division of anything
        }
        shares.sort(Comparator.comparingDouble(Share::fraction).reversed());
        boolean whole = sum == term;
        return new Influence(whole ? null : Render.show(sum), List.copyOf(shares));
    }

    /** The line a tooltip shows: the terms and their shares, biggest first. */
    String line() {
        StringBuilder out = new StringBuilder(subject == null ? "carried by: " : "in " + subject + ": ");
        for (int i = 0; i < Math.min(SHOWN, shares.size()); i++) {
            Share share = shares.get(i);
            if (i > 0) {
                out.append("   ");
            }
            out.append(share.term()).append(' ').append(Math.round(share.fraction() * 100)).append('%');
        }
        if (shares.size() > SHOWN) {
            out.append("   +").append(shares.size() - SHOWN).append(" more");
        }
        return out.toString();
    }

    /**
     * The nearest sum at or below {@code term} that the variable actually reaches, or null if there is none.
     *
     * <p>The descent goes through the operand carrying the variable and stops the moment that operand is
     * ambiguous — a quotient with the variable above <em>and</em> below the bar has two sums in it and no reason
     * to prefer either, and picking one would be this class guessing on a reader's behalf.
     */
    private static Term.Plus nearestSum(Term term, List<String> variables) {
        Term at = term;
        for (int depth = 0; depth < 32; depth++) {
            switch (at) {
                case Term.Plus p when p.args().size() >= 2 -> {
                    return p;
                }
                case Term.Neg n -> at = n.of();
                case Term.Inv i -> at = i.of();
                case Term.Div d -> {
                    Term next = sole(variables, d.of(), d.by());
                    if (next == null) {
                        return null;
                    }
                    at = next;
                }
                case Term.Times t -> {
                    Term next = sole(variables, t.args().toArray(new Term[0]));
                    if (next == null) {
                        return null;
                    }
                    at = next;
                }
                case Term.Pow p -> {
                    Term next = sole(variables, p.base(), p.exponent());
                    if (next == null) {
                        return null;
                    }
                    at = next;
                }
                default -> {
                    return null;
                }
            }
        }
        return null;   // pathologically deep: stop rather than walk forever
    }

    /** The one candidate carrying a variable, or null if none does or more than one does. */
    private static Term sole(List<String> variables, Term... candidates) {
        Term found = null;
        for (Term candidate : candidates) {
            if (Plottable.variablesIn(candidate).stream().anyMatch(variables::contains)) {
                if (found != null) {
                    return null;
                }
                found = candidate;
            }
        }
        return found;
    }

    /**
     * One term's value at {@code x}, through the same translation the plot is drawn from — so a term that the
     * plotter refuses is a term this declines to talk about, rather than one it evaluates a different way.
     */
    private static Double valueAt(Term term, List<String> variables, double x) {
        Plottable readable = Plottable.read(term, variables);
        if (!readable.ok()) {
            return null;
        }
        Reading read = new Reading();
        readable.expr().enclose(Interval.at(x)).emitTo(read);
        return read.middle;
    }

    /** Reads a point enclosure back as a number, through the sink rather than by asking its type. */
    private static final class Reading implements dev.vexelray.gui.plot.Enclosure.Sink {

        private Double middle;

        @Override
        public void bounded(BigDecimal lo, BigDecimal hi) {
            double m = lo.add(hi).doubleValue() / 2;
            middle = Double.isFinite(m) ? m : null;
        }

        @Override
        public void unbounded() {
            // no finite value here
        }

        @Override
        public void undefined() {
            // nor here
        }
    }
}
