package com.wywf.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wywf.core.DimensionSpace;
import com.wywf.core.MapBackground;
import com.wywf.core.WyWFDebug;
import com.wywf.WYWFClient;
import com.wywf.verify.MinecraftReliefFog;
import com.wywf.verify.PreviewWorld;
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
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

final class ReliefScreen extends Screen {
    private static final int HEADER = 54, CAVE_HEADER = 64, MAP_FOOT = 21;
    private static final int DIMENSION_LIST_HEIGHT = 40;
    private static final long OUTLINE_DURATION = 1_000_000_000L;
    private static final int MAX_PIXELS = 2_100_000;
    private static final int MAX_EXACT_RADIUS = ReliefRadius.MAX;
    private static final double MIN_PITCH = Math.toRadians(10), MAX_PITCH = Math.PI / 2;
    private final Screen parent;
    private final WorldMapSource source;
    private final DimensionSpace dimension;
    private final int launchX, launchZ;
    private int focusX, focusZ;
    private final ReliefTargets targets;
    private ReliefTargets.Pin pendingTarget;
    private final ArrayDeque<CameraView> jumpHistory = new ArrayDeque<>();
    private ReliefRaster.Outline outline, renderedOutline;
    private long outlineStartedAt;
    private MapContextMenu context;
    private boolean panDragged;
    private WorldPoint rotationPivot;
    private final MapImage image = new MapImage();
    private final MapImage outlineImage = new MapImage();
    private ReliefGpu gpu;
    private boolean gpuImage;
    private final PageLoads pageLoads = new PageLoads();
    private record Partial(long revision, List<ReliefTile> tiles) {}
    private final java.util.Queue<Partial> partialPages = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile long regionRevision;
    private long viewportRevision;
    private CompletableFuture<Frame> frame;
    private CompletableFuture<MeshUpdate> building;
    private MeshUpdate preparedMesh;
    private record MeshUpdate(ReliefMesh mesh, long nanos, int reusedChunks) {}
    private ReliefMesh.Builder meshBuilder;
    private record Frame(ReliefMesh mesh, float centerY, int width, int height, int[] colors, ReliefRaster.Buffers buffers,
                         Probe probe, ReliefRaster.Outline outline, long renderNanos, long meshNanos, int reusedChunks,
                         long queuedAt, long workerStartedAt, long viewportRevision) {}
    private record Probe(int width, int height, int x, int z, int span, float y,
                         double yaw, double pitch, double zoom, double panX, double panY, long revision, boolean underRoof) {
        double pixelsPerBlock() { return Math.min(width, height) * .82 * zoom / span; }
    }
    private record CameraView(int focusX, int focusZ, boolean caves, int caveY,
                              double yaw, double pitch, double zoom, double panX, double panY) {}
    record WorldPoint(double x, double y, double z) {}
    record Offset(double x, double y) {}
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
    private int caveY, fog, sampledFog, ready;
    private long lastChunkAt;
    private boolean chunkWarning;
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
        focusX = launchX = x;
        focusZ = launchZ = z;
        targets = new ReliefTargets(source, dimension);
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
        viewportRevision++;
        mapTop = (caves ? CAVE_HEADER : HEADER) + 4;
        footer = height - 36;
        var destinations = java.util.Arrays.stream(DimensionSpace.values()).filter(value -> value != dimension).toList();
        int switchWidth = Math.min(108, width / 3);
        dimensions = addRenderableWidget(new ConstructorDropList(12, 7, switchWidth, MapTexts.text("relief_switch_to"),
                destinations.stream().map(value -> MapTexts.text("dimension_" + value.name().toLowerCase(Locale.ROOT))).toList(),
                -1, font, index -> switchDimension(destinations.get(index)),
                list -> {}));
        String mode = caves ? "relief_surface" : dimension == DimensionSpace.OVERWORLD ? "caves" : "relief_slice";
        if (dimension != DimensionSpace.END) addRenderableWidget(MenuButton.builder(MapTexts.label(mode), b -> {
            caves = !caves;
            context = null;
            pendingTarget = null;
            clearOutline();
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
        addRenderableWidget(MenuButton.builder(MapTexts.label("reset"), b -> resetCamera())
                .bounds(width / 2 - w - 4, footer + 8, w, 20).build());
        addRenderableWidget(MenuButton.builder(ResultTexts.label("back"), b -> onClose())
                .bounds(width / 2 + 4, footer + 8, w, 20).build());
        dirty = true;
    }

    private void resetCamera() {
        yaw = Math.PI / 4;
        pitch = dimension == DimensionSpace.NETHER && caves ? .68 : caves ? .88 : .69;
        zoom = dimension == DimensionSpace.NETHER && caves ? 1.45 : 1;
        panX = panY = 0;
        zoomAnchorX = zoomAnchorY = 0;
        rotationPivot = null;
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
        int previousX = originX, previousZ = originZ;
        int detailRadius = Math.min(MAX_EXACT_RADIUS, radius);
        chunkSide = 2 * detailRadius;
        originX = ((focusX >> 4) - chunkSide / 2) << 4;
        originZ = ((focusZ >> 4) - chunkSide / 2) << 4;
        caveTiles = new ReliefTile[chunkSide * chunkSide];
        int pageSide = Math.ceilDiv(chunkSide * 16, ReliefBlocks.SIZE);
        var offsets = new ArrayList<PageOffset>();
        for (int z = 0; z < pageSide; z++) {
            for (int x = 0; x < pageSide; x++) offsets.add(new PageOffset(x, z));
        }
        double focusPageX = (focusX - originX) / (double)ReliefBlocks.SIZE;
        double focusPageZ = (focusZ - originZ) / (double)ReliefBlocks.SIZE;
        offsets.sort(Comparator.comparingDouble(p -> Math.hypot(p.x() + .5 - focusPageX, p.z() + .5 - focusPageZ)));
        pageLoads.enqueue(offsets);
        ready = meshTiles = 0;
        lastChunkAt = System.nanoTime();
        chunkWarning = false;
        lastMeshNanos = lastUploadedAt = 0;
        if (hasRegion && java.util.Objects.equals(loadedCut, cut)) {
            int shiftX = (previousX - originX) >> 4;
            int shiftZ = (previousZ - originZ) >> 4;
            for (int z = 0; z < previousSide; z++) for (int x = 0; x < previousSide; x++) {
                int destX = x + shiftX, destZ = z + shiftZ;
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
        centerY = Float.NaN;
        hasRegion = true;
        loadedCut = cut;
        mesh = null;
        dirty = meshDirty = true;
        previousImage = hasImage;
        requestAt = 0;
    }

    private void cancelWork() {
        pageLoads.clear();
        if (frame != null) frame.cancel(false);
        frame = null;
        if (building != null) building.cancel(false);
        building = null;
        preparedMesh = null;
    }

    @Override public void tick() {
        if (closed) return;
        if (!caves) targets.tick();
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
        long now = System.nanoTime();
        if (now >= requestAt) {
            pageLoads.tick(now, offset -> pageLoaded(caveTiles, chunkSide, offset.x(), offset.z()),
                    this::requestPage, this::addTiles, this::reportPageFailure);
        }
        updateFog();
        if (pendingRadius && !pageLoads.loading()
                && !meshDirty && frame == null && building == null && preparedMesh == null && hasImage) {
            pendingRadius = false;
            ReliefRadius.save(radius, false);
        }
    }

    private CompletableFuture<List<ReliefTile>> requestPage(PageOffset offset) {
        long revision = regionRevision;
        var request = reliefRequest(offset);
        var alreadyShown = new java.util.HashSet<Long>();
        int pageSide = ReliefBlocks.SIZE / 16;
        for (int dz = 0; dz < pageSide; dz++) {
            for (int dx = 0; dx < pageSide; dx++) {
                int x = offset.x() * pageSide + dx, z = offset.z() * pageSide + dz;
                if (x >= chunkSide || z >= chunkSide || caveTiles[z * chunkSide + x] != null)
                    alreadyShown.add(com.wywf.verify.SurfaceCache.key(request.x() + dx * 16, request.z() + dz * 16));
            }
        }
        return source.reliefPage(request, java.util.Set.copyOf(alreadyShown), tiles -> {
            var partialPage = new Partial(revision, tiles);
            partialPages.add(partialPage);
            if (closed || revision != regionRevision) partialPages.remove(partialPage);
        });
    }

    private ReliefRequest reliefRequest(PageOffset offset) {
        return new ReliefRequest(dimension, originX + offset.x() * ReliefBlocks.SIZE,
                originZ + offset.z() * ReliefBlocks.SIZE, loadedCut, new BlockPos(focusX, caveY, focusZ));
    }

    private void reportPageFailure(PageOffset offset, Throwable error) {
        chunkWarning = true;
        var request = reliefRequest(offset);
        WYWFClient.LOGGER.warn("[Relief loading] seed={} dimension={} page=({}, {}) cut={} chunks={}/{}",
                source.result().seed, dimension.id, request.x(), request.z(), loadedCut, ready, caveTiles.length, error);
    }

    record PageOffset(int x, int z) {}

    static final class PageLoads {
        private static final int LIMIT = 3;
        static final long TIMEOUT = TimeUnit.SECONDS.toNanos(120);
        private static final long MEMORY_RETRY_DELAY = TimeUnit.MILLISECONDS.toNanos(750);
        private record Pending(PageOffset offset, int attempts) {}
        private record Active(Pending page, long startedAt, CompletableFuture<List<ReliefTile>> future) {}

        private final ArrayDeque<Pending> pending = new ArrayDeque<>();
        private final ArrayList<Active> active = new ArrayList<>(LIMIT);
        private long resumeAt = Long.MIN_VALUE;

        void enqueue(List<PageOffset> pages) {
            for (var offset : pages) pending.addLast(new Pending(offset, 0));
        }

        boolean loading() {
            return !pending.isEmpty() || !active.isEmpty();
        }

        void clear() {
            for (var load : active) load.future().cancel(false);
            active.clear();
            pending.clear();
            resumeAt = Long.MIN_VALUE;
        }

        void tick(long now, Predicate<PageOffset> loaded, Function<PageOffset, CompletableFuture<List<ReliefTile>>> request,
                  Consumer<List<ReliefTile>> accept, BiConsumer<PageOffset, Throwable> report) {
            for (var iterator = active.iterator(); iterator.hasNext();) {
                var load = iterator.next();
                var future = load.future();
                if (!future.isDone() && now - load.startedAt() < TIMEOUT) continue;
                iterator.remove();

                Throwable failure = null;
                if (future.isDone()) {
                    try {
                        accept.accept(future.join());
                    } catch (CompletionException error) {
                        failure = error.getCause();
                    } catch (java.util.concurrent.CancellationException error) {
                        failure = error;
                    }
                } else {
                    future.cancel(false);
                    failure = new TimeoutException("Relief page waited 120 seconds without completing");
                }

                var page = load.page();
                if (loaded.test(page.offset())) continue;
                if (failure == null) failure = new IllegalStateException("Relief page returned incomplete chunk data");
                boolean lowHeap = failure instanceof PreviewWorld.BudgetExceeded limit && limit.heapPressure();
                if (lowHeap && page.attempts() < 3) {
                    resumeAt = Math.max(resumeAt, now + MEMORY_RETRY_DELAY * (1L << page.attempts()));
                    pending.addLast(new Pending(page.offset(), page.attempts() + 1));
                } else {
                    report.accept(page.offset(), failure);
                    if (page.attempts() == 0) pending.addLast(new Pending(page.offset(), 1));
                }
            }

            if (now < resumeAt) return;
            while (active.size() < LIMIT && !pending.isEmpty()) {
                var page = pending.removeFirst();
                if (!loaded.test(page.offset())) active.add(new Active(page, now, request.apply(page.offset())));
            }
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
            if (caveTiles[index] == null) {
                ready++;
                lastChunkAt = System.nanoTime();
            }
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
        if (rotationPivot != null && (nextYaw != renderedYaw || nextPitch != renderedPitch)) {
            var offset = centerOffset(rotationPivot, nextYaw, nextPitch, nextZoom);
            panX = offset.x();
            panY = offset.y();
        }
        renderedYaw = nextYaw;
        renderedPitch = nextPitch;
        if (!dragging && nextYaw == yaw && nextPitch == pitch) rotationPivot = null;
        if (nextZoom != renderedZoom) {
            panX = zoomPan(panX, zoomAnchorX, renderedZoom, nextZoom);
            panY = zoomPan(panY, zoomAnchorY, renderedZoom, nextZoom);
            renderedZoom = nextZoom;
        }
    }

    private void tickFrame() {
        if (outline != null && outlineOpacity(System.nanoTime() - outlineStartedAt) == 0) clearOutline();
        if (frame != null && frame.isDone()) {
            Frame result = frame.isCompletedExceptionally() ? null : frame.join();
            if (result != null && result.probe().revision() == regionRevision
                    && result.viewportRevision() == viewportRevision) {
                mesh = result.mesh();
                centerY = result.centerY();
                long uploadStart = WyWFDebug.ENABLED ? System.nanoTime() : 0;
                image.upload(result.width(), result.height(), result.colors());
                renderedOutline = outline == result.outline() ? outline : null;
                if (renderedOutline != null)
                    outlineImage.upload(result.width(), result.height(), result.buffers().outlinePixels());
                else outlineImage.close();
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
                if (WyWFDebug.ENABLED) displayedProbeFaces = idleBuffers.copyProbes(displayedProbeFaces);
                lastProbe = result.probe();
                hasImage = true;
                previousImage = false;
            } else {
                dirty = true;
                meshDirty = true;
                if (result != null) idleBuffers = result.buffers();
            }
            frame = null;
        }
        long now = System.nanoTime();
        if (pendingTarget != null && loaded(pendingTarget.x(), pendingTarget.z()) && Float.isFinite(centerY)) {
            moveToLoaded(pendingTarget);
            pendingTarget = null;
        }
        easeCamera(now);
        boolean stillLoading = pageLoads.loading();
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
            building = MapBackground.cpuResult(() -> {
                long start = System.nanoTime();
                var rebuilt = builder.build(tiles, side, dimension, cancelled);
                return new MeshUpdate(rebuilt, System.nanoTime() - start, builder.reusedChunks());
            });
        }
        if (frame != null || mesh == null && preparedMesh == null || mapHeight() <= 0 || width <= 24) return;
        boolean newGeometry = preparedMesh != null;
        boolean canUseGpu = gpu != null && outline == null && !stillLoading && !meshDirty && building == null;
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
                    lastProbe = new Probe(w, h, x, z, span, center, bearing, elevation, magnification, offsetX, offsetY,
                            revision, underRoof);
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
        var highlight = outline;
        long viewport = viewportRevision;
        frame = MapBackground.raster(workerLimit, granted -> {
            long workerStartedAt = WyWFDebug.ENABLED ? System.nanoTime() : 0;
            float center = Float.isNaN(y) ? geometry.faces().length == 0 ? Float.NaN
                    : (geometry.minimum() + geometry.maximum()) * .5f : y;
            float plane = Float.isNaN(center) ? 64 : center;
            var probe = new Probe(w, h, x, z, span, plane, bearing, elevation, magnification, offsetX, offsetY,
                    revision, underRoof);
            long start = WyWFDebug.ENABLED ? System.nanoTime() : 0;
            int[] pixels = ReliefRaster.draw(geometry, w, h, bearing, elevation, magnification,
                    dimension, underRoof, background, plane, span, x, z, null, offsetX, offsetY, buffers, granted, highlight, cancelled);
            return new Frame(geometry, center, w, h, pixels, buffers, probe, highlight,
                    WyWFDebug.ENABLED ? System.nanoTime() - start : 0, meshNanos, reusedChunks, queuedAt, workerStartedAt, viewport);
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
        boolean loading = pageLoads.loading();
        if (hasImage) {
            if (gpuImage) gpu.draw(g, 12, mapTop, width - 24, mapHeight());
            else {
                image.draw(g, 12, mapTop, width - 24, mapHeight());
                if (outline != null && renderedOutline == outline) {
                    int alpha = Math.round(255 * outlineOpacity(System.nanoTime() - outlineStartedAt));
                    if (alpha > 0)
                        outlineImage.drawTinted(g, 12, mapTop, width - 24, mapHeight(), (alpha << 24) | 0xFFFFFF);
                }
            }
        }
        int missing = caveTiles.length - ready;
        boolean failed = !loading && missing > 0;
        boolean warning = false;
        long idleSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - lastChunkAt);
        Component label = null;
        if (failed) {
            label = MapTexts.label("relief_chunks_failed", missing);
        } else if (loading && idleSeconds >= 5) {
            warning = true;
            label = MapTexts.label("relief_chunks_waiting", idleSeconds);
        } else if (loading && chunkWarning) {
            warning = true;
            label = MapTexts.label("relief_chunks_warning");
        } else if (!hasImage || previousImage || loading) {
            String key = previousImage ? "relief_previous"
                    : hasImage ? "relief_loading_chunks" : caves ? "relief_cave_wait" : "relief_wait";
            label = MapTexts.label(key);
        }
        if (label != null) {
            int status = hasImage ? mapTop + 6 : (footer + 12) / 2;
            var message = font.plainSubstrByWidth(label.getString(), Math.max(1, width - 36));
            if (hasImage || warning || failed) {
                int half = font.width(message) / 2 + 5;
                g.fill(width / 2 - half, status - 3, width / 2 + half, status + font.lineHeight + 3, 0xE0202020);
            }
            int color = failed ? 0xFFFF9B96 : warning ? 0xFFFFD27D
                    : hasImage || dimension != DimensionSpace.OVERWORLD || caves ? 0xFFFFFFFF : 0xFF29323A;
            g.centeredText(font, message, width / 2, status, color);
        }
        drawPins(g);
        String hint = MapTexts.text("relief_controls") + " · " + MapTexts.text("relief_menu_hint")
                + " · " + MapTexts.text("relief_tiles", ready, caveTiles.length);
        g.centeredText(font, font.plainSubstrByWidth(hint, Math.max(1, width - 24)), width / 2, footer - 15, 0xFFFFFFFF);
        super.extractRenderState(g, mouseX, mouseY, tick);
        dimensions.drawPopup(g, mouseX, mouseY, DIMENSION_LIST_HEIGHT, 1);
        if (context != null) context.draw(g, mouseX, mouseY);
    }

    private boolean inside(double x, double y) { return x >= 12 && x < width - 12 && y >= mapTop && y < footer - MAP_FOOT; }

    static Offset project(WorldPoint point, int originX, int originZ, int span, float centerY,
                          double yaw, double pitch, double heightScale) {
        double x = point.x() - originX - span / 2.0, z = point.z() - originZ - span / 2.0;
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        return new Offset(x * cos - z * sin,
                (x * sin + z * cos) * Math.sin(pitch) - (point.y() - centerY) * Math.cos(pitch) * heightScale);
    }

    private Offset centerOffset(WorldPoint point, double angle, double elevation, double magnification) {
        float plane = caves ? caveY : Float.isFinite(centerY) ? centerY : 64;
        int w = Math.max(1, width - 24), h = Math.max(1, mapHeight()), span = chunkSide * 16;
        double scale = Math.min(w, h) * .82 * magnification / span;
        var offset = project(point, originX, originZ, span, plane, angle, elevation, dimension == DimensionSpace.NETHER && caves ? .46 : 1);
        return new Offset(-offset.x() * scale / w, -offset.y() * scale / h);
    }

    private Probe camera() {
        if (lastProbe != null && lastProbe.revision() == regionRevision) return lastProbe;
        float plane = caves ? caveY : Float.isFinite(centerY) ? centerY : 64;
        return new Probe(Math.max(1, width - 24), Math.max(1, mapHeight()), originX, originZ, chunkSide * 16,
                plane, renderedYaw, renderedPitch, renderedZoom, panX, panY, regionRevision,
                dimension == DimensionSpace.NETHER && caves);
    }

    private WorldPoint pointAt(double mouseX, double mouseY) {
        return pointAt(mouseX, mouseY, true);
    }

    private WorldPoint pointAt(double mouseX, double mouseY, boolean traceSurface) {
        var view = camera();
        double px = (mouseX - 12) * view.width() / Math.max(1, width - 24);
        double py = (mouseY - mapTop) * view.height() / Math.max(1, mapHeight());
        double across = (px - view.width() * (.5 + view.panX())) / view.pixelsPerBlock();
        double forward = (py - view.height() * (.5 + view.panY())) / (view.pixelsPerBlock() * Math.sin(view.pitch()));
        double slope = (view.underRoof() ? .46 : 1) / Math.tan(view.pitch());
        double cos = Math.cos(view.yaw()), sin = Math.sin(view.yaw());
        double originX = view.x() + view.span() / 2.0, originZ = view.z() + view.span() / 2.0;
        WorldPoint groundPlane = new WorldPoint(originX + across * cos + forward * sin, view.y(),
                originZ - across * sin + forward * cos);
        if (!traceSurface || mesh == null || mesh.faces().length == 0) return groundPlane;

        double stride = .34 / Math.max(1, Math.abs(slope));
        for (double y = mesh.maximum() + 1; y >= mesh.minimum(); y -= stride) {
            double distance = forward + (y - view.y()) * slope;
            double x = originX + across * cos + distance * sin, z = originZ - across * sin + distance * cos;
            int bx = (int)Math.floor(x), by = (int)Math.floor(y), bz = (int)Math.floor(z);
            var tile = tileAt(bx, bz);
            if (tile == null) continue;
            int col = (bz & 15) * 16 + (bx & 15);
            if (!caves && tile.ground() != null && by < tile.ground()[col]
                    && tile.ground()[col] - by > 12) continue;
            int color = tile.voxel(col, by), material = color & ReliefTile.FACE_TYPE;
            if (color == 0 || color == ReliefTile.COVERED_WATER || ReliefTile.plant(material)) continue;
            double localY = y - by;
            if (material == ReliefTile.SHAPED_BLOCK && com.wywf.verify.ReliefShapes.get(color).boxes().stream()
                    .noneMatch(box -> box.contains(x - bx, localY, z - bz))) continue;
            return new WorldPoint(x, y, z);
        }
        return groundPlane;
    }

    private ReliefTile tileAt(int x, int z) {
        int cx = (x - originX) >> 4, cz = (z - originZ) >> 4;
        if (cx < 0 || cz < 0 || cx >= chunkSide || cz >= chunkSide) return null;
        return caveTiles[cz * chunkSide + cx];
    }

    private boolean loaded(int x, int z) { return tileAt(x, z) != null; }

    private int pinHeight(ReliefTargets.Pin pin) {
        if (pin.y() != Integer.MIN_VALUE) return pin.y();
        if (pin.structure() != null) {
            int roof = Integer.MIN_VALUE;
            for (var part : pin.structure().bounds()) {
                if (pin.x() >= part.minX() && pin.x() <= part.maxX() && pin.z() >= part.minZ() && pin.z() <= part.maxZ())
                    roof = Math.max(roof, part.maxY());
            }
            if (roof != Integer.MIN_VALUE) return roof;
        }
        var tile = tileAt(pin.x(), pin.z());
        if (tile != null) {
            int column = (pin.z() & 15) * 16 + (pin.x() & 15);
            if (tile.colors()[column] != 0) return tile.heights()[column];
        }
        return caves ? caveY : Float.isFinite(centerY) ? (int)centerY : 64;
    }

    private void drawPins(GuiGraphicsExtractor graphics) {
        if (caves || !hasImage || previousImage || lastProbe == null || lastProbe.revision() != regionRevision) return;
        var view = lastProbe;
        boolean labels = view.pixelsPerBlock() <= 5;
        graphics.enableScissor(12, mapTop, width - 12, mapTop + mapHeight());
        if (labels) {
            var point = pointAt(width / 2.0, mapTop + mapHeight() / 2.0, false);
            var nearby = targets.nearestBiome(point.x(), point.z());
            if (nearby != null) drawPin(graphics, view, nearby, true);
            for (var structure : targets.structures()) drawPin(graphics, view, structure, true);
        }
        if (targets.spawn() != null) drawPin(graphics, view, targets.spawn(), labels);
        graphics.disableScissor();
    }

    private void drawPin(GuiGraphicsExtractor graphics, Probe view, ReliefTargets.Pin pin, boolean label) {
        if (!loaded(pin.x(), pin.z())) return;
        var point = new WorldPoint(pin.x() + .5, pinHeight(pin) + 1, pin.z() + .5);
        var local = project(point, view.x(), view.z(), view.span(), view.y(), view.yaw(), view.pitch(), view.underRoof() ? .46 : 1);
        int sx = 12 + (int)Math.round((.5 + view.panX() + local.x() * view.pixelsPerBlock() / view.width()) * (width - 24));
        int sy = mapTop + (int)Math.round((.5 + view.panY() + local.y() * view.pixelsPerBlock() / view.height()) * mapHeight());
        if (!inside(sx, sy)) return;
        int tint = pin.spawn() ? 0xFFFFFFFF : pin.structure() == null ? 0xFF9BE8A5 : 0xFFFFD27D;
        if (pin.spawn()) {
            graphics.fill(sx - 4, sy, sx + 5, sy + 1, tint);
            graphics.fill(sx, sy - 4, sx + 1, sy + 5, tint);
        } else {
            graphics.fill(sx - 3, sy - 3, sx + 4, sy + 4, 0xFF24252A);
            graphics.fill(sx - 2, sy - 2, sx + 3, sy + 3, tint);
        }
        if (!label) return;
        int half = font.width(pin.name()) / 2 + 3;
        graphics.fill(sx - half, sy - font.lineHeight - 8, sx + half, sy - 6, 0xC0202020);
        graphics.centeredText(font, pin.name(), sx, sy - font.lineHeight - 7, tint);
    }

    private void showContext(double mouseX, double mouseY) {
        var point = pointAt(mouseX, mouseY);
        var entries = new ArrayList<MapContextMenu.Entry>();
        entries.add(new MapContextMenu.Entry(MapTexts.label("relief_go_back"), this::returnToView, !jumpHistory.isEmpty()));
        entries.add(new MapContextMenu.Entry(MapTexts.label("map_copy_coordinates"), () -> {
            minecraft.keyboardHandler.setClipboard((int)Math.floor(point.x()) + " "
                    + (int)Math.floor(point.y()) + " " + (int)Math.floor(point.z()));
            context = null;
        }));
        entries.add(new MapContextMenu.Entry(MapTexts.label("center_here"), () -> {
            context = null;
            centerOn(point);
        }));
        if (!caves) {
            var biome = targets.nearestBiome(point.x(), point.z());
            if (biome != null) entries.add(new MapContextMenu.Entry(MapTexts.label("relief_jump_biome", biome.name()), () -> jump(biome)));
            for (var structure : targets.structures()) {
                var text = MapTexts.label("relief_jump_structure", structure.name()).copy()
                        .append(" (" + structure.x() + ", " + structure.z() + ")");
                entries.add(new MapContextMenu.Entry(text, () -> jump(structure)));
            }
            var spawn = targets.spawn();
            if (spawn != null) entries.add(new MapContextMenu.Entry(MapTexts.label("relief_jump_spawn"), () -> jump(spawn)));
        }
        context = new MapContextMenu(font, width, height, mouseX, mouseY, entries);
    }

    private void centerOn(WorldPoint point) {
        rotationPivot = null;
        var offset = centerOffset(point, renderedYaw, renderedPitch, renderedZoom);
        panX = offset.x();
        panY = offset.y();
        dirty = true;
    }

    private void jump(ReliefTargets.Pin pin) {
        var view = new CameraView(focusX, focusZ, caves, caveY, renderedYaw, renderedPitch, renderedZoom, panX, panY);
        if ((jumpHistory.isEmpty() || pendingTarget == null && !previousImage) && !view.equals(jumpHistory.peekLast())) {
            if (jumpHistory.size() == 8) jumpHistory.removeFirst();
            jumpHistory.addLast(view);
        }
        context = null;
        rotationPivot = null;
        if (loaded(pin.x(), pin.z()) && mostlyLoaded(pin.structure())) {
            moveToLoaded(pin);
            return;
        }
        clearOutline();
        focusX = pin.x();
        focusZ = pin.z();
        pendingTarget = pin;
        panX = panY = 0;
        resetSlice();
    }

    private void returnToView() {
        var view = jumpHistory.pollLast();
        if (view == null) return;
        context = null;
        pendingTarget = null;
        rotationPivot = null;
        clearOutline();
        boolean modeChanged = caves != view.caves();
        boolean regionChanged = focusX != view.focusX() || focusZ != view.focusZ()
                || modeChanged || caves && caveY != view.caveY();
        focusX = view.focusX();
        focusZ = view.focusZ();
        caves = view.caves();
        caveY = view.caveY();
        if (regionChanged) resetSlice();
        yaw = renderedYaw = view.yaw();
        pitch = renderedPitch = view.pitch();
        zoom = renderedZoom = view.zoom();
        panX = view.panX();
        panY = view.panY();
        cameraUpdatedAt = System.nanoTime();
        dirty = true;
        if (modeChanged) rebuildWidgets();
    }

    private boolean mostlyLoaded(com.wywf.verify.VerifiedAnchor structure) {
        if (structure == null) return true;
        int minX = structure.minX() >> 4, minZ = structure.minZ() >> 4;
        int maxX = structure.maxX() >> 4, maxZ = structure.maxZ() >> 4;
        long total = (maxX - minX + 1L) * (maxZ - minZ + 1L);
        int ready = 0;
        for (int z = Math.max(minZ, originZ >> 4); z <= Math.min(maxZ, (originZ >> 4) + chunkSide - 1); z++) {
            for (int x = Math.max(minX, originX >> 4); x <= Math.min(maxX, (originX >> 4) + chunkSide - 1); x++)
                if (loaded(x << 4, z << 4)) ready++;
        }
        return ready >= total * .7;
    }

    private void moveToLoaded(ReliefTargets.Pin pin) {
        int y = pinHeight(pin);
        centerOn(new WorldPoint(pin.x() + .5, y + 1, pin.z() + .5));
        clearOutline();
        if (pin.structure() != null) {
            outline = new ReliefRaster.Outline(pin.structure(), y);
            outlineStartedAt = System.nanoTime();
        }
    }

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
        if (MapContextMenu.keyboardShortcut(event)) {
            showContext(width / 2.0, mapTop + mapHeight() / 2.0);
            return true;
        }
        if (context != null) {
            if (event.isEscape()) { context = null; return true; }
            if (context.key(event)) return true;
        }
        if (event.isEscape() && dimensions.isOpen()) {
            dimensions.close();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public boolean mouseClicked(MouseButtonEvent e, boolean keyboard) {
        if (context != null) {
            if (context.click(e)) return true;
            context = null;
        }
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
            panDragged = false;
            rotationPivot = null;
            probeClickAt = 0;
            return true;
        }
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT && inside(e.x(), e.y())) {
            dragging = true;
            rotationPivot = pointAt(width / 2.0, mapTop + mapHeight() / 2.0);
            probeDragged = false;
            return true;
        }
        probeClickAt = 0;
        return super.mouseClicked(e, keyboard);
    }

    @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (context != null && context.drag(e.y())) return true;
        if (panning && e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            panDragged |= dx != 0 || dy != 0;
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
        if (context != null && context.release()) return true;
        if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT && panning) {
            if (!panDragged && inside(e.x(), e.y())) showContext(e.x(), e.y());
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
        if (context != null && context.scroll(x, y, v)) return true;
        if (!inside(x, y) || v == 0) return super.mouseScrolled(x, y, h, v);
        context = null;
        rotationPivot = null;
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

    static float outlineOpacity(long elapsed) {
        return .86f * (1 - Math.clamp(elapsed / (float)OUTLINE_DURATION, 0, 1));
    }

    private void clearOutline() {
        if (outline == null) return;
        outline = renderedOutline = null;
        outlineImage.close();
        dirty = true;
    }

    private void switchDimension(DimensionSpace destination) {
        if (pendingRadius) ReliefRadius.save(radius, false);
        minecraft.setScreenAndShow(new ReliefScreen(parent, source, destination, launchX, launchZ));
    }

    @Override public void onClose() {
        if (pendingRadius) ReliefRadius.save(radius, false);
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override public void removed() {
        closed = true;
        targets.close();
        context = null;
        cancelWork();
        partialPages.clear();
        meshBuilder = null;
        mesh = null;
        caveTiles = null;
        idleBuffers = null;
        lastProbe = null;
        displayedProbeFaces = null;
        source.pauseSurface();
        clearOutline();
        image.close();
        if (gpu != null) {
            gpu.close();
            gpu = null;
        }
    }
}
