package com.wywf.client;

import com.wywf.core.*;
import com.wywf.verify.LootCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.*;

final class TargetEditor {
    private record Row(String label, AbstractWidget widget, int offset, int height) {}

    private record LootInput(LootConstraint.Requirement original, String quantity) {
        LootInput(LootConstraint.Requirement original) {
            this(original, Integer.toString(original.count()));
        }

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

    private record Value(int minimum, int maximum, Modifier countMode, int quantity, LootConstraint loot,
                         boolean pinned, StructureTags tags) {}
    private final SearchDraft draft;
    private final SearchDraft.Target target;
    private final CreateWorldScreen creator;
    private final Runnable refresh, close;
    private final java.util.function.Consumer<Runnable> guard;
    private final net.minecraft.client.gui.Font font=Minecraft.getInstance().font;
    private final List<Row> rows=new ArrayList<>();
    private final Map<String,LootCatalog.Choices> cachedChoices=new HashMap<>();
    private final Map<String, LootInput> items = new LinkedHashMap<>();
    private final List<String> containers;
    private String minimum, maximum, quantity, container, filter = "";
    private Modifier countMode;
    private StructureTags tags;
    private String eyesMinimum,eyesMaximum;
    private boolean pinned, lootEnabled, filtering;
    private int x,y,headerY,width,bottom,total,scroll;
    private List<net.minecraft.util.FormattedCharSequence> header=List.of();
    private ConstructorDropList open;
    private AbstractWidget focused,capture;
    private Button apply;
    TargetEditor(SearchDraft draft,SearchDraft.Target target,CreateWorldScreen creator,Runnable refresh,Runnable close) {
        this(draft,target,creator,refresh,close,Runnable::run);
    }
    TargetEditor(SearchDraft draft,SearchDraft.Target target,CreateWorldScreen creator,Runnable refresh,Runnable close,java.util.function.Consumer<Runnable> guard) {
        this.guard=guard;
        this.draft=draft;
        this.target=target;
        this.creator=creator;
        this.refresh=refresh;
        this.close=close;
        var p=draft.placement(target).orElseThrow();
        minimum=""+p.minRadius();
        maximum=""+p.radius();
        quantity=""+p.quantity();
        tags=p.tags();
        eyesMinimum=""+(tags.eyes()
                ? tags.eyesMin():1);
        eyesMaximum=""+(tags.eyes()
                ? tags.eyesMax():12);
        countMode=p.countMode();
        pinned=p.pinned();
        lootEnabled=p.loot()!=null;
        container=p.loot()==null
                ? "any":p.loot().container();
        if (p.loot() != null) p.loot().items().forEach(item -> items.put(item.item(), new LootInput(item)));
        containers=creator==null?List.of("any"):LootCatalog.containers(creator,target.canonical());
    }
    SearchDraft.Target target(){
        return target;
    }
    List<AbstractWidget> widgets(){
        return rows.stream().map(Row::widget).filter(Objects::nonNull).toList();
    }
    AbstractWidget focused(){
        return focused;
    }
    private boolean ru(){
        return Minecraft.getInstance().getLanguageManager().getSelected().startsWith("ru");
    }
    private LootCatalog.Choices choices() {
        return cachedChoices.computeIfAbsent(container, c -> isBiome()
                ? LootCatalog.biomeChoices(creator, target.canonical(), c) : LootCatalog.choices(creator, target.canonical(), c));
    }

    private boolean isBiome() {

        return target.category() == KeywordDictionary.Category.BIOME;
    }
    void bounds(int x,int y,int width,int bottom) {
        this.x=x;this.headerY=y;
        this.width=width;
        this.bottom=bottom;
        rows.clear();
        total=0;
        open=null;
        capture=null;
        focused=null;
        header=font.split(Component.literal(target.phrase(
                ru()?KeywordDictionary.Lang.RU:KeywordDictionary.Lang.EN)+(lootEnabled?" · "+LootContainers.name(container,ru()):"")),width-8);
        this.y=y+header.size()*font.lineHeight+8;
        heading(MapTexts.text("edit_distance"));
        var modes = new ArrayList<Modifier>();
        modes.add(null);
        Arrays.stream(Modifier.values()).filter(m -> SearchDraft.supportsModifier(target.category(), m))
                .filter(m -> !com.wywf.map.BiomeMapColors.cave(target.canonical())
                        || m != Modifier.UNDER && (m != Modifier.FAR || !target.canonical().equals("minecraft:deep_dark")))
                .forEach(modes::add);
        drop(MapTexts.text("marker_modifier"), modes.stream().map(this::modifierName).toList(),
                modes.indexOf(countMode), i -> {
                    countMode = modes.get(i);
                    if (countMode != null) minimum = "0";
                    if (countMode == Modifier.SOME && quantity.equals("1")) quantity = "2";
                    if (!supportsLoot()) lootEnabled = false;
                    refresh.run();
                });
        if (countMode == null) number(
                SearchConstructorScreen.label("minimum").getString(),minimum,4,v->{
                    minimum=v;validate();
                });
        if (countMode == null || counted()) number(SearchConstructorScreen.label("maximum").getString(),maximum,4,v->{
            maximum=v;validate();
        });
        if (usesCount()) number(MapTexts.text("quantity"),quantity,2,v->{
            quantity=v;validate();
        });
        add(MapTexts.text("keep_condition"),new SettingToggle(MapTexts.text("keep_condition"),pinned,v->pinned=v),40);
        if(target.category()==KeywordDictionary.Category.STRUCTURE) {
            var tagChoices=StructureTags.options(target.canonical());
            if(!tagChoices.isEmpty()){
                var labels=new ArrayList<String>();labels.add(ru()?"Без тегов":"No tags");
                tagChoices.forEach(id->labels.add((tags.selected(id)?"✓ ":"")+StructureTags.label(id,ru())));
                drop(ru()?"Теги структуры":"Structure tags",labels,java.util.stream.IntStream.range(0,tagChoices.size())
                        .filter(i->tags.selected(tagChoices.get(i))).map(i->i+1)
                        .findFirst().orElse(0),i->{tags=i==0?StructureTags.NONE:tags.toggle(tagChoices.get(i-1));refresh.run();});
                if(!tags.empty())heading(tags.description(ru()));
                if(tags.eyes()){
                    number(ru()?"Минимум оков":"Minimum eyes",eyesMinimum,2,v->{
                        eyesMinimum=v;validate();
                    });
                    number(ru()?"Максимум оков":"Maximum eyes",eyesMaximum,2,v->{
                        eyesMaximum=v;validate();
                    });
                }
            }
        }
        if (creator != null && supportsLoot()) {
            if (isBiome() || containers.size() > 1) {
                heading(MapTexts.text("loot_settings"));
                add(MapTexts.text("loot_enabled"),
                        new SettingToggle(
                        MapTexts.text("loot_enabled"),lootEnabled,v->{
                            lootEnabled=v;refresh.run();
                        }),40);
                if(lootEnabled)buildLoot();
            } else if(creator != null && supportsLoot()) heading(MapTexts.text("loot_no_containers"));
        }
        apply=Button.builder(MapTexts.label("edit_done"),
                b->commit()).bounds(x,bottom-21,(width-6)/2,20).build();
        var cancel=Button.builder(SearchConstructorScreen.label("back"),
                b->guard.accept(close)).bounds(x+(width+6)/2,bottom-21,(width-6)/2,20).build();
        var delete=Button.builder(SearchConstructorScreen.label("delete"),
                b->{draft.remove(target);close.run();}).bounds(x,bottom-45,width-6,20).build();
        rows.add(new Row("",delete,-1,0));
        rows.add(new Row("",apply,-1,0));rows.add(new Row("",cancel,-1,0));
        layout();validate();
    }
    private boolean counted() {
        return countMode != null && countMode.counted();
    }

    private boolean usesCount() {
        return counted() || countMode == Modifier.SOME;
    }

    private boolean supportsLoot() {
        return !usesCount() && countMode != Modifier.NEVER && countMode != Modifier.ONLY && countMode != Modifier.UNDER;
    }

    private String modifierName(Modifier mode) {
        if (mode == null) return MapTexts.text("modifier_between");
        String id = mode.name().toLowerCase(Locale.ROOT);
        return MapTexts.text((mode.counted() ? "count_mode_" : "modifier_") + id);
    }

    private void buildLoot() {
        List<String> values=new ArrayList<>(containers);if(!values.contains(container))values.add(container);
        if (!isBiome()) {
            drop(MapTexts.text("loot_container"),values.stream().map(c->LootContainers.name(c,ru()))
                    .toList(),values.indexOf(container),i->{container=values.get(i);
                filter="";filtering=false;
                refresh.run();});
        } else {
            heading(MapTexts.text("loot_in_biome"));
        }
        var available=choices();
        heading(MapTexts.text("loot_one_chest"));
        var search=box(filter,100,v->{
            filter=v;filtering=true;refresh.run();
        });
        if(filtering){
            focused=search;search.setFocused(true);
        }
        search.setHint(SearchConstructorScreen.label("filter"));
        add(MapTexts.text("loot_item_filter"),search,39);
        var options=available.items().stream()
                .filter(i->!items.containsKey(i.item())).filter(i->
                (ResultTexts.name(i.item())+" "+i.item()).toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(i->ResultTexts.name(i.item()))).toList();
        var choose=drop(MapTexts.text("loot_choose"),options.stream().map(i->
                ResultTexts.name(i.item())).toList(),-1,i->
        {
            String item = options.get(i).item();
            items.put(item, new LootInput(new LootConstraint.Requirement(item, 1)));
            filter = "";
            filtering = false;
            refresh.run();
        });
        choose.active=!options.isEmpty()&&items.size()<16;
        choose.setTooltip(Tooltip.create(MapTexts.label(options.isEmpty()?"loot_no_items":"loot_bound_help")));
        for (var input : items.values()) {
            var item = input.original();
            String name = ResultTexts.name(item.item());
            heading(name);
            var limit = available.items().stream().filter(i -> i.item().equals(item.item())).findFirst().orElse(null);
            String help = limit == null ? MapTexts.text("loot_not_here", name)
                    : MapTexts.text("loot_limit", limit.chestMax(), limit.rollMax(), limit.slotMax());
            var count = box(input.quantity(), 4, value -> {
                items.put(item.item(), new LootInput(item, value));
                validate();
            });
            count.setWidth(width - 38);
            count.setTooltip(Tooltip.create(Component.literal(help)));
            add("", count, 24);
            var remove = Button.builder(Component.literal("×"), b -> {
                items.remove(item.item());
                refresh.run();
            }).bounds(0, 0, 24, 20).build();
            rows.add(new Row("", remove, total - 24, -24));
        }
        if(!available.complete())heading(MapTexts.text("loot_incomplete"));
    }
    private EditBox box(String value,int max,java.util.function.Consumer<String> change) {
        var b=new EditBox(font,0,0,width-6,20,Component.empty());b.setMaxLength(max);b.setValue(value);b.setResponder(change);
        return b;
    }
    private void number(String name,String value,int max,java.util.function.Consumer<String> change){
        add(name,box(value,max,change),40);
    }
    private void heading(String value){
        int h=font.split(Component.literal(value),width-8).size()*font.lineHeight+7;
        rows.add(new Row(value,null,total,h));
        total+=h;
    }
    private void add(String label,AbstractWidget widget,int height) {
        int extra=label.isEmpty()?0:Math.max(0,font.split(Component.literal(label),width-8).size()-1)*font.lineHeight;
        rows.add(new Row(label,widget,total,height+extra));
        total+=height+extra;
    }
    private ConstructorDropList drop(String label,List<String> options,int current,java.util.function.Consumer<Integer> select) {
        var list=new ConstructorDropList(0,0,width-6,"",options,current,font,select,this::open);
        add(label,list,40);
        return list;
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

    private void commit() {
        if (applyToDraft()) close.run();
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

    void pauseInput(){capture=null;if(open!=null)open.close();}
    private int contentBottom(){return bottom-52;}
    private int maxScroll(){return Math.max(0,total-(contentBottom()-y));}
    private void layout() {
        scroll=Math.max(0,Math.min(maxScroll(),scroll));
        for(var row:rows)if(row.widget()!=null&&row.offset()>=0) {
            var widget=row.widget();widget.setX(x+(row.height()==-24?width-30:0));
            widget.setY(y+row.offset()-scroll+(row.label().isEmpty()?0:font.split(Component.literal(row.label()),width-8).size()*font.lineHeight+4));
            widget.visible=widget.getY()+widget.getHeight()>y&&widget.getY()<contentBottom();
        }
    }
    private int popupHeight(){return Math.max(20,contentBottom()-open.getY()-20);}
    private void open(ConstructorDropList list) {
        if(open!=null&&open!=list)open.close();open=list;
        int over=list.getY()+80-contentBottom();if(over>0){scroll=Math.min(maxScroll(),scroll+over);layout();}
    }
    boolean click(MouseButtonEvent e,boolean keyboard) {
        if(open!=null&&open.isOpen()) {
            if(open.thumbHit((float)e.x(),(float)e.y(),popupHeight())){open.beginPopupDrag((float)e.y(),popupHeight());return true;}
            if(open.popupHit((float)e.x(),(float)e.y(),popupHeight())){open.handlePopupClick((float)e.x(),(float)e.y(),popupHeight());return true;}
            if(open.isMouseOver(e.x(),e.y())){open.close();return true;}open.close();
        }
        if(e.x()<x||e.x()>=x+width||e.y()<y||e.y()>=bottom)return false;
        for(var row:rows)if(row.widget()!=null&&row.widget().visible&&row.widget().isMouseOver(e.x(),e.y())) {
            if(e.y()>=contentBottom()&&row.offset()>=0)continue;
            if(focused!=null)focused.setFocused(false);focused=row.widget();capture=focused;focused.setFocused(true);return focused.mouseClicked(e,keyboard);
        }
        return true;
    }
    boolean drag(MouseButtonEvent e,double dx,double dy){if(open!=null&&open.isPopupDrag()){open.dragPopupTo((float)e.y(),popupHeight());return true;}return capture!=null&&capture.mouseDragged(e,dx,dy);}
    void release(MouseButtonEvent e){if(open!=null)open.endPopupDrag();if(capture!=null){capture.mouseReleased(e);capture=null;}}
    boolean scroll(double mx,double my,double dy) {
        if(open!=null&&open.isOpen()&&open.popupHit((float)mx,(float)my,popupHeight())){open.scrollPopup((float)dy,popupHeight());return true;}
        if(mx<x||mx>=x+width||my<y||my>=contentBottom())return false;
        if(open!=null)open.close();scroll-=(int)(dy*28);layout();return true;
    }
    boolean key(KeyEvent e){if(open!=null&&open.isOpen())return open.keyPressed(e);if(e.isEscape()){guard.accept(close);return true;}return focused!=null&&focused.keyPressed(e);}
    void draw(GuiGraphicsExtractor g,int mx,int my,float delta) {
        int hy=headerY;for(var line:header){g.text(font,line,x,hy,0xFFFFDF92);hy+=font.lineHeight;}
        g.enableScissor(x,y,x+width,contentBottom());
        for(var row:rows) {
            if(row.offset()<0)continue;
            int ry=y+row.offset()-scroll;if(ry+Math.max(24,row.height())<=y||ry>=contentBottom())continue;
            int lineY=ry+2;for(var line:font.split(Component.literal(row.label()),width-8)){g.text(font,line,x,lineY,row.widget()==null?0xFFFFDF92:0xFFFFFFFF);lineY+=font.lineHeight;}
            if(row.widget()!=null&&row.widget().visible)row.widget().extractRenderState(g,mx,my,delta);
        }
        g.disableScissor();
        for(var row:rows)if(row.offset()<0)row.widget().extractRenderState(g,mx,my,delta);
        if(maxScroll()>0){int h=contentBottom()-y,th=Math.max(15,h*h/Math.max(1,total)),ty=y+(int)((h-th)*scroll/(double)maxScroll());g.fill(x+width-3,y,x+width,contentBottom(),0x55000000);g.fill(x+width-3,ty,x+width,ty+th,0xAAFFFFFF);}
    }
    void popup(GuiGraphicsExtractor g,int mx,int my){if(open!=null&&open.isOpen())open.drawPopup(g,mx,my,popupHeight(),1);}
}
