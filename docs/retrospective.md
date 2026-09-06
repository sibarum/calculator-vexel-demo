# What went wrong, and what would stop it

Twenty-six findings across five milestones ([framework-notes.md](framework-notes.md) has them individually).
This is the pass over them looking for *shapes* rather than instances — because a list of twenty-six fixes is a
backlog, and four structural changes would have prevented most of them.

---

## The pattern that cost the most: silent degradation

Five findings share one shape, and they are between them almost all of the time lost:

| | The promise | What happened | How it looked |
|---|---|---|---|
| FN-25 | `Surface.Stroke` carries per-vertex colour and gradients between them | `ConeField`'s buffer has no colour channel; `compose` passes no albedo function | the plot rendered, in one colour, and thin tubes at grazing angles came out paler — so a grid and a curve drawn in the *same* colour still looked like two different things |
| FN-19 | `SdfScene.Rgb` is "linear, not sRGB" | nothing downstream applies an OETF, and the attachment is `_UNORM` | everything 2.2× too dark — which reads as *"the plot is dim"*, not as a colour-space bug |
| FN-14 | `GuiApp.capture` photographs the tree | its own device can't bind an instance's render target | a picture correct about the chrome and silently wrong about the content |
| FN-16 | the atlas charset requests arrows, operators, shapes | Noto Sans covers almost none of them | `x → (Re, Im)` drew as `x □ (Re, Im)` |
| FN-22 | *(no promise — an easy wrong assumption)* | aspect passed from the target, not the node it's stretched into | every circle 9% wide, uniformly, reading as a slightly odd camera |

**The common factor is not that these are bugs. It is that each one produces a plausible picture.** Nothing
throws, nothing warns, and the output is wrong in a way that looks like a taste decision. FN-25 cost the most
because I built a colour ramp, a four-way picker and per-vertex painting for four different kinds of geometry,
watched all of it render, and only caught it when a deliberately achromatic ramp changed *nothing*.

### What would stop it

**1. A capability check at every composition seam, on by default, warn-once.** These are all one boolean:

- `ConeField.compose(scene)` — the surface it is handed carries colour and this technique cannot render it.
  One `hasAlbedo()` call it already has, one warning, problem gone forever.
- `GuiApp.capture` — the tree contains an `IMAGE` prop bound to a target from another device. It already
  substitutes a placeholder; making it *say so on stderr* costs a line.
- The MSDF plugin — diff the requested charset against the produced glyph list at build time and print what
  the font did not cover. This is a set difference over data the plugin already holds, and it would have
  saved FN-5 and FN-16 for every consumer of the framework, permanently.

The framework's own instincts already point here: `StrokeMarchSmoke` exists precisely because *"nothing
renders is three questions a window cannot tell apart"*. This is the same argument one level up — **a
capability that is silently dropped is a fourth question**, and the answer is cheap.

**2. Where a doc and the code disagree, pick one — and prefer changing the code.** `SdfScene.Rgb` says linear
and the pipeline does not encode. Either the fragment gains an OETF (and the doc is right) or the doc says
these are display-space values and shading is approximate. The present state — a documented contract the
pipeline does not honour — is the only option that costs every consumer the same afternoon.

---

## The second pattern: two implementations of one fact

FN-12 and FN-22, and they are the ones I am least comfortable having "solved".

`SdfComposer.cameraBytes` hands six floats to generated SPIR-V. Nothing exposes the inverse, so anything drawn
*over* a marched viewport — an axis label, a probe anchor, a crop handle — has to re-derive the projection in
Java. `Lens` is that re-derivation, and it is a transcription of a shader expression sitting in a different
language in a different repo with nothing holding the two together. A mismatch does not fail: labels drift off
their axes as the camera turns, which reads as a rendering glitch and is a units bug.

I defended it as well as I could — `LensTest` transcribes `primaryRay` and round-trips through it at every
camera an orbit can reach, and the pan directions are checked against the projection rather than against their
own derivation. That is a good test and it is still two transcriptions.

### What would stop it

**3. If you hand out an encoder, hand out the decoder.** `SdfComposer.project(world…) → uv` beside
`cameraBytes(…)`, derived from the same expression the fragment is built from. It is a small addition and it
is the difference between one source of truth and two. The general rule generalises past this case: **any
value the framework serialises into generated code should have a framework-side inverse**, because the
consumer will need it and will otherwise write it themselves, once per application, slightly differently.

---

## The third pattern: capability gaps found only by exhaustive reading

Ten of the twenty-six are "this doesn't exist": no `Gui.onWheel` (FN-1), no `Card.headerControl` (FN-2), no
`Button`/`Toolbar` (FN-3), no pointer-anchored readout (FN-4), no icon vocabulary (FN-5), no per-edge border
(FN-17), no per-surface material (FN-21), no `Rail` tile handle (FN-23), no modifier in the driver (FN-26), no
published headless fixture (FN-10).

Each is individually reasonable. The cost is not the gap, it is **finding out**: I established there is no
wheel hook by reading all 91 public methods on `Gui`, because there is no smaller place to look.

### What would stop it

**4. Finish the `Gui` decomposition — for discoverability, not tidiness.** `docs/gui-decomposition.md` argues
it as a god-object problem and measures the class growing (1,929 → 2,193 lines, 86 → 91 methods, nothing
extracted). From outside, the god object is nearly invisible — a facade of 91 delegates is an index. What
*is* visible is that "does the framework do X?" has no smaller unit than the whole class. `gui.input()`,
`gui.metrics()`, `gui.nav()` would make that a five-method scan. The plan's own §5 already proposes exactly
this; this is a second, independent reason to do it.

**5. Two house rules for widgets, both of which would have closed findings by construction:**

- **Every widget hands out a handle for every distinct surface it composes** — to style, not restructure.
  `Tabs` does this (`bar()`, `pages()`); `Rail` does it for two of three (`node()`, `panel()`, but not tiles),
  and that omission is what makes its buttons unaddressable.
- **Every interactive element a widget creates gets an accessible name from whatever the application called
  it.** `Rail.item(key, icon, title, page)` already takes a title. Putting that title on the tile as its name
  would make `find Layers` work with nothing added, and would close FN-23 outright.

---

## The fourth pattern: seams that were never run

FN-13 is the sharpest thing in the notes. `ConeField`, `GuiApp.storage` and the push-constant form of
`SampledColorTarget.renderInto` are a chain built one link at a time, each with this consumer named in its
javadoc — and **the calculator is the first program to execute any of it.** `ConeField`'s five tests prove the
SPIR-V is valid, geometry-independent and correctly packed; none draws.

FN-18 is why that persisted: `OffscreenRenderer.render` takes no descriptor set, so the one
render-and-count-pixels path in the stack *cannot* exercise a buffer-driven field. The instrument that exists
for exactly this question could not be pointed at it.

### What would stop it

**6. A seam ships with its smoke.** `StrokeMarchSmoke` is the model and its reasoning is already written down.
The rule: any seam whose failure mode is "a blank frame" gets a headless run that counts non-sky pixels,
before it is documented as available. And the enabler: add `descriptorSet` + `setLayouts` to
`OffscreenRenderer.render` — two parameters that make `ConeMarchSmoke` writable at all.

---

## Timing traps, briefly

FN-24 (`settle` cannot see a lazily-built panel) and FN-11 (`renderInto` blocks on a fence on the calling
thread) are both documented and both still surprised me in practice.

`settle`'s limit is stated in `automation.md` §5 in general terms; the *shape* it takes is that the first
`find` inside any `Rail`/`Tabs`/`Popout` page races, intermittently, because all three build lazily inside a
worker-thread handler. Either build the page from the drain instead — putting it inside the frame `settle`
waits for — or name lazily-built pages in the doc as the case where `settle` is not enough.

`renderInto`'s wait is honestly described in its own source comment. Measured, it is **13.4 ms of stalled GUI
thread per marched frame at 640×400** — 80% of a 60 Hz budget, serially, for work that would cost latency
rather than frame rate if it were asynchronous. The fix the comment names (persist the pool and fence, sample
once it signals, double-buffer the target) is worth roughly a doubling of the plot's resolution.

---

## And my own mistakes, which are the same shape as the framework's

Three times I trusted an instrument without first proving it could detect anything.

- **The performance sweep that lied.** Seven configurations, one identical number, because a `-D` on the `mvn`
  command line sets a *Maven* property and `exec:exec`'s `commandlineArgs` is a fixed string — none of the
  flags reached the JVM. I reported "pixels dominate, steps barely matter" from a run in which nothing varied.
- **"Zero MODEL lines."** I concluded a click handler never fired from a trace that had never compiled in; my
  `perl` pattern hadn't matched the real method signature.
- **A double-toggle in a driver script** convinced me a panel was broken when it had simply been opened and
  closed across two sessions.

The rule that covers all three is the framework's own, generalised. `vexel-demo-projects` already carries it
for tests — *break the code and watch the test fail before believing it*. The same applies to any instrument:
**before trusting a measurement, move one knob to an absurd value and confirm the number moves.** Had I set
the march to 64×64 first, the broken sweep would have announced itself in ten seconds.

Worth noting the framework agrees with this in principle and it is where `automation.md` §6 gets its authority
— *"an instrument that is approximate is worse than no instrument, because it produces confident wrong
answers about bugs that are already confusing."* That is exactly what happened to me three times, with
instruments I built myself.

---

## What is worth keeping exactly as it is

This should not read as a list of complaints, because the ratio is not what it looks like. Four milestones of
a real application produced twenty-six findings and **not one of them was an architectural problem** — they
are missing methods, an unencoded colour, a doc that outran its implementation, and one buffer layout.

The things that carried the project:

- **`Rail` + `Inspector` + `Property`.** Six panels, five folding layer cards with switches and badges, a
  segment control, a swatch picker and an aligned value column, and there is *no switch anywhere in my code
  over what kind a property is*. Declaring the schema and letting the layout be a consequence is better than
  what Qt, WinUI or SwiftUI offer for the same job, and the javadoc explains *why* rather than *what*.
- **The read-model.** `node.layout()` and `SemanticSnapshot` from any thread, lock-free, is what makes the
  probe, the label overlay, the wheel hit-test and the whole automation story possible. Resolved decision 7 is
  load-bearing far beyond what it was written for.
- **The automation socket.** Every milestone was proved by driving the real application and photographing it.
  Injection being the *production* input path means there is no mode to drift — and that dividend is
  collected constantly.
- **`Surface.Stroke`'s geometric guarantee.** *Every vertex you name lies on the centre line, at every
  curvature.* That is exactly the promise a plot needs, argued from first principles, and it is why the curve
  is where the samples say it is.

The seam design is right. What is missing is a layer of **loudness** over it: the framework is very good at
explaining itself to a reader and not yet good at complaining to a consumer who has asked for something it
cannot do.
