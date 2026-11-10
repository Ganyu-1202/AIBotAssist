package top.goldgom.aibotassist;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

public final class WorldTools {
    private record Blueprint(BlockPos origin,String dimension,Plan plan) {}
    private static final Map<UUID,LinkedHashMap<String,Blueprint>> blueprints=new HashMap<>();
    private static final Map<UUID,LinkedHashMap<String,JsonObject>> waypoints=new HashMap<>();
    private static final Map<UUID,ArrayDeque<JsonObject>> history=new HashMap<>();
    private WorldTools() {}
    public static void clear(){blueprints.clear();waypoints.clear();history.clear();}
    public static void record(ServerPlayer p,String id,String status,String detail) {
        var e=new JsonObject();e.addProperty("task_id",id);e.addProperty("status",status);e.addProperty("detail",detail);e.addProperty("time",System.currentTimeMillis());
        var list=history.computeIfAbsent(p.getUUID(),k->new ArrayDeque<>());list.addLast(e);while(list.size()>32)list.removeFirst();
    }
    public static void blueprint(ServerPlayer p,String id,BlockPos origin,Plan plan) {
        var map=blueprints.computeIfAbsent(p.getUUID(),k->new LinkedHashMap<>());map.put(id,new Blueprint(origin,p.serverLevel().dimension().location().toString(),plan));while(map.size()>16)map.remove(map.keySet().iterator().next());
    }
    public static JsonObject status(ServerPlayer p,String id) {
        JsonObject result=new JsonObject();
        for(var event:history.getOrDefault(p.getUUID(),new ArrayDeque<>()))if(event.get("task_id").getAsString().equals(id))result=event.deepCopy();
        if(result.size()==0){result.addProperty("status","rejected");result.addProperty("reason","没有此个人任务记录");}return result;
    }
    public static Plan repair(ServerPlayer p,String id,BlockPos currentOrigin) {
        Blueprint b=blueprints.getOrDefault(p.getUUID(),new LinkedHashMap<>()).get(id);
        if(b==null||!b.dimension.equals(p.serverLevel().dimension().location().toString()))throw new IllegalArgumentException("建筑记录不存在或不在当前维度");
        List<Plan.Placement> missing=new ArrayList<>();
        for(var target:b.plan.blocks()) {
            BlockPos pos=b.origin.offset(target.x(),target.y(),target.z());if(!p.serverLevel().hasChunkAt(pos))throw new IllegalArgumentException("建筑区块未加载");
            if(SpecialPlacement.matches(target,p.serverLevel().getBlockState(pos)))continue;
            var existing=p.serverLevel().getBlockState(pos);if(!existing.isAir()&&!existing.canBeReplaced())throw new IllegalArgumentException("修复目标被其他实心方块占用，请先检查，不自动拆除后来改建");
            BlockPos relative=pos.subtract(currentOrigin);if(Math.abs(relative.getX())>16||Math.abs(relative.getY())>16||Math.abs(relative.getZ())>16)throw new IllegalArgumentException("距离建筑过远");
            if(!SpecialPlacement.isUpperOrHead(target))missing.add(new Plan.Placement(relative.getX(),relative.getY(),relative.getZ(),target.block(),target.properties(),target.nbt()));
        }
        return new Plan("差异修复",missing);
    }
    private static java.nio.file.Path waypointPath(ServerPlayer p) {return p.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("aibotassist/waypoints/"+p.getUUID()+".json");}
    private static LinkedHashMap<String,JsonObject> loadPoints(ServerPlayer p) {
        LinkedHashMap<String,JsonObject> points=new LinkedHashMap<>();
        try {var file=waypointPath(p);if(java.nio.file.Files.size(file)>20000)return points;var json=JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();for(var entry:json.entrySet()){if(points.size()>=32)break;points.put(entry.getKey(),entry.getValue().getAsJsonObject());}}catch(Exception ignored){}return points;
    }
    private static void savePoints(ServerPlayer p,Map<String,JsonObject> points) {
        try {var file=waypointPath(p);java.nio.file.Files.createDirectories(file.getParent());var tmp=file.resolveSibling(file.getFileName()+".tmp");JsonObject json=new JsonObject();points.forEach(json::add);java.nio.file.Files.writeString(tmp,json.toString());java.nio.file.Files.move(tmp,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(java.io.IOException e){throw new IllegalStateException("标记保存失败");}
    }
    public static JsonObject handle(ServerPlayer p,String tool,JsonObject args) {
        JsonObject result=new JsonObject();result.addProperty("status","completed");
        switch(tool) {
            case "inspect_area" -> {
                int radius=args.has("radius")?args.get("radius").getAsInt():3;if(radius<1||radius>4)throw new IllegalArgumentException("radius必须1–4");
                int dx=args.has("x")?args.get("x").getAsBigDecimal().intValueExact():0,dy=args.has("y")?args.get("y").getAsBigDecimal().intValueExact():0,dz=args.has("z")?args.get("z").getAsBigDecimal().intValueExact():0;
                if(Math.abs((long)dx)>12||Math.abs((long)dy)>12||Math.abs((long)dz)>12)throw new IllegalArgumentException("查询偏移超限");
                BlockPos origin=p.blockPosition().offset(dx,dy,dz);JsonArray blocks=new JsonArray();boolean truncated=false;
                for(BlockPos pos:BlockPos.betweenClosed(origin.offset(-radius,-2,-radius),origin.offset(radius,2,radius))) {
                    if(!p.serverLevel().hasChunkAt(pos))continue;var state=p.serverLevel().getBlockState(pos);if(state.isAir())continue;
                    if(blocks.size()>=160){truncated=true;continue;}
                    JsonObject b=new JsonObject();b.addProperty("x",pos.getX()-origin.getX());b.addProperty("y",pos.getY()-origin.getY());b.addProperty("z",pos.getZ()-origin.getZ());
                    b.addProperty("block",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());b.addProperty("replaceable",state.canBeReplaced());b.addProperty("breakable",p.isCreative()||state.getDestroySpeed(p.serverLevel(),pos)>=0);b.addProperty("accessible",p.serverLevel().mayInteract(p,pos));blocks.add(b);
                }result.add("blocks",blocks);result.addProperty("truncated",truncated);result.addProperty("offset_x",dx);result.addProperty("offset_y",dy);result.addProperty("offset_z",dz);
            }
            case "inspect_entities" -> {
                JsonArray entities=new JsonArray();for(var e:p.serverLevel().getEntities(p,p.getBoundingBox().inflate(12),e->e instanceof net.minecraft.world.entity.LivingEntity)) {
                    if(entities.size()>=24)break;JsonObject item=new JsonObject();item.addProperty("type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());item.addProperty("distance",p.distanceTo(e));item.addProperty("hostile",e instanceof net.minecraft.world.entity.monster.Monster);entities.add(item);
                }result.add("entities",entities);
            }
            case "query_game_rules" -> {result.addProperty("dimension",p.serverLevel().dimension().location().toString());result.addProperty("time",p.serverLevel().getDayTime());result.addProperty("raining",p.serverLevel().isRaining());result.addProperty("difficulty",p.serverLevel().getDifficulty().getKey());result.addProperty("doTileDrops",p.serverLevel().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS));result.addProperty("keepInventory",p.serverLevel().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY));}
            case "manage_waypoints" -> {
                var map=waypoints.computeIfAbsent(p.getUUID(),k->loadPoints(p));String action=args.has("action")?args.get("action").getAsString():"list";
                if(!action.equals("list")) {String name=args.get("name").getAsString();if(!name.matches("[\\p{L}\\p{N}_-]{1,32}"))throw new IllegalArgumentException("标记名称无效");
                    if(action.equals("set")){if(map.size()>=32&&!map.containsKey(name))throw new IllegalArgumentException("标记数量超限");JsonObject point=new JsonObject();point.addProperty("dimension",p.serverLevel().dimension().location().toString());point.addProperty("x",p.blockPosition().getX());point.addProperty("y",p.blockPosition().getY());point.addProperty("z",p.blockPosition().getZ());map.put(name,point);}else if(action.equals("delete"))map.remove(name);else throw new IllegalArgumentException("无效标记动作");}
                if(!action.equals("list"))savePoints(p,map);JsonObject points=new JsonObject();map.forEach(points::add);result.add("waypoints",points);result.addProperty("persistence","按存档与玩家UUID持久保存");
            }
            case "get_operation_history" -> {JsonArray events=new JsonArray();history.getOrDefault(p.getUUID(),new ArrayDeque<>()).forEach(events::add);result.add("events",events);}
            case "verify_result" -> {
                Blueprint b=blueprints.getOrDefault(p.getUUID(),new LinkedHashMap<>()).get(args.get("task_id").getAsString());if(b==null||!b.dimension.equals(p.serverLevel().dimension().location().toString()))throw new IllegalArgumentException("没有当前维度个人建筑记录");
                int mismatched=0;for(var block:b.plan.blocks()){BlockPos pos=b.origin.offset(block.x(),block.y(),block.z());if(!p.serverLevel().hasChunkAt(pos))throw new IllegalArgumentException("区块未加载");if(!SpecialPlacement.matches(block,p.serverLevel().getBlockState(pos)))mismatched++;}
                result.addProperty("expected",b.plan.blocks().size());result.addProperty("mismatched",mismatched);result.addProperty("matches",mismatched==0);
            }
            default -> throw new IllegalArgumentException("未知世界工具");
        }return result;
    }
}
