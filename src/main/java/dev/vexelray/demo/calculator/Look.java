package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Palette;
import dev.vexelray.gui.core.style.Relief;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.core.style.Shading;
import dev.vexelray.gui.core.style.Theme;
import dev.vexelray.surface.Surface;

/**
 * The prototype's palette, as anchors — and the roles the framework does not already name.
 *
 * <h2>What the fit found</h2>
 *
 * <p>{@code docs/plot-viewport.html} authors fifteen colours. Measured in Oklab they are not fifteen decisions,
 * but they are not one construction either. They are <b>three families</b>:
 *
 * <ul>
 *   <li><b>Surfaces</b> — {@code #161826 → #1d2030 → #232532 → #2a2d3c} — are an even ladder, and a
 *       {@link Palette} step of {@code 0.0271} lands {@link Role#PANEL} within {@code 0.0008} of its authored
 *       lightness. This family is exactly what the framework's model is for.</li>
 *   <li><b>Ink</b> — {@code #e9e9ed}, essentially neutral (chroma {@code 0.005}).</li>
 *   <li><b>Greys</b> — {@code #b2b6ca → #9397ab → #75798c → #595d6c → #3f424d} — hold a constant cool tint
 *       (chroma {@code 0.019–0.030}, hue {@code −83°} to {@code −87°}) at every lightness.</li>
 * </ul>
 *
 * <h2>The divergence, measured rather than absorbed</h2>
 *
 * <p><b>The last two families are not one family, and {@link Palette} has one anchor for both.</b>
 * {@link Palette#text(int)} blends the ink towards the page, so its chroma is bounded by its two endpoints —
 * from a neutral ink it can only produce neutral greys. Reproducing the prototype's greys exactly needs an ink
 * anchor at chroma {@code 0.029}, and that ink renders {@code #e4e8fe}: primary text goes visibly periwinkle to
 * make secondary text the right shade of blue. Measured, both ways, before choosing.
 *
 * <p>So the ink stays neutral and correct, {@link Role#DIM} and {@link Role#FAINT} land on the right
 * <em>lightness</em> and about {@code 0.017} short of the right chroma, and the calculator's own greys —
 * {@link #QUIET}, {@link #EDGE}, {@link #LINE}, none of which the framework names — are declared at their
 * measured values. {@code LookTest} pins the gap rather than hiding it, so it is a known quantity and not a
 * surprise. See {@code docs/framework-notes.md}, FN-15.
 *
 * <p>The accent tints are declared for a different reason: they are hand-picked rather than constructed.
 * {@link Oklab#atLightness} scales chroma with lightness, so it makes {@code #d2cefd} far too saturated
 * ({@code 0.088} against an authored {@code 0.065}); a blend towards the ink makes it too pale. Neither is
 * wrong — the prototype's tints simply are not on either curve.
 */
final class Look {

    // ---------------------------------------------------------------- anchors

    /** {@code #161826} — the page, and the colour the march clears its sky to. */
    private static final Oklab PAGE = Oklab.polar(0.2141, 0.0278, -82.48);

    /** {@code #e9e9ed}. Neutral, deliberately: see the class note. */
    private static final Oklab INK = Oklab.polar(0.9352, 0.0054, -73.70);

    /** {@code #9184d9} — the blurple everything chromatic in this UI is made of. */
    private static final Oklab ACCENT = Oklab.polar(0.6600, 0.1245, -70.45);

    /** {@code #5d5294} — the prototype's accent border, which is also the right fill for a filled control. */
    private static final Oklab ACTION = Oklab.polar(0.4801, 0.1041, -70.46);

    /**
     * The prototype declares nothing destructive — it has no destructive action. This is the accent's chroma
     * and a similar lightness taken round to red, so a confirmation dialog belongs to the same palette as
     * everything else rather than importing a stock danger colour.
     */
    private static final Oklab DANGER = Oklab.polar(0.5894, 0.1448, 18.40);

    /** Shadows are {@code rgba(0,0,0,.55)}–{@code .65} in the prototype: near-black, at the page's hue. */
    private static final Oklab DEPTH = Oklab.polar(0.1236, 0.0130, -86.65);

    /** Two steps from the page lands on the authored panel {@code #232532}. */
    private static final double STEP = 0.0271;

    /**
     * The ink ramp's rate. Fitted to the prototype's three text greys by least squares on lightness: it puts
     * {@code text(1..3)} at {@code t = 0.202, 0.364, 0.492} against an authored {@code 0.216, 0.354, 0.494}.
     * The lightnesses are right to within {@code 0.010}; the chroma is the divergence in the class note.
     */
    private static final double FADE = 0.202;

    /** The prototype's panels sit on {@code 0 6px 18px rgba(0,0,0,.55)} and {@code 0 16px 40px …/.65}. */
    private static final double SHADOW_ALPHA = 0.60;

    static final Palette PALETTE =
            new Palette(PAGE, STEP, INK, FADE, ACCENT, ACTION, DANGER, DEPTH, SHADOW_ALPHA);

    /**
     * Lit surfaces on, letterpress off.
     *
     * <p>The edge light is what gives a dark panel its glint and the prototype's cards have one. Letterpress is
     * the opposite call: it buys contrast for white-on-fill labels by spending crispness, and every label in
     * this UI is either small mono or a 9.5px letterspaced badge — the two kinds of text that can least afford
     * it.
     */
    static final Theme THEME = Theme.of(PALETTE, Shading.ON_DARK, Relief.STANDARD, true, false);

    // ------------------------------------------------------------- the cool greys

    /** {@code #75798c} — an icon at rest, and the faintest thing still meant to be seen. */
    static final Role QUIET = p -> Oklab.polar(0.5793, 0.0295, -83.95).toColor();

    /** {@code #595d6c} — a rule that has to read against a panel rather than against the page. */
    static final Role EDGE = p -> Oklab.polar(0.4803, 0.0246, -86.36).toColor();

    /** {@code #3f424d} — every border, underline and separator in the prototype. */
    static final Role LINE = p -> Oklab.polar(0.3805, 0.0191, -86.60).toColor();

    // ------------------------------------------------------------ accent family

    /** {@code #b5abfc} — the accent as a label: a key name in the help list, an active icon. */
    static final Role ACCENT_LIGHT = p -> Oklab.polar(0.7798, 0.1144, -70.88).toColor();

    /** {@code #d2cefd} — the accent at its brightest: a selected icon, the value line in a probe. */
    static final Role ACCENT_BRIGHT = p -> Oklab.polar(0.8699, 0.0647, -71.12).toColor();

    /** {@code #2b2741} — the surface under a selected rail icon, and behind an error line. */
    static final Role ACCENT_SURFACE = p -> Oklab.polar(0.2898, 0.0470, -69.80).toColor();

    /** {@code #5d5294} — the border of anything the accent has claimed: a badge, an outline button. */
    static final Role ACCENT_LINE = p -> p.action().toColor();

    /** A wash of the accent, for the hover of an outline button. */
    static final Role ACCENT_WASH = p -> p.accent().toColor(0.14f);

    // ------------------------------------------------------------- into the scene

    /**
     * A colour as the march sees it — <b>display components, passed straight through</b>.
     *
     * <p>{@link Surface.Rgb} documents itself as linear, and for its <em>arithmetic</em> it is: shading
     * multiplies albedo by light, which is only correct in a linear space. But the composed fragment
     * <b>never encodes its result</b> — there is no OETF anywhere in {@code SdfComposer} or {@code Shadings},
     * and {@code SampledColorTarget}'s attachment is {@code R8G8B8A8_UNORM} rather than {@code _SRGB}, so
     * neither the shader nor the format applies one. Whatever the fragment writes lands in the texel verbatim
     * and the canvas samples it verbatim.
     *
     * <p>So a colour converted to linear on the way in comes out about 2.2× too dark, uniformly — which is
     * exactly what the first marched frame looked like: a near-black sky where the page should have been and a
     * curve two shades under its own accent. Passing display components makes the picture the colour it was
     * named, at the cost of shading that is not physically linear — a trade a plot can make without noticing
     * and a lit sphere could not. See {@code docs/framework-notes.md}, FN-19.
     */
    static Surface.Rgb scene(Color c) {
        return new Surface.Rgb(c.r(), c.g(), c.b());
    }

    /** {@code role}, as the march sees it. */
    static Surface.Rgb scene(Role role) {
        return scene(role.of(PALETTE));
    }

    private Look() {
    }
}
