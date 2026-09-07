package dev.vexelray.demo.calculator;

import sibarum.kronometer.Cell;
import sibarum.kronometer.Curve;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Interp;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Rate;
import sibarum.kronometer.anim.Animator;
import sibarum.kronometer.anim.Ease;

/**
 * The camera, as one cell the clock can drive.
 *
 * <h2>Why the camera is a cell and not six fields on {@link March}</h2>
 *
 * <p>It began as six fields, and every way of animating them is wrong. A field mutated by a drag and
 * <em>also</em> written once a frame by an animation has two writers and no rule about which wins, so an
 * auto-orbit fights a hand and the loser is whichever ran second. The framework's answer is that a value with a
 * future is a {@link Cell}: it is written by curves, read once a frame, and a new curve replaces the old one
 * rather than racing it. So the six numbers move here, {@link March} keeps only what it will render next, and
 * <b>every camera motion in the application — a drag, a throw, a preset, the spin — is a curve on one cell.</b>
 *
 * <p>That is also what makes each of them interruptible for free. {@code Animator.retarget} reads the cell's
 * current value and drives a fresh curve from it, so a preset caught halfway by another preset continues from
 * where it actually is; there is nothing to cancel and no half-finished state to reconcile.
 *
 * <h2>One cell, not six</h2>
 *
 * <p>A {@code Cell<Double>} per number would need six retargets to move a camera, six curves to keep in step,
 * and would still let a preset arrive with its yaw eased and its distance snapped. A {@link View} is the whole
 * camera, {@link View#LERP} interpolates it as a unit, and a preset is one call.
 *
 * <h2>What this costs at rest: nothing</h2>
 *
 * <p>{@code KronoGui.frames()} is a <em>dynamic</em> domain, so a binding on it declares no deadline of its
 * own and the render-on-demand loop still parks. What holds the loop awake is a cell that is <em>driven</em> —
 * its {@code varyingUntil} is in the future — which is true exactly while something is animating and false the
 * moment it settles. The spin is an infinite curve, so it keeps the window at frame rate for as long as it
 * runs, and that is the honest answer rather than an accident.
 *
 * <h2>Every entry point goes through the timeline</h2>
 *
 * <p>{@code Cell.set} and {@code Cell.drive} both call {@code requireOnTimeline}, and every caller here is a
 * handler — a drag on the GUI thread, a panel button on a worker. So each one is wrapped in
 * {@code kron.onTimeline}, which runs it at the next moment the kernel observes. In this application that is
 * the {@code KronoGui.tick()} of the very frame the input arrived on, because the run loop pumps input first, so
 * the indirection costs no latency at all.
 */
final class Motion implements Panels.Viewpoint {

    /**
     * All this needs of the renderer: somewhere to put a camera, and one question only the renderer can answer.
     *
     * <p>Narrow on purpose, and the reason is a test rather than taste. {@link March} cannot exist without a
     * Vulkan device, so a {@code Motion} that named it directly could only be tested on a machine with a GPU —
     * and every claim in this file (the spin's rate, the short way round to a preset, the speed a throw leaves
     * the hand at, the elevation clamp) is arithmetic that has nothing to do with one. Two methods behind an
     * interface is what moves the whole of it into §13's first tier.
     */
    interface Eye {

        /** Take a camera. Called once a frame, with the same values as last time whenever nothing moved. */
        void view(double yaw, double pitch, double distance, double tx, double ty, double tz);

        /** The world-space shift that dragging the picture by this many screen pixels amounts to. */
        double[] panDelta(double screenDx, double screenDy, double distance);
    }

    /**
     * How fast auto-orbit turns: a full revolution every ten seconds.
     *
     * <p>Expressed per second rather than per frame, which the first cut of this got wrong — {@code 0.6°} a
     * frame is a different speed on a 144 Hz monitor than on a 60 Hz one, and the curve is a function of time
     * precisely so it does not have to care.
     */
    private static final double SPIN_PER_SECOND = Math.toRadians(36);

    /** A preset's travel. Long enough to read as a move rather than a cut, short enough not to be waited on. */
    private static final Dur PRESET = Dur.ms(220);

    /**
     * How long a thrown orbit takes to come to rest.
     *
     * <p>Deceleration is what reads as weight: {@code OUT_CUBIC} over most of a second is a plot that was
     * pushed and is slowing down, where a linear stop is a plot that was switched off.
     */
    private static final Dur COAST = Dur.ms(900);

    /**
     * Below this, a release is a stop rather than a throw, in radians a second.
     *
     * <p>Without it every drag ends with a small drift, which is worse than no inertia at all: the picture will
     * not stay where it was put.
     */
    private static final double FLING_FLOOR = 0.15;

    /** The application's elevation clamp. {@link March}'s own is wider, and is a backstop rather than this. */
    private static final double PITCH_LIMIT = Math.toRadians(75);

    /** How far the eye orbits its target on a fresh view. The world box is about two units across. */
    private static final double DISTANCE = 7.0;

    /**
     * How close and how far a wheel may take the camera.
     *
     * <p>The near limit is the box's own half-diagonal and a little: inside it the eye is <em>within</em> the
     * plot, which is not a zoomed-in plot but a different and confusing picture. The far limit is where the
     * geometry stops being legible, and both exist so a wheel cannot lose the plot — recovering from that costs
     * the user a trip to Reset, which is a bad outcome for a scroll.
     */
    private static final double NEAR = 3.0;
    private static final double FAR = 24.0;

    /** Where a fresh view stands, and where Reset goes back to. */
    private static final View HOME =
            new View(Math.toRadians(38), Math.toRadians(26), DISTANCE, 0, 0, 0);

    /**
     * The whole camera: where it is pointed, how far out it stands, and what it stands off.
     *
     * @param yaw      azimuth, radians, unbounded — it may wind past a turn, and the spin relies on that
     * @param pitch    elevation, radians, clamped to ±75°
     * @param distance how far the eye is from the look-at point
     * @param tx       what the camera looks at, moved by a pan
     * @param ty       likewise
     * @param tz       likewise
     */
    record View(double yaw, double pitch, double distance, double tx, double ty, double tz) {

        /**
         * The camera between two cameras.
         *
         * <p>Angles and the look-at point interpolate straight, because they are positions. <b>Distance does
         * not</b>: it interpolates geometrically, the same multiplicative step a wheel notch takes, because a
         * straight lerp from 20 to 3 spends most of its duration far away and then rushes the last half of the
         * approach. Linear in a quantity the user experiences as a ratio is the standard way a zoom animation
         * comes out feeling wrong at one end.
         */
        static final Interp<View> LERP = (a, b, t) -> new View(
                a.yaw + (b.yaw - a.yaw) * t,
                a.pitch + (b.pitch - a.pitch) * t,
                a.distance * Math.pow(b.distance / a.distance, t),
                a.tx + (b.tx - a.tx) * t,
                a.ty + (b.ty - a.ty) * t,
                a.tz + (b.tz - a.tz) * t);

        View turnedBy(double dYaw, double dPitch) {
            return new View(yaw + dYaw, clampPitch(pitch + dPitch), distance, tx, ty, tz);
        }

        View movedBy(double[] d) {
            return new View(yaw, pitch, distance, tx + d[0], ty + d[1], tz + d[2]);
        }

        View at(double newDistance) {
            return new View(yaw, pitch, newDistance, tx, ty, tz);
        }

        /** How far out the camera stands, as the readout says it: 1.0 is a fresh view. */
        double zoom() {
            return DISTANCE / distance;
        }
    }

    private final Kron kron;
    private final Animator animator;
    private final Eye eye;
    private final Cell<View> view;

    /** The last view handed to the {@link Eye}, for readers that are not on the timeline. */
    private volatile View landed = HOME;

    Motion(Kron kron, Rate frames, Animator animator, Eye eye) {
        this.kron = kron;
        this.animator = animator;
        this.eye = eye;
        // Creating a cell and binding it are setup rather than timeline work -- a cell is a constructor, and
        // Rate.each appends to a list -- so this needs no baton, and KronoGui's own constructor does exactly
        // this to the same Rate. Only *writing* to the cell crosses, which is every other method here.
        this.view = kron.bound(frames, "camera", HOME, this::land);
        // The binding lands the camera once a frame from here on. This is the one before that: March holds no
        // camera of its own, so between its constructor and the first tick it has none, and a first frame that
        // beat the first tick would march from nothing.
        land(HOME);
    }

    /**
     * Where the cell lands: the six floats the next march will use.
     *
     * <p>Runs once a frame whether or not anything changed, so {@link March#view} compares before it dirties.
     * Without that comparison a bound camera would mark every frame dirty and the window would march forever,
     * which is the exact property the split in {@link March} exists to protect.
     */
    private void land(View v) {
        landed = v;
        eye.view(v.yaw(), v.pitch(), v.distance(), v.tx(), v.ty(), v.tz());
    }

    /**
     * What the readout shows: the camera as it was last landed.
     *
     * <p>The <em>landing</em> rather than the cell, and for two reasons. Reading a cell is a graph operation
     * and this is called from the frame loop, off the timeline; and a readout should quote the camera the
     * picture was actually drawn with, which is this one, not the one the curve has reached since.
     */
    View now() {
        return landed;
    }

    // ------------------------------------------------------------------ gestures

    /** Turn the camera by a hand's worth of drag. */
    void turn(double dYaw, double dPitch) {
        kron.onTimeline(() -> view.set(view.get().turnedBy(dYaw, dPitch)));
    }

    /** Slide the camera across its own view. The world-space direction is {@link March}'s to work out. */
    void pan(double screenDx, double screenDy) {
        kron.onTimeline(() -> {
            View v = view.get();
            view.set(v.movedBy(eye.panDelta(screenDx, screenDy, v.distance())));
        });
    }

    /**
     * Move the eye along its own view direction.
     *
     * <p>Multiplicative rather than additive, so one notch is the same proportion of the way in at every
     * distance — which is what a wheel feels like it should do, and what an additive step conspicuously does
     * not once you are close.
     */
    void zoom(double notches) {
        kron.onTimeline(() -> {
            View v = view.get();
            view.set(v.at(Math.clamp(v.distance() * Math.pow(0.88, notches), NEAR, FAR)));
        });
    }

    /**
     * Let go of an orbit that was still moving.
     *
     * <p>The distance is derived from the speed rather than chosen: {@code OUT_CUBIC} leaves its start at three
     * times its average rate, so a coast of {@code v·COAST/3} leaves the hand at exactly the speed the hand had.
     * Anything else is a visible discontinuity at the moment of release — the one frame the user is watching
     * most closely, because it is the frame they caused.
     *
     * @param yawRate   radians a second of azimuth at the moment of release
     * @param pitchRate radians a second of elevation, likewise
     */
    void fling(double yawRate, double pitchRate) {
        if (Math.hypot(yawRate, pitchRate) < FLING_FLOOR) {
            return;
        }
        double seconds = COAST.nanos() / 1e9;
        kron.onTimeline(() -> {
            View to = view.get().turnedBy(yawRate * seconds / 3, pitchRate * seconds / 3);
            animator.retarget(view, to, COAST, Ease.OUT_CUBIC, View.LERP);
        });
    }

    // ------------------------------------------------------------------ presets and the spin

    /**
     * Travel to a preset — and put the pan and the zoom back, which is what makes {@code Top} mean the same
     * thing twice running rather than "the top of wherever you had wandered to".
     */
    @Override
    public void look(double yawDegrees, double pitchDegrees) {
        kron.onTimeline(() -> {
            View from = view.get();
            View to = new View(nearest(from.yaw(), Math.toRadians(yawDegrees)),
                    clampPitch(Math.toRadians(pitchDegrees)), DISTANCE, 0, 0, 0);
            animator.retarget(view, to, PRESET, Ease.OUT_CUBIC, View.LERP);
        });
    }

    @Override
    public void reset() {
        look(38, 26);
    }

    /**
     * Start or stop auto-orbit.
     *
     * <p>An infinite {@link Curve} rather than a shred, which is the whole of §11's "a {@code Signal}, not a
     * {@code Shred}": the spin is a pure function of elapsed time, so it can be evaluated ahead of now and off
     * the baton, and stopping it is not a cancellation but a write. Anchored at the current view, so switching
     * it on does not move the camera by a single pixel on the frame it starts.
     *
     * <p>Stopping is {@code set(get())} rather than {@code live()}: reading the curve at this moment and
     * holding that value is what leaves the plot where the eye last saw it, where dropping the curve alone
     * would fall back to whatever the cell held before the spin began.
     */
    void spinning(boolean on) {
        kron.onTimeline(() -> {
            View from = view.get();
            if (on) {
                view.drive(Curve.forever(elapsed ->
                        from.turnedBy(SPIN_PER_SECOND * (elapsed.nanos() / 1e9), 0)));
            } else {
                view.set(from);
            }
        });
    }

    /**
     * The nearest angle to {@code current} that means the same direction as {@code wanted}.
     *
     * <p>A preset is a compass bearing, and the yaw it is retargeted against has been winding freely — the spin
     * leaves it at thirty turns and a drag leaves it anywhere. Without this, {@code Front} from a spun camera
     * unwinds every one of those turns inside 220 ms, which reads as the plot exploding.
     */
    private static double nearest(double current, double wanted) {
        double turns = 2 * Math.PI;
        return wanted + turns * Math.round((current - wanted) / turns);
    }

    private static double clampPitch(double radians) {
        return Math.clamp(radians, -PITCH_LIMIT, PITCH_LIMIT);
    }
}
