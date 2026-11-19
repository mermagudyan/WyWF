package com.wywf.client;

import com.wywf.WYWFClient;
import com.wywf.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

public final class QuerySyntaxScreen extends Screen {
    private final Screen parent;
    private final java.util.function.Consumer<String> accept;
    private final ParsedQuery query;
    private final String suggestion;
    public QuerySyntaxScreen(CreateWorldScreen parent, ParsedQuery query, SearchConfig config) {
        this(parent, query, corrected -> {
            parent.getUiState().setSeed(corrected);
            new SearchScreen(parent, corrected, WYWFClient.parser().parse(corrected), config).open();
        });
    }
    public QuerySyntaxScreen(Screen parent, ParsedQuery query, java.util.function.Consumer<String> accept) {
        super(MapTexts.label("query_error"));
        this.parent = parent;
        this.query = query;
        this.accept = accept;
        suggestion = QueryRepairs.suggest(query, WYWFClient.parser(), WYWFClient.dictionary());
    }
    @Override protected void init() {
        if (suggestion != null && WYWFClient.parser().parse(suggestion).error() == null) {
            addRenderableWidget(Button.builder(MapTexts.label("use_suggestion"), b -> {
                accept.accept(suggestion);
            }).bounds(width / 2 - 120, height - 58, 240, 20).build());
        }
        addRenderableWidget(Button.builder(ResultTexts.label("back"), b -> onClose())
                .bounds(width / 2 - 120, height - 30, 240, 20).build());
    }
    @Override public void onClose() { Minecraft.getInstance().setScreenAndShow(parent); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
        g.centeredText(font, title, width / 2, 20, 0xFFFFFFFF);
        g.centeredText(font, font.plainSubstrByWidth(query.raw(), width - 30), width / 2, 47, 0xFFE3D7AD);
        var detail = query.error() == null ? MapTexts.label("query_error_help") : net.minecraft.network.chat.Component.literal(query.error());
        int yDetail = 69;
        for (var line : font.split(detail, width - 30)) {
            if (yDetail + font.lineHeight > height - 62) break;
            g.centeredText(font, line, width / 2, yDetail, 0xFFD5E1E5);
            yDetail += font.lineHeight;
        }
        if (suggestion != null && yDetail + font.lineHeight + 8 < height - 60)
            g.centeredText(font, font.plainSubstrByWidth(suggestion, width - 30), width / 2, yDetail + 8, 0xFF9CCA9A);
        super.extractRenderState(g, x, y, delta);
    }
}
