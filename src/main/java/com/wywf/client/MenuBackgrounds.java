package com.wywf.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

final class MenuBackgrounds {
    private static final ArrayDeque<BlockMenuSkin> DIALOGS = new ArrayDeque<>();
    private static BlockMenuSkin lastDialog;

    private MenuBackgrounds() {}

    static List<BlockMenuSkin> cards(long firstSeed) {
        var skins = new ArrayList<>(List.of(BlockMenuSkin.values()));
        Collections.shuffle(skins, new Random(firstSeed ^ (firstSeed >>> 32)));
        int dirt = skins.indexOf(BlockMenuSkin.DIRT);
        if (dirt > 1) Collections.swap(skins, dirt, 1);
        return List.copyOf(skins);
    }

    static BlockMenuSkin nextDialog() {
        if (DIALOGS.isEmpty()) {
            var skins = new ArrayList<>(List.of(BlockMenuSkin.values()));
            Collections.shuffle(skins, ThreadLocalRandom.current());
            if (skins.getFirst() == lastDialog) Collections.swap(skins, 0, 1);
            DIALOGS.addAll(skins);
        }
        lastDialog = DIALOGS.removeFirst();
        return lastDialog;
    }
}
