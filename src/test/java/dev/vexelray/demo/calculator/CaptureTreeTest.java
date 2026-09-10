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
 * The tree {@link Capture} photographs is the tree the application runs.
 *
 * <p>{@code Capture} used to build its own — {@code new Gui()}, the theme, the minimum size and a
 * {@code TitleBar} by hand — which is the hazard {@code VexelApplication.toTree} names: <i>"a capture that
 * built its tree by a second route would be a capture of a different application."</i> Now every scene goes
 * through {@code VexelApplication.tree}, and this is what says so, because the difference the second route made
 * was invisible in the pictures.
 *
 * <p><b>No GPU.</b> This stops where {@code Capture} reaches for {@code GuiApp.capture}: the tree, the theme
 * and the zoom range are all built before a device exists, which is the whole claim {@code Phase.TREE} makes.
 * What is not covered is the PNG itself, which is library code this application does not own.
 */
final class CaptureTreeTest {

    /** As {@code Capture} passes: a still frame has nothing a setting override could change. */
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
    void theCaptureTreeCarriesTheApplicationsOwnZoomRange(@TempDir Path home) {
        CalculatorWiring wiring = new CalculatorWiring();
        Shell shell = tree(home, wiring);
        try {
            Gui gui = shell.gui();
            // The concrete thing the second route got wrong. A hand-built Gui keeps the library default of
            // [0.25, 4]; the framework applies this application's Appearance.ZoomRange before the first widget.
            // Capture's ladder ends at 3, so asking for 4 has to come back clamped to the application's ceiling
            // rather than the library's.
            gui.zoom(4f);
            assertEquals(3f, gui.zoom().value(), 1e-4f,
                    "the capture's Gui must clamp to the application's zoom range, not the library default");
            gui.zoom(0.1f);
            assertEquals(0.5f, gui.zoom().value(), 1e-4f, "and at the bottom of it too");
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void theCaptureTreeIsThemedByTheApplication(@TempDir Path home) {
        CalculatorWiring wiring = new CalculatorWiring();
        Shell shell = tree(home, wiring);
        try {
            // Capture reads its clear colour off this rather than off Look a second time. Same colour today,
            // which is the point: it is one reading instead of two that have to agree.
            assertSame(Look.THEME, shell.gui().theme(), "the framework applies the application's theme");
            assertEquals(Calculator.page(), shell.gui().theme().color(Role.PAGE));
        } finally {
            shell.disposer().close();
        }
    }

    @Test
    void everyPanelTheCaptureNamesIsOnTheRail(@TempDir Path home) {
        CalculatorWiring wiring = new CalculatorWiring();
        Shell shell = tree(home, wiring);
        try {
            assertNotNull(wiring.panels(), "the rail exists once TREE has run; Capture reaches it through this");
            // Capture writes one file per key in this list, so a key that stopped naming a panel would quietly
            // produce a picture of the rail with nothing open -- a file that still looks plausible.
            for (String key : new String[]{"layers", "domain", "crop", "color", "sample", "view", "help"}) {
                wiring.panels().rail().select(key);
                assertEquals(key, wiring.panels().rail().selected(), "no panel is named " + key);
            }
            // And the default scene's behaviour: a key naming no panel shuts it rather than failing.
            wiring.panels().rail().select("default");
            assertEquals(null, wiring.panels().rail().selected(), "an unknown scene name shuts the panel");
        } finally {
            shell.disposer().close();
        }
    }
}
