package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.draw.Picture;
import dev.vexelray.gui.draw.Sketch;

/**
 * The rail's eight marks, drawn rather than typed.
 *
 * <h2>Why these are geometry</h2>
 *
 * <p>The prototype uses Phosphor icons and there is nothing to substitute: the MSDF atlas holds 1112 glyphs and,
 * above U+2000, exactly two that are not punctuation, currency or Letterlike — {@code U+2212} and
 * {@code U+25CC}. Every geometric shape, arrow and mathematical operator a rail might want is absent, because
 * the charset asked for those blocks and Noto Sans does not cover them. So an icon here is a {@link Picture},
 * which is also what {@code TitleBar} does for its caption buttons and what automation.md §7 states as the rule:
 * <em>marks, not glyphs</em>.
 *
 * <p>It is not a hardship — a picture stays crisp at any zoom where a glyph is resampled, and the alphabet
 * (rounded box, its outline, a line at any angle, a circle) covers this vocabulary comfortably. It is simply
 * work every application on this framework has to repeat, which is {@code docs/framework-notes.md} FN-5.
 *
 * <h2>Authored in a unit square, drawn in a box</h2>
 *
 * <p>Each mark is described in {@code [0, 1]²} and scaled to the size asked for, so the rail can change its icon
 * size without any of these being re-authored. A picture is in the pixels of the box that measured it — the
 * renderer resolves no units of its own — so the scaling happens here, at build time, and the caller rebuilds
 * if the size changes.
 */
final class Icons {

    /** The prototype's rail buttons are 30px with a 16px mark inside. */
    static final double SIZE = 16;

    /** Stroke weight, at {@link #SIZE}. Thin enough to read as an icon, thick enough to survive the atlas. */
    private static final double W = 1.4;

    private Icons() {
    }

    /** Layers — three stacked plates, the top one drawn brightest. */
    static Picture layers(Color c) {
        Sketch s = new Sketch().tag("icon-layers");
        for (int i = 0; i < 3; i++) {
            double y = px(0.26 + i * 0.24);
            s.line(px(0.16), y, px(0.5), y - px(0.12), W, c);
            s.line(px(0.5), y - px(0.12), px(0.84), y, W, c);
            s.line(px(0.16), y, px(0.5), y + px(0.12), W, c);
            s.line(px(0.5), y + px(0.12), px(0.84), y, W, c);
        }
        return s.picture();
    }

    /** Domain and range — a dashed selection box, which is what "the region under consideration" looks like. */
    static Picture domain(Color c) {
        Sketch s = new Sketch().tag("icon-domain");
        double lo = px(0.18);
        double hi = px(0.82);
        for (double t = 0; t < 1; t += 0.25) {
            double a = lo + (hi - lo) * t;
            double b = a + (hi - lo) * 0.14;
            s.line(a, lo, b, lo, W, c);
            s.line(a, hi, b, hi, W, c);
            s.line(lo, a, lo, b, W, c);
            s.line(hi, a, hi, b, W, c);
        }
        return s.picture();
    }

    /** Crop — two overlapping right angles, the photographer's mark. */
    static Picture crop(Color c) {
        Sketch s = new Sketch().tag("icon-crop");
        s.line(px(0.28), px(0.1), px(0.28), px(0.72), W, c);
        s.line(px(0.28), px(0.72), px(0.9), px(0.72), W, c);
        s.line(px(0.1), px(0.28), px(0.72), px(0.28), W, c);
        s.line(px(0.72), px(0.28), px(0.72), px(0.9), W, c);
        return s.picture();
    }

    /** Colour — a disc split into quadrants, two filled. */
    static Picture colour(Color c) {
        Sketch s = new Sketch().tag("icon-colour");
        double r = px(0.34);
        s.ring(px(0.5), px(0.5), r, W, c);
        s.fill(px(0.5), px(0.5) - r, r, r, c);
        s.fill(px(0.5) - r, px(0.5), r, r, c);
        return s.picture();
    }

    /** Sampling — a three-by-three field of dots: a grid of samples, which is literally what it controls. */
    static Picture sampling(Color c) {
        Sketch s = new Sketch().tag("icon-sampling");
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                s.circle(px(0.24 + i * 0.26), px(0.24 + j * 0.26), px(0.075), c);
            }
        }
        return s.picture();
    }

    /** View — a cube in the same axonometric a reader expects a 3D view button to show. */
    static Picture view(Color c) {
        Sketch s = new Sketch().tag("icon-view");
        double cx = px(0.5);
        double top = px(0.12);
        double mid = px(0.34);
        double low = px(0.66);
        double bot = px(0.88);
        double left = px(0.14);
        double right = px(0.86);
        s.line(cx, top, right, mid, W, c);
        s.line(right, mid, right, low, W, c);
        s.line(right, low, cx, bot, W, c);
        s.line(cx, bot, left, low, W, c);
        s.line(left, low, left, mid, W, c);
        s.line(left, mid, cx, top, W, c);
        // The three edges that meet at the near corner -- without them it is a hexagon rather than a cube.
        s.line(left, mid, cx, low, W, c);
        s.line(right, mid, cx, low, W, c);
        s.line(cx, low, cx, bot, W, c);
        return s.picture();
    }

    /** Controls — a keyboard: an outline with three key rows suggested inside it. */
    static Picture keys(Color c) {
        Sketch s = new Sketch().tag("icon-keys");
        s.outline(px(0.08), px(0.24), px(0.84), px(0.52), px(0.08), W, c);
        for (int row = 0; row < 2; row++) {
            double y = px(0.38 + row * 0.16);
            for (int i = 0; i < 4; i++) {
                double x = px(0.2 + i * 0.16);
                s.line(x, y, x + px(0.06), y, W, c);
            }
        }
        s.line(px(0.32), px(0.64), px(0.68), px(0.64), W, c);
        return s.picture();
    }

    /** Reset view — a crosshair, the rail's one action rather than a panel. */
    static Picture crosshair(Color c) {
        Sketch s = new Sketch().tag("icon-crosshair");
        s.ring(px(0.5), px(0.5), px(0.26), W, c);
        s.line(px(0.5), px(0.06), px(0.5), px(0.26), W, c);
        s.line(px(0.5), px(0.74), px(0.5), px(0.94), W, c);
        s.line(px(0.06), px(0.5), px(0.26), px(0.5), W, c);
        s.line(px(0.74), px(0.5), px(0.94), px(0.5), W, c);
        return s.picture();
    }

    private static double px(double unit) {
        return unit * SIZE;
    }
}
