package com.wywf.client;

import com.mojang.blaze3d.platform.InputConstants;

import com.wywf.WYWFClient;
import com.wywf.core.*;
import com.wywf.map.MapViewport;
import com.wywf.verify.*;
import com.wywf.verify.PlaceSurvey.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.*;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class FtpScreen extends Screen {
    private final CreateWorldScreen creator;
    private final Screen parent;
    private final SearchResult source;
    private final SearchConfig config;
    private final Path savedDirectory;
    private final List<PreviewWorld.Session> preserved;
    private final MapViewport viewport;
    private final com.wywf.map.BiomeMaps.Scope cache = com.wywf.map.BiomeMaps.scope();
    private final DimensionSpace dimension;
    private final int initialX, initialZ;
    private int centerX, centerZ, mode, selected;
    private int panelWidth, mapX, mapY, mapBottom, footer;
    private int firstIndex, secondIndex;
    private int targetSpacing;
    private boolean panning, moved, leftClick, selecting, chooseArea, cancelled;
    private long rightClickAt;
    private double rightClickX, rightClickY;
    private double pressX, pressY, dragX, dragY;
    private Area selection;
    private ConstructorDropList modeList, firstList, secondList, openDrop;
    private EditBox conditions, count, distance;
    private Button find, previous, next, copy;
    private BiomeMapCanvas map;
    private PreviewWorld.Session surveyWorld;
    private CompletableFuture<Report> survey;
    private Request pending, activeRequest;
    private PlaceSearch surveyGrid;
    private long nextRegion;
    private int regionsScanned, partialRegions;
    private int searchOriginX, searchOriginZ;
    private boolean localArea;
    private Report report;
    private String error = "", message = "";
    private String queryText, villageCount = "3", villageGap = "400", spawnerCount = "2", spawnerGap = "32";
    private List<String> targets = List.of();
    private List<VerifiedAnchor> queryMarkers = List.of();

    FtpScreen(CreateWorldScreen creator, Screen parent, WorldMapSource source, int x, int z,
              List<PreviewWorld.Session> preserved, DimensionSpace dimension, Path savedDirectory) {
        super(Component.literal("FindThisPlace"));
        this.creator = creator;
        this.parent = parent;
        this.source = source.result();
        this.config = source.config().copy();
        this.queryText = source.query().expression();
        this.preserved = preserved;
        this.dimension = dimension;
        this.savedDirectory = savedDirectory;
        initialX = centerX = x;
        initialZ = centerZ = z;
        viewport = new MapViewport(x, z, 4);
    }

    List<PreviewWorld.Session> preserved() { return preserved; }

    void editCenter(int x, int z) {
        centerX = x;
        centerZ = z;
        viewport.reset(x, z, viewport.scale());
        Minecraft.getInstance().setScreenAndShow(this);
    }

    void editConditions(String text) {
        queryText = text;
        mode = 0;
        Minecraft.getInstance().setScreenAndShow(this);
    }

    private boolean unverifiedSearchResults;

    void showSearchResults(CandidateReview review, ParsedQuery query, SearchConfig options) {
        List<Finding> found = new ArrayList<>();
        List<VerifiedAnchor> markers = new ArrayList<>();
        for (var result : review.candidates()) {
            var session = PreviewWorld.find(result, query, options);
            int x = dimension.coordinate(result.centerX), z = dimension.coordinate(result.centerZ);
            List<Point> evidence = new ArrayList<>();
            if (session != null) for (var anchor : session.anchors()) {
                if (!anchor.dimension().equals(dimension.id)) continue;
                markers.add(anchor);
                evidence.add(new Point(anchor.x(), Integer.MIN_VALUE, anchor.z(), anchor.id()));
            }
            found.add(new Finding(new Point(x, Integer.MIN_VALUE, z, ""), evidence, 0));
        }
        queryMarkers = List.copyOf(markers);
        showReport(new Report(found, false, 0, 0));
        unverifiedSearchResults = !options.serverVerification();
        Minecraft.getInstance().setScreenAndShow(this);
    }

    @Override protected void init() {
        openDrop = null;
        int inset = 12, gap = 6;
        panelWidth = Math.clamp(width / 3, Math.min(112, width / 2), Math.min(210, width / 2));
        mapX = panelWidth + 24;
        mapY = 67;
        footer = height - 36;
        targetSpacing = height < 280 ? 23 : 30;
        mapBottom = Math.max(mapY + 1, footer - 31);
        if (map == null) {
            map = new BiomeMapCanvas(source, 512, viewport, new com.wywf.map.StructureLayers(source), cache);
            map.structures.enabled.clear();
            map.dimension(dimension);
            for (var marker : queryMarkers) {
                map.structures.enabled.add(marker.id());
                map.structures.matched(marker);
            }
        }
        map.bounds(mapX, mapY, Math.max(1, width - mapX - inset), mapBottom - mapY);
        int mapWidth = width - mapX - inset;
        int firstWidth = (mapWidth - 2 * gap) / 3, secondWidth = firstWidth;
        addRenderableWidget(MenuButton.builder(scopeLabel(), b -> {
            localArea = !localArea;
            selection = null;
            chooseArea = false;
            b.setMessage(scopeLabel());
        }).bounds(mapX, 43, firstWidth, 20).build()).active = creator != null;
        var areaButton = addRenderableWidget(MenuButton.builder(MapTexts.label(chooseArea ? "ftp_stop_selecting" : "ftp_select_area"), b -> {
            chooseArea = !chooseArea;
            if (chooseArea) localArea = true;
            rebuildWidgets();
        }).bounds(mapX + firstWidth + gap, 43, secondWidth, 20).build());
        areaButton.setTooltip(Tooltip.create(MapTexts.label("ftp_area_help")));
        addRenderableWidget(MenuButton.builder(MapTexts.label("reset"), b -> {
            viewport.reset(initialX, initialZ, 4);
            centerX = initialX;
            centerZ = initialZ;
            selection = null;
            map.dirty();
        }).bounds(mapX + firstWidth + secondWidth + 2 * gap, 43, mapWidth - firstWidth - secondWidth - 2 * gap, 20).build());

        var modes = List.of("query", "villages", "biome_border", "river_mouth", "spawners",
                "single_spawner", "geodes", "iron_veins", "copper_veins", "surface_lava", "slime_chunks");
        var availableModes = java.util.stream.IntStream.range(0, modes.size()).filter(index -> index == 0
                ? creator != null && dimension == DimensionSpace.OVERWORLD
                : dimension == DimensionSpace.OVERWORLD || !Kind.values()[index - 1].overworldOnly()).boxed().toList();
        if (!availableModes.contains(mode)) mode = availableModes.getFirst();
        modeList = drop(46, "ftp_search_type", availableModes.stream().map(index -> MapTexts.text("ftp_" + modes.get(index)))
                .toList(), availableModes.indexOf(mode), index -> {
            mode = availableModes.get(index);
            firstIndex = secondIndex = selected = 0;
            report = null;
            queryMarkers = List.of();
            if (map != null) map.structures.enabled.clear();
            error = "";
            message = "";
            rebuildWidgets();
        });
        conditions = count = distance = null;
        firstList = secondList = null;
        int nextRow = 84;
        if (mode == 0) {
            conditions = field("ftp_conditions", queryText, inset, nextRow, panelWidth, 20000, text -> queryText = text);
            conditions.moveCursorToStart(false);
        } else if (mode == 1 || mode == 4) {
            if (mode == 4) {
                targets = spawnerTypes();
                addTargets(nextRow);
                nextRow += targetSpacing + 20 + font.lineHeight + 5;
            }
            boolean spawners = mode == 4;
            int half = (panelWidth - gap) / 2;
            count = field("ftp_count", spawners ? spawnerCount : villageCount, inset, nextRow, half, 2,
                    text -> { if (spawners) spawnerCount = text; else villageCount = text; });
            distance = field("ftp_distance", spawners ? spawnerGap : villageGap, inset + half + gap,
                    nextRow, panelWidth - half - gap, 4, text -> { if (spawners) spawnerGap = text; else villageGap = text; });
        } else if (mode == 5) {
            targets = spawnerTypes();
            firstList = drop(nextRow, "ftp_first", targetLabels(), firstIndex, index -> firstIndex = index);
        } else if (mode == 2) {
            targets = WYWFClient.dictionary().all().stream().filter(e -> e.category == KeywordDictionary.Category.BIOME)
                    .map(e -> e.canonical).distinct().filter(id -> DimensionSpace.of(id) == dimension)
                    .filter(id -> !com.wywf.map.BiomeMapColors.cave(id))
                    .sorted(Comparator.comparing(ResultTexts::name)).toList();
            if (secondIndex == firstIndex && targets.size() > 1) secondIndex = 1;
            addTargets(nextRow);
        }

        int actionWidth = Math.min(150, (width - 36) / 2);
        int left = (width - actionWidth * 2 - 8) / 2, actionY = footer + (height - footer - 20) / 2;
        find = addRenderableWidget(MenuButton.builder(MapTexts.label(running() ? "ftp_cancel_scan" : "ftp_find"), b -> {
            if (running()) cancelSurvey(); else start();
        }).bounds(left, actionY, actionWidth, 20).build());
        addRenderableWidget(MenuButton.builder(ResultTexts.label("back"), b -> onClose())
                .bounds(left + actionWidth + 8, actionY, actionWidth, 20).build());
        int resultY = footer - 28;
        previous = addRenderableWidget(MenuButton.builder(Component.literal("<"), b -> focus(selected - 1))
                .bounds(inset, resultY, 20, 20).build());
        next = addRenderableWidget(MenuButton.builder(Component.literal(">"), b -> focus(selected + 1))
                .bounds(inset + 24, resultY, 20, 20).build());
        copy = addRenderableWidget(MenuButton.builder(MapTexts.label("ftp_copy_coords"), b -> copy())
                .bounds(inset + 50, resultY, panelWidth - 50, 20).build());
        updateButtons();
    }

    private ConstructorDropList drop(int y, String label, List<String> options, int index,
                                     java.util.function.Consumer<Integer> pick) {
        return addRenderableWidget(new ConstructorDropList(12, y, panelWidth, MapTexts.text(label), options, index, font,
                value -> { pick.accept(value); updateButtons(); }, d -> {
                    if (openDrop != null && openDrop != d) openDrop.close();
                    openDrop = d;
                }).popupLimits(36, footer - 4));
    }

    private static List<String> spawnerTypes() {
        return List.of("", "minecraft:zombie", "minecraft:skeleton", "minecraft:spider", "minecraft:cave_spider",
                "minecraft:blaze", "minecraft:silverfish", "minecraft:magma_cube", "minecraft:trial_spawner");
    }

    private List<String> targetLabels() {
        return targets.stream().map(id -> id.isEmpty() ? MapTexts.text("ftp_any") : ResultTexts.name(id)).toList();
    }

    private void addTargets(int y) {
        var labels = targetLabels();
        firstList = drop(y, "ftp_first", labels, firstIndex, index -> firstIndex = index);
        secondList = drop(y + targetSpacing, "ftp_second", labels, secondIndex, index -> secondIndex = index);
    }

    private EditBox field(String label, String value, int x, int y, int w, int limit,
                          java.util.function.Consumer<String> responder) {
        var box = addRenderableWidget(new EditBox(font, x, y, w, 20, MapTexts.label(label)));
        box.setMaxLength(limit);
        box.setValue(value);
        box.setResponder(text -> { responder.accept(text); error = ""; });
        return box;
    }

    private Component scopeLabel() {
        return MapTexts.label(creator == null ? "ftp_loaded_scope" : localArea ? "ftp_local_scope" : "ftp_world_scope");
    }

    private boolean running() { return pending != null || survey != null; }

    private void updateButtons() {
        if (find == null) return;
        find.setMessage(MapTexts.label(running() ? "ftp_cancel_scan" : "ftp_find"));
        modeList.active = !running();
        var finding = selectedFinding();
        copy.visible = previous.visible = next.visible = finding != null;
        previous.active = selected > 0;
        next.active = report != null && selected + 1 < report.findings().size();
    }

    private Finding selectedFinding() {
        return report == null || report.findings().isEmpty() ? null : report.findings().get(selected);
    }

    private void copy() {
        var finding = selectedFinding();
        if (finding == null) return;
        var p = finding.activation() == null ? finding.center() : finding.activation();
        Minecraft.getInstance().keyboardHandler.setClipboard(p.x() + (p.y() == Integer.MIN_VALUE ? " " : " " + p.y() + " ") + p.z());
        message = MapTexts.text("ftp_coords_copied", p.x(), p.z());
    }

    private void focus(int index) {
        if (report == null || index < 0 || index >= report.findings().size()) return;
        selected = index;
        var point = report.findings().get(index).center();
        viewport.reset(point.x(), point.z(), mode == 4 ? Math.min(viewport.scale(), 1) : viewport.scale());
        map.dirty();
        updateButtons();
    }

    private Area area() {
        if (selection != null) return selection;
        return new Area(block(viewport.worldX(0)), block(viewport.worldZ(0)),
                block(Math.ceil(viewport.worldX(viewport.width())) - 1), block(Math.ceil(viewport.worldZ(viewport.height())) - 1));
    }

    private static int block(double coordinate) { return Math.clamp((int)Math.floor(coordinate), -PlaceSearch.WORLD_EDGE, PlaceSearch.WORLD_EDGE); }

    private void start() {
        error = "";
        message = "";
        cancelled = false;
        boolean wholeSeed = creator != null && !localArea;
        var area = wholeSeed ? new Area(centerX, centerZ, centerX, centerZ) : area();
        if (chooseArea) { error = MapTexts.text("ftp_area_help"); return; }
        int areaWidth = area.maxX() - area.minX() + 1, areaHeight = area.maxZ() - area.minZ() + 1;
        Kind kind = mode == 0 ? null : Kind.values()[mode - 1];
        int limit = kind == null ? 4096 : kind.maxArea();
        if (areaWidth > limit || areaHeight > limit) {
            error = MapTexts.text("ftp_area_limit", limit);
            return;
        }
        if (mode == 0) {
            if (dimension != DimensionSpace.OVERWORLD) {
                error = MapTexts.text("ftp_query_overworld");
                return;
            }
            int x = area.minX() + areaWidth / 2, z = area.minZ() + areaHeight / 2;
            int radius = Math.max(64, (int)Math.ceil(Math.hypot(areaWidth, areaHeight) / 2));
            var place = wholeSeed ? PlaceSearch.wholeSeed(source.seed, x, z, 64) : new PlaceSearch(source.seed, x, z, radius, 64);
            var query = WYWFClient.parser().parse(place.prefix() + queryText);
            if (query.error() != null) { error = query.error(); return; }
            if (query.isEmpty() || !query.ignoredWords().isEmpty()) {
                error = MapTexts.text(query.isEmpty() ? "ftp_conditions_required" : "ftp_unknown");
                return;
            }
            new SearchScreen(creator, query.normalized(), query, config).forPlace(this, wholeSeed ? null : area).open(() -> {
                report = null;
                queryMarkers = List.of();
            });
            return;
        }
        int needed = mode == 1 || mode == 4 ? number(mode == 4 ? spawnerCount : villageCount, 2, 8) : 2;
        int gap = mode == 1 || mode == 4 ? number(mode == 4 ? spawnerGap : villageGap, 1, mode == 4 ? 256 : 2048) : 1;
        if (needed < 0 || gap < 0) {
            error = MapTexts.text("ftp_bad_group", mode == 4 ? 256 : 2048);
            return;
        }
        if (mode == 2 && (targets.size() < 2 || firstIndex == secondIndex)) {
            error = MapTexts.text("ftp_choose_biomes");
            return;
        }
        if (dimension != DimensionSpace.OVERWORLD && kind.overworldOnly()) {
            error = MapTexts.text("ftp_overworld");
            return;
        }
        String first = kind == Kind.BIOME_BORDER || kind.hasMobFilter() ? targets.get(firstIndex) : "";
        String second = mode == 2 || mode == 4 ? targets.get(secondIndex) : "";
        if (mode == 4 && first.isEmpty() && !second.isEmpty()) { first = second; second = ""; }
        searchOriginX = centerX;
        searchOriginZ = centerZ;
        regionsScanned = partialRegions = 0;
        nextRegion = 0;
        int step = switch (kind) {
            case VILLAGES -> Math.min(1024, 4096 - 2 * gap);
            case SLIME_CHUNKS -> 512;
            case SPAWNERS -> 64;
            default -> 96;
        };
        if (kind == Kind.VILLAGES) step = Math.max(64, step);
        searchOriginX = Math.clamp((searchOriginX >> 4) << 4, -PlaceSearch.WORLD_EDGE, PlaceSearch.WORLD_EDGE);
        searchOriginZ = Math.clamp((searchOriginZ >> 4) << 4, -PlaceSearch.WORLD_EDGE, PlaceSearch.WORLD_EDGE);
        surveyGrid = wholeSeed ? PlaceSearch.wholeSeed(source.seed, searchOriginX, searchOriginZ, step) : null;
        report = null;
        queryMarkers = List.of();
        map.structures.enabled.clear();
        var request = new Request(kind, dimension, area, needed, gap, first, second);
        pending = wholeSeed ? nextSurvey(request) : request;
        cancelled = false;
        updateButtons();
    }

    private Request nextSurvey(Request previous) {
        int step = surveyGrid.step();
        int padding = switch (previous.kind()) {
            case VILLAGES -> Math.min(previous.distance(), (4096 - step) / 2);
            case SPAWNERS -> Math.min(previous.distance(), 16);
            default -> 0;
        };
        int edge = PlaceSearch.WORLD_EDGE;
        while (nextRegion < surveyGrid.cells()) {
            var point = surveyGrid.position(nextRegion++);
            int x = point[0] - step / 2, z = point[1] - step / 2;
            if (x > edge || z > edge || x + step - 1 < -edge || z + step - 1 < -edge) continue;
            var area = new Area(Math.max(-edge, x - padding), Math.max(-edge, z - padding),
                    Math.min(edge, x + step - 1 + padding), Math.min(edge, z + step - 1 + padding));
            return new Request(previous.kind(), dimension, area, previous.count(), previous.distance(),
                    previous.first(), previous.second());
        }
        return null;
    }

    private static int number(String value, int min, int max) {
        try {
            int n = Integer.parseInt(value);
            return n >= min && n <= max ? n : -1;
        } catch (NumberFormatException ignored) { return -1; }
    }

    @Override public void tick() {
        if (map != null) map.tick();
        if (pending != null) {
            try {
                var server = Minecraft.getInstance().getSingleplayerServer();
                if (pending.kind() == Kind.SLIME_CHUNKS) {
                    Request request = pending;
                    var scan = new CompletableFuture<Report>();
                    long seed = source.seed;
                    survey = scan;
                    java.util.concurrent.ForkJoinPool.commonPool().execute(() -> {
                        if (scan.isCancelled()) return;
                        try {
                            scan.complete(PlaceSurvey.slimeChunks(seed, request, scan::isCancelled));
                        } catch (RuntimeException error) {
                            scan.completeExceptionally(error);
                        }
                    });
                } else if (creator == null && server != null) {
                    survey = PlaceSurvey.scanLoaded(server, pending);
                } else {
                    if (surveyWorld == null) {
                        if (PreviewWorld.active() != null) return;
                        surveyWorld = PreviewWorld.beginMap(creator, source, config, this);
                    }
                    survey = surveyWorld.survey(pending);
                }
                activeRequest = pending;
                pending = null;
            } catch (Exception problem) {
                pending = null;
                if (surveyWorld != null) surveyWorld.cancel();
                surveyWorld = null;
                error = MapTexts.text("ftp_scan_failed");
                WYWFClient.LOGGER.warn("Could not start FindThisPlace survey", problem);
                updateButtons();
            }
        }
        if (surveyWorld != null && surveyWorld.cancelled() && survey != null && !survey.isDone()) survey.cancel(false);
        if (surveyWorld != null) surveyWorld.tickMap();
        if (survey == null || !survey.isDone()) return;
        try {
            Report result = survey.join();
            regionsScanned++;
            if (result.partial()) partialRegions++;
            if (surveyGrid != null && result.findings().isEmpty()) {
                pending = nextSurvey(activeRequest);
                if (pending != null) return;
            }
            showReport(result);
            if (surveyGrid != null && !result.findings().isEmpty()) focus(0);
        }
        catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException problem) {
            error = MapTexts.text("ftp_scan_failed");
            WYWFClient.LOGGER.warn("FindThisPlace survey stopped", problem);
        } finally {
            survey = null;
            activeRequest = null;
            if (pending == null || regionsScanned % 32 == 0) {
                if (surveyWorld != null) surveyWorld.cancel();
                surveyWorld = null;
            }
            updateButtons();
        }
    }

    private void showReport(Report report) {
        this.report = report;
        unverifiedSearchResults = false;
        selected = 0;
        cancelled = false;
        if (!report.findings().isEmpty()) {
            centerX = report.findings().getFirst().center().x();
            centerZ = report.findings().getFirst().center().z();
        }
        updateButtons();
    }

    private void cancelSurvey() {
        pending = activeRequest = null;
        if (survey != null) survey.cancel(false);
        survey = null;
        if (surveyWorld != null) surveyWorld.cancel();
        surveyWorld = null;
        cancelled = true;
        updateButtons();
    }

    @Override public void onClose() {
        cancelSurvey();
        if (savedDirectory != null) HiddenWorldSettings.release(creator);
        cache.close();
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override public void added() {
        super.added();
        if (find != null && map == null) rebuildWidgets();
    }

    @Override protected void repositionElements() { rebuildWidgets(); }

    @Override public void removed() {
        if (running()) cancelSurvey();
        if (map != null) { map.close(); map = null; }
        panning = leftClick = selecting = false;
        rightClickAt = 0;
    }

    private int popupHeight() { return openDrop == null ? 0 : Math.max(0, footer - openDrop.getY() - 24); }

    @Override public boolean mouseClicked(MouseButtonEvent e, boolean keyboard) {
        if (openDrop != null && openDrop.isOpen()) {
            int space = popupHeight();
            if (openDrop.thumbHit((float)e.x(), (float)e.y(), space)) {
                openDrop.beginPopupDrag((float)e.y(), space);
                return true;
            }
            if (openDrop.handlePopupClick((float)e.x(), (float)e.y(), space)) return true;
            openDrop.close();
            openDrop = null;
        }
        if (!map.contains(e.x(), e.y())) return super.mouseClicked(e, keyboard);
        if (e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            panning = true;
            moved = false;
            pressX = e.x();
            pressY = e.y();
            return true;
        }
        if (e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (chooseArea) {
                selecting = true;
                pressX = dragX = e.x();
                pressY = dragY = e.y();
            } else leftClick = true;
            return true;
        }
        return super.mouseClicked(e, keyboard);
    }

    @Override public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (openDrop != null && openDrop.isPopupDrag()) {
            openDrop.dragPopupTo((float)e.y(), popupHeight());
            return true;
        }
        if (selecting && e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            dragX = e.x();
            dragY = e.y();
            return true;
        }
        if (panning && e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (!moved && Math.hypot(e.x() - pressX, e.y() - pressY) < 3) return true;
            if (!moved) { dx = e.x() - pressX; dy = e.y() - pressY; }
            moved = true;
            map.pan(dx, dy);
            return true;
        }
        if (leftClick && e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            leftClick = false;
            return true;
        }
        return super.mouseDragged(e, dx, dy);
    }

    @Override public boolean mouseReleased(MouseButtonEvent e) {
        if (openDrop != null && openDrop.isPopupDrag()) {
            openDrop.endPopupDrag();
            return true;
        }
        if (selecting && e.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            selecting = false;
            chooseArea = false;
            int ax = block(viewport.worldX(pressX - mapX)), az = block(viewport.worldZ(pressY - mapY));
            int bx = block(viewport.worldX(Math.clamp(e.x() - mapX, 0, viewport.width() - 1)));
            int bz = block(viewport.worldZ(Math.clamp(e.y() - mapY, 0, viewport.height() - 1)));
            if (Math.abs(pressX-e.x()) >= 3 && Math.abs(pressY-e.y()) >= 3)
                selection = new Area(Math.min(ax,bx), Math.min(az,bz), Math.max(ax,bx), Math.max(az,bz));
            localArea = true;
            rebuildWidgets();
            return true;
        }
        if (panning && e.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            panning = false;
            if (moved || !map.contains(e.x(), e.y())) rightClickAt = 0;
            else {
                long now = System.nanoTime();
                if (rightClickAt != 0 && now - rightClickAt <= 350_000_000L
                        && Math.hypot(e.x() - rightClickX, e.y() - rightClickY) <= 6) {
                    rightClickAt = 0;
                    selection = null;
                    localArea = false;
                    chooseArea = selecting = false;
                    rebuildWidgets();
                } else {
                    rightClickAt = now;
                    rightClickX = e.x();
                    rightClickY = e.y();
                }
            }
            return true;
        }
        if (!leftClick || e.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseReleased(e);
        leftClick = false;
        if (map.contains(e.x(), e.y())) {
            if (report != null) for (int i = 0; i < report.findings().size(); i++) {
                var hit = report.findings().get(i).center();
                if (Math.hypot(e.x() - mapX - viewport.pixelX(hit.x()), e.y() - mapY - viewport.pixelZ(hit.z())) <= 7) {
                    focus(i);
                    return true;
                }
            }
            centerX = block(viewport.worldX(e.x() - mapX));
            centerZ = block(viewport.worldZ(e.y() - mapY));
        }
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (openDrop != null && openDrop.isOpen() && openDrop.popupHit((float)x, (float)y, popupHeight())) {
            openDrop.scrollPopup((float)vertical, popupHeight());
            return true;
        }
        if (!map.contains(x, y)) return super.mouseScrolled(x, y, horizontal, vertical);
        map.zoom(vertical, x, y);
        return true;
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
        MenuBands.draw(g, width, height, 36, footer);
        int headerY = (36 - 2 * font.lineHeight - 4) / 2;
        g.centeredText(font, title, width / 2, headerY, 0xFFFFFFFF);
        g.centeredText(font, MapTexts.text("ftp_seed") + " " + source.seed, width / 2,
                headerY + font.lineHeight + 4, 0xFFFFFFFF);
        map.draw(g, false);
        drawSelection(g);
        map.drawMarkers(g);
        drawFindings(g);
        if (report == null) {
            var p = com.wywf.map.MapPointer.project(viewport.pixelX(centerX), viewport.pixelZ(centerZ),
                    viewport.width() - 1, viewport.height() - 1, 0);
            map.drawCrossAt(g, mapX + p.x(), mapY + p.y(), null);
        }
        if (conditions != null) caption(g, conditions, "ftp_conditions");
        if (count != null) {
            caption(g, count, "ftp_count");
            caption(g, distance, "ftp_distance");
        }
        int infoY = distance != null ? distance.getY() + 25 : secondList != null ? secondList.getY() + 26 : firstList != null ? firstList.getY() + 26 : 120;
        String status = !error.isEmpty() ? error : running()
                ? surveyGrid == null ? MapTexts.text("ftp_scanning") : MapTexts.text("ftp_regions", regionsScanned, partialRegions)
                : cancelled ? MapTexts.text("ftp_scan_cancelled") : message;
        if (status.isEmpty() && report != null) status = MapTexts.text(unverifiedSearchResults ? "raw_results" : "ftp_matches", report.findings().size())
                + (surveyGrid != null ? " · " + MapTexts.text("ftp_regions", regionsScanned, partialRegions)
                : report.partial() ? " · " + MapTexts.text("ftp_partial", report.checked(), report.total()) : "");
        if (status.isEmpty()) status = MapTexts.text(creator == null ? "ftp_loaded_help"
                : localArea ? "ftp_survey_help" : "ftp_world_help");
        Component statusText = !error.isEmpty() ? MenuAccent.conflictText(status)
                : running() || report != null || cancelled ? accent(status) : Component.literal(status);
        var hit = selectedFinding();
        int statusBottom = footer - (hit == null ? 6 : 28);
        for (var line : font.split(statusText, panelWidth - 10)) {
            if (infoY + font.lineHeight > statusBottom) break;
            g.text(font, line, 17, infoY, 0xFFFFFFFF);
            infoY += font.lineHeight;
        }
        boolean findingFitsSidebar = hit != null && infoY + 18 < footer - 31;
        String selectedText = hit == null ? "" : (selected + 1) + "/" + report.findings().size()
                + "  " + coordinates(hit.center());
        if (findingFitsSidebar) {
            var p = hit.center();
            g.text(font, font.plainSubstrByWidth(selectedText, panelWidth - 10), 17, infoY + 5, 0xFFFFFFFF);
            infoY += 20;
            if (hit.evidence().isEmpty() && !p.type().isEmpty() && infoY + font.lineHeight < footer - 31) {
                g.text(font, accent(ResultTexts.name(p.type())), 17, infoY, 0xFFFFFFFF);
                infoY += font.lineHeight + 2;
            }
            if (hit.distance() > 1) {
                g.text(font, MapTexts.text("ftp_group_gap", hit.distance()), 17, infoY, 0xFFFFFFFF);
                infoY += font.lineHeight + 2;
            }
            if (hit.activation() != null && infoY + font.lineHeight < footer - 31) {
                String activation = MapTexts.text("ftp_activation") + " · " + coordinates(hit.activation());
                for (var line : font.split(accent(activation), panelWidth - 10)) {
                    if (infoY + font.lineHeight >= footer - 31) break;
                    g.text(font, line, 17, infoY, 0xFFFFFFFF);
                    infoY += font.lineHeight;
                }
            }
            for (var point : hit.evidence()) {
                var name = ResultTexts.name(point.type()) + " · " + coordinates(point);
                for (var line : font.split(accent(name), panelWidth - 10)) {
                    if (infoY + font.lineHeight > footer - 31) break;
                    g.text(font, line, 17, infoY, 0xFFFFFFFF);
                    infoY += font.lineHeight;
                }
            }
        }
        var cell = map.hovered(mx, my);
        var region = area();
        String hover = cell == null ? (creator != null && !localArea
                ? MapTexts.text("ftp_search_origin", centerX, centerZ)
                : MapTexts.text("ftp_area_size", region.maxX()-region.minX()+1, region.maxZ()-region.minZ()+1))
                : coordinates(new Point(block(viewport.worldX(mx-mapX)), Integer.MIN_VALUE, block(viewport.worldZ(my-mapY)), ""))
                        + " · " + ResultTexts.name(cell.id());
        String mapStatus = map.status();
        int line = font.lineHeight + 1;
        int statusX = mapX + viewport.width() / 2;
        int mapInfoY = mapBottom + 2;
        if (hit != null && !findingFitsSidebar) {
            g.centeredText(font, font.plainSubstrByWidth(selectedText, viewport.width()), statusX, mapInfoY, MapTexts.EMPHASIS);
            mapInfoY += line;
        }
        g.centeredText(font, font.plainSubstrByWidth(hover, viewport.width()), statusX, mapInfoY, 0xFFFFFFFF);
        if (!mapStatus.isEmpty() && mapInfoY + line + font.lineHeight <= footer)
            g.centeredText(font, font.plainSubstrByWidth(MapTexts.text("ftp_map_status", mapStatus), viewport.width()),
                    statusX, mapInfoY + line, 0xFFBBD0D8);
        super.extractRenderState(g,mx,my,tick);
        for (var child : children()) {
            if (child instanceof ConstructorDropList list && list != openDrop)
                list.drawPopup(g, mx, my, Math.max(0, footer - list.getBottom() - 4), 1);
        }
        if (openDrop != null) openDrop.drawPopup(g, mx, my, popupHeight(), 1);
    }

    private static Component accent(String text) {
        return MenuAccent.text(text, 0xFFF0F0F0, 0xFFBDBDBD);
    }

    private void caption(GuiGraphicsExtractor g, EditBox field, String label) {
        g.centeredText(font, font.plainSubstrByWidth(MapTexts.text(label), field.getWidth()), field.getX() + field.getWidth()/2,
                field.getY()-font.lineHeight-2, 0xFFFFFFFF);
    }

    private static String coordinates(Point p) {
        return "X " + p.x() + (p.y() == Integer.MIN_VALUE ? "" : " Y " + p.y()) + " Z " + p.z();
    }

    private void drawSelection(GuiGraphicsExtractor g) {
        if (selection == null && !selecting) return;
        int ax = selecting ? (int)Math.min(pressX, dragX) : mapX + viewport.edgeX(selection.minX());
        int ay = selecting ? (int)Math.min(pressY, dragY) : mapY + viewport.edgeZ(selection.minZ());
        int bx = selecting ? (int)Math.max(pressX, dragX) : mapX + viewport.edgeX(selection.maxX() + 1d);
        int by = selecting ? (int)Math.max(pressY, dragY) : mapY + viewport.edgeZ(selection.maxZ() + 1d);
        g.enableScissor(mapX, mapY, mapX + viewport.width(), mapBottom);
        g.fill(ax, ay, bx, by, 0x229FE3AE);
        g.fill(ax, ay, bx, ay+1, 0xFF9FE3AE);
        g.fill(ax, by-1, bx, by, 0xFF9FE3AE);
        g.fill(ax, ay, ax+1, by, 0xFF9FE3AE);
        g.fill(bx-1, ay, bx, by, 0xFF9FE3AE);
        g.disableScissor();
    }

    private void drawFindings(GuiGraphicsExtractor g) {
        if (report == null) return;
        for (int i = 0; i < report.findings().size(); i++) {
            var hit = report.findings().get(i);
            int x = mapX + viewport.edgeX(hit.center().x()), y = mapY + viewport.edgeZ(hit.center().z());
            if (i == selected && map.contains(x,y)) map.drawCrossAt(g, x, y, Integer.toString(i + 1));
            else if (map.contains(x, y)) g.fill(x-2, y-2, x+3, y+3, 0xFF9FE3AE);
        }
        var hit = selectedFinding();
        if (hit == null) return;
        g.enableScissor(mapX, mapY, mapX + viewport.width(), mapBottom);
        for (var point : hit.evidence()) {
            int x = mapX + viewport.edgeX(point.x()), y = mapY + viewport.edgeZ(point.z());
            g.fill(x-1, y-1, x+2, y+2, 0xFFFFD179);
        }
        if (hit.activation() != null) {
            int x = mapX + viewport.edgeX(hit.activation().x()), y = mapY + viewport.edgeZ(hit.activation().z());
            g.fill(x - 2, y - 2, x + 3, y + 3, 0xFF9FE3AE);
        }
        g.disableScissor();
    }

    public static void openLive(Minecraft client) {
        var server = client.getSingleplayerServer();
        var player = client.player;
        if (server == null || !server.getWorldData().isAllowCommands() || player == null || client.level == null) return;
        DimensionSpace dimension = Arrays.stream(DimensionSpace.values())
                .filter(d -> d.id.equals(client.level.dimension().identifier().toString())).findFirst().orElse(null);
        if (dimension == null) return;
        int x = player.getBlockX(), z = player.getBlockZ();
        var result = new SearchResult(server.overworld().getSeed(), x, z, x, z, "", List.of(), List.of(), "");
        var source = new SavedWorldMap(java.util.UUID.randomUUID().toString(), net.minecraft.SharedConstants.getCurrentVersion().id(),
                result, new ParsedQuery("", List.of()), ConfigStore.load(), null, null, List.of(), List.of());
        client.setScreenAndShow(new FtpScreen(null, ClientPlatform.screen(client), source, x, z, List.of(), dimension, null));
    }

    @Override public boolean isPauseScreen() { return creator != null; }

    static void openSaved(Screen parent, WorldMapSource source, Path directory, int x, int z, DimensionSpace dimension) {
        if (directory == null) return;
        var client = Minecraft.getInstance();
        try (var access = client.getLevelSource().validateAndCreateAccess(directory.getFileName().toString())) {
            var data = new WorldOpenFlows(client, client.getLevelSource()).recreateWorldData(access);
            var packs = CreateWorldScreen.createTempDataPackDirFromExistingWorld(directory.resolve("datapacks"), client);
            var creator = CreateWorldScreen.createFromExisting(client, () -> client.setScreenAndShow(parent), data.getFirst(), data.getSecond(), packs);
            client.setScreenAndShow(new FtpScreen(creator, parent, source, x, z, List.of(), dimension, directory));
        } catch (Exception error) { WYWFClient.LOGGER.warn("Could not prepare FindThisPlace with saved world settings", error); }
    }
}
