package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Hex;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The palette fit, pinned.
 *
 * <p>A palette derived by fitting is exactly the kind of thing that drifts without anyone noticing: a handful of
 * anchors and two rates produce every colour in the application, so a nudge to {@code STEP} to make one panel
 * look right moves four other things a shade and nothing fails.
 *
 * <p>Assertions are in <b>Oklab lightness and chroma</b> rather than in hex, because those are the two axes a
 * reader would describe — "too dark", "too blue" — and because a hex comparison would fail on a rounding change
 * that nobody could see.
 */
class LookTest {

    /** How far a resolved colour may sit from its authored value in lightness. */
    private static final double L_TOLERANCE = 0.011;

    /** And in chroma. Tight, because this is the axis the framework's ink ramp cannot follow (see {@link #theInkRampCannotReachTheProtoypesGreys}). */
    private static final double C_TOLERANCE = 0.004;

    /** Lightness only — for the levels where the framework's colour model has an opinion about chroma. */
    private static void assertLightness(String authored, Color got, String what) {
        Oklab want = Oklab.of(Hex.parse(authored).orElseThrow());
        Oklab is = Oklab.of(got);
        assertTrue(Math.abs(want.l() - is.l()) <= L_TOLERANCE,
                () -> String.format("%s: wanted %s (L=%.4f), got %s (L=%.4f)",
                        what, authored, want.l(), Hex.format(got), is.l()));
    }

    private static void assertLandsOn(String authored, Color got, String what) {
        Oklab want = Oklab.of(Hex.parse(authored).orElseThrow());
        Oklab is = Oklab.of(got);
        assertTrue(Math.abs(want.l() - is.l()) <= L_TOLERANCE && Math.abs(want.chroma() - is.chroma()) <= C_TOLERANCE,
                () -> String.format("%s: wanted %s (L=%.4f C=%.4f), got %s (L=%.4f C=%.4f)",
                        what, authored, want.l(), want.chroma(), Hex.format(got), is.l(), is.chroma()));
    }

    @Test
    @DisplayName("the page is the authored colour exactly — it is the anchor, not a derivation")
    void pageIsExact() {
        assertEquals("#161826", Hex.format(Look.PALETTE.surface(0)));
    }

    @Test
    @DisplayName("the surface ladder lands on the prototype's three surfaces, in lightness")
    void surfaceLadder() {
        assertLightness("#1d2030", Look.PALETTE.surface(1), "CHROME");
        assertLightness("#232532", Look.PALETTE.surface(2), "PANEL");
        assertLightness("#2a2d3c", Look.PALETTE.surface(3), "RAISED");
    }

    /**
     * The second half of the same divergence as {@link #theInkRampCannotReachTheProtoypesGreys}, at the other
     * end of the palette.
     *
     * <p>{@link Oklab#atLightness} — which every rung of the surface ladder goes through — scales chroma
     * <b>in proportion to lightness</b>, so a ladder climbing away from a tinted page grows more saturated as it
     * rises. The prototype's surfaces hold a roughly constant tint instead, so the panel comes out about
     * {@code 0.010} bluer than authored.
     *
     * <p>It is fixable, and the fix was measured and rejected: dropping the page anchor's chroma from
     * {@code 0.0278} to {@code 0.0195} lands the panel exactly and makes {@link Role#PAGE} — the largest area in
     * the application, and the colour the march clears its sky to — no longer the authored one. An exact page
     * under a slightly blue panel is the better trade than the reverse.
     */
    @Test
    @DisplayName("...but climbs in chroma as it goes, by a known amount — FN-15, quantified")
    void theSurfaceLadderGainsChromaAsItClimbs() {
        double page = Oklab.of(Look.PALETTE.surface(0)).chroma();
        double panel = Oklab.of(Look.PALETTE.surface(2)).chroma();
        double authored = Oklab.of(Hex.parse("#232532").orElseThrow()).chroma();

        assertTrue(panel > page, "the ladder climbs in chroma; if it stopped, atLightness changed");
        double overshoot = panel - authored;
        assertTrue(overshoot > 0.006 && overshoot < 0.014,
                () -> String.format("the panel's chroma overshoot is a known %.4f; it is now %.4f — an anchor moved",
                        0.010, overshoot));
    }

    @Test
    @DisplayName("the calculator's own greys are exact")
    void greys() {
        assertLandsOn("#75798c", Look.QUIET.of(Look.PALETTE), "QUIET");
        assertLandsOn("#595d6c", Look.EDGE.of(Look.PALETTE), "EDGE");
        assertLandsOn("#3f424d", Look.LINE.of(Look.PALETTE), "LINE");
    }

    @Test
    @DisplayName("the accent family is exact")
    void accentFamily() {
        assertLandsOn("#9184d9", Role.ACCENT.of(Look.PALETTE), "ACCENT");
        assertLandsOn("#b5abfc", Look.ACCENT_LIGHT.of(Look.PALETTE), "ACCENT_LIGHT");
        assertLandsOn("#d2cefd", Look.ACCENT_BRIGHT.of(Look.PALETTE), "ACCENT_BRIGHT");
        assertLandsOn("#2b2741", Look.ACCENT_SURFACE.of(Look.PALETTE), "ACCENT_SURFACE");
        assertLandsOn("#5d5294", Look.ACCENT_LINE.of(Look.PALETTE), "ACCENT_LINE");
    }

    @Test
    @DisplayName("primary ink is exact — the reason the divergence below was accepted rather than fitted away")
    void inkIsExact() {
        assertLandsOn("#e9e9ed", Look.PALETTE.text(0), "INK");
    }

    /**
     * The known divergence, asserted in both directions.
     *
     * <p>Framework widgets resolve their secondary text through {@code Palette.text(n)}, which blends the ink
     * towards the page — so its chroma is bounded by those two endpoints and, from a neutral ink, it can only
     * produce neutral greys. The prototype's are cool at every lightness. This pins <b>the lightness as correct
     * and the chroma as short by a known amount</b>: a change that improved either would fail here and should,
     * because it would mean an anchor moved.
     */
    @Test
    @DisplayName("the ink ramp reaches the prototype's grey lightnesses but not its chroma — FN-15, quantified")
    void theInkRampCannotReachTheProtoypesGreys() {
        record Grey(String authored, Color got, String what) { }
        var greys = new Grey[]{
                new Grey("#b2b6ca", Look.PALETTE.text(1), "DIM"),
                new Grey("#9397ab", Look.PALETTE.text(2), "FAINT"),
        };
        for (Grey g : greys) {
            Oklab want = Oklab.of(Hex.parse(g.authored()).orElseThrow());
            Oklab is = Oklab.of(g.got());
            assertTrue(Math.abs(want.l() - is.l()) <= L_TOLERANCE,
                    () -> g.what() + ": lightness should still be right, got " + Hex.format(g.got()));
            double shortfall = want.chroma() - is.chroma();
            assertTrue(shortfall > 0.010 && shortfall < 0.020,
                    () -> String.format("%s: the chroma shortfall is a known %.4f; it is now %.4f — an anchor moved",
                            g.what(), 0.017, shortfall));
        }
    }

    @Test
    @DisplayName("sRGB reaches the march as linear — the conversion that has no type to enforce it")
    void sceneColourIsLinearised() {
        // Mid grey is the case that shows it: 0.5 sRGB is 0.214 linear, and passing it through unconverted is
        // the mistake this method exists to prevent. A component that came back as 0.5 would mean the call is a
        // no-op, which is exactly how a washed-out plot happens.
        var rgb = Look.linear(new Color(0.5f, 0.5f, 0.5f, 1f));
        assertEquals(0.2140, rgb.r(), 0.001, "sRGB 0.5 is 0.214 in linear light");
        assertEquals(rgb.r(), rgb.g(), 1e-9);
        assertEquals(rgb.r(), rgb.b(), 1e-9);
    }
}
