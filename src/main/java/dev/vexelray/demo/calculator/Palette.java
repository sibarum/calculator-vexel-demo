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
}
