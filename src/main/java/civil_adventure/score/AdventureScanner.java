package civil_adventure.score;

import civil_adventure.CivilAdventureMod;
import civil_adventure.faction.FactionDefinition;
import civil_adventure.faction.FactionRegistry;
import civil_adventure.override.CivillisStructureTracker;
import civil.CivilServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.List;

/**
 * 多派系高性能区块扫描器。
 * 1. 采用 LevelChunkSection.maybeHas(FactionRegistry::isAnyFactionBlock) 调色板快筛，
 *    无任何派系方块的 Section 纳秒级跳过。
 * 2. 动态读取 level.getMinSection() ~ level.getMaxSection() 全高度（-64~320）。
 * 3. 整合派系冒险区结构基础分（可被削减肃清，精准归属对应派系）。
 * 4. 整合文礼文明分（civil:civilization）。
 */
public final class AdventureScanner {

    private AdventureScanner() {}

    /**
     * 扫描指定区块，返回各派系分值数组（下标对应 FactionDefinition.index()）。
     */
    public static float[] scanChunkFactions(ServerLevel level, int cx, int cz) {
        return scanChunkFactions(level, null, cx, cz);
    }

    public static float[] scanChunkFactions(ServerLevel level, net.minecraft.world.level.chunk.ChunkAccess chunkAccess, int cx, int cz) {
        int count = FactionRegistry.getFactionCount();
        float[] scores = new float[count];

        // 1. 获取文礼文明分 (Index 0)
        var civ = CivilServices.getCivilizationService();
        if (civ != null) {
            BlockPos centerPos = new BlockPos((cx << 4) + 8, 64, (cz << 4) + 8);
            try {
                double cScore = civ.getScoreAt(level, centerPos);
                if (cScore >= 0.5) {
                    scores[0] = (float) cScore;
                }
            } catch (Exception ignored) {}
        }

        // 2. 扫描方块危险分（若无任何派系注册了方块，直接返回）
        if (!FactionRegistry.hasCustomBlocks()) {
            logNonZeroScores(cx, cz, scores);
            return scores;
        }

        net.minecraft.world.level.chunk.ChunkAccess targetChunk = chunkAccess;
        if (targetChunk == null) {
            targetChunk = level.getChunkSource().getChunkNow(cx, cz);
        }
        if (targetChunk == null) {
            logNonZeroScores(cx, cz, scores);
            return scores;
        }

        LevelChunkSection[] sections = targetChunk.getSections();
        if (sections == null || sections.length == 0) {
            logNonZeroScores(cx, cz, scores);
            return scores;
        }

        for (LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            // 调色板预过滤：若本 Section 调色板不含任何派系的方块，直接纳秒跳过
            if (!section.maybeHas(FactionRegistry::isAnyFactionBlock)) {
                continue;
            }

            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        if (state.isAir()) continue;
                        Block block = state.getBlock();

                        for (FactionDefinition faction : FactionRegistry.getAllFactions()) {
                            double w = faction.getBlockWeight(block);
                            if (w > 0.0) {
                                scores[faction.index()] += (float) w;
                            }
                        }
                    }
                }
            }
        }

        logNonZeroScores(cx, cz, scores);
        return scores;
    }

    /**
     * 判断区块是否存在非文明的敌对危险分（方块危险分或未肃清结构基础分）。
     * 只有危险分 ≥ 0.5 时才认定为威胁区块，杜绝微小浮点误差形成泛滥导线。
     */
    public static boolean hasDangerScore(float[] scores) {
        if (scores == null || scores.length <= 1) return false;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] >= 0.5f) {
                return true;
            }
        }
        return false;
    }

    private static void logNonZeroScores(int cx, int cz, float[] scores) {
        StringBuilder sb = null;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] >= 0.1f) {
                if (sb == null) sb = new StringBuilder();
                else sb.append(", ");
                FactionDefinition f = FactionRegistry.getByIndex((byte) i);
                sb.append(f != null ? f.id().toString() : ("idx_" + i))
                  .append("=")
                  .append(String.format("%.1f", scores[i]));
            }
        }
        if (sb != null) {
            CivilAdventureMod.LOGGER.debug("[civil_adventure][Step 3/ChunkScan] 区块 [{}, {}] 扫描分值: [{}]", cx, cz, sb);
        }
    }

    /** 兼容旧版单一分数查询 */
    public static double scanChunk(ServerLevel level, int cx, int cz) {
        float[] scores = scanChunkFactions(level, cx, cz);
        return scores.length > 1 ? scores[1] : 0.0;
    }
}
