package com.wywf.search;

import com.wywf.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SeedSearcher {

    private static final Logger LOGGER = LoggerFactory.getLogger("wywf-search");

    private final WorldContextFactory contextFactory;
    private final StructureChecker    structureChecker;
    private final BiomeChecker        biomeChecker;
    private final KeywordDictionary   dictionary;

    private ExecutorService pool;
    private volatile RunState state = new RunState();
    private final AtomicLong revision = new AtomicLong();

    private static final class RunState {
        final AtomicBoolean running = new AtomicBoolean();
        final AtomicLong cursor = new AtomicLong();
        final SearchProgress progress = new SearchProgress();
        final List<SearchResult> candidates = Collections.synchronizedList(new ArrayList<>());
        final SearchDiagnostics diagnostics = new SearchDiagnostics();
        volatile SearchConfig config;
        volatile CandidateStream stream;
        volatile boolean cancelledByUser;
        volatile String stopReason;
        volatile Set<Long> batchSeeds = Set.of();
    }

    public SearchResult pollCandidate() {
        var input = state.stream;
        return input == null ? null : input.poll();
    }

    public boolean waitingForVerification() {
        var input = state.stream;
        return input != null && input.full();
    }

    public int queuedCandidates() {
        var current = state;
        var input = current.stream;
        return input == null ? current.candidates.size() : input.size();
    }

    public void verificationLoad(boolean active, boolean backedUp) {
        var current = state;
        var input = current.stream;
        var cfg = current.config;
        if (input != null && cfg != null) {
            int budget = MapBackground.cpuAllowance(cfg.resolveThreadCount());
            if (active) budget = Math.min(budget, SearchScheduling.of(cfg).workers());
            input.workers(budget, backedUp);
        }
    }

    public int activeWorkers() {
        var current = state;
        int active = current.progress.snapshot().threads();
        return current.stream == null ? active : Math.min(current.stream.workers(), active);
    }

    public SearchDiagnostics diagnostics() { return state.diagnostics; }

    public boolean verifyCandidate(ParsedQuery query, SearchConfig config, SearchResult result,
                                   java.util.function.BooleanSupplier cancelled) {
        CubiomesBridge.setThreadMode(config.nativeMode());
        var checkedQuery = config.serverVerification() ? query.estimate() : query;
        try {
            return !DeepVerifier.survivors(contextFactory, structureChecker, checkedQuery, config,
                    List.of(result), config.biomeCheckRadiusChunks(), cancelled).isEmpty();
        } finally {
            CubiomesBridge.clearThreadMode();
            CubiomesBridge.destroyCurrentGenerator();
        }
    }

    public Set<Long> batchSeeds() { return state.batchSeeds; }

    public SeedSearcher(WorldContextFactory contextFactory, KeywordDictionary dictionary) {
        this.contextFactory   = contextFactory;
        this.dictionary       = dictionary;
        this.structureChecker = new VanillaStructureChecker(dictionary);
        this.biomeChecker     = new VanillaBiomeChecker();
    }

    public SearchProgress progress() { return state.progress; }

    public List<SearchResult> candidates() { return state.candidates; }

    public boolean isRunning() { return state.running.get(); }

    public String stopReason() { return state.stopReason; }

    public synchronized void start(ParsedQuery rawQuery, SearchConfig config, Consumer<SearchResult> onFound) {
        start(rawQuery, config, new SearchRun(), onFound);
    }

    public synchronized void start(ParsedQuery rawQuery,SearchConfig config,SearchRun run,Consumer<SearchResult> onFound){
        start(rawQuery,config,run,onFound,true);
    }
    public synchronized void startStreaming(ParsedQuery query,SearchConfig config,SearchRun run,Consumer<SearchResult> done){
        start(query,config,run,done,false);
    }
    private synchronized void start(ParsedQuery rawQuery,SearchConfig config,SearchRun run,Consumer<SearchResult> onFound,boolean denseAtEnd){
        if (isRunning()) throw new IllegalStateException("Search already running");
        final long request = revision.incrementAndGet();
        var current = new RunState();
        var running = current.running;
        var progress = current.progress;
        var candidates = current.candidates;
        final CandidateStream requestStream = denseAtEnd ? null
                : new CandidateStream(SearchScheduling.of(config).rawCapacity(), Math.min(2, config.resolveThreadCount()));
        current.stream = requestStream;
        state = current;
        if (rawQuery.place() != null) {
            startPlace(current, request, rawQuery, config.copy(), run, onFound);
            return;
        }
        Set<Long> excluded = run.excluded();
        long remainingTime = run.remainingTimeMs(config);
        long remainingSeeds = run.remainingSeeds(config);
        var available = filterToAvailable(rawQuery);
        ParsedQuery query = config.serverVerification() ? available.estimate() : available;

        if (query.isEmpty()) {
            if (WyWFDebug.ENABLED) LOGGER.info("No searchable terms in query \"{}\" — nothing to search, aborting", rawQuery.raw());
            String reason = rawQuery.ignoredWords().isEmpty()
                    ? "query is empty — no recognized keywords"
                    : "all keywords not available in this game version: " + String.join(", ", rawQuery.ignoredWords());
            SearchResult emptyResult = new SearchResult(
                    0, 0, 0, 0, 0, null, List.of(), List.of(), reason, Map.of(), Map.of());
            onFound.accept(emptyResult);
            return;
        }

        final SearchConfig searchCfg = config.copy();
        int threadCount = searchCfg.resolveThreadCount();
        current.config = searchCfg;
        if (requestStream != null) requestStream.workers(MapBackground.cpuAllowance(threadCount));
        running.set(true);
        progress.start(threadCount);
        SeedValidator.resetSpawnStats();
        SearchWorker.resetGateStats();

        if (WyWFDebug.ENABLED) {
            LOGGER.info("===== Starting seed search =====");
            LOGGER.info("Native mode: {}", searchCfg.nativeMode());
        }
        CubiomesBridge.setMode(searchCfg.nativeMode());
        // Fail FAST and clearly when NATIVE was demanded but the accelerator
        // is missing, instead of poisoning every seed with deep exceptions.
        if (!CubiomesBridge.isAvailable()) {
            String reason;
            boolean abort = false;
            if (searchCfg.nativeMode() == SearchConfig.NativeMode.NATIVE) {
                reason = "NATIVE mode is on but the cubiomes DLL failed to load - "
                        + "switch Native mode to AUTO or CLASSIC in settings";
                abort = true;
            } else if (searchCfg.nativeMode() == SearchConfig.NativeMode.AUTO) {
                reason = "accelerator DLL not loaded - running in slow Java mode "
                        + "(see 'Accelerator' row during the search)";
            } else {
                reason = null;
            }
            if (reason != null && WyWFDebug.ENABLED) LOGGER.warn("[SeedSearcher] {}", reason);
            if (abort) {
                // NATIVE demanded but unavailable: fail fast instead of
                // discarding every seed with deep exceptions.
                SearchResult failResult = new SearchResult(0, 0, 0, 0, 0,
                        null, List.of(), List.of(), reason, Map.of(), Map.of());
                running.set(false);
                progress.finish();
                onFound.accept(failResult);
                return;
            }
        }
        if (WyWFDebug.ENABLED) {
            LOGGER.info("Query: \"{}\"", query.raw());
            LOGGER.info("Terms: {}", query.terms().isEmpty() ? "(none)" : query.terms());
            LOGGER.info("Looking for structures: {}", query.structures().isEmpty() ? "(none)" : query.structures());
            LOGGER.info("Looking for biomes: {}",     query.biomes().isEmpty()     ? "(none)" : query.biomes());
            LOGGER.info("Looking for objects: {}",   query.objects().isEmpty()    ? "(none)" : query.objects());
            if (!query.ignoredWords().isEmpty()) {
                LOGGER.info("Ignored words (not recognized as biome/structure/block): {}", query.ignoredWords());
            }
            LOGGER.info("Threads: {}, structure radius: {} chunks, biome radius: {} chunks (step {}), limit: {}",
                    threadCount, searchCfg.searchRadiusChunks(), searchCfg.biomeCheckRadiusChunks(),
                    searchCfg.biomeSampleStepChunks(),
                    searchCfg.infiniteSeeds() ? "unlimited" : searchCfg.maxSeedsToCheck() + " seeds");
        }

        if (biomeChecker instanceof VanillaBiomeChecker vbc) {
            vbc.stepChunks(searchCfg.biomeSampleStepChunks());
        }

        SeedValidator validator = new SeedValidator(
                structureChecker, biomeChecker,
                searchCfg.searchRadiusChunks(), searchCfg.biomeCheckRadiusChunks()
        ).withQuery(query);

        var threadNum = new java.util.concurrent.atomic.AtomicInteger(0);
        pool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "WYWF-Search-" + threadNum.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        final AtomicBoolean requestRunning = running;
        final ExecutorService requestPool = pool;
        List<Future<?>> fs = new ArrayList<>(threadCount);
        long startOffset = config.randomizeStart()
                ? java.util.concurrent.ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFFFL
                : 0L;
        for (int i = 0; i < threadCount; i++) {
            SearchWorker worker = new SearchWorker(
                    i, threadCount, query,
                    contextFactory, validator, progress,
                    running, current.cursor, startOffset, candidates,
                    searchCfg
            );
            worker.exclude(excluded);
            worker.stream(requestStream);
            fs.add(pool.submit(() -> {
                try {
                    SearchDiagnostics.bind(current.diagnostics);
                    worker.run();
                } finally {
                    SearchDiagnostics.bind(null);
                    CubiomesBridge.destroyCurrentGenerator();
                }
            }));
        }

        Thread monitor = new Thread(() -> {
            long lastChecked = 0;
            long lastDiscarded = 0;
            long lastTime = System.currentTimeMillis();
            int lastTarget = searchCfg.candidatesToCollect();
            long searchStartTime = lastTime;
            long timeLimitMs = remainingTime;
            long maxSeeds = remainingSeeds;
            while (requestRunning.get() && revision.get() == request) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    return;
                }
                if (!requestRunning.get() || revision.get() != request) return;
                SearchProgress.Snapshot s = progress.snapshot();
                long now = System.currentTimeMillis();
                long dChecked = s.checkedSeeds() - lastChecked;
                long dDiscarded = s.discardedSeeds() - lastDiscarded;
                double checkedPerSec = dChecked * 1000.0 / Math.max(1, now - lastTime);
                double discardedPerSec = dDiscarded * 1000.0 / Math.max(1, now - lastTime);
                if (WyWFDebug.ENABLED) LOGGER.info("[progress] checked {} (~{}/sec), discarded {} (~{}/sec), candidates {}, elapsed {} ms; {} {}",
                        s.checkedSeeds(), Math.round(checkedPerSec),
                        s.discardedSeeds(), Math.round(discardedPerSec),
                        candidates.size(), s.elapsedMs(),
                        SeedValidator.spawnStats(), SearchWorker.gateStats());
                lastDiscarded = s.discardedSeeds();
                int target = searchCfg.effectiveCandidateTarget(s.elapsedMs());
                if (target < lastTarget) {
                    if (WyWFDebug.ENABLED) LOGGER.info("[progress] query is slow — collected {} of {} candidates, ramping target down to {}",
                            candidates.size(), searchCfg.candidatesToCollect(), target);
                    lastTarget = target;
                }
                if (requestStream==null && !candidates.isEmpty() && candidates.size() >= target) {
                    current.stopReason = "collected " + candidates.size() + " candidate(s) (target " + target + ")";
                    if (WyWFDebug.ENABLED) LOGGER.warn("[SeedSearcher] Stopping: {}", current.stopReason);
                    requestRunning.set(false);
                }
                if (timeLimitMs > 0 && (now - searchStartTime) >= timeLimitMs) {
                    current.stopReason = "time limit reached (" + searchCfg.timeLimitMinutes() + " min)";
                    if (WyWFDebug.ENABLED) LOGGER.warn("[SeedSearcher] Stopping: {}", current.stopReason);
                    requestRunning.set(false);
                }
                if (s.checkedSeeds() >= maxSeeds) {
                    current.stopReason = "seed limit reached (" + formatNumber(maxSeeds) + " seeds)";
                    if (WyWFDebug.ENABLED) LOGGER.warn("[SeedSearcher] Stopping: {}", current.stopReason);
                    requestRunning.set(false);
                }
                lastChecked = s.checkedSeeds();
                lastTime = now;
            }
        }, "WYWF-Search-Monitor");
        monitor.setDaemon(true);
        monitor.start();

        // Run completion off the worker pool to avoid starvation (pool size N workers + 1 task)
        Thread completionThread = new Thread(() -> {
            try {
                for (Future<?> f : fs) {
                    try { f.get(); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        LOGGER.error("[SeedSearcher] Worker future threw exception", e);
                    }
                }
            } catch (Throwable t) {
                LOGGER.error("[SeedSearcher] Unexpected error while waiting for workers", t);
            }
            shutdownPool(requestPool);
            if (revision.get() != request) return;
            requestRunning.set(false);
            progress.finish();

            if(requestStream!=null){
                requestStream.finish();
                current.batchSeeds=Set.of();
                if(!current.cancelledByUser&&revision.get()==request)onFound.accept(null);
                return;
            }
            SearchProgress.Snapshot snap;
            synchronized (candidates) {
                snap = progress.snapshot();
            }
            int poolSize;
            synchronized (candidates) {
                poolSize = candidates.size();
            }

            List<SearchResult> snapshot;
            synchronized (candidates) { snapshot = List.copyOf(candidates); }
            if (current.cancelledByUser || revision.get() != request) return;
            current.batchSeeds = Set.copyOf(snapshot.stream().map(result -> result.seed).toList());
            if (current.stopReason == null && !snapshot.isEmpty()) current.stopReason = "collected " + snapshot.size() + " candidate(s)";
            List<SearchResult> survivors=denseAtEnd ? DeepVerifier.survivors(contextFactory,structureChecker,query,searchCfg,
                    snapshot,searchCfg.biomeCheckRadiusChunks(),()->current.cancelledByUser||revision.get()!=request):snapshot;
            if (current.cancelledByUser || revision.get() != request) return;
            synchronized (candidates) { candidates.clear(); candidates.addAll(survivors); }
            SearchResult chosen = survivors.isEmpty() ? null : searchCfg.sortCandidatesByDistance()
                    ? survivors.stream().min(Comparator.comparingDouble(SearchResult::distanceToStructure)).orElseThrow()
                    : survivors.getFirst();
            if (chosen != null) {
                String reason = current.stopReason != null ? current.stopReason
                        : (snap.finished() ? "search complete" : "candidate found");
                SearchResult finalResult = new SearchResult(
                        chosen.seed, chosen.centerX, chosen.centerZ,
                        chosen.structureX, chosen.structureZ,
                        chosen.primaryDescription, chosen.matchedStructures,
                        chosen.matchedBiomes, reason, chosen.structurePositions,
                        chosen.biomeDistances);
                StringBuilder distInfo = new StringBuilder();
                for (var entry : chosen.structurePositions.entrySet()) {
                    int[] pos = entry.getValue();
                    double dx = pos[0] - chosen.centerX;
                    double dz = pos[1] - chosen.centerZ;
                    int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
                    if (!distInfo.isEmpty()) distInfo.append(", ");
                    distInfo.append(entry.getKey()).append(" ~").append(dist).append(" blocks");
                }
                for (var entry : chosen.biomeDistances.entrySet()) {
                    if (!distInfo.isEmpty()) distInfo.append(", ");
                    distInfo.append(entry.getKey()).append(" ~").append(entry.getValue()).append(" blocks");
                }
                if (WyWFDebug.ENABLED) {
                    LOGGER.info("===== Search finished: seed {} chosen from {} candidate(s) after {} checked seeds ({} ms, ~{} seeds/sec) native={} =====",
                            chosen.seed, poolSize, snap.checkedSeeds(), snap.elapsedMs(),
                            snap.elapsedMs() > 0 ? Math.round(snap.checkedSeeds() * 1000.0 / snap.elapsedMs()) : 0,
                            searchCfg.nativeMode());
                    LOGGER.info("  origin ({}, {}), distances: {}", chosen.centerX, chosen.centerZ, distInfo);
                }
                if (!current.cancelledByUser && revision.get() == request) onFound.accept(finalResult);
            } else {
                if (WyWFDebug.ENABLED) LOGGER.warn("===== Search finished: no matching seed found. Checked {} seeds ({} ms, ~{} seeds/sec) native={} =====",
                        snap.checkedSeeds(), snap.elapsedMs(),
                        snap.elapsedMs() > 0 ? Math.round(snap.checkedSeeds() * 1000.0 / snap.elapsedMs()) : 0,
                        searchCfg.nativeMode());
                if (!current.cancelledByUser && revision.get() == request) onFound.accept(null);
            }
            CubiomesBridge.destroyCurrentGenerator();
        }, "WYWF-Search-Completion");
        completionThread.setDaemon(true);
        completionThread.start();
    }

    private ParsedQuery filterToAvailable(ParsedQuery q) {
        if (q.hasLogic() || q.error() != null) return q;
        List<ParsedQuery.Term> kept = new ArrayList<>(q.terms().size());
        boolean changed = false;

        for (ParsedQuery.Term term : q.terms()) {
            switch (term.category) {
                case STRUCTURE -> {
                    boolean any = isStructureUsable(term.canonical);
                    if (!any) {
                        for (String realId : dictionary.getVariants(term.canonical)) {
                            if (isStructureUsable(realId)) { any = true; break; }
                        }
                    }
                    if (any) {
                        kept.add(term);
                    } else {
                        changed = true;
                        LOGGER.info("Structure '{}' is not present in this game version — ignoring keyword", term.canonical);
                    }
                }
                case BIOME -> {
                    if (contextFactory.isBiomeAvailable(term.canonical)) {
                        kept.add(term);
                    } else {
                        changed = true;
                        LOGGER.info("Biome '{}' is not present in this game version — ignoring keyword", term.canonical);
                    }
                }
                case OBJECT -> {
                    changed = true;
                    LOGGER.info("Object '{}' cannot be searched offline yet — ignoring keyword", term.canonical);
                }
                default -> kept.add(term);
            }
        }

        if (!changed) {
            return q;
        }
        return new ParsedQuery(q.raw(), kept);
    }

    // Usable when present in either the Structure or the StructureSet registry
    private boolean isStructureUsable(String id) {
        return contextFactory.isStructureAvailable(id) || contextFactory.isStructureSetAvailable(id);
    }

    private void startPlace(RunState current, long request, ParsedQuery original, SearchConfig config, SearchRun run, Consumer<SearchResult> done) {
        var flag = current.running;
        var candidates = current.candidates;
        var progress = current.progress;
        flag.set(true);
        progress.start(1);
        current.config = config;
        var place=original.place();
        var query = config.serverVerification() ? original.estimate() : original;
        var input = current.stream;
        var task=new Thread(()->{
            CubiomesBridge.setThreadMode(config.nativeMode());
            SearchDiagnostics.bind(current.diagnostics);
            try{
                var context=contextFactory.create(place.seed(),query.structures().contains("minecraft:stronghold"));
                if(CubiomesBridge.isActive())CubiomesBridge.applySeed(place.seed());
                var validator=new SeedValidator(structureChecker,biomeChecker,config.searchRadiusChunks(),config.biomeCheckRadiusChunks()).withQuery(query);
                long started=System.currentTimeMillis(),limit=run.remainingSeeds(config),time=run.remainingTimeMs(config);
                while(flag.get()&&revision.get()==request){
                    if(progress.snapshot().checkedSeeds()>=limit||System.currentTimeMillis()-started>=time){current.stopReason="FTP search limit reached";break;}
                    long index=run.nextPlace();if(index>=place.cells()){current.stopReason="FTP region exhausted";break;}
                    var p=place.position(index);if(!place.contains(p[0],p[1]))continue;
                    var outcome=validator.validate(context,query,p[0],p[1]);
                    progress.onSeedChecked();
                    if(!outcome.accepted)progress.onSeedDiscarded();
                    if(outcome.accepted){
                        if(input!=null){input.put(outcome.result,()->!flag.get()||revision.get()!=request);continue;}
                        synchronized(candidates){candidates.add(outcome.result);}
                        if(candidates.size()>=config.effectiveCandidateTarget(progress.snapshot().elapsedMs())){current.stopReason="collected "+candidates.size()+" FTP places";break;}}
                }
                if(current.cancelledByUser||revision.get()!=request)return;
                flag.set(false);
                progress.finish();
                current.batchSeeds=Set.of(place.seed());if(input!=null)input.finish();
                done.accept(candidates.isEmpty()?null:candidates.getFirst());
            }catch(Throwable error){
                if(WyWFDebug.ENABLED)LOGGER.warn("FTP search failed",error);
                flag.set(false);
                progress.finish();if(input!=null)input.finish();current.stopReason="FTP could not evaluate this generator";
                if(!current.cancelledByUser&&revision.get()==request)done.accept(null);
            }finally{
                SearchDiagnostics.bind(null);
                CubiomesBridge.clearThreadMode();
                CubiomesBridge.destroyCurrentGenerator();
            }
        },"WyWF-FindThisPlace");
        task.setDaemon(true);
        task.setPriority(Thread.MIN_PRIORITY);
        task.start();
    }
    public long screenedSeeds() {
        return state.cursor.get();
    }

    public synchronized void cancel() {
        revision.incrementAndGet();
        var current = state;
        current.cancelledByUser = true;
        current.running.set(false);
        if (current.stream != null) current.stream.close();
        current.progress.finish();
        if (WyWFDebug.ENABLED) LOGGER.warn("[SeedSearcher] Search cancelled by user");
        shutdownPool();
    }

    private void shutdownPool() { shutdownPool(pool); }

    private synchronized void shutdownPool(ExecutorService p) {
        if (p == null) return;
        if (pool == p) pool = null;
        p.shutdownNow();
        // Await termination off the calling thread: cancel() runs on the render
        // thread and must not stall the UI while workers unwind long scans.
        Thread reaper = new Thread(() -> {
            try {
                if (!p.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("[SeedSearcher] search workers did not terminate in time");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            CubiomesBridge.destroyCurrentGenerator();
        }, "WYWF-Pool-Reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    private static String formatNumber(long n) {
        if (n < 1_000) return Long.toString(n);
        if (n < 1_000_000) return String.format("%,d", n).replace(',', ' ');
        if (n < 1_000_000_000) return String.format("%.1fM", n / 1_000_000.0);
        return String.format("%.1fB", n / 1_000_000_000.0);
    }
}
