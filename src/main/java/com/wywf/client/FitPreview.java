package com.wywf.client;

import com.wywf.core.*;
import com.wywf.map.BiomeMapSnapshot;
import com.wywf.verify.*;
import java.util.*;
import java.util.concurrent.*;

final class FitPreview implements AutoCloseable {
    record Result(BiomeMapSnapshot map,int[] pixels){}
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{
        var t=new Thread(r,"WyWF-ConditionCoverage");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private volatile long revision;
    private volatile Result result;
    private BiomeMapSnapshot last;
    private List<VerifiedAnchor> lastAnchors=List.of();
    private Future<?> job;
    Result result(){
        return result;
    }
    synchronized void request(BiomeMapSnapshot map,ParsedQuery query,SearchConfig config,List<VerifiedAnchor> anchors){
        if(map==null||!map.complete()||map==last&&anchors.equals(lastAnchors))
            return;
        last=map;lastAnchors=List.copyOf(anchors);
        long version=++revision;if(job!=null)job.cancel(false);
        job=WORKER.submit(()->{
            int[] pixels=LocalFit.overview(map,query,config,anchors,()->revision!=version);
            if(pixels!=null&&revision==version)
                result=new Result(map,pixels);
        });
    }
    public synchronized void close(){
        revision++;
        if(job!=null)job.cancel(false);
        job=null;
        last=null;
        lastAnchors=List.of();
        result=null;
    }
}
