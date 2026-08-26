package dev.vexelray.demo.calculator;

import dev.supirvast.vastir.core.Expr;
import dev.vexelray.canvas.Color;
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.Bindings;
import dev.vexelray.shader.Shading;
import dev.vexelray.shader.ShadingPoint;
import dev.vexelray.shader.Shadings;

/**
 * What the marched surface is coloured by — the render styles the preview offers.
 *
 * <p>Each style supplies an <b>albedo</b> and nothing else, which is what lets three of them exist for the price
 * of one. A style wraps the shading it is given, replaces the colour on the point, and hands it on; so
 * {@link PlotGrid} still rules the surface and the key light still lights it, whichever style is chosen, and
 * neither of them had to learn that styles exist.
 *
 * <p><b>Changing style recompiles the shader, and that is the design rather than a cost of it.</b> VexelRay's
 * lighting contract is explicit that a model is "not a runtime branch inside a fixed shader; it is a
 * participant in shader composition" — so the code for the styles you did not pick is never emitted, and the
 * chosen one costs no branch. The compile happens on a worker, off the thread that presents, and a style is
 * changed by a person pressing a button rather than by anything per-frame.
 *
 * <p>Colours are taken from {@link Palette} — the same three stops {@code SurfacePlot} ramps its cells and its
 * legend through — so the two views of one expression agree about what a height looks like. They are used as
 * written rather than converted: the marched target is a UNORM image whose values reach the screen unencoded,
 * exactly as the canvas's own colours do, so importing them unchanged is what makes the two match.
 */
enum MarchStyle {

    /** A neutral surface under one light: shape read from shading alone. */
    LIT("Lit") {
        @Override
        Expr albedo(ShadingPoint point, Bindings bindings) {
            return point.albedo();
        }
    },

    /**
     * Cool at the floor, warm at the ceiling — {@code SurfacePlot}'s ramp, on a surface with no cells.
     *
     * <p>Worth having beside the box view for exactly that reason: it is the one style where the two renderers
     * are making the same statement in the same colours, so a disagreement between them is visible rather than
     * something you have to hold in your head.
     */
    HEIGHT("Height") {
        @Override
        Expr albedo(ShadingPoint point, Bindings bindings) {
            // Bound: the ramp is two mixes deep and is about to be used by both of them.
            Expr heat = bindings.bind("heat", SdfSurface.heat(Ir.y(point.position())));
            Expr lower = mix(Palette.LOW, Palette.MID, Ir.mul(heat, Ir.f(2.0)));
            Expr upper = mix(Palette.MID, Palette.HIGH, Ir.sub(Ir.mul(heat, Ir.f(2.0)), Ir.f(1.0)));
            // step(0.5, heat) is 1 on the top half, so this selects without a branch.
            return Ir.mix(lower, upper, Ir.broadcast(Ir.step(Ir.f(0.5), heat), Ir.V3));
        }
    },

    /**
     * The surface normal as a colour — the classic, and here it is showing something real.
     *
     * <p>The normal a march shades with is a central difference of the distance field, which is to say it is
     * derived from the expression itself rather than averaged off any tessellation. Colouring by it directly is
     * the most honest look at how smooth the compiled field actually is, and it is where a field that is
     * misbehaving — a pole's throat, a normalisation running out of gradient — shows up first.
     */
    NORMAL("Normals") {
        @Override
        Expr albedo(ShadingPoint point, Bindings bindings) {
            return Ir.add(Ir.scale(point.normal(), Ir.f(0.5)), Ir.v3(0.5, 0.5, 0.5));
        }

        /**
         * Unlit, and it has to be. A normal shown through a light is the normal multiplied by a function of
         * itself, which darkens exactly the faces turned away from the key — so the two things a normals view
         * is for, seeing the raw orientation and spotting where the field misbehaves, are dimmest precisely
         * where you were looking for them.
         */
        @Override
        boolean lit() {
            return false;
        }
    };

    private final String label;

    MarchStyle(String label) {
        this.label = label;
    }

    /** What the button says while this style is on show. */
    String label() {
        return label;
    }

    /** The next style in the cycle, which is all the control needs to be. */
    MarchStyle next() {
        MarchStyle[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** The colour this style gives the point, before the grid rules it and the light lights it. */
    abstract Expr albedo(ShadingPoint point, Bindings bindings);

    /** Whether this style's colour wants a light over it. True for everything that is describing a surface. */
    boolean lit() {
        return true;
    }

    /**
     * The whole shading chain for this style, over {@code volume}: colour, then the grid, then a light or not.
     *
     * <p>Built here rather than at the call sites so the two of them — the live viewport and the capture —
     * cannot come to differ about what a style is, which would make a photograph of a style evidence about
     * nothing.
     */
    Shading shading(dev.vexelray.gui.plot.Volume volume) {
        return over(PlotGrid.over(lit() ? Shadings.defaultKeyLight() : Shadings.unlit(), volume));
    }

    /** {@code inner}, shading a point this style has coloured. */
    Shading over(Shading inner) {
        return new Styled(this, inner);
    }

    /** A {@code vec3} of a palette colour, as the shader wants it. */
    private static Expr rgb(Color c) {
        return Ir.v3(c.r(), c.g(), c.b());
    }

    /** {@code from} to {@code to} across {@code t}, clamped — the ramp's two halves are each one of these. */
    private static Expr mix(Color from, Color to, Expr t) {
        return Ir.mix(rgb(from), rgb(to),
                Ir.broadcast(Ir.clamp(t, Ir.f(0.0), Ir.f(1.0)), Ir.V3));
    }

    /**
     * The wrapper. Its {@link #id()} carries the style, because two scenes differing only in how they are
     * coloured are two different shaders — and a cache that could not tell them apart would serve one style's
     * pipeline for another's, which is the collision {@code Shadings.Lambert} puts its whole light into its id
     * to avoid.
     */
    private record Styled(MarchStyle style, Shading inner) implements Shading {

        @Override
        public String id() {
            return "march-style(" + style.name() + ")/" + inner.id();
        }

        @Override
        public boolean usesLights() {
            return inner.usesLights();
        }

        @Override
        public Expr shade(ShadingPoint point, Bindings bindings) {
            Expr albedo = style.albedo(point, bindings);
            return inner.shade(new ShadingPoint(point.position(), point.normal(), point.view(), albedo,
                    point.roughness(), point.metallic()), bindings);
        }
    }
}
