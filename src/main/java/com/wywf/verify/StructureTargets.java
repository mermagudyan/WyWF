package com.wywf.verify;

import com.wywf.core.KeywordDictionary;
import java.util.*;

final class StructureTargets {
    static Set<String> resolve(String canonical,KeywordDictionary dictionary,boolean registered){
        var variants=dictionary.getVariants(canonical);
        if(registered&&!variants.contains(canonical))return Set.of(canonical);
        Set<String> targets=new HashSet<>(variants);targets.add(canonical);return Set.copyOf(targets);
    }
    private StructureTargets(){}
}
