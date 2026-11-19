package com.wywf.search;

import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.biome.Biome;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class StructureBiomeRules {
    private static final Map<String,Optional<Set<ResourceKey<Biome>>>> CACHE=new ConcurrentHashMap<>();
    public static Set<ResourceKey<Biome>> resolve(HolderSet<Biome> set) {
        try {Set<ResourceKey<Biome>> keys=new HashSet<>();for(var biome:set){var key=biome.unwrapKey();if(key.isEmpty())return null;keys.add(key.get());}if(!keys.isEmpty())return Set.copyOf(keys);}
        catch(RuntimeException ignored){}
        return set.unwrapKey().map(tag->CACHE.computeIfAbsent(tag.location().toString(),id->Optional.ofNullable(read(id,new HashSet<>()))).orElse(null)).orElse(null);
    }
    private static Set<ResourceKey<Biome>> read(String tag,Set<String> visiting) {
        if(!visiting.add(tag))return null;var id=Identifier.tryParse(tag);if(id==null)return null;
        try(var stream=StructureBiomeRules.class.getResourceAsStream("/data/"+id.getNamespace()+"/tags/worldgen/biome/"+id.getPath()+".json")){
            if(stream==null)return null;var object=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            Set<ResourceKey<Biome>> result=new HashSet<>();
            for(var value:object.getAsJsonArray("values")){
                String name=value.isJsonPrimitive()?value.getAsString():value.getAsJsonObject().get("id").getAsString();
                if(name.startsWith("#")){var nested=read(name.substring(1),visiting);if(nested==null)return null;result.addAll(nested);}
                else{var biome=Identifier.tryParse(name);if(biome==null)return null;result.add(ResourceKey.create(Registries.BIOME,biome));}
            }
            visiting.remove(tag);return Set.copyOf(result);
        }catch(IOException|RuntimeException error){return null;}
    }
    private StructureBiomeRules(){}
}
