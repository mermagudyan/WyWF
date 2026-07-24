package com.wywf.client;

import com.wywf.core.ConfigStore;
import com.wywf.core.KeywordDictionary;
import com.wywf.core.SearchConfig;
import dev.isxander.yacl3.api.Binding;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class WywfConfigScreen {

    private WywfConfigScreen() {}

    private static Component tr(String key) {
        return Component.literal(ConfigTranslations.tr(key));
    }

    public static Screen create(Screen parent) {
        SearchConfig config = ConfigStore.load();
        ConfigTranslations.invalidate();

        return YetAnotherConfigLib.createBuilder()
                .title(tr("wywf.config.title"))
                .save(() -> ConfigStore.save(config))
                .category(ConfigCategory.createBuilder()
                        .name(tr("wywf.config.category.general"))
                        .group(OptionGroup.createBuilder()
                                .name(tr("wywf.config.general.language.name"))
                                .description(OptionDescription.of(tr("wywf.config.general.language.desc")))
                                .option(Option.<KeywordDictionary.Lang>createBuilder()
                                        .name(tr("wywf.config.general.language.option"))
                                        .binding(
                                                KeywordDictionary.Lang.EN,
                                                () -> config.queryLanguage(),
                                                v -> config.queryLanguage(v)
                                        )
                                        .controller(opt -> EnumControllerBuilder.create(opt)
                                                .enumClass(KeywordDictionary.Lang.class))
                                        .build())
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(tr("wywf.config.general.performance.name"))
                                .description(OptionDescription.of(tr("wywf.config.general.performance.desc")))
                                .option(Option.<SearchConfig.Mode>createBuilder()
                                        .name(tr("wywf.config.general.performance.thread_mode"))
                                        .binding(
                                                SearchConfig.Mode.AUTO,
                                                () -> config.mode(),
                                                v -> config.mode(v)
                                        )
                                        .controller(opt -> EnumControllerBuilder.create(opt)
                                                .enumClass(SearchConfig.Mode.class))
                                        .build())
                                .build())
                        .build())
                .category(ConfigCategory.createBuilder()
                        .name(tr("wywf.config.category.search"))
                        .group(OptionGroup.createBuilder()
                                .name(tr("wywf.config.search.scan.name"))
                                .option(Option.<Integer>createBuilder()
                                        .name(tr("wywf.config.search.scan.structure_radius"))
                                        .description(OptionDescription.of(tr("wywf.config.search.scan.structure_radius.desc")))
                                        .binding(
                                                40,
                                                () -> config.searchRadiusChunks(),
                                                v -> config.searchRadiusChunks(v)
                                        )
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(8, 80)
                                                .step(4))
                                        .build())
                                .option(Option.<Integer>createBuilder()
                                        .name(tr("wywf.config.search.scan.biome_radius"))
                                        .description(OptionDescription.of(tr("wywf.config.search.scan.biome_radius.desc")))
                                        .binding(
                                                16,
                                                () -> config.biomeCheckRadiusChunks(),
                                                v -> config.biomeCheckRadiusChunks(v)
                                        )
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(4, 64)
                                                .step(4))
                                        .build())
                                .option(Option.<Integer>createBuilder()
                                        .name(tr("wywf.config.search.scan.biome_step"))
                                        .description(OptionDescription.of(tr("wywf.config.search.scan.biome_step.desc")))
                                        .binding(
                                                4,
                                                () -> config.biomeSampleStepChunks(),
                                                v -> config.biomeSampleStepChunks(v)
                                        )
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(1, 8)
                                                .step(1))
                                        .build())
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(tr("wywf.config.search.center.name"))
                                .description(OptionDescription.of(tr("wywf.config.search.center.desc")))
                                .option(Option.<SearchConfig.SearchCenter>createBuilder()
                                        .name(tr("wywf.config.search.center.option"))
                                        .binding(
                                                SearchConfig.SearchCenter.ORIGIN,
                                                () -> config.searchCenter(),
                                                v -> config.searchCenter(v)
                                        )
                                        .controller(opt -> EnumControllerBuilder.create(opt)
                                                .enumClass(SearchConfig.SearchCenter.class))
                                        .build())
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(tr("wywf.config.search.limits.name"))
                                .description(OptionDescription.of(tr("wywf.config.search.limits.desc")))
                                .option(Option.<Integer>createBuilder()
                                        .name(tr("wywf.config.search.limits.time"))
                                        .description(OptionDescription.of(tr("wywf.config.search.limits.time.desc")))
                                        .binding(
                                                SearchConfig.DEFAULT_TIME_LIMIT_MINUTES,
                                                () -> config.timeLimitMinutes(),
                                                v -> config.timeLimitMinutes(v)
                                        )
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(5, 120)
                                                .step(5))
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(tr("wywf.config.search.limits.infinite"))
                                        .description(OptionDescription.of(tr("wywf.config.search.limits.infinite.desc")))
                                        .binding(
                                                false,
                                                () -> config.infiniteSeeds(),
                                                v -> config.infiniteSeeds(v)
                                        )
                                        .controller(BooleanControllerBuilder::create)
                                        .build())
                                .option(Option.<Integer>createBuilder()
                                        .name(tr("wywf.config.search.limits.max_seeds"))
                                        .description(OptionDescription.of(tr("wywf.config.search.limits.max_seeds.desc")))
                                        .binding(
                                                (int) SearchConfig.DEFAULT_MAX_SEEDS,
                                                () -> (int) config.rawMaxSeedsToCheck(),
                                                v -> config.maxSeedsToCheck(v)
                                        )
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(1_000_000, 1_000_000_000)
                                                .step(10_000_000))
                                        .build())
                                .build())
                        .group(OptionGroup.createBuilder()
                                .name(tr("wywf.config.search.candidates.name"))
                                .option(Option.<Integer>createBuilder()
                                        .name(tr("wywf.config.search.candidates.collect"))
                                        .description(OptionDescription.of(tr("wywf.config.search.candidates.collect.desc")))
                                        .binding(
                                                8,
                                                () -> config.candidatesToCollect(),
                                                v -> config.candidatesToCollect(v)
                                        )
                                        .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                                .range(1, 20)
                                                .step(1))
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(tr("wywf.config.search.candidates.stop_first"))
                                        .description(OptionDescription.of(tr("wywf.config.search.candidates.stop_first.desc")))
                                        .binding(
                                                false,
                                                () -> config.stopAtFirstCandidate(),
                                                v -> config.stopAtFirstCandidate(v)
                                        )
                                        .controller(BooleanControllerBuilder::create)
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(tr("wywf.config.search.candidates.sort_distance"))
                                        .description(OptionDescription.of(tr("wywf.config.search.candidates.sort_distance.desc")))
                                        .binding(
                                                false,
                                                () -> config.sortCandidatesByDistance(),
                                                v -> config.sortCandidatesByDistance(v)
                                        )
                                        .controller(BooleanControllerBuilder::create)
                                        .build())
                                .build())
                        .build())
                .build()
                .generateScreen(parent);
    }
}
