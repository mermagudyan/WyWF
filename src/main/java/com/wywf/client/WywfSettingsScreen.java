package com.wywf.client;

import com.wywf.core.ConfigStore;
import com.wywf.core.KeywordDictionary;
import com.wywf.core.SearchConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class WywfSettingsScreen extends Screen {

    private static final int CONTENT_WIDTH = 340;

    private final Screen parent;
    private final SearchConfig config;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    public WywfSettingsScreen(Screen parent) {
        super(Component.literal("WyWF Settings"));
        this.parent = parent;
        this.config = ConfigStore.load();
    }

    @Override
    protected void init() {
        LinearLayout main = new LinearLayout(0, 0, LinearLayout.Orientation.VERTICAL);
        main.spacing(2);
        main.defaultCellSetting().alignHorizontallyCenter();

        addCategory(main, tr("wywf.config.category.general"));
        addRow(main, tr("wywf.config.general.language.option"),
                buildEnum(KeywordDictionary.Lang.class, config.queryLanguage(), config::queryLanguage));
        addRow(main, tr("wywf.config.general.performance.thread_mode"),
                buildEnum(SearchConfig.Mode.class, config.mode(), config::mode));

        addCategory(main, tr("wywf.config.category.search"));
        addCategory(main, tr("wywf.config.search.scan.name"));
        addRow(main, tr("wywf.config.search.scan.structure_radius"),
                buildSlider(8, 80, 4, config.searchRadiusChunks(), v -> config.searchRadiusChunks(v)));
        addRow(main, tr("wywf.config.search.scan.biome_radius"),
                buildSlider(4, 64, 4, config.biomeCheckRadiusChunks(), v -> config.biomeCheckRadiusChunks(v)));
        addRow(main, tr("wywf.config.search.scan.biome_step"),
                buildSlider(1, 8, 1, config.biomeSampleStepChunks(), v -> config.biomeSampleStepChunks(v)));

        addCategory(main, tr("wywf.config.search.center.name"));
        addRow(main, tr("wywf.config.search.center.option"),
                buildEnum(SearchConfig.SearchCenter.class, config.searchCenter(), config::searchCenter));

        addCategory(main, tr("wywf.config.search.limits.name"));
        addRow(main, tr("wywf.config.search.limits.time"),
                buildSlider(5, 120, 5, config.timeLimitMinutes(), v -> config.timeLimitMinutes(v)));
        addRow(main, tr("wywf.config.search.limits.infinite"),
                buildToggle(config.infiniteSeeds(), config::infiniteSeeds));
        addRow(main, tr("wywf.config.search.limits.max_seeds"),
                buildSlider(1, 1000, 10,
                        (int) (config.rawMaxSeedsToCheck() / 1_000_000),
                        v -> config.maxSeedsToCheck((long) v * 1_000_000L)));

        addCategory(main, tr("wywf.config.search.candidates.name"));
        addRow(main, tr("wywf.config.search.candidates.collect"),
                buildSlider(1, 20, 1, config.candidatesToCollect(), v -> config.candidatesToCollect(v)));
        addRow(main, tr("wywf.config.search.candidates.stop_first"),
                buildToggle(config.stopAtFirstCandidate(), config::stopAtFirstCandidate));
        addRow(main, tr("wywf.config.search.candidates.sort_distance"),
                buildToggle(config.sortCandidatesByDistance(), config::sortCandidatesByDistance));

        layout.addToContents(main);

        Button done = Button.builder(Component.literal("Done"), b -> {
            ConfigStore.save(config);
            Minecraft.getInstance().setScreenAndShow(parent);
        }).width(150).build();
        layout.addToFooter(done);
        layout.setFooterHeight(32);
        layout.setHeaderHeight(26);

        layout.addTitleHeader(Component.literal("WyWF Settings"), this.font);
        layout.arrangeElements();
        layout.visitWidgets(this::addRenderableWidget);
    }

    private void addCategory(LinearLayout parent, String name) {
        parent.addChild(new CategoryWidget(name, this.font));
    }

    private void addRow(LinearLayout parent, String label, AbstractWidget widget) {
        parent.addChild(new SettingRow(label, widget, this.font));
    }

    private <T extends Enum<T>> CycleButton<T> buildEnum(Class<T> cls, T current, Consumer<T> setter) {
        return CycleButton.builder((T val) -> Component.literal(val.name()), current)
                .withValues(cls.getEnumConstants())
                .create(0, 0, 150, 20, Component.empty(), (btn, val) -> setter.accept(val));
    }

    private CycleButton<Boolean> buildToggle(boolean current, Consumer<Boolean> setter) {
        return CycleButton.onOffBuilder(current)
                .create(0, 0, 150, 20, Component.empty(), (btn, val) -> setter.accept(val));
    }

    private CycleButton<Integer> buildSlider(int min, int max, int step, int current, Consumer<Integer> setter) {
        List<Integer> values = new ArrayList<>();
        for (int v = min; v <= max; v += step) values.add(v);
        int best = values.get(0);
        for (int v : values) if (Math.abs(v - current) < Math.abs(best - current)) best = v;
        return CycleButton.builder((Integer val) -> Component.literal(String.valueOf(val)), best)
                .withValues(values)
                .create(0, 0, 150, 20, Component.empty(), (btn, val) -> setter.accept(val));
    }

    private String tr(String key) { return ConfigTranslations.tr(key); }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        layout.visitChildren(w -> {
            if (w instanceof Renderable r) r.extractRenderState(g, mouseX, mouseY, partialTick);
        });
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    private static final class CategoryWidget extends AbstractWidget {
        private final String label;
        private final Font font;

        CategoryWidget(String label, Font font) {
            super(0, 0, CONTENT_WIDTH, 14, Component.empty());
            this.label = label;
            this.font = font;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            g.text(font, Component.literal("§l§7" + label + "§r"), getX(), getY() + 2, 0xAAAAAA);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {}
    }

    private static final class SettingRow extends AbstractWidget {
        private final String label;
        private final AbstractWidget widget;
        private final Font font;

        SettingRow(String label, AbstractWidget widget, Font font) {
            super(0, 0, CONTENT_WIDTH, 24, Component.empty());
            this.label = label;
            this.widget = widget;
            this.font = font;
            repositionWidget();
        }

        @Override
        public void setX(int x) {
            super.setX(x);
            repositionWidget();
        }

        @Override
        public void setY(int y) {
            super.setY(y);
            repositionWidget();
        }

        private void repositionWidget() {
            if (widget != null) {
                widget.setX(getX() + CONTENT_WIDTH - 154);
                widget.setY(getY() + 2);
            }
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            g.text(font, Component.literal("§f" + label), getX() + 4, getY() + 6, 0xFFFFFF);
            if (widget instanceof Renderable r) {
                r.extractRenderState(g, mouseX, mouseY, partialTick);
            }
        }

        @Override
        public void visitWidgets(java.util.function.Consumer<AbstractWidget> visitor) {
            if (widget != null) visitor.accept(widget);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput output) {}
    }
}
