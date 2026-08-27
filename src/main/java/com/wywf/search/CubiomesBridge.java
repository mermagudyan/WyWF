package com.wywf.search;

import com.sun.jna.*;
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

    public static final int MC_1_21 = 28;
    public static final int DIM_OVERWORLD = 0;
    public static final int DIM_NETHER = -1;

    public interface NativeLib extends Library {
        Pointer wywf_createGenerator(int mc, int flags);
        void wywf_destroyGenerator(Pointer g);
        void wywf_applySeed(Pointer g, int dim, long seed);
        int wywf_getBiomeAt(Pointer g, int scale, int x, int y, int z);
        int wywf_getStructurePos(int structType, int mc, long seed, int regX, int regZ, int[] outX, int[] outZ);
        int wywf_isViableStructurePos(int structType, Pointer g, int x, int z, int flags);
        int wywf_getStructureConfig(int structType, int mc, int[] salt, int[] regionSize, int[] chunkRange, int[] structTypeOut, int[] dim, float[] rarity);
        int wywf_getSpawn(Pointer g, int[] outX, int[] outZ);
    }

    static {
        try {
            Path tempLib = loadNativeLibrary();
            System.load(tempLib.toAbsolutePath().toString());
            lib = Native.load(tempLib.toAbsolutePath().toString(), NativeLib.class);
            available = true;
            LOGGER.info("[CubiomesBridge] Native library loaded from {}", tempLib);
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

    /** Canonical game directory via Fabric, falling back to process CWD. */
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

    /**
     * Locates the packaged native library inside the mod jar. Fabric's knot
     * classloader does not always serve arbitrary root-level resources through
     * {@link Class#getResourceAsStream}, so fall back through the standard
     * loaders and finally the Fabric ModContainer roots (canonical way).
     */
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
                            LOGGER.info("[CubiomesBridge] native found via container root: {}", p);
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
                            LOGGER.info("[CubiomesBridge] native pulled straight from {}", jar);
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

    /** Set the native mode for the current search session. */
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

    /**
     * Returns true if native acceleration should be used.
     * AUTO: use if DLL loaded successfully.
     * NATIVE: require DLL, throw error if not available.
     * CLASSIC: never use DLL, Java-only.
     */
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
        Pointer g = lib.wywf_createGenerator(MC_1_21, 0);
        LOGGER.info("[CubiomesBridge] Created generator mc={} at {}", MC_1_21, g);
        return g;
    });

    /** Separate overworld-dimension generator for nether structures
     *  (fortress/bastion) — viability must be evaluated against nether biomes. */
    private static final ThreadLocal<Pointer> GENERATOR_NETHER = ThreadLocal.withInitial(() -> {
        if (lib == null) return null;
        return lib.wywf_createGenerator(MC_1_21, 0);
    });

    private static final ThreadLocal<Long> NETHER_SEED = ThreadLocal.withInitial(() -> Long.MIN_VALUE);

    /** Threads that actually used a native generator (avoids lazy-create on foreign threads). */
    private static final java.util.Set<Long> USED_THREADS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void markUsed() {
        USED_THREADS.add(Thread.currentThread().getId());
    }

    /**
     * Destroys the native generators for the current thread and removes them from
     * the ThreadLocals. No-op for threads that never used the bridge.
     */
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
        return isViableStructurePos(structType, DIM_OVERWORLD, 0, x, z);
    }

    /**
     * Dimension-aware viability check. For nether structures pass
     * {@link #DIM_NETHER} and the seed — a dedicated nether-dimension generator
     * is seeded lazily (once per seed per thread). For overworld structures the
     * caller is expected to have called {@link #applySeed(long)} first; the seed
     * argument is ignored there.
     */
    public static boolean isViableStructurePos(int structType, int dim, long seed, int x, int z) {
        if (lib == null) return false;
        try {
            Pointer g;
            if (dim == DIM_NETHER) {
                g = GENERATOR_NETHER.get();
                markUsed();
                Long applied = NETHER_SEED.get();
                if (applied == null || applied != seed) {
                    lib.wywf_applySeed(g, DIM_NETHER, seed);
                    NETHER_SEED.set(seed);
                }
            } else {
                g = GENERATOR.get();
                markUsed();
            }
            return lib.wywf_isViableStructurePos(structType, g, x, z, 0) != 0;
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
            int[] ox = new int[1], oz = new int[1];
            int rc = lib.wywf_getSpawn(GENERATOR.get(), ox, oz);
            if (rc != 0) return null;
            return new int[]{ox[0], oz[0]};
        } catch (Throwable t) {
            LOGGER.error("[CubiomesBridge] getSpawn failed: {}", t.getMessage());
            return null;
        }
    }
}
