package com.wywf.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new Screen(Component.translatable("selectWorld.create")) {
            @Override protected void init() {
                Minecraft client = Minecraft.getInstance();
                CreateWorldScreen.openFresh(client, () -> client.setScreenAndShow(parent));
                if (ClientPlatform.screen(client) instanceof CreateWorldScreen creator)
                    client.setScreenAndShow(SearchConstructorScreen.forMenu(creator, parent));
            }
        };
    }
}
