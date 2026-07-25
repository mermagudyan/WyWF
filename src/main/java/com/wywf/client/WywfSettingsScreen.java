package com.wywf.client;

import com.wywf.core.ConfigStore;
import com.wywf.core.KeywordDictionary;
import com.wywf.core.SearchConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public final class WywfSettingsScreen extends Screen {

    private static final int CONTENT_WIDTH = 340;
    private static final int ROW_HEIGHT = 24;
    private static final int LABEL_WIDTH = CONTENT_WIDTH - 154;

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
        parent.addChild(new net.minecraft.client.gui.components.StringWidget(
                CONTENT_WIDTH, 14,
                Component.literal("\u00a7l\u00a77" + name + "\u00a7r"),
                this.font));
    }

    private void addRow(LinearLayout parent, String label, AbstractWidget widget) {
        parent.addChild(new SettingRow(label, widget, this.font));
    }

    private <T extends Enum<T>> CycleButton<T> buildEnum(Class<T> cls, T current, Consumer<T> setter) {
        return createCycleBuilder((T val) -> Component.literal(val.name()), current)
                .withValues(cls.getEnumConstants())
                .create(0, 0, 150, 20, Component.empty(), (btn, val) -> setter.accept(val));
    }

    private CycleButton<Boolean> buildToggle(boolean current, Consumer<Boolean> setter) {
        return createCycleBuilder((Boolean val) -> Component.literal(val ? "Options ON" : "Options OFF"), current)
                .withValues(true, false)
                .create(0, 0, 150, 20, Component.empty(), (btn, val) -> setter.accept(val));
    }

    private CycleButton<Integer> buildSlider(int min, int max, int step, int current, Consumer<Integer> setter) {
        List<Integer> values = new ArrayList<>();
        for (int v = min; v <= max; v += step) values.add(v);
        int best = values.get(0);
        for (int v : values) if (Math.abs(v - current) < Math.abs(best - current)) best = v;
        return createCycleBuilder((Integer val) -> Component.literal(String.valueOf(val)), best)
                .withValues(values)
                .create(0, 0, 150, 20, Component.empty(), (btn, val) -> setter.accept(val));
    }

    @SuppressWarnings("unchecked")
    private static <T> CycleButton.Builder<T> createCycleBuilder(Function<T, Component> nameProvider, T initial) {
        // 1.21.2+: builder(Function, T)
        try {
            return CycleButton.builder(nameProvider, initial);
        } catch (NoSuchMethodError ignored) {}

        // 1.21.1 fallback: find any static method with a single Function parameter
        for (java.lang.reflect.Method m : CycleButton.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && Function.class.isAssignableFrom(p[0])) {
                try {
                    CycleButton.Builder<T> b = (CycleButton.Builder<T>) m.invoke(null, nameProvider);
                    if (b == null) continue;
                    return b;
                } catch (Exception ignored) {}
            }
        }

        throw new RuntimeException("Cannot create CycleButton builder");
    }

    private static java.lang.reflect.Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
                // Also match by parameter signature (for intermediary names)
                Class<?>[] p = m.getParameterTypes();
                if (p.length == paramTypes.length) {
                    boolean match = true;
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (!p[i].equals(paramTypes[i])) { match = false; break; }
                    }
                    if (match) return m;
                }
            }
        }
        return null;
    }

    private String tr(String key) { return ConfigTranslations.tr(key); }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    private static final class SettingRow extends AbstractWidget {
        private final net.minecraft.client.gui.components.StringWidget label;
        private final AbstractWidget widget;

        SettingRow(String text, AbstractWidget widget, Font font) {
            super(0, 0, CONTENT_WIDTH, ROW_HEIGHT, Component.empty());
            this.widget = widget;
            this.label = new net.minecraft.client.gui.components.StringWidget(
                    LABEL_WIDTH, ROW_HEIGHT,
                    Component.literal(text),
                    font);
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
            label.setX(getX());
            label.setY(getY());
            if (widget != null) {
                widget.setX(getX() + CONTENT_WIDTH - 150);
                widget.setY(getY() + 2);
            }
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            label.render(g, mouseX, mouseY, partialTick);
            if (widget instanceof net.minecraft.client.gui.components.Renderable r) {
                r.render(g, mouseX, mouseY, partialTick);
            }
        }

        @Override
        public void visitWidgets(Consumer<AbstractWidget> visitor) {
            visitor.accept(label);
            if (widget != null) visitor.accept(widget);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {}
    }
}
