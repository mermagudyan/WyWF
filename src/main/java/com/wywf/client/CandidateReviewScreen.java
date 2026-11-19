package com.wywf.client;

import com.wywf.WYWFClient;
import com.wywf.core.*;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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
    private final int rareCard;
    private final BlockMenuSkin rareSkin;
    private final List<CandidateCard> cards = new ArrayList<>();
    private Button chunksView, detailsAction, createAction;
    private CandidateGrid grid;
    private int page = -1;
    private long copiedUntil, coordinatesCopiedUntil;
    private boolean creating;
    private final com.wywf.map.BiomeMaps.Scope mapCache=com.wywf.map.BiomeMaps.scope();
    private final java.util.Map<String,SurfaceMapScreen> mapScreens=new java.util.HashMap<>();
    com.wywf.map.BiomeMaps.Scope mapCache(){
        return mapCache;
    }
    private void releaseMaps(){
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
        var random = java.util.concurrent.ThreadLocalRandom.current();
        int count = review.candidates().size();
        rareCard = count > 0 && random.nextInt(100) < 8 ? random.nextInt(count) : -1;
        rareSkin = rareCard < 0 ? null : switch (random.nextInt(3)) {
            case 0 -> BlockMenuSkin.STONE;
            case 1 -> BlockMenuSkin.DEEPSLATE;
            default -> BlockMenuSkin.NETHERRACK;
        };
    }

    private boolean selectedPreviewAvailable() {
        return review.selected().map(result ->
                com.wywf.verify.PreviewWorld.find(result, query, config) != null).orElse(false);
    }

    @Override
    protected void init() {
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
            CandidateCard card = new CandidateCard(grid.x(slot), grid.y(slot), grid.cardWidth(), grid.cardHeight(),
                    font, review.candidates().get(index), query, config, mapCache, index == rareCard
                    ? rareSkin : null, () -> select(picked));
            card.selected(index == review.selectedIndex());
            cards.add(addRenderableWidget(card));
        }
        Button previous = addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(12, height - 60, 26, 20).build());
        previous.active = page > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(100, height - 60, 26, 20).build());
        next.active = page + 1 < grid.pages(review.candidates().size());
        int remaining = width - 156;
        int mapWidth = Math.min(100, (remaining - 12) / 3);
        chunksView = addRenderableWidget(Button.builder(MapTexts.label(review.candidates().isEmpty() ? "simplify" : "open"), b ->
                {
                    if (review.candidates().isEmpty())
                        Minecraft.getInstance().setScreenAndShow(
                                new SearchAdviceScreen(parent, queryText, query, config).forPlace(placeSearch));
                    else review.selected().ifPresent(result -> {
                        var saved = com.wywf.verify.PreviewWorld.find(result, query, config);
                        if (saved != null)
                            Minecraft.getInstance().setScreenAndShow(
                                    mapScreens.computeIfAbsent(result.seed+":"+result.centerX+":"+result.centerZ,key->
                                            new SurfaceMapScreen(this,saved)));
                        else if (!config.serverVerification()) Minecraft.getInstance().setScreenAndShow(new BiomeMapScreen(this, result));
                    });
                })
                .bounds(138, height - 60, mapWidth, 20).build());
        addRenderableWidget(Button.builder(ResultTexts.label("more"), b -> {
            releaseMaps();
            closeVerificationWorlds();
            Minecraft.getInstance().setScreenAndShow(new SearchScreen(parent, queryText, query, config.extendedLimits()).fresh().forPlace(placeSearch));
        })
                .bounds(144 + mapWidth, height - 60, remaining - mapWidth - 6, 20).build());
        int actionWidth = (width - 42) / 4;
        detailsAction = addRenderableWidget(Button.builder(ResultTexts.label("details"), b -> openDetails())
                .bounds(12, height - 30, actionWidth, 20).build());
        Button copy = addRenderableWidget(Button.builder(ResultTexts.label("copy_seed"), b -> copySeed())
                .bounds(18 + actionWidth, height - 30, actionWidth, 20).build());
        createAction = addRenderableWidget(Button.builder(placeSearch == null
                        ? ResultTexts.label("create")
                        : MapTexts.label("ftp_copy_coords"), b -> createSelected())
                .bounds(24 + actionWidth * 2, height - 30, actionWidth, 20).build());
        copy.active = !review.candidates().isEmpty() && !creating;
        updatePreviewActions();
        addRenderableWidget(Button.builder(ResultTexts.label("back"), b -> onClose())
                .bounds(30 + actionWidth * 3, height - 30, actionWidth, 20).build());
    }

    private void updatePreviewActions() {
        boolean selected = !review.candidates().isEmpty() && !creating;
        boolean available = selected && selectedPreviewAvailable();
        var unavailable = selected && !available ? Tooltip.create(MapTexts.label("candidate_preview_missing"))
                : null;
        boolean raw = selected && !config.serverVerification();
        chunksView.active = review.candidates().isEmpty() || available || raw;
        chunksView.setTooltip(available ? Tooltip.create(MapTexts.label("preview_open_help"))
                : raw ? Tooltip.create(MapTexts.label("raw_map_help")) : unavailable);
        detailsAction.active = available || raw;
        detailsAction.setTooltip(raw ? Tooltip.create(ResultTexts.label("offline_help")) : unavailable);
        createAction.active = selected && (placeSearch != null || available || raw);
        if (placeSearch == null) createAction.setTooltip(available ? Tooltip.create(MapTexts.label("create_ready_help"))
                : raw ? Tooltip.create(ResultTexts.label("offline_help")) : unavailable);
    }

    @Override
    protected void setInitialFocus() {
        int slot = review.selectedIndex() - page * grid.capacity();
        if (slot >= 0 && slot < cards.size()) setInitialFocus(cards.get(slot));
        else super.setInitialFocus();
    }

    @Override public void tick() {
        cards.forEach(CandidateCard::tickMap);
    }
    @Override public void removed() {
        cards.forEach(CandidateCard::closeMap);
    }

    private void select(int index) {
        review.select(index);
        updatePreviewActions();
        for (int i = 0; i < cards.size(); i++) {
            cards.get(i).selected(page * grid.capacity() + i == index);
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
        placeSearch = screen; return this;
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
            else if (!config.serverVerification()) Minecraft.getInstance().setScreenAndShow(new CandidateDetailsScreen(this, result, query));
        });
    }

    private void copySeed() {
        review.selected().ifPresent(result -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(Long.toString(result.seed));
            copiedUntil = System.currentTimeMillis() + 2000;
        });
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
                if (!config.serverVerification()) Minecraft.getInstance().setScreenAndShow(
                        new net.minecraft.client.gui.screens.ConfirmScreen(accepted -> {
                            if (!accepted) {
                                Minecraft.getInstance().setScreenAndShow(this);
                                return;
                            }
                            releaseMaps();
                            WYWFClient.worldCreator().create(result, queryText, parent);
                        }, MapTexts.label("offline_create"), MapTexts.label("offline_warning")));
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
        centered(g, config.serverVerification() ? ResultTexts.text("title_count", review.candidates().size())
                : MapTexts.text("raw_results", review.candidates().size()), 10, 0xFFFFFFFF);
        String context = query.ignoredWords().isEmpty() ? ResultTexts.text("query", ResultTexts.normalized(query))
                : ResultTexts.text("ignored", String.join(", ", query.ignoredWords()));
        var contextLines = font.split(Component.literal(context), width - 24);
        if (!contextLines.isEmpty())
            g.centeredText(font, contextLines.getFirst(), width / 2, 23, 0xFFD5DEE3);
        if (contextLines.size() > 1)
            g.centeredText(font, contextLines.get(1), width / 2, 33, 0xFFD5DEE3);
        String status = stopReason == null || stopReason.isBlank() ? ResultTexts.text("complete")
                : stopReason;
        g.centeredText(font, MenuAccent.text(font.plainSubstrByWidth(status, width - 24), 0xFFE1C790, 0xFFF5DEAA),
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

    private void centered(GuiGraphicsExtractor g, String line, int y, int color) {
        g.centeredText(font, font.plainSubstrByWidth(line, width - 24), width / 2, y, color);
    }
}
