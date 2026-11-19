package com.wywf.mixin;

import com.wywf.WYWFClient;
import com.wywf.client.DidYouMeanScreen;
import com.wywf.client.SearchScreen;
import com.wywf.client.SearchAdviceScreen;
import com.wywf.core.QueryAdvisor;
import com.wywf.core.ConfigStore;
import com.wywf.core.ParsedQuery;
import com.wywf.core.SearchConfig;
import com.wywf.world.WorldCreator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

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
            WYWFClient.LOGGER.info("Failed to read seed text", t);
            return;
        }
        if (seedText == null || seedText.isBlank()) return;

        ParsedQuery query = WYWFClient.parser().parse(seedText);
        if (query.error() != null) {
            ci.cancel();
            Minecraft.getInstance().setScreenAndShow(new com.wywf.client.QuerySyntaxScreen(self, query, ConfigStore.load())); return;
        }
        if (query.isEmpty()) {
            if (query.ignoredWords().isEmpty()) return;
            Map<String, String> corrections = DidYouMeanScreen.findCorrections(query.ignoredWords());
            if (!corrections.isEmpty()) {
                ci.cancel();
                SearchConfig config = ConfigStore.load();
                DidYouMeanScreen screen = new DidYouMeanScreen(
                        self, seedText, corrections, query, config);
                Minecraft.getInstance().setScreenAndShow(screen);
            }
            return;
        }

        ci.cancel();

        SearchConfig config = ConfigStore.load();

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
