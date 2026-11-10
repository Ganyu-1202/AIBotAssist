package top.goldgom.aibotassist;

import com.google.gson.*;
import java.util.*;

/** Untrusted model output; validated again on the authoritative server. */
public record Plan(String reply, List<Placement> blocks, List<String> commands, List<Removal> removals) {
    public Plan(String reply, List<Placement> blocks) { this(reply, blocks, List.of(), List.of()); }
    public Plan(String reply, List<Placement> blocks, List<String> commands) { this(reply, blocks, commands, List.of()); }
    public boolean hasActions() { return !blocks.isEmpty() || !commands.isEmpty() || !removals.isEmpty(); }
    public record Removal(int x, int y, int z) {}
    public static final int MAX_BLOCKS = 256, RADIUS = 16;
    public static final Set<String> ALLOWED = Set.of(
        "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
        "minecraft:cobblestone", "minecraft:stone", "minecraft:stone_bricks",
        "minecraft:bricks", "minecraft:glass", "minecraft:white_concrete",
        "minecraft:oak_log", "minecraft:spruce_log", "minecraft:polished_andesite",
        "minecraft:smooth_stone", "minecraft:sandstone", "minecraft:deepslate_bricks");
    public record Placement(int x, int y, int z, String block) {}
    public static Plan parse(String text) {
        if (text == null || text.length() > 100_000) throw new IllegalArgumentException("方案过长");
        JsonObject o = JsonParser.parseString(text).getAsJsonObject();
        String reply = o.get("reply").getAsString();
        if (reply.length() > 4000) throw new IllegalArgumentException("回复过长");
        JsonArray a = o.getAsJsonArray("blocks");
        if (a == null || a.size() > MAX_BLOCKS) throw new IllegalArgumentException("方块数量超限");
        List<Placement> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement e : a) {
            JsonObject b = e.getAsJsonObject();
            int x = integer(b, "x"), y = integer(b, "y"), z = integer(b, "z");
            String id = b.get("block").getAsString();
            if (Math.abs((long)x) > RADIUS || Math.abs((long)y) > RADIUS || Math.abs((long)z) > RADIUS)
                throw new IllegalArgumentException("超出建造半径");
            if (!ALLOWED.contains(id)) throw new IllegalArgumentException("不支持的方块");
            if (!seen.add(x + "," + y + "," + z)) throw new IllegalArgumentException("重复坐标");
            result.add(new Placement(x, y, z, id));
        }
        List<String> commands = new ArrayList<>();
        if (o.has("commands")) {
            JsonArray c = o.getAsJsonArray("commands");
            if (c.size() > CommandPolicy.MAX_COMMANDS) throw new IllegalArgumentException("命令数量超限");
            for (JsonElement e : c) commands.add(CommandPolicy.normalize(e.getAsString()));
        }
        List<Removal> removals = new ArrayList<>();
        if (o.has("removals")) {
            JsonArray removalsJson = o.getAsJsonArray("removals");
            if (removalsJson.size() + result.size() > MAX_BLOCKS) throw new IllegalArgumentException("放置和拆除总量超限");
            for (JsonElement e : removalsJson) {
                JsonObject b = e.getAsJsonObject();
                int x = integer(b, "x"), y = integer(b, "y"), z = integer(b, "z");
                if (Math.abs((long)x) > RADIUS || Math.abs((long)y) > RADIUS || Math.abs((long)z) > RADIUS)
                    throw new IllegalArgumentException("拆除超出半径");
                if (!seen.add(x + "," + y + "," + z)) throw new IllegalArgumentException("放置/拆除坐标重复");
                removals.add(new Removal(x, y, z));
            }
        }
        if ((!result.isEmpty() || !removals.isEmpty()) && !commands.isEmpty())
            throw new IllegalArgumentException("方块任务与一般命令请分开请求");
        return new Plan(reply, List.copyOf(result), List.copyOf(commands), List.copyOf(removals));
    }
    private static int integer(JsonObject o, String key) {
        return o.get(key).getAsBigDecimal().intValueExact();
    }
    public Map<String, Integer> materials() {
        Map<String, Integer> m = new TreeMap<>();
        blocks.forEach(b -> m.merge(b.block, 1, Integer::sum));
        return m;
    }
}
