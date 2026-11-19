package com.wywf.core;

import java.util.*;

public record StructureTags(boolean abandoned, String spawner, int eyesMin, int eyesMax) {
    public static final StructureTags NONE=new StructureTags(false,"",-1,-1);
    public StructureTags {
        Objects.requireNonNull(spawner);
        if(eyesMin < -1 || eyesMax < -1 || eyesMin > 12 || eyesMax > 12 || eyesMin > eyesMax
                || (eyesMin == -1) != (eyesMax == -1))throw new IllegalArgumentException("Eyes must be between 0 and 12");
        if(!spawner.isEmpty()&&!Set.of("zombie","skeleton","spider","cave_spider","silverfish","blaze","magma_cube").contains(spawner))
            throw new IllegalArgumentException("Unknown spawner mob");
    }
    public boolean empty(){return equals(NONE);}
    public boolean eyes(){return eyesMin>=0;}
    public boolean implies(StructureTags required){
        return (!required.abandoned||abandoned)&&(required.spawner.isEmpty()||required.spawner.equals(spawner))
                &&(!required.eyes()||eyes()&&eyesMin>=required.eyesMin&&eyesMax<=required.eyesMax);
    }
    public static List<String> options(String target){
        if(target.equals("minecraft:village")||target.startsWith("minecraft:village_"))return List.of("abandoned");
        return switch(target){
            case "minecraft:stronghold"->List.of("eyes","spawner_silverfish");
            case "minecraft:mineshaft","minecraft:mineshaft_mesa"->List.of("spawner_cave_spider");
            case "minecraft:fortress"->List.of("spawner_blaze");
            case "minecraft:bastion","minecraft:bastion_remnant"->List.of("spawner_magma_cube");
            default->List.of();
        };
    }
    public boolean supports(String target){
        var choices=options(target);
        return (!abandoned||choices.contains("abandoned"))&&(!eyes()||choices.contains("eyes"))
                &&(spawner.isEmpty()||choices.contains("spawner_"+spawner));
    }
    public boolean selected(String id){return id.equals("abandoned")?abandoned:id.equals("eyes")?eyes():id.equals("spawner_"+spawner);}
    public StructureTags toggle(String id){
        if(id.equals("abandoned"))return new StructureTags(!abandoned,spawner,eyesMin,eyesMax);
        if(id.equals("eyes"))return new StructureTags(abandoned,spawner,eyes()?-1:1,eyes()?-1:12);
        if(id.startsWith("spawner_"))return new StructureTags(abandoned,selected(id)?"":id.substring(8),eyesMin,eyesMax);
        throw new IllegalArgumentException("Unknown structure tag");
    }
    public String suffix(){
        var values=new ArrayList<String>();
        if(abandoned)values.add("abandoned");
        if(!spawner.isEmpty())values.add("spawner_"+spawner);
        if(eyes())values.add("eyes_"+eyesMin+"_"+eyesMax);
        return values.isEmpty()?"":" tags "+String.join(",",values);
    }
    public static StructureTags parse(String text){
        var result=NONE;var seen=new HashSet<String>();
        for(String token:text.toLowerCase(Locale.ROOT).split(",",-1)){
            token=token.strip();
            String group=token.startsWith("eyes_")?"eyes":token.startsWith("spawner_")?"spawner":token;
            if(!seen.add(group))throw new IllegalArgumentException("Duplicate or conflicting structure tags");
            if(token.startsWith("eyes_")){
                var parts=token.split("_");
                if(parts.length!=3)throw new IllegalArgumentException("Use eyes_min_max");
                result=new StructureTags(result.abandoned,result.spawner,Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));
            }else result=result.toggle(token);
        }
        return result;
    }
    public static String label(String id,boolean ru){return switch(id){
        case "abandoned"->ru?"Заброшенная деревня":"Abandoned village";
        case "eyes"->ru?"Проверка оков":"Check eyes";
        case "spawner_zombie"->ru?"Спавнер зомби":"Zombie spawner";
        case "spawner_skeleton"->ru?"Спавнер скелетов":"Skeleton spawner";
        case "spawner_spider"->ru?"Спавнер пауков":"Spider spawner";
        case "spawner_cave_spider"->ru?"Спавнер пещерных пауков":"Cave spider spawner";
        case "spawner_silverfish"->ru?"Спавнер чешуйниц":"Silverfish spawner";
        case "spawner_blaze"->ru?"Спавнер ифритов":"Blaze spawner";
        case "spawner_magma_cube"->ru?"Спавнер магмовых кубов":"Magma cube spawner";
        default->throw new IllegalArgumentException("Unknown structure tag");
    };}
    public String description(boolean ru){
        var values=new ArrayList<String>();
        if(abandoned)values.add(label("abandoned",ru));
        if(!spawner.isEmpty())values.add(label("spawner_"+spawner,ru));
        if(eyes())values.add(eyesMin==eyesMax?(ru?"ровно ":"exactly ")+eyesMin+(ru?" оков в одном портале":" eyes in one portal")
                :(ru?"от ":"between ")+eyesMin+(ru?" до ":" and ")+eyesMax+(ru?" оков в одном портале":" eyes in one portal"));
        return String.join(", ",values);
    }
}
