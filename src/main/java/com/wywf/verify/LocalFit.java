package com.wywf.verify;

import com.wywf.core.*;
import java.util.*;

public final class LocalFit {
    public static int[] raster(SurfaceSnapshot s, ParsedQuery query, SearchConfig config, List<VerifiedAnchor> anchors) {
        int[] scores = new int[s.colors().length];
        int conditions = 0;
        for (var term : query.terms()) {
            if (DimensionSpace.external(term)) continue;
            if (!(term.modifier == Modifier.DEFAULT || term.modifier == Modifier.IN || term.modifier == Modifier.AT || term.modifier == Modifier.NEAR
                    || term.modifier == Modifier.UNDER && term.category == KeywordDictionary.Category.BIOME
                    || term.modifier == Modifier.BETWEEN && term.betweenMin == 0)) continue;
            if (term.category != KeywordDictionary.Category.BIOME && term.category != KeywordDictionary.Category.STRUCTURE) continue;
            conditions++;
            float[] distances = null;
            if (term.category == KeywordDictionary.Category.BIOME) distances = biomeDistances(s, term.canonical);
            for (int z = 0; z < s.size(); z++) for (int x = 0; x < s.size(); x++) {
                int i = z * s.size() + x;
                if (s.blocks()[i] == null) continue;
                double distance = distances == null ? Double.POSITIVE_INFINITY : distances[i];
                if (distances == null) {
                    for (var anchor : anchors) if (anchor.id().equals(term.canonical))
                        distance = Math.min(distance, anchor.distance(s.x() + x, s.z() + z));
                }
                int limit = switch (term.modifier) {
                    case IN -> 64;
                    case AT -> 128;
                    case NEAR -> 200;
                    case UNDER -> 0;
                    case BETWEEN -> term.betweenMax;
                    default -> (term.category == KeywordDictionary.Category.BIOME ? config.biomeCheckRadiusChunks() : config.searchRadiusChunks()) * 16;
                };
                if (distance <= limit) scores[i]++;
            }
        }
        int[] pixels = new int[scores.length];
        for (int i = 0; i < pixels.length; i++) {
            if (conditions == 0 || s.blocks()[i] == null) { pixels[i] = 0xFF50616B; continue; }
            double ratio = scores[i] / (double) conditions;
            int r = (int) (190 - 120 * ratio), g = (int) (70 + 120 * ratio);
            pixels[i] = 0xFF000000 | r << 16 | g << 8 | 70;
        }
        return pixels;
    }

    public static int[] overview(com.wywf.map.BiomeMapSnapshot map,ParsedQuery query,SearchConfig config,List<VerifiedAnchor> anchors,java.util.function.BooleanSupplier cancelled) {
        int width=map.width(),height=map.height();
        int[] scores=new int[width*height];
        int conditions=0;
        for(var term:query.terms()){
            if(DimensionSpace.external(term)||term.modifier.counted()||term.modifier==Modifier.ONLY||term.modifier==Modifier.SOME||term.modifier==Modifier.NEVER||term.modifier==Modifier.FAR
                    ||term.category!=KeywordDictionary.Category.STRUCTURE&&term.category!=KeywordDictionary.Category.BIOME)continue;
            if(cancelled.getAsBoolean())return null;conditions++;
            float[] distance=null;
            if(term.category==KeywordDictionary.Category.BIOME){
                distance=new float[scores.length];for(int i=0;i<distance.length;i++)distance[i]=map.cells()[i]!=null&&term.canonical.equals(map.cells()[i].id())?0:Float.POSITIVE_INFINITY;
                for(int z=0;z<height;z++)for(int x=0;x<width;x++){
                    int i=z*width+x;if(x>0)distance[i]=Math.min(distance[i],distance[i-1]+1);if(z>0)distance[i]=Math.min(distance[i],distance[i-width]+1);
                    if(x>0&&z>0)distance[i]=Math.min(distance[i],distance[i-width-1]+1.414214f);if(x+1<width&&z>0)distance[i]=Math.min(distance[i],distance[i-width+1]+1.414214f);
                }
                for(int z=height-1;z>=0;z--)for(int x=width-1;x>=0;x--){
                    int i=z*width+x;if(x+1<width)distance[i]=Math.min(distance[i],distance[i+1]+1);if(z+1<height)distance[i]=Math.min(distance[i],distance[i+width]+1);
                    if(x+1<width&&z+1<height)distance[i]=Math.min(distance[i],distance[i+width+1]+1.414214f);if(x>0&&z+1<height)distance[i]=Math.min(distance[i],distance[i+width-1]+1.414214f);
                }
            }
            int limit=term.modifier==Modifier.UNDER?0:TargetDistance.radius(term,(term.category==KeywordDictionary.Category.BIOME?config.biomeCheckRadiusChunks():config.searchRadiusChunks())*16);
            for(int z=0;z<height;z++){
                if((z&31)==0){if(cancelled.getAsBoolean())return null;java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);}
                for(int x=0;x<width;x++){
                    int i=z*width+x;if(map.cells()[i]==null)continue;
                    double d=distance==null?Double.POSITIVE_INFINITY:distance[i]*map.step();
                    if(distance==null)for(var anchor:anchors)if(anchor.id().equals(term.canonical))d=Math.min(d,anchor.distance(map.x()+x*map.step(),map.z()+z*map.step()));
                    boolean match=term.modifier==Modifier.NEVER?d>limit:term.modifier==Modifier.FAR?d>=500:d<=limit&&(term.modifier!=Modifier.BETWEEN||d>=term.betweenMin);
                    if(match)scores[i]++;
                }
            }
        }
        int[] pixels=new int[scores.length];for(int i=0;i<pixels.length;i++){
            if(map.cells()[i]==null){pixels[i]=0;continue;}if(conditions==0){pixels[i]=0xff50616b;continue;}
            double ratio=scores[i]/(double)conditions;
            pixels[i]=0xff000046|(int)(190-120*ratio)<<16|(int)(70+120*ratio)<<8;
        }
        return pixels;
    }

    private static float[] biomeDistances(SurfaceSnapshot s, String id) {
        int n = s.size();
        float[] d = new float[n * n];
        for (int i = 0; i < d.length; i++) d[i] = id.equals(s.biomes()[i]) ? 0 : Float.POSITIVE_INFINITY;
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) {
            int i = z * n + x;
            if (x > 0) d[i] = Math.min(d[i], d[i - 1] + 1);
            if (z > 0) d[i] = Math.min(d[i], d[i - n] + 1);
            if (x > 0 && z > 0) d[i] = Math.min(d[i], d[i - n - 1] + 1.414214f);
            if (x + 1 < n && z > 0) d[i] = Math.min(d[i], d[i - n + 1] + 1.414214f);
        }
        for (int z = n - 1; z >= 0; z--) for (int x = n - 1; x >= 0; x--) {
            int i = z * n + x;
            if (x + 1 < n) d[i] = Math.min(d[i], d[i + 1] + 1);
            if (z + 1 < n) d[i] = Math.min(d[i], d[i + n] + 1);
            if (x + 1 < n && z + 1 < n) d[i] = Math.min(d[i], d[i + n + 1] + 1.414214f);
            if (x > 0 && z + 1 < n) d[i] = Math.min(d[i], d[i + n - 1] + 1.414214f);
        }
        return d;
    }
    private LocalFit() {}
}
