package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.style.Role;

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
 * Calculator --automation=on      the window, driveable over the socket
 * </pre>
 *
 * <p><b>There was a {@code --capture} and it is gone.</b> It rendered the tree to a PNG with no window, and
 * for this application that picture was a lie: {@code GuiApp.capture} builds its own device, a
 * {@code SampledColorTarget} belongs to the application's, and so the marched plot -- the thing this window is
 * for -- photographed as the framework's placeholder while the chrome around it came out perfect. The
 * framework removed its own {@code RunMode.CAPTURE} for that reason and this was the last copy on the stack.
 * A still now comes from the running window, on the application's own device, either by hand from the title
 * bar's screenshot instrument or through the automation socket's {@code shot}. See
 * {@code vexelray-gui/docs/automation-cli.md}.
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
     * The smallest this UI is still coherent at, in root ems — a floor, not the design size. Below it the rail
     * plus a 284px panel leaves nothing for the plot, which is the thing the window is for.
     *
     * <p>Named here rather than written at each use because it is read from more than one place and they must
     * agree: {@link CalculatorWiring#config} declares it to the framework, and anything photographing the tree
     * at exactly its minimum has to ask for the same number. Two literals would be a picture of a minimum the
     * application does not have.
     *
     * <p><b>Nothing photographs it today</b>, and that is a gap rather than a decision -- the retired capture
     * had a {@code smallest} scene and the automation socket has no {@code resize} verb to replace it. It is
     * recorded as V2 in {@code vexelray-gui/docs/automation-cli.md}; this constant is what that verb will need
     * to be handed. The last two defects at minimum size were both clipped bottom rows found by looking at
     * that picture.
     */
    static final float MIN_W_EM = 46;
    static final float MIN_H_EM = 30;

    /**
     * Entry point, and <b>nothing but</b> one.
     *
     * <p>This method used to pick {@code --capture} out of {@code args} ahead of the framework, because a
     * scene name is not an output filename and {@code Launch} would have read it as one. With that instrument
     * retired there is no argument this application understands better than the framework does, so there is no
     * pre-parse left: {@code Launch} owns the whole command line, including {@code --automation} and
     * {@code --profile}, and an unrecognised flag gets its usage message rather than a stack trace.
     */
    public static void main(String[] args) {
        VexelApplication.run(new CalculatorWiring(), args);
    }

    /** The clear colour behind the tree: the same role the root paints, so the frame is never a second opinion. */
    static Color page() {
        return Look.THEME.color(Role.PAGE);
    }

    private Calculator() {
    }
}
