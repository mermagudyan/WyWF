package com.wywf.client;

import com.wywf.core.MenuColors;
import com.wywf.core.SearchResult;
import com.wywf.core.ParsedQuery;
import com.wywf.verify.PreviewWorld;
import com.wywf.verify.VerificationSummary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.List;

final class CandidateCard extends AbstractWidget {
    private final Font font;
    private final Runnable onPick;
    private final BlockMenuSkin skin;
    private final List<String> lines;
    private final VerificationSummary verification;
    private final String[] visibleLines;
    private static final int FRAME_BASE = 0xFF606060;
    private static final int HOVER_LIGHT = 0xFFF8F8F8;
    private static final int HOVER_SHADOW = 0xFFD0D0D0;
    private final MenuColors.ReadyFramePalette readyPalette = MenuAccent.readyFramePalette();
    private final int[] selectedPalette = MenuAccent.selectionColors();
    private boolean selected;
    private boolean highlightSelection;
    private boolean ready;
    private float hoverAccent;
    private long lastPaint;
    private final BiomeMapCanvas map;
    private final MapImage exact = new MapImage();
    private boolean exactReady;
    private final SearchResult result;
    private final ParsedQuery query;
    private final com.wywf.core.SearchConfig config;

    CandidateCard(int x, int y, int width, int height, Font font, SearchResult result,
                  ParsedQuery query, com.wywf.core.SearchConfig config, com.wywf.map.BiomeMaps.Scope scope, BlockMenuSkin skin,
                  VerificationSummary verification, Runnable onPick) {
        super(x, y, width, height, Component.empty());
        this.font = font;
        this.skin = skin;
        this.onPick = onPick;
        this.result = result;
        this.query = query;
        this.config = config;
        this.verification = verification;
        map = new BiomeMapCanvas(result,32,null,new com.wywf.map.StructureLayers(result),scope);
        map.bounds(x + width - 86, y + 6, 80, Math.max(24, height - 12));
        ready = PreviewWorld.find(result, query, config) != null;
        lines = new java.util.ArrayList<>(List.of(ResultTexts.text("seed", result.seed), statusText(),
                ResultTexts.centerSummary(result,query,config),
                ResultTexts.text("structures", ResultTexts.names(result.matchedStructures, query)),
                ResultTexts.text("biomes", ResultTexts.names(result.matchedBiomes, query))));
        lines.addAll(ResultTexts.lootLines(result, query, config));
        visibleLines = new String[lines.size()];
        updateText();
    }

    void tickMap() {
        var verified = PreviewWorld.find(result, query, config);
        if (!exactReady && verified != null && verified.surface() != null) {
            var s = verified.surface();
            int[] colors = com.wywf.verify.SurfaceColors.raster(s, com.wywf.verify.SurfaceColors.Layer.BIOMES, false);
            exact.upload(s.size() * 2, s.size() * 2,
                    com.wywf.map.TerrainRaster.draw(colors, s.heights(), s.size(), s.size(), 2, false));
            exactReady = true;
        }
        if (!exactReady) map.tick();
        ready = verified != null;
        String status = statusText();
        if (!status.equals(lines.get(1))) {
            lines.set(1, status);
            updateText();
        }
    }
    private String statusText() {
        return ready ? MapTexts.text("candidate_ready") : CandidateVerificationScreen.statusText(verification);
    }

    private void updateText() {
        for (int i = 0; i < lines.size(); i++)
            visibleLines[i] = font.plainSubstrByWidth(lines.get(i), Math.max(0, width - 104));
        setMessage(Component.literal(String.join(". ", lines)));
        setTooltip(Tooltip.create(Component.literal(lines.get(0) + "\n" + lines.get(1) + "\n"
                + MapTexts.text("candidate_details_hint"))));
    }

    void closeMap() {
        map.close();
        exact.close();
    }

    void selected(boolean value, boolean showSelection) {
        selected = value;
        highlightSelection = value && showSelection;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        onPick.run();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (active && event.isSelection()) {
            onPick.run();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float elapsed = lastPaint == 0 ? 1 : Math.min(1, (now - lastPaint) / 140_000_000f);
        lastPaint = now;
        boolean hovered = isHovered || isFocused() && Minecraft.getInstance().getLastInputType().isKeyboard();
        float target = hovered ? 1 : 0;
        hoverAccent += (target - hoverAccent) * elapsed;
        drawFrame(g, now);
        drawBackground(g);
        g.fill(getX() + 2, getY() + 2, getX() + width - 2, getY() + height - 2, 0x55000000);
        if (exactReady) exact.draw(g, getX() + width - 86, getY() + 6, 80, Math.max(24, height - 12));
        else map.draw(g);
        for (int i = 0; i < lines.size(); i++) {
            int y = getY() + 9 + i * 14;
            if (y + font.lineHeight > getY() + height - 3)
                break;
            int color = i == 1
                    ? 0xFFE7C67C : i == 0 ? 0xFFFFFFFF : 0xFFD4DFE4;
            g.text(font, visibleLines[i], getX() + 9, y, color);
        }
    }

    private void drawFrame(GuiGraphicsExtractor g, long now) {
        int x = getX(), y = getY();
        if (width < 4 || height < 4) return;
        int topLeft, topRight, bottomLeft, bottomRight;
        int transition = 0;
        boolean showReadyGradient = ready && highlightSelection;
        if (showReadyGradient) {
            long millis = now / 1_000_000L;
            transition = Math.round(width * MenuColors.readyFrameCenter(millis));
            topLeft = readyPalette.shade(false, millis, 0);
            topRight = readyPalette.shade(true, millis, .25f);
            bottomRight = readyPalette.shade(true, millis, .5f);
            bottomLeft = readyPalette.shade(false, millis, .75f);
            int leftMiddle = readyPalette.shade(false, millis, .875f);
            int rightMiddle = readyPalette.shade(true, millis, .375f);
            int middle = y + height / 2;
            if (height > 4) {
                g.fillGradient(x, y + 2, x + 2, middle, topLeft, leftMiddle);
                g.fillGradient(x, middle, x + 2, y + height - 2, leftMiddle, bottomLeft);
                g.fillGradient(x + width - 2, y + 2, x + width, middle, topRight, rightMiddle);
                g.fillGradient(x + width - 2, middle, x + width, y + height - 2, rightMiddle, bottomRight);
            }
        } else {
            int first = highlightSelection ? selectedPalette[0] : HOVER_LIGHT;
            int last = highlightSelection ? selectedPalette[1] : HOVER_SHADOW;
            float strength = highlightSelection ? 1 : hoverAccent;
            float wave = (float) Math.sin(now / 900_000_000d + (result.seed & 255) / 64d) * .2f + .5f;
            topLeft = MenuAccent.mix(FRAME_BASE, MenuAccent.mix(first, last, wave), strength);
            topRight = MenuAccent.mix(FRAME_BASE, MenuAccent.mix(last, first, wave), strength);
            bottomLeft = topRight;
            bottomRight = topLeft;
            if (height > 4) {
                g.fillGradient(x, y + 2, x + 2, y + height - 2, topLeft, bottomLeft);
                g.fillGradient(x + width - 2, y + 2, x + width, y + height - 2, topRight, bottomRight);
            }
        }

        // use the same colors at each corner so the frame has no seams
        var pose = g.pose();
        pose.pushMatrix().translate(x, y).rotate((float) (-Math.PI / 2));
        try {
            if (showReadyGradient) {
                drawFrameEdge(g, -2, 0, transition, topLeft, topRight);
                drawFrameEdge(g, -height, -height + 2, transition, bottomLeft, bottomRight);
            } else {
                g.fillGradient(-2, 0, 0, width, topLeft, topRight);
                g.fillGradient(-height, 0, -height + 2, width, bottomLeft, bottomRight);
            }
        } finally {
            pose.popMatrix();
        }
    }

    private void drawFrameEdge(GuiGraphicsExtractor g, int from, int to, int transition, int first, int last) {
        int center = MenuAccent.mix(first, last, .5f);
        g.fillGradient(from, 0, to, transition, first, center);
        g.fillGradient(from, transition, to, width, center, last);
    }

    private void drawBackground(GuiGraphicsExtractor g) {
        int x = getX() + 2, y = getY() + 2;
        if (skin == BlockMenuSkin.DIRT) BlockMenuSkin.drawEarth(g, x, y, width - 4, height - 4, selected);
        else {
            skin.draw(g, x, y, width - 4, height - 4, result.seed);
            g.fill(x, y, getRight() - 2, getBottom() - 2, 0x550D100E);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(
                (selected ? ResultTexts.text("selected_short") + ". " : "") + getMessage().getString()));
    }
}
