package com.wywtf;

import com.wywtf.core.KeywordDictionary;
import com.wywtf.core.QueryParser;
import com.wywtf.search.SeedSearcher;
import com.wywtf.world.WorldCreator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа мода на стороне клиента.
 *
 * Создаёт одиночные экземпляры:
 *   - словарь ключевых слов (расширяется через API)
 *   - парсер запросов
 *   - поисковик
 *   - создатель мира
 *
 * Доступ к ним — через статику, потому что модификация GUI CreateWorldScreen
 * происходит из mixin-ов, которым нужен стабильный entry-point.
 */
public final class WYWTFClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("WhatYouWantToFind");

    private static KeywordDictionary dictionary;
    private static QueryParser       parser;
    private static SeedSearcher      searcher;
    private static WorldCreator      worldCreator;

    @Override
    public void onInitializeClient() {
        dictionary   = new KeywordDictionary();
        parser       = new QueryParser(dictionary);
        worldCreator = new WorldCreator();
        searcher     = new SeedSearcher(new com.wywtf.search.WorldContextFactory() {
            @Override public com.wywtf.search.WorldContext create(long seed) {
                // Заглушка: в реальном моде здесь создаётся BiomeSource + ChunkGenerator для сида.
                // В 26.x — через RegistryAccess и MultiNoiseBiomeSource.createvanilla(registries, seed).
                return new com.wywtf.search.WorldContext(seed, null, null, null);
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (searcher.isRunning()) searcher.cancel();
        });

        LOGGER.info("[WYYTF] What You Want To Find initialized. Dictionary size: {}", dictionary.all().size());
    }

    public static KeywordDictionary dictionary()   { return dictionary; }
    public static QueryParser       parser()       { return parser; }
    public static SeedSearcher      searcher()     { return searcher; }
    public static WorldCreator      worldCreator() { return worldCreator; }
}
