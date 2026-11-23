package com.wywf.client;

import com.wywf.WYWFClient;
import com.wywf.core.*;
import com.wywf.verify.PreviewWorld;
import com.wywf.verify.VerificationSummary;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

public final class CandidateReviewScreen extends Screen {
    private final CreateWorldScreen parent;
    private final String queryText;
    private final ParsedQuery query;
    private final SearchConfig config;
    private final CandidateReview review;
    private final String stopReason;
    private final List<BlockMenuSkin> cardSkins;
    private final List<CandidateCard> cards = new ArrayList<>();
    private Button chunksView, detailsAction, createAction;
    private CandidateGrid grid;
    private int page = -1;
    private String reviewStatus;
    private long copiedUntil, coordinatesCopiedUntil;
    private boolean creating;
    private boolean selectionConfirmed;
    private final com.wywf.map.BiomeMaps.Scope mapCache=com.wywf.map.BiomeMaps.scope();
    private final java.util.Map<String,SurfaceMapScreen> mapScreens=new java.util.HashMap<>();
    private SearchResult pendingMap;
    private PreviewWorld.Session unverifiedPreview;
    private SurfaceMapScreen unverifiedMap;
    private java.util.Set<String> layerSelection;

    void rememberLayers(java.util.Set<String> selection) {
        layerSelection = java.util.Set.copyOf(selection);
    }

    private void openMap(SurfaceMapScreen map) {
        map.restoreLayers(layerSelection);
        Minecraft.getInstance().setScreenAndShow(map);
    }
    com.wywf.map.BiomeMaps.Scope mapCache(){
        return mapCache;
    }
    private void releaseMaps(){
        releaseUnverifiedMap();
        cards.forEach(CandidateCard::closeMap);
        mapScreens.values().forEach(SurfaceMapScreen::release);
        mapScreens.clear();
        mapCache.close();
    }

    public CandidateReviewScreen(CreateWorldScreen parent, String queryText, ParsedQuery query, SearchConfig config,
                                 CandidateReview review, String stopReason) {
        super(ResultTexts.label("title"));
        this.parent = parent;
        this.queryText = queryText;
        this.query = query;
        this.config = config.copy();
        this.review = review;
        this.stopReason = stopReason;
        cardSkins = review.candidates().isEmpty() ? List.of() : MenuBackgrounds.cards(review.candidates().getFirst().seed);
    }

    private boolean selectedPreviewAvailable() {
        return review.selected().map(result ->
                com.wywf.verify.PreviewWorld.find(result, query, config) != null).orElse(false);
    }

    @Override
    protected void init() {
        if (unverifiedMap != null) releaseUnverifiedMap();
        boolean hasChecks = review.candidates().stream().anyMatch(result -> review.verification(result) != null);
        reviewStatus = hasChecks ? verificationStatus()
                : stopReason == null || stopReason.isBlank() ? ResultTexts.text("complete") : stopReason;
        grid = CandidateGrid.forWindow(width, height);
        if (page < 0)
            page = Math.max(0, review.selectedIndex()) / grid.capacity();
        page = Math.min(page, grid.pages(review.candidates().size()) - 1);
        cards.forEach(CandidateCard::closeMap);
        cards.clear();
        int first = page * grid.capacity();
        int limit = Math.min(review.candidates().size(), first + grid.capacity());
        for (int index = first; index < limit; index++) {
            final int picked = index;
            int slot = index - first;
            SearchResult result = review.candidates().get(index);
            CandidateCard card = new CandidateCard(grid.x(slot), grid.y(slot), grid.cardWidth(), grid.cardHeight(),
                    font, result, query, config, mapCache, cardSkins.get(index % cardSkins.size()),
                    review.verification(result), () -> select(picked));
            // keep the suggested seed available to the buttons before the user picks a card
            card.selected(index == review.selectedIndex(), selectionConfirmed);
            cards.add(addRenderableWidget(card));
        }
        Button previous = addRenderableWidget(MenuButton.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(12, height - 60, 26, 20).build());
        previous.active = page > 0;
        Button next = addRenderableWidget(MenuButton.builder(Component.literal(">"), b -> changePage(1))
                .bounds(100, height - 60, 26, 20).build());
        next.active = page + 1 < grid.pages(review.candidates().size());
        int remaining = width - 156;
        int mapWidth = Math.min(100, (remaining - 12) / 3);
        chunksView = addRenderableWidget(MenuButton.builder(MapTexts.label(review.candidates().isEmpty() ? "simplify" : "open"), b ->
                {
                    if (review.candidates().isEmpty())
                        Minecraft.getInstance().setScreenAndShow(
                                new SearchAdviceScreen(parent, queryText, query, config).forPlace(placeSearch));
                    else review.selected().ifPresent(result -> {
                        var saved = com.wywf.verify.PreviewWorld.find(result, query, config);
                        if (saved != null)
                            openMap(mapScreens.computeIfAbsent(result.seed+":"+result.centerX+":"+result.centerZ,key->
                                    new SurfaceMapScreen(this,saved)));
                        else {
                            pendingMap = result;
                            updatePreviewActions();
                        }
                    });
                })
                .bounds(138, height - 60, mapWidth, 20).build());
        int searchX = 144 + mapWidth;
        int searchWidth = remaining - mapWidth - 6;
        boolean offerBatch = placeSearch == null;
        int moreWidth = offerBatch ? (searchWidth - 6) / 2 : searchWidth;
        addRenderableWidget(MenuButton.builder(ResultTexts.label("more"), b -> {
            releaseMaps();
            new SearchScreen(parent, queryText, query, config.extendedLimits())
                    .fresh().forPlace(placeSearch).appendTo(review).withLayers(layerSelection).open();
        }).bounds(searchX, height - 60, moreWidth, 20).build());
        if (offerBatch) {
            Button verify = addRenderableWidget(MenuButton.builder(ResultTexts.label("verify_all"), b -> {
                releaseUnverifiedMap();
                Minecraft.getInstance().setScreenAndShow(new CandidateVerificationScreen(this, query, review));
            }).bounds(searchX + moreWidth + 6, height - 60, searchWidth - moreWidth - 6, 20).build());
            verify.setTooltip(Tooltip.create(ResultTexts.label("verify_all_hint")));
            verify.active = review.candidates().stream()
                    .anyMatch(result -> CandidateVerificationScreen.needsVerification(result, query, config, review));
        }
        int actionWidth = (width - 42) / 4;
        detailsAction = addRenderableWidget(MenuButton.builder(ResultTexts.label("details"), b -> openDetails())
                .bounds(12, height - 30, actionWidth, 20).build());
        Button copy = addRenderableWidget(MenuButton.builder(ResultTexts.label("copy_seed"), b -> copySeed())
                .bounds(18 + actionWidth, height - 30, actionWidth, 20).build());
        createAction = addRenderableWidget(MenuButton.builder(placeSearch == null
                        ? ResultTexts.label("create")
                        : MapTexts.label("ftp_copy_coords"), b -> createSelected())
                .bounds(24 + actionWidth * 2, height - 30, actionWidth, 20).build());
        copy.active = !review.candidates().isEmpty() && !creating;
        updatePreviewActions();
        addRenderableWidget(MenuButton.builder(ResultTexts.label("back"), b -> onClose())
                .bounds(30 + actionWidth * 3, height - 30, actionWidth, 20).build());
    }

    private void updatePreviewActions() {
        boolean selected = !review.candidates().isEmpty() && !creating;
        boolean available = selected && selectedPreviewAvailable();
        boolean mapOnly = selected && !available;
        boolean allowUnverifiedCreation = mapOnly && !config.serverVerification();
        var checked = review.selected().map(review::verification).orElse(null);
        var statusHint = checked == null ? ResultTexts.label("offline_help")
                : Component.literal(CandidateVerificationScreen.statusText(checked));
        chunksView.active = pendingMap == null && (review.candidates().isEmpty() || selected);
        chunksView.setMessage(MapTexts.label(pendingMap != null ? "loading" : review.candidates().isEmpty() ? "simplify" : "open"));
        chunksView.setTooltip(mapOnly ? Tooltip.create(MapTexts.label("raw_map_help"))
                : available ? Tooltip.create(MapTexts.label("preview_open_help")) : null);
        detailsAction.active = selected;
        detailsAction.setTooltip(mapOnly ? Tooltip.create(statusHint) : null);
        boolean previouslyVerified = checked != null && checked.status() == VerificationSummary.Status.PASS;
        createAction.active = selected && (placeSearch != null || available || previouslyVerified || allowUnverifiedCreation);
        if (placeSearch == null) createAction.setTooltip(available ? Tooltip.create(MapTexts.label("create_ready_help"))
                : previouslyVerified || allowUnverifiedCreation ? Tooltip.create(statusHint)
                : selected ? Tooltip.create(MapTexts.label("candidate_preview_missing")) : null);
    }

    @Override
    protected void setInitialFocus() {
        int slot = review.selectedIndex() - page * grid.capacity();
        if (slot >= 0 && slot < cards.size()) setInitialFocus(cards.get(slot));
        else super.setInitialFocus();
    }

    @Override public void tick() {
        cards.forEach(CandidateCard::tickMap);
        if (pendingMap != null) openUnverifiedMap();
    }

    private void openUnverifiedMap() {
        if (unverifiedPreview == null) {
            if (PreviewWorld.active() != null) return;
            try {
                unverifiedPreview = PreviewWorld.beginMap(parent, pendingMap, query, config, this);
            } catch (Exception error) {
                WYWFClient.LOGGER.warn("Could not start candidate map preview", error);
                showMapError(error.getMessage());
                return;
            }
        }
        var summary = unverifiedPreview.summary();
        if (unverifiedPreview.cancelled() || summary.terminal() && summary.status() != VerificationSummary.Status.PARTIAL) {
            showMapError(summary.note());
        } else if (summary.status() == VerificationSummary.Status.PARTIAL) {
            pendingMap = null;
            unverifiedMap = new SurfaceMapScreen(this, unverifiedPreview);
            openMap(unverifiedMap);
        }
    }

    private void showMapError(String message) {
        releaseUnverifiedMap();
        Minecraft.getInstance().setScreenAndShow(new AlertScreen(
                () -> Minecraft.getInstance().setScreenAndShow(this), MapTexts.label("surface_title"),
                MapTexts.label("error", message)));
    }

    private void releaseUnverifiedMap() {
        pendingMap = null;
        if (unverifiedMap != null) unverifiedMap.release();
        unverifiedMap = null;
        if (unverifiedPreview != null) unverifiedPreview.cancel();
        unverifiedPreview = null;
    }
    @Override public void removed() {
        cards.forEach(CandidateCard::closeMap);
    }

    private void select(int index) {
        if (pendingMap != null && review.candidates().get(index) != pendingMap) releaseUnverifiedMap();
        review.select(index);
        selectionConfirmed = true;
        updatePreviewActions();
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).selected(page * grid.capacity() + i == index, selectionConfirmed);
        }
    }

    private void changePage(int delta) {
        int next = Math.max(0, Math.min(grid.pages(review.candidates().size()) - 1, page + delta));
        if (next != page) { page = next; rebuildWidgets(); }
    }

    CreateWorldScreen creator(){
        return parent;
    }
    SearchConfig config() {
        return config;
    }

    private FtpScreen placeSearch;
    CandidateReviewScreen forPlace(FtpScreen screen) {
        placeSearch = screen;
        return this;
    }
    boolean hasPlaceSearch() {
        return placeSearch != null;
    }

    void findAnotherPlace(int x, int z) {
        releaseMaps();
        closeVerificationWorlds();
        placeSearch.editCenter(x, z);
    }

    java.util.List<com.wywf.verify.PreviewWorld.Session> sessions() {
        return review.candidates().stream()
                .map(result -> com.wywf.verify.PreviewWorld.find(result, query, config))
                .filter(java.util.Objects::nonNull).toList();
    }

    private void closeVerificationWorlds() {
        com.wywf.verify.PreviewWorld.shutdownExcept(placeSearch == null ? List.of() : placeSearch.preserved());
    }
    private void openDetails() {
        review.selected().ifPresent(result -> {
            var source = com.wywf.verify.PreviewWorld.find(result, query, config);
            if (source != null) Minecraft.getInstance().setScreenAndShow(new CandidateDetailsScreen(this, source));
            else Minecraft.getInstance().setScreenAndShow(
                    new CandidateDetailsScreen(this, result, query, review.verification(result)));
        });
    }

    private void copySeed() {
        review.selected().ifPresent(result -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(Long.toString(result.seed));
            copiedUntil = System.currentTimeMillis() + 2000;
        });
    }

    void saveVerifiedHistory() {
        if (placeSearch != null || query.error() != null || !query.ignoredWords().isEmpty()) return;
        try {
            SearchHistoryContext.store().remember(SearchHistoryContext.key(parent, query, config), review);
        } catch (Exception error) {
            WYWFClient.LOGGER.warn("Could not save candidate verification history", error);
        }
    }

    private void createSelected() {
        if (placeSearch != null) {
            review.selected().ifPresent(result -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(result.centerX + " " + result.centerZ);
                coordinatesCopiedUntil = System.currentTimeMillis() + 2000;
            });
            return;
        }
        review.selected().ifPresent(result -> {
            var saved = com.wywf.verify.PreviewWorld.find(result, query, config);
            if (saved == null) {
                var checked = review.verification(result);
                if (checked != null && checked.status() == VerificationSummary.Status.PASS) {
                    releaseMaps();
                    closeVerificationWorlds();
                    WYWFClient.worldCreator().create(result, query, config, parent, checked);
                    return;
                }
                var warning = checked != null && checked.status() == VerificationSummary.Status.FAIL
                        ? ResultTexts.label("verify_failed_create") : MapTexts.label("offline_warning");
                if (!config.serverVerification()) Minecraft.getInstance().setScreenAndShow(
                        new net.minecraft.client.gui.screens.ConfirmScreen(accepted -> {
                            if (!accepted) {
                                Minecraft.getInstance().setScreenAndShow(this);
                                return;
                            }
                            releaseMaps();
                            WYWFClient.worldCreator().create(result, query, config, parent);
                        }, MapTexts.label("offline_create"), warning));
                return;
            }
            try { saved.join();releaseMaps(); }
            catch (Exception error) {
                if (WyWFDebug.ENABLED) org.slf4j.LoggerFactory.getLogger("wywf-search").warn("Could not open selected world", error);
            }
        });
    }
    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0) { changePage(vertical > 0 ? -1 : 1); return true; }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_PAGEUP || event.key() == InputConstants.KEY_PAGEDOWN) {
            changePage(event.key() == InputConstants.KEY_PAGEUP ? -1 : 1);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        releaseMaps();
        closeVerificationWorlds();
        Minecraft.getInstance().setScreenAndShow(placeSearch == null ? parent : placeSearch);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        MenuBands.draw(g, width, height, 55, height - 86);
        var count = review.candidates().size();
        centered(g, ResultTexts.text("title_count", count), 10, 0xFFFFFFFF);
        String context = query.ignoredWords().isEmpty() ? ResultTexts.text("query", ResultTexts.normalized(query))
                : ResultTexts.text("ignored", String.join(", ", query.ignoredWords()));
        var contextLines = font.split(Component.literal(context), width - 24);
        if (!contextLines.isEmpty())
            g.centeredText(font, contextLines.getFirst(), width / 2, 23, 0xFFD5DEE3);
        if (contextLines.size() > 1)
            g.centeredText(font, contextLines.get(1), width / 2, 33, 0xFFD5DEE3);
        g.centeredText(font, MenuAccent.versionText(font.plainSubstrByWidth(reviewStatus, width - 24), 0xFFE1C790, 0xFFF5DEAA),
                width / 2, 44, 0xFFFFFFFF);
        if (review.candidates().isEmpty())
            centered(g, ResultTexts.text("empty"), 92, 0xFFD5DEE3);
        String selected = review.selected().map(result -> ResultTexts.text(
                System.currentTimeMillis() < copiedUntil ? "copied" : "selected", result.seed))
                .orElse(ResultTexts.text("empty"));
        if (placeSearch != null)
            selected = review.selected().map(result -> MapTexts.text(
                System.currentTimeMillis() < coordinatesCopiedUntil ? "ftp_coords_copied" : "ftp_coords",
                result.centerX, result.centerZ)).orElse(selected);
        centered(g, selected, height - 80, 0xFFDDEADD);
        g.centeredText(font, ResultTexts.text("page", page + 1, grid.pages(review.candidates().size())),
                69, height - 54, 0xFFFFFFFF);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private String verificationStatus() {
        int passed = 0, failed = 0, incomplete = 0, unchecked = 0;
        for (SearchResult result : review.candidates()) {
            var summary = review.verification(result);
            if (summary != null && summary.status() == VerificationSummary.Status.PASS
                    || PreviewWorld.find(result, query, config) != null) passed++;
            else if (summary == null) unchecked++;
            else if (summary.status() == VerificationSummary.Status.FAIL) failed++;
            else incomplete++;
        }
        return ResultTexts.text("verify_totals", passed, failed, incomplete, unchecked);
    }

    private void centered(GuiGraphicsExtractor g, String line, int y, int color) {
        g.centeredText(font, font.plainSubstrByWidth(line, width - 24), width / 2, y, color);
    }
}
