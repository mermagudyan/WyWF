package com.wywf.core;

public record PlaceSearch(long seed, int x, int z, int radius, int step, boolean wholeSeed) {
    public static final int WORLD_EDGE = 29_999_983;

    public PlaceSearch(long seed, int x, int z, int radius, int step) {
        this(seed, x, z, radius, step, false);
    }

    public static PlaceSearch wholeSeed(long seed, int x, int z, int step) {
        return new PlaceSearch(seed, x, z, 64, step, true);
    }
    public PlaceSearch {
        if (Math.abs((long)x) > WORLD_EDGE || Math.abs((long)z) > WORLD_EDGE
                || radius < 64 || radius > 16384 || step < 64 || step > 1024)
            throw new IllegalArgumentException("FTP: radius 64..16384, step 64..1024, coordinates within world border");
    }
    public int rings() {
        int reach = wholeSeed ? WORLD_EDGE + Math.max(Math.abs(x), Math.abs(z)) : radius;
        return (reach + step - 1) / step;
    }
    public long cells() { long side=2L*rings()+1;return side*side; }
    public int[] position(long index) {
        if(index<0||index>=cells())throw new IndexOutOfBoundsException();
        if(index==0)return new int[]{x,z};
        int ring=(int)Math.ceil((Math.sqrt(index+1)-1)/2),side=ring*2;
        long start=(long)(side-1)*(side-1),offset=index-start;
        int dx,dz;
        if(offset<side){dx=-ring+(int)offset;dz=-ring;}
        else if(offset<2L*side){dx=ring;dz=-ring+(int)(offset-side);}
        else if(offset<3L*side){dx=ring-(int)(offset-2L*side);dz=ring;}
        else{dx=-ring;dz=ring-(int)(offset-3L*side);}
        return new int[]{x+dx*step,z+dz*step};
    }
    public boolean contains(int px, int pz) {
        if (Math.abs((long)px) > WORLD_EDGE || Math.abs((long)pz) > WORLD_EDGE) return false;
        return wholeSeed || Math.hypot((double)px - x, (double)pz - z) <= radius;
    }

    public String prefix() {
        return "FTP " + seed + " around " + x + " " + z + " within " + (wholeSeed ? "world" : radius)
                + " step " + step + ": ";
    }
}
