package top.goldgom.aibotassist;

import com.google.gson.*;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Server-thread only. Call tick once per server tick; vanilla owns all processing ticks.
 * process_items: {device:"furnace"|"smoker"|"blast_furnace",input:"minecraft:...",
 * count:1..64,fuel:"minecraft:coal"|"minecraft:charcoal",fuel_count:1..64}
 * or {device:"brewing_stand",ingredient:"minecraft:...",potion_slots:[0..35, ...]}.
 * Brewing is ONE vanilla step on 1..3 existing inventory potion stacks, using one ingredient
 * and one player blaze powder (unused native fuel charges remain in the stand).
 * status/cancel require {task_id:"..."}. No arbitrary NBT, output or recipe supplied by caller.
 * clear forgets leases ONLY during server shutdown; it never fabricates refunds or removes blocks.
 */
public final class ProcessingService {
    private ProcessingService() {}
    private static final String TAG = "aibotassist_processing";
    private static final Map<String, Task> TASKS = new LinkedHashMap<>();
    private static boolean hooks, checkingAccess;
    private static final class Task {
        final String id = UUID.randomUUID().toString();
        final UUID owner;
        final net.minecraft.server.level.ServerLevel level;
        final BlockPos pos;
        final BaseContainerBlockEntity device;
        final boolean brewing;
        final List<ItemStack> initial = new ArrayList<>(), expected = new ArrayList<>();
        ItemStack output = ItemStack.EMPTY;
        int count, lastOutput, lastInput, lastFuel, elapsed;
        boolean cancelRequested;
        String status = "running", reason = "原版设备正在逐tick加工";
        Task(ServerPlayer p, BlockPos pos, BaseContainerBlockEntity be, boolean brewing) {
            owner=p.getUUID(); level=p.serverLevel(); this.pos=pos;device=be;this.brewing=brewing;
        }
        boolean leased() { return status.equals("running") || status.equals("waiting_owner") || status.equals("unknown"); }
    }
    private static void require(boolean ok, String reason) { if(!ok)throw new IllegalArgumentException(reason); }
    private static void installHooks() {
        if(hooks)return;
        // Even the owner must use cancellation rather than a menu while materials are leased.
        InteractionEvent.RIGHT_CLICK_BLOCK.register((p,hand,pos,face) -> {
            if(!checkingAccess && TASKS.values().stream().anyMatch(t->t.leased() && t.level==p.level() && t.pos.equals(pos)))
                return EventResult.interruptFalse();
            return EventResult.pass();
        });
        BlockEvent.BREAK.register((level,pos,state,p,xp) -> TASKS.values().stream()
            .anyMatch(t->t.leased() && t.level==level && t.pos.equals(pos)) ? EventResult.interruptFalse() : EventResult.pass());
        hooks=true;
    }
    public static JsonObject handle(ServerPlayer p, String tool, JsonObject args) {
        try {
            require(p.server.isSameThread(),"必须在服务器线程调用");
            installHooks();
            require(args!=null,"args不能为空");
            if(tool.equals("process_items"))return start(p,args);
            require(tool.equals("get_task_status") || tool.equals("cancel_task"),"未知加工工具");
            if(args.has("wait_ticks"))integer(args.get("wait_ticks"),0,1200); // advisory only: never sleep on server thread
            Task t=TASKS.get(text(args,"task_id"));
            require(t!=null && t.owner.equals(p.getUUID()),"任务不存在或不属于你");
            if(tool.equals("cancel_task") && t.leased() && !t.status.equals("unknown")) {
                t.cancelRequested=true;
                inspect(t,p);
                if(t.leased() && !t.status.equals("unknown"))finish(t,p,"cancelled");
            }
            return describe(t);
        } catch(IllegalArgumentException e) { return error(e.getMessage()); }
        catch(Exception e) {
            org.slf4j.LoggerFactory.getLogger("aibotassist").error("Processing request failed",e);
            for(Task t:TASKS.values())if(t.owner.equals(p.getUUID()) && t.leased())return describe(t);
            return error("加工调用异常；不要重复提交，请先检查设备现场");
        }
    }
    private static JsonObject start(ServerPlayer p,JsonObject a) {
        require(p.isAlive() && !p.isSpectator() && p.mayBuild(),"当前玩家状态不允许加工");
        require(TASKS.size()<128,"加工任务记录已达上限；服务器重启后清理，不重复执行旧任务");
        require(TASKS.values().stream().noneMatch(t->t.owner.equals(p.getUUID()) && t.leased()),"已有加工任务或未知状态任务，请先查询");
        String mode=text(a,"device");
        Block block=switch(mode) {case "furnace"->Blocks.FURNACE;case "smoker"->Blocks.SMOKER;
            case "blast_furnace"->Blocks.BLAST_FURNACE;case "brewing_stand"->Blocks.BREWING_STAND;
            default->throw new IllegalArgumentException("device必须是furnace/smoker/blast_furnace/brewing_stand");};
        boolean brewing=block==Blocks.BREWING_STAND;
        List<ItemStack> inputs=new ArrayList<>();
        List<Integer> slots=new ArrayList<>();
        ItemStack output=ItemStack.EMPTY;
        int count=0;
        if(brewing) {
            Item ingredient=item(text(a,"ingredient"));
            require(a.has("potion_slots") && a.get("potion_slots").isJsonArray(),"酿造必须指定potion_slots库存槽号数组");
            JsonArray potions=a.getAsJsonArray("potion_slots");
            require(potions.size()>=1 && potions.size()<=3,"每步酿造需要1至3瓶");
            Set<Integer> distinct=new HashSet<>();
            var mixes=p.serverLevel().potionBrewing();
            for(var v:potions) {
                int slot=integer(v,0,35);
                require(distinct.add(slot),"potion_slots不可重复");
                ItemStack s=p.getInventory().getItem(slot);
                require(s.getCount()==1 && (s.is(Items.POTION)||s.is(Items.SPLASH_POTION)||s.is(Items.LINGERING_POTION)),"指定槽必须是单瓶原版药水（包括水瓶）");
                require(s.get(DataComponents.CUSTOM_DATA)==null,"不接受带自定义数据的药水");
                require(mixes.hasMix(s,new ItemStack(ingredient)),"该药水与ingredient没有原版酿造配方");
                inputs.add(s.copy());slots.add(slot);
            }
            while(inputs.size()<3)inputs.add(ItemStack.EMPTY);
            inputs.add(new ItemStack(ingredient));inputs.add(new ItemStack(Items.BLAZE_POWDER));
            count=potions.size();
        } else {
            count=integer(a.get("count"),1,64);
            Item in=item(text(a,"input")), fuel=item(text(a,"fuel"));
            int fuels=integer(a.get("fuel_count"),1,64);
            require(fuel==Items.COAL || fuel==Items.CHARCOAL,"安全燃料仅支持coal/charcoal，不接受桶或其它容器余物燃料");
            ItemStack input=new ItemStack(in,count);
            require(count<=input.getMaxStackSize(),"输入超过单槽堆叠上限");
            RecipeType<? extends AbstractCookingRecipe> type=block==Blocks.SMOKER?RecipeType.SMOKING:block==Blocks.BLAST_FURNACE?RecipeType.BLASTING:RecipeType.SMELTING;
            AbstractCookingRecipe recipe=p.server.getRecipeManager().getAllRecipesFor(type).stream()
                .map(RecipeHolder::value).filter(r->r.matches(new SingleRecipeInput(input),p.serverLevel())).findFirst()
                .orElseThrow(()->new IllegalArgumentException("此设备没有匹配的原版/服务器加工配方"));
            output=recipe.assemble(new SingleRecipeInput(input),p.registryAccess());
            require(!output.isEmpty() && output.getCount()==1 && count<=output.getMaxStackSize(),"仅接受单次产量1且整批不超过单槽的加工配方");
            int burn=AbstractFurnaceBlockEntity.getFuel().getOrDefault(fuel,0)/(block==Blocks.FURNACE?1:2);
            require((long)burn*fuels>=(long)recipe.getCookingTime()*count,"fuel_count不足以完成本批原版加工");
            inputs.add(input); inputs.add(new ItemStack(fuel,fuels));inputs.add(ItemStack.EMPTY);
        }
        // Validate ALL consumables before creating/crafting any equipment.
        verifyStock(p,inputs,slots,brewing);
        BlockPos pos=findDevice(p,block);
        if(pos==null)pos=prepareDevice(p,block,inputs,slots,brewing);
        require(access(p,pos),"设备访问权限被拒绝；未投入加工物料");
        require(p.serverLevel().getBlockState(pos).is(block) && p.serverLevel().getBlockEntity(pos) instanceof BaseContainerBlockEntity,"设备已被改变");
        var be=(BaseContainerBlockEntity)p.serverLevel().getBlockEntity(pos);
        require(be!=null && idle(p,be),"设备在准备后已不可用；未投入加工物料");
        verifyStock(p,inputs,slots,brewing);
        Task t=new Task(p,pos,be,brewing);t.count=count;t.output=output.copy();
        for(ItemStack s:inputs)t.initial.add(s.copy());
        if(brewing)for(int i=0;i<3;i++) {
            ItemStack s=inputs.get(i);
            t.expected.add(s.isEmpty()?ItemStack.EMPTY:p.serverLevel().potionBrewing().mix(inputs.get(3),s).copy());
        }
        // Register before first mutation: an unexpected exception must never permit blind retry.
        TASKS.put(t.id,t);
        try {
            withdraw(p,inputs,slots,brewing);
            for(int i=0;i<inputs.size();i++) {
                ItemStack s=inputs.get(i).copy();
                if(!s.isEmpty())mark(s,t.id);
                be.setItem(i,s);
            }
            be.setChanged();
            t.lastInput=brewing?1:count;t.lastFuel=inputs.get(brewing?4:1).getCount();
        } catch(Exception e) { unknown(t,"投入阶段异常；已登记，不自动重试或猜测退款");throw e; }
        return describe(t);
    }
    private static String text(JsonObject a,String key) {
        require(a.has(key) && a.get(key).isJsonPrimitive() && a.getAsJsonPrimitive(key).isString(),"缺少字符串参数 "+key);
        return a.get(key).getAsString();
    }
    private static int integer(JsonElement e,int min,int max) {
        require(e!=null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber(),"需要整数参数");
        try { int n=e.getAsBigDecimal().intValueExact(); require(n>=min && n<=max,"整数参数超出范围 "+min+".."+max);return n; }
        catch(ArithmeticException ex) {throw new IllegalArgumentException("参数必须是整数");}
    }
    private static Item item(String id) {
        ResourceLocation key=ResourceLocation.tryParse(id);
        require(key!=null && key.getNamespace().equals("minecraft") && BuiltInRegistries.ITEM.containsKey(key),"需要已注册的minecraft物品ID");
        Item i=BuiltInRegistries.ITEM.get(key);require(i!=Items.AIR,"不接受air");return i;
    }
    private static Map<Item,Integer> needs(List<ItemStack> inputs,boolean brewing) {
        Map<Item,Integer> n=new HashMap<>();
        for(int i=brewing?3:0;i<inputs.size();i++) {ItemStack s=inputs.get(i);if(!s.isEmpty())n.merge(s.getItem(),s.getCount(),Integer::sum);}
        return n;
    }
    private static void verifyStock(ServerPlayer p,List<ItemStack> inputs,List<Integer> slots,boolean brewing) {
        if(brewing)for(int i=0;i<slots.size();i++)require(ItemStack.matches(p.getInventory().getItem(slots.get(i)),inputs.get(i)),"药水槽发生变化");
        for(var need:needs(inputs,brewing).entrySet()) {
            int available=0;for(int i=0;i<36;i++) {var s=p.getInventory().getItem(i);if(s.is(need.getKey()) && s.getComponentsPatch().isEmpty())available+=s.getCount();}
            require(available>=need.getValue(),"玩家缺少普通物料 "+BuiltInRegistries.ITEM.getKey(need.getKey()));
        }
    }
    private static void take(ServerPlayer p,Item item,int n) {
        for(int i=0;i<36 && n>0;i++) {var s=p.getInventory().getItem(i);if(s.is(item)&&s.getComponentsPatch().isEmpty()){int k=Math.min(n,s.getCount());s.shrink(k);n-=k;}}
        require(n==0,"预检后物料变化");p.getInventory().setChanged();
    }
    private static void withdraw(ServerPlayer p,List<ItemStack> inputs,List<Integer> slots,boolean brewing) {
        if(brewing)for(int slot:slots)p.getInventory().setItem(slot,ItemStack.EMPTY);
        needs(inputs,brewing).forEach((item,n)->take(p,item,n));
    }
    private static boolean access(ServerPlayer p,BlockPos pos) {
        var l=p.serverLevel();
        if(!p.isAlive() || !p.mayBuild() || p.isSpectator() || p.blockPosition().distSqr(pos)>16
            || l.isOutsideBuildHeight(pos) || !l.hasChunkAt(pos) || !l.getWorldBorder().isWithinBounds(pos) || !l.mayInteract(p,pos))return false;
        checkingAccess=true;
        try {return !InteractionEvent.RIGHT_CLICK_BLOCK.invoker().click(p,InteractionHand.MAIN_HAND,pos,Direction.UP).isFalse();}
        finally {checkingAccess=false;}
    }
    private static boolean isolated(net.minecraft.server.level.ServerLevel l,BlockPos pos,BaseContainerBlockEntity be) {
        // Reject all adjacent inventories, not just hoppers (also conservative for modded pipes).
        for(Direction d:Direction.values()) {
            BlockPos n=pos.relative(d);
            if(!l.hasChunkAt(n) || l.getBlockEntity(n) instanceof Container || l.getBlockState(n).is(Blocks.HOPPER))return false;
        }
        if(!l.getEntities((net.minecraft.world.entity.Entity)null,new AABB(pos).inflate(2),e->e instanceof Container).isEmpty())return false;
        // A menu opened before this lease must not retain access.
        for(var p:l.getServer().getPlayerList().getPlayers())
            if(p.containerMenu.slots.stream().anyMatch(s->s.container==be))return false;
        return true;
    }
    private static boolean idle(ServerPlayer p,BaseContainerBlockEntity be) {
        if(!be.isEmpty() || !be.canOpen(p) || !isolated(p.serverLevel(),be.getBlockPos(),be))return false;
        if(TASKS.values().stream().anyMatch(t->t.leased() && t.level==p.serverLevel() && t.pos.equals(be.getBlockPos())))return false;
        CompoundTag n=be.saveWithoutMetadata(p.registryAccess());
        // Do not spend someone else's residual furnace heat / blaze-powder charge.
        return n.getInt("BurnTime")==0 && n.getInt("CookTime")==0 && n.getInt("BrewTime")==0 && n.getInt("Fuel")==0;
    }
    private static BlockPos findDevice(ServerPlayer p,Block block) {
        for(BlockPos c:BlockPos.betweenClosed(p.blockPosition().offset(-4,-4,-4),p.blockPosition().offset(4,4,4))) {
            BlockPos pos=c.immutable();if(!access(p,pos))continue;
            if(p.serverLevel().getBlockState(pos).is(block) && p.serverLevel().getBlockEntity(pos) instanceof BaseContainerBlockEntity be && idle(p,be))return pos;
        }
        return null;
    }
    private static boolean placeable(ServerPlayer p,BlockPos pos,Block block) {
        var l=p.serverLevel();var state=block.defaultBlockState();
        if(!access(p,pos) || !l.getBlockState(pos).isAir() || !l.getFluidState(pos).isEmpty() || l.getBlockEntity(pos)!=null
            || !l.getBlockState(pos.below()).isFaceSturdy(l,pos.below(),Direction.UP) || !state.canSurvive(l,pos))return false;
        var shape=state.getCollisionShape(l,pos);
        if(!shape.isEmpty() && (!l.getEntities((net.minecraft.world.entity.Entity)null,shape.bounds().move(pos),e->!e.isSpectator()).isEmpty()))return false;
        for(Direction d:Direction.values())if(l.getBlockEntity(pos.relative(d)) instanceof Container)return false;
        if(BlockEvent.PLACE.invoker().placeBlock(l,pos,state,p).isFalse())return false;
        // Event callbacks can themselves change the target. Never overwrite their result.
        return l.getBlockState(pos).isAir() && l.getFluidState(pos).isEmpty() && l.getBlockEntity(pos)==null;
    }
    private static BlockPos placement(ServerPlayer p,Block block,BlockPos exclude) {
        for(BlockPos c:BlockPos.betweenClosed(p.blockPosition().offset(-4,-2,-4),p.blockPosition().offset(4,2,4))) {
            BlockPos pos=c.immutable();if(!pos.equals(exclude) && placeable(p,pos,block))return pos;
        }
        throw new IllegalArgumentException("附近4格没有受权限/区域事件允许的安全设备放置位置");
    }
    private static BlockPos prepareDevice(ServerPlayer p,Block block,List<ItemStack> inputs,List<Integer> slots,boolean brewing) {
        BlockPos pos=placement(p,block,null),tablePos=null;
        Map<String,Integer> goals=new TreeMap<>();
        needs(inputs,brewing).forEach((i,n)->goals.put(BuiltInRegistries.ITEM.getKey(i).toString(),n));
        goals.merge(BuiltInRegistries.ITEM.getKey(block.asItem()).toString(),1,Integer::sum);
        AutoCrafting.Result result=AutoCrafting.prepare(p,goals);
        if(result.needsTable() && AutoCrafting.findTable(p)==null) {
            tablePos=placement(p,Blocks.CRAFTING_TABLE,pos);
            goals.merge("minecraft:crafting_table",1,Integer::sum);
            result=AutoCrafting.prepare(p,goals);
        }
        // AutoCrafting may repack inventory. Record exact potion identities and relocate after commit.
        require(placeable(p,pos,block) && (tablePos==null || placeable(p,tablePos,Blocks.CRAFTING_TABLE)),"放置区域状态改变");
        AutoCrafting.commit(p,result);
        if(brewing) {
            Set<Integer> used=new HashSet<>();
            for(int k=0;k<slots.size();k++) {
                int found=-1;for(int i=0;i<36;i++)if(!used.contains(i)&&ItemStack.matches(p.getInventory().getItem(i),inputs.get(k))){found=i;break;}
                require(found>=0,"合成后药水不在库存中；未投入加工");slots.set(k,found);used.add(found);
            }
        }
        if(tablePos!=null)place(p,tablePos,Blocks.CRAFTING_TABLE);
        place(p,pos,block);return pos;
    }
    private static void place(ServerPlayer p,BlockPos pos,Block block) {
        require(placeable(p,pos,block),"放置前安全检查拒绝");
        // Pay before world mutation; no elevated commands and no creative material bypass.
        take(p,block.asItem(),1);
        boolean placed=false;
        try {placed=p.serverLevel().setBlock(pos,block.defaultBlockState(),3);}
        finally {
            if(!placed && p.serverLevel().getBlockState(pos).isAir())give(p,new ItemStack(block.asItem()));
        }
        require(placed && p.serverLevel().getBlockState(pos).is(block),"设备放置结果不确定，不重复放置");
    }
    private static void mark(ItemStack s,String id) {
        CompoundTag n=new CompoundTag();n.putString(TAG,id);s.set(DataComponents.CUSTOM_DATA,CustomData.of(n));
    }
    private static boolean marked(ItemStack s,String id) {
        var n=s.get(DataComponents.CUSTOM_DATA);return n!=null && n.copyTag().size()==1 && id.equals(n.copyTag().getString(TAG));
    }
    private static ItemStack clean(ItemStack s) {ItemStack c=s.copy();c.remove(DataComponents.CUSTOM_DATA);return c;}
    private static boolean owned(ItemStack actual,ItemStack initial,String id) {
        return !actual.isEmpty() && marked(actual,id) && ItemStack.isSameItemSameComponents(clean(actual),initial);
    }
    private static void unknown(Task t,String why) {t.status="unknown";t.reason=why+"；保留设备现场，不取物、不猜测、不重试";}
    private static void inspect(Task t,ServerPlayer p) {
        if(t.status.equals("unknown"))return;
        if(!t.level.hasChunkAt(t.pos)) {t.reason="区块未加载，等待原版设备恢复；不强制加载";return;}
        if(t.level.getBlockEntity(t.pos)!=t.device || t.device.isRemoved() || !isolated(t.level,t.pos,t.device)) {
            unknown(t,"设备被替换、接入自动化或出现外部菜单访问");return;
        }
        var be=t.device;
        if(t.brewing) {
            ItemStack ingredient=be.getItem(3),fuel=be.getItem(4);
            boolean remainder=ingredient.is(Items.GLASS_BOTTLE) && t.initial.get(3).is(Items.DRAGON_BREATH)
                && ingredient.getCount()==1 && ingredient.getComponentsPatch().isEmpty();
            if(!ingredient.isEmpty() && !remainder && (!owned(ingredient,t.initial.get(3),t.id)||ingredient.getCount()!=1)) {unknown(t,"酿造原料来源不符");return;}
            if(!fuel.isEmpty() && (!owned(fuel,t.initial.get(4),t.id)||fuel.getCount()>t.lastFuel)) {unknown(t,"酿造燃料来源不符");return;}
            boolean done=ingredient.isEmpty();
            // Some vanilla brewing ingredients leave a container (dragon breath -> glass bottle).
            if(remainder)done=true;
            for(int i=0;i<3;i++) {
                var actual=be.getItem(i);var original=t.initial.get(i);
                if(original.isEmpty()) {if(!actual.isEmpty()){unknown(t,"空药水槽被外部写入");return;}continue;}
                ItemStack wanted=done?t.expected.get(i):original;
                // Vanilla PotionBrewing.mix creates a NEW stack, discarding the input custom-data marker.
                boolean valid=done ? ItemStack.matches(actual,wanted)
                    : marked(actual,t.id) && ItemStack.isSameItemSameComponents(clean(actual),wanted);
                if(actual.getCount()!=1 || !valid) {unknown(t,"药水状态不符合单步原版酿造");return;}
            }
            t.lastFuel=fuel.getCount();t.lastInput=done?0:1;
            if(done && p!=null && canReturn(p,t))finish(t,p,t.cancelRequested?"cancelled":"completed");
        } else {
            var input=be.getItem(0);var fuel=be.getItem(1);var output=be.getItem(2);
            if(!input.isEmpty() && !owned(input,t.initial.get(0),t.id)) {unknown(t,"输入不再带有本任务来源标记");return;}
            if(!fuel.isEmpty() && !owned(fuel,t.initial.get(1),t.id)) {unknown(t,"燃料不再带有本任务来源标记");return;}
            if(!output.isEmpty() && !ItemStack.isSameItemSameComponents(output,t.output)) {unknown(t,"产物与原版配方不符");return;}
            int in=input.getCount(),out=output.getCount();
            if(in>t.lastInput || out<t.lastOutput || fuel.getCount()>t.lastFuel || out!=(t.count-in)*t.output.getCount()) {unknown(t,"设备物料守恒/单向进度校验失败");return;}
            t.lastInput=in;t.lastOutput=out;t.lastFuel=fuel.getCount();
            if(in==0 && p!=null && canReturn(p,t))finish(t,p,t.cancelRequested?"cancelled":"completed");
        }
    }
    private static boolean canReturn(ServerPlayer p,Task t) {return p.serverLevel()==t.level && access(p,t.pos) && t.device.canOpen(p);}
    private static void finish(Task t,ServerPlayer p,String status) {
        if(t.status.equals("unknown"))return;
        List<ItemStack> proven=new ArrayList<>();
        for(int i=0;i<t.device.getContainerSize();i++)proven.add(t.device.getItem(i).copy());
        if(!t.level.hasChunkAt(t.pos) || !canReturn(p,t)) {t.status="waiting_owner";t.reason="等待拥有者在线并回到设备4格内、恢复权限后归还";return;}
        if(t.level.getBlockEntity(t.pos)!=t.device || !isolated(t.level,t.pos,t.device)) {unknown(t,"归还检查期间设备环境改变");return;}
        for(int i=0;i<proven.size();i++)if(!ItemStack.matches(proven.get(i),t.device.getItem(i))) {unknown(t,"归还权限回调期间物料改变");return;}
        // inspect has just proven the complete batch, including exact vanilla outputs.
        for(int i=0;i<t.device.getContainerSize();i++) {
            ItemStack s=t.device.removeItemNoUpdate(i);
            if(marked(s,t.id))s=clean(s);
            give(p,s);
        }
        t.device.setChanged();t.status=status;t.reason=status.equals("completed")?"原版加工完成；产物及未消耗物料已归还":"已取消；仅归还可证明属于任务的现存物料，已燃烧燃料/酿造燃料储量不退款";
    }
    private static void give(ServerPlayer p,ItemStack s) {if(s.isEmpty())return;if(!p.getInventory().add(s))p.drop(s,false);p.getInventory().setChanged();}
    public static void tick(MinecraftServer server) {
        if(!server.isSameThread())throw new IllegalStateException("ProcessingService.tick requires server thread");
        for(Task t:TASKS.values()) {
            if(!t.leased() || t.status.equals("unknown"))continue;
            ServerPlayer p=server.getPlayerList().getPlayer(t.owner);
            try {
                inspect(t,p);
                if(t.status.equals("unknown"))continue;
                if(t.leased() && t.cancelRequested && p!=null)finish(t,p,"cancelled");
                else if(++t.elapsed>20*60*30) {t.cancelRequested=true;t.reason="任务超过30分钟，等待安全取消归还";}
            } catch(Exception ex) {unknown(t,"监测或归还阶段异常");org.slf4j.LoggerFactory.getLogger("aibotassist").error("Processing task {} stopped",t.id,ex);}
        }
    }
    public static void clear() {TASKS.clear();}
    private static JsonObject error(String why) {JsonObject o=new JsonObject();o.addProperty("status","rejected");o.addProperty("reason",why);return o;}
    private static JsonObject describe(Task t) {
        JsonObject o=new JsonObject();o.addProperty("task_id",t.id);o.addProperty("status",t.status);o.addProperty("reason",t.reason);
        o.addProperty("dimension",t.level.dimension().location().toString());o.addProperty("x",t.pos.getX());o.addProperty("y",t.pos.getY());o.addProperty("z",t.pos.getZ());
        o.addProperty("device",BuiltInRegistries.BLOCK.getKey(t.device.getBlockState().getBlock()).toString());
        o.addProperty("remaining_input",t.lastInput);o.addProperty("observed_output",t.lastOutput);o.addProperty("cancel_requested",t.cancelRequested);
        o.addProperty("persistent",false);o.addProperty("notice","任务仅保存在本次服务器运行内；重启/clear后不认领旧设备物料。设备、合成台、余热和酿造燃料储量留在世界。");return o;
    }
}
