package com.wywf.core;

// This is a test file. These files have no effect on the main gameplay.

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CompoundTest {
    private final QueryParser p = new QueryParser(new KeywordDictionary());

    @Test
    void plainsVillageBinds() {
        var q = p.parse("plains village");
        assertTrue(q.structures().contains("minecraft:village_plains"), () -> q.toString());
        assertTrue(q.biomes().contains("minecraft:plains"), () -> q.toString());
    }

    @Test
    void villagePlainsBinds() {
        var q = p.parse("village plains");
        assertTrue(q.structures().contains("minecraft:village_plains"), () -> q.toString());
    }

    @Test
    void desertTempleBinds() {
        var q = p.parse("desert temple");
        assertTrue(q.structures().contains("minecraft:desert_pyramid"), () -> q.toString());
    }

    @Test
    void jungleTempleResolvesToVariant() {
        var q = p.parse("jungle temple");
        assertTrue(q.structures().contains("minecraft:jungle_temple"), () -> q.toString());
    }

    @Test
    void snowyVillageBinds() {
        var q = p.parse("snowy village");
        assertTrue(q.structures().contains("minecraft:village_snowy"), () -> q.toString());
    }

    @Test
    void russianPlainsVillageBinds() {
        var q = p.parse("\u0440\u0430\u0432\u043d\u0438\u043d\u044b \u0434\u0435\u0440\u0435\u0432\u043d\u044f");
        assertTrue(q.structures().contains("minecraft:village_plains"), () -> q.toString());
    }

    @Test
    void ignoredWordsRecorded() {
        var q = p.parse("village blahblah temple");
        assertTrue(q.ignoredWords().contains("blahblah"), () -> q.ignoredWords().toString());
    }
}
