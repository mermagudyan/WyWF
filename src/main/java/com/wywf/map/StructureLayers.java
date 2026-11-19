package com.wywf.map;

import com.wywf.core.*;
import com.wywf.search.*;
import com.wywf.verify.VerifiedAnchor;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

public final class StructureLayers implements AutoCloseable {
    public record Marker(String id, int x, int z, boolean verified, int minX, int minZ, int maxX, int maxZ,
                         boolean queryMatched) {
        public Marker(String id, int x, int z, boolean verified) { this(id,x,z,verified,x,z,x,z); }
        public Marker(String id, int x, int z, boolean verified, int minX, int minZ, int maxX, int maxZ) {
            this(id, x, z, verified, minX, minZ, maxX, maxZ, false);
        }
        public long distance(int cx, int cz) {
            return Math.round(Math.hypot(Math.max(0, Math.max((double) minX - cx, (double) cx - maxX)),
                    Math.max(0, Math.max((double) minZ - cz, (double) cz - maxZ))));
        }
    }
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WyWF-MapStructures"); thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
    });
    public final Set<String> enabled = new LinkedHashSet<>();
    private final Set<String> defaults=new LinkedHashSet<>();
    private java.nio.file.Path preferences;
    private boolean endVisited;
    public synchronized void preferences(java.nio.file.Path path){
        preferences=path;
        try {
            if(!java.nio.file.Files.isRegularFile(path)||java.nio.file.Files.size(path)>32768)return;
            var data=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
            var values=data.getAsJsonArray("enabled");if(values==null||values.size()>256)return;
            var restored=new LinkedHashSet<String>();for(var value:values){String id=value.getAsString();if(!id.matches("[a-z0-9_]+:[a-z0-9_/]+"))return;restored.add(id);}
            enabled.clear();enabled.addAll(restored);endVisited=data.has("endVisited")&&data.get("endVisited").getAsBoolean();
        }catch(java.io.IOException|RuntimeException error){org.slf4j.LoggerFactory.getLogger(StructureLayers.class).warn("Could not read map layer choices",error);}
    }
    private void save(){
        if(preferences==null)return;
        try{
            java.nio.file.Files.createDirectories(preferences.getParent());var data=new com.google.gson.JsonObject();
            var values=new com.google.gson.JsonArray();enabled.forEach(values::add);data.add("enabled",values);data.addProperty("endVisited",endVisited);
            var tmp=java.nio.file.Files.createTempFile(preferences.getParent(),".layers-",".tmp");
            try{java.nio.file.Files.writeString(tmp,data.toString());java.nio.file.Files.move(tmp,preferences,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}finally{java.nio.file.Files.deleteIfExists(tmp);}
        }catch(java.io.IOException error){org.slf4j.LoggerFactory.getLogger(StructureLayers.class).warn("Could not save map layer choices",error);}
    }
    public synchronized void toggle(String id){markerVersion.incrementAndGet();if(!enabled.remove(id))enabled.add(id);last="";save();}
    public synchronized void clearExtra(){markerVersion.incrementAndGet();enabled.clear();enabled.addAll(defaults);last="";save();}
    private ParsedQuery distanceTerms = new ParsedQuery("",List.of());
    public void distanceTerms(ParsedQuery query) { distanceTerms=query; }
    public boolean boundary(Marker marker){return TargetDistance.boundary(distanceTerms,marker.id);}
    public long distance(Marker marker,int cx,int cz){return Math.round(TargetDistance.blocks(distanceTerms,marker.id,cx,cz,
            marker.x,marker.z,marker.minX,marker.minZ,marker.maxX,marker.maxZ));}
    private final List<Marker> known = new ArrayList<>();
    private volatile List<Marker> predicted = List.of();
    private java.util.function.Function<List<Marker>,CompletableFuture<List<com.wywf.verify.VerifiedAnchor>>> confirmer;
    public boolean supportsDiscovery(){return confirmer!=null;}
    public void confirmation(java.util.function.Function<List<Marker>,CompletableFuture<List<com.wywf.verify.VerifiedAnchor>>> value){confirmer=value;}
    private final java.util.Map<String,java.util.SortedSet<Integer>> featureHeights=new java.util.HashMap<>();
    public String featureHeights(Marker marker){var levels=featureHeights.get(marker.id+":"+marker.x+":"+marker.z);return levels==null?"":" · Y "+levels;}
    public synchronized void feature(com.wywf.verify.MapFeature feature){
        var levels = featureHeights.computeIfAbsent(feature.id() + ":" + feature.x() + ":" + feature.z(),
                key -> new TreeSet<>());
        if (!levels.add(feature.y())) return;
        if (levels.size() == 1) known.add(new Marker(feature.id(), feature.x(), feature.z(), true));
        markerVersion.incrementAndGet();
    }
    private volatile long revision;
    private final java.util.concurrent.atomic.AtomicLong markerVersion=new java.util.concurrent.atomic.AtomicLong();
    public long markerVersion(){return markerVersion.get();}
    public boolean requested(String id){return defaults.contains(id);}
    private volatile boolean paused;
    private Future<?> task;
    private String last = "";
    private com.wywf.core.DimensionSpace dimension = com.wywf.core.DimensionSpace.OVERWORLD;
    public synchronized void dimension(com.wywf.core.DimensionSpace value) {
        if (dimension != value) { dimension = value; close();if(value==DimensionSpace.END&&!endVisited){endVisited=true;enabled.add("minecraft:end_city");save();} }
    }
    public StructureLayers(SearchResult result) {
        enabled.addAll(result.matchedStructures);defaults.addAll(result.matchedStructures);
        result.structurePositions.forEach((id, pos) -> {
            if (pos.length >= 2) known.add(new Marker(id.split("#", 2)[0], pos[0], pos[1], false));
        });
    }
    public void verified(String id, int x, int z) {
        verified(id,x,z,x,z,x,z);
    }
    public void verified(String id, int x, int z, int minX, int minZ, int maxX, int maxZ) {
        remember(new Marker(id, x, z, true, minX, minZ, maxX, maxZ));
    }

    public void matched(VerifiedAnchor anchor) {
        remember(new Marker(anchor.id(), anchor.x(), anchor.z(), true, anchor.minX(), anchor.minZ(),
                anchor.maxX(), anchor.maxZ(), true));
    }

    private void remember(Marker marker) {
        for (int i = known.size() - 1; i >= 0; i--) {
            var previous = known.get(i);
            if (!previous.id().equals(marker.id()) || (previous.x() >> 4) != (marker.x() >> 4)
                    || (previous.z() >> 4) != (marker.z() >> 4)) continue;
            if ((previous.queryMatched() && !marker.queryMatched()) || previous.equals(marker)) return;
            known.remove(i);
        }
        known.add(marker);
        markerVersion.incrementAndGet();
    }
    public List<Marker> markers() {
        List<Marker> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(var group:List.of(known,predicted))for(var p:group){
            if(!enabled.contains(p.id)||DimensionSpace.of(p.id)!=dimension||confirmer!=null&&!p.verified)continue;
            String key=p.id+":"+(p.id.startsWith("wywf:")?p.x:Math.floorDiv(p.x,16))+":"+(p.id.startsWith("wywf:")?p.z:Math.floorDiv(p.z,16));
            if(seen.add(key))out.add(p);
        }
        return out;
    }
    public com.wywf.core.DimensionSpace dimension() { return dimension; }
    public boolean loading() { return task != null && !task.isDone(); }
    public void paused(boolean value) { paused = value; }
    public synchronized void request(long seed,int x,int z,int radius){var view=new MapViewport(x,z,1);view.resize(radius*2,radius*2);requestView(seed,view);}
    public synchronized void requestView(long seed,MapViewport view) {
        int tile=1024;while(tile<8192&&tile<view.scale()*96)tile*=2;
        int minX=Math.floorDiv((int)Math.floor(view.worldX(0)),tile),maxX=Math.floorDiv((int)Math.ceil(view.worldX(view.width())),tile);
        int minZ=Math.floorDiv((int)Math.floor(view.worldZ(0)),tile),maxZ=Math.floorDiv((int)Math.ceil(view.worldZ(view.height())),tile);
        var requestedDimension=dimension;
        List<String> ids=enabled.stream().filter(id->DimensionSpace.of(id)==requestedDimension)
                .filter(id->!(view.scale()>16&&(id.contains("buried_treasure")||id.contains("mineshaft")||id.contains("ocean_ruin")||id.contains("abandoned_camp"))))
                .filter(id->!id.startsWith("wywf:")).sorted().toList();
        String key=seed+":"+dimension+":"+tile+":"+minX+":"+maxX+":"+minZ+":"+maxZ+":"+ids;
        if(key.equals(last))return;last=key;long version=++revision;if(task!=null)task.cancel(false);
        if(ids.isEmpty()){predicted=List.of();return;}
        int span=tile;double centerX=view.x(),centerZ=view.z();
        List<int[]> windows=new ArrayList<>();for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++)windows.add(new int[]{x*span,z*span});
        windows.sort(Comparator.comparingDouble(p->Math.hypot(p[0]+span/2d-centerX,p[1]+span/2d-centerZ)));
        task=WORKER.submit(()->{
            var factory=new MinecraftWorldContextFactory();var context=factory.createDimension(seed,requestedDimension);
            CubiomesBridge.setThreadMode(SearchConfig.NativeMode.CLASSIC);
            try{
                var checker=new VanillaStructureChecker(new KeywordDictionary(KeywordDictionary.Lang.EN));List<Marker> found=new ArrayList<>();Set<String> seen=new HashSet<>();
                for(var window:windows)for(String id:ids){
                    while(paused&&revision==version)LockSupport.parkNanos(25_000_000);if(revision!=version)return;
                    int cx=window[0]+span/2,cz=window[1]+span/2;
                    List<Marker> candidates=new ArrayList<>();
                    // Let the server decide viability so approximate biome heights cannot hide real structures
                    var points=confirmer==null?checker.positions(context,cx,cz,span/32,id):checker.positionsPlacementOnly(context,cx,cz,span/32,id);
                    for(int[] point:points){
                        if(point[0]<window[0]||point[1]<window[1]||point[0]>=window[0]+span||point[1]>=window[1]+span)continue;
                        if(!seen.add(id+":"+point[0]+":"+point[1]))continue;
                        int offset=id.equals("minecraft:buried_treasure")?1:0;
                        if(found.size()+candidates.size()<8192)candidates.add(new Marker(id,point[0]+offset,point[1]+offset,false));
                    }
                    if(confirmer==null)found.addAll(candidates);
                    else for(int i=0;i<candidates.size();i+=32){
                        var future=confirmer.apply(List.copyOf(candidates.subList(i,Math.min(candidates.size(),i+32))));
                        while(!future.isDone()){if(revision!=version)return;LockSupport.parkNanos(25_000_000);}
                        try{for(var a:future.join())found.add(new Marker(a.id(),a.x(),a.z(),true,a.minX(),a.minZ(),a.maxX(),a.maxZ()));}
                        catch(java.util.concurrent.CompletionException error){org.slf4j.LoggerFactory.getLogger(StructureLayers.class).debug("Map confirmation stopped",error);}
                        publish(version,List.copyOf(found));
                    }
                    publish(version,List.copyOf(found));LockSupport.parkNanos(2_000_000);
                }
            }finally{CubiomesBridge.clearThreadMode();}
        });
    }
    private synchronized void publish(long version, List<Marker> next) { if (revision == version && !predicted.equals(next)) {predicted = next;markerVersion.incrementAndGet();} }
    @Override public synchronized void close() { revision++; if (task != null) task.cancel(false); last = ""; predicted = List.of();markerVersion.incrementAndGet(); }
}
