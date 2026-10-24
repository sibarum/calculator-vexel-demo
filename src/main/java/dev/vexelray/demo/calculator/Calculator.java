package dev.vexelray.demo.calculator;

import dev.vexelray.framework.shell.VexelApplication;


/**
 * A small calculator window onto cott-engine.
 *
 * <h2>What this class is</h2>
 *
 * <p>The entry point, and the constants the rest of this package reads. That is all it is, and the reason is
 * worth knowing before anything else here: <b>the application edge is the framework's now.</b> Opening an
 * input backend and settling its coordinate space, installing a clipboard, remembering where the window was,
 * attaching a clock, wiring the frame loop with its wakes and its pacing, installing the dialogs, parsing the
 * command line and closing everything in the right order — this file used to be three hundred lines of exactly
 * that, near-identically to every other application on this stack.
 *
 * <p>What this application actually builds is in {@link Recipes}, one method per part. {@code CalculatorAppWiring},
 * which builds those parts in order, is generated from them and from the {@code @VexelApp} on {@code CalculatorApp}
 * while the project compiles — so the facts below are stated once, here, and the wiring reads them. Everything above
 * that is in {@link Ui}; everything the application <em>knows</em> is in {@link Model}. This class holds no state of
 * its own, and that is a rule worth keeping: the moment the edge starts remembering things, there are two places a
 * value can live.
 *
 * <pre>
 * Calculator                     the window, interactively
 * Calculator &lt;frames&gt;            run a fixed number of frames and quit (a script, not a session)
 * Calculator --key=value         override a setting for this launch
 * </pre>
 *
 * <p>A misspelled flag is refused by name with the alternatives listed, rather than a stack trace before any
 * window. Needs {@code --enable-native-access=ALL-UNNAMED}.
 *
 * <p><b>{@code CalculatorApp} declares the application.</b> Its {@code starters} is everything configuring this
 * application beyond {@link Recipes}, listed rather than discovered: {@code AutomationStarter}, the automation
 * sockets, in every build — off unless the launch or the title bar's plug switches them on, and loopback-only when
 * they are, because remote control hands whoever reaches it full control of the application's input.
 */
public final class Calculator {

    /**
     * The application's own name: its settings directory, and its installer id (installer.json), which the build
     * checks are the same. Stable across releases; a rename lists the old one in {@link #FORMER}.
     */
    static final String APP = "calculator";

    /** The names it had before, whose settings directory moves to {@link #APP}'s on the first launch after. */
    static final String FORMER = "calculator-vexel-demo";

    /** The window's title. */
    static final String TITLE = "Calculator";

    /** Window size on a first run, in the engine's logical coordinates. */
    static final int W = 480;
    static final int H = 640;

    /**
     * Entry point.
     *
     * <p>There is no screenshot flag here on purpose. A picture of a running window, at a chosen size, zoom and
     * density, is {@code ottermate}'s, because it photographs the application's own device and so is correct
     * about content as well as chrome. See the README, <i>Taking a screenshot</i>.
     */
    public static void main(String[] args) {
        String[] cleaned = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);
        VexelApplication.run(new CalculatorAppWiring(), cleaned);
    }

    private Calculator() {
    }
}
