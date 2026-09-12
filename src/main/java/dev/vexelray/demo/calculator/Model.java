package dev.vexelray.demo.calculator;

import sibarum.atchung.State;
import sibarum.atchung.Committer;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The one authoritative {@link Scene}, and the only way to change it.
 *
 * <h2>Relative edits, not absolute writes</h2>
 *
 * <p>The framework's resolved decision 11 is about exactly this, and the reason it gives is the one that bites:
 * a widget that recomputes a whole value from a read it took a moment ago produces something perfectly coherent
 * with one change missing, and <b>nothing reports it</b>. So every change here is a function applied to
 * whatever the current value turns out to be, committed through {@link State}. Under contention that costs a
 * CAS retry; it can never cost an edit.
 *
 * <p>That is why the mutations are declared as {@link Committer}s rather than as a sealed {@code Edit} type,
 * which is what this design originally proposed. Atchung wants named commands: the names are what
 * {@code State.mutationNames()} publishes, they are the hook a replicated peer would bind to, and each carries
 * its own payload type so the compiler checks the pairing. A sealed hierarchy would have been a second
 * vocabulary sitting on top of one that already exists.
 *
 * <h2>Handlers run on workers; this is the serialization point</h2>
 *
 * <p>Every panel control's {@code onChange} fires on the handler executor, so several can be in flight at once.
 * They all arrive here, and the CAS is what puts them in an order. Nothing else in the application needs a lock.
 */
final class Model {

    private final State<Scene> state;

    /**
     * One committer for "apply this function".
     *
     * <p>A single generic mutation rather than twenty named ones, and the trade is worth stating: the names are
     * the vocabulary a remote peer would bind to, so collapsing them to one costs that. It buys not having to
     * declare a committer per control before knowing what the controls are — and when the algebra lands and
     * this state is worth replicating, splitting one committer into named ones is a mechanical change that the
     * compiler drives. Recorded so it is a decision with a trigger rather than a shortcut.
     */
    private final Committer<Scene, UnaryOperator<Scene>> edit;

    Model() {
        State.Builder<Scene> builder = State.of(Scene.initial());
        // Declared before build, held as a handle: State refuses a committer it was not built with, which is
        // what stops an unrelated component minting its own way to write this.
        this.edit = builder.mutation("scene.edit", (current, change) -> change.apply(current));
        this.state = builder.build();
    }

    /** The latest coherent snapshot. Lock-free, safe from any thread. */
    Scene scene() {
        return state.value();
    }

    /** Apply a change to whatever the scene currently is. */
    void change(UnaryOperator<Scene> change) {
        state.commit(edit, change);
    }

    /**
     * Commit a new expression: read it, and settle everything derived from it in the same version.
     *
     * <p>One commit rather than two, so there is no instant at which the expression and the reading disagree —
     * which would be a frame in which the badge says {@code SURFACE} over a line plot.
     */
    void submit(String expression) {
        change(s -> new Scene(expression, Algebra.read(expression),
                s.omega(), s.x0(), s.x1(), s.samples(), s.lineWidth(), s.furniture(), s.ramp(),
                s.cards(), s.cropping(), s.spinning()));
    }

    /**
     * React to every change, on the committing thread.
     *
     * <p>Inline delivery, deliberately: the listener here rebuilds geometry and republishes the panels, and both
     * of those are already off the GUI thread because handlers are. Handing it to another executor would add a
     * hop and an ordering question for no gain.
     */
    void onChange(Consumer<Scene> listener) {
        state.onCommit(v -> listener.accept(v.value()));
    }

    /** The version counter — what a test or a driver waits on to know a change landed. */
    long version() {
        return state.version();
    }
}
