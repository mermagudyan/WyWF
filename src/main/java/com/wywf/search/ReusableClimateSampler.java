package com.wywf.search;

import net.minecraft.core.HolderGetter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.biome.Climate.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Climate.Sampler} whose density-function graph is built <b>once</b> and
 * reused across seeds. The 73% of per-seed construction cost that vanilla spends
 * rebuilding the (seed-independent) wrapper graph is paid a single time; only the
 * six {@link NormalNoise} leaves are recreated per seed via {@link #reseed(long)}.
 *
 * <p>The graph is produced from vanilla's overworld climate density functions with
 * only the noise-reading leaves ({@code ShiftA}, {@code ShiftB}, {@code ShiftedNoise})
 * swapped for the mutable-noise versions below; all other nodes are the genuine
 * vanilla implementations, so sampled values are bit-identical to a real
 * {@code RandomState} (verified by {@code ReusableClimateSamplerTest}).
 *
 * <p><b>Not thread-safe:</b> the noise leaves are mutated by {@link #reseed(long)},
 * so each searcher thread must own its own instance.
 */
final class ReusableClimateSampler {

    private static final Logger LOGGER = LoggerFactory.getLogger("wywf-search");
    private final NoiseGeneratorSettings settings;
    private final HolderGetter<NormalNoise.NoiseParameters> noises;
    private final List<Slot> slots;
    private final Climate.Sampler sampler;
    private long currentSeed;
    private boolean seeded;

    ReusableClimateSampler(NoiseGeneratorSettings settings,
                           HolderGetter<NormalNoise.NoiseParameters> noises) {
        this.settings = settings;
        this.noises = noises;
        this.slots = new ArrayList<>();

        // One-time build using seed 0 so parent nodes can compute (seed-independent) maxValue.
        Builder builder = new Builder(0L, settings, noises, slots);
        NoiseRouter r = settings.noiseRouter();
        this.sampler = new Climate.Sampler(
                r.temperature().mapAll(builder),
                r.vegetation().mapAll(builder),
                r.continents().mapAll(builder),
                r.erosion().mapAll(builder),
                r.depth().mapAll(builder),
                r.ridges().mapAll(builder),
                effectiveSpawnTarget(settings.spawnTarget()));
        this.currentSeed = 0L;
        this.seeded = true;
    }

    /**
     * Returns the spawn target list, or a hardcoded vanilla overworld fallback if the
     * deserialized settings have an empty list (known codec round-trip issue).
     * Values match vanilla's {@code OverworldBiomeBuilder.spawnTarget()}.
     */
    private static List<Climate.ParameterPoint> effectiveSpawnTarget(List<Climate.ParameterPoint> target) {
        if (target != null && !target.isEmpty()) {
            return target;
        }
        LOGGER.info("[ReusableClimateSampler] spawnTarget is empty — using hardcoded vanilla overworld fallback (2 ParameterPoints)");
        Parameter full = Parameter.span(-1.0f, 1.0f);
        Parameter continentalness = Parameter.span(-0.11f, 1.0f);
        Parameter depth = Parameter.point(0.0f);
        return List.of(
                new Climate.ParameterPoint(full, full, continentalness, full, depth,
                        Parameter.span(-1.0f, -0.16f), 0L),
                new Climate.ParameterPoint(full, full, continentalness, full, depth,
                        Parameter.span(0.16f, 1.0f), 0L)
        );
    }

    Climate.Sampler sampler() {
        return sampler;
    }

    void reseed(long seed) {
        if (seeded && seed == currentSeed) return;
        PositionalRandomFactory random = settings.getRandomSource().newInstance(seed).forkPositional();
        Map<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> cache = new HashMap<>();
        for (Slot slot : slots) {
            NormalNoise noise = cache.computeIfAbsent(slot.key(),
                    k -> Noises.instantiate(noises, random, k));
            slot.setNoise(noise);
        }
        currentSeed = seed;
        seeded = true;
    }

    private interface Slot {
        ResourceKey<NormalNoise.NoiseParameters> key();
        void setNoise(NormalNoise noise);
    }

    /**
     * Wires noises (like vanilla's {@code NoiseWiringHelper}) and, in {@link #apply},
     * replaces the three noise-reading leaf types with mutable-noise equivalents.
     */
    private static final class Builder implements DensityFunction.Visitor {
        private final Map<DensityFunction, DensityFunction> wrapped = new HashMap<>();
        private final Map<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> instances =
                new ConcurrentHashMap<>();
        private final long seed;
        private final boolean useLegacyInit;
        private final HolderGetter<NormalNoise.NoiseParameters> noises;
        private final PositionalRandomFactory random;
        private final List<Slot> slots;

        Builder(long seed, NoiseGeneratorSettings settings,
                HolderGetter<NormalNoise.NoiseParameters> noises, List<Slot> slots) {
            this.seed = seed;
            this.useLegacyInit = settings.useLegacyRandomSource();
            this.noises = noises;
            this.random = settings.getRandomSource().newInstance(seed).forkPositional();
            this.slots = slots;
        }

        private RandomSource newLegacyInstance(long offset) {
            return new LegacyRandomSource(seed + offset);
        }

        private NormalNoise getOrCreateNoise(ResourceKey<NormalNoise.NoiseParameters> key) {
            return instances.computeIfAbsent(key, k -> Noises.instantiate(noises, random, k));
        }

        @Override
        public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noiseHolder) {
            var data = noiseHolder.noiseData();
            // TODO: In 1.21.x, Noises.TEMPERATURE_NETHER / VEGETATION_NETHER may not exist.
            // If they do, uncomment these blocks to use legacy nether biome noise.
            // if (data.is(Noises.TEMPERATURE_NETHER)) {
            //     return new DensityFunction.NoiseHolder(data,
            //             NormalNoise.createLegacyNetherBiome(newLegacyInstance(0L), data.value()));
            // }
            // if (data.is(Noises.VEGETATION_NETHER)) {
            //     return new DensityFunction.NoiseHolder(data,
            //             NormalNoise.createLegacyNetherBiome(newLegacyInstance(1L), data.value()));
            // }
            ResourceKey<NormalNoise.NoiseParameters> key = data.unwrapKey().orElseThrow();
            return new DensityFunction.NoiseHolder(data, getOrCreateNoise(key));
        }

        private static ResourceKey<NormalNoise.NoiseParameters> keyOf(DensityFunction.NoiseHolder h) {
            return h.noiseData().unwrapKey().orElseThrow();
        }

        private DensityFunction wrapNew(DensityFunction function) {
            if (function instanceof DensityFunctions.ShiftA a) {
                MutShiftA leaf = new MutShiftA(keyOf(a.offsetNoise()), a.offsetNoise().noise());
                slots.add(leaf);
                return leaf;
            }
            if (function instanceof DensityFunctions.ShiftB b) {
                MutShiftB leaf = new MutShiftB(keyOf(b.offsetNoise()), b.offsetNoise().noise());
                slots.add(leaf);
                return leaf;
            }
            if (function instanceof DensityFunctions.ShiftedNoise s) {
                MutShiftedNoise leaf = new MutShiftedNoise(
                        s.shiftX(), s.shiftY(), s.shiftZ(), s.xzScale(), s.yScale(),
                        keyOf(s.noise()), s.noise().noise());
                slots.add(leaf);
                return leaf;
            }
            if (function instanceof BlendedNoise blended) {
                RandomSource rs = useLegacyInit
                        ? newLegacyInstance(0L)
                        : random.fromHashOf(Identifier.withDefaultNamespace("terrain"));
                return blended.withNewRandom(rs);
            }
            return function;
        }

        @Override
        public DensityFunction apply(DensityFunction function) {
            return wrapped.computeIfAbsent(function, this::wrapNew);
        }
    }

    // ---- mutable-noise leaves (replicate vanilla ShiftA / ShiftB / ShiftedNoise) ----

    private static final class MutShiftA implements DensityFunction, Slot {
        private final ResourceKey<NormalNoise.NoiseParameters> key;
        private NormalNoise noise;

        MutShiftA(ResourceKey<NormalNoise.NoiseParameters> key, NormalNoise noise) {
            this.key = key;
            this.noise = noise;
        }

        @Override public ResourceKey<NormalNoise.NoiseParameters> key() { return key; }
        @Override public void setNoise(NormalNoise n) { this.noise = n; }

        @Override public double compute(DensityFunction.FunctionContext c) {
            return noise.getValue(c.blockX() * 0.25, 0.0, c.blockZ() * 0.25) * 4.0;
        }
        @Override public void fillArray(double[] array, DensityFunction.ContextProvider provider) {
            provider.fillAllDirectly(array, this);
        }
        @Override public DensityFunction mapAll(DensityFunction.Visitor visitor) { return this; }
        @Override public double minValue() { return -maxValue(); }
        @Override public double maxValue() { return noise.maxValue() * 4.0; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("wywf reusable climate leaf is not serializable");
        }
    }

    private static final class MutShiftB implements DensityFunction, Slot {
        private final ResourceKey<NormalNoise.NoiseParameters> key;
        private NormalNoise noise;

        MutShiftB(ResourceKey<NormalNoise.NoiseParameters> key, NormalNoise noise) {
            this.key = key;
            this.noise = noise;
        }

        @Override public ResourceKey<NormalNoise.NoiseParameters> key() { return key; }
        @Override public void setNoise(NormalNoise n) { this.noise = n; }

        @Override public double compute(DensityFunction.FunctionContext c) {
            return noise.getValue(c.blockZ() * 0.25, c.blockX() * 0.25, 0.0) * 4.0;
        }
        @Override public void fillArray(double[] array, DensityFunction.ContextProvider provider) {
            provider.fillAllDirectly(array, this);
        }
        @Override public DensityFunction mapAll(DensityFunction.Visitor visitor) { return this; }
        @Override public double minValue() { return -maxValue(); }
        @Override public double maxValue() { return noise.maxValue() * 4.0; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("wywf reusable climate leaf is not serializable");
        }
    }

    private static final class MutShiftedNoise implements DensityFunction, Slot {
        private final DensityFunction shiftX;
        private final DensityFunction shiftY;
        private final DensityFunction shiftZ;
        private final double xzScale;
        private final double yScale;
        private final ResourceKey<NormalNoise.NoiseParameters> key;
        private NormalNoise noise;

        MutShiftedNoise(DensityFunction shiftX, DensityFunction shiftY, DensityFunction shiftZ,
                        double xzScale, double yScale,
                        ResourceKey<NormalNoise.NoiseParameters> key, NormalNoise noise) {
            this.shiftX = shiftX;
            this.shiftY = shiftY;
            this.shiftZ = shiftZ;
            this.xzScale = xzScale;
            this.yScale = yScale;
            this.key = key;
            this.noise = noise;
        }

        @Override public ResourceKey<NormalNoise.NoiseParameters> key() { return key; }
        @Override public void setNoise(NormalNoise n) { this.noise = n; }

        @Override public double compute(DensityFunction.FunctionContext c) {
            double d = c.blockX() * xzScale + shiftX.compute(c);
            double e = c.blockY() * yScale + shiftY.compute(c);
            double f = c.blockZ() * xzScale + shiftZ.compute(c);
            return noise.getValue(d, e, f);
        }
        @Override public void fillArray(double[] array, DensityFunction.ContextProvider provider) {
            provider.fillAllDirectly(array, this);
        }
        @Override public DensityFunction mapAll(DensityFunction.Visitor visitor) { return this; }
        @Override public double minValue() { return -maxValue(); }
        @Override public double maxValue() { return noise.maxValue(); }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("wywf reusable climate leaf is not serializable");
        }
    }
}
