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
    /** The slot the last {@link #show} claimed. See {@link #latest}. */
    private volatile PlotWindow latest;

    /**
     * @param app the application these open onto, or null for a capture -- see {@link #show}, which then
     *            mounts and paints a preview where it would otherwise have asked for a window
     */
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
        PlotWindow slot = claim();
        latest = slot;
        if (app == null) {
            // No application to open onto: the capture. The plot is nodes, and nodes are laid out and
            // photographed without a window -- so the same slot is mounted and painted on this thread
            // instead of on the frame a window would have been created on. See PlotWindow.headless.
            //
            // Every plot then recycles slot one, since a slot that never opens never counts as taken. That
            // is the right behaviour rather than a limitation of it: a capture photographs a preview before
            // it moves on to the next, so there is never a second one that needed keeping.
            // Mounted but not yet painted, and that is the handshake rather than an omission: the plot
            // paints off a laid-out canvas, and nothing has been laid out until something frames the tree.
            // Capture frames it and then calls settle, which is why that pair lives with the photograph.
            slot.headless(entry, typed, plottable);
        } else {
            slot.show(app, entry, typed, plottable);
        }
    }

    /**
     * Draw where {@code place} sits, in a preview of its own, and raise it — {@link #show}'s counterpart for a
     * value that has a place rather than a curve.
     *
     * <p>It claims a slot the same way and for the same reasons, which is worth stating because the alternative
     * was tempting: a spiral is cheap and always the same shape, so a single shared window for all of them would
     * have worked. It would also have made the one comparison this feature exists for impossible —
     * {@code 2÷0} beside {@code 2·0}, which are two turns apart on the same coil and say nothing at all apart.
     */
    void show(String entry, Place place) {
        PlotWindow slot = claim();
        latest = slot;
        if (app == null) {
            slot.headless(entry, place);
        } else {
            slot.show(app, entry, place);
        }
    }

    /**
     * The preview the last {@link #show} went to, or null if nothing has been plotted.
     *
     * <p>For the capture, which has to drive the plot it has just opened -- zoom it, turn it, ask it what it
     * cached -- and cannot go looking for it, because which slot a plot lands in is this class's business.
     */
    PlotWindow latest() {
        return latest;
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
