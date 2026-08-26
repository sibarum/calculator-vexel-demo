package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Gui;
import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.input.DragEvent;
import dev.vexelray.gui.core.layout.Length;
import dev.vexelray.gui.core.layout.Rect;
import dev.vexelray.gui.plot.Camera;
import dev.vexelray.gui.plot.Framing;
import dev.vexelray.gui.plot.Volume;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.shader.Shadings;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.SampledColorTarget;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The third renderer: the expression compiled to a distance field and ray-marched, in a box beside the legend
 * and the status bar.
 *
 * <p>It draws nothing itself. A {@link SampledColorTarget} from {@link GuiApp#viewport} is a
 * {@code SampledImage}, and a node handed one through {@code Node.image} is a box that samples — so the whole
 * of the compositing is a marched image arriving as an ordinary node, rounding and clipping and laying out like
 * every other. There is no viewport node because there did not need to be one.
 *
 * <h2>The march happens between frames, and that is not a detail</h2>
 * {@link SampledColorTarget#renderInto} allocates a command pool, submits, and waits. A {@code VkQueue} is not
 * thread-safe, so doing that from the worker a drag handler runs on would race the presenter submitting the
 * frame. So a drag only moves the camera and raises a flag; {@link #pump()} is called from the frame loop's
 * own {@code beforeFrame} hook, on the thread that presents, and is the only place the GPU is touched.
 *
 * <h2>Two kinds of dirty</h2>
 * A <b>scene</b> change means new SPIR-V and a new pipeline — a different expression is a different shader, and
 * the compile is the expensive half. A <b>frame</b> change is only the camera, which is six floats of push
 * constant. Keeping them apart is the point of the whole exercise: turning this picture recompiles nothing and
 * rebuilds no geometry, where turning {@code SurfacePlot}'s rebuilds and re-uploads its entire lattice.
 *
 * <h2>The target is minted once and never closed here</h2>
 * A target from {@code GuiApp.viewport} belongs to the application, which closes every one it minted at
 * shutdown — and {@code SampledColorTarget.close()} is not idempotent, so closing one here as well would
 * destroy handles twice. It is therefore minted at the box's first laid-out size and replaced only if the box
 * grows well past it, with the superseded one left to the application to close. That costs a few megabytes in a
 * session where someone drags a window much larger, and it cannot double-free.
 *
 * <p>Sharpness is what a stale target costs, not correctness: {@code aspect} is a push constant and is always
 * the <em>box's</em> aspect, never the target's. The canvas stretches the target across the box, so rendering
 * at the box's aspect is what makes a circle in the box a circle — a target whose pixels no longer match is
 * resampled by the sampler and reads slightly soft, with nothing out of shape.
 */
final class SdfViewport {

    /** How far the eye orbits from the centre of the world box. */
    private static final double DISTANCE = 4.6;

    /** One press of an arrow key, matching {@code SurfacePlot}'s. */
    private static final double KEY_TURN = Math.toRadians(6);

    /** A drag across the whole box turns the picture this far, matching {@code SurfacePlot}'s. */
    private static final double DRAG_YAW = Math.PI;
    private static final double DRAG_PITCH = Math.PI / 2;

    /** The camera push-constant block: {@code camX, camY, camZ, yaw, pitch, aspect}. */
    private static final int PUSH_BYTES = SdfComposer.CAMERA_BYTES;

    /** One notch of zoom, matching the other two views: a root of two, so two notches are a doubling. */
    private static final double SCALE_STEP = Math.sqrt(2);

    /** Re-mint the target once the box exceeds it by this much, in either direction. */
    private static final float GROWTH = 1.35f;

    private final Gui gui;
    private final Node canvas;
    private final Consumer<String> status;

    /** Set the first time a window shows this viewport; the target cannot be minted before there is an app. */
    private volatile GuiApp app;

    private SampledColorTarget target;
    private GraphicsPipeline pipeline;

    /** The composed pair, built off the render thread and picked up by the next {@link #pump()}. */
    private volatile byte[] vertexSpirv;
    private volatile byte[] fragmentSpirv;
    private volatile SdfScene.Rgb sky = new SdfScene.Rgb(0.10, 0.12, 0.16);
    private volatile String report = "";

    private volatile boolean sceneDirty;
    private volatile boolean frameDirty;
    /** Whether this is the renderer currently mounted. A {@link Node} handle is write-only and cannot be asked. */
    private volatile boolean mounted;
    /** Latest-wins guard over {@link #show}, so a wheel gesture composes once rather than a dozen times. */
    private final AtomicInteger revision = new AtomicInteger();

    /** Whether the first marched frame has been reported. See {@link #pump()}. */
    private boolean announced;

    private Camera camera = Camera.DEFAULT;
    /** What the surface is coloured by, and what the next compose will bake in. */
    private volatile MarchStyle style = MarchStyle.LIT;
    /** The expression on show, kept so a change of style or of framing can recompile without being handed it. */
    private volatile Plottable plottable;
    /**
     * The volume the field on show was compiled for, or null to ask the framing pass for one.
     *
     * <p>Held here rather than derived each time because the controls move it: zooming scales its floor and
     * fitting re-runs its height, and each of those has to start from where the last one left off.
     */
    private volatile Volume volume;
    /** The two axis names the volume is bound to, as {@link SdfSurface} chose them. */
    private volatile String axisX = "x";
    private volatile String axisY = "y";

    SdfViewport(Gui gui, Consumer<String> status) {
        this.gui = gui;
        this.status = status;
        this.canvas = gui.box()
                .width(Length.FILL).height(Length.grow(1))
                .background(Palette.PLOT_BG)
                .corner(Length.rem(0.5f));
        gui.onDrag(canvas, this::drag);
        // Turning is a displacement, so the pointer is held for the gesture and warped back every frame -- the
        // same bargain SurfacePlot makes, and for the same reason.
        gui.dragLocksPointer(canvas, true);
        // A target needs a size and there is no size until the tree has been laid out, so the first box is the
        // cue to mint one rather than something to guess at.
        gui.onResize(canvas, box -> frameDirty = true);
    }

    Node node() {
        return canvas;
    }

    /** Remembered so {@link #pump()} can mint a target. Called every show; only the first one matters. */
    void attach(GuiApp app) {
        this.app = app;
    }

    /**
     * Mount or unmount this renderer — told rather than asked. A {@link Node} handle is write-only, so a hidden
     * viewport has no way to discover that it is hidden, and marching a box nobody can see is the most
     * expensive no-op in the application.
     */
    void mounted(boolean showing) {
        this.mounted = showing;
        canvas.visible(showing);
        if (showing) {
            frameDirty = true;
        }
    }

    /**
     * Compile {@code plottable} to a distance field and a shader for it.
     *
     * <p>On a worker, because this is where the cost is: lowering, the symbolic gradient that normalises the
     * implicit, and lowering the result to SPIR-V. None of it touches the GPU, so none of it belongs on the
     * thread that presents.
     */
    void show(Plottable plottable) {
        // The latest request wins and earlier ones drop their work, the way PlotSurface.invalidate does. It
        // matters more here: a wheel gesture asks for a dozen zooms in a second and each one is a lowering, a
        // symbolic gradient and a SPIR-V module. Without this they would all be built and all but one thrown
        // away, and the last picture would arrive well after the gesture stopped.
        int mine = revision.incrementAndGet();
        gui.async(() -> {
            if (revision.get() == mine) {
                showNow(plottable);
            }
        });
    }

    /**
     * Recolour without re-deriving anything.
     *
     * <p>A style is part of the shader, not a uniform in it, so this recompiles — see {@link MarchStyle}. It
     * goes back through the same worker the expression did, because the expensive half is the same half: the
     * field is lowered and normalised again on the way. Held expressions make that cheap to <em>ask</em> for
     * and it is still a compile, which is why it is on a button and not on the pointer.
     */
    void style(MarchStyle next) {
        this.style = next;
        Plottable current = plottable;
        if (current != null) {
            show(current);
        }
    }

    /** Which style the surface is coloured by. */
    MarchStyle style() {
        return style;
    }

    /** {@link #show} without the worker. */
    void showNow(Plottable plottable) {
        // A recolour or a zoom comes back through here with the expression it already had. Recognising that is
        // what keeps either from also throwing away the orientation someone turned the surface to -- neither is
        // a new picture, they are the same one repainted or rewidened.
        boolean sameExpression = plottable.equals(this.plottable);
        this.plottable = plottable;
        SdfSurface built = SdfSurface.of(plottable.expr(), plottable.variables(),
                sameExpression ? volume : null);
        if (!built.ok()) {
            vertexSpirv = null;
            fragmentSpirv = null;
            status.accept("cannot march: " + built.refusal());
            return;
        }
        // Style outermost, then the grid, then the light: the style says what colour the surface is, the grid
        // darkens that where a line falls, and the light shades the result. Each one only replaces the albedo
        // on the point it hands down, so none of the three knows the others exist.
        // What was actually compiled for, which a zoom or a fit starts from next time. On the first show that
        // is the framing pass's answer rather than anything this class chose.
        volume = built.volume();
        axisX = built.xName();
        axisY = built.yName();

        SdfScene scene = SdfScene.of(built.surface())
                .withAlbedo(new SdfScene.Rgb(0.78, 0.80, 0.86))
                .withShading(built.shading(style));
        List<ComposedShader> composed = new SdfComposer().compose(scene);
        sky = scene.sky();
        vertexSpirv = composed.get(0).spirv();
        fragmentSpirv = composed.get(1).spirv();
        report = style.label().toLowerCase() + ", marched -- " + fragmentSpirv.length / 1024
                + "kB of shader, " + scene.march().steps() + " steps a pixel";
        if (!sameExpression) {
            synchronized (this) {
                camera = Camera.DEFAULT;
            }
        }
        sceneDirty = true;
        frameDirty = true;
    }

    /**
     * Widen or narrow the domain, by the same root-of-two notch the other two views use.
     *
     * <p>Unlike turning, this <b>is</b> a recompile: the volume is what maps the plot onto the fixed world box,
     * so it is compiled into the field rather than read by the camera. It goes on a worker like every other
     * compose, so a notch costs a moment rather than a frame.
     */
    void zoom(int notches) {
        Volume from = volume;
        Plottable current = plottable;
        if (from == null || current == null) {
            return;
        }
        volume = from.scaledFloor(Math.pow(SCALE_STEP, -notches));
        show(current);
    }

    /** Re-fit the height to what the expression does across the floor now on show — "Fit", for a march. */
    void fitVertically() {
        Volume from = volume;
        Plottable current = plottable;
        if (from == null || current == null) {
            return;
        }
        volume = Framing.refit(current.expr(), from, axisX, axisY);
        show(current);
    }

    /** Turn the picture. Costs six floats next frame, and recompiles nothing. */
    synchronized void turn(double dYaw, double dPitch) {
        camera = camera.turned(dYaw, dPitch);
        frameDirty = true;
    }

    /** Where this picture is being looked at from, so the box view can match it when they are swapped. */
    synchronized Camera camera() {
        return camera;
    }

    /**
     * Look from {@code eye} instead — how the box view hands its orientation over when they are swapped.
     *
     * <p>The angles transfer verbatim. The eye this class orbits to is minus the forward direction the
     * generated fragment builds from {@code (yaw, pitch)}, which written in plot coordinates is exactly
     * {@link Camera#viewDirection}: {@code (cos p sin y, cos p cos y, -sin p)}. The box renderer projects along
     * that same vector, so the two agree about what a viewpoint is without either being adjusted for the other.
     *
     * <p>Costs nothing but a push constant — the camera was never in the shader, so arriving from somewhere
     * else does not recompile anything.
     */
    synchronized void camera(Camera eye) {
        this.camera = eye;
        frameDirty = true;
    }

    void nudge(double yawSteps, double pitchSteps) {
        turn(yawSteps * KEY_TURN, pitchSteps * KEY_TURN);
    }

    /** Back to where the framing pass put it, pointed the way it started — "Reset", for both halves at once. */
    void home() {
        synchronized (this) {
            camera = Camera.DEFAULT;
            frameDirty = true;
        }
        Plottable current = plottable;
        if (current != null) {
            volume = null;               // null asks SdfSurface to run the framing pass again
            show(current);
        }
    }

    private void drag(DragEvent e) {
        // Letting go is not a movement -- SurfacePlot's note applies unchanged.
        if (e.phase() != DragEvent.Phase.MOVE) {
            return;
        }
        double dYaw = e.dx() / Math.max(1f, e.nodeW()) * DRAG_YAW;
        double dPitch = e.dy() / Math.max(1f, e.nodeH()) * DRAG_PITCH;
        if (dYaw != 0 || dPitch != 0) {
            turn(dYaw, dPitch);
        }
    }

    /**
     * March one frame if anything asked for one. <b>Render thread only</b> — see the class note on the queue.
     *
     * <p>Cheap when nothing changed, which is the ordinary case: no flag, no work, and the node goes on showing
     * the target it was already showing.
     */
    void pump() {
        GuiApp live = app;
        byte[] vs = vertexSpirv;
        byte[] fs = fragmentSpirv;
        if (live == null || vs == null || fs == null || !mounted) {
            return;
        }
        Rect box = canvas.layout().content();
        int w = Math.round(box.w());
        int h = Math.round(box.h());
        if (w < 2 || h < 2) {
            return;                          // not laid out yet; the next frame will find it
        }
        boolean freshTarget = ensureTarget(live, w, h);
        if (ensurePipeline(vs, fs) || freshTarget) {
            frameDirty = true;
        }
        if (!frameDirty || pipeline == null || target == null) {
            return;
        }
        frameDirty = false;
        SdfScene.Rgb bg = sky;
        target.renderInto(pipeline, 0L, 0L, 3, cameraBytes((double) w / h),
                (float) bg.r(), (float) bg.g(), (float) bg.b(), 1f);
        status.accept(report + turned());
        if (!announced) {
            // One line, once. A windowed frame cannot be photographed from here, so this is the only evidence a
            // bounded launch leaves that the march actually ran rather than merely failing to crash.
            announced = true;
            System.out.println("marched " + target.width() + "x" + target.height() + " into a viewport: " + report);
        }
    }

    /** @return whether a new target was minted, which means the node has to be pointed at it */
    private boolean ensureTarget(GuiApp live, int w, int h) {
        if (target != null && w <= target.width() * GROWTH && h <= target.height() * GROWTH) {
            return false;
        }
        // The superseded target is deliberately not closed -- see the class note. The application minted it and
        // the application closes it.
        target = live.viewport(w, h);
        canvas.image(target);
        return true;
    }

    /** @return whether a new pipeline was built */
    private boolean ensurePipeline(byte[] vs, byte[] fs) {
        if (!sceneDirty && pipeline != null) {
            return false;
        }
        sceneDirty = false;
        if (pipeline != null) {
            // Safe here and nowhere else: renderInto waits for its own submission, and the presenter never
            // touches this pipeline -- it only samples the image the pipeline drew into.
            pipeline.close();
        }
        pipeline = target.pipelineFor(vs, "main", fs, "main", PUSH_BYTES);
        return true;
    }

    /**
     * The camera block for the current orientation: the eye orbited around the origin at {@link #DISTANCE},
     * pointed back at it.
     *
     * <p>The position comes from {@link Camera#eye} rather than from trigonometry written here. That is what
     * makes swapping with the box view sound instead of merely lucky: both renderers take their viewpoint from
     * the same vector, so equal angles are equal viewpoints by construction and there are not two derivations
     * to drift apart. It is also checked — {@code CameraTest} pins that an eye stands opposite the direction it
     * looks along, and that firing a ray from it arrives at the origin.
     *
     * <p>The only thing left to do here is the axis swap this class does everywhere: the plot's {@code z} is
     * the world's {@code y}, and the plot's {@code y} is the world's {@code z}.
     */
    private byte[] cameraBytes(double aspect) {
        Camera eye;
        synchronized (this) {
            eye = camera;
        }
        double[] at = eye.eye(DISTANCE);
        return SdfComposer.cameraBytes(at[0], at[2], at[1], eye.yaw(), eye.pitch(), aspect);
    }

    private String turned() {
        double yaw;
        double pitch;
        synchronized (this) {
            yaw = camera.yaw();
            pitch = camera.pitch();
        }
        return "  --  turned " + Math.round(Math.toDegrees(yaw)) % 360
                + ", tilted " + Math.round(Math.toDegrees(pitch));
    }
}
