package com.wywf.verify;

import com.mojang.serialization.Codec;
import com.wywf.core.DimensionSpace;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.material.MapColor;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class SavedSurfaceReader {
    private static final String DEFAULT_BIOME = "minecraft:plains";
    private static final int SECTOR_BYTES = 4096;
    private static final Codec<PalettedContainer<BlockState>> BLOCKS = PalettedContainer.codecRW(
            BlockState.CODEC, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY), Blocks.AIR.defaultBlockState());
    private static final Codec<PalettedContainerRO<String>> BIOMES = PalettedContainer.codecRO(
            Codec.STRING, Strategy.createForBiomes(new IdMapper<>()), DEFAULT_BIOME);
    private final Path directory;
    private final DimensionSpace dimension;
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Optional<Chunk>> chunks =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private record Section(PalettedContainer<BlockState> blocks, PalettedContainerRO<String> biomes) {}
    private record Chunk(int minSection, SimpleBitStorage heights, Section[] sections) {
        int maxY() { return (minSection + (minSection == -4 ? 24 : 16)) * 16 - 1; }

        Section section(int y) {
            int index = y - minSection;
            return index < 0 || index >= sections.length ? null : sections[index];
        }

        BlockState at(int x, int y, int z) {
            var section = section(y >> 4);
            return section == null ? Blocks.AIR.defaultBlockState() : section.blocks.get(x & 15, y & 15, z & 15);
        }
    }

    private SavedSurfaceReader(Path world, DimensionSpace dimension) {
        this.dimension = dimension;
        String folder = dimension.id.substring("minecraft:".length());
        directory = world.resolve("dimensions/minecraft").resolve(folder).resolve("region");
    }

    public static Map<Long, SurfaceSnapshot> readVisited(Path world, long seed, DimensionSpace dimension, List<Long> keys) {
        if (hasDatapacks(world)) return Map.of();
        var reader = new SavedSurfaceReader(world, dimension);
        var result = new HashMap<Long, SurfaceSnapshot>();
        for (long key : keys) {
            var tile = reader.tile(seed, (int)(key >> 32) << 4, (int)key << 4);
            if (tile != null) result.put(key, tile);
        }
        return result;
    }

    public static List<SurfaceSnapshot> read(Path world, long seed, DimensionSpace dimension, int x, int z) {
        if (hasDatapacks(world)) return List.of();
        var reader = new SavedSurfaceReader(world, dimension);
        var result = new ArrayList<SurfaceSnapshot>();
        int startX = (x - 32) & ~15, startZ = (z - 32) & ~15;
        for (int dz = 0; dz < 64 && !Thread.currentThread().isInterrupted(); dz += 16)
            for (int dx = 0; dx < 64; dx += 16) {
                var tile = reader.tile(seed, startX + dx, startZ + dz);
                if (tile != null) result.add(tile);
            }
        return result;
    }

    public static List<ReliefTile> reliefPage(Path world, long seed, ReliefRequest request) {
        if (hasDatapacks(world)) return List.of();
        var reader = new SavedSurfaceReader(world, request.dimension());
        var result = new ArrayList<ReliefTile>();
        var manager = new BiomeManager(reader::noiseBiome, BiomeManager.obfuscateSeed(seed));
        for (int dz = 0; dz < ReliefBlocks.SIZE; dz += 16) for (int dx = 0; dx < ReliefBlocks.SIZE; dx += 16) {
            int x = request.x() + dx, z = request.z() + dz;
            var chunk = reader.chunk(x >> 4, z >> 4);
            if (chunk != null) {
                int bottom = chunk.minSection * 16;
                int fog = request.dimension() == DimensionSpace.NETHER && request.containsFocus(x, z)
                        ? reader.fogAt(seed, request.focus()) : 0;
                result.add(ReliefBlocks.tile(request.dimension(), x, z, request.cut(), bottom, chunk.maxY(), EmptyBlockGetter.INSTANCE,
                        p -> chunk.at(p.getX(), p.getY(), p.getZ()),
                        (wx, wz) -> bottom + chunk.heights.get((wz & 15) * 16 + (wx & 15)) - 1,
                        (wx, wz) -> reader.reliefHeight(wx, wz, x, z, chunk),
                        p -> reader.riverAt(manager, p), fog));
            }
        }
        return result;
    }

    private int reliefHeight(int wx, int wz, int x, int z, Chunk current) {
        var neighbor = wx >> 4 == x >> 4 && wz >> 4 == z >> 4 ? current : chunk(wx >> 4, wz >> 4);
        return neighbor == null ? Integer.MIN_VALUE
                : neighbor.minSection * 16 + neighbor.heights.get((wz & 15) * 16 + (wx & 15)) - 1;
    }

    private boolean riverAt(BiomeManager manager, BlockPos pos) {
        try {
            var biome = manager.getBiome(pos);
            return biome.is(Biomes.RIVER) || biome.is(Biomes.FROZEN_RIVER);
        } catch (MissingBiome unavailable) {
            var chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
            var section = chunk == null ? null : chunk.section(pos.getY() >> 4);
            if (section == null) return false;
            var id = section.biomes.get(pos.getX() >> 2 & 3, pos.getY() >> 2 & 3, pos.getZ() >> 2 & 3);
            return id.equals("minecraft:river") || id.equals("minecraft:frozen_river");
        }
    }

    private int fogAt(long seed, BlockPos focus) {
        var manager = new BiomeManager(this::noiseBiome, BiomeManager.obfuscateSeed(seed));
        try {
            return MinecraftReliefFog.biome(manager.getBiome(focus).value());
        } catch (MissingBiome unavailable) {
            try {
                return MinecraftReliefFog.biome(noiseBiome(focus.getX() >> 2, focus.getY() >> 2, focus.getZ() >> 2).value());
            } catch (MissingBiome missing) {
                return 0;
            }
        }
    }

    private static boolean hasDatapacks(Path world) {
        Path packs = world.resolve("datapacks");
        if (!Files.isDirectory(packs)) return false;
        try (var entries = Files.list(packs)) {
            return entries.findAny().isPresent();
        } catch (IOException unavailable) {
            return true;
        }
    }

    private Chunk chunk(int x, int z) {
        return chunks.computeIfAbsent(SurfaceCache.key(x << 4, z << 4), key -> {
            try {
                return Optional.ofNullable(decode(readChunk(x, z)));
            } catch (IOException | IllegalArgumentException unreadable) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    public static List<MapFeature> endReturnGateways(Path world, int x, int z) {
        var reader = new SavedSurfaceReader(world, DimensionSpace.END);
        var features = new ArrayList<MapFeature>();
        for (int cz = z >> 4; cz < (z >> 4) + 4; cz++) for (int cx = x >> 4; cx < (x >> 4) + 4; cx++) {
            if (!EndReturnGateways.mayContain(cx, cz)) continue;
            try {
                var chunk = reader.readChunk(cx, cz);
                if (chunk == null) continue;
                for (var entity : chunk.getListOrEmpty("block_entities").compoundStream().toList()) {
                    if (!entity.getStringOr("id", "").equals("minecraft:end_gateway")) continue;
                    var source = new BlockPos(entity.getIntOr("x", 0), entity.getIntOr("y", 0), entity.getIntOr("z", 0));
                    if ((source.getX() >> 4) == cx && (source.getZ() >> 4) == cz)
                        EndReturnGateways.fromGateway(source, entity).ifPresent(features::add);
                }
            } catch (IOException | IllegalArgumentException error) {
                com.wywf.WYWFClient.LOGGER.debug("Could not read saved End gateways at {}, {}", cx, cz, error);
            }
        }
        return List.copyOf(features);
    }

    private CompoundTag readChunk(int x, int z) throws IOException {
        Path region = directory.resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
        if (!Files.isRegularFile(region)) return null;
        try (var file = FileChannel.open(region)) {
            ByteBuffer header = ByteBuffer.allocate(4);
            if (!readFully(file, header, 4L * ((x & 31) + (z & 31) * 32))) return null;
            int entry = header.getInt(0), offset = entry >>> 8, sectors = entry & 255;
            if (offset < 2 || sectors == 0) return null;
            long start = (long) offset * SECTOR_BYTES;
            ByteBuffer chunkHeader = ByteBuffer.allocate(5);
            if (!readFully(file, chunkHeader, start)) return null;
            int length = chunkHeader.getInt(0) - 1;
            int format = chunkHeader.get(4) & 255;
            if (length < 1 || length > sectors * SECTOR_BYTES - 5 || (format & 128) != 0) return null;
            var compression = RegionFileVersion.fromId(format);
            if (compression == null || compression == RegionFileVersion.VERSION_CUSTOM) return null;
            ByteBuffer payload = ByteBuffer.allocate(length);
            if (!readFully(file, payload, start + 5)) return null;
            try (var input = new DataInputStream(compression.wrap(new ByteArrayInputStream(payload.array())))) {
                var root = NbtIo.read(input, NbtAccounter.create(16L * 1024 * 1024));
                if (root.getIntOr("DataVersion", -1) != SharedConstants.getCurrentVersion().dataVersion().version()
                        || root.getIntOr("xPos", Integer.MIN_VALUE) != x || root.getIntOr("zPos", Integer.MIN_VALUE) != z
                        || !root.getStringOr("Status", "").equals("minecraft:full")) return null;
                return root;
            }
        }
    }

    private static boolean readFully(FileChannel file, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = file.read(buffer, position);
            if (read <= 0) return false;
            position += read;
        }
        return true;
    }

    private static Chunk decode(CompoundTag root) {
        if (root == null) return null;
        int minSection = root.getIntOr("yPos", Integer.MIN_VALUE);
        if (minSection != -4 && minSection != 0) return null;
        int sectionCount = minSection == -4 ? 24 : 16;
        var packed = root.getCompoundOrEmpty("Heightmaps").getLongArray("WORLD_SURFACE").orElse(null);
        if (packed == null) return null;
        var sections = new Section[sectionCount];
        for (var section : root.getListOrEmpty("sections").compoundStream().toList()) {
            int y = section.getByteOr("Y", (byte) -128);
            if (y < minSection || y >= minSection + sectionCount) continue;
            var blocks = BLOCKS.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("block_states")).result();
            var biomes = BIOMES.parse(NbtOps.INSTANCE, section.getCompoundOrEmpty("biomes")).result();
            if (blocks.isEmpty() || biomes.isEmpty()) return null;
            sections[y - minSection] = new Section(blocks.orElseThrow(), biomes.orElseThrow());
        }
        return new Chunk(minSection, new SimpleBitStorage(9, 256, packed), sections);
    }

    private static final class SavedBiomeLookup {
        static final net.minecraft.core.HolderLookup.RegistryLookup<net.minecraft.world.level.biome.Biome> LOOKUP =
                VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME);
    }
    private net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> noiseBiome(int x, int y, int z) {
        var chunk = chunk(x >> 2, z >> 2);
        if (chunk == null) throw new MissingBiome();
        var section = chunk.section(Math.clamp(y >> 2, chunk.minSection, chunk.minSection + 23));
        if (section == null) throw new MissingBiome();
        var id = Identifier.tryParse(section.biomes.get(x & 3, y & 3, z & 3));
        if (id == null) throw new MissingBiome();
        return SavedBiomeLookup.LOOKUP.get(ResourceKey.create(Registries.BIOME, id)).orElseThrow(MissingBiome::new);
    }
    private static final class MissingBiome extends RuntimeException {
        MissingBiome() { super(null, null, false, false); }
    }

    private SurfaceSnapshot tile(long seed, int x, int z) {
        var chunk = chunk(x >> 4, z >> 4);
        if (chunk == null) return null;
        var manager = new BiomeManager(this::noiseBiome, BiomeManager.obfuscateSeed(seed));
        int[] colors = new int[256], heights = new int[256];
        String[] blocks = new String[256], biomes = new String[256];
        float[] temperatures = new float[256], climate = new float[256];
        Arrays.fill(climate, Float.NaN);
        var pos = new BlockPos.MutableBlockPos();
        try {
            for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                int index = dz * 16 + dx;
                int wx = x + dx, wz = z + dz, bottom = chunk.minSection * 16;
                int y = Math.clamp(bottom + chunk.heights.get(index) - 1, bottom, bottom + 383);
                var state = chunk.at(wx, y, wz);
                var color = state.getMapColor(EmptyBlockGetter.INSTANCE, pos.set(wx, y, wz));
                while (y > bottom && (state.isAir() || color == MapColor.NONE)) {
                    state = chunk.at(wx, --y, wz);
                    color = state.getMapColor(EmptyBlockGetter.INSTANCE, pos.set(wx, y, wz));
                }
                var biome = manager.getBiome(pos);
                colors[index] = ReliefBlocks.surfaceColor(state, EmptyBlockGetter.INSTANCE, pos, bottom,
                        p -> chunk.at(p.getX(), p.getY(), p.getZ()));
                heights[index] = dimension == DimensionSpace.END && state.isAir() ? Integer.MIN_VALUE : y;
                blocks[index] = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                biomes[index] = biome.unwrapKey().orElseThrow().identifier().toString();
                temperatures[index] = biome.value().getBaseTemperature();
            }
        } catch (MissingBiome unavailable) {
            return null;
        }
        return new SurfaceSnapshot(seed, x, z, 16, colors, heights, blocks, biomes, temperatures, climate, true);
    }
}
