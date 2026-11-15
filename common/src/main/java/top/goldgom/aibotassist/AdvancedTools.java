package top.goldgom.aibotassist;

import com.google.gson.*;
import dev.architectury.platform.Platform;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;

/** Read-only generic mod introspection and permission-preserving command collaboration. */
public final class AdvancedTools {
    public static final Set<String> NAMES=Set.of("query_environment","query_registry","inspect_machine","command_help","run_advanced_command","world_snapshot","read_server_diagnostics");
    private AdvancedTools() {}
    public static boolean owner(ServerPlayer p){return p.server.isSingleplayer()&&p.server.getSingleplayerProfile()!=null&&p.getUUID().equals(p.server.getSingleplayerProfile().getId());}
    public static boolean admin(ServerPlayer p){return owner(p)||p.hasPermissions(2);}
    public static JsonObject ok(){JsonObject o=new JsonObject();o.addProperty("status","completed");return o;}
    public static JsonObject error(String message){JsonObject o=new JsonObject();o.addProperty("status","rejected");o.addProperty("reason",message);return o;}
    public static JsonObject handle(ServerPlayer p,String tool,JsonObject a) throws Exception {
        return switch(tool){
            case "query_environment" -> environment(p);
            case "query_registry" -> registry(a);
            case "inspect_machine" -> machine(p,a);
            case "world_snapshot" -> {if(!admin(p))yield error("存档读取需要单人存档所有者或服务器OP权限");yield WorldSnapshots.handle(p,a);}
            case "read_server_diagnostics" -> {if(!admin(p))yield error("服务器日志需要管理员权限");yield DiagnosticFiles.read(p.server.getServerDirectory(),a);}
            case "command_help" -> commands(p,a,false);
            case "run_advanced_command" -> commands(p,a,true);
            default -> error("unknown_tool");
        };
    }
    private static JsonObject environment(ServerPlayer p){
        JsonObject o=ok();o.addProperty("snapshot_access",admin(p));o.addProperty("advanced_commands",p.hasPermissions(2));o.addProperty("creative",p.isCreative());
        o.addProperty("command_policy","实际玩家权限；不自动提升OP。单人未开启作弊时先在游戏中开启作弊。");
        JsonArray mods=new JsonArray();Platform.getMods().stream().sorted(Comparator.comparing(m->m.getModId())).limit(200).forEach(m->{JsonObject v=new JsonObject();v.addProperty("id",m.getModId());v.addProperty("version",m.getVersion());mods.add(v);});o.add("mods",mods);o.addProperty("mods_truncated",Platform.getMods().size()>200);
        JsonArray types=new JsonArray();BuiltInRegistries.RECIPE_TYPE.keySet().stream().sorted().forEach(id->types.add(id.toString()));o.add("recipe_types",types);
        o.addProperty("machine_support","通用方块状态、红石、电路库存、配方；模组能量/流体/多方块规则需实际NBT或JEI证据，不能臆测。");return o;
    }
    private static JsonObject registry(JsonObject a){
        String kind=a.has("kind")?a.get("kind").getAsString():"blocks",query=a.has("query")?a.get("query").getAsString():"";
        int offset=ResourceTools.integer(a,"offset",0,0,Integer.MAX_VALUE),limit=ResourceTools.integer(a,"limit",20,1,40);
        var registry=switch(kind){case "blocks"->BuiltInRegistries.BLOCK;case "items"->BuiltInRegistries.ITEM;default->throw new IllegalArgumentException();};
        var keys=registry.keySet().stream().filter(id->id.toString().contains(query)).sorted().toList();JsonArray rows=new JsonArray();
        for(var id:keys.stream().skip(offset).limit(limit).toList()){
            JsonObject row=new JsonObject();row.addProperty("id",id.toString());
            if(kind.equals("blocks")){JsonObject properties=new JsonObject();BuiltInRegistries.BLOCK.get(id).getStateDefinition().getProperties().forEach(prop->{JsonArray vals=new JsonArray();prop.getPossibleValues().forEach(v->vals.add(v.toString().toLowerCase(Locale.ROOT)));properties.add(prop.getName(),vals);});row.add("properties",properties);}
            rows.add(row);
        }
        JsonObject o=ok();o.add("entries",rows);o.addProperty("total",keys.size());o.addProperty("next_offset",offset+rows.size());return o;
    }
    private static JsonObject machine(ServerPlayer p,JsonObject a){
        BlockPos pos=new BlockPos(ResourceTools.integer(a,"x",0,-30000000,30000000),ResourceTools.integer(a,"y",0,-2048,2048),ResourceTools.integer(a,"z",0,-30000000,30000000));
        if(!p.serverLevel().hasChunkAt(pos))return error("chunk_not_loaded_use_world_snapshot");
        if(!admin(p)&&pos.distSqr(p.blockPosition())>32*32)return error("outside_player_inspection_range");
        var level=p.serverLevel();var state=level.getBlockState(pos);JsonObject o=ok();o.addProperty("position",pos.toShortString());o.addProperty("block",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());o.addProperty("state",state.toString());
        JsonObject signals=new JsonObject();JsonArray neighbors=new JsonArray();
        for(Direction d:Direction.values()){BlockPos n=pos.relative(d);if(!level.hasChunkAt(n))continue;signals.addProperty(d.getName(),level.getSignal(n,d));JsonObject v=new JsonObject();v.addProperty("side",d.getName());v.addProperty("state",level.getBlockState(n).toString());neighbors.add(v);}
        o.add("incoming_signals",signals);o.add("neighbors",neighbors);o.addProperty("powered",level.hasNeighborSignal(pos));
        var be=level.getBlockEntity(pos);
        if(be!=null){o.addProperty("block_entity_type",BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());
            if(admin(p)){String nbt=be.saveWithFullMetadata(p.registryAccess()).toString();o.addProperty("nbt",DiagnosticFiles.redact(nbt.substring(0,Math.min(10000,nbt.length()))));o.addProperty("nbt_truncated",nbt.length()>10000);
                if(be instanceof Container c){JsonArray slots=new JsonArray();for(int i=0;i<Math.min(c.getContainerSize(),54);i++){var item=c.getItem(i);if(item.isEmpty())continue;JsonObject s=new JsonObject();s.addProperty("slot",i);s.addProperty("item",BuiltInRegistries.ITEM.getKey(item.getItem()).toString());s.addProperty("count",item.getCount());slots.add(s);}o.add("inventory",slots);}}
            else o.addProperty("private_data","容器/NBT需要存档所有者或OP权限");
        }
        o.addProperty("note","单次状态快照，不能证明时钟或生产吞吐；需隔一段时间复查。模组自定义能量/流体不保证存于NBT。");return o;
    }
    private static JsonObject commands(ServerPlayer p,JsonObject a,boolean execute) throws Exception {
        var dispatcher=p.server.getCommands().getDispatcher();var source=p.createCommandSourceStack();
        if(!execute){JsonObject o=ok();JsonArray entries=new JsonArray();String root=a.has("root")?a.get("root").getAsString():"";
            if(root.isBlank())dispatcher.getRoot().getChildren().stream().filter(n->n.canUse(source)).sorted(Comparator.comparing(n->n.getName())).limit(200).forEach(n->entries.add(n.getName()));
            else {var node=dispatcher.getRoot().getChild(root);if(node!=null&&node.canUse(source))for(String usage:dispatcher.getAllUsage(node,source,true))if(entries.size()<80)entries.add(root+" "+usage);}
            o.add("commands",entries);return o;
        }
        if(!p.hasPermissions(2))return error("需要实际玩家OP/作弊权限，创造模式不会自动提升权限");
        String command=a.get("command").getAsString().strip();if(command.startsWith("/"))command=command.substring(1);
        if(command.isBlank()||command.length()>4096||command.indexOf('\n')>=0||command.indexOf('\r')>=0)return error("invalid_command_length");
        JsonArray feedback=new JsonArray();CommandSource sink=new CommandSource(){public void sendSystemMessage(Component c){if(feedback.size()<16)feedback.add(DiagnosticFiles.redact(c.getString().substring(0,Math.min(500,c.getString().length()))));}public boolean acceptsSuccess(){return true;}public boolean acceptsFailure(){return true;}public boolean shouldInformAdmins(){return false;}};
        // Never elevate. Even nested execute and mod commands use the caller's actual source permissions.
        var parsed=dispatcher.parse(command,source.withSource(sink));if(parsed.getReader().canRead())return error("命令解析失败，先调用command_help检查语法/权限");
        JsonObject o=ok();int[] completed={0},failed={0};
        var executionSource=source.withSource(sink).withCallback((success,result)->{if(success)completed[0]++;else failed[0]++;});
        p.server.getCommands().performPrefixedCommand(executionSource,command);
        o.addProperty("status",failed[0]>0?"partial_possible":completed[0]>0?"completed":"submitted");o.addProperty("success_callbacks",completed[0]);o.addProperty("failure_callbacks",failed[0]);o.add("feedback",feedback);o.addProperty("note","指令按玩家原有权限执行；模组或延迟命令需另行观察验证，不支持撤销。");return o;
    }
}
