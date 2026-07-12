package com.wywf.search;

import com.wywf.core.*;

import java.util.List;
import java.util.Set;

public final class SeedValidator {

    public enum Reason {
        ACCEPTED("matches"),
        NO_STRUCTURE("required structure not found near spawn"),
        BIOME_MISMATCH("missing required biomes near spawn"),
        OBJECT_MISMATCH("missing required objects");

        public final String description;
        Reason(String description) { this.description = description; }
    }

    public static final class Outcome {
        public final boolean       accepted;
        public final Reason        reason;
        public final SearchResult  result;
        public final boolean       structuresMatched;
        public final int           structureX;
        public final int           structureZ;
        public final String        structureId;

        private Outcome(boolean accepted, Reason reason, SearchResult result,
                        boolean structuresMatched, int sx, int sz, String sid) {
            this.accepted = accepted;
            this.reason = reason;
            this.result = result;
            this.structuresMatched = structuresMatched;
            this.structureX = sx;
            this.structureZ = sz;
            this.structureId = sid;
        }

        static Outcome accepted(SearchResult r, StructureChecker.Result s) {
            return new Outcome(true, Reason.ACCEPTED, r, s.found, s.structureX, s.structureZ, s.structureId);
        }

        static Outcome rejected(Reason reason, StructureChecker.Result s) {
            return new Outcome(false, reason, null, s.found, s.structureX, s.structureZ, s.structureId);
        }
    }

    private final StructureChecker structureChecker;
    private final BiomeChecker     biomeChecker;
    private final int              searchRadiusChunks;
    private final int              biomeRadiusChunks;

    public SeedValidator(StructureChecker sc, BiomeChecker bc,
                         int searchRadiusChunks, int biomeRadiusChunks) {
        this.structureChecker  = sc;
        this.biomeChecker      = bc;
        this.searchRadiusChunks = searchRadiusChunks;
        this.biomeRadiusChunks = biomeRadiusChunks;
    }

    public StructureChecker structureChecker() {
        return structureChecker;
    }

    /**
     * Radius (in chunks) that {@link #validate} will actually scan for the given
     * structure term. Because structure placement depends only on the low 48 bits
     * of the seed (identical for every high-16-bit variant), the prefilter can use
     * this exact radius to reject whole 48-bit bases safely.
     */
    public int structureScanRadiusChunks(ParsedQuery.Term term) {
        return switch (term.modifier) {
            case NEAR   -> chunks(NEAR_BLOCKS);
            case IN, ON -> UNDERGROUND.contains(term.canonical) ? searchRadiusChunks : chunks(IN_ON_BLOCKS);
            default     -> searchRadiusChunks;
        };
    }

    private static final int NEAR_BLOCKS    = 200;
    private static final int FAR_MIN_BLOCKS = 1000;
    private static final int FAR_MAX_BLOCKS = 2000;
    private static final int IN_ON_BLOCKS   = 64;

    private static final Set<String> UNDERGROUND = Set.of(
            "minecraft:stronghold", "minecraft:mineshaft",
            "minecraft:ancient_city", "minecraft:trial_chambers");

    private static final Set<String> UNIQUE = Set.of(
            "minecraft:mansion", "minecraft:stronghold",
            "minecraft:ancient_city", "minecraft:ocean_monument");

    public Outcome validate(WorldContextFactory factory, long seed, ParsedQuery query, boolean accurateRings) {

        WorldContext ctx = factory.create(seed, accurateRings);

        int cx = 0;
        int cz = 0;

        StructureChecker.Result spawnStruct = StructureChecker.Result.notFound();

        for (ParsedQuery.Term term : query.terms()) {
            switch (term.category) {
                case STRUCTURE -> {
                    StructureChecker.Result r = evalStructureTerm(ctx, cx, cz, term);
                    if (r == null) return Outcome.rejected(Reason.NO_STRUCTURE, spawnStruct);
                    if (r.found && !spawnStruct.found) spawnStruct = r;
                }
                case BIOME -> {
                    if (!evalBiomeTerm(ctx, cx, cz, term)) {
                        return Outcome.rejected(Reason.BIOME_MISMATCH, spawnStruct);
                    }
                }
                case OBJECT -> {
                }
            }
        }

        String desc = spawnStruct.found ? spawnStruct.structureId
                : (!query.biomes().isEmpty() ? query.biomes().get(0) : "origin");
        SearchResult result = new SearchResult(seed, cx, cz, desc);
        return Outcome.accepted(result, spawnStruct);
    }

    private StructureChecker.Result evalStructureTerm(WorldContext ctx, int cx, int cz, ParsedQuery.Term term) {
        String canonical = term.canonical;
        Modifier mod = term.modifier;

        if (mod == Modifier.NEVER) {
            int[] p = structureChecker.firstPosition(ctx, cx, cz, searchRadiusChunks, canonical);
            return p == null ? StructureChecker.Result.notFound() : null;
        }

        if (mod == Modifier.FAR) {
            List<int[]> pos = structureChecker.positions(ctx, cx, cz, chunks(FAR_MAX_BLOCKS), canonical);
            if (pos.isEmpty()) return null;
            int nearest = Integer.MAX_VALUE;
            for (int[] p : pos) nearest = Math.min(nearest, dist(p, cx, cz));
            if (nearest < FAR_MIN_BLOCKS) return null;
            return StructureChecker.Result.notFound();
        }

        if (mod == Modifier.SOME && !UNIQUE.contains(canonical)) {
            List<int[]> pos = structureChecker.positions(ctx, cx, cz, searchRadiusChunks, canonical);
            int count = 0;
            int[] best = null; int bestD = Integer.MAX_VALUE;
            int limit = searchRadiusChunks * 16;
            for (int[] p : pos) {
                int d = dist(p, cx, cz);
                if (d <= limit) count++;
                if (d < bestD) { bestD = d; best = p; }
            }
            if (count < 2 || best == null) return null;
            return StructureChecker.Result.found(best[0], best[1], canonical);
        }

        int scanChunks = switch (mod) {
            case NEAR   -> chunks(NEAR_BLOCKS);
            case IN, ON -> UNDERGROUND.contains(canonical) ? searchRadiusChunks : chunks(IN_ON_BLOCKS);
            default     -> searchRadiusChunks;
        };

        int[] found = structureChecker.firstPosition(ctx, cx, cz, scanChunks, canonical);
        if (found == null) return null;
        return StructureChecker.Result.found(found[0], found[1], canonical);
    }

    private boolean evalBiomeTerm(WorldContext ctx, int cx, int cz, ParsedQuery.Term term) {
        String canonical = term.canonical;
        Modifier mod = term.modifier;

        switch (mod) {
            case IN, ON -> {
                return biomeChecker.exists(ctx, cx, cz, chunks(IN_ON_BLOCKS), canonical);
            }
            case UNDER -> {
                return biomeChecker.matchesAt(ctx, cx, cz, canonical);
            }
            case NEAR -> {
                int d = biomeChecker.nearestDistanceBlocks(ctx, cx, cz, chunks(NEAR_BLOCKS), canonical);
                return d >= 0 && d <= NEAR_BLOCKS;
            }
            case FAR -> {
                int d = biomeChecker.nearestDistanceBlocks(ctx, cx, cz, chunks(FAR_MAX_BLOCKS), canonical);
                return d >= FAR_MIN_BLOCKS && d <= FAR_MAX_BLOCKS;
            }
            case NEVER -> {
                return !biomeChecker.exists(ctx, cx, cz, biomeRadiusChunks, canonical);
            }
            default -> {
                return biomeChecker.exists(ctx, cx, cz, biomeRadiusChunks, canonical);
            }
        }
    }

    private static int chunks(int blocks) {
        return (blocks + 15) / 16;
    }

    private static int dist(int[] p, int cx, int cz) {
        double dx = p[0] - cx;
        double dz = p[1] - cz;
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }
}
