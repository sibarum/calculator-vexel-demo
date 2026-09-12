package dev.vexelray.demo.calculator;

import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.surface.ParamBlock;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.StorageBuffer;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * The plot's march as a {@link RenderTechnique}: a {@link dev.vexelray.technique.sdf.ConeField} module reading
 * geometry from a storage buffer, with the camera and the scene's parameters as one push-constant block.
 *
 * <p>This is what {@link March} used to do inline against {@code SampledColorTarget.renderInto}, and the whole
 * of what moved is where the four commands live. A technique binds its pipeline, binds the cone buffer, pushes
 * its block and draws three vertices; the pass around it belongs to whoever is driving — here a
 * {@code SampledTechniqueHost} over one of the plot's two targets, and in principle a window's swapchain
 * instead, with no change to this file.
 *
 * <h2>One of these per pass, not one per plot</h2>
 *
 * <p>A technique owns a pipeline and a pipeline is built against one render pass at one size, which is exactly
 * why {@link March} already had a {@code Pass} pair rather than a single pipeline. So the draft and the still
 * get a technique each, and what they <em>share</em> is everything expensive: the composed module (a few
 * kilobytes of SPIR-V, composed once per colour) and the {@link StorageBuffer} of cones (the geometry, updated
 * by the worker). Neither is owned here — {@link #close()} takes the pipeline and the command handles and
 * leaves the buffer to the thing that minted it.
 *
 * <h2>Why the module can change without a retarget</h2>
 *
 * <p>{@code ConeField} bakes the albedo in, so a colour change is new SPIR-V and a new pipeline. That is not a
 * target rebuild — the render pass and the extent are exactly as they were — so it is not
 * {@code SampledTechniqueHost.retarget}'s business and it would be wrong to realise again for it. A technique
 * owns its pipeline, so rebuilding one is its own affair: {@link #module} takes the new stages and builds
 * against the render pass this was realised on. The cost is the reason it is affordable at all — the module is
 * a fixed size whatever the buffer holds, which is {@code ConeField}'s entire point.
 *
 * <h2>Dynamic viewport</h2>
 *
 * <p>Built with dynamic viewport and scissor, as the SPI asks, so the extent is the frame's rather than the
 * pipeline's. On this path the extent only changes when a target is re-minted — which realises again anyway —
 * but building it the way every other technique is built is what keeps this one movable to a path where the
 * extent really does change under it.
 */
final class ConeFieldTechnique implements RenderTechnique {

    /** The geometry, shared with the other pass and owned by neither. */
    private final StorageBuffer cones;

    /** The composed module, and the scene it carries the lens and parameter layout of. */
    private ComposedShader vertex;
    private ComposedShader fragment;
    private SdfScene scene;
    private ParamBlock params;

    /** This frame's push block, built in {@link #camera} and copied into the command buffer in {@link #record}. */
    private byte[] push;

    // Realise-time state, kept so that a module change can rebuild without a second realise.
    private VulkanDevice device;
    private long renderPass;
    private int width;
    private int height;
    private boolean hasDepth;

    private GraphicsPipeline pipeline;
    private DrawCommands cmds;
    private MemorySegment pushSeg;
    private int pushCapacity;

    ConeFieldTechnique(StorageBuffer cones, ComposedShader vertex, ComposedShader fragment,
                       SdfScene scene, ParamBlock params) {
        this.cones = cones;
        this.vertex = vertex;
        this.fragment = fragment;
        this.scene = scene;
        this.params = params;
    }

    /**
     * Where the eye is and what shape the box is — the per-frame data, through this technique's own API rather
     * than as bytes the caller had to lay out (D5).
     *
     * <p>The block is the camera, then the lens, then one float per scene parameter, and {@link SdfComposer}
     * is what knows that. Built here rather than in {@link #record} because record is inside a begun render
     * pass and this is CPU work that could have happened before it.
     */
    void camera(double x, double y, double z, double yaw, double pitch, double aspect) {
        this.push = SdfComposer.pushConstantBytes(scene, x, y, z, yaw, pitch, aspect, params);
    }

    /**
     * Swap in a newly composed module — a colour change, which is new SPIR-V because the albedo is compiled in.
     *
     * <p>Rebuilds the pipeline immediately when this technique has already been realised, so the cost lands
     * here, on the frame that asked for the colour, rather than inside the next {@code record}. Before realise
     * it only stores, and the first realise builds from it.
     */
    void module(ComposedShader vertex, ComposedShader fragment, SdfScene scene, ParamBlock params) {
        this.vertex = vertex;
        this.fragment = fragment;
        this.scene = scene;
        this.params = params;
        if (device != null) {
            rebuild();
        }
    }

    @Override
    public void realize(TechniqueContext ctx) {
        this.device = ((VulkanTechniqueContext) ctx).device();
        this.renderPass = ctx.renderPass();
        this.width = ctx.width();
        this.height = ctx.height();
        this.hasDepth = ctx.hasDepth();
        this.cmds = new DrawCommands(device);
        rebuild();
    }

    @Override
    public void record(FrameContext frame) {
        byte[] block = push;
        if (pipeline == null || block == null) {
            // No camera yet: the picture has not been aimed, so there is nothing honest to draw. The clear the
            // host already issued is the right frame for that, rather than a march from an undefined eye.
            return;
        }
        MemorySegment cmd = frame.commandBuffer();
        cmds.bindPipeline(cmd, pipeline);
        cmds.bindDescriptorSet(cmd, pipeline, 0, cones.descriptorSet());
        ensurePush(block.length);
        MemorySegment.copy(block, 0, pushSeg, JAVA_BYTE, 0, block.length);
        cmds.pushFragment(cmd, pipeline, pushSeg, block.length);
        // Three vertices and no vertex buffer: the fullscreen triangle synthesises its positions from
        // gl_VertexIndex, which is what every march in this stack draws.
        cmds.draw(cmd, 3);
    }

    @Override
    public void close() {
        // The pipeline first, then the command handles; the cone buffer belongs to March and outlives this.
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
        if (cmds != null) {
            cmds.close();
            cmds = null;
        }
        pushSeg = null;
        pushCapacity = 0;
        device = null;
    }

    /**
     * Build the pipeline for the module in hand, against the pass and extent realise was given.
     *
     * <p>The old one is closed rather than leaked, which is the one place this differs from what {@link March}
     * did before: it deliberately kept superseded pipelines alive because a frame might still be in flight
     * against one and there was no fence on that side to wait on. There is one here —
     * {@code SampledColorTarget.renderInto} waits on its own submission before returning, and this runs between
     * frames on the same thread — so by the time a colour change is being applied, nothing is reading the
     * pipeline it replaces.
     */
    private void rebuild() {
        GraphicsPipeline old = pipeline;
        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                0, List.of(), new long[]{cones.descriptorSetLayout()}, false,
                Vk.SHADER_STAGE_FRAGMENT_BIT, SdfComposer.pushBytes(scene), true)
                .withDepth(hasDepth
                        ? GraphicsPipeline.Config.Depth.TEST_AND_WRITE
                        : GraphicsPipeline.Config.Depth.NONE);
        pipeline = new GraphicsPipeline(device, renderPass, width, height,
                vertex.spirv(), vertex.entryPoint(), fragment.spirv(), fragment.entryPoint(), config);
        if (old != null) {
            old.close();
        }
    }

    private void ensurePush(int bytes) {
        if (pushSeg == null || bytes > pushCapacity) {
            pushSeg = cmds.allocatePushConstants(bytes);
            pushCapacity = bytes;
        }
    }
}
