package com.ygoleague.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageResizerTest {

    @Test
    void scalesDownToFitKeepingTheAspect() throws Exception {
        BufferedImage shrunk = ImageIO.read(new ByteArrayInputStream(ImageResizer.fitWithin(jpeg(620, 610), 200)));
        assertEquals(200, shrunk.getWidth());
        assertEquals(197, shrunk.getHeight());
    }

    @Test
    void leavesSmallImagesAndNonImagesAlone() throws Exception {
        byte[] small = jpeg(120, 120);
        assertArrayEquals(small, ImageResizer.fitWithin(small, 200));
        byte[] junk = {1, 2, 3};
        assertArrayEquals(junk, ImageResizer.fitWithin(junk, 200));
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) img.setRGB(x, y, (x * 7 + y * 13) & 0xffffff);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        return out.toByteArray();
    }
}
