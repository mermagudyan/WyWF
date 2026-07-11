package com.wywtf.mixin;

import com.wywtf.world.PendingWorldCreation;
import net.minecraft.client.gui.screens.worldselection.WorldGenSettingsComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Перехватывает формирование сида в {@link WorldGenSettingsComponent}.
 *
 * Когда пользователь (или наш мод) вызывает создание мира, компонент парсит
 * строку сида в long. Мы подменяем полученный long на наш pending-сид,
 * если он есть.
 *
 * В 26.x точное имя метода парсинга сида может отличаться —
 * используем {@code require = 0} чтобы не падать, если инъекция не сработает.
 */
@Mixin(WorldGenSettingsComponent.class)
public abstract class WorldGenSettingsComponentMixin {

    @ModifyVariable(
        method = "*",
        at = @At("STORE"),
        ordinal = 0,
        require = 0
    )
    private long wywtf_overrideSeed(long originalSeed) {
        if (PendingWorldCreation.has()) {
            return PendingWorldCreation.seed();
        }
        return originalSeed;
    }
}
