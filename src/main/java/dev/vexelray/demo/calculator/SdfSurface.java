package dev.vexelray.demo.calculator;

import dev.supirvast.vastir.core.MathFn;
import dev.vexelray.gui.plot.Expr;
import dev.vexelray.gui.plot.Framing;
import dev.vexelray.gui.plot.Volume;
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.Shading;
import dev.vexelray.surface.Surface;

import java.math.BigDecimal;
import java.util.List;

/**
 * An expression, as a surface VexelRay can ray-march: {@code z = f(x, y)} written as the implicit
 * {@code y - h(x, z)} over world space, handed to {@code vexelray-surface} as a {@link Surface.Implicit}.
 *
 * <p>This is the bridge between two expression languages that have nothing to do with each other. The plot's
 * {@link Expr} is a small interval-arithmetic tree over {@code BigDecimal}; SupirVast's {@code core} expression
 * is shader IR over {@code f32}. Every node the calculator can produce has a counterpart — the four arithmetic
 * operations, a power, the three trigonometric functions, {@code exp} and {@code log} — and each of those is a
 * node {@code Gradient} knows how to differentiate, which is the property that actually matters: the compiler
 * normalises an implicit by its own symbolic gradient, and a node it cannot differentiate is a surface it
 * cannot march.
 *
 * <p><b>Two coordinate systems, and they disagree about which way is up.</b> The plot's volume is
 * {@code (x, y)} across the floor with {@code z} for height; the marched world has {@code y} up, because that
 * is what the ground plane and the default key light assume. So the plot's {@code y} axis becomes the world's
 * {@code z}, and the plot's {@code z} becomes the world's {@code y}. The mapping is also a rescaling: the
 * volume {@link Framing} chose could be any size at all — {@code 1÷(x²+y²)} is framed hundreds of units tall —
 * and a camera has to be somewhere specific. So the volume is mapped onto a fixed box around the origin and the
 * camera never moves. What changes between expressions is the surface, not the point of view.
 *
 * <p><b>What is drawn here is the picture, not the proof.</b> The surface plot's honest layer is the enclosure
 * over each cell, drawn as an outline; this is the other layer, and it stands further from the arithmetic than
 * the bilinear one it sits beside — a march is sampling, and the field it samples is {@code f} rescaled by its
 * own gradient, which {@code Normalize} documents as a local correction rather than a proof. Nothing here
 * claims otherwise, and nothing here is drawn inside an outline yet.
 */
record SdfSurface(Surface surface, Volume volume, Expr plotExpr, String xName, String yName, String refusal) {

    /** Half the width and depth of the world box the volume's floor is mapped onto. */
    private static final double HALF = 2.0;

    /** Half the height of that box. Flatter than it is wide, which is how a graph is usually drawn. */
    private static final double HALF_Z = 1.25;

    /** The largest integer exponent expanded into repeated multiplication rather than handed to {@code pow}. */
    private static final int MAX_UNROLL = 16;

    /**
     * How far past the box's own ceiling a height is allowed to reach before it is clamped.
     *
     * <p><b>Nothing infinite may enter the field.</b> A pole sends the height to something enormous, and an
     * enormous value is fine — it is outside the box and the intersection clips it away, which is the honest
     * picture of a pole. An <em>infinite</em> one is not fine: infinity minus infinity is NaN, and one NaN
     * anywhere poisons the field, then the gradient that normalises it, then the finite-difference normal that
     * shades it. So the height is clamped well outside anything visible, where the clamp changes no picture and
     * the arithmetic downstream stays finite. Honest up to machine precision, and no further.
     */
    private static final double HEIGHT_CLAMP = HALF_Z * 4;

    /**
     * The smallest base any fractional power is raised from — strictly positive, and that is the whole point.
     *
     * <p>Clamping the base at <em>zero</em> is not enough, and the reason is a good illustration of why a field
     * has to be finite in its derivative and not only in its value. {@code pow(u, ½)} at {@code u = 0} is a
     * perfectly good 0; its derivative is {@code ½·u^(-½)}, which is <b>infinite</b> there. The compiler
     * normalises an implicit by its own symbolic gradient, so an infinite derivative divides the field by
     * infinity and hands the marcher a distance of zero everywhere — which it reads as "already touching the
     * surface" and reports a hit for almost every ray. The speckle comes back, from the gradient rather than
     * from the value.
     *
     * <p>A strictly positive floor bounds the derivative at {@code floor^(e-1)} and costs nothing visible:
     * {@code (1e-4)^½} is a hundredth of a unit, far below a pixel at any framing this draws.
     */
    private static final double DOMAIN_FLOOR = 1e-4;

    boolean ok() {
        return surface != null;
    }

    /**
     * Lower a plottable expression to a marchable surface, or say why it cannot be.
     *
     * @param variables the expression's variables, in the order the plot uses them: one or two. With one, the
     *                  surface is a ridge — constant along the second axis — which is the honest picture of an
     *                  expression that does not mention it.
     */
    static SdfSurface of(Expr expr, List<String> variables) {
        return of(expr, variables, null);
    }

    /**
     * As {@link #of(Expr, List)}, over a volume chosen by the caller rather than by the framing pass.
     *
     * <p>This is what makes the marched view zoomable at all. A marched extent is not a viewport the picture is
     * drawn through — the volume is compiled <em>into</em> the field, since it is what maps the plot onto the
     * fixed world box — so widening the window means lowering a new field and building a new pipeline. Passing
     * the volume in is what lets the controls ask for that instead of being told the framing belongs to some
     * other view.
     *
     * @param over the volume to compile for, or null to run the framing pass
     */
    static SdfSurface of(Expr expr, List<String> variables, Volume over) {
        if (variables.isEmpty() || variables.size() > 2) {
            return refused(variables.size() + " variables; a surface takes one or two");
        }
        String xName = variables.get(0);
        String yName = variables.size() == 2 ? variables.get(1) : spare(xName);
        Volume volume = over != null ? over : Framing.automatic(expr, xName, yName);
        if (!(volume.zHeight() > 0)) {
            return refused("nothing to frame");
        }
        try {
            return new SdfSurface(clipped(implicit(expr, volume, xName, yName)),
                    volume, expr, xName, yName, null);
        } catch (Unlowerable e) {
            return refused(e.getMessage());
        }
    }

    /**
     * The graph, cut down to the range that was actually framed: {@code intersection(implicit, box)}.
     *
     * <p>Two problems, one modifier. A graph is a heightfield and a heightfield is <b>infinite</b> — nothing in
     * {@code y - h(x, z)} says where the domain stops, so without a bound the surface runs off in every
     * direction and every ray that would have missed instead marches to the far plane at full step budget.
     * Intersecting with the world box ends both: the picture shows the volume {@link Framing} chose and nothing
     * outside it, and a ray that leaves the box is cheaply done.
     *
     * <p>It also changes what is drawn, for the better. The implicit is negative <em>below</em> the graph, so
     * intersecting it with a solid box gives the solid under the graph rather than an infinitely thin sheet:
     * a block whose top face is the surface and whose sides are the edges of the framed range, which makes the
     * framing something you can see instead of something you have to be told.
     *
     * <p>Soundness is unchanged. {@link Surface.Intersection} lowers to a pointwise {@code max}, and the larger
     * of two fields that each grow no faster than distance also grows no faster than distance — so the result
     * is still 1-Lipschitz and still marchable. It is, as the module's own documentation says, conservative but
     * not exact near the edges: along the seam where the graph meets a wall of the box the field slightly
     * under-reports, which costs steps and never costs geometry.
     */
    private static Surface clipped(dev.supirvast.vastir.core.Expr implicit) {
        return Surface.intersection(
                new Surface.Implicit(implicit),
                new Surface.Box(0, 0, 0, HALF, HALF_Z, HALF));
    }

    /** An axis name for the variable the expression does not have, chosen not to collide with the one it does. */
    private static String spare(String taken) {
        return taken.equals("y") ? "t" : "y";
    }

    private static SdfSurface refused(String why) {
        return new SdfSurface(null, null, null, null, null, why);
    }

    /**
     * The whole shading chain for this surface at {@code style} — the one place it is assembled.
     *
     * <p>The undefined paint goes <b>outside</b> everything else, because it must not be lit. A style replaces
     * the albedo and the light shades it; a region where the expression has no value is not a surface being
     * lit, it is a place where there is no surface to light, and shading it would make it read as geometry.
     */
    Shading shading(MarchStyle style) {
        return Undefined.over(style.shading(volume), this);
    }

    /**
     * Where this expression has a real value, as a signed expression of a <em>world</em> position: non-negative
     * where defined, negative where not. Null when the expression is defined everywhere.
     *
     * <p>Built through the same {@link #lower} the geometry went through, from the same volume and the same
     * axis names, so the painted region and the clamped one are the same region by construction rather than by
     * two predicates being written to agree.
     */
    dev.supirvast.vastir.core.Expr domainAt(dev.supirvast.vastir.core.Expr worldPoint) {
        Lowering ctx = new Lowering(new Axes(
                xName, plotX(volume, Ir.x(worldPoint)),
                yName, plotY(volume, Ir.z(worldPoint))));
        lower(plotExpr, ctx);
        if (ctx.guards.isEmpty()) {
            return null;
        }
        dev.supirvast.vastir.core.Expr worst = ctx.guards.get(0);
        for (int i = 1; i < ctx.guards.size(); i++) {
            // Defined only where every guard is, so the tightest one decides -- a min, not a sum.
            worst = Ir.min(worst, ctx.guards.get(i));
        }
        return worst;
    }

    /**
     * The implicit whose zero set is the graph: {@code worldY - h(worldX, worldZ)}.
     *
     * <p>Written this way rather than as {@code f(x, y) - z} for a reason worth stating. The gradient of
     * {@code y - h} is {@code (-h_x, 1, -h_z)}, whose magnitude is at least 1 everywhere — so dividing by it,
     * which is what {@code Normalize} does, never inflates the field and the march stays conservative in the
     * direction it needs to be. Where the graph is steep the gradient is large and the steps become small,
     * which is exactly the behaviour a pole wants: near {@code 1÷(x²+y²)} the height runs away, but so does its
     * derivative, and the normalised field stays finite.
     */
    private static dev.supirvast.vastir.core.Expr implicit(Expr expr, Volume volume, String xName, String yName) {
        // World point back to plot coordinates: the volume's floor covers [-HALF, HALF] in x and z.
        Lowering ctx = new Lowering(new Axes(
                xName, plotX(volume, Ir.x(Ir.POINT)),
                yName, plotY(volume, Ir.z(Ir.POINT))));
        dev.supirvast.vastir.core.Expr height = lower(expr, ctx);

        // Plot height to world height: the same mapping, run the other way.
        double zMid = (volume.zLo() + volume.zHi()) / 2;
        double zHalf = volume.zHeight() / 2;
        dev.supirvast.vastir.core.Expr worldHeight =
                Ir.mul(Ir.sub(height, Ir.f(zMid)), Ir.f(HALF_Z / zHalf));

        // Clamped far outside the box, where it changes no picture -- see HEIGHT_CLAMP on why an infinity is a
        // different thing from a very large number here.
        worldHeight = Ir.clamp(worldHeight, Ir.f(-HEIGHT_CLAMP), Ir.f(HEIGHT_CLAMP));

        return Ir.sub(Ir.y(Ir.POINT), worldHeight);
    }

    /**
     * A world {@code x} read back as the plot's {@code x}.
     *
     * <p>Exposed for {@link PlotGrid}, which has to answer "which plot coordinate is this shaded point at" and
     * must answer it the same way the geometry did. Sharing the conversion rather than restating it is what
     * keeps the drawn gridlines on the surface they are drawn on: any drift between two copies of this
     * arithmetic would show up as a grid that slides across the shape as the framing changes.
     */
    static dev.supirvast.vastir.core.Expr plotX(Volume volume, dev.supirvast.vastir.core.Expr worldX) {
        return unmap(worldX, volume.xLo(), volume.xHi(), HALF);
    }

    /** A world {@code z} read back as the plot's {@code y}. The axis swap of the class note, in one place. */
    static dev.supirvast.vastir.core.Expr plotY(Volume volume, dev.supirvast.vastir.core.Expr worldZ) {
        return unmap(worldZ, volume.yLo(), volume.yHi(), HALF);
    }

    /**
     * Where a world point sits between the floor and the ceiling of the box, as 0 to 1.
     *
     * <p>The height ramp's input, and it needs no volume: the whole point of mapping every framing onto one
     * fixed box is that the box's floor and ceiling are the same numbers for every expression, so "how high is
     * this" is answerable without knowing what is being drawn.
     */
    static dev.supirvast.vastir.core.Expr heat(dev.supirvast.vastir.core.Expr worldY) {
        return Ir.clamp(
                Ir.div(Ir.add(worldY, Ir.f(HALF_Z)), Ir.f(2 * HALF_Z)),
                Ir.f(0.0), Ir.f(1.0));
    }

    /** A world coordinate in {@code [-half, half]}, read back as the plot coordinate it stands for. */
    private static dev.supirvast.vastir.core.Expr unmap(dev.supirvast.vastir.core.Expr world,
                                                       double lo, double hi, double half) {
        double mid = (lo + hi) / 2;
        double scale = (hi - lo) / 2 / half;
        return Ir.add(Ir.f(mid), Ir.mul(world, Ir.f(scale)));
    }

    /** Which world expression each of the plot's parameter names stands for. */
    private record Axes(String xName, dev.supirvast.vastir.core.Expr x,
                        String yName, dev.supirvast.vastir.core.Expr y) {

        dev.supirvast.vastir.core.Expr of(String name) {
            if (name.equals(xName)) {
                return x;
            }
            if (name.equals(yName)) {
                return y;
            }
            throw new Unlowerable("no axis for " + name);
        }
    }

    /**
     * The translation itself. {@link Expr} is a deliberately <em>open</em> interface — a node can be written
     * outside the plot module — so the default case refuses by class name rather than pretending the switch is
     * exhaustive.
     *
     * <p>Division is lowered as division, poles and all. A shader divides by zero without complaining and
     * produces an infinity, which the march sees as a step it is not allowed to take: {@code MarchSettings}
     * clamps every step to {@code maxStep} and gives up at {@code farPlane}. So a pole costs a ray its whole
     * step budget and comes back as sky, rather than as anything untrue.
     */
    private static dev.supirvast.vastir.core.Expr lower(Expr e, Lowering ctx) {
        return switch (e) {
            case Expr.Const c -> Ir.f(c.value().doubleValue());
            case Expr.Param p -> ctx.axes.of(p.name());
            case Expr.Add a -> Ir.add(lower(a.left(), ctx), lower(a.right(), ctx));
            case Expr.Sub s -> Ir.sub(lower(s.left(), ctx), lower(s.right(), ctx));
            case Expr.Mul m -> Ir.mul(lower(m.left(), ctx), lower(m.right(), ctx));
            case Expr.Div d -> Ir.div(lower(d.left(), ctx), lower(d.right(), ctx));
            case Expr.Power p -> power(p, ctx);
            case Expr.Sin s -> Ir.call(MathFn.SIN, Ir.F32, lower(s.arg(), ctx));
            case Expr.Cos c -> Ir.call(MathFn.COS, Ir.F32, lower(c.arg(), ctx));
            case Expr.Tan t -> Ir.call(MathFn.TAN, Ir.F32, lower(t.arg(), ctx));
            case Expr.Exp x -> Ir.call(MathFn.EXP, Ir.F32, lower(x.arg(), ctx));
            case Expr.Log l -> Ir.call(MathFn.LOG, Ir.F32, lower(l.arg(), ctx));
            default -> throw new Unlowerable("no shader form for " + e.getClass().getSimpleName());
        };
    }

    /**
     * A power, expanded into multiplication where the exponent is a small integer.
     *
     * <p>Not an optimisation — a correctness fix. GLSL's {@code pow(x, n)} is undefined for a negative base, so
     * {@code x^2} lowered as {@code pow} loses the whole half of the surface where {@code x < 0}. Repeated
     * multiplication has no such hole, and it differentiates through the product rule the compiler already has
     * rather than through {@code POW}'s logarithm, which carries the same restriction into the gradient.
     */
    private static dev.supirvast.vastir.core.Expr power(Expr.Power p, Lowering ctx) {
        BigDecimal exponent = p.exponent().constant()
                .orElseThrow(() -> new Unlowerable("a varying exponent"));
        dev.supirvast.vastir.core.Expr base = lower(p.base(), ctx);
        BigDecimal whole = exponent.stripTrailingZeros();
        if (whole.scale() <= 0 && whole.abs().compareTo(BigDecimal.valueOf(MAX_UNROLL)) <= 0) {
            int n = whole.intValueExact();
            if (n == 0) {
                return Ir.f(1.0);
            }
            dev.supirvast.vastir.core.Expr product = base;
            for (int i = 1; i < Math.abs(n); i++) {
                product = Ir.mul(product, base);
            }
            return n > 0 ? product : Ir.div(Ir.f(1.0), product);
        }

        // A fractional exponent is a root, and a root of a negative number is not a real value. This is the
        // case the integer unroll above cannot cover -- there is no repeated multiplication that means x^0.5 --
        // so it is the one place the shader's own pow is reached, and pow of a negative base is NaN.
        //
        // Two things happen here rather than one. The base is <b>clamped</b>, so the arithmetic stays finite
        // and no NaN is ever created; and the unclamped base is recorded as a <b>guard</b>, so the shading can
        // paint the region where the clamp was doing something. Clamping alone would be dishonest -- it draws
        // a flat plateau of zeroes exactly where the expression has no value, and a plateau is a claim.
        ctx.guards.add(base);
        dev.supirvast.vastir.core.Expr safe = Ir.max(base, Ir.f(DOMAIN_FLOOR));
        return Ir.call(MathFn.POW, Ir.F32, safe, Ir.f(exponent.doubleValue()));
    }

    /**
     * What a single lowering pass carries: where the axes are, and what it learned about the domain on the way.
     *
     * <p>The guards are collected rather than returned because they are found deep inside the tree and belong
     * to the whole expression. Each one is an expression that is non-negative exactly where that node had a
     * real value to give.
     */
    private static final class Lowering {
        final Axes axes;
        final java.util.List<dev.supirvast.vastir.core.Expr> guards = new java.util.ArrayList<>();

        Lowering(Axes axes) {
            this.axes = axes;
        }
    }

    /** Thrown while lowering, caught by {@link #of}, and reported as a refusal rather than a crash. */
    private static final class Unlowerable extends RuntimeException {
        Unlowerable(String message) {
            super(message);
        }
    }
}
