package civil_adventure.score;

import civil_adventure.CivilAdventureMod;
import civil_adventure.cluster.MacroCluster;
import civil_adventure.cluster.MacroClusterManager;
import civil_adventure.config.AdventureConfig;
import civil_adventure.core.ChunkCoordPacker;
import civil_adventure.faction.FactionDefinition;
import civil_adventure.faction.FactionRegistry;
import civil_adventure.network.AdventureClusterSyncPayload;
import civil_adventure.override.CivillisStructureTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 核心多派系生态威胁分与宏观领地聚类服务。
 * 遵循 2.0-FINAL 架构规范：
 * 1. 管理各维度按区块存储的多派系分值张量 (float[])。
 * 2. 驱动 MacroClusterManager 执行 8-连通域成王败寇宏观领地聚类。
 * 3. 压制核心支持全额并发削减一切非文明、非白名单派系。
 * 4. 完美接管文礼结构基础分并在清零时肃清，动态叠加，杜绝僵尸残留。
 * 5. 多维度独立防抖计时，网络包向客户端同步显性统治区块分布。
 */
public class AdventureScoreService {

    // 维度 ResourceLocation → (压缩区块坐标 Long → 各派系有效分 float[])
    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, float[]>> chunkScores =
            new ConcurrentHashMap<>();

    // 维度 ResourceLocation → (压缩区块坐标 Long → 各派系临时压制量 float[])
    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<Long, float[]>> chunkSuppression =
            new ConcurrentHashMap<>();

    // 维度 ResourceLocation → MacroClusterManager
    private final ConcurrentHashMap<ResourceLocation, MacroClusterManager> clusterManagers =
            new ConcurrentHashMap<>();

    // 待重算区块坐标集合 Set<Long>
    private final ConcurrentHashMap<ResourceLocation, Set<Long>> dirtyChunks =
            new ConcurrentHashMap<>();

    // 维度 ResourceLocation → 冷却到期时间戳（独立防抖，解决多维度饿死）
    private final ConcurrentHashMap<ResourceLocation, Long> cooldownEnds =
            new ConcurrentHashMap<>();

    // ── 突击前哨站：活跃压制泡追踪（防止前哨安全空腔被形态学回填） ──
    private final ConcurrentHashMap<ResourceLocation, ConcurrentHashMap<BlockPos, Float>> activeSuppressorBubbles =
            new ConcurrentHashMap<>();

    // ── 优雅倒灌回弹波实体与列表 ──
    public static class DecayWave {
        public final ResourceLocation dim;
        public final BlockPos pos;
        public final int centerCx;
        public final int centerCz;
        public float currentRadius;
        public final Map<Long, float[]> remainingSuppression;
        public int tickTimer;

        public DecayWave(ResourceLocation dim, BlockPos pos, float initialRadius, Map<Long, float[]> suppression) {
            this.dim = dim;
            this.pos = pos;
            this.centerCx = pos.getX() >> 4;
            this.centerCz = pos.getZ() >> 4;
            this.currentRadius = initialRadius;
            this.remainingSuppression = new HashMap<>(suppression);
            this.tickTimer = 40; // 每 40 tick (2 秒) 向内收缩 1 个区块环
        }
    }

    private final List<DecayWave> activeDecayWaves = new CopyOnWriteArrayList<>();

    public void registerActiveSuppressor(ResourceLocation dim, BlockPos pos, float waveRadius) {
        activeSuppressorBubbles.computeIfAbsent(dim, k -> new ConcurrentHashMap<>()).put(pos, waveRadius);
    }

    public void unregisterActiveSuppressor(ResourceLocation dim, BlockPos pos) {
        Map<BlockPos, Float> map = activeSuppressorBubbles.get(dim);
        if (map != null) {
            map.remove(pos);
            if (map.isEmpty()) {
                activeSuppressorBubbles.remove(dim);
            }
        }
    }

    public boolean isInsideActiveSuppressorBubble(ResourceLocation dim, int cx, int cz) {
        Map<BlockPos, Float> map = activeSuppressorBubbles.get(dim);
        if (map != null && !map.isEmpty()) {
            for (Map.Entry<BlockPos, Float> entry : map.entrySet()) {
                BlockPos pos = entry.getKey();
                float waveRadius = entry.getValue();
                int centerCx = pos.getX() >> 4;
                int centerCz = pos.getZ() >> 4;
                double dist = Math.sqrt((cx - centerCx) * (cx - centerCx) + (cz - centerCz) * (cz - centerCz));
                if (dist <= waveRadius) {
                    return true;
                }
            }
        }
        for (DecayWave wave : activeDecayWaves) {
            if (wave.dim.equals(dim) && wave.currentRadius > 0.0f) {
                double dist = Math.sqrt((cx - wave.centerCx) * (cx - wave.centerCx) + (cz - wave.centerCz) * (cz - wave.centerCz));
                if (dist <= wave.currentRadius) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasActiveSuppressionNear(ResourceLocation dim, BlockPos pos) {
        var suppMap = chunkSuppression.get(dim);
        if (suppMap == null || suppMap.isEmpty()) return false;
        long centerKey = ChunkCoordPacker.pack(pos.getX() >> 4, pos.getZ() >> 4);
        float[] arr = suppMap.get(centerKey);
        if (arr != null) {
            for (float v : arr) {
                if (v > 0.001f) return true;
            }
        }
        return false;
    }

    public void startGracefulDecay(ServerLevel level, BlockPos pos, Map<Long, float[]> suppressionToRelease, float initialRadius) {
        if (suppressionToRelease == null || suppressionToRelease.isEmpty()) return;
        ResourceLocation dim = level.dimension().location();
        // [修复 V3-C01] 若同一位置已有残余倒灌波，将其未释放的压制量合并进新倒灌波，绝不丢弃账目
        Map<Long, float[]> mergedSuppression = new HashMap<>();
        suppressionToRelease.forEach((k, v) -> mergedSuppression.put(k, Arrays.copyOf(v, v.length)));

        DecayWave prev = cancelGracefulDecay(level, pos);
        if (prev != null) {
            prev.remainingSuppression.forEach((key, arr) -> {
                mergedSuppression.merge(key, arr, (oldArr, newArr) -> {
                    float[] m = new float[Math.max(oldArr.length, newArr.length)];
                    for (int i = 0; i < m.length; i++) {
                        m[i] = (i < oldArr.length ? oldArr[i] : 0.0f) + (i < newArr.length ? newArr[i] : 0.0f);
                    }
                    return m;
                });
            });
            initialRadius = Math.max(initialRadius, prev.currentRadius);
        }

        activeDecayWaves.add(new DecayWave(dim, pos, Math.max(1.0f, initialRadius), mergedSuppression));
        CivilAdventureMod.LOGGER.debug("[civil_adventure][Step 7/Suppression] 压制核心在 ({}, {}, {}) 启动平滑倒灌回弹: 初始半径 {}, 待倒灌区块数 {}",
                pos.getX(), pos.getY(), pos.getZ(), String.format("%.2f", initialRadius), mergedSuppression.size());
    }

    /**
     * [修复 V3-C01] 中断并返回指定位置的倒灌波（供压制核心重新开机时无缝接管账单与半径）。
     */
    public DecayWave cancelGracefulDecay(ServerLevel level, BlockPos pos) {
        ResourceLocation dim = level.dimension().location();
        for (DecayWave w : activeDecayWaves) {
            if (w.dim.equals(dim) && w.pos.equals(pos)) {
                activeDecayWaves.remove(w);
                return w;
            }
        }
        return null;
    }

    public void shutdown() {
        chunkScores.clear();
        chunkSuppression.clear();
        clusterManagers.clear();
        dirtyChunks.clear();
        cooldownEnds.clear();
        activeSuppressorBubbles.clear();
        activeDecayWaves.clear();
        CivillisStructureTracker.clearInMemory();
    }

    public MacroClusterManager getClusterManager(ResourceLocation dim) {
        return clusterManagers.computeIfAbsent(dim, MacroClusterManager::new);
    }

    public MacroCluster getClusterAt(ServerLevel level, BlockPos pos) {
        return getClusterManager(level.dimension().location()).getClusterAt(pos);
    }

    public MacroCluster getClusterAt(ServerLevel level, int cx, int cz) {
        return getClusterManager(level.dimension().location()).getClusterAt(cx, cz);
    }

    public MacroCluster getClusterAt(ResourceLocation dim, int cx, int cz) {
        return getClusterManager(dim).getClusterAt(cx, cz);
    }

    public FactionDefinition getDominantFactionAt(ServerLevel level, BlockPos pos) {
        return getClusterManager(level.dimension().location()).getDominantFactionAt(pos);
    }

    // ===================================================================
    //  脏标记与冷却批处理 (Debounce)
    // ===================================================================

    public void markDirty(ResourceLocation dim, int cx, int cz) {
        long key = ChunkCoordPacker.pack(cx, cz);
        dirtyChunks.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet()).add(key);
        cooldownEnds.putIfAbsent(dim, 1L);
    }

    public void markDirty(String dimStr, int cx, int cz) {
        markDirty(new ResourceLocation(dimStr), cx, cz);
    }

    public void tickCooldown(ServerLevel level, long currentTick) {
        ResourceLocation dim = level.dimension().location();

        // 驱动平滑倒灌回弹波
        tickDecayWaves(level, currentTick);

        Long end = cooldownEnds.get(dim);
        if (end == null || end < 0) return;

        if (end == 1L) {
            cooldownEnds.put(dim, currentTick + AdventureConfig.RECALC_COOLDOWN.get());
            return;
        }

        if (currentTick < end) return;

        cooldownEnds.remove(dim);
        Set<Long> set = dirtyChunks.remove(dim);
        if (set != null && !set.isEmpty()) {
            for (long packed : set) {
                int cx = ChunkCoordPacker.unpackCx(packed);
                int cz = ChunkCoordPacker.unpackCz(packed);
                recalculateFullChunk(level, cx, cz);
            }
            // 重构宏观连通域聚类
            rebuildClusters(level);
        }
    }

    private void tickDecayWaves(ServerLevel level, long currentTick) {
        if (activeDecayWaves.isEmpty()) return;
        ResourceLocation dim = level.dimension().location();
        boolean anyChanged = false;

        for (DecayWave wave : activeDecayWaves) {
            if (!wave.dim.equals(dim)) continue;

            if (--wave.tickTimer <= 0) {
                wave.tickTimer = 40; // 每 40 tick (2 秒) 收缩一环
                float targetRadius = wave.currentRadius - 1.0f;
                Map<Long, float[]> toReleaseNow = new HashMap<>();

                var it = wave.remainingSuppression.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    long key = entry.getKey();
                    int cx = ChunkCoordPacker.unpackCx(key);
                    int cz = ChunkCoordPacker.unpackCz(key);
                    double dist = Math.sqrt((cx - wave.centerCx) * (cx - wave.centerCx) + (cz - wave.centerCz) * (cz - wave.centerCz));

                    if (targetRadius <= 0.0f || dist > targetRadius) {
                        toReleaseNow.put(key, entry.getValue());
                        it.remove();
                    }
                }

                if (!toReleaseNow.isEmpty()) {
                    removeSuppressionConcurrentlyNoRebuild(level, toReleaseNow);
                    anyChanged = true;
                }

                wave.currentRadius = targetRadius;
                if (wave.currentRadius <= 0.0f || wave.remainingSuppression.isEmpty()) {
                    activeDecayWaves.remove(wave);
                }
            }
        }

        if (anyChanged) {
            rebuildClusters(level);
        }
    }

    private void removeSuppressionConcurrentlyNoRebuild(ServerLevel level, Map<Long, float[]> toRelease) {
        ResourceLocation dim = level.dimension().location();
        var suppMap = chunkSuppression.get(dim);
        if (suppMap == null) return;

        for (Map.Entry<Long, float[]> e : toRelease.entrySet()) {
            long key = e.getKey();
            float[] toRemove = e.getValue();
            float[] curSupp = suppMap.get(key);
            if (curSupp != null) {
                for (int i = 0; i < Math.min(curSupp.length, toRemove.length); i++) {
                    curSupp[i] = Math.max(0.0f, curSupp[i] - toRemove[i]);
                }
            }
        }
    }

    public void recalculateChunkScore(ServerLevel level, BlockPos pos) {
        recalculateFullChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
        rebuildClusters(level);
    }

    public void recalculateFullChunk(ServerLevel level, int cx, int cz) {
        ResourceLocation dim = level.dimension().location();
        long key = ChunkCoordPacker.pack(cx, cz);
        float[] rawScores = AdventureScanner.scanChunkFactions(level, cx, cz);

        var dimMap = chunkScores.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        boolean hasDanger = AdventureScanner.hasDangerScore(rawScores);
        boolean hasCivil = rawScores.length > 0 && rawScores[0] >= 0.5f;
        boolean hasStruct = !CivillisStructureTracker.getActiveStructures(level, cx, cz).isEmpty();

        if (hasDanger || hasCivil || hasStruct) {
            dimMap.put(key, rawScores);
        } else {
            dimMap.remove(key);
        }
    }

    public void onChunkLoaded(ServerLevel level, net.minecraft.world.level.chunk.ChunkAccess chunk, int cx, int cz) {
        ResourceLocation dim = level.dimension().location();
        long key = ChunkCoordPacker.pack(cx, cz);
        float[] rawScores = AdventureScanner.scanChunkFactions(level, chunk, cx, cz);

        var dimMap = chunkScores.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        boolean hasDanger = AdventureScanner.hasDangerScore(rawScores);
        boolean hasCivil = rawScores.length > 0 && rawScores[0] >= 0.5f;
        boolean hasStruct = !CivillisStructureTracker.getActiveStructures(level, cx, cz).isEmpty();

        if (hasDanger || hasCivil || hasStruct) {
            dimMap.put(key, rawScores);
            if (hasDanger || hasStruct) {
                markDirty(dim, cx, cz);
            }
        } else {
            if (dimMap.remove(key) != null) {
                markDirty(dim, cx, cz);
            }
        }
    }

    public void rebuildClustersImmediately(ServerLevel level) {
        rebuildClusters(level);
    }

    private void rebuildClusters(ServerLevel level) {
        ResourceLocation dim = level.dimension().location();
        var rawMap = chunkScores.get(dim);
        if (rawMap == null || rawMap.isEmpty()) {
            getClusterManager(dim).clear();
            syncToPlayers(level);
            return;
        }

        var suppMap = chunkSuppression.get(dim);
        Map<Long, float[]> effectiveMap = new HashMap<>();

        for (Map.Entry<Long, float[]> e : rawMap.entrySet()) {
            long key = e.getKey();
            float[] raw = e.getValue();
            float[] supp = (suppMap != null) ? suppMap.get(key) : null;

            int cx = ChunkCoordPacker.unpackCx(key);
            int cz = ChunkCoordPacker.unpackCz(key);

            float[] eff = new float[raw.length];
            for (int i = 0; i < raw.length; i++) {
                float s = raw[i] - ((supp != null && i < supp.length) ? supp[i] : 0.0f);
                eff[i] = Math.max(0.0f, s);
            }

            // 动态叠加该区块当前未肃清的结构基础分（解耦方块扫描，杜绝已肃清结构僵尸残留）
            List<CivillisStructureTracker.ActiveStructure> activeStructs =
                    CivillisStructureTracker.getActiveStructures(level, cx, cz);
            for (CivillisStructureTracker.ActiveStructure as : activeStructs) {
                byte idx = as.faction().index();
                if (idx >= 0 && idx < eff.length) {
                    eff[idx] += (float) as.remainingScore();
                }
            }

            // 若该区块结构已肃清且纯方块分与文明分全为 0，顺手清理空壳 Key，避免后续重复查询
            if (activeStructs.isEmpty() && !AdventureScanner.hasDangerScore(raw) && (raw.length == 0 || raw[0] < 0.5f)) {
                rawMap.remove(key);
                continue;
            }

            boolean hasEffectiveDanger = false;
            for (int i = 1; i < eff.length; i++) {
                if (eff[i] >= 0.5f) {
                    hasEffectiveDanger = true;
                    break;
                }
            }

            // 核心机制：宏观领地聚类必须仅由【实质性危险区块】发起连通！
            if (hasEffectiveDanger) {
                effectiveMap.put(key, eff);
            }
        }

        // === 形态学微隙/河道闭合填充 (Anti-Fissure Gap Infill) ===
        // 防止自然地形中的单区块峡谷、河道或空地将大连通域腰斩并产生双重对峙结界墙。
        // 但若该空隙处于某个活跃压制核心的安全泡内，则严禁填充（保障突击前哨站的安全空腔）。
        if (!effectiveMap.isEmpty()) {
            Map<Long, float[]> infillMap = new HashMap<>();
            int[][] cardinalOpposites = {
                {-1, 0, 1, 0},  // West & East
                {0, -1, 0, 1}   // North & South
            };

            for (Long activeKey : effectiveMap.keySet()) {
                int acx = ChunkCoordPacker.unpackCx(activeKey);
                int acz = ChunkCoordPacker.unpackCz(activeKey);

                int[][] directNeighbors = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
                for (int[] dn : directNeighbors) {
                    int ncx = acx + dn[0];
                    int ncz = acz + dn[1];
                    long candidateKey = ChunkCoordPacker.pack(ncx, ncz);

                    if (effectiveMap.containsKey(candidateKey) || infillMap.containsKey(candidateKey)) {
                        continue;
                    }

                    // 核心校验：如果该区块处于任意活跃压制核心的安全泡内，绝对不填补（保障前哨站净空）
                    if (isInsideActiveSuppressorBubble(dim, ncx, ncz)) {
                        continue;
                    }

                    // [修复 V3-M01] 保证候选区块不是文明保护区（同时检查缓存与文礼实时文明分，防止新建聚落被侵蚀）
                    float[] candRaw = (rawMap != null) ? rawMap.get(candidateKey) : null;
                    if (candRaw != null && candRaw.length > 0 && candRaw[0] >= 0.5f) {
                        continue;
                    }
                    if (candRaw == null) {
                        var civ = civil.CivilServices.getCivilizationService();
                        if (civ != null) {
                            try {
                                BlockPos candCenter = new BlockPos((ncx << 4) + 8, 64, (ncz << 4) + 8);
                                if (civ.getScoreAt(level, candCenter) >= 0.5) {
                                    continue;
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    boolean shouldInfill = false;
                    byte dominantFactionIdx = -1;

                    // 1. 对向夹击判定：东西两头都是同派系危险，或南北两头都是同派系危险
                    for (int[] opp : cardinalOpposites) {
                        long k1 = ChunkCoordPacker.pack(ncx + opp[0], ncz + opp[1]);
                        long k2 = ChunkCoordPacker.pack(ncx + opp[2], ncz + opp[3]);
                        float[] eff1 = effectiveMap.get(k1);
                        float[] eff2 = effectiveMap.get(k2);
                        if (eff1 != null && eff2 != null) {
                            byte dom1 = getDominantFactionIndex(eff1);
                            byte dom2 = getDominantFactionIndex(eff2);
                            if (dom1 > 0 && dom1 == dom2) {
                                shouldInfill = true;
                                dominantFactionIdx = dom1;
                                break;
                            }
                        }
                    }

                    // 2. [修复 V3-M01] 环绕腹地判定：8-邻域中必须有 >= 5 个【同一派系】的危险区块包围
                    if (!shouldInfill) {
                        int[] factionNeighborCounts = new int[FactionRegistry.getFactionCount()];
                        int[][] allDirs = {
                            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
                        };
                        for (int[] ad : allDirs) {
                            long nk = ChunkCoordPacker.pack(ncx + ad[0], ncz + ad[1]);
                            float[] neff = effectiveMap.get(nk);
                            if (neff != null) {
                                byte ndom = getDominantFactionIndex(neff);
                                if (ndom > 0 && ndom < factionNeighborCounts.length) {
                                    if (++factionNeighborCounts[ndom] >= 5) {
                                        shouldInfill = true;
                                        dominantFactionIdx = ndom;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if (shouldInfill && dominantFactionIdx > 0) {
                        float[] infillScores = new float[FactionRegistry.getFactionCount()];
                        infillScores[dominantFactionIdx] = 1.0f; // 桥接保底危险分
                        infillMap.put(candidateKey, infillScores);
                    }
                }
            }

            effectiveMap.putAll(infillMap);
        }

        getClusterManager(dim).rebuildClusters(level, effectiveMap);
        syncToPlayers(level);
    }

    private byte getDominantFactionIndex(float[] eff) {
        if (eff == null) return -1;
        byte best = -1;
        float max = 0.5f;
        for (int i = 1; i < eff.length; i++) {
            if (eff[i] > max) {
                max = eff[i];
                best = (byte) i;
            }
        }
        return best;
    }

    // ===================================================================
    //  全额并发压制 (Concurrent Suppression - 突击前哨波前模型)
    // ===================================================================

    public Map<Long, float[]> suppressRegionConcurrently(ServerLevel level, BlockPos pos, int maxRadius, float waveRadius,
                                                         double percent, double fixed) {
        int centerCx = pos.getX() >> 4;
        int centerCz = pos.getZ() >> 4;
        ResourceLocation dim = level.dimension().location();
        Map<Long, float[]> addedSuppressions = new HashMap<>();

        var rawMap = chunkScores.get(dim);
        var suppMap = chunkSuppression.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        List<? extends String> ignored = AdventureConfig.IGNORED_FACTIONS.get();
        Set<String> ignoredSet = (ignored != null) ? new HashSet<>(ignored) : Set.of();

        Set<Long> processedStructures = new HashSet<>();
        boolean anyStructureReduced = false;
        int ceilR = (int) Math.ceil(waveRadius);

        for (int dx = -ceilR; dx <= ceilR; dx++) {
            for (int dz = -ceilR; dz <= ceilR; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > waveRadius) continue; // 欧氏同心圆波前截断

                int cx = centerCx + dx;
                int cz = centerCz + dz;
                long key = ChunkCoordPacker.pack(cx, cz);

                // 中心能量聚焦：中心区块 (dist=0) 获得 3.0x 压制加成，随距离平滑衰减至 1.0x，确保前哨立足点快速成型
                double focusMultiplier = 1.0 + 2.0 * Math.max(0.0, 1.0 - (dist / (waveRadius + 0.5)));

                // 1. 结构基础分全额并发削减（即使 rawMap 为空也照常执行！）
                List<CivillisStructureTracker.ActiveStructure> activeStructs =
                        CivillisStructureTracker.getActiveStructures(level, cx, cz);
                for (CivillisStructureTracker.ActiveStructure as : activeStructs) {
                    if (ignoredSet.contains(as.faction().id().toString())) continue;
                    if (!processedStructures.add(as.rootKey())) continue;

                    double structTotal = CivillisStructureTracker.getRemainingScore(level, as.rootKey(), as.faction().structureBaseScore());
                    if (structTotal > 0.0) {
                        double reduction = (structTotal * percent + fixed) * focusMultiplier;
                        CivillisStructureTracker.reduceStructureScore(level, as.rootKey(), reduction, as.faction().structureBaseScore());
                        anyStructureReduced = true;
                        CivilAdventureMod.LOGGER.debug("[civil_adventure][Step 7/Suppression] 压制核心削减结构基础分: 结构根 {} | 削减量: {} | 归属派系: {}",
                                as.rootKey(), String.format("%.1f", reduction), as.faction().id());
                    }
                }

                // 2. 区块多派系纯方块分全额并发削减
                if (rawMap == null) continue;
                float[] raw = rawMap.get(key);
                if (raw == null) continue;

                float[] alreadySupp = suppMap.computeIfAbsent(key, k -> new float[raw.length]);
                float[] addedThisChunk = new float[raw.length];
                boolean hasAdded = false;

                // 遍历所有非文明、非白名单忽略的派系
                for (int i = 1; i < raw.length; i++) {
                    FactionDefinition f = FactionRegistry.getByIndex((byte) i);
                    if (f == null) continue;
                    if (ignoredSet.contains(f.id().toString())) continue;

                    float original = raw[i];
                    if (original <= 0.0f) continue;

                    float curSupp = (i < alreadySupp.length) ? alreadySupp[i] : 0.0f;
                    float remaining = original - curSupp;
                    if (remaining <= 0.001f) continue;

                    float amount = (float) ((original * percent + fixed) * focusMultiplier);
                    amount = Math.min(amount, remaining);

                    alreadySupp[i] = curSupp + amount;
                    addedThisChunk[i] = amount;
                    hasAdded = true;
                }

                if (hasAdded) {
                    addedSuppressions.put(key, addedThisChunk);
                }
            }
        }

        if (!addedSuppressions.isEmpty()) {
            CivilAdventureMod.LOGGER.debug("[civil_adventure][Step 7/Suppression] 压制核心在 ({}, {}, {}) 波前半径 {} (上限 {}) 触发压制: 削减了 {} 个区块",
                    pos.getX(), pos.getY(), pos.getZ(), String.format("%.2f", waveRadius), maxRadius, addedSuppressions.size());
        }

        if (!addedSuppressions.isEmpty() || anyStructureReduced) {
            rebuildClusters(level);
        }
        return addedSuppressions;
    }

    public void removeSuppressionConcurrently(ServerLevel level, Map<Long, float[]> added) {
        if (added == null || added.isEmpty()) return;
        ResourceLocation dim = level.dimension().location();
        var suppMap = chunkSuppression.get(dim);
        if (suppMap == null) return;

        for (Map.Entry<Long, float[]> e : added.entrySet()) {
            long key = e.getKey();
            float[] toRemove = e.getValue();
            float[] curSupp = suppMap.get(key);
            if (curSupp != null) {
                for (int i = 0; i < Math.min(curSupp.length, toRemove.length); i++) {
                    curSupp[i] = Math.max(0.0f, curSupp[i] - toRemove[i]);
                }
            }
        }
        rebuildClusters(level);
    }

    /**
     * 查询指定中心点半径内是否仍然存在未被完全压制的威胁（纯方块威胁分或未肃清结构）。
     * 用于压制核心判断是否已实现“全域压制”（驱动 RUNNING 动画与比较器 15 满格信号）。
     */
    public boolean hasRemainingThreatInRadius(ServerLevel level, BlockPos pos, int radius) {
        int centerCx = pos.getX() >> 4;
        int centerCz = pos.getZ() >> 4;
        ResourceLocation dim = level.dimension().location();
        var rawMap = chunkScores.get(dim);
        var suppMap = chunkSuppression.get(dim);
        List<? extends String> ignored = AdventureConfig.IGNORED_FACTIONS.get();
        Set<String> ignoredSet = (ignored != null) ? new HashSet<>(ignored) : Set.of();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue; // 欧氏同心圆判定
                int cx = centerCx + dx;
                int cz = centerCz + dz;
                long key = ChunkCoordPacker.pack(cx, cz);

                // 1. 检查未肃清的结构分
                List<CivillisStructureTracker.ActiveStructure> structs =
                        CivillisStructureTracker.getActiveStructures(level, cx, cz);
                for (CivillisStructureTracker.ActiveStructure as : structs) {
                    if (ignoredSet.contains(as.faction().id().toString())) continue;
                    if (as.remainingScore() > 0.001) {
                        return true;
                    }
                }

                // 2. 检查未压制完的纯方块分
                if (rawMap != null) {
                    float[] raw = rawMap.get(key);
                    if (raw != null) {
                        float[] supp = (suppMap != null) ? suppMap.get(key) : null;
                        for (int i = 1; i < raw.length; i++) {
                            FactionDefinition f = FactionRegistry.getByIndex((byte) i);
                            if (f == null || ignoredSet.contains(f.id().toString())) continue;
                            float curSupp = (supp != null && i < supp.length) ? supp[i] : 0.0f;
                            if ((raw[i] - curSupp) >= 0.5f) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    // ===================================================================
    //  客户端领地同步 (Client Sync Networking)
    // ===================================================================

    public void syncToPlayers(ServerLevel level) {
        ResourceLocation dim = level.dimension().location();
        MacroClusterManager mcm = getClusterManager(dim);
        Map<Long, Byte> dominanceMap = mcm.getDominanceMap();

        List<AdventureClusterSyncPayload.FactionInfo> factions = new ArrayList<>();
        for (FactionDefinition f : FactionRegistry.getAllFactions()) {
            factions.add(new AdventureClusterSyncPayload.FactionInfo(
                    f.index(), f.nameTranslationKey(), f.colorRgb(), f.isCivilization()
            ));
        }

        AdventureClusterSyncPayload pkt = new AdventureClusterSyncPayload(dim, factions, dominanceMap);
        for (ServerPlayer player : level.players()) {
            CivilAdventureMod.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), pkt);
        }
    }

    public void syncToPlayer(ServerPlayer player) {
        if (player.level() instanceof ServerLevel level) {
            ResourceLocation dim = level.dimension().location();
            MacroClusterManager mcm = getClusterManager(dim);
            Map<Long, Byte> dominanceMap = mcm.getDominanceMap();

            List<AdventureClusterSyncPayload.FactionInfo> factions = new ArrayList<>();
            for (FactionDefinition f : FactionRegistry.getAllFactions()) {
                factions.add(new AdventureClusterSyncPayload.FactionInfo(
                        f.index(), f.nameTranslationKey(), f.colorRgb(), f.isCivilization()
                ));
            }

            AdventureClusterSyncPayload pkt = new AdventureClusterSyncPayload(dim, factions, dominanceMap);
            CivilAdventureMod.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), pkt);
        }
    }

    public void onReload(Iterable<ServerLevel> levels) {
        if (levels != null) {
            for (ServerLevel level : levels) {
                ResourceLocation dim = level.dimension().location();
                var oldMap = chunkScores.get(dim);
                Set<Long> loadedKeys = (oldMap != null) ? new HashSet<>(oldMap.keySet()) : Set.of();
                if (oldMap != null) oldMap.clear();

                for (long key : loadedKeys) {
                    int cx = ChunkCoordPacker.unpackCx(key);
                    int cz = ChunkCoordPacker.unpackCz(key);
                    recalculateFullChunk(level, cx, cz);
                }
                rebuildClusters(level);
            }
        }
    }

    // ===================================================================
    //  对外查询与向后兼容接口
    // ===================================================================

    public double getNormalizedScoreAt(ServerLevel level, BlockPos pos) {
        MacroCluster cluster = getClusterAt(level, pos);
        if (cluster == null || !cluster.hasDominance()) return 0.0;
        FactionDefinition dom = cluster.getDominantFaction();
        if (dom == null || dom.isCivilization()) return 0.0;
        return Math.min(1.0, cluster.getDominantTotalScore() / AdventureConfig.NORMALIZATION_DIVISOR.get());
    }

    public double getRawScoreAt(ServerLevel level, BlockPos pos) {
        MacroCluster cluster = getClusterAt(level, pos);
        return (cluster != null) ? cluster.getDominantTotalScore() : 0.0;
    }

    public int getZoneSize(ServerLevel level, BlockPos pos, int radius) {
        MacroCluster cluster = getClusterAt(level, pos);
        return (cluster != null) ? cluster.getChunkCount() : 0;
    }

    public BlockPos getZoneCenter(ServerLevel level, BlockPos pos) {
        MacroCluster cluster = getClusterAt(level, pos);
        return (cluster != null) ? cluster.getEpicenter() : pos;
    }

    public void onChunkUnload(ServerLevel level, int cx, int cz) {
        ResourceLocation dim = level.dimension().location();
        long key = ChunkCoordPacker.pack(cx, cz);
        var rawMap = chunkScores.get(dim);
        if (rawMap != null) rawMap.remove(key);
        // 保留 chunkSuppression，避免正在运行的压制核心在区块卸载重载后账目失步
    }
}
