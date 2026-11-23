package com.wywf.verify;

import com.wywf.map.MapViewport;

public record SurfaceWindow(int x,int z,int width,int height) {
    public boolean contains(int wx,int wz){return wx>=x&&wz>=z&&wx<x+width&&wz<z+height;}
    public static SurfaceWindow around(MapViewport view,int capacity) {
        int pages=Math.max(1,capacity/16);
        int visibleX=Math.max(1,(int)Math.ceil(view.width()*view.scale()/64)+1);
        int visibleZ=Math.max(1,(int)Math.ceil(view.height()*view.scale()/64)+1);
        int nx=Math.min(visibleX,pages),nz=Math.min(visibleZ,pages);
        while(nx*nz>pages){if(nx/(double)visibleX>=nz/(double)visibleZ&&nx>1)nx--;else nz--;}
        int cx=(int)Math.floor(view.x()/64),cz=(int)Math.floor(view.z()/64);
        return new SurfaceWindow((cx-nx/2)*64,(cz-nz/2)*64,nx*64,nz*64);
    }
    public int[] missing(SurfaceCache cache,double cx,double cz) {
        return missing(cache, cx, cz, (x, z) -> true);
    }
    public int[] missing(SurfaceCache cache, double cx, double cz, java.util.function.BiPredicate<Integer, Integer> eligible) {
        int[] best=null;
        double nearest=Double.POSITIVE_INFINITY;
        for(int pz=z;pz<z+height;pz+=64)for(int px=x;px<x+width;px+=64){
            if (!eligible.test(px + 32, pz + 32)) continue;
            boolean missing=false;
            for(int dz=0;dz<64&&!missing;dz+=16)for(int dx=0;dx<64;dx+=16)if(cache.get(px+dx,pz+dz)==null){missing=true;break;}
            double distance=Math.hypot(px+32-cx,pz+32-cz);
            if(missing&&distance<nearest){nearest=distance;best=new int[]{px+32,pz+32};}
        }
        return best;
    }
}
