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

import java.util.Comparator;
import java.util.List;

public final class SearchLimitReachedScreen extends Screen {

    private final CreateWorldScreen parentScreen;
    private final String queryText;
    private final ParsedQuery parsedQuery;
    private final SearchConfig config;
    private final String stopReason;
    private final List<SearchResult> candidates;

    public SearchLimitReachedScreen(CreateWorldScreen parentScreen, String queryText,
                                   ParsedQuery parsedQuery, SearchConfig config,
                                   String stopReason, List<SearchResult> candidates) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font,
                Component.literal("Search Limit Reached"));
        this.parentScreen = parentScreen;
        this.queryText = queryText;
        this.parsedQuery = parsedQuery;
        this.config = config;
        this.stopReason = stopReason;
        this.candidates = candidates;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int bw = 200;

        if (!candidates.isEmpty()) {
            this.addRenderableWidget(Button.builder(
                    Component.literal("Use best candidate (" + candidates.size() + " found)"),
                    b -> useBestCandidate()
            ).bounds(cx - bw / 2, this.height / 2 + 10, bw, 20).build());
        }

        this.addRenderableWidget(Button.builder(
                Component.literal(config.infiniteSeeds() ? "Search more (double time)" : "Search more (double limit)"),
                b -> searchMore()
        ).bounds(cx - bw / 2, this.height / 2 + (candidates.isEmpty() ? 10 : 36), bw, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Cancel"),
                b -> onClose()
        ).bounds(cx - bw / 2, this.height / 2 + (candidates.isEmpty() ? 36 : 62), bw, 20).build());
    }

    private void useBestCandidate() {
        if (candidates.isEmpty()) return;
        SearchResult best = candidates.stream()
                .min(Comparator.comparingDouble(SearchResult::distanceToStructure))
                .orElse(null);
        if (best == null) return;
        WYWFClient.LOGGER.info("User chose best candidate from {} limits: seed {}", candidates.size(), best.seed);
        WYWFClient.worldCreator().create(best, queryText, parentScreen);
    }

    private void searchMore() {
        SearchConfig extended = SearchConfig.defaults();
        extended.timeLimitMinutes(config.timeLimitMinutes() * 2);
        extended.maxSeedsToCheck(config.rawMaxSeedsToCheck() * 2);
        extended.infiniteSeeds(config.infiniteSeeds());
        extended.mode(config.mode());
        extended.manualThreads(config.manualThreads());
        extended.searchRadiusChunks(config.searchRadiusChunks());
        extended.biomeCheckRadiusChunks(config.biomeCheckRadiusChunks());
        extended.biomeSampleStepChunks(config.biomeSampleStepChunks());
        extended.candidatesToCollect(config.candidatesToCollect());
        extended.minCandidates(config.minCandidates());
        extended.candidateRampDownSeconds(config.candidateRampDownSeconds());
        extended.randomizeStart(config.randomizeStart());
        extended.stopAtFirstCandidate(config.stopAtFirstCandidate());
        extended.sortCandidatesByDistance(config.sortCandidatesByDistance());
        extended.searchCenter(config.searchCenter());
        extended.queryLanguage(config.queryLanguage());

        SearchScreen screen = new SearchScreen(parentScreen, queryText, parsedQuery, extended);
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

        g.centeredText(font, Component.literal("§lSearch Limit Reached§r"), cx, this.height / 2 - 50, 0xFFFFFFFF);
        g.centeredText(font, Component.literal("§c" + stopReason), cx, this.height / 2 - 30, 0xFFFFFFFF);

        if (candidates.isEmpty()) {
            g.centeredText(font, Component.literal("§7No matching seeds found."), cx, this.height / 2 - 10, 0xFFAAAAAA);
        } else {
            g.centeredText(font, Component.literal("§7Found §f" + candidates.size() + " §7candidate(s)"), cx, this.height / 2 - 10, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }
}
