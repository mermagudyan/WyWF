package com.wywf.core;

import java.util.List;

public record SearchFailure(long seed,Kind kind,String note,List<String> evidence,List<String> conditions) {
    public enum Kind { CONDITION, PARTIAL, ERROR, CANCELLED, STARTUP }
    public SearchFailure {
        note=note==null?"":note;
        evidence=List.copyOf(evidence);
        conditions=List.copyOf(conditions);
    }
    public boolean infrastructure(){return kind==Kind.ERROR||kind==Kind.CANCELLED||kind==Kind.STARTUP;}
}
