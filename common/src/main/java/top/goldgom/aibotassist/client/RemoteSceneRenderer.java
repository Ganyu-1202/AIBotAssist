package top.goldgom.aibotassist.client;

import com.google.gson.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import net.minecraft.client.*;
import net.minecraft.client.renderer.*;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import org.joml.Matrix4f;

/** Offscreen Minecraft block models, textures and fluids from an authorized server snapshot. */
public final class RemoteSceneRenderer {
    private record Frame(BufferedImage pixels,int modeled,int fluids,int unsupported,int failures){}
    private RemoteSceneRenderer(){}
    public static JsonObject capture(JsonObject scene,BooleanSupplier valid)throws Exception{
        if(!valid.getAsBoolean())throw new InterruptedException();
        CompletableFuture<Frame> future=new CompletableFuture<>();
        Minecraft.getInstance().execute(()->{
            if(future.isDone())return;
            if(!valid.getAsBoolean()||Minecraft.getInstance().level==null){future.completeExceptionally(new InterruptedException());return;}
            try{future.complete(render(scene));}catch(Throwable e){future.completeExceptionally(e);}
        });
        try{
            Frame frame=future.get(15,TimeUnit.SECONDS);if(!valid.getAsBoolean())throw new InterruptedException();
            var encoded=VisionImage.encode(frame.pixels());if(!valid.getAsBoolean())throw new InterruptedException();
            JsonObject result=new JsonObject();result.addProperty("status","captured");result.addProperty("source","fakeplayer_region_render");
            for(String field:new String[]{"camera","camera_uuid","captured_at","dimension","min_x","min_y","min_z","side","note","block_entities"})if(scene.has(field))result.add(field,scene.get(field).deepCopy());
            result.addProperty("width",encoded.width());result.addProperty("height",encoded.height());result.addProperty("modeled_blocks",frame.modeled());result.addProperty("fluid_blocks",frame.fluids());result.addProperty("unsupported_dynamic_blocks",frame.unsupported());result.addProperty("failed_models",frame.failures());
            result.addProperty("rendered_at",System.currentTimeMillis());JsonObject image=new JsonObject();image.addProperty("mimeType","image/jpeg");image.addProperty("data",encoded.data());result.add("image",image);return result;
        }catch(TimeoutException e){return ClientVision.rejected("远程区域渲染等待超时；请恢复游戏窗口");}
        catch(ExecutionException e){if(e.getCause() instanceof InterruptedException)throw new InterruptedException();return ClientVision.rejected("远程场景渲染失败；当前资源包、模组模型或图形环境可能不兼容，未返回图片");}
        finally{future.cancel(false);}
    }
    private static PoseStack cameraPose(JsonObject camera){
        PoseStack pose=new PoseStack();pose.mulPose(Axis.XP.rotationDegrees(camera.get("pitch").getAsFloat()));pose.mulPose(Axis.YP.rotationDegrees(camera.get("yaw").getAsFloat()+180));return pose;
    }
    private static Frame render(JsonObject scene){
        RenderSystem.assertOnRenderThread();Minecraft mc=Minecraft.getInstance();RemoteSceneView world=new RemoteSceneView(scene);JsonObject camera=scene.getAsJsonObject("camera");
        double eyeX=camera.get("x").getAsDouble(),eyeY=camera.get("y").getAsDouble(),eyeZ=camera.get("z").getAsDouble();
        Matrix4f projection=new Matrix4f(RenderSystem.getProjectionMatrix());VertexSorting sorting=RenderSystem.getVertexSorting();var shader=RenderSystem.getShader();
        float fogStart=RenderSystem.getShaderFogStart(),fogEnd=RenderSystem.getShaderFogEnd();float[] fog=RenderSystem.getShaderFogColor().clone(),color=RenderSystem.getShaderColor().clone();int[] textures=new int[12];for(int i=0;i<12;i++)textures[i]=RenderSystem.getShaderTexture(i);
        var modelView=RenderSystem.getModelViewStack();modelView.pushMatrix();modelView.identity();RenderSystem.applyModelViewMatrix();
        RenderTarget target=null;LinkedHashMap<RenderType,ByteBufferBuilder> allocated=new LinkedHashMap<>();ByteBufferBuilder fallback=null;
        try{
            target=new TextureTarget(640,480,true,Minecraft.ON_OSX);target.setClearColor(0.42f,0.64f,0.82f,1);target.clear(Minecraft.ON_OSX);target.bindWrite(true);
            RenderSystem.setProjectionMatrix(new Matrix4f().perspective((float)Math.toRadians(camera.get("fov").getAsDouble()),640f/480f,0.05f,160),VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setShaderColor(1,1,1,1);RenderSystem.setShaderFogStart(1000);RenderSystem.setShaderFogEnd(2000);RenderSystem.enableDepthTest();mc.gameRenderer.lightTexture().turnOnLightLayer();
            for(RenderType layer:RenderType.chunkBufferLayers())allocated.put(layer,new ByteBufferBuilder(262144));fallback=new ByteBufferBuilder(262144);
            MultiBufferSource.BufferSource buffers=MultiBufferSource.immediateWithBuffers(allocated,fallback);RandomSource random=RandomSource.create(0);int modeled=0,fluids=0,unsupported=0,failures=0;
            for(int y=0;y<world.side;y++)for(int z=0;z<world.side;z++)for(int x=0;x<world.side;x++){
                BlockPos pos=new BlockPos(world.minX+x,world.minY+y,world.minZ+z);var state=world.getBlockState(pos);if(state.isAir())continue;
                try{
                    if(state.getRenderShape()==RenderShape.MODEL){
                        PoseStack pose=cameraPose(camera);pose.translate(pos.getX()-eyeX,pos.getY()-eyeY,pos.getZ()-eyeZ);
                        mc.getBlockRenderer().renderBatched(state,pos,world,pose,buffers.getBuffer(ItemBlockRenderTypes.getChunkRenderType(state)),true,random);modeled++;
                    }else if(state.getRenderShape()==RenderShape.ENTITYBLOCK_ANIMATED)unsupported++;
                    var fluid=state.getFluidState();if(!fluid.isEmpty()){
                        PoseStack pose=cameraPose(camera);pose.translate((pos.getX()&~15)-eyeX,(pos.getY()&~15)-eyeY,(pos.getZ()&~15)-eyeZ);
                        mc.getBlockRenderer().renderLiquid(pos,world,new Transformed(buffers.getBuffer(ItemBlockRenderTypes.getRenderLayer(fluid)),pose.last()),state,fluid);fluids++;
                    }
                }catch(Exception incompatible){failures++;}
            }
            buffers.endBatch();
            BufferedImage pixels=new BufferedImage(640,480,BufferedImage.TYPE_INT_RGB);
            try(NativeImage image=Screenshot.takeScreenshot(target)){for(int y=0;y<480;y++)for(int x=0;x<640;x++)pixels.setRGB(x,y,VisionImage.rgbFromNative(image.getPixelRGBA(x,y)));}
            return new Frame(pixels,modeled,fluids,unsupported,failures);
        }finally{
            for(var buffer:allocated.values())buffer.close();if(fallback!=null)fallback.close();if(target!=null)target.destroyBuffers();
            modelView.popMatrix();RenderSystem.applyModelViewMatrix();RenderSystem.setProjectionMatrix(projection,sorting);
            RenderSystem.setShaderFogStart(fogStart);RenderSystem.setShaderFogEnd(fogEnd);RenderSystem.setShaderFogColor(fog[0],fog[1],fog[2],fog[3]);RenderSystem.setShaderColor(color[0],color[1],color[2],color[3]);
            mc.gameRenderer.lightTexture().turnOffLightLayer();for(int i=0;i<12;i++)RenderSystem.setShaderTexture(i,textures[i]);RenderSystem.setShader(()->shader);RenderSystem.disableDepthTest();mc.getMainRenderTarget().bindWrite(true);
        }
    }
    private record Transformed(VertexConsumer target,PoseStack.Pose pose) implements VertexConsumer {
        public VertexConsumer addVertex(float x,float y,float z){target.addVertex(pose,x,y,z);return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){target.setColor(r,g,b,a);return this;}
        public VertexConsumer setUv(float u,float v){target.setUv(u,v);return this;}
        public VertexConsumer setUv1(int u,int v){target.setUv1(u,v);return this;}
        public VertexConsumer setUv2(int u,int v){target.setUv2(u,v);return this;}
        public VertexConsumer setNormal(float x,float y,float z){target.setNormal(pose,x,y,z);return this;}
    }
}
