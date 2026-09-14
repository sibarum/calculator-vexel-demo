package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.technique.panel.Panel;

/**
 * The three coordinate planes, as drawings rather than as geometry in the field — and, on them, the axes and
 * their graduations. The whole frame the curve is read against.
 *
 * <h2>Why they stopped being tubes</h2>
 *
 * <p>A grid line was a stroke in the same union as the curve, at {@code GRID_RADIUS} 0.008 — and the march's hit
 * threshold is {@code hitEpsilon + hitEpsilonSlope × distance}, about 0.015 at seven units. A tube thinner than
 * the test that finds it is found <em>by the test</em>: whether a pixel is grid is settled by an epsilon
 * comparison rather than by how much of the pixel the line covers, and a sphere-tracer has no coverage to
 * offer. That is what made the grid come out dashed, and it is why raising the marched resolution improved it
 * without fixing it — more pixels move the threshold, they do not remove it.
 *
 * <p>A {@code Canvas} on a {@link Panel} has the opposite problem to solve and solves it exactly: the shapes
 * <em>are</em> the geometry, their edges are analytic, and {@code PanelTechnique} computes its own
 * anti-aliasing width from the closed-form Jacobian of the projection — so a line is one pixel soft at every
 * distance and every angle, and there is no resolution anywhere in the path to have chosen wrongly.
 *
 * <h2>Through the origin, which reverses a decision</h2>
 *
 * <p>{@code Geometry.grid} put its planes on the box's far <em>faces</em>, and said why: "a plane through the
 * middle of the volume is something the curve has to be read through, and one on the back wall is something it
 * is read against". That was the right call for planes that could not be read through — a lit tube is opaque
 * and one crossing the curve is an object in front of it.
 *
 * <p>These are centred on the origin instead, and the thing that makes it work is the thing that was missing:
 * the planes are alpha-blended drawings that <b>test the depth the march writes</b>. A grid line nearer than
 * the curve draws over it and one behind is hidden by it, per pixel, so a plane through the middle reads as a
 * plane the curve passes through rather than as a cage in front of it. Each plane is perpendicular to one axis
 * and carries that axis's two neighbours, so the three of them are the coordinate system drawn out.
 *
 * <h2>What a canvas unit is here</h2>
 *
 * <p>A panel says how big one canvas unit is in the world, and {@link #UNITS_PER_PIXEL} is chosen to make the
 * arithmetic disappear: every plane's canvas is exactly the span of the box in its own two directions. A line
 * at canvas {@code x} is at world {@code x} by construction, and there is no second mapping to keep in step
 * with {@code Geometry}'s.
 *
 * <p>The consequence worth stating, because it is a change from the marched grid: <b>a line's width is a
 * measurement in the world</b>, not a count of pixels. {@link #WIDTH} canvas units is that many world units
 * wide wherever it is, so the far side of a plane draws thinner than the near side. That is what a drawn plane
 * in perspective looks like; a uniform hairline would be a different decision, and would want the width divided
 * by the projected scale rather than fixed.
 */
enum Grid {

    /**
     * Perpendicular to the vertical output axis — the one that used to be the floor, now through the origin.
     *
     * <p>Yaw zero leaves the canvas's {@code +x} along the world's, and the quarter turn lays the sheet down: at
     * {@code roll = 0} a panel's down axis is {@code (−sin·sin, −cos, −sin·cos)}, so {@code pitch = −π/2} sends
     * canvas-down to world {@code +z} and the plane becomes the {@code xz} one.
     */
    XZ(2 * Geometry.BOX, 2 * Geometry.BOX_H, 0, -Math.PI / 2),

    /**
     * Perpendicular to the second output axis — the plane a camera at rest is looking straight at.
     *
     * <p>No rotation at all. A panel's unrotated axes are world {@code +x} and world {@code −y}, and canvas y
     * points down, so an untouched panel is already the {@code xy} plane the right way up.
     */
    XY(2 * Geometry.BOX, 2 * Geometry.BOX_H, 0, 0),

    /**
     * Perpendicular to the input axis — the one that stands across the domain.
     *
     * <p>A quarter turn of yaw swings the canvas's {@code +x} onto world {@code −z} and leaves down where it
     * was, since a pitch of zero keeps it at world {@code −y} whatever the yaw. The grid is symmetric along
     * {@code z}, so which way the quarter turn goes is not observable in the picture.
     */
    YZ(2 * Geometry.BOX_H, 2 * Geometry.BOX_H, Math.PI / 2, 0);

    /**
     * How many canvas units the domain is drawn across. The one number here that is a choice.
     *
     * <p>Nothing is rasterised at this size — a canvas unit is a measurement on a plane, not a pixel — so what
     * it decides is only how fine the arithmetic is. 720 across 3.6 world units makes a canvas unit five
     * thousandths of one, which is small enough that a line's width and a division's position are exact in
     * {@code float} and round enough to read in a debugger.
     */
    private static final int DOMAIN_UNITS = 720;

    /**
     * World units per canvas unit, shared by all three so that one width means one thickness everywhere.
     *
     * <p><b>Derived, not chosen</b>, and that is load-bearing: the grid divides the box and the axis ticks are
     * mapped into the same box, so the two agree only while a canvas unit is exactly the box over the canvas.
     * Writing the quotient out is what makes a change to {@code BOX} carry, rather than leaving a literal that
     * used to be right.
     */
    static final double UNITS_PER_PIXEL = 2 * Geometry.BOX / DOMAIN_UNITS;

    /**
     * How wide a grid line is drawn, in canvas units — so {@code 2} is {@code 0.01} world units.
     *
     * <p>Thinner than the tube it replaces (which was {@code 0.016} across) because it no longer has to survive
     * being found by an epsilon test: a drawn line half a pixel wide still draws, at half coverage, where a
     * marched tube that thin disappears between the rays.
     */
    private static final float WIDTH = 2f;

    /**
     * The line's colour — the axes' own, because this is the first time a grid has had one at all.
     *
     * <p>Two things were hiding each other here. {@code Geometry} asked for {@code surface(1)}, pushed most of
     * the way down to the page so that a <em>lit</em> grid line would stop competing with the curve (FN-21's
     * cost paragraph) — and {@code ConeField} then dropped it, because a cone in its buffer is eight floats of
     * geometry and carries no colour at all (FN-25). So the marched grid was never {@code surface(1)}: it was
     * the scene's one albedo, dimmed by the lighting it happened to catch.
     *
     * <p>A drawn line has neither problem. It carries its own colour and nothing dims it, so the number that
     * was calibrated against a highlight is far too dark — tried, and the grid came out all but invisible.
     * {@link Look#QUIET} is what {@code Geometry} gives the axes, and a plane wants to sit with them rather
     * than a step below a brightness it no longer gets.
     */
    private static final Role LINE = Look.LINE;

    /**
     * An axis, and its ticks: thicker than a grid line and a step brighter.
     *
     * <p>The two were separated by colour when they were tubes and had to be, because {@code ConeField} drops
     * a stroke's own colour and the only thing left to tell them apart was how the light caught them — which
     * was nothing, since they were the same shape. Drawn, they can differ in both, and what they mean is
     * different enough to deserve it: a grid line is a repeated interval and an axis is where zero is.
     */
    private static final float AXIS_WIDTH = 3f;
    private static final float TICK_WIDTH = 2.5f;
    private static final Role AXIS = Look.QUIET;

    /**
     * How far a tick stands off its axis, in canvas units — {@code 0.06} world units, as it was when a tick
     * was a tube.
     *
     * <p>It stands off into the third direction, which is what decides <em>which</em> plane can draw it: a mark
     * on the domain axis reaching along {@code Re} only fits on the plane that holds both.
     */
    private static final float TICK = 12f;

    private final int width;
    private final int height;
    private final double yaw;
    private final double pitch;

    Grid(double worldWidth, double worldHeight, double yaw, double pitch) {
        this.width = (int) Math.round(worldWidth / UNITS_PER_PIXEL);
        this.height = (int) Math.round(worldHeight / UNITS_PER_PIXEL);
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** The canvas this plane is drawn on, in canvas units — the box's own span in this plane's two directions. */
    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** Where this plane hangs: through the origin, turned to face along the axis it is perpendicular to. */
    Panel panel() {
        return new Panel(0, 0, 0, yaw, pitch, 0, UNITS_PER_PIXEL);
    }

    /**
     * Whether the scene asks for this plane.
     *
     * <p>The three flags were named for the box's faces and still name the same three planes, so they carry
     * over unchanged — what moved is where the plane sits, not which one it is.
     */
    boolean on(Geometry.Furniture furniture) {
        return switch (this) {
            case XY -> furniture.gridXY();
            case XZ -> furniture.gridXZ();
            case YZ -> furniture.gridYZ();
        };
    }

    /**
     * Everything this plane carries: its grid, and whichever axes and ticks lie in it.
     *
     * <p>Each axis lies in <em>two</em> of the three planes and is drawn on exactly one of them, because these
     * are alpha-blended and a line drawn twice is a brighter line. The assignment keeps an axis with its own
     * ticks, which is not a free choice: a tick stands off its axis into a third direction, so it can only be
     * drawn on the plane that contains that direction. The domain and {@code Re} ticks are in {@code z = 0} and
     * the {@code Tr} ticks are in {@code x = 0}, and the axes follow them there.
     *
     * <p>The grid answers to its own flag and the axes to theirs, so a plane switched off in LAYERS still
     * carries the axis that lives in it — what the toggle turns off is the grid, not the coordinate system.
     */
    void draw(Canvas canvas, Geometry.Furniture furniture, Geometry.Marks marks) {
        if (on(furniture)) {
            grid(canvas, Math.max(2, furniture.divisions()), furniture.axes());
        }
        if (!furniture.axes()) {
            return;
        }
        Color ink = AXIS.of(Look.PALETTE);
        float midX = width / 2f;
        float midY = height / 2f;
        switch (this) {
            case XY -> {
                canvas.strokeLine(0, midY, width, midY, AXIS_WIDTH, ink);        // the domain axis
                canvas.strokeLine(midX, 0, midX, height, AXIS_WIDTH, ink);       // Re
                if (furniture.ticks()) {
                    for (double at : marks.domain()) {
                        float x = midX + units(at);
                        canvas.strokeLine(x, midY - TICK, x, midY + TICK, TICK_WIDTH, ink);
                    }
                    for (double at : marks.output()) {
                        float y = midY - units(at);                              // canvas down is world -y
                        canvas.strokeLine(midX - TICK, y, midX + TICK, y, TICK_WIDTH, ink);
                    }
                }
            }
            case YZ -> {
                canvas.strokeLine(0, midY, width, midY, AXIS_WIDTH, ink);        // Tr
                if (furniture.ticks()) {
                    for (double at : marks.output()) {
                        float x = midX - units(at);                              // canvas right is world -z
                        canvas.strokeLine(x, midY - TICK, x, midY + TICK, TICK_WIDTH, ink);
                    }
                }
            }
            case XZ -> {
                // Both axes lying in this plane are drawn by the planes that carry their ticks.
            }
        }
    }

    /**
     * {@code divisions} squares across the sheet: both families span it, because the sheet is the plane.
     *
     * <p><b>Except the middle pair, when the axes are drawn.</b> A plane through the origin has its centre
     * lines <em>along</em> the two axes it contains — that is what being through the origin means — so with an
     * even division count the grid would draw the axes a second time, underneath and thinner. Alpha-blended,
     * that is a brighter axis than the one asked for, and it would appear and disappear as the divisions
     * slider passed through odd numbers, which is the kind of thing nobody ever tracks down.
     */
    private void grid(Canvas canvas, int divisions, boolean axes) {
        Color colour = LINE.of(Look.PALETTE);
        for (int i = 0; i <= divisions; i++) {
            if (axes && i * 2 == divisions) {
                continue;
            }
            float t = i / (float) divisions;
            canvas.strokeLine(t * width, 0, t * width, height, WIDTH, colour);
            canvas.strokeLine(0, t * height, width, t * height, WIDTH, colour);
        }
    }

    /** A distance in the box's coordinates, as canvas units. The panel's scale, read the other way. */
    private static float units(double world) {
        return (float) (world / UNITS_PER_PIXEL);
    }
}
