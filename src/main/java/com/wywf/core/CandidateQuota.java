package com.wywf.core;

import java.util.*;

public final class CandidateQuota {
    private record Key(long seed,int x,int z){}
    private final Map<Key,SearchResult> accepted=new LinkedHashMap<>();
    private SearchFailure failure;
    private int infrastructureFailures;
    public void accept(SearchResult result){
        accepted.putIfAbsent(new Key(result.seed,result.centerX,result.centerZ),result);
        infrastructureFailures=0;
    }
    public void reject(SearchFailure value){
        failure=Objects.requireNonNull(value);
        infrastructureFailures=value.infrastructure()?infrastructureFailures+1:0;
    }
    public int size(){return accepted.size();}
    public List<SearchResult> candidates(){return List.copyOf(accepted.values());}
    public Optional<SearchFailure> failure(){return Optional.ofNullable(failure);}
    public int remaining(SearchConfig config,long elapsedMs){return Math.max(0,config.effectiveCandidateTarget(elapsedMs)-size());}
    public boolean retryBlocked(){return infrastructureFailures>=3;}
    public boolean shouldContinue(SearchConfig config,SearchRun run,boolean finishRequested){
        return !finishRequested&&run!=null&&!retryBlocked()&&remaining(config,run.elapsedMs())>0&&run.canContinue(config);
    }
}
