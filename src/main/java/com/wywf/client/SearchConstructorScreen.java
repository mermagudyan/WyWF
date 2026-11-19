package com.wywf.client;

import com.wywf.core.*;

import com.wywf.mixin.CreateWorldScreenInvoker;
import com.wywf.search.MinecraftWorldContextFactory;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    private final CreateWorldScreen parent;
    private final Screen returnTo;
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
    public static void openSettings(Screen parent) {
        SearchConstructorScreen screen;
        if(parent instanceof SearchConstructorScreen constructor)
            screen=constructor;
        else if(parent instanceof CreateWorldScreen creator)
            screen=forCreator(creator,value->creator.getUiState().setSeed(value),new SearchDraft());
        else screen=new SearchConstructorScreen(parent);
        screen.guardEditor(()->{
            screen.tab=2;
            if(Minecraft.getInstance().gui.screen()==screen)
                screen.rebuildWidgets();
            else Minecraft.getInstance().setScreenAndShow(screen);
        });
    }
    private SearchConstructorScreen(Screen parent) {
        super(Component.literal("WyWF"));
        this.parent=null;
        returnTo=parent;
        seedWriter=value->{};
        draft=new SearchDraft();
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
    private ConstructorDropList openDrop;
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
    private DimensionSpace dimension = DimensionSpace.OVERWORLD;
    private boolean fitNeeded = true;
    private int baseRadius;

    public SearchConstructorScreen(CreateWorldScreen parent, Consumer<String> seedWriter, SearchDraft draft) {
        super(Component.literal("WyWF"));
        this.parent = parent; this.returnTo=parent;
        SCREENS.put(parent,new java.lang.ref.WeakReference<>(this));
        this.seedWriter = seedWriter;
        this.draft = draft;
    }

    CreateWorldScreen creator() { return parent; }

    public static Component label(String suffix) {
        return Component.literal(ConstructorTexts.tr("wywf.constructor." + suffix));
    }

    private net.minecraft.client.gui.components.tabs.MenuTabBar navigation;

    private record ConstructorTab(int index, Component getTabTitle)
            implements net.minecraft.client.gui.components.tabs.Tab {
        @Override public Component getTabExtraNarration() {
            return Component.empty();
        }
        @Override public void visitChildren(java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> visitor) {}
        @Override public void doLayout(net.minecraft.client.gui.navigation.ScreenRectangle area) {}
        @Override public net.minecraft.client.gui.layouts.Layout getLayout() {
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
                tab = next;
                rebuildWidgets();
            });
            if (unsavedPrompt != null)
                navigation.selectTab(tab, false);
        }, selected -> {});
        navigation = addRenderableWidget(net.minecraft.client.gui.components.tabs.MenuTabBar.builder(manager, width)
                .addTabs(tabs).build());
        navigation.arrangeElements(width);
        navigation.selectTab(tab, false);
        if (parent == null) {
            navigation.setTabActiveState(0, false);
            navigation.setTabActiveState(1, false);
        }
    }

    @Override
    protected void extractMenuBackground(GuiGraphicsExtractor g) {
        int header = navigation == null ? 24 : navigation.getHeight();
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
        openDrop = null;
        if(config==null)
            config=ConfigStore.load();
        displayLanguage = ConstructorTexts.displayLanguage(config.queryLanguage());
        loadCatalog();
        addNavigation();
        int sideW = targetEditor!=null&&tab!=2 ? Math.min(width-140,Math.max(180,Math.min(250,width/3))) : width < 540 ? 120 : 180;
        int left = 12;
        int top = 47;
        if (tab == 0 && targetEditor == null) {
            EditBox search = new EditBox(font, left, top, sideW, 20, label("filter"));
            search.setHint(label("filter"));
            search.setValue(filter);
            structures = new ConstructorDropList(left, top + 25, sideW, text("structures"), List.of(), -1,
                    font, index -> addTarget(structureTargets.get(index)), this::openList);
            biomes = new ConstructorDropList(left, top + 50, sideW, text("biomes"), List.of(), -1,
                    font, index -> addTarget(biomeTargets.get(index)), this::openList);
            refreshLists();
            search.setResponder(value -> {
                filter = value; refreshLists();
            });
            addRenderableWidget(search);
            addRenderableWidget(structures);
            addRenderableWidget(biomes);
        } else if(tab==1 && targetEditor == null) {
            int row = 0;
            for (SearchDraft.Preset preset : SearchDraft.Preset.values()) {
                addRenderableWidget(Button.builder(label("preset." + preset.name().toLowerCase(Locale.ROOT)), b -> {
                    draft.preset(preset); variation++; fitNeeded = true;
                    rebuildWidgets();
                }).bounds(left, top + row++ * 24, sideW, 20).build());
            }
        }
        if(tab!=2 && targetEditor==null) {
        Button undo = Button.builder(label("undo"), b -> {
            draft.undo(); rebuildWidgets();
        })
                .bounds(left, top + 78, sideW / 3 - 2, 20).build();
        undo.active = draft.canUndo();
        addRenderableWidget(undo);
        Button clear = Button.builder(label("clear"), b -> {
            draft.clear(); rebuildWidgets();
        })
                .bounds(left + sideW / 3, top + 78, sideW / 3 - 2, 20).build();
        clear.active = !draft.isEmpty();
        addRenderableWidget(clear);
        }
        addRenderableWidget(Button.builder(MapTexts.label("structures_short"), b ->
                Minecraft.getInstance().setScreenAndShow(new StructureLayersScreen(this, structureLayers)))
                .bounds(left + sideW * 2 / 3 + 2, 28, sideW / 3 - 2, 16).build());
        addRenderableWidget(Button.builder(MapTexts.label("dimension_" + dimension.name().toLowerCase(Locale.ROOT)), b -> {
            dimension = DimensionSpace.values()[(dimension.ordinal() + 1) % 3]; filter = ""; rebuildWidgets();
        }).bounds(left, 28, sideW * 2 / 3, 16).build());
        canvasX = left + sideW + 12;
        canvasY = top + 24;
        canvasW = width - canvasX - 12;
        canvasH = Math.max(52, height - canvasY - 109);
        int biomeW = Math.max(36, canvasW / 4), caveW = Math.max(36, canvasW / 5);
        int heatW = canvasW - biomeW - caveW - 8;
        Button biomeLayer = addRenderableWidget(Button.builder(MapTexts.label("biomes"), b -> { temperature = false; rebuildWidgets(); })
                .bounds(canvasX, top, biomeW, 20).build());
        Button heatLayer = addRenderableWidget(Button.builder(MapTexts.label("temperature"), b -> { temperature = true; rebuildWidgets(); })
                .bounds(canvasX + biomeW + 4, top, heatW, 20).build());
        biomeLayer.active = temperature; heatLayer.active = !temperature;
        addRenderableWidget(Button.builder(MapTexts.label("caves"), b -> { caves = !caves; rebuildWidgets(); })
                .bounds(canvasX + biomeW + heatW + 8, top, caveW, 20).build()).active = dimension == DimensionSpace.OVERWORLD;
        centerX = canvasX + canvasW / 2;
        centerY = canvasY + canvasH / 2;
        baseRadius = Math.max(12, Math.min(canvasW, canvasH) / 2 - 22);
        if (fitNeeded) fitView();

        centerX += (int) panX;
        centerY += (int) panY;
        if(tab!=2 && targetEditor==null) {
        Button example = Button.builder(MapTexts.label("new_example_short"), b -> { variation++; })
                .bounds(left + sideW * 2 / 3, top + 78, sideW / 3 - 2, 20).build();
        example.setTooltip(Tooltip.create(MapTexts.label("example_help")));
        addRenderableWidget(example);
        }
        markers.clear();
        markerPlacements.clear();
        anchors.clear();
        for (SearchDraft.Placement placement : draft.placements()) {
            Button marker = Button.builder(Component.empty(), b -> details(placement.target()))
                    .bounds(0, 0, 20, 20).build();
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
            settings.bounds(left,47,sideW,height-92);
            settings.widgets().forEach(this::addWidget);
        }
        if(tab!=2&&targetEditor!=null) {
            targetEditor.bounds(left,top,sideW,height-96);
            targetEditor.widgets().forEach(this::addWidget);
            if(targetEditor.focused()!=null)setFocused(targetEditor.focused());
        }
        addRenderableWidget(Button.builder(MapTexts.label(borders ? "borders_on" : "borders_off"), b -> {
            borders = !borders; exampleMap.borders(borders); b.setMessage(MapTexts.label(borders ? "borders_on" : "borders_off"));
        }).bounds(canvasX,height-91,Math.max(24,(canvasW-8)/3),16).build());
        addRenderableWidget(Button.builder(MapTexts.label("reset"),b->{panX=panY=0;fitNeeded=true;rebuildWidgets();})
                .bounds(canvasX+Math.max(24,(canvasW-8)/3)+4,height-91,Math.max(24,(canvasW-8)/3),16).build());
        addRenderableWidget(Button.builder(MapTexts.label(draft.logic() == QueryLogic.Op.AND ? "all_conditions" : "any_condition"), b -> {
            draft.logic(draft.logic() == QueryLogic.Op.AND ? QueryLogic.Op.OR : QueryLogic.Op.AND); rebuildWidgets();
        }).bounds(canvasX+(Math.max(24,(canvasW-8)/3)+4)*2,height-91,Math.max(24,canvasW-(Math.max(24,(canvasW-8)/3)+4)*2),16).build());
        int restoreWidth = font.width("↺") + 16;
        prompt = new EditBox(font, 12, height - 66, width - 28 - restoreWidth, 20, label("prompt"));
        prompt.setMaxLength(20000);
        generatedQuery = draft.compile(config.queryLanguage());
        prompt.setValue(manualQuery == null ? generatedQuery : manualQuery);
        addRenderableWidget(prompt);
        int actionW = Math.min(140, (width - 32) / 3);
        int actionsX = (width - actionW * 3 - 12) / 2;
        Button apply = Button.builder(label("apply"), b -> submit(false))
                .bounds(actionsX, height - 29, actionW, 20)
                .tooltip(Tooltip.create(label("apply_help"))).build();
        Button find = Button.builder(label("find"), b -> submit(true))
                .bounds(actionsX + actionW + 6, height - 29, actionW, 20)
                .tooltip(Tooltip.create(label("find_help"))).build();
        Button restore = Button.builder(Component.literal("↺"), b -> {
            manualQuery = null;
            updateGeneratedPrompt();
        }).bounds(width - 12 - restoreWidth, height - 66, restoreWidth, 20)
                .tooltip(Tooltip.create(MapTexts.label("marker_query_restore"))).build();
        Runnable updateActions = () -> {
            apply.active = find.active = parent != null && !prompt.getValue().isBlank();
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
        addRenderableWidget(Button.builder(label("back"), b -> onClose())
                .bounds(actionsX + (actionW + 6) * 2, height - 29, actionW, 20).build());
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
        guardEditor(()->{
            draft.endMove();
            targetEditor=new TargetEditor(draft,target,parent,this::rebuildWidgets,()->{targetEditor=null;rebuildWidgets();},this::guardEditor);
            rebuildWidgets();
        });
    }

    private void layoutMarkers() {
        for (int i = 0; i < markers.size(); i++) {
            SearchDraft.Placement placement = draft.placement(markerPlacements.get(i).target()).orElseThrow();
            markerPlacements.set(i, placement);
            int x = centerX + (int) (mapPlacement(placement).x() * baseRadius * sketchZoom);
            int y = centerY + (int) (mapPlacement(placement).y() * baseRadius * sketchZoom);
            anchors.set(i, new int[]{x, y});
            Button marker = markers.get(i);
            marker.visible = DimensionSpace.of(placement.target().canonical()) == dimension && x >= canvasX + 10 && x < canvasX + canvasW - 10 && y >= canvasY + 10 && y < canvasY + canvasH - 10;
            int[] slot = markerSlot(x - 10, y - 10, i);
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

    private int[] markerSlot(int x, int y, int index) {
        for (int step = 0; step <= 5; step++) {
            for (int dx = -step; dx <= step; dx++) {
                for (int dy = -step; dy <= step; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != step) continue;
                    int px = Math.max(canvasX + 2, Math.min(canvasX + canvasW - 22, x + dx * 23));
                    int py = Math.max(canvasY + 2, Math.min(canvasY + canvasH - 22, y + dy * 23));
                    boolean occupied = Math.abs(px + 10 - centerX) < 27 && Math.abs(py + 10 - (centerY + 8)) < 19;
                    for (int j = 0; j < index; j++) {
                        if (Math.abs(markers.get(j).getX() - px) < 22 && Math.abs(markers.get(j).getY() - py) < 22) {
                            occupied = true;
                            break;
                        }
                    }
                    if (!occupied) return new int[]{px, py};
                }
            }
        }
        return new int[]{Math.max(canvasX + 2, Math.min(canvasX + canvasW - 22, x)),
                Math.max(canvasY + 2, Math.min(canvasY + canvasH - 22, y))};
    }

    private void submit(boolean find) {
        if(parent==null)return;
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
        seedWriter.accept(query);
        parent.getUiState().setSeed(query);
        if (!find) {
            releaseMap();
            Minecraft.getInstance().setScreenAndShow(parent);
        } else if (QueryAdvisor.inspect(compiled, config).reviewBeforeSearch()) {
            releaseMap();
            Minecraft.getInstance().setScreenAndShow(new SearchAdviceScreen(parent, query, compiled, config));
        } else {
            new SearchScreen(parent, query, compiled, config).open(this::releaseMap);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean focusedViaKeyboard) {
        if(unsavedPrompt!=null)return unsavedPrompt.click(event,focusedViaKeyboard);
        var editor=targetEditor;
        if(tab!=2&&editor!=null&&editor.click(event,focusedViaKeyboard)){
            if(targetEditor==editor&&tab!=2)setFocused(editor.focused());return true;
        }
        var panel=settings;
        if(tab==2&&panel!=null&&panel.click(event,focusedViaKeyboard)){
            if(settings==panel&&tab==2)setFocused(panel.focused());return true;
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
            if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) { details(placement.target()); return true; }
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
            panning = true; return true;
        }
        return super.mouseClicked(event, focusedViaKeyboard);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if(unsavedPrompt!=null)return true;
        if(tab!=2&&targetEditor!=null&&targetEditor.drag(event,dx,dy))return true;
        if(tab==2&&settings!=null&&settings.drag(event,dx,dy))return true;
        if (openDrop != null && openDrop.isPopupDrag()) {
            openDrop.dragPopupTo((float) event.y(), popupHeight());
            return true;
        }
        if (panning) {
            panX += dx; panY += dy;
            centerX = canvasX + canvasW / 2 + (int) panX; centerY = canvasY + canvasH / 2 + (int) panY;
            layoutMarkers(); updateExampleView();
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
        if(tab!=2&&targetEditor!=null)targetEditor.release(event);
        if(tab==2&&settings!=null)settings.release(event);
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
        if(tab!=2&&targetEditor!=null&&targetEditor.scroll(x,y,vertical))return true;
        if(tab==2&&settings!=null&&settings.scroll(x,y,vertical))return true;
        if (openDrop != null && openDrop.isOpen() && openDrop.popupHit((float) x, (float) y, popupHeight())) {
            openDrop.scrollPopup((float) vertical, popupHeight());
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
        if(tab!=2&&targetEditor!=null&&targetEditor.key(event))return true;
        if(tab==2&&settings!=null&&settings.key(event))return true;
        if (openDrop != null && openDrop.isOpen() && openDrop.keyPressed(event)) return true;
        if (event.key() == InputConstants.KEY_HOME && getFocused() != prompt) {
            panX = panY = 0; fitNeeded = true; rebuildWidgets(); return true;
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
        exampleMap.drawCross(g, config.searchCenter().name());
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
            int x = marker.getX() + 10;
            int y = marker.getY() + 10;
            g.fill(Math.min(point[0], x), point[1], Math.max(point[0], x) + 1, point[1] + 1, 0xFFBAC9CF);
            g.fill(x, Math.min(point[1], y), x + 1, Math.max(point[1], y) + 1, 0xFFBAC9CF);
            g.fill(point[0] - 1, point[1] - 1, point[0] + 2, point[1] + 2, 0xFFE6EFF2);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        if(tab==2&&settings!=null)settings.draw(g,mouseX,mouseY,partialTick);
        if(tab!=2&&targetEditor!=null)targetEditor.draw(g,mouseX,mouseY,partialTick);
        for (int i = 0; i < markers.size(); i++) {
            Button marker = markers.get(i);
            if (!marker.visible) continue;
            SearchDraft.Target target = markerPlacements.get(i).target();
            Item icon = target.category() == KeywordDictionary.Category.BIOME ? null : icon(target);
            if (icon != null) MenuItemIcon.draw(g, icon, marker.getX() + 2, marker.getY() + 2);
            else {
                int color = target.category() == KeywordDictionary.Category.BIOME
                        ? com.wywf.map.BiomePalette.color(target.canonical())
                        : 0xFF000000 | (target.canonical().hashCode() & 0x7F7F7F) | 0x404040;
                g.fill(marker.getX() + 4, marker.getY() + 4, marker.getX() + 16, marker.getY() + 16, color);
            }
            var modifier = markerPlacements.get(i).countMode();
            boolean badge = target.category() == KeywordDictionary.Category.BIOME && modifier != null;
            if (badge) {
                String name = MapTexts.text("modifier_short_" + modifier.name().toLowerCase(Locale.ROOT));
                int bw = font.width(name);
                int x = marker.getX() + 19 - bw, y = marker.getY();
                g.fill(x - 1, y, x + bw + 1, y + font.lineHeight, 0xDF212520);
                g.text(font, name, x, y, 0xFFFFF3CF);
            }
            if (draft.markerLocked(target)) {
                int px = marker.getX() + (badge ? 2 : 13), py = marker.getY() + (badge ? 11 : 2);
                g.fill(px + 1, py, px + 4, py + 4, 0xFFE8EDDE);
                g.fill(px + 2, py + 1, px + 3, py + 3, 0xFF202820);
                g.fill(px, py + 3, px + 5, py + 7, 0xFFE8EDDE);
            }
        }
        if(tab==2&&settings!=null)settings.popup(g,mouseX,mouseY);
        if(tab!=2&&targetEditor!=null)targetEditor.popup(g,mouseX,mouseY);
        if (openDrop != null) openDrop.drawPopup(g, mouseX, mouseY, popupHeight(), 1);
        if(unsavedPrompt!=null){unsavedPrompt.bounds(width,height);unsavedPrompt.draw(g,overlayMouseX,overlayMouseY,partialTick);}
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
        sketchZoom = 512 / (baseRadius * scale); panX = panY = 0; fitNeeded = false;
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
        exampleMap.view.reset(-panX * scale, -panY * scale, scale); exampleMap.dirty();
    }
    private void advanceMapZoom(){
        if(exampleMap==null)return;
        if(exampleMap.view.advance())exampleMap.dirty();
        if(!exampleMap.view.zooming()&&sketchZoom==512d/(baseRadius*exampleMap.view.scale()))return;
        double scale=exampleMap.view.scale();sketchZoom=512d/(baseRadius*scale);panX=-exampleMap.view.x()/scale;panY=-exampleMap.view.z()/scale;
        centerX=canvasX+canvasW/2+(int)panX;centerY=canvasY+canvasH/2+(int)panY;layoutMarkers();
    }
    @Override public void tick() {
        advanceMapZoom();
        if(settings!=null)settings.tick();
        adaptation.fitSeconds(config.exampleFitSeconds());
        var goals = com.wywf.map.PreviewAdaptation.goals(draft.placements());
        if (fitVariation != variation || !goals.equals(fitGoals)) {
            fitGoals = goals; fitVariation = variation; illustrative = false; paintedMap = null;
            adaptation.request(variation, goals);
        }
        boolean paused=config.pauseMapInactive()&&!Minecraft.getInstance().isWindowActive();
        adaptation.paused(paused);regions.paused(paused);
        var fit = adaptation.result();
        illustrative = fit != null && !fit.matched() && !goals.isEmpty();
        long seed = fit != null && fit.matched() ? fit.seed() : variation;
        if (exampleMap == null || exampleSeed != seed) updateExampleView();
        exampleMap.settings(config); exampleMap.dimension(dimension); exampleMap.temperature(temperature); exampleMap.borders(borders); exampleMap.y(caves && dimension == DimensionSpace.OVERWORLD ? -50 : 64); exampleMap.tick();
        if (paused || !illustrative) return;
        var base = exampleMap.snapshot();
        if (base == null || !base.complete()) return;
        regions.request(base, goals.stream().filter(g -> DimensionSpace.of(g.biome()) == dimension).toList());
        var next = regions.result();
        if (next == null || next == regionResult) return;
        regionResult = next; paintedMap = next.map();
        sketch.upload(paintedMap.width(), paintedMap.height(), next.pixels());
    }
    @Override public void removed() {
        if(settings!=null)settings.flush();
        if(adaptation.result()==null){adaptation.close();fitVariation=Long.MIN_VALUE;}
        regions.close();sketch.close();
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
        paintedMap=null;regionResult=null;regions.close();sketch.close();
    }
    private void guardEditor(Runnable action){
        var editor=targetEditor;
        if(editor==null||!editor.dirty()){action.run();return;}
        editor.pauseInput();setFocused(null);panning=false;dragTarget=null;draft.endMove();
        Runnable keep = () -> {
            unsavedPrompt = null;
            if (targetEditor == editor) setFocused(editor.focused());
        };
        unsavedPrompt = new MenuModal(font, label("unsaved_title"), label("unsaved_body"), editor::canApply, keep,
                Button.builder(label("unsaved_save"), button -> {
                    if (editor.applyToDraft()) {
                        unsavedPrompt = null;
                        action.run();
                    }
                }).build(),
                Button.builder(label("unsaved_discard"), button -> {
                    unsavedPrompt = null;
                    targetEditor = null;
                    action.run();
                }).build(),
                Button.builder(label("unsaved_keep"), button -> keep.run()).build());
        unsavedPrompt.bounds(width,height);
    }
    @Override public boolean charTyped(net.minecraft.client.input.CharacterEvent event){return unsavedPrompt!=null||super.charTyped(event);}
    @Override public boolean keyReleased(KeyEvent event){return unsavedPrompt!=null||super.keyReleased(event);}
    @Override
    public void onClose(){guardEditor(this::closeConstructor);}
    private void closeConstructor() {
        draft.endMove();releaseMap();
        Minecraft.getInstance().setScreenAndShow(returnTo);
    }
}
