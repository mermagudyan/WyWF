package com.wywf.client;

import com.wywf.WYWFClient;
import com.wywf.core.DimensionSpace;
import com.wywf.verify.SavedSurfaceReader;
import com.wywf.verify.SurfaceCache;
import com.wywf.verify.SurfaceSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class PlayerAtlas {
    private static final long INDEX_LIMIT = 32L * 1024 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(
            task -> Thread.ofPlatform().daemon().name("WyWF-Atlas").unstarted(task));
    private static final Map<String, PlayerAtlas> DIMENSIONS = new HashMap<>();
    private static Object connection;
    private ClientLevel level;
    private final Path directory, index;
    private final DimensionSpace dimension;
    private final long seed;
    private final Set<Long> visited = new java.util.HashSet<>();
    private final Set<Long> absent = new java.util.HashSet<>();
    private final Map<Long, SurfaceSnapshot> tiles = new LinkedHashMap<>(128, .75f, true);
    private final List<Long> pendingWrites = new ArrayList<>();
    private boolean reading;
    private long revision, lastWrite;

    private PlayerAtlas(ClientLevel level, DimensionSpace dimension, Path directory, long seed) {
        this.level = level;
        this.dimension = dimension;
        this.directory = directory;
        this.seed = seed;
        index = directory == null || dimension == null ? null : directory.resolve("wywf/visited-chunks.bin");
        if (index != null) IO.execute(() -> readIndex(Minecraft.getInstance()));
    }

    public static PlayerAtlas current(Minecraft client) {
        if (client.getConnection() != connection) {
            flush();
            DIMENSIONS.clear();
            connection = client.getConnection();
        }
        if (client.level == null || client.player == null || connection == null) return null;
        var local = client.getSingleplayerServer();
        String key = client.level.dimension().identifier().toString();
        var dimension = Arrays.stream(DimensionSpace.values()).filter(candidate -> candidate.id.equals(key)).findFirst().orElse(null);
        var atlas = DIMENSIONS.computeIfAbsent(key, id -> new PlayerAtlas(client.level, dimension,
                local == null ? null : local.getWorldPath(LevelResource.ROOT),
                local == null ? 0 : local.overworld().getSeed()));
        atlas.level = client.level;
        return atlas;
    }

    public static void tick(Minecraft client) {
        var atlas = current(client);
        if (atlas == null || client.player.isSpectator()) return;
        var position = client.player.blockPosition();
        atlas.capture(position.getX(), position.getZ(), true);
        if (System.nanoTime() - atlas.lastWrite > 2_000_000_000L) atlas.write();
    }

    public static void flush() { DIMENSIONS.values().forEach(PlayerAtlas::write); }

    private boolean capture(int x, int z, boolean markVisited) {
        long key = SurfaceCache.key(x, z);
        if (tiles.containsKey(key)) {
            if (markVisited) visit(key);
            return false;
        }
        var chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return false;
        int startX = x & ~15, startZ = z & ~15;
        int[] colors = new int[256], heights = new int[256];
        String[] blocks = new String[256], biomes = new String[256];
        float[] temperatures = new float[256], climate = new float[256];
        Arrays.fill(climate, Float.NaN);
        var biomeColors = new java.util.HashMap<String, Integer>();
        var pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int i = dz * 16 + dx;
            int y = Math.max(level.getMinY(), chunk.getHeight(Heightmap.Types.WORLD_SURFACE, dx, dz));
            pos.set(startX + dx, y, startZ + dz);
            var block = chunk.getBlockState(pos);
            if (block.isAir()) {
                y = Math.max(level.getMinY(), y - 1);
                block = chunk.getBlockState(pos.setY(y));
            }
            var biome = level.getBiome(pos);
            biomes[i] = biome.unwrapKey().map(id -> id.identifier().toString()).orElse("minecraft:plains");
            dev.diylib.client.render.DiyBlockColors.recordBiomeColor(biomeColors, biomes[i], biome.value());
            temperatures[i] = biome.value().getBaseTemperature();
            heights[i] = dimension == DimensionSpace.END && block.isAir() ? Integer.MIN_VALUE : y;
            colors[i] = com.wywf.verify.ReliefBlocks.surfaceColor(block, level, pos, level.getMinY(), chunk::getBlockState);
            blocks[i] = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
        }
        keep(key, new SurfaceSnapshot(seed, startX, startZ, 16, colors, heights, blocks, biomes, temperatures, climate, true, biomeColors));
        absent.remove(key);
        if (markVisited) visit(key);
        return true;
    }

    private void visit(long key) {
        if (visited.add(key) && index != null) pendingWrites.add(key);
    }

    private void keep(long key, SurfaceSnapshot tile) {
        tiles.put(key, tile);
        while (tiles.size() > 1536) tiles.remove(tiles.keySet().iterator().next());
        revision++;
    }

    private void readIndex(Minecraft client) {
        var stored = new HashSet<Long>();
        try {
            if (!Files.isRegularFile(index) || Files.size(index) > INDEX_LIMIT) return;
            try (var stream = new DataInputStream(new BufferedInputStream(Files.newInputStream(index)))) {
                while (true) {
                    int ordinal = stream.readUnsignedByte();
                    long key = stream.readLong();
                    if (ordinal == dimension.ordinal()) stored.add(key);
                }
            }
        } catch (EOFException finished) {
            client.execute(() -> { visited.addAll(stored); revision++; });
        } catch (IOException error) { WYWFClient.LOGGER.debug("Could not restore WyWF visited chunks", error); }
    }

    private void write() {
        lastWrite = System.nanoTime();
        if (pendingWrites.isEmpty()) return;
        var batch = List.copyOf(pendingWrites);
        pendingWrites.clear();
        IO.execute(() -> {
            try {
                Files.createDirectories(index.getParent());
                try (var stream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(index,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)))) {
                    for (long key : batch) {
                        stream.writeByte(dimension.ordinal());
                        stream.writeLong(key);
                    }
                }
            } catch (IOException error) {
                WYWFClient.LOGGER.debug("Could not save WyWF visited chunks", error);
                Minecraft.getInstance().execute(() -> pendingWrites.addAll(batch));
            }
        });
    }

    long revision() {
        return revision;
    }

    Map<Long, SurfaceSnapshot> visible(boolean sampleLoaded, int minX, int minZ, int maxX, int maxZ,
                                       double focusX, double focusZ) {
        var client = Minecraft.getInstance();
        if (sampleLoaded && client.player != null)
            captureLoaded(client, minX, minZ, maxX, maxZ, focusX, focusZ);
        Map<Long, SurfaceSnapshot> visible = new LinkedHashMap<>();
        for (var entry : tiles.entrySet()) {
            var tile = entry.getValue();
            if (tile.x() >= minX && tile.x() <= maxX && tile.z() >= minZ && tile.z() <= maxZ)
                visible.put(entry.getKey(), tile);
        }
        if (directory != null && dimension != null && !reading) {
            var missing = new ArrayList<Long>();
            for (long key : visited) {
                int x = (int)(key >> 32) << 4, z = (int)key << 4;
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ && !tiles.containsKey(key) && !absent.contains(key))
                    missing.add(key);
            }
            missing.sort(Comparator.comparingDouble(key -> Math.hypot(((int)(key >> 32) << 4) - focusX, ((int)(long)key << 4) - focusZ)));
            if (!missing.isEmpty()) {
                reading = true;
                var batch = List.copyOf(missing.subList(0, Math.min(16, missing.size())));
                var biomes = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
                IO.execute(() -> {
                    Map<Long, SurfaceSnapshot> found;
                    try { found = SavedSurfaceReader.readVisited(directory, seed, dimension, batch, biomes); }
                    catch (RuntimeException error) { found = Map.of(); }
                    var result = found;
                    Minecraft.getInstance().execute(() -> {
                        for (long key : batch) {
                            var tile = result.get(key);
                            if (tile != null) { if (!tiles.containsKey(key)) keep(key, tile); }
                            else absent.add(key);
                        }
                        reading = false;
                    });
                });
            }
        }
        return visible;
    }

    private void captureLoaded(Minecraft client, int minX, int minZ, int maxX, int maxZ,
                               double focusX, double focusZ) {
        int radius = client.options.getEffectiveRenderDistance() * 16;
        int playerX = client.player.getBlockX() & ~15, playerZ = client.player.getBlockZ() & ~15;
        var candidates = new ArrayList<Long>();
        for (int z = Math.max(minZ, playerZ - radius); z <= Math.min(maxZ, playerZ + radius); z += 16)
            for (int x = Math.max(minX, playerX - radius); x <= Math.min(maxX, playerX + radius); x += 16) {
                long key = SurfaceCache.key(x, z);
                if (!tiles.containsKey(key) && level.getChunkSource().hasChunk(x >> 4, z >> 4))
                    candidates.add(key);
            }
        candidates.sort(Comparator.comparingDouble(key ->
                Math.hypot(((int)(key >> 32) << 4) + 8 - focusX, ((int)(long)key << 4) + 8 - focusZ)));
        int budget = 4;
        for (long key : candidates)
            if (capture((int)(key >> 32) << 4, (int)key << 4, false) && --budget == 0) break;
    }
}
