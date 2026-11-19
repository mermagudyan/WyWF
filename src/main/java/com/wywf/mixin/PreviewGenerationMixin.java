package com.wywf.mixin;

import com.wywf.verify.PreviewWorld;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import java.util.concurrent.Executor;

@Mixin(ServerChunkCache.class)
public abstract class PreviewGenerationMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static Executor wywf_limitPreviewWork(Executor executor) { return PreviewWorld.limitExecutor(executor); }
}
