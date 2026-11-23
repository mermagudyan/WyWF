package com.wywf;

import com.wywf.core.KeywordDictionary;
import com.wywf.core.QueryParser;
import com.wywf.core.WyWFDebug;
import com.wywf.search.AuditLogger;
import com.wywf.search.SeedSearcher;
import com.wywf.world.WorldCreator;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WYWFClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("WhatYouWantToFind");

    private static KeywordDictionary dictionary;
    private static QueryParser       parser;
    private static SeedSearcher      searcher;
    private static WorldCreator      worldCreator;

    @Override
    public void onInitializeClient() {
        worldCreator = new WorldCreator();
        applyQueryLanguage(KeywordDictionary.Lang.AUTO);
        AuditLogger.enable();
        com.wywf.client.OnlineSearchBenchmark.install();
        com.wywf.client.ReliefGpu.register();
        com.wywf.client.SearchHistoryPrompt.install();
        var mapCategory = net.minecraft.client.KeyMapping.Category.register(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("wywf", "controls"));
        var openMap = net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(
                new net.minecraft.client.KeyMapping("key.wywf.find_place",
                        com.mojang.blaze3d.platform.InputConstants.KEY_M, mapCategory));
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register(
                (client, screen, width, height) -> com.wywf.client.PauseMapButton.add(client, screen));
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            com.wywf.client.PlayerAtlas.tick(client);
            while (openMap.consumeClick()) if (com.wywf.client.ClientPlatform.screen(client) == null)
                com.wywf.client.PauseMapButton.openMap(null);
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            com.wywf.client.PlayerAtlas.flush();
            if (searcher.isRunning()) searcher.cancel();
        });

        if (WyWFDebug.ENABLED) LOGGER.info("[WyWF] What you Want to Find initialized. Dictionary size: {}", dictionary.all().size());
    }

    // Rebuilds dictionary/parser/searcher for the language. Called at startup and on language change
    public static synchronized void applyQueryLanguage(KeywordDictionary.Lang lang) {
        if (searcher != null && searcher.isRunning()) searcher.cancel();
        dictionary   = new KeywordDictionary(lang);
        parser       = new QueryParser(dictionary);
        searcher     = new SeedSearcher(new com.wywf.search.MinecraftWorldContextFactory(), dictionary);
    }

    public static KeywordDictionary dictionary()   { return dictionary; }
    public static QueryParser       parser()       { return parser; }
    public static SeedSearcher      searcher()     { return searcher; }
    public static WorldCreator      worldCreator() { return worldCreator; }
}
