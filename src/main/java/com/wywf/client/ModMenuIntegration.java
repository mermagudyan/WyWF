package com.wywf.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            if (FabricLoader.getInstance().isModLoaded("yet-another-config-lib-v3")
                    || FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) {
                return WywfConfigScreen.create(parent);
            }
            return new WywfSettingsScreen(parent);
        };
    }
}
