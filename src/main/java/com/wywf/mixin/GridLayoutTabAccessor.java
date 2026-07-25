package com.wywf.mixin;

import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GridLayoutTab.class)
public interface GridLayoutTabAccessor {
    @Accessor("layout")
    GridLayout wywf_getLayout();
}
