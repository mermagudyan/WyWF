package com.wywf.verify;

import com.google.gson.Gson;
import com.wywf.core.*;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class SavedWorldMaps {
    public static final String FILE = "wywf/map.json";
    public static final String TAG_FILE = "wywf-world.json";
    public static final String TAG = "\u2764\uFE0F";
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_DISCOVERY_BYTES = 2 * 1024 * 1024;
    private static final String DISCOVERIES_FILE = "wywf/discoveries.json";
    private static final Gson JSON = new Gson();
    private record Options(SearchConfig.SearchCenter center, int structureRadius, int biomeRadius, int step,
                           KeywordDictionary.Lang language) {
        static Options of(SearchConfig c) { return new Options(c.searchCenter(), c.searchRadiusChunks(),
                c.biomeCheckRadiusChunks(), c.biomeSampleStepChunks(), c.queryLanguage()); }
        SearchConfig restore() {
            if (center == null || language == null || structureRadius < 0 || structureRadius > 1000000
                    || biomeRadius < 0 || biomeRadius > 1000000 || step < 1 || step > 1000000)
                throw new IllegalArgumentException("Invalid saved map options");
            return SearchConfig.defaults().searchCenter(center).searchRadiusChunks(structureRadius)
                    .biomeCheckRadiusChunks(biomeRadius).biomeSampleStepChunks(step).queryLanguage(language);
        }
    }
    private record Found(long seed, int centerX, int centerZ, int structureX, int structureZ, String description,
                         List<String> structures, List<String> biomes, Map<String,int[]> positions, Map<String,Integer> distances) {
        static Found of(SearchResult r) { return new Found(r.seed, r.centerX, r.centerZ, r.structureX, r.structureZ,
                r.primaryDescription, r.matchedStructures, r.matchedBiomes, r.structurePositions, r.biomeDistances); }
        SearchResult restore() {
            if (structures.size() > 256 || biomes.size() > 256 || positions.size() > 256 || distances.size() > 256
                    || positions.values().stream().anyMatch(p -> p == null || p.length != 2))
                throw new IllegalArgumentException("Invalid saved map targets");
            return new SearchResult(seed, centerX, centerZ, structureX, structureZ, description,
                    structures, biomes, "", positions, distances);
        }
    }
    private record Tag(int schema, String tag, String worldId) {}
    private record Document(int schema, String worldId, String gameVersion, String query, Options options,
                            Found result, VerificationSummary summary, SurfaceSnapshot surface,
                            List<VerifiedAnchor> anchors, List<LootWitness> loot) {}
    public record Discoveries(long seed, String version, List<VerifiedAnchor> anchors, List<MapFeature> features) {
        public Discoveries {
            anchors = List.copyOf(anchors);
            features = List.copyOf(features);
        }
    }

    public static void write(Path directory, SavedWorldMap map) throws IOException {
        validateSurface(map.surface(), map.result().seed);
        Document data = new Document(2, map.worldId(), map.gameVersion(), map.query().normalized(), Options.of(map.config()),
                Found.of(map.result()), map.summary(), map.surface(), map.anchors(), map.loot());
        byte[] bytes = JSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Saved map exceeds its file budget");
        Files.createDirectories(directory.resolve("wywf"));
        atomicWrite(directory.resolve(FILE), bytes);
        atomicWrite(directory.resolve(TAG_FILE), JSON.toJson(new Tag(1, TAG, map.worldId())).getBytes(StandardCharsets.UTF_8));
    }

    public static Optional<Discoveries> readDiscoveries(Path directory, long seed, String version) throws IOException {
        Path file = directory.resolve(DISCOVERIES_FILE);
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_DISCOVERY_BYTES) return Optional.empty();
        try (var stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(MAX_DISCOVERY_BYTES + 1);
            if (bytes.length > MAX_DISCOVERY_BYTES) return Optional.empty();
            var data = JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Discoveries.class);
            if (data == null || data.seed() != seed || !version.equals(data.version())
                    || data.anchors().size() > 4096 || data.features().size() > 4096) return Optional.empty();
            return Optional.of(data);
        } catch (RuntimeException error) {
            throw new IOException("Invalid saved map discoveries", error);
        }
    }

    public static void writeDiscoveries(Path directory, Discoveries data) throws IOException {
        if (data.anchors().isEmpty() && data.features().isEmpty()) return;
        byte[] bytes = JSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_DISCOVERY_BYTES || data.anchors().size() > 4096 || data.features().size() > 4096)
            throw new IOException("Saved map discoveries exceed their file budget");
        Path file = directory.resolve(DISCOVERIES_FILE);
        Files.createDirectories(file.getParent());
        atomicWrite(file, bytes);
    }

    private static void atomicWrite(Path file, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), ".wywf-map-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static Optional<SavedWorldMap> read(Path directory, QueryParser parser, String gameVersion) throws IOException {
        if (!Files.isDirectory(directory)) return Optional.empty();
        Path file = directory.resolve(FILE);
        Path marker = directory.resolve(TAG_FILE);
        if (!Files.exists(marker)) return Files.exists(file) ? Optional.empty() : legacy(directory, gameVersion);
        if (!Files.isRegularFile(marker) || Files.size(marker) > 4096 || !Files.exists(file)) return Optional.empty();
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return Optional.empty();
        try {
            Tag markerData;
            try (var stream = Files.newInputStream(marker)) {
                byte[] tagBytes = stream.readNBytes(4097);
                if (tagBytes.length > 4096) return Optional.empty();
                markerData = JSON.fromJson(new String(tagBytes, StandardCharsets.UTF_8), Tag.class);
            }
            if (markerData == null || markerData.schema != 1 || !TAG.equals(markerData.tag)) return Optional.empty();
            byte[] bytes;
            try (var stream = Files.newInputStream(file)) { bytes = stream.readNBytes((int) MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) return Optional.empty();
            Document d = JSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Document.class);
            if (d == null || (d.schema != 1 && d.schema != 2) || !Objects.equals(markerData.worldId, d.worldId)
                    || !gameVersion.equals(d.gameVersion)
                    || d.query == null || d.query.length() > 20000 || d.anchors.size() > 4096 || d.loot.size() > 4096)
                return Optional.empty();
            var query = parser.parse(d.query);
            if (query.error() != null || !query.ignoredWords().isEmpty()) return Optional.empty();
            SearchResult result = d.result.restore();
            validateSurface(d.surface, result.seed);
            if (d.summary == null || !d.summary.terminal()) return Optional.empty();
            // Schema 1 mixed query matches with discoveries and cannot prove which anchors qualified
            var matches = d.schema == 2 ? d.anchors : List.<VerifiedAnchor>of();
            var legacyAnchors = d.schema == 1 ? d.anchors : List.<VerifiedAnchor>of();
            return Optional.of(new SavedWorldMap(d.worldId, d.gameVersion, result, query, d.options.restore(),
                    d.summary, d.surface, matches, d.loot, legacyAnchors));
        } catch (RuntimeException error) { return Optional.empty(); }
    }

    private static Optional<SavedWorldMap> legacy(Path directory, String version) throws IOException {
        if (!PreviewDirectory.accepted(directory)) return Optional.empty();
        Path dataFile = directory.resolve("level.dat");
        if (!Files.isRegularFile(dataFile)) return Optional.empty();
        var data = NbtIo.readCompressed(dataFile, NbtAccounter.create(16L * 1024 * 1024)).getCompoundOrEmpty("Data");
        if (!version.equals(data.getCompoundOrEmpty("Version").getStringOr("Name", ""))) return Optional.empty();
        var settings = data.getCompoundOrEmpty("WorldGenSettings");
        Path settingsFile = directory.resolve("data/minecraft/world_gen_settings.dat");
        if (settings.isEmpty() && Files.isRegularFile(settingsFile)) settings = NbtIo.readCompressed(
                settingsFile, NbtAccounter.create(16L * 1024 * 1024)).getCompoundOrEmpty("data");
        var seed = settings.getLong("seed");
        if (seed.isEmpty()) return Optional.empty();
        int x = data.getIntOr("SpawnX", 0), y = data.getIntOr("SpawnY", 64), z = data.getIntOr("SpawnZ", 0);
        int[] spawn = data.getCompoundOrEmpty("spawn").getIntArray("pos").orElse(new int[0]);
        if (spawn.length == 3) { x = spawn[0]; y = spawn[1]; z = spawn[2]; }
        var result = new SearchResult(seed.get(), x, z, x, z, "", List.of(), List.of(), "");
        var summary = new VerificationSummary(VerificationSummary.Status.JOINED, x, y, z, List.of(), "");
        String id = UUID.nameUUIDFromBytes(directory.getFileName().toString().getBytes(StandardCharsets.UTF_8)).toString();
        return Optional.of(new SavedWorldMap(id, version, result, new ParsedQuery("", List.of()), SearchConfig.defaults(),
                summary, null, List.of(), List.of()));
    }

    private static void validateSurface(SurfaceSnapshot s, long seed) {
        if (s == null) return;
        int n = s.size() * s.size();
        if (s.size() < 1 || s.size() > 160 || s.seed() != seed || s.colors().length != n || s.heights().length != n
                || s.blocks().length != n || s.biomes().length != n || s.temperatures().length != n || s.climate().length != n)
            throw new IllegalArgumentException("Invalid saved surface snapshot");
        for (int i = 0; i < n; i++) if (!Float.isFinite(s.temperatures()[i]) || !Float.isFinite(s.climate()[i]))
            throw new IllegalArgumentException("Invalid saved climate sample");
    }
    private SavedWorldMaps() {}
}