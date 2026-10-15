# Framework notes

Findings about VexelRay, vexelray-gui, Kronometer, tactroller and atchung that came out of building
**Calculator** — things the framework does not have, does not document, or does in a way that cost time to
discover.

**Why this file exists.** An application built on a framework is the only place its gaps are visible, and they
are visible exactly once: at the moment they are worked around. A workaround with no note beside it becomes a
piece of application code nobody can tell from a design decision, and the framework never hears about it. So
the rule is to write the note *when the workaround is written*, not in a retrospective, and to write down what
was measured rather than what was assumed.

**Before writing a workaround, ask whether it is a component.** If the answer is "every project on this
framework will write these same four calls" — that is a finding, and the fix belongs upstream. Say so here
with that framing, so the retrospective has a candidate rather than a complaint.

## How to write one

    ## FN-1 · One line saying what is missing 🔬
    
    What was wanted, what the framework offers instead, and what was done about it.
    Then: what it costs, and what the framework could do about it.

The markers are a filter, not decoration:

| | |
| --- | --- |
| 🔬 | a framework gap — something upstream could fix |
| 💡 | an idea, not yet a finding |
| 📋 | carried over: needs re-verifying against a newer build before it is repeated |

## Findings

## FN-1 · A part that only claims a window must still be `@MainThread` 🔬 (fixed upstream)

The settings window is a named `AppWindow`, which is claimed through `GuiApp.window(...)`. `AppWindow` is safe
from any thread (every command only posts to the frame loop), but the processor's T2.2 rule refuses a
`@Provides` method that takes the `GuiApp` unless its value is `@MainThread`. So `Recipes.settingsHost` is
marked `@MainThread` and returns the `AppWindow`. That overstates it, though harmlessly, since nothing injects it.
The tree itself is a separate, unmarked part (`SettingsWindow`), so the overstatement stays confined to the
handle.

What it costs: every app with a second window writes this same split. What the framework could do: hand out
`AppWindow`s itself, e.g. a `WindowRegistry` root that wraps `GuiApp.window` and is not main-thread, or a
`@Window("settings")` recipe whose value is the tree and whose claim is generated.

Fixed by the first of those: the framework's `Windows` root, from `GUI`, which is not main-thread. `Recipes.settingsWindow`
declares the window (`windows.named(SettingsWindow.DECLARED)`) and gets a `Window` whose tree the framework has built.
The framework claims the window at `ATTACH`, so `settingsHost`, its `@MainThread` and the `GuiApp` are gone, and
`SettingsWindow` builds into the tree it is handed. See the framework's `docs/architecture.md`, *what the two-window
witness found*.

## FN-2 · `Popout` puts its second tree on the host's bus, which `Gui` forbids 🔬 (fixed upstream)

`Gui(Atchung)`'s javadoc says never to share one bus between two `Gui`s, because the topics are static and each
tree receives the other's mutations. `Popout` does `new Gui(host.bus())` anyway. The settings window copied that
line first, and the main window came up drawing the **settings tree** in place of the calculator. No exception
was thrown, and the main window was simply wrong from the first frame.

What was done here: the settings tree gets `new Gui(Atchung.create(), lanes.handlers(), lanes.offload())`, which
gives it its own bus while still using the application's threads. `Popout` had the same defect whenever a
panel was popped out, and now gives the popped-out tree a bus of its own (vexelray-gui `a93a661`). Separately, the framework could build the tree for a named window itself, since it knows
the lanes, the appearance, and the rule. It does now (see FN-1), so the settings window no longer makes a `Gui` at
all, and the line that could be copied wrong is gone from this application.

## FN-3 · The atlas font has no `≤` 📋

cott-engine's `Limits` labels contain `≤`, which renders as an empty box. `SettingsWindow.label` spells it as
`<=`. Same family as the missing ⌫, ⊥, square and chevron glyphs noted elsewhere.

## FN-4 · Closing the settings window crashed the application 🔬 (fixed upstream)

Closing the settings window crashed the calculator a few frames later, every time:
`NativeException: GetWindowRect failed`, thrown from `WindowMemory.poll` inside `FrameHooks.run`. `claim`
had called `memory.watch(KEY, w)` from `onCreated`, and nothing called the matching `forget` when the window
closed. The next poll read a destroyed window, and the frame loop let that one failure take the whole
application down with it.

Fixed in three places upstream, so nothing here works around it:

- `WindowMemory.remember(key, spec, w, h)` pairs the watch with its `forget`. The framework claims the settings window
  with it now (`Windows.named`, see FN-1), which also remembers this window's zoom.
- A destroyed `Win32Window` answers with its last values instead of calling into Win32.
- `FrameHooks` stops a hook that throws and logs it, rather than ending the run.

The component witness now opens and closes a remembered second window. See the framework's
`docs/architecture.md`, under *the frame loop is the lifecycle*.

## FN-5 · `IconButton`'s hover asks for a shadelet the default shader does not have 🔬 (fixed upstream)

The plot explorer's toolbar and the tape's pop-out button are `IconButton`s. The first hover over one logs
`vexelray.diag - the modifier 'weight' — the application's canvas shader was not composed with it`. The theme's
`IconEffects.STANDARD` thickens an icon under the pointer by `Effects.WEIGHT`, and the canvas shader every application
gets from the framework is not composed with that modifier. Only `vexelray-gui-demo`'s `DemoShader` is. So the icon
just doesn't thicken, which is cosmetic, but the warning appears in every application that uses an `IconButton`.

Nothing was ever done about it here. The framework now draws every application with `FrameworkShader`
(vexelray-framework-shell), which is the core set plus `Effects.WEIGHT` and is pre-compiled into the shell's jar. It
holds only what a standard widget asks for, not `Effects.ALL`. `GuiApp` gained the constructor that passes it through.

## FN-6 · A tree whose only context menu is the application's own draws no menu 🔬 (fixed upstream)

`gui.onContextMenu(node, ...)` collects items, but a menu is only drawn once a `MenuPresenter` is installed, and the
widgets install `ContextMenu` the first time one of them has a default menu, such as a text field's Copy and Paste.
The plot explorer's tree has no text field. Its right-click menu was built on every click and never shown, and
nothing reported it. `PlotExplorer` calls `ContextMenu.presentOn(gui)` itself.

Fixed upstream, both ways, and the `presentOn` call is gone from `PlotExplorer`. The first `gui.onContextMenu` on a
tree with no presenter installs the `MenuPresenter.Default` on the class path. `vexelray-gui-widget` registers
`ContextMenu` as that default through `META-INF/services`, so core still names no widget. With none registered, a
right click on a node with a menu says once that the menu went undrawn.

## FN-7 · Windows are declared before the tree, so a window per document is not possible yet 💡

A plot explorer per popped-out plot would be the natural shape, but `Windows.named` refuses a declaration after
`TREE`. So there is one explorer, and popping out a second plot replaces the first. An application that opens
documents in windows (an image viewer, a second editor) needs windows made at run time. That could be a pool
declared up front, or a `Windows.open(NamedWindow)` that claims at the next frame.

