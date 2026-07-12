package com.wywf.client;

import com.wywf.WYWFClient;
import com.wywf.core.*;
import com.wywf.search.SeedSearcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;

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

    public SearchScreen(CreateWorldScreen parentScreen, String queryText, ParsedQuery parsedQuery, SearchConfig config) {
        super(Minecraft.getInstance(), Minecraft.getInstance().font, Component.literal("Seed Search"));
        this.parentScreen = parentScreen;
        this.queryText    = queryText;
        this.parsedQuery  = parsedQuery;
        this.config       = config;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
            WYWFClient.searcher().cancel();
            onClose();
        }).bounds(cx - 75, this.height - 40, 150, 20).build());

        startSearch();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parentScreen);
    }

    private void startSearch() {
        SeedSearcher searcher = WYWFClient.searcher();
        if (searcher.isRunning()) {
            WYWFClient.LOGGER.warn("Search already running, ignoring start");
            return;
        }

        searcher.start(parsedQuery, config, result -> {
            foundResult.set(result);
            Minecraft.getInstance().execute(() -> onSearchFinished(result));
        });
    }

    private void onSearchFinished(SearchResult result) {
        WYWFClient.LOGGER.info("Found seed: {}", result);
        WYWFClient.worldCreator().create(result, queryText, parentScreen);
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
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        Font font = this.font;
        int cx = this.width / 2;
        int y  = 30;

        g.centeredText(font, Component.literal("§l§nSeed Search§r"), cx, y, 0xFFFFFF);
        y += 18;

        g.centeredText(font, Component.literal("§7Query:§r §e" + truncate(queryText, 60)), cx, y, 0xFFFFFF);
        y += 14;

        if (parsedQuery != null) {
            g.centeredText(font, Component.literal("§8" + parsedQuery), cx, y, 0xAAAAAA);
            y += 14;
        }

        y += 8;

        if (lastSnapshot == null) {
            g.centeredText(font, Component.literal("Preparing..."), cx, y, 0xCCCCCC);
            return;
        }

        long checked = lastSnapshot.checkedSeeds();
        long maxSeeds = config.maxSeeds();
        int barWidth = 320;
        int barX = cx - barWidth / 2;
        int barY = y;

        g.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + 11, 0xFF404040);
        g.fill(barX, barY, barX + barWidth, barY + 10, 0xFF202020);

        if (config.unbounded()) {
            if (!lastSnapshot.finished()) {
                int segW = barWidth / 4;
                int travel = barWidth + segW;
                int pos = (int) ((System.currentTimeMillis() / 8) % travel) - segW;
                int segStart = Math.max(barX, barX + pos);
                int segEnd = Math.min(barX + barWidth, barX + pos + segW);
                if (segEnd > segStart) g.fill(segStart, barY, segEnd, barY + 10, 0xFF00AA00);
            }
        } else {
            double ratio = maxSeeds > 0 ? (double) checked / maxSeeds : 0;
            if (ratio > 1) ratio = 1;
            int filled = (int) (barWidth * ratio);
            g.fill(barX, barY, barX + filled, barY + 10, 0xFF00AA00);
        }

        y += 18;

        int leftCol = cx - 160;
        int rightCol = cx + 20;
        int rowH = 14;

        drawRow(g, leftCol, y,  "Checked seeds:",   formatNumber(checked));
        drawRow(g, rightCol, y, "Discarded:",       formatNumber(lastSnapshot.discardedSeeds()));
        y += rowH;

        drawRow(g, leftCol, y,  "Threads:",         String.valueOf(lastSnapshot.threads()));
        drawRow(g, rightCol, y, "Time:",            formatTime(lastSnapshot.elapsedMs()));
        y += rowH;

        drawRow(g, leftCol, y,  "Speed:",           formatNumber(lastUpdateSpeed) + "/s");
        drawRow(g, rightCol, y, "Average:",         formatNumber(lastSnapshot.seedsPerSecond()) + "/s");
        y += rowH;

        int cpuUsage = estimateCpuUsage();
        drawRow(g, leftCol, y,  "CPU:",             cpuUsage + "%");
        drawRow(g, rightCol, y, "Seed limit:",      config.unbounded() ? "\u221e" : formatNumber(maxSeeds));
        y += rowH + 6;

        SearchResult best = lastSnapshot.currentBest();
        String candidateText;
        if (best != null) {
            candidateText = "§a✓ " + best.seed + " @ (" + best.centerX + ", " + best.centerZ + ") — " + best.matchedDescription;
        } else if (lastSnapshot.finished()) {
            candidateText = "§cNo matching seed found.";
        } else {
            candidateText = "§7No candidate found yet...";
        }
        g.centeredText(font, Component.literal(candidateText), cx, y, 0xFFFFFF);
    }

    private void drawRow(GuiGraphicsExtractor g, int x, int y, String label, String value) {
        g.text(this.font, Component.literal("§7" + label), x, y, 0xAAAAAA);
        g.text(this.font, Component.literal("§f" + value), x + 110, y, 0xFFFFFF);
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
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }
}
