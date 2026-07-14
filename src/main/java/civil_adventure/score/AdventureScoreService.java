package civil_adventure.score;

import civil_adventure.config.AdventureConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 区块级冒险区分数追踪。
 * 方块变化时全量扫描重算，查询时区域聚合。
 * 区块键自包含，不依赖文礼 VoxelChunkKey。
 */
public class AdventureScoreService {
    private final ConcurrentHashMap<String, Double> perChunkScores = new ConcurrentHashMap<>();

    public void shutdown() {
        perChunkScores.clear();
    }

    /** 区块卸载时清理对应分数（含所有 Y 层），防内存泄漏 */
    public void onChunkUnload(ServerLevel level, int cx, int cz) {
        String prefix = level.dimension().location().toString() + ":" + cx + "," + cz + ",";
        perChunkScores.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ── 结算 ──────────────────────────────────────────

    /** 方块变化 / 区块加载：全高度扫描该区块所有 Y 层 */
    public void recalculateChunkScore(ServerLevel level, BlockPos pos) {
        recalculateFullChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public void recalculateFullChunk(ServerLevel level, int cx, int cz) {
        String chunkKey = ChunkKey.key(level.dimension().location().toString(), cx, cz);
        double total = 0.0;
        int bx = cx << 4;
        int bz = cz << 4;
        // 扫 0~255 高度（覆盖 16 个截面）
        for (int sy = 0; sy < 16; sy++) {
            total += scanSection(level, bx, sy << 4, bz);
        }
        if (total > 0) perChunkScores.put(chunkKey, total);
        else perChunkScores.remove(chunkKey);
    }

    /** 扫描单个 16×16×16 截面 */
    private double scanSection(ServerLevel level, int startX, int startY, int startZ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        double total = 0.0;
        for (int y = startY; y < startY + 16; y++) {
            for (int z = startZ; z < startZ + 16; z++) {
                for (int x = startX; x < startX + 16; x++) {
                    Block block = level.getBlockState(cursor.set(x, y, z)).getBlock();
                    double w = AdventureBlockLoader.getWeight(block);
                    if (w > 0) total += w;
                }
            }
        }
        return total;
    }

    // ── 查询 ────────────────────────────────────────────

    /** 单个区块的原始冒险区分数 */
    public double queryScoreAt(ServerLevel level, BlockPos pos) {
        String key = ChunkKey.key(level.dimension().location().toString(),
            pos.getX() >> 4, pos.getZ() >> 4);
        return perChunkScores.getOrDefault(key, 0.0);
    }

    /**
     * 区域聚合原始分：累加检测半径内所有区块的分数。
     */
    public double getRegionScoreAt(ServerLevel level, BlockPos pos, int radius) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        String dim = level.dimension().location().toString();
        double total = 0.0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                total += perChunkScores.getOrDefault(
                    ChunkKey.key(dim, cx + dx, cz + dz), 0.0);
            }
        }
        return total;
    }

    /**
     * 归一化冒险区分数 [0, 1]。
     * 取检测半径内所有区块的原始分之和，归一化到 [0, 1]。
     */
    public double getNormalizedScoreAt(ServerLevel level, BlockPos pos) {
        int radius = AdventureConfig.DETECTION_RADIUS.get();
        double raw = getRegionScoreAt(level, pos, radius);
        double divisor = AdventureConfig.NORMALIZATION_DIVISOR.get();
        return Math.min(1.0, raw / divisor);
    }

    /** 检测半径内冒险区区块数 */
    public int getZoneSize(ServerLevel level, BlockPos pos, int radius) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        String dim = level.dimension().location().toString();
        double threshold = AdventureConfig.ZONE_THRESHOLD.get();
        double divisor = AdventureConfig.NORMALIZATION_DIVISOR.get();
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double raw = perChunkScores.getOrDefault(
                    ChunkKey.key(dim, cx + dx, cz + dz), 0.0);
                if (Math.min(1.0, raw / divisor) >= threshold) count++;
            }
        }
        return count;
    }
}
