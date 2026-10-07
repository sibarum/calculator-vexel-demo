package dev.vexelray.demo.calculator;

import sibarum.atchung.Committer;
import sibarum.atchung.State;
import sibarum.cott.calculator.Arithmetic;
import sibarum.cott.calculator.Calculator;
import sibarum.cott.calculator.CalculatorException;
import sibarum.cott.calculator.Escapes;
import sibarum.cott.calculator.Limits;
import sibarum.cott.calculator.Result;
import sibarum.cott.notation.SyntaxException;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The one authoritative {@link Doc}, and the only way to change it.
 *
 * <p>The evaluating is cott-engine's: a line goes to its {@link Calculator} and the {@link Result} is written
 * down as it comes back. That calculator is mutable — it holds the definitions — and handlers run on workers,
 * so every call into it holds its lock, and the commit happens under the same lock so that the tape is in the
 * order the lines were evaluated in.
 */
final class Model {

    private final Calculator calc = new Calculator();
    private final State<Doc> state;
    private final Committer<Doc, UnaryOperator<Doc>> edit;

    Model() {
        State.Builder<Doc> builder = State.of(Doc.initial(calc.arithmetic().label(), calc.limits().key()));
        this.edit = builder.mutation("doc.edit", (current, change) -> change.apply(current));
        this.state = builder.build();
    }

    /** The latest coherent snapshot. Lock-free, safe from any thread. */
    Doc doc() {
        return state.value();
    }

    /**
     * Evaluate a line and put it on the tape. Symbols may be typed as cott-engine's escapes ({@code \o} for
     * {@code ω}).
     *
     * @return whether the line was taken; a refused one leaves the reason in {@link Doc#error} and stays in the
     *         entry for fixing
     */
    boolean enter(String line) {
        if (line.isBlank()) return false;
        synchronized (calc) {
            try {
                Doc.Entry entry = entry(line, calc.enter(Escapes.expand(line)), calc.arithmetic(), calc.limits());
                state.commit(edit, d -> d.with(entry));
                return true;
            } catch (SyntaxException | CalculatorException e) {
                String reason = e.getMessage() == null ? "cannot read that" : e.getMessage();
                state.commit(edit, d -> d.withError(reason));
                return false;
            }
        }
    }

    /** Move to the next arithmetic. Definitions are kept, so the tape's next lines read them in the new one. */
    void nextArithmetic() {
        synchronized (calc) {
            Arithmetic[] all = Arithmetic.values();
            Arithmetic next = all[(calc.arithmetic().ordinal() + 1) % all.length];
            calc.set(next);
            state.commit(edit, d -> d.withArithmetic(next.label()));
        }
    }

    /** Move to the next recursion limits: how far the descent behind {@code cos} and {@code sin} may go. */
    void nextLimits() {
        synchronized (calc) {
            Limits[] all = Limits.values();
            Limits next = all[(calc.limits().ordinal() + 1) % all.length];
            calc.set(next);
            state.commit(edit, d -> d.withLimits(next.key()));
        }
    }

    /**
     * What the tape shows for a result. A line left with one free name is plotted over it; with more than one it
     * stays as written and says why there is no plot.
     */
    private static Doc.Entry entry(String line, Result result, Arithmetic arithmetic, Limits limits) {
        if (result instanceof Result.Unevaluated u) {
            Set<String> free = Graph.free(u.expr());
            if (free.size() == 1) {
                Graph graph = Graph.of(u.expr(), free.iterator().next(), arithmetic, limits);
                return graph.empty()
                        ? new Doc.Entry(line, u.text(), "nothing to plot: " + graph.refusal())
                        : new Doc.Entry(line, u.text(), "", graph);
            }
            return new Doc.Entry(line, u.text(), String.join(" and ", free)
                    + (free.size() == 2 ? " are both" : " are all") + " free, so there is nothing to plot");
        }
        if (result instanceof Result.Value v) {
            StringBuilder readings = new StringBuilder();
            v.readings().forEach((name, text) -> {
                if (!readings.isEmpty()) readings.append("    ");
                readings.append(name).append(' ').append(text);
            });
            return new Doc.Entry(line, "= " + v.text(), readings.toString());
        }
        return new Doc.Entry(line, result.text(), "");
    }

    /** React to every change, newest last; see the template's notes on {@code onCommitLatest}. */
    void onChange(Consumer<Doc> listener) {
        state.onCommitLatest(v -> listener.accept(v.value()));
    }
}
