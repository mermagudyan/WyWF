package com.wywf.client;

import com.wywf.core.WyWFDebug;
import com.wywf.verify.*;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.storage.LevelSummary;
import java.nio.file.*;
import java.util.concurrent.*;

public final class SavedWorldMapButton extends ConstructorCompassButton implements AutoCloseable {
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "WyWF-Saved-Map");
                thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Screen owner;
    private Future<?> pending;
    private String selected;
    private long revision;
    private SavedWorldMap data;
    private boolean mapVisible;

    public boolean mapVisible() { return mapVisible; }
    public void returned() { mapVisible = false; }

    public SavedWorldMapButton(Screen owner) {
        super(MapTexts.label("saved_open"), b -> ((SavedWorldMapButton) b).open());
        this.owner = owner;
        active = false;
        setTooltip(Tooltip.create(MapTexts.label("saved_unavailable")));
    }

    public void select(LevelSummary summary) {
        String id = summary == null ? null : summary.getLevelId();
        if (java.util.Objects.equals(selected, id)) return;
        selected = id;
        load();
    }

    private void open() {
        if (!active || data == null || directory == null) return;
        mapVisible = true;
        Minecraft.getInstance().setScreenAndShow(new SurfaceMapScreen(owner, data, directory));
    }

    private Path directory;
    private void load() {
        long version = ++revision;
        if (pending != null) pending.cancel(true);
        IO.purge(); active = false; data = null; directory = null;
        if (selected == null) { setTooltip(Tooltip.create(MapTexts.label("saved_unavailable"))); return; }
        Minecraft client = Minecraft.getInstance();
        Path root = client.getLevelSource().getBaseDir().toAbsolutePath().normalize();
        Path directory = root.resolve(selected).normalize();
        if (!root.equals(directory.getParent())) return;
        setTooltip(Tooltip.create(MapTexts.label("saved_loading")));
        String gameVersion = SharedConstants.getCurrentVersion().id();
        var parser = com.wywf.WYWFClient.parser();
        pending = IO.submit(() -> {
            SavedWorldMap restored = null;
            try { restored = SavedWorldMaps.read(directory, parser, gameVersion).orElse(null); }
            catch (Exception error) {
                if (WyWFDebug.ENABLED) com.wywf.WYWFClient.LOGGER.warn("Saved map could not be read: {}", directory, error);
            }
            SavedWorldMap loaded = restored;
            client.execute(() -> {
                if (version != revision || client.gui.screen() != owner || !owner.children().contains(this)) return;
                data = loaded; active = data != null;
                this.directory = active ? directory : null;
                setTooltip(Tooltip.create(MapTexts.label(active ? "saved_open_help" : "saved_unavailable")));
            });
        });
    }

    @Override public void close() {
        revision++; data = null; selected = null; directory = null; active = false;
        if (pending != null) pending.cancel(true);
        IO.purge();
    }
}