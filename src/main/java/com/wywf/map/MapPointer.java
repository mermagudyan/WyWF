package com.wywf.map;

public final class MapPointer {
    public record Point(int x,int y,boolean outside,double angle){}
    public static Point project(double x,double y,int width,int height,int margin) {
        double cx=width/2d,cy=height/2d,dx=x-cx,dy=y-cy;
        if (width == 0 || height == 0)
            return new Point((int) Math.round(Math.clamp(x, 0, width)),
                    (int) Math.round(Math.clamp(y, 0, height)), true, 0);
        int mx=Math.min(margin,Math.max(0,width/2-1)),my=Math.min(margin,Math.max(0,height/2-1));
        if(x>=mx&&x<=width-mx&&y>=my&&y<=height-my)return new Point((int)Math.round(x),(int)Math.round(y),false,0);
        double ratio=Math.min(dx==0?Double.POSITIVE_INFINITY:(cx-mx)/Math.abs(dx),dy==0?Double.POSITIVE_INFINITY:(cy-my)/Math.abs(dy));
        return new Point((int)Math.round(cx+dx*ratio),(int)Math.round(cy+dy*ratio),true,Math.atan2(dy,dx));
    }
    private MapPointer(){}
}
