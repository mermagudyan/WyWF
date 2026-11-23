package com.wywf.world;

import com.wywf.WYWFClient;
import com.wywf.core.ConfigStore;
import com.wywf.core.ParsedQuery;
import com.wywf.core.SearchConfig;
import com.wywf.core.SearchResult;
import com.wywf.verify.SavedWorldMap;
import com.wywf.verify.SavedWorldMaps;
import com.wywf.verify.VerificationSummary;
import com.wywf.mixin.CreateWorldScreenInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import java.util.WeakHashMap;
import java.util.UUID;

public final class WorldCreator {

    private record Creation(CreateWorldScreen screen, long seed) {}
    private static Creation creation;
    private static SavedWorldMap pendingMap;
    private record AppliedCenter(String query, SearchConfig.SearchCenter center) {}
    private static final Map<CreateWorldScreen, AppliedCenter> appliedCenters = new WeakHashMap<>();

    public static void applySearchCenter(CreateWorldScreen screen, String query, SearchConfig.SearchCenter center) {
        if (center == null) appliedCenters.remove(screen);
        else appliedCenters.put(screen, new AppliedCenter(query, center));
    }

    public static SearchConfig searchConfig(CreateWorldScreen screen, String query) {
        var config = ConfigStore.load();
        var applied = appliedCenters.get(screen);
        if (applied != null && applied.query().equals(query)) config.searchCenter(applied.center());
        return config;
    }

    public static boolean isCreatingCandidate(CreateWorldScreen screen) {
        return creation != null && creation.screen() == screen;
    }

    public static WorldCreationContext withCandidateSeed(CreateWorldScreen screen, WorldCreationContext settings) {
        return isCreatingCandidate(screen)
                ? settings.withOptions(options -> options.withSeed(OptionalLong.of(creation.seed())))
                : settings;
    }

    public void create(SearchResult result, String originalText, CreateWorldScreen screen) {
        create(result, null, null, screen);
    }

    public void create(SearchResult result, ParsedQuery query, SearchConfig config, CreateWorldScreen screen) {
        create(result, query, config, screen, new VerificationSummary(VerificationSummary.Status.PARTIAL,
                result.centerX, 0, result.centerZ, List.of(), "Created without server verification"));
    }

    public void create(SearchResult result, ParsedQuery query, SearchConfig config, CreateWorldScreen screen,
                       VerificationSummary verification) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            pendingMap = query == null ? null : new SavedWorldMap(UUID.randomUUID().toString(),
                    net.minecraft.SharedConstants.getCurrentVersion().id(), result, query, config.copy(),
                    verification, null, List.of(), List.of());
            creation = new Creation(screen, result.seed);
            try {
                ((CreateWorldScreenInvoker) screen).wywf_invokeOnCreate();
            } catch (RuntimeException error) {
                pendingMap = null;
                throw error;
            } finally {
                creation = null;
            }
        });
    }

    public static void persistMap(Path directory, long seed, boolean fresh) {

        var map = pendingMap;
        pendingMap = null;
        if (!fresh || map == null || seed != map.result().seed) return;
        try {
            SavedWorldMaps.write(directory, map);
        } catch (IOException error) {
            WYWFClient.LOGGER.warn("Could not save unverified search layers", error);
        }
    }
}
