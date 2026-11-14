package dev.vexelray.demo.calculator;

import dev.vexelray.framework.api.Configuration;
import dev.vexelray.framework.api.Provides;
import dev.vexelray.framework.shell.Appearance;
import dev.vexelray.framework.shell.Window;
import dev.vexelray.framework.shell.Windows;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.widget.TitleBar;

/**
 * What this application builds — one recipe per part — and nothing about when.
 *
 * <h2>The wiring is generated from this</h2>
 *
 * <p>{@code CalculatorAppWiring} is written by {@code vexelray-framework-processor} while this project compiles,
 * and lands in {@code target/generated-sources/annotations}. It calls each method below once, in the phase its
 * parameters put it in: <b>a part's phase is the latest phase of anything it takes</b>, so the look and the model,
 * which take nothing the framework builds late, exist before the {@code Gui} does, and the tree waits for the
 * {@code Gui} and its title bar. There is no phase to declare and none to get wrong: a part asking for something
 * that does not exist yet is not a part that can be written.
 *
 * <p>The automation sockets are not here either. They come from {@code AutomationStarter}, which
 * {@code CalculatorApp} names in its {@code @VexelApp} — so this application is drivable because it says so in one
 * place, and a {@code @Provides Driver} method here would replace the starter's with this application's own.
 *
 * <h2>What is not here is the point</h2>
 *
 * <p>Opening an input backend and settling its coordinate space, installing a clipboard, remembering where the
 * window was, attaching a clock, wiring the frame loop with its wakes and its pacing, parsing the command line,
 * installing the dialogs, and closing everything in the right order — every one of those used to be written out
 * by hand, and all of them are {@code vexelray-framework}'s now. So is the order this file's parts are built in,
 * and so is closing the ones that are {@code AutoCloseable}.
 *
 * <p>Adding a part is a method here. It returns an interface when other code should be able to swap it; the
 * application's own package-private classes, like {@link Model} and {@link Ui}, may be returned as they are,
 * because nothing outside this package could hold a call site against them anyway. Watch out for the case that
 * is easy to read past — an announcement depends on its <em>listeners</em>, so seeding state belongs after the
 * things it wakes up rather than beside the model it edits.
 */
@Configuration
final class Recipes {

    /**
     * The look, and no size floor. A floor in em grows with the zoom, and once it is past the window the root
     * scrolls to reach the rest; this layout fits any window instead, the tape giving up its room first.
     *
     * <p>Built from nothing the framework makes, so it exists in the first phase, and the framework applies it the
     * moment it creates the {@code Gui}. That is what makes "the theme is applied before the first widget"
     * structural rather than a comment somebody has to keep obeying; a look that took the {@code Gui} would be a
     * compile error, not a half-themed window.
     */
    @Provides
    Appearance look() {
        return Appearance.of(Look.THEME);
    }

    /** The one authoritative state, built before the tree because every control is a view onto it. */
    @Provides
    Model model() {
        return new Model();
    }

    /**
     * The tree. Needs the {@code Gui} and its title bar, and no window.
     *
     * <p>That last part is a claim worth keeping true as this grows: a tree that can be built without a window is
     * a tree a test can build and read headlessly. It is also why the initial {@code show} is here rather than
     * later — the tree a test gets should be the tree a user gets, already carrying the document rather than
     * whatever a node was constructed with.
     *
     * <p>The title bar is the framework's. Chrome placement belongs to whoever owns the window, so that the
     * screenshot instrument in it means the same thing in every window on the desk; this application places the
     * node and supplies every colour in it.
     */
    @Provides
    Ui ui(Gui gui, Model model, TitleBar titleBar) {
        Ui ui = new Ui(gui, model, titleBar);
        ui.show(model.doc());
        return ui;
    }

    /**
     * The settings window: one drop-down per cott-engine modeset, kept in step with the document by the
     * {@link Screen}, which also carries out what the gear asks.
     *
     * <p>One declaration: the framework builds the window's tree now, beside the main one, and claims the window
     * once there is a device, so this needs no window and is not main-thread. Showing it is a
     * {@code WindowCommand}, which only a part with a sender can send, so the gear's press goes through the screen.
     */
    @Provides
    SettingsWindow settingsWindow(Windows windows, Model model) {
        SettingsWindow settings = new SettingsWindow(windows.named(SettingsWindow.DECLARED));
        settings.show(model.doc());
        return settings;
    }

    /**
     * The plot explorer: a window a plot on the tape pops out into, to be panned, zoomed and read closely. Declared
     * as the settings window is, and the tape's pop-out buttons open it, through the {@link Screen}.
     */
    @Provides
    PlotExplorer plotExplorer(Windows windows, Model model) {
        Window window = windows.named(PlotExplorer.DECLARED);
        return new PlotExplorer(window, model);
    }
}
