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

The keypad has the constants `e`, `i`, `π`, the wheel's `ω`, plotting variables `x y z`, `^` for
powers, and `log(x, n)` for COTT's base-0 exponent reading. A rejected expression is *reported* on the status
line and never written into the field, so it can be fixed and re-evaluated without retyping.

## Two pads, behind icons

`⁘` is the arithmetic keys and `θ` the circular ones: sin cos tan, their inverses, sec csc cot and theirs, the
six hyperbolics, `atan2(x, y)` — the angle of the point, in the order written — and `rad`/`deg`. Words would
not fit; "Trigonometry" over a 420dp window is most of a row of keys spent saying what the row below already
shows. The icons come from a short list, because the text atlas carries Latin, Greek, punctuation and the
letterlike block and of the mathematical operators exactly one, the minus sign — so a 2×2 box glyph, a sine
wave and a script f are all missing glyphs, and a missing glyph renders as a box.

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
as something the plotter can draw.

**`log` is not among them and has not moved.** It is COTT's base-0 exponent reading, it returns an *exponent*,
and an exponent is not an operand — so `0^-23+log(2,3)` is a sort error, not an arithmetic one. What changed is
that it now says so in those words instead of assuming you already knew `log` was not the logarithm.

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
`f(x)` plots f's body rather than an opaque symbol with no curve. A body keeps the names it was written with and
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
slot is refused too — otherwise `g` would ride into the answer looking exactly like a variable, and get plotted
as one.

Below the notation nothing is new. A passed function is an `Atom`, which is what a bare name has always parsed
to, and `Bindings.expand` substitutes a name for a name and then applies it — so what reaches COTT is still a
term with no notion of a function anywhere in it, and `iter(sin, x)` is `sin(sin(x))` by the time anything is
evaluated or drawn. A ring built this way (`loop(f(), n) = f(f, n)`, then `loop(loop, 3)`) runs into the same
expansion limit that has always caught `a = b` over `b = a`, and says the same thing.

## Plotting

**Press `=` on an expression with a variable in it and it is plotted**, in a window of its own, framed
automatically. How many free variables it has is most of the decision: **one is a curve** against that variable
(usually `x`, and nothing depends on its being `x`), **two is a surface in 3D** over the pair, and three or more
is reported on the status line rather than half-drawn by pinning the rest to zero. The line falls at two because
there is no fourth dimension to put a third variable on.

**None used to be the end of it** — an expression with no variable is arithmetic and passed silently. It still
does when the answer is a number on the real line, but when it is not, the question becomes what *kind* of
number: a zero, an infinity, a twist that is not a whole turn, and there is a picture of where those are. See
[the spiral](#the-spiral-where-a-value-is-when-it-is-not-on-a-line).

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
claim is per cell rather than per pixel: *the box drawn for a cell contains every point of the surface above
it*. The projection is axonometric with no perspective at all, which is not a simplification — it is what makes
the painting order a function of the floor alone, and the painting order is the whole of the occlusion.

**It is drawn as two layers, because they say two different things.** Boxes alone read as blocky, and the fix is
not finer boxes — it is to stop asking one layer to be both the evidence and the picture. The **proof** is the
enclosure over each cell, drawn as a hollow outline: an enclosure is a claim about a range, so its edges are
what it has to say, and a filled box would be claiming the surface is everywhere inside it. The **picture** is a
smooth surface bilinearly interpolated between the cell lattice's corners and diffuse-lit.

Interpolating is joining up points that were evaluated — the very thing this module refuses to do on its own —
and it is safe here for exactly one reason: **it is drawn inside the outline that contains it.** The enclosure
covers every value the surface takes on that cell and the interpolation's own corner heights are among them, so
the smooth layer can never draw outside the honest one. Where the arithmetic is tight the outline vanishes into
the surface; where it is loose the outline stands visibly taller than the surface threading through it, which is
where a reader should be looking.

It is also cheaper than one fine layer would have been: an enclosure costs interval arithmetic over
`BigDecimal`, an interpolation costs four multiplies. So the proof stays coarse and the picture gets nine times
the resolution for one height and one gradient per lattice corner.

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
`--capture=surface` reports `1600/1600 cached` for the turn. Between gestures the picture is a tree of pooled nodes with no timer behind it,
which is what makes it retained while it is idle.

Landmarks are for curves only. The 3D analogues are saddle points and critical curves, and picking one under a
pointer is a different problem; that is a scope decision rather than an obstacle, and `Expr.derivative` already
takes the axis to differentiate along, so the partials are there when someone wants them.

Exploring is cheap because enclosures are cached per *column of x* rather than per picture. Moving in
y re-classifies and evaluates nothing at all; panning evaluates only the columns that came into view;
and zooming lands on a discrete set of scales, so going back to one costs nothing. `--capture=curve`
prints the counts — a pan of 40% of the window answers 418 of 696 columns from cache, and going home
answers all of them. A redraw is applied as one atomic batch over pooled nodes, so the old picture
stays up whole until the new one replaces it.

Two things the keypad cannot ask for: there is **no trigonometry** in COTT's vocabulary (the plot
module has `sin`/`cos`/`tan`, but no key reaches them), and **`log` is refused**. COTT's `log(x, b)`
is the base-0 exponent reading, not the logarithm of a real number — on ordinary numbers it does not
reduce at all — so plotting it as a natural log would draw a curve the calculator itself disagrees
with. That is a notation question rather than a plotting one, and it is refused by name on the status
line until it is settled.

## The spiral: where a value is, when it is not on a line

**Press `=` on `2÷0` and a picture opens.** It used to answer `2ω` and stop there, and every renderer above
would have refused to draw it — `ω is not a value the real line holds`, which was true and was the end of the
matter. It is not the end of the matter, because the line is the wrong figure.

Zero and ω add a dimension to the algebra the way `i` adds one to the reals, so the figure to draw a value on
is a **disc**: `0` at the centre, `1` a circle inside it, `ω` the edge. The question is what the radial
coordinate does between them, and the algebra answers it in three facts.

- **The grade is dense.** `Xp.grade` is a `Rational`, not an integer, and the keypad reaches it: `0^(1÷2)` is
  `pt(1, xp(1/2, 0, 0))` and `0^(1÷3)` is a third. Anything drawing one figure per whole grade has nowhere to
  put those.
- **Grades add.** `0^(2÷3)·0^(1÷3)` is `0`. On a radius exponential in the grade, multiplying is **one
  similarity of the picture** — the same rotation and scaling wherever it is applied. On evenly spaced rings,
  multiplying by `0` is "move in one ring", which is not a motion of the plane at all.
- **Both ends are limits, not edges.** `0, 0², 0³ …` converges into the centre and `ω, ω², ω³ …` runs away, and
  neither sequence stops. A spiral has room for all of both and needs no ceiling.

And `r = e^(bθ)` is the only plane curve whose radius is exponential in its angle, while every value in COTT is
`0^E`. Drawing a base-0 exponential algebra on the exponential curve is not an analogy.

### A base and a fibre, which is what a graded algebra is

The spiral fuses radius and angle into one parameter, so it holds **one** coordinate and not two. That is not a
shortage to work around — it is the shape of the object. A graded algebra is a **bundle**: the grade group is
the base, and the coefficient `k·e^(iπt)` lives in the fibre over it. So the spiral is the base, carrying the
grade and nothing else, and the tube swept along it is the fibre.

**The base is the grade, exactly.** **One turn is one grade**, so the picture is the Riemann surface of the
logarithm with the branch being the grade: `0` is one turn in from `1`, `0²` is two, `ω` is one turn out. Every
whole grade falls on **one ray**, each at `2.2` times the radius of the next — the picture is a ruler and that
ray is its scale. A grade of a half is half a turn round, which is the case evenly spaced rings could not draw
at all. And because nothing but the grade is in it, multiplying by `0` shifts every value by exactly one turn:
one rotation and one scaling, **a similarity of the whole picture**, which is the property the logarithmic
spiral was chosen for.

**The fibre is the coefficient, and it is a disc.** Round it is the **twist**: it closes at two and `0^ω` is
`−1`, so `π·t` is an angle that closes exactly when the twist does — `1` at the crest of the coil, `−1`
directly underneath, `i` a quarter turn out and `−i` a quarter turn in. Across it is the **count**, radially,
because the coefficient is a modulus as well as an argument.

`Re` is not a coordinate and lost nothing by not being one: it is `magnitude·cos(π·t)`, which is what the three
above already say together. It was always a projection, and this is the picture that projection was of.

### Why the count cannot go on the grade's axis, which is where it was

It was, for one revision, and the wrongness was not a badly chosen constant — so it is worth writing down.

COTT's order is **lexicographic**: every multiple of `0` is below every positive real, however many copies are
taken, so the grade dominates the count *absolutely*. That is a non-Archimedean order, and a single real
coordinate is Archimedean. It cannot hold both, and both ways of trying fail in the picture rather than in the
abstract.

- Let the count contribute **linearly** in `log|k|` and multiplication stays a similarity, but the count
  *crosses*: a thousand copies of `0` gets drawn among the ordinary reals, which the algebra flatly denies.
- **Bound** it and nothing crosses, but the count *converges* and piles onto a fractional grade that has
  nothing to do with it. Measured: `1000000·0` landed at turn `−0.650699` and `0^(13÷20)` at `−0.650000`, a
  quarter of a degree apart on the same coil. And multiplication by 2 stopped being a similarity, because a
  bounded map of `log|k|` is not additive — the same doubling shifted `ω→2ω` by `0.060` of a turn and
  `8ω→16ω` by `0.043`.

The fibre has none of that, and it does not have it **by construction rather than by tuning**. A count cannot
cross a grade because it cannot leave its fibre, and the fibres over consecutive grades are disjoint exactly
when the tube is narrow enough not to touch the next coil — the condition `ALPHA < tanh(ln(PITCH)/2)`, which
was already in the renderer for the shallower reason that two coils which nearly touch read as one. So the
non-Archimedean fact gets a non-Archimedean picture: each grade carries a whole bounded copy of the magnitude
structure, and no amount of counting inside one reaches the next.

A count of one sits at the **middle** of its fibre, so that `1÷2` is exactly as far the other side as `2` is
this side, and so that a count running to zero or to infinity approaches the fibre's centre line or its rim and
reaches neither. **Every value the picture names has a count of one** — `1`, `0`, `0²`, `ω`, `−1` are all
`pt(1, …)` — so the surface drawn there is the locus they all lie on, and a mark off it is a mark whose count
is not one.

What the fibre does *not* fix is that the count's action is still not by isometry: a doubling moves a mark a
third of the way from the unit locus toward the rim, then less, then less again. That is forced and it is the
right compression rather than the old fudge — the fibre is bounded because the algebra says a count cannot
escape its grade, so an unbounded count inside a bounded fibre cannot be drawn to scale. It is the same trade a
disc model of the hyperbolic plane makes, and it is confined to the fibre instead of leaking into the base.

### What opens a window, and what does not

The gate is narrow on purpose. Ordinary arithmetic is still silent: `2+2` answers 4, `5÷2` answers `5÷2`, and
`0^ω` answers `−1`, which is a real number however it was spelled. What opens a window is a value the real line
cannot hold — a grade, a torsion, or a twist that is not a whole turn — which is exactly the set of answers
that used to be correct on the display and undrawable everywhere else.

A standing sum is drawn as **several marks**, one per term. `1+ω` is left standing by the evaluator precisely
because it is not one place, and showing the two places it is instead is the honest picture of that.

A **torsion** is refused by name. It closes at one where the twist closes at two, so it is a second phase and
would need a second tube, and there is no third dimension left to sweep one along; folding it into the
spiral's angle would be claiming a torsion is a grade, which it is not — a whole torsion is the identity and a
whole grade is not. Nothing the keypad builds reaches one: `0^(ω÷2)` is `i`, `0^(ω÷4)` is a quarter twist, and
the torsion slot stays zero throughout.

**Both residue families are refused**, as the curve plotter refuses them: `1^a` and `0^a` are forms that
remember an operand where a place is a point, and both spell themselves the way a literal power of 1 or 0
does. So `0·0` draws `0²` and `2−2` draws nothing, and the difference between them is which of the two the
engine actually produced — which is an [open notation question](../cott-engine), not a drawing one.

### Reading it

Whole grades are dotted along the crest and named while there is room for one; coming in from the outside they
converge geometrically, so past a few turns the names are dropped and the beads left, because the ray is a
scale and a reader who can see `0` and `0²` on it can count the rest inward. A value standing exactly on a
crossing — same grade, no phase, one copy — replaces it rather than printing over it.

**Each of the two fibre coordinates gets its own piece of furniture, and only when it has moved the mark.**
Where a value has a **phase**, the unit circle it turned along is beaded all the way round, since a quarter turn
round a fibre seen at three quarters is a small displacement and needs a zero to be measured against. Where its
**count** is not one, the spoke it travelled out along is beaded too, so the mark reads as being *in the fibre
over that grade* rather than adrift beside the coil — and the crossing it came from puts its name on the far
side, because both labels otherwise want the same patch of screen for the same reason: they are on one radius.

The ramp is cool at the inward end and the calculator's one red at the outward, and that red is the same red a
pole is painted in, deliberately: a column a curve could not bound is a column where the value ran to ω, and
this is where ω *is*. The status line says the **algebra** — the grade, the count, the phase — because the
picture is already the geometry.

Drag turns it, `+` and `−` lengthen and shorten the coil, **Fit** goes down to the turns the value needs, and
**Reset** to that and the three-quarter view. Framing runs from the **outside in**: the outermost coil is
scaled to the canvas and everything winds inward from there, which is the only end that can be an anchor —
pinning the inner end instead would make the scale depend on how many turns happen to be on show, so pressing
`+` would shrink what you were already looking at. It is also why there is no ceiling on the grade: `0^24` is
drawn by winding down to it, not by drawing the twenty-three coils between it and 1 at a scale where none of
them is visible.

**Turning it costs no arithmetic, for a stronger reason than the surface's.** A surface orbits for free because
its enclosures are cached per cell; this orbits for free because there was never anything to evaluate. The
geometry is a spiral and the value is two numbers on it, both settled before the first frame — so the capture
reports `nothing to cache` where the others report a ratio, and that is the honest answer rather than a missing
feature. Nothing here is an enclosure and nothing here claims to be: the spiral is a coordinate system, known
in closed form and the same for every expression, so the boxes are a sampling of a shape rather than evidence
about a function.

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

One mode, one application, and a list of **scenes** over it (no GPU window or input backend needed, except for
`sdf`). Everything, at its defaults:

```bash
mvn compile exec:exec "-Dapp.args=--capture"
```

One scene, or a few, or one told what to be about:

```bash
mvn compile exec:exec "-Dapp.args=--capture=curve"
```

```bash
mvn compile exec:exec "-Dapp.args=--capture=names,curve=f(x)"
```

| scene | what it photographs | subject |
| --- | --- | --- |
| `keypad` | each pad at the window's ordinary size, and each again at the smallest size the window manager will allow | the base filename |
| `names` | the definitions window, and what those names then mean | the filename |
| `curve` | `plot.png`, `plot-zoomed.png`, `plot-landmark.png`, with what the cache saved at each step | an expression |
| `surface` | `surface.png` and `surface-turned.png`, likewise | an expression |
| `spiral` | `spiral.png` and `spiral-turned.png`: where a value off the real line sits | an expression |
| `sdf` | one ray-marched frame per render style — needs a graphics device | an expression |
| `cue` | the four things the interface says without words, each caught mid-flight | the base filename |

The two small keypad pictures are the only way to find out whether the window's minimum is still big enough,
and a number nobody photographs stops being right the first time a row of keys is added. `plot-landmark.png` is
the hover tooltip: a capture has no input backend and so no pointer, so the hover path is walked from its own
end, through the same device-pixel conversion a real pointer takes. Use ASCII `-` and `/` in a subject, since
the argument goes through the console's codepage on the way in and `Notation.normalize` maps them anyway.

**Every scene drives the keypad.** A line is put in the display exactly as typing it would be and `=` is
pressed, so what is photographed is what pressing that key does — including the plots, which the engine offers
to `Previews` on its own. That is why `--capture=names,curve=f(x)` draws a parabola: the `names` scene defines
`f(t) = k·t^2+1` and `k = 4`, and the `curve` scene after it is looking at the same session. It is also what
`--march=EXPRESSION` does to open the windowed app with something already plotted.

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
> keep a definition, `--capture-plot` went round the engine and could not draw a defined name at all, and
> `--capture-surface` was `--capture-plot` with different filenames. None of them could photograph a *moment*,
> because each returned before the frame loop and so had no clock. `Capture` is one world with a clock on every
> window; the differences are data. See its class comment.

The window frame is the calculator's own: `Decorations.CLIENT` extends the client area over the whole
window and a `TitleBar` -- ordinary widgets, drawn in the same palette as the keypad -- stands where the
system caption was, on the main window, the history and the plot alike. Dragging, snapping, Win+arrow,
double-click-to-maximize and the system menu are still the window manager's.

Ctrl+= / Ctrl+- / Ctrl+0 zoom the whole UI — every length is relative. In the plot window that is
separate from the plot's own zoom: Ctrl+= scales the interface, bare `+` scales the *plane*.

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

**Application Control will lie to you about this build.** `Unable to run 'WindowsDirectives.exe' to compute
offsets in C data structures` is the policy blocking a probe — retry. `UnsatisfiedLinkError: Can't load
library: awt` on the *result* is **not** a missing DLL: `awt.dll` imports from the `java.dll` and `jvm.dll`
shims native-image generates fresh on every build, those are unsigned and hash-unique per build, and a
blocked one leaves `awt.dll` unable to resolve its imports. Rebuild until a set is allowed through.

Verified from the executable, not from the JVM arrangement: one `--capture` draws the keypad, frames
`1÷(x²−1)` and walks the hover path to name the landmark with its cache counts intact, and compiles the last
expression to fragment SPIR-V to ray-march it on the GPU.

## The other way round: MainFrame opens the calculator

```bash
mvn compile exec:exec "-Dapp.mainClass=dev.vexelray.demo.calculator.CalculatorDesktop"
```

That boots [MainFrame](../mainframe) as the main window, with the calculator plugged into it as an
app. `apps` lists what is on the desk and **`calc` opens the keypad** — which then opens its own
history and plot windows, so the window list is a tree rather than a list.

```
~ > apps
name        launchable  summary
profiles    false       named sets of environment variables and binary directories
calculator  true        a keypad, a tape, and a plotter for anything with a variable in it

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
`CalculatorApp.Window` is the same keypad, the same engine and the same previews, opened under a name
on somebody else's `GuiApp` instead of being the main window of its own — so neither is a fork of the
other. What differs is who owns the frame loop.

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
