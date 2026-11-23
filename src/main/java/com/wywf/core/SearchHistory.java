package com.wywf.core;

import com.google.gson.Gson;
import com.wywf.verify.VerificationSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class SearchHistory {
    private static final Gson JSON = new Gson();
    private static final int SCHEMA = 1;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_KEY_LENGTH = 50_000;
    private static final int MAX_RESULTS = 64;
    private record Location(long seed, int x, int z) {}
    private record Seed(long seed, int x, int z, SearchResult evidence, VerificationSummary verification) {
        private Object identity(boolean preserveCoordinates) {
            return preserveCoordinates ? new Location(seed, x, z) : seed;
        }

        private SearchResult restore(ParsedQuery query, boolean offline) {
            if (!offline && evidence == null)
                return new SearchResult(seed, x, z, x, z, "History", query.structures(), query.biomes(), "");
            if (evidence == null || evidence.primaryDescription == null || evidence.seed != seed
                    || evidence.centerX != x || evidence.centerZ != z || evidence.matchedStructures == null
                    || evidence.matchedBiomes == null || evidence.structurePositions == null || evidence.biomeDistances == null
                    || evidence.matchedStructures.stream().anyMatch(Objects::isNull)
                    || evidence.matchedBiomes.stream().anyMatch(Objects::isNull)
                    || evidence.structurePositions.values().stream().anyMatch(p -> p == null || p.length != 2)
                    || evidence.biomeDistances.values().stream().anyMatch(Objects::isNull)) return null;
            return evidence;
        }
    }
    private record Entry(String key, List<Seed> seeds) {}
    private record Document(int schema, List<Entry> entries) {}
    private final Path file;
    public SearchHistory(Path file) { this.file = file; }
    public static String key(String environment, ParsedQuery query, SearchConfig config) {
        String key = environment + "|" + query.normalized() + "|" + config.searchCenter() + "|"
                + config.searchRadiusChunks() + "|" + config.biomeCheckRadiusChunks();
        return config.serverVerification() ? key
                : key + "|offline:" + query.estimated() + ":" + config.biomeSampleStepChunks();
    }
    private List<Entry> read() {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return List.of();
            Document d = JSON.fromJson(Files.readString(file), Document.class);
            if (d == null || d.schema != SCHEMA || d.entries == null || d.entries.size() > MAX_ENTRIES) return List.of();
            return d.entries.stream().filter(e -> e != null && e.key != null && e.key.length() <= MAX_KEY_LENGTH
                    && e.seeds != null && e.seeds.size() <= MAX_RESULTS && e.seeds.stream().allMatch(s -> s != null && Math.abs((long) s.x) <= 29990000 && Math.abs((long) s.z) <= 29990000)).toList();
        } catch (Exception ignored) { return List.of(); }
    }
    public List<SearchResult> find(String key, ParsedQuery query) {
        return findReview(key, query).candidates();
    }

    public synchronized CandidateReview findReview(String key, ParsedQuery query) {
        boolean offline = offlineKey(key);
        boolean preserveCoordinates = query.place() != null;
        var results = new LinkedHashMap<Object, SearchResult>();
        var checks = new LinkedHashMap<SearchResult, VerificationSummary>();
        read().stream().filter(entry -> entry.key.equals(key)).flatMap(entry -> entry.seeds.stream()).forEach(seed -> {
            var result = seed.restore(query, offline);
            if (result != null && results.putIfAbsent(seed.identity(preserveCoordinates), result) == null) {
                var verification = completed(seed.verification);
                if (verification != null) checks.put(result, verification);
            }
        });
        var review = new CandidateReview(List.copyOf(results.values()), null, false);
        checks.forEach(review::recordVerification);
        return review;
    }

    public void remember(String key, List<SearchResult> results) throws IOException {
        remember(key, new CandidateReview(results, null, false));
    }

    public synchronized void remember(String key, CandidateReview review) throws IOException {
        var results = review.candidates();
        if (results.isEmpty() || key.length() > MAX_KEY_LENGTH) return;
        List<Entry> entries = new ArrayList<>(read());
        boolean offline = offlineKey(key);
        boolean preserveCoordinates = key.contains("|FTP ");
        var merged = new LinkedHashMap<Object, Seed>();
        for (var result : results) {
            if (result.primaryDescription == null) continue;
            var verification = completed(review.verification(result));
            var seed = new Seed(result.seed, result.centerX, result.centerZ,
                    offline || verification != null ? result : null, verification);
            merged.putIfAbsent(seed.identity(preserveCoordinates), seed);
        }
        entries.stream().filter(entry -> entry.key.equals(key)).flatMap(entry -> entry.seeds.stream())
                .forEach(seed -> merged.merge(seed.identity(preserveCoordinates), seed, (recent, old) -> {
                    if (recent.verification != null || completed(old.verification) == null
                            || recent.x != old.x || recent.z != old.z) return recent;
                    return new Seed(recent.seed, recent.x, recent.z,
                            old.evidence != null ? old.evidence : recent.evidence, old.verification);
                }));
        entries.removeIf(e -> e.key.equals(key));
        var seeds = new ArrayList<Seed>(Math.min(MAX_RESULTS, merged.size()));
        int entryBytes = jsonBytes(new Entry(key, List.of()));
        int documentBytes = jsonBytes(new Document(SCHEMA, List.of()));
        for (var seed : merged.values()) {
            if (seeds.size() == MAX_RESULTS) break;
            int bytes = jsonBytes(seed) + (seeds.isEmpty() ? 0 : 1);
            if (bytes > MAX_BYTES - documentBytes - entryBytes) continue;
            seeds.add(seed);
            entryBytes += bytes;
        }
        if (seeds.isEmpty()) return;
        entries.addFirst(new Entry(key, seeds));
        byte[] contents = encodeRecentEntries(entries);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".history-", ".tmp");
        try {
            Files.write(tmp, contents);
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }

    private static VerificationSummary completed(VerificationSummary summary) {
        return summary != null && (summary.status() == VerificationSummary.Status.PASS
                || summary.status() == VerificationSummary.Status.FAIL) ? summary : null;
    }

    private static boolean offlineKey(String key) {
        return key.substring(key.lastIndexOf('|') + 1).startsWith("offline:");
    }

    private static int jsonBytes(Object value) {
        return JSON.toJson(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static byte[] encodeRecentEntries(List<Entry> entries) throws IOException {
        var retained = new ArrayList<Entry>(Math.min(entries.size(), MAX_ENTRIES));
        int size = JSON.toJson(new Document(SCHEMA, List.of())).getBytes(StandardCharsets.UTF_8).length;
        for (var entry : entries) {
            int separatorSize = retained.isEmpty() ? 0 : 1;
            int entrySize = JSON.toJson(entry).getBytes(StandardCharsets.UTF_8).length + separatorSize;
            if (retained.size() == MAX_ENTRIES || entrySize > MAX_BYTES - size) break;
            retained.add(entry);
            size += entrySize;
        }
        byte[] contents = JSON.toJson(new Document(SCHEMA, retained)).getBytes(StandardCharsets.UTF_8);
        if (contents.length > MAX_BYTES) throw new IOException("Search history exceeds its byte limit");
        return contents;
    }
}
