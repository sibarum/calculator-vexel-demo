package dev.vexelray.demo.calculator;

import dev.vexelray.demo.calculator.Messages.Enter;
import dev.vexelray.demo.calculator.Messages.Entered;
import dev.vexelray.framework.api.Job;

/**
 * A line evaluated on the job lane: cott-engine at the limits the settings allow can take far longer than a frame,
 * so the {@link Screen} sends the line here and draws the {@link Model}'s document when the answer comes back.
 */
@Job
public final class Evaluate {

    private final Model model;

    public Evaluate(Model model) {
        this.model = model;
    }

    public Entered run(Enter request) {
        return new Entered(request.line(), model.enter(request.line()));
    }
}
