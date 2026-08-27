package com.wywf.search;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.Holder;
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
import java.util.function.Function;

/**
 * Wraps the {@code finalDensity} density function from vanilla's NoiseRouter
 * to compute terrain height at arbitrary (x, z) coordinates.
 *
 * <p>Every seed-dependent leaf is registered as a {@link Slot}: plain noise
 * holders ({@link DensityFunctions.Noise}), shift wrappers
 * ({@code ShiftA}/{@code ShiftB}/{@code ShiftedNoise}) and {@link BlendedNoise}.
 * {@link #reseed(long)} swaps every slot to freshly instantiated noise for the
 * new seed, so heights are correct for arbitrary seeds.</p>
 *
 * <p><b>Not thread-safe:</b> each searcher thread must own its own instance.</p>
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
        Function<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> factory =
                k -> Noises.instantiate(noises, random, k);
        for (Slot slot : slots) {
            slot.reseed(seed, cache, factory, random);
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
        void reseed(long seed,
                    Map<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> cache,
                    Function<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> noiseFactory,
                    PositionalRandomFactory random);
    }

    /**
     * Reflection-based accessor for the plain noise-holder density function node
     * (Mojang mappings: {@code DensityFunctions.Noise}). Some mapped artifacts
     * expose the nested class with restricted visibility, so the class is located
     * structurally (methods {@code noise(): NoiseHolder}, {@code xzScale()},
     * {@code yScale()}) instead of by name.
     */
    private static final class NoiseNodeAccess {
        final Class<?> type;
        final java.lang.reflect.Method noise;
        final java.lang.reflect.Method xzScale;
        final java.lang.reflect.Method yScale;

        NoiseNodeAccess(Class<?> type, java.lang.reflect.Method noise,
                        java.lang.reflect.Method xzScale, java.lang.reflect.Method yScale) {
            this.type = type;
            this.noise = noise;
            this.xzScale = xzScale;
            this.yScale = yScale;
        }
    }

    private static final NoiseNodeAccess NOISE_NODE = findNoiseNode();

    private static NoiseNodeAccess findNoiseNode() {
        for (Class<?> c : DensityFunctions.class.getDeclaredClasses()) {
            try {
                java.lang.reflect.Method n = c.getMethod("noise");
                if (n.getReturnType() != DensityFunction.NoiseHolder.class) continue;
                java.lang.reflect.Method xz = c.getMethod("xzScale");
                java.lang.reflect.Method ys = c.getMethod("yScale");
                if (xz.getReturnType() != double.class || ys.getReturnType() != double.class) continue;
                n.setAccessible(true);
                xz.setAccessible(true);
                ys.setAccessible(true);
                return new NoiseNodeAccess(c, n, xz, ys);
            } catch (NoSuchMethodException | SecurityException ignored) {
                // try next nested class
            }
        }
        return null;
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
            // Pre-instantiates seed-0 noise; mutability comes from the Mut* wrappers
            // installed later in wrapNew().
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
            NoiseNodeAccess na = NOISE_NODE;
            if (na != null && na.type.isInstance(function)) {
                try {
                    DensityFunction.NoiseHolder holder =
                            (DensityFunction.NoiseHolder) na.noise.invoke(function);
                    double xzs = (Double) na.xzScale.invoke(function);
                    double ys = (Double) na.yScale.invoke(function);
                    MutNoiseLeaf leaf = new MutNoiseLeaf(holder.noiseData(), xzs, ys,
                            getOrCreateNoise(keyOf(holder)));
                    slots.add(leaf);
                    return leaf;
                } catch (Exception ignored) {
                    // fall through — leave the node untouched rather than fail
                }
            }
            if (function instanceof BlendedNoise blended) {
                RandomSource rs = useLegacyInit
                        ? newLegacyInstance(0L)
                        : random.fromHashOf(Identifier.withDefaultNamespace("terrain"));
                MutBlended leaf = new MutBlended(useLegacyInit, blended.withNewRandom(rs));
                slots.add(leaf);
                return leaf;
            }
            return function;
        }

        @Override
        public DensityFunction apply(DensityFunction function) {
            return wrapped.computeIfAbsent(function, this::wrapNew);
        }
    }

    /** Plain noise-holder leaf ({@link DensityFunctions.Noise}) with swappable noise. */
    private static final class MutNoiseLeaf implements DensityFunction, Slot {
        private final Holder<NormalNoise.NoiseParameters> noiseData;
        private final double xzScale;
        private final double yScale;
        private NormalNoise noise;

        MutNoiseLeaf(Holder<NormalNoise.NoiseParameters> noiseData,
                     double xzScale, double yScale, NormalNoise noise) {
            this.noiseData = noiseData;
            this.xzScale = xzScale;
            this.yScale = yScale;
            this.noise = noise;
        }

        @Override
        public void reseed(long seed,
                           Map<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> cache,
                           Function<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> noiseFactory,
                           PositionalRandomFactory random) {
            this.noise = cache.computeIfAbsent(noiseData.unwrapKey().orElseThrow(), noiseFactory);
        }

        @Override public double compute(DensityFunction.FunctionContext c) {
            return noise.getValue(c.blockX() * xzScale, c.blockY() * yScale, c.blockZ() * xzScale);
        }
        @Override public void fillArray(double[] array, DensityFunction.ContextProvider p) { p.fillAllDirectly(array, this); }
        @Override public DensityFunction mapChildren(DensityFunction.Visitor v) { return this; }
        @Override public double minValue() { return -maxValue(); }
        @Override public double maxValue() { return noise.maxValue() * Math.max(Math.abs(xzScale), Math.abs(yScale)); }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("wywf terrain leaf");
        }
    }

    private static final class MutShiftA implements DensityFunction, Slot {
        private final ResourceKey<NormalNoise.NoiseParameters> key;
        private NormalNoise noise;
        MutShiftA(ResourceKey<NormalNoise.NoiseParameters> key, NormalNoise noise) {
            this.key = key; this.noise = noise;
        }
        @Override
        public void reseed(long seed,
                           Map<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> cache,
                           Function<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> noiseFactory,
                           PositionalRandomFactory random) {
            this.noise = cache.computeIfAbsent(key, noiseFactory);
        }
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
        @Override
        public void reseed(long seed,
                           Map<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> cache,
                           Function<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> noiseFactory,
                           PositionalRandomFactory random) {
            this.noise = cache.computeIfAbsent(key, noiseFactory);
        }
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
        @Override
        public void reseed(long seed,
                           Map<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> cache,
                           Function<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> noiseFactory,
                           PositionalRandomFactory random) {
            this.noise = cache.computeIfAbsent(key, noiseFactory);
        }
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

    /** {@link BlendedNoise} leaf whose random state can be swapped per seed. */
    private static final class MutBlended implements DensityFunction, Slot {
        private final boolean useLegacyInit;
        private BlendedNoise blended;

        MutBlended(boolean useLegacyInit, BlendedNoise blended) {
            this.useLegacyInit = useLegacyInit;
            this.blended = blended;
        }

        @Override
        public void reseed(long seed,
                           Map<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> cache,
                           Function<ResourceKey<NormalNoise.NoiseParameters>, NormalNoise> noiseFactory,
                           PositionalRandomFactory random) {
            RandomSource rs = useLegacyInit
                    ? new LegacyRandomSource(seed)
                    : random.fromHashOf(Identifier.withDefaultNamespace("terrain"));
            this.blended = blended.withNewRandom(rs);
        }

        @Override public double compute(DensityFunction.FunctionContext c) {
            return blended.compute(c);
        }
        @Override public void fillArray(double[] array, DensityFunction.ContextProvider p) { blended.fillArray(array, p); }
        @Override public DensityFunction mapChildren(DensityFunction.Visitor v) { return this; }
        @Override public double minValue() { return blended.minValue(); }
        @Override public double maxValue() { return blended.maxValue(); }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("wywf terrain leaf");
        }
    }
}
