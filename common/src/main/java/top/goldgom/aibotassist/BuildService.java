package top.goldgom.aibotassist;

import com.google.gson.*;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.networking.NetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

public final class BuildService {
    private record Request(String id, BlockPos origin, String dimension, long expires) {}
    private record Pending(Request request, Plan plan, long expires, Map<BlockPos, BlockState> removalStates) {}
    private static final class Job {
        final Pending pending;
        int index;
        int placedCount,removedCount;
        boolean blocksDone;
        Job(Pending pending) { this.pending = pending; }
    }
    private record ProcessingWait(UUID player,String call,String task,long deadline) {}
    private static final List<ProcessingWait> processingWaits=new ArrayList<>();
    private static final Map<UUID,LinkedHashMap<String,JsonObject>> receipts=new HashMap<>();
    private static final Map<UUID,Set<String>> inflight=new HashMap<>();
    private static final Map<UUID,String> agentCalls = new HashMap<>();
    private static final Map<UUID,String> observations = new HashMap<>();
    private static final Map<UUID, Request> requests = new HashMap<>();
    private static final Map<UUID, Job> jobs = new HashMap<>();
    private static final Map<UUID, Long> cooldowns = new HashMap<>();
    private static final class Rejected extends RuntimeException {
        Rejected(String reason) { super(reason); }
    }
    private static long now() { return System.currentTimeMillis(); }
    public static void clear() { requests.clear(); jobs.clear(); cooldowns.clear(); agentCalls.clear(); observations.clear(); WorldTools.clear(); ProcessingService.clear(); receipts.clear();inflight.clear();processingWaits.clear(); }
    public static void tell(ServerPlayer p, String message) { p.sendSystemMessage(Component.literal("[AI] " + message)); }
    private static void send(ServerPlayer p, JsonObject o) {
        if(o.toString().length()>31000) {
            JsonObject bounded=new JsonObject();bounded.addProperty("action","agent_result");
            if(o.has("call"))bounded.add("call",o.get("call"));bounded.addProperty("status","partial_possible");
            bounded.addProperty("reason","工具响应超出网络上限，操作可能已完成；缩小查询范围或查询任务状态，不要盲目重试");o=bounded;
        }
        NetworkManager.sendToPlayer(p, new Wire.Down(o.toString()));
    }
    public static void receive(ServerPlayer p, String json) {
        String stage = "读取请求";
        String incomingCall=null;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String action = o.get("action").getAsString();
            if(o.has("call")) {
                incomingCall=o.get("call").getAsString();
                if(!incomingCall.matches("[a-zA-Z0-9-]{1,64}"))throw new IllegalArgumentException("invalid call id");
                var old=receipts.getOrDefault(p.getUUID(),new LinkedHashMap<>()).get(incomingCall);
                if(old!=null){send(p,old);return;}
                if(!inflight.computeIfAbsent(p.getUUID(),k->new HashSet<>()).add(incomingCall))return;
            }
            boolean preview=false;
            if(action.equals("agent_tool")) {
                String tool=o.get("tool").getAsString(),call=o.get("call").getAsString();
                JsonObject args=o.getAsJsonObject("args");
                if(!AgentToolCatalog.NAMES.contains(tool))throw new Rejected("工具未注册");
                stage="校验工具参数："+tool;
                try{AgentToolCatalog.validateArgs(tool,args);}catch(IllegalArgumentException invalid){agentReply(p,call,"rejected",invalid.getMessage());return;}
                if(tool.equals("preview_task")||tool.equals("build_structure")||tool.equals("repair_structure")) {
                    Request observed=requests.get(p.getUUID());
                    if(observed==null||!observed.id.equals(args.get("token").getAsString())){agentReply(p,call,"rejected","观察token无效");return;}
                    Plan plan;
                    if(tool.equals("build_structure"))plan=Structures.create(args.get("kind").getAsString(),args.get("width").getAsInt(),args.get("depth").getAsInt(),args.get("height").getAsInt(),AgentToolCatalog.fallbackMaterial(args),structurePalette(args),args.has("facing")?args.get("facing").getAsString():p.getDirection().getName(),0,0,0);
                    else if(tool.equals("repair_structure")) {
                        try{plan=WorldTools.repair(p,args.get("task_id").getAsString(),observed.origin);}
                        catch(IllegalArgumentException failure){agentReply(p,call,"rejected",failure.getMessage());return;}
                    }
                    else plan=Plan.parse(args.get("plan").toString());
                    o.addProperty("id",observed.id);o.addProperty("plan",AIBotAssist.JSON.toJson(plan));
                    preview=tool.equals("preview_task");action="agent_execute";
                } else {
                    JsonObject result;
                    try {
                        result=switch(tool) {
                            case "inspect_inventory","query_recipes","plan_resources","find_workstations","craft_items" -> ResourceTools.handle(p,tool,args);
                            case "process_items" -> ProcessingService.handle(p,tool,args);
                            case "get_task_status","cancel_task" -> {
                                String id=args.get("task_id").getAsString();
                                if(id.equals(agentCalls.get(p.getUUID()))) {
                                    if(tool.equals("cancel_task")){cancel(p);JsonObject done=new JsonObject();done.addProperty("status","cancelled");yield done;}
                                    JsonObject running=new JsonObject();running.addProperty("status","running");running.addProperty("task_id",id);yield running;
                                }
                                JsonObject processed=ProcessingService.handle(p,tool,args);
                                if(processed.has("status")&&processed.get("status").getAsString().equals("rejected")) {
                                    JsonObject historic=WorldTools.status(p,id);if(!historic.get("status").getAsString().equals("rejected"))yield historic;
                                }
                                yield processed;
                            }
                            default -> WorldTools.handle(p,tool,args);
                        };
                    }catch(Exception failure){result=new JsonObject();result.addProperty("status","rejected");result.addProperty("reason","参数、材料、设备或权限不满足，请检查查询结果");}
                    if(tool.equals("get_task_status")&&args.has("wait_ticks")&&result.has("status")&&Set.of("running","waiting_owner").contains(result.get("status").getAsString())) {
                        int ticks=args.get("wait_ticks").getAsBigDecimal().intValueExact();
                        if(ticks<0||ticks>1200)throw new Rejected("wait_ticks必须0到1200");
                        if(ticks>0){processingWaits.add(new ProcessingWait(p.getUUID(),call,args.get("task_id").getAsString(),p.server.getTickCount()+ticks));return;}
                    }
                    if(!result.has("status"))result.addProperty("status",result.has("ok")&&!result.get("ok").getAsBoolean()?"rejected":"completed");
                    WorldTools.record(p,call,result.get("status").getAsString(),tool);
                    result.addProperty("action","agent_result");result.addProperty("call",call);recordReceipt(p,call,result);send(p,result);return;
                }
            }
            if (action.equals("agent_observe")) {
                if (jobs.containsKey(p.getUUID())) { agentReply(p,o.get("call").getAsString(),"rejected","已有任务执行中");return; }
                observations.put(p.getUUID(),o.get("call").getAsString());
                action="context";
            } else if (action.equals("agent_execute")) {
                String call=o.get("call").getAsString();
                if(agentCalls.containsKey(p.getUUID()) || jobs.containsKey(p.getUUID())) { agentReply(p,call,"rejected","已有任务执行中");return; }
                agentCalls.put(p.getUUID(),call);
                action="plan";
            }
            if (action.equals("context")) {
                if (jobs.containsKey(p.getUUID())) { tell(p, "已有任务执行中，请先取消。"); return; }
                if (!observations.containsKey(p.getUUID()) && now() - cooldowns.getOrDefault(p.getUUID(), 0L) < 3000) return;
                cooldowns.put(p.getUUID(), now());
                Request r = new Request(UUID.randomUUID().toString(), p.blockPosition(), p.serverLevel().dimension().location().toString(), now() + 120_000);
                requests.put(p.getUUID(), r);
                JsonObject reply = new JsonObject();
                reply.addProperty("action", "context"); reply.addProperty("id", r.id);
                reply.addProperty("mode", p.gameMode.getGameModeForPlayer().getName());
                reply.add("blockPolicy",AIBotAssist.JSON.toJsonTree(BlockPolicy.snapshot().modes.get(p.gameMode.getGameModeForPlayer().getName())));
                reply.addProperty("commandPermission", p.hasPermissions(2) ? "operator" : "normal_player");
                reply.addProperty("deniedCommands", CommandPolicy.DENIED.toString());
                reply.addProperty("creativeOnlyCommands", CommandPolicy.CREATIVE_ONLY.toString());
                JsonObject inventory = new JsonObject();
                Map<String, Integer> counts = new TreeMap<>();
                for (int i = 0; i < 36; i++) {
                    ItemStack s = p.getInventory().getItem(i);
                    if (!s.isEmpty() && s.getComponentsPatch().isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
                }
                counts.forEach(inventory::addProperty); reply.add("inventory", inventory);
                String observation=observations.remove(p.getUUID());
                if(observation!=null) {
                    reply.addProperty("action","agent_result");reply.addProperty("call",observation);reply.addProperty("status","observed");
                    reply.addProperty("token",r.id);reply.addProperty("yaw",p.getYRot());reply.addProperty("facing",p.getDirection().getName());
                    JsonArray terrain=new JsonArray();
                    // 9x5x9 non-air sample. No block entity contents, NBT or private inventories.
                    for(int x=-4;x<=4;x++)for(int y=-2;y<=2;y++)for(int z=-4;z<=4;z++) {
                        BlockPos pos=r.origin.offset(x,y,z);if(!p.serverLevel().hasChunkAt(pos))continue;
                        var block=p.serverLevel().getBlockState(pos);if(block.isAir() || terrain.size()>=192)continue;
                        JsonObject b=new JsonObject();b.addProperty("x",x);b.addProperty("y",y);b.addProperty("z",z);
                        b.addProperty("block",BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());
                        b.addProperty("replaceable",block.canBeReplaced());b.addProperty("unbreakable",block.getDestroySpeed(p.serverLevel(),pos)<0);terrain.add(b);
                    }
                    reply.add("terrain",terrain);reply.addProperty("terrainTruncated",terrain.size()>=192);
                }
                if(observation!=null)recordReceipt(p,observation,reply);
                send(p, reply);
            } else if (action.equals("plan")) {
                if (jobs.containsKey(p.getUUID())) { tell(p, "已有任务执行中，请先取消。"); return; }
                Request r = preview?requests.get(p.getUUID()):requests.remove(p.getUUID());
                if (r == null || r.expires < now() || !r.id.equals(o.get("id").getAsString())) { finishAgent(p,"rejected","观察token无效或过期，请重新观察");return; }
                stage = "解析建造方案";
                Plan plan = Plan.parse(o.get("plan").getAsString());
                plan = new Plan(plan.reply(),SpecialPlacement.expand(plan.blocks()),plan.commands(),plan.removals());
                if (!plan.hasActions()) {finishAgent(p,"completed","空任务，无世界修改");return;}
                if (p.isSpectator()) { tell(p, "旁观模式只能聊天。");finishAgent(p,"rejected","旁观模式只能聊天"); return; }
                Map<BlockPos, BlockState> removalStates = new HashMap<>();
                for (var b : plan.removals()) { var pos = r.origin.offset(b.x(), b.y(), b.z()); removalStates.put(pos, p.serverLevel().getBlockState(pos)); }
                // Resolve physical obstruction locally, without another paid model call.
                for (var b : plan.blocks()) {
                    var pos = r.origin.offset(b.x(), b.y(), b.z());
                    var existing = p.serverLevel().getBlockState(pos);
                    if (!existing.isAir() && !existing.canBeReplaced()) removalStates.putIfAbsent(pos, existing);
                }
                if (removalStates.size() + plan.blocks().size() > Plan.MAX_BLOCKS)
                    throw new Rejected("自动清障与放置合计超过256步，请缩小任务范围");
                Pending task = new Pending(r, plan, now() + 60_000, Map.copyOf(removalStates));
                stage = "校验游戏状态、区域、工具和材料";
                validate(p, task);
                if(preview){finishAgent(p,"completed","预检通过，尚未执行、未扣材料");return;}
                WorldTools.blueprint(p,agentCalls.getOrDefault(p.getUUID(),r.id),r.origin,plan);
                jobs.put(p.getUUID(), new Job(task));
                if (!plan.commands().isEmpty()) {
                    tell(p, "AI 提交 " + plan.commands().size() + " 条命令（按你的当前权限执行，不提升 OP）：");
                    for (String command : plan.commands()) tell(p, "/" + command);
                    tell(p, "命令可能改变世界或影响其他玩家，执行后不自动撤销。");
                } else tell(p, "方案包含 " + plan.blocks().size() + " 个放置、" + task.removalStates.size() + " 个拆除/清障；原点 " + r.origin.toShortString() + "。材料：" + plan.materials());
                tell(p, "检查通过，任务将自动执行。取消排队/后续操作可用 /aibot cancel；已执行部分不会自动撤销。");
            }
        } catch (Rejected e) { tell(p, "请求被拒绝：" + e.getMessage());finishAgent(p,"rejected",e.getMessage());if(incomingCall!=null&&!receipts.getOrDefault(p.getUUID(),new LinkedHashMap<>()).containsKey(incomingCall))agentReply(p,incomingCall,"rejected",e.getMessage()); }
        catch (Exception e) {
            String code = UUID.randomUUID().toString().substring(0,8);
            org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger("aibotassist");
            logger.error("AI request failed [{}] stage={} exception={}", code, stage, e.getClass().getName());
            for (StackTraceElement frame : e.getStackTrace()) logger.error("AI diagnostic [{}] at {}", code, frame);
            finishAgent(p,"rejected","处理失败："+stage+"，诊断编号"+code);
            if(incomingCall!=null&&!receipts.getOrDefault(p.getUUID(),new LinkedHashMap<>()).containsKey(incomingCall))agentReply(p,incomingCall,"rejected","参数或状态错误，诊断编号"+code);
            tell(p, "请求在“" + stage + "”阶段失败，诊断编号 " + code + "；请查看服务端日志。未执行操作。");
        }
    }
    private static Map<String,String> structurePalette(JsonObject args) {
        Map<String,String> palette=new HashMap<>();
        if(args.has("palette"))for(var entry:args.getAsJsonObject("palette").entrySet())palette.put(entry.getKey(),entry.getValue().getAsString());
        return palette;
    }
    private static void recordReceipt(ServerPlayer p,String call,JsonObject result) {
        inflight.computeIfAbsent(p.getUUID(),k->new HashSet<>()).remove(call);
        var cache=receipts.computeIfAbsent(p.getUUID(),k->new LinkedHashMap<>());cache.put(call,result.deepCopy());
        while(cache.size()>64)cache.remove(cache.keySet().iterator().next());
    }
    private static void agentReply(ServerPlayer p,String call,String status,String reason) {
        JsonObject reply=new JsonObject();reply.addProperty("action","agent_result");reply.addProperty("call",call);
        reply.addProperty("status",status);reply.addProperty("reason",reason);reply.addProperty("task_id",call);recordReceipt(p,call,reply);send(p,reply);
    }
    private static void finishAgent(ServerPlayer p,String status,String reason) {
        String call=agentCalls.remove(p.getUUID());if(call!=null){WorldTools.record(p,call,status,reason);agentReply(p,call,status,reason);}
    }
    private static BlockState state(Plan.Placement b) {
        try {return SpecialPlacement.state(b);}catch(IllegalArgumentException e){throw new Rejected("方块ID或状态无效："+b.block());}
    }
    private static void location(ServerPlayer p, Pending task) {
        if (!p.isAlive() || p.isSpectator() || !p.serverLevel().dimension().location().toString().equals(task.request.dimension)
            || p.blockPosition().distSqr(task.request.origin) > 32 * 32)
            throw new IllegalStateException("玩家状态改变");
    }
    private static void target(ServerPlayer p, BlockPos pos, BlockState state) { target(p, pos, state, false); }
    private static void target(ServerPlayer p, BlockPos pos, BlockState state, boolean clearing) {
        var level = p.serverLevel();
        try{BlockPolicy.check(p,BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),false);}catch(IllegalArgumentException e){throw new Rejected(e.getMessage());}
        String at = "（" + pos.toShortString() + "）";
        if (level.isOutsideBuildHeight(pos)) throw new Rejected("目标超出建造高度" + at);
        if (!level.hasChunkAt(pos)) throw new Rejected("目标区块尚未加载" + at);
        if (!level.getWorldBorder().isWithinBounds(pos)) throw new Rejected("目标超出世界边界" + at);
        BlockState existing = level.getBlockState(pos);
        if (!clearing && !existing.isAir() && (!existing.canBeReplaced() || !existing.getFluidState().isEmpty() || level.getBlockEntity(pos) != null))
            throw new Rejected("目标被 " + BuiltInRegistries.BLOCK.getKey(existing.getBlock()) + " 占用" + at + "；请指定空地或先要求拆除");
        if (!level.mayInteract(p, pos)) throw new Rejected("出生点或区域保护不允许操作" + at);
        if (!p.mayBuild()) throw new Rejected("当前游戏模式不允许建造" + at);
        var shape = state.getCollisionShape(level, pos);
        if (!shape.isEmpty() && !level.getEntities(p, shape.bounds().move(pos), e -> e instanceof net.minecraft.world.entity.LivingEntity && !e.isSpectator()).isEmpty())
            throw new Rejected("目标位置有玩家或生物" + at);
        if (!shape.isEmpty() && shape.bounds().move(pos).intersects(p.getBoundingBox()))
            throw new Rejected("目标位置与你重叠" + at + "；请移开后重试");
        if (BlockEvent.PLACE.invoker().placeBlock(level, pos, state, p).isFalse())
            throw new Rejected("区域保护模组拒绝放置" + at);
    }
    private static void validate(ServerPlayer p, Pending task) {
        location(p, task);
        for (String command : task.plan.commands()) parseCommand(p, command);
        for (var entry : task.removalStates.entrySet()) removalTarget(p, entry.getKey(), entry.getValue());
        for (Plan.Placement b : task.plan.blocks()) {
            SpecialPlacement.blockArgument(b,p.isCreative());
            var pos = task.request.origin.offset(b.x(), b.y(), b.z());
            target(p, pos, state(b), task.removalStates.containsKey(pos));
        }
        tools(p, task.removalStates);
        if (!p.isCreative()) prepareResources(p, task);
    }
    private record CraftPreparation(AutoCrafting.Result result, BlockPos tablePlacement) {}
    private static CraftPreparation prepareResources(ServerPlayer p, Pending task) {
        try {
            Map<String,Integer> goals = new TreeMap<>(SpecialPlacement.costs(task.plan.blocks()));
            AutoCrafting.Result result = AutoCrafting.prepare(p, goals);
            if (!result.needsTable() || AutoCrafting.findTable(p) != null) return new CraftPreparation(result, null);
            // A crafting table itself is a 2x2 recipe; reserve it separately from build supplies.
            goals.merge("minecraft:crafting_table", 1, Integer::sum);
            result = AutoCrafting.prepare(p, goals);
            for (BlockPos candidate : BlockPos.betweenClosed(p.blockPosition().offset(-3,-1,-3),p.blockPosition().offset(3,1,3))) {
                BlockPos pos = candidate.immutable();
                if (task.removalStates.containsKey(pos) || task.plan.blocks().stream().anyMatch(b -> task.request.origin.offset(b.x(),b.y(),b.z()).equals(pos))) continue;
                if (!p.serverLevel().getBlockState(pos.below()).isFaceSturdy(p.serverLevel(), pos.below(), net.minecraft.core.Direction.UP)) continue;
                try {
                    target(p,pos,net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState());
                    return new CraftPreparation(result,pos);
                } catch (Rejected rejected) { /* Try another nearby, protected-area-safe location. */ }
            }
            throw new Rejected("需要3x3合成；附近没有可用合成台，也没有合法位置放置新合成台");
        } catch (IllegalArgumentException e) {
            throw new Rejected("自动合成失败：材料不足、配方不受支持或需要熔炼/酿造加工；请先准备原料或成品");
        }
    }
    private static void removalTarget(ServerPlayer p, BlockPos pos, BlockState expected) {
        var level = p.serverLevel();
        BlockState actual = level.getBlockState(pos);
        try{BlockPolicy.check(p,BuiltInRegistries.BLOCK.getKey(actual.getBlock()).toString(),true);}catch(IllegalArgumentException e){throw new Rejected(e.getMessage());}
        if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)
            || !actual.equals(expected) || actual.isAir() || (!p.isCreative() && actual.getDestroySpeed(level, pos) < 0)
            || !actual.getFluidState().isEmpty() || level.getBlockEntity(pos) != null
            || !level.mayInteract(p, pos) || !p.mayBuild()
            || BlockEvent.BREAK.invoker().breakBlock(level, pos, actual, p, null).isFalse())
            throw new Rejected("无法拆除 " + BuiltInRegistries.BLOCK.getKey(actual.getBlock()) + "（" + pos.toShortString() + "）：目标已改变、不可挖掘、含液体/方块实体或区域受保护");
    }
    /** Conservative one durability point per block. Never break a tool or assume Unbreaking savings. */
    private static Map<BlockPos, Integer> tools(ServerPlayer p, Map<BlockPos, BlockState> removals) {
        Map<BlockPos, Integer> selected = new LinkedHashMap<>();
        if (p.isCreative()) return selected;
        Map<Integer, Integer> reserved = new HashMap<>();
        for (var entry : removals.entrySet()) {
            var state = entry.getValue();
            // Dirt, wood etc. can be broken by hand if vanilla does not require a tool for drops.
            if (!state.requiresCorrectToolForDrops()) continue;
            int chosen = -1;
            for (int slot=0;slot<36;slot++) {
                var stack=p.getInventory().getItem(slot);
                if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)
                    && (!stack.isDamageableItem() || stack.getMaxDamage()-stack.getDamageValue()-reserved.getOrDefault(slot,0)>1)) {
                    chosen=slot; break;
                }
            }
            if (chosen<0) throw new Rejected("没有合适或剩余耐久足够的工具拆除 " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "（" + entry.getKey().toShortString() + "）");
            selected.put(entry.getKey(),chosen);
            reserved.merge(chosen,1,Integer::sum);
        }
        return selected;
    }
    private static void consume(ServerPlayer p, Map<String, Integer> materials) {
        for (var entry : materials.entrySet()) {
            int remaining = entry.getValue();
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.getKey()));
            for (int slot=0;slot<36 && remaining>0;slot++) {
                var stack=p.getInventory().getItem(slot);
                if (stack.is(item) && stack.getComponentsPatch().isEmpty()) {
                    int take=Math.min(remaining,stack.getCount()); stack.shrink(take); remaining-=take;
                }
            }
            if (remaining != 0) throw new IllegalStateException("材料在预检后发生变化");
        }
        p.getInventory().setChanged();
    }
    private static void executeBatch(ServerPlayer p, Job job) throws Exception {
        Pending task=job.pending;
        boolean paid = !p.isCreative();
        // Plan completely on a copied inventory before committing any crafting effects.
        if (paid) {
            CraftPreparation crafting = prepareResources(p,task);
            AutoCrafting.commit(p,crafting.result());
            if (crafting.tablePlacement()!=null) {
                BlockPos pos=crafting.tablePlacement();
                target(p,pos,net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState());
                generatedCommand(p,"setblock "+pos.getX()+" "+pos.getY()+" "+pos.getZ()+" crafting_table replace");
                consume(p,Map.of("minecraft:crafting_table",1));
                tell(p,"已用库存材料制作并放置合成台："+pos.toShortString());
            }
            if (crafting.result().crafts()>0) tell(p,"已按原版配方完成 " + crafting.result().crafts() + " 次合成（2x2无需合成台）。");
            consume(p, SpecialPlacement.costs(task.plan.blocks()));
        }
        Set<BlockPos> placed = new HashSet<>();
        try {
            var toolSlots=tools(p,task.removalStates);
            for (var entry : task.removalStates.entrySet()) {
                removalTarget(p,entry.getKey(),entry.getValue());
                BlockPos pos=entry.getKey();
                Integer slot=toolSlots.get(pos);
                ItemStack tool=slot==null ? ItemStack.EMPTY : p.getInventory().getItem(slot);
                // Charge conservatively before the command: uncertain partial effects must not grant free wear.
                if (!tool.isEmpty() && tool.isDamageableItem()) tool.setDamageValue(tool.getDamageValue()+1);
                generatedCommand(p,"setblock "+pos.getX()+" "+pos.getY()+" "+pos.getZ()+" air destroy");
                if (!p.serverLevel().getBlockState(pos).isAir()) throw new Rejected("清障失败，已停止后续建造");
                job.removedCount++;
            }
            // Exact state/NBT support: a bounded batch in the same server tick, each command generated locally.
            // Defer neighbor notifications until doors/beds have both parts, avoiding orphan drops/refunds.
            for (var block:task.plan.blocks()) {
                BlockPos pos=task.request.origin.offset(block.x(),block.y(),block.z());
                target(p,pos,state(block));
                String argument=SpecialPlacement.blockArgument(block,p.isCreative());
                var parsed=net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(p.serverLevel().holderLookup(net.minecraft.core.registries.Registries.BLOCK),argument,true);
                var input=new net.minecraft.commands.arguments.blocks.BlockInput(parsed.blockState(),parsed.properties().keySet(),parsed.nbt());
                if(!input.place(p.serverLevel(),pos,2))throw new Rejected("方块放置失败："+pos.toShortString());
                placed.add(pos);job.placedCount++;
            }
            for(var block:task.plan.blocks()) {
                BlockPos pos=task.request.origin.offset(block.x(),block.y(),block.z());
                p.serverLevel().updateNeighborsAt(pos,state(block).getBlock());
            }
            for(var block:task.plan.blocks()) {
                BlockPos pos=task.request.origin.offset(block.x(),block.y(),block.z());
                if(!SpecialPlacement.matches(block,p.serverLevel().getBlockState(pos)))throw new Rejected("方块因支撑或邻居规则发生变化："+pos.toShortString()+"；可能部分完成，不重复退款");
            }
            if (placed.size()!=task.plan.blocks().size()) throw new IllegalStateException("部分放置失败");

        } finally {
            if (paid) {
                Map<String,Integer> paidCosts=SpecialPlacement.costs(task.plan.blocks());
                // A paired block counts as one item only once. Never refund a surviving half.
                List<Plan.Placement> successful=task.plan.blocks().stream().filter(b->placed.contains(task.request.origin.offset(b.x(),b.y(),b.z()))).toList();
                Map<String,Integer> spent=SpecialPlacement.costs(successful);
                for(var entry:paidCosts.entrySet()) {
                    int refund=entry.getValue()-spent.getOrDefault(entry.getKey(),0);
                    if(refund>0){ItemStack stack=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(entry.getKey())),refund);if(!p.getInventory().add(stack))p.drop(stack,false);}
                    if((entry.getKey().equals("minecraft:water_bucket")||entry.getKey().equals("minecraft:lava_bucket"))) {
                        int used=spent.getOrDefault(entry.getKey(),0);if(used>0){ItemStack buckets=new ItemStack(net.minecraft.world.item.Items.BUCKET,used);if(!p.getInventory().add(buckets))p.drop(buckets,false);}
                    }
                }
                p.getInventory().setChanged();
            }
        }
    }
    private static void generatedCommand(ServerPlayer p, String command) throws Exception {
        // Only this internal path receives permission 2, and only generated setblock/fill strings.
        // Model-provided general commands NEVER use this source.
        var dispatcher=p.server.getCommands().getDispatcher();
        String root=command.substring(0,command.indexOf(' '));
        String name=dispatcher.getRoot().getChild("minecraft:"+root)!=null ? "minecraft:"+root : root;
        int result=dispatcher.execute(name+command.substring(root.length()),p.createCommandSourceStack().withPermission(2).withSuppressedOutput());
        if(result<=0) throw new IllegalStateException("批量指令执行失败");
    }
    private static com.mojang.brigadier.ParseResults<net.minecraft.commands.CommandSourceStack> parseCommand(ServerPlayer p, String command) {
        CommandPolicy.checkMode(command, p.isCreative());
        var dispatcher = p.server.getCommands().getDispatcher();
        String root = CommandPolicy.root(command);
        // Only a known vanilla namespaced registration; no mod aliases, redirects or wrapper commands.
        var node = dispatcher.getRoot().getChild("minecraft:" + root);
        if (node == null) node = dispatcher.getRoot().getChild(root);
        if (node == null || node.getRedirect() != null || !node.canUse(p.createCommandSourceStack()))
            throw new Rejected("命令不是受支持的原版命令，或玩家没有执行权限：" + root);
        String normalized = CommandPolicy.normalize(command);
        int space = normalized.indexOf(' ');
        String canonical = node.getName() + (space < 0 ? "" : normalized.substring(space));
        var parsed = dispatcher.parse(canonical, p.createCommandSourceStack());
        if (parsed.getReader().canRead() || !parsed.getExceptions().isEmpty()) throw new Rejected("命令参数错误或玩家没有权限：" + root);
        return parsed;
    }
    public static void cancel(ServerPlayer p) {
        requests.remove(p.getUUID()); jobs.remove(p.getUUID()); observations.remove(p.getUUID()); finishAgent(p,"partial_possible","玩家取消，已执行部分可能保留"); tell(p, "已取消，已建造部分保留。");
    }
    public static void tick(MinecraftServer server) {
        ProcessingService.tick(server);
        var waits=processingWaits.iterator();
        while(waits.hasNext()) {
            var wait=waits.next();var player=server.getPlayerList().getPlayer(wait.player());
            if(player==null){waits.remove();continue;}
            JsonObject args=new JsonObject();args.addProperty("task_id",wait.task());
            JsonObject result=ProcessingService.handle(player,"get_task_status",args);
            String status=result.has("status")?result.get("status").getAsString():"rejected";
            if(!Set.of("running","waiting_owner").contains(status)||server.getTickCount()>=wait.deadline()) {
                result.addProperty("action","agent_result");result.addProperty("call",wait.call());recordReceipt(player,wait.call(),result);send(player,result);waits.remove();
            }
        }
        int budget = 32;
        var iterator = jobs.entrySet().iterator();
        while (iterator.hasNext() && budget > 0) {
            var entry = iterator.next();
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            Job j = entry.getValue();
            if (p == null) { agentCalls.remove(entry.getKey()); iterator.remove(); continue; }
            try {
                location(p, j.pending);
                if (!j.blocksDone) {
                    validate(p, j.pending);
                    executeBatch(p, j);
                    j.blocksDone=true;
                    if (!j.pending.plan.blocks().isEmpty() || !j.pending.removalStates.isEmpty()) {
                        budget -= Plan.MAX_BLOCKS;
                        tell(p,"清障/建造阶段完成：放置 " + j.pending.plan.blocks().size() + "，拆除 " + j.pending.removalStates.size());
                    }
                }
                if (j.index < j.pending.plan.commands().size()) {
                    var parsed = parseCommand(p, j.pending.plan.commands().get(j.index));
                    int result = server.getCommands().getDispatcher().execute(parsed);
                    budget--;
                    if (result <= 0) throw new IllegalStateException("命令报告失败");
                    j.index++;
                    tell(p, "命令执行成功：" + j.index + "/" + j.pending.plan.commands().size());
                }
                if (j.index == j.pending.plan.commands().size()) { tell(p, "任务完成。");finishAgent(p,"completed","服务器确认任务阶段完成"); iterator.remove(); }
            } catch (Exception e) { finishAgent(p,"partial_possible",e instanceof Rejected?e.getMessage():"执行异常，可能部分生效；先观察再决定后续操作"); if (e instanceof Rejected) tell(p,e.getMessage()); tell(p, "任务已停止：已放置 " + j.placedCount + " 格、拆除 " + j.removedCount + " 格、完成命令 " + j.index + " 条。已执行部分保留，不自动重试或撤销。"); iterator.remove(); }
        }
        long time = now();
        requests.values().removeIf(r -> r.expires < time);
        cooldowns.entrySet().removeIf(e -> time - e.getValue() > 120_000);
    }
}

