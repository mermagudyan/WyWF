package com.wywf.mixin;

import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GridLayoutTab.class)
public interface GridLayoutAccessor {
    @Accessor("layout") GridLayout wywf_layout();
}
