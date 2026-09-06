package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.style.Oklab;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.surface.Surface;

import java.util.List;

/**
 * The colour maps the curve can be drawn in — the prototype's Blurple, Indigo, Steel and Ash.
 *
 * <h2>Today this picks one colour, not a gradient</h2>
 *
 * <p>{@code ConeField} shades everything with one albedo (FN-25), so {@link March} samples this ramp once and
 * builds a pipeline for that colour. The ramp is still the right shape to declare — the picker wants stops,
 * and the geometry will want the gradient the moment the buffer can carry it — but be clear that what reaches
 * the screen right now is {@code at(0.6)} and nothing else.
 *
 * <h2>Stops, not a gradient</h2>
 *
 * <p>A ramp is a handful of colours and a rule for reading between them. That shape is what
 * {@code Property.swatches} wants for the picker — it draws the stops side by side rather than blending them,
 * deliberately, since <em>which hues are in it</em> is what a reader is choosing between — and it is also what
 * {@link Surface.Stroke} wants, because a stroke gradients between its vertex colours on its own.
 *
 * <h2>Read in Oklab, always</h2>
 *
 * <p>{@link #at} interpolates perceptually. A blend through raw sRGB passes through a muddy middle, and a ramp
 * is precisely where that shows — it is the whole visible length of the thing. That rule comes with the
 * framework's colour type rather than being invented here.
 *
 * <h2>Three of the four are derived; one is not</h2>
 *
 * <p>Blurple, Indigo and Steel are the palette's own accent walked to its brightest at three chromas, so they
 * belong to the theme and would follow it. Ash is deliberately achromatic — a plot sometimes has to be read for
 * <em>shape</em> rather than for value, and a colour map that still carries hue is one more thing in the way.
 */
enum Ramp {

    /** The prototype's default: the accent, from its full chroma to its palest tint. */
    BLURPLE("Blurple", Role.ACCENT, Look.ACCENT_BRIGHT),

    /** Deeper and more saturated — the same hue with the dark end pushed down. */
    INDIGO("Indigo", p -> p.accent().atLightness(0.42).toColor(), Look.ACCENT_LIGHT),

    /** The accent's hue at half chroma: cooler, and closer to the chrome it sits among. */
    STEEL("Steel", p -> Oklab.polar(0.46, 0.055, -78).toColor(), p -> Oklab.polar(0.86, 0.028, -78).toColor()),

    /** No hue at all, for reading shape rather than value. */
    ASH("Ash", p -> p.text(3), p -> p.text(0));

    private final String label;
    private final Role low;
    private final Role high;

    Ramp(String label, Role low, Role high) {
        this.label = label;
        this.low = low;
        this.high = high;
    }

    String label() {
        return label;
    }

    /** The colour at {@code t} in {@code [0, 1]}, blended perceptually. */
    Color at(double t) {
        double clamped = Math.clamp(t, 0, 1);
        return Oklab.of(low.of(Look.PALETTE)).mix(Oklab.of(high.of(Look.PALETTE)), clamped).toColor();
    }

    /** The same colour as the march wants it — display components, per {@link Look#scene}. */
    Surface.Rgb scene(double t) {
        var rgb = Look.scene(at(t));
        return new Surface.Rgb(rgb.r(), rgb.g(), rgb.b());
    }

    /**
     * The stops the picker draws, low to high.
     *
     * <p>Five is enough to say what the map is and few enough that each stop is a legible band at the width the
     * panel gives it — the prototype's strips are 284px wide minus a label column.
     */
    List<Color> stops() {
        return List.of(at(0), at(0.25), at(0.5), at(0.75), at(1));
    }
}
