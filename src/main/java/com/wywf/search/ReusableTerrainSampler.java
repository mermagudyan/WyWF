package com.wywf.search;

import net.minecraft.core.HolderGetter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.RandomSource;
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

/**
 * Wraps the {@code finalDensity} density function from vanilla's NoiseRouter
 * to compute terrain height at arbitrary (x, z) coordinates. Uses the same
 * mutable-noise-leaf pattern as {@link ReusableClimateSampler} so the expensive
 * graph is built once and only noise leaves are reseeded per seed.
 *
 * <p><b>Not thread-safe:</b> each searcher thread must own its own instance.
 */
final class ReusableTerrainSampler {

    private final NoiseGeneratorSettings settings;
    private final HolderGetter<NormalNoise.NoiseParameters> noises;
    private final List<Slot> slots;
    private final DensityFunction finalDensity;
    private final int minY;
    private final int maxY;
    private long currentSeed;
    private boolean seeded;

    ReusableTerrainSampler(NoiseGeneratorSettings settings,
                           HolderGetter<NormalNoise.NoiseParameters> noises) {
        this.settings = settings;
        this.noises = noises;
        this.slots = new ArrayList<>();

        Builder builder = new Builder(0L, settings, noises, slots);
        NoiseRouter r = settings.noiseRouter();
        this.finalDensity = r.finalDensity().mapAll(builder);

        net.minecraft.world.level.levelgen.NoiseSettings ns = settings.noiseSettings();
        this.minY = ns.minY();
        this.maxY = ns.minY() + ns.height();
        this.currentSeed = 0L;
        this.seeded = true;
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

    /**
     * Compute terrain height at (blockX, blockZ) by scanning downward
     * from maxY. Returns the Y of the highest solid block (density > 0).
     */
    int computeHeight(int blockX, int blockZ) {
        for (int y = maxY; y >= minY; y--) {
            if (finalDensity.compute(new DensityFunction.SinglePointContext(blockX, y, blockZ)) > 0.0) {
                return y;
            }
        }
        return minY;
    }

    private interface Slot {
        ResourceKey<NormalNoise.NoiseParameters> key();
        void setNoise(NormalNoise noise);
    }

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
            return instances.computeIfAbsent(key, k ->
                    Noises.instantiate(noises, random, k));
        }

        @Override
        public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noiseHolder) {
            var data = noiseHolder.noiseData();
            if (data.is(Noises.TEMPERATURE_NETHER)) {
                return new DensityFunction.NoiseHolder(data,
                        NormalNoise.createLegacyNetherBiome(newLegacyInstance(0L), data.value()));
            }
            if (data.is(Noises.VEGETATION_NETHER)) {
                return new DensityFunction.NoiseHolder(data,
                        NormalNoise.createLegacyNetherBiome(newLegacyInstance(1L), data.value()));
            }
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

    private static final class MutShiftA implements DensityFunction, Slot {
        private final ResourceKey<NormalNoise.NoiseParameters> key;
        private NormalNoise noise;
        MutShiftA(ResourceKey<NormalNoise.NoiseParameters> key, NormalNoise noise) {
            this.key = key; this.noise = noise;
        }
        @Override public ResourceKey<NormalNoise.NoiseParameters> key() { return key; }
        @Override public void setNoise(NormalNoise n) { this.noise = n; }
        @Override public double compute(DensityFunction.FunctionContext c) {
            return noise.getValue(c.blockX() * 0.25, 0.0, c.blockZ() * 0.25) * 4.0;
        }
        @Override public void fillArray(double[] array, DensityFunction.ContextProvider p) { p.fillAllDirectly(array, this); }
        @Override public DensityFunction mapChildren(DensityFunction.Visitor v) { return this; }
        @Override public double minValue() { return -maxValue(); }
        @Override public double maxValue() { return noise.maxValue() * 4.0; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("wywf terrain leaf");
        }
    }

    private static final class MutShiftB implements DensityFunction, Slot {
        private final ResourceKey<NormalNoise.NoiseParameters> key;
        private NormalNoise noise;
        MutShiftB(ResourceKey<NormalNoise.NoiseParameters> key, NormalNoise noise) {
            this.key = key; this.noise = noise;
        }
        @Override public ResourceKey<NormalNoise.NoiseParameters> key() { return key; }
        @Override public void setNoise(NormalNoise n) { this.noise = n; }
        @Override public double compute(DensityFunction.FunctionContext c) {
            return noise.getValue(c.blockZ() * 0.25, c.blockX() * 0.25, 0.0) * 4.0;
        }
        @Override public void fillArray(double[] array, DensityFunction.ContextProvider p) { p.fillAllDirectly(array, this); }
        @Override public DensityFunction mapChildren(DensityFunction.Visitor v) { return this; }
        @Override public double minValue() { return -maxValue(); }
        @Override public double maxValue() { return noise.maxValue() * 4.0; }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("wywf terrain leaf");
        }
    }

    private static final class MutShiftedNoise implements DensityFunction, Slot {
        private final DensityFunction shiftX, shiftY, shiftZ;
        private final double xzScale, yScale;
        private final ResourceKey<NormalNoise.NoiseParameters> key;
        private NormalNoise noise;
        MutShiftedNoise(DensityFunction shiftX, DensityFunction shiftY, DensityFunction shiftZ,
                        double xzScale, double yScale,
                        ResourceKey<NormalNoise.NoiseParameters> key, NormalNoise noise) {
            this.shiftX = shiftX; this.shiftY = shiftY; this.shiftZ = shiftZ;
            this.xzScale = xzScale; this.yScale = yScale;
            this.key = key; this.noise = noise;
        }
        @Override public ResourceKey<NormalNoise.NoiseParameters> key() { return key; }
        @Override public void setNoise(NormalNoise n) { this.noise = n; }
        @Override public double compute(DensityFunction.FunctionContext c) {
            double d = c.blockX() * xzScale + shiftX.compute(c);
            double e = c.blockY() * yScale + shiftY.compute(c);
            double f = c.blockZ() * xzScale + shiftZ.compute(c);
            return noise.getValue(d, e, f);
        }
        @Override public void fillArray(double[] array, DensityFunction.ContextProvider p) { p.fillAllDirectly(array, this); }
        @Override public DensityFunction mapChildren(DensityFunction.Visitor v) { return this; }
        @Override public double minValue() { return -maxValue(); }
        @Override public double maxValue() { return noise.maxValue(); }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("wywf terrain leaf");
        }
    }
}
