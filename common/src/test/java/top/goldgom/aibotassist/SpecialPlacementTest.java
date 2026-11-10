package top.goldgom.aibotassist;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpecialPlacementTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private static Plan.Placement p(String block, Map<String, String> props, String nbt) {
        return new Plan.Placement(0, 0, 0, "minecraft:" + block, props, nbt);
    }
    private static Plan.Placement p(String block) { return p(block, Map.of(), null); }
    private static void reject(String block, String nbt, boolean creative) {
        assertThrows(IllegalArgumentException.class, () -> SpecialPlacement.blockArgument(p(block, Map.of(), nbt), creative), nbt);
    }

    @Test void registryAndPropertiesAreStrictWithoutBlockWhitelist() {
        assertTrue(SpecialPlacement.state(p("diamond_block")).is(Blocks.DIAMOND_BLOCK));
        assertTrue(SpecialPlacement.state(p("oak_stairs", Map.of("facing", "east", "half", "top"), null)).is(Blocks.OAK_STAIRS));
        for (var placement : List.of(p("does_not_exist"), p("stone", Map.of("facing", "north"), null),
            p("oak_stairs", Map.of("facing", "north];kill @a"), null), p("water", Map.of("level", "1"), null),
            p("lava", Map.of("level", "15"), null), p("stone\nkill")))
            assertThrows(IllegalArgumentException.class, () -> SpecialPlacement.state(placement));
    }

    @Test void doorsAndBedsDefaultNorthAndExpandOnce() {
        var door = SpecialPlacement.expand(List.of(p("oak_door")));
        assertEquals(2, door.size()); assertEquals(1, door.get(1).y());
        assertEquals("north", door.getFirst().properties().get("facing"));
        assertTrue(SpecialPlacement.isUpperOrHead(door.get(1)));
        var bed = SpecialPlacement.expand(List.of(p("red_bed")));
        assertEquals(-1, bed.get(1).z());
        assertEquals(BedPart.HEAD, SpecialPlacement.state(bed.get(1)).getValue(BedBlock.PART));
        assertThrows(IllegalArgumentException.class, () -> SpecialPlacement.expand(door));
        assertThrows(IllegalArgumentException.class, () -> SpecialPlacement.expand(List.of(p("red_bed", Map.of("part", "head"), null))));
    }

    @Test void bedDirectionsAreGeometric() {
        for (var entry : Map.of("north", new int[]{0, -1}, "south", new int[]{0, 1}, "east", new int[]{1, 0}, "west", new int[]{-1, 0}).entrySet()) {
            var head = SpecialPlacement.expand(List.of(p("blue_bed", Map.of("facing", entry.getKey()), null))).get(1);
            assertEquals(entry.getValue()[0], head.x()); assertEquals(entry.getValue()[1], head.z());
        }
    }

    @Test void generatedCellsAreIncludedInLimitsAndCollisions() {
        assertThrows(IllegalArgumentException.class, () -> SpecialPlacement.expand(List.of(
            new Plan.Placement(0, 16, 0, "minecraft:oak_door"))));
        assertThrows(IllegalArgumentException.class, () -> SpecialPlacement.expand(List.of(
            new Plan.Placement(0, 0, -16, "minecraft:red_bed"))));
        assertThrows(IllegalArgumentException.class, () -> SpecialPlacement.expand(List.of(p("oak_door"),
            new Plan.Placement(0, 1, 0, "minecraft:stone"))));
        assertThrows(IllegalArgumentException.class, () -> SpecialPlacement.expand(List.of(p("stone"), p("stone"))));
        assertThrows(IllegalArgumentException.class, () -> SpecialPlacement.expand(List.of(
            new Plan.Placement(Integer.MIN_VALUE, 0, 0, "minecraft:stone"))));
        List<Plan.Placement> input = new ArrayList<>();
        for (int x = -16; x <= 16 && input.size() < 129; x++)
            for (int z = -16; z <= 16 && input.size() < 129; z++) input.add(new Plan.Placement(x, 0, z, "minecraft:oak_door"));
        assertEquals(256, SpecialPlacement.expand(input.subList(0, 128)).size());
        assertThrows(IllegalArgumentException.class, () -> SpecialPlacement.expand(input));
    }

    @Test void actualItemsAndPartialSuccessCannotMakeHalfBlocksFree() {
        for (String block : List.of("oak_door", "red_bed")) {
            var pair = SpecialPlacement.expand(List.of(p(block)));
            var cost = Map.of("minecraft:" + block, 1);
            assertEquals(cost, SpecialPlacement.costs(List.of(p(block))));
            assertEquals(cost, SpecialPlacement.costs(pair));
            assertEquals(cost, SpecialPlacement.costs(List.of(pair.getFirst())));
            assertEquals(cost, SpecialPlacement.costs(List.of(pair.get(1))));
        }
        assertEquals(Map.of("minecraft:water_bucket", 1, "minecraft:lava_bucket", 1), SpecialPlacement.costs(List.of(
            p("water"), new Plan.Placement(1, 0, 0, "minecraft:lava"))));
        assertEquals(Map.of("minecraft:wheat_seeds", 1), SpecialPlacement.costs(List.of(p("wheat"))));
        assertThrows(IllegalArgumentException.class, () -> SpecialPlacement.costs(List.of(p("air"))));
    }

    @Test void canonicalArgumentsAndSafeSurvivalNbt() {
        assertEquals("minecraft:stone", SpecialPlacement.blockArgument(p("stone"), false));
        String arg = SpecialPlacement.blockArgument(p("chest", Map.of("facing", "east"), "{Lock:'key',CustomName:'{\"text\":\"Safe\"}'}"), false);
        assertTrue(arg.startsWith("minecraft:chest[")); assertTrue(arg.contains("facing=east")); assertTrue(arg.contains("CustomName:"));
        reject("stone", "{Lock:'key'}", false);
        reject("chest", "{Items:[]}", false);
        reject("chest", "{} run kill @a", true);
        reject("chest", "{}\nkill @a", true);
        assertTrue(SpecialPlacement.blockArgument(p("command_block"), true).startsWith("minecraft:command_block"));
        assertThrows(IllegalArgumentException.class, () -> SpecialPlacement.blockArgument(p("command_block"), false));
    }

    @Test void rejectsCommandsActivationIdentityAndNestedItemPayloads() {
        for (String nbt : List.of("{Command:'kill @a'}", "{command:'gamemode creative @a'}", "{auto:1b}", "{powered:1b}",
            "{x:1}", "{y:1}", "{z:1}", "{id:'minecraft:command_block'}", "{LootTable:'minecraft:chests/simple_dungeon'}",
            "{Items:[{Slot:0b,id:'minecraft:stone',count:1,components:{'minecraft:block_entity_data':{Command:'kill @a'}}}]}",
            "{Items:[{Slot:0b,id:'minecraft:command_block',count:1}]}",
            "{Items:[{Slot:0b,id:'minecraft:command_block_minecart',count:1}]}",
            "{CustomName:'{\"text\":\"x\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/kill @a\"}}'}"))
            for (boolean creative : List.of(false, true)) reject("chest", nbt, creative);
        reject("command_block", "{Command:''}", true);
        reject("chest", "{Lock:'" + "a".repeat(SpecialPlacement.MAX_NBT_LENGTH) + "'}", true);
        reject("chest", "{a:".repeat(20) + "0" + "}".repeat(20), true);
    }

    @Test void creativeContainersAcceptOnlyPlainValidatedStacks() {
        String nbt = "{Items:[{Slot:0b,id:'minecraft:diamond',count:2}]}";
        assertTrue(SpecialPlacement.blockArgument(p("chest", Map.of(), nbt), true).contains("Items:"));
        reject("oak_sign", nbt, true);
        for (String items : List.of("[{Slot:0b,id:'minecraft:diamond',count:65}]", "[{Slot:27b,id:'minecraft:diamond',count:1}]",
            "[{Slot:0b,id:'minecraft:missing',count:1}]", "[{Slot:0b,id:'minecraft:stone',count:1},{Slot:0b,id:'minecraft:stone',count:1}]",
            "[{Slot:0b,id:'minecraft:stone',count:1,tag:{Command:'kill @a'}}]", "[1]")) reject("chest", "{Items:" + items + "}", true);
    }

    @org.junit.jupiter.api.Test void neighborConnectionsAreNotPlacementFailures() {
        var pane=new Plan.Placement(0,0,0,"minecraft:glass_pane");
        var connected=SpecialPlacement.state(pane).setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.NORTH,true);
        org.junit.jupiter.api.Assertions.assertTrue(SpecialPlacement.matches(pane,connected));
        org.junit.jupiter.api.Assertions.assertFalse(SpecialPlacement.matches(pane,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()));
    }
    @org.junit.jupiter.api.Test void semanticDirectionStillMatters() {
        var door=new Plan.Placement(0,0,0,"minecraft:oak_door",java.util.Map.of("facing","east"),"");
        org.junit.jupiter.api.Assertions.assertFalse(SpecialPlacement.matches(door,SpecialPlacement.state(door).setValue(net.minecraft.world.level.block.DoorBlock.FACING,net.minecraft.core.Direction.WEST)));
    }
}
