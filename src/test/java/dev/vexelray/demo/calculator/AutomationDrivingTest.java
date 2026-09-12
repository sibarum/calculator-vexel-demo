package dev.vexelray.demo.calculator;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.automation.Automation;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application driven the way an agent drives it: {@code find}, {@code click}, {@code key} — the same
 * command surface the socket exposes, against the same tree {@code VexelApplication.tree} builds.
 *
 * <h2>Why this is worth having as a test, when automation.md says the socket is not a test harness</h2>
 *
 * <p>It says that about the <em>instrument</em> — the socket, the cursor pacing, the correlation log — and it is
 * right. What is pinned here is not the instrument but <b>this application's half of the contract</b>: the
 * landmark names a script writes down. {@code Landmarks.java} calls them a published contract, and a published
 * contract with nothing checking it is the kind that breaks quietly — a landmark that stops naming a node
 * leaves {@code click rail.layers} selecting nothing and <em>reporting ok</em>, which is the same
 * plausible-looking wrong answer {@code WiringTreeTest} was written about. Every command below goes through the
 * ordinary input path, because injection <em>is</em> the input path; there is no automation branch to drift.
 *
 * <h2>No GPU, and what that costs</h2>
 *
 * <p>{@code Phase.TREE} is as far as this can build — a device belongs to {@code WINDOW}. So the widgets, the
 * rail, the landmarks and the key handlers are all real, and the one thing that is not is the listener in
 * {@code CalculatorWiring.attach} that repaints the bar when the scene changes. Assertions after a keystroke
 * are therefore on {@link Model}, which is where the keystroke's effect actually lands; asserting on the
 * subtitle instead would be asserting that nothing happened, and would pass whether or not the key was wired.
 *
 * <h2>The expression field, and what it caught</h2>
 *
 * <p>These were written while {@code TextField.onSubmit} was unwired — nothing in the application called
 * {@code Model.submit}, so Enter edited the field and changed nothing. That is now connected in {@code Ui}, and
 * the two tests that type into the field are the ones that would have failed the whole time it was not: both go
 * red with that one line removed, reporting the entry still sitting at {@code 0^x}.
 *
 * <p>Clearing the field is End and then Backspace rather than Ctrl+A, because the {@code key} verb takes one
 * key and no chord. Reaching past the driver to set the text directly would not exercise the path that was
 * broken, which is the whole point of driving it.
 */
final class AutomationDrivingTest {

    private static final float W = 1180f;
    private static final float H = 720f;

    /** Nothing here is reachable from a setting. */
    private static final String[] NO_ARGS = new String[0];

    /**
     * Layout without a font atlas.
     *
     * <p>Text measures to nothing, which is correct for what is asserted: {@code find} matches on role, name
     * and landmark — all published in the semantic snapshot — and every node this drives has a declared size or
     * a box of its own. A node whose only extent came from its glyphs would collapse, and none of the targets
     * below is one.
     */
    private static final TextMeasurer NO_TEXT = new TextMeasurer() {
        @Override
        public float intrinsic(RetainedNode node, Axis axis, float px) {
            return 0f;
        }

        @Override
        public int offsetAt(String text, float localX, float px) {
            return 0;
        }

        @Override
        public float[] caretAdvances(String text, float px) {
            return new float[(text == null ? 0 : text.length()) + 1];
        }
    };

    /**
     * Build the application as far as {@code TREE}, lay it out, and hand the driver to the body.
     *
     * <p>Through {@code VexelApplication.tree} and not by assembling a {@code Gui} here, for the reason
     * {@code WiringTreeTest} states: a second route builds a different application, and what it silently drops
     * is whatever the framework was applying. An automation test built that way would be driving something the
     * socket never sees.
     */
    private static void driving(Path home, Consumer<Driver> body) {
        // The wiring opens Settings under $HOME/.vexel-calculator; the real one is a developer's preferences.
        System.setProperty(Calculator.APP + ".home", home.toString());
        CalculatorWiring wiring = new CalculatorWiring();
        Shell shell;
        try {
            shell = VexelApplication.tree(wiring, NO_ARGS);
        } finally {
            System.clearProperty(Calculator.APP + ".home");
        }
        try {
            Gui gui = shell.gui();
            gui.frame(W, H, NO_TEXT);
            body.accept(new Driver(gui, wiring, new Automation(gui)));
        } finally {
            shell.disposer().close();
        }
    }

    /** The application, its driver, and the two things a test needs to do between commands. */
    private record Driver(Gui gui, CalculatorWiring wiring, Automation automation) {

        String run(String command) {
            String out = automation.command(command);
            assertTrue(out.startsWith("ok"), command + " -> " + out);
            return out;
        }

        /**
         * Put {@code entry} in the expression field and commit it, the way a person with no mouse would.
         *
         * <p>End, then Backspace to the start, then the new text. <b>Not Ctrl+A</b>: the {@code key} verb takes
         * one key and no chord ({@code Key.valueOf} of the word), so a select-all is not expressible over this
         * surface — and rather than reach past the driver to clear the field directly, this does what the
         * driver can actually do. A test that set the text through the widget would not be exercising the path
         * that was broken.
         */
        void clearFieldAndType(String entry) {
            run("click " + Landmarks.EXPR);
            run("key END");
            for (int i = 0; i < 64; i++) {
                run("key BACKSPACE");
            }
            run("type " + entry);
            run("key ENTER");
        }

        /**
         * Drive frames until {@code done}, or give up after two seconds.
         *
         * <p>Frames are pumped here rather than waited for: there is no loop at {@code TREE}, so nothing else
         * will draw one, and {@code Automation.settle} would wait out its own timeout on a frame that is owed
         * and never comes.
         *
         * <p><b>The sleep is load-bearing and was learned the hard way.</b> This first spun on
         * {@code Thread.onSpinWait()} for a fixed number of iterations, and the SPACE test failed while the
         * otherwise identical C test passed — not because the shortcut was unwired, but because a shortcut's
         * command runs on the executor the shell built, and a spin that never yields can finish every
         * iteration before that executor is scheduled at all. A bounded wall-clock wait that gives the worker
         * somewhere to run is the difference between testing the application and testing the scheduler. It is
         * the exact failure automation.md attributes to "did I wait long enough?", written into the harness
         * rather than into the thing being driven.
         */
        boolean await(BooleanSupplier done) {
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (System.nanoTime() < deadline) {
                if (done.getAsBoolean()) {
                    return true;
                }
                gui.frame(W, H, NO_TEXT);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return done.getAsBoolean();
        }
    }

    private static boolean matched(String findOutput) {
        return !findOutput.startsWith("ok (nothing matches");
    }

    /**
     * The contract in `Landmarks.java`, checked against the tree an agent actually gets.
     *
     * <p>These are the names a script writes down and the reason the class exists. A renamed or unattached one
     * fails here rather than in somebody's script six months from now.
     */
    @Test
    @DisplayName("every landmark the contract publishes resolves against the real tree")
    void everyLandmarkResolves(@TempDir Path home) {
        driving(home, d -> {
            for (String landmark : new String[]{
                    Landmarks.EXPR, Landmarks.MODE, Landmarks.STATUS, Landmarks.VIEWPORT, Landmarks.READOUT}) {
                assertTrue(matched(d.run("find " + landmark)), "no node carries the landmark " + landmark);
            }
            for (String key : new String[]{"layers", "domain", "crop", "color", "sample", "view", "help"}) {
                assertTrue(matched(d.run("find " + Landmarks.rail(key))),
                        "the rail button for " + key + " is not addressable");
            }
        });
    }

    /**
     * {@code await mode <text>} is how a script waits for readiness, and it works because the badge's
     * accessible NAME is the detected mode rather than a label beside it. Design §12 says so; this is what
     * makes it true.
     */
    @Test
    @DisplayName("the mode badge is named for the mode, which is what await waits on")
    void theModeBadgeCarriesTheMode(@TempDir Path home) {
        driving(home, d -> {
            // The scene opens on 0^x -- one free name, so a curve.
            assertEquals(Algebra.Mode.CURVE, d.wiring().model().scene().reading().mode(), "the premise");
            assertTrue(d.run("find " + Landmarks.MODE).contains("CURVE"),
                    "the badge has to be NAMED the mode, or await mode CURVE waits forever");
        });
    }

    /** The subtitle says what is on screen, and a driver can read it without a screenshot. */
    @Test
    @DisplayName("the status line publishes what is drawn")
    void theStatusLinePublishesWhatIsDrawn(@TempDir Path home) {
        driving(home, d -> {
            String out = d.run("find " + Landmarks.STATUS);
            assertTrue(out.contains("curve over x"), "the subtitle should name the curve and its input: " + out);
        });
    }

    /**
     * A derivation belongs to a single value, and the opening scene is a curve — so the block is out of the
     * layout entirely. It is still in the semantic snapshot, which is the right answer: a driver can see that
     * it exists and is hidden, rather than having to tell "absent" from "not built".
     */
    @Test
    @DisplayName("the derivation block is hidden while a curve is showing")
    void theDerivationBlockIsHiddenForACurve(@TempDir Path home) {
        driving(home, d -> {
            String out = d.run("find " + Landmarks.DERIVATION);
            assertTrue(matched(out), "the block should exist even when it is not showing: " + out);
            assertTrue(out.contains("hidden"), "a curve has no single derivation to show: " + out);
        });
    }

    /**
     * A real pointer path to a rail button, then the panel it names.
     *
     * <p>The cursor does not teleport — it publishes a stepped path from where it is — so this provokes the
     * same enter/leave transitions a hand would. What is asserted is the rail's selection rather than a
     * picture, because that is the thing a script's next command depends on.
     */
    @Test
    @DisplayName("clicking a rail button by its landmark opens that panel")
    void clickingTheRailOpensThePanel(@TempDir Path home) {
        driving(home, d -> {
            d.run("click " + Landmarks.rail("layers"));
            assertTrue(d.await(() -> "layers".equals(d.wiring().panels().rail().selected())),
                    "the rail should have selected layers, not " + d.wiring().panels().rail().selected());

            d.run("click " + Landmarks.rail("view"));
            assertTrue(d.await(() -> "view".equals(d.wiring().panels().rail().selected())),
                    "and moving to another button should move the panel with it");
        });
    }

    /**
     * A keystroke through the ordinary input path, asserted where its effect lands.
     *
     * <p>SPACE is the auto-orbit toggle. Nothing repaints at {@code TREE}, so the assertion is on the scene —
     * see the class note. This is the test that would have caught a shortcut that stopped being registered.
     */
    @Test
    @DisplayName("a keystroke reaches the application's own state")
    void aKeystrokeReachesTheModel(@TempDir Path home) {
        driving(home, d -> {
            Model model = d.wiring().model();
            assertFalse(model.scene().spinning(), "the premise: the scene opens parked");

            d.run("key SPACE");
            assertTrue(d.await(() -> model.scene().spinning()), "SPACE should have started the orbit");

            d.run("key SPACE");
            assertTrue(d.await(() -> !model.scene().spinning()), "and stopped it again");
        });
    }

    @Test
    @DisplayName("the crop shortcut reaches the application's own state too")
    void theCropShortcutReachesTheModel(@TempDir Path home) {
        driving(home, d -> {
            Model model = d.wiring().model();
            assertFalse(model.scene().cropping(), "the premise");

            d.run("key C");
            assertTrue(d.await(() -> model.scene().cropping()), "C should have turned crop mode on");
        });
    }

    /**
     * The expression field, typed into and committed the way a person does it.
     *
     * <p>This is the path that was dead: {@code TextField.onSubmit} was never wired, so Enter edited the field
     * and changed nothing. Everything downstream is a consequence of the {@link Scene} changing, so the scene
     * is what this asserts on — and at {@code Phase.TREE} it is also all there is, per the class note.
     */
    @Test
    @DisplayName("typing an expression and pressing Enter commits it")
    void typingAnExpressionCommitsIt(@TempDir Path home) {
        driving(home, d -> {
            Model model = d.wiring().model();
            assertEquals(Algebra.DEFAULT_EXPRESSION, model.scene().expression(), "the premise");

            d.clearFieldAndType("2+2");

            assertTrue(d.await(() -> "2+2".equals(model.scene().expression())),
                    "Enter should have committed the entry, not left it in the field: "
                            + model.scene().expression());
        });
    }

    /**
     * And the reading is settled in the same version as the entry, so nothing downstream can read one without
     * the other. {@code 2+2} has no free names, so committing it turns a curve into a placed point.
     */
    @Test
    @DisplayName("a committed expression brings its reading with it")
    void aCommittedExpressionBringsItsReading(@TempDir Path home) {
        driving(home, d -> {
            Model model = d.wiring().model();
            assertEquals(Algebra.Mode.CURVE, model.scene().reading().mode(), "the premise: 0^x is a curve");

            d.clearFieldAndType("2+2");

            assertTrue(d.await(() -> model.scene().reading().mode() == Algebra.Mode.POINT),
                    "a closed expression should read as a point, not " + model.scene().reading().mode());
            assertEquals("4", model.scene().reading().answer(), "and the engine should have answered it");
        });
    }

    /**
     * An unknown command is answered rather than thrown.
     *
     * <p>Worth one test because the driver's contract is that it never dies on a bad line — a session lost to a
     * typo is a session lost in the middle of whatever it was investigating.
     */
    @Test
    @DisplayName("a command this application has no answer for is refused, not fatal")
    void anUnknownTargetIsRefusedRatherThanFatal(@TempDir Path home) {
        driving(home, d -> {
            String out = d.automation().command("click rail.nosuchpanel");
            assertTrue(out.startsWith("err"), "a target that names nothing should say so: " + out);
            // And the driver is still usable afterwards, which is the actual claim.
            assertTrue(matched(d.run("find " + Landmarks.EXPR)));
        });
    }
}
