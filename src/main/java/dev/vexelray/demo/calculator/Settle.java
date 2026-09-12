package dev.vexelray.demo.calculator;

import sibarum.kronometer.Cell;
import sibarum.kronometer.Curve;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Rate;

/**
 * "Nothing has changed for a moment" — as a cell, because it is a value with a future.
 *
 * <h2>Why this is not a flag and a frame counter</h2>
 *
 * <p>{@link March} needs to know when the picture has stopped moving, so it can march the sharp one. The
 * obvious implementation — raise a flag on every change, clear it on a frame where nothing changed — does not
 * work at all in a render-on-demand loop, and the way it fails is worth stating because it is not a bug you
 * can see in the code: <b>the frame after the last change never happens</b>. The loop parks when nothing is
 * animating, so the last frame of a drag is the last frame, full stop, and a flag waiting for the next one
 * waits until the user touches something else. The plot would stay soft after every orbit and sharpen the
 * instant you did anything at all, which reads as a renderer that cannot make up its mind.
 *
 * <p>What keeps a parked loop awake is a <em>driven</em> cell — {@code varyingUntil} in the future — so the
 * pause has to be something the clock is carrying rather than something an application is counting. Each
 * {@link #stir} re-anchors a countdown curve from now; while it runs the loop is awake and doing nothing
 * (a frame that marches nothing costs a comparison); when it reaches zero the cell stops varying, the loop is
 * owed exactly one more frame, and that frame is the one the refinement happens on.
 *
 * <p>The value is the countdown itself rather than a boolean, which is what makes a stir <em>replace</em> the
 * pause instead of racing it: a second change half way through simply drives a fresh curve, and there is no
 * timer to cancel and no pending callback to remember to drop.
 */
final class Settle {

    private final Kron kron;
    private final Dur pause;
    private final Cell<Double> remaining;
    private final Runnable settled;

    /**
     * Whether the current pause has already been reported.
     *
     * <p>The sink runs every frame for the life of the window, and a cell that has run down stays run down, so
     * without this the callback would fire on every idle frame rather than on the one where the pause ended.
     * Touched only from the timeline — the sink runs there, and {@link #stir} queues its write onto it.
     */
    private boolean fired = true;

    /**
     * @param pause   how long nothing must change before {@code settled} runs
     * @param settled run once per quiet stretch, on the timeline, before the frame's own work
     */
    Settle(Kron kron, Rate frames, Dur pause, Runnable settled) {
        this.kron = kron;
        this.pause = pause;
        this.settled = settled;
        // Creating and binding a cell is setup rather than timeline work, exactly as in Motion's constructor:
        // a cell is a constructor and Rate.each appends to a list. Only driving it crosses, which is stir().
        this.remaining = kron.bound(frames, "settle", 0.0, this::land);
    }

    /** Something changed: the picture is moving, and the pause starts again from here. */
    void stir() {
        kron.onTimeline(() -> {
            fired = false;
            remaining.drive(Curve.ramp(1.0, 0.0, pause));
        });
    }

    /**
     * Where the countdown lands, once a frame.
     *
     * <p>{@code Cell} clamps a curve's elapsed time to its extent, so the first frame at or after the deadline
     * reads exactly zero — and a driven cell holds the loop awake until that deadline, so there is always such
     * a frame.
     */
    private void land(double left) {
        if (fired || left > 0) {
            return;
        }
        fired = true;
        settled.run();
    }
}
