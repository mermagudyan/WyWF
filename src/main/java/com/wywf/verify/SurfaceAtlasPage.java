package com.wywf.verify;

import java.util.*;

public final class SurfaceAtlasPage {
    public static final int SIZE=64;
    private final int x,z;
    private final int[] pixels=new int[SIZE*SIZE];
    private final Map<Long,Long> stamps=new HashMap<>();
    private final Map<Long,SurfaceSnapshot> samples=new HashMap<>();
    public SurfaceSnapshot at(int wx,int wz){return samples.get(SurfaceCache.key(wx,wz));}
    public SurfaceAtlasPage(int x,int z){this.x=Math.floorDiv(x,SIZE)*SIZE;this.z=Math.floorDiv(z,SIZE)*SIZE;}
    public int x(){return x;}public int z(){return z;}public int[] pixels(){return pixels;}
    public boolean current(SurfaceSnapshot tile,long stamp){return Objects.equals(stamps.get(SurfaceCache.key(tile.x(),tile.z())),stamp);}
    public void put(SurfaceSnapshot tile,long stamp,int[] colors,int sampleSize,int inset) {
        int dx=tile.x()-x,dz=tile.z()-z;
        if(tile.size()!=16||dx<0||dz<0||dx+16>SIZE||dz+16>SIZE)throw new IllegalArgumentException("Tile outside atlas page");
        for(int row=0;row<16;row++)System.arraycopy(colors,(row+inset)*sampleSize+inset,pixels,(dz+row)*SIZE+dx,16);
        stamps.put(SurfaceCache.key(tile.x(),tile.z()),stamp);samples.put(SurfaceCache.key(tile.x(),tile.z()),tile);
    }
}
