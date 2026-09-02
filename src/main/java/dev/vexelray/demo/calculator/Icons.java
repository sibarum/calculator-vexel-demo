package dev.vexelray.demo.calculator;

import dev.vexelray.os.Icon;
import dev.vexelray.os.NativePlatform;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The calculator's mark, and the one call that puts it on every window this process opens.
 *
 * <h2>Which mark</h2>
 * {@code a-curves} from vexelray-icons, hue {@code #2fc4d6}. None of the ten marks on that sheet is a
 * calculator, so this is the closest fit rather than a lookup: a curve is what a calculator is for, and it is
 * not the host's. {@code a-mainframe}
 * is the tempting one, being a body with a display band and two rows of keys, but MainFrame is the shell the
 * calculator runs <em>inside</em> (see {@link CalculatorDesktop}), and a guest wearing its host's mark makes
 * the two indistinguishable wherever windows are listed.
 *
 * <h2>Why four files and not one</h2>
 * The window manager asks for sizes the application never sees — the caption, Alt-Tab, a 200%-scaled
 * display — and {@code Icon} answers each request with the nearest image it was given rather than resampling.
 * A mark legible at 256 is not legible reduced to 16, so 16, 32, 48 and 256 are each rasterised at their own
 * size by {@code src/main/native/MakeIcon.java}, which is also what writes {@code calculator.ico} for the
 * executable. Re-run it if the mark changes; nothing in the build does.
 *
 * <h2>What this does not cover</h2>
 * The taskbar <em>button</em> of a running process, and what Explorer shows for the program on disk, both come
 * from resources linked into the {@code .exe} — no running process can set them. That half is
 * {@code src/main/native/calculator.rc} and the {@code native} profile's {@code /calculator.res} linker
 * option. This half is the title bar, Alt-Tab and the taskbar group's thumbnail flyout.
 */
final class Icons {

    private static final String[] FILES = {"icon-16.png", "icon-32.png", "icon-48.png", "icon-256.png"};

    /** Decoded once, on the first window that asks. */
    private static Icon mark;

    private Icons() {}

    /**
     * Points every window this process opens at the calculator's mark — the ones already open and the ones
     * opened later, which is what makes this a single call at start-up rather than an argument threaded
     * through {@code GuiApp} and the history popup both.
     *
     * <p>A no-op on Linux and macOS, where the platform has no window icon yet. Deliberately not called by
     * {@link CalculatorDesktop}: there the window is MainFrame's and should say so.
     */
    static void applyTo(NativePlatform platform) {
        platform.setApplicationIcon(mark());
    }

    /**
     * The mark itself, for a window that has to name one rather than inherit it.
     *
     * <p>Running standalone, {@link #applyTo} settles it for the whole process and no window says anything.
     * Running inside MainFrame it cannot: the process is MainFrame, its console wears the MainFrame mark, and
     * a calculator window that inherited the application icon would be indistinguishable from the shell in
     * Alt-Tab and on the taskbar. So each of this app's windows names this one on its {@code WindowConfig},
     * which is the per-window half the platform already supports.
     *
     * <p>Read once and held: four small PNGs is cheap, but three windows asking for the same four is three
     * times nothing for no reason.
     */
    static synchronized Icon mark() {
        if (mark == null) {
            byte[][] encoded = new byte[FILES.length][];
            for (int i = 0; i < FILES.length; i++) {
                try (InputStream in = Icons.class.getResourceAsStream(FILES[i])) {
                    if (in == null) {
                        throw new IllegalStateException("icon resource missing from the jar: " + FILES[i]);
                    }
                    encoded[i] = in.readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException("reading icon resource " + FILES[i], e);
                }
            }
            mark = Icon.fromBytes(encoded);
        }
        return mark;
    }
}
