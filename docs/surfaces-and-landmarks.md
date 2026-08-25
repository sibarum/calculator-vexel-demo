# Surfaces and landmarks

*Scoped, then built. Where the two diverged the text below says what was built and why the plan changed;
nothing here is aspirational.*

Two enhancements to the calculator's plotting, scoped together because they share one substrate and pull it in
two different directions. **Surfaces** widen the domain an expression is evaluated over — from a column of x to
a cell of (x, y) — and **landmarks** ask the arithmetic a new kind of question about the answer: not "what is
the curve doing here" but "where are the places worth pointing at". Both land mostly in `vexelray-gui-plot`,
because both are pure, and the standing rule for this pair of repos is that anything pure belongs to the
framework module.

Three things move at once and it is worth naming them separately up front:

| | what changes | where it lands |
| --- | --- | --- |
| **A. Surfaces** | two variables, drawn in 3D | module: `Cell`, `Volume`, `Camera`; demo: `SurfacePlot` |
| **B. Landmarks** | roots, extrema, inflections, poles, and a tooltip | module: `Expr.derivative`, `Landmarks`; demo: markers, hover, influence |
| **C. Previews** | every `=` opens its own window | demo: `Previews` |

---

## A. Two variables, as a surface

### A1. The domain becomes a cell

`Expr.enclose(Interval column)` is the single-variable substrate: a node encloses itself over a column of x, and
`Param` answers with the column *whatever its name is*. That last clause is the thing that has to go. With two
variables the answer depends on which parameter is asking, so the column becomes a **cell** — a binding from a
parameter's name to the interval it ranges over:

```java
public interface Cell {
    Interval of(String parameter);
    static Cell column(Interval column);                                  // every name → the column
    static Cell of(String a, Interval ai, String b, Interval bi);         // a surface's two axes
}
```

`Expr.enclose(Cell)` becomes the primary operation and `enclose(Interval)` a default that wraps the column in
`Cell.column`, so every existing call site and every existing test is unchanged in meaning. Each built-in node
forwards the cell where it used to forward the column; `Param` looks its own name up. **This is a source-breaking
change to the `Expr` interface** — a node written outside the module implements `enclose(Cell)` now — and it is
worth taking rather than bolting a parallel two-variable evaluator alongside the one-variable one, because two
evaluators is two things to keep in agreement and the whole point of `Interval` doubling as "the column" was to
avoid exactly that.

Nothing about soundness changes. An enclosure over a cell contains every value the expression takes anywhere in
that cell, for the same reason and by the same arithmetic; the dependency problem is unchanged; a divisor whose
range straddles zero is still a pole, now a pole *somewhere in a patch* rather than somewhere in a column.

### A2. The viewport becomes a volume

`Frame` is the rectangle of plot space on show and owns the conversions above it — which column of x a pixel
column covers, where a y falls down the height. `Volume` is its 3D counterpart: `x ∈ [xLo, xHi]`,
`y ∈ [yLo, yHi]`, `z ∈ [zLo, zHi]`, with `cell(ix, iy, nx, ny)` returning the cell of the grid square at those
indices — **computed from the indices alone**, exactly as `Frame.column` is, so the grid tiles without a seam and
a cached enclosure is a true statement about the cell it is looked up for.

### A3. Classification is unchanged, and that is the point

A cell's z-enclosure classifies into the same three answers a column's y-enclosure does: a bounded stretch
clipped to the visible extent, a fill where the arithmetic could not bound it, or nothing. So `Span` is reused
verbatim, and the only addition it needs is a `Span.of(Enclosure, double lo, double hi)` that classifies against
a bare vertical extent, with the existing `Span.of(enclosure, frame)` delegating to it. A surface renderer
implements the same three-method `Span.Sink` a curve renderer does.

### A4. What is drawn, and what is honestly claimed

The 2D plot's argument is that **a column of x classifies to a vertical span, and a vertical span is a box**, so
a reliable plot has nothing diagonal in it. The 3D generalisation is exact: a cell of (x, y) classifies to a
z-span, and a cell crossed with a z-span is an **axis-aligned box in space**. The surface is drawn as a field of
those — short and flat where the surface is tame, tall where it is steep, full-height where the arithmetic could
not bound it.

Projection is **axonometric** (`Camera`: yaw, pitch, scale — no perspective), and each cell's box is drawn as the
**screen-space bounding rectangle of its eight projected corners**. That rectangle is a conservative cover: it
contains every point of the true surface patch over that cell. So the claim the surface plot makes is per-cell
and it is a real one —

> the box drawn for a cell provably contains every point of the surface above that cell

— and it is *weaker* than the curve plot's claim, because it says nothing about the pixels between one cell's box
and the next. Two further honest limitations, stated rather than hidden:

- **Occlusion order is presentation, not proof.** Cells are drawn far-to-near, sorted on `Camera.depthKey` —
  which is a function of the *floor* alone, height deliberately left out. That is the standard heightmap order:
  right for a surface standing over a grid, and not a theorem about arbitrary boxes. It is also the reason the
  camera has no perspective; with perspective the ordering would start depending on height too.
- **The bounding rectangle over-covers.** A projected box is a hexagon; its bounding rectangle is up to twice its
  area. At the grid resolutions used this reads as a slightly chunky surface, which is the same trade the fill
  makes: over-cover rather than under-draw. (In the horizontal direction it is not even loose: `u` does not
  depend on `z` at all under this projection, so a cell's drawn *width* is exactly its projected width.)

**An unbounded cell is painted through.** On a curve an unbounded column is one pixel wide and reads as a
stripe; on a surface it is a box the full height of the volume, wide enough to hide what is behind it. So the
surface's pole colour is translucent, and at an isolated pole — `1÷(x²+y²)` at the origin — the surface around
it reads straight through the mark. Where the poles run in a *line* it still comes out as a wall, and that is
the right answer rather than a rendering failure: `1÷(x+y)` leaves every bound along the whole diagonal
`x = −y`, so the far half of the surface really is behind something infinitely tall. Turning the picture to look
along the ridge brings both halves back.

### A4a. Two layers

Boxes alone read as blocky, and the fix turned out not to be finer boxes. It was to stop asking one layer to be
both the evidence and the picture.

- The **proof** is the enclosure over each cell, drawn as a **hollow outline**. An enclosure is a claim about a
  range, so its edges are what it has to say; a filled box claims the surface is everywhere inside it.
- The **picture** is a smooth surface **bilinearly interpolated** between the cell lattice's corners, `SUB`
  pieces per cell per axis, diffuse-lit.

This is also where the honesty tension named at the top of this document actually gets resolved rather than
dodged. Interpolating *is* joining up points that were evaluated — the very thing the module refuses to do on
its own — and it is safe here for exactly one reason: **it is drawn inside the outline that contains it.** The
enclosure covers every value the surface takes on that cell, and the interpolation's own corner heights are
among those values, so the smooth layer provably cannot draw outside the honest one. Both are on screen, each
saying what it is.

It reads better than either alone, too. Where the arithmetic is tight the outline is a sliver and vanishes into
the surface; where it is loose — a steep cell, the throat of `1÷(x²+y²)` — the outline stands visibly taller
than the surface threading through it. *The picture shows you where its own evidence is weak*, which is more
than the box layer managed on its own and much more than a smooth surface would.

And it is cheaper than one fine layer. An enclosure costs interval arithmetic over `BigDecimal`; an
interpolation costs four multiplies. The proof stays at `CELLS`, the picture is drawn at `CELLS × SUB`, and the
only new evaluation is one height and one gradient per lattice **corner** — shared between the four cells that
meet there, so a lattice of `(CELLS+1)²` covers `CELLS²` cells and the surface comes out continuous across every
cell boundary rather than agreeing only approximately.

Interpolating the **gradient** as well as the height is what makes the shading continuous. It is the idea behind
shading a mesh from vertex normals, except that these normals are the surface's own analytic ones rather than an
average of whatever faces met there — so there is still no tessellation for the light to trace. Curvature is
deliberately *not* interpolated: second derivatives between corners are a difference of differences, noisy where
the surface is interesting and worthless where it is not, so the cavity term stays on the proven cells where it
is exact.

### A5. Framing, and the cache

`Framing` gains `automatic(Expr, xLo, xHi, yLo, yHi)` returning a `Volume`, fitting z by the same robust
interquartile policy it fits y with — sampled over a grid instead of a row, poles and gaps contributing nothing.
The policy itself is not re-argued; it is the same preference, applied to one more axis.

The cache generalises the same way: enclosures keyed by grid scale and then by `(ix, iy)`, so orbiting the camera
— which changes no cell at all — re-evaluates **nothing**, and only a change of x/y window or grid resolution
costs arithmetic. That is what makes the surface *retained when idle*: the picture is a tree of pooled boxes that
sits there, no repaint is scheduled unless the camera, the window or the expression moves, and orbiting repaints
from cache without touching the arithmetic.

### A5a. Shading, and why these two effects

The surface is shaded by its own calculus — a **cavity** term from curvature and a **Fresnel** term from the
angle to the eye (`Sheen`). Those two were picked over the obvious diffuse/specular pair on a specific
criterion: they are the effects a triangle renderer finds hardest, and for the same underlying reason in both
cases. *A mesh does not know its own calculus.* It has positions; normals are averaged from adjacent faces and
interpolated across each triangle, and curvature is an estimate over a vertex neighbourhood, noisy and
tessellation-dependent enough that it is normally baked offline into a curvature map.

Here both are exact and per cell, from `Expr.derivative` — the same machinery the landmarks are found with. And
the orthographic projection helps a second time: **the view direction is one vector for the whole picture**, so
the Fresnel angle is a dot product rather than a normalised per-fragment vector. Fresnel is the effect that
usually reads as a graphics bug, because it does all its work at silhouettes, which is exactly where
interpolated normals are least trustworthy — banding, shimmering, and tracing the tessellation. There is nothing
interpolated here to band.

**Mean curvature, not Gaussian.** Gaussian is the one that first comes to mind and it is wrong for a cavity: it
is zero on a cylinder, which visibly curves and visibly does occlude, and negative on a saddle in both
directions at once, so it cannot tell a valley from a ridge. Mean curvature can. (Gaussian would be the
interesting one to *read* — negative everywhere on `x²−y²`, positive everywhere on `1÷(x²+y²)` — but colouring
by it would fight the height ramp for the same channel.)

**Three things went wrong before it looked right**, each of them the physically-correct choice:

1. *Reflecting a bright sky.* A Fresnel term is largest at grazing angles, and a bright sky seen at a grazing
   angle is the **horizon** — so every steep cell, which at a 26° pitch is most of them, picked up a pale
   mid-tone and the height ramp vanished under it. The render wasn't wrong; it was a correct picture of a glass
   saddle in a white room. Fix: put the light overhead and leave the surround dark.
2. *Mixing rather than adding.* The energy-conserving form `base·(1−F) + env·F` is the physical one and it takes
   the height ramp away precisely where the reflection is strongest. A dielectric's reflection sits *on* the
   transmitted colour, so adding keeps the ramp legible underneath and the rim reads as light rather than as
   lost information.
3. *A symmetric cavity.* Occlusion only subtracts — a fold is shadowed by its own walls, a ridge is merely
   *un*occluded, which is not the same as being lit. Symmetric, it multiplied the funnel walls of `1÷(x²+y²)` by
   one and a half until they were white. A concavity may now take away up to `CAVITY`; a ridge may add back a
   third of that.

**One point evaluation, and it is worth naming.** The partials are read at each cell's *centre*, not enclosed
over the cell — the only point sampling in either renderer. Two things make it legitimate. It is shading only:
the drawn box is still the proven enclosure, and nothing here can move an edge, drop a cell or invent one. And
enclosing a derivative over a cell is *worse*, not better — near a fold that range is enormous and its midpoint
is a number with no relationship to the surface. Where a point evaluation comes back non-finite, the cell keeps
its flat colour rather than being given an invented one.

### A6. Interaction, and the one thing a surface does not have

Drag turns the picture (yaw and pitch), the arrow keys turn it a step at a time, the wheel and `+`/`−` widen and
narrow the floor, `Fit` re-fits z over the floor now on show, `Reset` returns to the framed volume and the
three-quarter view. Deliberately the same four controls as the curve plot, each doing the corresponding thing.

**There is no pan**, and this is where the plan changed. A curve is explored by sliding its window along x; a
surface has no such direction once it has been turned, because "left" on the screen is a different way through
the domain at every yaw. So the floor stays centred on the origin and the gestures divide cleanly — the drag
turns, the zoom widens or narrows, and neither has to guess what the other meant. The side effect is that the
cell grid is always centred, which is what keeps a scale reproducible and therefore cacheable.

The floor also carries no gridlines, for the reason the whole renderer exists: a line between two points of a
projected grid is **diagonal**, and there is nothing diagonal in this renderer either. Height is given instead by
a **legend** down the right-hand edge — a ramp in screen space with the volume's floor, middle and ceiling
labelled — which costs one box per band and is the only piece of furniture a surface gets.

### A7. Out of scope, said plainly

**Landmarks are not computed for surfaces.** The 3D analogues are saddle points and critical curves, the picking
problem is a different problem, and pretending otherwise would double B for a fraction of its value. A surface
plot shows the surface; a curve plot shows the surface's landmarks. This is a scope decision, not a technical
obstacle — `Expr.derivative(String parameter)` already takes the parameter to differentiate with respect to, so
partials are there when someone wants them.

**Three or more variables stays refused**, on the status line, by name, exactly as two variables is refused
today.

---

## B. Landmarks

### B1. A node differentiates itself

Extrema are roots of `f′` and inflections are roots of `f″`, so the landmark finder needs a derivative. The
module's design rule is that a node **encloses itself** rather than being walked by a closed switch, and the
derivative follows the same shape:

```java
default Optional<Expr> derivative(String parameter) { return Optional.empty(); }
```

Each built-in node differentiates itself by the ordinary rules and asks its children for theirs; a node that
cannot returns empty, and empty propagates up through any composite containing it. So the interface stays open,
and the consequence is stated rather than papered over: **a node that cannot differentiate itself contributes no
extrema and no inflections.** All twelve built-in nodes can. There is deliberately no second, derivative-free
extremum finder as a fallback — two algorithms for one question is two things to keep in agreement, and the
discrete-slope method the Pontif original used is point sampling wearing a hat.

### B2. What is looked for, and how each one is proven

Every landmark is found by the interval arithmetic proposing and a separate test disposing. The bias is the
module's own — **candidates may be over-generated, but nothing survives that was not confirmed** — which is the
opposite way round from the enclosure algebra and is right for this job: an enclosure that over-warns is
conservative, whereas a landmark that is not there is a lie drawn on the picture.

| landmark | proposed by | confirmed by |
| --- | --- | --- |
| **root** (x-intercept) | a sign change between adjacent probe points | bisection to double precision; rejected if the bracket straddles a pole |
| **y-intercept** | x = 0 in the window | `f(0)` encloses bounded |
| **minimum / maximum** | a sign change of `f′` | same bisection on `f′`; direction of the flip gives min or max |
| **inflection** | a sign change of `f″` | same again; dropped if it coincides with an extremum or a pole |
| **vertical asymptote** | a column enclosing ±∞ | the subdivision probe: the column must still hold a pole at 16× finer resolution, which kills the over-estimation smear columns flanking a real pole; then bisected on that same test |

The subdivision probe and the pole-straddle rejection are lifted from the Pontif implementation
(`pontif.plot.ptf`, 2026-07-22), where both were bug fixes rather than design: without the straddle rejection
`1÷(x²−1)` reported four bogus extrema hugging x = ±1, and without bisecting on the probe rather than on the raw
enclosure a pole at exactly 1 was labelled 0.997.

### B3. Sanity, which is a requirement and not a nicety

`tan(1÷x)` has infinitely many roots, extrema and asymptotes in any neighbourhood of the origin. So:

- a **dense** pole region — more than half of a column's sub-columns still unbounded under subdivision — is not
  marked at all. The red block already says "there is detail here finer than a pixel", and stacking a hundred
  asymptote markers on it says the same thing worse;
- each kind has a **cap**. A kind that overflows it is dropped *entirely* rather than truncated, and the fact is
  reported on the status line ("too many extrema to mark"). A truncated set of markers is a lie about which ones
  are there; an absent set with a notice is not;
- landmarks are found over the **visible x window** only, and re-found when it moves.

### B4. Shown without labels, told on hover

Markers are small unlabelled dots, coloured by kind — a box with a corner radius of half its size is a circle,
and the node vocabulary needs nothing else. Naming them on the plot is what makes an annotated plot unreadable,
and the information is one pointer-move away.

Where two landmarks land on the same spot — `x²` has a root and a minimum at the origin, `x³ − 3x` a root and an
inflection — only one dot is drawn, because a dot can only be one colour. Both are still *remembered*, and the
tooltip names both: the collapse is about what can be drawn, not about what is true.

Hovering within a few pixels of a marker opens a tooltip carrying three things. This is `x³ − 3x` at its
minimum, photographed by `--capture-plot`:

```
local minimum
x = 1    y = -2
carried by: -(3x) 75%   x^3 25%
```

The pointer arrives straight off the device bus rather than through a node handler, for the same reason the
wheel does: the markers are drawn into a pointer-transparent layer so that presses and drags still reach the
canvas underneath, which means nothing in the tree is ever going to be told it was hovered.

### B5. "Which terms have the most influence?"

The third line. The reading is **share of the nearest enclosing sum**: decompose the expression the user typed
into its top-level additive terms and give each one's `|value|` at the landmark's x as a percentage of the total.
Where the top level is not a sum — `1÷(x²−1)` — descend through the operand that contains the variable until a
sum is found, and say which subexpression is being broken down.

Two reasons this reading rather than a fancier one. It is **computed on the COTT term the user typed**, not on
the translated `Expr`, so the terms have names a person recognises (`x³`, `−3x`) rather than the plotter's
internal shape — which puts this squarely in the demo, since the demo is the only thing that knows COTT. And it
is at its most informative exactly where a plot is most interesting: at a root the terms are cancelling and the
percentages show *what* is cancelling, and next to a pole they show the denominator collapsing.

Where there is no sum anywhere down the path, the line is omitted. It is not worth inventing a decomposition for
`x³`.

---

## C. Previews always open in a new window

Today the plot is a *named* window: pressing `=` on a second expression re-plots in the window already there.
That becomes: every `=` opens a **preview of its own**, so two expressions can be compared side by side, which is
the reason to want it.

Named windows are the framework's unit of "one window however many times you ask for it", and their registry is
permanent — a name, once claimed, keeps its tree for the session. So previews are **twelve slots**,
`plot1`…`plot12`: a new preview takes the lowest slot with no window open on it, and if all twelve are open it
recycles the least recently shown. The cap is a real limit and it is what stops fifty presses of `=` opening
fifty GPU surfaces and fifty input backends; it is generous enough that nothing ordinary reaches it.

Each slot remembers its own placement, size, maximized state and UI zoom under its own key, which falls out of
the existing `WindowMemory` and is better than it sounds: arrange three previews across the desk, quit, come
back, plot three things, and they arrange themselves the same way. What is still **not** remembered is whether
any preview was open — for the reason the README already gives, that a plot window has no expression to draw at
launch.

No framework change is needed for this. `WindowMemory` keys and `GuiApp.window(key, spec)` already do all of it;
the demo gains a `Previews` class that owns the slots.

---

## Where each piece lands

**`vexelray-gui-plot`** — pure, with its tests there, and its zero-dependency pom untouched:

| file | |
| --- | --- |
| `Cell.java` | new — the region an expression is enclosed over |
| `Expr.java` | `enclose(Cell)` primary; `derivative(String)` on every node |
| `Span.java` | `of(Enclosure, lo, hi)` — classify against a bare extent |
| `Volume.java` | new — the 3D viewport, and the cell grid |
| `Camera.java` | new — axonometric projection, pure |
| `Landmark.java` | new — kind, x, y |
| `Landmarks.java` | new — the finder, the confirmations, the caps |
| `Framing.java` | `automatic(…) → Volume`, z fitted by the existing policy |

**`calculator-vexel-demo`** — everything that knows about pixels, COTT, or windows:

| file | |
| --- | --- |
| `Plottable.java` | two variables; the name travels into the expression rather than being flattened |
| `SurfacePlot.java` | new — the surface renderer, its cache, its orbit and its height legend |
| `PlotSurface.java` | landmark markers, the hover tooltip, and the widened search window behind them |
| `Influence.java` | new — the share-of-the-sum reading, over a COTT term |
| `Previews.java` | new — the twelve preview slots |
| `PlotWindow.java` | holds both renderers and shows one; one window per preview |
| `Palette.java` | four landmark colours, the height ramp, the translucent surface pole |
| `CalculatorApp.java` | one variable or two; `--capture-surface` |

**`vexelray-gui-core`** — nothing. Both features are built out of seams that already exist.

## Verification

Headless, as everything else here is. Both flags take `=EXPRESSION` (ASCII `-` and `/`, since the console's
codepage mangles `−` and `÷` before they reach `main`).

```bash
mvn compile exec:exec "-Dapp.args=--capture-plot"
```

Writes `plot.png`, `plot-zoomed.png` and `plot-landmark.png`, and prints what the cache saved at each step. The
landmarks are drawn into all three: the default `1÷(x²−1)` photographs its two asymptotes pinned to ±1, and
`=x^3-3x` photographs three roots, two turning points, and the tooltip above. The last of those is worth
noticing — a capture has no input backend and therefore no pointer, so the hover path is walked from its own
end (`PlotSurface.hoverFirstMark`) through the same device-pixel conversion a real pointer takes.

```bash
mvn compile exec:exec "-Dapp.args=--capture-surface"
```

Writes `surface.png` and `surface-turned.png`. The default `x^2−y^2` is a saddle, which is the one picture that
shows at a glance whether the projection and the painting order are both right, since it rises on one axis
exactly as it falls on the other. `=1/(x^2+y^2)` is the isolated-pole case and `=1/(x+y)` the ridge.

**The cache numbers are the claim, checked rather than asserted.** A surface capture prints:

```
  framed     1600/3200 cached      two paints, the second entirely hits
  zoomed in    14/1640 cached      a new floor scale: nearly every cell is new
  moved      1600/1600 cached      turning the picture evaluates NOTHING
  back home  1640/1640 cached      a scale visited before costs nothing at all
```

The middle line is the stronger of the two claims this design makes: an enclosure belongs to a cell of the
domain and knows nothing about where the eye is, so orbiting re-projects and re-sorts and asks the arithmetic
nothing. That is also what "retained when idle" means here — between gestures the picture is a tree of pooled
nodes with no timer behind it and nothing scheduled.

Unit tests for the module's new units live in `vexelray-gui-plot`: `DerivativeTest` (each rule against known
values, the folding, and a node that declines), `LandmarksTest` (`x²−4`, `x³−3x`, `1÷(x²−1)` for the spurious
features a naive finder invents beside a pole, `tan(1÷x)` for the cap), and `SurfaceTest` (the cell binding by
name, the grid tiling without a seam, classification against a bare extent agreeing with classification against
a frame, and the camera's two invariants).
