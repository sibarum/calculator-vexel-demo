package dev.vexelray.demo.calculator;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.MathFn;
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.Bindings;
import dev.vexelray.shader.Shading;
import dev.vexelray.shader.ShadingPoint;

/**
 * Paints the places where the expression has no real value, so that "there is no answer here" looks like
 * <em>that</em> rather than like a surface.
 *
 * <p>The case this exists for is {@code (x²−y²)^(1÷2)}, and before it that expression rendered as a field of
 * speckle across the two wedges where {@code x² − y² < 0}. The speckle was not a stylistic problem; it was a
 * shader computing {@code pow} of a negative number, getting NaN, and carrying it into the field, the gradient
 * and the normal, where every comparison against it is false and the hit test decides essentially at random.
 * A reader had no way to tell that from a surface that genuinely looked like that.
 *
 * <p>So two things changed together. {@link SdfSurface} clamps, so the arithmetic never produces a NaN at all —
 * and clamping alone would be worse than the speckle, because it draws a smooth plateau of zeroes over the
 * undefined region and a plateau is a <b>claim</b>, one the expression does not support. This is the other
 * half: the same guard the clamp was applied against is evaluated at the shaded point, and where it is negative
 * the surface is painted as what it is.
 *
 * <h2>Why it is not lit</h2>
 * This wraps the <em>outside</em> of the whole shading chain and mixes the finished colour, where a
 * {@link MarchStyle} replaces the albedo underneath the light. Deliberately: a lit surface reads as geometry —
 * the eye takes the shading as evidence of a shape being there — and the whole point is that no surface is
 * there. An unlit, self-lit colour reads as a mark on the picture instead of a part of it, which is what it is.
 *
 * <h2>The edge is soft, and that is honest too</h2>
 * The guard is faded across a narrow band rather than switched at zero. Right at the domain edge the surface is
 * genuinely marginal — the value exists but its derivative is running away, which is why the speckle used to
 * fringe outward past the true boundary — so a hard line would claim a precision the arithmetic has not got.
 * The band is in the guard's own units, so it narrows and widens with the expression rather than with the view.
 */
record Undefined(SdfSurface surface, Shading inner) implements Shading {

    /** Linear RGB of the mark. Red, bright, and nothing else in the palette is near it. */
    private static final double R = 0.85;
    private static final double G = 0.12;
    private static final double B = 0.10;

    /** How much of the mark is added on top of the mix, which is what makes it read as glowing. */
    private static final double GLOW = 0.35;

    /** How far either side of the domain edge the mark fades, in the guard's own units. */
    private static final double EDGE = 0.05;

    /** {@code inner}, with the undefined region of {@code surface} painted over whatever it produced. */
    static Shading over(Shading inner, SdfSurface surface) {
        return new Undefined(surface, inner);
    }

    /**
     * Carries the expression, because two expressions are two different regions and therefore two different
     * shaders. The plot expression's own {@code toString} is a structural rendering of a record tree, so equal
     * ids really do mean equal IR — which is the contract, and the one {@code Shadings.Lambert} puts its whole
     * light into its id to keep.
     */
    @Override
    public String id() {
        return "undefined(" + surface.plotExpr() + "@" + surface.volume() + ")/" + inner.id();
    }

    @Override
    public boolean usesLights() {
        return inner.usesLights();
    }

    @Override
    public Expr shade(ShadingPoint point, Bindings bindings) {
        Expr lit = inner.shade(point, bindings);
        Expr guard = surface.domainAt(point.position());
        if (guard == null) {
            return lit;                      // defined everywhere: not a node of cost, not a node at all
        }
        // 1 where the expression has no value, 0 where it has one, faded across EDGE between them.
        Expr mark = bindings.bind("undefined", Ir.sub(Ir.f(1.0),
                Ir.call(MathFn.SMOOTHSTEP, Ir.F32, Ir.f(-EDGE), Ir.f(EDGE), guard)));

        Expr colour = Ir.v3(R, G, B);
        Expr mixed = Ir.mix(lit, colour, Ir.broadcast(mark, Ir.V3));
        // Added, not just mixed: the addition is what makes it self-lit rather than merely red, so it does not
        // fall into shadow with the surface around it and cannot be mistaken for a coloured face.
        return Ir.add(mixed, Ir.scale(colour, Ir.mul(mark, Ir.f(GLOW))));
    }
}
