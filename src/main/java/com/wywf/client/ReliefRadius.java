package com.wywf.client;

import com.wywf.WYWFClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.*;

final class ReliefRadius {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("wywf/relief-radius");
    static final int MIN = 4, MAX = 20;

    static int load() {
        try {
            if (!Files.isRegularFile(FILE)) return 8;
            if (Files.size(FILE) > 16) return MIN;
            String saved = Files.readString(FILE).strip();
            if (saved.startsWith("pending ")) {
                save(MIN, false);
                return MIN;
            }
            return Math.clamp(Integer.parseInt(saved), MIN, MAX);
        } catch (IOException | NumberFormatException invalid) {
            return MIN;
        }
    }

    static boolean save(int radius, boolean pending) {
        try {
            Files.createDirectories(FILE.getParent());
            Path temporary = Files.createTempFile(FILE.getParent(), "relief-radius-", ".tmp");
            try {
                Files.writeString(temporary, (pending ? "pending " : "") + radius, StandardOpenOption.SYNC);
                try {
                    Files.move(temporary, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return true;
        } catch (IOException error) {
            WYWFClient.LOGGER.warn("Could not write WyWF relief recovery setting", error);
            return false;
        }
    }

    private ReliefRadius() {}
}
