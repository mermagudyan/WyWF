package com.wywf.search;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured audit logger that records every seed validation decision.
 * Output: wywf-audit.jsonl (one JSON object per line)
 * Used by the standalone verifier (test_verifier.exe) to cross-check with cubiomes.
 *
 * <p>The log file is created in the Minecraft game directory (where mods/ folder is).
 * Each line contains the MC version so the verifier knows which cubiomes version to use.
 */
public final class AuditLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("wywf-search");
    private static volatile Path logPath;
    private static volatile boolean enabled = false;
    private static volatile String mcVersion = "1.21";
    private static final Object FILE_LOCK = new Object();

    /** Enable audit logging. Called from WYWFClient.onInitializeClient().
     *  Only activates if system property "wywf.audit" is set to "true". */
    public static void enable() {
        if (!"true".equals(System.getProperty("wywf.audit"))) return;
        enabled = true;
        try {
            // Find game directory: go up from mods/ to find .minecraft or instance dir
            Path gameDir = findGameDir();
            logPath = gameDir.resolve("wywf-audit.jsonl");
            Files.deleteIfExists(logPath);
            Files.writeString(logPath, "");
            LOGGER.warn("[AuditLogger] Audit log enabled → {}", logPath.toAbsolutePath());
        } catch (Exception e) {
            // Fallback: write to current directory
            logPath = Path.of("wywf-audit.jsonl");
            try { Files.deleteIfExists(logPath); } catch (IOException ignored) {}
            LOGGER.warn("[AuditLogger] Using fallback path: {}", logPath.toAbsolutePath());
        }
    }

    /** Set MC version string (e.g. "1.21", "26.2"). Called from WorldContextFactory. */
    public static void setMcVersion(String version) {
        mcVersion = version;
    }

    private static Path findGameDir() {
        // Running from .minecraft/ or instance dir — mods/ is a subdirectory
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path modsDir = cwd.resolve("mods");
        if (Files.isDirectory(modsDir)) return cwd;
        // Try parent (some launchers set cwd to game dir root)
        Path parent = cwd.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("mods"))) return parent;
        return cwd;
    }

    public static void logSeed(long seed, int spawnX, int spawnZ,
                                String query,
                                List<String> matchedStructures,
                                List<String> structModifiers,
                                Map<String, int[]> structurePositions,
                                List<String> matchedBiomes,
                                List<String> biomeModifiers,
                                Map<String, Integer> biomeDistances,
                                boolean accepted) {
        if (!enabled || logPath == null) return;
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"mc\":\"").append(escapeJson(mcVersion)).append('"');
        sb.append(",\"seed\":").append(seed);
        sb.append(",\"spawnX\":").append(spawnX);
        sb.append(",\"spawnZ\":").append(spawnZ);
        sb.append(",\"query\":\"").append(escapeJson(query)).append('\"');
        sb.append(",\"accepted\":").append(accepted);
        sb.append(",\"structures\":[");
        if (matchedStructures != null) {
            for (int i = 0; i < matchedStructures.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"name\":\"").append(escapeJson(matchedStructures.get(i))).append('"');
                String mod = (structModifiers != null && i < structModifiers.size()) ? structModifiers.get(i) : "DEFAULT";
                sb.append(",\"mod\":\"").append(escapeJson(mod)).append('"');
                int[] pos = structurePositions != null ? structurePositions.get(matchedStructures.get(i)) : null;
                if (pos != null) { sb.append(",\"x\":").append(pos[0]); sb.append(",\"z\":").append(pos[1]); }
                sb.append('}');
            }
        }
        sb.append("],\"biomes\":[");
        if (matchedBiomes != null) {
            for (int i = 0; i < matchedBiomes.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"name\":\"").append(escapeJson(matchedBiomes.get(i))).append('"');
                String mod = (biomeModifiers != null && i < biomeModifiers.size()) ? biomeModifiers.get(i) : "DEFAULT";
                sb.append(",\"mod\":\"").append(escapeJson(mod)).append('"');
                Integer dist = biomeDistances != null ? biomeDistances.get(matchedBiomes.get(i)) : null;
                if (dist != null) sb.append(",\"dist\":").append(dist);
                sb.append('}');
            }
        }
        sb.append("]}\n");

        synchronized (FILE_LOCK) {
            try {
                Files.writeString(logPath, sb.toString(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOGGER.error("[AuditLogger] Write failed", e);
            }
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
