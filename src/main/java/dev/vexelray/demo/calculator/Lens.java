package dev.vexelray.demo.calculator;

/**
 * Where a world point lands on the marched image — the inverse of the ray the fragment shader casts.
 *
 * <h2>This is a second implementation of one fact, and that is the problem with it</h2>
 *
 * <p>{@code SdfComposer.cameraBytes} hands the shader six floats and {@code SdfComposer.primaryRay} turns a
 * pixel into a ray from them. Nothing anywhere exposes the <b>forward</b> direction, so anything drawn
 * <em>over</em> the marched image — an axis label, the probe's anchor, a crop handle — has to re-derive it. The
 * arithmetic is easy. The hazard is that there are now two statements of one convention, one in Java and one
 * generated into SPIR-V, and nothing holds them together: a mismatch does not fail, it makes labels drift off
 * their axes as the camera turns, which reads as a rendering glitch and is miserable to chase.
 *
 * <p>Two things bound that. This class is pure and pinned by {@code LensTest} against the shader's own
 * expression, transcribed; and the transcription is written out below rather than paraphrased, so the next
 * person can diff it against the source rather than trust it. See {@code docs/framework-notes.md} FN-12 for
 * what the framework could do instead.
 *
 * <h2>The forward transform, as the shader states it</h2>
 *
 * <pre>
 * sx = (2u - 1) * aspect          sy = 1 - 2v
 * py = sy·cosPitch - focal·sinPitch
 * pz = sy·sinPitch + focal·cosPitch
 * rx = sx·cosYaw + pz·sinYaw      rz = pz·cosYaw - sx·sinYaw
 * direction = normalize(rx, py, rz)
 * </pre>
 *
 * <p>Both of those pairs are plane rotations, which is what makes the inverse exact rather than a solve:
 * {@code (sx, pz)} is {@code (rx, rz)} rotated by yaw, and {@code (sy, focal)} is {@code (py, pz)} rotated by
 * pitch. Undo them in the other order and the only thing left is that a ray is defined up to scale — so the
 * recovered {@code focal} component says what that scale was, and dividing by it is the perspective divide.
 *
 * @param yaw    radians, as handed to the shader
 * @param pitch  radians
 * @param aspect the marched target's width over its height — <b>not</b> the node's, which may differ
 * @param focal  the scene's focal length; a compile-time property of the pipeline
 */
record Lens(double eyeX, double eyeY, double eyeZ, double yaw, double pitch, double aspect, double focal) {

    /** Where a world point lands, or {@code null} if it is behind the image plane and has no place on it. */
    Point project(double x, double y, double z) {
        double rx = x - eyeX;
        double ry = y - eyeY;
        double rz = z - eyeZ;

        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double sx = rx * cosYaw - rz * sinYaw;
        double pz = rx * sinYaw + rz * cosYaw;

        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        double sy = ry * cosPitch + pz * sinPitch;
        double depth = pz * cosPitch - ry * sinPitch;

        // Behind the eye, or exactly on the plane through it: there is no image of this point. Answering with a
        // coordinate anyway is how a label ends up mirrored on the far side of the screen when the camera swings
        // past it, which looks like a projection bug in the general case rather than the special case it is.
        if (depth <= 1e-9) {
            return null;
        }
        double scale = focal / depth;
        return new Point((sx * scale / aspect + 1) * 0.5, (1 - sy * scale) * 0.5, depth);
    }

    /**
     * A projected point.
     *
     * @param u     across the image, {@code 0} at the left edge and {@code 1} at the right
     * @param v     down the image, {@code 0} at the top
     * @param depth distance along the view direction — what a painter's-order sort would use, and what says
     *              whether one label is in front of another
     */
    record Point(double u, double v, double depth) {

        /** Whether this landed inside the image at all. Off-image points are still meaningful, just not drawn. */
        boolean onScreen() {
            return u >= 0 && u <= 1 && v >= 0 && v <= 1;
        }
    }
}
