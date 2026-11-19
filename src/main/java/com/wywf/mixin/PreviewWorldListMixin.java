package com.wywf.mixin;

import com.wywf.verify.PreviewDirectory;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelStorageSource.class)
public abstract class PreviewWorldListMixin {
    @Inject(method = "findLevelCandidates", at = @At("RETURN"), cancellable = true)
    private void wywf_hideUnacceptedWorlds(CallbackInfoReturnable<LevelStorageSource.LevelCandidates> cir) {
        cir.setReturnValue(new LevelStorageSource.LevelCandidates(cir.getReturnValue().levels().stream()
                .filter(level -> !PreviewDirectory.hidden(level.path())).toList()));
    }
}
