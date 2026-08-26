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
import sibarum.cott.Term;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;

/**
 * One preview: the chrome around a plot, and everything the user reaches for.
 *
 * <p>It holds <b>both</b> renderers — {@link PlotSurface} for a curve of one variable, {@link SurfacePlot} for a
 * surface of two — and mounts whichever the expression turned out to need. Which one is not a mode the user
 * selects; it is the arity of what they typed, decided once in {@link #show} and never asked about again. The
 * controls below are deliberately the same four whichever is mounted, doing the corresponding thing: the plus
 * and minus narrow and widen the window, <b>Fit</b> re-runs the framing policy over the window now on show, and
 * <b>Reset</b> goes back to where the framing pass put it.
 *
 * <h2>One window per preview</h2>
 * This used to be <em>the</em> plot window — a single named window that re-plotted whatever was evaluated last.
 * It is now one of several, each owning its own {@link Gui} and its own tree, so two expressions can be looked
 * at side by side. {@link Previews} owns the slots and decides which one a new plot goes to; this class knows
 * only its own name.
 *
 * <h2>Four ways to move, because they suit different questions</h2>
 * On a curve: dragging is for "what is over there", the wheel is for "what is happening exactly here" (it zooms
 * about the pointer, so the feature under it stays under it), the buttons are for a deliberate step, and the
 * arrow keys are for a nudge without leaving the keyboard. On a surface the drag turns the picture instead,
 * because a surface has no direction to be panned in once it has been turned, and the arrow keys turn it too.
 *
 * <p>The wheel and the pointer's motion both arrive straight off the bus rather than through node handlers.
 * Scroll dispatch belongs to scrollable containers and a plot is emphatically not one, and the markers a
 * pointer hovers are drawn into a pointer-transparent layer so that presses still reach the canvas underneath —
 * which means nothing in the tree is ever going to be told it was hovered. So the window takes the raw events
 * and asks the surface whether they were over it.
 */
final class PlotWindow {

    private static final int W = 720;
    private static final int H = 560;
    /** {@code TitleBar}'s own height in dp, as the main window has it. */
    private static final int BAR_H = 32;

    private final String key;
    private final WindowMemory memory;
    private final Gui gui = new Gui();
    private final TitleBar titleBar;
    private final PlotSurface curve;
    private final SurfacePlot surface;
    private final SdfViewport marched;
    private final Node heading;
    private final Node status;
    private final Node body;

    private final Node controls;
    private final Node toggle;
    private final Node styleButton;
    private volatile AppWindow window;
    /** Which renderer is mounted. Set before the tree is touched, and read by every control below. */
    private volatile boolean showingSurface;
    /**
     * Whether the surface on show is the marched one rather than the box one.
     *
     * <p>Only ever true while {@link #showingSurface} is: the two are the same expression drawn two ways, and
     * the comparison is the point of offering both. A curve has no marched counterpart worth looking at — the
     * field would be its graph extruded into a ridge, which is a worse picture of one variable than the curve
     * already is.
     */
    private volatile boolean marching;

    PlotWindow(String key, WindowMemory memory) {
        this.key = key;
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
        this.curve = new PlotSurface(gui, status::text);
        this.surface = new SurfacePlot(gui, status::text);
        this.marched = new SdfViewport(gui, status::text);

        this.toggle = button("March", this::flip);
        // Cycles rather than opening a menu: there are three, they are all worth seeing, and the fastest way to
        // compare them is a button you can press three times without moving the pointer.
        this.styleButton = button(marched.style().label(), this::cycleStyle);
        this.controls = gui.row().width(Length.FILL).height(Length.rem(2f))
                .gap(Length.dp(8)).alignItems(AlignItems.STRETCH)
                .children(button("−", () -> step(-1)),
                          button("+", () -> step(1)),
                          button("Fit", this::fit),
                          button("Reset", this::home),
                          toggle,
                          styleButton,
                          status);

        // Both renderers live in the tree from the start and one of them is hidden. A hidden child is not
        // placed, not measured and not counted toward the gaps -- it occupies no space at all -- so this is a
        // swap rather than two half-height canvases, and neither renderer has to be built or torn down when
        // the next expression turns out to be the other kind.
        this.surface.node().visible(false);
        this.marched.mounted(false);
        this.body = gui.column().width(Length.FILL).height(Length.FILL)
                .padding(Length.dp(12)).gap(Length.dp(8))
                .children(heading, curve.node(), surface.node(), marched.node(), controls);

        this.titleBar = new TitleBar(gui, WindowControls.NONE, "Plot");
        gui.root().background(Palette.BG).children(titleBar.node(), body);

        gui.minSize(Length.em(18), Length.em(14));
        // Bare +/- move the plot's window; Ctrl+= and friends scale the UI, and are bound where every other
        // window's are. Shortcut equality is over the whole modifier set, so the two do not collide.
        gui.shortcut(Key.LEFT, () -> arrow(-1, 0));
        gui.shortcut(Key.RIGHT, () -> arrow(1, 0));
        gui.shortcut(Key.UP, () -> arrow(0, 1));
        gui.shortcut(Key.DOWN, () -> arrow(0, -1));
        gui.shortcut(Key.EQUAL, () -> step(1));
        gui.shortcut(Key.MINUS, () -> step(-1));
        gui.shortcut(Key.HOME, this::home);
        // Straight off the device bus: see the class note on why neither of these is a node handler.
        gui.bus().subscribe(InputTopics.INPUT, event -> {
            if (event instanceof InputEvent.Scrolled s && s.yOffset() != 0) {
                if (marching) {
                    // No "about the pointer" here: a marched zoom recompiles, so it moves in whole notches
                    // rather than continuously, and there is nothing to keep under the cursor between them.
                    marched.zoom(s.yOffset() > 0 ? 1 : -1);
                    return;
                }
                if (showingSurface) {
                    surface.wheel(s.yOffset(), s.x(), s.y());
                } else {
                    curve.wheel(s.yOffset(), s.x(), s.y());
                }
            } else if (event instanceof InputEvent.PointerMoved m && !showingSurface) {
                curve.hover(m.x(), m.y());
            }
        });
    }

    /** This window's tree, live whether or not a window is open on it. */
    Gui gui() {
        return gui;
    }

    /** Whether an OS window is currently open on this preview. */
    boolean open() {
        AppWindow live = window;
        return live != null && live.open();
    }

    /**
     * Show {@code plottable}, written as {@code entry}, and raise the window. Safe from the worker thread the
     * keypad's = handler runs on: registering the name, opening the window and mutating the tree are each safe
     * from anywhere, and the evaluation each renderer does happens on a worker of its own.
     *
     * @param typed the term as it was written, which only a curve uses — see {@link Influence}
     */
    void show(GuiApp app, String entry, Term typed, Plottable plottable) {
        mount(entry, plottable, true);
        if (plottable.isSurface()) {
            surface.show(plottable);
            // Compiled up front rather than when the button is pressed: it is a worker's work either way, and
            // doing it now means the swap is instant instead of pausing on a shader compile.
            marched.attach(app);
            marched.show(plottable);
        } else {
            curve.show(plottable, typed);
        }
        AppWindow open = window;
        if (open == null) {
            // The bar wires itself to whatever window this opens, and unwires when it closes -- the two lines
            // every window in this application used to write out by hand, and the hand-rolled WindowControls
            // that went with them.
            open = app.window(key, () -> titleBar.commands(WindowSpec
                    .of(memory.config(key, "Plot", W, H + BAR_H).decorations(Decorations.CLIENT), gui)
                    .onCreated(this::placed)
                    .onClosed(() -> memory.forget(key))));
            window = open;
        }
        // show() only posts the request; the window is created at the top of the next frame, and laid out in
        // the one after that. So there is nothing useful to ask the renderer to draw here -- it watches the
        // layout and paints itself the moment its canvas has a size. An invalidate at this point was the bug
        // that made the first plot of a session open blank until something else provoked a repaint.
        open.show();
    }

    /**
     * Show the renderer this expression needs, hide the others, and title the window after what is in it.
     *
     * @param canMarch whether the marched view is available. False for the headless captures, which have no
     *                 {@link GuiApp} to mint a render target from and so cannot march at all — the box surface
     *                 is not a fallback there, it is the only thing that can be photographed without a window.
     */
    private void mount(String entry, Plottable plottable, boolean canMarch) {
        boolean asSurface = plottable.isSurface();
        heading.text((asSurface ? "z = " : "y = ") + entry);
        titleBar.title(entry);
        showingSurface = asSurface;
        // A surface arrives marched. It is the better picture of one -- smooth, turnable without rebuilding
        // anything, and shaded from the expression's own normals -- so it is what pressing = should give you,
        // and the boxes are the thing to ask for when you want the arithmetic's own evidence instead.
        marching = asSurface && canMarch;
        toggle.text(marching ? "Boxes" : "March");
        toggle.visible(asSurface && canMarch);
        apply();
    }

    /** Put exactly one of the three renderers on screen, matching {@link #showingSurface} and {@link #marching}. */
    private void apply() {
        curve.node().visible(!showingSurface);
        surface.node().visible(showingSurface && !marching);
        marched.mounted(showingSurface && marching);
        // Only the marched view has styles, so the control appears with it rather than sitting there greyed.
        styleButton.visible(showingSurface && marching);
    }

    /** Next render style, and recompile for it. See {@link MarchStyle} on why that is a compile and not a flag. */
    private void cycleStyle() {
        if (!marching) {
            return;
        }
        MarchStyle next = marched.style().next();
        styleButton.text(next.label());
        marched.style(next);
    }

    /**
     * Swap between the box surface and the marched one — the same expression, drawn by two entirely different
     * routes, in the same box.
     *
     * <p>Which is the whole demonstration. One is a lattice of enclosures with a bilinear picture inside it,
     * rebuilt and re-uploaded whenever the camera moves; the other is a distance field compiled to SPIR-V,
     * where the camera is six floats of push constant and turning rebuilds nothing at all.
     */
    private void flip() {
        if (!showingSurface) {
            return;
        }
        marching = !marching;
        toggle.text(marching ? "Boxes" : "March");
        apply();
    }


    /**
     * Plot into the tree without opening a window, for the headless captures. The window is the only part of
     * this that needs a GPU surface and an event loop; the plot itself is nodes, and nodes can be laid out and
     * photographed headlessly like any other tree in this application.
     */
    void headless(String entry, Term typed, Plottable plottable) {
        mount(entry, plottable, false);
        status.text("");
        if (plottable.isSurface()) {
            surface.showNow(plottable);
        } else {
            curve.showNow(plottable, typed);
        }
    }

    /** Paint what {@link #headless} set up, on the calling thread. */
    void settle() {
        if (showingSurface) {
            surface.settle();
        } else {
            curve.settle();
        }
    }

    /** Zoom about the middle and paint, without a pointer. For the captures. */
    void zoomTo(int notches) {
        step(notches);
        settle();
    }

    /** Pan a curve by a fraction of the window, or turn a surface by a quarter. For the captures. */
    void panBy(double fraction) {
        if (showingSurface) {
            surface.turn(fraction * Math.PI, 0);
        } else {
            curve.nudge(fraction, 0);
        }
        settle();
    }

    /** Back to the framed window, and paint. */
    void goHome() {
        home();
        settle();
    }

    /** Open the first landmark's tooltip without a pointer, for the capture. False when there is no landmark. */
    boolean hoverMark() {
        return !showingSurface && curve.hoverFirstMark();
    }

    /** What the cache saved across everything asked of it so far. */
    String cacheReport() {
        return showingSurface ? surface.cacheReport() : curve.cacheReport();
    }

    /**
     * The OS window exists: put it back where it was. The chrome is not this method's business any more — the
     * bar binds itself through {@link TitleBar#commands}.
     *
     * <p>The {@link WindowSpec} above was built the first time this slot's name was claimed, so the
     * {@link WindowConfig} in it is a snapshot of the placement at that moment. The bounds worth restoring are
     * the ones the window was last left at, which may be from later in the same session — drag the plot
     * somewhere, close it, evaluate something else into the same slot. Correcting here, before the first frame,
     * is why that is not a visible jump.
     */
    private void placed(dev.vexelray.os.NativeWindow window) {
        if (memory.maximized(key)) {
            window.maximize();
        } else {
            memory.restoreBounds(key, window, W, H + BAR_H);
        }
        memory.watch(key, window, gui);
    }

    /** March a frame if the viewport asked for one. Called from the frame loop; see {@link SdfViewport#pump}. */
    void pump() {
        marched.pump();
    }

    private void home() {
        if (marching) {
            marched.home();
        } else if (showingSurface) {
            surface.reset();
            surface.invalidate();
        } else {
            curve.reset();
            curve.invalidate();
        }
    }

    private void fit() {
        if (marching) {
            marched.fitVertically();
        } else if (showingSurface) {
            surface.fitVertically();
        } else {
            curve.fitVertically();
        }
    }

    /**
     * A zoom notch. It costs the marched view a recompile where it costs the other two a repaint, because a
     * marched extent is compiled <em>into</em> the field rather than being a viewport the picture is drawn
     * through. Same notch, same direction, same key — the difference is in what it costs, not in what it means.
     */
    private void step(int notches) {
        if (marching) {
            marched.zoom(notches);
        } else if (showingSurface) {
            surface.zoom(notches);
            surface.invalidate();
        } else {
            curve.zoom(notches);
            curve.invalidate();
        }
    }

    /** An arrow key: a nudge along the plane for a curve, a turn of the picture for either kind of surface. */
    private void arrow(int x, int y) {
        if (marching) {
            marched.nudge(x, y);
        } else if (showingSurface) {
            surface.nudge(x, y);
        } else {
            curve.pan(x, y);
        }
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
}
