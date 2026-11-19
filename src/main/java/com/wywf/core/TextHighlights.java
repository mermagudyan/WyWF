package com.wywf.core;
import java.util.*;
public final class TextHighlights {
    public record Span(int start,int end){}
    public static List<Span> conflicts(String text,List<String> phrases){
        List<Span> found=new ArrayList<>();
        for(String phrase:phrases){if(phrase.isBlank())continue;int from=0,index;
            while((index=text.indexOf(phrase,from))>=0){int start=index;if(start>=5&&text.regionMatches(true,start-5,"with ",0,5))start-=5;found.add(new Span(start,index+phrase.length()));from=index+phrase.length();}
        }
        found.sort(Comparator.comparingInt(Span::start).thenComparingInt(Span::end));List<Span> merged=new ArrayList<>();
        for(var span:found){if(!merged.isEmpty()&&merged.getLast().end>=span.start){var old=merged.removeLast();merged.add(new Span(old.start,Math.max(old.end,span.end)));}else merged.add(span);}
        return List.copyOf(merged);
    }
    private TextHighlights(){}
}
