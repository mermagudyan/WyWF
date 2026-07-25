package com.wywf.mixin;

import com.wywf.client.WywfSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$MoreTab")
public abstract class CreateWorldScreenMoreTabMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void wywf_addSettingsButton(CreateWorldScreen screen, CallbackInfo ci) {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("modmenu")) return;

        try {
            GridLayout layout = ((GridLayoutTabAccessor) this).wywf_getLayout();
            if (layout != null) {
                final int[] childCount = {0};
                layout.visitChildren(w -> childCount[0]++);
                int nextRow = childCount[0];
                layout.addChild(
                        Button.builder(
                                net.minecraft.network.chat.Component.literal("WyWF Settings"),
                                b -> Minecraft.getInstance().setScreenAndShow(
                                        new WywfSettingsScreen(screen))
                        ).width(210).build(),
                        nextRow, 0
                );
                layout.arrangeElements();
            }
        } catch (Throwable t) {
            com.wywf.WYWFClient.LOGGER.warn("Failed to add WyWF settings button to More tab", t);
        }
    }
}
