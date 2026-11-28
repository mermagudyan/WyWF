package com.wywf.client;

import dev.diylib.client.ui.DiyVanillaButton;
import com.wywf.core.*;

import com.wywf.mixin.CreateWorldScreenInvoker;
import com.wywf.search.MinecraftWorldContextFactory;
import com.wywf.world.WorldCreator;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import java.util.*;
import java.util.function.Consumer;

public final class SearchConstructorScreen extends Screen {
    private final CreateWorldScreen worldCreator;
    private final Screen backScreen;
    private ConstructorSettings settings;
    private TargetEditor targetEditor;
    private MenuModal unsavedPrompt;
    private static final Map<CreateWorldScreen,java.lang.ref.WeakReference<SearchConstructorScreen>> SCREENS = new WeakHashMap<>();
    public static SearchConstructorScreen forCreator(CreateWorldScreen creator,Consumer<String> writer,SearchDraft draft) {
        var ref=SCREENS.get(creator);
        var screen=ref==null ? null : ref.get();
        if(screen==null)
            screen=new SearchConstructorScreen(creator,writer,draft);
        screen.seedWriter=writer;
        return screen;
    }
    static SearchConstructorScreen forMenu(CreateWorldScreen creator, Screen backScreen) {
        return new SearchConstructorScreen(creator, backScreen, creator.getUiState()::setSeed, new SearchDraft());
    }

    public static void openSettings(CreateWorldScreen creator) {
        var screen = forCreator(creator, creator.getUiState()::setSeed, new SearchDraft());
        screen.guardEditor(() -> {
            screen.tab = 2;
            Minecraft.getInstance().setScreenAndShow(screen);
        });
    }

    private Consumer<String> seedWriter;
    private final SearchDraft draft;
    private final List<Button> markers = new ArrayList<>();
    private final List<int[]> anchors = new ArrayList<>();
    private final List<SearchDraft.Placement> markerPlacements = new ArrayList<>();
    private List<SearchDraft.Target> catalog;
    private List<SearchDraft.Target> structureTargets = List.of();
    private List<SearchDraft.Target> biomeTargets = List.of();
    private ConstructorDropList structures;
    private ConstructorDropList biomes;
    private ConstructorDropList openDrop, retiringDrop;
    private SearchDraft.Target dragTarget;
    private double grabX, grabY, originalX, originalY;
    private String filter = "";
    private int tab, canvasX, canvasY, canvasW, canvasH, centerX, centerY;
    private EditBox prompt;
    private String manualQuery;
    private String generatedQuery;
    private boolean updatingPrompt;
    private SearchConfig config;
    private KeywordDictionary.Lang displayLanguage;
    private BiomeMapCanvas exampleMap;
    private long exampleSeed;
    private com.wywf.map.BiomeMapSnapshot paintedMap;
    private final com.wywf.map.PreviewAdaptation adaptation = new com.wywf.map.PreviewAdaptation();
    private List<com.wywf.map.PreviewAdaptation.Goal> fitGoals = List.of();
    private long fitVariation = Long.MIN_VALUE;
    private boolean illustrative;
    private final com.wywf.map.BiomeRegionAdaptation.Session regions = new com.wywf.map.BiomeRegionAdaptation.Session();
    private com.wywf.map.BiomeRegionAdaptation.Session.Result regionResult;
    private final com.wywf.map.StructureLayers structureLayers = new com.wywf.map.StructureLayers(
            new SearchResult(0, 0, 0, 0, 0, "example", List.of(), List.of(), ""));
    private final MapImage sketch = new MapImage();

    private long variation = new java.util.Random().nextLong();

    private double sketchZoom = 1, panX, panY;
    private boolean panning;
    private boolean temperature, caves, borders;
    private final List<Button> presetButtons = new ArrayList<>();
    private int presetX, presetTop, presetWidth, presetBottom, presetScroll, presetGrab = -1;
    private DimensionSpace dimension = DimensionSpace.OVERWORLD;
    private boolean fitNeeded = true;
    private int baseRadius;

    public SearchConstructorScreen(CreateWorldScreen worldCreator, Consumer<String> seedWriter, SearchDraft draft) {
        this(worldCreator, worldCreator, seedWriter, draft);
    }

    private SearchConstructorScreen(CreateWorldScreen worldCreator, Screen backScreen, Consumer<String> seedWriter, SearchDraft draft) {
        super(Component.literal("WyWF"));
        this.worldCreator = worldCreator;
        this.backScreen = backScreen;
        SCREENS.put(worldCreator, new java.lang.ref.WeakReference<>(this));
        this.seedWriter = seedWriter;
        this.draft = draft;
    }

    CreateWorldScreen creator() { return worldCreator; }

    public static Component label(String suffix) {
        return Component.literal(ConstructorTexts.tr("wywf.constructor." + suffix));
    }

    private TabNavigationBar navigation;

    private record ConstructorTab(int index, Component getTabTitle)
            implements net.minecraft.client.gui.components.tabs.Tab {
        @Override public Component getTabExtraNarration() {
            return Component.empty();
        }
        @Override public void visitChildren(java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> visitor) {}
        @Override public void doLayout(net.minecraft.client.gui.navigation.ScreenRectangle area) {}
        public net.minecraft.client.gui.layouts.Layout getLayout() {
            return new net.minecraft.client.gui.layouts.FrameLayout();
        }
    }

    private void addNavigation() {
        var tabs = new ConstructorTab[] {
                new ConstructorTab(0, label("search")),
                new ConstructorTab(1, label("canvas")), new ConstructorTab(2, label("settings"))};
        var manager = new net.minecraft.client.gui.components.tabs.TabManager(this::addRenderableWidget,
                this::removeWidget, selected -> {
            int next = ((ConstructorTab) selected).index();
            if (next == tab) return;
            guardEditor(() -> {
                if (settings != null) settings.flush();
                targetEditor = null;
                tab = next;
                rebuildWidgets();
            });
            if (unsavedPrompt != null)
                navigation.selectTab(tab, false);
        }, selected -> {});
        navigation = addRenderableWidget(ClientPlatform.navigation(manager, width, tabs));
        navigation.selectTab(tab, false);

    }

    @Override
    protected void extractMenuBackground(GuiGraphicsExtractor g) {
        int header = navigation == null ? 24 : navigation.getRectangle().height();
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, CreateWorldScreen.TAB_HEADER_BACKGROUND,
                0, 0, 0, 0, width, header, 16, 16);
        extractMenuBackground(g, 0, header, width, height);
    }

    private void loadCatalog() {
        if (catalog != null)
            return;
        catalog = ConstructorCatalog.available(new MinecraftWorldContextFactory());
    }

    @Override
    protected void init() {
        draft.endMove();
        dragTarget = null;
        retiringDrop = openDrop != null && !openDrop.isOpen() && openDrop.isPopupVisible() ? openDrop : null;
        openDrop = null;
        if(config==null)
            config=ConfigStore.load();
        displayLanguage = ConstructorTexts.displayLanguage(config.queryLanguage());
        loadCatalog();
        addNavigation();
        presetButtons.clear();
        presetGrab = -1;
        int sideW = width < 540 ? 120 : 180;
        int left = 12;
        int top = 47;
        if (tab == 0) {
            EditBox search = new EditBox(font, left, top, sideW, 20, label("filter"));
            search.setHint(label("filter"));
            search.setValue(filter);
            structures = new ConstructorDropList(left, top + 25, sideW, text("structures"), List.of(), -1,
                    font, index -> addTarget(structureTargets.get(index)), this::openList).popupLimits(top, height - 42);
            biomes = new ConstructorDropList(left, top + 50, sideW, text("biomes"), List.of(), -1,
                    font, index -> addTarget(biomeTargets.get(index)), this::openList).popupLimits(top, height - 42);
            biomes.biomeIcons(index -> biomeTargets.get(index).canonical());
            refreshLists();
            search.setResponder(value -> {
                filter = value;
                refreshLists();
            });
            addRenderableWidget(search);
            addRenderableWidget(structures);
            addRenderableWidget(biomes);
        } else if (tab == 1) {
            initPresets(left, top, sideW);
        }        if (tab == 0) {
        Button undo = DiyVanillaButton.builder(label("undo"), b -> {
            draft.undo();
            rebuildWidgets();
        })
                .bounds(left, top + 78, sideW / 3 - 2, 20).build();
        undo.active = draft.canUndo();
        addRenderableWidget(undo);
        Button clear = DiyVanillaButton.builder(label("clear"), b -> {
            draft.clear();
            rebuildWidgets();
        })
                .bounds(left + sideW / 3, top + 78, sideW / 3 - 2, 20).build();
        clear.active = !draft.isEmpty();
        addRenderableWidget(clear);
        }
        addRenderableWidget(DiyVanillaButton.builder(MapTexts.label("structures_short"), b ->
                Minecraft.getInstance().setScreenAndShow(new StructureLayersScreen(this, structureLayers)))
                .bounds(left + sideW * 2 / 3 + 2, 28, sideW / 3 - 2, 16).build());
        addRenderableWidget(DiyVanillaButton.builder(MapTexts.label("dimension_" + dimension.name().toLowerCase(Locale.ROOT)), b -> {
            dimension = DimensionSpace.values()[(dimension.ordinal() + 1) % 3];
            filter = "";
            rebuildWidgets();
        }).bounds(left, 28, sideW * 2 / 3, 16).build());
        canvasX = left + sideW + 12;
        canvasY = top + 24;
        canvasW = width - canvasX - 12;
        canvasH = Math.max(1, height - canvasY - 109);
        boolean showCaves = dimension == DimensionSpace.OVERWORLD;
        var modes = new ArrayList<>(List.of(MapTexts.text("biomes_surface"), MapTexts.text("temperature")));
        if (showCaves) modes.add(MapTexts.text("caves"));
        addRenderableWidget(new ConstructorDropList(canvasX, top, Math.max(1, canvasW), MapTexts.text("biomes"),
                modes, temperature ? 1 : caves && showCaves ? 2 : 0, font, index -> {
                    temperature = index == 1;
                    caves = index == 2;
                    rebuildWidgets();
                }, this::openList).popupLimits(top, height - 42));
        centerX = canvasX + canvasW / 2;
        centerY = canvasY + canvasH / 2;
        baseRadius = Math.max(12, Math.min(canvasW, canvasH) / 2 - 22);
        if (fitNeeded) fitView();

        centerX += (int) panX;
        centerY += (int) panY;
        if (tab == 0) {
        Button example = DiyVanillaButton.builder(MapTexts.label("new_example_short"), b -> { variation++; })
                .bounds(left + sideW * 2 / 3, top + 78, sideW / 3 - 2, 20).build();
        example.setTooltip(Tooltip.create(MapTexts.label("example_help")));
        addRenderableWidget(example);
        }
        markers.clear();
        markerPlacements.clear();
        anchors.clear();
        for (SearchDraft.Placement placement : draft.placements()) {
            int size = placement.target().category() == KeywordDictionary.Category.BIOME
                    ? BiomeIcons.MAP_SIZE + 4 : 20;
            Button marker = DiyVanillaButton.builder(Component.empty(), b -> details(placement.target()))
                    .bounds(0, 0, size, size).build();
            markers.add(marker);
            markerPlacements.add(placement);
            anchors.add(new int[2]);
            addRenderableWidget(marker);
        }
        layoutMarkers();
        structureLayers.distanceTerms(draft.query(config.queryLanguage()));
        updateExampleView();
        if(exampleMap!=null){exampleMap.resume();exampleMap.settings(config);}
        if(paintedMap!=null&&regionResult!=null)sketch.upload(paintedMap.width(),paintedMap.height(),regionResult.pixels());
        if(tab==2){
            if(settings==null)settings=new ConstructorSettings(config);
            settings.bounds(left, 47, sideW, height - 92, draft.requiredSearchCenter());
            settings.widgets().forEach(this::addWidget);
        }
        if (targetEditor != null) targetEditor.bounds(width, height);
        addRenderableWidget(DiyVanillaButton.builder(MapTexts.label(borders ? "borders_on" : "borders_off"), b -> {
            borders = !borders;
            exampleMap.borders(borders);
            b.setMessage(MapTexts.label(borders ? "borders_on" : "borders_off"));
        }).bounds(canvasX,height-91,Math.max(24,(canvasW-8)/3),16).build());
        addRenderableWidget(DiyVanillaButton.builder(MapTexts.label("reset"),b->{
            panX=panY=0;
            fitNeeded=true;
            rebuildWidgets();
        })
                .bounds(canvasX+Math.max(24,(canvasW-8)/3)+4,height-91,Math.max(24,(canvasW-8)/3),16).build());
        addRenderableWidget(DiyVanillaButton.builder(MapTexts.label(draft.logic() == QueryLogic.Op.AND ? "all_conditions" : "any_condition"), b -> {
            draft.logic(draft.logic() == QueryLogic.Op.AND ? QueryLogic.Op.OR : QueryLogic.Op.AND);
            rebuildWidgets();
        }).bounds(canvasX+(Math.max(24,(canvasW-8)/3)+4)*2,height-91,Math.max(24,canvasW-(Math.max(24,(canvasW-8)/3)+4)*2),16).build());
        int restoreWidth = font.width("↺") + 16;
        prompt = new EditBox(font, 12, height - 66, width - 28 - restoreWidth, 20, label("prompt"));
        prompt.setMaxLength(20000);
        generatedQuery = draft.compile(config.queryLanguage());
        prompt.setValue(manualQuery == null ? generatedQuery : manualQuery);
        addRenderableWidget(prompt);
        int actionW = Math.min(140, (width - 32) / 3);
        int actionsX = (width - actionW * 3 - 12) / 2;
        Button apply = DiyVanillaButton.builder(label("apply"), b -> submit(false))
                .bounds(actionsX, height - 29, actionW, 20)
                .tooltip(Tooltip.create(label("apply_help"))).build();
        Button find = DiyVanillaButton.builder(label("find"), b -> submit(true))
                .bounds(actionsX + actionW + 6, height - 29, actionW, 20)
                .tooltip(Tooltip.create(label("find_help"))).build();
        Button restore = DiyVanillaButton.builder(Component.literal("↺"), b -> {
            manualQuery = null;
            updateGeneratedPrompt();
        }).bounds(width - 12 - restoreWidth, height - 66, restoreWidth, 20)
                .tooltip(Tooltip.create(MapTexts.label("marker_query_restore"))).build();
        Runnable updateActions = () -> {
            apply.active = find.active = !prompt.getValue().isBlank();
            restore.active = manualQuery != null;
        };
        prompt.setResponder(value -> {
            if (!updatingPrompt) manualQuery = value.equals(generatedQuery) ? null : value;
            updateActions.run();
        });
        updateActions.run();
        addRenderableWidget(restore);
        addRenderableWidget(apply);
        addRenderableWidget(find);
        addRenderableWidget(DiyVanillaButton.builder(label("back"), b -> onClose())
                .bounds(actionsX + (actionW + 6) * 2, height - 29, actionW, 20).build());
    }

    private void initPresets(int x, int top, int width) {
        presetX = x;
        presetTop = top;
        presetWidth = width;
        presetBottom = Math.max(top + 1, height - 98);
        for (var preset : SearchDraft.Preset.values()) {
            var title = label("preset." + preset.name().toLowerCase(Locale.ROOT));
            var button = DiyVanillaButton.builder(title, ignored -> {
                draft.preset(preset);
                manualQuery = null;
                variation++;
                fitNeeded = true;
                rebuildWidgets();
            }).bounds(x, top, Math.max(1, width - 6), 20).tooltip(Tooltip.create(title)).build();
            presetButtons.add(button);
            addWidget(button);
        }
        scrollPresets(presetScroll);
    }

    private int presetContentHeight() { return presetButtons.size() * 24; }
    private int presetWindowHeight() { return presetBottom - presetTop; }
    private int maxPresetScroll() { return Math.max(0, presetContentHeight() - presetWindowHeight()); }
    private int presetThumbHeight() {
        int area = presetWindowHeight();
        return Math.min(area, Math.max(12, area * area / Math.max(1, presetContentHeight())));
    }

    private int presetThumbTop() {
        return presetTop + (presetWindowHeight() - presetThumbHeight()) * presetScroll / Math.max(1, maxPresetScroll());
    }

    private boolean overPresets(double x, double y) {
        return !presetButtons.isEmpty() && x >= presetX && x < presetX + presetWidth
                && y >= presetTop && y < presetBottom;
    }

    private void scrollPresets(int scroll) {
        presetScroll = Math.clamp(scroll, 0, maxPresetScroll());
        for (int i = 0; i < presetButtons.size(); i++) {
            var button = presetButtons.get(i);
            int y = presetTop + i * 24 - presetScroll;
            button.setY(y);
            button.visible = y + button.getHeight() > presetTop && y < presetBottom;
        }
    }

    private void drawPresets(GuiGraphicsExtractor graphics, int mx, int my, float tick) {
        if (presetButtons.isEmpty()) return;
        graphics.enableScissor(presetX, presetTop, presetX + presetWidth, presetBottom);
        for (var button : presetButtons)
            if (button.visible) button.extractRenderState(graphics, mx, overPresets(mx, my) ? my : -1, tick);
        graphics.disableScissor();
        if (maxPresetScroll() > 0) {
            int edge = presetX + presetWidth;
            graphics.fill(edge - 3, presetTop, edge, presetBottom, 0x55000000);
            graphics.fill(edge - 3, presetThumbTop(), edge, presetThumbTop() + presetThumbHeight(), 0xAAFFFFFF);
        }
    }

    private void refreshLists() {
        String query = filter.strip().toLowerCase(Locale.ROOT);
        var targets = catalog.stream().filter(t -> DimensionSpace.of(t.canonical()) == dimension).filter(t -> (t.phrase(displayLanguage) + " " + t.english()
                + " " + t.russian() + " " + t.canonical()).toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(t -> t.phrase(displayLanguage))).toList();
        structureTargets = targets.stream().filter(t -> t.category() == KeywordDictionary.Category.STRUCTURE).toList();
        biomeTargets = targets.stream().filter(t -> t.category() == KeywordDictionary.Category.BIOME).toList();
        structures.setOptions(structureTargets.stream().map(t -> t.phrase(displayLanguage)).toList());
        biomes.setOptions(biomeTargets.stream().map(t -> t.phrase(displayLanguage)).toList());
    }

    private void openList(ConstructorDropList list) {
        if (openDrop != null && openDrop != list) openDrop.close();
        openDrop = list;
    }

    private int popupHeight() { return Math.max(0, height - 42 - openDrop.getY() - 20); }

    private void addTarget(SearchDraft.Target target) {
        dimension = DimensionSpace.of(target.canonical());
        long existing = draft.placements().stream().filter(p -> p.target().canonical().equals(target.canonical())).count();
        double distance = Math.log((target.equals(SearchDraft.Target.STRONGHOLD) ? 3008 : 200) / 64.0) / Math.log(64);
        double angle = Math.floorMod(target.canonical().hashCode(), 360) * Math.PI / 180 + existing * Math.PI / 6;
        draft.addPlacement(target, Math.cos(angle) * distance, Math.sin(angle) * distance);
        fitNeeded = true;
        rebuildWidgets();
    }

    private void details(SearchDraft.Target target) {
        for (int i = 0; i < markerPlacements.size(); i++) {
            if (!markerPlacements.get(i).target().equals(target)) continue;
            var marker = markers.get(i);
            details(target, marker.getX() + marker.getWidth() / 2, marker.getY() + marker.getHeight() / 2);
            return;
        }
    }

    private void details(SearchDraft.Target target, double x, double y) {
        guardEditor(() -> {
            draft.endMove();
            dragTarget = null;
            panning = false;
            setFocused(null);
            if (openDrop != null) openDrop.close();
            openDrop = null;
            targetEditor = new TargetEditor(draft, target, worldCreator, (int)x, (int)y,
                    this::closeTargetEditor, this::guardEditor);
            targetEditor.bounds(width, height);
        });
    }

    private void closeTargetEditor() {
        targetEditor = null;
        rebuildWidgets();
    }

    private void layoutMarkers() {
        for (int i = 0; i < markers.size(); i++) {
            SearchDraft.Placement placement = draft.placement(markerPlacements.get(i).target()).orElseThrow();
            markerPlacements.set(i, placement);
            int x = centerX + (int) (mapPlacement(placement).x() * baseRadius * sketchZoom);
            int y = centerY + (int) (mapPlacement(placement).y() * baseRadius * sketchZoom);
            anchors.set(i, new int[]{x, y});
            Button marker = markers.get(i);
            int size = marker.getWidth();
            int half = size / 2;
            marker.visible = DimensionSpace.of(placement.target().canonical()) == dimension
                    && x >= canvasX + half && x < canvasX + canvasW - half
                    && y >= canvasY + half && y < canvasY + canvasH - half;
            int[] slot = markerSlot(x - half, y - half, size, i);
            marker.setX(slot[0]);
            marker.setY(slot[1]);
            String condition = placement.countMode() == null
                    ? placement.minRadius() + ".." + placement.radius() + " " + text("blocks")
                    : MapTexts.text((placement.countMode().counted() ? "count_mode_" : "modifier_")
                            + placement.countMode().name().toLowerCase(Locale.ROOT));
            marker.setTooltip(Tooltip.create(Component.literal(placement.target().phrase(displayLanguage)
                    + " · " + condition
                    + " · " + text("marker_help") + " · "
                    + MapTexts.text("marker_lock_help"))));
        }
    }

    private int[] markerSlot(int x, int y, int size, int index) {
        int maxX = Math.max(canvasX + 2, canvasX + canvasW - size - 2);
        int maxY = Math.max(canvasY + 2, canvasY + canvasH - size - 2);
        int stride = size + 3;
        for (int step = 0; step <= 5; step++) {
            for (int dx = -step; dx <= step; dx++) {
                for (int dy = -step; dy <= step; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != step) continue;
                    int px = Math.clamp(x + dx * stride, canvasX + 2, maxX);
                    int py = Math.clamp(y + dy * stride, canvasY + 2, maxY);
                    boolean occupied = Math.abs(px + size / 2 - centerX) < 17 + size / 2
                            && Math.abs(py + size / 2 - (centerY + 8)) < 9 + size / 2;
                    for (int j = 0; j < index && !occupied; j++) {
                        Button previous = markers.get(j);
                        if (!previous.visible) continue;
                        int left = previous.getX(), top = previous.getY();
                        occupied = px < left + previous.getWidth() + 2 && px + size + 2 > left
                                && py < top + previous.getHeight() + 2 && py + size + 2 > top;
                    }
                    if (!occupied) return new int[]{px, py};
                }
            }
        }
        return new int[]{Math.clamp(x, canvasX + 2, maxX), Math.clamp(y, canvasY + 2, maxY)};
    }

    private void submit(boolean find) {
        if (worldCreator == null) return;
        if(targetEditor!=null){if(!targetEditor.applyToDraft())return;targetEditor=null;}
        if(settings!=null)settings.flush();
        String query = manualQuery == null ? draft.compile(config.queryLanguage()) : manualQuery;
        if (query.isBlank()) return;
        ParsedQuery compiled = manualQuery == null ? draft.query(config.queryLanguage())
                : new com.wywf.core.QueryParser(new KeywordDictionary(config.queryLanguage())).parse(query);
        if (manualQuery != null) {
            var corrections = DidYouMeanScreen.findCorrections(compiled.ignoredWords());
            if (!corrections.isEmpty()) {
                Minecraft.getInstance().setScreenAndShow(new DidYouMeanScreen(
                        this, query, corrections, compiled.error() == null && !compiled.isEmpty(),
                        corrected -> acceptManualQuery(corrected, find)));
                return;
            }
            if (compiled.error() != null || compiled.isEmpty()) {
                Minecraft.getInstance().setScreenAndShow(new QuerySyntaxScreen(
                        this, compiled, corrected -> acceptManualQuery(corrected, find)));
                return;
            }
        }
        finishSubmit(query, compiled, find);
    }

    private void acceptManualQuery(String query, boolean find) {
        manualQuery = query;
        ParsedQuery parsed = new com.wywf.core.QueryParser(new KeywordDictionary(config.queryLanguage())).parse(query);
        if (parsed.error() != null || parsed.isEmpty()) {
            Minecraft.getInstance().setScreenAndShow(new QuerySyntaxScreen(
                    this, parsed, corrected -> acceptManualQuery(corrected, find)));
            return;
        }
        finishSubmit(query, parsed, find);
    }

    private void updateGeneratedPrompt() {
        generatedQuery = draft.compile(config.queryLanguage());
        if (manualQuery == null) {
            updatingPrompt = true;
            try {
                prompt.setValue(generatedQuery);
            } finally {
                updatingPrompt = false;
            }
        }
    }

    private void finishSubmit(String query, ParsedQuery compiled, boolean find) {
        SearchConfig.SearchCenter center = compiled.place() == null ? draft.requiredSearchCenter() : null;
        WorldCreator.applySearchCenter(worldCreator, query, center);
        seedWriter.accept(query);
        worldCreator.getUiState().setSeed(query);
        if (!find) {
            releaseMap();
            Minecraft.getInstance().setScreenAndShow(worldCreator);
        } else {
            SearchConfig searchConfig = center == null ? config.copy() : config.copy().searchCenter(center);
            if (QueryAdvisor.inspect(compiled, searchConfig).reviewBeforeSearch()) {
                releaseMap();
                Minecraft.getInstance().setScreenAndShow(new SearchAdviceScreen(worldCreator, query, compiled, searchConfig));
            } else {
                new SearchScreen(worldCreator, query, compiled, searchConfig).open(this::releaseMap);
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean focusedViaKeyboard) {
        if(unsavedPrompt!=null)return unsavedPrompt.click(event,focusedViaKeyboard);
        if (targetEditor != null) {
            if (targetEditor.click(event, focusedViaKeyboard)) return true;
            if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                for (int i = markers.size() - 1; i >= 0; i--) {
                    var marker = markers.get(i);
                    if (!marker.visible || !marker.isMouseOver(event.x(), event.y())) continue;
                    details(markerPlacements.get(i).target(), event.x(), event.y());
                    return true;
                }
            }
            guardEditor(this::closeTargetEditor);
            return true;
        }
        var panel=settings;
        if(tab==2&&panel!=null&&panel.click(event,focusedViaKeyboard)){
            if(settings==panel&&tab==2)setFocused(panel.focused());return true;
        }
        if (overPresets(event.x(), event.y())) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && maxPresetScroll() > 0
                    && event.x() >= presetX + presetWidth - 5) {
                presetGrab = Math.clamp((int)event.y() - presetThumbTop(), 0, presetThumbHeight());
                return true;
            }
        }
        if (openDrop != null && openDrop.isOpen()) {
            ConstructorDropList list = openDrop;
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && list.thumbHit((float) event.x(), (float) event.y(), popupHeight())) {
                list.beginPopupDrag((float) event.y(), popupHeight());
                return true;
            }
            if (list.popupHit((float) event.x(), (float) event.y(), popupHeight())) {
                if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) list.handlePopupClick((float) event.x(), (float) event.y(), popupHeight());
                return true;
            }
            boolean headerClick = list.isMouseOver(event.x(), event.y());
            list.close();
            openDrop = null;
            if (headerClick) return true;
        }
        for (int i = markers.size() - 1; i >= 0; i--) {
            if (!markers.get(i).visible || !markers.get(i).isMouseOver(event.x(), event.y())) continue;
            SearchDraft.Placement placement = markerPlacements.get(i);
            if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                details(placement.target(), event.x(), event.y());
                return true;
            }
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                if (event.hasShiftDown()) {
                    draft.toggleMarkerLock(placement.target());
                    return true;
                }
                if (draft.markerLocked(placement.target())) return true;
                dragTarget = placement.target();
                grabX = event.x();
                grabY = event.y();
                originalX = mapPlacement(placement).x();
                originalY = mapPlacement(placement).y();
                draft.beginMove(dragTarget);
                return true;
            }
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && onCanvas(event.x(), event.y())) {
            panning = true;
            return true;
        }
        return super.mouseClicked(event, focusedViaKeyboard);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if(unsavedPrompt!=null)return true;
        if(targetEditor!=null&&targetEditor.drag(event,dx,dy))return true;
        if(tab==2&&settings!=null&&settings.drag(event,dx,dy))return true;
        if (presetGrab >= 0) {
            int travel = Math.max(1, presetWindowHeight() - presetThumbHeight());
            scrollPresets((int)Math.round((event.y() - presetTop - presetGrab) * maxPresetScroll() / travel));
            return true;
        }
        if (openDrop != null && openDrop.isPopupDrag()) {
            openDrop.dragPopupTo((float) event.y(), popupHeight());
            return true;
        }
        if (panning) {
            panX += dx;
            panY += dy;
            centerX = canvasX + canvasW / 2 + (int) panX;
            centerY = canvasY + canvasH / 2 + (int) panY;
            layoutMarkers();
            updateExampleView();
            return true;
        }
        if (dragTarget != null) {
            double wx = originalX + (event.x() - grabX) / (baseRadius * sketchZoom);
            double wz = originalY + (event.y() - grabY) / (baseRadius * sketchZoom);
            double distance = Math.max(0, Math.log(Math.max(64, Math.hypot(wx, wz) * 512) / 64.0) / Math.log(64));
            double angle = Math.atan2(wz, wx);
            draft.move(dragTarget, Math.cos(angle) * distance, Math.sin(angle) * distance);
            layoutMarkers();
            updateGeneratedPrompt();
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if(unsavedPrompt!=null)return true;
        if(targetEditor!=null)targetEditor.release(event);
        if(tab==2&&settings!=null)settings.release(event);
        presetGrab = -1;
        panning = false;
        if (openDrop != null) openDrop.endPopupDrag();
        if (dragTarget != null) {
            draft.endMove();
            dragTarget = null;
            rebuildWidgets();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if(unsavedPrompt!=null)return true;
        if(targetEditor!=null&&targetEditor.scroll(x,y,vertical))return true;
        if(tab==2&&settings!=null&&settings.scroll(x,y,vertical))return true;
        if (openDrop != null && openDrop.isOpen() && openDrop.popupHit((float) x, (float) y, popupHeight())) {
            openDrop.scrollPopup((float) vertical, popupHeight());
            return true;
        }
        if (overPresets(x, y)) {
            scrollPresets(presetScroll - (int)Math.round(vertical * 24));
            return true;
        }
        if (onCanvas(x, y) && vertical != 0 && dragTarget == null) {
            if(fitNeeded){fitView();updateExampleView();}
            if(exampleMap==null)updateExampleView();exampleMap.zoom(vertical,x,y);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if(unsavedPrompt!=null)return unsavedPrompt.key(event);
        if(targetEditor!=null&&targetEditor.key(event))return true;
        if(tab==2&&settings!=null&&settings.key(event))return true;
        if (openDrop != null && openDrop.isOpen() && openDrop.keyPressed(event)) return true;
        if (event.key() == InputConstants.KEY_HOME && getFocused() != prompt) {
            panX = panY = 0;
            fitNeeded = true;
            rebuildWidgets();
            return true;
        }
        if (navigation != null && navigation.keyPressed(event)) return true;
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int overlayMouseX=mouseX,overlayMouseY=mouseY;
        if(unsavedPrompt!=null){mouseX=-10000;mouseY=-10000;}
        advanceMapZoom();
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, Screen.FOOTER_SEPARATOR,
                0, height - 35, 0, 0, width, 2, 32, 2);

        String seedCaption = MapTexts.text(adaptation.searching() ? "fitting_seed" : illustrative
                ? temperature ? "illustrated_heat" : "illustrated_seed" : "real_example", exampleSeed);
        g.centeredText(font, font.plainSubstrByWidth(seedCaption, canvasW), canvasX + canvasW / 2, 32, 0xFFFFFFFF);
        g.fill(canvasX, canvasY, canvasX + canvasW, canvasY + canvasH, 0xEB172126);
        if (exampleMap != null) exampleMap.draw(g, false);
        g.enableScissor(canvasX, canvasY, canvasX + canvasW, canvasY + canvasH);
        if (illustrative && !temperature && paintedMap != null) {
            int sx = canvasX + (int) Math.floor(exampleMap.view.pixelX(paintedMap.x()));
            int sz = canvasY + (int) Math.floor(exampleMap.view.pixelZ(paintedMap.z()));
            int ex = canvasX + (int) Math.floor(exampleMap.view.pixelX(paintedMap.x() + paintedMap.width() * paintedMap.step()));
            int ez = canvasY + (int) Math.floor(exampleMap.view.pixelZ(paintedMap.z() + paintedMap.height() * paintedMap.step()));
            sketch.draw(g, sx, sz, ex - sx, ez - sz);
        }
        if (exampleMap != null) exampleMap.drawMarkers(g);
        var requiredCenter = draft.requiredSearchCenter();
        exampleMap.drawCross(g, (requiredCenter == null ? config.searchCenter() : requiredCenter).name());
        g.disableScissor();
        var hovered = exampleMap.hovered(mouseX, mouseY);
        String biomeText = hovered == null ? "" : ResultTexts.name(hoveredBiome(mouseX, mouseY, hovered.id()));
        String info = biomeText + (biomeText.isEmpty() ? "" : " · ") + MapTexts.text("scale", exampleMap.view.scale())
                + (caves && dimension == DimensionSpace.OVERWORLD ? " · Y=-50" : "");
        g.text(font, font.plainSubstrByWidth(info, canvasW), canvasX, height - 106, 0xFFFFFFFF);
        g.text(font, MapTexts.label(manualQuery == null ? "seed_query_map" : "seed_query_manual"),
                12, height - 78, 0xFFFFFFFF);
        for (int i = 0; i < markers.size(); i++) {
            Button marker = markers.get(i);
            if (!marker.visible) continue;
            int[] point = anchors.get(i);
            if (marker.isMouseOver(point[0], point[1])) continue;
            int x = marker.getX() + marker.getWidth() / 2;
            int y = marker.getY() + marker.getHeight() / 2;
            g.fill(Math.min(point[0], x), point[1], Math.max(point[0], x) + 1, point[1] + 1, 0xFFBAC9CF);
            g.fill(x, Math.min(point[1], y), x + 1, Math.max(point[1], y) + 1, 0xFFBAC9CF);
            g.fill(point[0] - 1, point[1] - 1, point[0] + 2, point[1] + 2, 0xFFE6EFF2);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        drawPresets(g, mouseX, mouseY, partialTick);
        if(tab==2&&settings!=null)settings.draw(g,mouseX,mouseY,partialTick);
        for (int i = 0; i < markers.size(); i++) {
            Button marker = markers.get(i);
            if (!marker.visible) continue;
            SearchDraft.Target target = markerPlacements.get(i).target();
            if (target.category() == KeywordDictionary.Category.BIOME) {
                BiomeIcons.draw(g, target.canonical(), marker.getX() + 2, marker.getY() + 2, BiomeIcons.MAP_SIZE);
            } else {
                Item icon = icon(target);
                if (icon != null) {
                    MenuItemIcon.draw(g, icon, marker.getX() + 2, marker.getY() + 2);
                } else {
                    int color = 0xFF000000 | (target.canonical().hashCode() & 0x7F7F7F) | 0x404040;
                    g.fill(marker.getX() + 4, marker.getY() + 4, marker.getX() + 16, marker.getY() + 16, color);
                }
            }
            var placement = markerPlacements.get(i);
            String name = markerModifier(placement);
            int badgeWidth = font.width(name);
            int badgeX = Math.clamp(marker.getX() + marker.getWidth() / 2 - badgeWidth / 2, canvasX + 1,
                    Math.max(canvasX + 1, canvasX + canvasW - badgeWidth - 1));
            int badgeY = Math.max(canvasY, marker.getY() - font.lineHeight - 1);
            g.fill(badgeX - 1, badgeY, badgeX + badgeWidth + 1, badgeY + font.lineHeight, 0xDF212520);
            g.text(font, name, badgeX, badgeY, MapTexts.EMPHASIS);
            if (draft.markerLocked(target)) {
                int px = marker.getX() + marker.getWidth() - 7, py = marker.getY() + 2;
                g.fill(px + 1, py, px + 4, py + 4, 0xFFE8EDDE);
                g.fill(px + 2, py + 1, px + 3, py + 3, 0xFF202820);
                g.fill(px, py + 3, px + 5, py + 7, 0xFFE8EDDE);
            }
        }
        if(tab==2&&settings!=null)settings.popup(g,mouseX,mouseY);
        if (tab != 2) {
            if (retiringDrop != null && retiringDrop.isPopupVisible()) {
                var old = retiringDrop;
                boolean sameAnchor = children().stream().anyMatch(child -> child instanceof ConstructorDropList list
                        && list.getX() == old.getX() && list.getY() == old.getY() && list.getWidth() == old.getWidth());
                if (sameAnchor) old.drawPopup(g, mouseX, mouseY, Math.max(0, height - 42 - old.getBottom()), 1);
            } else {
                retiringDrop = null;
            }
            for (var child : children()) {
                if (child instanceof ConstructorDropList list && list != openDrop)
                    list.drawPopup(g, mouseX, mouseY, Math.max(0, height - 42 - list.getBottom()), 1);
            }
        }
        if (openDrop != null) openDrop.drawPopup(g, mouseX, mouseY, popupHeight(), 1);
        if (targetEditor != null) targetEditor.draw(g, mouseX, mouseY, partialTick);
        if(unsavedPrompt!=null){
            unsavedPrompt.bounds(width,height);
            unsavedPrompt.draw(g,overlayMouseX,overlayMouseY,partialTick);
        }
    }

    private static String markerModifier(SearchDraft.Placement placement) {
        Modifier modifier = placement.countMode();
        if (modifier == null || modifier == Modifier.BETWEEN)
            return placement.minRadius() + "–" + placement.radius();
        return switch (modifier) {
            case AT_LEAST -> "≥" + placement.quantity();
            case AT_MOST -> "≤" + placement.quantity();
            case EXACTLY -> "=" + placement.quantity();
            case SOME -> "×" + placement.quantity();
            case ONLY -> "1";
            default -> MapTexts.text("modifier_short_" + modifier.name().toLowerCase(Locale.ROOT));
        };
    }

    private String hoveredBiome(double x, double y, String actual) {
        if (!illustrative || temperature || paintedMap == null || regionResult == null) return actual;
        int wx = (int) Math.floor(exampleMap.view.worldX(x - canvasX));
        int wz = (int) Math.floor(exampleMap.view.worldZ(y - canvasY));
        int cx = Math.floorDiv(wx - paintedMap.x(), paintedMap.step()), cz = Math.floorDiv(wz - paintedMap.z(), paintedMap.step());
        if (cx < 0 || cz < 0 || cx >= paintedMap.width() || cz >= paintedMap.height()) return actual;
        int color = regionResult.pixels()[cz * paintedMap.width() + cx];
        for (var goal : fitGoals) if (com.wywf.map.BiomePalette.color(goal.biome()) == color) return goal.biome();
        return actual;
    }
    private boolean onCanvas(double x, double y) {
        return x >= canvasX && x < canvasX + canvasW && y >= canvasY && y < canvasY + canvasH;
    }

    private void fitView() {
        double scale = 4;
        for (var p : draft.placements()) {
            double angle = Math.atan2(p.y(), p.x());
            scale = Math.max(scale, Math.abs(Math.cos(angle) * p.radius()) / Math.max(8, canvasW / 2.0 - 14));
            scale = Math.max(scale, Math.abs(Math.sin(angle) * p.radius()) / Math.max(8, canvasH / 2.0 - 14));
        }
        sketchZoom = 512 / (baseRadius * scale);
        panX = panY = 0;
        fitNeeded = false;
    }

    private SearchDraft.Placement mapPlacement(SearchDraft.Placement p) {
        double angle = Math.atan2(p.y(), p.x());
        return new SearchDraft.Placement(p.target(), Math.cos(angle) * p.radius() / 512,
                Math.sin(angle) * p.radius() / 512, p.minRadius(), p.radius());
    }

    private void updateExampleView() {
        var fit = adaptation.result();
        long seed = fit != null && fit.matched() ? fit.seed() : variation;
        if (exampleMap == null || exampleSeed != seed) {
            if (exampleMap != null) exampleMap.close();
            exampleSeed = seed;
            exampleMap = new BiomeMapCanvas(new SearchResult(seed, 0, 0, 0, 0, "example",
                    List.of(), List.of(), ""), 512, null, structureLayers);
            paintedMap = null;
        }
        exampleMap.dimension(dimension);
        exampleMap.bounds(canvasX, canvasY, canvasW, canvasH);
        double scale = 512.0 / (baseRadius * sketchZoom);
        exampleMap.view.reset(-panX * scale, -panY * scale, scale);
        exampleMap.dirty();
    }
    private void advanceMapZoom(){
        if(exampleMap==null)return;
        if(exampleMap.view.advance())exampleMap.dirty();
        if(!exampleMap.view.zooming()&&sketchZoom==512d/(baseRadius*exampleMap.view.scale()))return;
        double scale=exampleMap.view.scale();
        sketchZoom=512d/(baseRadius*scale);
        panX=-exampleMap.view.x()/scale;
        panY=-exampleMap.view.z()/scale;
        centerX=canvasX+canvasW/2+(int)panX;
        centerY=canvasY+canvasH/2+(int)panY;
        layoutMarkers();
    }
    @Override public void tick() {
        advanceMapZoom();
        if(settings!=null)settings.tick();
        adaptation.fitSeconds(config.exampleFitSeconds());
        var goals = com.wywf.map.PreviewAdaptation.goals(draft.placements());
        if (fitVariation != variation || !goals.equals(fitGoals)) {
            fitGoals = goals;
            fitVariation = variation;
            illustrative = false;
            paintedMap = null;
            adaptation.request(variation, goals);
        }
        boolean paused=config.pauseMapInactive()&&!Minecraft.getInstance().isWindowActive();
        adaptation.paused(paused);
        regions.paused(paused);
        var fit = adaptation.result();
        illustrative = fit != null && !fit.matched() && !goals.isEmpty();
        long seed = fit != null && fit.matched() ? fit.seed() : variation;
        if (exampleMap == null || exampleSeed != seed) updateExampleView();
        exampleMap.settings(config);
        exampleMap.dimension(dimension);
        exampleMap.temperature(temperature);
        exampleMap.borders(borders);
        exampleMap.y(caves && dimension == DimensionSpace.OVERWORLD ? -50 : 64);
        exampleMap.tick();
        if (paused || !illustrative) return;
        var base = exampleMap.snapshot();
        if (base == null || !base.complete()) return;
        regions.request(base, goals.stream().filter(g -> DimensionSpace.of(g.biome()) == dimension).toList());
        var next = regions.result();
        if (next == null || next == regionResult) return;
        regionResult = next;
        paintedMap = next.map();
        sketch.upload(paintedMap.width(), paintedMap.height(), next.pixels());
    }
    @Override public void removed() {
        if(settings!=null)settings.flush();
        if(adaptation.result()==null){adaptation.close();fitVariation=Long.MIN_VALUE;}
        regions.close();
        sketch.close();
        if(exampleMap!=null)exampleMap.suspend();
    }
        private static Item icon(SearchDraft.Target target) { return icon(target.canonical()); }
    static Item icon(String id) {

        if (id.contains("village")) return Items.VILLAGER_SPAWN_EGG;
        return switch (id) {
            case "minecraft:stronghold" -> Items.ENDER_EYE;
            case "wywf:slime_chunk" -> Items.SLIME_BALL;
            case "wywf:lava_pool" -> Items.LAVA_BUCKET;
            case "wywf:amethyst_geode" -> Items.AMETHYST_SHARD;
            case "wywf:iron_vein" -> Items.RAW_IRON;
            case "wywf:copper_vein" -> Items.RAW_COPPER;
            case "wywf:spawner" -> Items.SPAWNER;
            case com.wywf.verify.EndReturnGateways.ID -> Items.ENDER_PEARL;
            case "minecraft:cherry_grove" -> Items.CHERRY_SAPLING;
            case "minecraft:forest" -> Items.OAK_SAPLING;
            case "minecraft:desert" -> Items.SAND;
            case "minecraft:mushroom_fields" -> Items.RED_MUSHROOM;
            case "minecraft:desert_pyramid" -> Items.CHISELED_SANDSTONE;
            case "minecraft:ruined_portal", "minecraft:ruined_portal_nether" -> Items.OBSIDIAN;
            default -> null;
        };
    }

    private static String text(String key) { return ConstructorTexts.tr("wywf.constructor." + key); }

    private void releaseMap(){
        if(exampleMap!=null){exampleMap.close();exampleMap=null;}
        paintedMap=null;
        regionResult=null;
        regions.close();
        sketch.close();
    }
    private void guardEditor(Runnable action){
        var editor=targetEditor;
        if(editor==null||!editor.dirty()){action.run();return;}
        editor.pauseInput();
        setFocused(null);
        panning=false;
        dragTarget=null;
        draft.endMove();
        Runnable keep = () -> unsavedPrompt = null;
        unsavedPrompt = new MenuModal(font, label("unsaved_title"), label("unsaved_body"), editor::canApply, keep,
                DiyVanillaButton.builder(label("unsaved_save"), button -> {
                    if (editor.applyToDraft()) {
                        unsavedPrompt = null;
                        action.run();
                    }
                }).build(),
                DiyVanillaButton.builder(label("unsaved_discard"), button -> {
                    unsavedPrompt = null;
                    targetEditor = null;
                    action.run();
                }).build(),
                DiyVanillaButton.builder(label("unsaved_keep"), button -> keep.run()).build());
        unsavedPrompt.bounds(width,height);
    }
    @Override public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (unsavedPrompt != null) return true;
        if (targetEditor != null) return targetEditor.charTyped(event);
        return super.charTyped(event);
    }
    @Override public boolean keyReleased(KeyEvent event){return unsavedPrompt!=null||super.keyReleased(event);}
    @Override
    public void onClose(){guardEditor(this::closeConstructor);}
    private void closeConstructor() {
        draft.endMove();
        releaseMap();
        Minecraft.getInstance().setScreenAndShow(backScreen);
    }
}
