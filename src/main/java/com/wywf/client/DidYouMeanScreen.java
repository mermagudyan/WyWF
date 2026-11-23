package com.wywf.client;

import com.wywf.WYWFClient;
import com.wywf.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class DidYouMeanScreen extends Screen {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_-]+");

    private final Component heading = MenuAccent.heading(title.getString());
    private final Screen back;
    private final String original;
    private final String corrected;
    private final Consumer<String> accept;
    private final boolean canUseOriginal;
    private final BlockMenuSkin skin = BlockMenuSkin.random();
    private int panelWidth, panelX, panelY, panelHeight;

    public DidYouMeanScreen(CreateWorldScreen parent, String original, Map<String, String> corrections,
                            ParsedQuery parsed, SearchConfig config) {
        this(parent, original, corrections, parsed.error() == null && !parsed.isEmpty(), queryText -> {
            ParsedQuery query = WYWFClient.parser().parse(queryText);
            if (query.error() != null || query.isEmpty()) {
                Minecraft.getInstance().setScreenAndShow(new QuerySyntaxScreen(parent, query, config));
                return;
            }
            parent.getUiState().setSeed(queryText);
            if (QueryAdvisor.inspect(query, config).reviewBeforeSearch()) {
                Minecraft.getInstance().setScreenAndShow(new SearchAdviceScreen(parent, queryText, query, config));
            } else new SearchScreen(parent, queryText, query, config).open();
        });
    }

    DidYouMeanScreen(Screen back, String original, Map<String, String> corrections,
                     boolean canUseOriginal, Consumer<String> accept) {
        super(MapTexts.label("spelling_title"));
        this.back = back;
        this.original = original;
        this.corrected = applyCorrections(original, corrections);
        this.accept = accept;
        this.canUseOriginal = canUseOriginal;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(420, width - 24);
        panelHeight = Math.min(150, height - 24);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        int buttonWidth = (panelWidth - 24 - 8) / 3;
        int x = (width - buttonWidth * 3 - 8) / 2;
        int y = panelY + panelHeight - 30;
        addRenderableWidget(MenuButton.builder(MapTexts.label("spelling_use"), b -> accept.accept(corrected))
                .bounds(x, y, buttonWidth, 20).build());
        Button unchanged = addRenderableWidget(MenuButton.builder(MapTexts.label("spelling_as_written"), b -> accept.accept(original))
                .bounds(x + buttonWidth + 4, y, buttonWidth, 20).build());
        unchanged.active = canUseOriginal;
        addRenderableWidget(MenuButton.builder(ResultTexts.label("back"), b -> onClose())
                .bounds(x + 2 * (buttonWidth + 4), y, buttonWidth, 20).build());
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(back);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        BlockMenuSkin.panel(g, skin, panelX, panelY, panelWidth, panelHeight, 0xFF83BC9D, 0xFFD9BA86);
        g.centeredText(font, heading, width / 2, panelY + 10, 0xFFFFFFFF);
        int area = (panelHeight - 60) / 2;
        int firstY = panelY + 29;
        int secondY = firstY + area;
        g.centeredText(font, MapTexts.label("spelling_typed"), width / 2, firstY, 0xFFE3DDD3);
        drawQuery(g, original, firstY + font.lineHeight + 2, area - font.lineHeight - 3, 0xFFFFC7C0);
        g.centeredText(font, MapTexts.label("spelling_corrected"), width / 2, secondY, 0xFFE3DDD3);
        drawQuery(g, corrected, secondY + font.lineHeight + 2, area - font.lineHeight - 3, 0xFFD4F2C9);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawQuery(GuiGraphicsExtractor g, String query, int y, int area, int color) {
        int measure = panelWidth - 24;
        int offset = 0;
        var lines = font.split(Component.literal(query), measure - font.width("…"));
        for (var line : lines) {
            if (offset + font.lineHeight > area) break;
            g.centeredText(font, line, width / 2, y + offset, color);
            offset += font.lineHeight;
        }
        if (offset < lines.size() * font.lineHeight) g.text(font, "…", panelX + panelWidth - 16, y + Math.max(0, offset - font.lineHeight), color);
    }

    static String applyCorrections(String original, Map<String, String> corrections) {
        return WORD.matcher(original).replaceAll(word -> java.util.regex.Matcher.quoteReplacement(
                corrections.getOrDefault(word.group().toLowerCase(Locale.ROOT), word.group())));
    }

    public static Map<String, String> findCorrections(List<String> ignoredWords) {
        Map<String, String> corrections = new LinkedHashMap<>();
        for (String word : ignoredWords) {
            String fix = WYWFClient.dictionary().findSuggestion(word);
            if (fix != null) corrections.put(word, fix);
        }
        return corrections;
    }
}
