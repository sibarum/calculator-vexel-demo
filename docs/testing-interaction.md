# Testing an interaction, not a tree

Status: **harness available** (`vexelray-gui-harness`); no tests in this repo use it yet.

Every test this application could write before now called `Gui.frame()` itself. That is enough to prove
a tree lays out, a handler fires, an expression parses — and it cannot prove the thing that actually
broke here, which is whether a **frame arrives on its own** after a click.

The distinction is not academic. Clicking an entry in the history window did nothing until the mouse
moved, for weeks, while the suite stayed green. The handler ran. The engine restored the expression.
Nothing drew it, because nothing asked the frame loop for a frame, and a test that draws its own frames
cannot notice.

## What the harness gives you

`HarnessApp` runs this application's **real** frame loop — real `GuiApp`, real window, real swapchain,
real pixels — with the four methods the loop uses to decide whether to draw under the test's control.
The window is created and never shown.

```xml
<dependency>
    <groupId>dev.vexelray.gui</groupId>
    <artifactId>vexelray-gui-harness</artifactId>
    <version>${vexelray-gui.version}</version>
    <scope>test</scope>
</dependency>
```

```java
try (HarnessApp harness = HarnessApp.start(gui, WindowConfig.of("calculator", 420, 632))) {
    harness.settle();                          // let start-up finish
    long before = harness.frames();

    harness.click(x, y);                       // press + release, no pointer motion
    assertTrue(harness.awaitFrame(before, 3_000), "a click has to produce a frame");
    assertTrue(harness.await(() -> display.text().equals("7"), 2_000));
}
```

**The wait really waits.** `waitEvents` blocks until something calls `postWake` or the budget expires.
That is the whole design: a harness that returned immediately would let the loop spin, and a spinning
loop draws the next frame whether or not anything asked for one — so every test would pass, including
against the bug it was written for.

## What is worth asserting here

The three interactions that broke, in the order they were found:

| Interaction | The assertion | What it caught |
|---|---|---|
| A keypad key | a frame follows the click | the retained tree's mutation channel had no wake |
| **An entry in the history window** | the expression reaches the display, and the swipe plays | the request was queued for a `beforeFrame` drain nobody woke the loop for |
| Anything in a plot or definitions window | a frame follows | those are separate `Gui` trees; only the main one was wired |

The history one is the most valuable test this repo could have. It is also the one that needs the
history window open first, so the test has to click the history button, wait for the window, and then
click a row.

`window().budgets()` is the other half — it records what the loop parked on each iteration, so a test
can assert this application **parked** rather than merely that it looked idle:

```java
harness.window().resetBudgets();
harness.settle();
assertTrue(harness.window().budgets().stream().allMatch(b -> b >= 200_000_000L),
        "an idle calculator must not be drawing: " + harness.window().budgets());
```

## Two things that will bite

**Handlers are asynchronous.** They run on the worker executor, so a frame arriving does not mean the
handler that asked for it has finished. Asserting immediately after `awaitFrame` races it — use
`await(condition, timeout)` for anything the application does *because* of the click.

**It needs a Vulkan device.** This is an integration harness, not a unit fixture. On a machine without
a GPU it fails to start rather than silently proving nothing, which is the right way round, but it does
mean these tests cannot run on a GPU-less CI box without lavapipe.

## Why not the capture harness

[`Capture`](../src/main/java/dev/vexelray/demo/calculator/Capture.java) is the other headless path here
and it answers a different question. It has no pointer — `press` calls `engine.press`, `arrive` calls
`engine.restore`, and its own comment says *"a photograph has no pointer, so the strip is asked
directly"*. It also hand-calls `history.drain()` and `definitions.drain()`, standing in for the frame
that would have run them.

So `Capture` proves what a settled tree looks like, and is structurally blind to input, scheduling, and
anything the frame loop is responsible for. It could not have caught any of the three rows above, and
it was not supposed to. The two are complements: `Capture` for pixels, `HarnessApp` for behaviour.

See [kronometer/docs/render-on-demand.md](../../kronometer/docs/render-on-demand.md) for why the loop
parks at all, and what has to wake it.
