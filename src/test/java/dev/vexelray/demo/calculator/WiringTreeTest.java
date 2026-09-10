package dev.vexelray.demo.calculator;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What {@code VexelApplication.tree} hands back is this application, not a near-miss of it.
 *
 * <p>These assertions were written for a capture instrument that has since been retired, and they outlived it
 * because they were never really about photography. That instrument used to build its own tree — {@code new
 * Gui()}, the theme, the minimum size and a {@code TitleBar} by hand — which is the hazard
 * {@code VexelApplication.toTree} names: <i>"a capture that built its tree by a second route would be a
 * capture of a different application."</i> What the second route silently dropped was the application's own
 * zoom range, and <b>nothing visible went wrong</b>; that is why the claim is worth a test rather than a
 * comment.
 *
 * <p>The claim now stands on its own: an application declares its theme, its zoom range and its rail in
 * {@code CalculatorWiring}, and the framework is what applies them. Anything driving this application — a
 * test, the automation socket, a person — is entitled to the same tree.
 *
 * <p><b>No GPU.</b> The tree, the theme and the zoom range are all built before a device exists, which is the
 * whole claim {@code Phase.TREE} makes.
 */
final class WiringTreeTest {

    /** Nothing to override: none of what is asserted here is reachable from a setting. */
    private static final String[] NO_ARGS = new String[0];

    private static Shell tree(Path home, CalculatorWiring wiring) {
        // The wiring opens Settings under $HOME/.vexel-calculator; a test that used the real one would read a
        // developer's preferences and could write to them.
        System.setProperty(Calculator.APP + ".home", home.toString());
        try {
            return VexelApplication.tree(wiring, NO_ARGS);
        } finally {
            System.clearProperty(Calculator.APP + ".home");
        }
    }

    @Test
    void theTreeCarriesTheApplicationsOwnZoomRange(@TempDir Path home) {
        CalculatorWiring wiring = new CalculatorWiring();
        Shell shell = tree(home, wiring);
        try {
            Gui gui = shell.gui();
            // The concrete thing the second route got wrong. A hand-built Gui keeps the library default of
            // [0.25, 4]; the framework applies this application's Appearance.ZoomRange before the first widget.
            // This application's ceiling is 3, so asking for 4 has to come back clamped to it rather than to
            // the library's -- and a `zoom` verb on the automation socket will owe the same answer, which is
            // why this outlived the instrument it was written for.
            gui.zoom(4f);
            assertEquals(3f, gui.zoom().value(), 1e-4f,
                    "the tree's Gui must clamp to the application's zoom range, not the library default");
            gui.zoom(0.1f);
            assertEquals(0.5f, gui.zoom().value(), 1e-4f, "and at the bottom of it too");
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void theTreeIsThemedByTheApplication(@TempDir Path home) {
        CalculatorWiring wiring = new CalculatorWiring();
        Shell shell = tree(home, wiring);
        try {
            // Read off the tree rather than off Look a second time. Same colour today, which is the point:
            // one reading instead of two that have to agree.
            assertSame(Look.THEME, shell.gui().theme(), "the framework applies the application's theme");
            assertEquals(Calculator.page(), shell.gui().theme().color(Role.PAGE));
        } finally {
            shell.disposer().close();
        }
    }

    /**
     * The rail's panel names, which are this application's addressable vocabulary.
     *
     * <p>Written when a capture wrote one file per name in this list; kept because <b>the names are now how
     * anything drives the rail</b>. An automation script says {@code click rail.layers}, so a key that
     * silently stopped naming a panel would leave a script selecting nothing and reporting success — the same
     * plausible-looking wrong answer as the picture of a rail with nothing open, which is the failure this
     * pins down either way.
     */
    @Test
    void everyPanelNameOnTheRailResolves(@TempDir Path home) {
        CalculatorWiring wiring = new CalculatorWiring();
        Shell shell = tree(home, wiring);
        try {
            assertNotNull(wiring.panels(), "the rail exists once TREE has run, and this is the way to it");
            for (String key : new String[]{"layers", "domain", "crop", "color", "sample", "view", "help"}) {
                wiring.panels().rail().select(key);
                assertEquals(key, wiring.panels().rail().selected(), "no panel is named " + key);
            }
            // Rail's documented answer for a key naming no panel: shut it rather than fail.
            wiring.panels().rail().select("nosuchpanel");
            assertEquals(null, wiring.panels().rail().selected(), "an unknown panel name shuts the panel");
        } finally {
            shell.disposer().close();
        }
    }
}
