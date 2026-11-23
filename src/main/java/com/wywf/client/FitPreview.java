package com.wywf.client;

import com.wywf.core.*;
import com.wywf.map.BiomeMapSnapshot;
import com.wywf.verify.*;
import java.util.*;
import java.util.concurrent.*;

final class FitPreview implements AutoCloseable {
    record Result(BiomeMapSnapshot map,int[] pixels){}
    private final MapBackground.Revision revision = new MapBackground.Revision();
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
        last=map;
        var snapshot = List.copyOf(anchors);
        lastAnchors = snapshot;
        var settings = config.copy();
        long version = revision.advance();
        result = null;
        if (job != null) job.cancel(false);
        var submitted = MapBackground.cpu(() -> {
            int[] pixels = LocalFit.overview(map, query, settings, snapshot, () -> !revision.isCurrent(version));
            if (pixels != null) revision.publish(version, () -> result = new Result(map, pixels));
        });
        job = submitted;
        submitted.whenComplete((ignored, error) -> {
            if (error != null) {
                synchronized (this) {
                    if (revision.isCurrent(version)) last = null;
                }
            }
        });
    }
    public synchronized void close(){
        revision.advance();
        if(job!=null)job.cancel(false);
        job=null;
        last=null;
        lastAnchors=List.of();
        result=null;
    }
}
