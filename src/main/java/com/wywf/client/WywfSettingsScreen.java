package com.wywf.client;

import com.wywf.core.ConfigStore;
import com.wywf.core.KeywordDictionary;
import com.wywf.core.SearchConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

public final class WywfSettingsScreen extends Screen {

    private static final int CONTENT_WIDTH = 340;
    private static final int ROW_HEIGHT = 24;
    private static final int CATEGORY_HEIGHT = 16;
    private static final int LABEL_WIDTH = CONTENT_WIDTH - 154;
    private static final int HEADER_HEIGHT = 26;
    private static final int FOOTER_HEIGHT = 32;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_MIN_HEIGHT = 32;

    private final Screen parent;
    private final SearchConfig config;

    private final List<RowEntry> rows = new ArrayList<>();
    private Button doneButton;
    private int scrollOffset = 0;
    private int contentHeight;
    private int visibleHeight;

    public WywfSettingsScreen(Screen parent) {
        super(Component.literal("WyWF Settings"));
        this.parent = parent;
        this.config = ConfigStore.load();
    }

    @Override
    protected void init() {
        rows.clear();
        scrollOffset = 0;

        addCategory(tr("wywf.config.category.general"));
        addToggleRow(tr("wywf.config.general.language.option"),
                KeywordDictionary.Lang.class, config.queryLanguage(), config::queryLanguage);
        addToggleRow(tr("wywf.config.general.performance.thread_mode"),
                SearchConfig.Mode.class, config.mode(), config::mode);
        addNumberRow(tr("wywf.config.general.performance.manual_threads"),
                config.manualThreads(), 0, 64, v -> config.manualThreads(v));
        addToggleRow(tr("wywf.config.general.native.mode"),
                SearchConfig.NativeMode.class, config.nativeMode(), config::nativeMode);

        addCategory(tr("wywf.config.search.scan.name"));
        addNumberRow(tr("wywf.config.search.scan.structure_radius"),
                config.searchRadiusChunks(), 8, 80, v -> config.searchRadiusChunks(v));
        addNumberRow(tr("wywf.config.search.scan.biome_radius"),
                config.biomeCheckRadiusChunks(), 4, 64, v -> config.biomeCheckRadiusChunks(v));
        addNumberRow(tr("wywf.config.search.scan.biome_step"),
                config.biomeSampleStepChunks(), 1, 8, v -> config.biomeSampleStepChunks(v));

        addCategory(tr("wywf.config.search.center.name"));
        addToggleRow(tr("wywf.config.search.center.option"),
                SearchConfig.SearchCenter.class, config.searchCenter(), config::searchCenter);
        addBoolToggleRow(tr("wywf.config.search.center.random_start"),
                config.randomizeStart(), config::randomizeStart);

        addCategory(tr("wywf.config.search.limits.name"));
        addNumberRow(tr("wywf.config.search.limits.time"),
                config.timeLimitMinutes(), 5, 120, v -> config.timeLimitMinutes(v));
        addBoolToggleRow(tr("wywf.config.search.limits.infinite"),
                config.infiniteSeeds(), v -> config.infiniteSeeds(v));
        addNumberRow(tr("wywf.config.search.limits.max_seeds"),
                (int) (config.rawMaxSeedsToCheck() / 1_000_000), 1, 1000,
                v -> config.maxSeedsToCheck((long) v * 1_000_000L));

        addCategory(tr("wywf.config.search.candidates.name"));
        addNumberRow(tr("wywf.config.search.candidates.collect"),
                config.candidatesToCollect(), 1, 20, v -> config.candidatesToCollect(v));
        addBoolToggleRow(tr("wywf.config.search.candidates.stop_first"),
                config.stopAtFirstCandidate(), config::stopAtFirstCandidate);
        addBoolToggleRow(tr("wywf.config.search.candidates.sort_distance"),
                config.sortCandidatesByDistance(), config::sortCandidatesByDistance);
        addBoolToggleRow(tr("wywf.config.search.candidates.linear"),
                config.linearBiomeSearch(), config::linearBiomeSearch);

        doneButton = Button.builder(Component.literal("Done"), b -> {
            ConfigStore.save(config);
            Minecraft.getInstance().setScreenAndShow(parent);
        }).bounds(this.width / 2 - 75, this.height - FOOTER_HEIGHT + 6, 150, 20).build();
        addWidget(doneButton);

        recalcLayout();
    }

    private void recalcLayout() {
        contentHeight = 0;
        for (RowEntry entry : rows) {
            contentHeight += entry.height();
        }
        visibleHeight = this.height - HEADER_HEIGHT - FOOTER_HEIGHT;
        clampScroll();
        repositionRows();
    }

    private void repositionRows() {
        int x = (this.width - CONTENT_WIDTH) / 2;
        int y = HEADER_HEIGHT - scrollOffset;
        for (RowEntry entry : rows) {
            entry.setX(x);
            entry.setY(y);
            entry.visible = (y + entry.height() >= HEADER_HEIGHT && y < this.height - FOOTER_HEIGHT);
            y += entry.height();
        }
        doneButton.setX(this.width / 2 - 75);
        doneButton.setY(this.height - FOOTER_HEIGHT + 6);
    }

    private void clampScroll() {
        int max = Math.max(0, contentHeight - visibleHeight);
        scrollOffset = Math.max(0, Math.min(max, scrollOffset));
    }

    private void addCategory(String name) {
        rows.add(new RowEntry.Category(stripColon(name), this.font));
    }

    private static String stripColon(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.endsWith(":") || t.endsWith("：")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    @SuppressWarnings("unchecked")
    private <T extends Enum<T>> void addToggleRow(String label, Class<T> cls, T current, Consumer<T> setter) {
        label = stripColon(label);
        T[] values = cls.getEnumConstants();
        Object[] state = {current};
        Function<Object, Component> labelFn = (Object val) -> Component.literal(((Enum<?>) val).name());

        Button btn = Button.builder(labelFn.apply(current), b -> {
            int idx = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == state[0]) { idx = i; break; }
            }
            int next = (idx + 1) % values.length;
            state[0] = values[next];
            setter.accept(values[next]);
            b.setMessage(labelFn.apply(values[next]));
        }).bounds(0, 0, 150, 20).build();
        addWidget(btn);

        rows.add(new RowEntry.Control(label, this.font, btn));
    }

    private void addBoolToggleRow(String label, boolean current, Consumer<Boolean> setter) {
        label = stripColon(label);
        Object[] state = {current};

        Button btn = Button.builder(Component.literal(current ? "ON" : "OFF"), b -> {
            boolean next = !(boolean) state[0];
            state[0] = next;
            setter.accept(next);
            b.setMessage(Component.literal(next ? "ON" : "OFF"));
        }).bounds(0, 0, 150, 20).build();
        addWidget(btn);

        rows.add(new RowEntry.Control(label, this.font, btn));
    }

    private void addNumberRow(String label, int current, int min, int max, IntConsumer setter) {
        label = stripColon(label);
        EditBox editBox = new EditBox(this.font, 0, 0, 150, 18, Component.empty());
        editBox.setValue(String.valueOf(current));
        editBox.setResponder(text -> {
            try {
                int val = Integer.parseInt(text.trim());
                setter.accept(Math.max(min, Math.min(max, val)));
            } catch (NumberFormatException ignored) {}
        });
        addWidget(editBox);

        rows.add(new RowEntry.Control(label, this.font, editBox));
    }

    private String tr(String key) { return ConfigTranslations.tr(key); }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double amount) {
        scrollOffset -= (int) (amount * 16);
        clampScroll();
        repositionRows();
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        g.fill(0, HEADER_HEIGHT, this.width, this.height - FOOTER_HEIGHT, 0x80000000);

        int contentX = (this.width - CONTENT_WIDTH) / 2;
        g.enableScissor(contentX, HEADER_HEIGHT, contentX + CONTENT_WIDTH, this.height - FOOTER_HEIGHT);
        for (RowEntry entry : rows) {
            if (entry.visible) {
                entry.render(g, mouseX, mouseY, partialTick);
            }
        }
        g.disableScissor();

        drawScrollbar(g);

        g.fill(0, HEADER_HEIGHT, this.width, HEADER_HEIGHT + 1, 0xFF404040);
        g.fill(0, this.height - FOOTER_HEIGHT - 1, this.width, this.height - FOOTER_HEIGHT, 0xFF404040);

        doneButton.render(g, mouseX, mouseY, partialTick);

        GuiGraphicsHelper.drawCenteredString(g, this.font,
                Component.literal("\u00a7l\u00a7fWyWF Settings"), this.width / 2, 8, 0xFFFFFFFF);
    }

    private void drawScrollbar(GuiGraphics g) {
        if (contentHeight <= visibleHeight) return;

        int sbx = (this.width - CONTENT_WIDTH) / 2 + CONTENT_WIDTH + 10;
        int trackH = visibleHeight;
        int scH = Math.max(SCROLLBAR_MIN_HEIGHT, Math.min(visibleHeight * visibleHeight / contentHeight, visibleHeight - 8));
        int max = contentHeight - visibleHeight;
        int sby = (max == 0) ? HEADER_HEIGHT
                : HEADER_HEIGHT + scrollOffset * (visibleHeight - scH) / max;

        g.fill(sbx, HEADER_HEIGHT, sbx + SCROLLBAR_WIDTH, HEADER_HEIGHT + trackH, 0x80000000);
        g.fill(sbx, sby, sbx + SCROLLBAR_WIDTH, sby + scH, 0xFFAAAAAA);
    }

    private abstract static class RowEntry {
        int x, y;
        boolean visible;
        abstract int height();
        abstract void setX(int x);
        abstract void setY(int y);
        abstract void render(GuiGraphics g, int mouseX, int mouseY, float partialTick);

        static final class Category extends RowEntry {
            private final String name;
            private final Font font;

            Category(String name, Font font) {
                this.name = name;
                this.font = font;
            }

            @Override int height() { return CATEGORY_HEIGHT + 4; }
            @Override void setX(int x) { this.x = x; }
            @Override void setY(int y) { this.y = y; }
            @Override void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                GuiGraphicsHelper.drawCenteredString(g, font,
                        Component.literal("\u00a7l\u00a7f" + name),
                        x + CONTENT_WIDTH / 2, y + 2, 0xFFFFFFFF);
            }
        }

        static final class Control extends RowEntry {
            private final net.minecraft.client.gui.components.StringWidget label;
            private final AbstractWidget widget;

            Control(String text, Font font, AbstractWidget widget) {
                this.label = new net.minecraft.client.gui.components.StringWidget(
                        LABEL_WIDTH, ROW_HEIGHT, Component.literal(text), font);
                this.widget = widget;
            }

            @Override int height() { return ROW_HEIGHT + 2; }

            @Override void setX(int x) {
                this.x = x;
                label.setX(x);
                widget.setX(x + CONTENT_WIDTH - 150);
            }

            @Override void setY(int y) {
                this.y = y;
                label.setY(y);
                widget.setY(y + 2);
            }

            @Override void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                label.render(g, mouseX, mouseY, partialTick);
                widget.render(g, mouseX, mouseY, partialTick);
            }
        }
    }
}
