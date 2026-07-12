package com.wywf;

import com.wywf.core.KeywordDictionary;
import com.wywf.core.QueryParser;
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
        dictionary   = new KeywordDictionary();
        parser       = new QueryParser(dictionary);
        worldCreator = new WorldCreator();
        searcher     = new SeedSearcher(new com.wywf.search.MinecraftWorldContextFactory());

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (searcher.isRunning()) searcher.cancel();
        });

        LOGGER.info("[WYWF] What You Want To Find initialized. Dictionary size: {}", dictionary.all().size());
    }

    public static KeywordDictionary dictionary()   { return dictionary; }
    public static QueryParser       parser()       { return parser; }
    public static SeedSearcher      searcher()     { return searcher; }
    public static WorldCreator      worldCreator() { return worldCreator; }
}
