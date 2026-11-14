package top.goldgom.aibotassist.client;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VisionImageTest {
    @Test void screenshotNativeChannelsAreConvertedToRgbWithoutRedBlueSwap() {
        assertEquals(0xFF0000,VisionImage.rgbFromNative(0xFF0000FF));
        assertEquals(0x0000FF,VisionImage.rgbFromNative(0xFFFF0000));
        assertEquals(0x123456,VisionImage.rgbFromNative(0xFF563412));
    }
    @Test void compressedScreenshotIsReadableAndPreservesOrientationAndColors() throws Exception {
        var image=new BufferedImage(200,100,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<100;y++)for(int x=0;x<200;x++)image.setRGB(x,y,y<50?0xFF0000:0x0000FF);
        var encoded=VisionImage.encode(image);byte[] bytes=Base64.getDecoder().decode(encoded.data());
        assertEquals(200,encoded.width());assertEquals(100,encoded.height());
        assertTrue(bytes.length<=VisionImage.MAX_BYTES);assertEquals(0xFF,bytes[0]&255);assertEquals(0xD8,bytes[1]&255);
        var restored=ImageIO.read(new ByteArrayInputStream(bytes));
        assertTrue(((restored.getRGB(20,20)>>16)&255)>240);assertTrue((restored.getRGB(20,80)&255)>240);
    }
    @Test void noisyHighResolutionScreenshotsStayWithinBridgeBudget() throws Exception {
        var image=new BufferedImage(1920,1080,BufferedImage.TYPE_INT_RGB);var random=new Random(123);
        int[] pixels=new int[1920*1080];for(int i=0;i<pixels.length;i++)pixels[i]=random.nextInt(0x1000000);
        image.setRGB(0,0,1920,1080,pixels,0,1920);
        var encoded=VisionImage.encode(image);
        assertTrue(encoded.width()<=960);assertTrue(encoded.height()<=540);
        assertEquals(16.0/9.0,encoded.width()/(double)encoded.height(),0.02);
        assertTrue(Base64.getDecoder().decode(encoded.data()).length<=192*1024);
        assertTrue(encoded.data().length()<=262144);
    }
    @Test void portraitAndSmallViewportsAreNotStretchedOrUpscaled() throws Exception {
        var portrait=VisionImage.encode(new BufferedImage(100,1600,BufferedImage.TYPE_INT_RGB));
        assertEquals(60,portrait.width());assertEquals(960,portrait.height());
        var tiny=VisionImage.encode(new BufferedImage(1,2,BufferedImage.TYPE_INT_RGB));
        assertEquals(1,tiny.width());assertEquals(2,tiny.height());
    }
}
