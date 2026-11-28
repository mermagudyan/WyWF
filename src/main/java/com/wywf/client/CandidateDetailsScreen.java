package com.wywf.client;

import dev.diylib.client.ui.DiyVanillaButton;
import com.wywf.core.SearchResult;
import com.wywf.core.ParsedQuery;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.ArrayList;
import java.util.List;

final class CandidateDetailsScreen extends Screen {
    private final Screen parent;
    private final List<String> extra;
    private final boolean saved, raw;
    private final List<com.wywf.verify.VerifiedAnchor> anchors;
    private final SearchResult result;
    private final ParsedQuery query;
    private List<FormattedCharSequence> lines = List.of();
    private int offset;
    private int visibleRows;
    private long copiedUntil;

    CandidateDetailsScreen(CandidateReviewScreen parent, SearchResult result, ParsedQuery query,
                           com.wywf.verify.VerificationSummary verification) {
        this(parent, result, query, uncheckedEvidence(result, query, parent, verification), false,
                verification == null, List.of());
    }

    private static List<String> uncheckedEvidence(SearchResult result, ParsedQuery query, CandidateReviewScreen parent,
                                                 com.wywf.verify.VerificationSummary verification) {
        var lines = new ArrayList<>(ResultTexts.lootLines(result, query, parent.config()));
        if (verification != null) {
            lines.add(CandidateVerificationScreen.statusText(verification));
            lines.add(verification.note());
            lines.addAll(verification.evidence());
        }
        return lines;
    }
    CandidateDetailsScreen(Screen parent, com.wywf.verify.WorldMapSource source) {
        this(parent, source.result(), source.query(), evidence(source), source.saved(), false, source.anchors());
    }
    private CandidateDetailsScreen(Screen parent, SearchResult result, ParsedQuery query, List<String> extra,
                                   boolean saved, boolean raw, List<com.wywf.verify.VerifiedAnchor> anchors) {
        super(ResultTexts.label("details_title"));
        this.parent = parent;
        this.result = result;
        this.query = query;
        this.extra = extra;
        this.saved = saved;
        this.raw = raw;
        this.anchors=List.copyOf(anchors);
    }
    private static List<String> evidence(com.wywf.verify.WorldMapSource source) {
        List<String> lines = new ArrayList<>(ResultTexts.lootLines(source.loot()));
        for (var a : source.anchors()) lines.add(MapTexts.text(
                "dimension_" + com.wywf.core.DimensionSpace.of(a.id()).name().toLowerCase(java.util.Locale.ROOT)) + " · " + MapTexts.text("saved_anchor",
                ResultTexts.name(a.id()),
                a.minX(), a.minZ(), a.maxX(), a.maxZ(), Math.round(a.distance(com.wywf.core.DimensionSpace.of(a.id()).coordinate(source.result().centerX),
                        com.wywf.core.DimensionSpace.of(a.id()).coordinate(source.result().centerZ)))));
        return List.copyOf(lines);
    }

    @Override
    protected void init() {
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        if (saved)
            wrapped.addAll(font.split(MapTexts.label("saved_details"), width - 32));
        else if (raw)
            wrapped.addAll(font.split(ResultTexts.label("offline_help"), width - 32));
        List<String> description = query.isEmpty() ? List.of(MapTexts.text("saved_legacy"), ResultTexts.centerSummary(result))
                : ResultTexts.details(result,query,anchors);
        for (String line : description)
            wrapped.addAll(font.split(Component.literal(line), width - 32));
        for (String line : extra)
            wrapped.addAll(font.split(Component.literal(line), width - 32));
        lines = List.copyOf(wrapped);
        visibleRows = Math.max(1, (height - 48 - 86) / 12);
        offset = Math.max(0, Math.min(offset, Math.max(0, lines.size() - visibleRows)));
        Button up = addRenderableWidget(DiyVanillaButton.builder(Component.literal("<"), b -> scroll(-visibleRows))
                .bounds(12, height - 60, 30, 20).build());
        up.active = offset > 0;
        Button down = addRenderableWidget(DiyVanillaButton.builder(Component.literal(">"), b -> scroll(visibleRows))
                .bounds(width - 42, height - 60, 30, 20).build());
        down.active = offset + visibleRows < lines.size();
        int actionWidth = (width - 36) / 3;
        addRenderableWidget(DiyVanillaButton.builder(ResultTexts.label("copy_seed"), b -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(Long.toString(result.seed));
            copiedUntil = System.currentTimeMillis() + 2000;
        }).bounds(12, height - 30, actionWidth, 20).build());
        addRenderableWidget(DiyVanillaButton.builder(ResultTexts.label("copy_center"), b -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(result.centerX + " " + result.centerZ);
            copiedUntil = System.currentTimeMillis() + 2000;
        }).bounds(18 + actionWidth, height - 30, actionWidth, 20).build());
        addRenderableWidget(DiyVanillaButton.builder(ResultTexts.label("back"), b -> onClose())
                .bounds(24 + actionWidth * 2, height - 30, actionWidth, 20).build());
    }

    private void scroll(int amount) {
        int next = Math.max(0, Math.min(Math.max(0, lines.size() - visibleRows), offset + amount));
        if (next != offset) {
            offset = next;
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical != 0) {
            scroll(vertical > 0 ? -3 : 3);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_PAGEUP || event.key() == InputConstants.KEY_PAGEDOWN) {
            scroll(event.key() == InputConstants.KEY_PAGEUP ? -visibleRows : visibleRows);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);

    }

    @Override
    public Component getNarrationMessage() {
        return Component.literal(title.getString() + ". " + result.seed + ". " + (query.isEmpty()
                ? MapTexts.text("saved_legacy") : String.join(". ", ResultTexts.details(result,query,anchors))));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        MenuBands.draw(g, width, height, 43, height - 86);
        g.centeredText(font, title, width / 2, 10, 0xFFFFFFFF);
        g.centeredText(font, ResultTexts.label("seed", result.seed), width / 2, 28, 0xFFDDEADD);
        for (int i = 0; i < visibleRows && offset + i < lines.size(); i++) {
            g.text(font, lines.get(offset + i), 16, 48 + i * 12, 0xFFD5DEE3);
        }
        g.centeredText(font, System.currentTimeMillis() < copiedUntil ? ResultTexts.text("copied_short")
                : ResultTexts.text("rows", offset + 1, Math.min(offset + visibleRows, lines.size()), lines.size()),
                width / 2, height - 54, 0xFFFFFFFF);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
