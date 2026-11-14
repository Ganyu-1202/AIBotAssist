package top.goldgom.aibotassist.client;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/** Read-only render world. No client chunk cache, active dimension or camera is replaced. */
public final class RemoteSceneView implements BlockAndTintGetter {
    public final int minX,minY,minZ,side;
    private final BlockState[] states;
    private final int[] light;
    private final Biome[] biomes;
    public RemoteSceneView(JsonObject scene){
        if(scene.get("scene_version").getAsInt()!=1)throw new IllegalArgumentException("Unsupported remote scene version");
        side=scene.get("side").getAsInt();if(side<9||side>33||(side&1)==0)throw new IllegalArgumentException("Invalid remote scene size");
        minX=scene.get("min_x").getAsInt();minY=scene.get("min_y").getAsInt();minZ=scene.get("min_z").getAsInt();
        if(Math.abs((long)minX)>30_000_000||Math.abs((long)minZ)>30_000_000||Math.abs((long)minY)>20_000_100)throw new IllegalArgumentException("Invalid scene origin");
        int count=side*side*side;var blocks=scene.getAsJsonArray("blocks");JsonArray lights=scene.getAsJsonArray("light"),palette=scene.getAsJsonArray("palette"),names=scene.getAsJsonArray("biomes");
        if(blocks.size()!=count||lights.size()!=count||palette.isEmpty()||palette.size()>count||names.size()!=side*side)throw new IllegalArgumentException("Incomplete remote scene");
        List<BlockState> types=new ArrayList<>();for(JsonElement entry:palette){int id=entry.getAsBigDecimal().intValueExact();BlockState state=Block.stateById(id);if(id<0||Block.getId(state)!=id)throw new IllegalArgumentException("Server/client block registry mismatch");types.add(state);}
        states=new BlockState[count];light=new int[count];for(int i=0;i<count;i++){
            int index=blocks.get(i).getAsBigDecimal().intValueExact(),brightness=lights.get(i).getAsBigDecimal().intValueExact();
            if(index<0||index>=types.size()||brightness<0||brightness>255)throw new IllegalArgumentException("Invalid remote scene cell");states[i]=types.get(index);light[i]=brightness;
        }
        var registry=Minecraft.getInstance().level.registryAccess().registryOrThrow(Registries.BIOME);biomes=new Biome[side*side];
        for(int i=0;i<biomes.length;i++){Biome biome=registry.get(ResourceLocation.parse(names.get(i).getAsString()));if(biome==null)throw new IllegalArgumentException("Server/client biome registry mismatch");biomes[i]=biome;}
    }
    private int index(BlockPos pos){int x=pos.getX()-minX,y=pos.getY()-minY,z=pos.getZ()-minZ;return x<0||x>=side||y<0||y>=side||z<0||z>=side?-1:(y*side+z)*side+x;}
    @Override public BlockState getBlockState(BlockPos pos){int i=index(pos);return i<0?Blocks.AIR.defaultBlockState():states[i];}
    @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
    @Override public BlockEntity getBlockEntity(BlockPos pos){return null;}
    @Override public int getHeight(){return side;}
    @Override public int getMinBuildHeight(){return minY;}
    @Override public float getShade(Direction face,boolean shade){if(!shade)return 1;return switch(face){case DOWN->0.5f;case UP->1;case NORTH,SOUTH->0.8f;case EAST,WEST->0.6f;};}
    @Override public int getBrightness(LightLayer layer,BlockPos pos){int i=index(pos);if(i<0)return layer==LightLayer.SKY?15:0;return layer==LightLayer.SKY?light[i]>>4:light[i]&15;}
    @Override public int getRawBrightness(BlockPos pos,int skyDarken){return Math.max(getBrightness(LightLayer.BLOCK,pos),getBrightness(LightLayer.SKY,pos)-skyDarken);}
    @Override public LevelLightEngine getLightEngine(){throw new UnsupportedOperationException("Remote scene has sampled light only");}
    @Override public int getBlockTint(BlockPos pos,ColorResolver resolver){int x=Math.clamp(pos.getX()-minX,0,side-1),z=Math.clamp(pos.getZ()-minZ,0,side-1);return resolver.getColor(biomes[z*side+x],pos.getX(),pos.getZ());}
}
