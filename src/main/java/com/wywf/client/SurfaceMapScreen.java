package com.wywf.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wywf.core.DimensionSpace;
import com.wywf.verify.*;
import com.wywf.map.MapViewport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;

public final class SurfaceMapScreen extends Screen {
    private enum View { GENERAL, BIOMES, SURFACE, CAVES, TERRAIN }
    private record MapTab(View view, Component getTabTitle) implements Tab {
        @Override public Component getTabExtraNarration() { return Component.empty(); }
        @Override public Layout getLayout() { return new FrameLayout(); }
        @Override public void visitChildren(java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> visitor) {}
        @Override public void doLayout(net.minecraft.client.gui.navigation.ScreenRectangle area) {}
    }
    private static final SurfaceColors.Layer[] TERRAIN = {SurfaceColors.Layer.WATER, SurfaceColors.Layer.TEMPERATURE,
            SurfaceColors.Layer.SLOPE, SurfaceColors.Layer.CLIMATE, SurfaceColors.Layer.MATCH};
    private final java.util.ArrayList<Button> context = new java.util.ArrayList<>();
    private View tab = View.GENERAL;
    private int terrainIndex, header, footer, statusY;
    private MenuTabBar navigation;

    private final Screen parent;
    private java.nio.file.Path savedDirectory;
    private final WorldMapSource session;
    private final java.util.Set<String> seenMapObjects=new java.util.HashSet<>();
    private final java.util.Set<String> scannedFeaturePages=new java.util.LinkedHashSet<>();
    private java.util.concurrent.CompletableFuture<java.util.List<MapFeature>> featureWork;
    private String featureSignature="";
    private final MapImage image = new MapImage();
    private final MapImage fitImage=new MapImage();
    private final FitPreview fitPreview=new FitPreview();
    private FitPreview.Result fitDisplayed;
    private final SurfaceTiles exactTiles=new SurfaceTiles();
    private boolean autoSurface=true;
    private long stableSince;
    private double lastX=Double.NaN,lastZ,lastScale;
    private final MapViewport view;
    private BiomeMapCanvas overview;
    private final com.wywf.map.BiomeMaps.Scope mapCache;
    private final boolean ownsCache;
    private final com.wywf.map.StructureLayers structures;
    private SurfaceSnapshot displayed;
    private VerificationSummary summary;
    private float[] climateRange = {0, 0};
    private SurfaceColors.Layer layer = SurfaceColors.Layer.BIOMES;
    private boolean dirty = true, grid, dragging, localContrast;
    private com.wywf.core.DimensionSpace dimension = com.wywf.core.DimensionSpace.OVERWORLD;
    private int left = 12, top = 90, mapWidth, mapHeight, displayedStep = 1;

    public SurfaceMapScreen(Screen parent, WorldMapSource session) {
        super(MapTexts.label("surface_title"));
        this.parent = parent; this.session = session;
        ownsCache=!(parent instanceof CandidateReviewScreen);
        mapCache=parent instanceof CandidateReviewScreen review ? review.mapCache() : com.wywf.map.BiomeMaps.scope();
        structures = new com.wywf.map.StructureLayers(session.result());
        structures.distanceTerms(session.query());
        structures.confirmation(session::confirmMap);
        if(session instanceof PreviewWorld.Session preview)
            structures.preferences(preview.directory.resolve("wywf/layers.json"));
        session.anchors().forEach(structures::matched);
        var s = session.surface();
        view = new MapViewport(s == null ? session.result().centerX : s.x() + s.size() / 2.0,
                s == null ? session.result().centerZ : s.z() + s.size() / 2.0, s == null ? 2 : 1);
    }

    public SurfaceMapScreen(Screen parent,WorldMapSource source,java.nio.file.Path directory){this(parent,new SavedMapPreview(source,directory));savedDirectory=directory;structures.preferences(directory.resolve("wywf/layers.json"));}
    private void resetMapView() {
        var result = session.result();
        int x = dimension == com.wywf.core.DimensionSpace.END ? 0 : dimension.coordinate(result.centerX);
        int z = dimension == com.wywf.core.DimensionSpace.END ? 0 : dimension.coordinate(result.centerZ);
        view.reset(x, z, 2);
    }

    @Override protected void init() {
        context.clear();
        var manager = new TabManager(this::addRenderableWidget, this::removeWidget, selected -> {
            View next = ((MapTab)selected).view();
            if (next == tab) return;
            tab = next;
            layer = next == View.SURFACE ? SurfaceColors.Layer.SURFACE
                    : next == View.TERRAIN ? TERRAIN[terrainIndex] : SurfaceColors.Layer.BIOMES;
            if (layer == SurfaceColors.Layer.SURFACE && session instanceof SavedMapPreview saved)
                saved.refreshSavedSurface(dimension, (int)Math.floor(view.x()), (int)Math.floor(view.z()));
            dirty = true;
            rebuildWidgets();
        }, selected -> {});

        var tabs = new java.util.ArrayList<MapTab>(5);
        tabs.add(new MapTab(View.GENERAL, MapTexts.label("live_general")));
        tabs.add(new MapTab(View.BIOMES, MapTexts.label("layer_biomes")));
        tabs.add(new MapTab(View.SURFACE, MapTexts.label("layer_surface")));
        if (dimension == DimensionSpace.OVERWORLD) {
            tabs.add(new MapTab(View.CAVES, MapTexts.label("caves")));
            tabs.add(new MapTab(View.TERRAIN, MapTexts.label("live_terrain")));
        }
        var chosen = tabs.stream().filter(candidate -> candidate.view() == tab).findFirst().orElseThrow();
        navigation = addRenderableWidget(MenuTabBar.builder(manager, width).addTabs(tabs.toArray(MapTab[]::new)).build());
        navigation.arrangeElements(width);
        navigation.selectTab(tabs.indexOf(chosen), false);
        header = navigation.getHeight();
        top = header + 6;
        footer = height - 35;
        int controlsHeight = tab == View.GENERAL ? 68 : tab == View.TERRAIN ? 48 : 23;
        mapWidth = Math.max(1, width - 24);
        mapHeight = Math.max(1, footer - top - controlsHeight);
        statusY = top + mapHeight + 3;
        view.resize(mapWidth, mapHeight);
        if (overview == null) {
            overview = new BiomeMapCanvas(session.result(), 512, view, structures, mapCache);
            overview.dimension(dimension);
            overview.borders(grid);
        }
        overview.bounds(left, top, mapWidth, mapHeight);
        overview.resume();
        if (tab == View.GENERAL) initGeneral();
        if (tab == View.TERRAIN) initTerrain();
        int backWidth = Math.min(150, width / 3);
        addRenderableWidget(Button.builder(ResultTexts.label("back"), b -> onClose())
                .bounds((width - backWidth) / 2, footer + 8, backWidth, 20).build());
    }

    private void initGeneral() {
        int row = footer - 24, upperRow = row - 24;
        int w = Math.max(1, Math.min(108, (width - 40) / 5));
        int center = (width - w) / 2, gap = w + 5;
        addRenderableWidget(Button.builder(borderLabel(), b -> {
            grid = !grid;
            overview.borders(grid);
            b.setMessage(borderLabel());
            dirty = true;
        }).bounds(center - 2 * gap, row, w, 20).build());
        addRenderableWidget(Button.builder(MapTexts.label("reset"), b -> {
            resetMapView();
            overview.dirty();
            dirty = true;
        }).bounds(center - gap, row, w, 20).build());
        addRenderableWidget(Button.builder(MapTexts.label("structures_short"), b ->
                minecraft.setScreenAndShow(new StructureLayersScreen(this, structures)))
                .bounds(center, row, w, 20).build());
        addRenderableWidget(Button.builder(MapTexts.label("ftp_find"), b -> {
            int x = (int)Math.floor(view.x()), z = (int)Math.floor(view.z());
            if (parent instanceof CandidateReviewScreen review) {
                if (review.hasPlaceSearch()) review.findAnotherPlace(x, z);
                else minecraft.setScreenAndShow(new FtpScreen(review.creator(), this, session, x, z,
                        review.sessions(), dimension, null));
            } else FtpScreen.openSaved(this, session, savedDirectory, x, z, dimension);
        }).bounds(center + gap, row, w, 20).tooltip(net.minecraft.client.gui.components.Tooltip.create(
                MapTexts.label("ftp_open_help"))).build()).active = parent instanceof CandidateReviewScreen || savedDirectory != null;
        addRenderableWidget(Button.builder(ResultTexts.label("details"), b ->
                minecraft.setScreenAndShow(new CandidateDetailsScreen(this, session)))
                .bounds(center + 2 * gap, row, w, 20).build()).active = session.saved();

        addRenderableWidget(Button.builder(MapTexts.label("relief_view"), b -> {
            int x = (int)Math.floor(view.x()), z = (int)Math.floor(view.z());
            var result = session.summary();
            if (dimension == com.wywf.core.DimensionSpace.OVERWORLD && result != null
                    && (result.status() == VerificationSummary.Status.PASS || result.status() == VerificationSummary.Status.PARTIAL)) {
                x = result.spawnX();
                z = result.spawnZ();
            }
            minecraft.setScreenAndShow(new ReliefScreen(this, session, dimension, x, z));
        }).bounds(center - gap, upperRow, w, 20).build()).active = session.surfaceExpandable() || session.surface() != null;
        addRenderableWidget(Button.builder(MapTexts.label("dimension_" + dimension.name().toLowerCase(java.util.Locale.ROOT)), b -> {
            dimension = com.wywf.core.DimensionSpace.values()[(dimension.ordinal() + 1) % 3];
            overview.dimension(dimension);
            exactTiles.close();
            resetMapView();
            overview.dirty();
            dirty = true;
            rebuildWidgets();
        }).bounds(center, upperRow, w, 20).build());
        addRenderableWidget(Button.builder(MapTexts.label(autoSurface ? "auto_short_on" : "auto_short_off"), b -> {
            autoSurface = !autoSurface;
            b.setMessage(MapTexts.label(autoSurface ? "auto_short_on" : "auto_short_off"));
        }).bounds(center + gap, upperRow, w, 20).build()).active = session.surfaceExpandable();
    }

    private Component borderLabel() {
        return MapTexts.label(grid ? "borders_on" : "borders_off");
    }

    private void initTerrain() {
        int count = dimension != com.wywf.core.DimensionSpace.OVERWORLD ? 2 : session.query().isEmpty() ? 4 : 5;
        if (terrainIndex >= count) terrainIndex = 0;
        layer = TERRAIN[terrainIndex];
        int w = Math.min(135, (width - 32) / 2);
        int row = footer - 24;
        var contrast = Button.builder(contrastLabel(), b -> {
            localContrast = !localContrast;
            b.setMessage(contrastLabel());
            dirty = true;
        }).bounds(width / 2 + 3, row, w, 20).build();
        contrast.visible = layer == SurfaceColors.Layer.CLIMATE && session.surface() != null;
        addRenderableWidget(Button.builder(terrainLabel(), b -> {
            terrainIndex = (terrainIndex + 1) % count;
            layer = TERRAIN[terrainIndex];
            dirty = true;
            rebuildWidgets();
        }).bounds(contrast.visible ? width / 2 - w - 3 : (width - w) / 2, row, w, 20).build());
        addRenderableWidget(contrast);
    }

    private Component contrastLabel() {
        return MapTexts.label(localContrast ? "contrast_local" : "contrast_fixed");
    }

    private Component terrainLabel() {
        return MapTexts.label("layer_" + layer.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Override public void tick() {
        session.tickMap();
        for(var a:session.mapAnchors())
            if(seenMapObjects.add(a.id()+":"+a.x()+":"+a.z()))
                structures.verified(a.id(),a.x(),a.z(),a.minX(),a.minZ(),a.maxX(),a.maxZ());
        for(var feature:session.mapFeatures())
            if(seenMapObjects.add(feature.id()+":"+feature.x()+":"+feature.y()+":"+feature.z()))
                structures.feature(feature);
        overview.y(tab == View.CAVES ? -50 : 64);
        overview.layer(layer);
        overview.temperature(layer == SurfaceColors.Layer.TEMPERATURE);
        overview.water(layer == SurfaceColors.Layer.WATER); overview.tick();
        boolean topView = layer == SurfaceColors.Layer.SURFACE && dimension != DimensionSpace.NETHER;
        var surfaceCache = session.surfaceCache(dimension);
        boolean canLoadSurface = topView
                && surfaceCache != null && session.surfaceExpandable()
                && !session.cancelled()
                && session.summary().terminal() && !session.surfaceBusy();

        if(lastX!=view.x()||lastZ!=view.z()||lastScale!=view.scale()){
            lastX=view.x();
            lastZ=view.z();
            lastScale=view.scale();
            stableSince=System.currentTimeMillis();
        }
        if (topView) {
            exactTiles.update(session,dimension,view,layer,grid,mapWidth,mapHeight);
            if(autoSurface&&canLoadSurface && view.scale()<=4 && System.currentTimeMillis()-stableSince>400){
                var cache = surfaceCache;
                var window=SurfaceWindow.around(view,1024);
                if(cache != null)cache.protect(window);
                int[] missing = cache == null?null:window.missing(cache,view.x(),view.z());
                if(missing!=null){stableSince=System.currentTimeMillis();session.requestSurface(dimension, missing[0], missing[1]);}
            }
        }

        if (dimension != DimensionSpace.NETHER && System.currentTimeMillis() - stableSince > 400
                && (featureWork == null || featureWork.isDone())) {
            var selected = structures.enabled.stream().filter(MapFeature.GENERATED::contains)
                    .filter(id -> DimensionSpace.of(id) == dimension).collect(java.util.stream.Collectors.toSet());
            String signature = dimension.id + ":" + selected.stream().sorted().collect(java.util.stream.Collectors.joining(","));
            if(!signature.equals(featureSignature)){
                featureSignature=signature;scannedFeaturePages.clear();
            }
            if(!selected.isEmpty()){
                var window=SurfaceWindow.around(view,256);
                int[] next=null;double nearest=Double.POSITIVE_INFINITY;
                for(int z=window.z();z<window.z()+window.height();z+=64)
                    for(int x=window.x();x<window.x()+window.width();x+=64){
                    if(scannedFeaturePages.contains(x+":"+z))
                        continue;
                    double distance=Math.hypot(x+32-view.x(),z+32-view.z());
                    if(distance<nearest){
                        next=new int[]{x,z};nearest=distance;
                    }
                }
                if(next!=null){
                    scannedFeaturePages.add(next[0]+":"+next[1]);
                    while(scannedFeaturePages.size()>1024)
                        scannedFeaturePages.remove(scannedFeaturePages.iterator().next());
                    featureWork=session.discoverFeatures(next[0],next[1],selected);}
            }
        }
        if(layer==SurfaceColors.Layer.MATCH){
            fitPreview.request(overview.snapshot(),session.query(),session.config(),session.anchors());
            var next=fitPreview.result();
            if(next!=null&&next!=fitDisplayed){
                fitImage.upload(next.map().width(),next.map().height(),next.pixels());
                fitDisplayed=next;
            }
        }
        var s = session.surface();
        int step = 1;
        while (step < 32 && step * 2 <= view.scale()) step *= 2;
        if(layer==SurfaceColors.Layer.SURFACE&&s!=null){displayed=s;dirty=false;summary=session.summary();}
        if (tab != View.CAVES
                && layer!=SurfaceColors.Layer.SURFACE
                && layer!=SurfaceColors.Layer.MATCH
                && layer!=SurfaceColors.Layer.SLOPE
                && s != null
                && (s != displayed || dirty || summary != session.summary() || step != displayedStep)) {
            int[] pixels = layer == SurfaceColors.Layer.CLIMATE
                    ? SurfaceColors.climateRaster(s, localContrast) : SurfaceColors.raster(s, layer, grid);
            if (step == 1 && layer == SurfaceColors.Layer.BIOMES)
                image.upload(s.size() * 2, s.size() * 2,
                        com.wywf.map.TerrainRaster.draw(pixels, s.heights(), s.size(), s.size(), 2, grid));
            else image.upload(s.size() / step, s.size() / step, step == 1
                    ? pixels : layer == SurfaceColors.Layer.BIOMES
                               ? SurfaceColors.reduceBiomes(s, step) : SurfaceColors.reduce(pixels, s, step));
            displayedStep = step;
            if (layer == SurfaceColors.Layer.CLIMATE && localContrast)
                climateRange = SurfaceColors.climateRange(s);
            summary = session.summary();
            displayed = s; dirty = false;
        }
    }
    private String centerLabel() {
        if (session.query().place() != null)
            return "FTP";
        return MapTexts.text(session.config().searchCenter() == com.wywf.core.SearchConfig.SearchCenter.SPAWN
                ? "spawn_marker" : "origin_marker");
    }

    private boolean inside(double x, double y) {
        return x >= left && x < left + mapWidth && y >= top && y < top + mapHeight;
    }
    @Override public boolean mouseScrolled(double x, double y, double h, double v) {
        if (inside(x, y)) { closeContext(); view.zoomSmooth(v, x - left, y - top); overview.dirty();
            return true;
        }
        return super.mouseScrolled(x, y, h, v);
    }
    private void closeContext() {
        for (var button : context) removeWidget(button);
        context.clear();
    }

    private void showContext(double mouseX, double mouseY) {
        closeContext();
        int wx = (int)Math.floor(view.worldX(mouseX - left)), wz = (int)Math.floor(view.worldZ(mouseY - top));
        var relief = MapTexts.label("relief_here");
        var center = MapTexts.label("center_here");
        var copy = MapTexts.label("ftp_copy_coords");
        int w = Math.min(mapWidth, Math.max(font.width(relief), Math.max(font.width(center), font.width(copy))) + 16);
        int x = Math.clamp((int)mouseX, left, left + mapWidth - w);
        int y = Math.clamp((int)mouseY, top, Math.max(top, top + mapHeight - 60));
        var open = addRenderableWidget(Button.builder(relief, b -> {
            closeContext();
            Minecraft.getInstance().setScreenAndShow(new ReliefScreen(this, session, dimension, wx, wz));
        }).bounds(x, y, w, 20).build());
        open.active = session.surfaceExpandable() || session.surface() != null;
        context.add(open);
        context.add(addRenderableWidget(Button.builder(center, b -> {
            closeContext();
            view.reset(wx, wz, view.scale());
            overview.dirty();
        }).bounds(x, y + 20, w, 20).build()));
        context.add(addRenderableWidget(Button.builder(copy, b -> {
            minecraft.keyboardHandler.setClipboard(wx + " " + wz);
            closeContext();
        }).bounds(x, y + 40, w, 20).build()));
    }

    @Override public boolean mouseClicked(MouseButtonEvent e, boolean keyboard) {
        if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT && inside(e.x(), e.y())) {
            showContext(e.x(), e.y());
            return true;
        }
        if (!context.isEmpty()) {
            if (context.stream().anyMatch(b -> b.isMouseOver(e.x(), e.y())))
                return super.mouseClicked(e, keyboard);
            closeContext();
        }
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT && inside(e.x(), e.y())) { dragging = true;
            return true; }
        return super.mouseClicked(e, keyboard);
    }
    @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging) { view.pan(dx, dy); overview.dirty(); return true; }
        return super.mouseDragged(e, dx, dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent e) { dragging = false; return super.mouseReleased(e); }
    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE && !context.isEmpty()) {
            closeContext();
            return true;
        }
        if (navigation.keyPressed(event)) return true;
        if (event.key() == InputConstants.KEY_PAGEUP || event.key() == InputConstants.KEY_PAGEDOWN) {
            view.zoom(event.key() == InputConstants.KEY_PAGEUP ? 1 : -1, mapWidth / 2.0, mapHeight / 2.0); overview.dirty();
            return true;
        }
        if (event.isUp() || event.isDown() || event.isLeft() || event.isRight()) {
            view.pan(event.isLeft() ? 32 : event.isRight() ? -32 : 0, event.isUp() ? 32 : event.isDown() ? -32 : 0); overview.dirty();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void removed() {
        session.pauseSurface();
        exactTiles.close();
        image.close();
        fitPreview.close();
        fitImage.close();
        displayed = null;
        fitDisplayed = null;
        dirty = true;
        if (overview != null) overview.suspend();
    }

    void release() {
        removed();
        session.closeMap();
        if (overview != null) {
            overview.close();
            overview = null;
        }
        if (ownsCache) mapCache.close();
    }
    @Override public void onClose() { if(ownsCache)release();Minecraft.getInstance().setScreenAndShow(parent); }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
        MenuBands.draw(g, width, height, header, footer);
        g.blit(RenderPipelines.GUI_TEXTURED, CreateWorldScreen.TAB_HEADER_BACKGROUND,
                0, 0, 0, 0, width, header, 16, 16);
        String caption = MapTexts.text("surface_seed", session.result().seed);
        if (tab == View.GENERAL) {
            String hint = caption + " · " + MapTexts.text("relief_context_help");
            g.centeredText(font, font.plainSubstrByWidth(hint, mapWidth), width / 2, statusY, 0xFFFFFFFF);
        }
        g.fill(left, top, left + mapWidth, top + mapHeight, 0xFF26343D);
        if (overview != null) overview.drawBase(g);
        if(layer==SurfaceColors.Layer.MATCH&&fitDisplayed!=null){
            var m=fitDisplayed.map();int sx=left+(int)Math.floor(view.pixelX(m.x())),sy=top+(int)Math.floor(view.pixelZ(m.z()));
            int ex=left+(int)Math.ceil(view.pixelX(m.x()+m.width()*m.step())),ey=top+(int)Math.ceil(view.pixelZ(m.z()+m.height()*m.step()));
            g.enableScissor(left,top,left+mapWidth,top+mapHeight);fitImage.draw(g,sx,sy,ex-sx,ey-sy);g.disableScissor();
        }
        var s = dimension == com.wywf.core.DimensionSpace.OVERWORLD && tab != View.CAVES ? displayed : null;
        var surfaceCache = session.surfaceCache(dimension);
        if (dimension == DimensionSpace.END && layer == SurfaceColors.Layer.SURFACE) s = session.surface(dimension);
        if (s == null && layer == SurfaceColors.Layer.SURFACE && dimension != DimensionSpace.NETHER) {
            int wx = (int) Math.floor(view.worldX(mx - left)), wz = (int) Math.floor(view.worldZ(my - top));
            s = exactTiles.at(wx, wz);
            if (s == null && surfaceCache != null) {
                var tiles = surfaceCache.tiles();
                if (!tiles.isEmpty()) s = tiles.getFirst();
            }
        }
        if (s != null) {
            g.enableScissor(left, top, left + mapWidth, top + mapHeight);
            int x = left + (int) Math.floor(view.pixelX(s.x())), z = top + (int) Math.floor(view.pixelZ(s.z()));
            int right = left + (int) Math.ceil(view.pixelX(s.x() + s.size()));
            int bottom = top + (int) Math.ceil(view.pixelZ(s.z() + s.size()));
            if (layer != SurfaceColors.Layer.BIOMES && layer != SurfaceColors.Layer.TEMPERATURE && layer != SurfaceColors.Layer.WATER
                    && layer!=SurfaceColors.Layer.SLOPE && layer!=SurfaceColors.Layer.MATCH && (layer != SurfaceColors.Layer.CLIMATE || localContrast))
                {if(layer==SurfaceColors.Layer.SURFACE)exactTiles.draw(g,view,left,top);else image.draw(g, x, z, right - x, bottom - z);}
            if(grid&&layer==SurfaceColors.Layer.SURFACE&&view.scale()<=1.0/6){
                for(int wx=(int)Math.floor(view.worldX(0));wx<=view.worldX(mapWidth);wx++){int gx=left+(int)view.pixelX(wx);g.fill(gx,top,gx+1,top+mapHeight,0x33404C54);}
                for(int wz=(int)Math.floor(view.worldZ(0));wz<=view.worldZ(mapHeight);wz++){int gz=top+(int)view.pixelZ(wz);g.fill(left,gz,left+mapWidth,gz+1,0x33404C54);}
            }
            g.disableScissor();
            int wx = (int) Math.floor(view.worldX(mx - left)), wz = (int) Math.floor(view.worldZ(my - top));
            var retained = surfaceCache == null ? null : surfaceCache.get(wx, wz);
            if(retained==null&&layer==SurfaceColors.Layer.SURFACE)retained=exactTiles.at(wx,wz);
            if(retained!=null&&layer==SurfaceColors.Layer.SURFACE)s=retained;
            String help = switch (layer) {
                case MATCH -> "surface_fit";
                case SURFACE -> "surface_help";
                default -> "controls";
            };
            String hover = MapTexts.text(help);
            if (inside(mx, my) && (layer == SurfaceColors.Layer.BIOMES || layer == SurfaceColors.Layer.TEMPERATURE || layer == SurfaceColors.Layer.WATER
                    || layer == SurfaceColors.Layer.CLIMATE || layer == SurfaceColors.Layer.SLOPE)) {
                var cell = overview.hovered(mx, my);
                if (cell != null) hover = MapTexts.text("hover", wx, wz, ResultTexts.name(cell.id()), cell.temperature(),
                        Math.round(Math.hypot((double) wx - session.result().centerX, (double) wz - session.result().centerZ)));
            } else if (inside(mx, my) && s.contains(wx, wz)) {
                int i = s.index(wx, wz);
                if (s.blocks()[i] != null) {
                    hover = s.heights()[i] == Integer.MIN_VALUE ? MapTexts.text("end_void", wx, wz)
                            : ResultTexts.name(s.biomes()[i]) + " · " + String.format(java.util.Locale.ROOT, "X %d Y %d Z %d · %s · T %.2f",
                            wx, s.heights()[i], wz, s.blocks()[i], s.temperatures()[i]);
                    if (Float.isFinite(s.climate()[i])) hover += String.format(java.util.Locale.ROOT, " · noise %.3f", s.climate()[i]);
                }
            }
            String marker = overview.markerHover(mx, my);
            if (marker != null) hover = marker;
            if (tab != View.GENERAL) g.text(font, font.plainSubstrByWidth(hover, width - 24), 12, statusY, 0xFFE2EBEF);
            var legend = layer == SurfaceColors.Layer.BIOMES || layer == SurfaceColors.Layer.TEMPERATURE
                    ? MapTexts.label("biome_overview") : layer == SurfaceColors.Layer.WATER
                    ? MapTexts.label("water_overview") : layer == SurfaceColors.Layer.CLIMATE && localContrast
                    ? MapTexts.label("climate_range", climateRange[0], climateRange[1])
                    : layer == SurfaceColors.Layer.CLIMATE ? MapTexts.label("climate_overview")
                    : layer == SurfaceColors.Layer.SLOPE ? MapTexts.label("slope_overview")
                    : layer==SurfaceColors.Layer.MATCH ? MapTexts.label("coverage_overview") : layer==SurfaceColors.Layer.SURFACE ? MapTexts.label(dimension == DimensionSpace.END ? "surface_end_blocks" : "surface_chunks",
                            surfaceCache == null ? session.surfaceTiles().size() : surfaceCache.size()) : MapTexts.label("surface_coverage", s.size(), s.size(), displayedStep);
            if (tab != View.GENERAL) g.text(font, session.saved() && !session.surfaceExpandable() && layer == SurfaceColors.Layer.SURFACE
                    ? MapTexts.label("saved_snapshot") : legend, 12, statusY + 11, 0xFFBBD0D8);
        }
        if (s == null) {
            var cell = overview.hovered(mx, my);
            String hover = cell == null ? MapTexts.text("controls")
                    : dimension == DimensionSpace.END && cell.height() == Integer.MIN_VALUE
                    ? MapTexts.text("end_void", (int)Math.floor(view.worldX(mx - left)), (int)Math.floor(view.worldZ(my - top)))
                    : ResultTexts.name(cell.id());
            String marker = overview.markerHover(mx, my);
            if (marker != null) hover = marker;
            if (tab != View.GENERAL) g.text(font, font.plainSubstrByWidth(hover, width - 24), 12, statusY, 0xFFE2EBEF);
            if (tab != View.GENERAL) g.text(font,
                    tab == View.CAVES ? "Y=-50 · " + overview.status()
                            : dimension == DimensionSpace.END && tab == View.SURFACE && !overview.loading()
                            ? MapTexts.text("surface_end_estimated") : overview.status(),
                    12, statusY + 11, 0xFFBBD0D8);
        }
        overview.drawSlimeChunks(g);
        String label = dimension == com.wywf.core.DimensionSpace.OVERWORLD ? centerLabel() : null;
        overview.drawMarkers(g, label);
        if (label != null) overview.drawCross(g, label);
        super.extractRenderState(g, mx, my, tick);
    }
}
