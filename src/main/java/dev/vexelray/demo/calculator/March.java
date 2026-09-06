package dev.vexelray.demo.calculator;

import dev.vexelray.gui.core.Node;
import dev.vexelray.gui.core.app.GuiApp;
import dev.vexelray.gui.core.layout.NodeLayout;
import dev.vexelray.shader.ComposedShader;
import dev.supirvast.vastir.core.ShaderStage;
import dev.vexelray.surface.Cones;
import dev.vexelray.surface.Surface;
import dev.vexelray.technique.sdf.ConeField;
import dev.vexelray.technique.sdf.MarchSettings;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.SampledColorTarget;
import dev.vexelray.vulkan.present.StorageBuffer;
import sibarum.probe.Lane;
import sibarum.probe.Probe;

import java.util.List;

/**
 * The ray-marched plot: geometry in a buffer, camera in a push constant, one pipeline for the life of the
 * window.
 *
 * <h2>Why this is not a shader per expression</h2>
 *
 * <p>{@code SurfaceCompiler} unrolls a scene <em>into</em> the fragment — every cone becomes its own tree of
 * folded arithmetic. That marches fastest, and it is why a curve of a few hundred segments lowers to hundreds
 * of kilobytes of SPIR-V, and why building a pipeline from one was measured at <b>five seconds, on the frame
 * loop</b>. The previous build of this calculator is where that number comes from.
 *
 * <p>{@link ConeField} trades it back: the same formula with its coefficients loaded per cone per step instead
 * of folded, so the module is a fixed size and <b>the same bytes for every scene</b>. One pipeline, built once
 * when the window opens. A new expression is a {@code memcpy}.
 *
 * <h2>The split that makes an orbit free</h2>
 *
 * <p>Everything depending on the expression, the domain or the sample count is <b>geometry</b>: it runs on a
 * worker, produces a {@code float[]}, and lands as one {@link StorageBuffer#update}. Everything about the
 * camera is <b>six floats of push constant</b>. So orbiting repacks nothing — no resampling, no stroke
 * rebuild, no shader work at all — and a scene that takes a second to sample orbits at full rate throughout,
 * because it is orbiting the previous buffer. That is the whole reason the plot is marched rather than drawn.
 *
 * <h2>The cost this cannot avoid</h2>
 *
 * <p>{@link SampledColorTarget#renderInto} submits a one-time command buffer and <b>blocks on a fence, on the
 * calling thread</b>, before returning — its contract is that the image is ready when it returns. The march
 * therefore happens on the GUI thread, inside the frame. At rest it costs nothing, because a still plot under
 * a still camera is not marched at all; during an orbit it is paid every frame. It is measured here
 * ({@code -Dprobe=all}, lane {@code gpu}, {@code plot.march}) rather than assumed. See
 * {@code docs/framework-notes.md} FN-11.
 */
final class March {

    /**
     * The marched image's pixels.
     *
     * <p>Fixed, and smaller than the window on purpose: a target has fixed pixels and the sampler upscales, so
     * this is the quality knob rather than a compromise. {@code GuiApp.viewport} is deliberately not resized for
     * the application — re-marching is far too expensive to trigger from a resize the framework merely noticed.
     */
    private static final int MARCH_W = Integer.getInteger("plot.width", 640);
    private static final int MARCH_H = Integer.getInteger("plot.height", 400);

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

    /** How far the eye orbits the origin. The world box the geometry is built into is about two units. */
    private static final double DISTANCE = 7.0;

    private final SampledColorTarget target;
    private final StorageBuffer cones;
    private final GraphicsPipeline pipeline;
    private final SdfScene.Rgb sky;

    /** Set by the worker when new geometry is ready; taken by the frame loop. */
    private volatile float[] pending;

    /** Whether anything the picture depends on has changed since the last march. */
    private volatile boolean dirty = true;

    private volatile double yaw = Math.toRadians(38);
    private volatile double pitch = Math.toRadians(26);
    private volatile int coneCount;

    /** The node showing the image. Held for its measured box, which is where the aspect comes from. */
    private volatile Node viewport;

    /** The aspect the last march used, so a resize re-marches even though the camera did not move. */
    private volatile double lastAspect;

    March(GuiApp app) {
        this.sky = Look.scene(Calculator.page());

        target = app.viewport(MARCH_W, MARCH_H);
        cones = app.storage(ConeField.floatsFor(MAX_CONES), ConeField.BINDING);

        // The scene handed to compose() is a *placeholder*: ConeField does not compile scene.surface() at all,
        // because the field comes from the buffer. Everything else about the picture -- shading, march budget,
        // albedo, sky, focal length -- is read from it, so those are compile-time properties of this pipeline
        // and not live controls.
        SdfScene scene = new SdfScene(
                new Surface.Sphere(0, 0, 0, 1),      // never compiled; ConeField replaces the field
                dev.vexelray.shader.Shadings.defaultKeyLight(),
                new MarchSettings(MARCH_STEPS, MarchSettings.DEFAULT.maxStep(), FAR_PLANE,
                        MarchSettings.DEFAULT.hitEpsilon(), MarchSettings.DEFAULT.hitEpsilonSlope(),
                        MarchSettings.DEFAULT.normalEpsilon(), MarchSettings.DEFAULT.normalEpsilonSlope()),
                Look.scene(dev.vexelray.gui.core.style.Role.ACCENT),
                sky,
                FOCAL_LENGTH);

        List<ComposedShader> composed = ConeField.compose(scene);
        ComposedShader vertex = stage(composed, ShaderStage.VERTEX);
        ComposedShader fragment = stage(composed, ShaderStage.FRAGMENT);

        pipeline = target.pipelineFor(
                vertex.spirv(), vertex.entryPoint(),
                fragment.spirv(), fragment.entryPoint(),
                SdfComposer.CAMERA_BYTES,
                new long[]{cones.descriptorSetLayout()});
    }

    /** Point a node at the marched image. Called once; the handle never changes, so nothing rebinds per frame. */
    void showIn(Node node) {
        this.viewport = node;
        node.image(target);
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
    void geometry(List<Surface.Stroke> strokes) {
        List<Cones.Cone> flat = Cones.of(strokes);
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

    /** Turn the camera. Six floats next frame; no geometry is touched. */
    void turn(double dYaw, double dPitch) {
        yaw += dYaw;
        // The prototype clamps elevation to +/-75 degrees. The march's own clamp is wider (4..86), so this is
        // the application's rule and the framework's is a backstop rather than the thing being relied on.
        pitch = Math.clamp(pitch + dPitch, Math.toRadians(-75), Math.toRadians(75));
        dirty = true;
    }

    double yaw() {
        return yaw;
    }

    double pitch() {
        return pitch;
    }

    int cones() {
        return coneCount;
    }

    /**
     * One frame's worth of GPU work, on the GUI thread, from {@code beforeFrame}.
     *
     * <p>Does nothing at all when neither the geometry nor the camera has moved, which is what makes a still
     * window cost nothing — the frame loop parks and this is not reached.
     */
    void frame() {
        float[] fresh = pending;
        if (fresh != null) {
            pending = null;
            cones.update(fresh, fresh.length);
        }
        double aspect = aspect();
        // A resize moves nothing about the camera and still changes the picture, because the aspect the shader
        // corrects for is the box it is stretched into. Without this the plot keeps whatever proportions the
        // window had when it was last turned.
        if (!dirty && aspect == lastAspect) {
            return;
        }
        dirty = false;
        lastAspect = aspect;
        try (var zone = Probe.zone(Lane.GPU, "plot.march")) {
            target.renderInto(pipeline, 0, cones.descriptorSet(), 3,
                    SdfComposer.cameraBytes(eyeX(), eyeY(), eyeZ(), yaw, pitch, aspect),
                    (float) sky.r(), (float) sky.g(), (float) sky.b(), 1f);
        }
    }

    // ------------------------------------------------------------------ camera

    // An eye orbiting the origin at a fixed distance, looking back at it -- and the axis swap every renderer in
    // this stack makes: the plot's z is the world's y. Taken from StrokeMarchSmoke, which is the reference for
    // this convention, because there is no Java-side projection to read it off (framework-notes.md FN-12).

    private double eyeX() {
        return -DISTANCE * Math.cos(pitch) * Math.sin(yaw);
    }

    private double eyeY() {
        return DISTANCE * Math.sin(pitch);
    }

    private double eyeZ() {
        return -DISTANCE * Math.cos(pitch) * Math.cos(yaw);
    }

    private static ComposedShader stage(List<ComposedShader> composed, ShaderStage want) {
        return composed.stream().filter(c -> c.stage() == want).findFirst()
                .orElseThrow(() -> new IllegalStateException("ConeField.compose produced no " + want + " stage"));
    }
}
