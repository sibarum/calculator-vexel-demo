package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;

/**
 * What a cell of a surface looks like once its own geometry is taken into account: a <b>cavity</b> term from the
 * curvature there, and a <b>Fresnel</b> term from the angle it makes with the eye.
 *
 * <h2>Both of these are cheap here and expensive everywhere else</h2>
 * The two effects below are the ones a triangle renderer has the hardest time with, and in both cases the reason
 * is the same: a mesh does not know its own calculus. It has positions, and everything else is estimated from
 * the neighbours.
 *
 * <ul>
 *   <li><b>Fresnel</b> is {@code (1 − cos θ)⁵}, evaluated where {@code cos θ} is smallest — at silhouettes, which
 *       is exactly where a mesh's <em>interpolated</em> vertex normals are least trustworthy. That is why rim
 *       lighting on polygons bands, shimmers and traces the tessellation unless someone spends real effort on it.
 *       Here the normal is <b>analytic and exact per cell</b>, straight from {@code Expr.derivative} — the same
 *       machinery the landmarks are found with — so there is nothing interpolated to band. And the view direction
 *       is <b>one vector for the whole picture</b>, because the projection is orthographic, so the angle costs a
 *       dot product rather than a normalised per-fragment vector.
 *   <li><b>Curvature</b> on a mesh is an estimate over a vertex neighbourhood — noisy, dependent on how the thing
 *       happens to be tessellated, and expensive enough that it is usually baked offline into a curvature map.
 *       Here the second partials come from differentiating twice, exactly, for the cost of three more enclosures.
 * </ul>
 *
 * <h2>Mean curvature, not Gaussian</h2>
 * The obvious curvature to reach for is Gaussian, and it is the wrong one for a cavity term. Gaussian curvature
 * is zero on a cylinder — which is visibly curved and visibly does occlude — and negative on a saddle in both
 * of its directions at once, so it cannot tell a valley from a ridge. <b>Mean</b> curvature can: it is positive
 * where the surface cups toward the sky and negative where it humps away, which is precisely the concave/convex
 * distinction a cavity term is about.
 *
 * <p>(Gaussian curvature would be the interesting one to <em>read</em> rather than to shade with — it is negative
 * everywhere on {@code x²−y²} and positive everywhere on {@code 1÷(x²+y²)}, so it says what kind of surface this
 * is. That is a different feature, and colouring by it would fight the height ramp for the same channel.)
 *
 * <h2>What is reflected</h2>
 * A Fresnel term needs something to be reflecting, and there is no environment here and no way to upload one —
 * the only texture in the whole renderer is the glyph atlas. So the environment is <b>procedural</b>: the view
 * vector is reflected about the cell's normal and the result indexes a two-stop sky-to-ground gradient by its
 * vertical component. Five lines, no texture, and it is the real effect rather than a rim colour standing in for
 * one: a cell tilted toward the sky reflects the sky, and one tilted away reflects the ground.
 */
final class Sheen {

    /** The reflectance of a dielectric seen head-on. Glass, water, and anything that reads as either. */
    private static final double F0 = 0.04;

    /** How much of the Fresnel term actually shows. Below 1 so that a grazing cell tints rather than vanishes. */
    private static final double STRENGTH = 0.6;

    /**
     * Where the environment stops being the dark surround and starts being the light overhead, as a component
     * of the reflected direction's vertical.
     *
     * <p>This pair is the whole difference between a surface that reads as glass and one that reads as milk, and
     * getting it wrong the first time was instructive. A Fresnel term is largest at grazing angles; the first
     * version reflected a bright sky, and a bright sky seen at a grazing angle is the <em>horizon</em> — so
     * every steep cell, which at this pitch is most of them, picked up a pale mid-tone and the height ramp
     * disappeared under it. The picture was not wrong, it was a correct rendering of a glass saddle in a white
     * room. Putting the light overhead and leaving the surround dark is the studio a dark plot wants: a grazing
     * cell now reflects the dark and reads as an edge, and only a cell genuinely tilted skyward catches
     * anything.
     */
    private static final double HORIZON = -0.3;
    private static final double ZENITH = 0.9;

    /** How far a concavity may be darkened. */
    private static final double CAVITY = 0.55;

    /**
     * How far a ridge may be lifted — much less, because <b>occlusion only subtracts</b>. A fold is shadowed by
     * its own walls; a convex ridge is not shadowed, which is not the same as being lit. Making the two
     * symmetric was the second thing that went wrong here: near the throat of {@code 1÷(x²+y²)} the curvature
     * runs away, and a symmetric term multiplied the funnel walls by one and a half until they were white.
     */
    private static final double RIDGE = 0.15;

    /** What counts as a lot of curvature, before the {@code tanh} that keeps a pole from going pure black. */
    private static final double CURVE_GAIN = 3.0;

    private Sheen() {
    }

    /**
     * The partial derivatives of the surface at one cell, <b>in the volume's normalised space</b> — the same
     * {@code [-0.5, 0.5]} cube the camera projects, so that everything below is dimensionless and a cell shades
     * the same way at every zoom.
     *
     * @param gx  ∂z/∂x
     * @param gy  ∂z/∂y
     * @param gxx ∂²z/∂x²
     * @param gyy ∂²z/∂y²
     * @param gxy ∂²z/∂x∂y
     */
    record Slope(double gx, double gy, double gxx, double gyy, double gxy) {

        /** Whether every partial came back finite — a cell at a pole has no slope, and gets no shading. */
        boolean usable() {
            return Double.isFinite(gx) && Double.isFinite(gy)
                    && Double.isFinite(gxx) && Double.isFinite(gyy) && Double.isFinite(gxy);
        }

        /** The outward unit normal {@code (−gx, −gy, 1)}, which is where both terms below start. */
        double[] normal() {
            double length = Math.sqrt(gx * gx + gy * gy + 1);
            return new double[]{-gx / length, -gy / length, 1 / length};
        }

        /**
         * Mean curvature — positive where the surface cups upward, negative where it humps. The standard form
         * for a height field, and the reason this record carries second derivatives at all.
         */
        double meanCurvature() {
            double slope = 1 + gx * gx + gy * gy;
            return ((1 + gx * gx) * gyy - 2 * gx * gy * gxy + (1 + gy * gy) * gxx)
                    / (2 * Math.pow(slope, 1.5));
        }
    }

    /**
     * {@code base}, shaded for the cell it belongs to: darkened in a cavity, lifted on a ridge, and tinted
     * toward whatever it reflects at the angle it is being seen from.
     *
     * @param base  the cell's colour before optics — here, its height on the ramp
     * @param slope the surface's derivatives there, in normalised space
     * @param view  the unit direction the whole picture is seen along, from {@code Camera.viewDirection}
     */
    static Color shade(Color base, Slope slope, double[] view) {
        if (slope == null || !slope.usable()) {
            return base;                       // no derivatives here: the height ramp on its own, unembellished
        }
        double[] normal = slope.normal();

        // Cavity. tanh rather than a clamp so that a very sharp fold eases into the limit instead of hitting a
        // flat black wall -- near a pole the curvature runs away, and the shading should not run away with it.
        // The two directions are deliberately unequal: see RIDGE.
        double cupped = Math.tanh(slope.meanCurvature() * CURVE_GAIN);
        Color shaded = scale(base, 1 - CAVITY * Math.max(0, cupped) + RIDGE * Math.max(0, -cupped));

        // Fresnel. cos θ between the surface and the eye; abs because a cell turned away from the camera is
        // still being seen, just from behind, and a signed term would make the far side of a fold go black.
        double facing = Math.abs(dot(normal, view));
        double fresnel = F0 + (1 - F0) * Math.pow(1 - facing, 5);

        // Added, not mixed. The energy-conserving form -- base·(1−F) + env·F -- is the physically correct one
        // and it is the wrong one to look at here, because it takes the height ramp away in exactly the places
        // the reflection is strongest. A dielectric's reflection sits ON the transmitted colour rather than
        // instead of it, so adding keeps the ramp legible underneath and the rim reads as light rather than as
        // a loss of information. The clamp in add() is the only thing standing in for the energy the honest
        // form would have removed.
        return add(shaded, environment(reflect(view, normal)), fresnel * STRENGTH);
    }

    /** {@code v} mirrored about {@code n} — where a ray would have gone, without a ray to send. */
    private static double[] reflect(double[] v, double[] n) {
        double twice = 2 * dot(v, n);
        return new double[]{v[0] - twice * n[0], v[1] - twice * n[1], v[2] - twice * n[2]};
    }

    /**
     * The procedural environment: a dark surround with a broad light above it, mixed by how far up the reflected
     * direction points. A real environment map would be a texture and there is no path for one — the only image
     * this renderer can sample is the glyph atlas — so this is two colours and a {@code smoothstep}, which at
     * these angles is not a difference a reader could name.
     */
    private static Color environment(double[] direction) {
        return mix(Palette.HORIZON_LIGHT, Palette.ZENITH_LIGHT, smoothstep(HORIZON, ZENITH, direction[2]));
    }

    /** {@code onto} with {@code light} laid over it at {@code amount}, clamped where it would blow out. */
    private static Color add(Color onto, Color light, double amount) {
        float f = (float) Math.max(0, Math.min(1, amount));
        return Color.rgba(Math.min(1, onto.r() + light.r() * f),
                          Math.min(1, onto.g() + light.g() * f),
                          Math.min(1, onto.b() + light.b() * f),
                          onto.a());
    }

    private static double smoothstep(double from, double to, double at) {
        double t = Math.max(0, Math.min(1, (at - from) / (to - from)));
        return t * t * (3 - 2 * t);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static Color scale(Color c, double by) {
        float f = (float) Math.max(0, by);
        return Color.rgba(Math.min(1, c.r() * f), Math.min(1, c.g() * f), Math.min(1, c.b() * f), c.a());
    }

    static Color mix(Color from, Color to, double t) {
        float f = (float) Math.max(0, Math.min(1, t));
        return Color.rgba(from.r() + (to.r() - from.r()) * f,
                          from.g() + (to.g() - from.g()) * f,
                          from.b() + (to.b() - from.b()) * f,
                          from.a() + (to.a() - from.a()) * f);
    }
}
