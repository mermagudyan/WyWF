package com.wywtf.mixin;

import com.wywtf.WYWTFClient;
import com.wywtf.client.SearchScreen;
import com.wywtf.core.ParsedQuery;
import com.wywtf.core.SearchConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldGenSettingsComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Перехват создания мира из {@link CreateWorldScreen}.
 *
 * Когда пользователь нажимает «Создать мир», мы:
 *   1. Достаём текст из поля Seed (через WorldGenSettingsComponent).
 *   2. Если парсер распознал в нём запрос — показываем {@link SearchScreen}
 *      и отменяем стандартное создание мира.
 *   3. Иначе — отдаём управление ванильному пути.
 *
 * В 26.x метод onCreate() в CreateWorldScreen — приватный, перехватываем
 * через @At("HEAD") и cancel=true.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    @Shadow @Final
    private WorldGenSettingsComponent worldGenSettingsComponent;

    @Inject(method = "onCreateWorld", at = @At("HEAD"), cancellable = true)
    private void wywtf_interceptCreate(CallbackInfo ci) {
        if (this.worldGenSettingsComponent == null) return;

        String seedText = readSeedText();
        if (seedText == null || seedText.isBlank()) return;

        ParsedQuery query = WYWTFClient.parser().parse(seedText);
        if (query.isEmpty()) {
            // Обычный сид — не вмешиваемся
            return;
        }

        // Это запрос — перехватываем
        ci.cancel();

        SearchConfig config = SearchConfig.defaults();
        SearchScreen screen = new SearchScreen(seedText, query, config);
        Minecraft.getInstance().setScreen(screen);
    }

    /**
     * Читает текст поля Seed.
     *
     * В 26.x путь: {@code worldGenSettingsComponent.getSeed()} (String).
     * Если сигнатура изменилась — заменить здесь.
     */
    private String readSeedText() {
        try {
            // WorldGenSettingsComponent.getSeed() — публичный метод, возвращает строку
            return worldGenSettingsComponent.getSeed();
        } catch (Throwable t) {
            WYWTFClient.LOGGER.warn("Failed to read seed text", t);
            return null;
        }
    }
}
