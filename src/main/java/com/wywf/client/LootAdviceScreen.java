package com.wywf.client;

import com.wywf.core.*;
import com.wywf.verify.LootCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

final class LootAdviceScreen extends Screen {
    private final Consumer<String> applyCorrection;
    private final Runnable edit;
    private final List<LootCatalog.Issue> issues;
    private final String corrected;
    private final List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
    private int offset;
    LootAdviceScreen(CreateWorldScreen parent, ParsedQuery query, SearchConfig config, List<LootCatalog.Issue> issues) {
        this(query, issues, corrected -> {
            parent.getUiState().setSeed(corrected);
            var parsed = new QueryParser(new KeywordDictionary(config.queryLanguage())).parse(corrected);
            new SearchScreen(parent, corrected, parsed, config).open();
        }, () -> Minecraft.getInstance().setScreenAndShow(parent));
    }

    LootAdviceScreen(ParsedQuery query, List<LootCatalog.Issue> issues, Consumer<String> applyCorrection, Runnable edit) {
        super(MapTexts.label("loot_advice_title"));
        this.issues = List.copyOf(issues);
        this.applyCorrection = applyCorrection;
        this.edit = edit;
        corrected = correctedQuery(query, issues);
    }

    static String correctedQuery(ParsedQuery query, List<LootCatalog.Issue> issues) {
        Map<ParsedQuery.Term, ParsedQuery.Term> replacements = new IdentityHashMap<>();
        for (var term : query.terms()) {
            if (term.loot == null) continue;
            List<LootConstraint.Requirement> items = new ArrayList<>();
            for (var item : term.loot.items()) {
                var issue = issues.stream().filter(i -> i.term() == term && i.item().equals(item)).findFirst().orElse(null);
                if (issue == null) {
                    items.add(item);
                } else if (!issue.absent()) {
                    items.add(new LootConstraint.Requirement(item.item(), issue.chestMax(),
                            item.enchantment(), item.level(), item.durability()));
                }
            }
            LootConstraint loot = items.isEmpty() && term.loot.container().equals("any")
                    ? null : new LootConstraint(items, term.loot.container());
            replacements.put(term, term.withLoot(loot));
        }
        var terms = query.terms().stream().map(term -> replacements.getOrDefault(term, term)).toList();
        return new ParsedQuery(query.raw(), terms, query.ignoredWords(), replaceTerms(query.logic(), replacements), query.error())
                .withPlace(query.place()).normalized();
    }

    private static QueryLogic.Node replaceTerms(QueryLogic.Node node, Map<ParsedQuery.Term, ParsedQuery.Term> replacements) {
        return switch (node) {
            case null -> null;
            case QueryLogic.Leaf leaf -> new QueryLogic.Leaf(replacements.getOrDefault(leaf.term(), leaf.term()));
            case QueryLogic.Not not -> new QueryLogic.Not(replaceTerms(not.child(), replacements));
            case QueryLogic.Binary binary -> new QueryLogic.Binary(binary.op(), replaceTerms(binary.left(), replacements),
                    replaceTerms(binary.right(), replacements));
        };
    }

    @Override protected void init() {
        lines.clear();
        for (var issue : issues) {
            String line = issue.absent()
                    ? MapTexts.text("loot_absent",ResultTexts.name(issue.item().item()),ResultTexts.name(issue.term().canonical))
                    : MapTexts.text("loot_too_many",ResultTexts.name(issue.item().item()),issue.item().count(),issue.chestMax(),issue.rollMax(),issue.slotMax());
            lines.addAll(font.split(Component.literal(line),width-24)); lines.add(net.minecraft.util.FormattedCharSequence.EMPTY);
        }
        int w=(width-30)/2;
        Button use = addRenderableWidget(Button.builder(MapTexts.label("loot_use_fix"), b -> applyCorrection.accept(corrected))
                .bounds(12, height - 30, w, 20).build());
        use.setTooltip(Tooltip.create(Component.literal(corrected)));
        addRenderableWidget(Button.builder(MapTexts.label("loot_edit"),b -> onClose()).bounds(18+w,height-30,w,20).build());
    }
    @Override public boolean mouseScrolled(double x,double y,double h,double v) {
        offset=Math.max(0,Math.min(Math.max(0,lines.size()-(height-105)/12),offset+(v>0?-3:3)));
        return true;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float tick) {
        MenuBands.draw(g,width,height,32,height-65);
        g.centeredText(font,title,width/2,12,0xFFFFFFFF);
        int rows=Math.max(1,(height-105)/12);
        for(int i=offset;i<Math.min(lines.size(),offset+rows);i++)g.text(font,lines.get(i),12,43+(i-offset)*12,0xFFFFD18A);
        g.text(font,font.plainSubstrByWidth(MapTexts.text("loot_bound_help"),width-24),12,height-53,0xFFDDDDDD);
        super.extractRenderState(g,mx,my,tick);
    }
    @Override public void onClose() { edit.run(); }
}