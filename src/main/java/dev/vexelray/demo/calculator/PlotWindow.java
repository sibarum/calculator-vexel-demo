package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.WindowControls;
import dev.vexelray.gui.core.app.AppWindow;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.app.WindowSpec;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.LayoutEnums.AlignItems;
import dev.vexelray.gui.widget.TitleBar;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.text.TextLayout;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;

/**
 * The plot's window: the chrome around {@link PlotSurface}, and everything the user reaches for.
 *
 * <p>It is a <b>named window</b> ({@link GuiApp#window}) rather than a popup the application tracks itself,
 * which is the difference between "open the plot" and "open a plot". Pressing = on a second expression
 * re-plots in the window that is already there and raises it; the tree behind it is built once, here, and
 * outlives every open and close, so the window comes back where it was with what it was showing.
 *
 * <h2>Four ways to move, because they suit different questions</h2>
 * Dragging is for "what is over there", the wheel is for "what is happening exactly here" (it zooms about the
 * pointer, so the feature under it stays under it), the buttons are for a deliberate step, and the arrow keys
 * are for a nudge without leaving the keyboard. <b>Fit</b> is the one that is not a transform at all: it runs
 * the framing policy again over the x window currently on screen, which is what you want after panning
 * somewhere the original framing never looked.
 *
 * <p>The wheel arrives straight off the bus rather than through a node handler. Scroll dispatch belongs to
 * scrollable containers, and the plot is emphatically not one — it has no content to scroll, it has a window
 * onto a plane — so the application takes the raw event and asks the surface whether the pointer was over it.
 */
final class PlotWindow {

    private static final int W = 720;
    private static final int H = 560;
    /** {@code TitleBar}'s own height in dp, as the main window has it. */
    private static final int BAR_H = 32;

    private final WindowMemory memory;
    private final Gui gui = new Gui();
    private final TitleBar titleBar;
    private final PlotSurface surface;
    private final Node heading;
    private final Node status;
    private volatile AppWindow window;

    PlotWindow(WindowMemory memory) {
        this.memory = memory;
        this.heading = gui.text("")
                .width(Length.FILL).height(Length.rem(1.5f))
                .textSize(Length.rem(1f)).textColor(Palette.INK)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)
                .scroll(false, false);
        this.status = gui.text("")
                .width(Length.FILL).height(Length.rem(1.25f))
                .textSize(Length.rem(0.8125f)).textColor(Palette.DIM)
                .align(TextLayout.HAlign.LEFT, TextLayout.VAlign.MIDDLE)
                .scroll(false, false);
        this.surface = new PlotSurface(gui, status::text);

        Node controls = gui.row().width(Length.FILL).height(Length.rem(2f))
                .gap(Length.dp(8)).alignItems(AlignItems.STRETCH)
                .children(button("−", () -> step(-1)),
                          button("+", () -> step(1)),
                          button("Fit", surface::fitVertically),
                          button("Reset", this::home),
                          status);

        Node body = gui.column().width(Length.FILL).height(Length.FILL)
                .padding(Length.dp(12)).gap(Length.dp(8))
                .children(heading, surface.node(), controls);

        this.titleBar = new TitleBar(gui, WindowControls.NONE, "Plot");
        gui.root().background(Palette.BG).children(titleBar.node(), body);

        gui.minSize(Length.em(18), Length.em(14));
        // Bare +/- move the plot's window; Ctrl+= and friends scale the UI, and are bound where every other
        // window's are. Shortcut equality is over the whole modifier set, so the two do not collide.
        gui.shortcut(Key.LEFT, () -> surface.pan(-1, 0));
        gui.shortcut(Key.RIGHT, () -> surface.pan(1, 0));
        gui.shortcut(Key.UP, () -> surface.pan(0, 1));
        gui.shortcut(Key.DOWN, () -> surface.pan(0, -1));
        gui.shortcut(Key.EQUAL, () -> step(1));
        gui.shortcut(Key.MINUS, () -> step(-1));
        gui.shortcut(Key.HOME, this::home);
        // Zoom about the pointer, from the device event itself: see the class note on why this is not a
        // node handler.
        gui.bus().subscribe(InputTopics.INPUT, event -> {
            if (event instanceof InputEvent.Scrolled s && s.yOffset() != 0) {
                surface.wheel(s.yOffset(), s.x(), s.y());
            }
        });
    }

    /** This window's tree, live whether or not a window is open on it. */
    Gui gui() {
        return gui;
    }

    /**
     * Show {@code plottable}, written as {@code entry}, and raise the window. Safe from the worker thread the
     * keypad's = handler runs on: registering the name, opening the window and mutating the tree are each
     * safe from anywhere, and the evaluation the surface does happens on a worker of its own.
     */
    void show(GuiApp app, String entry, Plottable plottable) {
        heading.text("y = " + entry);
        surface.show(plottable);
        AppWindow open = window;
        if (open == null) {
            open = app.window("plot", () -> WindowSpec
                    .of(memory.config("plot", "Plot", W, H + BAR_H).decorations(Decorations.CLIENT), gui)
                    .onCreated(this::created)
                    // The window this bar commanded is gone; the tree outlives it and is shown again on the
                    // next plot, so the buttons go back to commanding nothing until onCreated rebinds them.
                    .onClosed(() -> {
                        titleBar.controls(WindowControls.NONE);
                        memory.forget("plot");
                    }));
            window = open;
        }
        // show() only posts the request; the window is created at the top of the next frame, and laid out in
        // the one after that. So there is nothing useful to ask the surface to draw here -- it watches the
        // layout and paints itself the moment its canvas has a size. An invalidate at this point was the bug
        // that made the first plot of a session open blank until something else provoked a repaint.
        open.show();
    }

    /**
     * Plot into the tree without opening a window, for {@code --capture-plot}. The window is the only part of
     * this that needs a GPU surface and an event loop; the plot itself is nodes, and nodes can be laid out and
     * photographed headlessly like any other tree in this application.
     */
    void headless(String entry, Plottable plottable) {
        heading.text("y = " + entry);
        status.text("");
        surface.showNow(plottable);
    }

    /** Paint what {@link #headless} set up, on the calling thread. See {@link PlotSurface#settle}. */
    void settle() {
        surface.settle();
    }

    /** Zoom about the middle and paint, without a pointer. For {@code --capture-plot}. */
    void zoomTo(int notches) {
        surface.zoomAbout(notches, 0.5, 0.5);
        surface.settle();
    }

    /** Pan by a fraction of the window and paint. For {@code --capture-plot}. */
    void panBy(double fractionX) {
        surface.nudge(fractionX, 0);
        surface.settle();
    }

    /** Back to the framed window, and paint. */
    void goHome() {
        home();
        surface.settle();
    }

    /** What the cache saved across everything asked of it so far. */
    String cacheReport() {
        return surface.cacheReport();
    }

    /**
     * The OS window exists: point the chrome at it, and put it back where it was.
     *
     * <p>The {@link WindowSpec} above was built the first time the name {@code plot} was claimed, so the
     * {@link WindowConfig} in it is a snapshot of the placement at that moment. The bounds worth restoring are
     * the ones the window was last left at, which may be from later in the same session — drag the plot
     * somewhere, close it, evaluate something else. Correcting here, before the first frame, is why that is not
     * a visible jump.
     */
    private void created(dev.vexelray.os.NativeWindow window) {
        titleBar.controls(new NativeWindowControls(window));
        if (memory.maximized("plot")) {
            window.maximize();
        } else {
            memory.restoreBounds("plot", window, W, H + BAR_H);
        }
        memory.watch("plot", window, gui);
    }

    private void home() {
        surface.reset();
        surface.invalidate();
    }

    private void step(int notches) {
        surface.zoom(notches);
        surface.invalidate();
    }

    /** One control: a small panel key in the calculator's palette, restyled per interaction state. */
    private Node button(String label, Runnable command) {
        Node b = gui.text(label).width(Length.dp(64)).height(Length.FILL)
                .background(Palette.PANEL).corner(Length.rem(0.5f)).border(Length.rem(0.1f), Palette.LINE)
                .textSize(Length.rem(0.875f)).textColor(Palette.INK)
                .align(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE)
                .lit(true).elevation(Length.rem(0.25f));
        gui.onState(b, state -> b.background(switch (state) {
            case NORMAL -> Palette.PANEL;
            case HOVER -> Palette.PANEL_HOVER;
            case PRESSED -> Palette.PANEL_PRESSED;
        }));
        gui.onClick(b, command);
        return b;
    }

    /**
     * {@link WindowControls} over the window this one was opened as. The main window's come from
     * {@code GuiApp.controls()}; every other window's the application holds itself, since the window its
     * caption buttons command is the one handed to {@code onCreated}.
     *
     * <p>Close is a <em>request</em>, exactly as the system close button would have been: the frame loop
     * observes it, tears the window down in its own order and runs {@code onClosed}. Destroying the window
     * here would pull resources out from under a frame in flight.
     */
    private record NativeWindowControls(dev.vexelray.os.NativeWindow window) implements WindowControls {

        @Override
        public void minimize() {
            window.minimize();
        }

        @Override
        public void toggleMaximize() {
            if (window.isMaximized()) {
                window.restore();
            } else {
                window.maximize();
            }
        }

        @Override
        public boolean maximized() {
            return window.isMaximized();
        }

        @Override
        public void close() {
            window.requestClose();
        }
    }
}
