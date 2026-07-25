package com.wywf.client;

import com.wywf.WYWFClient;
import com.wywf.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
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
        super(Component.literal("Did you mean?"));
        this.parentScreen = parentScreen;
        this.originalQuery = originalQuery;
        this.corrections = corrections;
        this.parsedQuery = parsedQuery;
        this.config = config;
    }

    private void addCenteredText(String text, int y) {
        int w = this.font.width(text);
        this.addRenderableWidget(new StringWidget(
                (this.width - w) / 2, y, w, 14,
                Component.literal(text), this.font));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int bw = 200;

        String correctedQuery = applyCorrections(originalQuery, corrections);

        int ty = this.height / 2 - 60;

        addCenteredText("\u00a7l\u00a7nDid you mean?\u00a7r", ty); ty += 18;
        addCenteredText("\u00a77You typed:\u00a7r \u00a7c\"" + truncate(originalQuery, 60) + "\"", ty); ty += 18;

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : corrections.entrySet()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append("\u00a7c").append(e.getKey()).append("\u00a7r \u2192 \u00a7a").append(e.getValue());
        }
        addCenteredText("\u00a77Suggested:\u00a7r " + sb, ty);

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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
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
