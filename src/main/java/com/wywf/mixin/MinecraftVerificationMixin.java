package com.wywf.mixin;

import com.wywf.core.PreviewScreenGuard;
import com.wywf.verify.PreviewWorld;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Optional;
import java.util.function.Function;

@Mixin(Minecraft.class)
public abstract class MinecraftVerificationMixin {
    @Inject(method = "doWorldLoad", at = @At("HEAD"), cancellable = true)
    private void wywf_captureVerification(LevelStorageSource.LevelStorageAccess storage, PackRepository packs,
                                          WorldStem stem, Optional<?> rules, boolean fresh, CallbackInfo ci) {
        if (PreviewWorld.capture(storage, packs, stem, rules, fresh)) {
            ci.cancel();
            return;
        }
        com.wywf.world.WorldCreator.persistMap(storage.getLevelDirectory().path(),
                stem.worldDataAndGenSettings().genSettings().options().seed(), fresh);
    }

    @Redirect(method = "doWorldLoad", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;spin(Ljava/util/function/Function;)Lnet/minecraft/server/MinecraftServer;"))
    private MinecraftServer wywf_reuseVerifiedServer(Function<Thread, MinecraftServer> factory) {
        return PreviewWorld.startOrReuse(factory);
    }

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true, require = 0)
    private void wywf_keepLegacySearchVisible(Screen screen, CallbackInfo ci) {
        if (PreviewScreenGuard.loading) ci.cancel();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void wywf_stopVerification(CallbackInfo ci) { PreviewWorld.shutdown(); }
}
