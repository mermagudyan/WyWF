package com.wywf.client;

import com.wywf.core.*;
import com.wywf.verify.*;
import com.wywf.map.StructureLayers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

final class SavedMapPreview implements WorldMapSource {
    private final WorldMapSource original;
    private final Path directory;
    private final java.util.Queue<Runnable> waiting=new ConcurrentLinkedQueue<>();
    private final Set<VerifiedAnchor> discoveries = new CopyOnWriteArraySet<>();
    private final Set<MapFeature> features = new CopyOnWriteArraySet<>();
    private PreviewWorld.Session preview;
    private net.minecraft.core.HolderGetter<net.minecraft.world.level.biome.Biome> biomeRegistry;
    private boolean biomeRegistryLoaded;
    private SurfaceRequest pendingSurface;
    private CompletableFuture<List<SurfaceSnapshot>> diskRead;
    private boolean surfaceNeedsGeneration;
    private boolean allowSurfaceGeneration;
    private record SurfaceRequest(DimensionSpace dimension, int x, int z) {}
    private final SurfaceCache surfaces=new SurfaceCache(1024);
    private final SurfaceCache endSurfaces = new SurfaceCache(1024);
    private boolean closed,failed;
    SavedMapPreview(WorldMapSource source, Path directory) {
        original = source;
        this.directory = directory;
        if (source.surface() != null) surfaces.put(source.surface());
        var matches = Set.copyOf(source.anchors());
        try {
            SavedWorldMaps.readDiscoveries(directory, source.result().seed,
                    net.minecraft.SharedConstants.getCurrentVersion().id()).ifPresent(data -> {
                discoveries.addAll(data.anchors());
                discoveries.removeAll(matches);
                features.addAll(data.features());
            });
        } catch (java.io.IOException error) {
            com.wywf.WYWFClient.LOGGER.warn("Could not read saved map discoveries", error);
        }
        for (var anchor : source.mapAnchors()) {
            if (discoveries.size() < 4096 && !matches.contains(anchor)) discoveries.add(anchor);
        }
    }
    private boolean ensure() {
        if(closed||failed)return false;
        if(preview!=null&&preview.cancelled())preview=null;
        if(preview!=null)return true;
        if(PreviewWorld.active()!=null)return false;
        var client=Minecraft.getInstance();
        Screen owner=ClientPlatform.screen(client);
        CreateWorldScreen creator=null;
        try(var access=client.getLevelSource().validateAndCreateAccess(directory.getFileName().toString())){
            var data=new WorldOpenFlows(client,client.getLevelSource()).recreateWorldData(access);
            biomeRegistry = data.getSecond().worldgenLoadContext().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
            biomeRegistryLoaded = true;
            var packs=CreateWorldScreen.createTempDataPackDirFromExistingWorld(directory.resolve("datapacks"),client);
            creator=CreateWorldScreen.createFromExisting(client,()->client.setScreenAndShow(owner),data.getFirst(),data.getSecond(),packs);
            preview=PreviewWorld.beginMap(creator,original.result(),original.config(),owner);
            return true;
        }catch(PreviewWorld.BusyException busy){return false;}
        catch(Exception error){
            failed=true;
            com.wywf.WYWFClient.LOGGER.warn("Could not prepare saved map discovery",error);
            return false;
        }
        finally{HiddenWorldSettings.release(creator);}
    }
    private net.minecraft.core.HolderGetter<net.minecraft.world.level.biome.Biome> savedBiomes() {
        if (!SavedSurfaceReader.supportsRegionFiles() || biomeRegistryLoaded) return biomeRegistry;
        biomeRegistryLoaded = true;
        var client = Minecraft.getInstance();
        try (var access = client.getLevelSource().validateAndCreateAccess(directory.getFileName().toString())) {
            var data = new WorldOpenFlows(client, client.getLevelSource()).recreateWorldData(access);
            biomeRegistry = data.getSecond().worldgenLoadContext().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
        } catch (Exception error) {
            com.wywf.WYWFClient.LOGGER.debug("Could not load saved map biomes", error);
        }
        return biomeRegistry;
    }

    static void drainWhenReady(java.util.Queue<Runnable> queue,java.util.function.BooleanSupplier ready){
        if(queue.isEmpty()||!ready.getAsBoolean())return;
        Runnable work;while((work=queue.poll())!=null)work.run();
    }
    private <T> CompletableFuture<List<T>> request(Function<PreviewWorld.Session,CompletableFuture<List<T>>> work,Set<T> destination){
        var answer=new CompletableFuture<List<T>>();
        waiting.add(()->{
            if(closed||failed||preview==null){
                answer.completeExceptionally(new CancellationException("Map preview unavailable"));
                return;
            }
            work.apply(preview).whenComplete((found,error)->{
                if(error!=null)answer.completeExceptionally(error);
                else {for(var item:found)if(destination.size()<4096)destination.add(item);answer.complete(found);}
            });
        });
        return answer;
    }
    public CompletableFuture<List<VerifiedAnchor>> confirmMap(List<StructureLayers.Marker> markers){return request(p->p.confirmMap(markers),discoveries);}
    public CompletableFuture<List<MapFeature>> discoverFeatures(int x, int z, Set<String> selected) {
        if (selected.contains(EndReturnGateways.ID)) {
            return MapBackground.io(() -> SavedSurfaceReader.endReturnGateways(directory, x, z))
                    .thenApply(found -> {
                        if (!closed) for (var item : found) if (features.size() < 4096) features.add(item);
                        return found;
                    });
        }
        return request(p -> p.discoverFeatures(x, z, selected), features);
    }
    @Override public CompletableFuture<List<ReliefTile>> reliefPage(ReliefRequest request,
                                                                  java.util.function.Consumer<List<ReliefTile>> partial) {
        return reliefPage(request, Set.of(), partial);
    }

    @Override public CompletableFuture<List<ReliefTile>> reliefPage(ReliefRequest request, Set<Long> alreadyShown,
                                                                  java.util.function.Consumer<List<ReliefTile>> partial) {
        var answer = new CompletableFuture<List<ReliefTile>>();
        var biomes = savedBiomes();
        var read = MapBackground.io(() -> SavedSurfaceReader.reliefPage(directory, result().seed, request, biomes));
        answer.whenComplete((tiles, error) -> { if (answer.isCancelled()) read.cancel(false); });
        read.whenComplete((saved, error) -> {
                    if (answer.isCancelled()) return;
                    if (error != null) { answer.completeExceptionally(error); return; }
                    int side = ReliefBlocks.SIZE / 16;
                    var existing = new HashSet<>(alreadyShown);
                    for (var tile : saved) existing.add(SurfaceCache.key(tile.x(), tile.z()));
                    if (existing.size() == side * side) { answer.complete(saved); return; }
                    if (!saved.isEmpty()) partial.accept(saved);
                    Runnable generate = () -> {
                        if (answer.isCancelled()) return;
                        if (closed || failed || preview == null) {
                            answer.completeExceptionally(new IllegalStateException("Saved world preview unavailable"));
                            return;
                        }
                        var generated = preview.reliefPage(request, existing, partial);
                        answer.whenComplete((tiles, failure) -> { if (answer.isCancelled()) generated.cancel(false); });
                        generated.whenComplete((tiles, failure) -> {
                            if (failure != null) {
                                answer.completeExceptionally(failure);
                                return;
                            }
                            var byChunk = new LinkedHashMap<Long, ReliefTile>();
                            for (var tile : tiles) byChunk.put(SurfaceCache.key(tile.x(), tile.z()), tile);
                            for (var tile : saved) byChunk.put(SurfaceCache.key(tile.x(), tile.z()), tile);
                            answer.complete(List.copyOf(byChunk.values()));
                        });
                    };
                    waiting.add(generate);
                    answer.whenComplete((tiles, failure) -> { if (answer.isCancelled()) waiting.remove(generate); });
                });
        return answer;
    }

    public void tickMap(){
        tickSurface();
        drainWhenReady(waiting,()->ensure()||closed||failed);
        if (preview != null && !closed && !failed) {
            preview.tickMap();
            for (var tile : preview.surfaceTiles()) surfaces.put(tile);
            for (var tile : preview.surfaceCache(DimensionSpace.END).tiles()) endSurfaces.put(tile);
        }
    }
    private void tickSurface() {
        if (pendingSurface == null || closed) return;
        var position = pendingSurface;
        if (!surfaceNeedsGeneration) {
            if (diskRead == null) {
                var biomes = savedBiomes();
                diskRead = MapBackground.io(() -> SavedSurfaceReader.read(directory, result().seed,
                        position.dimension(), position.x(), position.z(), biomes));
                return;
            }
            if (!diskRead.isDone()) return;
            try {
                diskRead.join().forEach(surfaceCache(position.dimension())::putSavedTile);
            } catch (CompletionException error) {
                com.wywf.WYWFClient.LOGGER.debug("Unable to read saved surface", error);
            }
            diskRead = null;
            if (!allowSurfaceGeneration || hasSurfacePage(position)) {
                pendingSurface = null;
                return;
            }
            surfaceNeedsGeneration = true;
        }
        if (!ensure()) {
            if (failed) pendingSurface = null;
            return;
        }
        if (!preview.summary().terminal() || preview.surfaceBusy()) return;
        pendingSurface = null;
        if (!preview.cancelled()) preview.requestSurface(position.dimension(), position.x(), position.z());
    }

    private boolean hasSurfacePage(SurfaceRequest position) {
        int x = (position.x() - 32) & ~15, z = (position.z() - 32) & ~15;
        for (int dz = 0; dz < 64; dz += 16)
            for (int dx = 0; dx < 64; dx += 16)
                if (surfaceCache(position.dimension()).get(x + dx, z + dz) == null) return false;
        return true;
    }

    public void closeMap(){
        closed=true;
        pauseSurface();
        surfaces.clear();
        endSurfaces.clear();if(preview!=null)preview.cancel();Runnable work;while((work=waiting.poll())!=null)work.run();
        try {
            var unqualified = new LinkedHashSet<>(discoveries);
            unqualified.removeAll(anchors());
            SavedWorldMaps.writeDiscoveries(directory, new SavedWorldMaps.Discoveries(result().seed,
                    net.minecraft.SharedConstants.getCurrentVersion().id(), List.copyOf(unqualified), mapFeatures()));
        } catch (java.io.IOException error) {
            com.wywf.WYWFClient.LOGGER.warn("Could not save map discoveries", error);
        }
    }
    public SearchResult result(){return original.result();}public ParsedQuery query(){return original.query();}public SearchConfig config(){return original.config();}
    public VerificationSummary summary(){return original.summary();}public SurfaceSnapshot surface(){return preview!=null&&preview.surface()!=null?preview.surface():original.surface();}
    public List<SurfaceSnapshot> surfaceTiles(){return surfaces.tiles();}public SurfaceCache surfaceCache(){return surfaces;}
    @Override public SurfaceCache surfaceCache(DimensionSpace dimension) {
        return dimension == DimensionSpace.END ? endSurfaces : WorldMapSource.super.surfaceCache(dimension);
    }
    @Override public SurfaceSnapshot surface(DimensionSpace dimension) {
        return dimension == DimensionSpace.END && preview != null
                ? preview.surface(dimension) : WorldMapSource.super.surface(dimension);
    }
    public List<VerifiedAnchor> anchors(){return original.anchors();}
    public List<VerifiedAnchor> mapAnchors() {
        var all = new LinkedHashSet<>(anchors());
        all.addAll(discoveries);
        return List.copyOf(all);
    }
    public List<MapFeature> mapFeatures(){return List.copyOf(features);}public List<LootWitness> loot(){return original.loot();}
    public boolean surfaceExpandable(){return true;}
    public boolean surfaceBusy(){return pendingSurface!=null||!waiting.isEmpty()||preview!=null&&(!preview.summary().terminal()||preview.surfaceBusy());}
    public boolean cancelled(){return closed||failed;}
    public void requestSurface(int x, int z) {
        requestSurface(DimensionSpace.OVERWORLD, x, z);
    }
    @Override public void requestSurface(DimensionSpace dimension, int x, int z) {
        if (surfaceCache(dimension) == null) return;
        if (closed || pendingSurface != null) return;
        pendingSurface = new SurfaceRequest(dimension, x, z);
        surfaceNeedsGeneration = false;
        allowSurfaceGeneration = true;
    }
    void refreshSavedSurface(DimensionSpace dimension, int x, int z) {
        if (closed || pendingSurface != null || surfaceCache(dimension) == null) return;
        pendingSurface = new SurfaceRequest(dimension, x, z);
        surfaceNeedsGeneration = false;
        allowSurfaceGeneration = false;
    }
    public boolean saved(){return true;}
    public void pauseSurface(){
        pendingSurface = null;
        if (diskRead != null) { diskRead.cancel(false); diskRead = null; }
        if (preview != null) preview.pauseSurface();
    }
}
