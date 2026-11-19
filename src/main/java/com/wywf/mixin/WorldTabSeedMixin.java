package com.wywf.mixin;

import com.wywf.client.SearchConstructorScreen;
import com.wywf.core.SearchDraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class WorldTabSeedMixin {
    @Shadow @Final private EditBox seedEdit;
    @Shadow @Final private CreateWorldScreen this$0;
    @Unique private final SearchDraft wywf$draft = new SearchDraft();
    @Unique private SearchConstructorScreen wywf$constructor;

    @org.spongepowered.asm.mixin.injection.Inject(method="<init>", at=@At("TAIL"))
    private void wywf$syncSeed(CreateWorldScreen creator, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        creator.getUiState().addListener(state -> {
            if(!seedEdit.getValue().equals(state.getSeed())) seedEdit.setValue(state.getSeed());
        });
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/CommonLayouts;labeledElement(Lnet/minecraft/client/gui/Font;Lnet/minecraft/client/gui/layouts/LayoutElement;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/client/gui/layouts/Layout;"),
            index = 1)
    private LayoutElement wywf$seedControls(LayoutElement original) {
        seedEdit.setMaxLength(20000);
        LinearLayout row = LinearLayout.horizontal().spacing(4);
        Button open = new com.wywf.client.ConstructorCompassButton(b -> {
            wywf$constructor = SearchConstructorScreen.forCreator(this$0, seedEdit::setValue, wywf$draft);
            Minecraft.getInstance().setScreenAndShow(wywf$constructor);
        });
        open.setTooltip(Tooltip.create(SearchConstructorScreen.label("open")));
        seedEdit.setWidth(seedEdit.getWidth() - open.getWidth() - 4);
        row.addChild(original);
        row.addChild(open);
        return row;
    }
}
