package com.wywf.verify;

import com.wywf.core.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SavedWorldMap(String worldId, String gameVersion, SearchResult result, ParsedQuery query,
                            SearchConfig config, VerificationSummary summary, SurfaceSnapshot surface,
                            List<VerifiedAnchor> anchors, List<LootWitness> loot,
                            List<VerifiedAnchor> legacyAnchors) implements WorldMapSource {
    public SavedWorldMap(String worldId, String gameVersion, SearchResult result, ParsedQuery query,
                         SearchConfig config, VerificationSummary summary, SurfaceSnapshot surface,
                         List<VerifiedAnchor> anchors, List<LootWitness> loot) {
        this(worldId, gameVersion, result, query, config, summary, surface, anchors, loot, List.of());
    }

    public SavedWorldMap {
        UUID.fromString(worldId);
        if (gameVersion == null || gameVersion.isBlank()) throw new IllegalArgumentException("Missing world version");
        config = config.copy();
        anchors = List.copyOf(anchors);
        loot = List.copyOf(loot);
        legacyAnchors = List.copyOf(legacyAnchors);
    }

    @Override public List<VerifiedAnchor> mapAnchors() {
        if (legacyAnchors.isEmpty()) return anchors;
        var all = new ArrayList<>(anchors);
        all.addAll(legacyAnchors);
        return List.copyOf(all);
    }

    @Override public boolean saved() { return true; }
}