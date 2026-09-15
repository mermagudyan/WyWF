package com.wywf.search;

import com.sun.jna.*;
import com.wywf.core.WyWFDebug;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;

public final class CubiomesBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("wywf-search");
    private static boolean available = false;
    private static NativeLib lib;
    private static volatile com.wywf.core.SearchConfig.NativeMode nativeMode = com.wywf.core.SearchConfig.NativeMode.AUTO;
    private static final ThreadLocal<com.wywf.core.SearchConfig.NativeMode> threadMode = new ThreadLocal<>();

    // MC slot follows the running game, unknown versions round down
    private static final int MC_26_1 = 33;
    private static final int MC_26_2 = 34;
    private static final int MC_26_3 = 35;
    private static volatile int resolvedSlot = -1;

    public static int mcSlot() {
        int s = resolvedSlot;
        if (s >= 0) return s;
        s = resolveSlot(runningGameVersion());
        resolvedSlot = s;
        return s;
    }

    static String runningGameVersion() {
        try {
            var c = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("minecraft").orElse(null);
            if (c != null) return c.getMetadata().getVersion().getFriendlyString();
        } catch (Throwable ignored) {
        }
        return "";
    }

    static int resolveSlot(String version) {
        int minor = -1;
        try {
            String[] p = version.split("[.\\-]");
            if (p.length >= 2 && p[0].equals("26")) minor = Integer.parseInt(p[1]);
        } catch (Throwable ignored) {
        }
        int slot;
        if (minor < 0) {
            slot = MC_26_2;
            LOGGER.warn("[CubiomesBridge] unknown game version '{}', assuming safest slot {}", version, slot);
        } else if (minor <= 1) {
            slot = MC_26_1;
        } else if (minor == 2) {
            slot = MC_26_2;
        } else {
            slot = MC_26_3;
            if (minor > 3) LOGGER.warn("[CubiomesBridge] game {} newer than known slots, using {}", version, slot);
        }
        return slot;
    }
    public static final int DIM_OVERWORLD = 0;
    public static final int DIM_NETHER = -1;

    // No mapApproxHeight binding by design, nether has no single surface

    public interface NativeLib extends Library {
        Pointer wywf_createGenerator(int mc, int flags);
        void wywf_destroyGenerator(Pointer g);
        void wywf_applySeed(Pointer g, int dim, long seed);
        int wywf_getBiomeAt(Pointer g, int scale, int x, int y, int z);
        int wywf_getStructurePos(int structType, int mc, long seed, int regX, int regZ, int[] outX, int[] outZ);
        int wywf_isViableStructurePos(int structType, Pointer g, int x, int z, int flags);
        int wywf_getStructureConfig(int structType, int mc, int[] salt, int[] regionSize, int[] chunkRange, int[] structTypeOut, int[] dim, float[] rarity);
        int wywf_getSpawn(Pointer g, int[] outX, int[] outZ);
        int wywf_estimateSpawn(Pointer g, int[] outX, int[] outZ);
        int wywf_biomeExists(Pointer g, int biomeId, int y, int ccx, int ccz, int rChunks, int step, int centerX, int centerZ, int rBlocks);
        int wywf_biomeNearest(Pointer g, int biomeId, int y, int ccx, int ccz, int rChunks, int step, int centerX, int centerZ);
        int wywf_sampleBiomeGrid(Pointer g, int y, int ccx, int ccz, int rChunks, int step, int[] out, int maxOut);
        int wywf_isViableMany(Pointer g, int structType, int[] xs, int[] zs, int n, int flags, byte[] out);
    }

    static {
        try {
            Path tempLib = loadNativeLibrary();
            System.load(tempLib.toAbsolutePath().toString());
            lib = Native.load(tempLib.toAbsolutePath().toString(), NativeLib.class);
            available = true;
            if (WyWFDebug.ENABLED) LOGGER.info("[CubiomesBridge] Native library loaded from {}", tempLib);
        } catch (Throwable t) {
            LOGGER.info("[CubiomesBridge] Not available: {}: {}", t.getClass().getSimpleName(), t.getMessage());
            available = false;
        }
    }

    private static Path loadNativeLibrary() throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String libName = os.contains("win") ? "wywf_native.dll" : "libwywf_native.so";
        // Extract into the GAME directory (canonical FabricLoader.getGameDir()),
        // never the shared TEMP dir: TEMP files get locked by antivirus / dead
        // processes and break every subsequent launch.
        Path cacheDir = gameDir().resolve("wywf_cache");
        Files.createDirectories(cacheDir);
        Path target = cacheDir.resolve(libName);

        byte[] expected = readNativeResource(libName);

        // Reuse an existing extraction when it matches by size (avoids rewriting
        // a file another game instance may still have mapped).
        if (Files.exists(target) && Files.size(target) == expected.length) {
            return target;
        }

        // Write to a unique temp file first, then atomically move onto target.
        Path tmp = cacheDir.resolve(libName + "." + ProcessHandle.current().pid() + ".tmp");
        Files.write(tmp, expected);
        try {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException amnse) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException locked) {
            // Target held by another process: load our own private copy instead.
            LOGGER.warn("[CubiomesBridge] target locked ({}), loading private copy {}", locked.toString(), tmp.getFileName());
            return tmp;
        }
        return target;
    }

    // Game dir via Fabric, else process CWD
    private static Path gameDir() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
        } catch (Throwable t) {
            return Paths.get("").toAbsolutePath();
        }
    }

    private static void dumpDiagnostics(String libName) {
        try {
            StringBuilder sb = new StringBuilder("[CubiomesBridge] native lookup failed. cwd=")
                    .append(Paths.get("").toAbsolutePath())
                    .append(", gameDir=").append(gameDir());
            Object containerObj = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("wywf").orElse(null);
            if (containerObj instanceof net.fabricmc.loader.api.ModContainer mc) {
                java.util.List<java.nio.file.Path> roots = null;
                try {
                    roots = (java.util.List<java.nio.file.Path>) mc.getClass()
                            .getMethod("getRootPaths").invoke(mc);
                } catch (Throwable notPresent) { }
                sb.append(", containerRoots=").append(roots != null ? roots : mc.getRoot());
            } else {
                sb.append(", container=null");
            }
            LOGGER.warn(sb.toString());
            Path modsDir = gameDir().resolve("mods");
            if (java.nio.file.Files.isDirectory(modsDir)) {
                try (java.nio.file.DirectoryStream<Path> ds =
                             java.nio.file.Files.newDirectoryStream(modsDir)) {
                    for (Path p : ds) {
                        if (p.getFileName().toString().toLowerCase().contains("wywf")) {
                            LOGGER.warn("[CubiomesBridge]   mods entry: {} ({} bytes)", p.getFileName(),
                                    java.nio.file.Files.size(p));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[CubiomesBridge] diagnostics themselves failed: {}", t.toString());
        }
    }

    // Packaged native lib via classloaders, container roots, natives/ or straight from mods/*.jar
    private static byte[] readNativeResource(String libName) throws IOException {
        // 1) absolute + relative through our own class
        try (InputStream s = CubiomesBridge.class.getResourceAsStream("/" + libName)) {
            if (s != null) return s.readAllBytes();
        } catch (IOException ignored) { }
        try (InputStream s = CubiomesBridge.class.getResourceAsStream(libName)) {
            if (s != null) return s.readAllBytes();
        } catch (IOException ignored) { }

        // 2) context / bridge classloaders
        ClassLoader[] loaders = {
                Thread.currentThread().getContextClassLoader(),
                CubiomesBridge.class.getClassLoader(),
                CubiomesBridge.class.getClassLoader() != null
                        ? CubiomesBridge.class.getClassLoader().getParent() : null
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            for (String name : new String[]{libName, "natives/" + libName}) {
                try (InputStream s = cl.getResourceAsStream(name)) {
                    if (s != null) return s.readAllBytes();
                } catch (IOException ignored) { }
            }
        }

        // 3) Fabric ModContainer — enumerate ALL roots (container may merge
        //    several sources; getRootPath alone returns an arbitrary one).
        try {
            Object containerObj = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer("wywf").orElse(null);
            if (containerObj instanceof net.fabricmc.loader.api.ModContainer mc) {
                java.util.List<java.nio.file.Path> roots = null;
                try {
                    roots = (java.util.List<java.nio.file.Path>) mc.getClass()
                            .getMethod("getRootPaths").invoke(mc);
                } catch (Throwable notPresent) { }
                if (roots == null || roots.isEmpty()) {
                    java.nio.file.Path single = mc.getRoot();
                    roots = single == null ? java.util.List.of() : java.util.List.of(single);
                }
                for (java.nio.file.Path root : roots) {
                    for (String name : new String[]{libName, "natives/" + libName}) {
                        java.nio.file.Path p = root.resolve(name);
                        if (java.nio.file.Files.exists(p)) {
                            if (WyWFDebug.ENABLED) LOGGER.info("[CubiomesBridge] native found via container root: {}", p);
                            return java.nio.file.Files.readAllBytes(p);
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }

        // 4) dev-mode fallback: natives folder next to the project
        Path local = Paths.get("").toAbsolutePath().resolve("natives").resolve(libName);
        if (java.nio.file.Files.exists(local)) {
            return java.nio.file.Files.readAllBytes(local);
        }

        // 5) Last resort: read the packaged library straight out of every jar
        //    in ./mods with a plain ZipFile — bypasses every classloader quirk.
        Path cwd = Paths.get("").toAbsolutePath();
        Path[] modDirs = { gameDir().resolve("mods"), cwd.resolve("mods") };
        for (Path modsDir : modDirs) {
            if (!java.nio.file.Files.isDirectory(modsDir)) continue;
            try (java.nio.file.DirectoryStream<Path> ds =
                         java.nio.file.Files.newDirectoryStream(modsDir, "*.jar")) {
                for (Path jar : ds) {
                    try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
                        java.util.zip.ZipEntry e = zf.getEntry(libName);
                        if (e == null) e = zf.getEntry("natives/" + libName);
                        if (e != null) {
                            if (WyWFDebug.ENABLED) LOGGER.info("[CubiomesBridge] native pulled straight from {}", jar);
                            try (InputStream s = zf.getInputStream(e)) {
                                return s.readAllBytes();
                            }
                        }
                    } catch (IOException ignored) { }
                }
            } catch (IOException ignored) { }
        }

        dumpDiagnostics(libName);
        throw new FileNotFoundException(
                "Native library '" + libName + "' not reachable via classloaders, mod container or mods/*.jar");
    }

    public static boolean isAvailable() { return available; }

    // Pin the native mode for the search session
    public static void setMode(com.wywf.core.SearchConfig.NativeMode mode) {
        com.wywf.core.SearchConfig.NativeMode m = (mode != null) ? mode : com.wywf.core.SearchConfig.NativeMode.AUTO;
        nativeMode = m;
        threadMode.set(m);
    }

    public static void setThreadMode(com.wywf.core.SearchConfig.NativeMode mode) {
        if (mode == null) threadMode.remove();
        else threadMode.set(mode);
    }

    public static void clearThreadMode() { threadMode.remove(); }

    // AUTO = use if loaded; NATIVE = require or throw; CLASSIC = never
    public static boolean isActive() {
        com.wywf.core.SearchConfig.NativeMode effective = threadMode.get();
        if (effective == null) effective = nativeMode;
        return switch (effective) {
            case AUTO    -> available;
            case NATIVE  -> {
                if (!available) {
                    throw new IllegalStateException("Native mode requires cubiomes DLL but it is not available");
                }
                yield true;
            }
            case CLASSIC -> false;
        };
    }

    private static final ThreadLocal<Pointer> GENERATOR = ThreadLocal.withInitial(() -> {
        if (lib == null) return null;
        Pointer g = lib.wywf_createGenerator(mcSlot(), 0);
        if (WyWFDebug.ENABLED) LOGGER.info("[CubiomesBridge] Created generator mc={} at {}", mcSlot(), g);
        return g;
    });

    // Own nether-dimension generator for fortress/bastion viability
    private static final ThreadLocal<Pointer> GENERATOR_NETHER = ThreadLocal.withInitial(() -> {
        if (lib == null) return null;
        return lib.wywf_createGenerator(mcSlot(), 0);
    });

    private static final ThreadLocal<long[]> NETHER_SEED = ThreadLocal.withInitial(() -> new long[]{Long.MIN_VALUE});

    // Scratch out-params, JNA fills them synchronously inside one call
    private static final ThreadLocal<int[]> SCRATCH_X = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<int[]> SCRATCH_Z = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<byte[]> SCRATCH_RAW = ThreadLocal.withInitial(() -> new byte[64]);

    // Sulfur-surface veto for xpple/cubiomes#19, underground and nether exempt
    private static final java.util.Set<Integer> SULFUR_VETO_STRUCTS =
            java.util.Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 14, 24, 26);
    private static final int SULFUR_CAVES_ID = 187;
    private static final int SURFACE_Y = 64;

    private static boolean sulfurVeto(int structType, int dim, Pointer g, int x, int z) {
        if (dim != DIM_OVERWORLD || !SULFUR_VETO_STRUCTS.contains(structType)) return false;
        try {
            return lib.wywf_getBiomeAt(g, 1, x, SURFACE_Y, z) == SULFUR_CAVES_ID;
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] rawBuffer(int n) {
        byte[] b = SCRATCH_RAW.get();
        if (b.length < n) {
            b = new byte[n];
            SCRATCH_RAW.set(b);
        }
        return b;
    }

    // Threads that used a generator (skip lazy-create on foreign threads)
    private static final java.util.Set<Long> USED_THREADS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void markUsed() {
        USED_THREADS.add(Thread.currentThread().getId());
    }

    // Destroys this thread's generators. No-op if it never used the bridge
    public static void destroyCurrentGenerator() {
        if (lib == null) return;
        if (!USED_THREADS.remove(Thread.currentThread().getId())) return;
        Pointer g = GENERATOR.get();
        if (g != null) {
            try {
                lib.wywf_destroyGenerator(g);
            } catch (Throwable t) {
                LOGGER.warn("[CubiomesBridge] destroyGenerator failed: {}", t.getMessage());
            }
            GENERATOR.remove();
        }
        Pointer n = GENERATOR_NETHER.get();
        if (n != null) {
            try {
                lib.wywf_destroyGenerator(n);
            } catch (Throwable t) {
                LOGGER.warn("[CubiomesBridge] destroyNetherGenerator failed: {}", t.getMessage());
            }
            GENERATOR_NETHER.remove();
        }
        NETHER_SEED.remove();
    }

    public static void applySeed(long seed) {
        if (lib == null) return;
        try {
            markUsed();
            lib.wywf_applySeed(GENERATOR.get(), DIM_OVERWORLD, seed);
        } catch (Throwable t) {
            LOGGER.error("[CubiomesBridge] applySeed failed for seed {}: {}", seed, t.getMessage());
        }
    }

    public static int getBiomeAt(int x, int y, int z) {
        if (lib == null) return -1;
        try {
            markUsed();
            return lib.wywf_getBiomeAt(GENERATOR.get(), 1, x, y, z);
        } catch (Throwable t) {
            LOGGER.error("[CubiomesBridge] getBiomeAt at ({},{},{}) failed: {}", x, y, z, t.getMessage());
            return -1;
        }
    }

    public static boolean getStructurePos(int structType, int mc, long seed, int regX, int regZ, int[] outPos) {
        if (lib == null) return false;
        try {
            int[] ox = new int[1], oz = new int[1];
            int found = lib.wywf_getStructurePos(structType, mc, seed, regX, regZ, ox, oz);
            if (found != 0) {
                outPos[0] = ox[0];
                outPos[1] = oz[0];
                return true;
            }
        } catch (Throwable t) {
            LOGGER.error("[CubiomesBridge] getStructurePos(type={}, seed={}, region={},{}) failed: {}",
                    structType, seed, regX, regZ, t.getMessage());
        }
        return false;
    }

    public static boolean isViableStructurePos(int structType, int x, int z) {
        return isViableStructurePos(structType, DIM_OVERWORLD, 0, x, z, 0);
    }

    // Nether passes DIM_NETHER + seed (own lazily-seeded generator), overworld uses applySeed() first
    public static boolean isViableStructurePos(int structType, int dim, long seed, int x, int z) {
        return isViableStructurePos(structType, dim, seed, x, z, 0);
    }

    // variantFlags: village-variant id, 0 means all variants
    public static boolean isViableStructurePos(int structType, int dim, long seed, int x, int z, int variantFlags) {
        if (lib == null) return false;
        try {
            Pointer g;
            if (dim == DIM_NETHER) {
                g = GENERATOR_NETHER.get();
                markUsed();
                long[] cell = NETHER_SEED.get();
                if (cell[0] != seed) {
                    lib.wywf_applySeed(g, DIM_NETHER, seed);
                    cell[0] = seed;
                }
            } else {
                g = GENERATOR.get();
                markUsed();
            }
            boolean ok = lib.wywf_isViableStructurePos(structType, g, x, z, variantFlags) != 0;
            if (ok && sulfurVeto(structType, dim, g, x, z)) return false;
            return ok;
        } catch (Throwable t) {
            LOGGER.error("[CubiomesBridge] isViableStructurePos(type={}, pos={},{}) failed: {}",
                    structType, x, z, t.getMessage());
            return false;
        }
    }

    public static boolean getStructureConfig(int structType, int mc, int[] outConf) {
        if (lib == null) return false;
        try {
            int[] salt = new int[1], rs = new int[1], cr = new int[1], st = new int[1], dim = new int[1];
            float[] rarity = new float[1];
            int found = lib.wywf_getStructureConfig(structType, mc, salt, rs, cr, st, dim, rarity);
            if (found != 0) {
                outConf[0] = salt[0];
                outConf[1] = rs[0];
                outConf[2] = cr[0];
                outConf[3] = st[0];
                outConf[4] = dim[0];
                outConf[5] = Float.floatToIntBits(rarity[0]);
                return true;
            }
        } catch (Throwable t) {
            LOGGER.error("[CubiomesBridge] getStructureConfig(type={}, mc={}) failed: {}", structType, mc, t.getMessage());
        }
        return false;
    }

    public static int[] getSpawn() {
        if (lib == null) return null;
        try {
            markUsed();
            int[] ox = SCRATCH_X.get(), oz = SCRATCH_Z.get();
            int rc = lib.wywf_getSpawn(GENERATOR.get(), ox, oz);
            if (rc != 0) return null;
            return new int[]{ox[0], oz[0]};
        } catch (Throwable t) {
            LOGGER.error("[CubiomesBridge] getSpawn failed: {}", t.getMessage());
            return null;
        }
    }

    // Cheap spawn estimate for bulk, exact getSpawn stays for finalists
    public static int[] estimateSpawn() {
        if (lib == null) return null;
        try {
            markUsed();
            int[] ox = SCRATCH_X.get(), oz = SCRATCH_Z.get();
            int rc = lib.wywf_estimateSpawn(GENERATOR.get(), ox, oz);
            if (rc != 0) return null;
            return new int[]{ox[0], oz[0]};
        } catch (Throwable t) {
            LOGGER.error("[CubiomesBridge] estimateSpawn failed: {}", t.getMessage());
            return null;
        }
    }

    // Bulk biome scan: 1 roundtrip instead of one getBiomeAt per grid point, 1/0 or -1 on error
    public static int biomeExists(int biomeId, int y, int ccx, int ccz,
                                  int rChunks, int step, int centerX, int centerZ, int rBlocks) {
        if (lib == null) return -1;
        try {
            markUsed();
            return lib.wywf_biomeExists(GENERATOR.get(), biomeId, y, ccx, ccz, rChunks, step, centerX, centerZ, rBlocks);
        } catch (Throwable t) {
            LOGGER.error("[CubiomesBridge] biomeExists failed: {}", t.getMessage());
            return -1;
        }
    }

    // Bulk nearest distance, or -1 when absent (also -1 on error: caller retries per-point)
    public static int biomeNearest(int biomeId, int y, int ccx, int ccz,
                                   int rChunks, int step, int centerX, int centerZ) {
        if (lib == null) return -1;
        try {
            markUsed();
            return lib.wywf_biomeNearest(GENERATOR.get(), biomeId, y, ccx, ccz, rChunks, step, centerX, centerZ);
        } catch (Throwable t) {
            LOGGER.error("[CubiomesBridge] biomeNearest failed: {}", t.getMessage());
            return -1;
        }
    }

    // Fills out[] row-major with raw biome ids, returns count or -1 on error
    public static int sampleBiomeGrid(int y, int ccx, int ccz, int rChunks, int step, int[] out) {
        if (lib == null) return -1;
        try {
            markUsed();
            return lib.wywf_sampleBiomeGrid(GENERATOR.get(), y, ccx, ccz, rChunks, step, out, out.length);
        } catch (Throwable t) {
            LOGGER.error("[CubiomesBridge] sampleBiomeGrid failed: {}", t.getMessage());
            return -1;
        }
    }

    // Batch viability for already-placed candidates, same verdicts in one roundtrip (null = per-point fallback)
    public static boolean[] viableMany(int structType, int dim, long seed, int[] xs, int[] zs) {
        return viableMany(structType, dim, seed, xs, zs, 0);
    }

    // variantFlags: see isViableStructurePos, 0 means all variants
    public static boolean[] viableMany(int structType, int dim, long seed, int[] xs, int[] zs, int variantFlags) {
        return viableMany(structType, dim, seed, xs, zs, xs.length, variantFlags);
    }

    // First-n variant for reused oversized buffers, same verdicts
    public static boolean[] viableMany(int structType, int dim, long seed, int[] xs, int[] zs, int n, int variantFlags) {
        if (lib == null || n <= 0) return null;
        try {
            Pointer g;
            if (dim == DIM_NETHER) {
                g = GENERATOR_NETHER.get();
                markUsed();
                long[] cell = NETHER_SEED.get();
                if (cell[0] != seed) {
                    lib.wywf_applySeed(g, DIM_NETHER, seed);
                    cell[0] = seed;
                }
            } else {
                g = GENERATOR.get();
                markUsed();
            }
            byte[] out = rawBuffer(n);
            lib.wywf_isViableMany(g, structType, xs, zs, n, variantFlags, out);
            boolean[] res = new boolean[n];
            for (int i = 0; i < n; i++) {
                res[i] = out[i] != 0;
                if (res[i] && sulfurVeto(structType, dim, g, xs[i], zs[i])) res[i] = false;
            }
            return res;
        } catch (Throwable t) {
            LOGGER.error("[CubiomesBridge] isViableMany(type={}) failed: {}", structType, t.getMessage());
            return null;
        }
    }
}
