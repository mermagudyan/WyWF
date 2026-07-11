package com.wywtf.client;

import com.wywtf.WYWTFClient;
import com.wywtf.core.*;
import com.wywtf.search.SeedSearcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Экран поиска сида.
 *
 * Отображает:
 *   - исходный запрос
 *   - количество проверенных сидов
 *   - количество потоков
 *   - время поиска
 *   - скорость (сидов/сек)
 *   - использование процессора (аппроксимация)
 *   - прогресс-бар
 *   - текущий найденный кандидат
 *   - кнопку Отмена
 *
 * GUI обновляется каждый кадр через чтение snapshot из {@link SearchProgress}.
 * Главный поток Minecraft НЕ блокируется.
 */
public final class SearchScreen extends Screen {

    private final String queryText;
    private final ParsedQuery parsedQuery;
    private final SearchConfig config;

    private volatile SearchProgress.Snapshot lastSnapshot;
    private final AtomicReference<SearchResult> foundResult = new AtomicReference<>(null);

    private long lastUpdateTime = 0;
    private long lastCheckedSeeds = 0;
    private long lastUpdateSpeed = 0;

    public SearchScreen(String queryText, ParsedQuery parsedQuery, SearchConfig config) {
        super(Component.literal("Поиск сида"));
        this.queryText  = queryText;
        this.parsedQuery = parsedQuery;
        this.config     = config;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        // Кнопка Отмена
        this.addRenderableWidget(Button.builder(Component.literal("Отмена"), b -> {
            WYWTFClient.searcher().cancel();
            onClose();
        }).bounds(cx - 75, this.height - 40, 150, 20).build());

        // Стартуем поиск
        startSearch();
    }

    private void startSearch() {
        SeedSearcher searcher = WYWTFClient.searcher();
        if (searcher.isRunning()) {
            WYWTFClient.LOGGER.warn("Search already running, ignoring start");
            return;
        }

        searcher.start(parsedQuery, config, result -> {
            // callback в рабочем потоке! Сохраняем и переключаемся на main.
            foundResult.set(result);
            Minecraft.getInstance().execute(() -> onSearchFinished(result));
        });
    }

    private void onSearchFinished(SearchResult result) {
        WYWTFClient.LOGGER.info("Found seed: {}", result);
        WYWTFClient.worldCreator().create(result, queryText);
        // Закрываем экран после запуска создания мира
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void tick() {
        super.tick();
        // Обновляем снапшот прогресса
        SeedSearcher searcher = WYWTFClient.searcher();
        if (searcher != null) {
            lastSnapshot = searcher.progress().snapshot();
        }

        // Расчёт мгновенной скорости (на каждые 500ms)
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
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);

        int cx = this.width / 2;
        int y  = 30;

        // Заголовок
        g.drawCenteredString(this.font, Component.literal("§l§nПоиск сида§r"), cx, y, 0xFFFFFF);
        y += 18;

        // Запрос
        g.drawCenteredString(this.font,
                Component.literal("§7Запрос:§r §e" + truncate(queryText, 60)), cx, y, 0xFFFFFF);
        y += 14;

        if (parsedQuery != null) {
            g.drawCenteredString(this.font,
                    Component.literal("§8" + parsedQuery.toString()), cx, y, 0xAAAAAA);
            y += 14;
        }

        y += 8;

        if (lastSnapshot == null) {
            g.drawCenteredString(this.font, Component.literal("Подготовка..."), cx, y, 0xCCCCCC);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        // Прогресс-бар (визуальный — лимит сида или 30s шкала)
        long checked = lastSnapshot.checkedSeeds();
        long maxSeeds = config.maxSeeds();
        int barWidth = 320;
        int barX = cx - barWidth / 2;
        int barY = y;

        g.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + 11, 0xFF404040);
        g.fill(barX, barY, barX + barWidth, barY + 10, 0xFF202020);

        double ratio = maxSeeds > 0 ? (double) checked / maxSeeds : 0;
        if (ratio > 1) ratio = 1;
        int filled = (int) (barWidth * ratio);
        g.fill(barX, barY, barX + filled, barY + 10, 0xFF00AA00);

        y += 18;

        // Метрики
        int leftCol = cx - 160;
        int rightCol = cx + 20;
        int rowH = 14;

        drawRow(g, leftCol, y,           "Проверено сидов:", formatNumber(checked));
        drawRow(g, rightCol, y,          "Отброшено:",      formatNumber(lastSnapshot.discardedSeeds()));
        y += rowH;

        drawRow(g, leftCol, y,           "Потоков:",        String.valueOf(lastSnapshot.threads()));
        drawRow(g, rightCol, y,          "Время:",          formatTime(lastSnapshot.elapsedMs()));
        y += rowH;

        drawRow(g, leftCol, y,           "Скорость:",       formatNumber(lastUpdateSpeed) + "/с");
        drawRow(g, rightCol, y,          "Средняя:",        formatNumber(lastSnapshot.seedsPerSecond()) + "/с");
        y += rowH;

        int cpuUsage = estimateCpuUsage();
        drawRow(g, leftCol, y,           "ЦП:",             cpuUsage + "%");
        drawRow(g, rightCol, y,          "Лимит сидов:",    formatNumber(maxSeeds));
        y += rowH + 6;

        // Текущий кандидат
        SearchResult best = lastSnapshot.currentBest();
        String candidateText;
        if (best != null) {
            candidateText = "§a✓ " + best.seed + " @ (" + best.spawnX + ", " + best.spawnZ + ") — " + best.matchedDescription;
        } else if (lastSnapshot.finished()) {
            candidateText = "§cПодходящий сид не найден.";
        } else {
            candidateText = "§7Кандидат пока не найден...";
        }
        g.drawCenteredString(this.font, Component.literal(candidateText), cx, y, 0xFFFFFF);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawRow(GuiGraphics g, int x, int y, String label, String value) {
        g.drawString(this.font, Component.literal("§7" + label), x, y, 0xAAAAAA);
        g.drawString(this.font, Component.literal("§f" + value), x + 110, y, 0xFFFFFF);
    }

    private int estimateCpuUsage() {
        if (lastSnapshot == null) return 0;
        // Аппроксимация: доля активных потоков от числа ядер
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
