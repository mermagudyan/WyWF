package com.wywf.mixin;

import com.wywf.core.PreviewScreenGuard;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiVerificationScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void wywf_keepSearchVisible(Screen screen, CallbackInfo ci) {
        if (PreviewScreenGuard.loading) ci.cancel();
    }
}
