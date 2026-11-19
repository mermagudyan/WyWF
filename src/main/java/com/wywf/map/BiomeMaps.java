package com.wywf.map;

import com.wywf.core.DimensionSpace;
import com.wywf.search.MinecraftWorldContextFactory;
import com.wywf.search.WorldContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.LockSupport;

public final class BiomeMaps {
    public static final int TILE_SIZE = 16;
    public static final int CACHE_LIMIT = 2048;
    public record Request(long seed, int x, int z, int step, int width, int height, int y, com.wywf.core.DimensionSpace dimension, boolean climate) {
        public Request(long seed, int x, int z, int step, int width, int height, int y, com.wywf.core.DimensionSpace dimension) {
            this(seed, x, z, step, width, height, y, dimension, false);
        }
        public Request(long seed, int x, int z, int step, int width, int height, int y) {
            this(seed, x, z, step, width, height, y, com.wywf.core.DimensionSpace.OVERWORLD);
        }
        public Request(long seed, int x, int z, int step, int width, int height) {
            this(seed, x, z, step, width, height, 64);
        }
        public Request {
            if (y < -64 || y > 320 || step < 1 || width < 1 || height < 1 || width > 512 || height > 512)
                throw new IllegalArgumentException("Invalid preview dimensions");
        }
    }
    private record HeightKey(long seed, String dimension, int step, int x, int z) {}
    private record Tile(long seed, com.wywf.core.DimensionSpace dimension, int step, int x, int z, int y) {}
    private record Job(Session session, Request request, long version) {}
    private static final BiomeMaps INSTANCE = new BiomeMaps();
    private final Object lock = new Object();
    private final LinkedHashMap<Session, Job> pending = new LinkedHashMap<>();
    private MinecraftWorldContextFactory factory;
    private long sliceStart;
    private static volatile int workMillis = 3;
    public static void workMillis(int value) { workMillis = Math.max(1, Math.min(6,value)); }
    private record Source(long seed, com.wywf.core.DimensionSpace dimension) {}
    private volatile long sampleCount;

    private BiomeMaps() {
        Thread thread = new Thread(this::run, "WyWF-Map");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public static final class Scope implements AutoCloseable {
        private volatile boolean closed;
        private volatile int tileCount;
        private final Map<HeightKey, Integer> heights = new LinkedHashMap<>(1024,0.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<HeightKey,Integer> entry) { return size()>32768; }
        };
        private final Map<Tile,BiomeMapSnapshot.Cell[]> cache = new LinkedHashMap<>(256,0.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Tile,BiomeMapSnapshot.Cell[]> entry) { return size()>CACHE_LIMIT; }
        };
        private final Map<Source,MapBiomeSampler> sources = new LinkedHashMap<>(3,0.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Source,MapBiomeSampler> entry) { return size()>2; }
        };

        public int tileCount() { return tileCount; }
        public boolean closed() { return closed; }
        @Override public void close() {
            if(closed)return;closed=true;
            synchronized(INSTANCE.lock){
                INSTANCE.pending.entrySet().removeIf(e->e.getKey().scope==this);
                INSTANCE.releases.add(this);INSTANCE.lock.notifyAll();
            }
        }
        private void release() { cache.clear();heights.clear();sources.clear();tileCount=0; }
    }
    private final java.util.ArrayDeque<Scope> releases = new java.util.ArrayDeque<>();
    public static Scope scope() { return new Scope(); }
    public static Session open() { return new Session(new Scope(),true); }
    public static Session open(Scope scope) { return new Session(scope,false); }
    public static long sampleCount() { return INSTANCE.sampleCount; }

    public static final class Session implements AutoCloseable {
        private final Scope scope;
        private final boolean ownsScope;
        private Session(Scope scope,boolean ownsScope){this.scope=scope;this.ownsScope=ownsScope;}
        private volatile long version;
        private volatile boolean closed, paused, loading;
        private volatile BiomeMapSnapshot snapshot;
        private volatile String error;
        private Request last;

        public BiomeMapSnapshot snapshot() { return snapshot; }
        public String error() { return error; }
        public boolean loading() { return loading; }
        public void paused(boolean value) { paused = value; }

        public void suspend() {
            synchronized (INSTANCE.lock) {
                version++; INSTANCE.pending.remove(this);
                if (loading) last = null;
                loading = false;
            }
        }
        public void invalidate() {
            synchronized (INSTANCE.lock) { version++; last = null; snapshot = null; loading = false; INSTANCE.pending.remove(this); }
        }
        public boolean request(Request request) {
            synchronized (INSTANCE.lock) {
                if (closed || scope.closed || request.equals(last)) return true;
                if (INSTANCE.pending.size() >= 8 && !INSTANCE.pending.containsKey(this)) return false;
                last = request; error = null; snapshot = null; loading = true;
                Job job = new Job(this, request, ++version);
                INSTANCE.pending.remove(this);
                INSTANCE.pending.put(this, job);
                INSTANCE.lock.notifyAll();
                return true;
            }
        }

        @Override public void close() {
            synchronized (INSTANCE.lock) {
                closed = true; version++; loading = false;
                INSTANCE.pending.remove(this);
                snapshot = null;
                if(ownsScope)scope.close();
            }
        }
    }

    private void run() {
        while (true) {
            Job job=null;
            synchronized (lock) {
                while (pending.isEmpty() && releases.isEmpty()) {
                    try { lock.wait(); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
                }
                while(!releases.isEmpty())releases.removeFirst().release();
                if(!pending.isEmpty()){
                    Session owner = pending.keySet().iterator().next();job = pending.remove(owner);
                }
            }
            if(job==null)continue;
            try {
                checkpoint(job);
                if (factory == null) factory = new MinecraftWorldContextFactory();
                MapBiomeSampler source = job.session.scope.sources.computeIfAbsent(new Source(job.request.seed(), job.request.dimension()),
                        key -> new MapBiomeSampler(factory.createDimension(key.seed(),key.dimension())));
                WorldContext context = source.context;
                sliceStart = System.nanoTime();
                render(job, context, source, 8);
                render(job, context, source, 1);
            } catch (CancellationException ignored) {
            } catch (RuntimeException error) {
                synchronized (lock) {
                    if (valid(job)) {
                        job.session.error = error.getClass().getSimpleName();
                        job.session.loading = false; job.session.last = null;
                    }
                }
                org.slf4j.LoggerFactory.getLogger(BiomeMaps.class).warn("Biome preview failed", error);
            }
        }
    }

    private boolean valid(Job job) { return !job.session.closed && !job.session.scope.closed && job.session.version == job.version; }

    private void checkpoint(Job job) {
        if (!valid(job)) throw new CancellationException();
        while (job.session.paused) {
            LockSupport.parkNanos(25_000_000);
            if (!valid(job)) throw new CancellationException();
        }
        if (System.nanoTime() - sliceStart >= workMillis * 1_000_000L) {
            LockSupport.parkNanos(3_000_000);
            sliceStart = System.nanoTime();
        }
    }

    private void publish(Job job, BiomeMapSnapshot snapshot) {
        synchronized (lock) {
            if (valid(job)) {
                job.session.snapshot = snapshot;
                if (snapshot.complete()) job.session.loading = false;
            }
        }
    }

    private int height(Scope scope,WorldContext context, int x, int z, int step) {
        int grid = step <= 2 ? 16 : Math.max(32, step * 4), gx = Math.floorDiv(x, grid), gz = Math.floorDiv(z, grid);
        double fx = Math.floorMod(x, grid) / (double) grid, fz = Math.floorMod(z, grid) / (double) grid;
        int a = corner(scope, context, grid, gx, gz), b = corner(scope, context, grid, gx + 1, gz);
        int c = corner(scope, context, grid, gx, gz + 1), d = corner(scope, context, grid, gx + 1, gz + 1);
        return (int) Math.round((a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz);
    }
    private int corner(Scope scope,WorldContext context, int grid, int x, int z) {
        return scope.heights.computeIfAbsent(new HeightKey(context.seed, context.noiseSettingsKey().identifier().toString(), grid, x, z),
                key -> context.mapHeight(x * grid, z * grid));
    }
    private int endHeight(Scope scope, WorldContext context, int x, int z, int step) {
        int grid = Math.max(4, step);
        int gx = Math.floorDiv(x + step / 2, grid), gz = Math.floorDiv(z + step / 2, grid);
        return scope.heights.computeIfAbsent(new HeightKey(context.seed, DimensionSpace.END.id, grid, gx, gz),
                key -> context.mapHeight(gx * grid + grid / 2, gz * grid + grid / 2));
    }

    private static int dominant(Map<String, Integer> counts) {
        return BiomePalette.color(counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).findFirst().orElseThrow().getKey());
    }
    private BiomeMapSnapshot.Overview overview(MapBiomeSampler source, int x, int z, int y, int step) {
        int probes = step >= 128 ? 4 : 2;
        while (true) {
            Map<String, Integer> counts = new java.util.HashMap<>(), upperCounts = new java.util.HashMap<>();
            float temperature = 0, upperTemperature = 0;
            var detail = new java.util.ArrayList<Integer>(probes * probes);
            var samples = new java.util.ArrayList<BiomeMapSnapshot.Cell>(probes * probes);
            for (int dz = 0; dz < probes; dz++) for (int dx = 0; dx < probes; dx++) {
                var sample = source.point(x + (dx * 2 + 1) * step / (probes * 2),
                        y, z + (dz * 2 + 1) * step / (probes * 2), false);
                detail.add(BiomePalette.color(sample.id()));
                if (y < 64) samples.add(sample);
                counts.merge(sample.id(), 1, Integer::sum); upperCounts.merge(sample.surfaceId(), 1, Integer::sum);
                temperature += sample.temperature(); upperTemperature += sample.surfaceTemperature();
            }
            if (probes == 2 && (counts.containsKey("minecraft:river") || counts.containsKey("minecraft:frozen_river"))) {
                probes = 4; continue;
            }
            return new BiomeMapSnapshot.Overview(dominant(counts), temperature / (probes * probes),
                    dominant(upperCounts), upperTemperature / (probes * probes), java.util.List.copyOf(detail), java.util.List.copyOf(samples));
        }
    }
    private void render(Job job, WorldContext context, MapBiomeSampler source, int lod) {
        Request request = job.request;
        int step = request.step * lod;
        int startX = Math.floorDiv(request.x, step) * step;
        int startZ = Math.floorDiv(request.z, step) * step;
        int width = Math.min(512, (request.width + lod - 1) / lod + 1);
        int height = Math.min(512, (request.height + lod - 1) / lod + 1);
        BiomeMapSnapshot.Cell[] cells = new BiomeMapSnapshot.Cell[width * height];
        int firstTileX = Math.floorDiv(startX, step * TILE_SIZE);
        int firstTileZ = Math.floorDiv(startZ, step * TILE_SIZE);
        int lastTileX = Math.floorDiv(startX + (width - 1) * step, step * TILE_SIZE);
        int lastTileZ = Math.floorDiv(startZ + (height - 1) * step, step * TILE_SIZE);
        long publishAt = 0;
        var jobs = new java.util.ArrayList<Tile>();
        for (int tz = firstTileZ; tz <= lastTileZ; tz++) for (int tx = firstTileX; tx <= lastTileX; tx++)
            jobs.add(new Tile(request.seed, request.dimension, step, tx, tz, request.y));
        double centerX = (startX / (double) step + width * .5) / TILE_SIZE;
        double centerZ = (startZ / (double) step + height * .5) / TILE_SIZE;
        jobs.sort(java.util.Comparator.comparingDouble(tile ->
                Math.hypot(tile.x + .5 - centerX, tile.z + .5 - centerZ)));
        for (Tile key : jobs) {
            checkpoint(job);
            int tx = key.x, tz = key.z;
            BiomeMapSnapshot.Cell[] tile = job.session.scope.cache.get(key);
            if (tile == null) {
                tile = new BiomeMapSnapshot.Cell[TILE_SIZE * TILE_SIZE];
                job.session.scope.cache.put(key,tile);job.session.scope.tileCount=job.session.scope.cache.size();
            }
            for (int i = 0; i < tile.length; i++) {
                if (i % 8 == 0) checkpoint(job);
                int bx = (tx * TILE_SIZE + i % TILE_SIZE) * step;
                int bz = (tz * TILE_SIZE + i / TILE_SIZE) * step;
                if (bx < startX || bz < startZ || bx >= startX + width * step || bz >= startZ + height * step) continue;
                var cached = tile[i];
                if (cached != null) {
                    if (request.climate && Float.isNaN(cached.climate())) {
                        tile[i] = new BiomeMapSnapshot.Cell(cached.id(), cached.temperature(), cached.surfaceId(),
                                cached.surfaceTemperature(), cached.overview(), cached.height(), source.climate(bx, request.y, bz));
                    }
                    continue;
                }
                BiomeMapSnapshot.Cell point = source.point(bx,request.y,bz,step < 4);
                if (step >= 8) point = new BiomeMapSnapshot.Cell(point.id(), point.temperature(),
                        point.surfaceId(), point.surfaceTemperature(), overview(source,bx,bz,request.y,step));
                int terrainHeight = Integer.MIN_VALUE;
                if (request.dimension == DimensionSpace.END) {
                    terrainHeight = endHeight(job.session.scope, context, bx, bz, step);
                } else if (request.dimension == DimensionSpace.OVERWORLD && request.y >= 64 && step <= 32) {
                    terrainHeight = height(job.session.scope, context, bx, bz, step);
                }
                tile[i] = new BiomeMapSnapshot.Cell(point.id(), point.temperature(), point.surfaceId(),
                        point.surfaceTemperature(), point.overview(), terrainHeight,
                        request.climate ? source.climate(bx, request.y, bz) : Float.NaN);
                sampleCount++;
            }
            int offsetX = tx * TILE_SIZE - Math.floorDiv(startX, step);
            int offsetZ = tz * TILE_SIZE - Math.floorDiv(startZ, step);
            for (int z = 0; z < TILE_SIZE; z++) for (int x = 0; x < TILE_SIZE; x++) {
                int cx = offsetX + x, cz = offsetZ + z;
                if (cx >= 0 && cz >= 0 && cx < width && cz < height)
                    cells[cz * width + cx] = tile[z * TILE_SIZE + x];
            }
            if (lod == 1 && System.nanoTime() >= publishAt && valid(job)) {
                publish(job, new BiomeMapSnapshot(startX, startZ, step, width, height, cells.clone(), false, request.y));
                publishAt = System.nanoTime() + 200_000_000;
            }
        }
        checkpoint(job);
        publish(job, new BiomeMapSnapshot(startX, startZ, step, width, height, cells, lod == 1, request.y));
    }
}
