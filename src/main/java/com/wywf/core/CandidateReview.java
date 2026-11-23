package com.wywf.core;

import com.wywf.verify.VerificationSummary;
import java.util.*;

public final class CandidateReview {
    private record Key(long seed, int x, int z) {}
    private final List<SearchResult> candidates;
    private final Map<Key, VerificationSummary> verifications = new HashMap<>();
    private int selected;

    public CandidateReview(List<SearchResult> pool, SearchResult recommended, boolean distanceFirst) {
        Map<Key, SearchResult> unique = new LinkedHashMap<>();
        for (SearchResult result : pool) add(unique, result);
        add(unique, recommended);
        List<SearchResult> ordered = new ArrayList<>(unique.values());
        if (distanceFirst) ordered.sort(Comparator.comparingDouble(SearchResult::distanceToStructure));
        candidates = List.copyOf(ordered);
        selected = candidates.isEmpty() ? -1 : 0;
        if (recommended != null) {
            Key wanted = key(recommended);
            for (int i = 0; i < candidates.size(); i++) {
                if (key(candidates.get(i)).equals(wanted)) { selected = i; break; }
            }
        }
    }

    private static void add(Map<Key, SearchResult> unique, SearchResult result) {
        if (result == null || result.primaryDescription == null) return;
        Map<String, int[]> positions = new HashMap<>();
        result.structurePositions.forEach((name, position) -> positions.put(name, position.clone()));
        SearchResult snapshot = new SearchResult(result.seed, result.centerX, result.centerZ,
                result.structureX, result.structureZ, result.primaryDescription,
                result.matchedStructures, result.matchedBiomes, result.stopReason, positions, result.biomeDistances);
        unique.put(key(result), snapshot);
    }

    private static Key key(SearchResult result) { return new Key(result.seed, result.centerX, result.centerZ); }
    public CandidateReview prepend(List<SearchResult> previous) {
        var combined = new ArrayList<>(previous);
        combined.addAll(candidates);
        var merged = new CandidateReview(combined, selected().orElse(null), false);
        merged.verifications.putAll(verifications);
        return merged;
    }

    public CandidateReview prepend(CandidateReview previous) {
        CandidateReview combined = prepend(previous.candidates());
        previous.verifications.forEach(combined.verifications::putIfAbsent);
        return combined;
    }

    public VerificationSummary verification(SearchResult result) {
        return verifications.get(key(result));
    }

    public void recordVerification(SearchResult result, VerificationSummary summary) {
        verifications.put(key(result), summary);
    }

    public List<SearchResult> candidates() { return candidates; }
    public int selectedIndex() { return selected; }
    public Optional<SearchResult> selected() {
        return selected < 0 ? Optional.empty() : Optional.of(candidates.get(selected));
    }

    public void select(int index) {
        if (index < 0 || index >= candidates.size()) throw new IndexOutOfBoundsException(index);
        selected = index;
    }
}
