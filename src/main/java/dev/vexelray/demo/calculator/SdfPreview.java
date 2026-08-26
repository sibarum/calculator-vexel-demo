package dev.vexelray.demo.calculator;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.shader.Shadings;
import dev.vexelray.surface.Field;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;
import dev.vexelray.vulkan.offscreen.OffscreenRenderer;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The cheap preview: an expression compiled to a true SDF shader and photographed.
 *
 * <p>Deliberately the smallest thing that exercises the whole chain — COTT term, plot {@link
 * dev.vexelray.gui.plot.Expr}, SupirVast {@code core} IR, {@code Surface}, gradient normalisation, SPIR-V,
 * a ray-march, pixels. Nothing here is embedded in the GUI, and that is the point of it being cheap.
 *
 * <p><b>Why a capture rather than a window.</b> {@code GuiWindow} builds exactly one graphics pipeline, from
 * {@code CanvasShader}, and {@code Canvas} has no call that samples an image — so a marched region inside a
 * window that also holds a legend and a status bar needs a second pipeline and a canvas that can composite it,
 * which is a framework change and not a calculator one. The offscreen path needs neither: it owns its own
 * instance and device for the length of one frame, the same way {@code GuiApp.capture} does. It also means
 * this can be checked headlessly, which the windowed Vulkan path cannot.
 *
 * <p>The camera is fixed. {@link SdfSurface} maps whatever volume the framing pass chose onto a box of a known
 * size around the origin, so one eye position frames every expression and the picture that changes between two
 * captures is the surface rather than the viewpoint.
 */
final class SdfPreview {

    /** Capture size. Square, so the fixed camera does not have to care about the window's shape. */
    private static final int W = 720;
    private static final int H = 720;

    /** Where the eye sits: back along {@code -z} and above the box, looking down at the origin. */
    private static final double EYE_Y = 2.3;
    private static final double EYE_Z = -4.4;

    /** Pitch that points the eye at the origin from there. Positive pitch looks down. */
    private static final double PITCH = Math.atan2(EYE_Y, -EYE_Z);

    /** The fullscreen triangle the composer's vertex stage draws. */
    private static final int VERTICES = 3;

    private SdfPreview() {
    }

    /**
     * Compile {@code surface} and write one marched frame to {@code path}.
     *
     * <p>Prints what the compiler concluded on the way through — the field's Lipschitz bound and the size of
     * the SPIR-V it produced. Both are worth seeing rather than assuming: the bound is the compiler saying
     * whether it had to normalise, and the module size is the one number that runs away when gradient
     * normalisation compounds through a deeply nested expression.
     */
    static void capture(SdfSurface surface, MarchStyle style, String path) throws IOException {
        SdfScene scene = SdfScene.of(surface.surface())
                .withAlbedo(new SdfScene.Rgb(0.78, 0.80, 0.86))
                .withShading(style.shading(surface.volume()));

        Field field = SdfComposer.field(scene);
        System.out.println("  field      lipschitz " + field.lipschitz()
                + (field.isMarchable() ? " (marchable)" : " (NOT marchable)"));

        List<ComposedShader> shaders = new SdfComposer().compose(scene);
        byte[] vertex = shaders.get(0).spirv();
        byte[] fragment = shaders.get(1).spirv();
        System.out.println("  shader     " + fragment.length + " bytes of fragment SPIR-V");

        SdfScene.Rgb sky = scene.sky();
        byte[] camera = SdfComposer.cameraBytes(0, EYE_Y, EYE_Z, 0, PITCH, (double) W / H);

        NativePlatform platform = NativePlatform.current();
        try (VulkanInstance instance = new VulkanInstance("calculator-sdf",
                platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics device"));
            System.out.println("  device     " + selection.deviceName());
            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection)) {
                byte[] rgba = OffscreenRenderer.render(device, W, H,
                        vertex, shaders.get(0).entryPoint(),
                        fragment, shaders.get(1).entryPoint(),
                        VERTICES, (float) sky.r(), (float) sky.g(), (float) sky.b(), 1f, camera);
                ImageIO.write(toImage(rgba), "PNG", new File(path));
            }
        }
    }

    /** Tightly-packed RGBA, row-major top-to-bottom, as an image {@code ImageIO} will write. */
    private static BufferedImage toImage(byte[] rgba) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                image.setRGB(x, y, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16)
                        | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
            }
        }
        return image;
    }
}
