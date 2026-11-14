package top.goldgom.aibotassist;

import com.google.gson.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.*;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** Captures only bounded render data: no inventories, block-entity NBT or commands. */
public final class RemoteSceneSnapshot {
    private RemoteSceneSnapshot(){}
    public static JsonObject capture(ServerLevel level,ServerPlayer camera,RemoteViewSpec spec){
        int radius=spec.radius(),side=spec.side(),minX=spec.centerX()-radius,minY=spec.centerY()-radius,minZ=spec.centerZ()-radius;
        if(!level.getWorldBorder().isWithinBounds(new BlockPos(minX,spec.centerY(),minZ))||!level.getWorldBorder().isWithinBounds(new BlockPos(minX+side-1,spec.centerY(),minZ+side-1)))throw new IllegalArgumentException("取景区域超出世界边界");
        if(spec.centerY()<level.getMinBuildHeight()-radius||spec.centerY()>level.getMaxBuildHeight()+radius)throw new IllegalArgumentException("取景区域不在世界高度附近");
        for(int cx=minX>>4;cx<=(minX+side-1)>>4;cx++)for(int cz=minZ>>4;cz<=(minZ+side-1)>>4;cz++){
            if(!level.hasChunk(cx,cz)){if(!spec.loadChunks())throw new IllegalArgumentException("取景区域含未加载区块；指定load_chunks=true可加载/生成最多9个区块");level.getChunk(cx,cz);}
        }
        JsonObject out=AdvancedTools.ok();out.addProperty("scene_version",1);out.addProperty("captured_at",System.currentTimeMillis());out.addProperty("dimension",level.dimension().location().toString());
        out.add("camera",AIBotAssist.JSON.toJsonTree(spec));out.addProperty("camera_uuid",camera.getUUID().toString());out.addProperty("min_x",minX);out.addProperty("min_y",minY);out.addProperty("min_z",minZ);out.addProperty("side",side);
        JsonArray palette=new JsonArray(),blocks=new JsonArray(),lights=new JsonArray(),biomes=new JsonArray();Map<BlockState,Integer> indices=new LinkedHashMap<>();
        var biomeRegistry=level.registryAccess().registryOrThrow(Registries.BIOME);
        for(int z=0;z<side;z++)for(int x=0;x<side;x++)biomes.add(biomeRegistry.getKey(level.getBiome(new BlockPos(minX+x,spec.centerY(),minZ+z)).value()).toString());
        int blockEntities=0;
        for(int y=0;y<side;y++)for(int z=0;z<side;z++)for(int x=0;x<side;x++){
            BlockPos pos=new BlockPos(minX+x,minY+y,minZ+z);BlockState state=level.isOutsideBuildHeight(pos)?Blocks.AIR.defaultBlockState():level.getBlockState(pos);
            Integer index=indices.get(state);if(index==null){index=indices.size();indices.put(state,index);palette.add(Block.getId(state));}blocks.add(index);
            lights.add((level.getBrightness(LightLayer.SKY,pos)<<4)|level.getBrightness(LightLayer.BLOCK,pos));if(state.hasBlockEntity())blockEntities++;
        }
        out.add("palette",palette);out.add("blocks",blocks);out.add("light",lights);out.add("biomes",biomes);out.addProperty("block_entities",blockEntities);
        out.addProperty("note","Bounded server block-state snapshot, rendered offscreen with client block models/textures. Outside bounds is unknown, NOT air. No entities, block-entity animation/NBT, particles, shaders or sky/weather simulation. Biome tint sampled per column. May be insufficient to verify dynamic mod machines; use inspect_machine/verify_result too.");return out;
    }
}
