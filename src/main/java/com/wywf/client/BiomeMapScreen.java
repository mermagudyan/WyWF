package com.wywf.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wywf.core.SearchResult;
import com.wywf.map.BiomePalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

public final class BiomeMapScreen extends Screen {
    private final Screen parent;
    private final SearchResult result;
    private BiomeMapCanvas map;
    private final com.wywf.map.StructureLayers structures;
    private boolean temperature, dragging, borders = true, distances = true;
    private int sidebar, sampleY = 64;
    private EditBox yInput;

    public BiomeMapScreen(Screen parent, SearchResult result) {
        super(MapTexts.label("title"));
        this.parent = parent; this.result = result; structures = new com.wywf.map.StructureLayers(result);
    }

    @Override protected void init() {
        if (map == null) map = new BiomeMapCanvas(result, 512, null, structures);
        addRenderableWidget(Button.builder(MapTexts.label("structures"), b ->
                Minecraft.getInstance().setScreenAndShow(new StructureLayersScreen(this, structures)))
                .bounds(width - 114, 7, 102, 20).build());
        sidebar = width >= 620 ? 180 : 0;
        map.bounds(12, 88, width - 24 - sidebar, Math.max(24, height - 142));
        int buttonW = Math.min(130, (width - 42) / 3);
        Button biome = addRenderableWidget(Button.builder(MapTexts.label("biomes"), b -> layer(false))
                .bounds(12, 33, buttonW, 20).build());
        biome.active = temperature;
        Button heat = addRenderableWidget(Button.builder(MapTexts.label("temperature"), b -> layer(true))
                .bounds(18 + buttonW, 33, buttonW, 20).build());
        heat.active = !temperature;
        addRenderableWidget(Button.builder(MapTexts.label("reset"), b -> {
            map.view.reset(result.centerX, result.centerZ); map.dirty();
        })
                .bounds(24 + buttonW * 2, 33, buttonW, 20).build());
        yInput = new EditBox(font, 12, 57, 46, 20, MapTexts.label("height"));
        yInput.setMaxLength(4);
        yInput.setValue(Integer.toString(sampleY));
        addRenderableWidget(yInput);
        Button applyY = addRenderableWidget(Button.builder(net.minecraft.network.chat.Component.literal("Y"), b -> {
            try {
                setY(Integer.parseInt(yInput.getValue()));
            }
            catch (NumberFormatException ignored) {}
        })
                .bounds(62, 57, 26, 20).build());
        applyY.setTooltip(Tooltip.create(MapTexts.label("height")));
        yInput.setResponder(value -> {
            try {
                int y = Integer.parseInt(value);
                applyY.active = y >= -64 && y <= 320;
            }
            catch (NumberFormatException error) {
                applyY.active = false;
            }
        });
        addRenderableWidget(Button.builder(MapTexts.label("caves"), b -> setY(sampleY == 64 ? -32 : sampleY == -32 ? -50 : 64))
                .bounds(94, 57, 52, 20).build());
        int toolW = (width - 170) / 2;
        Button boundary = addRenderableWidget(Button.builder(MapTexts.label(borders ? "borders_on" : "borders_off"), b -> {
            borders = !borders; map.borders(borders); b.setMessage(MapTexts.label(borders ? "borders_on" : "borders_off"));
        })
                .bounds(152, 57, toolW, 20).build());
        boundary.setTooltip(Tooltip.create(MapTexts.label("borders_help")));
        Button distance = addRenderableWidget(Button.builder(MapTexts.label(distances ? "distances_on" : "distances_off"), b -> {
            distances = !distances; map.distances(distances); b.setMessage(MapTexts.label(distances ? "distances_on" : "distances_off"));
        })
                .bounds(158 + toolW, 57, toolW, 20).build());
        distance.setTooltip(Tooltip.create(MapTexts.label("distances_help")));
        addRenderableWidget(Button.builder(ResultTexts.label("back"), b -> onClose())
                .bounds(width - 102, height - 27, 90, 20).build());
    }

    private void setY(int value) {
        if (value < -64 || value > 320)
            return;
        sampleY = value;
        map.y(value);
        rebuildWidgets();
    }

    private void layer(boolean value) {
        temperature = value;
        map.temperature(value);
        rebuildWidgets();
    }

    @Override public void tick() {
        map.tick();
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (map.contains(x, y) && vertical != 0) {
            map.zoom(vertical, x, y); return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean keyboard) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && map.contains(event.x(), event.y())) {
            dragging = true; setFocused(null);
            return true;
        }
        return super.mouseClicked(event, keyboard);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging) {
            map.pan(dx, dy);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        dragging = false;
        return super.mouseReleased(event);
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (yInput.isFocused())
            return super.keyPressed(event);
        if (event.key() == InputConstants.KEY_PAGEUP || event.key() == InputConstants.KEY_PAGEDOWN) {
            map.zoom(event.key() == InputConstants.KEY_PAGEUP ? 1 : -1, 12 + map.view.width() / 2.0, 88 + map.view.height() / 2.0);
            return true;
        }
        if (event.isUp() || event.isDown() || event.isLeft() || event.isRight()) {
            map.pan(event.isLeft() ? 32 : event.isRight() ? -32 : 0, event.isUp() ? 32 : event.isDown() ? -32 : 0);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void removed() {
        if (map != null) {
            map.close(); map = null;
        }
    }
    @Override public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    private String hover(int mouseX, int mouseY) {
        if (!map.contains(mouseX, mouseY))
            return MapTexts.text("controls");
        String marker = map.markerHover(mouseX, mouseY);
        if (marker != null)
            return marker;
        int x = (int) Math.floor(map.view.worldX(mouseX - 12));
        int z = (int) Math.floor(map.view.worldZ(mouseY - 88));
        long distance = Math.round(Math.hypot((double) x - result.centerX, (double) z - result.centerZ));
        for (var entry : result.structurePositions.entrySet()) {
            int[] pos = entry.getValue();
            if (pos.length < 2)
                continue;
            if (Math.hypot((double) pos[0] - x, (double) pos[1] - z) <= map.view.scale() * 6)
                return MapTexts.text("target", ResultTexts.name(entry.getKey().split("#", 2)[0]), pos[0], pos[1],
                        Math.round(Math.hypot((double) pos[0] - result.centerX, (double) pos[1] - result.centerZ)));
        }
        var cell = map.hovered(mouseX, mouseY);
        if (cell != null && sampleY < 64 && !com.wywf.map.BiomeMapColors.cave(cell.id()))
            return MapTexts.text("upper_hover", x, z, ResultTexts.name(cell.surfaceId()), cell.surfaceTemperature(), distance);
        return cell == null ? "X " + x + "  Z " + z + " · " + distance + " b"
                : MapTexts.text("hover", x, z, ResultTexts.name(cell.id()), cell.temperature(), distance);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xE51B282F);
        g.centeredText(font, MapTexts.text("seed", result.seed), width / 2, 10, 0xFFFFFFFF);
        map.draw(g);
        String status = "Y=" + sampleY + " · " + map.status() + " · " + MapTexts.text("scale", map.view.scale());
        g.text(font, font.plainSubstrByWidth(status, width - 24), 12, 78, 0xFFD8E1E6);
        if (temperature && sidebar == 0) {
            int legendY = height - 65;
            for (int i = 0; i < 80; i++) g.fill(18 + i, legendY, 19 + i, legendY + 5,
                    BiomePalette.temperature(-0.5f + 2.5f * i / 79));
            g.text(font, "−0.5   T   2.0", 18, legendY - 10, 0xFFFFFFFF);
        }
        g.text(font, font.plainSubstrByWidth(hover(mouseX, mouseY), width - 24), 12, height - 47, 0xFFFFFFFF);
        if (sidebar > 0)
            legend(g);
        g.text(font, font.plainSubstrByWidth(MapTexts.text(sampleY < 64 ? "caves_short" : "source_short"), width - 120),
                12, height - 22, 0xFFBBCBD3);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void legend(GuiGraphicsExtractor g) {
        int x = width - sidebar, row = 105;
        g.enableScissor(x, 88, width, height - 54);
        g.text(font, MapTexts.label(temperature ? "legend" : "biomes"), x, 90, 0xFFFFFFFF);
        if (temperature) {
            for (float value : new float[]{-0.5f, 0, 0.5f, 1, 1.5f, 2}) {
                g.fill(x, row, x + 12, row + 9, BiomePalette.temperature(value));
                g.text(font, String.format(java.util.Locale.ROOT, "T %.1f", value), x + 18, row, 0xFFE0E7E8);
                row += 13;
            }
        }
        else {
            for (var entry : map.legend()) {
                g.fill(x, row, x + 12, row + 9, entry.color());
                g.text(font, font.plainSubstrByWidth(entry.name(), sidebar - 30), x + 18, row, 0xFFE0E7E8);
                row += 13;
            }
        }
        for (var line : font.split(MapTexts.label(sampleY < 64 ? "caves_source" : "source"), sidebar - 18)) {
            g.text(font, line, x, row + 5, 0xFFB9CBD1); row += 11;
        }
        if (row + 30 < height - 54) {
            g.fill(x, row + 12, x + 7, row + 19, 0xFFFFD179);
            g.text(font, MapTexts.label("structure"), x + 12, row + 12, 0xFFE0E7E8);
            g.text(font, MapTexts.label("center"), x, row + 25, 0xFFE0E7E8);
        }
        g.disableScissor();
    }
}
