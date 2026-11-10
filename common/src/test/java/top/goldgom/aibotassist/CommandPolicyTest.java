package top.goldgom.aibotassist;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandPolicyTest {
    @Test void deniesKillAndGamemodeVariants() {
        for (String s : new String[]{"kill @a", "/kill @s", "minecraft:kill @s", "/minecraft:gamemode creative", "gamemode survival", "defaultgamemode creative"})
            assertThrows(IllegalArgumentException.class, () -> CommandPolicy.normalize(s));
    }
    @Test void deniesIndirectAndAdministrativeExecution() {
        for (String s : new String[]{"execute run kill @a", "function test:kill", "schedule function test:a 1t", "return run kill @a", "op player", "stop", "fill ~ ~ ~ ~1 ~1 ~1 air", "minecraft:setblock ~ ~ ~ air", "clone ~ ~ ~ ~1 ~1 ~1 ~ ~ ~", "other:alias", "aibot confirm", "data merge block ~ ~ ~ {}", "setblock ~ ~ ~ command_block", "summon command_block_minecart", "give @s stone{a:1}", "say hi\nkill @a"})
            assertThrows(IllegalArgumentException.class, () -> CommandPolicy.normalize(s), s);
    }
    @Test void acceptsOrdinaryCommands() {
        for (String s : new String[]{"time query daytime", "weather clear", "tp @s ~ ~1 ~", "say hello", "minecraft:give @s minecraft:stone 1"})
            assertDoesNotThrow(() -> CommandPolicy.normalize(s));
    }
    @Test void survivalCannotBypassInventory() {
        for (String s : new String[]{"give @s stone", "loot give @s loot test:a", "summon item", "item replace entity @s weapon.mainhand with stone"}) {
            assertThrows(IllegalArgumentException.class, () -> CommandPolicy.checkMode(s, false));
            assertDoesNotThrow(() -> CommandPolicy.checkMode(s, true));
        }
        assertDoesNotThrow(() -> CommandPolicy.checkMode("time query daytime", false));
    }
    @Test void supportsCommandOnlyPlansAndLegacyPlans() {
        Plan p = Plan.parse("{\"reply\":\"查询时间\",\"blocks\":[],\"commands\":[\"/time query daytime\"]}");
        assertTrue(p.hasActions()); assertEquals("time query daytime", p.commands().getFirst());
        assertFalse(Plan.parse("{\"reply\":\"你好\",\"blocks\":[]}").hasActions());
    }
    @Test void rejectsForbiddenPlansAndAcceptsMixedActions() {
        assertThrows(Exception.class, () -> Plan.parse("{\"reply\":\"x\",\"blocks\":[],\"commands\":[\"kill @s\"]}"));
        assertDoesNotThrow(() -> Plan.parse("{\"reply\":\"x\",\"blocks\":[{\"x\":1,\"y\":1,\"z\":1,\"block\":\"minecraft:stone\"}],\"commands\":[\"time query daytime\"]}"));
    }
    @Test void limitsCommandCountAndLength() {
        assertThrows(Exception.class, () -> CommandPolicy.normalize("say " + "x".repeat(256)));
        String commands = String.join(",", java.util.Collections.nCopies(9, "\"time query daytime\""));
        assertThrows(Exception.class, () -> Plan.parse("{\"reply\":\"x\",\"blocks\":[],\"commands\":[" + commands + "]}"));
    }
}
