package com.wywf.core;

import java.util.*;

public final class QueryConflicts {
    public enum Reason { ABSENCE, DISTANCE, COUNT, SPAWN, LOGIC }
    public record Conflict(Reason reason,List<ParsedQuery.Term> conditions){public Conflict{conditions=List.copyOf(conditions);}}
    private record Interval(int min,int max){}
    public static Optional<Conflict> find(ParsedQuery query,SearchConfig config){
        if(query.error()!=null||!query.ignoredWords().isEmpty()||query.terms().size()>64)return Optional.empty();
        if(query.hasLogic()&&!QueryLogic.conjunction(query.logic()))return impossibleBoolean(query);
        var terms=query.terms();
        for(int i=0;i<terms.size();i++)for(int j=i+1;j<terms.size();j++){
            var a=terms.get(i);
            var b=terms.get(j);
            if(a.category==KeywordDictionary.Category.SPAWN&&b.category==a.category&&a.modifier==Modifier.DEFAULT&&b.modifier==Modifier.DEFAULT
                    &&!a.canonical.equals(b.canonical)&&!a.canonical.equals("any_solid")&&!b.canonical.equals("any_solid"))return conflict(Reason.SPAWN,a,b);
            if(a.category!=b.category||!a.canonical.equals(b.canonical))continue;
            if(a.category!=KeywordDictionary.Category.STRUCTURE&&a.category!=KeywordDictionary.Category.BIOME)continue;
            var ai=distance(a,config);
            var bi=distance(b,config);
            if(a.modifier==Modifier.NEVER&&bi!=null&&bi.max<=radius(a,config)&&subset(b,a)
                    ||b.modifier==Modifier.NEVER&&ai!=null&&ai.max<=radius(b,config)&&subset(a,b))return conflict(Reason.ABSENCE,a,b);
            if(!Objects.equals(a.loot,b.loot)||!a.tags.equals(b.tags))continue;
            if(ai!=null&&bi!=null&&Math.max(ai.min,bi.min)>Math.min(ai.max,bi.max))return conflict(Reason.DISTANCE,a,b);
            if(a.category==KeywordDictionary.Category.STRUCTURE&&a.modifier.counted()&&b.modifier.counted()&&radius(a,config)==radius(b,config)){
                var ac=count(a);
                var bc=count(b);if(Math.max(ac.min,bc.min)>Math.min(ac.max,bc.max))return conflict(Reason.COUNT,a,b);
            }
        }
        return Optional.empty();
    }
    private static Optional<Conflict> conflict(Reason reason,ParsedQuery.Term a,ParsedQuery.Term b){return Optional.of(new Conflict(reason,List.of(a,b)));}
    private static Interval count(ParsedQuery.Term t){return new Interval(t.modifier==Modifier.AT_MOST?0:t.someCount,t.modifier==Modifier.AT_LEAST?Integer.MAX_VALUE:t.someCount);}
    private static int radius(ParsedQuery.Term t,SearchConfig c){
        if(t.category==KeywordDictionary.Category.STRUCTURE)return TargetDistance.radius(t,c.searchRadiusChunks()*16);
        boolean cave=Set.of("minecraft:deep_dark","minecraft:lush_caves","minecraft:dripstone_caves","minecraft:sulfur_caves").contains(t.canonical);
        if(cave&&t.modifier!=Modifier.BETWEEN&&t.modifier!=Modifier.FAR&&t.modifier!=Modifier.NEVER&&t.modifier!=Modifier.AT)return 200;
        return switch(t.modifier){case BETWEEN->t.betweenMax;case FAR->1000;case ONLY->500;case IN->64;case AT->128;case NEAR->200;case UNDER->0;default->c.biomeCheckRadiusChunks()*16;};
    }
    private static Interval distance(ParsedQuery.Term t,SearchConfig c){
        return switch(t.modifier){
            case DEFAULT,NEAR,IN,AT,BETWEEN,FAR->new Interval(t.modifier==Modifier.BETWEEN?t.betweenMin:t.modifier==Modifier.FAR?500:0,radius(t,c));
            default->null;
        };
    }
    private static Optional<Conflict> impossibleBoolean(ParsedQuery q){
        Map<String,Integer> bits=new LinkedHashMap<>();for(var t:q.terms())bits.computeIfAbsent(t.withPinned(false).toString(),k->bits.size());
        if(bits.size()>12)return Optional.empty();
        for(int mask=0;mask<(1<<bits.size());mask++){
            int values=mask;if(QueryLogic.evaluate(q.logic(),t->QueryLogic.Truth.of((values&(1<<bits.get(t.withPinned(false).toString())))!=0))==QueryLogic.Truth.YES)return Optional.empty();
        }
        return Optional.of(new Conflict(Reason.LOGIC,q.terms().stream().limit(6).toList()));
    }
    private static boolean subset(ParsedQuery.Term specific,ParsedQuery.Term broad){
        return (broad.loot==null||Objects.equals(specific.loot,broad.loot))&&specific.tags.implies(broad.tags);
    }
    private QueryConflicts(){}
}
