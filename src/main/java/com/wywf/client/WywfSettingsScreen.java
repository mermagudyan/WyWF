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
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class WywfSettingsScreen extends Screen {

    private static final int CONTENT_WIDTH = 340;
    private static final int ROW_HEIGHT = 24;
    private static final int LABEL_WIDTH = CONTENT_WIDTH - 154;
    private static final int HEADER_HEIGHT = 26;
    private static final int FOOTER_HEIGHT = 32;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_MIN_HEIGHT = 32;
    private static final Identifier SCROLLER_SPRITE = Identifier.withDefaultNamespace("widget/scroller");
    private static final Identifier SCROLLER_BACKGROUND_SPRITE = Identifier.withDefaultNamespace("widget/scroller_background");

    private final Screen parent;
    private final SearchConfig config;

    private final List<AbstractWidget> rows = new ArrayList<>();
    private EditBox focusedEditBox = null;
    private Button doneButton;
    private int scrollOffset = 0;
    private int contentHeight = 0;
    private int visibleHeight = 0;
    private int contentX;

    public WywfSettingsScreen(Screen parent) {
        super(Component.literal("WyWF Settings"));
        this.parent = parent;
        this.config = ConfigStore.load();
    }

    @Override
    protected void init() {
        rows.clear();
        scrollOffset = 0;
        contentX = (this.width - CONTENT_WIDTH) / 2;

        addCategory(tr("wywf.config.category.general"));
        addRow(tr("wywf.config.general.language.option"),
                buildEnum(KeywordDictionary.Lang.class, config.queryLanguage(), config::queryLanguage));
        addRow(tr("wywf.config.general.performance.thread_mode"),
                buildEnum(SearchConfig.Mode.class, config.mode(), config::mode));
        addNumberRow(tr("wywf.config.general.performance.manual_threads"),
                config.manualThreads(), 0, 64, v -> config.manualThreads(v));
        addRow(tr("wywf.config.general.native.mode"),
                buildEnum(SearchConfig.NativeMode.class, config.nativeMode(), config::nativeMode));

        addCategory(tr("wywf.config.search.scan.name"));
        addNumberRow(tr("wywf.config.search.scan.structure_radius"),
                config.searchRadiusChunks(), 8, 80, v -> config.searchRadiusChunks(v));
        addNumberRow(tr("wywf.config.search.scan.biome_radius"),
                config.biomeCheckRadiusChunks(), 4, 64, v -> config.biomeCheckRadiusChunks(v));
        addNumberRow(tr("wywf.config.search.scan.biome_step"),
                config.biomeSampleStepChunks(), 1, 8, v -> config.biomeSampleStepChunks(v));

        addCategory(tr("wywf.config.search.center.name"));
        addRow(tr("wywf.config.search.center.option"),
                buildEnum(SearchConfig.SearchCenter.class, config.searchCenter(), config::searchCenter));
        addRow(tr("wywf.config.search.center.random_start"),
                buildToggle(config.randomizeStart(), config::randomizeStart));

        addCategory(tr("wywf.config.search.limits.name"));
        addNumberRow(tr("wywf.config.search.limits.time") + " (min)",
                config.timeLimitMinutes(), 5, 120, v -> config.timeLimitMinutes(v));

        CycleButton<Boolean> infiniteToggle = buildToggle(config.infiniteSeeds(), v -> config.infiniteSeeds(v));
        addRow(tr("wywf.config.search.limits.infinite"), infiniteToggle);

        int maxSeedsDisplay = (int) (config.rawMaxSeedsToCheck() / 1_000_000L);
        addNumberRow(tr("wywf.config.search.limits.max_seeds") + " (M)",
                maxSeedsDisplay, 1, 1000, v -> config.maxSeedsToCheck((long) v * 1_000_000L));

        addCategory(tr("wywf.config.search.candidates.name"));
        addNumberRow(tr("wywf.config.search.candidates.collect"),
                config.candidatesToCollect(), 1, 20, v -> config.candidatesToCollect(v));
        addRow(tr("wywf.config.search.candidates.stop_first"),
                buildToggle(config.stopAtFirstCandidate(), config::stopAtFirstCandidate));
        addRow(tr("wywf.config.search.candidates.sort_distance"),
                buildToggle(config.sortCandidatesByDistance(), config::sortCandidatesByDistance));
        addRow(tr("wywf.config.search.candidates.linear"),
                buildToggle(config.linearBiomeSearch(), config::linearBiomeSearch));

        doneButton = Button.builder(Component.literal("Done"), b -> {
            ConfigStore.save(config);
            Minecraft.getInstance().setScreenAndShow(parent);
        }).bounds(this.width / 2 - 75, this.height - FOOTER_HEIGHT + 6, 150, 20).build();
        addRenderableWidget(doneButton);

        recalcLayout();
    }

    private void recalcLayout() {
        contentHeight = 0;
        for (AbstractWidget row : rows) {
            contentHeight += ROW_HEIGHT + 2;
        }
        visibleHeight = this.height - HEADER_HEIGHT - FOOTER_HEIGHT;
        clampScroll();
        repositionRows();
    }

    private void repositionRows() {
        int x = (this.width - CONTENT_WIDTH) / 2;
        int y = HEADER_HEIGHT - scrollOffset;
        for (AbstractWidget row : rows) {
            row.setX(x);
            row.setY(y);
            row.visible = (y + ROW_HEIGHT >= HEADER_HEIGHT && y < this.height - FOOTER_HEIGHT);
            y += ROW_HEIGHT + 2;
        }
    }

    private boolean inContentArea(double x, double y) {
        return x >= contentX && x < contentX + CONTENT_WIDTH
                && y >= HEADER_HEIGHT && y < this.height - FOOTER_HEIGHT;
    }

    private AbstractWidget findRowAt(double mouseX, double mouseY) {
        for (AbstractWidget row : rows) {
            if (row.visible && row.isMouseOver(mouseX, mouseY)) {
                return row;
            }
        }
        return null;
    }

    private EditBox findEditBoxAt(double mouseX, double mouseY) {
        for (AbstractWidget row : rows) {
            if (row.visible && row instanceof NumberInputRow nir
                    && nir.editBox.isMouseOver(mouseX, mouseY)) {
                return nir.editBox;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean focusedViaKeyboard) {
        if (focusedEditBox != null) {
            focusedEditBox.setFocused(false);
            focusedEditBox = null;
        }

        if (doneButton != null && doneButton.isMouseOver(event.x(), event.y())) {
            return doneButton.mouseClicked(event, focusedViaKeyboard);
        }
        if (inContentArea(event.x(), event.y())) {
            EditBox clicked = findEditBoxAt(event.x(), event.y());
            if (clicked != null) {
                clicked.setFocused(true);
                clicked.mouseClicked(event, focusedViaKeyboard);
                focusedEditBox = clicked;
                return true;
            }
            AbstractWidget row = findRowAt(event.x(), event.y());
            if (row != null) return row.mouseClicked(event, focusedViaKeyboard);
        }
        return super.mouseClicked(event, focusedViaKeyboard);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (doneButton != null) doneButton.mouseReleased(event);
        if (focusedEditBox != null) {
            return focusedEditBox.mouseReleased(event);
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (focusedEditBox != null) {
            return focusedEditBox.mouseDragged(event, dx, dy);
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scrollOffset -= (int) (verticalAmount * 16);
        clampScroll();
        repositionRows();
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (focusedEditBox != null) {
            return focusedEditBox.keyPressed(event);
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (focusedEditBox != null) {
            return focusedEditBox.charTyped(event);
        }
        return super.charTyped(event);
    }

    @Override
    public void tick() {
    }

    private void clampScroll() {
        int maxScroll = Math.max(0, contentHeight - visibleHeight);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - visibleHeight);
    }

    private int scrollerHeight() {
        int h = visibleHeight * visibleHeight / contentHeight;
        return Math.max(SCROLLBAR_MIN_HEIGHT, Math.min(h, visibleHeight - 8));
    }

    private int scrollBarX() {
        return contentX + CONTENT_WIDTH + 30;
    }

    private int scrollBarY() {
        int max = maxScroll();
        if (max == 0) return HEADER_HEIGHT;
        return HEADER_HEIGHT + scrollOffset * (visibleHeight - scrollerHeight()) / max;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.enableScissor(contentX, HEADER_HEIGHT, contentX + CONTENT_WIDTH, this.height - FOOTER_HEIGHT);
        for (AbstractWidget row : rows) {
            if (row.visible) {
                row.extractRenderState(g, mouseX, mouseY, partialTick);
            }
        }
        g.disableScissor();

        g.blit(RenderPipelines.GUI_TEXTURED, HEADER_SEPARATOR, 0, HEADER_HEIGHT - 2,
                0, 0, this.width, 2, 32, 2);
        g.blit(RenderPipelines.GUI_TEXTURED, FOOTER_SEPARATOR, 0, this.height - FOOTER_HEIGHT,
                0, 0, this.width, 2, 32, 2);

        g.centeredText(this.font, Component.literal("\u00a7l\u00a7fWyWF Settings"), this.width / 2,
                (HEADER_HEIGHT - this.font.lineHeight) / 2 + 1, 0xFFFFFFFF);

        if (doneButton != null) {
            doneButton.extractRenderState(g, mouseX, mouseY, partialTick);
        }

        drawScrollbar(g);
    }

    private void drawScrollbar(GuiGraphicsExtractor g) {
        int sbx = scrollBarX();
        int sbw = SCROLLBAR_WIDTH;
        int trackH = visibleHeight;
        int scH = scrollerHeight();
        int sby = scrollBarY();

        g.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_BACKGROUND_SPRITE,
                sbx, HEADER_HEIGHT, sbw, trackH);

        if (contentHeight > visibleHeight) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLLER_SPRITE,
                    sbx, sby, sbw, scH);
        }
    }

    private void addCategory(String name) {
        rows.add(new CategoryWidget(stripColon(name), this.font));
    }

    private void addRow(String label, AbstractWidget widget) {
        rows.add(new SettingRow(stripColon(label), widget, this.font));
    }

    private void addNumberRow(String label, int current, int min, int max, IntConsumer setter) {
        NumberInputRow row = new NumberInputRow(stripColon(label), this.font, current, min, max, setter);
        rows.add(row);
    }

    private static String stripColon(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.endsWith(":") || t.endsWith("：")) t = t.substring(0, t.length() - 1).trim();
        return t;
    }

    private <T extends Enum<T>> CycleButton<T> buildEnum(Class<T> cls, T current, Consumer<T> setter) {
        return CycleButton.builder((T val) -> Component.literal(val.name()), current)
                .withValues(cls.getEnumConstants())
                .displayOnlyValue()
                .create(0, 0, 150, 20, Component.empty(), (btn, val) -> setter.accept(val));
    }

    private CycleButton<Boolean> buildToggle(boolean current, Consumer<Boolean> setter) {
        return CycleButton.onOffBuilder(current)
                .displayOnlyValue()
                .create(0, 0, 150, 20, Component.empty(), (btn, val) -> setter.accept(val));
    }

    private String tr(String key) { return ConfigTranslations.tr(key); }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    private static final class CategoryWidget extends AbstractWidget {
        private final String name;
        private final Font font;

        CategoryWidget(String name, Font font) {
            super(0, 0, CONTENT_WIDTH, 16, Component.empty());
            this.name = name;
            this.font = font;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            g.centeredText(font, Component.literal("\u00a7l\u00a7f" + name),
                    getX() + getWidth() / 2, getY() + 2, 0xFFFFFFFF);
        }

        @Override
        public void visitWidgets(Consumer<AbstractWidget> visitor) {}

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {}
    }

    private static final class NumberInputRow extends AbstractWidget {
        private final EditBox editBox;
        private final net.minecraft.client.gui.components.StringWidget label;
        private final int min;
        private final int max;
        private final IntConsumer setter;

        NumberInputRow(String text, Font font, int current, int min, int max, IntConsumer setter) {
            super(0, 0, CONTENT_WIDTH, ROW_HEIGHT, Component.empty());
            this.min = min;
            this.max = max;
            this.setter = setter;
            this.label = new net.minecraft.client.gui.components.StringWidget(
                    LABEL_WIDTH, ROW_HEIGHT, Component.literal(text), font);
            this.editBox = new EditBox(font, 0, 0, 150, 18, Component.empty());
            this.editBox.setValue(String.valueOf(current));
            this.editBox.setResponder(this::onTextChanged);
            repositionWidget();
        }

        private void onTextChanged(String text) {
            try {
                int val = Integer.parseInt(text.trim());
                int clamped = Math.max(min, Math.min(max, val));
                setter.accept(clamped);
            } catch (NumberFormatException ignored) {}
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
            editBox.setX(getX() + CONTENT_WIDTH - 150);
            editBox.setY(getY() + 3);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean focusedViaKeyboard) {
            if (editBox.active && editBox.visible && editBox.isMouseOver(event.x(), event.y())) {
                editBox.mouseClicked(event, focusedViaKeyboard);
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            return editBox.mouseReleased(event);
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
            return editBox.mouseDragged(event, dx, dy);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            return false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            label.extractRenderState(g, mouseX, mouseY, partialTick);
            editBox.extractRenderState(g, mouseX, mouseY, partialTick);
        }

        @Override
        public void visitWidgets(Consumer<AbstractWidget> visitor) {
            visitor.accept(label);
            visitor.accept(editBox);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {}
    }

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
        public boolean mouseClicked(MouseButtonEvent event, boolean focusedViaKeyboard) {
            if (widget != null && widget.active && widget.visible
                    && widget.isMouseOver(event.x(), event.y())) {
                return widget.mouseClicked(event, focusedViaKeyboard);
            }
            return false;
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            if (widget != null) return widget.mouseReleased(event);
            return false;
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
            if (widget != null) return widget.mouseDragged(event, dx, dy);
            return false;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
            return false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            label.extractRenderState(g, mouseX, mouseY, partialTick);
            if (widget instanceof net.minecraft.client.gui.components.Renderable r) {
                r.extractRenderState(g, mouseX, mouseY, partialTick);
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
