# Calculator — technical design

**Status: proposal, for review. No code written.**
*Rev 2 — the plot is a ray-marched `Surface.Stroke`, not a `Picture`. See §7.*

A plot viewport built on vexelray-gui, and the reference implementation of what a client application of that
framework looks like. This document says what is being built, which framework component supplies each part of
it, what the application has to write for itself, and — most importantly — the handful of places where the two
do not meet cleanly.

Framework findings and integration gotchas accumulate in **[framework-notes.md](framework-notes.md)**; this
document links to them rather than restating them.

---

## 0. Scope, as agreed

- **The prototype is the whole application.** `docs/plot-viewport.html` — an expression bar, a full-bleed
  viewport, an icon rail with six panels, a hover probe, a camera readout. There is no keypad and no history
  tape. Expressions arrive by keyboard or paste, and nothing else.
- **No evaluation.** The algebra is not ready. The plot is **hardcoded scene data**, and every path that would
  one day call an evaluator goes through one class (§7.5) so that switching it on is deleting a file rather
  than threading a new dependency through the app.
- **Look and feel is the deliverable.** The measure of success is that a screenshot of the running application
  is indistinguishable from the prototype, and that orbiting it is smooth while something slow runs on a
  worker.
- Work happens on `main`. No framework changes without asking first.

### What "hardcoded" means precisely, and the one place I am proposing to bend it

A frozen picture cannot tell you whether the controls feel right — a slider that moves nothing is a slider you
cannot evaluate. So the canned scene is **parametric in the controls the panels expose**: the ω slider, the
domain bounds, the sample count, the camera. The *shape* is hardcoded (a helix for the default expression, a
ripple for the two-variable case); what the panels do to it is live.

That is trigonometry over a fixed parametric form, not an evaluator, and it lives entirely in `Canned.java`. If
you would rather the picture never move at all, it is a one-line change and worth saying now rather than
discovering in review.

---

## 1. The prototype, read precisely

Everything below is taken from the markup, not from memory of it.

### Palette

| | |
|---|---|
| page | `#161826` |
| panel | `#232532` at 86–93% over the canvas |
| well / inset | `#161826` (fields sit at page level, sunken against the panel) |
| line | `#3f424d` |
| line, strong | `#595d6c` |
| ink | `#e9e9ed` |
| ink, secondary | `#b2b6ca` |
| ink, dim | `#9397ab` |
| ink, faint | `#75798c` |
| accent | `#9184d9` |
| accent, light | `#b5abfc` |
| accent, lighter | `#d2cefd` |
| accent surface | `#2b2741` / `rgba(43,39,65,·)` |
| accent border | `#5d5294` |

Two faces: **Inter** for labels and prose, **IBM Plex Mono** for every number, the expression, and every badge.
The split is not decorative — a monospace column is what makes a stack of readouts legible, and the badges are
letterspaced small-caps mono precisely so they read as *state* rather than as text.

### Regions

1. **Canvas** — full bleed, `cursor: grab`.
2. **Top-left cluster** (22.4 / 16.8 px in): `f =` label, a 238 px transparent input underlined in `#3f424d`,
   an `AUTO LINE` badge; below it a `CROP` notice when crop mode is on, an error line when there is one, and a
   subtitle describing what kind of plot this is.
3. **Left rail** (16.8 px in, vertically centred): a floating 8-icon card — Layers, Domain & range, Crop,
   Color, Sampling & readout, View, Controls, then a rule and a Reset-view crosshair — and, when a panel is
   open, a 284 px panel beside it at up to 78 vh, with a header (title, hint, close) over a scrolling body.
4. **Bottom-right readout**: `az — el — zoom — <samples>`, mono, pointer-transparent.
5. **Probe**: a floating three-line bubble that follows the pointer to the nearest sampled point.

### Panels

| Panel | Contents |
|---|---|
| **LAYERS** | Five cards — volume, surface, line, axes, grid — each a switch, a name, an `AUTO` badge on the detected one, an opacity slider **in the header**, and a fold. Bodies: material segments, slice/wire/width ranges, side-wall and drop-line flags, tick and label settings, per-plane grid flags. A dashed footnote: *"Layers are independent. A surface can also draw as lines; a volume as a surface."* Header hint: `n on`. |
| **DOMAIN & CROP** | ω range; aspect segment (Cube / Fit each axis); crop-handles flag; X, Y and Z bounds as number fields; Z auto-fit. Actions: Symmetric ±3, ±2π, Unit box, Fit line to X, Re-fit Z to data. |
| **COLOR** | Colour-by segment (Height / Position); ambient-glow flag; four colour maps as swatch strips — Blurple, Indigo, Steel, Ash. |
| **SAMPLING & READOUT** | Grid range 12–110 step 2, displayed `n²`; numbers segment (Rational / Decimal); decimal places 1–8. |
| **VIEW** | Auto-orbit flag. Actions: Iso, Top, Front, Side, Fit, Reset. |
| **CONTROLS** | The key list below, as `key → what` rows. |

### Gestures and keys

| | |
|---|---|
| drag | orbit — azimuth free, elevation clamped to ±75° |
| shift + drag | pan |
| scroll | zoom about the centre |
| hover | snap the probe to the nearest sampled point |
| `R` / `F` | reset view · fit to frame |
| `T` / `S` | top view · front view |
| `space` | toggle auto-orbit |
| `C` | crop mode |
| `enter` | commit the expression |

---

## 2. Ground rules

1. **Look in `vexelray-gui-widget` before writing anything.** This cost a whole rebuild last time. §3 is the
   result of doing it first.
2. **The GUI thread only drains, lays out and presents.** Nothing the application *computes* runs on it. The
   one thing that unavoidably does is the march itself, and §5.4 says why and what it costs.
3. **Colour is a `Role`**, except inside a `Picture` (which carries resolved `Color` by the framework's own
   rule) and inside a `Surface` (which carries linear `Rgb`, because shading arithmetic is only correct in a
   linear space).
4. **Every length is a `Length`.** `em`/`rem` for anything sized by or containing text; `dp` for chrome that
   should not grow with zoom. The prototype's px values are read as `dp` for gutters and `rem` for type.
5. **Anything the framework could own, the framework owns.** Where the calculator has to write something that
   looks general, it goes in `framework-notes.md` as a candidate rather than being quietly kept.

---

## 3. What the framework already supplies

This is the headline result of the study, and it is a better result than I expected: **the widget shelf covers
essentially the entire panel vocabulary of the prototype, down to the wording** — `Property`'s javadoc uses
`"sss"` as its example value and `Look` / `Sampling` as its example sections; `Segment`'s uses
`Solid | Additive | SSS`; `Inspector.card` is described as "a heading with a switch and a badge on it, and a
body of rows that folds away", which is the layers panel exactly.

| Prototype element | Framework component | Module |
|---|---|---|
| Icon rail + swappable panel, none-selected legal | `widget.Rail` — `item`, `action`, `hint`, `panelWidth`, `titles(Tooltip)` | `-widget` |
| Panel body: sections, labelled rows, aligned value column | `widget.Inspector` | `-widget` |
| Layer card: switch, name, badge, fold | `Inspector.card(title, badge, on, toggle)` | `-widget` |
| `Solid │ Additive │ SSS` | `Property.choice` → `widget.Segment` | `-widget` |
| Sliders with a value readout | `Property.range` → `widget.Slider` | `-widget` |
| Number fields (domain bounds) | `Property.number` → `widget.NumberField` | `-widget` |
| Flags (Shading, Wireframe, XY plane…) | `Property.flag` → `widget.Toggle` | `-widget` |
| Colour-map strips | `Property.swatches` | `-widget` |
| Expression input | `widget.TextField` | `-widget` |
| Rail icon tooltips | `widget.Tooltip` | `-widget` |
| Window chrome, title, caption buttons | `widget.TitleBar` + `Decorations.CLIENT` | `-widget`, `vexelray-os` |
| Placement + zoom remembered across runs | `core.app.WindowMemory` | `-core` |
| **The curve, the axes, the grid — as 3D geometry** | **`Surface.Stroke`** | `vexelray-surface` |
| **That geometry as numbers, not shader code** | **`Cones.of` / `Cones.flatten`** | `vexelray-surface` |
| **A march that reads geometry from a buffer** | **`ConeField` + `SdfScene` + `MarchSettings`** | `vexelray-technique-sdf` |
| **The buffer, on the app's device** | **`GuiApp.storage(floats, binding)`** | `-core` |
| **The target the march renders into** | **`GuiApp.viewport(w, h)` → `SampledColorTarget`** | `-core` |
| **A box that samples it** | **`Node.image(SampledImage)`** | `-core` |
| Axis tick labels over the marched image | `draw.Sketch` / `Picture`, `Node.picture(…)` | `-draw` |
| Motion, and a clock to hang it on | `krono.KronoGui` → Kronometer | `-krono` |
| Input | `tactroller-atchung` → `InputTopics.INPUT`, `State<PointerState>` | tactroller |
| Every message between threads | `atchung-core` — `Topic`, `Pump`, `State<T>` | atchung |
| Driving the real app: click, type, drag, screenshot | `automation.Automation` + `AutomationServer` | `-automation` |
| One correlation log for a run | `sibarum.probe.Probe`, `csvview` | atchung |
| Interaction tests against a real frame loop | `harness.HarnessApp` | `-harness` |

### The reuse worth calling out

**`ConeField` was built for this consumer, and its javadoc says so.** *"A curve of a few hundred segments
lowers to some hundreds of kilobytes of SPIR-V, and building a pipeline from it was measured at five seconds —
on the frame loop, which is one thread for every window, so the whole application stopped for it."* That is the
previous build of this calculator, and `ConeField` is the answer: the same march with the cones read from a
storage buffer, so **the shader is the same bytes for every scene, the pipeline is built once when the window
opens, and a new expression is a buffer copy.** `ConeFieldTest.isIndependentOfItsGeometry` is what holds it.

**`Surface.Stroke` is not a polyline, it is the curve.** Per-vertex radius (which is the Width slider),
per-vertex curvature, and **per-vertex colour that gradients between neighbours** — which is the colour ramp,
for free, as a property of the geometry rather than as a thing the renderer has to apply. Its guarantee is the
one a plot needs: *every vertex you name lies on the centre line of the rendered shape, at every curvature*,
because the corner control point is solved for rather than used as a Bézier handle.

`GuiApp.storage`'s own javadoc names the motivating case; `SampledColorTarget.renderInto`'s says outright:
*"What the calculator demo could not do was exactly this: composite a marched region into a window that also
holds a legend and a status bar."* This project is the consumer these three seams were designed against, and
none of them has run yet ([FN-13](framework-notes.md#fn-13)).

### What is no longer used, and why

**`vexelray-gui-plot` is dropped from the dependency list.** Its `Camera` is deliberately **orthographic**,
with a good argument — *"a surface is read by comparing heights, and making the far side smaller than the near
side makes that comparison a lie."* The march is a **pinhole with a focal length** (`SdfScene.focalLength`,
default 1.4). The two cannot both be the projection, and the marched one wins because it is the one drawing the
picture. `Expr`/`Enclosure`/`Framing` were never going to be used before there is something to evaluate.

That leaves a hole: the labels drawn over the image must be positioned by the *march's* projection, and there
is no Java-side twin of it. See [FN-12](framework-notes.md#fn-12) — this is the finding I am least comfortable
with.

---

## 4. What the application must write

The honest list. Nothing here is a framework failure; most of it is application work by definition.

1. **The window and the application edge** — `Demo.java`'s pattern: input backend, coordinate space, clipboard,
   window memory, close gate, pacing, wakes. ~200 lines, and it is the part a reference implementation exists
   to demonstrate.
2. **The palette** — nine Oklab anchors derived from §1's hexes, as one `Palette`.
3. **The icons.** Eight rail marks, drawn as `Picture` geometry. The MSDF atlas carries no symbol glyphs, so
   this is not a choice ([FN-5](framework-notes.md#fn-5)).
4. **The scene builder** — canned samples → `Surface.Stroke`s → cones → packed floats. §7.
5. **The march driver** — pipeline once, buffer on change, push constants per frame. §7.3.
6. **The label projector** — a Java twin of the shader's camera. §7.4, [FN-12](framework-notes.md#fn-12).
7. **The overlay clusters** — expression bar, readout strip, probe bubble. Plain nodes, floated.
8. **The action rows** (Iso / Top / Front / …). No framework component;
   see [FN-3](framework-notes.md#fn-3).
9. **The layer card's header slider.** `Inspector.Card` has no room for one;
   see [FN-2](framework-notes.md#fn-2).
10. **The scene model and its reducers**, and the canned data behind them.

---

## 5. Architecture

### 5.1 Module and build

One Maven module, `dev.vexelray.demo:calculator:0.1.0-SNAPSHOT`, modelled on `vexelray-gui-demo`'s pom:
OS-activated platform profiles, `exec:exec` with `--enable-native-access=ALL-UNNAMED`, a profile-gated `native`
profile, and a `-Pprofiler` profile that sets the Probe defaults.

| Dependency | Why |
|---|---|
| `vexelray-gui-widget` | the shelf; brings `-core` and `-draw` |
| `vexelray-gui-krono` | motion at the application edge |
| `vexelray-gui-automation` | the driving socket |
| `vexelray-surface` | `Surface.Stroke`, `Cones` |
| `vexelray-technique-sdf` | `ConeField`, `SdfScene`, `MarchSettings` |
| `tactroller-atchung`, `tactroller-clipboard` | input and paste |
| `vexelray-gui-harness` | *test scope* — interaction tests against a real loop |

`vexelray-vulkan` arrives transitively through `-core`; the application still names no Vulkan type beyond the
three handles the framework hands it (`SampledColorTarget`, `StorageBuffer`, `GraphicsPipeline`).

> ⚠ **Build order.** The whole stack must be `mvn install`ed before this project builds: atchung → supirvast →
> tactroller → kronometer → vexelray → vexelray-gui → calculator. `vexelray-surface`, `vexelray-technique-sdf`
> and `-harness` are the ones most easily missed.

### 5.2 Packages

**One package, `dev.vexelray.demo.calculator`.** Rev 1 proposed `model/`, `march/` and `view/` subpackages;
that was the wrong call for a fifteen-class application and it is worth saying why rather than quietly changing
it. Java's default access is *package*-private, and it is the right default here — nothing in this app is API.
Splitting into subpackages would have forced `public` onto every type and method that crosses a boundary, which
is a worse outcome than a flat package: it turns "internal" into "published" for the sake of directory tidiness.

```
Calculator.java     main: the application edge, per Demo.java
Ui.java             builds the tree; owns nothing
Look.java           the Palette, the Theme, and the app's own Roles           [M0]
Type.java           the two faces and the type scale, read off the prototype  [M0]
Bar.java            expression, AUTO badge, subtitle, error, crop notice      [M0]
Readout.java        az / el / zoom / cones — hit-inert, name carries state    [M0]
Capture.java        headless PNGs of the chrome (never of the plot: FN-14)    [M0]
Landmarks.java      every automation landmark, in one place                   [M0]
March.java          target, storage buffer, pipeline, one renderInto a frame  [M1]
Geometry.java       Scene → List<Surface.Stroke> → cones          (worker)    [M1]
Canned.java         ★ the whole of the fake. Deleting this is the integration [M1]
Lens.java           the Java twin of the shader's camera            ★ FN-12   [M2]
Labels.java         axis names and tick numbers, as a Picture over the image  [M2]
Ticks.java          round numbers on an axis, shared by Geometry and Labels   [M2]

still to come:   Model.java + Edit.java (State<Scene> and its reducer), Panels.java (Rail + six
                 Inspectors), Icons.java (eight Pictures), Probe.java (the pointer-following bubble)
```

### 5.3 State: one `State<Scene>`, changed by relative edits

The framework's resolved decision 11 is explicit about this, and the reason it gives is the one that bites:
*recomputing the whole value from a stale read yields a coherent result with a keystroke missing, and nothing
reports it.* So:

- `Model` owns a `sibarum.atchung.State<Scene>`.
- Every control commits an **`Edit`** — `SetOmega(v)`, `ToggleLayer(id)`, `Orbit(dYaw, dPitch)`,
  `Commit(expression)` — which the reducer resolves against whatever the current value is. A concurrent change
  costs a CAS retry, never a lost edit.
- Readers take a `Versioned<Scene>` snapshot, lock-free, from any thread.
- `Inspector.refresh()` is how the panels catch up with a change made somewhere else. `Property`'s
  getters-not-values contract makes this automatic; the panels never have to be pushed to.

### 5.4 Threading, and the one honest problem

| Thread | Runs |
|---|---|
| **GUI (main)** | `waitEvents` → pump input → `krono.tick()` → **the march** → drain mutations → layout → compute → publish → emit → present |
| **`gui.handlers()` worker pool** | every handler; every `Edit` commit; `Geometry` — sampling, stroke building, `Cones.of`, `Cones.flatten`, `ConeField.pack` |
| **Automation** | one loopback connection, publishing input on the bus like any device |

**The split is between geometry and camera, and it is exactly the right one.**

- **Geometry** — everything that depends on the expression, the domain, the layers or the sample count —
  is CPU work producing a `float[]`. It runs on a worker, on change only, and lands as one
  `StorageBuffer.update(...)`, which is a memcpy.
- **The camera** is six floats of push constant. Orbiting, panning and zooming **repack nothing** — no
  resampling, no stroke rebuild, no shader work of any kind. That is what makes the orbit smooth, and it is a
  structurally better answer than reprojecting a picture every frame.

So *"the GUI should be lightning fast regardless of how much processing we do in the background"* holds by
construction: a scene that takes a second to sample orbits at full rate throughout, because it is orbiting the
previous buffer.

**The problem.** `SampledColorTarget.renderInto` submits a one-time command buffer and **blocks on a fence, on
the calling thread**, before returning. Its own source comment says so and names the fix as future work. So one
march per frame is one synchronous GPU round-trip on the GUI thread, inside the frame — the single place where
the framework's own "the GUI is never blocked" promise is not yet structurally kept. This is
[FN-11](framework-notes.md#fn-11), and it is the top item in §16.

What the application can do about it, in order:

1. **March only when something changed.** Render-on-demand already parks a still window; a still *plot* under a
   still camera needs no march either. This makes the cost zero at rest and a per-frame cost only while
   orbiting — which is the case that matters, so it is a mitigation and not a fix.
2. **Size the target for the march, not for the window.** The target has fixed pixels and the sampler upscales,
   so a 960×600 march in a 1400×900 viewport is a legitimate quality knob rather than a compromise — and the
   prototype already exposes the control for it (Sampling → Grid).
3. **Spend the `MarchSettings.steps` budget deliberately** — the default 128 is Fathom's, tuned for a dungeon,
   not for a curve in an empty box.

**It will be measured under `-Dprobe=all` before M4 closes, and the number recorded in this section.** If a
frame cannot afford it, the answer is the framework fix (keep the pool and fence alive across frames, sample
once the fence signals, double-buffer the target) rather than anything clever here.

### 5.5 The frame loop

Following `Demo.main` almost exactly, because that file *is* the specification of an application edge:

```java
Gui gui = new Gui();
gui.minSize(Length.em(46), Length.em(30));
gui.theme(Look.THEME);
KronoGui krono = KronoGui.attach(gui);

try (Tactroller input = openInput();
     GuiApp app = new GuiApp(memory.config("main", "Calculator", W, H).decorations(Decorations.CLIENT));
     Clipboard clipboard = openClipboard(gui)) {

    March march = new March(app, model);          // target + storage + pipeline, built once
    Ui ui = new Ui(gui, krono, model, march);

    attachInput(input, gui, app);                 // CLIENT space, density left at 1.0 — see FN-8
    ui.titleBar().controls(app.controls());
    memory.watch("main", app.window(), gui);
    app.input(Calculator::windowInput);
    AutomationServer.start(new Automation(gui, app.controls()));

    app.pacing(() -> Math.min(krono.kron().sleepTimeout().nanos(), memory.nanosUntilSettle()))
       .idleRefresh(200_000_000L)
       .maxFrameRate(16_666_666L);
    gui.onWork(app::postWake);
    krono.kron().onWork(app::postWake);

    app.run(gui, 0, () -> {
        pump(bridge);
        krono.tick();      // input first, then the clock — the other order costs a whole frame of latency
        march.frame();     // buffer copy if the geometry changed; one renderInto if anything changed
        memory.poll();
    });
}
```

Two orderings in there are load-bearing and both are documented mistakes elsewhere: **input before the tick**,
and **`onWork` wired before parking** (without it `sleepTimeout()` returns `FOREVER` and the window freezes
while the animation runs perfectly on a kernel nobody is ticking).

---

## 6. Look

One `Palette` from nine Oklab anchors, following `Palette.DARK`'s own method — pick page, ink, accent, action,
danger and depth, then choose the ladder `step` and `fade` so the derived levels land on the prototype's
authored values:

| Role | Should resolve to | Ladder level |
|---|---|---|
| `PAGE` | `#161826` | `surface(0)` |
| `WELL` | ≈ `#161826` | `surface(-1)` |
| `CHROME` | ≈ `#1d2030` | `surface(1)` |
| `PANEL` | `#232532` | `surface(2)` |
| `RAISED` | ≈ `#2a2d3c` | `surface(3)` |
| `LINE` / `TRACK` | `#3f424d` | `surface(4)` |
| `EDGE` | `#595d6c` | `surface(6)` |
| `INK` | `#e9e9ed` | `text(0)` |
| `DIM` | `#b2b6ca` | `text(1)` |
| `FAINT` | `#9397ab` | `text(2)` |
| `ACCENT` | `#9184d9` | accent anchor |

`#75798c` and the accent tints become app-declared roles — `Role` is an open functional interface
(`Role tint = p -> p.accent().toColor(0.14f)`), so these theme with everything else and no table has to be
extended. **A fit of the anchors will be done and the residuals recorded here**; where the ladder cannot land on
an authored value, the authored value wins and the divergence is written down rather than absorbed.

**Two colours cross into the scene**, and they must be converted rather than passed: `SdfScene.Rgb` is
**linear**, and the palette is sRGB. The sky is the page colour, so the marched image and the window agree at
the edges; the curve's ramp stops are the accent family. A missed conversion here reads as "the plot is a
slightly different purple from the UI", which is exactly the kind of thing that is never quite noticed and
never quite right.

**One visible difference from the prototype, and it cannot be closed today.** The rail card and the panel use
`backdrop-filter: blur(10px)` / `blur(14px)`. Backdrop blur samples neighbouring pixels, so it is a multi-pass
effect and is explicitly deferred at the engine seam. The panels will be translucent — `Color` carries alpha —
but not blurred, and translucent-over-a-plot without blur is busier than blurred. If it reads badly the
fallback is a higher alpha, and that is a look decision for review.
See [FN-7](framework-notes.md#fn-7).

---

## 7. The viewport

### 7.1 One node, three props

```java
viewport.image(march.target())     // the marched scene    — drawn between background and border
        .picture(labels)           // the tick labels      — drawn over the image, under the border
        .background(page);         // what shows through   — and what the sky is cleared to
```

That composition is the framework's own: a viewport *is not a node kind*, it is a box that samples, so it sizes
by flex and its corner, border, clip and opacity apply to the scene for free. A picture on the same node draws
over the image and under the border, which is exactly where labels belong.

### 7.2 The geometry is `Surface.Stroke`s, and all of it goes in one buffer

| Layer | Geometry |
|---|---|
| line | one `Stroke` through the sampled points, radius = the Width slider ÷ 2, per-vertex colour from the ramp |
| grid | one thin `Stroke` per grid line, per enabled plane |
| axes | three thin `Stroke`s, plus tick marks as very short ones |
| box | twelve `Stroke`s along the render volume's edges |
| surface *(later)* | one `Stroke` per row and per column of the sample grid — a marched wireframe |

**They are all in one `Surface.Union`, and therefore one buffer and one march.** This is the part that is
strictly better than a drawn picture: occlusion is correct without being managed. A grid line behind the curve
is *behind* it, because the ray hit the curve first — no depth sort, no painter's order, no per-cell
bounding-box approximation.

Sampling the joints: consecutive samples on a dense curve are close, so vertices are **sharp**
(`curvature = 0`), which emits no corner sub-cones at all — a 500-sample curve is 500 cones rather than 4 500.
The tapered round cone between neighbours already reads as a smooth tube.

**Budget.** `ConeField.floatsFor(n) = 8 + 8n + 4·ceil(n/8)`. The storage buffer cannot be resized (the pipeline
was built against its descriptor set layout), so it is sized once for a declared worst case:

| | cones | floats |
|---|---|---|
| default line scene (curve 500 + grid ~150 + axes/box ~60) | ~710 | ~6 100 |
| line at the maximum sample count | ~1 300 | ~11 000 |
| surface wireframe at 46² | ~4 100 | ~35 000 |
| **declared ceiling** | **32 768** | **278 536** (≈ 1.1 MB) |

Anything that would exceed the ceiling is refused with a message rather than truncated — a plot missing a
quarter of its geometry with no notice is the one outcome worth engineering against.

### 7.3 Driving the march

Once, at startup:

```java
SampledColorTarget target = app.viewport(MARCH_W, MARCH_H);
StorageBuffer      cones  = app.storage(ConeField.floatsFor(MAX_CONES), ConeField.BINDING);
List<ComposedShader> pair = ConeField.compose(SdfScene.of(placeholder).withShading(...).withMarch(...));
GraphicsPipeline   pipe   = target.pipelineFor(vertex.spirv(), ENTRY, fragment.spirv(), ENTRY,
                                               SdfComposer.CAMERA_BYTES,
                                               new long[]{ cones.descriptorSetLayout() });
```

The scene handed to `compose` is a *placeholder* — `ConeField` does not compile `scene.surface()` at all; the
field comes from the buffer. Everything else about the picture (shading, march settings, albedo, sky, focal
length) *is* read from it, so those are compile-time and a change to them is a rebuild. **Shading and march
settings are therefore not live controls**, which is worth knowing before a panel promises otherwise.

Per frame, in `beforeFrame`:

```java
if (geometryChanged) cones.update(packed, packed.length);       // memcpy
if (anythingChanged) target.renderInto(pipe, 0, cones.descriptorSet(), 3,
                                       SdfComposer.cameraBytes(ex, ey, ez, yaw, pitch, aspect),
                                       sky.r(), sky.g(), sky.b(), 1f);
```

Vertex buffer `0` and vertex count `3`: the fullscreen triangle the vertex stage synthesises from
`gl_VertexIndex`. `ConeField.compose` pairs the stages itself, deliberately — *"paired with the stage that
writes only `gl_Position` the input is simply never written, every pixel marches the same ray, the frame comes
out one flat colour, and the module is still perfectly valid SPIR-V."*

### 7.4 `Lens` — the Java twin of the shader's camera

The tick labels, the probe's anchor point and the crop handles all have to land where the marched image puts
their world positions. The shader's `primaryRay` is a pinhole: screen `uv` → `(2u−1)·aspect`, `(2v−1)`, through
`focalLength`, pitched then yawed. `Lens` is its inverse — world → uv — written from the same convention.

**It is a second implementation of one fact, and nothing holds the two together.** A mismatch does not fail; it
shows up as labels drifting off their axes as the camera turns, which is subtle, intermittent-looking, and
awful to chase. Two things bound it: `Lens` is pure and unit-tested against hand-computed cases, and a
**round-trip check runs in the capture scenes** — mark a known world point, assert the drawn label lands within
a pixel of where a reference sample of the image says the geometry is. That is a weaker guarantee than one
implementation would give, and it is the best available from this side.
[FN-12](framework-notes.md#fn-12) is the framework answer: `SdfComposer.project(...)` beside `cameraBytes(...)`,
derived from the same source.

### 7.5 `Canned` — the whole of the fake

```java
/** Scene data until there is an evaluator. One class, one seam, deliberately. */
final class Canned {
    static Reading read(String expression);              // → mode + parameters + a refusal, if any
    static double[] sample(Reading r, Scene s);          // → world-space points, xyz interleaved
}
```

`read` is a small table of recognised expressions:

| Input | Mode | Subtitle | Shape |
|---|---|---|---|
| `e^(i·ω·x/2)` *(default)* | LINE | complex output · space curve  x → (Re, Im) | helix, radius 1, pitch from ω |
| `sin(x)·cos(y)` | SURFACE | real output over x, y · height field | ripple |
| anything else | LINE | — | the default shape, error line reading *"no evaluator yet — showing the reference scene"* |

That last row matters: it exercises the error affordance honestly rather than leaving it dead. **A picture of a
different expression than the one in the field is the one outcome this must never produce**, so the refusal is
loud and the subtitle always describes what is actually drawn.

### 7.6 Resize

`GuiApp.viewport` is explicitly *not* resized for you, and the reason is sound: re-marching is far too
expensive to trigger from a resize the framework merely noticed. So the policy is the application's:

- The sampler upscales, so a resized viewport is soft, not broken, and needs nothing immediately.
- On `onResize` (the worker lane — this is not the picture case, so a frame's lag is invisible), if the box has
  changed by more than 25% in either axis, mint a new target at the new size and close the old one **after a
  frame that no longer names it**.
- The label `Picture` rebuilds on `onResizeUi` — same frame as the layout, because a picture is authored in
  pixels for the box that was measured and is clipped to it.

### 7.7 The probe

The prototype's probe follows the pointer to the nearest sampled point, with no delay. `widget.Tooltip` is a
different thing on purpose — anchored to a control's box, delay-armed, and *"once shown it never moves"*. So
the probe is application-built: a floating, `hitInert` node with three text children, positioned with `floatAt`
from a `PointerMoved` subscription, faded with `OPACITY` on a Kronometer ramp. Its anchor comes from `Lens`:
project every sample, take the nearest in screen space.
See [FN-4](framework-notes.md#fn-4).

### 7.8 Overlay parenting — the detail that decides whether the gestures feel right

The prototype's overlay containers are `pointer-events: none` with `pointer-events: auto` on the controls
inside, so a drag that misses a control still orbits. `Node.hitInert` cannot express that: it covers the whole
subtree with no way back out.

The framework's own mechanism gives the same result — **pointer events bubble leaf→root**, and `HitTest`
returns the deepest node under the point whether or not it has a handler. So:

- The **expression cluster**, the **readout** and the **probe** are floating children **of the viewport node**.
  A press on their empty space finds no drag handler, bubbles to the viewport, and orbits. The prototype's
  behaviour, from parenting rather than from a property.
- The **rail card** and the **panel** are floating children of the viewport too, but they are solid surfaces
  and must *not* pass a drag through. They get an empty `onDrag` registration, which stops the bubble.

That empty handler is a wart: [FN-9](framework-notes.md#fn-9).

---

## 8. Panels

`Rail` with seven `item(...)`s and one `action(...)` for Reset view. Crop is an `item` in the prototype's rail
but toggles a mode rather than opening a panel, so it is an `action` here — and the `CROP` notice in the
top-left cluster is what tells the user it is on, exactly as designed.

Each panel is an `Inspector`. The properties map one-for-one onto §1's table; nothing needs a custom
`Property` except:

- **The layer opacity slider**, which the prototype puts in the card *header*.
  `Inspector.Card` offers title, badge, switch and fold and no header slot. Options: (a) put opacity as the
  first row of the card body — a real behaviour change, since it is then hidden when folded; (b) write a
  `Property` that renders a whole card; (c) ask for `Card.headerControl(Node)`. **I recommend (a) for the first
  pass** and raising (c) — [FN-2](framework-notes.md#fn-2).
- **The action rows** (Iso / Top / Front / Side / Fit / Reset, and the five domain presets). A wrapping row of
  outline buttons, written here, and a candidate for the framework's own `Toolbar`
  ([FN-3](framework-notes.md#fn-3)).

**Two controls the panels must not promise.** `Material` and the march quality are compiled into the fragment
(§7.3), so changing them rebuilds a pipeline. For the first pass they are shown and change the *albedo and
step budget only* — which is what most of the visible difference is anyway — and anything genuinely needing a
recompile is deferred rather than made to stutter. Flagged here because a segment that looks live and is not is
worse than one that is absent.

---

## 9. The expression bar

A `TextField`, transparent, underlined, monospace. Enter commits; blur commits; Escape reverts.

The commit path is the one place the fake is visible from the UI, and it must behave like the real thing will:
`Commit(text)` → `Canned.read` → the reducer settles a new `Scene` → mode, subtitle, `AUTO` badge and the error
line are all derived from it. Nothing in the view holds a second copy of the mode.

Undo: `TextField` keeps a `History` and binds Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y while focused. **Every whole-entry
replacement goes through `select(0, len)` + `insert(...)`, never `text(String)`** — `text` clears the history by
contract, which is right for a document loaded over the top and wrong for anything the user just did.

---

## 10. Input

Orbit and pan are `gui.onDrag(viewport, …)` committing an `Orbit` / `Pan` edit; `gui.cursor(viewport, GRAB)`
declares the affordance (and degrades to the arrow, because the engine has no hand cursors yet — E5).

Every key is a **claim**, not a handler — `gui.claim(root, Shortcut.of(Key.R), ClaimScope.GLOBAL, …)` and so on
for F, T, S, C and space. Claims are how this framework does preemption, and using them means the focused
expression field outranks the single-letter shortcuts for free, without the bar knowing they exist. That is the
whole reason `R` in a text field types an `r`.

**Wheel zoom needs the bus.** `InputDispatcher` routes a scroll edge to the nearest *scrollable* ancestor and
drops it otherwise, so a notch over the viewport goes nowhere. The way in is
`gui.bus().subscribe(InputTopics.INPUT, …)` and a hit-test of the viewport's own `layout()` rect against the
event's coordinates — the same space the dispatcher uses, so nothing is converted.
`Gui.onWheel(node, handler)` would be the honest API and does not exist:
[FN-1](framework-notes.md#fn-1).

---

## 11. Motion

Kronometer, through `KronoGui`, ticked once per presented frame from `beforeFrame`.

| What moves | How |
|---|---|
| Auto-orbit | a `Cell<Double>` for azimuth, `drive`n by a linear curve. Pure, so a **`Signal`, not a `Shred`** — precomputed ahead, off the baton, zero handoffs |
| Orbit inertia after a drag | `Animator.retarget` with `Ease.OUT_CUBIC` — decelerating into rest is what reads as weight |
| Camera presets (Iso / Top / …) | `retarget` on yaw and pitch over `ms(220)`, `OUT_CUBIC` |
| Panel open / close | `Rail`'s own transition, timed through the `Ramp` seam by `KronoGui.ramp` |
| Toggle knobs | `Toggle`'s `Ramp`, likewise |
| Probe fade | `OPACITY` over `ms(90)`, matching the prototype's `.09s` |

Every one of these that moves the *camera* is free: it changes six push-constant floats. That is the payoff of
§5.4's split, and it is why auto-orbit can simply be left running.

Two rules from the framework's own experience are adopted rather than rediscovered: **fade linear, travel
eased**, and **schedule through `kron.onTimeline(...)`** from every handler, because handlers are on workers
and the timeline is single-threaded.

---

## 12. Automation

The socket is wired from the first milestone. `automation.md` names the calculator as the intended proving
ground for the whole instrument, and it is far cheaper to keep it working than to make it work later. It also
becomes the *primary* way the plot is verified, because a headless capture cannot show it (§13).

**Landmarks are the contract.** A ref is a per-run id; a landmark is the name that still means something
tomorrow. All of them live in `Landmarks.java`:

| Landmark | On |
|---|---|
| `expr` | the expression field |
| `mode` | the AUTO badge — its *name* is the detected mode, so `await mode LINE` works |
| `status` | the subtitle line |
| `error` | the error line |
| `viewport` | the node carrying the marched image |
| `readout` | the az/el/zoom strip — its name carries the numbers, so a script asserts against them |
| `rail.layers` … `rail.help` | the eight rail buttons |
| `panel` | the open panel |
| `probe` | the probe bubble |

`await <landmark> <text>` is how a script waits for readiness, with no application-specific hook: readiness is
declared by writing it into a landmark's accessible name.

> **One thing to remember, or every command will answer `ok` and nothing will happen:** a render-on-demand loop
> has three reasons to wake and injected input is none of them. `Gui.wakeForInput()` exists for exactly this
> and must be called on the injection path.

---

## 13. Testing

**The marched plot cannot be verified by headless capture, and that is a fact about the framework rather than a
choice.** `GuiApp.capture` is `static` and builds its *own* `VulkanInstance` and `VulkanDevice`; a
`SampledColorTarget` minted by a `GuiApp` instance belongs to a different device, so a capture of a tree
carrying one draws the placeholder texture instead ([FN-14](framework-notes.md#fn-14)). Four tiers, arranged
around that:

1. **Pure unit tests, no GUI.** `Model`'s reducer, `Lens`'s projection against hand-computed cases,
   `Geometry`'s stroke and cone counts against the declared ceiling, `Canned.read`'s table, tick-label
   formatting, the elevation clamp. The majority, and fast.
2. **A march smoke, in the spirit of `StrokeMarchSmoke`.** Pack a known curve, march it into an offscreen
   target, read the pixels back, and **count the ones that are not sky.** "Nothing renders" is three questions
   a window cannot tell apart, and this answers the first one in numbers. It is also the first test anywhere
   that proves `ConeField` draws a picture rather than merely valid SPIR-V
   ([FN-13](framework-notes.md#fn-13)).
3. **Tree tests** — build the tree, publish input, step frames, assert on the published `LayoutSnapshot` and
   `SemanticSnapshot`. The framework's `HeadlessGui` does exactly this and is **test-private in `-widget`**, so
   the calculator writes its own copy: [FN-10](framework-notes.md#fn-10).
4. **Interaction tests** with `HarnessApp` — real loop, real Vulkan, windows created and never shown. The one
   question no unit test can ask: *after this gesture, does a frame arrive on its own?* Needs a GPU, so local
   rather than CI.

**Visual record**, split by what each medium can actually show:

| | Medium | Shows |
|---|---|---|
| `panel-<name>` × 6, `bar`, `smallest`, `zoom-<n>` ladder | `GuiApp.capture` | all the chrome — and the viewport as a placeholder, which is expected and must be stated in the file name so nobody reads it as a broken plot |
| `plot-<scene>` | `shot` over the automation socket, on the real window | the marched picture |

The `smallest` capture earns its keep: *photograph the minimum, not just the ordinary size*, because a minimum
chosen by eye stops being right the first time a row is added. The zoom ladder is the em check — every step
should be the previous one scaled, and anything holding its pixel size is still pinned to the device grid.

> ⚠ **Always `mvn clean test`, never `mvn test`, when checking that a new test catches the bug it was written
> for.** Incremental compilation in this toolchain does not reliably pick up a main-source edit, which produces
> a green run against deliberately broken code — a false negative on the one check that matters.

---

## 14. Build and run

```bash
mvn compile exec:exec                                  # windowed
mvn compile exec:exec "-Dapp.args=--capture panels"    # headless PNG (chrome only)
mvn -Pprofiler compile exec:exec                       # + probe=all, csv
printf 'find expr\ntype sin(x)\nkey ENTER\nsettle\nshot plot.png\nquit\n' | nc localhost 7654
```

> ⚠ `app.args` is split on whitespace only with the `commandlineArgs` pom form (as in `vexelray-gui-demo`); the
> older argument-element form makes a multi-word value one token. And a Unicode `−`, `÷` or `π` in
> `-Dapp.args` is mangled by the console codepage before it reaches `main` — pass ASCII, or hardcode the case.

---

## 15. Milestones

Each ends with a screenshot and a green build.

| | | Proof |
|---|---|---|
| **M0** | Skeleton: pom, window, `TitleBar`, palette, window memory, input, clipboard, Krono, automation socket, captures | an empty page in the prototype's colours; `tree` over the socket |
| **M1** | **The march, end to end**: target, buffer, pipeline, a hardcoded straight `Stroke` in the box | the march smoke counts non-sky pixels; `shot` shows a line |
| **M2** | The scene: helix, axes, grid, box, colour ramp, `Lens` labels | `shot` beside the prototype |
| **M3** | Chrome: expression bar, AUTO badge, subtitle, error, readout strip | `find expr`; `await mode LINE` |
| **M4** | Rail + six panels: icons, `Rail`, `Inspector`, every property bound; **the march budget measured** | six panel captures, driven over the socket; a probe CSV with the frame cost in it |
| **M5** | Interaction: orbit, pan, wheel zoom, probe, every claim | a driving script + one `correlations.csv` |
| **M6** | Motion: auto-orbit, panel transitions, probe fade, presets, crop mode | the zoom ladder and the state shots |
| **M7** | Retrospective: `framework-notes.md` finalised, `driving-the-preview.md` written (automation.md §5 links to it) | — |

M1 is deliberately first and deliberately trivial in content: it is the riskiest thing in the project (§16.1),
and the cheapest version of it that can fail is one straight line.

---

## 16. Concerns, stated up front

Fourteen findings are in [framework-notes.md](framework-notes.md). Five want a decision *before* code:

1. **[FN-13] Nothing has ever rendered a `ConeField`.** Its tests prove the SPIR-V is valid, geometry-
   independent and correctly packed — none of them proves it draws. There is a `StrokeMarchSmoke` for the
   compiled-in path and no equivalent for this one, so **the calculator is the first program to run it**, and
   M1 exists to find out early. If it does not work, that is engine work and I will stop and tell you rather
   than working around it.
2. **[FN-11] `renderInto` blocks on a fence on the GUI thread.** One synchronous GPU round-trip per marched
   frame, inside the frame loop. The source comment already names the fix (persist the pool and fence, sample
   once it signals). Mitigable from here (§5.4) but not fixable from here, and it is the one thing that could
   make an orbit feel worse than the prototype's canvas.
3. **[FN-12] The march camera has no Java twin.** Anything drawn over the image — tick labels, the probe, crop
   handles — re-derives the projection by hand, and a drift shows up as labels sliding off their axes while you
   orbit. `SdfComposer.project(...)` beside `cameraBytes(...)` is a small, obviously correct addition.
4. **[FN-14] The marched viewport cannot appear in a headless capture**, because `GuiApp.capture` builds its
   own device. Workable — chrome by capture, plot by `shot` — but it means the visual regression story for the
   thing this app is *about* depends on a running window.
5. **[FN-10] No published headless GUI fixture.** `HeadlessGui` is 200 lines of exactly what every application
   needs and is test-private in `-widget`. My copy will drift from yours and keep passing when your dispatch
   changes. `-harness` is already shipped for this reason and is the natural home.

And one thing that is not a concern: **the widget shelf, and the fact that `Surface`, `Cones`, `ConeField`,
`GuiApp.storage` and `SampledColorTarget.renderInto` were each designed with this consumer written down in
their javadoc.** Reading those five in sequence is reading a plan; the thing being asked for here is the last
link in a chain that was built deliberately, one piece at a time, over months. That is rare and it is worth
saying.

---

## 17. Open questions

1. **Perspective is now the projection.** The march is a pinhole with a focal length; `plot.Camera`'s
   orthographic argument — *"making the far side smaller than the near side makes height comparison a lie"* —
   is real and now does not apply. The prototype looks perspective to me and you have chosen `Surface`, so I am
   proposing a long lens (focal length ~2.5 rather than the default 1.4) to keep the distortion mild. Confirm?
2. **Compiled-in vs live controls.** Material, shading model and march quality are compile-time in the fragment
   (§7.3). First pass makes the *visible* part of them live (albedo, steps) and defers the rest. Acceptable, or
   should those controls be hidden until they can be honest?
3. **Application name and settings key.** `"Calculator"` and `vexel-calculator`? Window memory is keyed by it,
   so changing it later orphans saved placement.
4. **Design size and minimum.** The prototype declares 1180×720. I propose a minimum of `em(46) × em(30)` —
   below that the rail plus a 284 px panel leaves no plot.
5. **§0's parametric fake** — live ω, or a completely frozen picture?
6. **Does `Export SVG` belong here?** With the geometry marched it is no longer nearly free (it would need a
   separate `Picture` path), so my answer is now clearly no. Noting it because rev 1 said otherwise.
