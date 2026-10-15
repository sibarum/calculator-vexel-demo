package dev.vexelray.demo.calculator;

/**
 * The rectangle of plot space on show: x from {@code xLo} to {@code xHi} across, y from {@code yLo} at the bottom to
 * {@code yHi} at the top. Pure, and in plot units throughout; a box's pixels come in only as arguments, so the same
 * view draws into a box of any size.
 *
 * <p>Every way the explorer moves — a drag, the wheel, a key, a dragged rectangle — is one of the three operations
 * here, so the rules about how far a view may go are in one place: a span is never narrower than {@link #NARROWEST}
 * nor wider than {@link #WIDEST}, which keeps a sample step a decimal the engine can read and a view the doubles can
 * still tell apart.
 */
record View(double xLo, double xHi, double yLo, double yHi) {

    static final double NARROWEST = 1e-9;
    static final double WIDEST = 1e9;

    View {
        if (!(xHi > xLo) || !(yHi > yLo)) {
            throw new IllegalArgumentException("a view has to have some size: " + xLo + ".." + xHi
                    + " by " + yLo + ".." + yHi);
        }
    }

    double width() {
        return xHi - xLo;
    }

    double height() {
        return yHi - yLo;
    }

    /** Where {@code x} lands across a box {@code w} pixels wide. */
    double px(double x, double w) {
        return (x - xLo) / width() * w;
    }

    /** Where {@code y} lands down a box {@code h} pixels tall. */
    double py(double y, double h) {
        return (yHi - y) / height() * h;
    }

    /** The x at {@code px} pixels across a box {@code w} wide. */
    double x(double px, double w) {
        return xLo + px / w * width();
    }

    /** The y at {@code py} pixels down a box {@code h} tall. */
    double y(double py, double h) {
        return yHi - py / h * height();
    }

    /** Moved by {@code dx} and {@code dy} in plot units. */
    View panned(double dx, double dy) {
        return new View(xLo + dx, xHi + dx, yLo + dy, yHi + dy);
    }

    /**
     * Each span multiplied by its factor, about {@code (x, y)}, which stays where it is: under the pointer, for the
     * wheel. A factor under one zooms in. A span already at its limit stays there.
     */
    View zoomed(double fx, double fy, double x, double y) {
        double[] across = scale(xLo, xHi, fx, x);
        double[] up = scale(yLo, yHi, fy, y);
        return new View(across[0], across[1], up[0], up[1]);
    }

    /** Equal axes in a {@code w × h} box: y rescaled about its middle so a unit up is as long as a unit across. */
    View aspect(double w, double h) {
        if (w <= 0 || h <= 0) return this;
        double half = width() / w * h / 2;
        double mid = (yLo + yHi) / 2;
        return new View(xLo, xHi, mid - half, mid + half);
    }

    /** The same x, with y as given. */
    View withY(double lo, double hi) {
        return new View(xLo, xHi, lo, hi);
    }

    /** Whether {@code x} is on show. */
    boolean showsX(double x) {
        return x >= xLo && x <= xHi;
    }

    private static double[] scale(double lo, double hi, double f, double at) {
        double span = hi - lo;
        double next = Math.clamp(span * f, NARROWEST, WIDEST);
        double k = next / span;
        return new double[]{at - (at - lo) * k, at + (hi - at) * k};
    }
}
