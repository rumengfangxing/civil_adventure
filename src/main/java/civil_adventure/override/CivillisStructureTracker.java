package civil_adventure.override;

import civil_adventure.CivilAdventureMod;
import civil_adventure.core.ChunkCoordPacker;
import civil_adventure.faction.FactionDefinition;
import civil_adventure.faction.FactionRegistry;
import civil.registry.ZonePolicyRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 派系冒险区结构追踪与肃清系统。
 * 遵循 2.0-FINAL 架构规范：
 * 1. 允许为每个派系独立分配结构 ID / 标签。
 * 2. 属于某派系的结构自动激活该派系冒险区，并注入基础威胁分（默认 500.0 或数据包自定义）。
 * 3. 追踪结构基础分削减，当被压制清零时，永久标记为【已肃清 (Cleansed)】。
 * 4. 肃清后该结构的冒险区判定永久失效，但绝不影响后续新派系在此滋生。
 */
public final class CivillisStructureTracker {

    /** 结构冒险区默认初始基础分 */
    public static final double DEFAULT_STRUCTURE_BASE_SCORE = 500.0;

    public record ActiveStructure(long rootKey, FactionDefinition faction, double remainingScore) {}


    private CivillisStructureTracker() {}

    /**
     * 判断结构所属的派系。
     * 遵循派系显式分配原则：仅匹配派系定义中声明的 structureIds 或 structureTags。
     * 绝不盲目回退匹配所有原版无关遗迹，防止地图到处泛滥生成冒险区。
     */
    public static FactionDefinition getFactionForStructure(ServerLevel level, Structure structure) {
        if (structure == null) return null;
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        ResourceLocation structureId = registry.getKey(structure);
        if (structureId == null) return null;

        var holderOpt = registry.getHolder(ResourceKey.create(Registries.STRUCTURE, structureId));
        Holder<Structure> holder = holderOpt.orElse(null);

        // 优先遍历所有非文明派系定义 (包含默认灾厄派系 DEFAULT_ADVENTURE)
        for (FactionDefinition faction : FactionRegistry.getAllFactions()) {
            if (faction.isCivilization()) continue; // 文明区不作为敌对冒险结构
            if (faction.matchesStructure(structureId, holder)) {
                return faction;
            }
        }

        return null;
    }

    /**
     * 查询指定区块位置存在的所有尚未肃清的派系冒险结构。
     */
    public static List<ActiveStructure> getActiveStructures(ServerLevel level, int cx, int cz) {
        List<StructureStart> starts = level.structureManager().startsForStructure(new ChunkPos(cx, cz), s -> true);
        if (starts == null || starts.isEmpty()) return List.of();

        List<ActiveStructure> result = new ArrayList<>();
        for (StructureStart start : starts) {
            if (start == null || !start.isValid()) continue;

            BoundingBox box = start.getBoundingBox();
            if (cx < (box.minX() >> 4) || cx > (box.maxX() >> 4) ||
                cz < (box.minZ() >> 4) || cz > (box.maxZ() >> 4)) {
                continue;
            }

            FactionDefinition faction = getFactionForStructure(level, start.getStructure());
            if (faction == null) continue;

            long rootKey = ChunkCoordPacker.pack(start.getChunkPos().x, start.getChunkPos().z);

            // 检查是否已被持久化肃清
            if (CleansedStructureStorage.get(level).isCleansed(rootKey)) {
                continue;
            }

            double defaultBase = faction.structureBaseScore();
            double remaining = getRemainingScore(level, rootKey, defaultBase);
            if (remaining > 0.0) {
                // 计算结构所覆盖的区块总数，分摊基础分，避免多区块大结构总分倍增膨胀
                int minXChunk = box.minX() >> 4;
                int maxXChunk = box.maxX() >> 4;
                int minZChunk = box.minZ() >> 4;
                int maxZChunk = box.maxZ() >> 4;
                int chunkCount = Math.max(1, (maxXChunk - minXChunk + 1) * (maxZChunk - minZChunk + 1));
                double perChunkScore = remaining / chunkCount;
                result.add(new ActiveStructure(rootKey, faction, perChunkScore));
            }
        }
        return result;
    }

    public static List<ActiveStructure> getActiveStructures(ServerLevel level, BlockPos pos) {
        return getActiveStructures(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * 获取指定结构锚点的当前剩余基础分。
     */
    public static double getRemainingScore(ServerLevel level, long rootKey, double defaultBaseScore) {
        CleansedStructureStorage storage = CleansedStructureStorage.get(level);
        if (storage.isCleansed(rootKey)) return 0.0;
        Double saved = storage.getRemainingScore(rootKey);
        if (saved != null) {
            return saved;
        }
        return defaultBaseScore > 0.0 ? defaultBaseScore : DEFAULT_STRUCTURE_BASE_SCORE;
    }

    public static double getRemainingScore(ServerLevel level, long rootKey) {
        return getRemainingScore(level, rootKey, DEFAULT_STRUCTURE_BASE_SCORE);
    }

    /**
     * 削减结构基础分。若削减至 0，则触发肃清结算！
     * 返回实际扣除量。
     */
    public static double reduceStructureScore(ServerLevel level, long rootKey, double amount, double defaultBaseScore) {
        if (amount <= 0.0) return 0.0;
        CleansedStructureStorage storage = CleansedStructureStorage.get(level);
        if (storage.isCleansed(rootKey)) return 0.0;

        double current = getRemainingScore(level, rootKey, defaultBaseScore);
        double actualReduction = Math.min(current, amount);
        double next = current - actualReduction;

        if (next <= 0.001) {
            // 彻底肃清！
            storage.markCleansed(rootKey);
            CivilAdventureMod.LOGGER.info("[civil_adventure] 维度 {} 坐标 {} 处的冒险区结构已被彻底肃清！",
                    level.dimension().location(), rootKey);
            try {
                civil.civilization.ZonePolicyService zps = civil.CivilServices.getZonePolicyService();
                if (zps != null) {
                    zps.clear();
                }
            } catch (Throwable ignored) {}
        } else {
            storage.setRemainingScore(rootKey, next);
        }

        return actualReduction;
    }

    public static double reduceStructureScore(ServerLevel level, long rootKey, double amount) {
        return reduceStructureScore(level, rootKey, amount, DEFAULT_STRUCTURE_BASE_SCORE);
    }

    /**
     * 查询指定区块位置是否存在尚未肃清的冒险结构。
     * 若存在，返回第一个结构的唯一锚点 key；否则返回 0L。
     */
    public static long getActiveStructureKey(ServerLevel level, int cx, int cz) {
        List<ActiveStructure> list = getActiveStructures(level, cx, cz);
        return list.isEmpty() ? 0L : list.get(0).rootKey();
    }

    public static long getActiveStructureKey(ServerLevel level, BlockPos pos) {
        return getActiveStructureKey(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * 检查某位置是否被任意派系冒险区结构覆盖且未肃清。
     */
    public static boolean hasActiveAdventureStructure(ServerLevel level, BlockPos pos) {
        return !getActiveStructures(level, pos.getX() >> 4, pos.getZ() >> 4).isEmpty();
    }

    /**
     * 查询指定方块位置是否存在已经被彻底肃清的派系冒险结构。
     */
    public static boolean isStructureCleansed(ServerLevel level, BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        List<StructureStart> starts = level.structureManager().startsForStructure(new ChunkPos(cx, cz), s -> true);
        if (starts == null || starts.isEmpty()) return false;

        CleansedStructureStorage storage = CleansedStructureStorage.get(level);
        for (StructureStart start : starts) {
            if (start == null || !start.isValid()) continue;
            BoundingBox box = start.getBoundingBox();
            if (pos.getX() < box.minX() || pos.getX() > box.maxX() ||
                pos.getZ() < box.minZ() || pos.getZ() > box.maxZ()) {
                continue;
            }
            FactionDefinition faction = getFactionForStructure(level, start.getStructure());
            if (faction == null) continue;

            long rootKey = ChunkCoordPacker.pack(start.getChunkPos().x, start.getChunkPos().z);
            if (storage.isCleansed(rootKey)) {
                return true;
            }
        }
        return false;
    }

    public static void clearInMemory() {
        // 持久化存储由 CleansedStructureStorage 管理
    }
}
