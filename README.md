# calculator-vexel-demo

A deceptively simple calculator built on [vexelray-gui](../vexelray-gui): a display over a flex
grid of lit, elevated keys, rendered as one batched SDF draw. Click handlers run on worker threads
and mutate the display through its thread-safe `Node` handle.

The mathematics is [cott-engine](../cott-engine) — COTT, where an identity belongs to an *operation*
rather than a value, so the same subterm gives two answers in two contexts: `x×(0÷0)` is `x` (the
operand erases) while `x+0÷0` is `1+x` (it materialises as a residue). There is one engine and no
engine key: it answers every expression the keypad can build. `1÷0` is `ω`, `2÷0` is `2ω`, `ω^ω` is
`-1`, and `i` is derived rather than adjoined. Arithmetic is exact (`1÷3` stays `1÷3`), and adjacency
multiplies (`2π`, `3(x+1)`).

The keypad has the constants `e`, `i`, `π`, the wheel's `ω`, plotting variables `x y z`, `^` for
powers, and `log(x, n)` for log base n. A rejected expression is *reported* on the status line and
never written into the field, so it can be fixed and re-evaluated without retyping.

## Plotting

**Press `=` on an expression with a variable in it and it is plotted**, in a window of its own, framed
automatically. How many free variables it has is the whole decision: none is arithmetic and passes silently,
**one is a curve** against that variable (usually `x`, and nothing depends on its being `x`), **two is a surface
in 3D** over the pair, and three or more is reported on the status line rather than half-drawn by pinning the
rest to zero. The line falls at two because there is no fourth dimension to put a third variable on.

**Every `=` opens a preview of its own**, so two expressions can be looked at side by side — which is the reason
to want it. There are twelve preview slots and a new plot takes the lowest one with nothing open on it, so
opening a plot, closing it and plotting something else is always the same window; if all twelve really are on
screen, the thirteenth recycles the one shown longest ago.

The plot is [vexelray-gui-plot](../vexelray-gui/vexelray-gui-plot), so **it is reliable rather than
sampled**: every pixel column is evaluated as a *column of x* by interval arithmetic, and what comes
back provably contains every value the expression takes there. A pole is not looked for — it is a
divisor whose range straddles zero, so it falls out of the arithmetic and is painted rather than
drawn through. `1÷x` gets a red stripe at the origin and two clean branches; `1÷(x²−1)` gets two.
Nothing here can draw a confident line through a singularity, because nothing here draws lines: a
classified column is a vertical span, and a vertical span is a box.

Drag to pan, wheel to zoom about the pointer, `+` / `−` / arrow keys / Home, and **Fit** to re-run
the framing policy over whatever x window you have panned to.

## Landmarks

The interesting places on a curve are found and marked: **roots, the y-intercept, local minima and maxima,
inflections, and vertical asymptotes.** They are drawn as small unlabelled dots — a plot with a number beside
every feature stops being a picture of a curve and becomes a table drawn over one — and **the numbers are a
pointer-move away**. Hover near a dot and it says what it is, where it is, and *what is carrying the value
there*: the expression's top-level terms with each one's share of the total magnitude, so `x³ − 3x` at its
minimum reads `-(3x) 75%   x^3 25%`. Next to an asymptote that share is the most useful thing on the screen,
because the denominator's terms cancelling is *why* there is an asymptote there.

Each one is found twice: a cheap scan **proposes** a candidate and a narrower test **confirms** it, and nothing
survives that was not confirmed. That bias is the opposite of the enclosure algebra's, on purpose — an enclosure
that over-warns is conservative, while a marker that is not there is a lie drawn on the picture. Extrema are
roots of `f′` and inflections roots of `f″`, so `Expr` grew a `derivative` alongside `enclose`, shaped the same
way: a node differentiates itself, and one that cannot says so and contributes no extrema.

Two rules keep it sane, and `tan(1÷x)` is why both exist. A **dense** pole region gets no markers at all — the
painted block already says "there is detail here finer than a pixel", and a hundred asymptote markers on top of
it say the same thing worse — and a kind with too many of them is dropped **entirely**, with a notice on the
status line, because a truncated set of markers is a lie about which ones are there and an absent set is not.

## Surfaces

Two variables draw a surface, and the argument is the curve plot's one axis further on. A column of x classifies
to a span of y, and a span is a box; a **cell** of (x, y) classifies to a span of z, and a cell crossed with a
span of z is an **axis-aligned box in space**. The surface is a field of them — flat where it is tame, tall where
it is steep, the full height of the volume where the arithmetic could not bound it.

What is drawn for a cell is the screen-space bounding rectangle of that box's eight projected corners, so the
claim is per cell rather than per pixel: *the rectangle drawn for a cell contains every point of the surface
above it*. The projection is axonometric with no perspective at all, which is not a simplification — it is what
makes the painting order a function of the floor alone, and the painting order is the whole of the occlusion.

Drag turns the picture, the wheel and `+`/`−` widen and narrow the window, **Fit** re-fits the height. There is
no pan, because a surface has no direction to be panned in once it has been turned. There are no gridlines
either, for the reason the renderer exists — a line between two points of a projected grid is diagonal, and
there is nothing diagonal here — so height is read off a legend down the right-hand edge instead.

It is **shaded by its own calculus**: every cell carries the five partial derivatives of the surface there —
exact, from the same `Expr.derivative` the landmarks are found with — and they become a cavity term from the
curvature and a Fresnel term from the angle to the eye. Those two were chosen because they are what a triangle
renderer finds hardest, and for one shared reason: a mesh doesn't know its own calculus. Its normals are
averaged from adjacent faces and interpolated across each triangle, which is why rim lighting on polygons bands
and shimmers at silhouettes — exactly where Fresnel does all of its work. Nothing here is interpolated. And
because the projection is orthographic, the view direction is one vector for the whole picture rather than a
per-fragment one.

**Turning it costs no arithmetic.** An enclosure belongs to a cell of the domain and knows nothing about where
the eye is, so orbiting re-projects, re-sorts and re-shades while asking the arithmetic nothing:
`--capture-surface` reports `1600/1600 cached` for the turn. Between gestures the picture is a tree of pooled nodes with no timer behind it,
which is what makes it retained while it is idle.

Landmarks are for curves only. The 3D analogues are saddle points and critical curves, and picking one under a
pointer is a different problem; that is a scope decision rather than an obstacle, and `Expr.derivative` already
takes the axis to differentiate along, so the partials are there when someone wants them.

Exploring is cheap because enclosures are cached per *column of x* rather than per picture. Moving in
y re-classifies and evaluates nothing at all; panning evaluates only the columns that came into view;
and zooming lands on a discrete set of scales, so going back to one costs nothing. `--capture-plot`
prints the counts — a pan of 40% of the window answers 418 of 696 columns from cache, and going home
answers all of them. A redraw is applied as one atomic batch over pooled nodes, so the old picture
stays up whole until the new one replaces it.

Two things the keypad cannot ask for: there is **no trigonometry** in COTT's vocabulary (the plot
module has `sin`/`cos`/`tan`, but no key reaches them), and **`log` is refused**. COTT's `log(x, b)`
is the base-0 exponent reading, not the logarithm of a real number — on ordinary numbers it does not
reduce at all — so plotting it as a natural log would draw a curve the calculator itself disagrees
with. That is a notation question rather than a plotting one, and it is refused by name on the status
line until it is settled.

## Prerequisites

The sibling stack installed to the local Maven repo, in order: `supirvast`, `vexelray`,
`tactroller` (+ `atchung`), `vexelray-gui`, `cott-engine`. Java 25, and a Vulkan-capable GPU to run
windowed.

## Run

```bash
mvn compile exec:exec
```

Headless capture to PNG (no GPU window / input backend needed):

```bash
mvn compile exec:exec "-Dapp.args=--capture"
```

The plot draws headlessly too — no window, no pointer, and it reports what the cache saved at each
step. Writes `plot.png`, `plot-zoomed.png` and `plot-landmark.png`:

```bash
mvn compile exec:exec "-Dapp.args=--capture-plot"
```

And the surface, writing `surface.png` and `surface-turned.png`:

```bash
mvn compile exec:exec "-Dapp.args=--capture-surface"
```

Append `=EXPRESSION` to either to plot something else (the defaults are `1÷(x²−1)` and `x²−y²`). Use ASCII `-`
and `/`, since the argument goes through the console's codepage on the way in and `Notation.normalize` maps them
anyway. `plot-landmark.png` is the hover tooltip: a capture has no input backend and so no pointer, so the hover
path is walked from its own end, through the same device-pixel conversion a real pointer takes.

The window frame is the calculator's own: `Decorations.CLIENT` extends the client area over the whole
window and a `TitleBar` -- ordinary widgets, drawn in the same palette as the keypad -- stands where the
system caption was, on the main window, the history and the plot alike. Dragging, snapping, Win+arrow,
double-click-to-maximize and the system menu are still the window manager's.

Ctrl+= / Ctrl+- / Ctrl+0 zoom the whole UI — every length is relative. In the plot window that is
separate from the plot's own zoom: Ctrl+= scales the interface, bare `+` scales the *plane*.

## Where you left it

Every window comes back where it was: position, size, maximized state, and the UI zoom, in
`~/.calculator/settings.properties` through the framework's `WindowMemory`. Placement is restored at
*creation* rather than after, so a window appears where it belongs instead of jumping there on its
first frame, and it is clamped to a monitor that still exists on the way — shrunk if the saved
rectangle no longer fits, then nudged until it is fully on screen. Zoom is restored before the first
frame too, since every `em` resolves against it.

Each preview slot keeps its own shape under its own key, which falls out of `WindowMemory` needing one key per
watched window and turns out to be the better behaviour anyway: arrange three previews across the desk, quit,
come back and plot three things, and they arrange themselves the same way.

**What is deliberately not remembered is whether the history and preview windows were open.** The
framework offers it and the text editor uses it, but a calculator has nothing to reopen them onto:
the tape is this session's, and a preview has no expression until you evaluate one. Restoring a window
to show emptiness is worse than not restoring it — there is nothing there to be where you left it.
