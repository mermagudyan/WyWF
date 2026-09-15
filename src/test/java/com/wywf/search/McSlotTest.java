package com.wywf.search;

// This is a test file. These files have no effect on the main gameplay.

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McSlotTest {

    @Test
    void resolvesKnownVersions() {
        assertEquals(26, CubiomesBridge.resolveSlot("1.21.1"));
        assertEquals(26, CubiomesBridge.resolveSlot("1.21.2"));
        assertEquals(27, CubiomesBridge.resolveSlot("1.21.3"));
        assertEquals(28, CubiomesBridge.resolveSlot("1.21.4"));
        assertEquals(29, CubiomesBridge.resolveSlot("1.21.5"));
        assertEquals(32, CubiomesBridge.resolveSlot("1.21.11"));
    }

    @Test
    void unknownRoundsDown() {
        assertEquals(30, CubiomesBridge.resolveSlot("1.21.8"));
        assertEquals(32, CubiomesBridge.resolveSlot("1.21.99"));
        assertEquals(32, CubiomesBridge.resolveSlot(""));
    }
}
