package top.goldgom.aibotassist.client;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Bounded, in-memory encoding; never writes screenshots or metadata to disk. */
public final class VisionImage {
    public static final int MAX_EDGE=960,MAX_BYTES=192*1024;
    public record Encoded(int width,int height,String data) {}
    private VisionImage() {}
    public static int rgbFromNative(int abgr){return ((abgr&255)<<16)|(abgr&0xFF00)|((abgr>>>16)&255);}
    public static Encoded encode(BufferedImage source) throws IOException {
        double scale=Math.min(1.0,MAX_EDGE/(double)Math.max(source.getWidth(),source.getHeight()));
        int width=Math.max(1,(int)(source.getWidth()*scale)),height=Math.max(1,(int)(source.getHeight()*scale));
        for(int attempt=0;attempt<5;attempt++) {
            BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
            var graphics=image.createGraphics();
            try{graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);graphics.drawImage(source,0,0,width,height,null);}
            finally{graphics.dispose();}
            byte[] bytes;
            var writers=ImageIO.getImageWritersByFormatName("jpeg");
            if(!writers.hasNext())throw new IOException("JPEG encoder unavailable");
            var writer=writers.next();
            try(var buffer=new ByteArrayOutputStream();var output=new MemoryCacheImageOutputStream(buffer)) {
                var parameters=writer.getDefaultWriteParam();parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(attempt==0?0.80f:0.60f);
                writer.setOutput(output);writer.write(null,new IIOImage(image,null,null),parameters);output.flush();bytes=buffer.toByteArray();
            }finally{writer.dispose();}
            if(bytes.length<=MAX_BYTES)return new Encoded(width,height,Base64.getEncoder().encodeToString(bytes));
            width=Math.max(1,width*3/4);height=Math.max(1,height*3/4);
        }
        throw new IOException("View image exceeds size limit");
    }
}
