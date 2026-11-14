package top.goldgom.aibotassist;

import com.google.gson.*;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Dedicated/integrated server API policy and shared-provider relay, never executes client tools. */
public final class ServerApiService {
    private record Pending(ServerPlayer player,String call,String revision,CompletableFuture<JsonObject> future){}
    private static ServerApiConfig config=new ServerApiConfig();
    private static String revision=UUID.randomUUID().toString();
    private static String configError="",worldId="";
    private static final SharedApiRequests gate=new SharedApiRequests();
    private static final Map<UUID,Pending> pending=new HashMap<>();
    private ServerApiService(){}
    private static Path path(){return Platform.getConfigFolder().resolve("aibotassist-server-api.json");}
    public static boolean canManage(ServerPlayer p){return AdvancedTools.admin(p);}
    public static void start(MinecraftServer server){clear();try{worldId=WorldIdentity.load(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));}catch(Exception e){worldId=UUID.randomUUID().toString();org.slf4j.LoggerFactory.getLogger("aibotassist").error("Cannot persist save identity; chat identity is temporary for this server run");}reload(server);}
    private static JsonObject publicPolicy(boolean admin){JsonObject o=config.publicView(revision,admin);o.addProperty("world_id",worldId);return o;}
    public static boolean reload(MinecraftServer server){
        try{ServerApiConfig next=ServerApiConfig.load(path());apply(server,next);configError="";return true;}
        catch(Exception e){configError="服务器API配置无效，未应用；请管理员检查配置文件。";org.slf4j.LoggerFactory.getLogger("aibotassist").error("Server API configuration invalid/unreadable; no credentials logged");return false;}
    }
    private static void apply(MinecraftServer server,ServerApiConfig next){
        cancelAll("管理员已更改API策略，请重新发送消息");config=next;revision=UUID.randomUUID().toString();configError="";
        if(server.getPlayerList()!=null)for(ServerPlayer player:server.getPlayerList().getPlayers())sendPolicy(player);
    }
    public static void clear(){var old=new ArrayList<>(pending.values());pending.clear();gate.clear();for(Pending p:old)p.future.cancel(true);config=new ServerApiConfig();revision=UUID.randomUUID().toString();configError="";}
    public static boolean setMode(MinecraftServer server,String mode){try{var a=new JsonObject();a.addProperty("mode",mode);ServerApiConfig next=config.updated(a);next.save(path());apply(server,next);return true;}catch(Exception e){return false;}}
    public static String mode(){return config.mode;}
    public static void sendPolicy(ServerPlayer p){JsonObject policy=publicPolicy(canManage(p));policy.addProperty("action","api_policy");if(!configError.isBlank()&&canManage(p))policy.addProperty("error",configError);send(p,policy);}
    private static void send(ServerPlayer p,JsonObject packet){
        if(!NetworkManager.canPlayerReceive(p,Wire.Down.TYPE))return;
        String id=packet.has("call")?packet.get("call").getAsString():UUID.randomUUID().toString();
        List<String> parts;
        try{parts=PacketChunks.encode(packet.toString(),id);}
        catch(IllegalArgumentException tooLarge){JsonObject small=new JsonObject();small.addProperty("action","api_result");small.addProperty("call",id);small.addProperty("status","rejected");small.addProperty("reason","共享API响应超过8MiB，已停止传输；不要自动重试");parts=List.of(small.toString());}
        try{for(String part:parts)NetworkManager.sendToPlayer(p,new Wire.Down(part));}
        catch(Exception e){/* A disconnected client must not receive another player's result. */}
    }
    private static void reply(ServerPlayer p,String call,JsonObject body){JsonObject o=new JsonObject();o.addProperty("action","api_result");o.addProperty("call",call);o.addProperty("status","completed");o.add("result",body);send(p,o);}
    private static void reject(ServerPlayer p,String call,String reason){JsonObject o=new JsonObject();o.addProperty("action","api_result");o.addProperty("call",call);o.addProperty("status","rejected");o.addProperty("reason",reason);send(p,o);}
    private static void cancelAll(String reason){var old=new ArrayList<>(pending.values());pending.clear();gate.clear();for(Pending p:old){p.future.cancel(true);reject(p.player,p.call,reason);}}
    public static void cancel(ServerPlayer p){Pending old=pending.remove(p.getUUID());if(old!=null){old.future.cancel(true);gate.finish(p.getUUID(),old.call);}}
    public static void leave(ServerPlayer p){cancel(p);gate.leave(p.getUUID());}
    /** Called after packet reassembly, before normal tool receipts/history. */
    public static boolean receive(ServerPlayer player,JsonObject packet){
        String action=packet.get("action").getAsString();if(!action.startsWith("api_"))return false;
        String call=packet.has("call")?packet.get("call").getAsString():"";
        if(!call.matches("[a-zA-Z0-9-]{1,64}"))return true;
        try{
            switch(action){
                case "api_policy" -> {JsonObject o=publicPolicy(canManage(player));o.addProperty("config_error",canManage(player)?configError:"");reply(player,call,o);}
                case "api_config" -> {
                    if(!canManage(player)){reject(player,call,"只有存档所有者或服务器管理员能修改API策略");break;}
                    if(!revision.equals(packet.get("revision").getAsString())){reject(player,call,"服务器设置已改变，请刷新后保存");break;}
                    ServerApiConfig next=config.updated(packet.getAsJsonObject("config"));next.save(path());apply(player.server,next);reply(player,call,publicPolicy(true));
                }
                case "api_cancel" -> {cancel(player);reply(player,call,new JsonObject());}
                case "api_completion" -> completion(player,call,packet);
                default -> reject(player,call,"不支持的服务器API操作");
            }
        }catch(IllegalArgumentException e){reject(player,call,"API参数无效；共享模式须填写HTTPS地址、模型和API密钥");}
        catch(Exception e){reject(player,call,"服务器API配置或请求失败，请联系管理员；未输出敏感信息");}
        return true;
    }
    private static void completion(ServerPlayer player,String call,JsonObject packet){
        if(!config.shared()){reject(player,call,"服务器使用独立API模式，请在客户端配置自己的API");return;}
        if(!revision.equals(packet.get("revision").getAsString())){reject(player,call,"API策略已改变，请重新同步设置");return;}
        // Validate before admission. Only the authenticated packet sender owns the call.
        JsonObject body=SharedModelHttp.body(config,packet.getAsJsonObject("body"));
        var admission=gate.start(player.getUUID(),call,System.currentTimeMillis(),config.maxConcurrent,config.playerIntervalSeconds*1000);
        if(admission!=SharedApiRequests.Admission.ACCEPTED){reject(player,call,switch(admission){case DUPLICATE->"重复请求已阻止，不会再次扣费";case BUSY->"你已有共享API请求正在处理";case FULL->"共享API并发已满，请稍后发送";default->"共享API请求过于频繁，请稍后发送";});return;}
        CompletableFuture<JsonObject> future;
        try{future=SharedModelHttp.request(config,packet.getAsJsonObject("body"));}catch(Exception e){gate.finish(player.getUUID(),call);reject(player,call,"共享API请求无法发起，请联系管理员");return;}
        Pending work=new Pending(player,call,revision,future);pending.put(player.getUUID(),work);
        future.whenComplete((result,error)->player.server.execute(()->{
            if(pending.get(player.getUUID())!=work||!gate.active(player.getUUID(),call))return;
            pending.remove(player.getUUID());gate.finish(player.getUUID(),call);
            if(player.server.getPlayerList().getPlayer(player.getUUID())!=player||!revision.equals(work.revision))return;
            if(error==null)reply(player,call,result);
            else{Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();reject(player,call,cause instanceof SharedModelHttp.Failure?cause.getMessage():"共享API连接超时或失败，请联系管理员；未自动重试");}
        }));
    }
}
