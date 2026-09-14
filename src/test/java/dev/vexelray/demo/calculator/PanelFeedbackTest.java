package dev.vexelray.demo.calculator;

import dev.vexelray.framework.shell.Shell;
import dev.vexelray.framework.shell.VexelApplication;
import dev.vexelray.gui.core.layout.LayoutEnums.Axis;
import dev.vexelray.gui.core.layout.TextMeasurer;
import dev.vexelray.gui.core.model.RetainedNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The panels are a view of the model <em>and</em> a way to edit it, and that is a cycle unless one direction is
 * silent.
 *
 * <p>It was not. {@code Panels} republishes every inspector on any model change; {@code Inspector.Card.refresh}
 * wrote the model's value into its switch; and {@code Toggle} notified its handler on a programmatic set the
 * same as on a click — so the refresh read as an edit, the edit committed, the commit republished, without
 * bound and regardless of the value. Opening LAYERS and then changing anything at all overflowed the stack on
 * the GUI worker. Fixed in the widget module by {@code Toggle.show}, which moves the knob without notifying,
 * and both refresh paths now use it.
 *
 * <p>Pinned here rather than only there because it takes this application's wiring to close the circuit: the
 * widget on its own is not wrong, and neither is {@code State.commit} fanning out unconditionally. What was
 * wrong was the three of them together.
 */
final class PanelFeedbackTest {

    /** Layout without a font atlas; nothing here is measured by its glyphs. */
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

    @Test
    @DisplayName("changing the model with a panel open does not feed back into itself")
    void aPanelDoesNotEditTheModelWhileReadingIt(@TempDir Path home) {
        built(home, wiring -> {
            Model model = wiring.model();
            long before = model.version();
            assertDoesNotThrow(() -> model.change(s -> Scene.with(s, w -> w.spinning(!s.spinning()))),
                    "the panel refreshed, wrote back, and refreshed again");
            assertEquals(before + 1, model.version(),
                    "one change should be one commit; more than one means the panel committed too");
        });
    }

    /**
     * The same circuit through a card's own switch rather than through an unrelated field, which is the path a
     * person takes: click Line off, and the card that owns the switch is the first thing refreshed.
     */
    @Test
    @DisplayName("toggling a layer card commits once, not once per refresh")
    void togglingALayerCardCommitsOnce(@TempDir Path home) {
        built(home, wiring -> {
            Model model = wiring.model();
            boolean was = model.scene().cards().line();
            long before = model.version();
            assertDoesNotThrow(() -> model.change(s -> Scene.with(s, w -> w.cards(
                    new Scene.Cards(!was, s.cards().surface(), s.cards().volume(), s.cards().axes(),
                            s.cards().grid(), s.cards().lineOpen(), s.cards().axesOpen(), s.cards().gridOpen())))));
            assertEquals(before + 1, model.version());
            assertEquals(!was, model.scene().cards().line(), "the switch should have landed where it was put");
        });
    }

    /** Build the application to {@code TREE}, open the layers panel, and hand the wiring to the body. */
    private static void built(Path home, Consumer<CalculatorWiring> body) {
        System.setProperty(Calculator.APP + ".home", home.toString());
        CalculatorWiring wiring = new CalculatorWiring();
        Shell shell;
        try {
            shell = VexelApplication.tree(wiring, new String[0]);
        } finally {
            System.clearProperty(Calculator.APP + ".home");
        }
        try {
            shell.gui().frame(1180f, 720f, NO_TEXT);
            // The panels are built when first opened, so the cycle does not exist until one is. LAYERS is the
            // one with switched cards in it, which is what the cycle ran through.
            wiring.panels().rail().select("layers");
            shell.gui().frame(1180f, 720f, NO_TEXT);
            body.accept(wiring);
        } finally {
            shell.disposer().close();
        }
    }
}
