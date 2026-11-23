package com.wywf.client;

import com.wywf.core.DimensionSpace;
import com.wywf.map.BiomeMapColors;
import com.wywf.map.BiomeMapSnapshot;
import com.wywf.map.BiomeMaps;
import com.wywf.verify.SurfaceSnapshot;
import com.wywf.verify.VerificationSummary;
import com.wywf.verify.VerifiedAnchor;
import com.wywf.verify.WorldMapSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class ReliefTargets implements AutoCloseable {
    record Pin(String id, int x, int y, int z, VerifiedAnchor structure, boolean spawn) {
        String name() { return spawn ? MapTexts.text("spawn_marker") : ResultTexts.name(id); }
    }

    private final WorldMapSource source;
    private final DimensionSpace dimension;
    private final Set<String> matchedBiomes;
    private BiomeMaps.Session biomeLookup;
    private BiomeMaps.Request request;
    private List<Pin> biomePins = List.of();
    private final List<Pin> structures;
    private Pin spawn;
    private SurfaceSnapshot exact;
    private BiomeMapSnapshot sampled;

    ReliefTargets(WorldMapSource source, DimensionSpace dimension) {
        this.source = source;
        this.dimension = dimension;
        matchedBiomes = source.result().matchedBiomes.stream()
                .filter(id -> DimensionSpace.of(id) == dimension && !BiomeMapColors.cave(id))
                .collect(Collectors.toUnmodifiableSet());
        structures = source.anchors().stream().filter(anchor -> dimension.id.equals(anchor.dimension())).map(anchor -> {
            int x = anchor.minX() + (anchor.maxX() - anchor.minX()) / 2;
            int z = anchor.minZ() + (anchor.maxZ() - anchor.minZ()) / 2;
            return new Pin(anchor.id(), x, Integer.MIN_VALUE, z, anchor, false);
        }).distinct().toList();
        var summary = source.summary();
        if (dimension == DimensionSpace.OVERWORLD && summary != null && switch (summary.status()) {
            case PASS, PARTIAL, JOINED -> true;
            default -> false;
        }) spawn = new Pin("", summary.spawnX(), summary.spawnY(), summary.spawnZ(), null, true);
    }

    void tick() {
        if (matchedBiomes.isEmpty()) return;
        var surface = source.surface(dimension);
        if (surface != null && surface != exact) {
            exact = surface;
            biomePins = exactPins(surface, matchedBiomes);
            sampled = null;
        }
        if (biomePins.size() >= matchedBiomes.size()) return;
        if (biomeLookup == null) biomeLookup = BiomeMaps.open();
        int centerX = dimension == DimensionSpace.END ? 0 : dimension.coordinate(source.result().centerX);
        int centerZ = dimension == DimensionSpace.END ? 0 : dimension.coordinate(source.result().centerZ);
        int radius = source.query().terms().stream().filter(term -> matchedBiomes.contains(term.canonical)).mapToInt(term -> term.betweenMax).max().orElse(0);
        radius = Math.clamp(Math.max(radius, source.config().biomeCheckRadiusChunks() * 16), 320, 4096);
        int step = Math.max(4, Math.ceilDiv(radius, 64));
        var next = new BiomeMaps.Request(source.result().seed, centerX - 64 * step, centerZ - 64 * step,
                step, 129, 129, 64, dimension);
        if (!next.equals(request) && biomeLookup.request(next)) {
            request = next;
            sampled = null;
        }
        var grid = biomeLookup.snapshot();
        if (grid == null || grid == sampled) return;
        sampled = grid;
        List<Pin> found = new ArrayList<>(biomePins);
        for (String id : matchedBiomes) {
            if (found.stream().anyMatch(pin -> pin.id().equals(id))) continue;
            var pin = regionCenter(id, grid);
            if (pin != null) found.add(pin);
        }
        biomePins = List.copyOf(found);
    }

    private static List<Pin> exactPins(SurfaceSnapshot surface, Set<String> eligible) {
        var pins = new ArrayList<Pin>();
        for (String id : eligible) {
            int count = 0;
            long sumX = 0, sumZ = 0;
            for (int dz = 1; dz < surface.size(); dz += 4) {
                for (int dx = 1; dx < surface.size(); dx += 4) {
                    int cell = dz * surface.size() + dx;
                    if (surface.heights()[cell] == Integer.MIN_VALUE || !id.equals(surface.biomes()[cell])) continue;
                    sumX += dx;
                    sumZ += dz;
                    count++;
                }
            }
            if (count == 0) continue;
            double centerX = sumX / (double)count, centerZ = sumZ / (double)count, nearest = Double.POSITIVE_INFINITY;
            Pin best = null;
            for (int dz = 1; dz < surface.size(); dz += 4) {
                for (int dx = 1; dx < surface.size(); dx += 4) {
                    int cell = dz * surface.size() + dx;
                    if (surface.heights()[cell] == Integer.MIN_VALUE || !id.equals(surface.biomes()[cell])) continue;
                    double distance = Math.hypot(dx - centerX, dz - centerZ);
                    if (distance < nearest) {
                        nearest = distance;
                        best = new Pin(id, surface.x() + dx, surface.heights()[cell], surface.z() + dz, null, false);
                    }
                }
            }
            if (best != null) pins.add(best);
        }
        return List.copyOf(pins);
    }

    private static Pin regionCenter(String id, BiomeMapSnapshot grid) {
        int w = grid.width(), h = grid.height(), nearest = -1, distance = Integer.MAX_VALUE;
        var cells = grid.cells();
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == null || !id.equals(cells[i].id())) continue;
            int dx = i % w - w / 2, dz = i / w - h / 2;
            int d = dx * dx + dz * dz;
            if (d < distance) {
                nearest = i;
                distance = d;
            }
        }
        if (nearest < 0) return null;
        boolean[] seen = new boolean[cells.length];
        int[] connected = new int[cells.length];
        int end = 1, sumX = 0, sumZ = 0;
        connected[0] = nearest;
        seen[nearest] = true;
        for (int index = 0; index < end; index++) {
            int cell = connected[index], x = cell % w, z = cell / w;
            sumX += x;
            sumZ += z;
            if (x > 0) end = add(cell - 1, end, seen, connected, cells, id);
            if (x + 1 < w) end = add(cell + 1, end, seen, connected, cells, id);
            if (z > 0) end = add(cell - w, end, seen, connected, cells, id);
            if (z + 1 < h) end = add(cell + w, end, seen, connected, cells, id);
        }
        double centerX = sumX / (double)end, centerZ = sumZ / (double)end, best = Double.POSITIVE_INFINITY;
        for (int index = 0; index < end; index++) {
            int cell = connected[index];
            double d = Math.hypot(cell % w - centerX, cell / w - centerZ);
            if (d < best) {
                nearest = cell;
                best = d;
            }
        }
        return new Pin(id, grid.x() + nearest % w * grid.step(), cells[nearest].height(),
                grid.z() + nearest / w * grid.step(), null, false);
    }

    private static int add(int cell, int end, boolean[] seen, int[] connected, BiomeMapSnapshot.Cell[] cells, String id) {
        if (!seen[cell] && cells[cell] != null && id.equals(cells[cell].id())) {
            seen[cell] = true;
            connected[end++] = cell;
        }
        return end;
    }

    Pin nearestBiome(double x, double z) {
        Pin result = null;
        double best = Double.POSITIVE_INFINITY;
        for (var pin : biomePins) {
            double distance = Math.hypot(x - pin.x(), z - pin.z());
            if (distance < best) {
                result = pin;
                best = distance;
            }
        }
        return result;
    }

    List<Pin> structures() { return structures; }
    Pin spawn() { return spawn; }

    @Override public void close() {
        if (biomeLookup != null) biomeLookup.close();
    }
}
