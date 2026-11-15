package civil_adventure.cluster;

import civil_adventure.core.ChunkCoordPacker;
import civil_adventure.faction.FactionDefinition;
import civil_adventure.faction.FactionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.*;

/**
 * 宏观连通域聚类实体（Macro Cluster）。
 * 遵循 2.0-FINAL 架构规范：
 * - 8-连通相连的所有非零威胁区块合并为一个宏观领地
 * - 成王败寇（Winner-Takes-All）：整片领地统一划归给总分最高者
 * - 孤立不相连的聚类各自独立裁决，呈现异色割据态势
 * - 携带开放的 CompoundTag 自定义数据槽供 KubeJS 自由挂载数据
 */
public final class MacroCluster {

    private final int id;
    private final Set<Long> chunkKeys = new HashSet<>();
    private int minCx = Integer.MAX_VALUE;
    private int maxCx = Integer.MIN_VALUE;
    private int minCz = Integer.MAX_VALUE;
    private int maxCz = Integer.MIN_VALUE;

    private float[] factionTotals;
    private byte dominantIndex = -1;
    private float dominantTotalScore = 0.0f;
    private float runnerUpTotalScore = 0.0f;

    private BlockPos epicenter = BlockPos.ZERO;
    private final CompoundTag customData = new CompoundTag();

    public MacroCluster(int id) {
        this.id = id;
        this.factionTotals = new float[FactionRegistry.getFactionCount()];
    }

    public int getId() {
        return id;
    }

    public synchronized Set<Long> getChunkKeys() {
        return Collections.unmodifiableSet(chunkKeys);
    }

    public synchronized int getChunkCount() {
        return chunkKeys.size();
    }

    public synchronized boolean containsChunk(int cx, int cz) {
        return chunkKeys.contains(ChunkCoordPacker.pack(cx, cz));
    }

    public synchronized boolean containsBlock(BlockPos pos) {
        return containsChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public synchronized void addChunk(int cx, int cz, float[] scores) {
        long key = ChunkCoordPacker.pack(cx, cz);
        if (chunkKeys.add(key)) {
            minCx = Math.min(minCx, cx);
            maxCx = Math.max(maxCx, cx);
            minCz = Math.min(minCz, cz);
            maxCz = Math.max(maxCz, cz);

            if (scores != null) {
                ensureFactionCapacity(scores.length);
                for (int i = 0; i < scores.length; i++) {
                    factionTotals[i] += scores[i];
                }
            }
        }
    }

    private void ensureFactionCapacity(int count) {
        if (factionTotals.length < count) {
            factionTotals = Arrays.copyOf(factionTotals, count);
        }
    }

    /** 计算宏观总分仲裁：成王败寇，总分最高者整体通吃统治整片大地盘 */
    public synchronized void finalizeEvaluation() {
        byte bestIdx = -1;
        float max = 0.0f;
        float runnerUp = 0.0f;

        for (byte i = 0; i < factionTotals.length; i++) {
            float s = factionTotals[i];
            if (s > max) {
                runnerUp = max;
                max = s;
                bestIdx = i;
            } else if (s > runnerUp) {
                runnerUp = s;
            }
        }

        if (bestIdx >= 0) {
            FactionDefinition def = FactionRegistry.getByIndex(bestIdx);
            double threshold = (def != null) ? def.minDominanceScore() : 1.0;
            if (max >= threshold) {
                this.dominantIndex = bestIdx;
                this.dominantTotalScore = max;
                this.runnerUpTotalScore = runnerUp;
            } else {
                this.dominantIndex = -1;
                this.dominantTotalScore = max;
                this.runnerUpTotalScore = runnerUp;
            }
        } else {
            this.dominantIndex = -1;
            this.dominantTotalScore = 0.0f;
            this.runnerUpTotalScore = 0.0f;
        }

        // 计算几何质心
        if (!chunkKeys.isEmpty()) {
            int avgX = ((minCx + maxCx) << 3) + 8;
            int avgZ = ((minCz + maxCz) << 3) + 8;
            this.epicenter = new BlockPos(avgX, 64, avgZ);
        }
    }

    public synchronized byte getDominantIndex() {
        return dominantIndex;
    }

    public synchronized FactionDefinition getDominantFaction() {
        return FactionRegistry.getByIndex(dominantIndex);
    }

    public synchronized boolean hasDominance() {
        return dominantIndex >= 0;
    }

    public synchronized float getDominantTotalScore() {
        return dominantTotalScore;
    }

    public synchronized float getRunnerUpTotalScore() {
        return runnerUpTotalScore;
    }

    public synchronized float getDominantMargin() {
        return Math.max(0.0f, dominantTotalScore - runnerUpTotalScore);
    }

    public synchronized BlockPos getEpicenter() {
        return epicenter;
    }

    public synchronized CompoundTag getCustomData() {
        return customData;
    }

    public synchronized int getMinCx() { return minCx; }
    public synchronized int getMaxCx() { return maxCx; }
    public synchronized int getMinCz() { return minCz; }
    public synchronized int getMaxCz() { return maxCz; }
}
