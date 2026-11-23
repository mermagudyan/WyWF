package com.wywf.client;

import com.wywf.core.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.*;

final class ConstructorSettings {
    private record Row(java.util.List<net.minecraft.util.FormattedCharSequence> lines, AbstractWidget widget, int offset, int height) {}
    private final List<Row> rows = new ArrayList<>();
    private final SearchConfig config;
    private final net.minecraft.client.gui.Font font;
    private int x,y,width,bottom,total,scroll;
    private boolean dirty,scrollDrag;
    private long saveAt;
    private double scrollGrab;
    private AbstractWidget capture,focused;
    private ConstructorDropList open;
    private SettingSlider candidates, minimum, ramp, seeds;
    ConstructorSettings(SearchConfig config) {
        this.config=config;
        font=Minecraft.getInstance().font;
    }
    void bounds(int x, int y, int width, int bottom, SearchConfig.SearchCenter requiredCenter) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.bottom = Math.max(y + 1, bottom);
        if(focused!=null)
            focused.setFocused(false);
        if(open!=null){
            open.close();
            open.endPopupDrag();
        }
        rows.clear();
        total=0;
        open=null;
        capture=null;
        focused=null;
        scrollDrag=false;
        scrollGrab=0;
        category("category.general");
        enumeration("general.language.option", KeywordDictionary.Lang.values(), config.queryLanguage(), config::queryLanguage);

        category("search.center.name");
        var center = enumeration("search.center.option", SearchConfig.SearchCenter.values(),
                requiredCenter == null ? config.searchCenter() : requiredCenter, config::searchCenter);
        center.active = requiredCenter == null;
        toggle("search.center.random_start", config.randomizeStart(), config::randomizeStart);

        category("search.scan.name");
        number("search.scan.structure_radius", config.searchRadiusChunks(), 8, 80, config::searchRadiusChunks, v -> v + " ch");
        number("search.scan.biome_radius", config.biomeCheckRadiusChunks(), 4, 64, config::biomeCheckRadiusChunks, v -> v + " ch");
        number("search.scan.biome_step", config.biomeSampleStepChunks(), 1, 8, config::biomeSampleStepChunks, v -> v + " ch");
        toggle("search.candidates.linear", config.linearBiomeSearch(), config::linearBiomeSearch);

        category("search.candidates.name");
        toggle("search.candidates.server_verification", config.serverVerification(), config::serverVerification);
        toggle("search.candidates.offer_history", config.offerSearchHistory(), config::offerSearchHistory);
        toggle("search.candidates.sort_distance", config.sortCandidatesByDistance(), config::sortCandidatesByDistance);
        candidates = number("search.candidates.collect", config.candidatesToCollect(), 1, 20, config::candidatesToCollect, Integer::toString);
        minimum = number("candidates.minimum", config.minCandidates(), 1, 20, config::minCandidates, Integer::toString);

        category("search.limits.name");
        toggle("search.candidates.stop_first", config.stopAtFirstCandidate(), config::stopAtFirstCandidate);
        ramp = number("candidates.ramp", config.candidateRampDownSeconds(), 0, 120, config::candidateRampDownSeconds, v -> v + " s");
        number("search.limits.time", config.timeLimitMinutes(), 5, 120, config::timeLimitMinutes, v -> v + " min");
        toggle("search.limits.infinite", config.infiniteSeeds(), config::infiniteSeeds);
        seeds = number("search.limits.max_seeds", (int)(config.rawMaxSeedsToCheck() / 1_000_000), 1, 100_000,
                v -> config.maxSeedsToCheck(v * 1_000_000L), v -> v + " M");

        category("constructor.performance");
        enumeration("general.performance.thread_mode", SearchConfig.Mode.values(), config.mode(), config::mode);
        number("general.performance.manual_threads", config.manualThreads(), 0, 64, config::manualThreads,
                v -> v == 0 ? text("auto") : Integer.toString(v));
        enumeration("general.native.mode", SearchConfig.NativeMode.values(), config.nativeMode(), config::nativeMode);

        category("map.category");
        if (ReliefGpu.available())
            toggle("map.gpu_relief", config.gpuRelief(), config::gpuRelief);
        number("map.work",config.mapWorkMillis(),1,6,config::mapWorkMillis,v->v+" ms");
        toggle("map.pause",config.pauseMapInactive(),config::pauseMapInactive);
        number("map.fit",config.exampleFitSeconds(),1,10,config::exampleFitSeconds,v->v+" s");
        updateLimits();
        layout();
    }
    java.util.List<AbstractWidget> widgets(){
        return rows.stream().map(Row::widget).filter(Objects::nonNull).toList();
    }
    AbstractWidget focused(){
        return focused;
    }
    private static String text(String suffix) {
        return ConfigTranslations.tr("wywf.config."+suffix);
    }
    private void category(String key) {
        var heading = MenuAccent.heading(text(key)).copy().withStyle(ChatFormatting.BOLD);
        rows.add(new Row(font.split(heading, width - 8), null, total, 27));
        total += 27;
    }
    private void add(String key,AbstractWidget widget,int height) {
        String help=text(key+".desc");
        widget.setTooltip(Tooltip.create(Component.literal(help.equals("wywf.config."+key+".desc") ? text(key) : help)));
        var lines = font.split(Component.literal(text(key)), width - 8);
        height += Math.max(0, lines.size() - 1) * font.lineHeight;
        rows.add(new Row(lines, widget, total, height));
        total+=height;
    }
    private void changed() {
        dirty=true;
        saveAt=System.currentTimeMillis()+300;
    }
    void flush() {
        if(dirty){
            ConfigStore.save(config);
            dirty=false;
        }
    }
    void tick() {
        if(dirty&&System.currentTimeMillis()>=saveAt)
            flush();
    }
    private <T extends Enum<T>> ConstructorDropList enumeration(String key,T[] values,T current,Consumer<T> setter) {
        var options=Arrays.stream(values).map(Enum::name).toList();
        var list=new ConstructorDropList(0,0,width-8,"",options,Arrays.asList(values).indexOf(current),font,index->{
            setter.accept(values[index]);
            changed();
            },this::open);
        list.popupLimits(y, bottom);
        add(key, list, 44);
        return list;
    }
    private SettingSlider number(String key, int value, int min, int max, IntConsumer setter, IntFunction<String> format) {
        var slider = new SettingSlider(width - 8, text(key), value, min, max, next -> {
            setter.accept(next);
            changed();
        }, format);
        add(key, slider, 54);
        return slider;
    }
    private void toggle(String key,boolean value,Consumer<Boolean> setter) {
        add(key,new SettingToggle(text(key),value,v->{
            setter.accept(v);
            updateLimits();
            changed();
        }),42);
    }
    private void updateLimits() {
        boolean one = config.stopAtFirstCandidate();
        candidates.lockWithBedrock(one);
        minimum.lockWithBedrock(one);
        ramp.lockWithBedrock(one);
        seeds.lockWithBedrock(config.infiniteSeeds());
    }

    private int maxScroll() {
        return Math.max(0,total-(bottom-y));
    }
    private void layout() {
        scroll=Math.max(0,Math.min(maxScroll(),scroll));
        for(var row:rows)if(row.widget!=null){
            var widget=row.widget;
            widget.setX(x);
            widget.setY(y+row.offset-scroll+(widget instanceof SettingSlider
                    ? 28 : 15)+Math.max(0,row.lines().size()-1)*font.lineHeight);
            widget.visible=widget.getY()+widget.getHeight()>y&&widget.getY()<bottom;
        }
    }
    private void open(ConstructorDropList list) {
        if(open!=null&&open!=list)
            open.close();
        open=list;

    }
    private int popupHeight(){
        return open==null ? 0 : Math.max(0,bottom-open.getY()-20);
    }
    boolean contains(double mx,double my){
        return mx>=x&&mx<x+width&&my>=y&&my<bottom;
    }
    private int thumbHeight(){
        int h=bottom-y;
        return Math.max(18,h*h/Math.max(1,total));
    }
    private int thumbTop(){
        return y+(int)((bottom-y-thumbHeight())*(scroll/(double)Math.max(1,maxScroll())));
    }
    boolean click(MouseButtonEvent event,boolean keyboard){
        if(open!=null&&open.isOpen()){
            if(open.thumbHit((float)event.x(),(float)event.y(),popupHeight())){
                open.beginPopupDrag((float)event.y(),popupHeight());
                return true;
            }
            if(open.popupHit((float)event.x(),(float)event.y(),popupHeight())){
                open.handlePopupClick((float)event.x(),(float)event.y(),popupHeight());
                return true;
            }
            if(open.isMouseOver(event.x(),event.y())){
                open.close();
                return true;
            }
            open.close();
        }
        if(!contains(event.x(),event.y()))
            return false;
        if(event.x()>=x+width-4&&maxScroll()>0){
            scrollDrag=true;
            scrollGrab=event.y()-thumbTop();
            return true;
        }
        for(var row:rows)
            if(row.widget!=null&&row.widget.visible&&row.widget.isMouseOver(event.x(),event.y())){
            if(focused!=null)
                focused.setFocused(false);
            focused=row.widget;
            capture=row.widget;
            capture.setFocused(true);
            return capture.mouseClicked(event,keyboard);
        }
        return true;
    }
    boolean drag(MouseButtonEvent event,double dx,double dy){
        if(open!=null&&open.isPopupDrag()){
            open.dragPopupTo((float)event.y(),popupHeight());
            return true;
        }
        if(scrollDrag){
            scroll=(int)((event.y()-scrollGrab-y)*maxScroll()/Math.max(1,bottom-y-thumbHeight()));
            layout();
            return true;
        }
        return capture!=null&&capture.mouseDragged(event,dx,dy);
    }
    boolean release(MouseButtonEvent event){
        boolean handled=capture!=null||scrollDrag;
        scrollDrag=false;
        if(open!=null)
            open.endPopupDrag();
        if(capture!=null){
            capture.mouseReleased(event);
            capture=null;}
        flush();
        return handled;
    }
    boolean scroll(double mx,double my,double dy){
        if(open!=null&&open.isOpen()&&open.popupHit((float)mx,(float)my,popupHeight())){
            open.scrollPopup((float)dy,popupHeight());
            return true;
        }
        if(!contains(mx,my))return false;
        if(open!=null)open.close();scroll-=(int)(dy*28);
        layout();
        return true;
    }
    boolean key(KeyEvent event){
        if(open!=null&&open.isOpen())
            return open.keyPressed(event);
        return focused!=null&&focused.keyPressed(event);
    }
    void draw(GuiGraphicsExtractor g,int mx,int my,float delta){
        g.enableScissor(x,y,x+width,bottom);
        for(var row:rows){
            int ry=y+row.offset-scroll;
            if(ry+row.height<=y||ry>=bottom)continue;
            int lineY=ry+2;
            for(var line:row.lines()){
                if (row.widget == null)
                    g.centeredText(font, line, x + width / 2, lineY + 4, 0xFFFFFFFF);
                else
                    g.text(font, line, x, lineY, 0xFFFFFFFF);
                lineY += font.lineHeight;
            }
            if(row.widget!=null&&row.widget.visible)row.widget.extractRenderState(g,mx,my,delta);
        }
        g.disableScissor();
        if(maxScroll()>0){
            g.fill(x+width-3,y,x+width,bottom,0x55000000);
            g.fill(x+width-3,thumbTop(),x+width,thumbTop()+thumbHeight(),0xAAFFFFFF);
        }
    }
    void popup(GuiGraphicsExtractor g,int mx,int my){
        for (var row : rows) {
            if (row.widget instanceof ConstructorDropList list && list != open && list.visible)
                list.drawPopup(g, mx, my, Math.max(0, bottom - list.getBottom()), 1);
        }
        if (open != null && open.visible)
            open.drawPopup(g, mx, my, popupHeight(), 1);
    }
}
