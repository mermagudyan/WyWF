package com.wywf.client;

import dev.diylib.client.ui.DiyVanillaButton;
import com.wywf.core.*;
import com.wywf.verify.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import java.util.ArrayList;
import java.util.List;

public final class VerificationScreen extends Screen {
    private final CandidateReviewScreen parent;
    private final CreateWorldScreen creator;
    private final SearchResult result;
    private final ParsedQuery query;
    private final SearchConfig config;
    private PreviewWorld.Session session;
    private boolean launched, waitingPrevious, offline;
    private String error;
    private int scroll;
    private Button map, enter, fallback, cancel;
    private List<String> lines = List.of();
    private VerificationSummary shown;

    public VerificationScreen(CandidateReviewScreen parent, CreateWorldScreen creator, SearchResult result,
                              ParsedQuery query, SearchConfig config) {
        super(MapTexts.label("verify_title"));
        this.parent = parent;
        this.creator = creator;
        this.result = result;
        this.query = query;
        this.config = config.copy();
        var current = PreviewWorld.active();
        if (current != null && current.matches(result, query, config)) {
            session = current;
            launched = true;
        } else if (current != null) { current.cancel(); waitingPrevious = true; }
    }

    @Override protected void init() {
        int w = (width - 42) / 4;
        map = addRenderableWidget(DiyVanillaButton.builder(MapTexts.label("surface"), b ->
                Minecraft.getInstance().setScreenAndShow(new SurfaceMapScreen(this, session)))
                .bounds(12, height - 30, w, 20).build());
        enter = addRenderableWidget(DiyVanillaButton.builder(ResultTexts.label("create"), b -> {
            try { session.join(); } catch (Exception failure) { error = failure.toString(); }
        }).bounds(18 + w, height - 30, w, 20).build());
        fallback = addRenderableWidget(DiyVanillaButton.builder(MapTexts.label("offline_create"), b -> {
            Minecraft.getInstance().setScreenAndShow(new net.minecraft.client.gui.screens.ConfirmScreen(accepted -> {
                Minecraft.getInstance().setScreenAndShow(this);
                if (accepted) { offline = true; if (session != null) session.cancel(); }
            }, MapTexts.label("offline_create"), MapTexts.label("offline_warning")));
        }).bounds(24 + w * 2, height - 30, w, 20).build());
        cancel = addRenderableWidget(DiyVanillaButton.builder(MapTexts.label("discard"), b -> {
            if (session != null) session.cancel(); Minecraft.getInstance().setScreenAndShow(parent);
        }).bounds(30 + w * 3, height - 30, w, 20).build());
        addRenderableWidget(DiyVanillaButton.builder(ResultTexts.label("back"), b -> onClose())
                .bounds(width - 102, 7, 90, 20).build());
        updateButtons();
    }

    private void updateButtons() {
        var status = session == null ? VerificationSummary.Status.BOOTING : session.summary().status();
        map.active = session != null && session.surface() != null;
        enter.active = status == VerificationSummary.Status.PASS && !offline;
        fallback.active = error != null || status == VerificationSummary.Status.ERROR || status == VerificationSummary.Status.PARTIAL;
        cancel.active = !offline;
    }

    @Override public void tick() {
        if (offline) {
            if (PreviewWorld.active() == null) {
                offline = false;
                com.wywf.WYWFClient.worldCreator().create(result, query.raw(), creator);
            }
            return;
        }
        if (!launched && (!waitingPrevious || PreviewWorld.active() == null)) {
            launched = true;
            try { session = PreviewWorld.begin(creator, result, query, config, this); }
            catch (Exception failure) { error = failure.toString(); }
        }
        if (session == null && PreviewWorld.active() != null && PreviewWorld.active().result.seed == result.seed)
            session = PreviewWorld.active();
        if (session != null && shown != session.summary()) {
            shown = session.summary();
            List<String> all = new ArrayList<>();
            all.add(MapTexts.text("verify_status", shown.status()));
            all.add(shown.note());
            all.addAll(shown.evidence());
            List<String> wrapped = new ArrayList<>();
            for (String line : all) {
                if (line.isEmpty()) wrapped.add("");
                else wrapped.addAll(font.getSplitter().splitLines(line, Math.max(20, width - 32),
                        net.minecraft.network.chat.Style.EMPTY).stream().map(net.minecraft.network.chat.FormattedText::getString)
                        .toList());
            }
            lines = List.copyOf(wrapped);
        }
        updateButtons();
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        scroll = Math.max(0, Math.min(Math.max(0, lines.size() - Math.max(1, (height - 100) / 12)), scroll - (int) vertical * 3));
        return true;
    }
    @Override public void onClose() { Minecraft.getInstance().setScreenAndShow(parent); }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
        g.fill(0, 0, width, height, 0xEE152027);
        g.text(font, title, 12, 12, 0xFFFFFFFF);
        g.text(font, MapTexts.label("verify_seed", result.seed), 12, 36, 0xFFDFE8DC);
        if (session == null || !session.summary().terminal())
            g.text(font, MapTexts.label("verify_working"), 12, 53, 0xFFE5CD94);
        else {
            int rows = Math.max(1, (height - 100) / 12);
            for (int i = 0; i < rows && scroll + i < lines.size(); i++)
                g.text(font, lines.get(scroll + i), 12, 57 + i * 12, 0xFFD7E3E8);
        }
        if (error != null) g.text(font, font.plainSubstrByWidth(error, width - 24), 12, 75, 0xFFFFC0AA);
        super.extractRenderState(g, mx, my, tick);
    }
}
