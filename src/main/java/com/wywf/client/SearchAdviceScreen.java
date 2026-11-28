package com.wywf.client;

import dev.diylib.client.ui.DiyVanillaButton;
import com.wywf.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import java.util.List;

public final class SearchAdviceScreen extends Screen {
    private final CreateWorldScreen parent;
    private final String original;
    private final ParsedQuery query;
    private final SearchConfig config;
    private final QueryAdvisor.Report report;
    private final Component heading = MenuAccent.heading(title.getString());
    private String measured="";
    private int page;
    private final BlockMenuSkin skin = BlockMenuSkin.random();
    private FtpScreen placeSearch;
    SearchAdviceScreen forPlace(FtpScreen screen) { placeSearch = screen; return this; }

    public SearchAdviceScreen(CreateWorldScreen parent, String original, ParsedQuery query, SearchConfig config) {
        this(parent,original,query,config,List.of());
    }
    public SearchAdviceScreen(CreateWorldScreen parent,String original,ParsedQuery query,SearchConfig config,List<SearchDiagnostics.Count> diagnostics){
        super(MapTexts.label("advice_title"));
        this.parent = parent;
        this.original = original;
        this.query = query;
        this.config = config.copy();
        diagnostics.stream().filter(d->d.failed()>=8).findFirst().ifPresent(d->{
            query.terms().stream().filter(t->t.toString().equals(d.condition())).findFirst().ifPresent(t->
                measured=MapTexts.text("obstacle",ResultTexts.name(t.canonical),d.failed(),d.total())+(t.pinned?" "+MapTexts.text("obstacle_pinned"):""));
        });
        var base=QueryAdvisor.inspect(query,config);
        var ordered=new java.util.ArrayList<>(base.advice());
        ordered.sort(java.util.Comparator.comparingDouble((QueryAdvisor.Advice a)->diagnostics.stream()
                .filter(d->query.terms().stream().anyMatch(t->t.canonical.equals(a.target())&&t.toString().equals(d.condition())))
                .mapToDouble(SearchDiagnostics.Count::rejection).max().orElse(-1)).reversed());
        report=new QueryAdvisor.Report(List.copyOf(ordered),base.reviewBeforeSearch(),base.conflict());
    }

    private net.minecraft.network.chat.Component header(){return measured.isEmpty()?MapTexts.label(report.conflict()?"advice_conflict":"advice_help"):net.minecraft.network.chat.Component.literal(measured);}
    private int contentTop(){return Math.max(69,27+font.split(header(),width-24).size()*font.lineHeight+8);}
    private int capacity(){return Math.max(1,(height-contentTop()-64)/40);}
    @Override protected void init() {
        int count = capacity();
        List<QueryAdvisor.Advice> choices = report.advice();
        int pages = Math.max(1, (choices.size() + count - 1) / count);
        page = Math.min(page, pages - 1);
        for (int i = page * count; i < Math.min(choices.size(), (page + 1) * count); i++) {
            var choice = choices.get(i);
            String label = choice.kind() == QueryAdvisor.Kind.CONFLICT
                    ? MapTexts.text(choice.before().startsWith("never ") ? "advice_remove_exclusion" : "advice_remove_requirement",
                            ResultTexts.name(choice.target()))
                    : MapTexts.text("advice_" + choice.kind().name().toLowerCase(java.util.Locale.ROOT), ResultTexts.name(choice.target()));
            Button use = DiyVanillaButton.builder(net.minecraft.network.chat.Component.literal(label), b -> start(choice.query()))
                    .bounds(12, contentTop() + (i % count) * 40, width - 24, 20).build();
            use.setTooltip(Tooltip.create(net.minecraft.network.chat.Component.literal(
                    choice.before() + " → " + (choice.after().isEmpty() ? MapTexts.text("removed") : choice.after())
                    + "\n" + choice.query())));
            addRenderableWidget(use);
        }
        addRenderableWidget(DiyVanillaButton.builder(net.minecraft.network.chat.Component.literal("<"), b -> {
            page = Math.max(0, page - 1);
            rebuildWidgets();
        })
                .bounds(12, height - 55, 30, 20).build()).active = page > 0;
        addRenderableWidget(DiyVanillaButton.builder(net.minecraft.network.chat.Component.literal(">"), b -> {
            page++;
            rebuildWidgets();
        })
                .bounds(48, height - 55, 30, 20).build()).active = page + 1 < pages;
        int w = Math.min(180, (width - 30) / 2);
        addRenderableWidget(DiyVanillaButton.builder(MapTexts.label("as_is"), b -> start(original))
                .bounds(12, height - 29, w, 20).build());
        addRenderableWidget(DiyVanillaButton.builder(ResultTexts.label("back"), b -> onClose())
                .bounds(width - w - 12, height - 29, w, 20).build());
    }

    private void start(String text) {
        var parsed = new QueryParser(new KeywordDictionary(config.queryLanguage())).parse(text);
        if (parsed.isEmpty()) return;
        if (placeSearch != null) {
            placeSearch.editConditions(parsed.expression());
            return;
        }
        parent.getUiState().setSeed(text);
        new SearchScreen(parent, text, parsed, config).open();
    }

    @Override public void onClose() { Minecraft.getInstance().setScreenAndShow(placeSearch == null ? parent : placeSearch); }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float tick) {
        BlockMenuSkin.panel(g, skin, 8, 4, width - 16, height - 8, 0xFF9FBF91, 0xFFD9BA86);
        g.centeredText(font, heading, width / 2, 10, 0xFFFFFFFF);
        int y = 27;
        for (var line : font.split(header(), width - 24)) {
            g.centeredText(font, line, width / 2, y, 0xFFE5CD94);
            y += 11;
        }
        int count = capacity();
        for (int i = page * count; i < Math.min(report.advice().size(), (page + 1) * count); i++) {
            var choice = report.advice().get(i);
            String detail = choice.before() + " → " + (choice.after().isEmpty() ? MapTexts.text("removed") : choice.after());
            g.text(font, font.plainSubstrByWidth(detail, width - 24), 12, contentTop()+23+(i % count)*40, 0xFFCEDDE2);
        }
        if (report.advice().isEmpty()) g.text(font, MapTexts.label("advice_none"), 12, 80, 0xFFE5CD94);
        super.extractRenderState(g, mx, my, tick);
    }
}
