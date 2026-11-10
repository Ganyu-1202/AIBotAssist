package top.goldgom.aibotassist;

import java.util.*;

/** Deterministic checks supplement (never trust) the model's judgment. */
public final class CommandPolicy {
    public static final int MAX_COMMANDS = 8, MAX_LENGTH = 256;
    public static final Set<String> DENIED = Set.of(
        "kill", "gamemode", "defaultgamemode", "execute", "function", "schedule", "return",
        "op", "deop", "stop", "kick", "ban", "ban-ip", "pardon", "pardon-ip",
        "whitelist", "save-off", "save-all", "save-on", "reload", "datapack",
        "debug", "jfr", "perf", "publish", "aibot", "damage", "data", "setblock", "fill", "clone", "place", "fillbiome");
    // These can create resources or mutate blocks/entities outside the inventory-backed builder.
    public static final Set<String> CREATIVE_ONLY = Set.of(
        "give", "item", "loot", "setblock", "fill", "clone", "summon", "data",
        "place", "enchant", "experience", "xp", "clear", "effect", "attribute", "fillbiome", "advancement", "recipe");
    public static final Set<String> VANILLA = Set.of(
        "advancement", "attribute", "bossbar", "clear", "clone", "defaultgamemode", "difficulty",
        "effect", "enchant", "experience", "xp", "fill", "fillbiome", "forceload", "gamerule",
        "give", "help", "item", "list", "locate", "loot", "me", "msg", "tell", "w", "particle",
        "place", "playsound", "random", "recipe", "ride", "say", "scoreboard", "seed",
        "setblock", "setworldspawn", "spawnpoint", "spectate", "spreadplayers", "stopsound",
        "summon", "tag", "team", "teammsg", "tm", "teleport", "tp", "time", "title", "trigger",
        "weather", "worldborder");
    private CommandPolicy() {}
    public static String normalize(String command) {
        if (command == null) throw new IllegalArgumentException("命令为空");
        String s = command.trim();
        if (s.startsWith("/")) s = s.substring(1);
        if (s.isBlank() || s.length() > MAX_LENGTH || s.chars().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
            throw new IllegalArgumentException("命令过长或含控制字符");
        String lower = s.toLowerCase(Locale.ROOT);
        if (s.contains("{") || s.contains("}") || lower.contains("command_block") || lower.contains("commandblock")
            || lower.contains("structure_block") || lower.contains("jigsaw") || lower.contains("click_event") || lower.contains("clickevent"))
            throw new IllegalArgumentException("不允许 NBT 或间接命令执行载体");
        String root = s.split(" +", 2)[0];
        if (!root.matches("(?:minecraft:)?[a-z][a-z0-9_-]*")) throw new IllegalArgumentException("不支持的命令命名空间或格式");
        String plainRoot = root.replace("minecraft:", "");
        if (DENIED.contains(plainRoot) || !VANILLA.contains(plainRoot)) throw new IllegalArgumentException("命令被禁止或不是受支持的原版命令");
        return s;
    }
    public static String root(String command) { return normalize(command).split(" +", 2)[0].replace("minecraft:", ""); }
    public static void checkMode(String command, boolean creative) {
        if (!creative && CREATIVE_ONLY.contains(root(command))) throw new IllegalArgumentException("该命令仅允许创造模式，生存建造请使用库存材料方案");
    }
}
