package com.wywf.verify;

import com.wywf.core.*;
import com.wywf.map.StructureLayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

final class MapDiscovery {
    static List<VerifiedAnchor> confirm(PreviewWorld.Session session,List<StructureLayers.Marker> candidates) {
        List<VerifiedAnchor> anchors=new ArrayList<>();var dictionary=new KeywordDictionary();
        for(var marker:candidates){
            session.checkpoint();var dimension=DimensionSpace.of(marker.id());var level=session.level(dimension);
            int cx=Math.floorDiv(marker.x(),16),cz=Math.floorDiv(marker.z(),16);
            var chunk=session.chunk(level,cx,cz,ChunkStatus.STRUCTURE_STARTS);
            var registry=level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            var targets=StructureTargets.resolve(marker.id(),dictionary,registry.containsKey(net.minecraft.resources.Identifier.parse(marker.id())));
            for(var entry:chunk.getAllStarts().entrySet()){
                String actual = registry.getKey(entry.getKey()).toString();
                var start = entry.getValue();
                if (!targets.contains(actual) || !start.isValid() || start.getPieces().isEmpty()) continue;
                var box = start.getBoundingBox();
                int x = marker.x(), z = marker.z();
                if (actual.equals("minecraft:buried_treasure")) {
                    var chest = VanillaStructures.buriedTreasureChest(session, level, new ChunkPos(cx, cz));
                    if (chest == null) continue;
                    x = chest.getX(); z = chest.getZ();
                }
                anchors.add(new VerifiedAnchor(marker.id(),x,z,box.minX(),box.minZ(),box.maxX(),box.maxZ(),start.getPieces().size(),dimension.id));
            }
            java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
        }
        return List.copyOf(anchors);
    }
    static String kind(net.minecraft.world.level.block.state.BlockState state) {
        if(state.is(Blocks.BUDDING_AMETHYST))return "wywf:amethyst_geode";
        if(state.is(Blocks.RAW_IRON_BLOCK))return "wywf:iron_vein";
        if(state.is(Blocks.RAW_COPPER_BLOCK))return "wywf:copper_vein";
        if(state.is(Blocks.SPAWNER))return "wywf:spawner";
        if(state.is(Blocks.LAVA))return "wywf:lava_pool";
        return "";
    }
    static List<MapFeature> features(PreviewWorld.Session session,int px,int pz,Set<String> selected) {
        if (selected.contains(EndReturnGateways.ID)) return endGateways(session, px, pz);
        var level=session.server().overworld();List<MapFeature> found=new ArrayList<>();
        for(int cz=Math.floorDiv(pz,16);cz<Math.floorDiv(pz,16)+4;cz++)for(int cx=Math.floorDiv(px,16);cx<Math.floorDiv(px,16)+4;cx++){
            session.checkpoint();var chunk=(LevelChunk)session.chunk(level,cx,cz,ChunkStatus.FULL);Set<String> once=new HashSet<>();var sections=chunk.getSections();
            for(int section=0;section<sections.length;section++){
                var slice=sections[section];if(!slice.maybeHas(s->selected.contains(kind(s))))continue;
                for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                    var state=slice.getBlockState(x,y,z);String id=kind(state);if(!selected.contains(id)||once.contains(id))continue;
                    int wx=cx*16+x,wy=level.getSectionYFromSectionIndex(section)*16+y,wz=cz*16+z;
                    if(id.equals("wywf:lava_pool")&&wy!=level.getHeight(Heightmap.Types.WORLD_SURFACE,wx,wz)-1)continue;
                    if(id.equals("wywf:spawner")){
                        if(!(level.getBlockEntity(new BlockPos(wx,wy,wz)) instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity))continue;
                    }else once.add(id);
                    found.add(new MapFeature(id,wx,wy,wz));
                }
                session.checkpoint();java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            }
        }
        return List.copyOf(found);
    }
    private static List<MapFeature> endGateways(PreviewWorld.Session session, int x, int z) {
        var level = session.level(DimensionSpace.END);
        var found = new ArrayList<MapFeature>();
        for (int cz = z >> 4; cz < (z >> 4) + 4; cz++) for (int cx = x >> 4; cx < (x >> 4) + 4; cx++) {
            session.checkpoint();
            if (EndReturnGateways.mayContain(cx, cz)) {
                var chunk = (LevelChunk) session.chunk(level, cx, cz, ChunkStatus.FULL);
                found.addAll(EndReturnGateways.inChunk(chunk));
            }
        }
        return List.copyOf(found);
    }

    private MapDiscovery(){}
}
