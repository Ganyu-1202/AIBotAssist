package top.goldgom.aibotassist;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BuildSlicesTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void full4096PlanExecutesAsBoundedSlicesWithNoMissingOrDuplicateVoxels() {
        var blocks=new ArrayList<Plan.Placement>();for(int y=15;y>=0;y--)for(int x=0;x<16;x++)for(int z=0;z<16;z++)blocks.add(new Plan.Placement(x,y,z,"minecraft:stone"));
        var batches=BuildSlices.partition(SpecialPlacement.expand(blocks));assertEquals(64,batches.size());
        assertTrue(batches.stream().allMatch(b->b.size()<=64));
        var flattened=batches.stream().flatMap(List::stream).toList();assertEquals(4096,new HashSet<>(flattened).size());
        for(int i=1;i<flattened.size();i++)assertTrue(flattened.get(i-1).y()<=flattened.get(i).y());
    }
    @Test void doorAndBedPairsNeverCrossTickBoundaries() {
        var blocks=new ArrayList<Plan.Placement>();
        for(int i=0;i<63;i++)blocks.add(new Plan.Placement(i%16,0,i/16,"minecraft:stone"));
        blocks.add(new Plan.Placement(-1,0,0,"minecraft:oak_door"));blocks.add(new Plan.Placement(-3,0,0,"minecraft:red_bed"));
        var batches=BuildSlices.partition(SpecialPlacement.expand(blocks));assertEquals(2,batches.size());
        assertEquals(63,batches.getFirst().size());assertEquals(4,batches.get(1).size());
        for(var batch:batches)assertFalse(SpecialPlacement.isUpperOrHead(batch.getFirst()));
        assertEquals(2,SpecialPlacement.costs(batches.get(1)).values().stream().mapToInt(Integer::intValue).sum());
    }
}
