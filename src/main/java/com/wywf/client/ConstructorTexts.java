package com.wywf.client;

import com.google.gson.Gson;
import com.wywf.core.KeywordDictionary;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class ConstructorTexts {
    private static final Map<String, Map<String, String>> CACHE = new HashMap<>();
    private ConstructorTexts() {}

    static String tr(String key) {
        String code = Minecraft.getInstance().getLanguageManager().getSelected();
        String language = code.startsWith("ru") ? "ru_ru" : "en_us";
        return CACHE.computeIfAbsent(language, ConstructorTexts::load).getOrDefault(key, key);
    }

    static KeywordDictionary.Lang displayLanguage(KeywordDictionary.Lang language) {
        if (language != KeywordDictionary.Lang.AUTO)
            return language;
        return Minecraft.getInstance().getLanguageManager().getSelected().startsWith("ru")
                ? KeywordDictionary.Lang.RU
                : KeywordDictionary.Lang.EN;
    }

    private static Map<String, String> load(String language) {
        String path = "/assets/wywf/data/constructor_" + language + ".json";
        try (var stream = ConstructorTexts.class.getResourceAsStream(path)) {
            if (stream == null)
                return Map.of();
            var reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            Map<String, String> values = new Gson().fromJson(reader,
                    new TypeToken<Map<String, String>>() {}.getType());
            return values == null ? Map.of() : Map.copyOf(values);
        } catch (java.io.IOException | RuntimeException error) {
            com.wywf.WYWFClient.LOGGER.warn("Failed to load constructor labels {}", language, error);
            return Map.of();
        }
    }
}
