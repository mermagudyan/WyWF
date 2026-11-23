package com.wywf.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wywf.WYWFClient;
import com.wywf.core.ConfigStore;
import com.wywf.core.DimensionSpace;
import com.wywf.core.SearchResult;
import com.wywf.map.*;
import com.wywf.verify.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.util.*;
import java.util.concurrent.CompletableFuture;

final class LiveMapScreen extends Screen {
    private enum View { GENERAL, BIOMES, SURFACE, TERRAIN }
    private static final int INSET = 12;
    private static final SurfaceColors.Layer[] TERRAIN = {SurfaceColors.Layer.WATER, SurfaceColors.Layer.TEMPERATURE,
            SurfaceColors.Layer.SLOPE, SurfaceColors.Layer.CLIMATE, SurfaceColors.Layer.MATCH};
    private final Screen parent;
    private final PlayerAtlas atlas;
    private final MapViewport viewport;
    private final int playerX, playerZ;
    private final boolean full;
    private final DimensionSpace dimension;
    private final BiomeMapCanvas overview;
    private final StructureLayers structures;
    private final FitPreview fit = new FitPreview();
    private final MapImage coverage = new MapImage();
    private final Map<Long, MapImage> images = new LinkedHashMap<>();
    private final CompletableFuture<Optional<SavedWorldMap>> saved;
    private CompletableFuture<Optional<SavedWorldMaps.Discoveries>> savedDiscoveries;
    private SavedWorldMap metadata;
    private FitPreview.Result fitted;
    private Map<Long, SurfaceSnapshot> visible = Map.of();
    private TabNavigationBar navigation;
    private View view = View.GENERAL;
    private int terrain, x, y, mapWidth, mapHeight, footer, ticks;
    private long revision = -1;
    private boolean drag, borders;
    private int gatewayScan, gatewayScanX = Integer.MIN_VALUE, gatewayScanZ = Integer.MIN_VALUE, gatewayScanRadius;

    private record MapTab(View view, Component getTabTitle) implements Tab {
        @Override public Component getTabExtraNarration() {
            return Component.empty();
        }
        public Layout getLayout() {
            return new FrameLayout();
        }
        @Override public void visitChildren(java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> visitor) {}
        @Override public void doLayout(net.minecraft.client.gui.navigation.ScreenRectangle area) {}
    }

    static void open(Screen parent) {
        var client = Minecraft.getInstance();
        var atlas = PlayerAtlas.current(client);
        if (atlas == null)
            return;
        PlayerAtlas.tick(client);
        client.setScreenAndShow(new LiveMapScreen(parent, client, atlas));
    }

    private LiveMapScreen(Screen parent, Minecraft client, PlayerAtlas atlas) {
        super(MapTexts.label("live_map"));
        this.parent = parent;
        this.atlas = atlas;
        playerX = client.player.getBlockX();
        playerZ = client.player.getBlockZ();
        var id = client.level.dimension().identifier().toString();
        dimension = Arrays.stream(DimensionSpace.values()).filter(d -> d.id.equals(id)).findFirst().orElse(null);
        var server = client.getSingleplayerServer();
        full = server != null && dimension != null;
        viewport = new MapViewport(playerX + .5, playerZ + .5, full ? 2 : 0.5);
        if (full) {
            long seed = server.overworld().getSeed();
            var source = new SearchResult(seed, playerX, playerZ, playerX, playerZ, "", List.of(), List.of(), "");
            structures = new StructureLayers(source);
            var directory = server.getWorldPath(LevelResource.ROOT);
            structures.worldPreferences(directory);
            var level = server.getLevel(client.level.dimension());
            if (level != null) structures.confirmation(markers -> com.wywf.verify.MapDiscovery.confirmLive(server, level, markers));
            overview = new BiomeMapCanvas(source, 512, viewport, structures);
            overview.settings(ConfigStore.load());
            overview.dimension(dimension);
            savedDiscoveries = CompletableFuture.supplyAsync(() -> {
                try {
                    return SavedWorldMaps.readDiscoveries(directory, seed, net.minecraft.SharedConstants.getCurrentVersion().id());
                } catch (java.io.IOException error) {
                    WYWFClient.LOGGER.warn("Could not read saved map discoveries", error);
                    return Optional.empty();
                }
            });
            saved = CompletableFuture.supplyAsync(() -> {
                try {
                    return SavedWorldMaps.read(directory, WYWFClient.parser(), net.minecraft.SharedConstants.getCurrentVersion().id());
                }
                catch (Exception error) {
                    return Optional.empty();
                }
            });
        } else {
            structures = null;
            overview = null;
            saved = CompletableFuture.completedFuture(Optional.empty());
        }
    }

    @Override protected void init() {
        var manager = new TabManager(this::addRenderableWidget, this::removeWidget, selected -> {
            var next = ((MapTab)selected).view();
            if (next == view) return;
            view = next;
            rebuildWidgets();
        }, selected -> {});
        var tabs = new ArrayList<MapTab>(List.of(
                new MapTab(View.GENERAL, MapTexts.label("live_general")),
                new MapTab(View.BIOMES, MapTexts.label("layer_biomes")),
                new MapTab(View.SURFACE, MapTexts.label("layer_surface"))));
        if (dimension != DimensionSpace.NETHER && dimension != DimensionSpace.END)
            tabs.add(new MapTab(View.TERRAIN, MapTexts.label("live_terrain")));
        navigation = addRenderableWidget(ClientPlatform.navigation(manager, width, tabs.toArray(MapTab[]::new)));
        navigation.selectTab(view.ordinal(), false);
        y = navigation.getRectangle().height() + 5;
        footer = height - 38;
        x = INSET;
        mapWidth = Math.max(1, width - x - INSET);
        mapHeight = Math.max(1, footer - y - (view == View.GENERAL ? 43 : 17));
        viewport.resize(mapWidth, mapHeight);
        if (overview != null) {
            overview.bounds(x, y, mapWidth, mapHeight);
            overview.resume();
            overview.dirty();
        }
        if (view == View.GENERAL) initGeneral();
        if (view == View.TERRAIN) {
            int w = Math.min(142, width / 3);
            addRenderableWidget(MenuButton.builder(layerName(), b -> {
                terrain = (terrain + 1) % terrainCount();
                b.setMessage(layerName());
                invalidate();
            }).bounds(INSET, footer + 9, w, 20).build());
        }
        int doneWidth = Math.min(150, width / 3);
        addRenderableWidget(MenuButton.builder(
                net.minecraft.network.chat.CommonComponents.GUI_DONE, b -> onClose())
                .bounds((width - doneWidth) / 2, footer + 9, doneWidth, 20).build());
        invalidate();
    }

    private MapContextMenu context;

    private void initGeneral() {
        int row = footer - 23, count = !full ? 2 : metadata == null ? 3 : 4;
        int w = Math.max(1, Math.min(106, (width - 32) / count));
        int center = (width - w) / 2, step = w + 4;
        addRenderableWidget(MenuButton.builder(borderLabel(), b -> {
            borders = !borders;
            b.setMessage(borderLabel());
            if (overview != null) overview.borders(borders);
            invalidate();
        }).bounds(center - (count - 1) * step / 2, row, w, 20).build());
        addRenderableWidget(MenuButton.builder(MapTexts.label("reset"), b -> {
            viewport.reset(playerX + .5, playerZ + .5, full ? 2 : .5);
            if (overview != null) overview.dirty();
        }).bounds(center - (count - 3) * step / 2, row, w, 20).build());
        if (!full) return;
        addRenderableWidget(MenuButton.builder(MapTexts.label("structures_short"), b ->
                minecraft.setScreenAndShow(new StructureLayersScreen(this, structures)))
                .bounds(center + (5 - count) * step / 2, row, w, 20).build());
        if (metadata != null) addRenderableWidget(MenuButton.builder(ResultTexts.label("details"), b ->
                minecraft.setScreenAndShow(new CandidateDetailsScreen(this, metadata)))
                .bounds(center + 3 * step / 2, row, w, 20).build());
    }

    private Component borderLabel() {
        return MapTexts.label(borders ? "borders_on" : "borders_off");
    }
    private int terrainCount() {
        return !full ? 3 : metadata == null || metadata.query().isEmpty() ? 4 : 5;
    }
    private Component layerName() {
        return MapTexts.label("layer_" + layer().name().toLowerCase(Locale.ROOT));
    }
    private SurfaceColors.Layer layer() {
        return switch (view) {
            case BIOMES, GENERAL -> SurfaceColors.Layer.BIOMES;
            case SURFACE -> SurfaceColors.Layer.SURFACE;
            case TERRAIN -> TERRAIN[terrain];
        };
    }

    private void invalidate() {
        closeImages();
        revision = -1;
    }

    @Override public void tick() {
        if (savedDiscoveries != null && savedDiscoveries.isDone()) {
            savedDiscoveries.getNow(Optional.empty()).ifPresent(discoveries -> {
                discoveries.anchors().forEach(a -> structures.verified(a.structureId(), a.x(), a.z(),
                        a.minX(), a.minZ(), a.maxX(), a.maxZ()));
                discoveries.features().forEach(structures::feature);
            });
            savedDiscoveries = null;
        }
        if (full && metadata == null && saved.isDone() && !saved.isCompletedExceptionally()) {
            metadata = saved.getNow(Optional.empty()).orElse(null);
            if (metadata != null) {
                structures.includeSearchResult(metadata.result());
                structures.distanceTerms(metadata.query());
                metadata.anchors().forEach(structures::matched);
                if (view == View.GENERAL) rebuildWidgets();
            }
        }
        if (overview != null) {
            overview.layer(layer());
            overview.temperature(layer() == SurfaceColors.Layer.TEMPERATURE);
            overview.water(layer() == SurfaceColors.Layer.WATER);
            overview.tick();
            if (layer() == SurfaceColors.Layer.MATCH && metadata != null) {
                fit.request(overview.snapshot(), metadata.query(), metadata.config(), metadata.anchors());
                var result = fit.result();
                if (result != null && result != fitted) {
                    fitted = result;
                    coverage.upload(result.map().width(), result.map().height(), result.pixels());
                }
            }
        }
        if (full && dimension == DimensionSpace.END && structures.enabled.contains(EndReturnGateways.ID))
            scanLoadedGateways();
        if (revision != atlas.revision() || ++ticks % 8 == 0 || drag)
            refreshTiles();
    }

    private void scanLoadedGateways() {
        var player = minecraft.player;
        var level = minecraft.level;
        if (player == null || level == null || !level.dimension().identifier().toString().equals(dimension.id))
            return;
        int cx = player.getBlockX() >> 4, cz = player.getBlockZ() >> 4;
        int radius = minecraft.options.getEffectiveRenderDistance();
        if (cx != gatewayScanX || cz != gatewayScanZ || radius != gatewayScanRadius) {
            gatewayScanX = cx;
            gatewayScanZ = cz;
            gatewayScanRadius = radius;
            gatewayScan = 0;
        }
        int width = radius * 2 + 1;
        for (int i = 0; i < 8; i++) {
            int x = cx - radius + gatewayScan % width;
            int z = cz - radius + gatewayScan / width;
            gatewayScan = (gatewayScan + 1) % (width * width);
            if (!EndReturnGateways.mayContain(x, z))
                continue;
            var chunk = level.getChunkSource().getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
            if (chunk != null) EndReturnGateways.inChunk(chunk).forEach(structures::feature);
        }
    }

    private void refreshTiles() {
        revision = atlas.revision();
        visible = atlas.visible(full, ((int)Math.floor(viewport.worldX(0))) & ~15,
                ((int)Math.floor(viewport.worldZ(0))) & ~15, (int)Math.ceil(viewport.worldX(mapWidth)),
                (int)Math.ceil(viewport.worldZ(mapHeight)), viewport.x(), viewport.z());
        images.entrySet().removeIf(entry -> {
            if (visible.containsKey(entry.getKey()))
                return false;
            entry.getValue().close();
            return true;
        });
        int budget = 16;
        for (var entry : visible.entrySet()) {
            if (images.containsKey(entry.getKey()))
                continue;
            var image = new MapImage();
            image.upload(16, 16, SurfaceColors.raster(entry.getValue(), layer(), borders));
            images.put(entry.getKey(), image);
            if (--budget == 0)
                break;
        }
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
        int header = navigation.getRectangle().height();
        g.blit(RenderPipelines.GUI_TEXTURED, CreateWorldScreen.TAB_HEADER_BACKGROUND,
                0, 0, 0, 0, width, header, 16, 16);
        g.blit(RenderPipelines.GUI_TEXTURED, Screen.FOOTER_SEPARATOR, 0, footer, 0, 0, width, 2, 32, 2);
        if (viewport.advance() && overview != null)
            overview.dirty();
        g.fill(x, y, x + mapWidth, y + mapHeight, dimension == DimensionSpace.END ? DimensionSpace.END_VOID_COLOR : 0xFF26343D);
        if (overview != null)
            overview.drawBase(g);
        g.enableScissor(x, y, x + mapWidth, y + mapHeight);
        for (var entry : visible.entrySet()) {
            var tile = entry.getValue();
            var image = images.get(entry.getKey());
            if (image == null) continue;
            drawImage(g, image, tile.x(), tile.z(), 16, 16);
        }
        if (layer() == SurfaceColors.Layer.MATCH && fitted != null) {
            var area = fitted.map();
            drawImage(g, coverage, area.x(), area.z(), area.width() * area.step(), area.height() * area.step());
        }
        if (overview != null) {
            overview.drawSlimeChunks(g);
            overview.drawMarkers(g);
        }
        var marker = MapPointer.project(viewport.pixelX(playerX + .5), viewport.pixelZ(playerZ + .5),
                mapWidth - 1, mapHeight - 1, 0);
        g.disableScissor();
        BiomeMapCanvas.drawCrossAt(g, x + marker.x(), y + marker.y(), "YOU", x, y, mapWidth, mapHeight);
        String source = full ? MapTexts.text("live_full") : MapTexts.text("live_visited");
        String guide = full ? source : MapTexts.text("live_explore");
        if (inside(mx, my)) {
            int wx = (int)Math.floor(viewport.worldX(mx - x)), wz = (int)Math.floor(viewport.worldZ(my - y));
            var tile = visible.get(SurfaceCache.key(wx, wz));
            String biome = tile == null ? null : tile.biomes()[tile.index(wx, wz)];
            String coordinates = MapTexts.coordinates(wx, wz);
            if (overview != null && biome == null) {
                var cell = overview.hovered(mx, my);
                if (cell != null) biome = cell.id();
            }
            String feature = overview == null ? null : overview.markerHover(mx, my);
            if (feature != null) guide = coordinates + " · " + feature;
            else if (biome != null) guide = coordinates + " · " + ResultTexts.name(biome);
            else guide = coordinates + " · " + guide;
        }
        g.centeredText(font, font.plainSubstrByWidth(guide, mapWidth), x + mapWidth / 2,
                y + mapHeight + 4, 0xFFFFFFFF);
        super.extractRenderState(g, mx, my, tick);
        if (context != null) context.draw(g, mx, my);
    }

    private void drawImage(GuiGraphicsExtractor g, MapImage image, int wx, int wz, int w, int h) {
        int dx = x + viewport.edgeX(wx), dz = y + viewport.edgeZ(wz);
        image.draw(g, dx, dz, x + viewport.edgeX(wx + w) - dx, y + viewport.edgeZ(wz + h) - dz);
    }

    private boolean inside(double mx, double my) {
        return mx >= x && my >= y && mx < x + mapWidth && my < y + mapHeight;
    }

    private void showContext(double mouseX, double mouseY) {
        int wx = (int)Math.floor(viewport.worldX(mouseX - x)), wz = (int)Math.floor(viewport.worldZ(mouseY - y));
        context = new MapContextMenu(font, width, height, mouseX, mouseY, List.of(
                new MapContextMenu.Entry(MapTexts.label("center_here"), () -> {
                    context = null;
                    viewport.reset(wx, wz, viewport.scale());
                    if (overview != null) overview.dirty();
                }),
                new MapContextMenu.Entry(MapTexts.label("map_copy_coordinates"), () -> {
                    minecraft.keyboardHandler.setClipboard(wx + " " + wz);
                    context = null;
                })));
    }

    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (MapContextMenu.keyboardShortcut(event)) {
            showContext(x + mapWidth / 2.0, y + mapHeight / 2.0);
            return true;
        }
        if (context != null) {
            if (event.isEscape()) { context = null; return true; }
            if (context.key(event)) return true;
        }
        return super.keyPressed(event);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean keyboard) {
        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT && inside(event.x(), event.y())) {
            showContext(event.x(), event.y());
            return true;
        }
        if (context != null) {
            if (context.click(event)) return true;
            context = null;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && inside(event.x(), event.y())) {
            drag = true;
            return true;
        }
        return super.mouseClicked(event, keyboard);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        drag = false;
        return context != null && context.release() || super.mouseReleased(event);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (context != null && context.drag(event.y())) return true;
        if (!drag) return super.mouseDragged(event, dx, dy);
        viewport.pan(dx, dy);
        if (overview != null) overview.dirty();
        return true;
    }
    @Override public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (context != null && context.scroll(mx, my, v)) return true;
        if (!inside(mx, my)) return super.mouseScrolled(mx, my, h, v);
        context = null;
        viewport.zoomSmooth(v, mx - x, my - y);
        return true;
    }

    private void closeImages() {
        images.values().forEach(MapImage::close);
        images.clear();
    }
    @Override public void removed() {
        context = null;
        closeImages();
        visible = Map.of();
        revision = -1;
        coverage.close();
        fitted = null;
        fit.close();
        if (overview != null) overview.suspend();
    }

    @Override public void onClose() {
        if (overview != null) overview.close();
        minecraft.setScreenAndShow(parent);
    }
    @Override public boolean isPauseScreen() {
        return true;
    }
}
