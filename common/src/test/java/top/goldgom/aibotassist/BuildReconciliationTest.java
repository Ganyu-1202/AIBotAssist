package top.goldgom.aibotassist;

import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildReconciliationTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void expandingDoesNotTurnImplicitElectricalDefaultsIntoConstraints(){
        for(var id:List.of("repeater","dispenser","hopper","redstone_wire")){
            var p=SpecialPlacement.expand(List.of(new Plan.Placement(0,0,0,"minecraft:"+id))).getFirst();
            assertFalse(p.properties().containsKey("powered"));assertFalse(p.properties().containsKey("triggered"));assertFalse(p.properties().containsKey("power"));assertFalse(p.properties().containsKey("enabled"));
            var state=SpecialPlacement.state(p);
            for(var prop:state.getProperties())if(Set.of("powered","triggered","enabled","locked").contains(prop.getName())){
                var changed=state.cycle(prop);assertTrue(SpecialPlacement.matches(p,changed),id+"/"+prop.getName());
            }
        }
    }
    @Test void explicitPowerAndStructuralDelayRemainStrictAfterExpansion(){
        var p=SpecialPlacement.expand(List.of(new Plan.Placement(0,0,0,"minecraft:repeater",Map.of("powered","false","delay","4"),""))).getFirst();
        assertFalse(SpecialPlacement.matches(p,SpecialPlacement.state(p).setValue(RepeaterBlock.POWERED,true)));
        assertFalse(SpecialPlacement.matches(p,SpecialPlacement.state(p).setValue(RepeaterBlock.DELAY,1)));
    }
    @Test void subsequentBuildRetainsExistingDispenserAndOnlyAddsMissingWire(){
        var dispenser=new Plan.Placement(0,0,0,"minecraft:dispenser");var wire=new Plan.Placement(1,0,0,"minecraft:redstone_wire");
        var desired=new Plan("repair",List.of(dispenser,wire));
        var work=BuildReconciliation.missing(desired,p->SpecialPlacement.matches(p,p.x()==0?Blocks.DISPENSER.defaultBlockState():Blocks.AIR.defaultBlockState()));
        assertEquals(List.of(wire),work.blocks());assertEquals(2,desired.blocks().size());
        assertTrue(BuildReconciliation.missing(desired,p->true).blocks().isEmpty());
    }
    @Test void unspecifiedInventoryIsRetainedButExplicitRestockIsNotSilentlySkipped()throws Exception{
        var plain=new Plan.Placement(0,0,0,"minecraft:dispenser");
        var requested=new Plan.Placement(0,0,0,"minecraft:dispenser",Map.of(),"{Items:[{Slot:0b,id:'minecraft:tnt',count:64}]}");
        var actual=TagParser.parseTag("{Items:[{Slot:0b,id:'minecraft:tnt',count:61}]}");
        assertTrue(SpecialPlacement.matchesNbt(plain,actual));assertFalse(SpecialPlacement.matchesNbt(requested,actual));
        assertTrue(SpecialPlacement.matchesNbt(requested,TagParser.parseTag(requested.nbt())));
    }
    @Test void noOpFilteringKeepsIndependentCommandsAndRemovals(){
        var desired=new Plan("mixed",List.of(new Plan.Placement(0,0,0,"minecraft:stone")),List.of("time set day"),List.of(new Plan.Removal(2,0,0)));
        var work=BuildReconciliation.missing(desired,p->true);assertTrue(work.blocks().isEmpty());assertEquals(desired.commands(),work.commands());assertEquals(desired.removals(),work.removals());assertTrue(work.hasActions());
    }
}
