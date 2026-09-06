# Framework notes

Findings about vexelray-gui and its siblings, gathered while building the calculator. This file is the input to
the retrospective: each entry says what was hit, what it cost, and what the framework-side answer would be.

Nothing here is a complaint about a decision that was made on purpose — where a limit is deliberate and
documented (backdrop blur, the picture alphabet), the note records the *consequence for a consumer*, which is
the thing a framework author cannot see from inside.

**Status key** — 🔬 verified against the source this session · 📋 carried over from the previous build, needs
re-verification · 💡 opportunity, not a defect.

---

## FN-1 · No per-node wheel hook 🔬

`Gui` has `onClick`, `onDrag`, `onKey`, `onChar`, `onState`, `onResize`, `onDrop` — and no `onWheel`.
`InputDispatcher` routes a `Scrolled` edge to the nearest **scrollable** ancestor and drops it otherwise, so a
wheel notch over a canvas that does not scroll goes nowhere at all, silently.

**Cost to the calculator:** wheel zoom on the viewport cannot use the dispatcher. The way in is
`gui.bus().subscribe(InputTopics.INPUT, …)` plus a hand-rolled hit-test of the node's own `layout()` rect
against the event coordinates. That works — it is the same coordinate space, so nothing is converted, and
inline delivery is fine for a handler that only commits a state edit — but every application that draws
something zoomable will write the same twenty lines, and each will get the hit-test slightly differently.

**Framework answer:** `Gui.onWheel(Node, Consumer<ScrollEvent>)`, dispatched by the same ancestor-or-self walk
clicks already use, resolved *before* the scrollable-ancestor fallback so a declared handler outranks a
scrolling parent.

*(This is the second time it has come up — it was hit on 2026-09-03 in the previous build too.)*

---

## FN-2 · `Inspector.Card` has no header slot 🔬

`Inspector.card(title, badge, on, toggle)` builds a heading with a switch, a name, a badge and a fold caret.
The prototype's layer cards put an **opacity slider in that header**, beside the badge — deliberately, because
opacity is the one setting you want to reach without unfolding the card.

`Card` exposes `node()`, `open()`, `badge()` and `add(Property)`. `node()` is a handle to style, not to
restructure, and the header is not separately reachable.

**Cost:** either the slider moves into the card body (a real behaviour change — it is then hidden when folded),
or the layer card stops being an `Inspector.Card` and is hand-built, which loses the fold, the dimming and the
scroll behaviour the widget already gets right.

**Framework answer:** `Card.headerControl(Node)` — one slot between the badge and the caret, reserving its
width so nothing in the strip moves as controls come and go (the same no-movement rule `TitleBar`'s instruments
follow).

---

## FN-3 · No action-button row 💡🔬

Six of the prototype's panels end in a wrapping row of outline buttons — `Iso · Top · Front · Side · Fit ·
Reset`, `Symmetric ±3 · ±2π · Unit box · …`. There is no framework component for this, and no plain `Button`
either: a button in this framework is a styled box with `onClick`, `focusable` and a state handler, which is
five lines each time and five chances to forget the focus ring.

The framework's own `docs/todo.md` §4.5 already names **`Toolbar` with overflow** as missing, for the stronger
reason (measure what fits, move the rest into a reserved chevron). A panel action row is the degenerate case of
the same component.

**Framework answer:** `Toolbar`, per todo §4.5 — and a `Button` under it, because every widget on the shelf
that has a clickable thing in it currently builds one privately.

---

## FN-4 · No pointer-following readout 💡🔬

`widget.Tooltip` is explicitly *not* this: it is anchored to the target's box, delay-armed, and *"once shown it
never moves"*. Every one of those is right for help text and wrong for a data probe, which must track the
pointer, appear instantly, and update its contents continuously.

**Cost:** the probe is built in the app — a floating `hitInert` node with three text children, positioned from
a `PointerMoved` subscription, faded with `OPACITY`. Perhaps 80 lines. The subtle parts are the ones a
component should own: keeping the bubble inside the window near an edge, not flickering when it crosses its own
old position, and being `hitInert` so it cannot steal the hover that produced it.

**Framework answer:** `Probe` (or `Readout`) beside `Tooltip` — same bubble chrome, pointer-anchored, no delay,
edge-aware placement (`widget.Placement` already exists for exactly this arithmetic).

---

## FN-5 · There is no icon vocabulary anywhere in the stack 🔬📋

The prototype uses eight Phosphor icons. The primary MSDF atlas has **no symbol glyphs to substitute** — 📋 the
previous build measured it at 1112 glyphs, of which the entire Mathematical Operators and Geometric Shapes
range contributes exactly two (U+2212 and U+25CC), so `⊞ ▦ ∿ √ ≡ ∂ ∫ ⌫ ⊥ ≈` all render as boxes. *(Worth
re-measuring against the current `primary.json` before relying on it: `grep -q '"unicode":<decimal>,'`.)*

`TitleBar` already knows this and draws its caption icons as geometry rather than characters; `automation.md`
§7 states the rule outright — *"Marks, not glyphs."*

**Cost:** eight hand-drawn `Picture`s, and every future application draws its own *close*, *search*,
*chevron*, *settings*. That is correct for a logo and plainly wrong for a chevron.

**Framework answer:** a small `Icons` vocabulary in `-widget` as `Picture` geometry — a couple of dozen marks,
crisp at any zoom, theme-coloured at build. It is a day of work that every consumer would otherwise repeat.

---

## FN-6 · `Picture` has no filled polygon 🔬

Documented and argued in `docs/drawing.md` §3: the alphabet is what the engine's rounded-box SDF can draw, and
the uber-shader has no triangle or convex-polygon kind, so a polygon could only be faked on the canvas while
SVG drew it exactly — *"a picture that means two different things on two targets is worse than one that cannot
express the shape."* Correct reasoning, and the prerequisite lands in `vexelray`, not here.

**Cost to the calculator: much smaller than it first appeared.** The plot's geometry is marched
(`Surface.Stroke` → `ConeField`), not drawn, so a shaded surface is an SDF problem rather than a polygon one.
`Picture` is now only carrying the axis tick labels over the marched image, which is text and lines. Kept on
the list because the label overlay would still like a filled arrowhead, and because the note is true.

**Framework answer:** already named in `drawing.md` §7 and `todo.md` §5 — a triangle or convex-polygon kind in
the engine's uber-shader, then `Sink.polygon`.

---

## FN-7 · No backdrop blur, and it changes the design 🔬

The prototype's rail card and panel are `backdrop-filter: blur(10px)` / `blur(14px)` over the plot. Backdrop
blur samples neighbouring pixels, so it is multi-pass and is explicitly deferred at the engine seam
(`architecture.md` §2, requirement 6 — *"only multi-pass effects that sample neighbouring pixels (backdrop
blur, bloom) remain deferred"*).

**Cost:** the panels can be translucent (`Color` carries alpha) but not blurred. Translucent over a busy plot
without blur is noisier than the design; the likely fallback is a higher alpha, which is a visible departure
from the prototype rather than a subtle one.

**Framework answer:** none available cheaply — this is genuinely a second pass. Worth noting that
`GuiApp.viewport()` now exists, so *a* mechanism exists that did not before; whether a blur is worth a target
and a composite per panel is a real question and probably answers "no".

---

## FN-8 · Density is still pinned at 1.0 (engine E4) 🔬

`architecture.md` §3 is unusually clear that this one "breaks a framework rather than merely limiting it":
`NativeWindow` exposes one `width()`/`height()` and no content scale, so points and pixels are the same number
and three consumers that do not want the same space all get it. The framework half (`Gui.dpi`, `Length.dp`,
`DpiTest`) is done and tested; the wiring cannot happen until the engine reports a framebuffer extent and the
process is DPI-aware — which is declared by *packaging*, so an application cannot opt in from its own code.

**Cost:** the calculator follows `Demo.attachInput` exactly — `CoordinateSpace.CLIENT`, density left at 1.0 —
and inherits the OS's own scaling of a logical-space window. On a 125% display the UI is scaled once, by the
OS, and is slightly soft rather than wrong. Feeding `contentScale()` in would scale it *twice* (1.56×), which
reads as "everything is too big" rather than as a units bug, and `FRAMEBUFFER` coordinates would land every
press down-and-right of the cursor.

**No action for this project** beyond not touching it. Recorded because it is the single most likely thing for
a new consumer to "fix" and break.

---

## FN-9 · A surface cannot declare itself opaque to the pointer 🔬

Two facts combine:

- `HitTest.at` returns the deepest node under the point **whether or not it has a handler**, and pointer events
  then bubble leaf→root. So any node under the pointer is a target.
- `Node.hitInert(true)` covers the node *and its whole subtree*, with no way back out on a descendant — there
  is no `pointer-events: auto`.

The prototype relies on the pair CSS gives it: `pointer-events: none` on an overlay container, `auto` on the
controls inside it, so a drag that misses a control still reaches the canvas.

**What works instead, and it is arguably better:** parent the mostly-empty overlay clusters to the *viewport
node* rather than to the root. A press on their empty space finds no handler and bubbles to the viewport, which
orbits — the prototype's behaviour, from the framework's own dispatch rather than from a property.

**What has no clean answer:** the reverse. The panel is a solid surface inside that subtree and must *not* pass
a drag through, and the only way to stop the bubble is to register an `onDrag` handler that does nothing.

**Framework answer:** either `Node.hitInert` gains a per-node override (`hitInert(false)` on a child meaning
"but not me"), or — smaller and more honest — `Node.opaqueToPointer(true)`, which stops the bubble without
inventing a handler. The second is a one-line dispatcher change and names the intent.

---

## FN-10 · No published headless GUI fixture 🔬

`HeadlessGui` — no window, no Vulkan, no worker threads, a same-thread handler executor so an event published
on the bus is dispatched and fully handled inside `frame()`, and a fixed monospace text stub so caret and
offset geometry is exact — is **200 lines of exactly what every application needs to test its own tree**, and
it is package-private in `vexelray-gui-widget`'s *test* sources.

`vexelray-gui-harness` is shipped precisely so an application can assert its own interaction end to end, and
its own pom says so — but it drives a **real** loop and needs a Vulkan device, which is the other tier.

**Cost:** the calculator copies `HeadlessGui`. The copy will drift from the original, and when the framework's
dispatch changes, the copy will keep passing.

**Framework answer:** publish it, most naturally as a headless sibling in `-harness` (`HeadlessGui` alongside
`HarnessApp`, one for tree tests and one for loop tests). This is the finding I would most like fixed in the
framework rather than worked around here.

---

## FN-11 · `Node.padding` is symmetric only 🔬📋

`padding(Length)` and `padding(vertical, horizontal)`; no per-edge form. The prototype's panel header is
`8.4px 8.4px 8.4px 11.2px` — a wider left inset, which is what makes the title line up with the rows below it
rather than with the switch column.

📋 The previous build hit this and paid for it: substituting symmetric padding for "a gap above the first row"
cost 8 dp under the bottom row, which the window then had to find at its minimum size, and the
minimum-size capture is what caught it.

**Workaround:** a spacer child, or `margin` on the inner node, or an outer box. All three work; all three are
noise at the call site.

---

## FN-11 · `renderInto` blocks on a fence, on the calling thread 🔬

`SampledColorTarget.renderInto` creates a command pool, records a one-time command buffer, submits it, and
**waits on a fence before returning**. The source comment is candid about it:

> *"It is still a wait, and it is still on the calling thread. Removing it altogether means keeping the pool and
> the fence alive across frames and sampling the image only once the fence signals — worth doing, and a larger
> change than this one, because renderInto's contract currently promises that the image is ready when it
> returns."*

(It has already been improved once — it used to be `vkDeviceWaitIdle`, which stalled behind everything on the
device including the swapchain work of the frame it was inside.)

**Cost to the calculator:** the viewport must be marched before the frame that samples it, so this lands in the
frame loop, on the GUI thread. One synchronous GPU round-trip per marched frame. At rest it costs nothing
(render-on-demand parks, and a still plot under a still camera needs no march), but **an orbit is exactly the
case where it is paid every frame** — and an orbit is the interaction this whole application is about.

This is the one place the framework's own "the GUI thread is never blocked" promise is not yet structurally
kept, which is why it is worth a note rather than a shrug: everything else in the stack goes to real trouble to
honour it.

**Framework answer:** the one the comment names — persist the pool and fence across frames, return without
waiting, and sample only once the fence signals. That needs the contract to change (the image is ready *later*,
not on return), which most naturally means double-buffering the target and handing back the one that is ready.
A viewport that is one frame behind is invisible; a frame loop that stalls is not.

---

## FN-12 · The march camera has no Java-side twin 🔬

`SdfComposer.cameraBytes(x, y, z, yaw, pitch, aspect)` is the push-constant block, and `primaryRay` inside the
composed fragment is the projection: screen `uv` → `(2u−1)·aspect`, `(2v−1)`, through `focalLength`, pitched
then yawed. Nothing anywhere exposes the **forward** direction — world point → screen uv — in Java.

**Cost:** anything composited over a marched viewport has to re-derive it. For the calculator that is the axis
tick labels, the hover probe's anchor and the crop handles. The arithmetic is easy; the problem is that it
becomes **a second implementation of one convention, one in Java and one generated into SPIR-V, with nothing
holding them together.** A mismatch does not fail loudly — labels drift off their axes as the camera turns,
which reads as a rendering glitch and is miserable to chase.

`vexelray-gui-plot.Camera` does not help: it is deliberately **orthographic**, and the march is a pinhole. Two
different projections, and the marched one is the one drawing the picture.

**Framework answer:** `SdfComposer.project(worldX, worldY, worldZ, camera…) → uv` beside `cameraBytes`,
derived from the same expression the fragment is built from, so the two cannot disagree. It is a small addition
and it is the difference between one source of truth and two.

---

## FN-13 · Nothing has ever rendered a `ConeField` 🔬

`ConeFieldTest` has five tests: the output is SPIR-V, `spirv-val` accepts it, it is byte-identical for an
8-cone and a 200-cone stroke, `pack` agrees with `floatsFor`, and every cone lies inside its group's bounding
sphere. All good tests. **None of them draws anything.**

vexelray ships `StrokeMarchSmoke` for exactly this reason on the *compiled-in* path — its README makes the
argument outright: *"'nothing renders' is three questions a window cannot tell apart — a shader that draws
nothing, a good shader with the camera pointed elsewhere, or a draw that never happened. It runs the first in
isolation and counts the pixels that are not sky."* There is no equivalent for `ConeField`.

Likewise, `GuiApp.storage` and the push-constant form of `SampledColorTarget.renderInto` have no consumer
anywhere in either repo. **The calculator is the first program to run any of this**, which is why the design's
M1 is a single straight line marched into a target and nothing else.

**Framework answer:** a `ConeMarchSmoke` in `vexelray-technique-sdf`, the same shape as `StrokeMarchSmoke` —
pack a known curve, march offscreen, count the non-sky pixels. Cheap, and it converts "should work" into
"does".

---

## FN-14 · A marched viewport cannot appear in a headless capture 🔬

`GuiApp.capture` is `static`, and it builds its **own** `VulkanInstance` and `VulkanDevice` for the occasion. A
`SampledColorTarget` comes from a `GuiApp` **instance**, on that application's device. The two are different
devices, and `GuiApp.viewport`'s javadoc is clear about what that means — *"a target allocated on a different
device yields a descriptor set this application's pipeline cannot bind."*

So a capture of a tree carrying a viewport draws `AtlasTexture.placeholder`, not the scene. It does not fail;
it produces a picture that is *correct about the chrome and silently wrong about the content*, which is the
failure mode worth naming.

**Cost:** the calculator's visual regression story splits in two — chrome by `GuiApp.capture`, plot by `shot`
over the automation socket against a running window. That is workable and arguably more honest, but it means
the one thing this application is *about* has no CI-shaped proof.

**Framework answer:** an instance-scoped capture — `app.capture(path)` on the live device, which
`WindowControls.capture` already almost is (it photographs the window, so it captures the viewport correctly;
what it needs is to work without the window being mapped). Worth noting that `HarnessApp` creates real windows
and never shows them, so most of that machinery exists.

---

## FN-15 · `Palette`'s colour model is relational; a hand-authored design is not 🔬

Fitting the prototype's fifteen colours to a `Palette` (`Look.java`, `LookTest`) landed every **lightness**
within 0.011 and missed **chroma** in two places, both for the same underlying reason: the model derives colour
from relationships, and a designer picking values by eye does not.

- **Surfaces gain chroma as they climb.** Every rung goes through `Oklab.atLightness`, which scales chroma *in
  proportion to lightness* — so a ladder rising from a tinted page grows more saturated. The prototype's
  surfaces hold a roughly constant tint, so `PANEL` comes out ~0.010 bluer than `#232532`.
- **The ink ramp cannot make a cool grey from a neutral ink.** `text(n)` blends ink→page, so its chroma is
  bounded by its endpoints. The prototype's ink is essentially neutral (chroma 0.005) while its greys hold
  0.019–0.030 at every lightness — two families, one anchor. Reaching the greys needs an ink at chroma 0.029,
  which renders `#e4e8fe`: **primary text goes visibly periwinkle to make secondary text the right blue.**

Both are fixable by moving an anchor, and both fixes were measured and rejected because they trade an exact,
large-area colour for an exact, small-area one. What is in the code is: anchors true, derived levels drifting
by an amount `LookTest` **asserts in both directions**, so a change that improved either would fail the build —
because it would mean an anchor moved.

**Cost:** small and cosmetic here. Worth recording because the framework's palette doc argues the relational
model as a strength (*"secondary text is a relationship rather than a colour"*) and it genuinely is — but the
first consumer to fit a designed palette to it found the seam within an hour, in the two places where the
relationship is imposed rather than observed.

**Framework answer, if any:** none urgent. If it recurs, the smallest honest change is for chroma to be its own
declared behaviour on the ladder (constant, or proportional) rather than always proportional — one boolean on
`Palette`, defaulting to today's behaviour.

---

## Carried over, to re-verify 📋

Facts from the previous build of this application. They were true then; they have not been re-checked against
the current source, and each should be confirmed the first time it matters rather than trusted.

- **`Node.children(...)` appends** — it is a loop over `append`, not a replace, so "swap what is mounted" by
  re-calling it silently leaves the old child in the tree. Use `visible(false)`, which `FlexLayout` skips
  entirely (not placed, not measured, **not counted toward gaps**). 🔬 *re-verified this session — still a
  plain append.*
- **`GuiWindow.CAPACITY_FLOATS`** is one vertex buffer for a whole window, at 23 floats a vertex and 6 vertices
  a quad. 🔬 *re-verified: 4 M floats ≈ 30 000 quads, and over-budget now truncates with one warning rather
  than throwing out of the render loop.* Any generated-geometry feature must count itself against it, and the
  windowed path **cannot be exercised headlessly** (`GuiApp.capture` sizes its own buffer), so a regression
  only shows up when the app is run for real.
- **There is no public measurement of a string not in the tree**, so anything sized to its own text has to
  estimate — but a node that *has* been laid out publishes exact metrics at `node.layout().text()`, carrying
  the absolute x of every character boundary plus `offsetAt(x, y)`. Reach for that before estimating.
- **Nothing lays the tree out until something renders it**, so `layout()` is absent through a capture's tick
  loop. A capture that needs measured geometry has to render once, settle, and render again.
- **`TextField.text(String)` clears the undo history** by contract. Every whole-entry replacement must be
  `select(0, len)` + `insert(...)`. Clearing first is two entries, and the first Ctrl+Z lands on an empty field
  the user was never shown.
- **`mvn clean test`, not `mvn test`**, whenever checking that a new test catches the bug it was written for —
  incremental compilation here does not reliably pick up a main-source edit, which produces a green run against
  deliberately broken code.

---

## Observations, not findings 💡

- **`Gui` is a god object and its own decomposition plan says so, with numbers.** `docs/gui-decomposition.md`
  measures it at 2 193 lines and 91 public methods, up 264 lines since the plan was written, with nothing
  extracted. From a consumer's side this is mostly invisible — a facade with 91 delegates is an index — but it
  shows up in discoverability: finding out that there is no `onWheel` took reading the whole method list,
  because there is no `gui.input()` to look inside. The plan's §5 (`gui.metrics()`, `gui.nav()`, …) would fix
  the discoverability as a side effect of fixing the class.
- **The widget shelf is the best thing in the framework.** `Rail` (a rail is not tabs turned sideways, and
  *none selected* is a legitimate state), `Inspector`/`Property` (declare the schema, let the layout be a
  consequence, and no switch anywhere), `Popout` (popping out is not a reparent, and here is exactly what
  diverges if you pretend otherwise) — each of these is better reasoned than the equivalent in Qt, WinUI or
  SwiftUI, and the javadoc explains *why* rather than *what*. Whatever else changes, this is the bar.
- **`Surface` → `Cones` → `ConeField` → `GuiApp.storage` → `renderInto` is a chain built one link at a time
  with this consumer written down in each javadoc**, and reading the five in order reads like a plan rather
  than like five separate decisions. `ConeField`'s opening paragraph is a post-mortem of the previous build of
  this calculator ("*building a pipeline from it was measured at five seconds — on the frame loop*") and
  `renderInto`'s says outright "*what the calculator demo could not do was exactly this*". The gap between "the
  seams exist and are argued for" and "anything has run them" is FN-13, and it is the only thing between this
  and a very good story.
- **The prototype and the shelf already agree**, down to `"sss"` and `Solid | Additive | SSS` appearing as the
  worked examples in `Property` and `Segment`. That is either excellent foresight or a happy accident, and
  either way it means this application is mostly wiring — which is the strongest possible evidence that the
  framework is at the right altitude.

---

## FN-16 · The atlas decides the typography, and it is baked into the framework jar 🔬

`vexelray-text` bakes one MSDF atlas at build time: **face 0 Noto Sans, face 1 Noto Sans Mono**. `GuiApp` loads
exactly one atlas, by the fixed classpath name `/dev/vexelray/text/atlas/primary.{json,png}`.

The prototype sets **Inter** and **IBM Plex Mono**. The proportional/mono *split* is right and available; the
typefaces are not, and an application cannot pass a font — it has to shadow that classpath resource with its own
generated atlas (the `vexelray-msdf-maven-plugin`, two font files, and a committed 1 MB PNG). Which works, and
the previous build of this calculator did exactly that for STIX.

**Cost:** deferred here, and it is the one part of the look a capture will not match.

**Framework answer:** none needed — shadowing is a legitimate mechanism and it is documented. Recording it so
that "why does it not look like the design" has an answer, and because *one atlas per process, by fixed name*
is worth knowing before an app has two windows that want different faces.

### The re-measurement, since it decides what an icon can be

The charset in `vexelray-text/pom.xml` asks for arrows, mathematical operators, box drawing, geometric shapes
and dingbats. The built atlas holds **1112 glyphs**, and above U+2000 it has General Punctuation, currency and
the whole Letterlike block — plus, of Mathematical Operators and Geometric Shapes, **exactly two**: U+2212 (−)
and U+25CC (◌). Noto Sans simply does not cover the rest, and a charset range is a request, not a guarantee.

So `→ ⊞ ▦ ∿ √ ≡ ∂ ∫ ⌫ ⊥ ≈` all draw as a box. This bit immediately: the prototype's subtitle is
`x → (Re, Im)` and it renders as `x □ (Re, Im)`. **Check a codepoint against the atlas before using it:**
`unzip -p vexelray-text-*.jar dev/vexelray/text/atlas/primary.json | grep -o '"unicode":<decimal>,'`.

This is the measurement behind [FN-5](#fn-5): there is no symbol vocabulary to build icons from, so every icon
in every application is hand-drawn `Picture` geometry.

---

## FN-17 · `Node.border` is all four edges 🔬

`border(Length, Color)` paints the whole box's inside edge; there is no per-edge form, and `padding` is
similarly symmetric-only (`padding(Length)` or `padding(vertical, horizontal)`).

The prototype's expression field is a transparent box with **one rule under it**, which is a common enough shape
— an underlined input, a section divider, a bar with a rule on one side.

**Cost:** small and structural. The underline is a 1px child box in a column, which is one extra node and reads
fine. The alternative — a bordered box with three edges painted the background colour — was rejected because it
lies to anything reading the tree, and because it stops being the background colour the moment the thing sits on
a different surface.

**Framework answer:** it is genuinely unclear that per-edge borders are worth the prop-key cost. The narrower
ask that covers most real cases is a `Node.rule(Edge, Length, Color)` — one edge, drawn like the border, no
box model change. Noted rather than asked for.

---

## FN-18 · The offscreen path cannot exercise a `ConeField` 🔬

`OffscreenRenderer.render(device, w, h, vertexSpirv, entry, fragmentSpirv, entry, vertexCount, clear…, push)`
has **no descriptor-set parameter**. `ConeField` reads its geometry from a storage buffer bound at set 0, so the
one headless render-and-read-back path in the stack cannot run it at all.

That matters because of what it takes away. `StrokeMarchSmoke` exists for the *compiled-in* surface path and
its argument is the strongest one in the repo — *"'nothing renders' is three questions a window cannot tell
apart… it runs the first in isolation and counts the pixels that are not sky."* There is no equivalent
available for the data-driven path, so a blank `ConeField` frame has to be diagnosed through a window, which is
precisely the situation that argument says not to be in.

**Cost:** M1 was proven by running the real application and photographing it over the automation socket. That
worked, and it is a weaker instrument: it could not have separated "the shader draws nothing" from "the camera
is pointed elsewhere" from "the buffer never uploaded".

**Framework answer:** one more parameter — `long descriptorSet` and `long[] setLayouts` on
`OffscreenRenderer.render` — and then a `ConeMarchSmoke` beside `StrokeMarchSmoke`. Small, and it closes
[FN-13](#fn-13) at the same time.

---

## FN-19 · `SdfScene.Rgb` says linear; the pipeline never encodes 🔬

`SdfScene.Rgb`'s javadoc is explicit: *"Linear, not sRGB: shading arithmetic is only correct in a linear
space."* True of the arithmetic. But **nothing downstream applies an OETF**: there is no `pow`, gamma or
`linearToSrgb` anywhere in `SdfComposer` or `Shadings`, and `SampledColorTarget`'s attachment is
`VK_FORMAT_R8G8B8A8_UNORM` rather than `_SRGB`, so the format does not apply one either. Whatever the fragment
writes lands in the texel verbatim, and the GUI's canvas samples it verbatim.

So a colour dutifully converted to linear on the way in renders **about 2.2× too dark**, uniformly. The first
marched frame of this application is the evidence: the sky was near-black where `#161826` should have been, and
the curve sat two shades under its own accent. Neither reads as a colour-space bug; it reads as "the plot is
dim", which is a much longer hunt.

**What the calculator does:** passes display components straight through (`Look.scene`), and pins it with a test
that asserts *identity*, so anyone "fixing" it back to a linearisation fails the build and reads the note.

**Framework answer:** pick one and make the other true. Either the composed fragment encodes on the way out
(and the doc is right), or the doc says these are display-space values and shading is approximate. The present
state — a documented contract the pipeline does not honour — is the one option that costs every consumer the
same afternoon.

---

## FN-20 · What the march actually costs, measured 🔬

The number [FN-11](#fn-11) is about. Measured on this machine with the Probe (`-Dprobe=all`, lane `gpu`, span
`plot.march`), auto-orbiting so the camera changes every frame, one canned 420-point helix (419 cones):

| target | steps | march, mean |
|---|---|---|
| 1024×640 | 128 | 34.50 ms |
| 1024×640 | 48 | 28.68 ms |
| 768×480 | 48 | 18.32 ms |
| **640×400** | **64** | **14.21 ms** |
| **640×400** | **48** | **13.39 ms** |
| 512×320 | 48 | 9.02 ms |
| 512×320 | 48, 140 pts | 4.14 ms |
| 256×160 | 48 | 3.45 ms |

Three readings, and the third is the one that matters:

1. **Pixels dominate.** Quartering the pixel count is a 3.8× speedup; cutting the step budget from 128 to 48 is
   17%. The march is bandwidth- and occupancy-bound rather than step-bound, at least at this cone count.
2. **Cones matter at low resolution and not at high.** At 512×320, cutting 419 cones to 139 nearly halves the
   time; at 1024×640 the per-pixel cost swamps it. `ConeField`'s group culling is doing real work — 419 cones
   is 53 groups and the loop is over groups — but there is no spatial hierarchy above that.
3. **The shipped default is 640×400 at 48 steps: 13.39 ms.** That is **80% of a 60 Hz frame spent on the plot
   alone**, serially, on the GUI thread, because `renderInto` blocks. The plot is a quarter of the window's
   linear resolution and upscaled by the sampler — which, at this tube thickness, still looks clean.

**The point of FN-11 in one line:** 13 ms of GPU work is not the problem — 13 ms of *stalled GUI thread* is.
Were `renderInto` asynchronous, the same work would overlap the tree's own draw and present and cost latency
instead of frame rate, and 1024×640 would be affordable. The fix named in `SampledColorTarget`'s own source
comment is worth roughly a doubling of the plot's resolution.

*(An app-side mitigation exists and is not yet taken: march at 30 Hz while orbiting and let the GUI run at 60.
Recorded so it is a decision rather than an oversight.)*

### And one integration trap, found by a measurement that lied

A `-D` on the `mvn` command line sets a **Maven** property. `exec:exec`'s `commandlineArgs` is a fixed string,
so it reaches the forked JVM only if the pom names it. The first sweep run here changed resolution, step budget
and cone count across seven configurations and reported the same number every time — because all seven runs
were byte-identical. Fixed with an `${app.jvmArgs}` pass-through in the pom, and worth knowing before trusting
any measurement taken through `exec:exec`.

---

## FN-21 · Shading is per scene, so a plot cannot have both lit tubes and flat lines 🔬

`SdfScene` carries one `Shading` for everything in it. `Shadings.defaultKeyLight()` is
`albedo × (max(0, N·L) × 0.92 + 0.08)`, so a round tube always reaches roughly full albedo somewhere along its
lit side, whatever its albedo is.

That is right for the curve — the shading is what makes it read as a tube in space rather than a flat ribbon,
and it is most of why the marched plot looks better than a drawn one. It is wrong for the furniture: a grid
line wants to be a faint, even mark, and a lit one has a bright edge and a dark edge and reads as another
object in the scene rather than as a scale behind it.

`SdfScene.albedo`'s own javadoc already names the gap — *"Giving any surface its own material still waits on
the material matrix of docs/vexel-world.md §2"* — so this is a known future rather than a surprise.

**Cost:** the grid's albedo had to be pushed most of the way down to the page (`surface(1)`) to stop it
competing with the curve, and even then every grid line carries a highlight. The first version of the scene had
three grid planes at `LINE` and looked like a cage with a curve inside it. What shipped is one floor plane at a
near-page albedo.

**Framework answer:** the material matrix, when it comes. The narrow version that would have solved this is a
per-`Surface` unlit flag — the shading model already has an `unlit()` and the composer already branches on
`usesLights()`, so the machinery mostly exists one level up from where it is needed.

---

## FN-22 · The aspect ratio belongs to the node, not to the render target 🔬

`SdfComposer.cameraBytes(…, aspect)` corrects the ray fan for a non-square image, and the obvious value to pass
is the target's own `width / height`. **That is wrong whenever the target is not displayed at its own aspect** —
which is the normal case, because `GuiApp.viewport(w, h)` has fixed pixels and the node showing it is laid out
by flex.

`Node.image` draws the image across the whole border box, so a 640×400 target in a 1180×675 node is *stretched*.
The shader maps its `uv` square onto whatever that box turns out to be, so the aspect it must correct for is the
**box's**. Pass the target's and every circle in the plot comes out about 9% wide, uniformly — which does not
read as a bug at all, it reads as a slightly odd camera.

The fix is small (`March.aspect()` reads `viewport.layout().rect()`) and it has a consequence worth stating: a
**resize now has to re-march** even though nothing about the camera moved, because the aspect the shader was
given is stale. That is a dirty-flag case that is easy to miss, and missing it leaves the plot holding whatever
proportions the window had when it was last turned.

**Framework answer:** none required — this is an application getting its own arithmetic right. Worth recording
because the trap is entirely invisible: nothing errors, nothing looks broken, and the one symptom is a shape
that is subtly not the shape it should be. A line in `GuiApp.viewport`'s javadoc would save the next consumer
the hunt, since that method is where the fixed-size-target-in-a-flexed-box arrangement is introduced.

---

## FN-25 · `ConeField` silently discards `Surface.Stroke`'s per-vertex colour 🔬

**The most valuable finding so far, because nothing tells you.**

`Surface.Stroke` advertises per-vertex colour as a feature, and its javadoc is specific: *"This vertex,
coloured. The stroke's colour gradients between neighbouring vertices."* The record even validates that colour
is all-or-nothing across a stroke, *"because a colour gradient between a colour and an absence has no
meaning"* — which is a strong signal that colour is carried.

Through `ConeField` it is not:

- `Cones.Cone` is `(ax, ay, az, ar, bx, by, bz, br)` and `Cones.FLOATS` is **8**. The flattened buffer has no
  colour channel at all.
- `ConeField.compose` calls `SdfComposer.fragmentSpirv(scene, sdfFunction(...), **null**)` — no albedo
  function — so the fragment shades every hit with the scene's single `albedo()`.

So a plot built from painted strokes renders in **one flat colour**, and the only variation is lighting. There
is no error, no warning, and — this is the part that cost the time — the picture looks *plausible*: thin tubes
at grazing angles come out paler than fat ones, so a grid drawn in one colour and a curve drawn in another
still look like two different things. I built a colour ramp, a four-way ramp picker and per-vertex painting for
the curve, the axes, the ticks and the grid, watched all of it render, and only found out when a deliberate
switch to an achromatic ramp changed nothing.

**What the calculator does instead:** the colour is the scene's `albedo`, which is compiled in, so a colour
change is a new module and a new pipeline. Affordable *only* because `ConeField`'s module is a fixed size —
**measured at 55–67 ms**, against the five seconds an unrolled scene costs. Acceptable for a control clicked
occasionally; not for anything continuous. The `.painted(...)` calls are left in `Geometry` deliberately: they
are correct, they cost one call each, and the day the buffer carries colour the plot lights up with no change
here.

**Framework answer, and this is the one request that would most improve the picture:** carry colour in the
buffer. `Cones.FLOATS` 8 → 12 (`ar`/`br` already pad each half to four), an albedo function in
`ConeField.compose` that reads it, and `Cones.of` propagating what `Spine` already interpolates. Until then,
**`Surface.Stroke`'s colour contract should say it is honoured by `SurfaceCompiler` and dropped by
`ConeField`** — a doc line is worth an afternoon to every consumer.

---

## FN-23 · `Rail` exposes no handle to a tile, so its buttons are unaddressable 🔬

`Rail` hands out `node()` (the icon column) and `panel()`, and keeps its per-item tiles private. There is no
way to landmark "the layers button", and the tiles carry `role("rail-item")` with **no accessible name** —
their content is an application-supplied icon, which for this framework means a `Picture`, which has no text.
So `find layers` matches nothing and a driver cannot click a rail item by name.

**Workaround, and it only works by luck:** the icon *is* the application's node, so a landmark on it resolves
to a box inside the tile whose centre is the tile's. That is what `Panels.tile` does. A rail whose tiles were
built by the widget rather than handed to it would be undrivable.

**Framework answer:** `Rail.tile(String key)` returning the `Node`, the same way `Tabs` hands out `bar()` and
`pages()` "to style, not restructure". Or — better, and consistent with `Rail`'s own title/tooltip handling —
give the tile the item's `title` as its accessible name, so `find Layers` works with nothing added.

---

## FN-24 · `settle` cannot see a lazily-built panel 🔬

Documented rather than broken — `automation.md` §5 says outright that `settle` *"cannot see a handler still
running on a worker: that handler has published nothing yet, so nothing reports it owed."* Worth recording
because the shape it takes in practice is a trap a script author will hit on their first rail:

```
click rail.color      # Rail builds the page inside the click handler, on a worker
settle                # returns: no frame is owed *yet*
find Ash              # "nothing matches" -- and it is intermittent
```

The page is built the first time it is shown (`Rail`, `Tabs` and `Popout` all do this, for the good reason
that a rebuilt page comes back inert). So the first `find` inside any lazily-built panel races, and it races
*intermittently*, which is the worst kind. Two `settle`s usually work and are not a guarantee.

**What works:** retry the `find` until it matches, or `await` on a landmark inside the page — which is the
framework's own answer and needs the page to carry one.

**Framework answer:** `Rail`/`Tabs`/`Popout` could build the page on the GUI thread from the drain rather than
in the handler, which would put it inside the frame `settle` waits for. Failing that, one line in
`automation.md` naming lazily-built pages as the case where `settle` is not enough.
