package com.wywf.client;

import dev.diylib.client.ui.DiyToggle;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

final class SettingToggle extends DiyToggle {
    SettingToggle(String label, boolean value, Consumer<Boolean> setter) {
        super(0, 0, 18, 18, Component.literal(label), value, setter);
    }
}
