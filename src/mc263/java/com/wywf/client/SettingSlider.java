package com.wywf.client;

import dev.diylib.client.ui.DiySlider;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

final class SettingSlider extends DiySlider {
    private boolean boldValue;

    SettingSlider(int width, String label, int current, int min, int max, IntConsumer setter, IntFunction<String> format) {
        super(0, 0, width, 18, label, min, max, current, value -> setter.accept((int) Math.round(value)));
        stepped();
        display(value -> {
            var component = Component.literal(format.apply((int) Math.round(value)));
            return boldValue ? component.withStyle(ChatFormatting.BOLD) : component;
        });
    }

    void lockWithBedrock(boolean locked) {
        active = !locked;
        handleStyle(locked ? dev.diylib.client.ui.DiySliderTrack.HandleStyle.BEDROCK
                : dev.diylib.client.ui.DiySliderTrack.HandleStyle.VANILLA);
    }

    void boldValue() { boldValue = true; }

    int number() { return (int) Math.round(value()); }

    @Override
    protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
        return wrapDefaultNarrationMessage(getMessage().copy().append(" ").append(Integer.toString(number())));
    }
}
