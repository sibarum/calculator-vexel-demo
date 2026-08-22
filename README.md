# calculator-vexel-demo

A deceptively simple calculator built on [vexelray-gui](../vexelray-gui): a display over a flex
grid of lit, elevated keys, rendered as one batched SDF draw. Click handlers run on worker threads
and mutate the display through its thread-safe `Node` handle.

It is symbolic, backed by SymEngine through [symengine-panama](../symengine-panama), with a wheel
algebra: `1/0 = ω` (complex infinity) and `0/0` is the wheel bottom, with `ω+a=ω`, `0·ω=0/0`,
`1/ω=0`. The keypad has the constants `e`, `i`, `π`, the wheel's `ω`, plotting variables `x y z`,
`^` for powers, and `log(x, n)` for log base n. Arithmetic is exact (`1/3 + 1/6` → `1/2`), and
adjacency multiplies (`2π`, `3(x+1)`).

## Prerequisites

The sibling stack installed to the local Maven repo, in order: `supirvast`, `vexelray`,
`tactroller` (+ `atchung`), `vexelray-gui`. Java 25, and a Vulkan-capable GPU to run windowed.

## Run

```bash
mvn compile exec:exec
```

Headless capture to PNG (no GPU window / input backend needed):

```bash
mvn compile exec:exec "-Dapp.args=--capture"
```

The window frame is the calculator's own: `Decorations.CLIENT` extends the client area over the whole
window and a `TitleBar` -- ordinary widgets, drawn in the same palette as the keypad -- stands where the
system caption was, on both the main window and the history window. Dragging, snapping, Win+arrow,
double-click-to-maximize and the system menu are still the window manager's.

Ctrl+= / Ctrl+- / Ctrl+0 zoom the whole UI — every length is relative.
