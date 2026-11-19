package com.wywf.verify;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.wywf.core.*;
import com.wywf.mixin.VerificationSettingsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.level.progress.LoggingLevelLoadListener;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class PreviewWorld {
    private static final AtomicReference<Session> active = new AtomicReference<>();
    private static volatile Session booting;
    private static final CopyOnWriteArrayList<Session> retained = new CopyOnWriteArrayList<>();
    private static final ExecutorService GENERATION = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "WyWF-Preview-Generation");
        thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
    });

    public static Session active() {
        Session session = active.get();
        if (session != null && session.joined && session.server != null && session.server.isStopped()) {
            active.compareAndSet(session, null);
        }
        return active.get();
    }
    private static void finishBoot(Session session) {
        if (booting == session) {
            PreviewScreenGuard.loading = false;
            booting = null;
        }
    }
    public static boolean loadingQuietly() {
        Session session = booting;
        return session != null && !session.cancelled && !session.joined;
    }
    public static boolean quiet(MinecraftServer server) {
        Session session = active.get();
        return session != null && session.server == server && !session.joined;
    }
    public static Executor limitExecutor(Executor original) {
        Session session = active.get();
        if (session == null || session.joined || session.cancelled) return original;
        return runnable -> {
            if (!session.joined) GENERATION.execute(runnable); else original.execute(runnable);
        };
    }

    public static synchronized Session begin(CreateWorldScreen parent, SearchResult result, ParsedQuery query,
                                             SearchConfig config, Screen progress) throws Exception {
        return begin(parent,result,query,config,progress,null);
    }
    public static synchronized Session begin(CreateWorldScreen parent,SearchResult result,ParsedQuery query,
                                             SearchConfig config,Screen progress,SearchDiagnostics diagnostics)throws Exception{
        return begin(parent,result,query,config,progress,diagnostics,false);
    }
    public static synchronized Session beginMap(CreateWorldScreen parent,SearchResult result,SearchConfig config,Screen progress)throws Exception{
        return begin(parent,result,new ParsedQuery("",List.of()),config,progress,null,true);
    }
    private static Session begin(CreateWorldScreen parent,SearchResult result,ParsedQuery query,
                                 SearchConfig config,Screen progress,SearchDiagnostics diagnostics,boolean mapOnly)throws Exception{
        Session previous = active();
        if (previous != null && !previous.joined) throw new BusyException();
        Minecraft client = Minecraft.getInstance();
        if (client.hasSingleplayerServer() || client.level != null) throw new IllegalStateException("A world is already open");
        var ui = parent.getUiState();
        var context = ui.getSettings();
        var accessor = (VerificationSettingsAccessor) parent;
        Path directory = PreviewDirectory.create(client.getLevelSource().getBaseDir());
        Session session = new Session(result, query, config, directory, progress,
                ui.getGameRules().copy(context.dataConfiguration().enabledFeatures()),mapOnly);
        session.diagnostics=diagnostics;
        active.set(session); booting = session; PreviewScreenGuard.loading = true;
        try {
            JsonElement encoded = WorldDimensions.CODEC.codec().encodeStart(
                    RegistryOps.create(JsonOps.INSTANCE, context.worldgenLoadContext()), context.selectedDimensions()).getOrThrow();
            Path packs = accessor.wywf_previewDataPacks();
            if (packs != null && Files.exists(packs)) {
                try (var paths = Files.walk(packs)) {
                    for (Path entry : paths.toList()) {
                        if (Files.isSymbolicLink(entry)) throw new IllegalStateException("Symlink datapack copy is unsupported");
                        Path target = directory.resolve("datapacks").resolve(packs.relativize(entry)).normalize();
                        if (!target.startsWith(directory.resolve("datapacks"))) throw new IllegalStateException("Invalid datapack path");
                        if (Files.isDirectory(entry)) Files.createDirectories(target);
                        else Files.copy(entry, target);
                    }
                }
            }
            Function<net.minecraft.core.HolderLookup.Provider, WorldDimensions> dimensions = lookup ->
                    WorldDimensions.CODEC.codec().parse(RegistryOps.create(JsonOps.INSTANCE, lookup), encoded).getOrThrow();
            new WorldOpenFlows(client, client.getLevelSource()).createFreshLevel(directory.getFileName().toString(),
                    accessor.wywf_previewSettings(ui.isDebug()),
                    new WorldOptions(result.seed, ui.isGenerateStructures(), ui.isBonusChest()), dimensions, progress);
            if (session.server == null && !session.cancelled) {
                session.error("World resources could not be loaded");
                client.setScreenAndShow(progress);
            }
        } catch (Exception error) {
            session.error(error.toString()); session.cancel();
            throw error;
        } finally { finishBoot(session); }
        return session;
    }

    public static boolean capture(LevelStorageSource.LevelStorageAccess storage, PackRepository packs,
                                  WorldStem stem, Optional<?> ignored, boolean fresh) {
        Session session = booting;
        if (session == null || session.joined || !session.directory.equals(storage.getLevelDirectory().path().toAbsolutePath().normalize()))
            return false;
        if (session.server != null) return false;
        if (session.cancelled) {
            try {
                stem.close();
            } finally {
                storage.safeClose();
                finishBoot(session);
            }
            return true;
        }
        session.storage = storage; session.packs = packs; session.stem = stem;
        storage.saveDataTag(stem.worldDataAndGenSettings().data());
        session.server = MinecraftServer.spin(thread -> new QuietIntegratedServer(thread, Minecraft.getInstance(),
                storage, packs, stem, Optional.of(session.rules), Minecraft.getInstance().services(),
                LoggingLevelLoadListener.forSingleplayer(), session));
        Thread monitor = new Thread(() -> session.monitor(), "WyWF-Preview-Control");
        monitor.setDaemon(true); monitor.setPriority(Thread.MIN_PRIORITY); monitor.start();
        finishBoot(session);
        if (Minecraft.getInstance().gui.screen() != session.progress) Minecraft.getInstance().setScreenAndShow(session.progress);
        return true;
    }

    public static MinecraftServer startOrReuse(Function<Thread, MinecraftServer> factory) {
        Session session = active.get();
        if (session != null && session.joined && session.joinPending) {
            session.joinPending = false; return session.server;
        }
        return MinecraftServer.spin(factory);
    }

    public static Session find(SearchResult result, ParsedQuery query, SearchConfig config) {
        Session live = active();
        if (live != null && live.summary().status() == VerificationSummary.Status.PASS && live.matches(result, query, config)) return live;
        for (Session saved : retained) if (saved.matches(result, query, config)) return saved;
        return null;
    }
    public static void shutdownActive() {
        Session session = active.get();
        if (session != null && !session.joined) session.cancel();
    }
    public static void shutdown() {
        shutdownExcept(List.of());
    }
    public static void shutdownExcept(Collection<Session> preserved) {
        Set<Session> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        keep.addAll(preserved);
        Session live = active.get();
        if (live != null && !live.joined && !keep.contains(live)) live.cancel();
        for (Session saved : retained) {
            if (!keep.contains(saved)) saved.cancel();
        }
    }
    public static final class Session implements WorldMapSource {
        public SearchDiagnostics diagnostics;
        public final SearchResult result;
        public final ParsedQuery query;
        public final SearchConfig config;
        public volatile Path directory;
        private volatile boolean joinRequested;
        public final List<VerifiedAnchor> anchors = new java.util.concurrent.CopyOnWriteArrayList<>();
        private Screen progress;
        private record SurfaceRequest(DimensionSpace dimension, int x, int z) {}
        private SurfaceRequest expanding;
        private volatile SurfaceRequest pendingSurface;
        private final GameRules rules;
        private final boolean mapOnly;
        private volatile IntegratedServer server;
        private LevelStorageSource.LevelStorageAccess storage;
        private PackRepository packs;
        private WorldStem stem;
        private volatile boolean cancelled, joined, joinPending, parked;
        private record MapWork<T>(java.util.concurrent.Callable<T> task,CompletableFuture<T> future){}
        private final java.util.Queue<MapWork<?>> mapWork=new ConcurrentLinkedQueue<>();
        private final java.util.concurrent.atomic.AtomicBoolean mapBusy=new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.Map<String,VerifiedAnchor> discoveredAnchors=new ConcurrentHashMap<>();
        private final java.util.Map<String,MapFeature> discoveredFeatures=new ConcurrentHashMap<>();
        public List<VerifiedAnchor> mapAnchors(){var all=new ArrayList<>(anchors);all.addAll(discoveredAnchors.values());return List.copyOf(all);}
        public List<MapFeature> mapFeatures(){return List.copyOf(discoveredFeatures.values());}
        public CompletableFuture<List<VerifiedAnchor>> confirmMap(java.util.List<com.wywf.map.StructureLayers.Marker> markers){
            var future=new CompletableFuture<List<VerifiedAnchor>>();mapWork.add(new MapWork<>(()->{
                var confirmed=MapDiscovery.confirm(this,markers);for(var a:confirmed)if(discoveredAnchors.size()+anchors.size()<4096)discoveredAnchors.put(a.id()+":"+a.x()+":"+a.z(),a);return confirmed;
            },future));return future;
        }
        public CompletableFuture<List<MapFeature>> discoverFeatures(int x,int z,Set<String> selected){
            var future=new CompletableFuture<List<MapFeature>>();mapWork.add(new MapWork<>(()->{
                var found=MapDiscovery.features(this,x,z,selected);for(var f:found)if(discoveredFeatures.size()<4096)discoveredFeatures.put(f.id()+":"+f.x()+":"+f.y()+":"+f.z(),f);return found;
            },future));return future;
        }
        public CompletableFuture<PlaceSurvey.Report> survey(PlaceSurvey.Request request) {
            var future = new CompletableFuture<PlaceSurvey.Report>();
            mapWork.add(new MapWork<>(() -> PlaceSurvey.scan(this, request, future::isCancelled), future));
            return future;
        }
        public CompletableFuture<List<ReliefTile>> reliefPage(ReliefRequest request) {
            return reliefPage(request, Set.of());
        }

        public CompletableFuture<List<ReliefTile>> reliefPage(ReliefRequest request, Set<Long> alreadySaved) {
            var future = new CompletableFuture<List<ReliefTile>>();
            var work = new MapWork<>(() -> {
                var level = level(request.dimension());
                Integer slice = request.cut() == null ? null : Math.clamp(request.cut(), level.getMinY(), level.getMaxY() - 1);
                var result = new java.util.ArrayList<ReliefTile>();
                var status = net.minecraft.world.level.chunk.status.ChunkStatus.FULL;
                for (int dz = 0; dz < ReliefBlocks.SIZE; dz += 16) for (int dx = 0; dx < ReliefBlocks.SIZE; dx += 16) {
                    if (future.isCancelled()) return List.of();
                    int x = request.x() + dx, z = request.z() + dz;
                    if (!alreadySaved.contains(SurfaceCache.key(x, z)))
                        level.getChunkSource().getChunkFuture(x >> 4, z >> 4, status, true);
                }
                var neighbors = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<
                        java.util.Optional<net.minecraft.world.level.chunk.ChunkAccess>>();
                for (int dz = 0; dz < ReliefBlocks.SIZE; dz += 16) for (int dx = 0; dx < ReliefBlocks.SIZE; dx += 16) {
                    if (future.isCancelled()) return List.of();
                    int x = request.x() + dx, z = request.z() + dz;
                    if (alreadySaved.contains(SurfaceCache.key(x, z))) continue;
                    var chunk = chunk(level, x >> 4, z >> 4, status);
                    neighbors.put(SurfaceCache.key(x, z), java.util.Optional.of(chunk));
                }
                for (int dz = 0; dz < ReliefBlocks.SIZE; dz += 16) for (int dx = 0; dx < ReliefBlocks.SIZE; dx += 16) {
                    if (future.isCancelled()) return List.of();
                    int x = request.x() + dx, z = request.z() + dz;
                    if (alreadySaved.contains(SurfaceCache.key(x, z))) continue;
                    var chunk = neighbors.get(SurfaceCache.key(x, z)).orElseThrow();
                    int fog = request.dimension() == DimensionSpace.NETHER && request.containsFocus(x, z)
                            ? MinecraftReliefFog.at(level, request.focus()) : 0;
                    result.add(ReliefBlocks.tile(request.dimension(), x, z, slice, level.getMinY(), level.getMaxY() - 1, level,
                            chunk::getBlockState, (wx, wz) -> chunk.getHeight(
                                    net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15),
                            (wx, wz) -> {
                                long key = SurfaceCache.key(wx, wz);
                                var neighbor = neighbors.get(key);
                                if (neighbor == null) {
                                    neighbor = java.util.Optional.ofNullable(level.getChunkSource().getChunkNow(wx >> 4, wz >> 4));
                                    neighbors.put(key, neighbor);
                                }
                                return neighbor.isEmpty() ? Integer.MIN_VALUE : neighbor.get().getHeight(
                                        net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15);
                            }, p -> level.getBiome(p).is(net.minecraft.tags.BiomeTags.IS_RIVER), fog));
                }
                return List.copyOf(result);
            }, future);
            mapWork.add(work);
            future.whenComplete((tiles, error) -> { if (future.isCancelled()) mapWork.remove(work); });
            return future;
        }

        public void tickMap(){
            if(pendingSurface!=null&&!mapBusy.get()&&!surfaceBusy.get()&&!closing.get()){
                var position=pendingSurface;pendingSurface=null;requestSurface(position.dimension(), position.x(), position.z());return;
            }
            if(mapWork.isEmpty()||mapBusy.get())return;
            if(cancelled||joined){cancelMapWork("Map preview closed");return;}
            if(!summary.terminal()||surfaceBusy.get()||closing.get())return;
            if(parked||server==null){requestSurface(result.centerX,result.centerZ);return;}
            if (!mapBusy.compareAndSet(false, true)) return;
            var job = mapWork.poll();
            if (job == null) {
                mapBusy.set(false);
                return;
            }
            server.execute(() -> runMap(job));
        }
        private void cancelMapWork(String reason) {
            MapWork<?> work;
            while ((work = mapWork.poll()) != null)
                work.future.completeExceptionally(new CancellationException(reason));
        }

        private <T> void runMap(MapWork<T> job){
            try{chunks.clear();deadline=System.nanoTime()+90_000_000_000L;if(!job.future.isCancelled())job.future.complete(job.task.call());}
            catch(Exception error){job.future.completeExceptionally(error);}
            finally{mapBusy.set(false);if(pauseSurface&&!surfaceBusy.get())park();}
        }
        private volatile VerificationSummary summary = new VerificationSummary(VerificationSummary.Status.BOOTING,
                0, 0, 0, List.of(), "");
        private volatile SurfaceSnapshot surface;
        private final SurfaceCache surfaceCache=new SurfaceCache(1024);
        private final SurfaceCache endSurfaceCache = new SurfaceCache(1024);
        private volatile SurfaceSnapshot endSurface;
        private volatile boolean pauseSurface;
        public SurfaceCache surfaceCache(){return surfaceCache;}
        @Override public SurfaceCache surfaceCache(DimensionSpace dimension) {
            return dimension == DimensionSpace.END ? endSurfaceCache : WorldMapSource.super.surfaceCache(dimension);
        }
        @Override public SurfaceSnapshot surface(DimensionSpace dimension) {
            return dimension == DimensionSpace.END ? endSurface : WorldMapSource.super.surface(dimension);
        }
        public List<SurfaceSnapshot> surfaceTiles(){return surfaceCache.tiles();}
        void tile(DimensionSpace dimension, SurfaceSnapshot tile) { surfaceCache(dimension).put(tile); }
        public void pauseSurface(){pendingSurface=null;pauseSurface=true;if(!surfaceBusy.get()&&!mapBusy.get())park();}
        private final java.util.concurrent.atomic.AtomicBoolean surfaceBusy = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
        public final List<LootWitness> loot = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.Map<Object, LootWitness> lootContainers = new java.util.HashMap<>();
        private record ChunkKey(String dimension, int x, int z) {}
        private final Set<ChunkKey> chunks = new HashSet<>();
        private volatile VerificationProfile verificationProfile = new VerificationProfile(0, 0, 0);
        private final Set<ChunkKey> verificationFullChunks = com.wywf.client.OnlineSearchBenchmark.enabled() ? new HashSet<>() : null;
        public record VerificationProfile(long nanos, int chunkCount, int fullChunks) {}
        public VerificationProfile verificationProfile() { return verificationProfile; }
        public net.minecraft.server.level.ServerLevel level(DimensionSpace dimension) {
            var key = switch (dimension) {
                case OVERWORLD -> net.minecraft.world.level.Level.OVERWORLD;
                case NETHER -> net.minecraft.world.level.Level.NETHER;
                case END -> net.minecraft.world.level.Level.END;
            };
            var level = server.getLevel(key);
            if (level == null) throw new BudgetExceeded("Dimension unavailable: " + dimension.id);
            return level;
        }
        private long deadline;
        Session(SearchResult result, ParsedQuery query, SearchConfig config, Path directory, Screen progress, GameRules rules) {
            this(result,query,config,directory,progress,rules,false);
        }
        Session(SearchResult result, ParsedQuery query, SearchConfig config, Path directory, Screen progress, GameRules rules,boolean mapOnly) {
            this.result = result; this.query = query; this.config = config.copy();
            this.directory = directory; this.progress = progress; this.rules = rules;this.mapOnly=mapOnly;
        }
        static boolean parkable(boolean mapOnly,VerificationSummary.Status status){
            return status==VerificationSummary.Status.PASS||mapOnly&&status==VerificationSummary.Status.PARTIAL;
        }
        private volatile SearchResult confirmedResult;
        final Set<String> confirmedBiomes = new java.util.concurrent.CopyOnWriteArraySet<>();
        public SearchResult result() { return confirmedResult == null ? result : confirmedResult; }
        private void recordResult(VerificationSummary verified) {
            var structures = new LinkedHashSet<>(result.matchedStructures);
            var biomes = new LinkedHashSet<>(result.matchedBiomes); biomes.addAll(confirmedBiomes);
            var positions = new HashMap<>(result.structurePositions);
            for (var anchor : anchors) { structures.add(anchor.id()); positions.putIfAbsent(anchor.id(), new int[]{anchor.x(), anchor.z()}); }
            int x = config.searchCenter() == SearchConfig.SearchCenter.SPAWN ? verified.spawnX() : result.centerX;
            int z = config.searchCenter() == SearchConfig.SearchCenter.SPAWN ? verified.spawnZ() : result.centerZ;
            confirmedResult = new SearchResult(result.seed, x, z, result.structureX, result.structureZ, result.primaryDescription,
                    List.copyOf(structures), List.copyOf(biomes), result.stopReason, positions, result.biomeDistances);
        }
        public ParsedQuery query() { return query; }
        public SearchConfig config() { return config; }
        public List<VerifiedAnchor> anchors() { return anchors; }
        public List<LootWitness> loot() { return loot; }
        public VerificationSummary summary() { return summary; }
        public SurfaceSnapshot surface() { return surface; }
        public boolean surfaceBusy() { return surfaceBusy.get(); }
        public void requestSurface(int x, int z) {
            requestSurface(DimensionSpace.OVERWORLD, x, z);
        }
        @Override public void requestSurface(DimensionSpace dimension, int x, int z) {
            if (surfaceCache(dimension) == null) return;
            IntegratedServer running;
            boolean reopen;
            synchronized (PreviewWorld.class) {
                synchronized (this) {
                    if (mapBusy.get() && !cancelled && !joined) {
                        pendingSurface = new SurfaceRequest(dimension, x, z);
                        return;
                    }
                    if (cancelled || joined || joinRequested || !summary.terminal() || closing.get()) return;
                    Session owner = active();
                    if (owner != null && owner != this) return;
                    running = server;
                    reopen = parked;
                    if (reopen == (running != null) || !surfaceBusy.compareAndSet(false, true)) return;
                    pauseSurface = false;
                    if (reopen) {
                        expanding = new SurfaceRequest(dimension, x, z);
                        progress = Minecraft.getInstance().gui.screen();
                        parked = false;
                        retained.remove(this);
                        active.set(this);
                        booting = this;
                        PreviewScreenGuard.loading = true;
                    }
                }
            }
            if (reopen) {
                try {
                    new WorldOpenFlows(Minecraft.getInstance(), Minecraft.getInstance().getLevelSource()).openWorld(
                            directory.getFileName().toString(), () -> {
                                finishBoot(this);
                                expanding = null; surfaceBusy.set(false); park();
                                Minecraft.getInstance().setScreenAndShow(progress);
                            });
                } catch (Exception error) {
                    org.slf4j.LoggerFactory.getLogger(PreviewWorld.class).warn("Could not reopen surface preview", error);
                    finishBoot(this);
                    expanding = null; surfaceBusy.set(false); park();
                }
                return;
            }
            running.execute(() -> {
                try {
                    chunks.clear(); deadline = System.nanoTime() + 90_000_000_000L;
                    VanillaVerifier.buildSurface(this, dimension, x, z, 64);
                } catch (Exception error) {
                    org.slf4j.LoggerFactory.getLogger(PreviewWorld.class).warn("Surface preview stopped", error);
                } finally { surfaceBusy.set(false);if(pauseSurface&&!mapBusy.get())park(); }
            });
        }
        public boolean matches(SearchResult other, ParsedQuery conditions, SearchConfig options) {
            return !mapOnly && !joined && !cancelled && result.seed == other.seed && (result.centerX == other.centerX && result.centerZ == other.centerZ
                        || confirmedResult != null && confirmedResult.centerX == other.centerX && confirmedResult.centerZ == other.centerZ)
                    && query.normalized().equals(conditions.normalized())
                    && query.ignoredWords().equals(conditions.ignoredWords())
                    && config.searchCenter() == options.searchCenter()
                    && config.searchRadiusChunks() == options.searchRadiusChunks()
                    && config.biomeCheckRadiusChunks() == options.biomeCheckRadiusChunks();
        }
        public boolean joined() { return joined; }
        public boolean cancelled() { return cancelled; }
        public IntegratedServer server() { return server; }

        public void error(String text) {
            summary = new VerificationSummary(VerificationSummary.Status.ERROR, 0, 0, 0, List.of(), text);
        }

        private void monitor() {
            long timeout = System.nanoTime() + 120_000_000_000L;
            while (!cancelled && server != null && !server.isReady() && server.isRunning() && System.nanoTime() < timeout) {
                try { Thread.sleep(50); } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
            }
            if (cancelled) return;
            if (server == null || !server.isReady()) { error("Local server startup failed or timed out"); cancel(); return; }
            deadline = System.nanoTime() + 90_000_000_000L;
            if (expanding != null) {
                var center = expanding; expanding = null;
                server.execute(() -> {
                    try { chunks.clear(); VanillaVerifier.buildSurface(this, center.dimension(), center.x(), center.z(), 64); }
                    catch (Exception error) { org.slf4j.LoggerFactory.getLogger(PreviewWorld.class).warn("Surface expansion stopped", error); }
                    finally { surfaceBusy.set(false);if(pauseSurface&&!mapBusy.get())park(); }
                });
                return;
            }
            if(mapOnly){
                var spawn=server.getRespawnData().pos();
                summary=new VerificationSummary(VerificationSummary.Status.PARTIAL,spawn.getX(),spawn.getY(),spawn.getZ(),
                        List.of("Map preview only; no search conditions verified"),"Map preview ready");
                return;
            }
            summary = new VerificationSummary(VerificationSummary.Status.VERIFYING, 0, 0, 0, List.of(), "");
            server.execute(() -> {
                try {
                    long started = verificationFullChunks == null ? 0 : System.nanoTime();
                    var verified = VanillaVerifier.verify(this);
                    if (verificationFullChunks != null)
                        verificationProfile = new VerificationProfile(System.nanoTime() - started, chunks.size(), verificationFullChunks.size());
                    publishVerification(verified);
                } catch (Exception error) {
                    error(error.toString());
                }
            });
        }

        void publishVerification(VerificationSummary verified) {
            if (verified.status() == VerificationSummary.Status.PASS) recordResult(verified);
            summary = verified;
        }

        public void checkpoint() {
            if (cancelled) throw new CancellationException("Verification cancelled");
            if (System.nanoTime() > deadline) throw new BudgetExceeded("Verification time budget reached");
            Runtime memory = Runtime.getRuntime();
            if (memory.maxMemory() - memory.totalMemory() + memory.freeMemory() < 192L * 1024 * 1024)
                throw new BudgetExceeded("Verification stopped to keep 192 MiB of Java heap headroom");
        }

        public net.minecraft.world.level.chunk.ChunkAccess chunk(int x, int z, net.minecraft.world.level.chunk.status.ChunkStatus status) {
            return chunk(server.overworld(), x, z, status);
        }
        public net.minecraft.world.level.chunk.ChunkAccess chunk(net.minecraft.server.level.ServerLevel level, int x, int z,
                net.minecraft.world.level.chunk.status.ChunkStatus status) {
            checkpoint();
            var key = new ChunkKey(level.dimension().identifier().toString(), x, z);
            if (!chunks.contains(key) && chunks.size() >= 128) throw new BudgetExceeded("128-chunk verification budget reached");
            chunks.add(key);
            if (verificationFullChunks != null
                    && status == net.minecraft.world.level.chunk.status.ChunkStatus.FULL) verificationFullChunks.add(key);
            var cache = level.getChunkSource();
            var future = cache.getChunkFuture(x, z, status, true);
            while (!future.isDone()) {
                checkpoint();
                if (!cache.pollTask()) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            }
            checkpoint();
            return future.join().orElseThrow(() -> new IllegalStateException("Chunk generation failed"));
        }

        void surface(DimensionSpace dimension, SurfaceSnapshot snapshot) {
            if (dimension == DimensionSpace.END) endSurface = snapshot;
            else surface = snapshot;
            surfaceCache(dimension).put(snapshot);
        }

        public void join() throws Exception {
            if (mapOnly || cancelled || joined || joinRequested || !summary.terminal() || summary.status() != VerificationSummary.Status.PASS) return;
            if(!parked||closing.get()||server!=null||surfaceBusy.get()||mapBusy.get()){
                joinRequested=true;pendingSurface=null;pauseSurface=true;
                cancelMapWork("Opening accepted world");
                if(!parked&&!surfaceBusy.get()&&!mapBusy.get())park();
                Thread wait=new Thread(()->{
                    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
                    while((!parked||closing.get()||server!=null)&&!cancelled&&System.nanoTime()<until)java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
                    Minecraft.getInstance().execute(()->{
                        joinRequested=false;
                        if(cancelled||closing.get()||server!=null)return;
                        try{join();}catch(Exception error){org.slf4j.LoggerFactory.getLogger(PreviewWorld.class).warn("Could not open accepted world",error);}
                    });
                },"WyWF-World-Open");
                wait.setDaemon(true);wait.start();return;
            }
            String version = net.minecraft.SharedConstants.getCurrentVersion().id();
            SavedWorldMaps.write(directory, new SavedWorldMap(UUID.randomUUID().toString(),
                    version, result(), query, config, summary, surface, anchors(), loot));
            try {
                var discoveries = new LinkedHashSet<>(discoveredAnchors.values());
                discoveries.removeAll(anchors());
                SavedWorldMaps.writeDiscoveries(directory,
                        new SavedWorldMaps.Discoveries(result.seed, version, List.copyOf(discoveries), mapFeatures()));
            } catch (java.io.IOException error) {
                org.slf4j.LoggerFactory.getLogger(PreviewWorld.class).warn("Could not save map discoveries for {}", directory, error);
            }
            PreviewDirectory.accept(directory);
            try{directory=PreviewDirectory.promote(directory,WorldSplashes.choose());}
            catch(java.io.IOException error){org.slf4j.LoggerFactory.getLogger(PreviewWorld.class).warn("Could not apply splash world name; keeping accepted save {}",directory,error);}
            joined = true; retained.remove(this);
            for (Session other : retained) other.cancel();
            if (parked) {
                new WorldOpenFlows(Minecraft.getInstance(), Minecraft.getInstance().getLevelSource()).openWorld(
                        directory.getFileName().toString(), () -> Minecraft.getInstance().setScreenAndShow(progress));
            } else {
                joinPending = true;
                Minecraft.getInstance().doWorldLoad(storage, packs, stem, Optional.of(rules), false);
            }
        }
        public synchronized void park() {
            if (cancelled || joined || !parkable(mapOnly,summary.status())) return;
            parked = true;
            stop();
        }
        public synchronized void cancel() {
            if (joined) return;
            cancelled = true;pendingSurface=null;surfaceCache.clear();endSurfaceCache.clear();endSurface=null; retained.remove(this);
            cancelMapWork("Map preview closed");
            if (active.get() == this) PreviewScreenGuard.loading = false;
            stop();
        }
        private void stop() {
            if (!closing.compareAndSet(false, true)) return;
            Thread cleanup = new Thread(() -> {
                while (booting == this) java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
                IntegratedServer running = server;
                try {
                    if (running != null && !running.isStopped()) running.halt(true);
                    if (running == null) {
                        try {
                            if (stem != null) stem.close();
                        } finally {
                            if (storage != null) storage.safeClose();
                        }
                    }
                } catch (Exception error) {
                    org.slf4j.LoggerFactory.getLogger(PreviewWorld.class).warn("Could not stop verification world {}", directory, error);
                }
                synchronized (this) {
                    try {
                        if (running != null && !running.isStopped()) {
                            org.slf4j.LoggerFactory.getLogger(PreviewWorld.class).warn("Preview server is still running for {}", directory);
                            return;
                        }
                        server = null; storage = null; packs = null; stem = null;
                        if (cancelled) {
                            progress = null;
                            surface = null;
                            surfaceCache.clear();endSurfaceCache.clear();endSurface=null;
                        }
                        if (parked && !cancelled) retained.addIfAbsent(this);
                        else PreviewDirectory.discard(Minecraft.getInstance().getLevelSource().getBaseDir(), directory);
                    } catch (Exception error) {
                        org.slf4j.LoggerFactory.getLogger(PreviewWorld.class).warn("Could not discard verification world {}", directory, error);
                    } finally {
                        // Release ownership before allowing this session to reopen
                        if (running == null || running.isStopped()) active.compareAndSet(this, null);
                        closing.set(false);
                    }
                }
            }, "WyWF-Preview-Cleanup");
            cleanup.setDaemon(true); cleanup.start();
        }
        public boolean parked() { return parked; }
    }
    public static final class BusyException extends IllegalStateException {
        public BusyException(){super("Verifier already active");}
    }
    public static final class BudgetExceeded extends RuntimeException {
        public BudgetExceeded(String message) { super(message); }
    }
    private PreviewWorld() {}
}
