package com.wywf.search;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SulfurRegressionTest {

    @Test
    void corpusVerdicts() throws Exception {
        assumeTrue(CubiomesBridge.isActive());
        Path p = Paths.get("src/test/resources/regression/structures.json");
        if (!Files.exists(p)) {
            p = Paths.get("src/test/resources/regression/structures.json").toAbsolutePath();
        }
        String json = Files.readString(p, StandardCharsets.UTF_8);
        List<Map<String, Object>> cases = new Gson().fromJson(json,
                new TypeToken<List<Map<String, Object>>>() {}.getType());
        assertFalse(cases.isEmpty());
        int skipped = 0;
        int slot = CubiomesBridge.mcSlot();
        for (Map<String, Object> c : cases) {
            // Cases are worldgen-specific: sulfur verdicts only exist
            // on slots that have sulfur (26.2+). Other slots skip them.
            boolean applies = false;
            for (Object s : (List<?>) c.get("slots")) {
                if (((Number) s).intValue() == slot) { applies = true; break; }
            }
            if (!applies) {
                skipped++;
                continue;
            }
            long seed = ((Number) c.get("seed")).longValue();
            int struct = ((Number) c.get("struct")).intValue();
            int x = ((Number) c.get("x")).intValue();
            int z = ((Number) c.get("z")).intValue();
            boolean expected = (Boolean) c.get("viable");
            CubiomesBridge.applySeed(seed);
            boolean got = CubiomesBridge.isViableStructurePos(struct, x, z);
            assertEquals(expected, got, "seed=" + seed + " struct=" + struct
                    + " (" + x + "," + z + ") " + c.get("source"));
        }
        System.out.println("[sulfur] cases=" + cases.size() + " skipped=" + skipped);
    }
}
