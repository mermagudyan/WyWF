package com.wywf.verify;

import com.wywf.core.*;
import com.wywf.map.StructureLayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

public final class MapDiscovery {
    private static final KeywordDictionary DICTIONARY = new KeywordDictionary();

    static List<VerifiedAnchor> confirm(PreviewWorld.Session session, List<StructureLayers.Marker> candidates) {
        List<VerifiedAnchor> anchors = new ArrayList<>();
        for (var marker : candidates) {
            session.checkpoint();
            var level = session.level(DimensionSpace.of(marker.id()));
            int cx = marker.x() >> 4, cz = marker.z() >> 4;
            var chunk = session.chunk(level, cx, cz, ChunkStatus.STRUCTURE_STARTS);
            collect(level, chunk, marker,
                    () -> VanillaStructures.buriedTreasureChest(session, level, new ChunkPos(cx, cz)), anchors);
        }
        return List.copyOf(anchors);
    }

    public static CompletableFuture<List<VerifiedAnchor>> confirmLive(MinecraftServer server, ServerLevel level,
                                                                      List<StructureLayers.Marker> candidates) {
        var result = new CompletableFuture<List<VerifiedAnchor>>();
        List<VerifiedAnchor> anchors = new ArrayList<>();
        CompletableFuture<Void> scan = CompletableFuture.completedFuture(null);
        for (var marker : candidates) {
            scan = scan.thenComposeAsync(ignored -> {
                if (result.isDone() || !server.isRunning()) return CompletableFuture.completedFuture(null);
                int cx = marker.x() >> 4, cz = marker.z() >> 4;
                return level.getChunkSource().getChunkFuture(cx, cz, ChunkStatus.STRUCTURE_STARTS, true)
                        .thenAcceptAsync(chunk -> {
                            if (!result.isDone()) collect(level, chunk.orElse(null), marker,
                                    () -> new BlockPos((cx << 4) + 9, 0, (cz << 4) + 9), anchors);
                        }, server);
            }, server);
        }
        scan.whenComplete((ignored, error) -> {
            if (error == null) result.complete(List.copyOf(anchors));
            else result.completeExceptionally(error);
        });
        return result;
    }

    private static void collect(ServerLevel level, ChunkAccess chunk, StructureLayers.Marker marker,
                                java.util.function.Supplier<BlockPos> treasure, List<VerifiedAnchor> anchors) {
        if (chunk == null) return;
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        for (var entry : chunk.getAllStarts().entrySet()) {
            var key = registry.getKey(entry.getKey());
            var start = entry.getValue();
            if (key == null || !start.isValid() || start.getPieces().isEmpty()) continue;
            String actual = key.toString();
            if (!DICTIONARY.matchesStructureLayer(marker.id(), actual)) continue;
            int x = start.getChunkPos().getBlockX(8), z = start.getChunkPos().getBlockZ(8);
            if (actual.equals("minecraft:buried_treasure")) {
                var chest = treasure.get();
                if (chest == null) continue;
                x = chest.getX();
                z = chest.getZ();
            }
            anchors.add(VerifiedAnchor.of(actual, x, z, level.dimension().identifier().toString(), start));
        }
    }

    static String kind(net.minecraft.world.level.block.state.BlockState state) {
        if(state.is(Blocks.BUDDING_AMETHYST))return "wywf:amethyst_geode";
        if(state.is(Blocks.RAW_IRON_BLOCK))return "wywf:iron_vein";
        if(state.is(Blocks.RAW_COPPER_BLOCK))return "wywf:copper_vein";
        if(state.is(Blocks.SPAWNER))return "wywf:spawner";
        if(state.is(Blocks.LAVA))return "wywf:lava_pool";
        return "";
    }
    static List<MapFeature> features(PreviewWorld.Session session,int px,int pz,Set<String> selected,Set<Long> savedChunks) {
        if (selected.contains(EndReturnGateways.ID)) return endGateways(session, px, pz, savedChunks);
        var level=session.server().overworld();
        List<MapFeature> found=new ArrayList<>();
        for(int cz=Math.floorDiv(pz,16);cz<Math.floorDiv(pz,16)+4;cz++)for(int cx=Math.floorDiv(px,16);cx<Math.floorDiv(px,16)+4;cx++){
            session.checkpoint();
            var chunk=(LevelChunk)session.chunk(level,cx,cz,ChunkStatus.FULL);
            Set<String> once=new HashSet<>();
            var sections=chunk.getSections();
            for(int section=0;section<sections.length;section++){
                var slice=sections[section];if(!slice.maybeHas(s->selected.contains(kind(s))))continue;
                for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                    var state=slice.getBlockState(x,y,z);
                    String id=kind(state);if(!selected.contains(id)||once.contains(id))continue;
                    int wx=cx*16+x,wy=level.getSectionYFromSectionIndex(section)*16+y,wz=cz*16+z;
                    if(id.equals("wywf:lava_pool")&&wy!=level.getHeight(Heightmap.Types.WORLD_SURFACE,wx,wz)-1)continue;
                    if(id.equals("wywf:spawner")){
                        if(!(level.getBlockEntity(new BlockPos(wx,wy,wz)) instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity))continue;
                    }else once.add(id);
                    found.add(new MapFeature(id,wx,wy,wz));
                }
                session.checkpoint();
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            }
        }
        return List.copyOf(found);
    }
    private static List<MapFeature> endGateways(PreviewWorld.Session session, int x, int z, Set<Long> savedChunks) {
        var level = session.level(DimensionSpace.END);
        var found = new ArrayList<MapFeature>();
        for (int cz = z >> 4; cz < (z >> 4) + 4; cz++) for (int cx = x >> 4; cx < (x >> 4) + 4; cx++) {
            session.checkpoint();
            if (EndReturnGateways.mayContain(cx, cz) && !savedChunks.contains(SurfaceCache.key(cx << 4, cz << 4))) {
                var chunk = (LevelChunk) session.chunk(level, cx, cz, ChunkStatus.FULL);
                found.addAll(EndReturnGateways.inChunk(chunk));
            }
        }
        return List.copyOf(found);
    }

    private MapDiscovery(){}
}
