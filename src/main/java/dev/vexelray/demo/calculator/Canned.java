package dev.vexelray.demo.calculator;

import java.util.Locale;

/**
 * <b>The whole of the fake.</b> Scene data until there is an evaluator.
 *
 * <p>The algebra this calculator is for is not ready, so nothing here evaluates anything: {@link #read} is a
 * table of recognised expressions and {@link #curve} is a parametric form. Both exist so that the rest of the
 * application can be built, looked at and driven against a picture that behaves like a real one — and so that
 * switching the real thing on is <b>deleting this file</b> and satisfying the same two signatures, rather than
 * threading a new dependency through the view.
 *
 * <h2>Parametric, not frozen — and why that is the safer fake</h2>
 *
 * <p>The shapes are hardcoded; what the panels do to them is live. A slider that moves nothing cannot tell you
 * whether it feels right, and "the controls are wired but the picture is a photograph" is a state the
 * application would have to be taken out of later anyway. So {@code ω}, the domain and the sample count reach
 * the geometry, and nothing else does.
 *
 * <h2>The one rule this file must never break</h2>
 *
 * <p><b>A picture of a different expression than the one in the field is the one outcome that is not allowed.</b>
 * An unrecognised expression therefore does not quietly draw the default: it draws the default <em>and says
 * so</em>, through {@link Reading#refusal()}, and the subtitle always describes what is actually on screen. A
 * plot that is confidently wrong is worse than no plot, and it is the failure mode a fake invites.
 */
final class Canned {

    /** What the prototype opens with. */
    static final String DEFAULT_EXPRESSION = "e^(i*w*x/2)";

    /** How an expression wants to be drawn. Detected, in the real thing; looked up, here. */
    enum Mode {
        /** One real input, complex output: a path through space. */
        LINE,
        /** Two real inputs, real output: a height field. */
        SURFACE
    }

    /** The canned shapes. Not a general vocabulary — two entries, because the table has two rows. */
    enum Shape {
        /** {@code x → (cos, sin)} — the space curve the prototype opens on. */
        HELIX,
        /** {@code (x, y) → sin·cos} — a ripple, for the two-variable case. */
        RIPPLE
    }

    /**
     * What reading an expression produced.
     *
     * @param mode     which of the two kinds of plot this is
     * @param subtitle the line under the field — always a description of <em>what is drawn</em>
     * @param refusal  {@code null} when the expression was understood; otherwise what to tell the user, in
     *                 which case the picture is the reference scene and the message says so
     */
    record Reading(Mode mode, Shape shape, String subtitle, String refusal) {

        boolean understood() {
            return refusal == null;
        }
    }

    // "x -> (Re, Im)", not "x → (Re, Im)". The atlas asks for the arrows block and Noto Sans does not cover it,
    // so U+2192 is one of the 1112 glyphs that is not there and draws as a box. See framework-notes.md FN-5.
    private static final Reading HELIX = new Reading(Mode.LINE, Shape.HELIX,
            "complex output · space curve  x -> (Re, Im)", null);

    private static final Reading RIPPLE = new Reading(Mode.SURFACE, Shape.RIPPLE,
            "real output over x, y · height field", null);

    /**
     * Read an expression.
     *
     * <p>Whitespace and case are ignored and {@code ω} is accepted for {@code w}, because the field is the only
     * way an expression arrives and a user pasting from the design should not be told their own notation is
     * unrecognised.
     */
    static Reading read(String expression) {
        String s = expression == null ? "" : expression
                .replace("·", "*")     // the prototype writes multiplication as a middle dot
                .replace("ω", "w")     // and omega as omega
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);

        if (s.equals("e^(i*w*x/2)") || s.equals("e^(iwx/2)")) {
            return HELIX;
        }
        if (s.equals("sin(x)*cos(y)")) {
            return RIPPLE;
        }
        return new Reading(HELIX.mode(), HELIX.shape(), HELIX.subtitle(),
                s.isEmpty()
                        ? "type an expression — showing the reference scene"
                        : "no evaluator yet — showing the reference scene, not this expression");
    }

    /**
     * The curve, as interleaved {@code x, y, z} in world space.
     *
     * <p>Pure, allocation-per-call, and cheap enough to be so: it is a few hundred sine calls and it runs on a
     * worker whenever the expression or the domain changes, never per frame. Nothing about the camera reaches
     * it, which is the property that makes an orbit free.
     *
     * @param samples how many points; the caller has already clamped this to the buffer's ceiling
     */
    static double[] curve(Reading reading, double omega, double x0, double x1, int samples) {
        int n = Math.max(2, samples);
        double[] xyz = new double[n * 3];
        for (int i = 0; i < n; i++) {
            double t = i / (double) (n - 1);
            double x = x0 + (x1 - x0) * t;
            double phase = omega * x * 0.5;
            xyz[i * 3] = x;
            xyz[i * 3 + 1] = Math.cos(phase);
            xyz[i * 3 + 2] = Math.sin(phase);
        }
        return xyz;
    }

    private Canned() {
    }
}
