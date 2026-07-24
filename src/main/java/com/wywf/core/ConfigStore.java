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

    private static final Path CONFIG_PATH = Path.of(
            System.getProperty("user.home"), ".minecraft", "config", "wywf.json");

    private ConfigStore() {}

    public static SearchConfig load() {
        if (!Files.exists(CONFIG_PATH)) return SearchConfig.defaults();
        try (Reader r = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            SearchConfig cfg = GSON.fromJson(r, SearchConfig.class);
            return cfg != null ? cfg : SearchConfig.defaults();
        } catch (IOException | RuntimeException e) {
            WYWFClient.LOGGER.info("[WyWF] Failed to load config, using defaults: {}", e.getMessage());
            return SearchConfig.defaults();
        }
    }

    public static void save(SearchConfig cfg) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer w = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(cfg, w);
            }
        } catch (IOException e) {
            WYWFClient.LOGGER.info("[WyWF] Failed to save config: {}", e.getMessage());
        }
    }
}
