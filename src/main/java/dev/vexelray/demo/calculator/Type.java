package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.layout.Length;

/**
 * The two faces and the type scale, read off the prototype.
 *
 * <h2>Two faces, and the split is not decoration</h2>
 *
 * <p>The prototype sets Inter for labels and prose and IBM Plex Mono for every number, the expression itself,
 * and every badge. A monospace column is what makes a stack of readouts legible — {@code az 38  el 26} lines up
 * and {@code az 138 el 6} does not — and the badges are letterspaced small mono precisely so they read as
 * <em>state</em> rather than as text.
 *
 * <p><b>The atlas ships Noto Sans and Noto Sans Mono, not Inter and IBM Plex Mono.</b> The split is right and
 * the typefaces are not: the framework's atlas is baked at build time from the fonts in {@code vexelray-text},
 * and shadowing it needs the two font files and an {@code msdf} plugin run. Deferred, and visible — this is the
 * one part of the look that a capture will not match. See {@code docs/framework-notes.md}, FN-16.
 *
 * <h2>The scale</h2>
 *
 * <p>The prototype's pixel sizes are all {@code 1.4x} a round number ({@code 22.4, 16.8, 11.2, 8.4, 5.6, 2.8}),
 * which is what a design authored at one scale and exported at another looks like. They are kept as authored
 * rather than rounded, because rounding them would be a second, undocumented, design decision.
 *
 * <p><b>Type is {@code rem}; gutters are {@code dp}.</b> That is the framework's rule and it decides what zoom
 * does: {@code rem} grows with the user's zoom because it is proportional to text, and {@code dp} does not,
 * because tripling the frame around content you zoomed in to read means seeing less of it.
 */
final class Type {

    /** Face 0: Noto Sans, standing in for the prototype's Inter. Labels, names, prose. */
    static final int UI = 0;

    /** Face 1: Noto Sans Mono, standing in for IBM Plex Mono. Numbers, the expression, badges. */
    static final int MONO = 1;

    // ------------------------------------------------------------------ type

    /** 15px — the expression itself, the largest type in the UI. */
    static final Length EXPRESSION = Length.rem(0.9375f);

    /** 13.5px — the value line of a probe. */
    static final Length VALUE = Length.rem(0.84375f);

    /** 12px — a layer name. */
    static final Length NAME = Length.rem(0.75f);

    /** 11.5px — a property label. */
    static final Length LABEL = Length.rem(0.71875f);

    /** 11px — the {@code f =} prompt, an error line, a small label. */
    static final Length SMALL = Length.rem(0.6875f);

    /** 10.5px — the readout strip, the subtitle, a help key. */
    static final Length READOUT = Length.rem(0.65625f);

    /** 10px — a value in the inspector's right-hand column. */
    static final Length FIGURE = Length.rem(0.625f);

    /** 9.5px — a badge: {@code AUTO}, {@code CROP}, a section heading. */
    static final Length BADGE = Length.rem(0.59375f);

    // --------------------------------------------------------------- gutters

    /** 22.4px — the outer inset of the top-left cluster and the bottom-right readout. */
    static final Length EDGE_X = Length.dp(22.4f);

    /** 16.8px — the outer inset on the vertical, and the rail's own inset from the left. */
    static final Length EDGE_Y = Length.dp(16.8f);

    /** 11.2px — a panel's horizontal padding. */
    static final Length WIDE = Length.dp(11.2f);

    /** 8.4px — the standard gap between two things that belong together. */
    static final Length GAP = Length.dp(8.4f);

    /** 5.6px — a tight gap: rail icons, swatch rows. */
    static final Length TIGHT = Length.dp(5.6f);

    /** 2.8px — the tightest: between cards in a stack. */
    static final Length HAIR = Length.dp(2.8f);

    /** 1px — a border. Not scaled by zoom; a hairline is a hairline. */
    static final Length RULE = Length.dp(1);

    private Type() {
    }
}
