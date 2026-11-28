package com.wywf.client;

import dev.diylib.client.ui.DiyVanillaButton;
import com.wywf.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;

final class SearchQueryEditScreen extends Screen {
    private final CreateWorldScreen creator;
    private final SearchConfig config;
    private String text;
    private EditBox query;
    SearchQueryEditScreen(CreateWorldScreen creator,String text,SearchConfig config){
        super(MapTexts.label("edit_query"));
        this.creator=creator;
        this.text=text;
        this.config=config.copy();
    }
    @Override protected void init(){
        if(query!=null)
            text=query.getValue();
        int w=Math.min(700,width-32);
        query=addRenderableWidget(new EditBox(font,(width-w)/2,65,w,20,title));
        query.setMaxLength(20000);
        query.setValue(text);
        addRenderableWidget(DiyVanillaButton.builder(MapTexts.label("search_again"),b->{
            var value=query.getValue();
            var parsed=new QueryParser(new KeywordDictionary(config.queryLanguage())).parse(value);
            if(parsed.isEmpty())
                return;
            creator.getUiState().setSeed(value);
            Minecraft.getInstance().setScreenAndShow(new SearchScreen(creator,value,parsed,config).fresh());
        }).bounds(width/2-153,height-30,150,20).build());
        addRenderableWidget(DiyVanillaButton.builder(ResultTexts.label("back"),b->onClose()).bounds(width/2+3,height-30,150,20).build());
        setInitialFocus(query);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float delta){
        super.extractRenderState(g,x,y,delta);
        g.centeredText(font,title,width/2,20,0xFFFFFFFF);
        int row=96;for(var line:font.split(Component.literal(MapTexts.text("edit_query_help")),width-32)){
            g.text(font,line,(width-font.width(line))/2,row,0xFFD1DBD6);
            row+=font.lineHeight;
        }}
    @Override public void onClose(){
        creator.getUiState().setSeed(query.getValue());
        Minecraft.getInstance().setScreenAndShow(creator);
    }
}
