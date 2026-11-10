package top.goldgom.aibotassist;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BatchCommandsTest {
    @Test void mergesOnlyContiguousSameMaterialRows() {
        var list=List.of(new Plan.Placement(1,0,3,"minecraft:stone"),new Plan.Placement(2,0,3,"minecraft:stone"),new Plan.Placement(4,0,3,"minecraft:stone"),new Plan.Placement(3,1,3,"minecraft:stone"));
        var runs=BatchCommands.placements(list);
        assertEquals(3,runs.size()); assertEquals(4,runs.stream().mapToInt(BatchCommands.Run::count).sum());
        assertEquals("fill 11 20 33 12 20 33 minecraft:stone replace",runs.getFirst().command(10,20,30));
        assertEquals("setblock 14 20 33 minecraft:stone replace",runs.get(1).command(10,20,30));
    }
    @Test void doesNotMergeDifferentMaterials() {
        assertEquals(2,BatchCommands.placements(List.of(new Plan.Placement(1,0,0,"minecraft:stone"),new Plan.Placement(2,0,0,"minecraft:glass"))).size());
    }
    @Test void parsesRemovalOnlyAndLegacyPlans() {
        Plan p=Plan.parse("{\"reply\":\"拆除\",\"blocks\":[],\"removals\":[{\"x\":1,\"y\":0,\"z\":3}]}");
        assertTrue(p.hasActions());assertEquals(1,p.removals().size());
        assertTrue(Plan.parse("{\"reply\":\"你好\",\"blocks\":[]}").removals().isEmpty());
    }
    @Test void rejectsOverlappingPlaceAndRemove() {
        assertThrows(Exception.class,()->Plan.parse("{\"reply\":\"x\",\"blocks\":[{\"x\":1,\"y\":0,\"z\":3,\"block\":\"minecraft:stone\"}],\"removals\":[{\"x\":1,\"y\":0,\"z\":3}]}"));
    }
    @Test void rejectsOutOfRangeRemoval() {
        assertThrows(Exception.class,()->Plan.parse("{\"reply\":\"x\",\"blocks\":[],\"removals\":[{\"x\":17,\"y\":0,\"z\":3}]}"));
    }
}
