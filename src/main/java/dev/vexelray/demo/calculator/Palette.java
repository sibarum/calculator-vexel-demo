package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;

/**
 * The calculator's colours, in one place because there is more than one window wearing them. The keypad, the
 * history and the definitions are the same application and have to look like it — a window drawn in its own
 * palette would read as a different program that happened to open.
 *
 * <p>Everything here carries hierarchy rather than meaning, with two exceptions at the bottom: {@link #REFUSED}
 * marks a line the engine would not read, and {@link #WASH} marks one that arrived from somewhere else.
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
     * <p>The only warm colour in the interface, which is what makes it legible against a scheme that is
     * otherwise entirely cool.
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
}
