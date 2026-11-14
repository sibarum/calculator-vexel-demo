package dev.vexelray.demo.calculator;

import dev.vexelray.demo.calculator.Messages.Act;
import dev.vexelray.demo.calculator.Messages.Action;
import dev.vexelray.demo.calculator.Messages.Enter;
import dev.vexelray.demo.calculator.Messages.Entered;
import dev.vexelray.demo.calculator.Messages.ModeSet;
import dev.vexelray.demo.calculator.Messages.SetMode;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.core.CloseRequested;
import dev.vexelray.framework.core.Modifiers;
import dev.vexelray.framework.core.WindowClosed;
import dev.vexelray.framework.core.WindowCommand;
import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.input.ClaimScope;
import dev.vexelray.gui.core.input.Shortcut;
import dev.vexelray.gui.keymap.Keymap;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.Segment;
import dev.vexelray.gui.widget.Select;
import dev.vexelray.gui.widget.TextField;
import sibarum.atchung.Sender;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.Modifier;

/**
 * The view: what the person did, heard as messages in the frame, and handed to the part of the tree it concerns. A
 * {@code @View}, so it runs on the GUI thread, in each frame's widget tick (vexelray-framework's
 * {@code docs/threading.md}, T7.4).
 *
 * <h2>Why there is one</h2>
 *
 * <p>Since 0.2 a widget says what happened to it on a topic rather than calling code it was handed (T7.2): a button's
 * press is {@code Button.PRESSED}, the entry's Enter is {@code TextField.SUBMITTED}, a drop-down's choice is
 * {@code Select.COMMITTED}. And a chord, a claim or a menu item names the message it sends, through a {@link Sender}
 * only the container hands out (T7.5). So the {@link Ui}, the {@link SettingsWindow} and the {@link PlotExplorer} still
 * build and draw their trees, and this is where their messages come in: each handler asks which of them a message is
 * about, by the widget's address, and calls it.
 *
 * <h2>Slow work is a job</h2>
 *
 * <p>A line is evaluated by {@link Evaluate}, and a mode changed by {@link ChangeMode}, on the job lane: cott-engine at
 * deep limits can take far longer than a frame. When either answers, the {@link Model}'s new document is drawn into
 * both windows here, so every widget write happens on the GUI thread.
 */
@dev.vexelray.framework.api.View
final class Screen {

    private final Gui gui;
    private final Model model;
    private final Ui ui;
    private final SettingsWindow settings;
    private final PlotExplorer explorer;
    private final Sender<Enter> enter;
    private final Sender<SetMode> modes;

    Screen(Gui gui, Keymap keys, Model model, Ui ui, SettingsWindow settings, PlotExplorer explorer,
           Sender<Enter> enter, Sender<SetMode> modes, Sender<Act> acts, Sender<WindowCommand> windows) {
        this.gui = gui;
        this.model = model;
        this.ui = ui;
        this.settings = settings;
        this.explorer = explorer;
        this.enter = enter;
        this.modes = modes;

        ui.declare(acts);
        explorer.declare(acts);
        ui.onSettings(() -> windows.send(WindowCommand.show(settings.key())));
        ui.onExplore(line -> {
            explorer.open(line);
            windows.send(WindowCommand.show(explorer.key()));
        });

        keys.action("window.settings", "Settings", Shortcut.of(Key.COMMA, Modifier.CONTROL))
                .sends(windows, WindowCommand.show(settings.key()));
        zoom(keys, acts);
        zoom(settings.gui(), settings.key(), acts);
        zoom(explorer.gui(), explorer.key(), acts);
    }

    /**
     * Ctrl+= / Ctrl+- / Ctrl+0, and the numpad's three, in the main window, as commands the title bar's key button
     * lists. An application decision: which chord zooms, or whether zooming exists at all, is not something a
     * framework should choose. <b>How far the zoom goes is</b>, and it is not stated here: that is
     * {@code Appearance.ZoomRange}, applied by the framework before the first widget.
     */
    private static void zoom(Keymap keys, Sender<Act> acts) {
        String main = CloseRequested.MAIN;
        keys.action("view.zoom-in", "Zoom in", Shortcut.of(Key.EQUAL, Modifier.CONTROL),
                Shortcut.of(Key.NUMPAD_ADD, Modifier.CONTROL)).sends(acts, new Act(Action.ZOOM_IN, main));
        keys.action("view.zoom-out", "Zoom out", Shortcut.of(Key.MINUS, Modifier.CONTROL),
                Shortcut.of(Key.NUMPAD_SUBTRACT, Modifier.CONTROL)).sends(acts, new Act(Action.ZOOM_OUT, main));
        keys.action("view.zoom-reset", "Reset zoom", Shortcut.of(Key.DIGIT_0, Modifier.CONTROL),
                Shortcut.of(Key.NUMPAD_0, Modifier.CONTROL)).sends(acts, new Act(Action.ZOOM_RESET, main));
    }

    /** The same chords in a second window, which has its own tree and so its own zoom, claimed over its root. */
    private static void zoom(Gui gui, String window, Sender<Act> acts) {
        claim(gui, acts, new Act(Action.ZOOM_IN, window), Key.EQUAL, Key.NUMPAD_ADD);
        claim(gui, acts, new Act(Action.ZOOM_OUT, window), Key.MINUS, Key.NUMPAD_SUBTRACT);
        claim(gui, acts, new Act(Action.ZOOM_RESET, window), Key.DIGIT_0, Key.NUMPAD_0);
    }

    private static void claim(Gui gui, Sender<Act> acts, Act act, Key... keys) {
        for (Key k : keys) {
            gui.claim(gui.root(), Shortcut.of(k, Modifier.CONTROL), ClaimScope.VISIBLE, acts, act);
        }
    }

    // ------------------------------------------------------------------ what the jobs answer

    /** A line was evaluated: draw the document it left, and clear the entry if the line was taken. */
    @Subscribe
    public void entered(Entered m) {
        showDoc();
        if (m.taken()) {
            ui.taken();
        }
    }

    @Subscribe
    public void modeSet(ModeSet m) {
        showDoc();
    }

    private void showDoc() {
        Doc doc = model.doc();
        ui.show(doc);
        settings.show(doc);
    }

    // ------------------------------------------------------------------ what the widgets say

    @Subscribe
    public void submitted(TextField.Submitted m) {
        if (m.field().equals(ui.entryAddress()) && !m.text().isBlank()) {
            enter.send(new Enter(m.text()));
        }
    }

    @Subscribe
    public void pressed(Button.Pressed m) {
        if (!ui.pressed(m.button())) {
            explorer.pressed(m.button());
        }
    }

    @Subscribe
    public void toggled(Button.Toggled m) {
        explorer.toggled(m.button(), m.pressed());
    }

    @Subscribe
    public void segment(Segment.Changed m) {
        explorer.segment(m.segment(), m.value());
    }

    @Subscribe
    public void committed(Select.Committed m) {
        SetMode wanted = settings.chosen(m);
        if (wanted != null) {
            modes.send(wanted);
        }
    }

    // ------------------------------------------------------------------ what the person asked by chord or menu

    @Subscribe
    public void act(Act act) {
        switch (act.action()) {
            case ZOOM_IN -> treeOf(act.window()).zoomIn();
            case ZOOM_OUT -> treeOf(act.window()).zoomOut();
            case ZOOM_RESET -> treeOf(act.window()).resetZoom();
            case EXPLORE_RESET, EXPLORE_FIT, EXPLORE_ZOOM_IN, EXPLORE_ZOOM_OUT, EXPLORE_CLEAR_PINS -> explorer.act(act);
            default -> ui.act(act);
        }
    }

    private Gui treeOf(String window) {
        if (window.equals(settings.key())) return settings.gui();
        if (window.equals(explorer.key())) return explorer.gui();
        return gui;
    }

    // ------------------------------------------------------------------ the windows

    /** Control and Shift held: the tape's numbered lines in the main window, the wheel's axes in the explorer. */
    @Subscribe
    public void modifiers(Modifiers m) {
        if (m.main()) {
            ui.reuse().held(m.control(), m.shift());
        } else if (m.window().equals(explorer.key())) {
            explorer.held(m.shift(), m.control());
        }
    }

    @Subscribe
    public void closed(WindowClosed m) {
        if (m.window().equals(explorer.key())) {
            explorer.closed();
        }
    }
}
