// Not part of the build. Run by hand when the mark changes:
//   java src/main/native/MakeIcon.java src/main/native/out
// then copy icon-*.png to src/main/resources/dev/vexelray/demo/calculator/ and calculator.ico here.
// It is here rather than in a build plugin because it runs once a redesign, and a build step that
// rasterises identical PNGs on every compile is a slower build for no decision anybody makes.

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** One-off: rasterise the a-curves mark to PNGs and assemble a Windows .ico. */
public class MakeIcon {

    // vexelray-icons/svg/curves.svg, viewBox 0 0 96 96, colour #2fc4d6.
    static final String[] PATHS = {
        "M48 82 C38 62 25 50 22 36 C21 28 28 20 33 28 C38 36 40 54 50 70 Z",
        "M48 82 C58 60 73 46 77 28 C79 19 70 8 64 18 C58 28 56 52 46 70 Z"
    };
    static final Color HUE = new Color(0x2f, 0xc4, 0xd6);
    static final int[] SIZES = {16, 32, 48, 256};

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        List<byte[]> dibs = new ArrayList<>();
        for (int n : SIZES) {
            BufferedImage img = render(n);
            ImageIO.write(img, "PNG", out.resolve("icon-" + n + ".png").toFile());
            dibs.add(dib(img));
        }
        Files.write(out.resolve("calculator.ico"), ico(SIZES, dibs));
        System.out.println("wrote " + out);
    }

    static BufferedImage render(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(n / 96.0, n / 96.0);
        g.setColor(HUE);
        for (String d : PATHS) g.fill(parse(d));
        g.dispose();
        return img;
    }

    /** Absolute M / C / Z only — all this mark uses. */
    static Path2D parse(String d) {
        Path2D.Double p = new Path2D.Double();
        StringTokenizer t = new StringTokenizer(d.replaceAll("([MCZmcz])", " $1 ").replace(",", " "));
        List<String> tk = new ArrayList<>();
        while (t.hasMoreTokens()) tk.add(t.nextToken());
        for (int i = 0; i < tk.size(); ) {
            switch (tk.get(i)) {
                case "M" -> { p.moveTo(num(tk, i + 1), num(tk, i + 2)); i += 3; }
                case "C" -> {
                    p.curveTo(num(tk, i + 1), num(tk, i + 2), num(tk, i + 3),
                              num(tk, i + 4), num(tk, i + 5), num(tk, i + 6));
                    i += 7;
                }
                case "Z" -> { p.closePath(); i += 1; }
                default -> throw new IllegalArgumentException("unsupported command: " + tk.get(i));
            }
        }
        return p;
    }

    static double num(List<String> tk, int i) { return Double.parseDouble(tk.get(i)); }

    /** 32bpp bottom-up BGRA DIB plus an all-zero AND mask, as an .ico entry wants it. */
    static byte[] dib(BufferedImage img) throws IOException {
        int w = img.getWidth(), h = img.getHeight();
        int maskStride = ((w + 31) / 32) * 4;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        le32(o, 40); le32(o, w); le32(o, h * 2);           // height counts XOR + AND
        le16(o, 1); le16(o, 32); le32(o, 0);               // planes, bpp, BI_RGB
        le32(o, w * h * 4 + maskStride * h);
        le32(o, 0); le32(o, 0); le32(o, 0); le32(o, 0);
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                o.write(argb & 0xff); o.write((argb >> 8) & 0xff);
                o.write((argb >> 16) & 0xff); o.write((argb >>> 24) & 0xff);
            }
        }
        o.write(new byte[maskStride * h]);
        return b.toByteArray();
    }

    static byte[] ico(int[] sizes, List<byte[]> dibs) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        le16(o, 0); le16(o, 1); le16(o, sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int n = sizes[i];
            o.write(n == 256 ? 0 : n); o.write(n == 256 ? 0 : n);
            o.write(0); o.write(0);
            le16(o, 1); le16(o, 32);
            le32(o, dibs.get(i).length); le32(o, offset);
            offset += dibs.get(i).length;
        }
        for (byte[] d : dibs) o.write(d);
        return b.toByteArray();
    }

    static void le16(DataOutputStream o, int v) throws IOException { o.write(v & 0xff); o.write((v >> 8) & 0xff); }
    static void le32(DataOutputStream o, int v) throws IOException { le16(o, v); le16(o, v >>> 16); }
}
