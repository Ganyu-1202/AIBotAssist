package top.goldgom.aibotassist;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MachineBlueprintsTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void smelterHoppersConnectToTheCorrectTargetsAfterEveryRotation(){
        for(String facing:List.of("north","south","east","west")){
            var plan=MachineBlueprints.create("auto_smelter",facing);Map<String,Plan.Placement> cells=new HashMap<>();
            for(var b:plan.blocks()){SpecialPlacement.blockArgument(b,false);assertNull(cells.put(b.x()+","+b.y()+","+b.z(),b));}
            int furnaces=0,chests=0;
            for(var b:plan.blocks())if(b.block().equals("minecraft:hopper")){
                Direction d=Direction.byName(b.properties().get("facing"));var target=cells.get((b.x()+d.getStepX())+","+(b.y()+d.getStepY())+","+(b.z()+d.getStepZ()));assertNotNull(target);
                if(target.block().equals("minecraft:furnace"))furnaces++;else if(target.block().equals("minecraft:chest"))chests++;else fail("hopper has no machine target");
            }
            assertEquals(2,furnaces);assertEquals(1,chests);
        }
    }
    @Test void redstoneHasSupportAndCrafterSlotsCannotInjectOtherNbt(){
        var plan=MachineBlueprints.create("repeater_clock","south");
        for(var b:plan.blocks()){SpecialPlacement.blockArgument(b,false);if(b.y()==1)assertTrue(plan.blocks().stream().anyMatch(base->base.x()==b.x()&&base.z()==b.z()&&base.y()==0));}
        String slots=MachineBlueprints.disabledSlots(Set.of(0,1,3,4));assertEquals("{disabled_slots:[I;2,5,6,7,8]}",slots);
        var crafter=new Plan.Placement(0,0,0,"minecraft:crafter",Map.of("orientation","north_up"),slots);assertTrue(SpecialPlacement.blockArgument(crafter,false).contains("disabled_slots"));
        for(String invalid:List.of("{disabled_slots:[I;-1]}","{disabled_slots:[I;9]}","{disabled_slots:[I;0,0]}","{disabled_slots:[0]}","{disabled_slots:[I;0],Command:'say hi'}"))assertThrows(IllegalArgumentException.class,()->SpecialPlacement.blockArgument(new Plan.Placement(0,0,0,"minecraft:crafter",Map.of(),invalid),true));
        assertThrows(IllegalArgumentException.class,()->SpecialPlacement.blockArgument(new Plan.Placement(0,0,0,"minecraft:chest",Map.of(),slots),true));
    }
    @Test void livePowerAndWireConnectionsDoNotTriggerRepairsButOrientationStillDoes(){
        var wire=new Plan.Placement(0,0,0,"minecraft:redstone_wire");
        var live=SpecialPlacement.state(new Plan.Placement(0,0,0,"minecraft:redstone_wire",Map.of("power","15","north","side","south","side"),""));
        assertTrue(SpecialPlacement.matches(wire,live));
        var repeater=new Plan.Placement(0,0,0,"minecraft:repeater",Map.of("facing","east","delay","4"),"");
        assertTrue(SpecialPlacement.matches(repeater,SpecialPlacement.state(new Plan.Placement(0,0,0,"minecraft:repeater",Map.of("facing","east","delay","4","powered","true"),""))));
        assertFalse(SpecialPlacement.matches(repeater,SpecialPlacement.state(new Plan.Placement(0,0,0,"minecraft:repeater",Map.of("facing","west","delay","4"),""))));
        assertFalse(SpecialPlacement.matches(new Plan.Placement(0,0,0,"minecraft:redstone_wire",Map.of("power","0"),""),live));
    }
}
