package com.wywf.core;

public record SearchScheduling(int workers,int rawCapacity,int verificationCapacity) {
    public static SearchScheduling of(SearchConfig config){
        int budget=Math.max(1,config.resolveThreadCount());
        int reserve=budget>=6?3:budget>=3?1:0;
        int workers=Math.max(1,budget-reserve);
        int target=Math.min(8,config.candidatesToCollect());
        return new SearchScheduling(workers,Math.max(2,Math.min(16,target*2+workers)),Math.max(2,Math.min(8,target+1)));
    }
}
