package com.wywf.mixin;

import com.wywf.WYWFClient;
import com.wywf.client.SearchScreen;
import com.wywf.core.ParsedQuery;
import com.wywf.core.SearchConfig;
import com.wywf.world.WorldCreator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
    private void wywf_interceptCreate(CallbackInfo ci) {

        if (WorldCreator.consumeBypass()) return;

        CreateWorldScreen self = (CreateWorldScreen) (Object) this;

        String seedText;
        try {
            seedText = self.getUiState().getSeed();
        } catch (Throwable t) {
            WYWFClient.LOGGER.warn("Failed to read seed text", t);
            return;
        }
        if (seedText == null || seedText.isBlank()) return;

        ParsedQuery query = WYWFClient.parser().parse(seedText);
        if (query.isEmpty()) return;

        ci.cancel();

        SearchConfig config = SearchConfig.defaults();
        SearchScreen screen = new SearchScreen(self, seedText, query, config);
        Minecraft.getInstance().setScreenAndShow(screen);
    }
}
