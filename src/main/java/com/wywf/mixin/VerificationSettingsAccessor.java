package com.wywf.mixin;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.world.level.LevelSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.nio.file.Path;

@Mixin(CreateWorldScreen.class)
public interface VerificationSettingsAccessor {
    @Accessor("tempDataPackDir") Path wywf_previewDataPacks();
    @Invoker("createLevelSettings") LevelSettings wywf_previewSettings(boolean debug);
    @Invoker("removeTempDataPackDir") void wywf_releasePreviewDataPacks();
}
