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
    @Test void acceptsRegisteredIdSyntaxWithoutWhitelist() { assertDoesNotThrow(() -> Plan.parse(json(block("1","0","3","minecraft:command_block")))); }
    @Test void acceptsAllWellFormedBlockIds() {
        for (String id : new String[]{"minecraft:air","minecraft:tnt","minecraft:lava","minecraft:chest"})
            assertDoesNotThrow(() -> Plan.parse(json(block("1","0","3",id))));
    }
    @Test void rejectsDuplicatePositions() { String b=block("1","0","3","minecraft:oak_planks"); assertThrows(Exception.class, () -> Plan.parse(json(b+","+b))); }
    @Test void rejectsFractionsAndOverflow() {
        for (String x : new String[]{"0.5","2147483648","-2147483648","17","-17"})
            assertThrows(Exception.class, () -> Plan.parse(json(block(x,"0","3","minecraft:oak_planks"))));
    }
    @Test void acceptsRadiusBoundary() { assertEquals(1, Plan.parse(json(block("16","-16","16","minecraft:glass"))).blocks().size()); }
    private String volume(int edge) {return "{\"reply\":\"\",\"volumes\":[{\"kind\":\"box\",\"from\":{\"x\":0,\"y\":0,\"z\":0},\"to\":{\"x\":"+(edge-1)+",\"y\":"+(edge-1)+",\"z\":"+(edge-1)+"},\"block\":\"minecraft:stone\"}]}";}
    @Test void default4096CustomAndUnlimitedLimitsApplyToExpandedVolumes() {
        assertEquals(4096,Plan.parse(volume(16)).blocks().size());
        assertThrows(IllegalArgumentException.class,()->Plan.parse(volume(17)));
        assertEquals(4913,Plan.parse(volume(17),0).blocks().size());
        assertEquals(4913,Plan.parse(volume(17),5000).blocks().size());
        assertThrows(IllegalArgumentException.class,()->Plan.parse(volume(16),100));
        assertThrows(IllegalArgumentException.class,()->Plan.parse(volume(16),-1));
    }
    @Test void explicitDetailOverridesVolumeWithoutDuplicateCostAndEllipsoidHasDepth() {
        var p=com.google.gson.JsonParser.parseString(volume(3)).getAsJsonObject();
        p.add("blocks",com.google.gson.JsonParser.parseString("["+block("1","1","1","minecraft:gold_block")+"]"));
        assertEquals(27,Plan.parse(p.toString()).blocks().size());assertEquals(1,Plan.parse(p.toString()).materials().get("minecraft:gold_block"));
        p.remove("blocks");p.getAsJsonArray("volumes").get(0).getAsJsonObject().addProperty("kind","ellipsoid");
        var ellipsoid=Plan.parse(p.toString());assertTrue(ellipsoid.blocks().size()<27);
        assertEquals(3,ellipsoid.blocks().stream().map(Plan.Placement::z).distinct().count());
    }
    @Test void removalsCountTowardTheSelectedLimitAndUnlimitedKeepsCoordinateValidation() {
        var p=com.google.gson.JsonParser.parseString(volume(16)).getAsJsonObject();
        p.add("removals",com.google.gson.JsonParser.parseString("[{\"x\":-1,\"y\":0,\"z\":0}]"));
        assertThrows(IllegalArgumentException.class,()->Plan.parse(p.toString()));
        assertEquals(1,Plan.parse(p.toString(),4097).removals().size());
        assertThrows(IllegalArgumentException.class,()->Plan.parse(volume(18),0));
        assertThrows(IllegalArgumentException.class,()->Plan.parse(json(block("1.5","0","0","minecraft:stone")),0));
    }

    @Test void rejectsInvalidAndOversizedResponses() {
        for (String s : new String[]{"not json","{}","{\"reply\":\"x\"}","x".repeat(100001)}) assertThrows(Exception.class, () -> Plan.parse(s));
    }
    @Test void smallLegacyPlanFitsSingleMinecraftPacket() {
        var blocks = new java.util.ArrayList<Plan.Placement>();
        for (int x=-16;x<0;x++) for (int z=-16;z<0;z++) blocks.add(new Plan.Placement(x,-16,z,"minecraft:polished_andesite"));
        String plan = AIBotAssist.JSON.toJson(new Plan("",blocks));
        com.google.gson.JsonObject packet = new com.google.gson.JsonObject();
        packet.addProperty("action","plan"); packet.addProperty("id",java.util.UUID.randomUUID().toString()); packet.addProperty("plan",plan);
        assertTrue(packet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32700);
    }
}
