package com.wywf.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.concurrent.ThreadLocalRandom;

enum BlockMenuSkin {
    STONE("stone", "coal_ore", "iron_ore", "copper_ore", "gold_ore", "diamond_ore"),
    DEEPSLATE("deepslate", "deepslate_iron_ore", "deepslate_gold_ore", "deepslate_diamond_ore", "deepslate_redstone_ore"),
    NETHERRACK("netherrack", "nether_quartz_ore", "nether_gold_ore", "ancient_debris_top"),
    DIRT("dirt"),
    GRAVEL("gravel"),
    SANDSTONE("sandstone"),
    TUFF("tuff"),
    QUARTZ("quartz_block_side"),
    DIAMOND("diamond_block"),
    PRISMARINE("prismarine");

    private static final int[] PRISMARINE_FRAMES = {0, 1, 0, 2, 0, 3, 0, 1, 2, 1, 3, 1, 0, 2, 1, 2, 3, 2, 0, 3, 1, 3};
    private static final BlockMenuSkin[] SOLIDS = {STONE, DEEPSLATE, NETHERRACK, GRAVEL, SANDSTONE, TUFF, QUARTZ, DIAMOND};

    private final Identifier texture;
    private final Identifier[] ores;

    BlockMenuSkin(String block, String... ores) {
        texture = texture(block);
        this.ores = java.util.Arrays.stream(ores).map(BlockMenuSkin::texture).toArray(Identifier[]::new);
    }

    static BlockMenuSkin random() {
        return SOLIDS[ThreadLocalRandom.current().nextInt(SOLIDS.length)];
    }

    static BlockMenuSkin history() {
        var skins = values();
        return skins[ThreadLocalRandom.current().nextInt(skins.length)];
    }

    private static Identifier texture(String name) {
        return Identifier.withDefaultNamespace("textures/block/" + name + ".png");
    }

    void draw(GuiGraphicsExtractor g, int x, int y, int width, int height, long seed) {
        g.enableScissor(x, y, x + width, y + height);
        long now = System.currentTimeMillis();
        int frame = (int) ((now / 5000) % PRISMARINE_FRAMES.length);
        float progress = (now % 5000) / 5000f;
        for (int dy = 0; dy < height; dy += 16) {
            for (int dx = 0; dx < width; dx += 16) {
                if (this == PRISMARINE) {
                    g.blit(RenderPipelines.GUI_TEXTURED,
                            texture, x + dx, y + dy, 0, 16 * PRISMARINE_FRAMES[frame], 16, 16, 16, 64);
                    int next = PRISMARINE_FRAMES[(frame + 1) % PRISMARINE_FRAMES.length];
                    g.blit(RenderPipelines.GUI_TEXTURED,
                            texture, x + dx, y + dy, 0, 16 * next, 16, 16, 16, 64,
                            (Math.round(255 * progress) << 24) | 0xFFFFFF);
                }
                else {
                    long hash = seed + dx * 37799L + dy * 74197L;
                    hash = (hash ^ hash >>> 16) * 0x45d9f3bL;
                    Identifier tile = ores.length > 0 && Math.floorMod(hash, 47) == 0
                            ? ores[Math.floorMod(hash >>> 16, ores.length)] : texture;
                    g.blit(RenderPipelines.GUI_TEXTURED,
                            tile, x + dx, y + dy, 0, 0, 16, 16, 16, 16);
                }
            }
        }
        g.disableScissor();
    }

    static void panel(GuiGraphicsExtractor g, BlockMenuSkin skin, int x, int y, int width, int height, int a, int b) {
        skin.draw(g, x, y, width, height, skin.ordinal() * 17L);
        g.fill(x, y, x + width, y + height, skin == PRISMARINE ? 0x9A102422 : 0xBC171712);
        MenuAccent.frame(g, x - 1, y - 1, width + 2, height + 2, 1,
                a, b, System.nanoTime(), skin.ordinal(), 1);
    }
}
