package top.goldgom.aibotassist;

import com.google.gson.*;
import java.util.function.Function;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Read-only port geometry. Static evidence must not be mistaken for a successful functional test. */
public final class RedstoneDiagnostics {
    private RedstoneDiagnostics() {}
    public static JsonObject analyze(Plan plan){
        Map<BlockPos,BlockState> cells=new HashMap<>();
        for(var b:plan.blocks())cells.put(new BlockPos(b.x(),b.y(),b.z()),SpecialPlacement.state(b));
        var warnings=new JsonArray();var components=new JsonArray();int total=0;
        for(var entry:cells.entrySet()){
            var pos=entry.getKey();var state=entry.getValue();if(!relevant(state))continue;total++;
            if(components.size()<128)components.add(inspect(pos,state,cells::get));
            if(state.getBlock() instanceof DiodeBlock){
                Direction input=state.getValue(DiodeBlock.FACING);
                for(var d:List.of(input,input.getOpposite())){
                    var neighbor=cells.get(pos.relative(d));
                    if(neighbor==null||neighbor.isAir())warning(warnings,pos,d==input?"input_not_in_plan":"output_not_in_plan");
                    if(neighbor!=null&&neighbor.getBlock() instanceof DiodeBlock&&neighbor.getValue(DiodeBlock.FACING)!=input)warning(warnings,pos,"adjacent_diode_direction_differs_check_port");
                }
            }
            if(state.getBlock() instanceof DiodeBlock||state.is(Blocks.REDSTONE_WIRE)){
                var support=cells.get(pos.below());if(support==null||support.isAir())warning(warnings,pos,"support_not_in_plan");
            }
        }
        var out=new JsonObject();out.addProperty("status","completed");out.addProperty("function_verified",false);out.addProperty("component_count",total);out.addProperty("components_truncated",total>components.size());out.add("components",components);out.add("warnings",warnings);
        out.addProperty("scope","Plan coordinates only; missing cells may exist in world. No power simulation, support-shape validation, timing or function proof. Resolve warnings with inspect_machine/inspect_area before preview and execution.");return out;
    }
    private static void warning(JsonArray warnings,BlockPos pos,String code){if(warnings.size()>=128)return;var w=new JsonObject();w.add("position",position(pos));w.addProperty("code",code);warnings.add(w);}
    public static boolean relevant(BlockState state) {
        return state.getBlock() instanceof DiodeBlock || state.getBlock() instanceof DispenserBlock || state.getBlock() instanceof PistonBaseBlock || state.is(Blocks.OBSERVER) || state.is(Blocks.REDSTONE_WIRE);
    }
    public static JsonObject inspect(BlockPos pos,BlockState state,Function<BlockPos,BlockState> read) {
        JsonObject out=new JsonObject();out.addProperty("function_verified",false);out.addProperty("state",state.toString());out.add("position",position(pos));
        JsonArray warnings=new JsonArray();
        if(state.getBlock() instanceof DiodeBlock) {
            Direction input=state.getValue(DiodeBlock.FACING);
            out.add("input",port(pos.relative(input),read,warnings,"input_points_to_air"));
            out.add("output",port(pos.relative(input.getOpposite()),read,warnings,"output_points_to_air"));
            out.addProperty("facing_semantics","Repeater/comparator facing is the INPUT side; output is opposite facing.");
            if(state.is(Blocks.REPEATER))out.addProperty("delay_game_ticks",state.getValue(RepeaterBlock.DELAY)*2);
        } else if(state.getBlock() instanceof PistonBaseBlock) {
            Direction facing=state.getValue(PistonBaseBlock.FACING);
            out.add("extension_target",port(pos.relative(facing),read,new JsonArray(),""));
            out.addProperty("extended",state.getValue(PistonBaseBlock.EXTENDED));out.addProperty("vanilla_push_limit",12);
            out.addProperty("facing_semantics","Piston facing is extension direction. Check full movement space, immovable blocks and attached slime/honey; this does not simulate pushing.");
        } else if(state.is(Blocks.OBSERVER)) {
            Direction facing=state.getValue(ObserverBlock.FACING);
            out.add("observed",port(pos.relative(facing),read,new JsonArray(),""));
            out.add("output",port(pos.relative(facing.getOpposite()),read,warnings,"output_points_to_air"));
            out.addProperty("facing_semantics","Observer facing is the observed side; output is opposite. Verify actual updates and pulses over time.");
        } else if(state.getBlock() instanceof DispenserBlock) {
            out.add("ejection_target",port(pos.relative(state.getValue(DispenserBlock.FACING)),read,new JsonArray(),""));
            out.addProperty("facing_semantics","Dispenser facing is the EJECTION side, not its redstone input.");
            warnings.add("Check ammunition and compare inventory before/after a user-authorized activation. No activation was performed.");
        } else if(state.is(Blocks.REDSTONE_WIRE)) {
            out.addProperty("power",state.getValue(RedStoneWireBlock.POWER));
        }
        out.add("warnings",warnings);out.addProperty("note","Air at a diode port is a wiring warning, not a complete circuit simulation. An idle zero signal does not prove failure; inspect the whole path and timing. No machine was triggered.");return out;
    }
    private static JsonObject port(BlockPos pos,Function<BlockPos,BlockState> read,JsonArray warnings,String airWarning) {
        JsonObject out=new JsonObject();out.add("position",position(pos));BlockState state=read.apply(pos);
        out.addProperty("loaded",state!=null);
        if(state!=null){out.addProperty("state",state.toString());if(state.isAir()&&!airWarning.isBlank())warnings.add(airWarning);}
        return out;
    }
    private static JsonObject position(BlockPos pos) {JsonObject out=new JsonObject();out.addProperty("x",pos.getX());out.addProperty("y",pos.getY());out.addProperty("z",pos.getZ());return out;}
}
