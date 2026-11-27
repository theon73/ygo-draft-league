package com.ygoleague.image;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Scales a JPEG down to fit a box, with the JDK's own ImageIO (no native libraries). Spring-free,
 * so it's unit-tested directly. Anything it can't read is returned unchanged: a too-large avatar is
 * better than none.
 */
public final class ImageResizer {

    private static final float QUALITY = 0.85f;

    private ImageResizer() {}

    /** The image scaled so neither side exceeds {@code max} px (aspect kept); unchanged if it already fits. */
    public static byte[] fitWithin(byte[] jpeg, int max) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (src == null || (src.getWidth() <= max && src.getHeight() <= max)) return jpeg;
            double scale = (double) max / Math.max(src.getWidth(), src.getHeight());
            int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(src, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            return writeJpeg(out);
        } catch (IOException | RuntimeException e) {
            return jpeg;
        }
    }

    private static byte[] writeJpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
