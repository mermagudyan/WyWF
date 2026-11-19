package com.wywf.mixin;

import com.wywf.verify.PreviewWorld;
import com.wywf.verify.VerificationEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class PreviewLevelTickMixin implements VerificationEntities {
    @Shadow @Final private PersistentEntitySectionManager<Entity> entityManager;
    public void wywf_loadEntities(int chunkX, int chunkZ) {
        entityManager.updateChunkStatus(new net.minecraft.world.level.ChunkPos(chunkX, chunkZ),
                net.minecraft.world.level.entity.Visibility.TRACKED);
        entityManager.processPendingLoads();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void wywf_freezeUnacceptedWorld(BooleanSupplier available, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (PreviewWorld.quiet(level.getServer())) {
            level.getChunkSource().tick(available, false);
            entityManager.tick();
            ci.cancel();
        }
    }
}
