# calculator-vexel-demo

A deceptively simple calculator built on [vexelray-gui](../vexelray-gui): a display over a flex
grid of lit, elevated keys, rendered as one batched SDF draw. Click handlers run on worker threads
and mutate the display through its thread-safe `Node` handle.

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

Ctrl+= / Ctrl+- / Ctrl+0 zoom the whole UI — every length is relative.
