package top.goldgom.aibotassist;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlanTest {
    private String block(String x, String y, String z, String id) {
        return "{\"x\":"+x+",\"y\":"+y+",\"z\":"+z+",\"block\":\""+id+"\"}";
    }
    private String json(String blocks) { return "{\"reply\":\"你好\",\"blocks\":["+blocks+"]}"; }
    @Test void chatHasNoWorldActions() { assertTrue(Plan.parse(json("")).blocks().isEmpty()); }
    @Test void validMaterials() {
        Plan p = Plan.parse(json(block("1","0","3","minecraft:oak_planks")+","+block("2","0","3","minecraft:oak_planks")));
        assertEquals(2, p.materials().get("minecraft:oak_planks"));
    }
    @Test void rejectsCommandBlocks() { assertThrows(Exception.class, () -> Plan.parse(json(block("1","0","3","minecraft:command_block")))); }
    @Test void rejectsAirAndTnt() {
        for (String id : new String[]{"minecraft:air","minecraft:tnt","minecraft:lava","minecraft:chest"})
            assertThrows(Exception.class, () -> Plan.parse(json(block("1","0","3",id))));
    }
    @Test void rejectsDuplicatePositions() { String b=block("1","0","3","minecraft:oak_planks"); assertThrows(Exception.class, () -> Plan.parse(json(b+","+b))); }
    @Test void rejectsFractionsAndOverflow() {
        for (String x : new String[]{"0.5","2147483648","-2147483648","17","-17"})
            assertThrows(Exception.class, () -> Plan.parse(json(block(x,"0","3","minecraft:oak_planks"))));
    }
    @Test void acceptsRadiusBoundary() { assertEquals(1, Plan.parse(json(block("16","-16","16","minecraft:glass"))).blocks().size()); }
    @Test void rejectsTooManyBlocks() {
        String b=block("1","0","3","minecraft:oak_planks");
        assertThrows(Exception.class, () -> Plan.parse(json(String.join(",", java.util.Collections.nCopies(257,b)))));
    }
    @Test void rejectsInvalidAndOversizedResponses() {
        for (String s : new String[]{"not json","{}","{\"reply\":\"x\"}","x".repeat(100001)}) assertThrows(Exception.class, () -> Plan.parse(s));
    }
    @Test void maxPlanFitsMinecraftUploadLimit() {
        var blocks = new java.util.ArrayList<Plan.Placement>();
        for (int x=-16;x<0;x++) for (int z=-16;z<0;z++) blocks.add(new Plan.Placement(x,-16,z,"minecraft:polished_andesite"));
        String plan = AIBotAssist.JSON.toJson(new Plan("",blocks));
        com.google.gson.JsonObject packet = new com.google.gson.JsonObject();
        packet.addProperty("action","plan"); packet.addProperty("id",java.util.UUID.randomUUID().toString()); packet.addProperty("plan",plan);
        assertTrue(packet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32700);
    }
}
