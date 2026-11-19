package com.wywf.core;

public final class SearchStatistics {
    private long deepRejected,serverChecked,serverRejected;

    public void deepRejected(){deepRejected=add(deepRejected,1);}
    public void serverFinished(boolean rejected){
        serverChecked=add(serverChecked,1);
        if(rejected)serverRejected=add(serverRejected,1);
    }
    public long serverChecked(){return serverChecked;}
    public long deepRejectedCount(){return deepRejected;}
    public long serverRejectedCount(){return serverRejected;}
    public long discarded(long completed,long live){return add(add(completed,live),add(deepRejected,serverRejected));}
    public static long add(long total,long delta){return total+Math.min(Long.MAX_VALUE-total,Math.max(0,delta));}
}
