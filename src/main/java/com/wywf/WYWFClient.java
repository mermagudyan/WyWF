package com.wywf;

import com.wywf.core.KeywordDictionary;
import com.wywf.core.QueryParser;
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

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (searcher.isRunning()) searcher.cancel();
        });

        LOGGER.info("[WyWF] What you Want to Find initialized. Dictionary size: {}", dictionary.all().size());
    }

    /** (Re)builds the keyword dictionary, parser and searcher for the given query
     *  language. Called at startup and whenever the user changes the language
     *  in the search config. */
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
