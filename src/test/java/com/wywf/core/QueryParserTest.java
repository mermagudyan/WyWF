package com.wywf.core;

// This is a test file. These files have no effect on the main gameplay.

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryParserTest {

    private QueryParser parser;

    @BeforeEach
    void setUp() {
        parser = new QueryParser(new KeywordDictionary());
    }

    @Test
    void emptyInputYieldsEmptyQuery() {
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("   ").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    void pureNumericSeedIsNotAQuery() {
        assertFalse(parser.isQuery("123456789"));
        assertTrue(parser.parse("123456789").isEmpty());
    }

    @Test
    void singleStructure() {
        ParsedQuery q = parser.parse("деревня");
        assertEquals(java.util.List.of("minecraft:village"), q.structures());
        assertTrue(q.biomes().isEmpty());
    }

    @Test
    void ignoresFillerWords() {
        ParsedQuery a = parser.parse("деревня");
        ParsedQuery b = parser.parse("хочу заспавнить меня рядом с деревней пожалуйста");
        assertEquals(a.structures(), b.structures());
    }

    @Test
    void structureAndBiomeCombined() {
        ParsedQuery q = parser.parse("деревня возле теплого океана");
        assertTrue(q.structures().contains("minecraft:village"));
        assertTrue(q.biomes().contains("minecraft:warm_ocean"));
    }

    @Test
    void englishSynonyms() {
        ParsedQuery q = parser.parse("village near warm ocean");
        assertTrue(q.structures().contains("minecraft:village"));
        assertTrue(q.biomes().contains("minecraft:warm_ocean"));
    }

    @Test
    void greedyLongestMatchPrefersMultiWordKeys() {
        ParsedQuery q = parser.parse("темный лес");
        assertTrue(q.biomes().contains("minecraft:dark_forest"));
        assertFalse(q.biomes().contains("minecraft:forest"));
    }

    @Test
    void noDuplicates() {
        ParsedQuery q = parser.parse("деревня деревня village");
        assertEquals(1, q.structures().size());
    }

    @Test
    void primaryTargetPrefersStructure() {
        ParsedQuery q = parser.parse("особняк темный лес");
        assertEquals("minecraft:mansion", q.primaryTarget().orElseThrow());
    }

    @Test
    void spawnOnSandEnglish() {
        ParsedQuery q = parser.parse("spawn on sand");
        assertEquals(java.util.List.of("minecraft:sand"), q.spawns());
    }

    @Test
    void onTheSandBlockEnglish() {
        ParsedQuery q = parser.parse("on the sand block");
        assertEquals(java.util.List.of("minecraft:sand"), q.spawns());
    }

    @Test
    void onTheStoneEnglish() {
        ParsedQuery q = parser.parse("on the stone");
        assertEquals(java.util.List.of("minecraft:stone"), q.spawns());
    }

    @Test
    void russianOnBlockSand() {
        ParsedQuery q = parser.parse("на блоке песок");
        assertEquals(java.util.List.of("minecraft:sand"), q.spawns());
    }

    @Test
    void russianAnySolidBlock() {
        ParsedQuery q = parser.parse("на любых твёрдых блоках");
        assertEquals(java.util.List.of("any_solid"), q.spawns());
    }

    @Test
    void spawnTriggerWithoutBlockIsIgnored() {
        ParsedQuery q = parser.parse("spawn on lava");
        assertTrue(q.spawns().isEmpty());
    }

    @Test
    void sandAloneIsNotASpawnTerm() {
        ParsedQuery q = parser.parse("sand");
        assertTrue(q.spawns().isEmpty());
    }

    @Test
    void spawnBlockCombinesWithOtherTerms() {
        ParsedQuery q = parser.parse("village on sand");
        assertTrue(q.structures().contains("minecraft:village"));
        assertTrue(q.spawns().contains("minecraft:sand"));
    }
}
