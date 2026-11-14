package dev.vexelray.demo.calculator;

import dev.vexelray.demo.calculator.Messages.ModeSet;
import dev.vexelray.demo.calculator.Messages.SetMode;
import dev.vexelray.framework.api.Job;

/**
 * A mode changed on the job lane rather than in the frame: it waits for the calculator's lock, which a long
 * evaluation can hold.
 */
@Job
public final class ChangeMode {

    private final Model model;

    public ChangeMode(Model model) {
        this.model = model;
    }

    public ModeSet run(SetMode request) {
        model.set(request.modeset().modes().get(request.index()));
        return new ModeSet(request.modeset(), request.index());
    }
}
