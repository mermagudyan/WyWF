package com.wywf.mixin;

import com.wywf.client.SavedWorldMapButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldMapMixin extends Screen {
    protected SelectWorldMapMixin(net.minecraft.network.chat.Component title) { super(title); }
    @Shadow private WorldSelectionList list;
    @Unique private SavedWorldMapButton wywf$map;

    @ModifyArg(method = "createFooterButtons", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;I)Lnet/minecraft/client/gui/layouts/LayoutElement;", ordinal = 1), index = 0)
    private LayoutElement wywf$mapBesideCreate(LayoutElement original) {
        Button create = (Button) original;
        create.setWidth(create.getWidth() - 28);
        LinearLayout row = LinearLayout.horizontal().spacing(4);
        row.addChild(create);
        wywf$map = row.addChild(new SavedWorldMapButton((Screen) (Object) this));
        return row;
    }

    @Inject(method = "updateButtonStatus", at = @At("TAIL"))
    private void wywf$selectedMap(LevelSummary summary, CallbackInfo ci) {
        if (wywf$map != null) wywf$map.select(summary);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void wywf$closePreviousButton(CallbackInfo ci) {
        if (wywf$map != null) wywf$map.close();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void wywf$restoreMapSelection(CallbackInfo ci) {
        if (wywf$map != null && list != null) wywf$map.select(list.getSelectedOpt().map(WorldSelectionList.WorldListEntry::getLevelSummary).orElse(null));
    }

    @Override
    public void added() {
        super.added();
        if (wywf$map == null) return;
        wywf$map.returned();
        if (list != null) updateButtonStatus(list.getSelectedOpt().map(WorldSelectionList.WorldListEntry::getLevelSummary).orElse(null));
    }

    @Shadow public abstract void updateButtonStatus(LevelSummary summary);

    @Inject(method = "removed", at = @At("HEAD"), cancellable = true)
    private void wywf$closeMapLookup(CallbackInfo ci) {
        if (wywf$map == null) return;
        if (wywf$map.mapVisible()) ci.cancel();
        else wywf$map.close();
    }
}