package com.wywf.core;

import com.google.gson.Gson;
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
    private record Seed(long seed, int x, int z) {}
    private record Entry(String key, List<Seed> seeds) {}
    private record Document(int schema, List<Entry> entries) {}
    private final Path file;
    public SearchHistory(Path file) { this.file = file; }
    public static String key(String environment, ParsedQuery query, SearchConfig config) {
        return environment + "|" + query.normalized() + "|" + config.searchCenter() + "|"
                + config.searchRadiusChunks() + "|" + config.biomeCheckRadiusChunks();
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
    public synchronized List<SearchResult> find(String key, ParsedQuery query) {
        return read().stream().filter(e -> e.key.equals(key)).flatMap(e -> e.seeds.stream()).distinct()
                .map(s -> new SearchResult(s.seed, s.x, s.z, s.x, s.z, "History",
                        query.structures(), query.biomes(), "")).toList();
    }
    public synchronized void remember(String key, List<SearchResult> results) throws IOException {
        if (results.isEmpty() || key.length() > MAX_KEY_LENGTH) return;
        List<Entry> entries = new ArrayList<>(read());
        LinkedHashMap<Object, Seed> merged = new LinkedHashMap<>();
        for (var r : results) merged.putIfAbsent(key.contains("|FTP ") ? new Seed(r.seed,r.centerX,r.centerZ) : r.seed,new Seed(r.seed,r.centerX,r.centerZ));
        entries.stream().filter(e -> e.key.equals(key)).flatMap(e -> e.seeds.stream())
                .forEach(s -> merged.putIfAbsent(key.contains("|FTP ") ? s : s.seed,s));
        entries.removeIf(e -> e.key.equals(key));
        entries.addFirst(new Entry(key, merged.values().stream().limit(MAX_RESULTS).toList()));
        byte[] contents = encodeRecentEntries(entries);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), ".history-", ".tmp");
        try {
            Files.write(tmp, contents);
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
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
