package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.ComposedShader;
import dev.supirvast.vastir.core.ShaderStage;
import dev.vexelray.surface.Cones;
import dev.vexelray.surface.ParamBlock;
import dev.vexelray.surface.Surface;
import dev.vexelray.technique.sdf.ConeField;
import dev.vexelray.technique.sdf.MarchSettings;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;
import dev.vexelray.engine.embedded.SampledTechniqueHost;
import dev.vexelray.vulkan.present.SampledColorTarget;
import dev.vexelray.vulkan.present.StorageBuffer;
import sibarum.kronometer.Dur;
import sibarum.probe.Lane;
import sibarum.probe.Probe;

import java.util.List;

/**
 * The ray-marched plot: geometry in a buffer, camera in a push constant, and two resolutions of the same
 * picture.
 *
 * <h2>Why this is not a shader per expression</h2>
 *
 * <p>{@code SurfaceCompiler} unrolls a scene <em>into</em> the fragment — every cone becomes its own tree of
 * folded arithmetic. That marches fastest, and it is why a curve of a few hundred segments lowers to hundreds
 * of kilobytes of SPIR-V, and why building a pipeline from one was measured at <b>five seconds, on the frame
 * loop</b>. The previous build of this calculator is where that number comes from.
 *
 * <p>{@link ConeField} trades it back: the same formula with its coefficients loaded per cone per step instead
 * of folded, so the module is a fixed size and <b>the same bytes for every scene</b>. Built when the window
 * opens, and again only when the colour changes, because the colour is compiled in. A new expression is a
 * {@code memcpy}.
 *
 * <h2>The split that makes an orbit free</h2>
 *
 * <p>Everything depending on the expression, the domain or the sample count is <b>geometry</b>: it runs on a
 * worker, produces a {@code float[]}, and lands as one {@link StorageBuffer#update}. Everything about the
 * camera is <b>six floats of push constant</b>. So orbiting repacks nothing — no resampling, no stroke
 * rebuild, no shader work at all — and a scene that takes a second to sample orbits at full rate throughout,
 * because it is orbiting the previous buffer. That is the whole reason the plot is marched rather than drawn.
 *
 * <h2>Two resolutions, because the cost is very nearly all pixels</h2>
 *
 * <p>A target has fixed pixels and the sampler upscales, so the marched image's size is a quality knob with a
 * price list — and the price is measured, in {@code docs/framework-notes.md} FN-20: quartering the pixels is a
 * 3.8× speedup where cutting the step budget from 128 to 48 is 17%. At the window's own size a march costs
 * most of a frame, which is affordable once and not sixty times a second.
 *
 * <p>So there are two, and the only difference between them is how many pixels they have. The <b>draft</b> is
 * fixed and small and is marched every frame the picture is moving; the <b>still</b> is the size of the box it
 * is shown in, and is marched once, on the frame after everything has stopped for {@link #STILL_PAUSE}. Same
 * scene, same pipeline module, same camera, same everything else — so the swap is a sharpening and not a
 * change of picture, which is what makes it unobtrusive enough to do on every gesture.
 *
 * <p>The frame after everything stopped is not a frame a render-on-demand loop would otherwise run, and
 * arranging for it is the whole of {@link Settle}. The one visible cost of the arrangement is that a scripted
 * screenshot taken immediately after a gesture photographs the draft: {@code Automation.settle} waits on the
 * tree, not on the clock.
 *
 * <h2>The cost this cannot avoid</h2>
 *
 * <p>{@code SampledTechniqueHost} submits a one-time command buffer and <b>blocks on a fence, on the calling
 * thread</b>, before returning — as {@link SampledColorTarget#renderInto} did before it, and for the same
 * reason: the contract is that the image is ready when it returns. The march therefore happens on the GUI
 * thread, inside the frame. At rest it costs nothing, because a still plot under
 * a still camera is not marched at all; during an orbit it is paid every frame. Both passes are measured here
 * ({@code -Dprobe=all}, lane {@code gpu}, {@code plot.march} and {@code plot.march.still}) rather than
 * assumed. See {@code docs/framework-notes.md} FN-11.
 */
final class March implements Motion.Eye {

    /**
     * The draft image's pixels — what a moving picture is marched at.
     *
     * <p>Fixed, and smaller than the window on purpose: this is the resolution the orbit's frame rate is paid
     * out of, and FN-20's table is what the number comes from. The sampler upscales it into the box, which at
     * this tube thickness is soft rather than broken — and only for as long as the hand is on it, because the
     * frame after it stops is marched at {@link #still}'s size instead.
     */
    private static final int MARCH_W = Integer.getInteger("plot.width", 640);
    private static final int MARCH_H = Integer.getInteger("plot.height", 400);

    /**
     * How long everything must hold still before the sharp march is worth paying for.
     *
     * <p>Short enough that letting go of a drag and looking at the result is one motion rather than two, long
     * enough that it is not spent on the gap between two pointer events, or on the moment a preset's ease
     * passes through a frame where the camera happens to round to the same numbers.
     */
    static final Dur STILL_PAUSE = Dur.ms(160);

    /**
     * The still image's ceiling, in pixels.
     *
     * <p>The still march is one frame rather than every frame, so it can be expensive — but not unboundedly:
     * a maximised window on a large display is four megapixels, which at FN-20's measured rate is most of a
     * quarter of a second of stalled GUI thread, and a quarter-second stall after a gesture is worse than a
     * soft picture. Past this the still pass keeps the box's shape and gives up its scale, which is the
     * degradation the eye minds least.
     */
    private static final int STILL_PIXELS = Integer.getInteger("plot.still.pixels", 1_200_000);

    /**
     * The step the still target's size is rounded up to.
     *
     * <p>A target cannot be resized, and one that is replaced cannot be freed (see {@link #still()}), so the
     * question is not "what size exactly" but "how many different sizes can one session mint". Rounding to a
     * coarse grid answers it: a window dragged slowly across the screen crosses a handful of these rather than
     * hundreds, and the pixels the rounding wastes are marched once per gesture, not once per frame.
     */
    private static final int STILL_GRAIN = 256;

    /** The probe spans the two passes are measured as. Constants, because a lane's tallies key on the name. */
    private static final String DRAFT_SPAN = "plot.march";
    private static final String STILL_SPAN = "plot.march.still";

    /**
     * The sphere-trace budget.
     *
     * <p>{@link MarchSettings#DEFAULT} is 128 steps and a 120-unit far plane — the values Fathom settled on for
     * a first-person dungeon. A plot is a two-unit box seen from seven units away with nothing behind it, so
     * most of that budget is spent marching through empty space that ends at the sky. Tunable because it is the
     * dominant term in the frame cost (see the class note) and because the right value is a measurement, not a
     * default.
     */
    private static final int MARCH_STEPS = Integer.getInteger("plot.steps", 48);

    /** How far a ray goes before it is sky. The geometry is inside a box about two units across. */
    private static final double FAR_PLANE = Double.parseDouble(System.getProperty("plot.far", "14"));

    /**
     * The buffer's ceiling, in cones.
     *
     * <p>A storage buffer cannot be resized — the pipeline was built against its descriptor set layout — so it
     * is sized once for the worst case. At {@link ConeField#floatsFor} that is about 278k floats, 1.1 MB, which
     * is nothing on the GPU and comfortably above the surface wireframe's few thousand. Geometry that would
     * exceed it is <b>refused with a message</b> rather than truncated: a plot missing a quarter of itself with
     * no notice is the one outcome worth engineering against.
     */
    static final int MAX_CONES = 32_768;

    /**
     * A long lens.
     *
     * <p>The march is a pinhole, so the plot has perspective — which {@code vexelray-gui-plot}'s orthographic
     * camera deliberately does not, on the argument that making the far side smaller makes comparing heights a
     * lie. That argument is real, and a long lens is how it is answered here: at {@code 2.5} against the
     * framework's default {@code 1.4} the convergence is mild enough to read as depth rather than as
     * foreshortening.
     */
    private static final double FOCAL_LENGTH = 2.5;

    /**
     * The near plane of the depth this scene writes.
     *
     * <p>Nothing here reads that depth. The march owns its target outright — one technique, its own
     * {@link SampledColorTarget}, no second pass rasterising into the same attachment — so the clip depth this
     * number parameterises is written every frame and compared against never. It is a required part of a scene
     * regardless, because a scene that <em>does</em> share an attachment must not be able to leave the
     * convention unstated: see {@link ClipDepth}.
     *
     * <p>Which is why the framework default is taken as it comes rather than tuned to a plot's scale. The
     * hyperbolic curve spends its precision near the eye and this scene is a two-unit box seen from seven
     * units away, so a larger near plane would genuinely suit it — but it would be buying precision in a
     * buffer nothing reads, and would then need explaining to everyone who came looking for the second
     * technique that justified it.
     */
    private static final double NEAR_PLANE = ClipDepth.DEFAULT.near();

    /**
     * One resolution the plot can be marched at: somewhere to put the pixels, and the pipeline that writes
     * them.
     *
     * <p>They come in pairs because a pipeline is built against one render pass at one size — two sizes is two
     * pipelines, from the same SPIR-V — so a pass is the smallest thing that can honestly be asked for a
     * picture.
     */
    private static final class Pass {

        final SampledColorTarget target;

        /**
         * What this pass draws, and the thing that owns the pipeline for it.
         *
         * <p>One per pass rather than one per plot, for the reason the pair existed before techniques did: a
         * pipeline is built against one render pass at one size, so two sizes is two of them. What the two
         * share is everything worth sharing — the composed module and the cone buffer, both handed in.
         *
         * <p>The colour still means a new pipeline, because {@link ConeField} carries no colour in its buffer
         * -- a cone is eight floats of geometry -- and {@code compose} passes no albedo function, so the whole
         * scene is drawn in {@code SdfScene.albedo()}, which the fragment bakes as a constant. That is only
         * affordable because this module is a <em>fixed size</em>: the same few kilobytes whatever the buffer
         * holds, so the build is milliseconds rather than the five seconds an unrolled scene cost. It is now
         * {@link ConeFieldTechnique#module}'s business rather than this class's, and still measured on the gpu
         * lane as {@code plot.repipeline}.
         */
        final ConeFieldTechnique technique;

        /**
         * The pass around the technique: begins the render pass, sets the viewport, records, submits, waits.
         *
         * <p>This is the whole of what used to be a {@code renderInto(pipeline, ...)} call, and the reason it
         * is worth the indirection is that the technique on the other side of it is now an ordinary
         * {@code RenderTechnique} — the same interface the windowed engine drives, so a second thing to
         * composite over the plot is one more entry in the list rather than a second march.
         */
        final SampledTechniqueHost host;

        Pass(SampledColorTarget target, ConeFieldTechnique technique) {
            this.target = target;
            this.technique = technique;
            this.host = new SampledTechniqueHost(target, List.of(technique));
        }
    }

    /**
     * The application, kept for one thing: minting a bigger target when the box outgrows the one in hand.
     *
     * <p>Held rather than asked for at the call site because the device is not public and this is the only
     * door to it — see {@code GuiApp.viewport}, which exists for exactly that reason.
     */
    private final GuiApp app;

    private final StorageBuffer cones;

    /** The moving picture's pass: fixed size, marched every frame something is changing. */
    private final Pass draft;

    /**
     * The stopped picture's pass: the size of the box, marched once per quiet stretch.
     *
     * <p>Null until the first one is asked for, which is the first time the plot has stood still — so a window
     * that is never looked at never allocates it, and the size it is finally minted at is a box that has been
     * laid out rather than a guess made before the first frame.
     */
    private Pass still;

    /** Which pass the node is currently pointed at. Swapped in {@link #show}, on the GUI thread, never read off it. */
    private Pass showing;

    /**
     * The scene the current pipeline was composed from, and that scene's parameter block.
     *
     * <p>Kept because <b>the push constant is no longer the camera's six floats</b>: the block is the camera,
     * then the lens, then one float per scene parameter. A frame that pushes only the first six leaves the focal
     * length as whatever was last in the command buffer — a garbage lens, on a picture that still draws and says
     * nothing. That is why {@code SdfComposer.cameraBytes} is deprecated, and this pair is what its replacement
     * needs: the scene to read the lens from, the block to read the parameters from.
     */
    private volatile SdfScene scene;
    private volatile ParamBlock params;

    /**
     * The two stages the current scene composed to, kept so that a second target can be given a pipeline
     * without composing the module again.
     *
     * <p>A few kilobytes, and the alternative is recomposing an identical scene every time the window is
     * resized past a grain — which would be correct and would make a resize look like a colour change in the
     * profile, for no reason anyone could find later.
     */
    private ComposedShader vertex;
    private ComposedShader fragment;

    private final Surface.Rgb sky;

    /** Set by the worker when new geometry is ready; taken by the frame loop. */
    private volatile float[] pending;

    /** Whether anything the picture depends on has changed since the last march. */
    private volatile boolean dirty = true;

    /**
     * The camera the next march will use, as {@link Motion} last landed it.
     *
     * <p><b>Not the camera's state — a copy of it.</b> {@link Motion} owns the camera, because a camera has a
     * future (a preset is travelling to it, the spin is winding it) and this class only ever needs the six
     * numbers it is about to hand the shader. Written once a frame from the timeline, read by {@link #frame}
     * and {@link #lens} on the same thread in the same frame; volatile because the automation server's thread
     * also reads them through {@code lens()}.
     */
    private volatile double yaw;
    private volatile double pitch;
    private volatile double targetX;
    private volatile double targetY;
    private volatile double targetZ;
    private volatile double distance;
    private volatile int coneCount;

    /** The node showing the image. Held for its measured box, which is where the aspect comes from. */
    private volatile Node viewport;

    /** The aspect the last march used, so a resize re-marches even though the camera did not move. */
    private volatile double lastAspect;

    /** The colour the current pipeline was built for. */
    private volatile Ramp currentRamp = Ramp.BLURPLE;

    /** A colour the GUI thread owes a pipeline for; taken in frame(), because a build is not a worker's to do. */
    private volatile Ramp repipeline;

    /** Set by {@link Settle} when the picture has held still; taken by the next frame, which marches it sharp. */
    private volatile boolean refine;

    /**
     * How this says the picture is moving again, so that it is told when it stops.
     *
     * <p>A no-op until {@link #stirredBy} is called, which is deliberate: a {@code March} with no clock behind
     * it still draws, it simply never refines. That is the honest behaviour for a half-wired renderer, and it
     * is the difference between a missing wire and a null pointer on the frame loop.
     */
    private volatile Runnable stir = () -> {
    };

    March(GuiApp app) {
        this.app = app;
        this.sky = Look.scene(Calculator.page());

        cones = app.storage(ConeField.floatsFor(MAX_CONES), ConeField.BINDING);

        // Before the pass, not after: a technique is constructed with the module it will build from, and
        // compose() is what produces one. The pipeline itself is not built here at all -- the host realises on
        // the first render, which is on the frame thread where GPU work belongs.
        compose(Ramp.BLURPLE);
        draft = pass(app.viewport(MARCH_W, MARCH_H));
        showing = draft;
    }

    /**
     * Close both passes' pipelines, at shutdown, in reverse order of minting.
     *
     * <p>New with techniques, and worth saying why it did not exist before: a {@code GraphicsPipeline} built by
     * {@code pipelineFor} was never closed at all, on the reasoning that a handful of kilobytes across four
     * ramps was cheaper than getting the lifetime wrong (FN-25). A technique owns its pipeline and says so, so
     * the lifetime is no longer a judgement call — and the probe's resource ledger stops reporting live
     * pipelines at exit.
     *
     * <p>Registered with the framework's disposer <em>after</em> {@code GuiApp}, so it runs before it: these
     * are GPU objects on that device, and the device has to outlive them.
     */
    void close() {
        Pass sharp = still;
        if (sharp != null) {
            still = null;
            sharp.host.close();
        }
        draft.host.close();
    }

    /** How the picture says it is moving. Called with {@link Settle#stir}, which is what answers in the end. */
    void stirredBy(Runnable stir) {
        this.stir = stir;
    }

    /** The picture has held still for {@link #STILL_PAUSE}: the next frame owes it a march at the box's size. */
    void refine() {
        refine = true;
    }

    /**
     * Compose the module for one colour: the scene, its parameter block, and the two stages.
     *
     * <p>The surface handed to {@code compose} is a <b>placeholder</b>: {@link ConeField} does not compile
     * {@code scene.surface()} at all, because the field comes from the buffer. Everything <em>else</em> about
     * the picture is read from the scene and baked as a constant — shading, march budget, albedo, sky, focal
     * length — so each of those is a property of the pipelines built from this rather than a live control.
     *
     * <p>Which is why the colour is here. See {@link #recolour}.
     *
     * <p>The resolution is <b>not</b> here, and that is what lets the two passes be the same picture: the
     * module says nothing about how many pixels it will be asked to fill, so the draft and the still march
     * identical scenes and differ only in the target they are pointed at.
     */
    private void compose(Ramp ramp) {
        SdfScene scene = new SdfScene(
                new Surface.Sphere(0, 0, 0, 1),      // never compiled; ConeField replaces the field
                dev.vexelray.shader.Shadings.defaultKeyLight(),
                new MarchSettings(MARCH_STEPS, MarchSettings.DEFAULT.maxStep(), FAR_PLANE,
                        MarchSettings.DEFAULT.hitEpsilon(), MarchSettings.DEFAULT.hitEpsilonSlope(),
                        MarchSettings.DEFAULT.normalEpsilon(), MarchSettings.DEFAULT.normalEpsilonSlope()),
                // The whole scene's colour, because a cone in the buffer carries none of its own -- see
                // recolour(). Taken from the middle of the ramp, which is what a single sample of a gradient has
                // to be if it is going to stand for the whole of it.
                Look.scene(ramp.at(0.6)),
                sky,
                FOCAL_LENGTH,
                NEAR_PLANE);

        List<ComposedShader> composed = ConeField.compose(scene);
        this.vertex = stage(composed, ShaderStage.VERTEX);
        this.fragment = stage(composed, ShaderStage.FRAGMENT);

        // Held for the push constant, which is no longer the camera's six floats: the block carries the lens
        // and the scene's parameters after them, and the writer needs the scene to read the first and the block
        // to read the second. Assigned before any pipeline so a frame can never see one without the other.
        this.scene = scene;
        this.params = SdfComposer.paramBlock(scene);
    }

    /**
     * A pass over one target: a technique carrying the module composed so far, and a host to drive it.
     *
     * <p>Nothing is built on the GPU here. {@code SampledTechniqueHost} realises on its first render, which is
     * on the frame thread — so a pass can be minted from wherever a target was, and the pipeline is built where
     * every other piece of this application's GPU work happens.
     */
    private Pass pass(SampledColorTarget target) {
        return new Pass(target, new ConeFieldTechnique(cones, vertex, fragment, scene, params));
    }

    /**
     * Change the plot's colour.
     *
     * <p><b>A {@code ConeField} cone is eight floats of geometry and nothing else</b> — {@code ax, ay, az, ar,
     * bx, by, bz, br} — and {@code ConeField.compose} passes no albedo function, so the fragment shades every
     * hit with {@code scene.albedo()}. {@link Surface.Stroke}'s per-vertex colour, which its own javadoc
     * advertises and which the compiled-in path honours, <em>does not survive the trip through the buffer</em>.
     * It is dropped in silence: the picture still draws, in one colour, and nothing anywhere says so.
     *
     * <p>So a colour ramp cannot be a property of the geometry here, and a colour change is a new module and a
     * new pipeline per pass. That is affordable only because {@code ConeField}'s module is a fixed size — the
     * same few kilobytes whatever the buffer holds — which is the whole point of it. Measured rather than
     * assumed: {@code plot.repipeline} on the gpu lane is <b>62 ms</b> for the two, against five seconds for
     * one unrolled scene. Two builds rather than one is the second pass's whole cost outside a frame, and it
     * is paid when a person picks a colour rather than while anything is moving.
     *
     * <p>The old pipelines are <b>not</b> closed here. A frame may still be in flight against one, and there is
     * no fence this side to wait on; they are a handful of kilobytes each and there are four ramps, so leaking
     * at most a few is the cheaper mistake to make. See {@code docs/framework-notes.md} FN-25.
     */
    void recolour(Ramp ramp) {
        if (ramp == currentRamp) {
            return;
        }
        currentRamp = ramp;
        repipeline = ramp;
        dirty = true;
    }

    /**
     * Point a node at the marched image.
     *
     * <p>Called once, and the handle changes only when the picture swaps between the two passes — at most
     * twice per gesture, rather than the once per frame that would put a run boundary in the vertex buffer
     * every frame. {@link #show} is what keeps that promise.
     */
    void showIn(Node node) {
        this.viewport = node;
        node.image(draft.target);
        showing = draft;
    }

    /**
     * The aspect ratio the shader is given — <b>the node's, not the target's</b>.
     *
     * <p>This is the trap in showing a fixed-size render target in a flexed box, and it is invisible until
     * something in the picture is supposed to be round. The image is drawn across the node's whole border box,
     * so a 640×400 target in a 1180×675 node is <em>stretched</em>. The shader maps its {@code uv} square onto
     * whatever that box turns out to be, so the aspect it must correct for is the box's — hand it the target's
     * and every circle in the plot comes out 9% wide, uniformly, which reads as a slightly odd camera rather
     * than as a bug.
     *
     * <p>Falls back to the target's own ratio before the first layout, when there is no box to ask.
     */
    private double aspect() {
        NodeLayout box = viewport == null ? null : viewport.layout();
        if (box == null || box.rect().w() <= 0 || box.rect().h() <= 0) {
            return MARCH_W / (double) MARCH_H;
        }
        return box.rect().w() / (double) box.rect().h();
    }

    /**
     * The camera, as something that can project.
     *
     * <p>The one place the march's own parameters are handed out, so anything drawn over the image is using the
     * same six numbers the shader was given rather than its own idea of them.
     */
    Lens lens() {
        return new Lens(eyeX(), eyeY(), eyeZ(), yaw, pitch, aspect(), FOCAL_LENGTH);
    }

    /**
     * Hand over new geometry, from a worker.
     *
     * <p>Packed here rather than in the frame loop: {@link ConeField#pack} computes a bounding sphere per group
     * of eight cones, which is the culling the march depends on, and it is CPU work that has no business on the
     * GUI thread.
     */
    void geometry(Geometry.Built built) {
        List<Cones.Cone> flat = Cones.of(built.strokes());
        if (flat.size() > MAX_CONES) {
            System.out.println("plot refused: " + flat.size() + " cones exceeds the buffer's "
                    + MAX_CONES + "; nothing was drawn rather than part of it");
            return;
        }
        float[] packed = ConeField.pack(Cones.flatten(flat), flat.size());
        coneCount = flat.size();
        pending = packed;
        dirty = true;
    }

    /**
     * Take the camera {@link Motion} has arrived at.
     *
     * <p><b>Compares before it dirties, and that comparison is load-bearing.</b> This is called once a frame
     * for the life of the window, whether or not the camera moved, because it is the landing point of a bound
     * cell. Setting {@code dirty} unconditionally here would mark every frame dirty, and a marched frame costs
     * a synchronous GPU round-trip on the GUI thread (FN-11) — so the window would pay for an orbit it is not
     * doing, forever, and the whole "a still plot is not marched at all" property would quietly be gone.
     */
    @Override
    public void view(double newYaw, double newPitch, double newDistance, double tx, double ty, double tz) {
        if (newYaw == yaw && newPitch == pitch && newDistance == distance
                && tx == targetX && ty == targetY && tz == targetZ) {
            return;
        }
        yaw = newYaw;
        // The application's clamp is Motion's; this one is a backstop, and wider on purpose -- the march's own
        // limit is 4..86 degrees, and a value that reached here outside it would be a bug in the caller rather
        // than something to silently correct into a plausible picture.
        pitch = Math.clamp(newPitch, Math.toRadians(-86), Math.toRadians(86));
        distance = newDistance;
        targetX = tx;
        targetY = ty;
        targetZ = tz;
        dirty = true;
    }

    /**
     * The world-space shift that dragging the picture by this many screen pixels amounts to.
     *
     * <p>Panning moves what the camera is <em>looking at</em> rather than turning it, so the ray directions are
     * untouched and the whole of it is a shift of the eye. The two directions come out of the shader's own ray
     * construction rather than being guessed: {@code sx} contributes {@code (cosYaw, 0, -sinYaw)} and
     * {@code sy} contributes {@code (sinPitch·sinYaw, cosPitch, sinPitch·cosYaw)}, so those are exactly screen
     * right and screen up in world space.
     *
     * <p>Scaled by {@code distance}, which is what makes a drag move the picture by the same amount on screen
     * however far out the camera is. Without it, panning is unusably slow zoomed in and wild zoomed out. The
     * distance is passed in rather than read off the field because the caller may be a gesture ahead of the
     * last landing; the <em>directions</em> may safely come from the field, since a pan does not turn the
     * camera and so cannot invalidate them.
     */
    @Override
    public double[] panDelta(double screenDx, double screenDy, double distance) {
        Lens lens = lens();
        double[] right = lens.screenRight();
        double[] up = lens.screenUp();
        double scale = distance / MARCH_H;
        // Dragging moves the plot with the pointer, so the camera goes the other way on both axes -- and the
        // screen's y runs down while the world's up runs up, which is the second negation.
        double dx = -screenDx * scale;
        double dy = screenDy * scale;
        return new double[]{
                dx * right[0] + dy * up[0],
                dx * right[1] + dy * up[1],
                dx * right[2] + dy * up[2]};
    }

    int cones() {
        return coneCount;
    }

    /**
     * One frame's worth of GPU work, on the GUI thread, from {@code beforeFrame}.
     *
     * <p>Does nothing at all when neither the geometry nor the camera has moved and the picture is already as
     * sharp as it is going to get, which is what makes a still window cost nothing — the frame loop parks and
     * this is not reached.
     *
     * <p>The two branches are the whole of the resolution policy. <b>Something changed</b>: march the draft,
     * and say so, so that {@link Settle} can tell us when it stops. <b>Nothing has changed for a while</b>:
     * march the still one, once. A change while a refinement is owed cancels it rather than queueing it —
     * that refinement was for a picture that no longer exists, and the stir on the way past asks for the one
     * that replaces it.
     */
    void frame() {
        float[] fresh = pending;
        if (fresh != null) {
            pending = null;
            cones.update(fresh, fresh.length);
        }
        Ramp wanted = repipeline;
        if (wanted != null) {
            repipeline = null;
            try (var z = Probe.zone(Lane.GPU, "plot.repipeline")) {
                compose(wanted);
                draft.technique.module(vertex, fragment, scene, params);
                if (still != null) {
                    still.technique.module(vertex, fragment, scene, params);
                }
            }
        }
        double aspect = aspect();
        // A resize moves nothing about the camera and still changes the picture, because the aspect the shader
        // corrects for is the box it is stretched into. Without this the plot keeps whatever proportions the
        // window had when it was last turned.
        if (dirty || aspect != lastAspect) {
            dirty = false;
            lastAspect = aspect;
            refine = false;
            stir.run();
            march(draft, aspect, DRAFT_SPAN);
            return;
        }
        if (!refine) {
            return;
        }
        refine = false;
        Pass sharp = still();
        if (sharp != draft) {
            march(sharp, aspect, STILL_SPAN);
        }
    }

    /**
     * March into one pass and put it on screen. The only place either target is written or shown.
     *
     * <p>Aim, then render — the technique's content API first and the host second, which is the order the
     * engine's own frame callback runs in for the same reason: per-frame data reaches a technique a few
     * microseconds before the {@code record} that reads it, on one thread, with no handshake needed between
     * them.
     */
    private void march(Pass pass, double aspect, String span) {
        pass.technique.camera(eyeX(), eyeY(), eyeZ(), yaw, pitch, aspect);
        try (var zone = Probe.zone(Lane.GPU, span)) {
            pass.host.render((float) sky.r(), (float) sky.g(), (float) sky.b(), 1f);
        }
        show(pass);
    }

    /**
     * Point the node at a pass, if it is not already pointed there.
     *
     * <p>The guard is the point: {@code Node.image} is a mutation, and a mutation is a wake, so calling it on
     * every frame of an orbit would be a window that can never park — and it would park the moment the plot
     * went still, which is the one case this would be trying to serve.
     *
     * <p>Published from {@code FrameStage.APP}, which runs before the tree is reconciled, so the swap lands in
     * the same frame as the march that earned it rather than a frame later.
     */
    private void show(Pass pass) {
        if (showing == pass) {
            return;
        }
        showing = pass;
        viewport.image(pass.target);
    }

    /**
     * The pass to march a stopped picture into: one whose target is about the size of the box on screen.
     *
     * <p><b>Replaced rather than resized, and never freed.</b> {@code GuiApp.viewport}'s javadoc says an
     * application that resizes a viewport should close the old target itself — and doing that is a double free,
     * because {@code GuiApp} also closes every target it ever minted when the application shuts down, and
     * hands out no way to withdraw one. So the policy here is the one that survives that: round the box up to a
     * coarse grid so a session mints a handful of targets rather than one per resize event, re-mint only when
     * the box has outgrown the one in hand or has shrunk to less than half of it, and let the application's own
     * shutdown be what frees them. See {@code docs/framework-notes.md} FN-27.
     *
     * <p>Answers the draft pass when the box is small enough that the draft is already sharper than the screen
     * — a narrow window on this application is a plot smaller than 640×400, and marching a second image at the
     * same resolution to show the same pixels is work with no picture in it.
     */
    private Pass still() {
        NodeLayout box = viewport == null ? null : viewport.layout();
        if (box == null || box.rect().w() <= 0 || box.rect().h() <= 0) {
            return draft;      // no measured box yet; the next change will ask again
        }
        int w = grain(box.rect().w());
        int h = grain(box.rect().h());
        long pixels = (long) w * h;
        if (pixels > STILL_PIXELS) {
            // Keep the box's shape and give up its scale, which is the degradation the eye minds least.
            double shrink = Math.sqrt(STILL_PIXELS / (double) pixels);
            w = floorGrain(w * shrink);
            h = floorGrain(h * shrink);
        }
        if (w <= MARCH_W && h <= MARCH_H) {
            return draft;
        }
        Pass held = still;
        if (held != null && fits(held.target, w, h)) {
            return held;
        }
        Pass minted = pass(app.viewport(w, h));
        if (held != null) {
            // The superseded pass's pipeline is closed, which the hand-written version could not safely do:
            // there was no fence on this side to know a frame had finished with it, so it leaked deliberately
            // (FN-25). renderInto waits on its own submission before returning and this runs between frames on
            // the same thread, so by here nothing is reading it. The *target* is still not closed -- GuiApp
            // minted it and closes every one it ever minted, and there is no way to withdraw one (FN-27).
            held.host.close();
        }
        still = minted;
        return minted;
    }

    /**
     * Whether a target in hand will do for a box that wants {@code w} × {@code h}.
     *
     * <p>Big enough not to be upscaled, and not so big that most of the march is spent on pixels nobody will
     * see. The band between the two is what makes a window nudged back and forth across a grain boundary reuse
     * one target rather than minting one per nudge — which matters here more than it usually would, because
     * nothing minted is ever given back.
     */
    private static boolean fits(SampledColorTarget target, int w, int h) {
        return target.width() >= w && target.height() >= h
                && target.width() <= 2 * w && target.height() <= 2 * h;
    }

    /**
     * The box's size in target pixels: up to the grid, so that a slow resize crosses a handful of sizes.
     *
     * <p>A layout rect is in the engine's logical units and is a {@code float}; the density is pinned at 1.0
     * here (framework-notes FN-8), so the ceiling of it is the pixel count the box is actually drawn with.
     */
    private static int grain(double px) {
        int whole = (int) Math.ceil(px);
        return Math.max(STILL_GRAIN, (whole + STILL_GRAIN - 1) / STILL_GRAIN * STILL_GRAIN);
    }

    /** Likewise, down — for the cap, where rounding up would put the size back over the ceiling it just met. */
    private static int floorGrain(double px) {
        return Math.max(STILL_GRAIN, (int) px / STILL_GRAIN * STILL_GRAIN);
    }

    // ------------------------------------------------------------------ camera

    // An eye orbiting the origin at a fixed distance, looking back at it -- and the axis swap every renderer in
    // this stack makes: the plot's z is the world's y. Taken from StrokeMarchSmoke, which is the reference for
    // this convention, because there is no Java-side projection to read it off (framework-notes.md FN-12).

    private double eyeX() {
        return targetX - distance * Math.cos(pitch) * Math.sin(yaw);
    }

    private double eyeY() {
        return targetY + distance * Math.sin(pitch);
    }

    private double eyeZ() {
        return targetZ - distance * Math.cos(pitch) * Math.cos(yaw);
    }

    private static ComposedShader stage(List<ComposedShader> composed, ShaderStage want) {
        return composed.stream().filter(c -> c.stage() == want).findFirst()
                .orElseThrow(() -> new IllegalStateException("ConeField.compose produced no " + want + " stage"));
    }
}
