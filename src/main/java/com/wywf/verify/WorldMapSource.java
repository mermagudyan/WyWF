package com.wywf.verify;

import com.wywf.core.*;
import java.util.List;

public interface WorldMapSource {
    SearchResult result();
    ParsedQuery query();
    SearchConfig config();
    VerificationSummary summary();
    SurfaceSnapshot surface();
    default List<SurfaceSnapshot> surfaceTiles(){return surface()==null?List.of():List.of(surface());}
    default SurfaceCache surfaceCache(){return null;}
    default SurfaceSnapshot surface(DimensionSpace dimension) {
        return dimension == DimensionSpace.OVERWORLD ? surface() : null;
    }
    default SurfaceCache surfaceCache(DimensionSpace dimension) {
        return dimension == DimensionSpace.OVERWORLD ? surfaceCache() : null;
    }
    default void pauseSurface(){}
    List<VerifiedAnchor> anchors();
    default List<VerifiedAnchor> mapAnchors(){return anchors();}
    default List<MapFeature> mapFeatures(){return List.of();}
    default java.util.concurrent.CompletableFuture<List<VerifiedAnchor>> confirmMap(java.util.List<com.wywf.map.StructureLayers.Marker> markers){return java.util.concurrent.CompletableFuture.completedFuture(List.of());}
    default java.util.concurrent.CompletableFuture<List<MapFeature>> discoverFeatures(int x,int z,java.util.Set<String> selected){return java.util.concurrent.CompletableFuture.completedFuture(List.of());}
    default java.util.concurrent.CompletableFuture<List<ReliefTile>> reliefPage(ReliefRequest request) {
        return java.util.concurrent.CompletableFuture.completedFuture(List.of());
    }
    default java.util.concurrent.CompletableFuture<List<ReliefTile>> reliefPage(
            ReliefRequest request, java.util.function.Consumer<List<ReliefTile>> partial) {
        return reliefPage(request);
    }
    default void tickMap(){}
    default void closeMap(){}
    List<LootWitness> loot();
    default boolean parked() { return true; }
    default boolean surfaceBusy() { return false; }
    default boolean cancelled() { return false; }
    default boolean saved() { return false; }
    default boolean surfaceExpandable(){return !saved();}
    default void requestSurface(int x, int z) {}
    default void requestSurface(DimensionSpace dimension, int x, int z) {
        if (dimension == DimensionSpace.OVERWORLD) requestSurface(x, z);
    }
}