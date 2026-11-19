package com.wywf.verify;

import java.util.*;

public final class SurfaceCache {
    private final int capacity;
    private SurfaceWindow protectedWindow;
    public synchronized void protect(SurfaceWindow window){protectedWindow=window;}
    private final LinkedHashMap<Long,SurfaceSnapshot> tiles=new LinkedHashMap<>(128,0.75f,true);
    public SurfaceCache(int capacity){if(capacity<1)throw new IllegalArgumentException();this.capacity=capacity;}
    public static long key(int x,int z){return ((long)Math.floorDiv(x,16)<<32)^(Math.floorDiv(z,16)&0xffffffffL);}
    public synchronized SurfaceSnapshot get(int x,int z){return tiles.get(key(x,z));}
    public synchronized java.util.List<SurfaceSnapshot> tiles(){return List.copyOf(tiles.values());}
    public synchronized int size(){return tiles.size();}
    public synchronized void clear(){tiles.clear();}
    public synchronized void put(SurfaceSnapshot s){ put(s, false); }
    public synchronized void putSavedTile(SurfaceSnapshot s){ put(s, true); }
    private void put(SurfaceSnapshot s, boolean replace){
        if(!s.complete())return;
        for(int z=0;z+16<=s.size();z+=16)for(int x=0;x+16<=s.size();x+=16){
            long key=key(s.x()+x,s.z()+z);if(!replace&&tiles.containsKey(key))continue;
            tiles.put(key,s.size()==16?s:region(s,x,z,16));
            while(tiles.size()>capacity){
                Long victim=null;
                for(var entry:tiles.entrySet())if(protectedWindow==null||!protectedWindow.contains(entry.getValue().x(),entry.getValue().z())){victim=entry.getKey();break;}
                tiles.remove(victim==null?tiles.keySet().iterator().next():victim);
            }
        }
    }
    public static SurfaceSnapshot region(SurfaceSnapshot s,int x,int z,int size){
        int[] c=new int[size*size],h=new int[c.length];String[] b=new String[c.length],ids=new String[c.length];float[] t=new float[c.length],n=new float[c.length];
        for(int row=0;row<size;row++){int from=(z+row)*s.size()+x,to=row*size;
            System.arraycopy(s.colors(),from,c,to,size);System.arraycopy(s.heights(),from,h,to,size);
            System.arraycopy(s.blocks(),from,b,to,size);System.arraycopy(s.biomes(),from,ids,to,size);
            System.arraycopy(s.temperatures(),from,t,to,size);System.arraycopy(s.climate(),from,n,to,size);
        }
        return new SurfaceSnapshot(s.seed(),s.x()+x,s.z()+z,size,c,h,b,ids,t,n,true);
    }
    public synchronized SurfaceSnapshot halo(SurfaceSnapshot tile){
        int size=tile.size()+2;int[] c=new int[size*size],h=new int[c.length];String[] b=new String[c.length],ids=new String[c.length];float[] t=new float[c.length],n=new float[c.length];
        for(int z=0;z<size;z++)for(int x=0;x<size;x++){
            int wx=tile.x()+x-1,wz=tile.z()+z-1;var s=get(wx,wz);
            if(s==null)s=tile;
            int sx=Math.max(s.x(),Math.min(s.x()+s.size()-1,wx)),sz=Math.max(s.z(),Math.min(s.z()+s.size()-1,wz)),from=s.index(sx,sz),to=z*size+x;
            c[to]=s.colors()[from];h[to]=s.heights()[from];b[to]=s.blocks()[from];ids[to]=s.biomes()[from];t[to]=s.temperatures()[from];n[to]=s.climate()[from];
        }
        return new SurfaceSnapshot(tile.seed(),tile.x()-1,tile.z()-1,size,c,h,b,ids,t,n,true);
    }
    public synchronized long stamp(SurfaceSnapshot tile){
        long hash=System.identityHashCode(tile);
        for(int z=-16;z<=16;z+=16)for(int x=-16;x<=16;x+=16)hash=hash*31+System.identityHashCode(get(tile.x()+x,tile.z()+z));
        return hash;
    }
}
