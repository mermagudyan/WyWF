package com.wywf.client;

import com.wywf.core.DimensionSpace;
import com.wywf.core.SearchResult;
import com.wywf.map.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

final class BiomeMapCanvas implements AutoCloseable {
    final MapViewport view;
    private final SearchResult result;
    final StructureLayers structures;
    private final int resolution;
    private final BiomeMaps.Session session;
    private final BiomeMaps.Scope scope;
    private final boolean ownsScope;
    private final MapTiles tiles;
    private BiomeMapSnapshot displayed;
    private long requestAt;
    private boolean dirty = true, temperature, borders, distances = true;
    private boolean climate;
    private int sampleY = 64;
    private com.wywf.core.DimensionSpace dimension = com.wywf.core.DimensionSpace.OVERWORLD;
    void dimension(com.wywf.core.DimensionSpace value) {
        if (dimension == value)
            return;
        dimension = value;
        structures.dimension(value);
        tiles.dimension(value);
        displayed = null;
        session.invalidate();
        dirty();
    }
    void layer(com.wywf.verify.SurfaceColors.Layer value) {
        boolean requested = value == com.wywf.verify.SurfaceColors.Layer.CLIMATE;
        if (climate != requested) {
            climate = requested;
            dirty();
        }
        tiles.layer(value);
    }
    private int centerX() {
        return dimension.coordinate(result.centerX);
    }
    private int centerZ() {
        return dimension.coordinate(result.centerZ);
    }
    private java.util.List<Legend> legend = java.util.List.of();
    record Legend(int color, String name) {}
    private int x, y, width, height;

    BiomeMapCanvas(SearchResult result, int resolution) {
        this(result, resolution, null);
    }
    BiomeMapCanvas(SearchResult result, int resolution, MapViewport shared) {
        this(result, resolution, shared, new StructureLayers(result));
    }
    BiomeMapCanvas(SearchResult result, int resolution, MapViewport shared, StructureLayers structures) {
        this(result,resolution,shared,structures,BiomeMaps.scope(),true);
    }
    BiomeMapCanvas(SearchResult result,int resolution,MapViewport shared,StructureLayers structures,BiomeMaps.Scope scope) {
        this(result,resolution,shared,structures,scope,false);
    }
    private BiomeMapCanvas(SearchResult result,int resolution,MapViewport shared,StructureLayers structures,BiomeMaps.Scope scope,boolean ownsScope) {
        this.scope=scope;
        this.ownsScope=ownsScope;
        session=BiomeMaps.open(scope);
        this.result = result;
        tiles = new MapTiles(result.seed);
        this.resolution = resolution;
        this.structures = structures;
        settings(com.wywf.core.ConfigStore.load());
        view = shared != null ? shared : new MapViewport(result.centerX, result.centerZ, resolution <= 32 ? 32 : 2);
    }

    void bounds(int x, int y, int width, int height) {
        if (this.width != width || this.height != height)
            dirty();
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        view.resize(width, height);
    }
    BiomeMapSnapshot snapshot() {
        return displayed;
    }
    java.util.List<Legend> legend() {
        return legend;
    }
    void y(int value) {
        if (sampleY != value) {
            sampleY = value;
            tiles.y(value);
            displayed = null;
            dirty();
        }
    }
    void borders(boolean value) {
        if (borders == value)
            return;
        borders = value;
        tiles.borders(value);
    }
    void distances(boolean value) {
        distances = value;

    }
    boolean loading() {
        return dirty || session.loading();
    }
    int sampleY() {
        return sampleY;
    }
    void temperature(boolean value) {
        temperature = value;
        tiles.temperature(value);
    }
    void water(boolean value) {
        tiles.water(value);
    }
    void dirty() {
        dirty = true;
        requestAt = displayed == null ? 0 : System.nanoTime() + 120_000_000;
    }
    boolean contains(double mx, double my) {
        return mx >= x && my >= y && mx < x + width && my < y + height;
    }
    void zoom(double amount, double mx, double my) {
        view.zoomSmooth(amount, mx - x, my - y);
        dirty();
    }
    void pan(double dx, double dy) {
        view.pan(dx, dy);
        dirty();
    }

    void suspend() {
        displayed = null;
        dirty = true;
        session.invalidate();
        structures.paused(true);
        tiles.close();
    }
    void resume() {
        if (displayed != null) {
        upload(displayed);
        tiles.prepare(view);
        }
    }
    void settings(com.wywf.core.SearchConfig config) {
        BiomeMaps.workMillis(config.mapWorkMillis());
        pauseInactive = config.pauseMapInactive();
    }
    private boolean pauseInactive = true;

    void tick() {
        if(view.advance())dirty();
        boolean active = !pauseInactive || Minecraft.getInstance().isWindowActive();
        session.paused(!active);
        structures.paused(!active);
        if (!active)
            return;
        if (resolution > 32&&!view.zooming())
            structures.requestView(result.seed, view);
        tiles.step(view.sampleStep(resolution));
        tiles.prepare(view);
        if (dirty && System.nanoTime() >= requestAt) {
            int step = view.sampleStep(resolution);
            int startX = Math.floorDiv((int) Math.floor(view.worldX(0)), step) * step;
            int startZ = Math.floorDiv((int) Math.floor(view.worldZ(0)), step) * step;
            int cols = Math.min(511, (int) Math.ceil((view.worldX(width) - startX) / step) + 1);
            int rows = Math.min(511, (int) Math.ceil((view.worldZ(height) - startZ) / step) + 1);
            dirty = !session.request(new BiomeMaps.Request(
                    result.seed, startX, startZ, step, cols, rows, sampleY, dimension, climate));
            if (dirty) requestAt = System.nanoTime() + 200_000_000;
        }
        BiomeMapSnapshot snapshot = session.snapshot();
        if (snapshot == null || snapshot == displayed || snapshot.y() != sampleY)
            return;
        upload(snapshot);
        tiles.prepare(view);
        displayed = snapshot;
    }

    private void upload(BiomeMapSnapshot snapshot) {
        tiles.borders(borders);
        tiles.update(snapshot);
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (var cell : snapshot.cells())
            if (cell != null && (dimension != DimensionSpace.END || !BiomeMapColors.endVoid(cell))) {
            String id = snapshot.y() < 64 && !BiomeMapColors.cave(cell.id()) ? cell.surfaceId() : cell.id();
            counts.merge(id, 1, Integer::sum);
        }
        legend = counts.entrySet().stream().sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8).map(e -> new Legend(BiomePalette.mapColor(e.getKey()), ResultTexts.name(e.getKey()))).toList();
    }

    String status() {
        if (session.error() != null)
            return MapTexts.text("error", session.error());
        if (!loading() && displayed != null && displayed.step() >= 128)
            return MapTexts.text("overview", displayed.step());
        return MapTexts.text(loading() || displayed == null || !displayed.complete() ? "loading" : "resolution",
                displayed == null ? 0 : displayed.step());
    }

    BiomeMapSnapshot.Cell hovered(double mx, double my) {
        if (!contains(mx,my))
            return null;
        int wx = (int)Math.floor(view.worldX(mx-x)), wz = (int)Math.floor(view.worldZ(my-y));
        var cached = tiles.at(wx,wz);
        return cached != null ? cached : displayed == null ? null : BiomeMapColors.sampleAt(displayed.at(wx,wz),wx,wz,displayed.step());
    }

    void draw(GuiGraphicsExtractor g) {
        draw(g, true);
    }
    void draw(GuiGraphicsExtractor g, boolean markers) {
        drawBase(g);
        drawSlimeChunks(g);
        if (markers) {
            drawCross(g);
            drawMarkers(g);
        }
    }

    void drawBase(GuiGraphicsExtractor g) {
        drawBase(g, false);
    }

    void drawBase(GuiGraphicsExtractor g, boolean overviewOnly) {
        if(view.advance())dirty();
        g.fill(x, y, x + width, y + height, dimension == DimensionSpace.END ? DimensionSpace.END_VOID_COLOR : 0xFF29343D);
        g.enableScissor(x, y, x + width, y + height);
        tiles.prepare(view);
        tiles.draw(g, view, x, y, temperature, overviewOnly);
        g.disableScissor();
    }
    void drawCross(GuiGraphicsExtractor g) {
        drawCross(g, null);
    }

    void drawCross(GuiGraphicsExtractor g, String label) {
        var pointer = MapPointer.project(view.pixelX(centerX()), view.pixelZ(centerZ()), width - 1, height - 1, 0);
        drawCrossAt(g, x + pointer.x(), y + pointer.y(), label);
    }

    void drawCrossAt(GuiGraphicsExtractor g, int cx, int cy, String label) {
        drawCrossAt(g, cx, cy, label, x, y, width, height);
    }

    static void drawCrossAt(GuiGraphicsExtractor g, int cx, int cy, String label,
                            int x, int y, int width, int height) {
        g.enableScissor(x, y, x + width, y + height);
        g.fill(cx - 4, cy, cx + 5, cy + 1, 0xFFFFFFFF);
        g.fill(cx, cy - 4, cx + 1, cy + 5, 0xFFFFFFFF);
        int[] labelBox = crossLabelBounds(cx, cy, label, x, y, width, height);
        if (labelBox != null) {
            g.fill(labelBox[0], labelBox[1], labelBox[2], labelBox[3], 0xDC182027);
            g.text(Minecraft.getInstance().font, label, labelBox[0] + 2, labelBox[1] + 1, 0xFFFFFFFF);
        }
        g.disableScissor();
    }

    private static int[] crossLabelBounds(int cx, int cy, String label, int x, int y, int width, int height) {
        if (label == null) return null;
        var font = Minecraft.getInstance().font;
        int labelWidth = font.width(label);
        if (width <= labelWidth + 4 || height < font.lineHeight + 8) return null;
        int tx = Math.clamp(cx - labelWidth / 2, x + 2, x + width - labelWidth - 2);
        int ty = cy + 7;
        if (ty + font.lineHeight + 2 > y + height) ty = cy - 6 - font.lineHeight;
        ty = Math.max(y + 2, ty);
        return new int[]{tx - 2, ty - 1, tx + labelWidth + 2, ty + font.lineHeight + 1};
    }

    private int markerX(StructureLayers.Marker marker) {
        int margin = Math.min(10, (width - 1) / 2);
        return x + Math.clamp(view.edgeX(marker.x()), margin, width - 1 - margin);
    }

    private int markerY(StructureLayers.Marker marker) {
        int margin = Math.min(10, (height - 1) / 2);
        return y + Math.clamp(view.edgeZ(marker.z()), margin, height - 1 - margin);
    }

    String markerHover(double mx, double my) {
        if (!contains(mx, my))
            return null;
        com.wywf.map.StructureLayers.Marker closest = null;
        double distance = 10;
        for (var marker : shownMarkers()) {
            double pixels = Math.hypot(markerX(marker) - mx, markerY(marker) - my);
            if (pixels <= 9 && pixels < distance) {
                closest = marker;
                distance = pixels;
            }
        }
        if (closest == null) return null;
        return MapTexts.text("target", ResultTexts.name(closest.id()), closest.x(), closest.z(),
                structures.distance(closest, centerX(), centerZ()))
                + " · " + MapTexts.text(closest.queryMatched() ? "query_match" : closest.verified() ? "confirmed" : "predicted")
                + " · " + MapTexts.text(structures.boundary(closest) && closest.verified() ? "distance_edge" : "distance_start")
                + structures.featureHeights(closest);
    }

    private long markerLayoutVersion=Long.MIN_VALUE;
    private double markerLayoutX,markerLayoutZ,markerLayoutScale;
    private int markerLayoutWidth,markerLayoutHeight;
    private java.util.List<StructureLayers.Marker> markerLayout=java.util.List.of();
    private java.util.List<StructureLayers.Marker> shownMarkers(){
        if(markerLayoutVersion==structures.markerVersion()
                && markerLayoutX==view.x()
                && markerLayoutZ==view.z()
                && markerLayoutScale==view.scale()
                && markerLayoutWidth==width
                && markerLayoutHeight==height)
            return markerLayout;
        markerLayoutVersion=structures.markerVersion();
        markerLayoutX=view.x();
        markerLayoutZ=view.z();
        markerLayoutScale=view.scale();
        markerLayoutWidth=width;
        markerLayoutHeight=height;
        markerLayout=MarkerLayout.select(structures.markers(),markerLayout,view,structures::requested);
        return markerLayout;
    }
    private final java.util.Map<Long,Boolean> slimeCache=new java.util.LinkedHashMap<>(
            1024,0.75f,true){
        protected boolean removeEldestEntry(java.util.Map.Entry<Long,Boolean> e){
                return size()>32768;
            }
        };
    void drawSlimeChunks(GuiGraphicsExtractor g){
        if(dimension!=com.wywf.core.DimensionSpace.OVERWORLD||view.scale()>4||!structures.enabled.contains("wywf:slime_chunk"))
            return;
        int minX=Math.floorDiv((int)Math.floor(view.worldX(0)),16),maxX=Math.floorDiv((int)Math.ceil(view.worldX(width)),16);
        int minZ=Math.floorDiv((int)Math.floor(view.worldZ(0)),16),maxZ=Math.floorDiv((int)Math.ceil(view.worldZ(height)),16);
        g.enableScissor(x, y, x + width, y + height);
        for(int cz=minZ;cz<=maxZ;cz++)
            for(int cx=minX;cx<=maxX;cx++){
            long key=((long)cx<<32)^(cz&0xffffffffL);
            final int tx=cx,tz=cz;
            if(!slimeCache.computeIfAbsent(key,k->SlimeChunks.contains(result.seed,tx,tz)))continue;
            g.fill(x + view.edgeX(cx * 16), y + view.edgeZ(cz * 16),
                    x + view.edgeX(cx * 16 + 16), y + view.edgeZ(cz * 16 + 16), 0x6635FF3A);
            }
        g.disableScissor();
    }
    void drawMarkers(GuiGraphicsExtractor g) {
        drawMarkers(g, null);
    }

    void drawMarkers(GuiGraphicsExtractor g, String centerLabel) {
        g.enableScissor(x, y, x + width, y + height);
        int labels = 0;
        var shown = shownMarkers();
        var labelBoxes = new java.util.ArrayList<int[]>();
        if (centerLabel != null) {
            var pointer = MapPointer.project(view.pixelX(centerX()), view.pixelZ(centerZ()), width - 1, height - 1, 0);
            int[] labelBox = crossLabelBounds(x + pointer.x(), y + pointer.y(), centerLabel, x, y, width, height);
            if (labelBox != null) labelBoxes.add(labelBox);
        }
        for (var marker : shown) {
            int sx = markerX(marker), sy = markerY(marker);
            if (distances && resolution > 32 && view.scale()<=8 && labels++ < 48){
                String text=structures.distance(marker,centerX(),centerZ())+" b";
                int tw=Minecraft.getInstance().font.width(text);
                for(int[] pos:new int[][]{{sx+11,sy-4},{sx-tw-11,sy-4},{sx-tw/2,sy-20}}){
                    int[] box={pos[0],pos[1],pos[0]+tw,pos[1]+10};
                    boolean blocked=box[0]<x||box[2]>x+width||box[1]<y||box[3]>y+height;
                    for(var other:shown){
                        int ox=markerX(other),oy=markerY(other);
                        if(box[0]<ox+10&&box[2]>ox-10&&box[1]<oy+10&&box[3]>oy-10)
                            blocked=true;
                    }
                    for(var previous:labelBoxes)
                        if(box[0]<previous[2]+2&&box[2]>previous[0]-2&&box[1]<previous[3]+2&&box[3]>previous[1]-2)
                            blocked=true;
                    if(!blocked){
                        g.text(Minecraft.getInstance().font,text,pos[0],pos[1],MapTexts.EMPHASIS);
                        labelBoxes.add(box);
                        break;
                    }
                }
            }
            g.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xFF182027);
            int color = marker.queryMatched()
                    ? 0xFF9FE3AE : marker.verified() ? 0xFFFFD179 : 0xFFC5BDC2;
            var icon = SearchConstructorScreen.icon(marker.id());
            if (icon != null) {
                g.fill(sx - 9, sy - 9, sx + 9, sy + 9, color);
                MenuItemIcon.draw(g, icon, sx - 8, sy - 8);
                if (marker.queryMatched()) {
                    g.fill(sx + 5, sy - 10, sx + 10, sy - 5, 0xFF182027);
                    g.fill(sx + 6, sy - 9, sx + 9, sy - 6, color);
                }
            }
            else g.fill(sx - 2, sy - 2, sx + 3, sy + 3, color);
        }
        g.disableScissor();
    }

    @Override public void close() {
        displayed = null;
        session.close();
        tiles.close();
        structures.close();
        if(ownsScope)
            scope.close();
    }
}
