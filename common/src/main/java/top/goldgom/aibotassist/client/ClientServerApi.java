package top.goldgom.aibotassist.client;

import com.google.gson.*;
import dev.architectury.networking.NetworkManager;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import top.goldgom.aibotassist.*;

/** World-bound server policy and model RPC. Shared credentials never enter this class or Pi. */
public final class ClientServerApi {
    public record Policy(boolean known,String mode,String model,String revision,boolean canManage,boolean ready){public boolean shared(){return known&&mode.equals("shared");}}
    private record Pending(String action,CompletableFuture<JsonObject> future){}
    private static String worldId="";
    public static String worldId(){return worldId;}
    private static volatile Policy policy=new Policy(false,"independent","","",false,false);
    private static final Map<String,Pending> pending=new ConcurrentHashMap<>();
    private static final PacketChunks downloads=new PacketChunks();
    private static final UUID DOWNLOAD_ID=UUID.randomUUID();
    private ClientServerApi(){}
    public static Policy policy(){return policy;}
    public static boolean serverPresent(){return NetworkManager.canServerReceive(Wire.Up.TYPE);}
    public static boolean shared(){return serverPresent()&&policy.shared();}
    public static void reset(){worldId="";policy=new Policy(false,"independent","","",false,false);downloads.clear();pending.values().forEach(p->p.future.completeExceptionally(new InterruptedException()));pending.clear();}
    public static CompletableFuture<JsonObject> refresh(){
        if(!serverPresent())return CompletableFuture.failedFuture(new Failure("服务器未安装此模组，使用个人API配置"));
        return request("api_policy",new JsonObject());
    }
    public static boolean receive(JsonObject packet){
        String action=packet.has("action")?packet.get("action").getAsString():"";
        if(action.equals("fragment")){
            if(!packet.has("id")||!pending.containsKey(packet.get("id").getAsString()))return true;
            try{String complete=downloads.accept(DOWNLOAD_ID,packet,System.currentTimeMillis());if(complete!=null)receive(JsonParser.parseString(complete).getAsJsonObject());}
            catch(IllegalArgumentException e){pending.values().forEach(p->p.future.completeExceptionally(new Failure("服务器API响应分包无效；未自动重试")));pending.clear();}return true;
        }
        if(action.equals("api_policy")){apply(packet);return true;}
        if(!action.equals("api_result"))return false;
        Pending waiting=pending.remove(packet.get("call").getAsString());if(waiting==null)return true;
        if(!packet.get("status").getAsString().equals("completed"))waiting.future.completeExceptionally(new Failure(packet.has("reason")?packet.get("reason").getAsString():"服务器API请求失败"));
        else{
            JsonObject result=packet.getAsJsonObject("result");if(waiting.action.equals("api_policy")||waiting.action.equals("api_config"))apply(result);waiting.future.complete(result);
        }
        return true;
    }
    private static void apply(JsonObject o){
        worldId=o.has("world_id")?o.get("world_id").getAsString():"";ClientSessions.bind(worldId);
        Policy old=policy;policy=new Policy(true,o.get("mode").getAsString(),o.get("model").getAsString(),o.get("revision").getAsString(),o.get("can_manage").getAsBoolean(),o.has("ready")&&o.get("ready").getAsBoolean());
        if(old.known&&!old.revision.equals(policy.revision)&&ClientMod.isBusy())ClientMod.cancelAgent();
    }
    public static CompletableFuture<JsonObject> configure(JsonObject settings,String revision){JsonObject o=new JsonObject();o.addProperty("revision",revision);o.add("config",settings);return request("api_config",o);}
    public static JsonObject turn(String revision,JsonArray messages,JsonArray tools)throws Exception{
        if(!policy.shared()||!policy.revision.equals(revision))throw new Failure("服务器API策略已改变，请重新发送消息");
        JsonObject body=new JsonObject();body.add("messages",messages);body.add("tools",tools);JsonObject o=new JsonObject();o.addProperty("revision",revision);o.add("body",body);
        return request("api_completion",o).get(100,TimeUnit.SECONDS);
    }
    public static void cancelModels(){
        pending.entrySet().removeIf(e->{if(e.getValue().action.equals("api_completion")){e.getValue().future.completeExceptionally(new InterruptedException());return true;}return false;});
        downloads.clear();
        if(serverPresent())request("api_cancel",new JsonObject());
    }
    private static CompletableFuture<JsonObject> request(String action,JsonObject args){
        Minecraft mc=Minecraft.getInstance();var connection=mc.getConnection();var level=mc.level;String id=UUID.randomUUID().toString();
        JsonObject packet=args.deepCopy();packet.addProperty("action",action);packet.addProperty("call",id);List<String> parts;
        try{parts=PacketChunks.encode(packet.toString(),id);}catch(Exception e){return CompletableFuture.failedFuture(new Failure("服务器API消息超过传输上限"));}
        CompletableFuture<JsonObject> future=new CompletableFuture<>();pending.put(id,new Pending(action,future));
        future.orTimeout(action.equals("api_completion")?100:8,TimeUnit.SECONDS).whenComplete((v,e)->pending.remove(id));
        mc.execute(()->{
            if(future.isDone())return;
            if(mc.getConnection()!=connection||mc.level!=level||!serverPresent()){future.completeExceptionally(new InterruptedException());return;}
            try{for(String part:parts)NetworkManager.sendToServer(new Wire.Up(part));}catch(Exception e){future.completeExceptionally(new Failure("服务器API请求发送失败"));}
        });return future;
    }
    public static final class Failure extends Exception{public Failure(String reason){super(reason);}}
}
