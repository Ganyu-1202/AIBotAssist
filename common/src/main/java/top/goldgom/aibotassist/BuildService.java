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
        Job(Pending pending) { this.pending = pending; }
    }
    private static final Map<UUID, Request> requests = new HashMap<>();
    private static final Map<UUID, Pending> pending = new HashMap<>();
    private static final Map<UUID, Job> jobs = new HashMap<>();
    private static final Map<UUID, Long> cooldowns = new HashMap<>();
    private static long now() { return System.currentTimeMillis(); }
    public static void clear() { requests.clear(); pending.clear(); jobs.clear(); cooldowns.clear(); }
    public static void tell(ServerPlayer p, String message) { p.sendSystemMessage(Component.literal("[AI] " + message)); }
    private static void send(ServerPlayer p, JsonObject o) { NetworkManager.sendToPlayer(p, new Wire.Down(o.toString())); }
    public static void receive(ServerPlayer p, String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String action = o.get("action").getAsString();
            if (action.equals("context")) {
                if (jobs.containsKey(p.getUUID())) { tell(p, "已有任务执行中，请先取消。"); return; }
                if (now() - cooldowns.getOrDefault(p.getUUID(), 0L) < 3000) return;
                cooldowns.put(p.getUUID(), now());
                pending.remove(p.getUUID());
                Request r = new Request(UUID.randomUUID().toString(), p.blockPosition(), p.serverLevel().dimension().location().toString(), now() + 120_000);
                requests.put(p.getUUID(), r);
                JsonObject reply = new JsonObject();
                reply.addProperty("action", "context"); reply.addProperty("id", r.id);
                reply.addProperty("mode", p.gameMode.getGameModeForPlayer().getName());
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
                send(p, reply);
            } else if (action.equals("plan")) {
                Request r = requests.remove(p.getUUID());
                if (r == null || r.expires < now() || !r.id.equals(o.get("id").getAsString())) return;
                Plan plan = Plan.parse(o.get("plan").getAsString());
                if (!plan.hasActions()) return;
                if (p.isSpectator()) { tell(p, "旁观模式只能聊天。"); return; }
                Map<BlockPos, BlockState> removalStates = new HashMap<>();
                for (var b : plan.removals()) { var pos = r.origin.offset(b.x(), b.y(), b.z()); removalStates.put(pos, p.serverLevel().getBlockState(pos)); }
                Pending task = new Pending(r, plan, now() + 60_000, Map.copyOf(removalStates));
                validate(p, task);
                pending.put(p.getUUID(), task);
                if (!plan.commands().isEmpty()) {
                    tell(p, "AI 提交 " + plan.commands().size() + " 条命令（按你的当前权限执行，不提升 OP）：");
                    for (String command : plan.commands()) tell(p, "/" + command);
                    tell(p, "命令可能改变世界或影响其他玩家，执行后不自动撤销。");
                } else tell(p, "方案包含 " + plan.blocks().size() + " 个放置、" + plan.removals().size() + " 个拆除；原点 " + r.origin.toShortString() + "。材料：" + plan.materials());
                tell(p, "60 秒内点击确认或 @AI 确认；也可 /aibot confirm。取消用 /aibot cancel。");
            }
        } catch (Exception e) { tell(p, "请求被拒绝：方案无效、命令被禁止/无权限、材料不足或目标区域不允许。"); }
    }
    private static BlockState state(Plan.Placement b) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(b.block())).defaultBlockState();
    }
    private static void location(ServerPlayer p, Pending task) {
        if (!p.isAlive() || p.isSpectator() || !p.serverLevel().dimension().location().toString().equals(task.request.dimension)
            || p.blockPosition().distSqr(task.request.origin) > 32 * 32)
            throw new IllegalStateException("玩家状态改变");
    }
    private static void target(ServerPlayer p, BlockPos pos, BlockState state) {
        var level = p.serverLevel();
        if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)
            || !level.getBlockState(pos).isAir() || !level.mayInteract(p, pos) || !p.mayBuild()
            || !level.getEntities(null, new net.minecraft.world.phys.AABB(pos)).isEmpty()
            || BlockEvent.PLACE.invoker().placeBlock(level, pos, state, p).isFalse())
            throw new IllegalStateException("区域不可建造");
    }
    private static void validate(ServerPlayer p, Pending task) {
        location(p, task);
        for (String command : task.plan.commands()) parseCommand(p, command);
        for (var entry : task.removalStates.entrySet()) removalTarget(p, entry.getKey(), entry.getValue());
        for (Plan.Placement b : task.plan.blocks()) target(p, task.request.origin.offset(b.x(), b.y(), b.z()), state(b));
        if (!p.isCreative()) {
            for (var e : task.plan.materials().entrySet()) {
                var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(e.getKey()));
                int count = 0;
                for (int i = 0; i < 36; i++) {
                    ItemStack s = p.getInventory().getItem(i);
                    if (s.is(item) && s.getComponentsPatch().isEmpty()) count += s.getCount();
                }
                if (count < e.getValue()) throw new IllegalStateException("材料不足");
            }
        }
    }
    private static void removalTarget(ServerPlayer p, BlockPos pos, BlockState expected) {
        var level = p.serverLevel();
        BlockState actual = level.getBlockState(pos);
        if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)
            || !actual.equals(expected) || actual.isAir() || (!p.isCreative() && actual.getDestroySpeed(level, pos) < 0)
            || !actual.getFluidState().isEmpty() || level.getBlockEntity(pos) != null
            || !level.mayInteract(p, pos) || !p.mayBuild()
            || BlockEvent.BREAK.invoker().breakBlock(level, pos, actual, p, null).isFalse())
            throw new IllegalArgumentException("方块不可拆除、已改变、含液体/方块实体或区域受保护");
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
    private static void executeBatch(ServerPlayer p, Pending task) throws Exception {
        boolean paid = !p.isCreative();
        // Reserve all supplies first: removal drops cannot finance this same task.
        if (paid) consume(p, task.plan.materials());
        Set<BlockPos> placed = new HashSet<>();
        try {
            for (var run : BatchCommands.placements(task.plan.blocks())) {
                // Recheck immediately before command; event handlers may have modified the world.
                for (int x=run.x();x<=run.endX();x++) {
                    var pos=task.request.origin.offset(x,run.y(),run.z());
                    target(p,pos,BuiltInRegistries.BLOCK.get(ResourceLocation.parse(run.block())).defaultBlockState());
                }
                try {
                    generatedCommand(p,run.command(task.request.origin.getX(),task.request.origin.getY(),task.request.origin.getZ()));
                } finally {
                    for (int x=run.x();x<=run.endX();x++) {
                        var pos=task.request.origin.offset(x,run.y(),run.z());
                        if (p.serverLevel().getBlockState(pos).is(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(run.block())))) placed.add(pos);
                    }
                }
            }
            if (placed.size()!=task.plan.blocks().size()) throw new IllegalStateException("部分放置失败");
            for (var entry : task.removalStates.entrySet()) {
                removalTarget(p,entry.getKey(),entry.getValue());
                BlockPos pos=entry.getKey();
                generatedCommand(p,"setblock "+pos.getX()+" "+pos.getY()+" "+pos.getZ()+" air destroy");
                if (!p.serverLevel().getBlockState(pos).isAir()) throw new IllegalStateException("拆除失败");
            }
        } finally {
            if (paid) {
                for (var b : task.plan.blocks()) {
                    if (placed.contains(task.request.origin.offset(b.x(),b.y(),b.z()))) continue;
                    ItemStack refund=new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(b.block())));
                    if (!p.getInventory().add(refund)) p.drop(refund,false);
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
            throw new IllegalArgumentException("命令不是原版命令或当前玩家无权限");
        String normalized = CommandPolicy.normalize(command);
        int space = normalized.indexOf(' ');
        String canonical = node.getName() + (space < 0 ? "" : normalized.substring(space));
        var parsed = dispatcher.parse(canonical, p.createCommandSourceStack());
        if (parsed.getReader().canRead() || !parsed.getExceptions().isEmpty()) throw new IllegalArgumentException("命令参数错误或无权限");
        return parsed;
    }
    public static void confirm(ServerPlayer p) {
        Pending t = pending.remove(p.getUUID());
        if (t == null || t.expires < now()) { tell(p, "没有可确认的方案或方案已过期。"); return; }
        try { validate(p, t); jobs.put(p.getUUID(), new Job(t)); tell(p, t.plan.commands().isEmpty() ? "即将一次性执行方块任务：整批检查材料，拆除按原版规则掉落；执行后不自动撤销。" : "开始执行命令；失败会停止后续命令，已执行部分不会自动撤销。"); }
        catch (Exception e) { tell(p, "确认失败：检查模式、库存、站位及目标区域。"); }
    }
    public static void cancel(ServerPlayer p) {
        requests.remove(p.getUUID()); pending.remove(p.getUUID()); jobs.remove(p.getUUID()); tell(p, "已取消，已建造部分保留。");
    }
    public static void tick(MinecraftServer server) {
        int budget = 32;
        var iterator = jobs.entrySet().iterator();
        while (iterator.hasNext() && budget > 0) {
            var entry = iterator.next();
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            Job j = entry.getValue();
            if (p == null) { iterator.remove(); continue; }
            try {
                location(p, j.pending);
                if (!j.pending.plan.commands().isEmpty()) {
                    var parsed = parseCommand(p, j.pending.plan.commands().get(j.index));
                    int result = server.getCommands().getDispatcher().execute(parsed);
                    budget--;
                    if (result <= 0) throw new IllegalStateException("命令报告失败");
                    j.index++;
                    tell(p, "命令执行成功：" + j.index + "/" + j.pending.plan.commands().size());
                    if (j.index == j.pending.plan.commands().size()) { tell(p, "命令任务完成。"); iterator.remove(); }
                    continue;
                }
                // Entire bounded block task is executed in one server tick, not incrementally.
                validate(p, j.pending);
                executeBatch(p, j.pending);
                budget -= Plan.MAX_BLOCKS;
                tell(p, "批量方块任务完成：放置 " + j.pending.plan.blocks().size() + "，拆除 " + j.pending.plan.removals().size() + "。");
                iterator.remove();
            } catch (Exception e) { tell(p, "任务已停止，已完成 " + j.index + " 步；命令可能部分生效，不会重试或自动撤销。未放置方块未扣料。"); iterator.remove(); }
        }
        long time = now();
        requests.values().removeIf(r -> r.expires < time);
        pending.values().removeIf(r -> r.expires < time);
        cooldowns.entrySet().removeIf(e -> time - e.getValue() > 120_000);
    }
}

