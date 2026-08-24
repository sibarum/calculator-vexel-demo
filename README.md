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

**Press `=` on an expression with one variable and it is plotted**, in a window of its own, framed
automatically. How many free variables the expression has is the whole decision: none is arithmetic
and passes silently, one opens the plot against that variable (usually `x`, and nothing depends on
its being `x`), and more than one is reported on the status line rather than half-drawn by pinning
the others to zero.

The plot is [vexelray-gui-plot](../vexelray-gui/vexelray-gui-plot), so **it is reliable rather than
sampled**: every pixel column is evaluated as a *column of x* by interval arithmetic, and what comes
back provably contains every value the expression takes there. A pole is not looked for — it is a
divisor whose range straddles zero, so it falls out of the arithmetic and is painted rather than
drawn through. `1÷x` gets a red stripe at the origin and two clean branches; `1÷(x²−1)` gets two.
Nothing here can draw a confident line through a singularity, because nothing here draws lines: a
classified column is a vertical span, and a vertical span is a box.

Drag to pan, wheel to zoom about the pointer, `+` / `−` / arrow keys / Home, and **Fit** to re-run
the framing policy over whatever x window you have panned to.

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
step. Writes `plot.png` and `plot-zoomed.png`:

```bash
mvn compile exec:exec "-Dapp.args=--capture-plot"
```

Append `=EXPRESSION` to plot something else (the default is `1÷(x²−1)`). Use ASCII `-` and `/`, since
the argument goes through the console's codepage on the way in and `Notation.normalize` maps them
anyway.

The window frame is the calculator's own: `Decorations.CLIENT` extends the client area over the whole
window and a `TitleBar` -- ordinary widgets, drawn in the same palette as the keypad -- stands where the
system caption was, on the main window, the history and the plot alike. Dragging, snapping, Win+arrow,
double-click-to-maximize and the system menu are still the window manager's.

Ctrl+= / Ctrl+- / Ctrl+0 zoom the whole UI — every length is relative. In the plot window that is
separate from the plot's own zoom: Ctrl+= scales the interface, bare `+` scales the *plane*.

## Where you left it

All three windows come back where they were: position, size, maximized state, and the UI zoom, in
`~/.calculator/settings.properties` through the framework's `WindowMemory`. Placement is restored at
*creation* rather than after, so a window appears where it belongs instead of jumping there on its
first frame, and it is clamped to a monitor that still exists on the way — shrunk if the saved
rectangle no longer fits, then nudged until it is fully on screen. Zoom is restored before the first
frame too, since every `em` resolves against it.

**What is deliberately not remembered is whether the history and plot windows were open.** The
framework offers it and the text editor uses it, but a calculator has nothing to reopen them onto:
the tape is this session's, and the plot has no expression until you evaluate one. Restoring a window
to show emptiness is worse than not restoring it — there is nothing there to be where you left it.
