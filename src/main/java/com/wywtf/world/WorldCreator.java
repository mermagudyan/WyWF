package com.wywtf.world;

import com.wywtf.core.SearchResult;

/**
 * Создаёт мир с найденным сидом и сдвигает спавн максимально близко к найденной точке.
 *
 * Алгоритм:
 *   1. Положить найденный сид и спавн в {@link PendingWorldCreation}.
 *   2. Миксин {@code WorldGenSettingsComponentMixin} подхватит сид при парсинге.
 *   3. Миксин {@code ServerLevelMixin} подхватит спавн после создания мира.
 *   4. Запустить стандартный путь создания мира через CreateWorldScreen.onCreate.
 *
 * Запускать только на render/main потоке. Иначе Minecraft не применит изменения.
 */
public final class WorldCreator {

    /**
     * Создаёт мир с найденным сидом.
     *
     * @param seed           числовой сид
     * @param spawnX         целевой X спавна
     * @param spawnZ         целевой Z спавна
     * @param originalText   исходный текст пользователя (для истории)
     */
    public void create(long seed, int spawnX, int spawnZ, String originalText) {
        // 1. Положить результат в почтовый ящик
        PendingWorldCreation.set(seed, spawnX, spawnZ);
        // 2. Запланировать сдвиг спавна
        SpawnAdjuster.scheduleAdjustment(spawnX, spawnZ);
        // 3. Создание мира запускается через миксин CreateWorldScreenMixin:
        //    после того как мы вернулись из SearchScreen, экран создания мира
        //    снова вызовет onCreate() и на этот раз наш mixin увидит PendingWorldCreation
        //    и пропустит перехват (т.к. строка сида — уже числовой сид),
        //    а WorldGenSettingsComponentMixin подменит распарсенный сид на наш.
    }

    public void create(SearchResult result, String originalText) {
        create(result.seed, result.spawnX, result.spawnZ, originalText);
    }
}
