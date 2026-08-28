package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.app.Settings;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.krono.KronoGui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import sibarum.cott.Bindings;
import sibarum.cott.Notation;
import sibarum.cott.Parser;
import sibarum.cott.Term;
import sibarum.kronometer.Clock;
import sibarum.kronometer.Driven;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;

/**
 * Every headless photograph this application takes: one {@link World}, and a list of {@link Scene}s over it.
 *
 * <h2>Why one mode and not five</h2>
 *
 * There were five capture flags, and they differed in only two things -- what they did to the application and
 * what they photographed. Everything else was copied between them, and the copies had drifted:
 *
 * <ul>
 *   <li><b>Three different applications.</b> {@code --capture} held an engine with no {@link Definitions} and
 *       no {@link Previews}, so defining a name on that path answered <em>there is nowhere to keep a
 *       definition here</em>. {@code --capture-names} had names but no plotter. {@code --capture-plot} and
 *       {@code --capture-sdf} went round the engine altogether. No flag ever held the keypad and a plot at
 *       once, so the arrangement the program actually <em>is</em> had never been photographed.
 *   <li><b>Two readings of an expression.</b> The engine expands the session's names before parsing; the two
 *       plot captures parsed bare. So {@code --capture-plot=f(x)} could not draw a defined name -- not because
 *       plotting cannot, but because that path had never been told the session existed.
 *   <li><b>One of them was another one.</b> {@code --capture-surface} was {@code --capture-plot} with a
 *       different default expression and different filenames.
 *   <li><b>None of them could photograph a moment.</b> Every flag returned before the frame loop, so there was
 *       no clock -- and nowhere to put one that all five would have shared.
 * </ul>
 *
 * <p>So the world is built <b>once, the way {@code main} builds it</b> -- the real tree, the real engine, the
 * names, the plotter, the tape, and a clock on every window -- and the differences between the old flags
 * become data. A scene is a name and something done to that world. Adding <em>photograph the error ring at
 * 120ms</em> is a new entry in {@link #SCENES}, not a new mode.
 *
 * <h2>Running it</h2>
 *
 * <pre>{@code
 * --capture                    every scene, at its default
 * --capture=curve              one of them
 * --capture=curve=x^2-y^2      one of them, told what to draw
 * --capture=keypad,names       a couple
 * }</pre>
 *
 * <p>A scene that fails is reported and the rest still run, which matters now that one command takes every
 * picture: the {@code sdf} scene wants a graphics device, and a machine without one should still get the
 * other four.
 */
final class Capture {

    private Capture() {
    }

    /**
     * What a photograph is taken against: {@link Palette#BG}, which is what a window shows behind the tree.
     * Written as three floats because that is what {@link GuiApp#capture} takes.
     */
    private static final float BG_R = 0.06f;
    private static final float BG_G = 0.07f;
    private static final float BG_B = 0.09f;

    // ---- scenes ---------------------------------------------------------------------------------------

    /**
     * One named picture-taking.
     *
     * @param name    what it is called on the command line
     * @param subject what it is about when nothing is said: an expression for the plotting scenes, a filename
     *                for the ones that photograph a window there is only one of
     * @param run     what it does to the world, given that subject
     */
    record Scene(String name, String subject, BiConsumer<World, String> run) {
    }

    /**
     * The scenes, in the order {@code --capture} with nothing after it runs them.
     *
     * <p>Cheapest first, and deliberately: the {@code sdf} scene compiles SPIR-V and wants a device, so a run
     * that is going to fail for want of a graphics card has already written the other four pictures by the
     * time it gets there.
     */
    static final List<Scene> SCENES = List.of(
            new Scene("keypad", "calculator.png", Capture::keypad),
            new Scene("names", "names.png", Capture::names),
            // A pole pair: the whole point of the technique in one picture -- two poles, found by the
            // arithmetic rather than by a solver, drawn as painted columns instead of lines through infinity.
            new Scene("curve", "1÷(x^2−1)", (w, e) -> plot(w, e, "plot.png", "plot-zoomed.png")),
            // A saddle: the one picture that shows at a glance whether the projection and the painting order
            // are both right, since it rises on one axis exactly as it falls on the other.
            new Scene("surface", "x^2−y^2", (w, e) -> plot(w, e, "surface.png", "surface-turned.png")),
            // The answer that never had a picture. 2÷0 is 2ω -- correct on the display since the engine
            // arrived, and refused by every renderer above, because the real line does not hold it. On the
            // spiral it is one turn out from 1 along the crest, and a little further out again for the second
            // copy: the count and the grade on one axis, which is what that figure buys.
            new Scene("spiral", "2/0", (w, e) -> plot(w, e, "spiral.png", "spiral-turned.png")),
            // A pole ridge: the case that says whether the gradient normalisation is doing its job, since an
            // un-normalised implicit loses the surface exactly where the height runs away.
            new Scene("sdf", "1÷(x^2+y^2)", Capture::sdf),
            // The scene that could not have existed before: three photographs of one interface at three
            // moments, which is a thing only a world with a clock in it can be asked for.
            new Scene("cue", "cue.png", Capture::cue));

    /**
     * What the calculator does to say what happened, caught in the middle of saying it.
     *
     * <p>Each of these is a photograph of an instant, which is what {@link World#shotNow} is for: act, tick
     * the clock to a chosen moment, and photograph without letting anything settle. The moments are not
     * arbitrary -- each is where the mark it is about is at its strongest:
     *
     * <ul>
     *   <li><b>90ms of a 240ms sweep</b> puts the scanline's bright core about a third of the way down the
     *       entry, with its trail still hanging off the top edge.
     *   <li><b>100ms of a 400ms ring</b> is the peak of the first of its two pulses: the envelope is
     *       {@code sin} over each pulse's own span, so a two-pulse ring is brightest at a quarter and at
     *       three quarters and invisible at both ends and the middle.
     *   <li><b>80ms of a 160ms rise</b> catches the wash half-way down its slow release, and the status line
     *       part-risen behind it.
     * </ul>
     *
     * <p>Which makes these regression pictures rather than decoration. A cue whose timing drifts, or whose
     * envelope changes shape, shows up here as a mark in the wrong place -- and nowhere else, because at rest
     * every one of them is invisible by construction.
     */
    private static void cue(World w, String subject) {
        // The before, at rest -- and it has to come first, for a reason worth writing down. A cue paints into
        // a node's BOX, and a node has no box until the tree has been laid out. In a running window that is
        // never a problem, because layout runs every frame before anything else; here, layout runs only when
        // a picture is taken. So the first picture of any scene photographing a moment is what makes the
        // moments after it paintable, and taking it last would quietly produce three empty interfaces.
        w.type("x+(1÷1)");
        w.shot(suffixed(subject, "-entered"));

        // Acknowledged. Deliberately an expression that reduces to itself: the display reads the same before
        // and after, so the sweep is the only thing saying the key was pressed at all.
        w.press("=");
        w.moment(Dur.ms(90));
        w.shotNow(subject);
        w.rest();

        // Refused. The entry keeps the line so it can be corrected, the status line arrives with the reason,
        // and the ring marks the thing about to be corrected.
        w.type("2k = 3");
        w.press("=");
        w.moment(Dur.ms(100));
        w.shotNow(suffixed(subject, "-refused"));
        w.rest();

        // Changed from somewhere else -- a click in the history window, which is not this one.
        w.arrive("sin(x)^2+cos(x)^2");
        w.moment(Dur.ms(80));
        w.shotNow(suffixed(subject, "-arrived"));
        w.rest();

        // And the one transition: the trig pad arriving over the number pad. Unlike the three above this has
        // two states the eye can look at, so the frame worth having is one where both pads are present and
        // neither is where it belongs -- the only kind of picture that can show whether they are travelling
        // or merely dissolving into one another.
        //
        // A FIFTH of the way through, not half, and the difference is the whole point of taking the picture.
        // Tabs.slide eases its own travel, so by the halfway mark the arriving page is 87% of the way in and
        // what is left of the offset is a few pixels -- a photograph there shows a pad fading, which is
        // exactly the reading the slide exists to avoid, and would have been taken as evidence that it works.
        // The leaving page is also gone by then: it clears at twice the rate, so half way through there is
        // only one pad on screen.
        w.pad(1);
        w.moment(Motion.TRANSITION.times(0.2));
        w.shotNow(suffixed(subject, "-swapping"));
        w.rest();
    }

    /**
     * The keypad, both pads, at the ordinary size and at the smallest one a window manager will allow.
     *
     * <p>The two small ones are not decoration. A minimum chosen by eye and never photographed is a number
     * that stops being right the first time a row of keys is added -- and both pads are photographed at it
     * because they are tight in different directions: five narrow columns of short labels against four wide
     * ones holding {@code atan2}.
     *
     * <p>Its subject is the filename the other three are derived from.
     */
    private static void keypad(World w, String subject) {
        // The residue vertical: 1 over 1 in an additive context keeps its winding. It also has an x in it, so
        // pressing = here opens a preview -- which it always did in the running program, and which no capture
        // could see until this world held a plotter.
        w.keys("x", "+", "(", "1", "÷", "1", ")", "=");
        w.shot(subject);
        // And the other pad. It shares the slot with the first, so the only way to see it is to select it --
        // which a photograph has no pointer to do, hence the strip handing its selections back.
        w.pad(1);
        w.keys("C", "sin", "x", ")", CalculatorApp.TIMES, "cosh", "y", ")");
        w.shot(suffixed(subject, "-trig"));
        int minW = CalculatorApp.smallest(w.gui(), CalculatorApp.MIN_EM_W);
        int minH = CalculatorApp.smallest(w.gui(), CalculatorApp.MIN_EM_H);
        w.shot(suffixed(subject, "-trig-smallest"), minW, minH);
        w.pad(0);
        w.shot(suffixed(subject, "-smallest"), minW, minH);
    }

    /**
     * That a session can name things: define a few, photograph the list, then evaluate through the same key
     * the keypad uses, so the picture and the answers are evidence about one arrangement rather than two.
     *
     * <p>The evaluations are the point of the feature. {@code f(2)} is a number and {@code f(x)} is an
     * expression in x -- the same definition, read twice, because a definition is expanded into the term and
     * the term is then whatever COTT makes of it. {@code iter} is the same idea one turn further on: a
     * parameter written {@code f()} takes a <em>function</em>, so one definition serves a name this session
     * made and a name the catalogue owns, and nothing about {@code iter} knows what {@code sin} is.
     */
    private static void names(World w, String subject) {
        // Through the KEYPAD, not through Definitions: type() puts a line in the entry exactly as typing it
        // would, and = is the same key that evaluates. So this photograph is of what a definition made at the
        // keypad does, which is the only claim worth photographing.
        for (String line : new String[]{"k = 3", "f(t) = k·t^2+1", "theta = π÷4",
                "unit(a) = a÷k", "iter(g(), n) = g(g(n))", "k = 4"}) {
            w.type(line);
            w.press("=");
            w.drain();   // the frame the list would have been rebuilt on
            System.out.println("  " + line + "   entry now [" + w.engine().shown() + "]   "
                    + w.engine().reported());
        }
        w.shot(w.definitions().gui(), 400, 420 + CalculatorApp.BAR_H, subject);
        // And what those names then mean, through the same key again. Note the last definition above: k was 3
        // and is now 4, and f -- which mentions k rather than holding a 3 -- answers with the new one.
        for (String entry : new String[]{"k", "f(2)", "f(x)", "sin(theta)^2+cos(theta)^2", "unit(9)", "kx",
                // A functor over a name this session made, and over one the catalogue owns -- one definition,
                // and nothing in it knows what sin is.
                "iter(unit, 8)", "iter(sin, x)"}) {
            w.type(entry);
            w.press("=");
            System.out.println("  " + entry + "  ->  " + w.engine().shown());
        }
        // A refusal keeps the line, which is the rule every rejection here follows. And a functor given
        // something that is not a function is refused with the kind it wanted rather than with whatever the
        // arithmetic would have made of it -- while one given a function of the wrong width is refused by THAT
        // function, since iter never promised a number of arguments and atan2 did.
        for (String bad : new String[]{"2k = 3", "iter(3, 2)", "iter(atan2, 2)"}) {
            w.type(bad);
            w.press("=");
            System.out.println("  " + bad + "   entry kept [" + w.engine().shown() + "]   "
                    + w.engine().reported());
        }
    }

    /**
     * An expression plotted, through {@code =} rather than round the side of it.
     *
     * <p>That is the change worth naming. This used to parse the expression itself and hand the result
     * straight to a {@link PlotWindow} it had built, which meant the picture was evidence about a path nobody
     * uses and could not draw a defined name at all. Now the line goes into the entry, {@code =} is pressed,
     * and whatever the engine decides -- a curve, a surface, or a refusal in its own words -- is what gets
     * photographed. {@link Previews} chooses the slot, exactly as it does when someone presses the key.
     *
     * <p>Then the half that would otherwise never be exercised without a pointer, and the numbers that say
     * whether the cache is doing what the whole design is for: a zoom lands on a scale nothing is cached at
     * and pays for every column; the move after it stays within that scale and should pay for almost none;
     * and going home returns to a scale already visited and should pay for nothing at all. On a surface that
     * middle step turns the picture instead of panning it, which is the stronger claim of the two -- turning
     * re-projects and re-sorts and must evaluate nothing whatsoever.
     */
    private static void plot(World w, String entry, String first, String second) {
        w.type(entry);
        w.press("=");
        PlotWindow plot = w.previews().latest();
        if (plot == null) {
            // The engine's own words, off its own status line. This used to be a second set of refusal
            // messages kept in step with the first by hand.
            System.out.println("  nothing to plot: " + w.engine().reported());
            return;
        }
        // Two paints' worth, and it has to be: the plot draws off a laid-out canvas, and the first capture is
        // what gives the tree a size. settle() then paints on this thread, and the second capture is the
        // photograph. See Previews.show, which mounts and stops for exactly this reason.
        w.shot(plot.gui(), CalculatorApp.PLOT_W, CalculatorApp.PLOT_H, first);
        plot.settle();
        w.shot(plot.gui(), CalculatorApp.PLOT_W, CalculatorApp.PLOT_H, first);
        System.out.println("  framed     " + plot.cacheReport());
        plot.zoomTo(4);
        System.out.println("  zoomed in  " + plot.cacheReport());
        plot.panBy(0.4);
        System.out.println("  moved      " + plot.cacheReport());
        w.shot(plot.gui(), CalculatorApp.PLOT_W, CalculatorApp.PLOT_H, second);
        plot.goHome();
        System.out.println("  back home  " + plot.cacheReport());
        // And the half that needs a pointer. A capture has no input backend and so no pointer, but the hover
        // path can still be walked from its own end: point at the first marker, and photograph what it says.
        if (plot.hoverMark()) {
            w.shot(plot.gui(), CalculatorApp.PLOT_W, CalculatorApp.PLOT_H, suffixed(first, "-landmark"));
            System.out.println("  landmark   named");
        }
    }

    /**
     * The same expression the plot would draw, drawn instead by a shader compiled from it.
     *
     * <p>One photograph per render style, because a style is compiled into the shader rather than switched at
     * runtime -- so <em>does Height look right</em> is a question about a different SPIR-V module, and a
     * capture that only ever built one of them would not be asking it.
     *
     * <p>Everything up to {@link Plottable} is the reading the engine does, names and all, so an expression
     * that will not plot will not march either and says so in the same words.
     */
    private static void sdf(World w, String entry) {
        Plottable plottable = w.plottable(entry);
        if (plottable == null) {
            System.out.println("  nothing to march: " + w.refusal());
            return;
        }
        SdfSurface surface = SdfSurface.of(plottable.expr(), plottable.variables());
        if (!surface.ok()) {
            System.out.println("  nothing to march: " + surface.refusal());
            return;
        }
        System.out.println("  marching " + entry);
        for (MarchStyle style : MarchStyle.values()) {
            String file = style == MarchStyle.LIT
                    ? "sdf.png" : suffixed("sdf.png", "-" + style.label().toLowerCase());
            System.out.println("  " + style.label());
            try {
                SdfPreview.capture(surface, style, file);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("could not write " + file + ": " + e.getMessage(), e);
            }
            System.out.println("  captured " + new java.io.File(file).getAbsolutePath());
        }
    }

    /** {@code plot.png} and {@code -zoomed} into {@code plot-zoomed.png}. */
    private static String suffixed(String path, String suffix) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path + suffix : path.substring(0, dot) + suffix + path.substring(dot);
    }

    // ---- the world ------------------------------------------------------------------------------------

    /**
     * The whole application, with no windows: the tree {@code main} builds, the engine that drives it, the
     * names, the plotter, the tape -- and a clock on every one of their trees.
     *
     * <p><b>Built the way {@code main} builds it</b>, which is the point of the class. Each of the three
     * things that used to be missing from one capture or another is here because each was already willing to
     * be: {@link Definitions#show} is silent with no application attached, {@link Previews} mounts a preview
     * instead of asking for a window, and the history window fills its tape and does not pop up. None of them
     * needed a headless variant -- they needed to be assembled together once.
     *
     * <p><b>The clock is why this exists now.</b> A picture of something in motion is a picture taken at a
     * moment, and {@link #tick} is how a scene names one. It is on every tree rather than only the main one
     * because {@code prepare} is the single answer to "what does a window in this application get", and a
     * preview's tree is built lazily by {@link Previews} long after this has returned.
     */
    static final class World implements AutoCloseable {

        /** A tree and the clock driving it. Every window in the application is one of these. */
        private record Attached(Gui gui, KronoGui krono) {
        }

        private final Gui gui;
        private final CalculatorApp.Ui ui;
        private final CalculatorApp.Engine engine;
        private final Definitions definitions;
        private final CalculatorApp.History history;
        private final Previews previews;
        /** Every tree in the application, in the order it was built. Appended to as previews are claimed. */
        private final List<Attached> windows;
        /** Why the last {@link #plottable} came back null. */
        private volatile String refusal = "";

        private World(Gui gui, CalculatorApp.Ui ui, Definitions definitions, CalculatorApp.History history,
                      Previews previews, List<Attached> windows) {
            this.gui = gui;
            this.ui = ui;
            this.engine = ui.engine();
            this.definitions = definitions;
            this.history = history;
            this.previews = previews;
            this.windows = windows;
        }

        /**
         * Build it. The same sequence {@code main} runs, minus the window, the input backend and the clipboard
         * -- which are the three things that need an OS and the three things a photograph does not use.
         */
        static World open() {
            Gui gui = new Gui();
            gui.minSize(Length.em(CalculatorApp.MIN_EM_W), Length.em(CalculatorApp.MIN_EM_H));

            // What every window in this application gets, said once. Previews calls it for each preview it
            // builds, which is how a tree that does not exist yet still ends up with a clock and a zoom.
            List<Attached> windows = new java.util.concurrent.CopyOnWriteArrayList<>();
            Consumer<Gui> prepare = tree -> prepare(tree, windows);

            // The main tree first, because its clock has to exist before the UI is built: a widget that
            // animates is handed its timing at construction, exactly as main() hands it over.
            KronoGui krono = prepare(gui, windows);
            CalculatorApp.Ui ui = CalculatorApp.buildUi(gui, Motion.of(krono));
            CalculatorApp.Engine engine = ui.engine();

            // A real window memory over the real settings: only ever read here, since nothing on this path
            // opens a window to place and nothing calls watch, poll or save.
            WindowMemory memory = new WindowMemory(Settings.open(CalculatorApp.APP_NAME));

            Definitions definitions = new Definitions(memory, engine::bindings);
            prepare.accept(definitions.gui());
            engine.definitions(definitions);

            // Null application: the tape fills and the window never pops up. See HistoryWindow.add.
            CalculatorApp.History history = new CalculatorApp.History(engine, null, memory);
            prepare.accept(history.windowGui());
            engine.history(history);

            Previews previews = new Previews(null, memory, prepare);
            engine.plotter(previews);

            return new World(gui, ui, definitions, history, previews, windows);
        }

        /**
         * A tree gets this application's zoom shortcuts and a clock, and is remembered so it can be ticked.
         *
         * <p><b>An unbounded clock, which a render loop must never have.</b> {@code Kron.driven()} caps how
         * far one tick may carry logical time — a second by default — and forgives the excess, so that a
         * breakpoint or a sleeping laptop costs a skipped gap rather than however many seconds of replayed
         * simulation. Here the long jump is the whole point: a preview built on the fourth scene is handed
         * the world's accumulated time on its <em>first</em> tick, precisely so that it does not start again
         * from zero while everything else is at four seconds. That is a scripted jump, not a stall, and it
         * is the case {@code maxAdvance(FOREVER)} exists for.
         */
        private static KronoGui prepare(Gui tree, List<Attached> windows) {
            CalculatorApp.zoomShortcuts(tree);
            KronoGui krono = KronoGui.attach(tree,
                    Kron.of(Clock.driven(Driven.Mode.INLINE).maxAdvance(Dur.FOREVER)));
            windows.add(new Attached(tree, krono));
            return krono;
        }

        // ---- acting on it ---------------------------------------------------------------------------

        /** Put a line in the entry, exactly as typing it would. Unmarked, because typing is. */
        void type(String line) {
            engine.enter(line);
        }

        /**
         * Put a line in the entry the way a click in the history window does -- which washes it, because that
         * click happened somewhere the eye was not. The other half of {@link #type}.
         */
        void arrive(String line) {
            engine.restore(line);
        }

        /** Press one key, exactly as clicking it would. */
        void press(String key) {
            engine.press(key);
        }

        /** Press several, in order. */
        void keys(String... keys) {
            for (String key : keys) {
                engine.press(key);
            }
        }

        /** Select a pad. A photograph has no pointer, so the strip is asked directly. */
        void pad(int index) {
            ui.pads().select(index);
        }

        /** The frame the queued requests would have run on. */
        void drain() {
            definitions.drain();
            history.drain();
        }

        /**
         * How far this world's clocks have been wound on. See {@link #tick} for why it has to be kept.
         *
         * <p>Every tree is wound to the same reading, including one that only came into existence part way
         * through -- a preview built on the fourth scene is handed the world's current time on its first
         * tick, rather than starting again from zero while everything else is at four seconds.
         */
        private Dur now = Dur.ZERO;

        /**
         * Advance every clock in the application by {@code elapsed}.
         *
         * <p><b>{@code KronoGui.tick} takes an absolute reading, not a delta</b>, so this keeps the running
         * total and hands over the sum. Passing the delta straight through is the obvious thing and it is
         * wrong in a way nothing complains about: the clock simply stops, because the second
         * {@code tick(ms(90))} of a session says "it is still 90ms" rather than "another 90ms has passed",
         * and every animation asked for after the first one is frozen at the instant it began. What that
         * looks like is a photograph of a sweep sitting exactly where a sweep legitimately starts.
         *
         * <p>Note also that a ramp defers its completion to the frame <em>after</em> the one carrying 1 -- so
         * that the end value reaches the screen before anything tears down -- which is why settling takes two
         * ticks and not one. See {@link #rest}.
         */
        void tick(Dur elapsed) {
            now = now.plus(elapsed);
            for (Attached window : windows) {
                window.krono().tick(now);
            }
        }

        /**
         * Advance to {@code into} an animation that has just been asked for.
         *
         * <p><b>Two ticks, not one</b>, and the caller should not have to know why. An animation started off
         * the timeline -- which is every one of them, since they start in a key handler -- is <em>posted</em>
         * to it rather than begun on the spot. So the first tick after the request is the one that starts it,
         * and a lone {@code tick(90ms)} would spend itself on that setup and photograph the animation at zero.
         * The first tick here starts it; the second carries it to the moment.
         *
         * <p>Which is exactly the sort of thing that produces a picture that looks plausible and is wrong: a
         * scanline at the top of the entry is where a scanline legitimately begins, so nothing about the
         * photograph says it was taken too early.
         */
        void moment(Dur into) {
            tick(Dur.ms(0));
            tick(into);
        }

        /** Longer than {@link Motion#ALERT}, which is the longest thing this application plays. */
        private static final Dur PAST_THE_END = Dur.ms(500);

        /**
         * Run every animation in flight to its end, so what is photographed next is the application at rest.
         *
         * <p><b>Twice, and it has to be.</b> {@code KronoGui.ramp} defers its completion to the frame after
         * the one carrying 1, so that the end value reaches the screen before any consumer tears down --
         * which means one tick past the duration leaves the settle still owing, and a cue that never settles
         * is a node left permanently decorated.
         */
        void rest() {
            tick(PAST_THE_END);
            tick(PAST_THE_END);
        }

        // ---- photographing it -----------------------------------------------------------------------

        /** The calculator itself, at rest, at the size its window opens at. */
        void shot(String path) {
            shot(gui, CalculatorApp.W, CalculatorApp.H, path);
        }

        /** The calculator at rest at a given size -- the smallest a window manager will allow, in practice. */
        void shot(String path, int width, int height) {
            shot(gui, width, height, path);
        }

        /**
         * Any tree in the application, at any size, <b>at rest</b>.
         *
         * <p>Resting first is not tidiness. {@code Cues.play} paints its first frame synchronously, on the
         * thread that asked for it -- so a scene that presses {@code =} and photographs immediately catches a
         * scanline sitting at the top of the entry, in a picture that is supposed to be of a keypad. Every
         * scene here but one wants the application still, so still is what this gives them, and the one that
         * wants a moment asks for it by name.
         */
        void shot(Gui tree, int width, int height, String path) {
            rest();
            shotNow(tree, width, height, path);
        }

        /**
         * Photograph without settling first: this instant, whatever is mid-flight.
         *
         * <p>The whole reason the world carries a clock. Tick to the moment you mean and then call this --
         * see the {@code cue} scene, which is three of these at three chosen moments.
         */
        void shotNow(Gui tree, int width, int height, String path) {
            try {
                GuiApp.capture(tree, width, height, BG_R, BG_G, BG_B, path);
            } catch (Exception e) {
                throw new IllegalStateException("could not photograph " + path + ": " + e.getMessage(), e);
            }
            System.out.println("  wrote      " + new java.io.File(path).getAbsolutePath());
        }

        /** This instant of the calculator itself, at the size its window opens at. */
        void shotNow(String path) {
            shotNow(gui, CalculatorApp.W, CalculatorApp.H, path);
        }

        // ---- reading it -----------------------------------------------------------------------------

        /**
         * Read {@code entry} the way the engine reads it -- the session's names expanded first -- and say
         * whether it can be drawn. Null if it cannot, with {@link #refusal} saying why.
         *
         * <p>One reading of the session for the whole call, for the reason {@code Engine.definitions} gives:
         * {@link Notation#normalize} decides where a word begins and {@link Parser#parse} reads what is there,
         * and a definition landing between the two would leave them disagreeing.
         */
        Plottable plottable(String entry) {
            Bindings names = engine.bindings();
            Term term;
            try {
                term = names.expand(Parser.parse(Notation.normalize(entry, names), names));
            } catch (RuntimeException e) {
                refusal = String.valueOf(e.getMessage());
                return null;
            }
            List<String> variables = Plottable.variablesIn(term);
            if (variables.isEmpty() || variables.size() > 2) {
                refusal = variables.size() + " variables in " + entry + " -- the plotter takes one or two";
                return null;
            }
            Plottable plottable = Plottable.read(term, variables);
            if (!plottable.ok()) {
                refusal = plottable.refusal();
                return null;
            }
            return plottable;
        }

        /** Why the last {@link #plottable} came back null. */
        String refusal() {
            return refusal;
        }

        Gui gui() {
            return gui;
        }

        CalculatorApp.Engine engine() {
            return engine;
        }

        Definitions definitions() {
            return definitions;
        }

        Previews previews() {
            return previews;
        }

        /** Clock first, then the tree it drove -- the order the windowed path shuts down in. */
        @Override
        public void close() {
            for (Attached window : windows) {
                window.krono().close();
                window.gui().close();
            }
        }
    }

    // ---- running it -----------------------------------------------------------------------------------

    /** One scene and what it was told to be about, or the scene's own subject if it was told nothing. */
    private record Request(Scene scene, String subject) {
    }

    /**
     * Run {@code spec}: a comma-separated list of scene names, each optionally {@code =} something, or null
     * for all of them at their defaults.
     *
     * <p>A scene that throws is reported and the rest still run. That is not politeness -- it is what makes
     * one command able to replace five: the {@code sdf} scene wants a graphics device, and a machine without
     * one should still come away with the other four pictures rather than with nothing.
     */
    static void run(String spec) {
        List<Request> requests = parse(spec);
        if (requests == null) {
            return;
        }
        int failed = 0;
        try (World world = World.open()) {
            for (Request request : requests) {
                System.out.println(request.scene().name()
                        + (request.subject().equals(request.scene().subject())
                                ? "" : " " + request.subject()));
                try {
                    request.scene().run().accept(world, request.subject());
                } catch (RuntimeException e) {
                    failed++;
                    System.out.println("  FAILED     " + e.getMessage());
                }
            }
        }
        System.out.println(failed == 0
                ? "captured " + requests.size() + " scene(s)"
                : "captured " + (requests.size() - failed) + " of " + requests.size() + " scene(s); "
                        + failed + " failed");
    }

    /** The requested scenes, or null having said what the names are if one of them is not a scene. */
    private static List<Request> parse(String spec) {
        if (spec == null || spec.isBlank()) {
            List<Request> all = new ArrayList<>(SCENES.size());
            for (Scene scene : SCENES) {
                all.add(new Request(scene, scene.subject()));
            }
            return all;
        }
        List<Request> requests = new ArrayList<>();
        for (String piece : split(spec)) {
            int eq = piece.indexOf('=');
            String name = (eq < 0 ? piece : piece.substring(0, eq)).trim();
            String subject = eq < 0 ? null : piece.substring(eq + 1);
            Scene scene = byName(name);
            if (scene == null) {
                System.out.println("no such scene: " + name);
                System.out.println("scenes: " + names());
                return null;
            }
            requests.add(new Request(scene,
                    subject == null || subject.isBlank() ? scene.subject() : subject));
        }
        return requests;
    }

    /**
     * Split on commas <b>outside brackets</b>.
     *
     * <p>Which is not fussiness: the keypad has a comma key, and {@code atan2(x, y)} and {@code log(x, n)} are
     * both expressions someone will want to photograph. Splitting on every comma would tear one of those in
     * half and then report the halves as two scenes that do not exist.
     */
    private static List<String> split(String spec) {
        List<String> pieces = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                pieces.add(spec.substring(start, i));
                start = i + 1;
            }
        }
        pieces.add(spec.substring(start));
        return pieces;
    }

    private static Scene byName(String name) {
        for (Scene scene : SCENES) {
            if (scene.name().equals(name)) {
                return scene;
            }
        }
        return null;
    }

    /** The scene names, for the usage line and for a typo. */
    static String names() {
        StringBuilder out = new StringBuilder();
        for (Scene scene : SCENES) {
            out.append(out.isEmpty() ? "" : ", ").append(scene.name());
        }
        return out.toString();
    }
}
