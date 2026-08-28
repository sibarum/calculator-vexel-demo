package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;

/**
 * The calculator's colours, in one place because there is more than one window wearing them. The keypad, the
 * history and the plot are the same application and have to look like it — a plot drawn in its own palette
 * would read as a different program that happened to open.
 *
 * <p>The three plot colours at the bottom are the only ones that carry meaning rather than hierarchy, and the
 * meaning is the point of the whole plotter: {@link #CURVE} is a stretch the arithmetic bounded, {@link #POLE}
 * is a column it could not, and they are deliberately different hues rather than two shades of one. A reader
 * has to be able to tell "the curve is here" from "there is detail here finer than a pixel" at a glance, since
 * conflating exactly those two is what point sampling does wrong.
 */
final class Palette {

    private Palette() {
    }

    static final Color BG = Color.rgb(0x11141b);
    static final Color PANEL = Color.rgb(0x1b2130);
    static final Color PANEL_HOVER = Color.rgb(0x232a3d);
    static final Color PANEL_PRESSED = Color.rgb(0x151a26);
    static final Color LINE = Color.rgb(0x2b3346);
    static final Color BTN_BLUE = Color.rgb(0x2668b3);
    static final Color BTN_BLUE_HOVER = Color.rgb(0x2f78c9);
    static final Color BTN_BLUE_PRESSED = Color.rgb(0x1d548f);
    static final Color INK = Color.rgb(0xeef2f8);
    static final Color DIM = Color.rgb(0x93a0b4);

    /**
     * What a refusal is marked in -- the ring around an entry the engine would not read.
     *
     * <p>The same red {@link #MARK_POLE} gives a pole, deliberately: one red in this application rather than
     * two, since both of them mean the same thing to the eye. It is the only warm colour in the interface,
     * which is what makes it legible against a scheme that is otherwise entirely cool.
     */
    static final Color REFUSED = Color.rgb(0xff6b4d);

    /**
     * What a wash over the entry is tinted with when a line arrives in it from another window.
     *
     * <p>{@link #BTN_BLUE_HOVER}, at an alpha chosen so the tint reads and the expression underneath stays
     * legible. A wash takes the colour it is given <em>at the alpha it is given</em> and rises to it, so
     * handing it an opaque accent -- the obvious thing, since that is the accent -- covers the entry
     * completely at the peak and hides the very line the wash exists to point at.
     */
    static final Color WASH = Color.rgba(0.184f, 0.471f, 0.788f, 0.30f);

    /** The plot's own ground: a shade darker than a panel, so the curve on it is the brightest thing there. */
    static final Color PLOT_BG = Color.rgb(0x0c0f15);

    /** A gridline. Present enough to read a value off, faint enough never to be mistaken for the curve. */
    static final Color GRID = Color.rgb(0x1e2534);

    /** The two axes, one step up from the grid — they are gridlines that mean something. */
    static final Color AXIS = Color.rgb(0x3a4560);

    /** A bounded column: the curve. */
    static final Color CURVE = Color.rgb(0x5ab0ff);

    /** A column the arithmetic could not bound — a pole, or detail finer than a pixel. */
    static final Color POLE = Color.rgba(1f, 0.42f, 0.30f, 0.85f);

    // --- landmarks ---------------------------------------------------------------------------------------
    //
    // Four colours for six kinds, because the pairs that share one are the pairs a reader groups anyway: a
    // minimum and a maximum are both turning points and are told apart by which way the curve goes there, and a
    // root and a y-intercept are both axis crossings. The dots are deliberately unlabelled, so the palette is
    // carrying the only distinction drawn on the picture -- everything else is a hover away.

    /** A crossing of an axis: a root, or the y-intercept. */
    static final Color MARK_CROSSING = Color.rgb(0x7fe08a);

    /** A turning point, minimum or maximum. */
    static final Color MARK_TURNING = Color.rgb(0xffd166);

    /** An inflection: quieter than a turning point, because it is a subtler fact. */
    static final Color MARK_BEND = Color.rgb(0xb08cff);

    /** A vertical asymptote's marker, in the same hue as the painted columns it stands on. */
    static final Color MARK_POLE = Color.rgb(0xff6b4d);

    /** A landmark's halo, so a dot is legible over a curve of the same brightness. */
    static final Color MARK_HALO = Color.rgba(0.05f, 0.06f, 0.09f, 0.85f);

    /** The tooltip's ground: opaque, because it is read over whatever it happens to cover. */
    static final Color TIP_BG = Color.rgb(0x1e2534);

    // --- the surface -------------------------------------------------------------------------------------
    //
    // A surface is read by comparing heights, so height is what the colour carries: a ramp from the floor of the
    // volume to its ceiling, dimmed with distance so the far side of the grid recedes. The ramp runs cool to
    // warm rather than dark to light, since a dark-to-light ramp and the depth dimming would be the same signal
    // twice and a reader could not tell a low near cell from a high far one.

    /** The bottom of the height ramp. */
    static final Color LOW = Color.rgb(0x2f6fd0);

    /** The middle of it. */
    static final Color MID = Color.rgb(0x46c2b0);

    /** The top. */
    static final Color HIGH = Color.rgb(0xffd074);

    /**
     * The two stops of the surface's procedural environment — the light a cell reflects when it is tilted
     * skyward, and the dimmer one it reflects when it is edge-on.
     *
     * <p>There is no environment map and no way to upload one, so the reflection {@link Sheen} computes indexes
     * these two by the vertical component of the reflected direction, and they are <b>added</b> to the height
     * ramp rather than mixed with it. Both are cool, because a warm reflection over a warm ramp would read as
     * the ramp having changed rather than as light on it.
     *
     * <p>The horizon stop is the one that does the visible work. It is what a grazing cell catches, which is to
     * say it is the rim; too dark and the effect vanishes, too bright and every steep cell — most of them, at
     * this pitch — turns pale and the height ramp goes with it.
     */
    static final Color ZENITH_LIGHT = Color.rgb(0xbcd8ff);

    /** What an edge-on cell catches. Dim, cool, and the whole of what makes the silhouette read as a rim. */
    static final Color HORIZON_LIGHT = Color.rgb(0x2c4a72);

    /**
     * A cell of a surface the arithmetic could not bound — the same hue as a curve's painted column, and much
     * more transparent.
     *
     * <p>The transparency is the difference between the two dimensions rather than a matter of taste. On a curve
     * an unbounded answer is one pixel wide and reads as a stripe; on a surface it is a box the full height of
     * the volume, wide enough to hide things. At an <em>isolated</em> pole — {@code 1÷(x²+y²)} at the origin —
     * a handful of cells painted through let the surface around them read.
     *
     * <p>Where the poles run in a <b>line</b> they still come out as a wall, and that is not a failure of the
     * colour: {@code 1÷(x+y)} leaves every bound along the whole diagonal {@code x = −y}, so forty cells stack
     * one behind another and the far half of the surface really is behind something infinitely tall. Turn the
     * picture to look along the ridge and both halves come back. The wall is the answer, not the rendering of
     * it.
     */
    static final Color SURFACE_POLE = Color.rgba(1f, 0.42f, 0.30f, 0.4f);

    /**
     * The edge of a proven enclosure, drawn as a hollow outline over the interpolated surface inside it.
     *
     * <p>Two layers because they say two different things. The interpolated surface is smooth and is a
     * <em>guess</em> -- bilinear between corners the arithmetic did evaluate, which is the same joining-up that
     * point sampling does and would be a lie on its own. The outline is the enclosure, and it is the claim: the
     * surface is somewhere inside this box. Drawing both keeps the picture readable without the reader having to
     * take the readable part on trust, and where the proof is loose -- a steep cell, a fold -- the outline stands
     * visibly taller than the smooth surface threading through it, which is exactly where a reader should look.
     */
    static final Color PROOF_EDGE = Color.rgba(0.62f, 0.72f, 0.88f, 0.11f);

    // --- the spiral --------------------------------------------------------------------------------------

    /**
     * The outward end of the spiral — what {@code ω} is drawn in, where {@link SpiralPlot} draws where it is.
     *
     * <p>It is the same red as {@link #MARK_POLE} and {@link #REFUSED}, and being the same is the whole reason
     * it is written here rather than picked as a fourth warm colour. A column a curve could not bound is a
     * column where the value ran to {@code ω}; a refused line is usually one with an {@code ω} in it; and the
     * spiral is the picture of where {@code ω} actually sits. One fact, one colour, in an interface that is
     * otherwise entirely cool.
     *
     * <p>The inward end needs no constant of its own. A zero is drawn in {@link #LOW}, which is where the
     * surface's height ramp starts as well, and in both pictures that end of the ramp means the same thing:
     * the bottom of whatever the picture is of.
     */
    static final Color SPIRAL_OMEGA = Color.rgb(0xff6b4d);
}
