package com.wywf.mixin;

import com.wywf.client.ClientPlatform;
import com.wywf.client.SearchHistoryPrompt;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class HistoryPromptKeyboardMixin {
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void wywf_blockTypingUnderHistory(long window, CharacterEvent event, CallbackInfo ci) {
        if (SearchHistoryPrompt.blocksTyping(ClientPlatform.screen(Minecraft.getInstance()))) ci.cancel();
    }
}
