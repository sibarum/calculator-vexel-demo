package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.layout.LayoutEnums;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Decorations;
import dev.vexelray.text.TextLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import sibarum.cott.Bindings;
import sibarum.cott.SyntaxException;

/**
 * What this session has named, as a window: {@code k = 3} on one line, {@code f(x) = x^2+1} on the next.
 *
 * <h2>Why a window and not a key</h2>
 * A definition is not an operation on the entry — it is a change to the language the entry is read in. Once
 * {@code k} is defined, every expression typed afterwards means something different, including ones already
 * sitting in the history tape, and a keypad has nowhere to show a fact of that size. So the definitions get a
 * surface of their own where the whole list is on view at once, which is also the only place the answer to
 * "why did that not parse" is ever going to be.
 *
 * <h2>The list is the state; the entry is a way to add to it</h2>
 * The {@link Bindings} this holds is the session's, and it is handed out on every change through the consumer
 * given at construction — the keypad's engine keeps the latest and reads expressions in it. Nothing here caches
 * a parse: a definition is stored as it was typed, and what its names mean is settled each time it is used, so
 * correcting {@code k} corrects everything that mentions it without this window having to know what did.
 *
 * <p><b>Tree work is queued</b>, as the history's is. Handlers arrive on worker threads and {@link #drain} runs
 * them from the frame loop, which is the thread that may build nodes.
 *
 * <h2>The hint carries the third form</h2>
 * A parameter written with empty brackets takes a <em>function</em> — {@code iter(f(), n) = f(f(n))}, called as
 * {@code iter(sin, x)} — and the entry's hint says so, because this window is where a grammar is learned. It is
 * the reason the hint is a line of examples rather than a sentence about definitions: there are three shapes and
 * the third is the one nobody guesses.
 */
final class Definitions {

    /** The name this window is opened, raised and remembered under. */
    private static final String KEY = "definitions";

    private static final int W = 400;
    private static final int H = 420;
    /** {@code TitleBar}'s own height in dp, as every window in this application has it. */
    private static final int BAR_H = 32;

    /** The smallest this window may be, in em — the layout's minimum and the window manager's, from one pair. */
    private static final float MIN_EM_W = 16f;
    private static final float MIN_EM_H = 12f;

    /**
     * What the entry says when it is empty — the whole grammar, in the place it is needed.
     *
     * <p>All three shapes, and the third earns its room: {@code f()} in a parameter list is the only notation
     * here that cannot be arrived at by trying, since {@code f} without the brackets is a perfectly good value
     * parameter and the body reads as a product rather than failing.
     */
    private static final String HINT = "k = 3    f(x) = x^2+1    iter(f(), n) = f(f(n))";

    private final WindowMemory memory;
    private final Consumer<Bindings> onChange;
    private final Gui gui = new Gui();
    private final TitleBar titleBar;
    private final TextField entry;
    private final Node list;
    private final Node status;
    private final Node empty;
    private final List<Node> rows = new ArrayList<>();
    private final ConcurrentLinkedQueue<Runnable> requests = new ConcurrentLinkedQueue<>();

    /**
     * How a request reaches the frame loop; this class's own queue unless a host supplies better.
     *
     * <p>See {@code CalculatorApp.History} for the argument. Standalone there is a {@link GuiApp} whose
     * queue is drained to exhaustion at the top of each iteration, so a request that opens a window
     * rides the same drain as the window operation it triggers; hosted and headless there is no such
     * queue, and the default keeps both working unchanged.
     */
    private volatile java.util.function.Consumer<Runnable> onFrameLoop = requests::add;

    /** Marshal requests through {@code sink} instead of this class's own queue. */
    void onFrameLoop(java.util.function.Consumer<Runnable> sink) {
        this.onFrameLoop = java.util.Objects.requireNonNull(sink, "sink");
    }

    private volatile Bindings bindings = Bindings.EMPTY;
    private volatile GuiApp app;
    private volatile AppWindow window;

    Definitions(WindowMemory memory, Consumer<Bindings> onChange) {
        this.memory = memory;
        this.onChange = onChange;

        this.entry = new TextField(gui, "");
        entry.node()
                .width(Length.FILL).height(Length.rem(2.5f))
                .background(Palette.PANEL).corner(Length.rem(0.5f)).border(Length.rem(0.1f), Palette.LINE)
                .padding(Length.dp(10))
                .textSize(Length.rem(1f)).textColor(Palette.INK);
        gui.focusable(entry.node(), true);
        gui.focus(entry.node());
        // Enter defines, and the entry clears only if it was accepted -- a rejected line stays put so it can be
        // corrected, which is the same rule the keypad's status line follows for a rejected expression.
        entry.onSubmit(line -> onFrameLoop.accept(() -> define(line)));

        this.status = gui.text(HINT)
                .width(Length.FILL).height(Length.rem(1.25f))
                .textSize(Length.rem(0.8125f)).textColor(Palette.DIM)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)
                .scroll(false, false);

        this.empty = gui.text("Nothing is defined yet.")
                .width(Length.FILL).height(Length.rem(1.5f))
                .textSize(Length.rem(0.875f)).textColor(Palette.DIM)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)
                .scroll(false, false);

        this.list = gui.column().width(Length.FILL).height(Length.grow(1))
                .gap(Length.rem(0.375f))
                .scroll(false, true)
                .scrollLock(LayoutEnums.ScrollLock.TOP)
                .children(empty);

        Node body = gui.column().width(Length.FILL).height(Length.FILL)
                .padding(Length.dp(12)).gap(Length.dp(8))
                .children(entry.node(), status, list);

        this.titleBar = new TitleBar(gui, WindowControls.NONE, "Definitions");
        gui.root().background(Palette.BG).children(titleBar.node(), body);
        gui.minSize(Length.em(MIN_EM_W), Length.em(MIN_EM_H));
    }

    /** This window's tree, so the application can bind its zoom shortcuts and clipboard here too. */
    Gui gui() {
        return gui;
    }

    /** What is defined right now. Read from the worker thread the keypad's = handler runs on. */
    Bindings bindings() {
        return bindings;
    }

    /**
     * The application this window opens onto. Held rather than passed at each {@link #show}, because the button
     * that shows it is a keypad key and a keypad key has no business knowing what an application is.
     */
    void attach(GuiApp app) {
        this.app = app;
    }

    /**
     * Open the window, or raise it if it is already up. Safe from anywhere; the framework does the thread hop.
     * Silent with no application attached, which is the headless captures — there is nothing to open onto.
     */
    void show() {
        GuiApp on = app;
        if (on == null) {
            return;
        }
        AppWindow open = window;
        if (open == null) {
            open = on.window(KEY, () -> titleBar.commands(WindowSpec
                    .of(memory.config(KEY, "Definitions", W, H + BAR_H).decorations(Decorations.CLIENT)
                            .minSize(CalculatorApp.smallest(gui, MIN_EM_W), CalculatorApp.smallest(gui, MIN_EM_H)),
                            gui)
                    .onCreated(this::placed)
                    .onClosed(() -> memory.forget(KEY))));
            window = open;
        }
        open.show();
    }

    /**
     * GUI thread, once per frame: apply whatever the entry and the row buttons asked for.
     *
     * <p>Everything queued, not one request. A loop that parks when nothing is happening has no next
     * frame to leave the remainder for, and nothing here can ask for one.
     */
    void drain() {
        for (Runnable r; (r = requests.poll()) != null; ) {
            r.run();
        }
    }

    // ---- editing --------------------------------------------------------------------------------

    /** What an attempt at a definition came to: exactly one of these is null. */
    record Made(Bindings.Definition definition, String refusal) {
    }

    /**
     * Define {@code line}, from anywhere and on any thread — the window's own entry, the keypad's display, or a
     * shell prompt. This is the one door, so that all three make the same thing happen.
     *
     * <p>The binding itself is settled on the calling thread, because {@link Bindings} is immutable and settling
     * it is one volatile write; only the list and the window are queued to the frame loop, which is the thread
     * that may build nodes. Synchronized so that two callers cannot each read the old bindings and write back a
     * version missing the other's definition.
     *
     * <p><b>A definition raises the window.</b> Naming something is a change to the language every later
     * expression is read in, and the list of what has been named is the only place that change is visible — so
     * the place it is visible comes up, wherever the definition was made from.
     */
    synchronized Made request(String line) {
        if (line.isBlank()) {
            return new Made(null, "a definition is name = expression");
        }
        Bindings next;
        try {
            next = bindings.define(line);
        } catch (SyntaxException e) {
            return new Made(null, e.getMessage());
        } catch (RuntimeException e) {
            return new Made(null, "that is not a definition");
        }
        bindings = next;
        onChange.accept(next);
        Bindings.Definition made = next.get(name(line));
        onFrameLoop.accept(this::apply);
        return new Made(made, null);
    }

    /** The name {@code line} defines — its head, up to the brackets or the equals, whichever comes first. */
    private static String name(String line) {
        String head = line.substring(0, line.indexOf('=')).trim();
        int open = head.indexOf('(');
        return (open < 0 ? head : head.substring(0, open)).trim();
    }

    /** GUI thread: the list caught up with the bindings, and the window that shows it comes forward. */
    private void apply() {
        status.text(HINT);
        relist();
        show();
    }

    /**
     * One line from this window's own entry. GUI thread — reached from {@link #drain} in an application, and
     * called straight through by the headless capture, which has one thread and pumps the queue itself.
     */
    void define(String line) {
        Made made = request(line);
        if (made.refusal() != null) {
            status.text(made.refusal());   // the line stays in the entry, to be corrected
            return;
        }
        entry.text("");
    }

    /** GUI thread. Forget one name. */
    private void forget(String name) {
        bindings = bindings.without(name);
        status.text(HINT);
        relist();
        onChange.accept(bindings);
    }

    /**
     * Rebuild the list from the bindings.
     *
     * <p>Rebuilt whole rather than patched, and deliberately: a definition may be <em>replaced</em> in place as
     * well as added or removed, and the row that changed is not always the one that was touched — redefining
     * {@code k} changes what {@code f} evaluates to without changing a character of f's own line. Diffing a
     * list this short to save a handful of nodes would be the kind of cleverness that eventually shows the wrong
     * text.
     */
    private void relist() {
        rows.forEach(Node::remove);
        rows.clear();
        empty.visible(bindings.isEmpty());
        for (Bindings.Definition d : bindings.all()) {
            Node row = row(d);
            rows.add(row);
            list.append(row);
        }
    }

    /** One definition: what was typed, and a button to forget it. */
    private Node row(Bindings.Definition d) {
        Node text = gui.text(d.source())
                .width(Length.grow(1)).height(Length.AUTO)
                .textSize(Length.rem(0.9375f)).textColor(Palette.INK)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)
                .scroll(false, false);
        Node drop = gui.text("×")
                .width(Length.rem(1.75f)).height(Length.rem(1.75f))
                .background(Palette.PANEL_HOVER).corner(Length.rem(0.375f))
                .textSize(Length.rem(1f)).textColor(Palette.DIM)
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE);
        gui.onState(drop, state -> drop.background(switch (state) {
            case NORMAL -> Palette.PANEL_HOVER;
            case HOVER -> Palette.BTN_BLUE_HOVER;
            case PRESSED -> Palette.BTN_BLUE_PRESSED;
        }));
        gui.onClick(drop, () -> onFrameLoop.accept(() -> forget(d.name())));

        Node row = gui.row().width(Length.FILL).height(Length.AUTO)
                .padding(Length.rem(0.5f), Length.rem(0.625f))
                .gap(Length.rem(0.5f)).alignItems(AlignItems.CENTER)
                .background(Palette.PANEL).corner(Length.rem(0.5f)).border(Length.rem(0.1f), Palette.LINE)
                .scroll(false, false)
                .children(text, drop);
        return row;
    }

    /** The window exists: put it back where it was left. */
    private void placed(dev.vexelray.os.NativeWindow created) {
        if (memory.maximized(KEY)) {
            created.maximize();
        } else {
            memory.restoreBounds(KEY, created, W, H + BAR_H);
        }
        memory.watch(KEY, created, gui);
    }
}
