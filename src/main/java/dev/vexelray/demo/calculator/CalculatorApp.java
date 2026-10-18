package dev.vexelray.demo.calculator;

import dev.vexelray.framework.api.VexelApp;
import dev.vexelray.framework.automation.AutomationStarter;

/**
 * The application declaration: {@link Calculator}'s facts plus {@link AutomationStarter}, the automation sockets, in
 * every build, the release one included.
 *
 * <p>The sockets are off unless the launch asks ({@code --automation}, {@code VEXELRAY_AUTOMATION}) or a person
 * switches them on from the title bar's plug, and loopback-only when they are on. The plug's lamps show each one's
 * port open or not and anything connected now or earlier; {@code --automation.available=none} leaves them out of a
 * run altogether. The processor generates {@code CalculatorAppWiring} from this.
 */
@VexelApp(name = Calculator.APP, title = Calculator.TITLE, width = Calculator.W, height = Calculator.H,
        icon = "/calculator.ico", starters = AutomationStarter.class)
final class CalculatorApp {

    private CalculatorApp() {
    }
}
