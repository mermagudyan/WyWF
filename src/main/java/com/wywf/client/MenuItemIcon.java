package com.wywf.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class MenuItemIcon {
    static void draw(GuiGraphicsExtractor g, Item item, int x, int y) {
        if (item.builtInRegistryHolder().areComponentsBound()) {
            g.fakeItem(new ItemStack(item), x, y);
            return;
        }
        // Saved-world menus can render before datapack item components are bound
        var id = BuiltInRegistries.ITEM.getKey(item);
        var texture = Identifier.fromNamespaceAndPath(id.getNamespace(),
                "textures/" + (item instanceof BlockItem ? "block/" : "item/") + id.getPath() + ".png");
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, 16, 16, 16, 16);
    }

    private MenuItemIcon() {}
}
