package com.wywf.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class ConfigTranslations {

    private static final Logger LOGGER = LoggerFactory.getLogger("wywf-config");
    private static final Gson GSON = new Gson();

    private static volatile Map<String, String> cache;
    private static volatile String loadedLang;

    private ConfigTranslations() {}

    private static String currentLang() {
        String code = Minecraft.getInstance().getLanguageManager().getSelected();
        return code != null ? code : "en_us";
    }

    public static synchronized void invalidate() {
        cache = null;
        loadedLang = null;
    }

    public static synchronized String tr(String key) {
        String lang = currentLang();
        if (cache == null || !lang.equals(loadedLang)) {
            LOGGER.info("[WyWF] Config translations: {}", lang);
            load(lang);
            loadedLang = lang;
        }
        String value = cache.getOrDefault(key, key);
        if (value.equals(key)) {
            LOGGER.debug("[WyWF] Missing translation key: {}", key);
        }
        return value;
    }

    private static void load(String lang) {
        Map<String, String> map = new HashMap<>();

        loadFile(map, "assets/wywf/data/config_en_us.json");
        if (!"en_us".equals(lang)) {
            loadFile(map, "assets/wywf/data/config_" + lang + ".json");
        }

        cache = map;
    }

    private static void loadFile(Map<String, String> map, String resource) {
        InputStream in = ConfigTranslations.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) return;
        try (InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            if (root == null) return;
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    map.put(e.getKey(), e.getValue().getAsString());
                }
            }
        } catch (Exception ex) {
            LOGGER.info("Failed to load config translations '{}': {}", resource, ex.getMessage());
        }
    }
}
