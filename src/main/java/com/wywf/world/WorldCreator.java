package com.wywf.world;

import com.wywf.core.SearchResult;
import com.wywf.mixin.CreateWorldScreenInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

public final class WorldCreator {

    private static volatile boolean bypass = false;

    public static boolean consumeBypass() {
        if (bypass) { bypass = false; return true; }
        return false;
    }

    public void create(SearchResult result, String originalText, CreateWorldScreen screen) {
        PendingWorldCreation.set(result.seed, result.centerX, result.centerZ);

        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            screen.getUiState().setSeed(Long.toString(result.seed));
            bypass = true;
            mc.setScreenAndShow(screen);
            ((CreateWorldScreenInvoker) screen).wywf_invokeOnCreate();
        });
    }
}
