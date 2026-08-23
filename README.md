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

The window frame is the calculator's own: `Decorations.CLIENT` extends the client area over the whole
window and a `TitleBar` -- ordinary widgets, drawn in the same palette as the keypad -- stands where the
system caption was, on both the main window and the history window. Dragging, snapping, Win+arrow,
double-click-to-maximize and the system menu are still the window manager's.

Ctrl+= / Ctrl+- / Ctrl+0 zoom the whole UI — every length is relative.
