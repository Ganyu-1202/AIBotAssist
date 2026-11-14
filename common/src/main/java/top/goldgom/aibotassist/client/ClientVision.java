package top.goldgom.aibotassist.client;

import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;

/** Captures only the Minecraft framebuffer, before menus and the agent panel are drawn. */
public final class ClientVision {
    public static final String TOOL="capture_view", MAP_TOOL="capture_map",REMOTE_TOOL="capture_fakeplayer";
    public static boolean isLocalCapture(String tool){return tool.equals(TOOL)||tool.equals(MAP_TOOL);}
    public static boolean isCapture(String tool){return isLocalCapture(tool)||tool.equals(REMOTE_TOOL);}
    private record Frame(BufferedImage image,JsonObject metadata) {}
    private record Request(BooleanSupplier valid,CompletableFuture<Frame> result,boolean map) {}
    private static final AtomicReference<Request> pending=new AtomicReference<>();
    private ClientVision() {}
    public static JsonArray tools(boolean server) {
        JsonArray tools=server?MinecraftAgent.tools():new JsonArray();
        JsonObject tool=new JsonObject();tool.addProperty("type","function");
        JsonObject function=new JsonObject();function.addProperty("name",TOOL);
        function.addProperty("description","截取玩家当前游戏视野的真实图片（含原版HUD、不含菜单及AI对话面板），供支持图片的模型识别可见建筑/地形/物体。不会转动视角，不扫描遮挡后或视野外区域。仅视觉参考，不提供执行token，精确操作仍须observe_world。每轮最多3次；图片只在内存处理并发送当前模型服务。");
        function.add("parameters",JsonParser.parseString("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));
        tool.add("function",function);tools.add(tool);
        JsonObject mapTool=tool.deepCopy();mapTool.getAsJsonObject("function").addProperty("name",MAP_TOOL);mapTool.getAsJsonObject("function").addProperty("description","截取当前已打开的Xaero World Map界面真实图片（含可见地形及标记）。需要玩家先打开地图，工具不切换界面或解码缓存；与capture_view共用每轮3次预算。图片发送当前模型服务，仅支持图片模型可读。");tools.add(mapTool);ClientIntegrations.append(tools);return tools;
    }
    public static JsonObject capture(JsonObject args,BooleanSupplier valid) throws InterruptedException {
        return capture(args,valid,false);
    }
    public static JsonObject capture(JsonObject args,BooleanSupplier valid,boolean map) throws InterruptedException {
        if(args==null || !args.isEmpty())return rejected("capture_view does not accept arguments");
        if(!valid.getAsBoolean())throw new InterruptedException();
        Request request=new Request(valid,new CompletableFuture<>(),map);
        if(!pending.compareAndSet(null,request))return rejected("A view capture is already pending");
        try {
            Frame frame=request.result().get(5,TimeUnit.SECONDS);
            if(!valid.getAsBoolean())throw new InterruptedException();
            var encoded=VisionImage.encode(frame.image());
            if(!valid.getAsBoolean())throw new InterruptedException();
            JsonObject result=frame.metadata();result.addProperty("status","captured");
            result.addProperty("width",encoded.width());result.addProperty("height",encoded.height());
            JsonObject image=new JsonObject();image.addProperty("mimeType","image/jpeg");image.addProperty("data",encoded.data());result.add("image",image);
            return result;
        } catch(TimeoutException e){return rejected(map?"请先打开并保持Xaero World Map界面可见；5秒内未获取地图画面":"No rendered game frame within 5 seconds; restore the game window and retry if needed");}
        catch(ExecutionException | java.io.IOException e){return rejected("Game view capture failed; no image available");}
        finally{pending.compareAndSet(request,null);request.result().cancel(false);}
    }
    public static void cancel() {
        Request request=pending.getAndSet(null);
        if(request!=null)request.result().completeExceptionally(new InterruptedException());
    }
    /** Called on the render thread after the vanilla HUD, before screens and our own HUD. */
    public static void onHudRender(net.minecraft.client.gui.GuiGraphics graphics) {
        render(graphics,false);
    }
    public static void onScreenRender(net.minecraft.client.gui.screens.Screen screen,net.minecraft.client.gui.GuiGraphics graphics){
        if(screen.getClass().getName().equals("xaero.map.gui.GuiMap"))render(graphics,true);
    }
    private static void render(net.minecraft.client.gui.GuiGraphics graphics,boolean map){
        Request request=pending.get();if(request==null||request.map()!=map||!pending.compareAndSet(request,null))return;
        Minecraft mc=Minecraft.getInstance();
        if(!request.valid().getAsBoolean() || request.result().isDone() || mc.player==null || mc.level==null){request.result().completeExceptionally(new InterruptedException());return;}
        graphics.flush();
        try(NativeImage full=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            double scale=Math.min(1.0,VisionImage.MAX_EDGE/(double)Math.max(full.getWidth(),full.getHeight()));
            int width=Math.max(1,(int)(full.getWidth()*scale)),height=Math.max(1,(int)(full.getHeight()*scale));
            BufferedImage pixels=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
            try(NativeImage small=new NativeImage(width,height,false)) {
                full.resizeSubRectTo(0,0,full.getWidth(),full.getHeight(),small);
                for(int y=0;y<height;y++)for(int x=0;x<width;x++)pixels.setRGB(x,y,VisionImage.rgbFromNative(small.getPixelRGBA(x,y)));
            }
            JsonObject metadata=new JsonObject();metadata.addProperty("captured_at",System.currentTimeMillis());
            metadata.addProperty("perspective",mc.options.getCameraType().name());
            var camera=mc.gameRenderer.getMainCamera();var position=camera.getPosition();
            JsonObject view=new JsonObject();view.addProperty("x",position.x);view.addProperty("y",position.y);view.addProperty("z",position.z);
            view.addProperty("yaw",camera.getYRot());view.addProperty("pitch",camera.getXRot());metadata.add("camera",view);
            metadata.addProperty("dimension",mc.level.dimension().location().toString());
            metadata.addProperty("source",map?"xaero_world_map_screen":"player_view");
            metadata.addProperty("note",map?"Visible Xaero map screen only. Camera metadata is player camera, NOT map center/scale. Map may show another dimension; read visible labels and query waypoints. No hidden cache access.":"Current rendered view with vanilla HUD, before menus/AI overlays. Visual reference only; not proof of hidden blocks, exact coordinates, or task completion. Re-observe for action tokens. Requires an image-capable model.");
            if(mc.hitResult instanceof BlockHitResult hit && hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK)
                metadata.addProperty("crosshair_block",BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(hit.getBlockPos()).getBlock()).toString());
            request.result().complete(new Frame(pixels,metadata));
        } catch(Exception e){request.result().completeExceptionally(e);}
    }
    public static JsonObject rejected(String reason){JsonObject result=new JsonObject();result.addProperty("status","rejected");result.addProperty("reason",reason);return result;}
}
