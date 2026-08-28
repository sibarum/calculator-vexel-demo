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
 * <p>It holds <b>every</b> renderer — {@link PlotSurface} for a curve of one variable, {@link SurfacePlot} and
 * {@link SdfViewport} for a surface of two, and {@link SpiralPlot} for a value that has a place rather than a
 * curve — and mounts whichever the expression turned out to need. Which one is not a mode the user selects; it
 * is a property of what they typed, decided once in {@link #show} and never asked about again. The controls
 * below are deliberately the same four whichever is mounted, doing the corresponding thing: the plus and minus
 * narrow and widen the window (or lengthen and shorten the coil, which is the same gesture over the one axis a
 * spiral has), <b>Fit</b> re-runs the framing policy over the window now on show, and <b>Reset</b> goes back to
 * where the framing pass put it.
 *
 * <p>Three of the four are pictures of a <em>function</em> and the fourth is a picture of a <em>place</em>.
 * That is the only structural difference between them, and it shows up in exactly two places: the spiral is
 * given a {@link Place} rather than a {@link Plottable}, and it has no cache to report because it has nothing
 * to evaluate.
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
    private final SpiralPlot spiral;
    private final Node heading;
    private final Node status;
    private final Node body;

    private final Node controls;
    private final Node toggle;
    private final Node styleButton;
    private volatile AppWindow window;

    /**
     * Which of the four renderers is mounted. Set before the tree is touched, and read by every control below.
     *
     * <p>This was a pair of booleans — <em>is it a surface</em> and <em>is it marched</em> — which was exactly
     * right while there were three views and one of them was a mode of another, and stopped being right the
     * moment a fourth arrived that is neither. The states a pair of booleans can spell but the window cannot
     * be in (a marched curve) are now unspellable, which is the ordinary reason to reach for an enum and the
     * reason it is worth the diff: every control below asks one question instead of two nested ones.
     */
    private volatile View view = View.CURVE;

    /**
     * What a preview can be showing.
     *
     * <p>{@link #SURFACE} and {@link #MARCHED} are the same expression drawn two entirely different ways and
     * the comparison is the point of offering both, so <b>March</b> swaps between exactly those two. A curve
     * has no marched counterpart worth looking at — the field would be its graph extruded into a ridge — and
     * the {@link #SPIRAL} has none either, for a better reason: it is not a field at all, it is a place.
     */
    private enum View { CURVE, SURFACE, MARCHED, SPIRAL }

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
        this.spiral = new SpiralPlot(gui, status::text);

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
        this.spiral.node().visible(false);
        this.body = gui.column().width(Length.FILL).height(Length.FILL)
                .padding(Length.dp(12)).gap(Length.dp(8))
                .children(heading, curve.node(), surface.node(), marched.node(), spiral.node(), controls);

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
                switch (view) {
                    // No "about the pointer" here: a marched zoom recompiles, so it moves in whole notches
                    // rather than continuously, and there is nothing to keep under the cursor between them.
                    case MARCHED -> marched.zoom(s.yOffset() > 0 ? 1 : -1);
                    case SURFACE -> surface.wheel(s.yOffset(), s.x(), s.y());
                    // Nor here, and for the plainer reason: the wheel lengthens the coil by a whole turn,
                    // and there is nothing between one grade and the next to land on.
                    case SPIRAL -> spiral.wheel(s.yOffset(), s.x(), s.y());
                    case CURVE -> curve.wheel(s.yOffset(), s.x(), s.y());
                }
            } else if (event instanceof InputEvent.PointerMoved m && view == View.CURVE) {
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
        raise(app);
    }

    /**
     * Show where {@code place} sits, written as {@code entry}, and raise the window — {@link #show}'s
     * counterpart for the values that have a place rather than a curve.
     *
     * <p>There is no {@code typed} term here and there is nothing missing in that. A curve is given the term as
     * it was written because {@link Influence} breaks it into the pieces carrying the value at a landmark, and
     * a place has no landmarks: the value <em>is</em> the landmark, and it is the reduced one, because where
     * {@code 2÷0} sits is where {@code 2ω} sits.
     */
    void show(GuiApp app, String entry, Place place) {
        mount(entry, place);
        spiral.show(place);
        raise(app);
    }

    /** Open the OS window on this preview, building it the first time this slot is claimed. */
    private void raise(GuiApp app) {
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
        // A surface arrives marched. It is the better picture of one -- smooth, turnable without rebuilding
        // anything, and shaded from the expression's own normals -- so it is what pressing = should give you,
        // and the boxes are the thing to ask for when you want the arithmetic's own evidence instead.
        view = asSurface ? (canMarch ? View.MARCHED : View.SURFACE) : View.CURVE;
        toggle.visible(asSurface && canMarch);
        apply();
    }

    /**
     * Mount the spiral. The heading says neither {@code y =} nor {@code z =} because the picture is not a graph
     * of the entry against anything — it is where the entry <em>is</em>, and the word for that is "at".
     */
    private void mount(String entry, Place place) {
        heading.text(entry + " is at");
        titleBar.title(entry);
        view = View.SPIRAL;
        toggle.visible(false);
        apply();
    }

    /** Put exactly one of the four renderers on screen, matching {@link #view}. */
    private void apply() {
        curve.node().visible(view == View.CURVE);
        surface.node().visible(view == View.SURFACE);
        marched.mounted(view == View.MARCHED);
        spiral.node().visible(view == View.SPIRAL);
        toggle.text(view == View.MARCHED ? "Boxes" : "March");
        // Only the marched view has styles, so the control appears with it rather than sitting there greyed.
        styleButton.visible(view == View.MARCHED);
    }

    /** Next render style, and recompile for it. See {@link MarchStyle} on why that is a compile and not a flag. */
    private void cycleStyle() {
        if (view != View.MARCHED) {
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
        if (view != View.SURFACE && view != View.MARCHED) {
            return;
        }
        // Carry the viewpoint across, so the swap is the same surface seen the same way and not a new picture
        // of it. Without this the comparison the two views exist for is the hardest thing in the window to
        // make: you would have to turn the second one back to where the first was, by eye, every time.
        //
        // The angles copy verbatim rather than being converted. Both renderers keep a plot-module Camera, and
        // both mean the same thing by it: the box view projects along Camera.viewDirection, and the marched one
        // orbits its eye to minus the direction the generated fragment builds from the same two angles, which
        // in plot coordinates is that same vector term for term.
        if (view == View.MARCHED) {
            surface.camera(marched.camera());
            view = View.SURFACE;
            apply();
            surface.invalidate();        // the box view repaints to be turned; the marched one just marches
        } else {
            marched.camera(surface.camera());
            view = View.MARCHED;
            apply();
        }
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

    /** {@link #headless} for a place. The spiral photographs like the others: it is nodes and nothing else. */
    void headless(String entry, Place place) {
        mount(entry, place);
        status.text("");
        spiral.showNow(place);
    }

    /** Paint what {@link #headless} set up, on the calling thread. */
    void settle() {
        switch (view) {
            case SURFACE, MARCHED -> surface.settle();
            case SPIRAL -> spiral.settle();
            case CURVE -> curve.settle();
        }
    }

    /** Zoom about the middle and paint, without a pointer. For the captures. */
    void zoomTo(int notches) {
        step(notches);
        settle();
    }

    /** Pan a curve by a fraction of the window, or turn a surface or a coil by a quarter. For the captures. */
    void panBy(double fraction) {
        switch (view) {
            case SURFACE, MARCHED -> surface.turn(fraction * Math.PI, 0);
            case SPIRAL -> spiral.turn(fraction * Math.PI, 0);
            case CURVE -> curve.nudge(fraction, 0);
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
        return view == View.CURVE && curve.hoverFirstMark();
    }

    /**
     * What the cache saved across everything asked of it so far.
     *
     * <p>The spiral has none and reports none. That is not an omission: a place is two numbers settled before
     * the first frame, so there is nothing a cache would be holding and nothing a turn would ask it for.
     */
    String cacheReport() {
        return switch (view) {
            case SURFACE, MARCHED -> surface.cacheReport();
            case SPIRAL -> "nothing to cache";
            case CURVE -> curve.cacheReport();
        };
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
        switch (view) {
            case MARCHED -> marched.home();
            case SURFACE -> { surface.reset(); surface.invalidate(); }
            case SPIRAL -> { spiral.reset(); spiral.invalidate(); }
            case CURVE -> { curve.reset(); curve.invalidate(); }
        }
    }

    private void fit() {
        switch (view) {
            case MARCHED -> marched.fitVertically();
            case SURFACE -> surface.fitVertically();
            // The same act one dimension sideways: Fit leaves the window holding what is in it and no more,
            // and on a spiral the number that means is how many turns are on screen.
            case SPIRAL -> { spiral.fitVertically(); spiral.invalidate(); }
            case CURVE -> curve.fitVertically();
        }
    }

    /**
     * A zoom notch. It costs the marched view a recompile where it costs the other two a repaint, because a
     * marched extent is compiled <em>into</em> the field rather than being a viewport the picture is drawn
     * through. Same notch, same direction, same key — the difference is in what it costs, not in what it means.
     */
    private void step(int notches) {
        switch (view) {
            case MARCHED -> marched.zoom(notches);
            case SURFACE -> { surface.zoom(notches); surface.invalidate(); }
            // A notch is a whole turn of the coil, which is a whole grade -- the two are the same thing here.
            case SPIRAL -> { spiral.zoom(notches); spiral.invalidate(); }
            case CURVE -> { curve.zoom(notches); curve.invalidate(); }
        }
    }

    /** An arrow key: a nudge along the plane for a curve, a turn of the picture for everything else. */
    private void arrow(int x, int y) {
        switch (view) {
            case MARCHED -> marched.nudge(x, y);
            case SURFACE -> surface.nudge(x, y);
            case SPIRAL -> spiral.nudge(x, y);
            case CURVE -> curve.pan(x, y);
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
