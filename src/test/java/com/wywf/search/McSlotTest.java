package com.wywf.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McSlotTest {

    @Test
    void resolvesKnownVersions() {
        assertEquals(33, CubiomesBridge.resolveSlot("26.1"));
        assertEquals(33, CubiomesBridge.resolveSlot("26.1.2"));
        assertEquals(34, CubiomesBridge.resolveSlot("26.2"));
        assertEquals(35, CubiomesBridge.resolveSlot("26.3"));
        assertEquals(35, CubiomesBridge.resolveSlot("26.3-rc-2"));
    }

    @Test
    void unknownAssumesSafestWithWarn() {
        assertEquals(34, CubiomesBridge.resolveSlot("bogus"));
        assertEquals(34, CubiomesBridge.resolveSlot(""));
    }
}
