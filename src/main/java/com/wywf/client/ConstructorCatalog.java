package com.wywf.client;

import com.wywf.core.KeywordDictionary;
import com.wywf.core.SearchDraft;
import com.wywf.search.WorldContextFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

final class ConstructorCatalog {
    private static final Set<String> UNSUPPORTED = Set.of("minecraft:smithy");

    static List<SearchDraft.Target> available(WorldContextFactory factory) {
        KeywordDictionary dictionary = new KeywordDictionary();
        return Arrays.stream(SearchDraft.Target.values()).filter(target -> {
            if (UNSUPPORTED.contains(target.canonical())) return false;
            if (target.category() == KeywordDictionary.Category.BIOME) return factory.isBiomeAvailable(target.canonical());
            return factory.isStructureAvailable(target.canonical())
                    || dictionary.getVariants(target.canonical()).stream().anyMatch(factory::isStructureAvailable);
        }).toList();
    }

    private ConstructorCatalog() {}
}
