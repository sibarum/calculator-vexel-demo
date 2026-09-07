package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sibarum.kronometer.Dur;
import sibarum.kronometer.Kron;
import sibarum.kronometer.Rate;
import sibarum.kronometer.anim.Animator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every claim §11 makes about the camera, on a clock that is stepped by hand.
 *
 * <p>None of this needs a GPU, a window or a frame, which is the whole reason {@link Motion.Eye} exists: the
 * spin's rate, the way round to a preset, the speed a throw leaves the hand at and the elevation clamp are
 * arithmetic, and arithmetic that can only be checked by looking at a running window is arithmetic nobody
 * checks. {@code Kron.driven()} takes its elapsed time from the caller, so "a second later" here is a second
 * exactly rather than approximately.
 */
class MotionTest {

    private static final double DEGREE = Math.toRadians(1);

    /** A recording {@link Motion.Eye}: what the renderer would have been told, in the order it was told. */
    private static final class Landings implements Motion.Eye {

        private final List<Motion.View> seen = new ArrayList<>();

        @Override
        public void view(double yaw, double pitch, double distance, double tx, double ty, double tz) {
            seen.add(new Motion.View(yaw, pitch, distance, tx, ty, tz));
        }

        @Override
        public double[] panDelta(double screenDx, double screenDy, double distance) {
            // Screen right is +x and screen up is +y, which is the identity a pan test wants: it is checking
            // that the delta is applied to the look-at point, not re-deriving the shader's basis.
            return new double[]{screenDx, screenDy, 0};
        }

        Motion.View last() {
            return seen.get(seen.size() - 1);
        }
    }

    /** A driven clock, a per-frame domain and a camera on it — the three lines every test here starts with. */
    private record Rig(Kron kron, Motion motion, Landings eye) implements AutoCloseable {

        static Rig open() {
            Kron kron = Kron.driven();
            Rate frames = kron.dynamic("frames");
            Landings eye = new Landings();
            return new Rig(kron, new Motion(kron, frames, new Animator(kron), eye), eye);
        }

        /** Step to an absolute moment, in milliseconds since the origin. */
        void at(long millis) {
            kron.tick(Dur.ms(millis).nanos());
        }

        /**
         * Run the clock to {@code millis} the way a frame loop would, in sixteen-millisecond steps.
         *
         * <p>Not the same as one long {@link #at}, and the difference is a real rule rather than a test
         * convenience: {@code Driven.maxAdvance} bounds a single tick to one second and <em>forgives</em> the
         * rest as {@code Settlement.SKIP}, because a tick that carried a whole minute of logical time would
         * stall the render loop it is supposed to be pacing. So a test that wants thirty seconds of animation
         * has to ask for thirty seconds of frames.
         */
        void through(long millis) {
            for (long t = 16; t < millis; t += 16) {
                at(t);
            }
            at(millis);
        }

        @Override
        public void close() {
            kron.close();
        }
    }

    // ---------------------------------------------------------------- the spin

    @Test
    @DisplayName("auto-orbit turns at 36 degrees a second, and turns nothing but the azimuth")
    void theSpinIsAFunctionOfTime() {
        try (Rig rig = Rig.open()) {
            rig.motion.spinning(true);
            rig.at(0);
            Motion.View start = rig.eye.last();

            rig.at(1_000);
            Motion.View after = rig.eye.last();

            assertEquals(36, (after.yaw() - start.yaw()) / DEGREE, 0.001, "a second is 36 degrees");
            assertEquals(start.pitch(), after.pitch(), 1e-12, "the spin changed the elevation");
            assertEquals(start.distance(), after.distance(), 1e-12, "the spin changed the zoom");
        }
    }

    @Test
    @DisplayName("the frame rate is not the speed: half the frames cover the same ground")
    void theSpinDoesNotDependOnHowOftenItIsAsked() {
        try (Rig coarse = Rig.open(); Rig fine = Rig.open()) {
            coarse.motion.spinning(true);
            fine.motion.spinning(true);
            coarse.at(0);
            fine.at(0);
            double from = coarse.eye.last().yaw();

            for (long t = 100; t <= 1_000; t += 100) {
                coarse.at(t);
            }
            for (long t = 16; t <= 1_000; t += 16) {
                fine.at(t);
            }
            fine.at(1_000);

            assertEquals(36, (coarse.eye.last().yaw() - from) / DEGREE, 0.001);
            assertEquals(36, (fine.eye.last().yaw() - from) / DEGREE, 0.001);
        }
    }

    @Test
    @DisplayName("stopping the spin leaves the plot exactly where the eye last saw it")
    void stoppingHoldsTheCurrentAngle() {
        try (Rig rig = Rig.open()) {
            rig.motion.spinning(true);
            rig.at(0);
            rig.at(500);
            double stopped = rig.eye.last().yaw();

            rig.motion.spinning(false);
            rig.at(600);
            rig.at(5_000);

            assertEquals(18, (stopped - Math.toRadians(38)) / DEGREE, 0.001, "half a second is 18 degrees");
            assertEquals(stopped, rig.eye.last().yaw(), 1e-12,
                    "the camera fell back to where the spin started instead of holding");
        }
    }

    // ---------------------------------------------------------------- presets

    @Test
    @DisplayName("a preset takes the short way round, however far the spin has wound the azimuth")
    void aPresetUnwindsNothing() {
        try (Rig rig = Rig.open()) {
            rig.motion.spinning(true);
            rig.at(0);
            rig.through(30_000);                   // three full turns and then some
            double wound = rig.eye.last().yaw();
            assertTrue(wound > Math.toRadians(1_000), "the spin did not wind far enough to make the point");

            rig.motion.spinning(false);
            rig.motion.look(0, 0);                 // Front
            rig.at(30_016);
            rig.at(30_400);                        // past the 220ms travel

            double arrived = rig.eye.last().yaw();
            assertEquals(0, Math.IEEEremainder(arrived, 2 * Math.PI) / DEGREE, 0.001, "not facing front");
            assertTrue(Math.abs(arrived - wound) <= Math.PI + 1e-9,
                    "the preset travelled " + (arrived - wound) / DEGREE + " degrees to get somewhere 180 away");
        }
    }

    @Test
    @DisplayName("a preset puts the pan and the zoom back, so Top means Top twice running")
    void aPresetIsAWholeCamera() {
        try (Rig rig = Rig.open()) {
            rig.motion.pan(60, -20);
            rig.motion.zoom(4);
            rig.at(0);
            assertNotEquals(0.0, rig.eye.last().tx(), "the pan did not take");
            assertNotEquals(7.0, rig.eye.last().distance(), "the zoom did not take");

            rig.motion.look(0, 75);                // Top
            rig.at(100);
            rig.at(400);

            Motion.View home = rig.eye.last();
            assertEquals(75, home.pitch() / DEGREE, 0.001);
            assertEquals(0, home.tx(), 1e-9, "the pan survived a preset");
            assertEquals(0, home.ty(), 1e-9);
            assertEquals(0, home.tz(), 1e-9);
            assertEquals(7.0, home.distance(), 1e-9, "the zoom survived a preset");
        }
    }

    @Test
    @DisplayName("a preset caught halfway continues from where it actually is")
    void aPresetIsInterruptible() {
        try (Rig rig = Rig.open()) {
            rig.motion.look(0, 0);
            rig.at(0);
            rig.at(110);                            // halfway to Front
            double halfway = rig.eye.last().yaw();
            assertTrue(halfway < Math.toRadians(38) && halfway > 0, "the first preset is not underway");

            rig.motion.look(90, 0);                 // Side, from halfway
            rig.at(120);
            double resumed = rig.eye.last().yaw();

            // Ten milliseconds into a 220ms OUT_CUBIC is already 13% of the way, so "continues from where it
            // is" is not "has barely moved". What separates the two cases is where it set off from: continuing
            // leaves 4.7° heading for 90 and arrives near 16°, where a snap back to the first preset's start
            // leaves 38° and arrives near 45°.
            assertTrue(resumed > halfway, "the second preset is not heading for Side at all");
            assertTrue(resumed < Math.toRadians(25),
                    "the second preset snapped back to the first one's start instead of continuing: "
                            + resumed / DEGREE + "°");
            rig.at(400);
            assertEquals(90, rig.eye.last().yaw() / DEGREE, 0.001, "it did not arrive at Side");
        }
    }

    // ---------------------------------------------------------------- the throw

    @Test
    @DisplayName("a thrown orbit leaves the hand at exactly the speed the hand had")
    void aThrowIsContinuousAtTheMomentOfRelease() {
        try (Rig rig = Rig.open()) {
            double released = 2.0;                  // radians a second
            rig.motion.fling(released, 0);
            rig.at(0);
            double from = rig.eye.last().yaw();

            rig.at(10);
            double rate = (rig.eye.last().yaw() - from) / 0.010;

            assertEquals(released, rate, released * 0.03,
                    "the plot changed speed on the frame the button came up");
        }
    }

    @Test
    @DisplayName("a thrown orbit decelerates into rest and stays there")
    void aThrowSettles() {
        try (Rig rig = Rig.open()) {
            rig.motion.fling(2.0, 0);
            rig.at(0);
            double from = rig.eye.last().yaw();

            rig.at(450);
            double half = rig.eye.last().yaw();
            rig.at(900);
            double rest = rig.eye.last().yaw();
            rig.at(4_000);

            assertTrue(half - from > (rest - from) * 0.5,
                    "the throw is not front-loaded, so it is not decelerating");
            assertEquals(0.6, rest - from, 1e-6, "a 2 rad/s throw over 900ms should coast 0.6 rad");
            assertEquals(rest, rig.eye.last().yaw(), 1e-12, "the coast did not stop");
        }
    }

    @Test
    @DisplayName("a release slower than the floor is a stop, not a drift")
    void aSlowReleaseDoesNotDrift() {
        try (Rig rig = Rig.open()) {
            rig.at(0);
            double before = rig.eye.last().yaw();

            rig.motion.fling(0.1, 0.05);
            rig.at(100);
            rig.at(1_000);

            assertEquals(before, rig.eye.last().yaw(), 1e-12, "the picture would not stay where it was put");
        }
    }

    // ---------------------------------------------------------------- the limits

    @Test
    @DisplayName("elevation clamps at 75 degrees however hard it is dragged")
    void elevationIsClamped() {
        try (Rig rig = Rig.open()) {
            for (int i = 0; i < 50; i++) {
                rig.motion.turn(0, Math.toRadians(10));
            }
            rig.at(0);
            assertEquals(75, rig.eye.last().pitch() / DEGREE, 1e-9);

            for (int i = 0; i < 100; i++) {
                rig.motion.turn(0, Math.toRadians(-10));
            }
            rig.at(16);
            assertEquals(-75, rig.eye.last().pitch() / DEGREE, 1e-9);
        }
    }

    @Test
    @DisplayName("the wheel cannot lose the plot in either direction")
    void zoomIsClamped() {
        try (Rig rig = Rig.open()) {
            for (int i = 0; i < 100; i++) {
                rig.motion.zoom(1);
            }
            rig.at(0);
            assertEquals(3.0, rig.eye.last().distance(), 1e-9, "the near limit");

            for (int i = 0; i < 200; i++) {
                rig.motion.zoom(-1);
            }
            rig.at(16);
            assertEquals(24.0, rig.eye.last().distance(), 1e-9, "the far limit");
        }
    }

    @Test
    @DisplayName("distance interpolates as a ratio, because a zoom is one")
    void distanceIsGeometric() {
        Motion.View near = new Motion.View(0, 0, 3, 0, 0, 0);
        Motion.View far = new Motion.View(0, 0, 12, 0, 0, 0);

        // Halfway between 3 and 12 is 6, not 7.5: two notches out and two notches back is where you started.
        assertEquals(6, Motion.View.LERP.between(near, far, 0.5f).distance(), 1e-9);
        assertEquals(3, Motion.View.LERP.between(near, far, 0f).distance(), 1e-9);
        assertEquals(12, Motion.View.LERP.between(near, far, 1f).distance(), 1e-9);
    }

    // ---------------------------------------------------------------- the contract with March

    @Test
    @DisplayName("the camera lands every frame whether or not it moved -- the comparison is March's")
    void theEyeIsToldEveryFrame() {
        try (Rig rig = Rig.open()) {
            rig.at(0);
            rig.at(16);
            rig.at(32);

            // One landing per tick, plus the one the constructor does eagerly -- March holds no camera of its
            // own, so a first frame that beat the first tick would otherwise march from nothing.
            assertEquals(4, rig.eye.seen.size(), "a bound cell lands once a step, and this is the contract "
                    + "March.view's own comparison exists to absorb");
            assertEquals(rig.eye.seen.get(0), rig.eye.last(), "a still camera moved");
        }
    }
}
