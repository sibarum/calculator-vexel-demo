package dev.vexelray.demo.calculator;

import dev.vexelray.framework.api.VexelApp;

/**
 * The release edition's application declaration: {@link Calculator}'s facts and no starters. In particular no
 * {@code AutomationStarter}, and the pom drops {@code vexelray-framework-automation} from the classpath under
 * {@code -Pnative-release}, so a shipped binary cannot open a driving socket. The debug edition is
 * {@code src/edition-debug}; keep the two annotations identical apart from {@code starters}.
 */
@VexelApp(name = Calculator.APP, title = Calculator.TITLE, width = Calculator.W, height = Calculator.H,
        icon = "/calculator.ico")
final class CalculatorApp {

    private CalculatorApp() {
    }
}
