package com.wywf.core;

// This is a test file. These files have no effect on the main gameplay.

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SearchResultTest {
    @Test
    void carriesMatchedListsAndStopReason() {
        SearchResult r = new SearchResult(123L, 0, 0, 32, 48, "minecraft:village_plains",
                List.of("minecraft:village_plains"), List.of("minecraft:plains"), "collected 3 candidates");
        assertEquals(123L, r.seed);
        assertEquals(List.of("minecraft:village_plains"), r.matchedStructures);
        assertEquals(List.of("minecraft:plains"), r.matchedBiomes);
        assertEquals("collected 3 candidates", r.stopReason);
        assertTrue(r.toString().contains("structures="));
    }
}
