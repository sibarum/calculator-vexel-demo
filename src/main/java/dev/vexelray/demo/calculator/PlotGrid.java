package dev.vexelray.demo.calculator;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.MathFn;
import dev.vexelray.gui.plot.Framing;
import dev.vexelray.gui.plot.Volume;
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.Bindings;
import dev.vexelray.shader.Shading;
import dev.vexelray.shader.ShadingPoint;

/**
 * The plot's grid, drawn on the marched surface rather than under it.
 *
 * <p>A {@link Shading} that wraps another one and darkens the albedo it hands on wherever the shaded point is
 * near a gridline. Because the lines are a property of <em>where the point is</em>, not of any geometry, they
 * drape: they follow the surface over every rise and into every trough, at no cost in nodes, in field
 * evaluations, or in the march. The grid is the same one the plot draws — {@link Framing#tickStep} chooses the
 * spacing from the volume's own span, so a picture marched here and a picture drawn by {@code SurfacePlot} rule
 * their axes at the same numbers.
 *
 * <p><b>Where the lines are.</b> The hit position is a world coordinate, so it is converted back to plot
 * coordinates by {@link SdfSurface#plotX}/{@link SdfSurface#plotY} — the same mapping the geometry was built
 * through, called rather than restated, so the lines cannot drift away from the surface they are drawn on. A
 * point's distance to the nearest gridline is then {@code |fract(u + 0.5) - 0.5|} in cells, and the line is a
 * {@code smoothstep} across that distance.
 *
 * <p><b>The degenerate case, and the reason for the facing term.</b> A line of constant {@code x} is a plane,
 * and where the surface is <em>parallel</em> to that plane the intersection is not a line — it is the whole
 * face. The clipped box's own {@code ±x} walls are exactly that: every point on one has the same {@code x}, so
 * a wall whose {@code x} happens to fall near a tick would light up edge to edge. Weighting each family of
 * lines by {@code 1 - |n · axis|} removes precisely that case and nothing else: it is zero where the surface is
 * tangent to the gridline's plane and one where it is perpendicular. The normal is already bound by the
 * composer before shading is asked for, so reading it here is a local read and not six more taps into the
 * field.
 *
 * <p><b>What this cannot do.</b> {@code core} has no screen-space derivatives — no {@code fwidth}, which is
 * what an antialiased grid normally scales its line width by — so the width is a fixed fraction of a cell in
 * plot units instead. Lines therefore thin out with distance rather than holding a constant weight on screen,
 * and on a very steep face they can shimmer. The facing term takes the worst of it; the rest is honest
 * aliasing, and fixing it properly wants a derivative intrinsic that does not exist yet.
 */
record PlotGrid(Shading inner, Volume volume, double xStep, double yStep) implements Shading {

    /** Roughly how many cells across each axis. The plot's own taste, borrowed. */
    private static final int TARGET_DIVISIONS = 10;

    /** Line half-width and falloff, as fractions of a cell — so the weight holds as the spacing changes. */
    private static final double INNER = 0.010;
    private static final double OUTER = 0.028;

    /** The line colour, linear RGB. Darker than any albedo the preview uses, so a line always reads as one. */
    private static final double LINE_R = 0.16;
    private static final double LINE_G = 0.19;
    private static final double LINE_B = 0.26;

    /** Rule {@code base} with the grid the volume implies. */
    static PlotGrid over(Shading base, Volume volume) {
        return new PlotGrid(base,
                volume,
                Framing.tickStep(volume.xWidth(), TARGET_DIVISIONS),
                Framing.tickStep(volume.yDepth(), TARGET_DIVISIONS));
    }

    /**
     * The steps are in the id, not just the wrapper's name. Two surfaces framed differently rule their axes at
     * different numbers and so compile to different shaders; sharing an id would let the cache serve one
     * scene's grid to the other, which is the same collision {@code Shadings.Lambert} guards against by putting
     * its sun in its own id.
     */
    @Override
    public String id() {
        return "plot-grid(" + xStep + "," + yStep + ")/" + inner.id();
    }

    @Override
    public boolean usesLights() {
        return inner.usesLights();
    }

    @Override
    public Expr shade(ShadingPoint point, Bindings bindings) {
        Expr position = point.position();
        Expr normal = point.normal();

        // Lines of constant x, suppressed where the surface is tangent to them; then the same along the other
        // axis. The plot's y is the world's z, as everywhere else in this pair of files.
        Expr alongX = Ir.mul(
                line(SdfSurface.plotX(volume, Ir.x(position)), xStep),
                facing(Ir.x(normal)));
        Expr alongY = Ir.mul(
                line(SdfSurface.plotY(volume, Ir.z(position)), yStep),
                facing(Ir.z(normal)));

        // Bound because it is about to be broadcast into three colour channels, and a broadcast is three
        // copies of whatever it is given -- the mechanism Bindings exists to stop.
        Expr grid = bindings.bind("grid", Ir.max(alongX, alongY));

        Expr ruled = Ir.mix(point.albedo(), Ir.v3(LINE_R, LINE_G, LINE_B), Ir.broadcast(grid, Ir.V3));
        return inner.shade(new ShadingPoint(position, normal, point.view(), ruled,
                point.roughness(), point.metallic()), bindings);
    }

    /** How much of a line is at {@code plot}: 1 on a gridline, 0 by {@link #OUTER} of a cell away from one. */
    private static Expr line(Expr plot, double step) {
        Expr cells = Ir.div(plot, Ir.f(step));
        Expr toNearest = Ir.abs(Ir.sub(
                Ir.call(MathFn.FRACT, Ir.F32, Ir.add(cells, Ir.f(0.5))),
                Ir.f(0.5)));
        return Ir.sub(Ir.f(1.0),
                Ir.call(MathFn.SMOOTHSTEP, Ir.F32, Ir.f(INNER), Ir.f(OUTER), toNearest));
    }

    /**
     * How square-on the surface is to a family of gridlines: 0 where it is parallel to their plane — where the
     * line would smear across a whole face — and 1 where it is perpendicular.
     */
    private static Expr facing(Expr normalComponent) {
        return Ir.sub(Ir.f(1.0), Ir.abs(normalComponent));
    }
}
