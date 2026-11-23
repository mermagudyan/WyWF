package com.wywf.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.wywf.WYWFClient;
import com.wywf.client.DidYouMeanScreen;
import com.wywf.client.SearchScreen;
import com.wywf.client.SearchAdviceScreen;
import com.wywf.core.QueryAdvisor;
import com.wywf.core.ParsedQuery;
import com.wywf.core.SearchConfig;
import com.wywf.world.WorldCreator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @ModifyExpressionValue(method = "onCreate", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/screens/worldselection/WorldCreationUiState;getSettings()Lnet/minecraft/client/gui/screens/worldselection/WorldCreationContext;"))
    private WorldCreationContext wywf_candidateSettings(WorldCreationContext settings) {
        return WorldCreator.withCandidateSeed((CreateWorldScreen) (Object) this, settings);
    }

    @Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
    private void wywf_interceptCreate(CallbackInfo ci) {

        CreateWorldScreen self = (CreateWorldScreen) (Object) this;
        if (WorldCreator.isCreatingCandidate(self)) return;

        String seedText;
        try {
            seedText = self.getUiState().getSeed();
        } catch (Throwable t) {
            WYWFClient.LOGGER.info("Failed to read seed text", t);
            return;
        }
        if (seedText == null || seedText.isBlank()) return;

        ParsedQuery query = WYWFClient.parser().parse(seedText);
        if (query.error() != null) {
            ci.cancel();
            Minecraft.getInstance().setScreenAndShow(new com.wywf.client.QuerySyntaxScreen(self, query, WorldCreator.searchConfig(self, seedText)));
            return;
        }
        if (query.isEmpty()) {
            if (query.ignoredWords().isEmpty()) return;
            Map<String, String> corrections = DidYouMeanScreen.findCorrections(query.ignoredWords());
            if (!corrections.isEmpty()) {
                ci.cancel();
                SearchConfig config = WorldCreator.searchConfig(self, seedText);
                DidYouMeanScreen screen = new DidYouMeanScreen(
                        self, seedText, corrections, query, config);
                Minecraft.getInstance().setScreenAndShow(screen);
            }
            return;
        }

        ci.cancel();

        SearchConfig config = WorldCreator.searchConfig(self, seedText);

        if (!query.ignoredWords().isEmpty()) {
            Map<String, String> corrections = DidYouMeanScreen.findCorrections(query.ignoredWords());
            if (!corrections.isEmpty()) {
                DidYouMeanScreen screen = new DidYouMeanScreen(
                        self, seedText, corrections, query, config);
                Minecraft.getInstance().setScreenAndShow(screen);
                return;
            }
        }

        if (QueryAdvisor.inspect(query, config).reviewBeforeSearch()) {
            Minecraft.getInstance().setScreenAndShow(new SearchAdviceScreen(self, seedText, query, config));
            return;
        }
        SearchScreen screen = new SearchScreen(self, seedText, query, config);
        screen.open();
    }
}
