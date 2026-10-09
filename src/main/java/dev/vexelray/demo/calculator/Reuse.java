package dev.vexelray.demo.calculator;

import dev.vexelray.canvas.Color;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.CursorShape;
import dev.vexelray.gui.core.input.InputTopics;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.gui.core.style.Role;
import dev.vexelray.gui.draw.Sketch;
import dev.vexelray.gui.widget.TextField;
import dev.vexelray.gui.widget.Tooltip;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Putting past lines back in the entry, three ways:
 *
 * <ul>
 *   <li><b>Up and Down</b> step through what was typed, newest first, replacing the entry; Down past the newest
 *       brings back what was being written.</li>
 *   <li><b>Clicking</b> a line on the tape, or its answer, adds it to the end of the entry.</li>
 *   <li><b>Holding Ctrl</b> in the entry numbers the {@value #NUMBERED} newest lines, 1 the newest, and Ctrl+1…9
 *       inserts that line's value at the caret. With Shift held too, the numbers move to what was typed, and
 *       Ctrl+Shift+1…9 inserts that. Ctrl+0 is left alone: it is the zoom reset.</li>
 * </ul>
 *
 * <p>Anything added beside other text goes in brackets unless it is one number or one name, so {@code 2·} and
 * {@code x+1} make {@code 2·(x+1)}, not {@code 2·x+1}. Every change is one undo step.
 *
 * <p>What this remembers belongs to the entry's keys rather than to the document: how far Up has gone and what
 * the entry said before it started, which keys are held, and whether the entry has focus.
 */
final class Reuse {

    /** How many of the newest lines Ctrl numbers: one per digit key, less the 0 that zoom has. */
    static final int NUMBERED = 9;

    private static final Key[] DIGITS = {Key.DIGIT_1, Key.DIGIT_2, Key.DIGIT_3, Key.DIGIT_4, Key.DIGIT_5,
            Key.DIGIT_6, Key.DIGIT_7, Key.DIGIT_8, Key.DIGIT_9};
    private static final Key[] PAD = {Key.NUMPAD_1, Key.NUMPAD_2, Key.NUMPAD_3, Key.NUMPAD_4, Key.NUMPAD_5,
            Key.NUMPAD_6, Key.NUMPAD_7, Key.NUMPAD_8, Key.NUMPAD_9};

    /** One number or one name: safe to drop beside anything without brackets. */
    private static final Pattern ATOM = Pattern.compile("[\\p{L}\\p{N}_.]+");

    /** One line of the tape as something to reuse. {@code answer} and {@code value} are null when it has none. */
    private record Row(Node line, Node input, String typed, Node answer, String value) {

        Node target(boolean typedOne) {
            return typedOne || value == null ? input : answer;
        }

        String text(boolean typedOne) {
            return typedOne || value == null ? typed : value;
        }
    }

    private final Gui gui;
    private final Model model;
    private final TextField entry;
    private final Tooltip tips;
    private final List<Row> rows = new ArrayList<>();

    /** How far back Up has gone: an index into the tape, or -1 for the line being written. */
    private int recalled = -1;
    /** What the entry said when Up was first pressed, for Down to come back to. */
    private String draft = "";

    private final Set<Key> held = EnumSet.noneOf(Key.class);
    private boolean focused;
    /** What the badges were last drawn for: off, or on and over the values, or on and over what was typed. */
    private boolean numbered;
    private boolean numberedTyped;

    Reuse(Gui gui, Model model, TextField entry, Tooltip tips) {
        this.gui = gui;
        this.model = model;
        this.entry = entry;
        this.tips = tips;

        // Claims are heard before the field's own keys, and the field is one line, so Up and Down are free.
        Node field = entry.node();
        gui.claimUi(field, Shortcut.of(Key.UP), ClaimScope.FOCUSED, () -> step(1));
        gui.claimUi(field, Shortcut.of(Key.DOWN), ClaimScope.FOCUSED, () -> step(-1));
        for (int i = 0; i < NUMBERED; i++) {
            int n = i + 1;
            for (Key k : new Key[]{DIGITS[i], PAD[i]}) {
                gui.claimUi(field, Shortcut.of(k, Modifier.CONTROL), ClaimScope.FOCUSED, () -> insert(n, false));
                gui.claimUi(field, Shortcut.of(k, Modifier.CONTROL, Modifier.SHIFT), ClaimScope.FOCUSED,
                        () -> insert(n, true));
            }
        }

        gui.bus().subscribe(gui.focusEvents(), e -> {
            if (e.nodeId() == field.id()) {
                synchronized (this) {
                    focused = e.gained();
                    badges(false);
                }
            }
        });
        gui.bus().subscribe(InputTopics.INPUT, e -> {
            synchronized (this) {
                switch (e) {
                    case InputEvent.KeyPressed k -> held.add(k.key());
                    case InputEvent.KeyReleased k -> held.remove(k.key());
                    // A key let go in another window never comes back up here.
                    case InputEvent.FocusChanged f when !f.focused() -> held.clear();
                    default -> {
                        return;
                    }
                }
                badges(false);
            }
        });
    }

    /** The entry has been taken: whatever Up had reached, the next Up starts from the newest line again. */
    synchronized void submitted() {
        recalled = -1;
    }

    /**
     * Make a newly drawn line of the tape reusable. {@code answer} is the node showing its value, or null when it
     * shows none, and then {@code value} is null too.
     */
    synchronized void row(Node line, Node input, String typed, Node answer, String value) {
        pick(input, typed);
        if (answer != null) pick(answer, value);
        rows.add(new Row(line, input, typed, answer, value));
        badges(true);
    }

    // ---- clicking ----

    private void pick(Node node, String text) {
        gui.onClick(node, () -> {
            entry.replace(join(entry.text(), entry.text().length(), text));
            gui.focus(entry.node());
        });
        gui.cursor(node, CursorShape.POINTER);
        tips.attach(node, "Click to add this to the entry. Ctrl+number inserts at the caret.");
    }

    // ---- Ctrl+number ----

    private synchronized void insert(int n, boolean typedOne) {
        if (n > rows.size()) return;
        String text = rows.get(rows.size() - n).text(typedOne);
        entry.insert(bracketed(entry.text(), text));
    }

    /** {@code line} with {@code text} put in at {@code at}, bracketed if it lands beside anything. */
    static String join(String line, int at, String text) {
        return line.substring(0, at) + bracketed(line, text) + line.substring(at);
    }

    /** {@code text} as it should go into {@code line}: bare when the line is empty or it is one number or name. */
    static String bracketed(String line, String text) {
        return line.isBlank() || ATOM.matcher(text).matches() ? text : "(" + text + ")";
    }

    /**
     * Draw or clear the numbers to match the keys. {@code changed} redraws even when the keys have not moved,
     * because the rows have: a new line takes 1 and every other number goes up by one.
     */
    private void badges(boolean changed) {
        boolean on = focused && (held.contains(Key.LEFT_CONTROL) || held.contains(Key.RIGHT_CONTROL));
        boolean typedOne = held.contains(Key.LEFT_SHIFT) || held.contains(Key.RIGHT_SHIFT);
        if (!changed && on == numbered && (!on || typedOne == numberedTyped)) return;
        if (!on && !numbered) return;
        numbered = on;
        numberedTyped = typedOne;
        // Clearing one more than are numbered takes the badge off the line a new one just pushed out.
        for (int i = 0; i < rows.size() && i <= NUMBERED; i++) {
            Row r = rows.get(rows.size() - 1 - i);
            r.line().overlay(on && i < NUMBERED ? badge(r, i + 1, typedOne) : null);
        }
    }

    /** The number {@code n} on a chip at the right-hand end of the line it would insert, in the row's pixels. */
    private dev.vexelray.gui.draw.Picture badge(Row r, int n, boolean typedOne) {
        NodeLayout line = r.line().layout();
        NodeLayout target = r.target(typedOne).layout();
        if (!line.present() || !target.present()) return null;
        double px = Math.max(10, target.textSizePx() * 0.8);
        double h = px + 8;
        double w = 0.6 * px + 12;
        double x = line.rect().w() - w - 2;
        double y = target.rect().y() - line.rect().y() + (target.rect().h() - h) / 2;
        Color accent = gui.theme().color(Role.ACCENT);
        return new Sketch()
                .fill(x, y, w, h, 4, gui.theme().color(Role.RAISED))
                .outline(x, y, w, h, 4, 1, accent)
                .text(String.valueOf(n), x + 6, y + h / 2 + px * 0.35, px, accent)
                .picture();
    }

    // ---- Up and Down ----

    /** Up ({@code +1}) or Down ({@code -1}): put the next older or newer past line in the entry. */
    private synchronized void step(int direction) {
        List<Doc.Entry> past = model.doc().tape();
        String now = entry.text();
        // Edited since it was put there: what it says now is the line being written, and stepping starts over.
        if (recalled >= past.size() || recalled >= 0 && !past.get(recalled).input().equals(now)) recalled = -1;
        if (recalled == -1) draft = now;
        int next = recall(past.stream().map(Doc.Entry::input).toList(), recalled, direction, now);
        if (next == recalled) return;
        recalled = next;
        entry.replace(next == -1 ? draft : past.get(next).input());
    }

    /**
     * Where a step from {@code at} lands: the nearest past line in {@code direction} (+1 older, -1 newer) that
     * says something other than {@code now}, so every press changes the entry; -1 past the newest, which is the
     * line being written; and {@code at} itself when there is nothing older.
     */
    static int recall(List<String> inputs, int at, int direction, String now) {
        if (at == -1 && direction < 0) return -1;
        int i = at == -1 ? inputs.size() - 1 : at - direction;
        for (; i >= 0 && i < inputs.size(); i -= direction) {
            if (!inputs.get(i).equals(now)) return i;
        }
        return direction > 0 ? at : -1;
    }
}
