package top.goldgom.aibotassist;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.architectury.event.events.common.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.*;

/** Server-thread, owner-bound creative actors. No player impersonation or operator grants. */
public final class FakePlayerService {
    private static final class Actor {
        final ServerPlayer owner,player;long expires;Vec3 movement=Vec3.ZERO;int ticks;
        Actor(ServerPlayer owner,ServerPlayer player){this.owner=owner;this.player=player;touch();}
        void touch(){expires=System.currentTimeMillis()+600_000;}
    }
    private static final Map<UUID,Actor> actors=new HashMap<>();
    private FakePlayerService(){}
    public static JsonObject handle(ServerPlayer owner,String tool,JsonObject a){
        if(!AdvancedTools.admin(owner))return AdvancedTools.error("假人与远程取景需要存档所有者或服务器OP权限");
        try{return tool.equals("capture_fakeplayer")?capture(owner,a):control(owner,a);}
        catch(IllegalArgumentException e){return AdvancedTools.error(e.getMessage());}
    }
    private static ServerLevel level(ServerPlayer owner,JsonObject a,ServerLevel fallback){
        if(!a.has("dimension"))return fallback;
        var key=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(a.get("dimension").getAsString()));
        ServerLevel level=owner.server.getLevel(key);if(level==null)throw new IllegalArgumentException("维度不存在");return level;
    }
    private static void checkPosition(ServerLevel level,BlockPos pos,boolean load){
        if(!level.getWorldBorder().isWithinBounds(pos)||level.isOutsideBuildHeight(pos))throw new IllegalArgumentException("假人位置超出世界边界或高度");
        if(!level.hasChunkAt(pos)){if(!load)throw new IllegalArgumentException("目标区块未加载；需明确指定load_chunks=true，可能加载或生成区块");level.getChunkAt(pos);}
    }
    private static ServerPlayer make(ServerLevel level,UUID owner){
        var profile=new GameProfile(UUID.randomUUID(),"AI_"+owner.toString().substring(0,8));
        ServerPlayer fake=FakePlayers.create(level,profile);fake.setGameMode(GameType.CREATIVE);
        fake.getAbilities().flying=true;fake.setNoGravity(true);fake.setInvulnerable(true);fake.addTag("aibotassist_fakeplayer");return fake;
    }
    private static JsonObject control(ServerPlayer owner,JsonObject a){
        String action=a.get("action").getAsString();Actor actor=actors.get(owner.getUUID());
        if(action.equals("spawn")){
            if(actor!=null)throw new IllegalArgumentException("你已有假人，请使用teleport或remove");
            if(actors.size()>=8)throw new IllegalArgumentException("全服假人数量已达8个");
            var spec=RemoteViewSpec.parse(a,owner.getX(),owner.getY(),owner.getZ(),owner.getYRot(),owner.getXRot());
            ServerLevel level=level(owner,a,owner.serverLevel());checkPosition(level,BlockPos.containing(spec.x(),spec.y(),spec.z()),spec.loadChunks());
            ServerPlayer fake=make(level,owner.getUUID());fake.moveTo(spec.x(),spec.y(),spec.z(),spec.yaw(),spec.pitch());
            try{
                owner.server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fake)));
                level.addNewPlayer(fake);actor=new Actor(owner,fake);actors.put(owner.getUUID(),actor);
            }catch(Exception e){fake.discard();owner.server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(fake.getUUID())));throw e;}
        }else if(action.equals("remove")){remove(owner.getUUID());return AdvancedTools.ok();}
        else{
            if(actor==null)throw new IllegalArgumentException("尚未创建假人，请先spawn");
            ServerPlayer fake=actor.player;actor.touch();
            switch(action){
                case "status" -> {}
                case "stop" -> {actor.movement=Vec3.ZERO;actor.ticks=0;fake.stopUsingItem();fake.setShiftKeyDown(false);}
                case "look" -> {var s=RemoteViewSpec.parse(a,fake.getX(),fake.getY(),fake.getZ(),fake.getYRot(),fake.getXRot());fake.setYRot(s.yaw());fake.setYHeadRot(s.yaw());fake.setXRot(s.pitch());}
                case "teleport" -> {
                    var s=RemoteViewSpec.parse(a,fake.getX(),fake.getY(),fake.getZ(),fake.getYRot(),fake.getXRot());
                    if(level(owner,a,fake.serverLevel())!=fake.serverLevel())throw new IllegalArgumentException("跨维度请remove后在目标维度spawn");
                    checkPosition(fake.serverLevel(),BlockPos.containing(s.x(),s.y(),s.z()),s.loadChunks());
                    fake.stopUsingItem();actor.ticks=0;fake.moveTo(s.x(),s.y(),s.z(),s.yaw(),s.pitch());fake.setDeltaMovement(Vec3.ZERO);
                }
                case "move" -> {
                    Vec3 v=new Vec3(RemoteViewSpec.number(a,"dx",0),RemoteViewSpec.number(a,"dy",0),RemoteViewSpec.number(a,"dz",0));
                    int ticks=a.has("ticks")?a.get("ticks").getAsBigDecimal().intValueExact():20;
                    if(v.length()>0.4||ticks<1||ticks>200)throw new IllegalArgumentException("每tick位移长度最多0.4，ticks必须1到200");
                    actor.movement=v;actor.ticks=ticks;
                }
                case "sneak" -> fake.setShiftKeyDown(a.get("enabled").getAsBoolean());
                case "equip" -> {
                    ResourceLocation id=ResourceLocation.parse(a.get("item").getAsString());
                    if(!BuiltInRegistries.ITEM.containsKey(id))throw new IllegalArgumentException("物品ID不存在");
                    Item item=BuiltInRegistries.ITEM.get(id);if(item instanceof BlockItem block)BlockPolicy.check(owner,BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString(),false);
                    int count=a.has("count")?a.get("count").getAsBigDecimal().intValueExact():1;if(count<1||count>64)throw new IllegalArgumentException("count必须1到64");
                    fake.stopUsingItem();fake.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(item,Math.min(count,item.getDefaultMaxStackSize())));
                }
                case "use_block","break" -> {
                    BlockPos pos=position(a);ServerLevel level=fake.serverLevel();checkPosition(level,pos,false);
                    if(fake.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos))>36)throw new IllegalArgumentException("目标必须在假人眼睛6格以内");
                    if(!level.mayInteract(owner,pos)||!level.mayInteract(fake,pos))throw new IllegalArgumentException("出生点或区域保护拒绝操作");
                    Direction face=a.has("face")?Direction.byName(a.get("face").getAsString()):null;
                    if(a.has("face")&&face==null)throw new IllegalArgumentException("face无效");
                    Vec3 aim=Vec3.atCenterOf(pos);if(face!=null)aim=aim.add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.499));
                    BlockHitResult hit=level.clip(new ClipContext(fake.getEyePosition(),aim,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,fake));
                    if(hit.getType()!=HitResult.Type.BLOCK||!hit.getBlockPos().equals(pos)||face!=null&&hit.getDirection()!=face)throw new IllegalArgumentException("目标或指定面被方块遮挡，请调整假人位置");
                    face=hit.getDirection();fake.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,hit.getLocation());fake.setYHeadRot(fake.getYRot());
                    var state=level.getBlockState(pos);
                    if(action.equals("break")){
                        BlockPolicy.check(owner,BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),true);
                        if(BlockEvent.BREAK.invoker().breakBlock(level,pos,state,owner,null).isFalse()||BlockEvent.BREAK.invoker().breakBlock(level,pos,state,fake,null).isFalse())throw new IllegalArgumentException("保护模组拒绝假人破坏");
                        if(!fake.gameMode.destroyBlock(pos))throw new IllegalArgumentException("方块未被破坏");
                    }else{
                        if(!level.getWorldBorder().isWithinBounds(pos.relative(face))||!level.mayInteract(owner,pos.relative(face))||!level.mayInteract(fake,pos.relative(face)))throw new IllegalArgumentException("相邻放置位置受保护");
                        if(fake.getMainHandItem().getItem() instanceof BlockItem block)BlockPolicy.check(owner,BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString(),false);
                        if(InteractionEvent.RIGHT_CLICK_BLOCK.invoker().click(owner,InteractionHand.MAIN_HAND,pos,face).isFalse()||InteractionEvent.RIGHT_CLICK_BLOCK.invoker().click(fake,InteractionHand.MAIN_HAND,pos,face).isFalse())throw new IllegalArgumentException("保护模组拒绝假人交互");
                        var result=fake.gameMode.useItemOn(fake,level,fake.getMainHandItem(),InteractionHand.MAIN_HAND,hit);
                        JsonObject response=status(actor);response.addProperty("interaction_result",result.name());return response;
                    }
                    fake.swing(InteractionHand.MAIN_HAND);
                }
                case "attack" -> {
                    Entity target=fake.serverLevel().getEntity(a.get("entity_id").getAsInt());
                    if(target==null||target instanceof Player||fake.distanceToSqr(target)>36||!fake.hasLineOfSight(target))throw new IllegalArgumentException("只能攻击假人6格内的非玩家实体");
                    if(!fake.serverLevel().mayInteract(owner,target.blockPosition()))throw new IllegalArgumentException("区域保护拒绝操作");
                    fake.attack(target);fake.swing(InteractionHand.MAIN_HAND);
                }
                default -> throw new IllegalArgumentException("不支持的假人action");
            }
        }
        return status(actor);
    }
    private static BlockPos position(JsonObject a){return new BlockPos(a.get("x").getAsBigDecimal().intValueExact(),a.get("y").getAsBigDecimal().intValueExact(),a.get("z").getAsBigDecimal().intValueExact());}
    private static JsonObject status(Actor actor){
        JsonObject o=AdvancedTools.ok();if(actor.ticks>0)o.addProperty("status","running");ServerPlayer p=actor.player;o.addProperty("uuid",p.getUUID().toString());o.addProperty("name",p.getGameProfile().getName());o.addProperty("dimension",p.level().dimension().location().toString());
        o.addProperty("x",p.getX());o.addProperty("y",p.getY());o.addProperty("z",p.getZ());o.addProperty("eye_y",p.getEyeY());o.addProperty("yaw",p.getYRot());o.addProperty("pitch",p.getXRot());o.addProperty("remaining_ticks",actor.ticks);o.addProperty("mode","creative");
        o.addProperty("held_item",BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString());o.addProperty("note","Only your actor. Movement is tick-driven with collisions; status reports actual position. Creative items and actions may change the world.");return o;
    }
    private static JsonObject capture(ServerPlayer owner,JsonObject a){
        Actor actor=actors.get(owner.getUUID());ServerPlayer source=actor==null?owner:actor.player;if(actor!=null)actor.touch();
        if(a.has("dimension")&&!a.has("x"))throw new IllegalArgumentException("跨维度取景需要完整x/y/z眼睛坐标");
        var spec=RemoteViewSpec.parse(a,source.getX(),source.getEyeY(),source.getZ(),source.getYRot(),source.getXRot());ServerLevel level=level(owner,a,source.serverLevel());
        ServerPlayer camera=make(level,owner.getUUID());camera.moveTo(spec.x(),spec.y()-camera.getEyeHeight(),spec.z(),spec.yaw(),spec.pitch());
        try{return RemoteSceneSnapshot.capture(level,camera,spec);}finally{camera.discard();}
    }
    public static void tick(MinecraftServer server){
        for(var entry:new ArrayList<>(actors.entrySet())){
            Actor a=entry.getValue();if(a.player.isRemoved()||server.getPlayerList().getPlayer(entry.getKey())!=a.owner||!AdvancedTools.admin(a.owner)||System.currentTimeMillis()>a.expires){remove(entry.getKey());continue;}
            try{
                a.player.doTick();a.player.setDeltaMovement(Vec3.ZERO);
                if(a.ticks>0){Vec3 next=a.player.position().add(a.movement);BlockPos at=BlockPos.containing(next);
                    if(a.player.serverLevel().hasChunkAt(at)&&a.player.serverLevel().getWorldBorder().isWithinBounds(at)&&!a.player.serverLevel().isOutsideBuildHeight(at)){a.player.move(MoverType.SELF,a.movement);a.ticks--;}
                    else a.ticks=0;
                }
            }catch(Exception e){remove(entry.getKey());}
        }
    }
    public static void sendProfiles(ServerPlayer player){if(!actors.isEmpty())player.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(actors.values().stream().map(a->a.player).toList()));}
    public static void remove(UUID owner){Actor a=actors.remove(owner);if(a==null)return;a.player.stopUsingItem();a.player.getInventory().clearContent();a.player.serverLevel().removePlayerImmediately(a.player,Entity.RemovalReason.DISCARDED);a.owner.server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(a.player.getUUID())));}
    public static void clear(){for(UUID id:new ArrayList<>(actors.keySet()))remove(id);}
}
