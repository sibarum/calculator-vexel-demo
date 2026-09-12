package dev.vexelray.demo.calculator;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.Bindings;
import dev.vexelray.shader.Shading;
import dev.vexelray.shader.ShadingPoint;
import dev.vexelray.shader.Shadings;

/**
 * The plot's own shading model: a lit curve and flat furniture, in one scene.
 *
 * <h2>What this is working around</h2>
 *
 * <p>{@code SdfScene} carries <b>one</b> {@link Shading} for everything in it, which is
 * {@code docs/framework-notes.md} FN-21: a lit tube is what makes the curve read as a solid in space, and it is
 * exactly wrong for a grid line, which wants to be a faint even mark you read the curve <em>against</em> rather
 * than another object in the scene. Lit, every grid line carries a bright edge and a dark edge and a highlight
 * that slides along it as the camera turns.
 *
 * <p>The framework's answer to that is a per-surface material, which does not exist, and its nearer answer is
 * the <b>payload</b> — {@code SdfComposer} will pass the shading model a float saying which surface was hit,
 * and {@link ShadingPoint#payload()} is the slot it arrives in. That is the right seam and it is unreachable
 * from here: {@code ConeField}'s buffer is eight floats of geometry per cone and it composes no payload
 * function at all, so every hit on this path arrives as {@link ShadingPoint#NO_PAYLOAD}.
 *
 * <h2>So the geometry is asked instead of the material</h2>
 *
 * <p>What a shading model <em>does</em> get is the hit point, and the furniture has a property the curve does
 * not: <b>it lies in the box's faces</b>. {@code Geometry.grid} puts every grid line on {@code y = -BOX_H},
 * {@code z = -BOX_H} or {@code x = -BOX} and nowhere else — deliberately, and for a reason that has nothing to
 * do with shading ("a plane through the middle of the volume is something the curve has to be read through, and
 * one on the back wall is something it is read against"). A grid tube's centre line is <em>in</em> its plane, so
 * every point on its surface is within {@code GRID_RADIUS} of it, and nothing else in the scene is: the axes run
 * through the origin, the ticks stand off them, and the curve lives in the interior.
 *
 * <p>So "is this point on a face" is a distance test on three planes, and it is the discriminator this model
 * shades on. It costs three subtractions, three absolute values and two minimums per hit, once per pixel.
 *
 * <h2>Where it is wrong, which is worth knowing before trusting it</h2>
 *
 * <p><b>A curve that reaches the edge of its own box is flattened where it touches.</b> The domain axis runs to
 * {@code ±BOX} and the outputs are scaled to fill the box, so a curve at the extreme of its range grazes a face
 * and the sliver of it within the threshold shades as furniture. It is a few pixels at a tip, and it is the
 * honest cost of inferring identity from position rather than being told it.
 *
 * <p>It is also blind to which grid planes are <em>on</em>. The test asks about all three faces whatever the
 * LAYERS panel says, because the model is compiled into the pipeline and the panel is a live control — making
 * it follow the panel would mean a new pipeline per toggle. Nothing is drawn on a face whose grid is off, so
 * the only consequence is the tip case above, on a face with no grid on it.
 */
final class Lighting implements Shading {

    /**
     * How bright flat furniture is against a lit curve.
     *
     * <p>Unshaded does not mean unattenuated: the lighting model this replaces for the furniture reaches
     * roughly full albedo somewhere along a tube's lit side, so a grid line returning its albedo <em>whole</em>
     * would read as brighter than the curve rather than as flat. What the furniture wants is one even value
     * below the curve's, which is this one — the scene's albedo taken down until a grid line is something the
     * curve is read against.
     */
    private static final double FURNITURE = 0.42;

    /**
     * How near a face counts as being on it.
     *
     * <p>Above {@code Geometry.GRID_RADIUS}, because a tube's centre line is in the plane and its surface is a
     * radius off it, so the far side of a grid line is the deepest point that has to pass. The pair is a
     * {@code smoothstep} rather than a cut so the boundary is a blend: it changes nothing for a grid line,
     * which is inside it everywhere, and it keeps the curve's own graze of a face from arriving as a hard edge.
     */
    private static final double ON_FACE = 0.010;
    private static final double OFF_FACE = 0.016;

    /**
     * How far past a piece of furniture's own radius the test reaches.
     *
     * <p>Every threshold here is {@code radius + this}, because what the test has to catch is a <em>surface</em>
     * and a surface is a radius off the centre line it was built from. Written once rather than added to three
     * numbers, so that widening the tolerance is one edit and cannot be done to two of them.
     */
    private static final double MARGIN = 0.006;

    /** What everything that is not furniture gets, unchanged: the scene's key light. */
    private final Shading lit = Shadings.defaultKeyLight();

    @Override
    public String id() {
        return "plot-lit-curve-flat-furniture";
    }

    @Override
    public boolean usesLights() {
        // The curve is still lit, so the scene still needs whatever a light costs. Answering false here would
        // be answering for the furniture alone and would compile the light out from under the curve.
        return lit.usesLights();
    }

    @Override
    public Expr shade(ShadingPoint point, Bindings bindings) {
        Expr position = bindings.bind("p", point.position());
        Expr x = Ir.x(position);
        Expr y = Ir.y(position);
        Expr z = Ir.z(position);

        // On one of the three faces the grid is drawn on.
        Expr toFloor = faceDistance(y, Geometry.BOX_H);
        Expr toBack = faceDistance(z, Geometry.BOX_H);
        Expr toSide = faceDistance(x, Geometry.BOX);
        Expr toFace = Expr.MathCall.min(toSide, Expr.MathCall.min(toFloor, toBack));

        // Or on one of the three axes, which are lines through the origin rather than planes -- so the
        // question is distance to a line, and that is the length of the two coordinates the line is not along.
        Expr toAxis = bindings.bind("toAxis",
                Expr.MathCall.min(distance2(y, z), Expr.MathCall.min(distance2(x, z), distance2(x, y))));

        // Or on a tick, which is neither: a tick is a short mark standing off an axis, so it reaches TICK away
        // from one -- far enough that a cylinder wide enough to hold it would also hold a curve passing nearby.
        // What separates them is that a tick is flat against a coordinate plane and a curve crossing the same
        // space is not, so both have to hold: near an axis, AND in the plane the ticks are drawn in.
        Expr toPlane = Expr.MathCall.min(Expr.MathCall.abs(x),
                Expr.MathCall.min(Expr.MathCall.abs(y), Expr.MathCall.abs(z)));

        Expr furniture = bindings.bind("furniture", Expr.MathCall.max(
                within(toFace, ON_FACE, OFF_FACE),
                Expr.MathCall.max(
                        within(toAxis, Geometry.AXIS_RADIUS + MARGIN, Geometry.AXIS_RADIUS + 2 * MARGIN),
                        Expr.MathCall.min(
                                within(toAxis, Geometry.TICK + MARGIN, Geometry.TICK + 2 * MARGIN),
                                within(toPlane, ON_FACE, OFF_FACE)))));

        Expr flat = Ir.scale(point.albedo(), Ir.f(FURNITURE));
        return Expr.MathCall.mix(lit.shade(point, bindings), flat, Ir.broadcast(furniture, Ir.V3));
    }

    /** 1 where {@code d} is inside {@code near}, 0 outside {@code far}, and a blend across the boundary. */
    private static Expr within(Expr d, double near, double far) {
        return Ir.sub(Ir.f(1.0), Expr.MathCall.smoothstep(Ir.f(near), Ir.f(far), d));
    }

    /** Distance from a line, as the length of the two coordinates it does not run along. */
    private static Expr distance2(Expr a, Expr b) {
        return Expr.MathCall.length(Ir.v2(a, b));
    }

    /**
     * How far a coordinate is from the face at {@code -extent}.
     *
     * <p>The near faces only. The box has six and the furniture is on three of them, which is
     * {@code Geometry.grid}'s decision rather than this model's: the far faces are where a grid is read against
     * and the near ones are where it would be read through.
     */
    private static Expr faceDistance(Expr coordinate, double extent) {
        return Expr.MathCall.abs(Ir.add(coordinate, Ir.f(extent)));
    }
}
