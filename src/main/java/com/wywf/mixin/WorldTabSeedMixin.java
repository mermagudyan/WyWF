package com.wywf.mixin;

import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class WorldTabSeedMixin {

    @Shadow @Final private EditBox seedEdit;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void wywf_removeSeedLimit(CallbackInfo ci) {
        if (this.seedEdit != null) {
            this.seedEdit.setMaxLength(20000);
        }
    }
}
