package com.wywf.verify;

import com.wywf.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.phys.AABB;
import java.util.*;

final class VanillaLoot {
    static boolean verify(PreviewWorld.Session session, ParsedQuery.Term term, StructureStart start, List<String> evidence) {
        return verify(session, term, start, evidence, session.level(DimensionSpace.of(term.canonical)), pos -> true);
    }

    static boolean verify(PreviewWorld.Session session, ParsedQuery.Term term, StructureStart start, List<String> evidence,
                          net.minecraft.server.level.ServerLevel level, java.util.function.Predicate<BlockPos> inArea) {
        long started = System.nanoTime();
        int scanned = 0;
        boolean vanillaFortress = term.canonical.equals("minecraft:fortress") && start.getPieces().stream().allMatch(p ->
                p.getClass().getName().startsWith("net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces$"));
        var pieces = start.getPieces().stream().filter(piece -> !vanillaFortress
                || piece instanceof net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.CastleSmallCorridorLeftTurnPiece
                || piece instanceof net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces.CastleSmallCorridorRightTurnPiece)
                .filter(piece -> !term.loot.items().isEmpty() || !LootContainers.buildingOnly(term.loot.container())
                || piece instanceof PoolElementStructurePiece pool
                && building(pool.getElement(), term.loot.container())).toList();
        if (term.loot.items().isEmpty() && LootContainers.buildingOnly(term.loot.container()) && !pieces.isEmpty()) {
            for (var piece : pieces) {
                var box = piece.getBoundingBox();
                for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++)
                    for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) session.chunk(level, x, z, ChunkStatus.FULL);
            }
            evidence.add("Confirmed generated " + term.loot.container() + " building; chest contents were not requested");
            logTiming(started, 0, true);
            return true;
        }
        Set<Long> visited = new HashSet<>();
        Set<UUID> vehicles = new HashSet<>();
        for (var piece : pieces) {
            var box = piece.getBoundingBox();
            for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                long key = ((long) x & 0xFFFFFFFFL) | ((long) z << 32);
                if (!visited.add(key)) continue;
                var chunk = (LevelChunk) session.chunk(level, x, z, ChunkStatus.FULL);
                for (var entity : new ArrayList<>(chunk.getBlockEntities().values())) {
                    session.checkpoint();
                    BlockPos pos = entity.getBlockPos();
                    if (!(entity instanceof RandomizableContainerBlockEntity chest)
                            || !(chest instanceof ChestBlockEntity || chest instanceof BarrelBlockEntity)
                            || !inArea.test(pos)
                            || pieces.stream().noneMatch(p -> p.getBoundingBox().isInside(pos))) continue;
                    var witness = session.lootContainers.get(List.of(level.dimension(), pos));
                    if (witness == null) {
                        var table = chest.getLootTable();
                        if (table == null || !LootCatalog.acceptsTable(term.canonical, table.identifier().toString())
                                || !term.loot.acceptsTable(table.identifier().toString())) continue;
                        long seed = chest.getLootTableSeed();
                        chest.unpackLootTable(null);
                        witness = snapshot(term.canonical, chest, pos, table.identifier().toString(), seed);
                        session.lootContainers.put(List.of(level.dimension(), pos.immutable()), witness);
                    }
                    if (!LootCatalog.acceptsTable(term.canonical, witness.table()) || !term.loot.acceptsTable(witness.table())) continue;
                    scanned++;
                    if (!confirm(session, term, witness, evidence)) continue;
                    logTiming(started, scanned, true);
                    return true;
                }
                if (!term.canonical.startsWith("minecraft:mineshaft")) continue;
                int entityX = x, entityZ = z;
                // Entity deserialization runs on the server queue rather than the chunk queue
                session.server().managedBlock(() -> {
                    session.checkpoint();
                    ((VerificationEntities) level).wywf_loadEntities(entityX, entityZ);
                    return level.areEntitiesLoaded(key);
                });
                var area = new AABB(x * 16, level.getMinY(), z * 16, x * 16 + 16, level.getMaxY(), z * 16 + 16);
                for (var cart : level.getEntitiesOfClass(MinecartChest.class, area)) {
                    session.checkpoint();
                    BlockPos pos = cart.blockPosition();
                    if (!vehicles.add(cart.getUUID()) || !inArea.test(pos)
                            || pieces.stream().noneMatch(p -> p.getBoundingBox().isInside(pos))) continue;
                    var witness = session.lootContainers.get(cart.getUUID());
                    if (witness == null) {
                        var table = cart.getContainerLootTable();
                        if (table == null || !LootCatalog.acceptsTable(term.canonical, table.identifier().toString())
                                || !term.loot.acceptsTable(table.identifier().toString())) continue;
                        long seed = cart.getContainerLootTableSeed();
                        cart.unpackChestVehicleLootTable(null);
                        witness = snapshot(term.canonical, cart, pos, table.identifier().toString(), seed);
                        session.lootContainers.put(cart.getUUID(), witness);
                    }
                    if (!term.loot.acceptsTable(witness.table())) continue;
                    scanned++;
                    if (!confirm(session, term, witness, evidence)) continue;
                    logTiming(started, scanned, true);
                    return true;
                }
            }
        }
        evidence.add("No matching chest in this structure; checked " + scanned + " eligible containers");
        logTiming(started, scanned, false);
        return false;
    }

    private static boolean building(net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement element, String container) {
        if (element instanceof net.minecraft.world.level.levelgen.structure.pools.ListPoolElement list)
            return list.getElements().stream().anyMatch(e -> building(e, container));
        if (!(element instanceof net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement single)) return false;
        var location = single.getTemplateLocation();
        if (location == null) throw new PreviewWorld.BudgetExceeded("Inline building template cannot prove this profession");
        String path = location.getPath();
        return path.contains("_" + container + "_") || container.equals("toolsmith") && path.contains("_tool_smith_");
    }

    private static LootWitness snapshot(String structure, Container chest, BlockPos pos, String table, long seed) {
        Map<String, Integer> items = new LinkedHashMap<>();
        List<LootConstraint.Stack> stacks = new ArrayList<>();
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            var stack = chest.getItem(slot);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            items.merge(id, stack.getCount(), Integer::sum);
            Map<String, Integer> enchantments = new LinkedHashMap<>();
            for (var values : List.of(stack.getEnchantments(), stack.getOrDefault(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS,
                    net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY))) {
                for (var e : values.entrySet()) enchantments.merge(e.getKey().unwrapKey().orElseThrow().identifier().toString(), e.getIntValue(), Math::max);
            }
            int durability = stack.isDamageableItem() ? (stack.getMaxDamage() - stack.getDamageValue()) * 100 / stack.getMaxDamage() : -1;
            stacks.add(new LootConstraint.Stack(id, stack.getCount(), enchantments, durability));
        }
        return new LootWitness(structure, pos.getX(), pos.getY(), pos.getZ(), table, seed, items, stacks);
    }

    private static boolean confirm(PreviewWorld.Session session, ParsedQuery.Term term, LootWitness witness, List<String> evidence) {
        if (!term.loot.matches(witness.stacks())) return false;
        var confirmed = new LootWitness(term.canonical, witness.x(), witness.y(), witness.z(), witness.table(), witness.seed(), witness.items(), witness.stacks());
        if (!session.loot.contains(confirmed)) session.loot.add(confirmed);
        evidence.add("Confirmed container " + witness.x() + "," + witness.y() + "," + witness.z() + " · " + witness.table()
                + " · seed " + witness.seed() + " · " + witness.items());
        return true;
    }

    private static void logTiming(long started, int scanned, boolean matched) {
        if (WyWFDebug.ENABLED) org.slf4j.LoggerFactory.getLogger("wywf-search").info(
                "[loot-verify] Java/vanilla {} containers, {} ms, matched={}", scanned, (System.nanoTime() - started) / 1_000_000, matched);
    }
    private VanillaLoot() {}
}
