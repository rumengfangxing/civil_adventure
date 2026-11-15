package civil_adventure.cluster;

import civil_adventure.core.ChunkCoordPacker;
import civil_adventure.faction.FactionDefinition;
import civil_adventure.faction.event.FactionDominanceChangeEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.MinecraftForge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宏观连通域聚类管理器。
 * 1. 采用空间 8-连通域 Flood-Fill 算法将相邻地块聚类为宏观领地。
 * 2. 对每个宏观领地执行“成王败寇”总分仲裁。
 * 3. 统治权更替时，自动触发 FactionDominanceChangeEvent。
 */
public class MacroClusterManager {

    private final ResourceLocation dimension;
    private volatile Map<Integer, MacroCluster> clustersById = Map.of();
    private volatile Map<Long, MacroCluster> clusterByChunk = Map.of();
    private int nextClusterId = 1;

    public MacroClusterManager(ResourceLocation dimension) {
        this.dimension = dimension;
    }

    public synchronized void rebuildClusters(ServerLevel level, Map<Long, float[]> activeChunkScores) {
        Map<Integer, MacroCluster> prevClusters = this.clustersById;
        if (activeChunkScores == null || activeChunkScores.isEmpty()) {
            this.clustersById = Map.of();
            this.clusterByChunk = Map.of();
            for (MacroCluster oldCluster : prevClusters.values()) {
                if (oldCluster.hasDominance()) {
                    BlockPos ep = oldCluster.getEpicenter();
                    MinecraftForge.EVENT_BUS.post(new FactionDominanceChangeEvent(
                            level, new ChunkPos(ep), oldCluster.getDominantIndex(), (byte) -1, 0.0f, 0.0f
                    ));
                }
            }
            return;
        }

        Set<Long> visited = new HashSet<>();
        List<MacroCluster> newClusters = new ArrayList<>();
        Map<Long, MacroCluster> newClusterByChunk = new HashMap<>();
        List<FactionDominanceChangeEvent> pendingEvents = new ArrayList<>();

        // 8 方向邻居偏移
        int[][] directions = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };

        for (Long startKey : activeChunkScores.keySet()) {
            if (visited.contains(startKey)) continue;

            MacroCluster cluster = new MacroCluster(nextClusterId++);
            Queue<Long> queue = new ArrayDeque<>();
            queue.add(startKey);
            visited.add(startKey);

            while (!queue.isEmpty()) {
                long current = queue.poll();
                int cx = ChunkCoordPacker.unpackCx(current);
                int cz = ChunkCoordPacker.unpackCz(current);
                float[] scores = activeChunkScores.get(current);

                cluster.addChunk(cx, cz, scores);
                newClusterByChunk.put(current, cluster);

                for (int[] dir : directions) {
                    int ncx = cx + dir[0];
                    int ncz = cz + dir[1];
                    long nKey = ChunkCoordPacker.pack(ncx, ncz);

                    if (!visited.contains(nKey) && activeChunkScores.containsKey(nKey)) {
                        visited.add(nKey);
                        queue.add(nKey);
                    }
                }
            }

            // 统一仲裁领地统治者
            cluster.finalizeEvaluation();
            newClusters.add(cluster);

            // [修复 M-03] 统计连通分量内所有旧区块的主导派系（投票制），避免单区块扩张谎报易主
            Map<Byte, Integer> oldDomVotes = new HashMap<>();
            for (Long ck : cluster.getChunkKeys()) {
                MacroCluster prev = clusterByChunk.get(ck);
                if (prev != null && prev.hasDominance()) {
                    oldDomVotes.merge(prev.getDominantIndex(), 1, Integer::sum);
                }
            }
            byte oldDom = -1;
            int maxVotes = 0;
            for (Map.Entry<Byte, Integer> vote : oldDomVotes.entrySet()) {
                if (vote.getValue() > maxVotes) {
                    maxVotes = vote.getValue();
                    oldDom = vote.getKey();
                }
            }
            byte newDom = cluster.getDominantIndex();

            if (oldDom != newDom && (oldDom != -1 || newDom != -1)) {
                BlockPos ep = cluster.getEpicenter();
                ChunkPos chunkPos = (ep != null) ? new ChunkPos(ep) : new ChunkPos(ChunkCoordPacker.unpackCx(startKey), ChunkCoordPacker.unpackCz(startKey));
                pendingEvents.add(new FactionDominanceChangeEvent(
                        level, chunkPos, oldDom, newDom,
                        cluster.getDominantTotalScore(), cluster.getDominantMargin()
                ));
            }
        }

        // 检查是否有先前存在的聚类彻底瓦解消失（多聚类部分铲除场景）
        for (MacroCluster oldCluster : prevClusters.values()) {
            if (!oldCluster.hasDominance()) continue;
            boolean stillExists = false;
            for (Long ck : oldCluster.getChunkKeys()) {
                if (newClusterByChunk.containsKey(ck)) {
                    stillExists = true;
                    break;
                }
            }
            if (!stillExists) {
                BlockPos ep = oldCluster.getEpicenter();
                pendingEvents.add(new FactionDominanceChangeEvent(
                        level, new ChunkPos(ep), oldCluster.getDominantIndex(), (byte) -1, 0.0f, 0.0f
                ));
            }
        }

        // 提交原子更新，消除并发清空窗口期
        Map<Integer, MacroCluster> nextById = new HashMap<>(newClusters.size());
        for (MacroCluster c : newClusters) {
            nextById.put(c.getId(), c);
        }
        this.clustersById = Collections.unmodifiableMap(nextById);
        this.clusterByChunk = Collections.unmodifiableMap(newClusterByChunk);

        // [修复 M-03] 在集群内部状态原子更新后，再发布 Forge 事件，防止回调中读到脏状态
        for (FactionDominanceChangeEvent evt : pendingEvents) {
            MinecraftForge.EVENT_BUS.post(evt);
        }

        civil_adventure.CivilAdventureMod.LOGGER.debug("[civil_adventure][Step 4/ClusterRebuild] 维度 {} 宏观连通域聚类完成: 生成 {} 个领地聚类, 覆盖 {} 个活跃区块",
                dimension, newClusters.size(), newClusterByChunk.size());
        for (MacroCluster c : newClusters) {
            FactionDefinition dom = c.getDominantFaction();
            String domName = dom != null ? dom.id().toString() : "无统治/荒野";
            civil_adventure.CivilAdventureMod.LOGGER.debug("  -> 聚类 #{} [{}]: 显性总分={}, 次席分={}, 覆盖区块数={}, 质心=({}, {}, {})",
                    c.getId(), domName, String.format("%.1f", c.getDominantTotalScore()),
                    String.format("%.1f", c.getRunnerUpTotalScore()), c.getChunkCount(),
                    c.getEpicenter().getX(), c.getEpicenter().getY(), c.getEpicenter().getZ());
        }
    }

    public Map<Long, Byte> getDominanceMap() {
        Map<Long, Byte> result = new HashMap<>();
        for (Map.Entry<Long, MacroCluster> e : clusterByChunk.entrySet()) {
            MacroCluster c = e.getValue();
            if (c != null && c.hasDominance()) {
                result.put(e.getKey(), c.getDominantIndex());
            }
        }
        return result;
    }

    public MacroCluster getClusterAt(int cx, int cz) {
        return clusterByChunk.get(ChunkCoordPacker.pack(cx, cz));
    }

    public MacroCluster getClusterAt(BlockPos pos) {
        return getClusterAt(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public FactionDefinition getDominantFactionAt(BlockPos pos) {
        MacroCluster cluster = getClusterAt(pos);
        return (cluster != null) ? cluster.getDominantFaction() : null;
    }

    public Collection<MacroCluster> getAllClusters() {
        return clustersById.values();
    }

    public void clear() {
        this.clustersById = Map.of();
        this.clusterByChunk = Map.of();
    }
}
