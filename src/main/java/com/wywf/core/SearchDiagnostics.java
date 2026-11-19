package com.wywf.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class SearchDiagnostics {
    public record Count(String condition,long passed,long failed) {
        public long total(){return passed+failed;}
        public double rejection(){return total()==0 ? 0 : failed/(double)total();}
    }
    private static final ThreadLocal<SearchDiagnostics> CURRENT=new ThreadLocal<>();
    private static final class Counter {final LongAdder passed=new LongAdder(),failed=new LongAdder();}
    private final Map<String,Counter> counts=new ConcurrentHashMap<>();
    public static void bind(SearchDiagnostics value){if(value==null)CURRENT.remove();else CURRENT.set(value);}
    public static void record(ParsedQuery.Term term,boolean pass){var current=CURRENT.get();if(current!=null)current.add(term.toString(),pass);}
    public void add(String condition,boolean pass){var c=counts.computeIfAbsent(condition,k->new Counter());if(pass)c.passed.increment();else c.failed.increment();}
    public List<Count> snapshot(){return counts.entrySet().stream().map(e->new Count(e.getKey(),e.getValue().passed.sum(),e.getValue().failed.sum())).sorted(Comparator.comparingDouble(Count::rejection).reversed()).toList();}
    public Optional<Count> obstacle(){return snapshot().stream().filter(c->c.failed()>=8).findFirst();}
}
