package com.wywf.client;

import com.wywf.core.SearchResult;
import com.wywf.core.ParsedQuery;
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
    private boolean selected;
    private float accent;
    private long lastPaint;
    private final BiomeMapCanvas map;
    private final MapImage exact = new MapImage();
    private boolean exactReady;
    private final SearchResult result;
    private final ParsedQuery query;
    private final com.wywf.core.SearchConfig config;

    CandidateCard(int x, int y, int width, int height, Font font, SearchResult result,
                  ParsedQuery query, com.wywf.core.SearchConfig config, com.wywf.map.BiomeMaps.Scope scope, BlockMenuSkin skin, Runnable onPick) {
        super(x, y, width, height, Component.empty());
        this.font = font;
        this.skin = skin;
        this.onPick = onPick;
        this.result = result;
        this.query = query;
        this.config = config;
        map = new BiomeMapCanvas(result,32,null,new com.wywf.map.StructureLayers(result),scope);
        map.bounds(x + width - 86, y + 6, 80, Math.max(24, height - 12));
        lines = new java.util.ArrayList<>(List.of(ResultTexts.text("seed", result.seed), MapTexts.text("candidate_ready"),
                ResultTexts.centerSummary(result,query,config),
                ResultTexts.text("structures", ResultTexts.names(result.matchedStructures, query)),
                ResultTexts.text("biomes", ResultTexts.names(result.matchedBiomes, query))));
        lines.addAll(ResultTexts.lootLines(result, query, config));
        updateText();
    }

    void tickMap() {
        var verified = com.wywf.verify.PreviewWorld.find(result, query, config);
        if (!exactReady && verified != null && verified.surface() != null) {
            var s = verified.surface();
            int[] colors = com.wywf.verify.SurfaceColors.raster(s, com.wywf.verify.SurfaceColors.Layer.BIOMES, false);
            exact.upload(s.size() * 2, s.size() * 2,
                    com.wywf.map.TerrainRaster.draw(colors, s.heights(), s.size(), s.size(), 2, false));
            exactReady = true;
        }
        if (!exactReady) map.tick();
        String status = verified != null && verified.matches(result, query, config)
                ? MapTexts.text("candidate_ready") : ResultTexts.text("offline");
        if (!status.equals(lines.get(1))) {
            lines.set(1, status);
            updateText();
        }
    }
    private void updateText() {
        setMessage(Component.literal(String.join(". ", lines)));
        setTooltip(Tooltip.create(Component.literal(lines.get(0) + "\n" + lines.get(1) + "\n"
                + MapTexts.text("candidate_details_hint"))));
    }

    void closeMap() {
        map.close(); exact.close();
    }

    void selected(boolean value) {
        selected = value;
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
        long now=System.nanoTime();
        float elapsed=lastPaint==0?1:Math.min(1,(now-lastPaint)/140_000_000f);
        lastPaint=now;
        float target=selected||isFocused()||isHovered
                ? 1:0;
        accent+=(target-accent)*elapsed;
        var palette=MenuAccent.colors(selected
                ? 0xFFB7E8BF:0xFFF0F5F2,selected
                ? 0xFFD8BE7F:0xFF9BCDB3);
        int first = MenuAccent.mix(0xFF4C606A, palette[0], accent);
        int last = MenuAccent.mix(0xFF4C606A, palette[1], accent);
        MenuAccent.frame(g, getX(), getY(), width, height, 2, first, last, now, result.seed, accent);
        drawEarth(g);
        g.fill(getX() + 2, getY() + 2, getX() + width - 2, getY() + height - 2, 0x55000000);
        if (exactReady) exact.draw(g, getX() + width - 86, getY() + 6, 80, Math.max(24, height - 12));
        else map.draw(g);
        for (int i = 0; i < lines.size(); i++) {
            int y = getY() + 9 + i * 14;
            if (y + font.lineHeight > getY() + height - 3)
                break;
            int color = i == 1
                    ? 0xFFE7C67C : i == 0 ? 0xFFFFFFFF : 0xFFD4DFE4;
            String line = lines.get(i);
            g.text(font, font.plainSubstrByWidth(line, width - 104), getX() + 9, y, color);
        }
    }

    private void drawEarth(GuiGraphicsExtractor g) {
        if (skin != null) {
            skin.draw(g, getX() + 2, getY() + 2, width - 4, height - 4, result.seed);
            g.fill(getX() + 2, getY() + 2, getX() + width - 2, getY() + height - 2, 0x730D100E);
            return;
        }
        var dirt = net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/dirt.png");
        var grass = net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/grass_block_side.png");
        g.enableScissor(getX() + 2, getY() + 2, getX() + width - 2, getY() + height - 2);
        for (int y = getY() + 2; y < getY() + height - 2; y += 16)
            for (int x = getX() + 2; x < getX() + width - 2; x += 16)
                g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                        dirt, x, y, 0, 0, 16, 16, 16, 16);
        if (selected) for (int x = getX() + 2; x < getX() + width - 2; x += 16)
            g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    grass, x, getY() + 2, 0, 0, 16, 16, 16, 16);
        if (selected) for (int x = getX() + 2; x < getX() + width - 2; x += 16)
            g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    net.minecraft.resources.Identifier.withDefaultNamespace("textures/block/grass_block_side_overlay.png"),
                    x, getY() + 2, 0, 0, 16, 16, 16, 16, 0xFF83B95E);
        g.disableScissor();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, Component.literal(
                (selected ? ResultTexts.text("selected_short") + ". " : "") + getMessage().getString()));
    }
}
