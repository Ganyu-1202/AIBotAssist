package top.goldgom.aibotassist;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResourceToolsTest {
    private static JsonObject json(String s) { return JsonParser.parseString(s).getAsJsonObject(); }

    @Test void independentItemDefaultsToOneAndCanonicalizes() {
        assertEquals(Map.of("minecraft:oak_planks", 1), ResourceTools.parseGoals(json("{\"item\":\"oak_planks\"}")));
        assertEquals(Map.of("minecraft:stick", 8), ResourceTools.parseGoals(json("{\"item\":\"minecraft:stick\",\"count\":8}")));
    }
    @Test void multipleGoalsPreserveOrderAndCounts() {
        var goals = ResourceTools.parseGoals(json("{\"goals\":{\"stick\":4,\"minecraft:oak_planks\":2}}"));
        assertEquals(List.of("minecraft:stick", "minecraft:oak_planks"), new ArrayList<>(goals.keySet()));
        assertEquals(4, goals.get("minecraft:stick"));
    }
    @Test void rejectsAmbiguousAndEmptyGoals() {
        for (String s : List.of("{}", "{\"goals\":{}}", "{\"goals\":[]}",
            "{\"item\":\"stick\",\"goals\":{\"stick\":1}}", "{\"count\":1,\"goals\":{\"stick\":1}}",
            "{\"goals\":{\"stick\":1,\"minecraft:stick\":2}}"))
            assertThrows(IllegalArgumentException.class, () -> ResourceTools.parseGoals(json(s)), s);
    }
    @Test void countsAreStrictBoundedIntegers() {
        for (String n : List.of("0", "-1", "4097", "1.5", "2147483648", "\"2\"", "true", "null", "{}", "[]"))
            assertThrows(IllegalArgumentException.class, () -> ResourceTools.parseGoals(json("{\"item\":\"stick\",\"count\":" + n + "}")), n);
        assertEquals(4096, ResourceTools.parseGoals(json("{\"item\":\"stick\",\"count\":4096}")).get("minecraft:stick"));
        assertThrows(IllegalArgumentException.class, () -> ResourceTools.parseGoals(json("{\"goals\":{\"stick\":4096,\"oak_planks\":4096,\"dirt\":1}}")));
    }
    @Test void limitsGoalCount() {
        JsonObject args = new JsonObject(), goals = new JsonObject(); args.add("goals", goals);
        for (int i = 0; i < 17; i++) goals.addProperty("test:item_" + i, 1);
        assertThrows(IllegalArgumentException.class, () -> ResourceTools.parseGoals(args));
    }
    @Test void identifiersCannotContainCommandsNbtOrAir() {
        for (String id : List.of("", "air", "minecraft:air", "Minecraft:stone", "minecraft:stone{foo:1}", "stone;op", "a:b:c", " stone", "stone\n", "x".repeat(129)))
            assertThrows(IllegalArgumentException.class, () -> ResourceTools.canonicalId(id), id);
        assertEquals("example:some/item", ResourceTools.canonicalId("example:some/item"));
    }
    @Test void scanOptionsCannotBeUnbounded() {
        assertEquals(4, ResourceTools.integer(new JsonObject(), "radius", 4, 1, 8));
        assertThrows(IllegalArgumentException.class, () -> ResourceTools.integer(json("{\"radius\":9}"), "radius", 4, 1, 8));
        assertThrows(IllegalArgumentException.class, () -> ResourceTools.integer(json("{\"limit\":0}"), "limit", 8, 1, 32));
    }
    @Test void entryPointRejectsMissingPlayerWithoutLeakingException() {
        var reply = ResourceTools.handle(null, "inspect_inventory", new JsonObject());
        assertFalse(reply.get("ok").getAsBoolean());
        assertEquals("server_thread_required", reply.get("error").getAsString());
    }

    @Nested class InventoryPacking {
        @BeforeAll static void bootstrapMinecraft() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

        @Test void emptyInventoryIsValid() {
            assertTrue(ResourceTools.pack(List.of(), 64).isEmpty());
            assertTrue(ResourceTools.pack(List.of(ItemStack.EMPTY), 64).isEmpty());
        }
        @Test void reservedGoalsAndSurplusAreNotConsumedAndSourcesAreNotMutated() {
            ItemStack reserved = new ItemStack(Items.STICK, 8), surplus = new ItemStack(Items.STICK, 3);
            var packed = ResourceTools.pack(List.of(reserved, surplus), 64);
            assertEquals(11, packed.getFirst().getCount());
            assertEquals(8, reserved.getCount()); assertEquals(3, surplus.getCount());
            assertNotSame(reserved, packed.getFirst());
        }
        @Test void splitsOversizedSimulatorStacksAndKeepsRemainders() {
            var packed = ResourceTools.pack(List.of(new ItemStack(Items.STICK, 130), new ItemStack(Items.BUCKET, 2)), 64);
            assertEquals(List.of(64, 64, 2, 2), packed.stream().map(ItemStack::getCount).toList());
        }
        @Test void damagedToolsArePreservedAndNotMerged() {
            ItemStack a = new ItemStack(Items.IRON_PICKAXE), b = new ItemStack(Items.IRON_PICKAXE);
            a.setDamageValue(7); b.setDamageValue(20);
            var packed = ResourceTools.pack(List.of(a, b), 64);
            assertEquals(2, packed.size()); assertEquals(7, packed.get(0).getDamageValue()); assertEquals(20, packed.get(1).getDamageValue());
            assertEquals(7, a.getDamageValue());
        }
        @Test void fullInventoryAcceptedButOverflowRejectedWithoutMutation() {
            ItemStack full = new ItemStack(Items.STICK, 36 * 64);
            assertEquals(36, ResourceTools.pack(List.of(full), 64).size());
            ItemStack extra = new ItemStack(Items.STICK);
            assertThrows(IllegalArgumentException.class, () -> ResourceTools.pack(List.of(full, extra), 64));
            assertEquals(36 * 64, full.getCount()); assertEquals(1, extra.getCount());
        }
        @Test void respectsInventoryStackLimit() {
            assertEquals(List.of(16, 16, 1), ResourceTools.pack(List.of(new ItemStack(Items.STICK, 33)), 16).stream().map(ItemStack::getCount).toList());
        }
    }
}
