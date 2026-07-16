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

    // ── 持久分数存储 ──────────────────────────────────
    private final ConcurrentHashMap<String, Double> perChunkScores = new ConcurrentHashMap<>();

    // ── 冷却批处理 ─────────────────────────────────────
    private final ConcurrentHashMap<String, String> dirtyChunks = new ConcurrentHashMap<>(); // dim:cx,cz,0 → dim
    private long cooldownEnd = -1;

    public void shutdown() {
        perChunkScores.clear();
        dirtyChunks.clear();
        cooldownEnd = -1;
    }

    // ===================================================================
    //  冷却批处理
    // ===================================================================

    /** 标记脏区块（方块变化时调用），不立即重算 */
    public void markDirty(String dim, int cx, int cz) {
        String key = ChunkKey.key(dim, cx, cz);
        dirtyChunks.put(key, dim);
        if (cooldownEnd < 0) cooldownEnd = 1; // 启动冷却
    }

    /** 每 tick 调用：冷却到期后批量重算所有脏区块 */
    public void tickCooldown(ServerLevel level, long currentTick) {
        if (cooldownEnd < 0) return;

        if (cooldownEnd == 1) {
            // 第一次标记后的 tick：设定冷却到期时间
            cooldownEnd = currentTick + AdventureConfig.RECALC_COOLDOWN.get();
            return;
        }

        if (currentTick < cooldownEnd) return;

        // 冷却到期 → 批量重算所有脏区块
        for (String key : dirtyChunks.keySet()) {
            // key 格式: "minecraft:overworld:-32,-48,0"
            int lastColon = key.lastIndexOf(':');
            if (lastColon < 0) continue;
            String[] coords = key.substring(lastColon + 1).split(",");
            if (coords.length < 2) continue;
            int cx = Integer.parseInt(coords[0]);
            int cz = Integer.parseInt(coords[1]);
            recalculateFullChunk(level, cx, cz);
        }
        dirtyChunks.clear();
        cooldownEnd = -1;
    }

    // ===================================================================
    //  结算
    // ===================================================================

    public void recalculateChunkScore(ServerLevel level, BlockPos pos) {
        recalculateFullChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    public void recalculateFullChunk(ServerLevel level, int cx, int cz) {
        String chunkKey = ChunkKey.key(level.dimension().location().toString(), cx, cz);
        double total = 0.0;
        int bx = cx << 4;
        int bz = cz << 4;
        for (int sy = 0; sy < 16; sy++) {
            total += scanSection(level, bx, sy << 4, bz);
        }
        if (total > 0) perChunkScores.put(chunkKey, total);
        else perChunkScores.remove(chunkKey);
    }

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

    // ===================================================================
    //  查询
    // ===================================================================

    public double queryScoreAt(ServerLevel level, BlockPos pos) {
        String key = ChunkKey.key(level.dimension().location().toString(),
            pos.getX() >> 4, pos.getZ() >> 4);
        return perChunkScores.getOrDefault(key, 0.0);
    }

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

    public double getNormalizedScoreAt(ServerLevel level, BlockPos pos) {
        int radius = AdventureConfig.DETECTION_RADIUS.get();
        double raw = getRegionScoreAt(level, pos, radius);
        double divisor = AdventureConfig.NORMALIZATION_DIVISOR.get();
        return Math.min(1.0, raw / divisor);
    }

    /** 检测半径内区域原始分之和（无归一化，供 API 返回大数值） */
    public double getRawScoreAt(ServerLevel level, BlockPos pos) {
        int radius = AdventureConfig.DETECTION_RADIUS.get();
        return getRegionScoreAt(level, pos, radius);
    }

    /** 冒险区中心坐标（检测半径内所有冒险区块的平均位置） */
    public BlockPos getZoneCenter(ServerLevel level, BlockPos pos) {
        int radius = AdventureConfig.DETECTION_RADIUS.get();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        String dim = level.dimension().location().toString();
        double threshold = AdventureConfig.ZONE_THRESHOLD.get();
        double divisor = AdventureConfig.NORMALIZATION_DIVISOR.get();
        int count = 0;
        int sumX = 0, sumZ = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double raw = perChunkScores.getOrDefault(
                    ChunkKey.key(dim, cx + dx, cz + dz), 0.0);
                if (Math.min(1.0, raw / divisor) >= threshold) {
                    count++;
                    sumX += (cx + dx) * 16 + 8;
                    sumZ += (cz + dz) * 16 + 8;
                }
            }
        }
        if (count == 0) return pos;
        return new BlockPos(sumX / count, pos.getY(), sumZ / count);
    }

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

    // ===================================================================
    //  区块卸载
    // ===================================================================

    public void onChunkUnload(ServerLevel level, int cx, int cz) {
        String prefix = level.dimension().location().toString() + ":" + cx + "," + cz + ",";
        perChunkScores.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
