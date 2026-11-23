package com.wywf.verify;

import com.wywf.core.StructureTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pools.*;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces;
import net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces;
import java.util.*;

final class VanillaTags {
    static boolean verify(PreviewWorld.Session session,ServerLevel level,StructureStart start,StructureTags tags,List<String> evidence){
        if(tags.empty())return true;
        if(tags.abandoned()&&!start.getPieces().stream().filter(PoolElementStructurePiece.class::isInstance)
                .map(PoolElementStructurePiece.class::cast).anyMatch(p->abandoned(p.getElement())))return false;
        if(tags.abandoned())evidence.add("Confirmed abandoned village template in generated structure start");
        if(tags.eyes()){
            boolean matched=false;
            for(var piece:start.getPieces())if(piece instanceof StrongholdPieces.PortalRoom){
                var box=piece.getBoundingBox();
                generate(session,level,box);
                int frames=0,eyes=0;
                for(var pos:BlockPos.betweenClosed(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ())){
                    var state=level.getBlockState(pos);
                    if(state.is(Blocks.END_PORTAL_FRAME)){frames++;if(state.getValue(EndPortalFrameBlock.HAS_EYE))eyes++;}
                }
                if(frames!=12)throw new PreviewWorld.BudgetExceeded("Portal room has "+frames+" frames; all twelve are required for eye verification");
                evidence.add("Generated End portal at "+box.minX()+","+box.minY()+","+box.minZ()+": "+eyes+" of 12 eyes");
                matched|=eyes>=tags.eyesMin()&&eyes<=tags.eyesMax();
            }
            if(!matched)return false;
        }
        if(!tags.spawner().isEmpty()){
            var seen=new HashSet<BlockPos>();
            String family=switch(tags.spawner()){
                case "silverfish"->"StrongholdPieces$";case "blaze"->"NetherFortressPieces$";case "cave_spider"->"MineshaftPieces$";default->"";
            };
            boolean vanilla=!family.isEmpty()&&start.getPieces().stream().allMatch(p->p.getClass().getName().startsWith(
                    "net.minecraft.world.level.levelgen.structure.structures."+family));
            for(var piece:start.getPieces()){
                if(vanilla&&!(piece instanceof StrongholdPieces.PortalRoom||piece instanceof NetherFortressPieces.MonsterThrone
                        ||piece instanceof MineshaftPieces.MineShaftCorridor))continue;
                var box=piece.getBoundingBox();
                for(int z=box.minZ()>>4;z<=box.maxZ()>>4;z++)for(int x=box.minX()>>4;x<=box.maxX()>>4;x++){
                    var chunk=(LevelChunk)session.chunk(level,x,z,ChunkStatus.FULL);
                    for(var entry:chunk.getBlockEntities().entrySet()){
                        var pos=entry.getKey();
                        if(!box.isInside(pos)||!seen.add(pos)||!(entry.getValue() instanceof SpawnerBlockEntity spawner))continue;
                        var entity=spawner.getSpawner().getOrCreateDisplayEntity(level,pos);
                        if(entity==null)throw new PreviewWorld.BudgetExceeded("Spawner entity data could not be read");
                        String mob=BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                        if(mob.equals("minecraft:"+tags.spawner())){
                            evidence.add("Confirmed "+mob+" spawner at "+pos.getX()+","+pos.getY()+","+pos.getZ()+" in "+level.dimension().identifier());
                            return true;
                        }
                    }
                }
            }
            return false;
        }
        return true;
    }
    private static void generate(PreviewWorld.Session session,ServerLevel level,BoundingBox box){
        for(int z=box.minZ()>>4;z<=box.maxZ()>>4;z++)for(int x=box.minX()>>4;x<=box.maxX()>>4;x++)session.chunk(level,x,z,ChunkStatus.FULL);
    }
    private static boolean abandoned(StructurePoolElement element){
        if(element instanceof ListPoolElement list)return list.getElements().stream().anyMatch(VanillaTags::abandoned);
        if(!(element instanceof SinglePoolElement single))return false;
        var location=single.getTemplateLocation();
        if(location==null)throw new PreviewWorld.BudgetExceeded("Inline village template cannot prove abandoned variant");
        return location.getNamespace().equals("minecraft")&&location.getPath().startsWith("village/")&&location.getPath().contains("/zombie/");
    }
    private VanillaTags(){}
}
