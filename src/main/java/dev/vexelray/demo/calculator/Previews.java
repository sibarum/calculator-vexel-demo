package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowMemory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import sibarum.cott.Term;

/**
 * The preview windows, and which one a new plot goes to.
 *
 * <p><b>Pressing = opens a preview of its own.</b> That is the whole of what this class is for, and it is a
 * change from the calculator's first plotting: there was one plot window, and evaluating a second expression
 * re-drew the first one's contents. Comparing two curves meant remembering the first. Now they sit side by side.
 *
 * <h2>Twelve slots, and why there is a number at all</h2>
 * A window in this framework is a <b>name</b>: {@code GuiApp.window(key, spec)} builds the tree the first time a
 * name is used and hands back the same window forever after, which is what makes "open the plot" and "focus the
 * plot that is already open" one call. The registry is permanent by design — a closed window keeps its tree so
 * that reopening it is instant — so a scheme that minted a fresh name per evaluation would accumulate one dead
 * tree per press of {@code =} and never let go of any of them.
 *
 * <p>So previews are a fixed set of slots. A new plot takes <b>the lowest slot with no window open on it</b>,
 * and if every slot is open it recycles the one shown longest ago. In ordinary use — open a plot, close it,
 * plot something else — that is always slot one, so nothing accumulates and the window comes back where it was.
 * Twelve is generous enough that reaching the cap means twelve plots really are on screen at once, and it is the
 * only thing standing between a hand resting on {@code =} and fifty GPU surfaces.
 *
 * <h2>Each slot remembers its own shape</h2>
 * Slot {@code n} keeps its placement, size, maximized state and UI zoom under its own key, which falls out of
 * {@link WindowMemory} needing one key per watched window and turns out to be the better behaviour anyway:
 * arrange three previews across the desk, quit, come back and plot three things, and they arrange themselves the
 * same way. What is still not remembered is whether any preview was <em>open</em> — a plot window reopened at
 * launch would have no expression to draw, which is the same reason the history window is not restored either.
 */
final class Previews {

    /** How many previews may be open at once. See the class note on why there is a ceiling. */
    private static final int SLOTS = 12;

    private final GuiApp app;
    private final WindowMemory memory;
    /** Applied to each preview's tree as it is built — the UI zoom shortcuts every window in this app has. */
    private final Consumer<Gui> prepare;

    private final PlotWindow[] slots = new PlotWindow[SLOTS];
    /** Slot indices, least recently shown first. The recycling order when every slot is taken. */
    private final List<Integer> recency = new ArrayList<>(SLOTS);

    Previews(GuiApp app, WindowMemory memory, Consumer<Gui> prepare) {
        this.app = app;
        this.memory = memory;
        this.prepare = prepare;
    }

    /**
     * Plot {@code plottable} in a preview of its own, and raise it.
     *
     * <p>Called from the worker thread the keypad's {@code =} handler runs on. Everything it touches is safe
     * from anywhere — the slot bookkeeping under this lock, and the window and tree work through the framework's
     * own thread-safe handles.
     */
    void show(String entry, Term typed, Plottable plottable) {
        claim().show(app, entry, typed, plottable);
    }

    /** As {@link #show}, but showing the marched surface rather than the box one — what {@code --march} opens. */
    void showMarched(String entry, Term typed, Plottable plottable) {
        PlotWindow preview = claim();
        preview.show(app, entry, typed, plottable);
        preview.march();
    }

    /**
     * Give every live preview a chance to march a frame. Called once per frame from the loop's
     * {@code beforeFrame} hook, which is the thread that presents.
     *
     * <p>A ray-march submits to the device queue and waits, and a {@code VkQueue} is not thread-safe — so this
     * cannot be driven from the worker a drag handler runs on without racing the presenter. Every viewport
     * therefore only sets a flag when the camera moves, and does its GPU work here. Slots with nothing marched
     * in them return immediately, which is every slot until someone presses <b>March</b>.
     */
    void pump() {
        for (PlotWindow slot : slots) {
            if (slot != null) {
                slot.pump();
            }
        }
    }

    /** The preview a new plot should go to: a free slot if there is one, else the one shown longest ago. */
    private synchronized PlotWindow claim() {
        int index = free();
        if (index < 0) {
            // Every slot is on screen. The one nobody has looked at for longest is the one to take, which is
            // the only point at which this class does anything a user could be surprised by -- and by then
            // there are twelve plot windows open, so a thirteenth replacing the oldest is the kind thing.
            index = recency.get(0);
        }
        if (slots[index] == null) {
            slots[index] = new PlotWindow("plot" + (index + 1), memory);
            prepare.accept(slots[index].gui());
        }
        recency.remove(Integer.valueOf(index));
        recency.add(index);
        return slots[index];
    }

    /** The lowest slot with nothing open on it, or −1 when every one of them is showing. */
    private int free() {
        for (int i = 0; i < SLOTS; i++) {
            if (slots[i] == null || !slots[i].open()) {
                return i;
            }
        }
        return -1;
    }
}
