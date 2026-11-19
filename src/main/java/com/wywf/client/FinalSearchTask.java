package com.wywf.client;

import com.wywf.core.*;
import com.wywf.verify.*;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import java.util.*;

final class FinalSearchTask {
    private final SearchScreen owner;
    private final CreateWorldScreen creator;
    private final ParsedQuery query;
    private final SearchConfig config;
    private record Location(long seed, int x, int z) {
        Location(SearchResult result) {
            this(result.seed, result.centerX, result.centerZ);
        }
    }
    private final Deque<SearchResult> pending = new ArrayDeque<>();
    private final Set<Location> acceptedLocations = new HashSet<>();
    private boolean inputDone=true,finishRequested;
    private final CandidateQuota quota=new CandidateQuota();
    private final SearchResult recommended;
    private final SearchRun run;
    private PreviewWorld.Session current;
    private long processed;
    private long attemptStarted;
    private boolean stopping, cancelled, completed,budgetExpired;


    FinalSearchTask(SearchScreen owner, CreateWorldScreen creator, ParsedQuery query, SearchConfig config,
                    CandidateReview shortlist, SearchRun run) {
        this.owner = owner; this.creator = creator; this.query = query; this.config = config.copy();
        offer(shortlist.candidates()); recommended = shortlist.selected().orElse(null); this.run = run;
    }
    void streaming(){inputDone=false;}
    void offer(List<SearchResult> results) {
        if (finishRequested) return;
        for (var result : results) {
            var location = new Location(result);
            if (!acceptedLocations.contains(location) && pending.stream().noneMatch(r -> new Location(r).equals(location)))
                pending.addLast(result);
        }
    }
    void inputDone(){
        inputDone=true;
    }
    int ready(){
        return quota.size();
    }
    int queued(){
        return pending.size();
    }
    boolean canAccept(){
        return !finishRequested&&!completed&&!cancelled&&!budgetExpired&&!quota.retryBlocked()
            &&queued()<SearchScheduling.of(config).verificationCapacity();
    }
    boolean verifying(){
        return current!=null;
    }
    int remainingTarget(){
        return Math.max(1,quota.remaining(config,run==null?0:run.elapsedMs()));
    }
    void showReady(){
        if(quota.size()==0)return;
        finishRequested=true;
        inputDone=true;
        if(current!=null&&!stopping){
            current.cancel();
            stopping=true;
        }
        discardWaiting();
    }
    private void discardWaiting() {
        int keep = current != null || stopping ? 1 : 0;
        while (pending.size() > keep) pending.removeLast();
    }
    public void tick() {
        if (cancelled || completed) return;
        if(!budgetExpired&&run!=null&&run.remainingTimeMs(config)==0){
            budgetExpired=true;
            owner.stopInputForDeadline();
            inputDone=true;
            failed(new SearchFailure(current==null?0:current.result.seed,SearchFailure.Kind.ERROR,"Overall search time limit reached",List.of(),List.of()));
            discardWaiting();
            if(current!=null&&!stopping){
                current.cancel();
                stopping=true;
            }
        }
        if (stopping) {
            if (PreviewWorld.active() != null)
                return;
            var checked = pending.removeFirst();
            processed++;
            if (current != null && PreviewWorld.find(current.result, query, config) == current) {
                quota.accept(current.result());
                acceptedLocations.add(new Location(checked));
            }
            stopping = false;
            current = null;
        }
        if (!config.serverVerification()) acceptWithoutServer();
        if(run!=null&&!finishRequested&&(quota.remaining(config,run.elapsedMs())==0||quota.retryBlocked())){
            owner.stopInputForDeadline();
            finishRequested=!quota.retryBlocked();
            inputDone=true;
            discardWaiting();
        }
        if (pending.isEmpty()) {
            if(!inputDone)
                return;
            if(quota.shouldContinue(config,run,finishRequested)){
                inputDone=false;
                owner.retrySearch();
                return;
            }
            completed = true;
            var accepted=new ArrayList<>(quota.candidates());
            boolean rankAt = !query.hasAlternatives() && query.terms().stream().anyMatch(t ->
                    t.modifier == Modifier.AT && t.category == KeywordDictionary.Category.STRUCTURE);
            boolean verify = config.serverVerification();
            if (verify && (rankAt || config.sortCandidatesByDistance())) accepted.sort(Comparator.comparingDouble(this::atDistance));
            var selected = verify && rankAt && !accepted.isEmpty() ? accepted.getFirst() : accepted.contains(recommended) ? recommended : null;
            var review = new CandidateReview(accepted, selected, !verify && (rankAt || config.sortCandidatesByDistance()));
            String reason = verify ? MapTexts.text("finalizing_done", accepted.size(), processed)
                    : ResultTexts.text("offline_help");
            if(!finishRequested&&quota.remaining(config,run==null?0:run.elapsedMs())>0)
                reason=quota.failure().map(SearchFailure::note).filter(note->!note.isBlank()).orElse(owner.batchStopReason());
            owner.finishSearch(review,reason);
            return;
        }
        if (!config.serverVerification())
            return;
        if (current == null) {
            if (PreviewWorld.active() != null)
                return;
            if (OnlineSearchBenchmark.rankCandidates())
                promoteLikelyCandidate();
            attemptStarted = System.nanoTime();
            try {
                current = PreviewWorld.begin(creator, pending.getFirst(), query, config, owner,owner.diagnostics());
            }
            catch (Exception error) {
                org.slf4j.LoggerFactory.getLogger("wywf-search").warn("[vanilla-final] startup failed",error);
                failed(new SearchFailure(pending.getFirst().seed,SearchFailure.Kind.STARTUP,error.toString(),List.of(),List.of()));
                PreviewWorld.shutdownActive(); stopping = true;
            }
            return;
        }
        var result = current.summary();
        if (!result.terminal())
            return;
        OnlineSearchBenchmark.verified(owner, current.result.seed, result, System.nanoTime() - attemptStarted, current);
        if (WyWFDebug.ENABLED) {
            var log = org.slf4j.LoggerFactory.getLogger("wywf-search");
            log.info("[vanilla-final] seed {} -> {} ({})", current.result.seed, result.status(), result.note());
            result.evidence().forEach(line -> log.info("[vanilla-final] {}", line));
        }
        if(result.status()==VerificationSummary
                .Status.PASS||result.status()==VerificationSummary
                .Status.FAIL||result.status()==VerificationSummary
                .Status.PARTIAL)
            owner.serverFinished(result.status()!=VerificationSummary.Status.PASS);
        if (result.status() == VerificationSummary.Status.PASS) {
            current.park();
        } else {
            var kind=switch(result.status()){
                case FAIL->SearchFailure.Kind.CONDITION;
                case PARTIAL->SearchFailure.Kind.PARTIAL;
                case CANCELLED->SearchFailure.Kind.CANCELLED;
                default->SearchFailure.Kind.ERROR;
            };
            var conditions=query.terms().stream().map(Object::toString).filter(term->result.evidence().stream().anyMatch(line->
                    line.equals(term+": false")||line.equals(term+": FAIL")||line.equals(term+": PARTIAL"))).toList();
            failed(new SearchFailure(current.result.seed,kind,result.note(),result.evidence(),conditions));
            current.cancel();
        }
        stopping = true;
    }
    private void acceptWithoutServer() {
        int remaining = quota.remaining(config, run == null ? 0 : run.elapsedMs());
        while (remaining > 0 && !pending.isEmpty()) {
            var result = pending.removeFirst();
            if (acceptedLocations.add(new Location(result))) {
                quota.accept(result);
                remaining--;
            }
        }
    }

    private void promoteLikelyCandidate() {
        if (query.hasAlternatives() || pending.size() < 2)
            return;
        SearchResult first = pending.getFirst(), best = first;
        int bestScore = -1;
        for (var candidate : pending) {
            int score = 0;
            for (var term : query.terms()) {
                if (term.category == KeywordDictionary.Category.STRUCTURE
                        && (term.modifier == Modifier.BETWEEN || term.modifier == Modifier.SOME)) {
                    int count = 0;
                    for (String key : candidate.structurePositions.keySet()) {
                        if (key.equals(term.canonical) || key.startsWith(term.canonical + "#")) count++;
                    }
                    score += Math.min(term.modifier == Modifier.SOME ? term.someCount : 1, count);
                }
            }
            if (score > bestScore) {
                best = candidate; bestScore = score;
            }
        }
        if (best != first) {
            pending.remove(best);
            pending.addFirst(best);
        }
    }
    private void failed(SearchFailure failure){
        quota.reject(failure);owner.failure(failure);
    }
    private double atDistance(SearchResult result) {
        var saved = PreviewWorld.find(result, query, config);
        if (saved == null) return Double.POSITIVE_INFINITY;
        boolean rankAt=query.terms().stream().anyMatch(t->t.modifier==Modifier.AT&&t.category==KeywordDictionary.Category.STRUCTURE);
        double score=rankAt?0:Double.POSITIVE_INFINITY;
        for (var term : query.terms()) {
            if(term.category!=KeywordDictionary.Category.STRUCTURE||rankAt&&term.modifier!=Modifier.AT)continue;
            double nearest = Double.POSITIVE_INFINITY;
            for (var anchor : saved.anchors) if (anchor.id().equals(term.canonical))
                nearest = Math.min(nearest, Math.hypot(
                        Math.max(0, Math.max(anchor.minX() - DimensionSpace.of(anchor.id()).coordinate(result.centerX), DimensionSpace.of(anchor.id()).coordinate(result.centerX) - anchor.maxX())),
                        Math.max(0, Math.max(anchor.minZ() - DimensionSpace.of(anchor.id()).coordinate(result.centerZ), DimensionSpace.of(anchor.id()).coordinate(result.centerZ) - anchor.maxZ()))));
            score=rankAt?score+nearest:Math.min(score,nearest);
        }
        return score;
    }
    void cancel() {
        cancelled = true;
        if (current != null)
            current.cancel();
    }
}
