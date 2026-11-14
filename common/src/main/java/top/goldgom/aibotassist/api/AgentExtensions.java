package top.goldgom.aibotassist.api;

import com.google.gson.*;
import java.util.*;
import java.util.function.*;
import net.minecraft.server.level.ServerPlayer;
import top.goldgom.aibotassist.AdvancedTools;

/** API v1. Register once during common mod initialization on both Fabric and NeoForge. */
public final class AgentExtensions {
    public static final int API_VERSION=1;
    public enum Kind { COMMAND, MACHINE }
    @FunctionalInterface public interface Handler {JsonObject execute(ServerPlayer player,JsonObject arguments)throws Exception;}
    public record Operation(String id,Kind kind,String description,JsonObject parameters,boolean mutating,
                            int permissionLevel,Predicate<ServerPlayer> canUse,String verification,Handler handler){
        public Operation {
            if(id==null||!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||id.length()>128)throw new IllegalArgumentException("Use modid:operation_id");
            Objects.requireNonNull(kind);Objects.requireNonNull(canUse);Objects.requireNonNull(handler);
            if(description==null||description.isBlank()||description.length()>4000||verification==null||verification.isBlank()||verification.length()>2000||permissionLevel<0||permissionLevel>4)throw new IllegalArgumentException("Invalid operation metadata");
            parameters=parameters.deepCopy();if(!"object".equals(parameters.get("type").getAsString())||parameters.toString().length()>16000)throw new IllegalArgumentException("Invalid operation schema");OperationSchema.check(parameters);
        }
        @Override public JsonObject parameters(){return parameters.deepCopy();}
    }
    private static final Map<String,Operation> OPERATIONS=new TreeMap<>();
    private AgentExtensions(){}
    public static synchronized void register(Operation operation){
        Objects.requireNonNull(operation);
        if(OPERATIONS.containsKey(operation.id()))throw new IllegalArgumentException("Duplicate operation: "+operation.id());
        if(OPERATIONS.size()>=256)throw new IllegalStateException("Extension operation limit reached");
        OPERATIONS.put(operation.id(),operation);
    }
    /** Builds a command from validated arguments. Execution still uses actual player permissions. */
    public static void registerCommand(String id,String description,JsonObject schema,Predicate<ServerPlayer> permission,String verification,Function<JsonObject,String> command){
        register(new Operation(id,Kind.COMMAND,description,schema,true,2,permission,verification,(player,args)->{
            JsonObject request=new JsonObject();request.addProperty("command",command.apply(args));
            return AdvancedTools.handle(player,"run_advanced_command",request);
        }));
    }
    private static synchronized Operation find(String id){var op=OPERATIONS.get(id);if(op==null)throw new IllegalArgumentException("未注册模组操作，请先query_mod_operations");return op;}
    public static boolean mutating(String id){return find(id).mutating();}
    static boolean allowed(Operation op,ServerPlayer p){try{return p.hasPermissions(op.permissionLevel())&&(!op.mutating()||!p.isSpectator())&&op.canUse().test(p);}catch(RuntimeException e){return false;}}
    public static JsonObject query(ServerPlayer player,JsonObject args){
        String filter=args.has("query")?args.get("query").getAsString():"";
        int offset=integer(args,"offset",0,0,256),limit=integer(args,"limit",10,1,20);
        List<Operation> snapshot;synchronized(AgentExtensions.class){snapshot=List.copyOf(OPERATIONS.values());}
        var visible=snapshot.stream().filter(op->op.id().contains(filter)&&allowed(op,player)).toList();var rows=new JsonArray();
        for(var op:visible.stream().skip(offset).limit(limit).toList()){
            var row=new JsonObject();row.addProperty("id",op.id());row.addProperty("kind",op.kind().name().toLowerCase(Locale.ROOT));row.addProperty("description",op.description());row.add("parameters",op.parameters());row.addProperty("mutating",op.mutating());row.addProperty("requires_observation",op.mutating());row.addProperty("permission_level",op.permissionLevel());row.addProperty("verification",op.verification());rows.add(row);
        }
        var result=new JsonObject();result.addProperty("status","completed");result.addProperty("api_version",API_VERSION);result.add("operations",rows);result.addProperty("total",visible.size());result.addProperty("next_offset",offset+rows.size());return result;
    }
    public static JsonObject execute(ServerPlayer player,String id,JsonObject args){
        if(!player.server.isSameThread())throw new IllegalStateException("Extension handlers require the server thread");
        var op=find(id);if(!allowed(op,player))return result("rejected","模组操作权限不足");
        return invoke(op,player,args);
    }
    static JsonObject invoke(Operation op,ServerPlayer player,JsonObject args){
        if(args==null||args.toString().length()>32768)throw new IllegalArgumentException("操作参数超限");
        OperationSchema.validate(op.parameters(),args);
        try{
            var response=op.handler().execute(player,args.deepCopy());
            if(response==null||response.toString().length()>65536||!response.has("status")||!Set.of("completed","rejected","unknown","partial_possible","running","submitted","observed").contains(response.get("status").getAsString()))
                return result(op.mutating()?"unknown":"rejected","模组处理器返回无效结果；先检查实际状态，不重放操作");
            return response.deepCopy();
        }catch(Exception e){
            org.slf4j.LoggerFactory.getLogger("aibotassist").warn("Extension operation failed id={} type={}",op.id(),e.getClass().getName());
            return result(op.mutating()?"unknown":"rejected","模组处理器异常，可能部分执行；先查询实际状态，禁止自动重试");
        }
    }
    private static JsonObject result(String status,String reason){var out=new JsonObject();out.addProperty("status",status);out.addProperty("reason",reason);return out;}
    private static int integer(JsonObject a,String key,int fallback,int min,int max){int value=a.has(key)?a.get(key).getAsBigDecimal().intValueExact():fallback;if(value<min||value>max)throw new IllegalArgumentException("分页参数超限");return value;}
}
