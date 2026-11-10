package top.goldgom.aibotassist;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Bounded recipe search on a copy. No world/inventory effects until the whole plan succeeds. */
public final class AutoCrafting {
    public record Result(List<ItemStack> inventory, boolean needsTable, int crafts) {}
    private final ServerPlayer player;
    private List<ItemStack> stock;
    private final Set<Item> path = new HashSet<>();
    private boolean table;
    private int attempts, crafts;
    private AutoCrafting(ServerPlayer p) {
        player=p; stock=new ArrayList<>();
        for(int i=0;i<36;i++) stock.add(p.getInventory().getItem(i).copy());
    }
    public static Result prepare(ServerPlayer p, Map<String,Integer> goals) {
        AutoCrafting planner=new AutoCrafting(p);
        for(var goal:goals.entrySet()) {
            Item item=BuiltInRegistries.ITEM.get(ResourceLocation.parse(goal.getKey()));
            if(!planner.ensure(item,goal.getValue(),0))
                throw new IllegalArgumentException("已有材料无法合成足量 " + goal.getKey() + "；可能缺少原料、可用配方或需要加工设备");
            // Reserve each goal so later recipes cannot consume already promised materials.
            planner.take(item,goal.getValue());
        }
        for(var goal:goals.entrySet()) planner.add(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(goal.getKey())),goal.getValue()));
        return new Result(List.copyOf(planner.stock),planner.table,planner.crafts);
    }
    private boolean plain(ItemStack s) { return !s.isEmpty() && s.getComponentsPatch().isEmpty(); }
    private int count(Item item) { return stock.stream().filter(s->plain(s)&&s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private void take(Item item,int amount) {
        for(var s:stock) if(plain(s)&&s.is(item)) { int n=Math.min(amount,s.getCount());s.shrink(n);amount-=n;if(amount==0)return; }
        if(amount!=0)throw new IllegalStateException("合成预留库存不足");
    }
    private void add(ItemStack s) { if(!s.isEmpty())stock.add(s.copy()); }
    private List<ItemStack> copy() { return stock.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new)); }
    private boolean ensure(Item wanted,int count,int depth) {
        if(count(wanted)>=count)return true;
        if(depth>10 || attempts>2048 || crafts>512 || !path.add(wanted))return false;
        try {
            for(var holder:player.server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
                CraftingRecipe recipe=holder.value();
                if(recipe.isSpecial() || !(recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe))continue;
                ItemStack output=recipe.getResultItem(player.registryAccess());
                if(output.isEmpty() || !output.is(wanted) || !output.getComponentsPatch().isEmpty())continue;
                var backup=copy(); boolean oldTable=table;int oldCrafts=crafts;
                boolean success=true;
                while(count(wanted)<count) {
                    if(++attempts>2048 || ++crafts>512) {success=false;break;}
                    var ingredients=recipe.getIngredients();
                    int width=recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : Math.min(3,ingredients.size());
                    int height=recipe instanceof ShapedRecipe shaped ? shaped.getHeight() : (ingredients.size()+2)/3;
                    List<ItemStack> grid=new ArrayList<>(Collections.nCopies(width*height,ItemStack.EMPTY));
                    for(int i=0;i<ingredients.size();i++) {
                        var ingredient=ingredients.get(i); if(ingredient.isEmpty())continue;
                        ItemStack chosen=ItemStack.EMPTY;
                        var choices=new ArrayList<>(Arrays.asList(ingredient.getItems()));
                        choices.sort(Comparator.comparingInt(c->count(c.getItem())>0?0:1));
                        for(var option:choices) {
                            var branch=copy();boolean branchTable=table;int branchCrafts=crafts;
                            if(ensure(option.getItem(),1,depth+1)) { chosen=new ItemStack(option.getItem());take(option.getItem(),1);break; }
                            stock=branch;table=branchTable;crafts=branchCrafts;
                        }
                        if(chosen.isEmpty()) {success=false;break;}
                        grid.set(i,chosen);
                    }
                    if(!success)break;
                    var input=CraftingInput.of(width,height,grid);
                    if(!recipe.matches(input,player.serverLevel())) {success=false;break;}
                    ItemStack assembled=recipe.assemble(input,player.registryAccess());
                    if(assembled.isEmpty() || !assembled.is(wanted)) {success=false;break;}
                    recipe.getRemainingItems(input).forEach(this::add);
                    add(assembled);
                    if(!recipe.canCraftInDimensions(2,2))table=true;
                }
                if(success)return true;
                stock=backup;table=oldTable;crafts=oldCrafts;
            }
            return false;
        } finally {path.remove(wanted);}
    }
    public static BlockPos findTable(ServerPlayer p) {
        BlockPos base=p.blockPosition();
        for(BlockPos pos:BlockPos.betweenClosed(base.offset(-4,-2,-4),base.offset(4,2,4))) {
            if(p.serverLevel().hasChunkAt(pos) && p.serverLevel().getBlockState(pos).is(Blocks.CRAFTING_TABLE)
                && p.serverLevel().mayInteract(p,pos))return pos.immutable();
        }
        return null;
    }
    /** Preserve every simulated remainder; surplus goes to inventory or is dropped, never deleted. */
    public static void commit(ServerPlayer p,Result result) {
        for(int i=0;i<36;i++)p.getInventory().setItem(i,ItemStack.EMPTY);
        for(var stack:result.inventory()) {
            ItemStack rest=stack.copy();
            while(!rest.isEmpty()) {
                ItemStack portion=rest.split(Math.min(rest.getCount(),rest.getMaxStackSize()));
                if(!p.getInventory().add(portion))p.drop(portion,false);
            }
        }
        p.getInventory().setChanged();
    }
}
