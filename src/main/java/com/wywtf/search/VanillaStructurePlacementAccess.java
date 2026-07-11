package com.wywtf.search;

/**
 * Тонкая прослойка для доступа к ванильному {@code StructurePlacement}.
 *
 * В 26.x актуальный путь: {@code ChunkGenerator.getStructurePlacement(ResourceKey)}.
 * У {@code StructurePlacement.isStructureChunk()} приватные/пакетные поля,
 * поэтому в финальной сборке сюда подключается Mixin-Accessor:
 *
 *   @Mixin(StructurePlacement.class)
 *   public interface StructurePlacementAccessor {
 *       @Invoker("isStructureChunk") boolean invokeIsStructureChunk(long seed, int x, int z);
 *   }
 *
 * Здесь — заглушка с равномерной хэш-функцией, чтобы мод компилировался и
 * работал как каркас. Заменяется на реальный accessor после сборки.
 */
public final class VanillaStructurePlacementAccess {

    private VanillaStructurePlacementAccess() {}

    /**
     * Заглушка: равномерный детерминированный хэш.
     * Возвращает true с частотой ~1/100 чанков — этого достаточно для демонстрации.
     *
     * В реальной реализации делегирует в StructurePlacementAccessor.
     */
    public static boolean isStructureChunk(String canonical, long seed, int cx, int cz) {
        long h = seed;
        h = 0x9E3779B97F4A7C15L * (h + cx);
        h = 0x9E3779B97F4A7C15L * (h + cz);
        h ^= canonical == null ? 0 : canonical.hashCode();
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        // Частота 1/160 — близко к ванильному spacing для деревни
        return (h & 0xFFL) == 0;
    }
}
