package top.goldgom.aibotassist;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class RedstoneDiagnosticsTest {
    @Test void observerAndPistonPortsHaveDifferentMeanings(){
        for(Direction d:Direction.values()){
            var observer=RedstoneDiagnostics.inspect(BlockPos.ZERO,Blocks.OBSERVER.defaultBlockState().setValue(net.minecraft.world.level.block.ObserverBlock.FACING,d),p->null);
            var output=observer.getAsJsonObject("output").getAsJsonObject("position");assertEquals(-d.getStepX(),output.get("x").getAsInt());assertEquals(-d.getStepY(),output.get("y").getAsInt());assertEquals(-d.getStepZ(),output.get("z").getAsInt());
            var piston=RedstoneDiagnostics.inspect(BlockPos.ZERO,Blocks.PISTON.defaultBlockState().setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING,d),p->null);
            var target=piston.getAsJsonObject("extension_target").getAsJsonObject("position");assertEquals(d.getStepY(),target.get("y").getAsInt());assertEquals(d.getStepZ(),target.get("z").getAsInt());assertEquals(12,piston.get("vanilla_push_limit").getAsInt());
        }
    }
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void cannonRepeaterOutputIsEastOfWestFacingInputAndReportsMissingWire(){
        var p=new BlockPos(63,81,199);
        var state=Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING,Direction.WEST).setValue(RepeaterBlock.DELAY,4);
        var d=RedstoneDiagnostics.inspect(p,state,n->n.equals(p.west())?Blocks.REDSTONE_WIRE.defaultBlockState():Blocks.AIR.defaultBlockState());
        assertEquals(64,d.getAsJsonObject("output").getAsJsonObject("position").get("x").getAsInt());
        assertEquals(62,d.getAsJsonObject("input").getAsJsonObject("position").get("x").getAsInt());
        assertEquals(8,d.get("delay_game_ticks").getAsInt());assertFalse(d.get("function_verified").getAsBoolean());
        assertEquals("[\"output_points_to_air\"]",d.getAsJsonArray("warnings").toString());
    }
    @Test void diodePortsWorkInEveryHorizontalDirectionWithoutGuessingLoadedAir(){
        var p=BlockPos.ZERO;
        for(var direction:Direction.Plane.HORIZONTAL){
            var d=RedstoneDiagnostics.inspect(p,Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING,direction),n->null);
            var input=d.getAsJsonObject("input").getAsJsonObject("position");
            assertEquals(direction.getStepX(),input.get("x").getAsInt());assertEquals(direction.getStepZ(),input.get("z").getAsInt());
            assertFalse(d.getAsJsonObject("output").get("loaded").getAsBoolean());assertTrue(d.getAsJsonArray("warnings").isEmpty());
        }
    }
    @Test void dispenserFacingDescribesEjectionRatherThanDiodeInput(){
        var d=RedstoneDiagnostics.inspect(new BlockPos(63,81,197),Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING,Direction.SOUTH),n->Blocks.AIR.defaultBlockState());
        assertEquals(198,d.getAsJsonObject("ejection_target").getAsJsonObject("position").get("z").getAsInt());assertFalse(d.has("input"));assertFalse(d.get("function_verified").getAsBoolean());
    }
}
