package dev.vexelray.demo.calculator;

import dev.mainframe.gui.desktop.Desktop;

import java.util.List;

/**
 * The calculator, the other way round: MainFrame comes up, and the calculator is something it opens.
 *
 * <pre>{@code
 * mvn compile exec:exec "-Dapp.mainClass=dev.vexelray.demo.calculator.CalculatorDesktop"
 *
 * ~ > apps
 * name        launchable  summary
 * profiles    false       named sets of environment variables and binary directories
 * calculator  true        a keypad and a tape
 *
 * ~ > launch "calculator"
 * ~ > calc "x^2−y^2"
 * }</pre>
 *
 * <h2>Why this is six lines</h2>
 * Everything that is not the calculator is somebody else's now. The frame loop, the window memory, the input
 * backend, the clipboard, the title bar, the shell and its profiles are all in
 * {@link Desktop#run}, which is one boot shared by every application built this way. What is left here is the
 * one fact this application has that no other does: which apps are in it.
 *
 * <p>{@link CalculatorApp#main} still exists and still works, and is still the calculator as its own program.
 * The two arrangements are built out of exactly the same parts — see {@link CalculatorApp.Window} — so neither
 * one is a fork of the other, and neither has to be kept in step with the other by hand.
 *
 * <p>Settings are shared with the standalone calculator on purpose: the same {@code calculator} settings file,
 * so every window comes back where it was left whichever way the calculator was started.
 *
 * <p>Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class CalculatorDesktop {

    private CalculatorDesktop() {
    }

    public static void main(String[] args) throws Exception {
        Desktop.run("calculator", "MainFrame",
                (settings, memory) -> List.of(new Calculator(memory)), args);
    }
}
