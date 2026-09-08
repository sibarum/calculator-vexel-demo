package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.style.Role;

import java.io.IOException;

/**
 * The plot viewport: type an expression, look at it.
 *
 * <p>This class used to be the <b>application edge</b> -- opening an input backend and settling its coordinate
 * space, installing a clipboard, remembering where the window was, attaching a clock, wiring the frame loop and
 * its wakes, parsing the command line, and closing everything in the right order. All of that is now
 * {@code vexelray-framework}'s, and what this application actually builds is in {@link CalculatorWiring}.
 * Everything above that is in {@link Ui}; everything the application <em>knows</em> is in {@code Model}.
 *
 * <pre>
 * Calculator                      the window, interactively
 * Calculator &lt;frames&gt;             run a fixed number of frames and quit (a script, not a session)
 * Calculator --capture &lt;scene&gt; [out.png]   headless PNG; see {@link Capture}
 * </pre>
 *
 * <p>Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class Calculator {

    /** The application's own name, which is what its settings directory is called. */
    static final String APP = "vexel-calculator";

    /** Window size on a first run, in the engine's logical coordinates. The prototype declares 1180x720. */
    static final int W = 1180;
    static final int H = 720;

    /**
     * Entry point.
     *
     * <p>{@code --capture} is handled before the framework sees the arguments, and that is not an oversight.
     * The framework has a capture mode of its own -- one frame, one file, no window, no input backend -- but
     * {@link Capture} is a richer, application-specific instrument: a zoom ladder, every rail panel, the tree
     * at exactly {@code minSize}, each on a tree deliberately built with a camera that goes nowhere. Routing
     * {@code --capture zoom} through the framework would read {@code zoom} as an output filename. Generalising
     * the framework's capture to cover this is an open question; pretending it already does would break a
     * documented tool.
     */
    public static void main(String[] args) throws IOException {
        String[] cleaned = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);
        if (cleaned.length >= 1 && cleaned[0].equals("--capture")) {
            Capture.run(cleaned);
            return;
        }
        VexelApplication.run(new CalculatorWiring(), cleaned);
    }

    /** The clear colour behind the tree: the same role the root paints, so the frame is never a second opinion. */
    static Color page() {
        return Look.THEME.color(Role.PAGE);
    }

    private Calculator() {
    }
}
