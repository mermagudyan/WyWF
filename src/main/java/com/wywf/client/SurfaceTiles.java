package com.wywf.client;

import com.wywf.core.DimensionSpace;
import com.wywf.verify.*;
import com.wywf.map.MapViewport;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import java.util.*;

final class SurfaceTiles implements AutoCloseable {
    private record Entry(SurfaceAtlasPage page,MapImage image){}
    private final LinkedHashMap<Long,Entry> images=new LinkedHashMap<>(128,0.75f,true);
    private SurfaceColors.Layer layer;
    private final SurfaceCache savedCache=new SurfaceCache(1024);
    private SurfaceSnapshot savedSurface;
    private boolean borders;
    void update(WorldMapSource source,DimensionSpace dimension,MapViewport view,SurfaceColors.Layer next,boolean grid,int width,int height){
        if(layer!=next||borders!=grid){close();layer=next;borders=grid;}
        var sourceCache=source.surfaceCache(dimension);
        if(sourceCache==null){
            if(savedSurface!=source.surface(dimension)){
                savedSurface=source.surface(dimension);
                savedCache.clear();if(savedSurface!=null)savedCache.put(savedSurface);
            }
            sourceCache=savedCache;
        }
        Map<Long,List<SurfaceSnapshot>> groups=new HashMap<>();
        for(var tile:sourceCache.tiles()){
            if(view.pixelX(tile.x()+16)<0||view.pixelZ(tile.z()+16)<0||view.pixelX(tile.x())>width||view.pixelZ(tile.z())>height)continue;
            groups.computeIfAbsent(SurfaceCache.key(
                    Math.floorDiv(tile.x(),64)*64,Math.floorDiv(tile.z(),64)*64),k->new ArrayList<>()).add(tile);
        }
        var pages=new ArrayList<>(groups.values());
        pages.sort(Comparator.comparingDouble(ts->{
            var t=ts.getFirst();
            return Math.hypot(Math.floorDiv(t.x(),64)*64+32-view.x(),Math.floorDiv(t.z(),64)*64+32-view.z());
        }));
        long start=System.nanoTime();
        for(var group:pages){
            var first=group.getFirst();int px=Math.floorDiv(first.x(),64)*64,pz=Math.floorDiv(first.z(),64)*64;
            long key=SurfaceCache.key(px,pz);var entry=images.get(key);
            if(entry==null){
                entry=new Entry(new SurfaceAtlasPage(px,pz),new MapImage());images.put(key,entry);
            }
            boolean changed=false;var cache=sourceCache;
            for(var tile:group){
                long stamp=cache==null?System.identityHashCode(tile):cache.stamp(tile);
                if(entry.page.current(tile,stamp))continue;
                var sample=cache==null?tile:cache.halo(tile);
                int[] pixels=next==SurfaceColors.Layer.MATCH?LocalFit.raster(sample,source.query(),source.config(),source.anchors())
                        :SurfaceColors.raster(sample,next,grid);
                entry.page.put(tile,stamp,pixels,sample.size(),cache==null?0:1);
                changed=true;
            }
            if(changed)entry.image.upload(64,64,entry.page.pixels());
            while(images.size()>256){var it=images.entrySet().iterator();
                it.next().getValue().image.close();it.remove();}
            if(System.nanoTime()-start>3_000_000)
                break;
        }
    }
    SurfaceSnapshot at(int x,int z){
        var entry=images.get(SurfaceCache.key(Math.floorDiv(x,64)*64,Math.floorDiv(z,64)*64));
        return entry == null
                ? null:entry.page.at(x,z);
    }
    static int[] bounds(MapViewport view,int x,int z,int left,int top){
        int sx=left+view.edgeX(x),sz=top+view.edgeZ(z);
        return new int[]{
                sx,sz,left+view.edgeX(x+64)-sx,top+view.edgeZ(z+64)-sz
        };
    }
    void draw(GuiGraphicsExtractor g,MapViewport view,int left,int top){
        for(var e:images.values()){
            var p=e.page;var box=bounds(view,p.x(),p.z(),left,top);e.image.draw(g,box[0],box[1],box[2],box[3]);
        }
    }
    @Override public void close(){images.values().forEach(e->e.image.close());images.clear();savedCache.clear();savedSurface=null;}
}
