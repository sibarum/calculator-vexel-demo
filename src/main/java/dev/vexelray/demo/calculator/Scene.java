package dev.vexelray.demo.calculator;

/**
 * Everything the user has chosen, as one immutable value.
 *
 * <h2>What is here and what is not</h2>
 *
 * <p>This is the <em>settings</em>, not the state of the world. The camera's current yaw and pitch live on
 * {@link March} instead, and the split is deliberate rather than an oversight: those two change sixty times a
 * second during an orbit and everything here changes when somebody moves a control. Putting them together would
 * mean a new {@code State} version, a CAS and a listener fan-out per frame, for a value nothing but the shader
 * reads. What <em>is</em> here is {@link #spinning} — whether auto-orbit is on is a choice; where the camera got
 * to is not.
 *
 * <p>Nothing here is derived. {@link #reading} is stored rather than recomputed because it is the one thing that
 * must not disagree with the picture: the badge, the subtitle, the error line and the geometry all read this one
 * field, so they cannot describe different expressions. Recomputing it per consumer is exactly how they drift.
 *
 * @param expression what is in the field, as typed
 * @param reading    what {@link Algebra} made of it — the single source for mode, subtitle and refusal
 * @param omega      the one free parameter the prototype exposes
 * @param x0         domain, low
 * @param x1         domain, high
 * @param samples    points along the curve; clamped against the cone budget before it reaches the buffer
 * @param lineWidth  the curve's radius in world units — see {@link Geometry} on why this is not pixels
 * @param furniture  axes, ticks, grid planes and divisions
 * @param ramp       which colour map the curve is drawn in
 * @param cards      which layer cards are switched on and which are unfolded
 * @param cropping   whether the crop handles are showing
 * @param spinning   whether auto-orbit is running
 */
record Scene(String expression, Algebra.Reading reading,
             double omega, double x0, double x1, int samples,
             double lineWidth, Geometry.Furniture furniture, Ramp ramp,
             Cards cards, boolean cropping, boolean spinning) {

    /** What the plot opens on: the prototype's default expression, domain and sampling. */
    static Scene initial() {
        return new Scene(Algebra.DEFAULT_EXPRESSION, Algebra.read(Algebra.DEFAULT_EXPRESSION),
                4, -6, 6, 420,
                0.045, Geometry.Furniture.DEFAULT, Ramp.BLURPLE,
                Cards.DEFAULT, false, false);
    }

    /**
     * A one-field change.
     *
     * <p>A record with twelve components has a twelve-argument constructor, and a canonical call spelled out at
     * every call site is how a field ends up in the wrong slot — two {@code double}s adjacent in the signature
     * is all it takes, and the compiler cannot help. So a change is a short-lived mutable copy with named
     * setters, and the twelve-argument call appears exactly once, in {@link Draft#done()}.
     *
     * <p>Pure, as {@code State.commit} requires: the draft is created inside the call and never escapes, so a
     * CAS retry re-runs it against the newer value rather than replaying a stale one.
     */
    static Scene with(Scene s, java.util.function.Consumer<Draft> change) {
        Draft d = new Draft(s);
        change.accept(d);
        return d.done();
    }

    /** A mutable copy of a {@link Scene}, alive only for the length of one {@link #with}. */
    static final class Draft {
        private final String expression;
        private final Algebra.Reading reading;
        private double omega;
        private double x0;
        private double x1;
        private int samples;
        private double lineWidth;
        private Geometry.Furniture furniture;
        private Ramp ramp;
        private Cards cards;
        private boolean cropping;
        private boolean spinning;

        private Draft(Scene s) {
            expression = s.expression();
            reading = s.reading();
            omega = s.omega();
            x0 = s.x0();
            x1 = s.x1();
            samples = s.samples();
            lineWidth = s.lineWidth();
            furniture = s.furniture();
            ramp = s.ramp();
            cards = s.cards();
            cropping = s.cropping();
            spinning = s.spinning();
        }

        // The expression and its reading are deliberately not settable here: they change together or not at
        // all, which is what Model.submit is for. A draft that could set one without the other would be the
        // one way to make the badge disagree with the picture.

        Draft omega(double v) {
            omega = v;
            return this;
        }

        Draft x0(double v) {
            x0 = v;
            return this;
        }

        Draft x1(double v) {
            x1 = v;
            return this;
        }

        Draft samples(int v) {
            samples = v;
            return this;
        }

        Draft lineWidth(double v) {
            lineWidth = v;
            return this;
        }

        Draft furniture(java.util.function.UnaryOperator<Geometry.Furniture> f) {
            furniture = f.apply(furniture);
            return this;
        }

        Draft ramp(Ramp v) {
            ramp = v;
            return this;
        }

        Draft cards(Cards v) {
            cards = v;
            return this;
        }

        Draft cropping(boolean v) {
            cropping = v;
            return this;
        }

        Draft spinning(boolean v) {
            spinning = v;
            return this;
        }

        private Scene done() {
            return new Scene(expression, reading, omega, x0, x1, samples, lineWidth, furniture, ramp,
                    cards, cropping, spinning);
        }
    }

    /** Which layer cards are on, and which are open. The prototype's LAYERS panel is five of these. */
    record Cards(boolean line, boolean surface, boolean volume, boolean axes, boolean grid,
                 boolean lineOpen, boolean axesOpen, boolean gridOpen) {

        static final Cards DEFAULT = new Cards(true, false, false, true, true, true, false, false);
    }

    // The prototype's LAYERS panel puts an AUTO badge on whichever layer the expression asked for. It is a
    // reading of the mode rather than a sixth piece of state, which is what keeps it from ever being wrong.

    /** Whether {@code layer} is the one the expression called for. */
    boolean isAuto(String layer) {
        return switch (reading.mode()) {
            case CURVE -> layer.equals("line");
            // A single value is a marker standing in the box rather than anything drawn on a plane, so the
            // layer it asks for is the volume. It is still the AUTO badge answering "which layer did the
            // expression call for", which is the reading this is, and not a claim that anything is filled.
            case POINT -> layer.equals("volume");
            case SURFACE -> layer.equals("surface");
        };
    }

    /** How many layers are switched on — the panel header's {@code n on}. */
    int layersOn() {
        int n = 0;
        if (cards.line()) {
            n++;
        }
        if (cards.surface()) {
            n++;
        }
        if (cards.volume()) {
            n++;
        }
        if (cards.axes()) {
            n++;
        }
        if (cards.grid()) {
            n++;
        }
        return n;
    }

    /**
     * The furniture as the layer switches leave it.
     *
     * <p>A layer being switched off is not the same as its settings being cleared — the prototype's cards dim
     * their rows rather than forgetting them, so turning grid back on brings back the planes it had. So the
     * switches are applied here, on the way to the renderer, and {@link #furniture} keeps what was chosen.
     */
    Geometry.Furniture effectiveFurniture() {
        return new Geometry.Furniture(
                cards.axes() && furniture.axes(),
                furniture.ticks(),
                cards.grid() && furniture.gridXY(),
                cards.grid() && furniture.gridXZ(),
                cards.grid() && furniture.gridYZ(),
                furniture.divisions());
    }
}
