package com.wywf.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wywf.core.DimensionSpace;
import com.wywf.core.WyWFDebug;
import com.wywf.WYWFClient;
import com.wywf.verify.MinecraftReliefFog;
import com.wywf.verify.ReliefBlocks;
import com.wywf.verify.ReliefRequest;
import com.wywf.verify.ReliefTile;
import com.wywf.verify.WorldMapSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

final class ReliefScreen extends Screen {
    private static final int HEADER = 54, CAVE_HEADER = 64, MAP_FOOT = 21;
    private static final int DIMENSION_LIST_HEIGHT = 40;
    private static final int PAGE_LIMIT = 3, MAX_PIXELS = 2_100_000;
    private static final int MAX_EXACT_RADIUS = ReliefRadius.MAX;
    private static final double MIN_PITCH = Math.toRadians(10), MAX_PITCH = Math.PI / 2;
    private final Screen parent;
    private final WorldMapSource source;
    private final DimensionSpace dimension;
    private final int focusX, focusZ;
    private final MapImage image = new MapImage();
    private ReliefGpu gpu;
    private boolean gpuImage;
    private final ArrayList<CompletableFuture<List<ReliefTile>>> requests = new ArrayList<>();
    private record Partial(long revision, List<ReliefTile> tiles) {}
    private final java.util.Queue<Partial> partialPages = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile long regionRevision;
    private CompletableFuture<Frame> frame;
    private CompletableFuture<MeshUpdate> building;
    private MeshUpdate preparedMesh;
    private record MeshUpdate(ReliefMesh mesh, long nanos, int reusedChunks) {}
    private ReliefMesh.Builder meshBuilder;
    private record Frame(ReliefMesh mesh, float centerY, int width, int height, int[] colors, ReliefRaster.Buffers buffers,
                         Probe probe, long renderNanos, long meshNanos, int reusedChunks, long queuedAt, long workerStartedAt) {}
    private record Probe(int width, int height, int x, int z, int span, float y,
                         double yaw, double pitch, double zoom, double panX, double panY, long revision, boolean underRoof) {}
    private Probe lastProbe;
    private long lastRenderLog, lastUploadedAt;
    private int[] displayedProbeFaces;
    private long probeClickAt;
    private double probeClickX, probeClickY;
    private boolean probeDragged;
    private ReliefRaster.Buffers idleBuffers;
    private ReliefMesh mesh;
    private float centerY = Float.NaN;
    private int radius, sliderRadius, originX, originZ, chunkSide;
    private long radiusChangedAt, meshStartedAt, lastMeshNanos;
    private int meshTiles;
    private boolean pendingRadius;
    private int[][] pages;
    private int page, caveY, fog, sampledFog, ready;
    private Integer loadedCut;
    private boolean hasRegion;
    private long requestAt;
    private volatile boolean closed;
    private boolean caves, dragging, dirty, meshDirty, hasImage, previousImage;
    private double yaw, pitch, zoom, panX, panY;
    private double zoomAnchorX, zoomAnchorY;
    private boolean panning;
    private double renderedYaw, renderedPitch, renderedZoom;
    private long cameraUpdatedAt;
    private int mapTop, footer;
    private ConstructorDropList dimensions;
    private ReliefTile[] caveTiles;

    ReliefScreen(Screen parent, WorldMapSource source, DimensionSpace dimension, int x, int z) {
        super(MapTexts.label("relief_view"));
        this.parent = parent;
        this.source = source;
        this.dimension = dimension;
        focusX = x;
        focusZ = z;
        gpu = ReliefGpu.create(dimension);
        caves = dimension == DimensionSpace.NETHER;
        caveY = switch (dimension) {
            case OVERWORLD -> -32;
            case NETHER -> 72;
            case END -> 64;
        };
        radius = sliderRadius = ReliefRadius.load();
        pendingRadius = radius > ReliefRadius.MIN && ReliefRadius.save(radius, true);
        if (!pendingRadius && radius > ReliefRadius.MIN) radius = sliderRadius = ReliefRadius.MIN;
        resetSlice();
        resetCamera();
    }

    @Override protected void init() {
        mapTop = (caves ? CAVE_HEADER : HEADER) + 4;
        footer = height - 36;
        var destinations = java.util.Arrays.stream(DimensionSpace.values()).filter(value -> value != dimension).toList();
        int switchWidth = Math.min(108, width / 3);
        dimensions = addRenderableWidget(new ConstructorDropList(12, 7, switchWidth, MapTexts.text("relief_switch_to"),
                destinations.stream().map(value -> MapTexts.text("dimension_" + value.name().toLowerCase(Locale.ROOT))).toList(),
                -1, font, index -> switchDimension(destinations.get(index)),
                list -> {}));
        String mode = caves ? "relief_surface" : dimension == DimensionSpace.OVERWORLD ? "caves" : "relief_slice";
        if (dimension != DimensionSpace.END) addRenderableWidget(Button.builder(MapTexts.label(mode), b -> {
            caves = !caves;
            resetCamera();
            resetSlice();
            rebuildWidgets();
        }).bounds(12, 29, Math.min(90, width / 4), 20).build());
        int settingsWidth = Math.min(114, width / 3);
        var radiusSlider = new SettingSlider(settingsWidth, MapTexts.text("relief_radius_title"),
                sliderRadius, ReliefRadius.MIN, ReliefRadius.MAX, value -> {
                    sliderRadius = value;
                    radiusChangedAt = System.nanoTime();
                }, value -> MapTexts.text("relief_radius", value));
        radiusSlider.setX(width - 12 - settingsWidth);
        radiusSlider.setY(13);
        addRenderableWidget(radiusSlider);
        if (caves) {
            int min = dimension == DimensionSpace.OVERWORLD ? -63 : 0;
            int max = dimension == DimensionSpace.OVERWORLD ? 80 : 127;
            var slider = new SettingSlider(settingsWidth, MapTexts.text("relief_slice"), caveY, min, max,
                    y -> {
                        if (y == caveY) return;
                        caveY = y;
                        resetSlice();
                        requestAt = System.nanoTime() + 250_000_000L;
                    }, y -> "Y=" + y + " ±" + ReliefBlocks.SLICE_RADIUS);
            slider.setX(width - 12 - slider.getWidth());
            slider.setY(43);
            addRenderableWidget(slider);
        }
        int w = Math.max(1, Math.min(150, (width - 32) / 2));
        addRenderableWidget(Button.builder(MapTexts.label("reset"), b -> resetCamera())
                .bounds(width / 2 - w - 4, footer + 8, w, 20).build());
        addRenderableWidget(Button.builder(ResultTexts.label("back"), b -> onClose())
                .bounds(width / 2 + 4, footer + 8, w, 20).build());
        dirty = true;
    }

    private void resetCamera() {
        yaw = Math.PI / 4;
        pitch = dimension == DimensionSpace.NETHER && caves ? .68 : caves ? .88 : .69;
        zoom = dimension == DimensionSpace.NETHER && caves ? 1.45 : 1;
        panX = panY = 0;
        zoomAnchorX = zoomAnchorY = 0;
        renderedYaw = yaw;
        renderedPitch = pitch;
        renderedZoom = zoom;
        cameraUpdatedAt = System.nanoTime();
        dirty = true;
    }

    private void resetSlice() {
        cancelWork();
        meshBuilder = new ReliefMesh.Builder();
        if (gpu != null) gpu.discardMesh();
        regionRevision++;
        partialPages.clear();
        Integer cut = caves ? caveY : null;
        var previousTiles = caveTiles;
        int previousSide = chunkSide;
        int detailRadius = Math.min(MAX_EXACT_RADIUS, radius);
        chunkSide = 2 * detailRadius;
        originX = ((focusX >> 4) - chunkSide / 2) << 4;
        originZ = ((focusZ >> 4) - chunkSide / 2) << 4;
        caveTiles = new ReliefTile[chunkSide * chunkSide];
        int pageSide = Math.ceilDiv(chunkSide * 16, ReliefBlocks.SIZE);
        var offsets = new ArrayList<int[]>();
        for (int z = 0; z < pageSide; z++) for (int x = 0; x < pageSide; x++) offsets.add(new int[]{x, z});
        double focusPageX = (focusX - originX) / (double)ReliefBlocks.SIZE;
        double focusPageZ = (focusZ - originZ) / (double)ReliefBlocks.SIZE;
        offsets.sort(Comparator.comparingDouble(p -> Math.hypot(p[0] + .5 - focusPageX, p[1] + .5 - focusPageZ)));
        pages = offsets.toArray(int[][]::new);
        page = ready = meshTiles = 0;
        lastMeshNanos = lastUploadedAt = 0;
        if (hasRegion && java.util.Objects.equals(loadedCut, cut)) {
            int shift = (chunkSide - previousSide) / 2;
            for (int z = 0; z < previousSide; z++) for (int x = 0; x < previousSide; x++) {
                int destX = x + shift, destZ = z + shift;
                if (destX < 0 || destX >= chunkSide || destZ < 0 || destZ >= chunkSide) continue;
                var tile = previousTiles[z * previousSide + x];
                if (tile != null) {
                    caveTiles[destZ * chunkSide + destX] = tile;
                    ready++;
                }
            }
        } else {
            sampledFog = 0;
            fog = defaultFog();
            centerY = Float.NaN;
        }
        hasRegion = true;
        loadedCut = cut;
        mesh = null;
        dirty = meshDirty = true;
        previousImage = hasImage;
        requestAt = 0;
    }

    private void cancelWork() {
        for (var request : requests) request.cancel(false);
        requests.clear();
        if (frame != null) frame.cancel(false);
        frame = null;
        if (building != null) building.cancel(false);
        building = null;
        preparedMesh = null;
    }

    @Override public void tick() {
        if (closed) return;
        if (sliderRadius != radius && System.nanoTime() - radiusChangedAt >= 320_000_000L) {
            int next = sliderRadius;
            if (ReliefRadius.save(next, next > ReliefRadius.MIN)) {
                radius = next;
                pendingRadius = next > ReliefRadius.MIN;
            } else {
                radius = sliderRadius = ReliefRadius.MIN;
                rebuildWidgets();
            }
            resetSlice();
        }
        source.tickMap();
        Partial partial;
        while ((partial = partialPages.poll()) != null)
            if (partial.revision() == regionRevision) addTiles(partial.tiles());
        for (var iterator = requests.iterator(); iterator.hasNext();) {
            var request = iterator.next();
            if (!request.isDone()) continue;
            if (!request.isCompletedExceptionally()) addTiles(request.join());
            iterator.remove();
        }
        while (requests.size() < PAGE_LIMIT && page < pages.length && System.nanoTime() >= requestAt) {
            int[] offset = pages[page++];
            if (pageLoaded(caveTiles, chunkSide, offset[0], offset[1])) continue;
            var focus = new BlockPos(focusX, caveY, focusZ);
            long revision = regionRevision;
            requests.add(source.reliefPage(new ReliefRequest(dimension, originX + offset[0] * ReliefBlocks.SIZE,
                    originZ + offset[1] * ReliefBlocks.SIZE, caves ? caveY : null, focus),
                    tiles -> {
                        var partialPage = new Partial(revision, tiles);
                        partialPages.add(partialPage);
                        if (closed || revision != regionRevision) partialPages.remove(partialPage);
                    }));
        }
        updateFog();
        if (pendingRadius && page == pages.length && requests.isEmpty()
                && !meshDirty && frame == null && building == null && preparedMesh == null && hasImage) {
            pendingRadius = false;
            ReliefRadius.save(radius, false);
        }
    }

    static boolean pageLoaded(ReliefTile[] tiles, int chunkSide, int x, int z) {
        int side = ReliefBlocks.SIZE / 16;
        int startX = x * side, startZ = z * side;
        int endX = Math.min(chunkSide, startX + side), endZ = Math.min(chunkSide, startZ + side);
        for (int cz = startZ; cz < endZ; cz++) {
            for (int cx = startX; cx < endX; cx++) {
                if (tiles[cz * chunkSide + cx] == null) return false;
            }
        }
        return true;
    }

    private void addTiles(List<ReliefTile> completed) {
        for (var tile : completed) {
            int x = (tile.x() - originX) >> 4, z = (tile.z() - originZ) >> 4;
            if (x < 0 || x >= chunkSide || z < 0 || z >= chunkSide) continue;
            int index = z * chunkSide + x;
            if (caveTiles[index] == tile) continue;
            if (caveTiles[index] == null) ready++;
            caveTiles[index] = tile;
            if (tile.fog() != 0) sampledFog = tile.fog();
            meshDirty = true;
        }
    }

    private int defaultFog() {
        return switch (dimension) {
            case OVERWORLD -> caves ? 0xFF141722 : 0xFFC0D8FF;
            case NETHER -> 0xFF330808;
            case END -> DimensionSpace.END_VOID_COLOR;
        };
    }

    private void updateFog() {
        int target = sampledFog == 0 ? defaultFog() : sampledFog;
        var client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        if (dimension == DimensionSpace.NETHER && client.player != null && client.level != null
                && client.level.dimension().identifier().toString().equals(dimension.id)
                && server != null && server.overworld().getSeed() == source.result().seed) {
            target = MinecraftReliefFog.at(client.level, client.player.blockPosition());
        }
        if (fog != target) {
            fog = target;
            dirty = true;
        }
    }

    private int mapHeight() { return footer - MAP_FOOT - mapTop; }

    private void easeCamera(long now) {
        double weight = -Math.expm1(-(now - cameraUpdatedAt) / 32_000_000.0);
        cameraUpdatedAt = now;
        double turn = Math.IEEEremainder(yaw - renderedYaw, Math.TAU);
        boolean moving = Math.abs(turn) > .001 || Math.abs(pitch - renderedPitch) > .001
                || Math.abs(zoom - renderedZoom) > zoom * .001;
        double nextYaw = moving ? Math.IEEEremainder(renderedYaw + turn * weight, Math.TAU) : yaw;
        double nextPitch = moving ? renderedPitch + (pitch - renderedPitch) * weight : pitch;
        double nextZoom = moving ? renderedZoom + (zoom - renderedZoom) * weight : zoom;
        dirty |= nextYaw != renderedYaw || nextPitch != renderedPitch || nextZoom != renderedZoom;
        renderedYaw = nextYaw;
        renderedPitch = nextPitch;
        if (nextZoom != renderedZoom) {
            panX = zoomPan(panX, zoomAnchorX, renderedZoom, nextZoom);
            panY = zoomPan(panY, zoomAnchorY, renderedZoom, nextZoom);
            renderedZoom = nextZoom;
        }
    }

    private void tickFrame() {
        if (frame != null && frame.isDone()) {
            if (!frame.isCompletedExceptionally()) {
                var result = frame.join();
                mesh = result.mesh();
                centerY = result.centerY();
                long uploadStart = WyWFDebug.ENABLED ? System.nanoTime() : 0;
                image.upload(result.width(), result.height(), result.colors());
                gpuImage = false;
                if (WyWFDebug.ENABLED && uploadStart - lastRenderLog >= 1_000_000_000L) {
                    lastRenderLog = uploadStart;
                    WYWFClient.LOGGER.info("[Relief render] dimension={} chunks={}/{} faces={} meshMs={} reusedChunks={} rasterMs={} uploadMs={} frameGapMs={} idleMs={} queueMs={}",
                            dimension.id, ready, caveTiles.length, result.mesh().faces().length / 3,
                            result.meshNanos() / 1_000_000.0, result.reusedChunks(), result.renderNanos() / 1_000_000.0,
                            (System.nanoTime() - uploadStart) / 1_000_000.0,
                            lastUploadedAt == 0 ? 0 : (uploadStart - lastUploadedAt) / 1_000_000.0,
                            lastUploadedAt == 0 ? 0 : Math.max(0, result.queuedAt() - lastUploadedAt) / 1_000_000.0,
                            (result.workerStartedAt() - result.queuedAt()) / 1_000_000.0);
                }
                lastUploadedAt = uploadStart;
                idleBuffers = result.buffers();
                if (result.probe() != null) displayedProbeFaces = idleBuffers.copyProbes(displayedProbeFaces);
                lastProbe = result.probe();
                hasImage = true;
                previousImage = false;
            }
            frame = null;
        }
        long now = System.nanoTime();
        easeCamera(now);
        boolean stillLoading = page < pages.length || !requests.isEmpty();
        long rebuildInterval = Math.clamp(lastMeshNanos * 2, 250_000_000L, 1_200_000_000L);
        boolean rebuildBatch = !stillLoading || ready - meshTiles >= 64 && now - meshStartedAt >= rebuildInterval;
        if (building != null && building.isDone()) {
            if (building.isCompletedExceptionally()) meshDirty = true;
            else {
                preparedMesh = building.join();
                lastMeshNanos = preparedMesh.nanos();
            }
            building = null;
        }
        long revision = regionRevision;
        java.util.function.BooleanSupplier cancelled = () -> closed || revision != regionRevision;
        if (meshDirty && building == null && preparedMesh == null && ready > 0
                && (mesh == null ? ready >= 12 || !stillLoading || hasImage : rebuildBatch)) {
            var tiles = caveTiles.clone();
            int side = chunkSide;
            meshStartedAt = now;
            meshTiles = ready;
            meshDirty = false;
            var builder = meshBuilder;
            building = CompletableFuture.supplyAsync(() -> {
                long start = System.nanoTime();
                var rebuilt = builder.build(tiles, side, dimension, cancelled);
                return new MeshUpdate(rebuilt, System.nanoTime() - start, builder.reusedChunks());
            });
        }
        if (frame != null || mesh == null && preparedMesh == null || mapHeight() <= 0 || width <= 24) return;
        boolean newGeometry = preparedMesh != null;
        boolean canUseGpu = gpu != null && !stillLoading && !meshDirty && building == null;
        if (!dirty && !newGeometry && !(canUseGpu && gpu.uploading())) return;
        var window = Minecraft.getInstance().getWindow();
        double factor = window.getWidth() / (double)Math.max(1, width);
        double pixelWidth = (width - 24) * factor, pixelHeight = mapHeight() * factor;
        double fit = Math.min(1, Math.sqrt(MAX_PIXELS / (pixelWidth * pixelHeight)));
        int w = Math.max(1, (int)Math.round(pixelWidth * fit)), h = Math.max(1, (int)Math.round(pixelHeight * fit));
        var geometry = preparedMesh == null ? mesh : preparedMesh.mesh();
        long meshNanos = preparedMesh == null ? 0 : preparedMesh.nanos();
        int reusedChunks = preparedMesh == null ? 0 : preparedMesh.reusedChunks();
        preparedMesh = null;
        int background = fog;
        float y = caves ? caveY : centerY;
        double bearing = renderedYaw, elevation = renderedPitch, magnification = renderedZoom;
        boolean underRoof = dimension == DimensionSpace.NETHER && caves;
        boolean cameraChanged = dirty;
        dirty = false;
        int span = chunkSide * 16;
        int x = originX, z = originZ;
        double offsetX = panX, offsetY = panY;
        if (canUseGpu) {
            float center = Float.isNaN(y) ? (geometry.minimum() + geometry.maximum()) * .5f : y;
            try {
                if (gpu.render(geometry, w, h, background, center, bearing, elevation, magnification, offsetX, offsetY)) {
                    if (!gpuImage) image.close();
                    gpuImage = hasImage = true;
                    previousImage = false;
                    mesh = geometry;
                    centerY = center;
                    idleBuffers = null;
                    return;
                }
            } catch (RuntimeException error) {
                WYWFClient.LOGGER.warn("GPU relief disabled for this preview", error);
                gpu.close();
                gpu = null;
                if (gpuImage) hasImage = previousImage = gpuImage = false;
            }
        }
        if (!cameraChanged && !newGeometry && hasImage) return;
        int workerLimit = geometry.faces().length < 300_000 || h < 300 ? 1
                : stillLoading || building != null ? Math.min(4, ReliefRaster.MAX_WORKERS) : ReliefRaster.MAX_WORKERS;
        var buffers = idleBuffers == null ? new ReliefRaster.Buffers() : idleBuffers;
        idleBuffers = null;
        long queuedAt = WyWFDebug.ENABLED ? System.nanoTime() : 0;
        frame = CompletableFuture.supplyAsync(() -> {
            long workerStartedAt = WyWFDebug.ENABLED ? System.nanoTime() : 0;
            float center = Float.isNaN(y) ? geometry.faces().length == 0 ? Float.NaN
                    : (geometry.minimum() + geometry.maximum()) * .5f : y;
            float plane = Float.isNaN(center) ? 64 : center;
            var probe = WyWFDebug.ENABLED
                    ? new Probe(w, h, x, z, span, plane, bearing, elevation, magnification, offsetX, offsetY, revision, underRoof)
                    : null;
            long start = WyWFDebug.ENABLED ? System.nanoTime() : 0;
            int[] pixels = ReliefRaster.draw(geometry, w, h, bearing, elevation, magnification,
                    dimension, underRoof, background, plane, span, x, z, null, offsetX, offsetY, buffers, workerLimit, cancelled);
            return new Frame(geometry, center, w, h, pixels, buffers, probe,
                    WyWFDebug.ENABLED ? System.nanoTime() - start : 0, meshNanos, reusedChunks, queuedAt, workerStartedAt);
        });
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float tick) {
        tickFrame();
        MenuBands.draw(g, width, height, mapTop - 4, footer);
        g.centeredText(font, title, width / 2, 9, 0xFFFFFFFF);
        String subtitle = dimension == DimensionSpace.NETHER && caves ? "relief_roofless"
                : "dimension_" + dimension.name().toLowerCase(Locale.ROOT);
        g.centeredText(font, MapTexts.label(subtitle), width / 2, 24, 0xFFFFFFFF);
        g.fill(12, mapTop, width - 12, footer - MAP_FOOT, fog);
        boolean loading = page < pages.length || !requests.isEmpty();
        if (hasImage) {
            if (gpuImage) gpu.draw(g, 12, mapTop, width - 24, mapHeight());
            else image.draw(g, 12, mapTop, width - 24, mapHeight());
        }
        boolean unavailable = !loading && ready == 0;
        if (!hasImage || previousImage || loading || unavailable) {
            String key = unavailable ? (caves ? "relief_cave_unavailable" : "relief_surface_unavailable")
                    : previousImage ? "relief_previous"
                    : hasImage ? "relief_loading_chunks" : caves ? "relief_cave_wait" : "relief_wait";
            var label = MapTexts.label(key);
            int status = hasImage ? mapTop + 6 : (footer + 12) / 2;
            if (hasImage) {
                int half = font.width(label) / 2 + 5;
                g.fill(width / 2 - half, status - 3, width / 2 + half, status + font.lineHeight + 3, 0xB0202020);
            }
            g.centeredText(font, label, width / 2, status,
                    hasImage || dimension != DimensionSpace.OVERWORLD || caves ? 0xFFFFFFFF : 0xFF29323A);
        }
        String hint = MapTexts.text("relief_controls") + " · " + MapTexts.text("relief_tiles", ready, caveTiles.length);
        g.centeredText(font, font.plainSubstrByWidth(hint, Math.max(1, width - 24)), width / 2, footer - 15, 0xFFFFFFFF);
        super.extractRenderState(g, mouseX, mouseY, tick);
        dimensions.drawPopup(g, mouseX, mouseY, DIMENSION_LIST_HEIGHT, 1);
    }

    private boolean inside(double x, double y) { return x >= 12 && x < width - 12 && y >= mapTop && y < footer - MAP_FOOT; }

    private void logProbe(double mouseX, double mouseY) {
        var probe = lastProbe;
        if (probe == null) return;
        double u = (mouseX - 12) / (width - 24), v = (mouseY - mapTop) / mapHeight();
        if (probe.revision() != regionRevision || displayedProbeFaces == null || mesh == null) {
            WYWFClient.LOGGER.info("[Relief probe] Waiting for a frame from the current region");
            return;
        }
        int px = Math.clamp((int)(u * probe.width()), 0, probe.width() - 1);
        int py = Math.clamp((int)(v * probe.height()), 0, probe.height() - 1);
        int face = displayedProbeFaces[py * probe.width() + px];
        if (face == ReliefRaster.PROBE_EDGE_FOG) {
            WYWFClient.LOGGER.info("[Relief probe] seed={} dimension={} hit=edge-fog reason=map-boundary",
                    source.result().seed, dimension.id);
            return;
        }
        if (face >= 0) {
            int cell = mesh.faces()[face] / 6;
            int x = probe.x() + cell % mesh.side(), z = probe.z() + cell / mesh.side();
            int y = mesh.faces()[face + 1];
            String block = sampledBlock(x, y, z);
            int color = mesh.faces()[face + 2];
            if (color == ReliefTile.CAVE_SHADOW) {
                WYWFClient.LOGGER.info("[Relief probe] seed={} dimension={} hit=cave-shadow xyz=({},{},{}) reason=surface-sample-limit",
                        source.result().seed, dimension.id, x, y, z);
                return;
            }
            int material = color & ReliefTile.FACE_TYPE;
            WYWFClient.LOGGER.info("[Relief probe] seed={} dimension={} hit=block block={} xyz=({},{},{}) face={} layer={}",
                    source.result().seed, dimension.id, block == null ? "unavailable" : block, x, y, z, mesh.faces()[face] % 6,
                                        material == ReliefTile.WATER_FACE ? "water" : ReliefTile.plant(material) ? "plant"
                            : ReliefTile.shaped(material) ? "thin-block" : "terrain");
            return;
        }

        double scale = Math.min(probe.width(), probe.height()) * .82 * probe.zoom() / probe.span();
        double across = (px + .5 - probe.width() * (.5 + probe.panX())) / scale;
        double forwardAtCenter = (py + .5 - probe.height() * (.5 + probe.panY())) / (scale * Math.sin(probe.pitch()));
        double slope = (probe.underRoof() ? .46 : 1) / Math.tan(probe.pitch());
        double cosine = Math.cos(probe.yaw()), sine = Math.sin(probe.yaw());
        int centerX = (int)Math.floor(probe.x() + probe.span() * .5 + across * cosine + forwardAtCenter * sine);
        int centerZ = (int)Math.floor(probe.z() + probe.span() * .5 - across * sine + forwardAtCenter * cosine);
        var candidates = new java.util.LinkedHashSet<String>();
        if (mesh.faces().length != 0) {
            int firstY = Math.min(512, mesh.maximum() + 24);
            int lastY = Math.max(-128, mesh.minimum() - 24);
            for (double height = firstY; height >= lastY && candidates.size() < 6; height -= .25) {
                double forward = forwardAtCenter + (height - probe.y()) * slope;
                int x = (int)Math.floor(probe.x() + probe.span() * .5 + across * cosine + forward * sine);
                int z = (int)Math.floor(probe.z() + probe.span() * .5 - across * sine + forward * cosine);
                int y = (int)Math.floor(height);
                String block = sampledBlock(x, y, z);
                if (block != null) candidates.add(block + "@(" + x + "," + y + "," + z + ")"
                        + "/meshFaces=" + probeMeshFaces(x, y, z));
            }
        }
        WYWFClient.LOGGER.info("[Relief probe] seed={} dimension={} hit=background block=none reason=no-rasterized-face sampledNearRay={} traceXZ={},{}",
                source.result().seed, dimension.id, candidates, centerX, centerZ);
    }

    private String probeMeshFaces(int x, int y, int z) {
        int localX = x - originX, localZ = z - originZ;
        int chunk = (localZ >> 4) * chunkSide + (localX >> 4);
        int cell = localZ * mesh.side() + localX;
        int[] offsets = mesh.chunkOffsets(), faces = mesh.faces();
        var directions = new java.util.ArrayList<Integer>(6);
        for (int i = offsets[chunk]; i < offsets[chunk + 1]; i += 3) {
            if (faces[i] / 6 == cell && faces[i + 1] == y) directions.add(faces[i] % 6);
        }
        return directions.toString();
    }

    private String sampledBlock(int x, int y, int z) {
        int cx = (x - originX) >> 4, cz = (z - originZ) >> 4;
        if (cx < 0 || cz < 0 || cx >= chunkSide || cz >= chunkSide) return null;
        var tile = caveTiles[cz * chunkSide + cx];
        return tile == null ? null : tile.blockAt((z & 15) * 16 + (x & 15), y);
    }

    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.isEscape() && dimensions.isOpen()) {
            dimensions.close();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public boolean mouseClicked(MouseButtonEvent e, boolean keyboard) {
        if (dimensions.isOpen()) {
            float x = (float)e.x(), y = (float)e.y();
            if (dimensions.popupHit(x, y, DIMENSION_LIST_HEIGHT)) {
                if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) dimensions.handlePopupClick(x, y, DIMENSION_LIST_HEIGHT);
                return true;
            }
            dimensions.close();
            if (dimensions.isMouseOver(x, y)) return true;
        }
        if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT && inside(e.x(), e.y())) {
            panning = true;
            probeClickAt = 0;
            return true;
        }
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT && inside(e.x(), e.y())) {
            dragging = true;
            probeDragged = false;
            return true;
        }
        probeClickAt = 0;
        return super.mouseClicked(e, keyboard);
    }

    @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (panning && e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            panX = Math.clamp(panX + dx / Math.max(1, width - 24), -zoom, zoom);
            panY = Math.clamp(panY + dy / Math.max(1, mapHeight()), -zoom, zoom);
            dirty = true;
            return true;
        }
        if (!dragging || e.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseDragged(e, dx, dy);
        if (dx != 0 || dy != 0) {
            probeDragged = true;
            probeClickAt = 0;
        }
        yaw = Math.IEEEremainder(yaw + dx * .012, Math.TAU);
        pitch = Math.clamp(pitch + dy * .012, MIN_PITCH, MAX_PITCH);
        dirty = true;
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent e) {
        if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT && panning) {
            panning = false;
            return true;
        }
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT && dragging) {
            dragging = false;
            if (WyWFDebug.ENABLED && !probeDragged && inside(e.x(), e.y())) {
                long now = System.nanoTime();
                if (probeClickAt != 0 && now - probeClickAt <= 300_000_000L
                        && Math.hypot(e.x() - probeClickX, e.y() - probeClickY) <= 4) {
                    probeClickAt = 0;
                    logProbe(e.x(), e.y());
                } else {
                    probeClickAt = now;
                    probeClickX = e.x();
                    probeClickY = e.y();
                }
            } else probeClickAt = 0;
            return true;
        }
        return super.mouseReleased(e);
    }

    @Override public boolean mouseScrolled(double x, double y, double h, double v) {
        if (!inside(x, y) || v == 0) return super.mouseScrolled(x, y, h, v);
        double next = Math.clamp(zoom * Math.pow(1.17, v), .45, 20);
        if (next == zoom) return true;
        zoomAnchorX = (x - 12) / (width - 24) - .5;
        zoomAnchorY = (y - mapTop) / mapHeight() - .5;
        zoom = next;
        dirty = true;
        return true;
    }

    static double zoomPan(double pan, double anchor, double oldZoom, double newZoom) {
        return Math.clamp(anchor + (pan - anchor) * newZoom / oldZoom, -newZoom, newZoom);
    }

    private void switchDimension(DimensionSpace destination) {
        if (pendingRadius) ReliefRadius.save(radius, false);
        minecraft.setScreenAndShow(new ReliefScreen(parent, source, destination, focusX, focusZ));
    }

    @Override public void onClose() {
        if (pendingRadius) ReliefRadius.save(radius, false);
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override public void removed() {
        closed = true;
        cancelWork();
        partialPages.clear();
        meshBuilder = null;
        mesh = null;
        caveTiles = null;
        pages = null;
        idleBuffers = null;
        lastProbe = null;
        displayedProbeFaces = null;
        source.pauseSurface();
        image.close();
        if (gpu != null) {
            gpu.close();
            gpu = null;
        }
    }
}
