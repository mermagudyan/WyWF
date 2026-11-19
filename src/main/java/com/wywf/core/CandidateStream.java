package com.wywf.core;

import java.util.*;
import java.util.function.BooleanSupplier;

public final class CandidateStream {
    private final int capacity;
    private final int reserve;
    private final ArrayDeque<SearchResult> queue=new ArrayDeque<>();
    private boolean finished;
    private volatile boolean closed;
    private int workerBudget=Integer.MAX_VALUE;
    private boolean backedUp;
    private volatile int workers=Integer.MAX_VALUE;
    public synchronized void workers(int count) {
        workers(count, false);
    }
    public synchronized void workers(int count, boolean verificationBacklog) {
        workerBudget = Math.max(1, count);
        backedUp = verificationBacklog;
        updateWorkers();
    }
    private void updateWorkers() {
        int allowed = reserve > 0 && (backedUp || queue.size() >= capacity)
                ? Math.min(workerBudget, reserve) : workerBudget;
        if (workers != allowed) {
            workers = allowed;
            notifyAll();
        }
    }
    public int workers(){return workers;}
    public boolean awaitWorker(int index,BooleanSupplier cancelled){
        if(index<workers&&!closed)return !cancelled.getAsBoolean();
        synchronized(this){try{while(index>=workers&&!closed&&!cancelled.getAsBoolean())wait(50);return !closed&&!cancelled.getAsBoolean();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}}
    }
    public CandidateStream(int capacity) { this(capacity, 0); }
    public CandidateStream(int capacity, int reserve) {
        if (capacity < 1 || reserve < 0 || capacity > Integer.MAX_VALUE - reserve)
            throw new IllegalArgumentException("Invalid candidate capacity");
        this.capacity = capacity;
        this.reserve = reserve;
    }
    public boolean put(SearchResult result, BooleanSupplier cancelled) {
        return put(result, cancelled, false);
    }

    public synchronized boolean put(SearchResult result, BooleanSupplier cancelled, boolean preferred) {
        try{
            while(full()&&!closed&&!finished&&!cancelled.getAsBoolean())wait(50);
            if(closed||finished||cancelled.getAsBoolean())return false;
            if (preferred) queue.addFirst(result);
            else queue.addLast(result);
            updateWorkers();
            notifyAll();
            return true;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
    }
    public synchronized SearchResult poll() {
        var next = queue.pollFirst();
        if (next != null) {
            updateWorkers();
            notifyAll();
        }
        return next;
    }
    public synchronized int size(){return queue.size();}
    public synchronized boolean full(){return queue.size() >= capacity + reserve;}
    public synchronized boolean closed(){return closed;}
    public synchronized void finish(){finished=true;notifyAll();}
    public synchronized void close(){closed=true;queue.clear();notifyAll();}
}
