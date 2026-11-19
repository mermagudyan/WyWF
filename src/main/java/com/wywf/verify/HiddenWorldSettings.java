package com.wywf.verify;

import com.wywf.mixin.VerificationSettingsAccessor;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;

public final class HiddenWorldSettings {
    public static void release(CreateWorldScreen creator) {
        if (creator != null) ((VerificationSettingsAccessor) creator).wywf_releasePreviewDataPacks();
    }

    private HiddenWorldSettings() {}
}
