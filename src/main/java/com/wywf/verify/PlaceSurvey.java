package com.wywf.verify;

import com.wywf.core.DimensionSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import java.util.*;
import java.util.function.BooleanSupplier;

public final class PlaceSurvey {
    public enum Kind {
        VILLAGES, BIOME_BORDER, RIVER_MOUTH, SPAWNERS, SINGLE_SPAWNER, GEODES, IRON_VEINS, COPPER_VEINS, SURFACE_LAVA, SLIME_CHUNKS;
        public boolean hasMobFilter() { return this == SPAWNERS || this == SINGLE_SPAWNER; }
        public int maxArea() { return this == VILLAGES || this == SLIME_CHUNKS ? 4096 : 512; }
        public boolean overworldOnly() {
            return switch (this) {
                case BIOME_BORDER, SPAWNERS, SINGLE_SPAWNER -> false;
                default -> true;
            };
        }
    }
    public record Area(int minX, int minZ, int maxX, int maxZ) {
        public Area {
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Empty survey area");
        }
        public boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
        int x() { return minX + (maxX - minX) / 2; }
        int z() { return minZ + (maxZ - minZ) / 2; }
    }
    public record Request(Kind kind, DimensionSpace dimension, Area area, int count, int distance, String first, String second) {
        public Request {
            int maxSize = kind.maxArea();
            if ((long)area.maxX() - area.minX() >= maxSize || (long)area.maxZ() - area.minZ() >= maxSize
                    || count < 2 || count > 8 || distance < 1 || distance > 2048)
                throw new IllegalArgumentException("Invalid survey limits");
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(first);
            Objects.requireNonNull(second);
        }
    }
    public record Point(int x, int y, int z, String type, int activationRange) {
        public Point(int x, int y, int z, String type) { this(x, y, z, type, 0); }
    }
    public record Finding(Point center, List<Point> evidence, int distance, Point activation) {
        public Finding(Point center, List<Point> evidence, int distance) { this(center, evidence, distance, null); }
        public Finding { evidence = List.copyOf(evidence); }
    }
    public record Report(List<Finding> findings, boolean partial, int checked, int total) {
        public Report { findings = List.copyOf(findings); }
    }

    public static java.util.concurrent.CompletableFuture<Report> scanLoaded(net.minecraft.server.MinecraftServer server, Request request) {
        var answer = new java.util.concurrent.CompletableFuture<Report>();
        server.execute(() -> {
            try {
                var level = server.getLevel(switch (request.dimension()) {
                    case OVERWORLD -> net.minecraft.world.level.Level.OVERWORLD;
                    case NETHER -> net.minecraft.world.level.Level.NETHER;
                    case END -> net.minecraft.world.level.Level.END;
                });
                if (level == null) throw new IllegalStateException("Dimension unavailable");
                if (request.kind() == Kind.SLIME_CHUNKS) {
                    answer.complete(slimeChunks(level.getSeed(), request, answer::isCancelled));
                    return;
                }
                var chunks = nearbyChunks(request.area());
                int checked = 0;
                List<Point> points = new ArrayList<>();
                List<Finding> hits = new ArrayList<>();
                var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
                for (var pos : chunks) {
                    if (answer.isCancelled()) return;
                    if (request.kind() != Kind.VILLAGES && checked >= 48) break;
                    var chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
                    if (chunk == null) continue;
                    switch (request.kind()) {
                        case SPAWNERS, SINGLE_SPAWNER -> points.addAll(spawners(chunk, level, request.area()));
                        case GEODES, IRON_VEINS, COPPER_VEINS, SURFACE_LAVA ->
                                scanFeature(level, chunk, pos, request, hits, answer::isCancelled);
                        case VILLAGES -> {
                            for (var entry : chunk.getAllStarts().entrySet()) {
                                String id = registry.getKey(entry.getKey()).toString();
                                if (village(id) && entry.getValue().isValid() && request.area().contains(pos.getBlockX(8), pos.getBlockZ(8)))
                                    points.add(new Point(pos.getBlockX(8), Integer.MIN_VALUE, pos.getBlockZ(8), id));
                            }
                        }
                        default -> borders(level, chunk, pos, request, hits, answer::isCancelled,
                                key -> level.getChunkSource().getChunkNow(key.x(), key.z()), () -> {});
                    }
                    checked++;
                }
                hits.addAll(finishPoints(level, points, request, answer::isCancelled));
                hits.sort(Comparator.comparingLong(hit -> distance2(hit.center(), request.area().x(), request.area().z())));
                answer.complete(new Report(hits.stream().limit(64).toList(), checked < chunks.size() || hits.size() > 64,
                        checked, chunks.size()));
            } catch (Exception error) { answer.completeExceptionally(error); }
        });
        return answer;
    }

    static Report scan(PreviewWorld.Session session, Request request, BooleanSupplier cancelled) {
        var level = session.level(request.dimension());
        if (request.kind() == Kind.SLIME_CHUNKS) return slimeChunks(level.getSeed(), request, cancelled);
        List<Finding> found = new ArrayList<>();
        var area = request.area();
        int checked = 0, total = 0;
        try {
            if (request.kind() == Kind.VILLAGES) {
                var chunks = villagePlacements(level, area);
                total = chunks.size();
                List<Point> villages = new ArrayList<>();
                try {
                    for (var pos : chunks) {
                        if (cancelled.getAsBoolean()) break;
                        var chunk = session.chunk(level, pos.x(), pos.z(), ChunkStatus.STRUCTURE_STARTS);
                        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
                        for (var start : chunk.getAllStarts().entrySet()) {
                            session.checkpoint();
                            String id = registry.getKey(start.getKey()).toString();
                            if (village(id) && start.getValue().isValid() && area.contains(pos.getBlockX(8), pos.getBlockZ(8)))
                                villages.add(new Point(pos.getBlockX(8), Integer.MIN_VALUE, pos.getBlockZ(8), id));
                        }
                        checked++;
                    }
                } catch (PreviewWorld.BudgetExceeded limit) {}
                found.addAll(groups(villages, request, area));
            } else {
                var chunks = nearbyChunks(area);
                total = chunks.size();
                List<Point> spawners = new ArrayList<>();
                try {
                    for (var pos : chunks) {
                        if (cancelled.getAsBoolean() || checked >= 48) break;
                        var chunk = (LevelChunk) session.chunk(level, pos.x(), pos.z(), ChunkStatus.FULL);
                        switch (request.kind()) {
                            case SPAWNERS, SINGLE_SPAWNER -> spawners.addAll(spawners(chunk, level, area));
                            case GEODES, IRON_VEINS, COPPER_VEINS, SURFACE_LAVA ->
                                    scanFeature(level, chunk, pos, request, found, cancelled);
                            default -> borders(level, chunk, pos, request, found, cancelled,
                                    key -> (LevelChunk) session.chunk(level, key.x(), key.z(), ChunkStatus.FULL), session::checkpoint);
                        }
                        checked++;
                    }
                } catch (PreviewWorld.BudgetExceeded limit) {}
                if (request.kind().hasMobFilter()) found.addAll(finishPoints(level, spawners, request, cancelled));
            }
        } catch (PreviewWorld.BudgetExceeded limit) {
            return new Report(found, true, checked, total);
        }
        found.sort(Comparator.comparingLong(hit -> distance2(hit.center(), area.x(), area.z())));
        return new Report(found.stream().limit(64).toList(), checked < total || found.size() > 64, checked, total);
    }

    private static List<Finding> finishPoints(ServerLevel level, List<Point> points, Request request, BooleanSupplier cancelled) {
        if (request.kind() == Kind.SINGLE_SPAWNER) return points.stream()
                .filter(p -> request.first().isEmpty() || p.type().equals(request.first()))
                .map(p -> new Finding(p, List.of(p), 0)).toList();
        var result = groups(points, request, request.area());
        return request.kind() == Kind.SPAWNERS ? activation(level, result, cancelled) : result;
    }

    public static Report slimeChunks(long seed, Request request, BooleanSupplier cancelled) {
        var area = request.area();
        var chunks = nearbyChunks(area);
        List<Finding> found = new ArrayList<>();
        int checked = 0;
        for (var pos : chunks) {
            if (cancelled.getAsBoolean()) break;
            checked++;
            if (!com.wywf.map.SlimeChunks.contains(seed, pos.x(), pos.z())) continue;
            int x = Math.clamp(pos.getBlockX(8), area.minX(), area.maxX());
            int z = Math.clamp(pos.getBlockZ(8), area.minZ(), area.maxZ());
            found.add(new Finding(new Point(x, Integer.MIN_VALUE, z, "wywf:slime_chunk"), List.of(), 0));
        }
        return new Report(found.stream().limit(64).toList(), checked < chunks.size() || found.size() > 64, checked, chunks.size());
    }

    private static void scanFeature(ServerLevel level, LevelChunk chunk, ChunkPos pos, Request request,
                                    List<Finding> found, BooleanSupplier cancelled) {
        String id = switch (request.kind()) {
            case GEODES -> "wywf:amethyst_geode";
            case IRON_VEINS -> "wywf:iron_vein";
            case COPPER_VEINS -> "wywf:copper_vein";
            case SURFACE_LAVA -> "wywf:lava_pool";
            default -> throw new IllegalArgumentException("Not a block feature");
        };
        if (request.kind() == Kind.SURFACE_LAVA) {
            for (int z = 0; z < 16 && !cancelled.getAsBoolean(); z++) for (int x = 0; x < 16; x++) {
                int wx = pos.getBlockX(x), wz = pos.getBlockZ(z);
                if (!request.area().contains(wx, wz)) continue;
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                if (y < level.getMinY() || !chunk.getBlockState(new BlockPos(wx, y, wz)).is(net.minecraft.world.level.block.Blocks.LAVA)) continue;
                found.add(new Finding(new Point(wx, y, wz, id), List.of(), 0));
                return;
            }
            return;
        }
        var sections = chunk.getSections();
        for (int i = 0; i < sections.length && !cancelled.getAsBoolean(); i++) {
            var section = sections[i];
            if (!section.maybeHas(state -> MapDiscovery.kind(state).equals(id))) continue;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int wx = pos.getBlockX(x), wz = pos.getBlockZ(z);
                if (!request.area().contains(wx, wz)) continue;
                for (int y = 0; y < 16; y++) {
                    if (!MapDiscovery.kind(section.getBlockState(x, y, z)).equals(id)) continue;
                    int wy = level.getSectionYFromSectionIndex(i) * 16 + y;
                    found.add(new Finding(new Point(wx, wy, wz, id), List.of(), 0));
                    return;
                }
            }
        }
    }

    private static List<ChunkPos> nearbyChunks(Area area) {
        int minX = area.minX() >> 4, minZ = area.minZ() >> 4, maxX = area.maxX() >> 4, maxZ = area.maxZ() >> 4;
        var positions = new ArrayList<ChunkPos>();
        for (int z = minZ; z <= maxZ; z++)
            for (int x = minX; x <= maxX; x++) positions.add(new ChunkPos(x, z));
        return ordered(positions, area);
    }

    private static boolean village(String id) { return id.startsWith("minecraft:village_"); }

    private static List<ChunkPos> villagePlacements(ServerLevel level, Area area) {
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        var state = level.getChunkSource().getGeneratorState();
        Set<ChunkPos> positions = new HashSet<>();
        int minX = area.minX() >> 4, minZ = area.minZ() >> 4, maxX = area.maxX() >> 4, maxZ = area.maxZ() >> 4;
        for (var set : state.possibleStructureSets()) {
            if (set.value().structures().stream().noneMatch(e -> village(registry.getKey(e.structure().value()).toString()))) continue;
            var placement = set.value().placement();
            if (placement instanceof RandomSpreadStructurePlacement spread) {
                int spacing = spread.spacing();
                for (int z = Math.floorDiv(minZ, spacing); z <= Math.floorDiv(maxZ, spacing); z++)
                    for (int x = Math.floorDiv(minX, spacing); x <= Math.floorDiv(maxX, spacing); x++) {
                        var pos = spread.getPotentialStructureChunk(state.getLevelSeed(), x * spacing, z * spacing);
                        if (area.contains(pos.getBlockX(8), pos.getBlockZ(8)) && placement.isStructureChunk(state, pos.x(), pos.z()))
                            positions.add(pos);
                    }
            } else if (placement instanceof net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement rings) {
                var ringPositions = state.getRingPositionsFor(rings);
                if (ringPositions != null) for (var pos : ringPositions)
                    if (area.contains(pos.getBlockX(8), pos.getBlockZ(8))) positions.add(pos);
            } else {
                for (int z = minZ; z <= maxZ; z++)
                    for (int x = minX; x <= maxX; x++) if (placement.isStructureChunk(state, x, z))
                        positions.add(new ChunkPos(x, z));
            }
        }
        return ordered(positions, area);
    }

    private static List<ChunkPos> ordered(Collection<ChunkPos> positions, Area area) {
        return positions.stream().sorted(Comparator.comparingLong((ChunkPos p) ->
                distance2(p.getBlockX(8), p.getBlockZ(8), area.x(), area.z()))
                .thenComparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z)).toList();
    }

    static List<Point> spawners(LevelChunk chunk, ServerLevel level, Area area) {
        List<Point> found = new ArrayList<>();
        for (var pos : chunk.getBlockEntitiesPos()) {
            if (!area.contains(pos.getX(), pos.getZ())) continue;
            var block = chunk.getBlockEntity(pos);
            if (block instanceof SpawnerBlockEntity spawner) {
                var data = spawner.saveWithoutMetadata(level.registryAccess());
                String mob = data.getCompound("SpawnData").flatMap(n -> n.getCompound("entity"))
                        .flatMap(n -> n.getString("id")).orElse("");
                if (!mob.isEmpty()) found.add(new Point(pos.getX(), pos.getY(), pos.getZ(), mob,
                        data.getIntOr("RequiredPlayerRange", 16)));
            } else if (block instanceof TrialSpawnerBlockEntity) {
                found.add(new Point(pos.getX(), pos.getY(), pos.getZ(), "minecraft:trial_spawner"));
            }
        }
        return found;
    }

    static List<Finding> groups(List<Point> points, Request request, Area area) {
        int maximum = request.distance();
        long limit = (long) maximum * maximum;
        boolean underground = request.kind() == Kind.SPAWNERS;
        Set<Set<Point>> seen = new HashSet<>();
        List<Finding> findings = new ArrayList<>();
        for (var anchor : points) {
            if (!request.first().isEmpty() && !anchor.type().equals(request.first())) continue;
            if (underground && anchor.type().equals("minecraft:trial_spawner")
                    && !request.first().equals(anchor.type())) continue;
            var near = points.stream().filter(p -> (request.second().isEmpty() || p.type().equals(request.second()) || p.equals(anchor))
                    && (!underground || !p.type().equals("minecraft:trial_spawner") || request.second().equals(p.type()) || p.equals(anchor))
                    && distance2(anchor, p, underground) <= limit)
                    .sorted(Comparator.comparingLong(p -> distance2(anchor, p, underground))).toList();
            if (near.size() < request.count()) continue;
            List<Point> group = new ArrayList<>();
            group.add(anchor);
            if (!chooseNeighbors(near, 0, group, request.count(), underground, limit)
                    || !seen.add(Set.copyOf(group))) continue;
            int separation = 0;
            for (var a : group) for (var b : group) separation = Math.max(separation, (int)Math.ceil(Math.sqrt(distance2(a, b, underground))));
            var center = underground ? anchor : new Point((int)group.stream().mapToInt(Point::x).average().orElse(anchor.x()),
                    Integer.MIN_VALUE, (int)group.stream().mapToInt(Point::z).average().orElse(anchor.z()), "minecraft:village");
            if (area.contains(center.x(), center.z())) findings.add(new Finding(center, group, separation));
        }
        return findings;
    }

    private static List<Finding> activation(ServerLevel level, List<Finding> groups, BooleanSupplier cancelled) {
        List<Finding> result = new ArrayList<>();
        for (var hit : groups) {
            if (cancelled.getAsBoolean()) break;
            Point spot = hit.evidence().stream().allMatch(p -> p.activationRange() > 0 && p.activationRange() <= 32)
                    ? standingPoint(level, hit.evidence(), cancelled) : null;
            result.add(new Finding(hit.center(), hit.evidence(), hit.distance(), spot));
        }
        return result;
    }

    private static Point standingPoint(ServerLevel level, List<Point> spawners, BooleanSupplier cancelled) {
        var first = spawners.getFirst();
        int range = first.activationRange();
        for (int y = Math.max(level.getMinY() + 1, first.y() - range); y < Math.min(level.getMaxY(), first.y() + range); y++) {
            if (cancelled.getAsBoolean()) return null;
            for (int x = first.x() - range; x <= first.x() + range; x++)
                for (int z = first.z() - range; z <= first.z() + range; z++) {
                    if (!withinActivation(spawners, x, y, z)) continue;
                    var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null) continue;
                    var feet = new BlockPos(x, y, z);
                    if (chunk.getBlockState(feet).isAir() && chunk.getBlockState(feet.above()).isAir()
                            && chunk.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP))
                        return new Point(x, y, z, "");
                }
        }
        return null;
    }

    static boolean withinActivation(List<Point> spawners, int x, int y, int z) {
        for (var p : spawners) {
            double dy = (double)p.y() + .5 - y;
            double limit = p.activationRange();
            if (limit <= 0 || distance2(p, x, z) + dy * dy >= limit * limit) return false;
        }
        return true;
    }

    private static boolean chooseNeighbors(List<Point> near, int from, List<Point> group, int count, boolean height, long limit) {
        if (group.size() == count) return true;
        for (int i = from; i <= near.size() - (count - group.size()); i++) {
            var point = near.get(i);
            if (group.contains(point) || !group.stream().allMatch(p -> distance2(point, p, height) <= limit)) continue;
            group.add(point);
            if (chooseNeighbors(near, i + 1, group, count, height, limit)) return true;
            group.removeLast();
        }
        return false;
    }

    private record Surface(Point biome, boolean water) {}

    private static void borders(ServerLevel level, LevelChunk chunk, ChunkPos pos, Request request,
                                List<Finding> found, BooleanSupplier cancelled,
                                java.util.function.Function<ChunkPos, LevelChunk> lookup, Runnable checkpoint) {
        var area = request.area();
        var adjacent = new HashMap<ChunkPos, LevelChunk>();
        boolean mouth = request.kind() == Kind.RIVER_MOUTH;
        for (int dz = 0; dz < 16; dz++) {
            if (cancelled.getAsBoolean()) return;
            checkpoint.run();
            for (int dx = 0; dx < 16; dx++) {
                int x = pos.getBlockX(dx), z = pos.getBlockZ(dz);
                if (!area.contains(x, z)) continue;
                var a = surface(level, chunk, x, z);
                if (mouth ? !a.water() || !river(a.biome().type()) && !ocean(a.biome().type())
                        : !a.biome().type().equals(request.first()) && !a.biome().type().equals(request.second())) continue;
                for (int axis = 0; axis < 2; axis++) {
                    int bx = x + (axis == 0 ? 1 : 0), bz = z + (axis == 1 ? 1 : 0);
                    if (!area.contains(bx, bz)) continue;
                    var key = new ChunkPos(bx >> 4, bz >> 4);
                    var other = key.equals(pos) ? chunk : adjacent.computeIfAbsent(key, lookup);
                    if (other == null) continue;
                    var b = surface(level, other, bx, bz);
                    boolean match = mouth ? b.water() && a.biome().y() == b.biome().y() &&
                            (river(a.biome().type()) && ocean(b.biome().type()) || ocean(a.biome().type()) && river(b.biome().type()))
                            : !a.biome().type().equals(b.biome().type()) &&
                            (b.biome().type().equals(request.first()) || b.biome().type().equals(request.second()));
                    if (match && found.stream().noneMatch(f -> distance2(f.center(), x, z) < 48L * 48))
                        found.add(new Finding(a.biome(), List.of(a.biome(), b.biome()), 1));
                }
            }
        }
    }

    private static Surface surface(ServerLevel level, LevelChunk chunk, int x, int z) {
        int y = Math.max(level.getMinY(), chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15));
        var pos = new BlockPos(x, y, z);
        String biome = level.getBiome(pos).unwrapKey().orElseThrow().identifier().toString();
        return new Surface(new Point(x, y, z, biome), chunk.getFluidState(pos).is(FluidTags.WATER));
    }

    private static boolean river(String id) { return id.equals("minecraft:river") || id.equals("minecraft:frozen_river"); }
    private static boolean ocean(String id) { return id.startsWith("minecraft:") && id.endsWith("ocean"); }
    private static long distance2(Point p, int x, int z) { return distance2(p.x(), p.z(), x, z); }
    private static long distance2(int ax, int az, int bx, int bz) {
        long dx = (long)ax - bx, dz = (long)az - bz;
        return dx * dx + dz * dz;
    }
    private static long distance2(Point a, Point b, boolean height) {
        long dy = height ? (long)a.y() - b.y() : 0;
        return distance2(a, b.x(), b.z()) + dy * dy;
    }
    private PlaceSurvey() {}
}
