package dev.vexelray.demo.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Lens} against the shader it has to agree with.
 *
 * <h2>Why the test is a round trip and not a table of expected numbers</h2>
 *
 * <p>A table of hand-computed values would pin {@code Lens} to <em>itself</em> — it would keep the method
 * stable and say nothing at all about whether it matches the thing drawing the picture. The risk here is not
 * that the arithmetic is wrong today; it is that {@code SdfComposer.primaryRay} changes and nothing notices.
 *
 * <p>So {@link #forward} below is the shader's expression <b>transcribed</b>, and every test casts a ray
 * through it, walks along that ray, and asks {@code Lens} where the point it reached should appear. Recovering
 * the pixel it started from is the property that matters, and it is checkable at any camera and any point.
 *
 * <p>That is still two transcriptions rather than one source, which is exactly the complaint in
 * framework-notes FN-12 — but it fails loudly rather than drifting silently, and a diff of {@link #forward}
 * against {@code SdfComposer.primaryRay} is a five-line review rather than an investigation.
 */
class LensTest {

    private static final double FOCAL = 2.5;
    private static final double ASPECT = 640.0 / 400.0;
    private static final double DISTANCE = 7.0;

    /** The camera {@link March} builds: an eye orbiting the origin, looking back at it. */
    private static Lens orbiting(double yawDegrees, double pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        return new Lens(
                -DISTANCE * Math.cos(pitch) * Math.sin(yaw),
                DISTANCE * Math.sin(pitch),
                -DISTANCE * Math.cos(pitch) * Math.cos(yaw),
                yaw, pitch, ASPECT, FOCAL);
    }

    /**
     * {@code SdfComposer.primaryRay}, transcribed. The ray direction for a pixel, unnormalised — normalising is
     * a scale and the projection is scale-invariant, so leaving it out tests the same thing with less arithmetic
     * in the way.
     */
    private static double[] forward(Lens lens, double u, double v) {
        double sx = (2 * u - 1) * lens.aspect();
        double sy = 1 - 2 * v;
        double cosPitch = Math.cos(lens.pitch());
        double sinPitch = Math.sin(lens.pitch());
        double py = sy * cosPitch - lens.focal() * sinPitch;
        double pz = sy * sinPitch + lens.focal() * cosPitch;
        double cosYaw = Math.cos(lens.yaw());
        double sinYaw = Math.sin(lens.yaw());
        return new double[]{
                sx * cosYaw + pz * sinYaw,
                py,
                pz * cosYaw - sx * sinYaw};
    }

    private static void assertRoundTrips(Lens lens, double u, double v, double t) {
        double[] d = forward(lens, u, v);
        Lens.Point back = lens.project(lens.eyeX() + d[0] * t, lens.eyeY() + d[1] * t, lens.eyeZ() + d[2] * t);
        assertNotNull(back, "a point in front of the camera must have an image");
        assertEquals(u, back.u(), 1e-9, "u did not round trip");
        assertEquals(v, back.v(), 1e-9, "v did not round trip");
    }

    @Test
    @DisplayName("every pixel round trips, at the camera the plot opens on")
    void roundTripsAtRest() {
        Lens lens = orbiting(38, 26);
        for (double u = 0; u <= 1.0001; u += 0.25) {
            for (double v = 0; v <= 1.0001; v += 0.25) {
                assertRoundTrips(lens, u, v, 3.0);
            }
        }
    }

    @Test
    @DisplayName("and at every camera an orbit can reach, including the clamped extremes")
    void roundTripsThroughAnOrbit() {
        for (double yaw = -180; yaw <= 180; yaw += 37) {
            for (double pitch : new double[]{-75, -40, 0, 26, 75}) {
                Lens lens = orbiting(yaw, pitch);
                assertRoundTrips(lens, 0.13, 0.77, 5.0);
                assertRoundTrips(lens, 0.91, 0.08, 1.5);
            }
        }
    }

    @Test
    @DisplayName("distance along the ray does not move the pixel — that is what makes it a projection")
    void depthDoesNotMoveThePixel() {
        Lens lens = orbiting(38, 26);
        for (double t : new double[]{0.5, 2, 7, 40}) {
            assertRoundTrips(lens, 0.3, 0.6, t);
        }
    }

    @Test
    @DisplayName("the eye is aimed at the origin, so the origin is the centre of the image")
    void theOriginIsCentred() {
        for (double yaw = -180; yaw <= 180; yaw += 45) {
            Lens.Point centre = orbiting(yaw, 26).project(0, 0, 0);
            assertNotNull(centre);
            assertEquals(0.5, centre.u(), 1e-9, "the origin should be centred horizontally at yaw " + yaw);
            assertEquals(0.5, centre.v(), 1e-9, "the origin should be centred vertically at yaw " + yaw);
            assertEquals(DISTANCE, centre.depth(), 1e-9, "and exactly the orbit radius away");
        }
    }

    @Test
    @DisplayName("a point behind the image plane has no image, rather than a mirrored one")
    void behindTheCameraIsRefused() {
        Lens lens = orbiting(0, 0);
        // The eye is at z = -7 looking towards +z, so anything behind it is further negative.
        assertNull(lens.project(0, 0, -20), "a point behind the eye must not be given a pixel");
        assertNull(lens.project(lens.eyeX(), lens.eyeY(), lens.eyeZ()), "nor the eye itself");
        assertNotNull(lens.project(0, 0, 0), "but the point it is looking at must have one");
    }

    /**
     * The pan directions, checked against the projection rather than against their own derivation.
     *
     * <p>Moving the eye along {@code screenRight} must move the picture horizontally and <em>not</em> vertically,
     * at every camera. That is the property a pan needs and the one a sign error breaks — and it is checkable
     * without knowing how either vector was worked out, which is what makes this a test rather than a
     * restatement.
     */
    @Test
    @DisplayName("screen right and screen up move the picture along the axes they are named for")
    void panDirectionsAreWhatTheySay() {
        for (double yaw = -180; yaw <= 180; yaw += 41) {
            for (double pitch : new double[]{-75, -30, 0, 26, 75}) {
                Lens lens = orbiting(yaw, pitch);
                Lens.Point at = lens.project(0, 0, 0);
                assertNotNull(at);

                double[] right = lens.screenRight();
                Lens shifted = new Lens(lens.eyeX() + right[0] * 0.4, lens.eyeY() + right[1] * 0.4,
                        lens.eyeZ() + right[2] * 0.4, lens.yaw(), lens.pitch(), lens.aspect(), lens.focal());
                Lens.Point movedRight = shifted.project(0, 0, 0);
                assertNotNull(movedRight);
                assertTrue(movedRight.u() < at.u(),
                        "moving the eye right must move the picture left, at yaw " + yaw + " pitch " + pitch);
                assertEquals(at.v(), movedRight.v(), 1e-9,
                        "a horizontal pan moved the picture vertically at pitch " + pitch);

                double[] up = lens.screenUp();
                Lens lifted = new Lens(lens.eyeX() + up[0] * 0.4, lens.eyeY() + up[1] * 0.4,
                        lens.eyeZ() + up[2] * 0.4, lens.yaw(), lens.pitch(), lens.aspect(), lens.focal());
                Lens.Point movedUp = lifted.project(0, 0, 0);
                assertNotNull(movedUp);
                assertTrue(movedUp.v() > at.v(),
                        "lifting the eye must move the picture down, at yaw " + yaw + " pitch " + pitch);
                assertEquals(at.u(), movedUp.u(), 1e-9,
                        "a vertical pan moved the picture sideways at yaw " + yaw);
            }
        }
    }

    @Test
    @DisplayName("screen right is level at every pitch, which is what keeps a horizontal pan horizontal")
    void screenRightIsLevel() {
        for (double pitch : new double[]{-75, -30, 0, 26, 75}) {
            assertEquals(0, orbiting(38, pitch).screenRight()[1], 1e-12);
        }
    }

    /**
     * The orbit's sign, stated as something you can see rather than as an axis convention.
     *
     * <p>{@code Gestures} turns the camera by {@code +dx}, and whether that is right or backwards is not
     * decidable by looking at it: the eye orbits one way, the picture turns the other, and the two cancel.
     * What settles it is the <b>near face</b> — the part of the figure the user has a hand on — and the test
     * is that a positive yaw step carries it right, at every camera.
     *
     * <p>The point it follows is the one nearest the eye, which is the unit vector from the origin back
     * towards the camera. It starts dead centre, so the sign of its {@code u} after the step is the whole
     * answer and there is no tolerance to choose.
     */
    @Test
    @DisplayName("a positive yaw step carries the near face of the figure to the right")
    void turningRightTurnsThePictureRight() {
        for (double yaw = -180; yaw <= 180; yaw += 37) {
            for (double pitch : new double[]{-75, -30, 0, 26, 75}) {
                Lens before = orbiting(yaw, pitch);
                double toEye = 1 / DISTANCE;
                double nx = before.eyeX() * toEye;
                double ny = before.eyeY() * toEye;
                double nz = before.eyeZ() * toEye;

                Lens.Point centred = before.project(nx, ny, nz);
                assertNotNull(centred);
                assertEquals(0.5, centred.u(), 1e-9, "the nearest point starts on the axis of the view");

                // A hand's worth of drag: Gestures.ORBIT_PER_PX times a few dozen pixels.
                Lens.Point after = orbiting(yaw + 8, pitch).project(nx, ny, nz);
                assertNotNull(after, "a turn of eight degrees cannot put the near face behind the eye");
                assertTrue(after.u() > 0.5,
                        "yaw must turn the near face right, at yaw " + yaw + " pitch " + pitch
                                + " (it went to u=" + after.u() + ")");
            }
        }
    }

    @Test
    @DisplayName("the world box the plot is built into is inside the frame at rest")
    void thePlotBoxFitsTheFrame() {
        Lens lens = orbiting(38, 26);
        // If this fails the camera is too close and the plot is being cropped -- which is a framing bug that a
        // still capture shows only for the corner that happens to be clipped.
        for (double x : new double[]{-1.8, 1.8}) {
            for (double y : new double[]{-1, 1}) {
                for (double z : new double[]{-1, 1}) {
                    Lens.Point p = lens.project(x, y, z);
                    assertNotNull(p);
                    assertTrue(p.onScreen(),
                            () -> "corner (" + x + ", " + y + ", " + z + ") is off screen at " + p);
                }
            }
        }
    }
}
