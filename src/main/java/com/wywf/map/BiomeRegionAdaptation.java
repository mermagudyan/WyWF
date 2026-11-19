package com.wywf.map;

import java.util.*;

public final class BiomeRegionAdaptation {
    public static int[] raster(BiomeMapSnapshot map, List<PreviewAdaptation.Goal> goals) {
        return raster(map, goals, () -> false);
    }
    private record Region(int id, int minX, int minZ, int maxX, int maxZ) {}
    private static int[] raster(BiomeMapSnapshot map, List<PreviewAdaptation.Goal> goals, java.util.function.BooleanSupplier cancelled) {
        long slice = System.nanoTime(); int checked = 0;
        int[] pixels = new int[map.cells().length], regions = new int[pixels.length];
        double[] distance = new double[pixels.length]; Arrays.fill(distance, Double.POSITIVE_INFINITY);
        List<Region> shapes = new ArrayList<>();
        for (var goal : goals) {
            int x = Math.floorDiv(goal.x() - map.x(), map.step()), z = Math.floorDiv(goal.z() - map.z(), map.step());
            if (x < 0 || z < 0 || x >= map.width() || z >= map.height()) continue;
            int first = z * map.width() + x; var origin = map.cells()[first];
            if (origin == null) continue;
            if (regions[first] == 0) {
                int id = shapes.size() + 1, minX = x, maxX = x, minZ = z, maxZ = z;
                ArrayDeque<Integer> queue = new ArrayDeque<>(); regions[first] = id; queue.add(first);
                while (!queue.isEmpty()) {
                    if ((checked++ & 511) == 0) {
                        if (cancelled.getAsBoolean()) return pixels;
                        if (System.nanoTime() - slice > 2_000_000) { java.util.concurrent.locks.LockSupport.parkNanos(8_000_000); slice = System.nanoTime(); }
                    }
                    int i = queue.removeFirst(), cx = i % map.width(), cz = i / map.width();
                    minX = Math.min(minX,cx); maxX = Math.max(maxX,cx); minZ = Math.min(minZ,cz); maxZ = Math.max(maxZ,cz);
                    for (int next : new int[]{cx > 0 ? i-1 : -1,cx+1 < map.width() ? i+1 : -1,
                            cz > 0 ? i-map.width() : -1,cz+1 < map.height() ? i+map.width() : -1}) {
                        if (next >= 0 && regions[next] == 0 && map.cells()[next] != null && origin.id().equals(map.cells()[next].id())) {
                            regions[next] = id; queue.add(next);
                        }
                    }
                }
                shapes.add(new Region(id,minX,minZ,maxX,maxZ));
            }
            Region shape = shapes.get(regions[first] - 1);
            boolean clipped=shape.minX==0||shape.minZ==0||shape.maxX==map.width()-1||shape.maxZ==map.height()-1;
            double size = clipped ? 1 : 0.55 + Math.min(0.45, Math.hypot(goal.x(), goal.z()) / 2048);
            double gx = (goal.x() - map.x()) / (double)map.step(), gz = (goal.z() - map.z()) / (double)map.step();
            int minX = Math.max(0,(int)Math.floor(gx+(shape.minX-gx)*size)), maxX = Math.min(map.width()-1,(int)Math.ceil(gx+(shape.maxX+1-gx)*size));
            int minZ = Math.max(0,(int)Math.floor(gz+(shape.minZ-gz)*size)), maxZ = Math.min(map.height()-1,(int)Math.ceil(gz+(shape.maxZ+1-gz)*size));
            for (int dz=minZ;dz<=maxZ;dz++) for (int dx=minX;dx<=maxX;dx++) {
                if ((checked++ & 511) == 0) {
                    if (cancelled.getAsBoolean()) return pixels;
                    if (System.nanoTime()-slice > 2_000_000) {java.util.concurrent.locks.LockSupport.parkNanos(8_000_000);slice=System.nanoTime();}
                }
                int sx=(int)Math.floor(gx+(dx+0.5-gx)/size),sz=(int)Math.floor(gz+(dz+0.5-gz)/size);
                if(sx<0||sz<0||sx>=map.width()||sz>=map.height()||regions[sz*map.width()+sx]!=shape.id) continue;
                double wx=(dx+0.5-gx)*map.step(),wz=(dz+0.5-gz)*map.step(), score=(wx*wx+wz*wz)/(size*size);
                int i=dz*map.width()+dx;
                var destination = map.cells()[i];
                if (destination == null || regions[i] != shape.id) continue;
                if (score < distance[i]) { distance[i] = score; pixels[i] = BiomePalette.color(goal.biome()); }
            }
        }
        return pixels;
    }
    public static final class Session implements AutoCloseable {
        public record Result(BiomeMapSnapshot map, int[] pixels) {}
        private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "WyWF-ExampleRegions"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t;
        });
        private volatile long revision;
        private volatile boolean paused;
        private volatile Result result;
        private java.util.concurrent.Future<?> task;
        private BiomeMapSnapshot last;
        private List<PreviewAdaptation.Goal> goals = List.of();
        public Result result() { return result; }
        public void paused(boolean value) { paused = value; }
        public synchronized void request(BiomeMapSnapshot map, List<PreviewAdaptation.Goal> next) {
            if (map == last && goals.equals(next)) return;
            last = map; goals = List.copyOf(next); long version = ++revision;
            if (task != null) task.cancel(false);
            task = WORKER.submit(() -> {
                int[] pixels = raster(map, next, () -> {
                    while (paused && revision == version) java.util.concurrent.locks.LockSupport.parkNanos(25_000_000);
                    return revision != version;
                });
                publish(version, new Result(map, pixels));
            });
        }
        private synchronized void publish(long version, Result next) { if (revision == version) result = next; }
        @Override public synchronized void close() { revision++; if (task != null) task.cancel(false); result = null; last = null; }
    }
    private BiomeRegionAdaptation() {}
}
