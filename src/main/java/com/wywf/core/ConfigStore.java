package com.wywf.core;

import com.wywf.WYWFClient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path configPath() {
        try {
            Path gameDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
            return gameDir.resolve("config/wywf.json");
        } catch (Throwable t) {
            return Path.of(System.getProperty("user.home"), ".minecraft", "config", "wywf.json");
        }
    }
    private static final Path LEGACY_PATH = Path.of(
            System.getProperty("user.home"), ".minecraft", "config", "wywf.json");

    private ConfigStore() {}

    public static SearchConfig load() {
        Path p = configPath();
        // Migrate legacy location if needed
        if (!Files.exists(p) && Files.exists(LEGACY_PATH)) {
            try { Files.createDirectories(p.getParent()); Files.copy(LEGACY_PATH, p); } catch (IOException ignored) {}
        }
        if (!Files.exists(p)) return SearchConfig.defaults();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            SearchConfig cfg = GSON.fromJson(r, SearchConfig.class);
            return cfg != null ? cfg : SearchConfig.defaults();
        } catch (IOException | RuntimeException e) {
            WYWFClient.LOGGER.info("[WyWF] Failed to load config, using defaults: {}", e.getMessage());
            return SearchConfig.defaults();
        }
    }

    public static void save(SearchConfig cfg) {
        Path p = configPath();
        try {
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                GSON.toJson(cfg, w);
            }
        } catch (IOException e) {
            WYWFClient.LOGGER.info("[WyWF] Failed to save config: {}", e.getMessage());
        }
    }
}
