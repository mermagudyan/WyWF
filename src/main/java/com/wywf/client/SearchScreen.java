package com.wywf.client;

import com.wywf.WYWFClient;
import com.wywf.core.*;
import com.wywf.search.SeedSearcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class SearchScreen extends Screen {

    private final CreateWorldScreen parentScreen;
    private final String queryText;
    private final ParsedQuery parsedQuery;
    private final SearchConfig config;

    private volatile SearchProgress.Snapshot lastSnapshot;
    private final AtomicReference<SearchResult> foundResult = new AtomicReference<>(null);

    private long lastUpdateTime = 0;
    private long lastCheckedSeeds = 0;
    private long lastUpdateSpeed = 0;

    private final List<StringWidget> textWidgets = new ArrayList<>();
    private ProgressBarWidget progressBar;

    public SearchScreen(CreateWorldScreen parentScreen, String queryText, ParsedQuery parsedQuery, SearchConfig config) {
        super(Component.literal("Seed Search"));
        this.parentScreen = parentScreen;
        this.queryText    = queryText;
        this.parsedQuery  = parsedQuery;
        this.config       = config;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        textWidgets.clear();

        for (int i = 0; i < 16; i++) {
            addTextLine("");
        }

        progressBar = new ProgressBarWidget(cx - 161, 0, 322, 10);
        this.addRenderableWidget(progressBar);

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            WYWFClient.searcher().cancel();
            onClose();
        }).bounds(cx - 75, this.height - 40, 150, 20).build());

        startSearch();
    }

    private void addTextLine(String text) {
        StringWidget w = new StringWidget(0, 0, this.width, 12,
                Component.literal(text), this.font);
        textWidgets.add(w);
        this.addRenderableWidget(w);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parentScreen);
    }

    private void startSearch() {
        WYWFClient.applyQueryLanguage(config.queryLanguage());
        SeedSearcher searcher = WYWFClient.searcher();
        if (searcher.isRunning()) {
            WYWFClient.LOGGER.info("Search already running, ignoring start");
            return;
        }

        searcher.start(parsedQuery, config, result -> {
            foundResult.set(result);
            Minecraft.getInstance().execute(() -> onSearchFinished(result));
        });
    }

    private void onSearchFinished(SearchResult result) {
        WYWFClient.LOGGER.info("Search finished: {}", result);

        List<SearchResult> candidates = WYWFClient.searcher().candidates();
        String reason = (result != null) ? result.stopReason : null;
        boolean limitReached = reason != null && reason.contains("limit reached");

        if (limitReached || result == null || result.primaryDescription == null) {
            if (reason == null) reason = "search complete \u2014 no matching seeds found";
            SearchLimitReachedScreen screen = new SearchLimitReachedScreen(
                    parentScreen, queryText, parsedQuery, config, reason, candidates);
            Minecraft.getInstance().setScreenAndShow(screen);
        } else {
            WYWFClient.worldCreator().create(result, queryText, parentScreen);
        }
    }

    @Override
    public void tick() {
        super.tick();
        SeedSearcher searcher = WYWFClient.searcher();
        if (searcher != null) {
            lastSnapshot = searcher.progress().snapshot();
        }

        long now = System.currentTimeMillis();
        if (now - lastUpdateTime > 500 && lastSnapshot != null) {
            long delta = lastSnapshot.checkedSeeds() - lastCheckedSeeds;
            long dt   = now - lastUpdateTime;
            lastUpdateSpeed = delta * 1000L / Math.max(1L, dt);
            lastCheckedSeeds = lastSnapshot.checkedSeeds();
            lastUpdateTime = now;
        }

        rebuildTextLines();
    }

    private void rebuildTextLines() {
        int cx = this.width / 2;
        int y = 30;
        int idx = 0;

        idx = setTextLine(idx, "\u00a7l\u00a7nSeed Search\u00a7r", cx, y); y += 18;
        idx = setTextLine(idx, "\u00a77Query:\u00a7r \u00a7e" + truncate(queryText, 60), cx, y); y += 14;

        if (parsedQuery != null) {
            idx = setTextLine(idx, "\u00a78" + parsedQuery, cx, y); y += 14;
        }
        y += 8;

        if (lastSnapshot == null) {
            setTextLine(idx, "Preparing...", cx, y);
            if (progressBar != null) progressBar.setVisible(false);
            return;
        }

        long checked = lastSnapshot.checkedSeeds();
        long maxSeeds = config.maxSeedsToCheck();

        if (progressBar != null) {
            progressBar.setVisible(true);
            progressBar.setX(cx - 161);
            progressBar.setY(y);
        }
        y += 18;

        idx = setTextLine(idx, "\u00a77Checked seeds:\u00a7f " + formatNumber(checked)
                + "        \u00a77Discarded:\u00a7f " + formatNumber(lastSnapshot.discardedSeeds()), cx, y);
        y += 14;

        idx = setTextLine(idx, "\u00a77Threads:\u00a7f " + lastSnapshot.threads()
                + "        \u00a77Time:\u00a7f " + formatTime(lastSnapshot.elapsedMs()), cx, y);
        y += 14;

        idx = setTextLine(idx, "\u00a77Speed:\u00a7f " + formatNumber(lastUpdateSpeed) + "/s"
                + "        \u00a77Average:\u00a7f " + formatNumber(lastSnapshot.seedsPerSecond()) + "/s", cx, y);
        y += 14;

        int cpuUsage = estimateCpuUsage();
        idx = setTextLine(idx, "\u00a77CPU:\u00a7f " + cpuUsage + "%"
                + "        \u00a77Seed limit:\u00a7f " + formatNumber(maxSeeds), cx, y);
        y += 20;

        SearchResult best = lastCandidate();
        if (best != null) {
            String header = "\u00a7a\u2713 " + best.seed
                    + " @ (" + best.centerX + ", " + best.centerZ + ")";
            if (best.stopReason != null && !best.stopReason.isBlank()) {
                header += "  \u00a78[" + best.stopReason + "]";
            }
            idx = setTextLine(idx, header, cx, y); y += 12;

            if (!best.matchedStructures.isEmpty()) {
                idx = setTextLine(idx, "\u00a77structures:\u00a7f " + String.join(", ", best.matchedStructures), cx, y);
                y += 12;
            }
            if (!best.matchedBiomes.isEmpty()) {
                idx = setTextLine(idx, "\u00a77biomes:\u00a7f " + String.join(", ", best.matchedBiomes), cx, y);
                y += 12;
            }
        } else if (lastSnapshot.finished()) {
            idx = setTextLine(idx, "\u00a7cNo matching seed found.", cx, y); y += 12;
        } else {
            idx = setTextLine(idx, "\u00a77Searching candidates...", cx, y); y += 12;
        }

        if (parsedQuery != null && !parsedQuery.ignoredWords().isEmpty()) {
            setTextLine(idx, "\u00a7eUnknown words ignored:\u00a7f " + String.join(", ", parsedQuery.ignoredWords()), cx, this.height - 60);
        }
    }

    private int setTextLine(int idx, String text, int cx, int y) {
        if (idx < textWidgets.size()) {
            StringWidget w = textWidgets.get(idx);
            w.setMessage(Component.literal(text));
            w.setY(y);
            int tw = this.font.width(text);
            w.setWidth(tw);
            w.setX(cx - tw / 2);
        }
        return idx + 1;
    }

    private SearchResult lastCandidate() {
        List<SearchResult> cs = WYWFClient.searcher().candidates();
        return cs.isEmpty() ? null : cs.get(cs.size() - 1);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
    }

    private int estimateCpuUsage() {
        if (lastSnapshot == null) return 0;
        int threads = lastSnapshot.threads();
        int cpu = Runtime.getRuntime().availableProcessors();
        if (cpu == 0) return 0;
        return Math.min(100, (int) (threads * 100.0 / cpu * 0.85));
    }

    private static String formatNumber(long n) {
        if (n < 1_000) return Long.toString(n);
        if (n < 1_000_000) return String.format("%,d", n).replace(',', ' ');
        if (n < 1_000_000_000) return String.format("%.2fM", n / 1_000_000.0);
        return String.format("%.2fB", n / 1_000_000_000.0);
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        long mm = s / 60;
        long ss = s % 60;
        return String.format("%02d:%02d", mm, ss);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    private final class ProgressBarWidget extends AbstractWidget {
        private boolean visible = true;

        ProgressBarWidget(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty());
        }

        void setVisible(boolean v) { this.visible = v; }

        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            if (!visible || lastSnapshot == null) return;

            int x = this.getX();
            int y = this.getY();
            int barWidth = this.getWidth();
            long checked = lastSnapshot.checkedSeeds();
            long maxSeeds = config.maxSeedsToCheck();

            g.fill(x - 1, y - 1, x + barWidth + 1, y + 11, 0xFF404040);
            g.fill(x, y, x + barWidth, y + 10, 0xFF202020);

            if (lastSnapshot.finished()) {
                double ratio = maxSeeds > 0 ? (double) checked / maxSeeds : 0;
                if (ratio > 1) ratio = 1;
                int filled = (int) (barWidth * ratio);
                g.fill(x, y, x + filled, y + 10, 0xFF00AA00);
            } else {
                int segW = barWidth / 4;
                int travel = barWidth + segW;
                int pos = (int) ((System.currentTimeMillis() / 8) % travel) - segW;
                int segStart = Math.max(x, x + pos);
                int segEnd = Math.min(x + barWidth, x + pos + segW);
                if (segEnd > segStart) g.fill(segStart, y, segEnd, y + 10, 0xFF00AA00);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }
}
