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

## FN-1 · A part that only claims a window must still be `@MainThread` 🔬

The settings window is a named `AppWindow`, which is claimed through `GuiApp.window(...)`. `AppWindow` is safe
from any thread (every command only posts to the frame loop), but the processor's T2.2 rule refuses a
`@Provides` method that takes the `GuiApp` unless its value is `@MainThread`. So `Recipes.settingsHost` is
marked `@MainThread` and returns the `AppWindow`. That overstates it, though harmlessly, since nothing injects it.
The tree itself is a separate, unmarked part (`SettingsWindow`), so the overstatement stays confined to the
handle.

What it costs: every app with a second window writes this same split. What the framework could do: hand out
`AppWindow`s itself, e.g. a `WindowRegistry` root that wraps `GuiApp.window` and is not main-thread, or a
`@Window("settings")` recipe whose value is the tree and whose claim is generated.

## FN-2 · `Popout` puts its second tree on the host's bus, which `Gui` forbids 🔬

`Gui(Atchung)`'s javadoc says never to share one bus between two `Gui`s, because the topics are static and each
tree receives the other's mutations. `Popout` does `new Gui(host.bus())` anyway. The settings window copied that
line first, and the main window came up drawing the **settings tree** in place of the calculator. No exception
was thrown, and the main window was simply wrong from the first frame.

What was done here: the settings tree gets `new Gui(Atchung.create(), lanes.handlers(), lanes.offload())`, which
gives it its own bus while still using the application's threads. `Popout` likely has the same defect whenever a
panel is popped out. Separately, the framework could build the tree for a named window itself, since it knows
the lanes, the appearance, and the rule.

## FN-3 · The atlas font has no `≤` 📋

cott-engine's `Limits` labels contain `≤`, which renders as an empty box. `SettingsWindow.label` spells it as
`<=`. Same family as the missing ⌫, ⊥, square and chevron glyphs noted elsewhere.

