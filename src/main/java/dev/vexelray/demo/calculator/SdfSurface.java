package dev.vexelray.demo.calculator;

import dev.supirvast.vastir.core.MathFn;
import dev.vexelray.gui.plot.Expr;
import dev.vexelray.gui.plot.Framing;
import dev.vexelray.gui.plot.Volume;
import dev.vexelray.ir.Ir;
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
record SdfSurface(Surface surface, Volume volume, String xName, String yName, String refusal) {

    /** Half the width and depth of the world box the volume's floor is mapped onto. */
    private static final double HALF = 2.0;

    /** Half the height of that box. Flatter than it is wide, which is how a graph is usually drawn. */
    private static final double HALF_Z = 1.25;

    /** The largest integer exponent expanded into repeated multiplication rather than handed to {@code pow}. */
    private static final int MAX_UNROLL = 16;

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
            return new SdfSurface(clipped(implicit(expr, volume, xName, yName)), volume, xName, yName, null);
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
        return new SdfSurface(null, null, null, null, why);
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
        dev.supirvast.vastir.core.Expr height = lower(expr, new Axes(
                xName, plotX(volume, Ir.x(Ir.POINT)),
                yName, plotY(volume, Ir.z(Ir.POINT))));

        // Plot height to world height: the same mapping, run the other way.
        double zMid = (volume.zLo() + volume.zHi()) / 2;
        double zHalf = volume.zHeight() / 2;
        dev.supirvast.vastir.core.Expr worldHeight =
                Ir.mul(Ir.sub(height, Ir.f(zMid)), Ir.f(HALF_Z / zHalf));

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
    private static dev.supirvast.vastir.core.Expr lower(Expr e, Axes axes) {
        return switch (e) {
            case Expr.Const c -> Ir.f(c.value().doubleValue());
            case Expr.Param p -> axes.of(p.name());
            case Expr.Add a -> Ir.add(lower(a.left(), axes), lower(a.right(), axes));
            case Expr.Sub s -> Ir.sub(lower(s.left(), axes), lower(s.right(), axes));
            case Expr.Mul m -> Ir.mul(lower(m.left(), axes), lower(m.right(), axes));
            case Expr.Div d -> Ir.div(lower(d.left(), axes), lower(d.right(), axes));
            case Expr.Power p -> power(p, axes);
            case Expr.Sin s -> Ir.call(MathFn.SIN, Ir.F32, lower(s.arg(), axes));
            case Expr.Cos c -> Ir.call(MathFn.COS, Ir.F32, lower(c.arg(), axes));
            case Expr.Tan t -> Ir.call(MathFn.TAN, Ir.F32, lower(t.arg(), axes));
            case Expr.Exp x -> Ir.call(MathFn.EXP, Ir.F32, lower(x.arg(), axes));
            case Expr.Log l -> Ir.call(MathFn.LOG, Ir.F32, lower(l.arg(), axes));
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
    private static dev.supirvast.vastir.core.Expr power(Expr.Power p, Axes axes) {
        BigDecimal exponent = p.exponent().constant()
                .orElseThrow(() -> new Unlowerable("a varying exponent"));
        dev.supirvast.vastir.core.Expr base = lower(p.base(), axes);
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
        return Ir.call(MathFn.POW, Ir.F32, base, Ir.f(exponent.doubleValue()));
    }

    /** Thrown while lowering, caught by {@link #of}, and reported as a refusal rather than a crash. */
    private static final class Unlowerable extends RuntimeException {
        Unlowerable(String message) {
            super(message);
        }
    }
}
