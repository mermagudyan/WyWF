package com.wywf.map;

public final class MapViewport {
    public static final double LIMIT = 29_990_000;
    private double x, z, blocksPerPixel;
    private int width = 1, height = 1;
    private boolean zooming;
    private long zoomStarted;
    private double startX,startZ,startScale,targetX,targetZ,targetScale;
    public boolean zooming(){return zooming;}
    public void stopZoom(){zooming=false;}
    public void zoomSmooth(double wheel,double pixelX,double pixelZ){zoomSmooth(wheel,pixelX,pixelZ,System.nanoTime());}
    void zoomSmooth(double wheel,double pixelX,double pixelZ,long now){
        if(!Double.isFinite(wheel)||wheel==0)return;
        advance(now);double base=zooming?targetScale:blocksPerPixel;
        double next=Math.max(0.125,Math.min(512,base*Math.pow(2,-Math.max(-4,Math.min(4,wheel))/2)));
        startX=x;startZ=z;startScale=blocksPerPixel;
        targetX=clamp(worldX(pixelX)-(pixelX-width/2d)*next);targetZ=clamp(worldZ(pixelZ)-(pixelZ-height/2d)*next);targetScale=next;
        zoomStarted=now;zooming=next!=blocksPerPixel||targetX!=x||targetZ!=z;
    }
    public boolean advance(){return advance(System.nanoTime());}
    boolean advance(long now){
        if(!zooming)return false;
        double t=Math.max(0,Math.min(1,(now-zoomStarted)/180_000_000d)),ease=t*t*(3-2*t);
        double nx=startX+(targetX-startX)*ease,nz=startZ+(targetZ-startZ)*ease,ns=startScale+(targetScale-startScale)*ease;
        boolean changed=nx!=x||nz!=z||ns!=blocksPerPixel;x=nx;z=nz;blocksPerPixel=ns;
        if(t==1)zooming=false;return changed;
    }

    public MapViewport(double x, double z, double blocksPerPixel) {
        this.x = x; this.z = z; this.blocksPerPixel = blocksPerPixel;
    }

    public void resize(int width, int height) {
        if(this.width!=Math.max(1,width)||this.height!=Math.max(1,height))stopZoom();
        this.width = Math.max(1, width); this.height = Math.max(1, height);
    }

    public double x() { return x; }
    public double z() { return z; }
    public double scale() { return blocksPerPixel; }
    public int width() { return width; }
    public int height() { return height; }
    public double worldX(double pixel) { return x + (pixel - width / 2.0) * blocksPerPixel; }
    public double worldZ(double pixel) { return z + (pixel - height / 2.0) * blocksPerPixel; }
    public double pixelX(double world) { return (world - x) / blocksPerPixel + width / 2.0; }
    public double pixelZ(double world) { return (world - z) / blocksPerPixel + height / 2.0; }
    public int edgeX(double world){return (int)Math.floor(pixelX(world));}
    public int edgeZ(double world){return (int)Math.floor(pixelZ(world));}

    public void zoom(double wheel, double pixelX, double pixelZ) {
        if (!Double.isFinite(wheel) || wheel == 0) return;
        stopZoom();double anchorX = worldX(pixelX), anchorZ = worldZ(pixelZ);
        double next = Math.max(0.125, Math.min(512, blocksPerPixel * Math.pow(2, -Math.max(-4, Math.min(4, wheel)) / 2)));
        x = clamp(anchorX - (pixelX - width / 2.0) * next);
        z = clamp(anchorZ - (pixelZ - height / 2.0) * next);
        blocksPerPixel = next;
    }

    public void pan(double dx, double dz) {
        stopZoom();
        x = clamp(x - dx * blocksPerPixel);
        z = clamp(z - dz * blocksPerPixel);
    }

    public void reset(double x, double z) {
        stopZoom();
        this.x = clamp(x); this.z = clamp(z); blocksPerPixel = 8;
    }

    public void reset(double x, double z, double scale) {
        if (!Double.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("Invalid map scale");
        stopZoom();this.x = clamp(x); this.z = clamp(z); blocksPerPixel = Math.max(0.125, Math.min(512, scale));
    }

    public int sampleStep(int resolution) {
        double wanted = Math.max(1, blocksPerPixel * Math.max(width, height) / Math.max(1, resolution));
        int step = 1;
        while (step < wanted && step < 65536) step *= 2;
        return step;
    }

    private static double clamp(double value) { return Math.max(-LIMIT, Math.min(LIMIT, value)); }
}
