package com.wywf.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

public final class SearchHistoryPrompt {
    private record OpenPrompt(Screen origin, MenuModal modal) {}
    private static OpenPrompt current;

    private SearchHistoryPrompt() {}

    public static void install() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (active(screen)) current.modal().bounds(width, height);
            ScreenEvents.afterExtract(screen).register((parent, graphics, x, y, tick) -> {
                if (active(parent)) current.modal().draw(graphics, x, y, tick);
            });
            ScreenEvents.remove(screen).register(parent -> {
                if (active(parent)) current = null;
            });
            ScreenMouseEvents.allowMouseClick(screen).register((parent, event) -> {
                if (!active(parent)) return true;
                current.modal().click(event, false);
                return false;
            });
            ScreenMouseEvents.allowMouseRelease(screen).register((parent, event) -> !active(parent));
            ScreenMouseEvents.allowMouseDrag(screen).register((parent, event, x, y) -> !active(parent));
            ScreenMouseEvents.allowMouseScroll(screen).register((parent, x, y, horizontal, vertical) -> !active(parent));
            ScreenKeyboardEvents.allowKeyPress(screen).register((parent, event) -> {
                if (!active(parent)) return true;
                current.modal().key(event);
                return false;
            });
            ScreenKeyboardEvents.allowKeyRelease(screen).register((parent, event) -> !active(parent));
        });
    }

    static void show(Screen origin, SearchScreen search, int count, Runnable beforeSearch) {
        Runnable dismiss = () -> current = null;
        var modal = new MenuModal(Minecraft.getInstance().font, MapTexts.label("history_title"),
                MapTexts.label(search.verifiesHistory() ? "history_help" : "history_offline_help"), () -> true, dismiss,
                MenuButton.builder(MapTexts.label("history_show", count), button -> begin(search, true, beforeSearch)).build(),
                MenuButton.builder(MapTexts.label("history_new"), button -> begin(search, false, beforeSearch)).build(),
                MenuButton.builder(ResultTexts.label("back"), button -> dismiss.run()).build());
        modal.bounds(origin.width, origin.height);
        current = new OpenPrompt(origin, modal);
    }

    public static boolean blocksTyping(Screen screen) { return active(screen); }

    private static boolean active(Screen screen) {
        return current != null && current.origin() == screen;
    }

    private static void begin(SearchScreen search, boolean useHistory, Runnable beforeSearch) {
        current = null;
        search.reuseHistory(useHistory);
        beforeSearch.run();
        Minecraft.getInstance().setScreenAndShow(search);
    }
}
