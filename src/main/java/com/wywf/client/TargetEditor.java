package com.wywf.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wywf.core.*;
import com.wywf.verify.LootCatalog;
import dev.diylib.animation.RetargetableValue;
import dev.diylib.api.Scrollbar;
import dev.diylib.client.ui.DiyTileLayout;
import dev.diylib.client.ui.DiyUiLayers.Area;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class TargetEditor {
    private static final int MARGIN = 6, ANCHOR_GAP = 5, PAD = 5, ROW = 16, CARD = 30, CARD_GAP = 3;
    private static final int BACKGROUND = 0xFF202020, EDGE = 0xFF555555, TEXT = 0xFFF4F4F4;
    private static final int[] CARD_COLORS = {0xFF29312D, 0xFF302B28, 0xFF292D35, 0xFF322B31, 0xFF303127, 0xFF283236};
    private static final long ANIMATION = 165_000_000L;
    private static final long CARD_MOTION = 190_000_000L;
    private static final long REVEAL_ON_LAYOUT = Long.MIN_VALUE;

    private enum Section { MODIFIER, TAGS, LOOT, CONTAINER, ITEMS, CARDS }
    private record Value(int minimum, int maximum, Modifier countMode, int quantity, LootConstraint loot,
                         boolean pinned, StructureTags tags) {}
    private record LootInput(LootConstraint.Requirement original, String quantity) {
        LootInput(LootConstraint.Requirement original) { this(original, Integer.toString(original.count())); }

        LootConstraint.Requirement requirement() {
            try {
                return new LootConstraint.Requirement(original.item(), Integer.parseInt(quantity),
                        original.enchantment(), original.level(), original.durability());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(ResultTexts.name(original.item()) + ". "
                        + MapTexts.text("loot_count_invalid"), invalid);
            }
        }
    }

    private record Rect(int x, int y, int w, int h) {
        int right() { return x + w; }
        int bottom() { return y + h; }
        boolean hits(double px, double py) { return px >= x && px < right() && py >= y && py < bottom(); }
        boolean intersects(AbstractWidget widget) {
            return w > 0 && h > 0 && widget.getX() < right() && widget.getY() < bottom()
                    && widget.getX() + widget.getWidth() > x && widget.getY() + widget.getHeight() > y;
        }
        ScreenRectangle screenRect() { return new ScreenRectangle(x, y, w, h); }
    }
    private record Row(Component label, int offset, int labelColor, AbstractWidget control, int controlX, int controlY) {}
    private record LootChoice(String id, String name, String search) {}
    private record BranchMotion(RetargetableValue direction, RetargetableValue opening) {}
    private record Card(String label, net.minecraft.world.item.Item icon, EditBox count, MenuRow remove,
                        RetargetableValue slide, RetargetableValue shade) {}

    private final SearchDraft draft;
    private final SearchDraft.Target target;
    private final CreateWorldScreen creator;
    private final Runnable close;
    private final Consumer<Runnable> guard;
    private final Font font = Minecraft.getInstance().font;
    private final Map<String, LootCatalog.Choices> cachedChoices = new HashMap<>();
    private final Map<String, List<LootChoice>> cachedItems = new HashMap<>();
    private final Map<String, LootInput> items = new LinkedHashMap<>();
    private final List<String> containers;
    private final EnumMap<Section, Long> transitions = new EnumMap<>(Section.class);
    private final EnumMap<Section, MenuRow> triggers = new EnumMap<>(Section.class);
    private final EnumMap<Section, BranchMotion> branchMotions = new EnumMap<>(Section.class);
    private final List<Pane> panes = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private final int anchorX, anchorY;
    private int screenWidth, screenHeight, direction, paneLimit;
    private String minimum, maximum, quantity, container, filter = "", eyesMinimum, eyesMaximum;
    private Modifier countMode;
    private StructureTags tags;
    private boolean pinned, lootEnabled, lootOpen, itemOpen, cardsHidden;
    private Section choice;
    private EditBox filterBox;
    private AbstractWidget focused, capture;
    private Pane scrollingPane;
    private MenuRow apply;
    private Rect cardBounds;
    private Pane cardParent;
    private int cardSide;
    private int hoveredCard = -1, lastCardMouseX = Integer.MIN_VALUE, lastCardMouseY = Integer.MIN_VALUE;
    private int cardScroll;
    private boolean grabbingCardScroll;
    private long cardFrame;

    TargetEditor(SearchDraft draft, SearchDraft.Target target, CreateWorldScreen creator, int anchorX, int anchorY,
                 Runnable close, Consumer<Runnable> guard) {
        this.draft = draft;
        this.target = target;
        this.creator = creator;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.close = close;
        this.guard = guard;

        var placement = draft.placement(target).orElseThrow();
        minimum = Integer.toString(placement.minRadius());
        maximum = Integer.toString(placement.radius());
        quantity = Integer.toString(placement.quantity());
        countMode = placement.countMode();
        tags = placement.tags();
        eyesMinimum = Integer.toString(tags.eyes() ? tags.eyesMin() : 1);
        eyesMaximum = Integer.toString(tags.eyes() ? tags.eyesMax() : 12);
        pinned = placement.pinned();
        lootEnabled = placement.loot() != null;
        container = lootEnabled ? placement.loot().container() : "any";
        if (lootEnabled) placement.loot().items().forEach(item -> items.put(item.item(), new LootInput(item)));
        containers = creator == null ? List.of("any") : LootCatalog.containers(creator, target.canonical());
    }

    void bounds(int width, int height) {
        screenWidth = width;
        screenHeight = height;
        rebuild();
    }

    private boolean ru() { return Minecraft.getInstance().getLanguageManager().getSelected().startsWith("ru"); }
    private boolean isBiome() { return target.category() == KeywordDictionary.Category.BIOME; }
    private boolean counted() { return countMode != null && countMode.counted(); }
    private boolean usesCount() { return counted() || countMode == Modifier.SOME; }
    private boolean supportsLoot() {
        return !usesCount() && countMode != Modifier.NEVER && countMode != Modifier.ONLY && countMode != Modifier.UNDER;
    }

    private LootCatalog.Choices choices() {
        return cachedChoices.computeIfAbsent(container, value -> isBiome()
                ? LootCatalog.biomeChoices(creator, target.canonical(), value)
                : LootCatalog.choices(creator, target.canonical(), value));
    }

    private void rebuild() {
        if (screenWidth == 0) return;
        var oldFocus = focused;
        int savedScroll = panes.isEmpty() ? 0 : panes.getFirst().scroll;
        setFocus(null);
        capture = null;
        scrollingPane = null;
        grabbingCardScroll = false;
        triggers.clear();
        panes.clear();
        cards.clear();
        cardBounds = null;
        cardParent = null;
        direction = anchorX <= screenWidth / 2 ? 1 : -1;
        boolean rootPicker = choice == Section.MODIFIER || choice == Section.TAGS;
        boolean showLoot = lootOpen && lootEnabled && !rootPicker;
        int depth = 1 + (rootPicker || showLoot ? 1 : 0)
                + (showLoot && (choice == Section.CONTAINER || itemOpen) ? 1 : 0)
                + (showLoot && choice == null && !items.isEmpty() && !cardsHidden ? 1 : 0);
        int groupSpace = Math.max(1, screenWidth - 2 * MARGIN - (depth - 1) * 2);
        paneLimit = Math.max(1, Math.min(195, groupSpace / depth));
        int w = Math.min(Math.clamp(screenWidth / 4, 142, 192), paneLimit);
        var main = new Pane(w);
        var language = ru() ? KeywordDictionary.Lang.RU : KeywordDictionary.Lang.EN;
        main.title(target.phrase(language));
        main.heading(MapTexts.text("edit_distance"), 0xFFB7B7B7);
        var modes = new ArrayList<Modifier>();
        modes.add(null);
        Arrays.stream(Modifier.values()).filter(mode -> SearchDraft.supportsModifier(target.category(), mode))
                .filter(mode -> !com.wywf.map.BiomeMapColors.cave(target.canonical())
                        || mode != Modifier.UNDER && (mode != Modifier.FAR || !target.canonical().equals("minecraft:deep_dark")))
                .forEach(modes::add);
        main.branch(MapTexts.text("marker_modifier"), modifierName(countMode), Section.MODIFIER, () -> toggleChoice(Section.MODIFIER));
        if (countMode == null) main.number(SearchConstructorScreen.label("minimum").getString(), minimum, 4, value -> minimum = value);
        if (countMode == null || counted())
            main.number(SearchConstructorScreen.label("maximum").getString(), maximum, 4, value -> maximum = value);
        if (usesCount()) main.number(MapTexts.text("quantity"), quantity, 2, value -> quantity = value);
        main.toggle(MapTexts.text("keep_condition"), pinned, value -> pinned = value);
        var tagIds = isBiome() ? List.<String>of() : StructureTags.options(target.canonical());
        String tagLabel = ru() ? "Теги структуры" : "Structure tags";
        String noTags = ru() ? "Без тегов" : "No tags";
        if (!tagIds.isEmpty()) {
            main.branch(tagLabel, tags.empty() ? noTags : tags.description(ru()), Section.TAGS, () -> toggleChoice(Section.TAGS));
            if (tags.eyes()) {
                main.number(ru() ? "Минимум оков" : "Minimum eyes", eyesMinimum, 2, value -> eyesMinimum = value);
                main.number(ru() ? "Максимум оков" : "Maximum eyes", eyesMaximum, 2, value -> eyesMaximum = value);
            }
        }
        if (creator != null && supportsLoot()) {
            if (isBiome() || containers.size() > 1 || lootEnabled) {
                main.toggle(MapTexts.text("loot_enabled"), lootEnabled, value -> {
                    lootEnabled = value;
                    lootOpen = value;
                    itemOpen = false;
                    cardsHidden = false;
                    choice = null;
                    transitions.put(Section.LOOT, REVEAL_ON_LAYOUT);
                    rebuild();
                });
                if (lootEnabled) main.branch(MapTexts.text("loot_settings"), "", Section.LOOT, this::toggleLoot);
            } else main.heading(MapTexts.text("loot_no_containers"), 0xFFB7B7B7);
        }
        main.removeAction(SearchConstructorScreen.label("delete").getString(), () -> {
            draft.remove(target);
            close.run();
        });
        apply = main.footer(MapTexts.text("edit_done"), () -> {
            if (applyToDraft()) close.run();
        }, SearchConstructorScreen.label("back").getString(), () -> guard.accept(close));

        int reserve = (depth - 1) * (paneLimit + 2);
        int x = direction > 0 ? Math.min(anchorX + ANCHOR_GAP, screenWidth - MARGIN - w - reserve)
                : Math.max(anchorX - w - ANCHOR_GAP, MARGIN + reserve);
        int h = main.windowHeight(244);
        boolean above = ConstructorDropList.preferAbove(h, anchorY - MARGIN, screenHeight - MARGIN - anchorY);
        main.scroll = savedScroll;
        main.place(x, above ? anchorY - h : anchorY, h);
        panes.add(main);

        if (choice == Section.MODIFIER) picker(main, Section.MODIFIER, MapTexts.text("marker_modifier"),
                modes.stream().map(this::modifierName).toList(), modes.indexOf(countMode), index -> {
                    countMode = modes.get(index);
                    if (countMode != null) minimum = "0";
                    if (countMode == Modifier.SOME && quantity.equals("1")) quantity = "2";
                    if (!supportsLoot()) lootEnabled = lootOpen = itemOpen = false;
                    closeChoice();
                });
        if (choice == Section.TAGS && !tagIds.isEmpty()) {
            var labels = new ArrayList<>(List.of(noTags));
            tagIds.forEach(id -> labels.add((tags.selected(id) ? "✓ " : "") + StructureTags.label(id, ru())));
            picker(main, Section.TAGS, tagLabel, labels, tags.empty() ? 0 : -1, index -> {
                tags = index == 0 ? StructureTags.NONE : tags.toggle(tagIds.get(index - 1));
                closeChoice();
            });
        }
        if (showLoot) buildLoot(main);
        if (oldFocus == filterBox && filterBox != null && itemOpen && choice == null) setFocus(filterBox);
        validate();
    }

    private String modifierName(Modifier mode) {
        if (mode == null) return MapTexts.text("modifier_between");
        return MapTexts.text((mode.counted() ? "count_mode_" : "modifier_") + mode.name().toLowerCase(Locale.ROOT));
    }

    private void toggleChoice(Section section) {
        if (choice != null) transitions.put(choice, System.nanoTime());
        choice = choice == section ? null : section;
        if (choice != null) transitions.put(choice, System.nanoTime());
        if (section == Section.CONTAINER) itemOpen = false;
        rebuild();
    }

    private void closeChoice() {
        if (choice != null) transitions.put(choice, System.nanoTime());
        choice = null;
        rebuild();
    }

    private void toggleLoot() {
        lootOpen = !lootOpen;
        itemOpen = false;
        choice = null;
        transitions.put(Section.LOOT, REVEAL_ON_LAYOUT);
        rebuild();
    }

    private void picker(Pane parent, Section section, String title, List<String> labels, int selected, IntConsumer pick) {
        var pane = new Pane(popupWidth(labels, 126, 188));
        pane.header(title, this::closeChoice);
        for (int i = 0; i < labels.size(); i++) {
            int index = i;
            pane.row((i == selected && !labels.get(i).startsWith("✓") ? "✓ " : "") + labels.get(i), () -> pick.accept(index));
        }
        pane.attach(parent, section, 156, false);
    }

    private void buildLoot(Pane parent) {
        var values = new ArrayList<>(containers);
        if (!values.contains(container)) values.add(container);
        var pane = new Pane(popupWidth(List.of(MapTexts.text("loot_container"),
                LootContainers.name(container, ru()), MapTexts.text("loot_choose")), 140, 184));
        pane.header(MapTexts.text("loot_settings"), this::toggleLoot);
        if (isBiome()) pane.heading(MapTexts.text("loot_in_biome"), TEXT);
        else pane.branch(MapTexts.text("loot_container"), LootContainers.name(container, ru()),
                Section.CONTAINER, () -> toggleChoice(Section.CONTAINER));
        pane.wrapped(MapTexts.text("loot_one_chest"));
        var choose = pane.branch(MapTexts.text("loot_choose"), "", Section.ITEMS, this::toggleItems);
        choose.active = items.size() < 16 || itemOpen;
        if (!items.isEmpty()) pane.branch(MapTexts.text("loot_selected_cards", items.size()), "", Section.CARDS, () -> {
            cardsHidden = !cardsHidden;
            transitions.put(Section.CARDS, System.nanoTime());
            rebuild();
        });
        if (!choices().complete()) pane.wrapped(MapTexts.text("loot_incomplete"));
        pane.attach(parent, Section.LOOT, 170, true);

        Pane cardAnchor = pane;
        if (choice == Section.CONTAINER) picker(pane, Section.CONTAINER, MapTexts.text("loot_container"),
                values.stream().map(value -> LootContainers.name(value, ru())).toList(), values.indexOf(container), index -> {
                    container = values.get(index);
                    filter = "";
                    filterBox = null;
                    closeChoice();
                });
        if (itemOpen && choice == null) cardAnchor = buildItemPicker(pane);
        if (!items.isEmpty() && !cardsHidden && choice == null) buildCards(cardAnchor);
    }

    private void toggleItems() {
        itemOpen = !itemOpen;
        choice = null;
        if (itemOpen) transitions.put(Section.ITEMS, REVEAL_ON_LAYOUT);
        else transitions.remove(Section.ITEMS);
        if (itemOpen && filterBox == null) {
            filterBox = box(filter, 100, value -> {
                filter = value;
                rebuild();
            });
            filterBox.setHint(Component.literal(MapTexts.text("loot_item_filter")));
        }
        rebuild();
        if (itemOpen) setFocus(filterBox);
    }

    private Pane buildItemPicker(Pane parent) {
        var matches = cachedItems.computeIfAbsent(container, key -> choices().items().stream()
                .map(item -> {
                    String name = ResultTexts.name(item.item());
                    return new LootChoice(item.item(), name, (name + " " + item.item()).toLowerCase(Locale.ROOT));
                }).sorted(Comparator.comparing(LootChoice::name)).toList());
        String needle = filter.strip().toLowerCase(Locale.ROOT);
        var options = matches.stream()
                .filter(item -> !items.containsKey(item.id()) && item.search().contains(needle)).toList();
        var pane = new Pane(popupWidth(matches.stream().map(LootChoice::name).toList(), 148, 195));
        pane.header(MapTexts.text("loot_choose"), this::toggleItems);
        pane.filterBox = filterBox;
        if (options.isEmpty()) pane.heading(MapTexts.text("loot_empty_filter"), 0xFFBABABA);
        else for (var item : options) pane.row(item.name(), () -> {
            if (items.size() >= 16) return;
            items.put(item.id(), new LootInput(new LootConstraint.Requirement(item.id(), 1)));
            cardsHidden = false;
            if (items.size() == 16) itemOpen = false;
            rebuild();
        });
        int preferredHeight = Math.min(172, Math.max(1, matches.size()) * ROW + pane.fixedHeight());
        pane.attach(parent, Section.ITEMS, 172, preferredHeight, true);
        return pane;
    }

    private void buildCards(Pane parent) {
        int width = popupWidth(items.keySet().stream().map(ResultTexts::name).toList(), 130, 165);
        int contentHeight = items.size() * CARD + (items.size() - 1) * CARD_GAP;
        int height = Math.max(1, Math.min(contentHeight, parent.bounds.h()));
        var trigger = triggers.get(Section.CARDS);
        int y = itemOpen ? parent.bounds.y() : trigger.getY();
        var parentArea = new Area(parent.bounds.x(), parent.bounds.y(), parent.bounds.w(), parent.bounds.h());
        var viewport = new Area(MARGIN, MARGIN, Math.max(1, screenWidth - 2 * MARGIN), Math.max(1, screenHeight - 2 * MARGIN));
        var attachment = DiyTileLayout.attachSide(parentArea, viewport, width, height, y, parent.direction);
        var area = attachment.area();
        cardParent = parent;
        cardSide = attachment.direction();
        cardBounds = new Rect(area.x(), area.y(), area.width(), area.height());
        trigger.arrowDirection = cardSide;
        hoveredCard = -1;
        lastCardMouseX = lastCardMouseY = Integer.MIN_VALUE;
        for (var input : items.values()) {
            var item = input.original();
            var count = box(input.quantity(), 4, value -> {
                items.put(item.item(), new LootInput(item, value));
                validate();
            });
            var limit = choices().items().stream().filter(option -> option.item().equals(item.item())).findFirst().orElse(null);
            String name = ResultTexts.name(item.item());
            String help = limit == null ? MapTexts.text("loot_not_here", name)
                    : MapTexts.text("loot_limit", limit.chestMax(), limit.rollMax(), limit.slotMax());
            count.setTooltip(Tooltip.create(Component.literal(help)));
            var remove = new MenuRow("×", "", null, () -> {
                items.remove(item.item());
                rebuild();
            });
            var key = Identifier.tryParse(item.item());
            var icon = key != null && BuiltInRegistries.ITEM.containsKey(key) ? BuiltInRegistries.ITEM.getValue(key) : null;
            cards.add(new Card(name, icon, count, remove, new RetargetableValue(0, CARD_MOTION), new RetargetableValue(0, CARD_MOTION)));
        }
        cardScroll = Math.clamp(cardScroll, 0, cardScrollLimit());
        layoutCards(anchorX, anchorY);
    }

    static int cardStep(int count, int height) {
        return count <= 1 ? CARD : Math.clamp((height - CARD) / (count - 1), 1, CARD + CARD_GAP);
    }

    private int cardSlot() {
        return Math.max(cardStep(cards.size(), cardBounds.h()), Math.min(CARD, font.lineHeight + 4));
    }

    private int cardScrollLimit() {
        return Math.max(0, (cards.size() - 1) * cardSlot() + CARD - cardBounds.h());
    }

    private int cardSlide() {
        return cardSlot() >= CARD ? 0 : Math.min(8, cardBounds.w() / 8);
    }

    private int cardX(int index) {
        int start = cardSide < 0 ? cardBounds.right() - cardWidth() : cardBounds.x();
        return start - cardSide * (int) Math.round(cards.get(index).slide().value(cardFrame));
    }

    private int cardTop(int index) {
        return cardBounds.y() + index * cardSlot() - cardScroll;
    }

    private int cardWidth() {
        return Math.max(1, cardBounds.w() - (cardScrollLimit() == 0 ? 0 : 4));
    }

    private boolean cardHit(int index, double x, double y) {
        return new Rect(cardX(index), cardTop(index), cardWidth(), CARD).hits(x, y);
    }

    private boolean cardsAt(double x, double y) {
        return cardBounds != null && cardBounds.hits(x, y) && !cardParent.visible().hits(x, y);
    }

    private int cardScrollbarX() {
        return cardSide < 0 ? cardBounds.x() : cardBounds.right() - 3;
    }

    private boolean cardScrollHit(double x) {
        if (cardScrollLimit() == 0) return false;
        return cardSide < 0 ? x < cardBounds.x() + 4 : x >= cardBounds.right() - 4;
    }

    private int cardThumbHeight(int range) {
        return Scrollbar.thumbHeight(cardBounds.h(), cardBounds.h() + range, 1);
    }

    private void scrollCardsTo(int position) {
        cardScroll = Math.clamp(position, 0, cardScrollLimit());
        lastCardMouseX = lastCardMouseY = Integer.MIN_VALUE;
    }

    private void dragCardScroll(double y) {
        int range = cardScrollLimit();
        int thumb = cardThumbHeight(range);
        double track = Math.max(1, cardBounds.h() - thumb);
        scrollCardsTo((int) Math.round((y - cardBounds.y() - thumb / 2.0) / track * range));
    }

    private void layoutCards(int mx, int my) {
        if (cardBounds == null) return;
        cardFrame = System.nanoTime();
        if (mx != lastCardMouseX || my != lastCardMouseY) {
            lastCardMouseX = mx;
            lastCardMouseY = my;
            int next = cardSlide() > 0 && !grabbingCardScroll ? frontCardAt(mx, my) : -1;
            if (next != hoveredCard && hoveredCard >= 0 && capture == null) {
                var former = cards.get(hoveredCard);
                if (focused == former.count() || focused == former.remove()) setFocus(null);
            }
            hoveredCard = next;
        }

        for (int i = 0; i < cards.size(); i++) {
            var card = cards.get(i);
            boolean shifted = hoveredCard >= 0 && hoveredCard != i;
            card.slide().target(shifted ? cardSlide() : 0, cardFrame);
            card.shade().target(shifted ? .20 : 0, cardFrame);
            int x = cardX(i), y = cardTop(i), width = cardWidth();
            card.count().setX(x + 23);
            card.count().setY(y + 15);
            card.count().setWidth(Math.max(12, Math.min(39, width - 46)));
            card.remove().setX(x + width - 19);
            card.remove().setY(y + 14);
            card.remove().setWidth(14);
        }
    }

    private int frontCardAt(double x, double y) {
        if (!cardsAt(x, y) || cardScrollHit(x)) return -1;
        if (hoveredCard >= 0 && cardHit(hoveredCard, x, y)) return hoveredCard;
        for (int i = cards.size() - 1; i >= 0; i--) {
            if (cardHit(i, x, y)) return i;
        }
        return -1;
    }

    private boolean showCardControls(int index) {
        return (hoveredCard == index || cardSlot() >= CARD || index == cards.size() - 1)
                && cardWidth() >= 50;
    }

    private void drawCard(GuiGraphicsExtractor g, int index, int interactiveCard, int mx, int my, float tick) {
        var card = cards.get(index);
        int x = cardX(index), y = cardTop(index), w = cardWidth(), h = CARD;
        if (y >= cardBounds.bottom() || y + CARD <= cardBounds.y()) return;

        boolean interactive = index == interactiveCard;
        boolean emphasized = index == hoveredCard;
        TargetTileLayer.draw(g, x, y, w, h, () -> {
            int tint = CARD_COLORS[index % CARD_COLORS.length];
            int color = emphasized ? MenuAccent.mix(tint, 0xFFE9DDB3, .15f) : tint;
            g.fill(x, y, x + w, y + h, color);
            g.text(font, trim(card.label(), Math.max(1, w - 24 - PAD)), x + 23, y + 2, TEXT);
            if (card.icon() != null) MenuItemIcon.draw(g, card.icon(), x + 4, y + 7);
            if (showCardControls(index)) {
                card.count().extractRenderState(g, interactive ? mx : -1, interactive ? my : -1, tick);
                card.remove().extractRenderState(g, interactive ? mx : -1, interactive ? my : -1, tick);
            }
            g.nextStratum();
            g.outline(x, y, w, h, 0xFF55534F);
            TargetTileLayer.dim(g, x, y, w, h, card.shade().value(cardFrame));
        });
    }

    private int popupWidth(List<String> values, int minimum, int maximum) {
        int textWidth = values.stream().mapToInt(font::width).max().orElse(0);
        return Math.min(Math.clamp(textWidth + 22, minimum, maximum), paneLimit);
    }
    private int clampY(int y, int height) { return Math.clamp(y, MARGIN, Math.max(MARGIN, screenHeight - MARGIN - height)); }
    private int clampX(int x, int width) { return Math.clamp(x, MARGIN, Math.max(MARGIN, screenWidth - MARGIN - width)); }

    private int sideX(Rect parent, int width, int preferred) {
        int right = parent.right() + 2, left = parent.x() - width - 2;
        boolean fitsRight = right + width <= screenWidth - MARGIN, fitsLeft = left >= MARGIN;
        if (preferred > 0 && fitsRight || !fitsLeft && fitsRight) return right;
        if (fitsLeft) return left;
        int freeRight = screenWidth - MARGIN - right, freeLeft = parent.x() - MARGIN;
        return clampX(freeRight >= freeLeft ? right : left, width);
    }

    private EditBox box(String value, int max, Consumer<String> change) {
        var box = new EditBox(font, 0, 0, 40, 14, Component.empty());
        box.setMaxLength(max);
        box.setValue(value);
        box.setResponder(change);
        return box;
    }

    private String trim(String text, int width) {
        if (width <= 0) return "";
        if (font.width(text) <= width) return text;
        int available = width - font.width("…");
        return available < 0 ? "" : font.plainSubstrByWidth(text, available) + "…";
    }

    private void drawBranch(GuiGraphicsExtractor graphics, Section section, int x, int y,
                            int direction, int color) {
        var motion = branchMotions.computeIfAbsent(section,
                ignored -> new BranchMotion(new RetargetableValue(direction, CARD_MOTION), new RetargetableValue(0, CARD_MOTION)));
        long now = System.nanoTime();
        motion.direction().target(direction, now);
        motion.opening().target(expanded(section) ? 1 : 0, now);
        TargetTileLayer.chevron(graphics, x, y, motion.direction().value(now), motion.opening().value(now), color);
    }

    private boolean expanded(Section section) {
        return switch (section) {
            case LOOT -> lootOpen;
            case ITEMS -> itemOpen;
            case CARDS -> cardBounds != null;
            default -> choice == section;
        };
    }

    private void setFocus(AbstractWidget widget) {
        if (focused != null) focused.setFocused(false);
        focused = widget;
        if (widget != null) widget.setFocused(true);
    }

    void pauseInput() {
        capture = null;
        scrollingPane = null;
        grabbingCardScroll = false;
        setFocus(null);
    }

    boolean click(MouseButtonEvent event, boolean keyboard) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return over(event.x(), event.y());
        layoutCards((int)event.x(), (int)event.y());
        if (cardsAt(event.x(), event.y())) {
            if (cardScrollHit(event.x())) {
                grabbingCardScroll = true;
                setFocus(null);
                dragCardScroll(event.y());
                return true;
            }
            int visible = frontCardAt(event.x(), event.y());
            if (visible >= 0 && showCardControls(visible)) clickCard(cards.get(visible), event, keyboard);
            return true;
        }
        for (var pane : panes.reversed()) {
            if (!pane.visible().hits(event.x(), event.y())) continue;
            if (pane.pickScrollbar(event.x(), event.y())) scrollingPane = pane;
            else {
                var widget = pane.widgetAt(event.x(), event.y());
                setFocus(null);
                if (widget != null) clickWidget(widget, event, keyboard);
            }
            return true;
        }
        return false;
    }

    private boolean clickCard(Card card, MouseButtonEvent event, boolean keyboard) {
        if (card.count().isMouseOver(event.x(), event.y())) return clickWidget(card.count(), event, keyboard);
        if (card.remove().isMouseOver(event.x(), event.y())) return clickWidget(card.remove(), event, keyboard);
        return false;
    }

    private boolean clickWidget(AbstractWidget widget, MouseButtonEvent event, boolean keyboard) {
        setFocus(widget);
        capture = widget;
        return widget.mouseClicked(event, keyboard);
    }

    private boolean over(double x, double y) {
        return cardBounds != null && cardBounds.hits(x, y) || panes.stream().anyMatch(pane -> pane.visible().hits(x, y));
    }

    boolean drag(MouseButtonEvent event, double dx, double dy) {
        if (grabbingCardScroll) {
            dragCardScroll(event.y());
            return true;
        }
        if (scrollingPane != null) {
            scrollingPane.dragScrollbar(event.y());
            return true;
        }
        return capture != null && capture.mouseDragged(event, dx, dy);
    }

    void release(MouseButtonEvent event) {
        if (capture != null) capture.mouseReleased(event);
        capture = null;
        scrollingPane = null;
        grabbingCardScroll = false;
    }

    boolean scroll(double x, double y, double amount) {
        if (cardsAt(x, y)) {
            if (cardScrollLimit() > 0) {
                setFocus(null);
                scrollCardsTo(cardScroll - (int) Math.round(amount * cardSlot()));
            }
            return true;
        }
        for (var pane : panes.reversed()) {
            if (pane.visible().hits(x, y)) {
                pane.scrollTo(pane.scroll - (int)Math.round(amount * ROW));
                return true;
            }
        }
        return false;
    }

    boolean key(KeyEvent event) {
        if (event.isEscape()) {
            if (choice != null) closeChoice();
            else if (itemOpen) toggleItems();
            else if (lootOpen) toggleLoot();
            else guard.accept(close);
            return true;
        }
        if (event.key() == InputConstants.KEY_TAB) {
            var visible = new ArrayList<AbstractWidget>();
            for (var pane : panes) pane.visibleControls(visible);
            if (cardBounds != null) for (int i = 0; i < cards.size(); i++) {
                if (!showCardControls(i))
                    continue;
                var card = cards.get(i);
                if (cardBounds.intersects(card.count())) visible.add(card.count());
                if (cardBounds.intersects(card.remove())) visible.add(card.remove());
            }
            if (!visible.isEmpty()) {
                int step = event.hasShiftDown() ? -1 : 1;
                int index = visible.indexOf(focused);
                setFocus(visible.get(Math.floorMod(index < 0 && step < 0 ? -1 : index + step, visible.size())));
            }
            return true;
        }
        if (focused != null && focused.keyPressed(event)) return true;
        return true;
    }

    boolean charTyped(CharacterEvent event) {
        if (focused != null) focused.charTyped(event);
        return true;
    }

    void draw(GuiGraphicsExtractor g, int mx, int my, float tick) {
        long now = System.nanoTime();
        var masks = new Rect[panes.size()];
        for (int i = 0; i < masks.length; i++) masks[i] = panes.get(i).visible(now);
        boolean overCards = cardsAt(mx, my);
        for (int i = 0; i < masks.length; i++) {
            var pane = panes.get(i);
            if (pane == cardParent) drawCards(g, mx, my, tick);
            boolean covered = overCards && pane != cardParent;
            for (int j = i + 1; !covered && j < masks.length; j++)
                covered = masks[j].hits(mx, my);
            pane.draw(g, masks[i], covered ? -1 : mx, covered ? -1 : my, tick);
        }
    }

    private void drawCards(GuiGraphicsExtractor g, int mx, int my, float tick) {
        layoutCards(mx, my);
        int interactive = frontCardAt(mx, my);
        int slide = cardSlide();
        int left = Math.max(MARGIN, cardBounds.x() - (cardSide > 0 ? slide : 0));
        int right = Math.min(screenWidth - MARGIN, cardBounds.right() + (cardSide < 0 ? slide : 0));
        TargetTileLayer.draw(g, left, cardBounds.y(), right - left, cardBounds.h(), () -> {
            for (int i = 0; i < cards.size(); i++) {
                if (i != hoveredCard) drawCard(g, i, interactive, mx, my, tick);
            }
            if (hoveredCard >= 0) drawCard(g, hoveredCard, interactive, mx, my, tick);
            int range = cardScrollLimit();
            if (range > 0) {
                int thumb = cardThumbHeight(range);
                int top = cardBounds.y() + Scrollbar.thumbOffset(cardBounds.h(), cardBounds.h() + range, 1, cardScroll);
                int x = cardScrollbarX();
                g.nextStratum();
                g.fill(x, cardBounds.y(), x + 3, cardBounds.bottom(), 0xFF2F2F2F);
                g.fill(x, top, x + 3, top + thumb, 0xFFAFAFAF);
            }
        });
    }

    private final class Pane {
        private final int width;
        private final List<Row> rows = new ArrayList<>();
        private Rect bounds;
        private int total, scroll, thumbGrab, direction, fixedTop, fixedBottom, revealCenter;
        private long revealedAt;
        private EditBox filterBox;
        private boolean filterAbove;
        private MenuRow done, back, remove, closeButton;
        private List<AbstractWidget> fixedWidgets = List.of();
        private Component title;

        Pane(int width) { this.width = width; }

        void title(String label) {
            String text = trim(label, width - 2 * PAD - 2);
            rows.add(new Row(MenuAccent.heading(text), total + 3, 0xFFFFFFFF, null, 0, 0));
            total += ROW;
        }

        private Component rowLabel(String label) {
            return Component.literal(trim(label, width - 2 * PAD - 2));
        }

        void heading(String label, int color) {
            rows.add(new Row(rowLabel(label), total + 3, color, null, 0, 0));
            total += ROW;
        }

        void wrapped(String label) {
            for (var line : font.split(Component.literal(label), Math.max(1, width - 2 * PAD - 4))) {
                var builder = new StringBuilder();
                line.accept((index, style, point) -> { builder.appendCodePoint(point); return true; });
                rows.add(new Row(rowLabel(builder.toString()), total + 2, 0xFFC6C6C6, null, 0, 0));
                total += font.lineHeight + 2;
            }
            total += 3;
        }

        void number(String label, String current, int max, Consumer<String> onEdit) {
            var input = box(current, max, value -> { onEdit.accept(value); validate(); });
            input.setWidth(Math.max(1, Math.min(58, width - 2 * PAD)));
            rows.add(new Row(rowLabel(label), total + 2, TEXT, input, PAD, total + font.lineHeight + 4));
            total += font.lineHeight + 20;
        }

        void toggle(String label, boolean on, Consumer<Boolean> setter) {
            var row = row(label, () -> {});
            row.switchValue = on;
            row.action = () -> {
                row.switchValue = !row.switchValue;
                setter.accept(row.switchValue);
            };
        }

        MenuRow row(String label, Runnable action) { return addRow(label, "", null, action); }

        MenuRow branch(String label, String detail, Section section, Runnable action) {
            var row = addRow(label, detail, section, action);
            triggers.put(section, row);
            return row;
        }

        private MenuRow addRow(String label, String detail, Section section, Runnable action) {
            var row = new MenuRow(label, detail, section, action);
            row.setWidth(Math.max(1, width - 2 * PAD - 3));
            int labelWidth = row.getWidth() - (section == null ? 4 : 18);
            if (font.width(label) > labelWidth || font.width(detail) > labelWidth)
                row.setTooltip(Tooltip.create(Component.literal(detail.isEmpty() ? label : label + "\n" + detail)));
            rows.add(new Row(null, total, TEXT, row, PAD, total));
            total += detail.isEmpty() ? ROW : 2 * font.lineHeight + 7;
            return row;
        }

        void header(String label, Runnable onClose) {
            title = MenuAccent.heading(trim(label, width - 26));
            closeButton = new MenuRow("×", "", null, onClose);
            closeButton.setWidth(14);
            closeButton.centerLabel = true;
        }

        void removeAction(String label, Runnable action) {
            remove = new MenuRow(label, "", null, action);
            remove.footerStyle = 1;
        }

        private int actionHeight() {
            return done == null ? 0 : (remove != null && width < 144 ? 2 * ROW + 4 : ROW + 2);
        }

        MenuRow footer(String primary, Runnable confirm, String secondary, Runnable cancel) {
            done = new MenuRow(primary, "", null, confirm);
            back = new MenuRow(secondary, "", null, cancel);
            done.footerStyle = 3;
            back.footerStyle = 2;
            return done;
        }

        private int fixedHeight() {
            return 2 * PAD + (title == null ? 0 : ROW) + actionHeight() + (filterBox == null ? 0 : ROW + 2);
        }

        int windowHeight(int maximum) {
            return Math.max(1, Math.min(Math.min(total + fixedHeight(), maximum), screenHeight - 2 * MARGIN));
        }

        void attach(Pane parent, Section section, int maxHeight, boolean animate) {
            attach(parent, section, maxHeight, windowHeight(maxHeight), animate);
        }

        void attach(Pane parent, Section section, int maxHeight, int preferredHeight, boolean animate) {
            var anchor = triggers.get(section);
            int height = windowHeight(maxHeight);
            int anchorY = Math.clamp(anchor.getY(), parent.contentTop(), Math.max(parent.contentTop(), parent.contentBottom() - ROW));
            boolean above = ConstructorDropList.preferAbove(preferredHeight, anchorY + ROW - MARGIN, screenHeight - MARGIN - anchorY);
            int x = sideX(parent.bounds, width, parent.direction);
            direction = x >= parent.bounds.x() ? 1 : -1;
            filterAbove = !above;
            revealCenter = anchorY + ROW / 2;
            revealedAt = animate ? transitions.getOrDefault(section, 0L) : 0;
            anchor.arrowDirection = direction;
            place(x, above ? anchorY + ROW - height : anchorY, height);
            if (revealedAt == REVEAL_ON_LAYOUT) {
                revealedAt = System.nanoTime();
                transitions.put(section, revealedAt);
            }
            panes.add(this);
        }

        void place(int x, int y, int height) {
            if (direction == 0) direction = TargetEditor.this.direction;
            bounds = new Rect(clampX(x, width), clampY(y, height), width, height);
            for (var row : rows) {
                if (row.control() instanceof MenuRow branch && branch.section != null)
                    branch.arrowDirection = direction;
            }
            fixedTop = PAD + (title == null ? 0 : ROW) + (filterBox != null && filterAbove ? ROW + 2 : 0);
            fixedBottom = PAD + actionHeight() + (filterBox != null && !filterAbove ? ROW + 2 : 0);
            scrollTo(scroll);
            if (closeButton != null) {
                closeButton.setX(bounds.right() - 18);
                closeButton.setY(bounds.y() + 2);
            }
            if (filterBox != null) {
                filterBox.setWidth(Math.max(1, width - 2 * PAD));
                filterBox.setX(bounds.x() + PAD);
                filterBox.setY(filterAbove ? bounds.y() + PAD + ROW : bounds.bottom() - PAD - ROW);
            }
            if (done != null) {
                int left = bounds.x() + PAD, right = bounds.right() - PAD;
                int yButton = bounds.bottom() - PAD - ROW;
                boolean stacked = remove != null && width < 144;
                int columns = remove != null && !stacked ? 3 : 2;
                int buttonWidth = Math.max(1, (right - left - 3 * (columns - 1)) / columns);
                positionAction(done, right - buttonWidth, yButton, buttonWidth);
                positionAction(back, right - 2 * buttonWidth - 3, yButton, buttonWidth);
                if (remove != null) positionAction(remove, left, stacked ? yButton - ROW - 2 : yButton,
                        stacked ? Math.max(1, right - left) : buttonWidth);
            }
            var widgets = new ArrayList<AbstractWidget>(5);
            if (closeButton != null) widgets.add(closeButton);
            if (filterBox != null) widgets.add(filterBox);
            if (remove != null) widgets.add(remove);
            if (done != null) { widgets.add(back); widgets.add(done); }
            fixedWidgets = List.copyOf(widgets);
        }

        private void positionAction(MenuRow button, int x, int y, int w) {
            button.setX(x);
            button.setY(y);
            button.setWidth(w);
            button.setTooltip(font.width(button.getMessage()) > w - 4 ? Tooltip.create(button.getMessage()) : null);
        }

        int contentTop() { return bounds.y() + fixedTop; }
        int contentBottom() { return Math.max(contentTop(), bounds.bottom() - fixedBottom); }
        int viewportHeight() { return contentBottom() - contentTop(); }
        Rect contentBounds() { return new Rect(bounds.x(), contentTop(), bounds.w(), viewportHeight()); }
        int maxScroll() { return Math.max(0, total - viewportHeight()); }

        void scrollTo(int value) {
            scroll = Math.clamp(value, 0, maxScroll());
            for (var row : rows) if (row.control() != null) {
                row.control().setX(bounds.x() + row.controlX());
                row.control().setY(contentTop() + row.controlY() - scroll);
            }
        }

        int thumbHeight() { return Scrollbar.thumbHeight(viewportHeight(), total, 8); }
        int thumbTop() { return contentTop() + Scrollbar.thumbOffset(viewportHeight(), total, 8, scroll); }

        boolean pickScrollbar(double x, double y) {
            if (maxScroll() == 0 || x < bounds.right() - 5 || y < contentTop() || y >= contentBottom()) return false;
            thumbGrab = (int)y - thumbTop();
            if (thumbGrab < 0 || thumbGrab >= thumbHeight()) {
                thumbGrab = thumbHeight() / 2;
                dragScrollbar(y);
            }
            return true;
        }

        void dragScrollbar(double y) {
            int travel = viewportHeight() - thumbHeight();
            if (travel > 0) scrollTo((int)Math.round((y - contentTop() - thumbGrab) * maxScroll() / travel));
        }

        Rect visible() { return visible(System.nanoTime()); }

        Rect visible(long now) {
            if (revealedAt == 0) return bounds;
            double elapsed = Math.clamp((now - revealedAt) / (double) ANIMATION, 0, 1);
            if (elapsed == 1) return bounds;
            double remaining = 1 - elapsed;
            double progress = 1 - remaining * remaining * remaining;
            int startWidth = Math.min(20, width);
            int startHeight = Math.min(ROW, bounds.h());
            int startX = direction > 0 ? bounds.x() : bounds.right() - startWidth;
            int startY = Math.clamp(revealCenter - startHeight / 2, bounds.y(), bounds.bottom() - startHeight);
            var origin = new ScreenRectangle(startX, startY, startWidth, startHeight);
            var mask = TargetTileLayer.reveal(bounds.screenRect(), origin, progress);
            return new Rect(mask.left(), mask.top(), mask.width(), mask.height());
        }

        AbstractWidget widgetAt(double x, double y) {
            for (var widget : fixedWidgets) if (widget.active && widget.isMouseOver(x, y)) return widget;
            if (y >= contentTop() && y < contentBottom())
                for (var row : rows) if (row.control() != null && row.control().active && row.control().isMouseOver(x, y))
                    return row.control();
            return null;
        }

        void visibleControls(List<AbstractWidget> output) {
            var mask = visible();
            for (var widget : fixedWidgets)
                if (widget.active && mask.intersects(widget)) output.add(widget);
            var content = contentBounds();
            for (var row : rows) {
                var widget = row.control();
                if (widget != null && widget.active && content.intersects(widget) && mask.intersects(widget))
                    output.add(widget);
            }
        }

        void draw(GuiGraphicsExtractor g, Rect mask, int mx, int my, float tick) {
            g.nextStratum();
            g.fill(mask.x() - 1, mask.y() - 1, mask.right() + 1, mask.bottom() + 1, EDGE);
            g.fill(mask.x(), mask.y(), mask.right(), mask.bottom(), BACKGROUND);
            boolean hovered = mask.hits(mx, my);
            int pointerX = hovered ? mx : -1, pointerY = hovered ? my : -1;
            TargetTileLayer.clip(g, mask.screenRect(), () -> {
                if (title != null)
                    g.text(font, title, bounds.x() + PAD, bounds.y() + PAD, 0xFFFFFFFF);
                drawRows(g, pointerX, pointerY, tick);
                for (var widget : fixedWidgets)
                    widget.extractRenderState(g, pointerX, pointerY, tick);
            });
        }

        private void drawRows(GuiGraphicsExtractor g, int mx, int my, float tick) {
            var content = contentBounds();
            if (content.h() == 0) return;
            boolean hovered = content.hits(mx, my);
            TargetTileLayer.clip(g, content.screenRect(), () -> {
                for (var row : rows) {
                    int y = content.y() + row.offset() - scroll;
                    int extra = row.control() == null ? ROW : row.controlY() - row.offset() + row.control().getHeight();
                    if (y + extra <= content.y()) continue;
                    if (y >= content.bottom()) break;
                    if (row.label() != null)
                        g.text(font, row.label(), bounds.x() + PAD, y, row.labelColor());
                    if (row.control() != null)
                        row.control().extractRenderState(g, hovered ? mx : -1, hovered ? my : -1, tick);
                }
                if (maxScroll() > 0) {
                    int top = thumbTop();
                    g.fill(bounds.right() - 4, top, bounds.right() - 1, top + thumbHeight(), 0xFFAFAFAF);
                }
            });
        }
    }

    private final class MenuRow extends AbstractWidget {
        private final String title, detail;
        private final Section section;
        private Runnable action;
        private Boolean switchValue;
        private int footerStyle;
        private boolean centerLabel;
        private int arrowDirection;

        MenuRow(String title, String detail, Section section, Runnable action) {
            super(0, 0, 1, detail.isEmpty() ? ROW : 2 * font.lineHeight + 7, Component.literal(title));
            this.title = title;
            this.detail = detail;
            this.section = section;
            this.action = action;
            arrowDirection = direction;
        }

        @Override public void onClick(MouseButtonEvent event, boolean dbl) { action.run(); }
        @Override public boolean keyPressed(KeyEvent event) {
            if (!active || !event.isSelection()) return false;
            action.run();
            return true;
        }

        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
            int x = getX(), y = getY(), width = getWidth();
            if (active && (isHoveredOrFocused() || isMouseOver(mx, my))) g.fill(x, y, x + width, y + getHeight(), 0xFF3A3A3A);
            int color = active ? TEXT : 0xFF919191;
            int labelWidth = width - (section != null || switchValue != null ? 20 : 4);
            int labelX = x + 2;
            if (footerStyle != 0) {
                int border = footerStyle == 3 ? 0xFF9E8B69 : footerStyle == 1 ? 0xFF886359 : 0xFF66645F;
                TargetTileLayer.button(g, x, y, width, getHeight(), active, isHoveredOrFocused(), border);
                g.centeredText(font, trim(title, labelWidth), x + width / 2, y + 4, color);
            } else {
                if (!active && switchValue == null) TargetTileLayer.button(g, x, y, width, getHeight(), false, false, 0xFFB385E5);
                if (centerLabel) g.centeredText(font, title, x + width / 2, y + (getHeight() - font.lineHeight) / 2, color);
                else g.text(font, trim(title, labelWidth), labelX, y + 3, color);
            }
            if (!detail.isEmpty()) g.text(font, trim(detail, labelWidth), labelX, y + font.lineHeight + 4, 0xFFBABABA);
            if (switchValue != null) {
                TargetTileLayer.toggle(g, x + width - 15, y + 2, 12, 12, switchValue, isHoveredOrFocused(), active);
            }
            handleCursor(g);
            if (section != null)
                drawBranch(g, section, x + width - 7, y + getHeight() / 2, arrowDirection, color);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }



    private Value validate() {
        if (apply == null) return null;
        try {
            Value value = edited();
            if (value.loot() != null && creator != null) checkLootLimits(value.loot());
            apply.active = true;
            apply.setTooltip(null);
            return value;
        } catch (IllegalArgumentException invalid) {
            apply.active = false;
            apply.setTooltip(Tooltip.create(Component.literal(invalid.getMessage())));
            return null;
        }
    }

    private void checkLootLimits(LootConstraint loot) {
        var available = choices();
        if (!available.complete())
            return;
        for (var item : loot.items()) {
            var limit = available.items().stream().filter(i -> i.item().equals(item.item())).findFirst().orElse(null);
            String name = ResultTexts.name(item.item());
            if (limit == null)
                throw new IllegalArgumentException(MapTexts.text("loot_not_here", name));
            if (item.count() > limit.chestMax()) {
                throw new IllegalArgumentException(MapTexts.text("loot_too_many", name, item.count(),
                        limit.chestMax(), limit.rollMax(), limit.slotMax()));
            }
        }
    }

    boolean applyToDraft() {
        Value value = validate();
        if (value == null) return false;
        draft.configure(target, value.minimum(), value.maximum(), value.countMode(), value.quantity(),
                value.loot(), value.pinned(), value.tags());
        return true;
    }

    private Value edited() {
        int low, high, count;
        StructureTags selectedTags;
        try {
            low = Integer.parseInt(minimum);
            high = Integer.parseInt(maximum);
            count = usesCount() ? Integer.parseInt(quantity) : 1;
            selectedTags = tags.eyes() ? new StructureTags(tags.abandoned(), tags.spawner(),
                    Integer.parseInt(eyesMinimum), Integer.parseInt(eyesMaximum)) : tags;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(MapTexts.text("edit_range_invalid"), invalid);
        }
        if (low < 0 || high < 64 || high > 4096 || low > high || count < (countMode == Modifier.SOME ? 2 : 0) || count > 64) {
            throw new IllegalArgumentException(MapTexts.text("edit_range_invalid"));
        }
        LootConstraint loot = null;
        if (lootEnabled) {
            if (items.isEmpty() && (isBiome() || container.equals("any"))) {
                throw new IllegalArgumentException(MapTexts.text("loot_select_first"));
            }
            loot = new LootConstraint(items.values().stream().map(LootInput::requirement).toList(), container);
        }
        return new Value(low, high, countMode, count, loot, pinned, selectedTags);
    }

    boolean dirty() {
        var placement = draft.placement(target).orElse(null);
        if (placement == null) return true;
        var saved = new Value(placement.minRadius(), placement.radius(), placement.countMode(),
                placement.countMode() != null && (placement.countMode().counted() || placement.countMode() == Modifier.SOME)
                        ? placement.quantity() : 1, placement.loot(),
                placement.pinned(), placement.tags());
        try {
            return !edited().equals(saved);
        } catch (IllegalArgumentException invalid) {
            return true;
        }
    }

    boolean canApply() {
        return validate() != null;
    }

}
