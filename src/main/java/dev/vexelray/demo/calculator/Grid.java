package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.technique.panel.Panel;

/**
 * The three coordinate planes, as drawings rather than as geometry in the field — and, on the one the plot is
 * in, the axes and every graduation on them. The whole frame the curve is read against.
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
 * plane the curve passes through rather than as a cage in front of it.
 *
 * <h2>The sheet has no edge, which is the whole of the rebuild</h2>
 *
 * <p>The plane the plot is in reaches {@link Geometry#REACH} boxes in every direction and <b>fades out on the
 * way</b>, so what a reader sees is a plane running off past the frame rather than a mat with the plot in the
 * middle of it. The other two are unchanged at the box's own size: they stand out of the picture at right
 * angles to everything in it, and a reader who switches one on is asking to see that plane edge-on rather
 * than asking for a room.
 *
 * <p>The fade is per <em>piece</em> and not per line, because a {@code Canvas} primitive carries one flat
 * colour: a line is drawn as a run of shorter lines with the alpha falling along it. That is the whole of why
 * {@link #PIECES} exists, and it is what the vertex budget is spent on — see {@link #floats()}.
 *
 * <h2>The lines are the graduations</h2>
 *
 * <p>The square lattice is gone from this plane. It was {@code divisions} equal squares across the box, and
 * {@code Furniture.DEFAULT} switched it off with the reason written out: "a square grid at six divisions is
 * the picture of a scale, and this chart has none. A reader counting squares out from the origin would be
 * counting something that is not there."
 *
 * <p>That is fixed rather than worked around. The horizontal lines now stand at the graduations of the turn
 * axis — the eight named turns, and the log ruler {@link Turns} prints between them — and the vertical lines
 * stand at the input's own 1-2-5 marks, carried on past the walked domain at the same pitch. So counting
 * squares counts something: every line is a value or an input, and every one of them is labelled or has a
 * labelled line a known number of rungs away. {@code divisions} still rules the other two planes, which have
 * no graduations of their own and never will.
 *
 * <h2>What a canvas unit is here</h2>
 *
 * <p>A panel says how big one canvas unit is in the world, and {@link #UNITS_PER_PIXEL} is chosen to make the
 * arithmetic disappear: a line at canvas {@code x} is at world {@code x} by construction, and there is no
 * second mapping to keep in step with {@code Geometry}'s.
 *
 * <p>The consequence worth stating, because it is a change from the marched grid: <b>a line's width is a
 * measurement in the world</b>, not a count of pixels. {@link #WIDTH} canvas units is that many world units
 * wide wherever it is, so the far side of a plane draws thinner than the near side. That is what a drawn plane
 * in perspective looks like.
 */
enum Grid {

    /**
     * Perpendicular to the vertical output axis — the one that used to be the floor, now through the origin.
     *
     * <p>Yaw zero leaves the canvas's {@code +x} along the world's, and the quarter turn lays the sheet down: at
     * {@code roll = 0} a panel's down axis is {@code (−sin·sin, −cos, −sin·cos)}, so {@code pitch = −π/2} sends
     * canvas-down to world {@code +z} and the plane becomes the {@code xz} one.
     */
    XZ(2 * Geometry.BOX, 2 * Geometry.BOX, 0, -Math.PI / 2),

    /**
     * The plane the plot is in, and the only one that is a sheet rather than a square.
     *
     * <p>No rotation at all. A panel's unrotated axes are world {@code +x} and world {@code −y}, and canvas y
     * points down, so an untouched panel is already the {@code xy} plane the right way up.
     */
    XY(2 * Geometry.REACH * Geometry.BOX, 2 * Geometry.REACH * Geometry.BOX, 0, 0),

    /**
     * Perpendicular to the input axis — the one that stands across the domain.
     *
     * <p>A quarter turn of yaw swings the canvas's {@code +x} onto world {@code −z} and leaves down where it
     * was, since a pitch of zero keeps it at world {@code −y} whatever the yaw. The grid is symmetric along
     * {@code z}, so which way the quarter turn goes is not observable in the picture.
     */
    YZ(2 * Geometry.BOX, 2 * Geometry.BOX, Math.PI / 2, 0);

    /**
     * How many canvas units the box is drawn across. The one number here that is a choice.
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
     * <p><b>Derived, not chosen</b>, and that is load-bearing: the graduations are mapped into the box by
     * {@link Geometry} and drawn here, and the two agree only while a canvas unit is exactly the box over the
     * canvas. Writing the quotient out is what makes a change to {@code BOX} carry, rather than leaving a
     * literal that used to be right.
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
     * was a tube. A rung of the ruler stands off less, by its rank: a decade is a full tick and a mantissa
     * is a short one, which is how a ruler says which marks are the ones to count.
     */
    private static final float TICK = 12f;
    private static final float[] RUNG = {9f, 6.5f, 4.5f};

    /** How much of the line's colour a rung keeps, by rank. A decade is a graduation; a nine is a hint. */
    private static final float[] RUNG_INK = {0.9f, 0.6f, 0.4f};

    /**
     * Where the fade begins, as a multiple of the box.
     *
     * <p>Outside the box's own corner, which stands at {@code √2}: a fade that began at the box would dim the
     * corners of the picture the curve is drawn in, and the sheet is furniture — it has no business taking
     * contrast away from the plot to make a point about its own extent.
     */
    private static final double FADE_FROM = 1.4;

    /**
     * How many pieces a line is cut into on each side of the box, to fade along its length.
     *
     * <p>Twelve is what the banding decides. A {@code Canvas} primitive is one flat colour, so the alpha steps
     * once per piece, and a step of more than about a tenth reads as a stripe rather than as a fade. Twelve
     * pieces over the fade's range steps by 0.08, which does not.
     */
    private static final int PIECES = 12;

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

    /** The canvas this plane is drawn on, in canvas units — the plane's own span in its two directions. */
    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /**
     * How much vertex data this plane's canvas may produce, in floats.
     *
     * <p><b>Per plane, because the three are no longer the same kind of drawing.</b> {@code XZ} and {@code YZ}
     * are a few dozen strokes, which is what the one shared ceiling was sized for — 64k floats is about 475
     * quads at 23 floats a vertex and six vertices a quad. The sheet is a hundred-odd lines cut into
     * {@link #PIECES} pieces a side so they can fade, and it would run that buffer out in the first frame it
     * was asked for — {@code draw} refuses a batch that does not fit, so the failure would be a grid that
     * silently stops halfway.
     *
     * <p>Half a megabyte of floats is about 3,800 quads, against a <b>measured</b> worst case of 1,356 —
     * {@code SheetBudgetTest} draws the densest sheet the controls can ask for and counts, so this is a
     * ceiling with a known headroom rather than a number that looked big enough.
     */
    int floats() {
        return this == XY ? 1 << 19 : 1 << 16;
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
     * Everything this plane carries.
     *
     * <p>The grid answers to its own flag and the axes to theirs, so a plane switched off in LAYERS still
     * carries the axis that lives in it — what the toggle turns off is the grid, not the coordinate system.
     * The two are drawn from one list of graduations either way, which is what stops a grid line standing
     * anywhere but on a mark.
     */
    void draw(Canvas canvas, Geometry.Furniture furniture, Geometry.Marks marks) {
        switch (this) {
            case XY -> sheet(canvas, furniture, marks);
            case XZ, YZ -> {
                // Both stand out of the plane the plot is in, so neither contains an axis there is anything
                // left to draw: the two axes are the input and the turn, and they both lie in XY. What these
                // keep is a square lattice at the divisions asked for, which is a fair thing to switch on to
                // see the plane edge-on against.
                if (on(furniture)) {
                    square(canvas, Math.max(2, furniture.divisions()));
                }
            }
        }
    }

    /**
     * The plot's own plane: the endless grid, the two axes, and every graduation.
     *
     * <p>Drawn in that order, which is the order they compose in: the sheet is the faintest thing in the
     * picture, the axes sit on it, and the marks sit on the axes.
     */
    private void sheet(Canvas canvas, Geometry.Furniture furniture, Geometry.Marks marks) {
        Color ink = AXIS.of(Look.PALETTE);
        Color faint = LINE.of(Look.PALETTE);
        float midX = width / 2f;
        float midY = height / 2f;

        if (on(furniture)) {
            // The input's own marks, carried on past the domain at the pitch they came out at. Past the last
            // walked input there is no number to write, but there is still a scale -- the axis does not stop
            // being a ruler where the walk stopped.
            double pitch = marks.pitch();
            if (pitch > 0) {
                for (int i = 1; i * pitch <= Geometry.REACH * Geometry.BOX; i++) {
                    vertical(canvas, i * pitch, WIDTH, faint, 1);
                    vertical(canvas, -i * pitch, WIDTH, faint, 1);
                }
            }
            // And the turn's. The named turns get a line here only when the axes are not already ruling them
            // across the plot -- alpha-blended, a line drawn twice is a brighter line.
            if (!furniture.ticks()) {
                for (double at : marks.up()) {
                    horizontal(canvas, at, WIDTH, faint, 1);
                }
            }
            // Every rung, right out to the sheet's edge. What keeps that affordable is that the ruler thins
            // out on its own as it goes -- Turns widens the gap it demands with the distance from the box --
            // rather than this drawing only some of what it was handed, which was tried and read as a grid
            // that stops rather than one that fades.
            for (Turns.Rung rung : marks.ladder()) {
                horizontal(canvas, rung.at(), WIDTH, faint, RUNG_INK[rung.rank()]);
            }
        }

        if (!furniture.axes()) {
            return;
        }
        horizontal(canvas, 0, AXIS_WIDTH, ink, 1);           // the turn zero
        vertical(canvas, 0, AXIS_WIDTH, ink, 1);             // the input zero
        if (!furniture.ticks()) {
            return;
        }
        for (double at : marks.across()) {
            float x = midX + units(at);
            canvas.strokeLine(x, midY - TICK, x, midY + TICK, TICK_WIDTH, ink);
        }
        // A rule right across at every named turn, not a tick beside the axis. These are the landmarks on
        // this axis -- a reader's question is "which side of omega is the curve here", which wants a line to
        // compare against and not a mark at the edge of the picture.
        for (double at : marks.up()) {
            horizontal(canvas, at, TICK_WIDTH, ink, 1);
        }
        // The ruler between them is the other thing: a graduation, beside the axis, at the length its rank
        // has earned. A rung ruled right across would make the plot unreadable at the zoom where the ruler
        // is densest, which is exactly the zoom it was built for.
        for (Turns.Rung rung : marks.ladder()) {
            float y = midY - units(rung.at());
            float arm = RUNG[rung.rank()];
            canvas.strokeLine(midX - arm, y, midX + arm, y, TICK_WIDTH, ink);
        }
    }

    /** {@code divisions} squares across the sheet, for the two planes that carry no graduations. */
    private void square(Canvas canvas, int divisions) {
        Color colour = LINE.of(Look.PALETTE);
        for (int i = 0; i <= divisions; i++) {
            float t = i / (float) divisions;
            canvas.strokeLine(t * width, 0, t * width, height, WIDTH, colour);
            canvas.strokeLine(0, t * height, width, t * height, WIDTH, colour);
        }
    }

    /**
     * A line right across the sheet at a height, in pieces, fading as it goes.
     *
     * <p>One piece inside the box and {@link #PIECES} either side of it, each at the alpha its own middle
     * stands at. The inner piece is one quad however long it is, because nothing inside the box fades — which
     * is what keeps an ordinary unmagnified picture at about the cost it was.
     */
    private void horizontal(Canvas canvas, double y, float w, Color colour, float strength) {
        if (Math.abs(y) > Geometry.REACH * Geometry.BOX) {
            return;
        }
        float cy = height / 2f - units(y);
        for (double[] piece : CUTS) {
            float a = alpha(mid(piece), y) * strength;
            if (a > 0) {
                canvas.strokeLine(width / 2f + units(piece[0]), cy, width / 2f + units(piece[1]), cy,
                        w, Color.withAlpha(colour, colour.a() * a));
            }
        }
    }

    /** The same, standing up: a line right down the sheet at an input. */
    private void vertical(Canvas canvas, double x, float w, Color colour, float strength) {
        if (Math.abs(x) > Geometry.REACH * Geometry.BOX) {
            return;
        }
        float cx = width / 2f + units(x);
        for (double[] piece : CUTS) {
            float a = alpha(x, mid(piece)) * strength;
            if (a > 0) {
                canvas.strokeLine(cx, height / 2f - units(piece[0]), cx, height / 2f - units(piece[1]),
                        w, Color.withAlpha(colour, colour.a() * a));
            }
        }
    }

    /**
     * How a line is cut up: the box in one piece, then out to the sheet's edge in {@link #PIECES} either
     * side. Computed once — every line on the sheet is cut the same way, because the pieces are where the
     * fade is sampled and not anything about a particular line.
     */
    private static final double[][] CUTS = cuts();

    private static double[][] cuts() {
        double inner = Geometry.BOX;
        double outer = Geometry.REACH * Geometry.BOX;
        double[][] out = new double[1 + 2 * PIECES][];
        out[0] = new double[]{-inner, inner};
        double step = (outer - inner) / PIECES;
        for (int i = 0; i < PIECES; i++) {
            out[1 + i] = new double[]{inner + i * step, inner + (i + 1) * step};
            out[1 + PIECES + i] = new double[]{-(inner + i * step), -(inner + (i + 1) * step)};
        }
        return out;
    }

    private static double mid(double[] piece) {
        return (piece[0] + piece[1]) / 2;
    }

    /**
     * How much of a line survives at a point on the sheet: all of it near the plot, none of it at the edge.
     *
     * <p>Radial rather than per axis, because the picture it has to make is a plane running away in every
     * direction and not a square mat with soft sides. Smoothstepped for the reason any fade is: a linear ramp
     * has a corner at each end, and the eye finds the corner and reads it as the edge the fade was there to
     * hide.
     */
    private static float alpha(double x, double y) {
        double r = Math.hypot(x, y) / Geometry.BOX;
        if (r <= FADE_FROM) {
            return 1;
        }
        if (r >= Geometry.REACH) {
            return 0;
        }
        double t = (r - FADE_FROM) / (Geometry.REACH - FADE_FROM);
        return (float) (1 - t * t * (3 - 2 * t));
    }

    /** A distance in the box's coordinates, as canvas units. The panel's scale, read the other way. */
    private static float units(double world) {
        return (float) (world / UNITS_PER_PIXEL);
    }
}
