package com.wywf.client;

import dev.diylib.client.ui.DiyVanillaButton;
import com.wywf.core.*;
import com.wywf.map.StructureLayers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import java.util.*;

final class StructureLayersScreen extends Screen {
    private final Screen parent;
    private final StructureLayers layers;
    private String filter = "";
    private int page;
    private List<SearchDraft.Target> targets;
    private List<net.minecraft.util.FormattedCharSequence> help=List.of();
    private int helpY,navY;
    StructureLayersScreen(Screen parent, StructureLayers layers) {
        super(MapTexts.label("structures"));
        this.parent = parent;
        this.layers = layers;
    }
    @Override protected void init() {
        int x = width / 2 - 155;
        help=font.split(MapTexts.label("structures_help"),Math.min(520,width-24));
        helpY=height-36-Math.min(3,help.size())*font.lineHeight;
        navY=helpY-26;
        EditBox search = addRenderableWidget(new EditBox(font, x, 32, 310, 20, SearchConstructorScreen.label("filter")));
        search.setValue(filter);
        search.setResponder(value -> {
            filter = value;
            page = 0;
            rebuildWidgets();
            children().stream().filter(c -> c instanceof EditBox).findFirst().ifPresent(this::setFocused);
        });
        var available=new ArrayList<>(ConstructorCatalog.available(new com.wywf.search.MinecraftWorldContextFactory()));
        if(layers.dimension()==DimensionSpace.OVERWORLD)for(String id:List.of(
                "wywf:slime_chunk","wywf:lava_pool","wywf:amethyst_geode","wywf:iron_vein","wywf:copper_vein","wywf:spawner"))
            if(id.equals("wywf:slime_chunk") || layers.supportsDiscovery() && !(parent instanceof LiveMapScreen))
                available.add(new SearchDraft.Target(id,KeywordDictionary.Category.STRUCTURE,ResultTexts.name(id),ResultTexts.name(id)));
        if (layers.dimension() == DimensionSpace.END && (layers.supportsDiscovery() || parent instanceof LiveMapScreen)) {
            String id = com.wywf.verify.EndReturnGateways.ID;
            available.add(new SearchDraft.Target(id, KeywordDictionary.Category.STRUCTURE, ResultTexts.name(id), ResultTexts.name(id)));
        }
        targets = available.stream().filter(t -> t.category() == KeywordDictionary.Category.STRUCTURE
                && DimensionSpace.of(t.canonical()) == layers.dimension())
                .filter(t -> ResultTexts.name(t.canonical()).toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))
                                .sorted(Comparator.<SearchDraft.Target>comparingInt(t -> {
                    String name = ResultTexts.name(t.canonical()).toLowerCase(Locale.ROOT);
                    String needle = filter.toLowerCase(Locale.ROOT);
                    return name.equals(needle) ? 0 : name.startsWith(needle) ? 1 : 2;
                }).thenComparing(t -> ResultTexts.name(t.canonical()))).toList();
        int rows = Math.max(1, (navY-58) / 22), pages = Math.max(1, (targets.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        for (int i = page * rows; i < Math.min(targets.size(), (page + 1) * rows); i++) {
            var t = targets.get(i);
            var button = addRenderableWidget(DiyVanillaButton.builder(net.minecraft.network.chat.Component.literal((layers.enabled.contains(t.canonical()) ? "✓ " : "□ ")
                    + ResultTexts.name(t.canonical())), b -> {
                layers.toggle(t.canonical());
                rebuildWidgets();
            }).bounds(x, 58 + (i % rows) * 22, 310, 20).build());
            if (t.canonical().equals(com.wywf.verify.EndReturnGateways.ID))
                button.setTooltip(Tooltip.create(MapTexts.label("end_return_gateway_help")));
        }
        addRenderableWidget(DiyVanillaButton.builder(net.minecraft.network.chat.Component.literal("<"), b -> {
            page = Math.max(0, page - 1);
            rebuildWidgets();
        })
                .bounds(x, navY, 32, 20).build()).active = page > 0;
        addRenderableWidget(DiyVanillaButton.builder(net.minecraft.network.chat.Component.literal(">"), b -> {
            page++;
            rebuildWidgets();
        })
                .bounds(x + 278, navY, 32, 20).build()).active = page + 1 < pages;
        addRenderableWidget(DiyVanillaButton.builder(SearchConstructorScreen.label("clear"),b->{
            layers.clearExtra();
            rebuildWidgets();
        }).bounds(x+48,navY,214,20).build());
        addRenderableWidget(DiyVanillaButton.builder(ResultTexts.label("back"), b -> onClose()).bounds(x + 48, height - 31, 214, 20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
        g.fill(0, 0, width, height, 0xED172126);
        g.centeredText(font, title, width / 2, 13, 0xFFFFFFFF);
        int hy=helpY;for(var line:help.subList(0,Math.min(3,help.size()))){
            g.text(font,line,(width-font.width(line))/2,hy,0xFFBBD0D8);
            hy+=font.lineHeight;
        }
        super.extractRenderState(g, mx, my, tick);
    }
    @Override public void onClose() { Minecraft.getInstance().setScreenAndShow(parent); }
}
