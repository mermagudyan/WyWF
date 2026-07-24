package com.wywf.client;

import com.wywf.WYWFClient;
import com.wywf.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;

import java.util.*;

public final class DidYouMeanScreen extends Screen {

    private final CreateWorldScreen parentScreen;
    private final String originalQuery;
    private final Map<String, String> corrections;
    private final ParsedQuery parsedQuery;
    private final SearchConfig config;

    public DidYouMeanScreen(CreateWorldScreen parentScreen, String originalQuery,
                            Map<String, String> corrections, ParsedQuery parsedQuery, SearchConfig config) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font,
                Component.literal("Did you mean?"));
        this.parentScreen = parentScreen;
        this.originalQuery = originalQuery;
        this.corrections = corrections;
        this.parsedQuery = parsedQuery;
        this.config = config;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int bw = 200;

        String correctedQuery = applyCorrections(originalQuery, corrections);

        this.addRenderableWidget(Button.builder(
                Component.literal("Use: \"" + truncate(correctedQuery, 40) + "\""),
                b -> startSearchWith(correctedQuery)
        ).bounds(cx - bw / 2, this.height / 2 + 20, bw, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Search as-is"),
                b -> startSearchWith(originalQuery)
        ).bounds(cx - bw / 2, this.height / 2 + 46, bw, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Cancel"),
                b -> onClose()
        ).bounds(cx - bw / 2, this.height / 2 + 72, bw, 20).build());
    }

    private void startSearchWith(String queryText) {
        ParsedQuery query = WYWFClient.parser().parse(queryText);
        if (query.isEmpty()) {
            onClose();
            return;
        }
        SearchScreen screen = new SearchScreen(parentScreen, queryText, query, config);
        Minecraft.getInstance().setScreenAndShow(screen);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parentScreen);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Font font = this.font;
        int cx = this.width / 2;

        g.centeredText(font, Component.literal("§lDid you mean?§r"), cx, this.height / 2 - 60, 0xFFFFFF);
        g.centeredText(font, Component.literal("§7You typed:§r §c\"" + truncate(originalQuery, 60) + "\""), cx, this.height / 2 - 38, 0xFFFFFF);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : corrections.entrySet()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append("§c").append(e.getKey()).append("§r → §a").append(e.getValue());
        }
        g.centeredText(font, Component.literal("§7Suggested:§r " + sb), cx, this.height / 2 - 22, 0xFFFFFF);
    }

    static String applyCorrections(String original, Map<String, String> corrections) {
        String[] words = original.toLowerCase(java.util.Locale.ROOT).split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String fix = corrections.get(words[i]);
            if (fix != null) {
                words[i] = fix;
            }
        }
        return String.join(" ", words);
    }

    public static Map<String, String> findCorrections(List<String> ignoredWords) {
        var dict = WYWFClient.dictionary();
        Map<String, String> corrections = new LinkedHashMap<>();
        for (String word : ignoredWords) {
            if (corrections.containsKey(word)) continue;
            String fix = dict.findSuggestion(word);
            if (fix != null) {
                corrections.put(word, fix);
            }
        }
        return corrections;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }
}
