package com.wywf.client;

import com.wywf.core.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

final class SearchHistoryContext {
    static SearchHistory store() {
        return new SearchHistory(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("wywf/search-history.json"));
    }
    static String key(CreateWorldScreen creator, ParsedQuery query, SearchConfig config) {
        var ui = creator.getUiState();
        var context = ui.getSettings();
        String dimensions = WorldDimensions.CODEC.codec().encodeStart(RegistryOps.create(JsonOps.INSTANCE,
                context.worldgenLoadContext()), context.selectedDimensions()).getOrThrow().toString();
        String environment = net.minecraft.SharedConstants.getCurrentVersion().id() + "|" + ui.isGenerateStructures()
                + "|" + context.dataConfiguration() + "|" + dimensions;
        return SearchHistory.key(java.util.UUID.nameUUIDFromBytes(environment.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(), query, config);
    }
    private SearchHistoryContext() {}
}
