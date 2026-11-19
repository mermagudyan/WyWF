package com.wywf.map;

import java.util.*;

public final class MarkerSpacing {
    private final int gap;
    private final Map<Long,List<int[]>> cells=new HashMap<>();
    public MarkerSpacing(int gap){if(gap<1)throw new IllegalArgumentException();this.gap=gap;}
    private static long key(int x,int y){return ((long)x<<32)^(y&0xffffffffL);}
    public boolean add(int x,int y){
        int cx=Math.floorDiv(x,gap),cy=Math.floorDiv(y,gap);
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
            var nearby=cells.get(key(cx+dx,cy+dz));if(nearby==null)continue;
            for(var point:nearby)if(Math.abs((long)x-point[0])<gap&&Math.abs((long)y-point[1])<gap)return false;
        }
        cells.computeIfAbsent(key(cx,cy),k->new ArrayList<>()).add(new int[]{x,y});return true;
    }
}
