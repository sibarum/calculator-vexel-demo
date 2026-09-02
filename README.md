# calculator-vexel-demo

A deceptively simple calculator built on [vexelray-gui](../vexelray-gui): a display over a flex
grid of lit, elevated keys, rendered as one batched SDF draw. Click handlers run on worker threads
and mutate the display through its thread-safe `Node` handle.

The mathematics is [cott-engine](../cott-engine) — COTT, where an identity belongs to an *operation*
rather than a value, so the same subterm gives two answers in two contexts: `x·(0÷0)` is `x` (the
operand erases) while `x+0÷0` is `1+x` (it materialises as a residue). There is one engine and no
engine key: it answers every expression the keypad can build. `1÷0` is `ω`, `2÷0` is `2ω`, `ω^ω` is
`-1`, and `i` is derived rather than adjoined. Arithmetic is exact (`1÷3` stays `1÷3`), and adjacency
multiplies (`2π`, `3(x+1)`).

The keypad has the constants `e`, `i`, `π`, the wheel's `ω`, the variables `x y z`, `^` for
powers, and `log(x, n)` for COTT's base-0 exponent reading. A rejected expression is *reported* on the status
line and never written into the field, so it can be fixed and re-evaluated without retyping.

## Two pads, behind icons

`∷` is the arithmetic keys and `θ` the circular ones: sin cos tan, their inverses, sec csc cot and theirs, the
six hyperbolics, `atan2(x, y)` — the angle of the point, in the order written — and `rad`/`deg`. Words would
not fit; "Trigonometry" over a 420dp window is most of a row of keys spent saying what the row below already
shows. The icons are the ones the atlas has, and it has them because this application bakes its own -- see
**A serif with the mathematics in it** below. `∷` is the proportion sign, the 2×2 box the pad wanted.

The panel is the framework's own `Tabs`, and the calculator adds the two icons, the pages, and a **skin**. That
is worth saying because it was briefly not: the first version was a hand-rolled strip of two buttons swapping
two hidden columns, which is forty lines the widget already had and had better — its headers are focusable so
Tab reaches them and the arrow keys walk the bar, they carry a context menu, and pages are hidden rather than
removed because a registration is keyed by node id and a removed page comes back inert.

**The tabs are drawn as keys**, which is the whole of making the bar belong to the keypad instead of sitting on
top of it. A default tab is a document tab — a chrome slab with rounded shoulders and a flat seat, on a content
panel — and over a grid of floating keys that reads as a band of a different program: a full-width surface where
nothing else in the window has one, a silhouette nothing else has, and a selected-tab blue that is not the blue
the `=` key already means. So `Tabs.skin(...)` paints each header with the key's own geometry — same radius,
same hairline border, same lit elevation lifting under the pointer and dropping flat when pressed — the selected
one in the accent the `=` key wears, and `bar()`/`pages()` lose their surfaces so the row floats on the window
like everything around it.

Two things the widget was missing, both added for this and both general: `closable(false)`, because these tabs
are **panes** and a Close whose only outcome is a window with no keypad in it is worse than no menu at all; and
`skin`, so a bar can speak the vocabulary of what surrounds it. The skin is told the selection and the pointer
state *together*, which also closed a small bug — a tab selected while the pointer was on it used to paint the
not-hovered colour until the pointer moved away.

**`rad` and `deg` construct an angle rather than switching a mode.** `sin(deg(90))` is 1, and an expression
therefore carries which measure it was written in — a calculator with a DEG/RAD switch makes every stored
expression ambiguous about which one it was written under, including the ones already on the tape.

The answers are approximate, and they are the only approximate thing here. cott-engine rounds a call to fifteen
decimal places and shows twelve; the three between them are guard digits, which is why `sin(θ)²+cos(θ)²` is 1
rather than 1.000000000001. A call with a variable in it does not reduce at all — `sin(x)` stands, and stands
with its variable intact.

**`log` is not among them and has not moved.** It is COTT's base-0 exponent reading, it returns an *exponent*,
and an exponent is not an operand — so `0^-23+log(2,3)` is a sort error, not an arithmetic one. What changed is
that it now says so in those words instead of assuming you already knew `log` was not the logarithm.

## A serif with the mathematics in it

The calculator draws with its own glyph atlas, not the framework's. `GuiApp` loads exactly one, under the
fixed classpath name `/dev/vexelray/text/atlas/primary.{json,png}`; writing ours to that path under
`src/main/resources` puts the project's classes ahead of `vexelray-text` on the classpath, so this app gets
these faces and every other app keeps the framework's. Nothing in the source names a font — the swap is a
build step and a resource path.

**Face 0 is STIX Two Math** (OFL, in `fonts/`): a Times-cut serif, formal, and crisp at the sizes the keypad
uses. It is here for the mathematics. The framework's atlas asks for the operators, the arrows and the
geometric shapes and gets almost none of them, because the charset was willing and Noto Sans was not — one
glyph out of the whole of `0x2200–0x22FF`, the minus sign, and nothing at all from `0x2190–0x21FF`. Every
label in this program was written around that. STIX has all 256 operators, 103 arrows, the letterlike block,
the floor and ceiling corners and the superscripts, so `∷ ∈ √ ∑ ∫ ∞ ≈ ≠ ≤ ≥ ∂ ∇ ∿ ⌊ ⌋ sin⁻¹` are glyphs
rather than boxes.

**Face 1 stays `NotoSansMono-Regular`** at the index the framework put it. That is not a preference — the
calculator wants no monospace of its own — it is a contract: MainFrame's console addresses monospace as
`font(1)`, and this application is one of its guests (see [the other way round](#the-other-way-round-mainframe-opens-the-calculator)).
Moving it would leave the console's tables laid out in a proportional face.

The atlas is generated by `vexelray-msdf-maven-plugin` at `generate-resources` and **committed**, the way
`vexelray-text` commits its own. The generator's binary is Windows-only, so it re-runs only when a font is
newer than the outputs, and elsewhere:

```bash
mvn -Dmsdf.mode=prebuilt package
```

consumes the committed pair instead of regenerating it. The charset is wide but it is not everything — Noto's
Cyrillic and most of its currency are gone, and so is `U+2058`, the four-dot punctuation the arithmetic tab
used to wear. Check a new glyph against `primary.json` before putting it in a label; a missing one renders as
a box.


## Names this session has given

**Type `k = 3` into the calculator and press `=`.** A line with an `=` in it names something instead of asking
something, and there is no ambiguity to weigh: COTT's grammar has no `=` in it at all, so such a line was a
syntax error until now, and the `=` key never types one — it evaluates. `f(x) = x^2+1` defines a function the
same way. The entry clears, the status line says what was named, and **the definitions window comes up**, which
is the only place a change of this size is visible: once `k` is defined, everything typed afterwards means
something different, including what is already on the tape.

That window lists everything named, each row with a `×` to forget it, and its own entry for adding more. `ℱ` in
the title bar opens it again — in the caption rather than on the tab bar, because it opens a *window* while the
tabs beside it swap a pad, and two different kinds of thing reading as one row of buttons was the tell that the
hand-rolled strip had taken on a job that was not a tab bar's. `calc "k = 3"` at a MainFrame prompt is the third
way in, and all three go through one door.

Definitions are *expanded into the term* before COTT sees it, so the evaluator has no notion of a scope and
`f(x)` evaluates f's body rather than standing as an opaque symbol. A body keeps the names it was written with and
looks them up when it is used, so redefining `k` as 4 makes `f(2)` answer 17 without f being touched. Redefining
keeps a name where it is in the list, because correcting something should not move it.

A name is only a name once the notation knows it is one: `xy` is `x·y` and has to stay so, so `Notation` and
`Parser` scan words out of the function catalogue plus the session's names, longest first, and everything else
is still a single character.

### A parameter can be a function

**`iter(f(), n) = f(f(n))`** — a parameter written with empty brackets takes a *function*, and is given the bare
name of one: `iter(g, 3)`, `iter(sin, x)`. Nothing in `iter` knows what it will be handed, which is the whole
point of it; it is one definition and it serves a name this session made and a name the catalogue owns alike.

**The brackets are load-bearing, and they are load-bearing because of `xy`.** A body is read by the same
adjacency pass as everything else, so `f(n)` is a call only where the notation already knows `f` names a
function — and where it does not, `f(n)` is the product `f·(n)`, which is not an error but a perfectly good
reading of the same characters. That is exactly why it cannot be inferred from the body: `m(f, n) = f(f(n))`
still means `f·f·n`, still answers 18 for `m(3, 2)`, and lists itself as `f·f·n` so that what it means is on
screen. The `()` is the smallest mark that separates the two readings, and it goes in the head, where the rest
of what a name is gets settled.

**What the brackets do not say is how many arguments.** `f()` says "a function" and stops. How wide it is comes
from the function that is actually passed, checked when it is applied — so `iter(atan2, 2)` is refused by
*atan2* ("atan2 takes 2 arguments, not 1") rather than by `iter`, which never promised a number. That is the
same bargain every other name here makes: a definition settles which words are words, and what they mean waits
until it is used.

The kind is checked in both directions, at the binding, because both mistakes are ones people make.
`iter(3, 2)` says `iter's f is a function, so it is given the NAME of one`. And a bare function name in a value
slot is refused too — otherwise `g` would ride into the answer looking exactly like a variable.

Below the notation nothing is new. A passed function is an `Atom`, which is what a bare name has always parsed
to, and `Bindings.expand` substitutes a name for a name and then applies it — so what reaches COTT is still a
term with no notion of a function anywhere in it, and `iter(sin, x)` is `sin(sin(x))` by the time anything is
evaluated or drawn. A ring built this way (`loop(f(), n) = f(f, n)`, then `loop(loop, 3)`) runs into the same
expansion limit that has always caught `a = b` over `b = a`, and says the same thing.

## Prerequisites

The sibling stack installed to the local Maven repo, in order: `supirvast`, `vexelray`,
`tactroller` (+ `atchung`), `vexelray-gui`, `cott-engine`. Java 25, and a Vulkan-capable GPU to run
windowed.

## Run

```bash
mvn compile exec:exec
```

### Profiling

The whole stack is instrumented through one seam — `sibarum.probe.Probe`, in `atchung-probe` — and it is off
unless asked for. Ask for it here:

```bash
mvn compile exec:exec -Pprofiler
```

That turns on every lane, prints a rollup every two seconds as well as at exit, and names any frame over
16 ms the moment it happens. Narrow it or redirect it on the same command line:

```bash
mvn compile exec:exec -Pprofiler "-Dprobe=gpu,frame"          # two lanes
mvn compile exec:exec -Pprofiler "-Dprobe.out=run.log"        # to a file instead of stdout
mvn compile exec:exec -Pprofiler "-Dprobe.trace=true"         # a line per event, not just the rollup
```

Lanes: `bus state time anim input frame layout draw gpu shader app`. It works in headless capture too, though
the `gpu` and `frame` lanes stay empty there — that path renders offscreen and never enters the frame loop.

Every setting is also an environment variable, which is the form that needs no Maven and survives into the
native binary built by the `native` profile below (there is no JVM there to pass `-D` to):

```bash
PROBE=all PROBE_OUT=run.log PROBE_SLOW=16 ./calculator.exe
```

The report has three parts: **spans** (how long, with p99, max and a self time that says which nested span the
cost really belongs to), **counters** (how many, with the peak — mailbox depths live here), and a **resource
ledger** of what is still open, which is how a leaked swapchain or render target gets named. Full reference:
[`atchung/docs/probe.md`](../atchung/docs/probe.md).

### Headless capture

One mode, one application, and a list of **scenes** over it (no GPU window or input backend needed).
Everything, at its defaults:

```bash
mvn compile exec:exec "-Dapp.args=--capture"
```

One scene, or a few, or one told what to be about:

```bash
mvn compile exec:exec "-Dapp.args=--capture=keypad"
```

```bash
mvn compile exec:exec "-Dapp.args=--capture=keypad,names"
```

| scene | what it photographs | subject |
| --- | --- | --- |
| `keypad` | each pad at the window's ordinary size, and each again at the smallest size the window manager will allow | the base filename |
| `names` | the definitions window, and what those names then mean | the filename |
| `cue` | the four things the interface says without words, each caught mid-flight | the base filename |

The two small keypad pictures are the only way to find out whether the window's minimum is still big enough,
and a number nobody photographs stops being right the first time a row of keys is added. Use ASCII `-` and `/`
in a subject, since the argument goes through the console's codepage on the way in and `Notation.normalize`
maps them anyway.

**Every scene drives the keypad.** A line is put in the display exactly as typing it would be and `=` is
pressed, so what is photographed is what pressing that key does. Scenes share one session, so a scene after
`names` is looking at the names it made.

`names` is worth reading the console output of rather than only the picture. It evaluates the definitions it
made, redefines `k` to show that `f` follows, and offers a bad name to show that a refusal keeps the line.
`iter(g(), n) = g(g(n))` is defined among them and then applied to a session name and to `sin`, which is the
claim about functors written out: `iter(unit, 8)` is `1÷2` and `iter(sin, x)` is `sin(sin(x))`, from one
definition that knows neither of them.

### What the calculator says without words

Four marks, and none of them is decoration — each one exists because something was previously unsayable:

| mark | when | why |
| --- | --- | --- |
| a **sweep** down the entry | `=` was pressed | The press that most needs acknowledging is the one that changes nothing on screen — `=` on an expression that reduces to itself, or a second `=` on a result already showing. Both used to be indistinguishable from a dropped click. |
| a **ring** round the entry, twice | the line was refused | The entry deliberately keeps a rejected expression so it can be corrected, so the mark goes on the thing you are about to correct. Two pulses, because one flash is the same shape as an acknowledgement and refusal has to be told apart at a glance. |
| a **wash** over the entry | a line arrived from another window | A click in the history window changes this one. The eye was somewhere else; the wash says where to look. |
| the status line **rising** | there is something to read | Replacing the text left an identical string when the same thing was reported twice — so a second `=` on one bad expression looked like a dropped press. Reporting again is the ordinary case, so it is the case that has to read. |

Timings are in `Motion`: **160ms** for a transition, **240ms** for a cue, **400ms** for a refusal — a cue is longer than a transition because the question it answers is not "was it continuous" but "was it noticed", and a refusal is longer again because it is asking to be read. Every ramp is linear except the status line's travel, which is the only thing here that arrives at a *place*.

`--capture=cue` photographs all four mid-flight, plus the pad swap. Those are regression pictures rather than illustrations: at rest every one of these marks is invisible by construction, so a cue whose timing drifts or whose envelope changes shape shows up there and nowhere else.

> There used to be five capture flags. They differed in what they did to the application and what they
> photographed, and everything else was copied between them — so `--capture` held an engine with nowhere to
> keep a definition while others went round the engine altogether and could not see a defined name at all.
> None of them could photograph a *moment*,
> because each returned before the frame loop and so had no clock. `Capture` is one world with a clock on every
> window; the differences are data. See its class comment.

The window frame is the calculator's own: `Decorations.CLIENT` extends the client area over the whole
window and a `TitleBar` -- ordinary widgets, drawn in the same palette as the keypad -- stands where the
system caption was, on every window this application opens. Dragging, snapping, Win+arrow,
double-click-to-maximize and the system menu are still the window manager's.

Ctrl+= / Ctrl+- / Ctrl+0 zoom the whole UI — every length is relative.

**Every window has a floor, and it is two floors.** `Gui.minSize` is the smallest canvas the UI is laid out on:
below it the layout keeps running at the minimum and the window shows part of it, rather than six rows of keys
each absorbing a sixth of the deficit until they have no height left. `WindowConfig.minSize` is new, and it is
the smallest the *window manager* will let a drag reach — answered on `WM_GETMINMAXINFO`, which is the only
place it can be answered, since a drag runs inside Windows' own loop and a size refused after the fact is one
the user has already seen. Both come from one pair of numbers per window, because a window that stops at one
size and a layout that gives up at another is a bug exactly as wide as the disagreement.

## The native build

```bash
mvn -Pnative package                                 # target/calculator.exe
```

It is one executable and nine JDK DLLs beside it, not one file. `native-image` copies `awt.dll`,
`fontmanager.dll`, `freetype.dll`, `lcms.dll` and five others next to the exe and they are **required**:
the text atlas ships as a PNG and `GuiApp.loadAtlasRgba` decodes it with `ImageIO`, so `java.desktop` is
reachable from the first frame that draws a glyph. Moving them away breaks startup, not some optional path.

The switches are the ones [mainframe-dist](../mainframe/mainframe-dist/README.md) found the hard way and
each is load-bearing: `-H:+ForeignAPISupport` because the graphics stack and both input backends are Panama
downcalls and Win32 calls back in through an upcall stub, `-H:+SharedArenaSupport` because raw input opens
an `Arena.ofShared` on one thread and reads it on another, `-J-Djava.io.tmpdir=target/nitmp` because Windows
Application Control blocks a brand-new unsigned `.exe` under `%TEMP%` and native-image's own probes are
exactly that, and `/SUBSYSTEM:WINDOWS` + `/ENTRY:mainCRTStartup` so no console window appears beside the
keypad. The last two are the Windows linker's; a native build elsewhere drops them.

`src/main/resources/META-INF/native-image` is the tracing agent's output, less the parts that are only the
editor's — the TextMate grammars, joni's Unicode tables, TM4E's `Raw*` classes and the file-dialog library.
dist's second file, the X.509 machinery a *signed* jar drags into the image heap, is absent for the same
reason: TM4E is that jar, and nothing on this classpath is signed.

The `foreign` section is the part that goes stale on a dependency bump, and stales *loudly*: `User32` builds
every downcall handle in its static initialiser, so one new binding anywhere in that class fails the image at
the first window with `MissingForeignRegistrationError` rather than at the call. Window icons added four
shapes — `CreateDIBSection`, `CreateIconIndirect`, `MsgWaitForMultipleObjectsEx` and the point-by-value
`MonitorFromPoint` — and they are in the file now. Re-trace with
`-agentlib:native-image-agent=config-output-dir=…` and merge additively; the checked-in file covers paths one
run does not reach, so replacing it wholesale loses more than it gains.

**Application Control will lie to you about this build.** `Unable to run 'WindowsDirectives.exe' to compute
offsets in C data structures` is the policy blocking a probe — retry. `UnsatisfiedLinkError: Can't load
library: awt` on the *result* is **not** a missing DLL: `awt.dll` imports from the `java.dll` and `jvm.dll`
shims native-image generates fresh on every build, those are unsigned and hash-unique per build, and a
blocked one leaves `awt.dll` unable to resolve its imports. Rebuild until a set is allowed through.

Verified from the executable, not from the JVM arrangement: one `--capture` draws the keypad, the definitions
window, and the four cues mid-flight.

### The icon

The mark is `a-curves` from [vexelray-icons](../vexelray-icons/README.md), hue `#2fc4d6`. None of the ten
marks on that sheet is a calculator, so this is the closest fit rather than a lookup: a curve is what a
calculator is for. `a-mainframe` is the tempting one — a body with a display band and two rows of
keys — but MainFrame is the shell the calculator runs *inside*, and a guest wearing its host's mark makes the
two indistinguishable wherever windows are listed.

It goes on in two places, because no running process can set its own executable's icon and no executable can
set the icon of a window that does not exist yet.

| Where it shows | Set by | Where |
| --- | --- | --- |
| Title bar, Alt-Tab, the taskbar group's thumbnail flyout | `setApplicationIcon`, once at start-up | [`Icons`](src/main/java/dev/vexelray/demo/calculator/Icons.java) |
| Explorer, a pinned shortcut before launch, **the taskbar button while running** | an `ICON` resource linked into the exe | `src/main/native/calculator.rc` |

The taskbar button is on the second row and not the first, which is the part worth knowing: VexelRay sets no
AppUserModelID, so every window of the process shares one taskbar button and Windows derives that button's
icon from the executable. Setting only the window icon leaves the taskbar showing the default; setting only
the executable's leaves Alt-Tab showing it. Both, or neither is right.

Four sizes — 16, 32, 48, 256 — each rasterised at its own size rather than resampled from one, because the
detail that survives a reduction to 16px is not the detail a designer would have kept. `Icon.bestFor` picks;
nothing resamples. `src/main/native/MakeIcon.java` is what draws them and what assembles `calculator.ico`;
it is run by hand when the mark changes, not by the build. The `.rc` is compiled to `target/calculator.res`
at `prepare-package` by `rc.exe`, which the MSVC environment `native-image` already requires supplies, and
handed to the linker as `-H:NativeLinkerOption`. `CalculatorDesktop` deliberately does not call `Icons`:
there the window is MainFrame's and should say so.


## The other way round: MainFrame opens the calculator

```bash
mvn compile exec:exec "-Dapp.mainClass=dev.vexelray.demo.calculator.CalculatorDesktop"
```

That boots [MainFrame](../mainframe) as the main window, with the calculator plugged into it as an
app. `apps` lists what is on the desk and **`calc` opens the keypad** — which then opens its own
history and definitions windows, so the window list is a tree rather than a list.

```
~ > apps
name        launchable  summary
profiles    false       named sets of environment variables and binary directories
calculator  true        a keypad and a tape

~ > calc                       # the keypad
~ > calc "2^10"
1024
~ > calc "1÷(x^2−1)"
1÷(-1+x^2)
```

**`calc` is both doors.** Given an expression it runs the same three lines of COTT the `=` key does
and answers with a *value*, so a calculator in a shell is one the rest of the language can work on:
`calc "2^10" | save ./answer.txt`. Given nothing it opens the keypad — the same bargain `python`
makes, and it costs nothing, because bare `calc` previously only ever meant "you forgot the
expression". A syntax error comes back as COTT's own message, as a shell error with a hint.

`calculator` and `launch "calculator"` open it too. MainFrame names a command after every launchable
app, so typing a program's name runs it; `launch` is the explicit form for scripts, where a name that
came out of a variable has to be quoted anyway.

Add `"-Dapp.args=--launch" "-Dapp.args2=calculator"` to come up with the keypad already open.

**Nothing was rewritten for this.** `CalculatorApp` is still the calculator as its own program, and
`CalculatorDesktop` is six lines. The two arrangements are built from the same parts —
`CalculatorApp.Window` is the same keypad and the same engine, opened under a name
on somebody else's `GuiApp` instead of being the main window of its own — so neither is a fork of the
other. What differs is who owns the frame loop.

## Where you left it

Every window comes back where it was: position, size, maximized state, and the UI zoom, in
`~/.calculator/settings.properties` through the framework's `WindowMemory`. Placement is restored at
*creation* rather than after, so a window appears where it belongs instead of jumping there on its
first frame, and it is clamped to a monitor that still exists on the way — shrunk if the saved
rectangle no longer fits, then nudged until it is fully on screen. Zoom is restored before the first
frame too, since every `em` resolves against it.

**What is deliberately not remembered is whether the history window was open.** The
framework offers it and the text editor uses it, but a calculator has nothing to reopen it onto:
the tape is this session's. Restoring a window to show emptiness is worse than not restoring it — there is
nothing there to be where you left it.

## Testing an interaction

The headless captures render a settled tree to a PNG. They have no pointer and no frame loop — `press`
asks the engine directly, and the per-frame drains are called by hand — so they prove what the
calculator *looks* like and nothing about what it *does*. The complement is
[`vexelray-gui-harness`](../vexelray-gui/vexelray-gui-harness), which runs this application's real loop
with a synthetic pointer and asserts the question a capture cannot ask: after a click, does a frame
arrive on its own?

That is the question clicking a history entry got wrong, invisibly, while the suite stayed green. See
[docs/testing-interaction.md](docs/testing-interaction.md).
