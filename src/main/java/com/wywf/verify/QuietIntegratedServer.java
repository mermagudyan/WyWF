package com.wywf.verify;

import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.server.Services;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.util.Optional;

final class QuietIntegratedServer extends IntegratedServer {
    private final PreviewWorld.Session owner;
    QuietIntegratedServer(Thread thread, Minecraft client, LevelStorageSource.LevelStorageAccess storage,
                          PackRepository packs, WorldStem stem, Optional<GameRules> rules, Services services,
                          LevelLoadListener listener, PreviewWorld.Session owner) {
        super(thread, client, storage, packs, stem, rules, services, listener);
        this.owner = owner;
    }
    @Override protected void onServerCrash(CrashReport report) {
        if (owner.joined()) super.onServerCrash(report);
        else owner.error("Local verifier crashed: " + report.getTitle());
    }
}
