package dev.vexelray.demo.calculator;

import dev.mainframe.eval.Args;
import dev.mainframe.eval.Builtin;
import dev.mainframe.eval.Registry;
import dev.mainframe.eval.Signature;
import dev.mainframe.gui.app.ConsoleApp;
import dev.mainframe.gui.app.ConsoleContext;
import dev.mainframe.value.Value;
import dev.mainframe.value.ValueType;
import dev.vexelray.gui.core.app.WindowMemory;
import dev.vexelray.gui.core.input.MenuSink;
import sibarum.cott.Cott;
import sibarum.cott.Notation;
import sibarum.cott.Parser;
import sibarum.cott.Render;
import sibarum.cott.SyntaxException;
import sibarum.cott.Term;

import java.util.List;

/**
 * The calculator, as far as MainFrame is concerned: one command and a window it can open.
 *
 * <h2>Which way round this goes</h2>
 * The calculator used to be a program with a {@code main()} that owned a frame loop, and the plot and history
 * windows were things it opened. Plugged in here it is one rung down: MainFrame is the program, {@code apps}
 * lists the calculator among whatever else is on the desk, and {@code launch "calculator"} opens it. The
 * calculator's own windows still open out of it, so the tree is a tree rather than a list — which is what an
 * operating system's window list has always been.
 *
 * <p>Nothing in {@link CalculatorApp} had to be rewritten for that. {@link CalculatorApp.Window} is the same
 * keypad, the same engine and the same previews, opened under a name on somebody else's {@code GuiApp} instead
 * of being the main window of its own.
 *
 * <h2>calc, and why the window is not the only way in</h2>
 * {@code calc} evaluates an expression through the same COTT reduction the {@code =} key runs, and answers with
 * text rather than opening anything. That matters more than it looks: an answer that is a value is an answer the
 * rest of the language can work on, so a calculator in a shell is not a calculator you have to read numbers off
 * a screen from.
 *
 * <pre>{@code
 * ~ > calc "1÷(x^2−1)"
 * ~ > ls | where kind == "file" | first 1 | calc "2^10"
 * }</pre>
 */
public final class Calculator implements ConsoleApp {

    private final CalculatorApp.Window window;

    /**
     * @param memory where the calculator's window keeps its placement and zoom — the desk's, shared with the
     *               console and with the plot previews, because a window memory is one file with one key per
     *               window
     */
    public Calculator(WindowMemory memory) {
        this.window = new CalculatorApp.Window(memory);
    }

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String summary() {
        return "a keypad, a tape, and a plotter for anything with a variable in it";
    }

    @Override
    public void commands(Registry registry, ConsoleContext console) {
        registry.add(calc());
    }

    @Override
    public boolean launchable() {
        return true;
    }

    /**
     * Open the calculator, or raise it if it is already up.
     *
     * <p>Frame loop — {@code launch} does the thread hop, so this is free to build a window. Empty when the
     * console is running headless, which is the capture path: there is no application to open onto, and
     * {@code launch} has already refused for that reason before reaching here.
     */
    @Override
    public void launch(ConsoleContext console) {
        console.host().ifPresent(window::show);
    }

    /** The history's queue is drained here. See {@link CalculatorApp.Window#tick}. */
    @Override
    public void tick() {
        window.tick();
    }

    @Override
    public void menu(MenuSink menu, ConsoleContext console) {
        menu.item("Open the calculator", () -> console.run("launch \"calculator\""));
    }

    /** This window's Gui, so the host can bind the OS clipboard on it as it does on every other window. */
    public dev.vexelray.gui.core.Gui gui() {
        return window.gui();
    }

    // ---- the command -----------------------------------------------------------------

    /**
     * {@code calc} — reduce an expression and answer with what it came to.
     *
     * <p>Straight to COTT, not through the keypad's {@code Engine}: the engine's job is to edit a display field
     * and it would have to be given one. These are the same three lines the {@code =} key runs, which is the
     * point — a shell that agreed with the keypad only most of the time would be worse than not having one.
     */
    private Builtin calc() {
        Signature signature = Signature.named("calc", name())
                .summary("reduce an expression, the way the calculator's = key does")
                .rest("expression", ValueType.STRING, "what to work out; several are worked out in turn")
                .input(ValueType.NOTHING)
                .output(ValueType.STRING)
                .effect(Signature.Effect.PURE)
                .example("calc \"2^10\"")
                .example("calc \"x÷x\"")
                .example("calc \"(1+2)*3\" | save ./answer.txt")
                .build();
        return new Builtin() {
            @Override
            public Signature signature() {
                return signature;
            }

            @Override
            public Value run(Args args) {
                List<String> entries = args.strings(0);
                if (entries.isEmpty()) {
                    throw args.failUsage("E830", "calc needs something to work out")
                            .hint("calc \"2^10\"")
                            .hint("launch \"calculator\" opens the keypad instead")
                            .build();
                }
                List<Value> answers = new java.util.ArrayList<>();
                for (String entry : entries) {
                    answers.add(new Value.Str(reduce(args, entry)));
                }
                return answers.size() == 1 ? answers.getFirst() : new Value.ListVal(answers);
            }
        };
    }

    /**
     * One expression, reduced.
     *
     * <p>A syntax error is the shell's kind of error, with the message COTT already wrote — it says where the
     * expression stopped making sense, which is the whole of what anyone needs. An evaluator fault is a
     * different code, because "this is not valid" and "this is valid and I could not do it" are not the same
     * news.
     */
    private static String reduce(Args args, String entry) {
        try {
            Term term = Parser.parse(Notation.normalize(entry));
            return Render.show(Cott.reduce(term));
        } catch (SyntaxException e) {
            throw args.fail("E831", e.getMessage())
                    .hint("the multiplication sign is " + Notation.TIMES + ", and * is read as it too")
                    .build();
        } catch (RuntimeException e) {
            throw args.fail("E832", "that expression could not be worked out")
                    .hint("it parsed, so this is the evaluator giving up rather than a typo")
                    .build();
        }
    }
}
