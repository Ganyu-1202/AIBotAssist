package top.goldgom.aibotassist;

import com.google.gson.*;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.*;

/** Deterministic templates, submitted through normal preview/material/protection/build checks. */
public final class MachineBlueprints {
    private MachineBlueprints(){}
    public static Plan create(String kind,String facing){
        List<Plan.Placement> blocks=new ArrayList<>();
        if(kind.equals("auto_smelter")){
            for(int x=-1;x<=1;x++)for(int z=3;z<=5;z++)add(blocks,x,0,z,"stone",Map.of(),facing);
            add(blocks,0,1,3,"chest",Map.of("facing","north"),facing);
            add(blocks,0,1,4,"hopper",Map.of("facing","north"),facing);
            add(blocks,0,2,4,"furnace",Map.of("facing","north"),facing);
            add(blocks,0,3,4,"hopper",Map.of("facing","down"),facing);
            add(blocks,0,4,4,"chest",Map.of("facing","north"),facing);
            add(blocks,1,2,4,"hopper",Map.of("facing","west"),facing);
            add(blocks,1,3,4,"chest",Map.of("facing","east"),facing);
        }else if(kind.equals("repeater_clock")){
            for(int x=0;x<3;x++)for(int z=3;z<6;z++)add(blocks,x,0,z,"stone",Map.of(),facing);
            for(int x:new int[]{0,2})for(int z=3;z<6;z++)add(blocks,x,1,z,"redstone_wire",Map.of(),facing);
            add(blocks,1,1,3,"repeater",Map.of("facing","east","delay","4"),facing);
            add(blocks,1,1,5,"repeater",Map.of("facing","west","delay","4"),facing);
        }else throw new IllegalArgumentException("kind=auto_smelter/repeater_clock");
        return new Plan(kind,blocks);
    }
    private static void add(List<Plan.Placement> b,int x,int y,int z,String id,Map<String,String> properties,String facing){
        int turns=switch(facing){case "south"->0;case "west"->1;case "north"->2;case "east"->3;default->throw new IllegalArgumentException("invalid_facing");};
        Map<String,String> props=new HashMap<>(properties);List<String> directions=List.of("north","east","south","west");
        if(props.containsKey("facing")&&directions.contains(props.get("facing")))props.put("facing",directions.get((directions.indexOf(props.get("facing"))+turns)%4));
        for(int i=0;i<turns;i++){int old=x;x=-z;z=old;}b.add(new Plan.Placement(x,y,z,"minecraft:"+id,props,""));
    }
    public static JsonObject describe(ServerPlayer player,JsonObject a){
        if(a.get("kind").getAsString().equals("auto_crafter"))return crafter(player,a);
        String kind=a.get("kind").getAsString();Plan plan=create(kind,a.has("facing")?a.get("facing").getAsString():"south");JsonObject o=AdvancedTools.ok();o.add("plan",new Gson().toJsonTree(plan));o.addProperty("executed",false);
        o.addProperty("instructions",kind.equals("auto_smelter")?"最高箱放原料，侧箱放熔炉燃料，下箱收输出。先query_recipes验证smelting配方。空机器不会生产，勿把建造完成等同生产验证。":"8刻中继器环默认关闭；用短脉冲启动，长输入会锁成常亮。必须隔tick读取信号确认振荡，拆一段红石可停止。");
        o.addProperty("verification","execute_task/verify_result核对静态方块；inspect_machine比较投入前后库存与红石状态，capture_view校准外观。红石线连接/电量属动态状态，不保证与最初蓝图一致。");return o;
    }
    private static JsonObject crafter(ServerPlayer player,JsonObject a){
        if(!a.has("recipe_id"))return AdvancedTools.error("auto_crafter需要query_recipes返回的recipe_id");
        var holder=player.server.getRecipeManager().byKey(ResourceLocation.parse(a.get("recipe_id").getAsString()));
        if(holder.isEmpty())return AdvancedTools.error("recipe_not_found");var recipe=holder.get().value();
        if(recipe.isSpecial()||!(recipe instanceof ShapedRecipe||recipe instanceof ShapelessRecipe))return AdvancedTools.error("仅支持普通原版工作台配方；模组机器需要专用I/O设计");
        Set<String> common=null;Set<Integer> enabled=new TreeSet<>();var ingredients=recipe.getIngredients();
        for(int i=0;i<ingredients.size();i++){
            var ingredient=ingredients.get(i);if(ingredient.isEmpty())continue;Set<String> choices=new TreeSet<>();
            for(var stack:ingredient.getItems())if(stack.getComponentsPatch().isEmpty()&&!stack.getItem().hasCraftingRemainingItem())choices.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            if(common==null)common=choices;else common.retainAll(choices);
            int slot=recipe instanceof ShapedRecipe shaped?(i/shaped.getWidth())*3+i%shaped.getWidth():i;enabled.add(slot);
        }
        if(common==null||common.isEmpty()||enabled.isEmpty()||enabled.stream().anyMatch(i->i<0||i>8))return AdvancedTools.error("此配方不能可靠用单一原料漏斗供料；需设计分料/锁槽/顺序控制，不能套用通用合成器模板");
        String facing=a.has("facing")?a.get("facing").getAsString():"south";List<Plan.Placement> blocks=new ArrayList<>();
        for(int x=0;x<=4;x++)for(int z=3;z<=5;z++)add(blocks,x,0,z,"stone",Map.of(),facing);
        add(blocks,0,1,3,"chest",Map.of("facing","north"),facing);
        add(blocks,0,1,4,"crafter",Map.of("orientation","north_up"),facing);
        // Orientation is a composite property; rotate it separately from ordinary facing.
        var old=blocks.remove(blocks.size()-1);String output=switch(facing){case "south"->"north";case "west"->"east";case "north"->"south";case "east"->"west";default->throw new IllegalArgumentException();};
        blocks.add(new Plan.Placement(old.x(),old.y(),old.z(),old.block(),Map.of("orientation",output+"_up"),disabledSlots(enabled)));
        add(blocks,0,2,4,"hopper",Map.of("facing","down"),facing);add(blocks,0,3,4,"chest",Map.of("facing","north"),facing);
        add(blocks,1,1,4,"redstone_wire",Map.of(),facing);
        for(int x:new int[]{2,4})for(int z=3;z<=5;z++)add(blocks,x,1,z,"redstone_wire",Map.of(),facing);
        add(blocks,3,1,3,"repeater",Map.of("facing","east","delay","4"),facing);add(blocks,3,1,5,"repeater",Map.of("facing","west","delay","4"),facing);
        JsonObject o=AdvancedTools.ok();o.add("plan",new Gson().toJsonTree(new Plan("单原料自动合成器",blocks)));o.addProperty("executed",false);o.addProperty("recipe_id",holder.get().id().toString());
        JsonArray choices=new JsonArray();common.stream().limit(32).forEach(choices::add);o.add("feed_one_of",choices);o.addProperty("ingredients_per_craft",enabled.size());
        var result=recipe.getResultItem(player.registryAccess());o.addProperty("output_item",BuiltInRegistries.ITEM.getKey(result.getItem()).toString());o.addProperty("output_count",result.getCount());
        o.addProperty("instructions","合成器已禁用空槽，顶箱只放feed_one_of中的一种原料，禁止混料。前箱收成品，侧环用短脉冲启动（默认关闭）。preview_task再execute_task；完成后inspect_machine验证disabled_slots、供料和产出。时钟/产量需实测，不能仅凭蓝图宣称运行。");return o;
    }
    static String disabledSlots(Set<Integer> enabled){
        if(enabled.isEmpty()||enabled.stream().anyMatch(i->i<0||i>8))throw new IllegalArgumentException("invalid_crafter_slots");
        List<String> disabled=new ArrayList<>();for(int i=0;i<9;i++)if(!enabled.contains(i))disabled.add(Integer.toString(i));return "{disabled_slots:[I;"+String.join(",",disabled)+"]}";
    }
}
