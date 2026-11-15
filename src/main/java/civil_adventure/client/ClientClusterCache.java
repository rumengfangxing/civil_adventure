package civil_adventure.client;

import civil_adventure.CivilAdventureMod;
import civil_adventure.client.profile.OverlayProfile;
import civil_adventure.client.profile.OverlayProfileRegistry;
import civil_adventure.config.AdventureConfig;
import civil_adventure.core.ChunkCoordPacker;
import civil_adventure.network.AdventureClusterSyncPayload;
import civil.compat.bandsync.ChunkBandClientCache;
import civil.compat.xaeros.highlight.CivilXaeroChunkBandHighlightSupport;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端专属宏观领地显性统治缓存与战争迷雾系统。
 * 完全由服务端 S2C 数据包与客户端本地侦察驱动更新，供 Xaero 小地图/大地图在客户端渲染线程高效、线程安全地读取。
 * 支持按维度隔离的迷雾缓存、近邻桥接聚类脏标记缓存、断开连接重置与派系持久记忆。
 */
@Mod.EventBusSubscriber(modid = CivilAdventureMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientClusterCache {

    public static final class ZoneCluster {
        public final int minCx, maxCx, minCz, maxCz;
        public final int width, height;
        public final Set<Long> chunkKeys;
        private final AdventureClusterSyncPayload.FactionInfo dominantFaction;
        private final double estimatedScore;

        public ZoneCluster(int minCx, int maxCx, int minCz, int maxCz, Set<Long> chunkKeys,
                           AdventureClusterSyncPayload.FactionInfo dominantFaction) {
            this(minCx, maxCx, minCz, maxCz, chunkKeys, dominantFaction, 300.0);
        }

        public ZoneCluster(int minCx, int maxCx, int minCz, int maxCz, Set<Long> chunkKeys,
                           AdventureClusterSyncPayload.FactionInfo dominantFaction, double estimatedScore) {
            this.minCx = minCx;
            this.maxCx = maxCx;
            this.minCz = minCz;
            this.maxCz = maxCz;
            this.width = maxCx - minCx + 1;
            this.height = maxCz - minCz + 1;
            this.chunkKeys = chunkKeys != null ? Set.copyOf(chunkKeys) : Collections.emptySet();
            this.dominantFaction = dominantFaction;
            this.estimatedScore = estimatedScore;
        }

        public AdventureClusterSyncPayload.FactionInfo getDominantFaction() {
            return dominantFaction;
        }

        public double getEstimatedScore() {
            return estimatedScore;
        }

        public double getCenterWorldX() {
            if (chunkKeys.isEmpty()) return (minCx * 16.0 + maxCx * 16.0 + 16.0) / 2.0;
            double sum = 0;
            for (long k : chunkKeys) {
                sum += ChunkCoordPacker.unpackCx(k) * 16.0 + 8.0;
            }
            return sum / chunkKeys.size();
        }

        public double getCenterWorldZ() {
            if (chunkKeys.isEmpty()) return (minCz * 16.0 + maxCz * 16.0 + 16.0) / 2.0;
            double sum = 0;
            for (long k : chunkKeys) {
                sum += ChunkCoordPacker.unpackCz(k) * 16.0 + 8.0;
            }
            return sum / chunkKeys.size();
        }
    }

    private static final Map<Byte, AdventureClusterSyncPayload.FactionInfo> FACTIONS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ConcurrentHashMap<Long, Byte>> DIMENSION_DOMINANCE = new ConcurrentHashMap<>();

    // 客户端持久记忆的派系领地表（防止玩家探明后离开导致服务端卸载区块后丢失派系颜色）
    private static final Map<ResourceLocation, ConcurrentHashMap<Long, AdventureClusterSyncPayload.FactionInfo>> KNOWN_FACTION_MEMORY = new ConcurrentHashMap<>();

    // [修复 C-01] 按维度隔离已侦察区块集合
    private static final Map<ResourceLocation, Set<Long>> SCOUTED_CHUNKS_BY_DIM = new ConcurrentHashMap<>();

    // [修复 REG-03] 维度网络同步版本号 + Region 侦察版本号，供 Xaero World Map 增量刷新 Region 纹理
    private static final Map<ResourceLocation, Integer> DIMENSION_SYNC_REVISION = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ConcurrentHashMap<Long, Integer>> REGION_SCOUT_REVISION = new ConcurrentHashMap<>();

    // [修复 C-04 & REG-02] 未知危险区聚类缓存与脏标记（带哈希校验，避免 60~240 FPS 每帧全维度重跑 24 邻域 BFS）
    private static final Map<ResourceLocation, List<ZoneCluster>> CACHED_UNSCOUTED_CLUSTERS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Integer> LAST_RAW_BAND_HASH = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> DIRTY_DIMENSIONS = ConcurrentHashMap.newKeySet();

    // 追踪玩家上次触发侦察的区块坐标，避免同区块内每 Tick 重复循环
    private static ResourceLocation lastScoutDim = null;
    private static int lastScoutChunkX = Integer.MIN_VALUE;
    private static int lastScoutChunkZ = Integer.MIN_VALUE;

    // 近邻桥接邻域 (Chebyshev 距离 <= 2，包含 8-连通角点与跨越 1 区块河流/道路/侦察小径的桥接，共 24 偏移)
    private static final int[][] BRIDGING_OFFSETS;
    static {
        List<int[]> offsets = new ArrayList<>();
        // 4-正向相邻
        offsets.add(new int[]{1, 0});
        offsets.add(new int[]{-1, 0});
        offsets.add(new int[]{0, 1});
        offsets.add(new int[]{0, -1});
        // 4-角点对角 (8-连通)
        offsets.add(new int[]{1, 1});
        offsets.add(new int[]{1, -1});
        offsets.add(new int[]{-1, 1});
        offsets.add(new int[]{-1, -1});
        // 距离 2 的外圈近邻桥接 (跨越 1 区块缝隙)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) == 2) {
                    offsets.add(new int[]{dx, dz});
                }
            }
        }
        BRIDGING_OFFSETS = offsets.toArray(new int[0][]);
    }

    private ClientClusterCache() {}

    @SubscribeEvent
    public static void onClientLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        clear();
    }

    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null && mc.level != null) {
            ResourceLocation dim = mc.level.dimension().location();
            updatePlayerScouting(dim, mc.player.getX(), mc.player.getZ());
        }
    }

    public static void applySync(AdventureClusterSyncPayload payload) {
        if (payload == null) return;

        ResourceLocation dim = payload.dimension();
        for (AdventureClusterSyncPayload.FactionInfo fi : payload.factions()) {
            FACTIONS.put(fi.index(), fi);
        }

        ConcurrentHashMap<Long, Byte> chunkMap = new ConcurrentHashMap<>(payload.chunkDominance());
        DIMENSION_DOMINANCE.put(dim, chunkMap);

        // 将服务端同步来的非文明危险派系写入客户端持久记忆，避免区块卸载后颜色退化
        var memory = KNOWN_FACTION_MEMORY.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        chunkMap.forEach((k, fIdx) -> {
            AdventureClusterSyncPayload.FactionInfo fi = FACTIONS.get(fIdx);
            if (fi != null) {
                if (fi.isCivilization()) {
                    memory.remove(k);
                } else {
                    memory.put(k, fi);
                }
            }
        });

        // [修复 REG-03] 递增维度网络同步版本号，确保同尺寸易主或数据更替时 Xaero 即时重绘
        DIMENSION_SYNC_REVISION.merge(dim, 1, Integer::sum);
        DIRTY_DIMENSIONS.add(dim);
    }

    public static AdventureClusterSyncPayload.FactionInfo getDominantFaction(ResourceLocation dim, int cx, int cz) {
        if (dim == null) return null;
        var map = DIMENSION_DOMINANCE.get(dim);
        if (map != null) {
            long key = ChunkCoordPacker.pack(cx, cz);
            Byte fIdx = map.get(key);
            if (fIdx != null) {
                AdventureClusterSyncPayload.FactionInfo live = FACTIONS.get(fIdx);
                if (live != null) return live;
            }
        }
        var memory = KNOWN_FACTION_MEMORY.get(dim);
        if (memory != null) {
            long key = ChunkCoordPacker.pack(cx, cz);
            return memory.get(key);
        }
        return null;
    }

    /**
     * [修复 C-01 & REG-03] 由 ClientTickEvent 实时驱动，并支持跨区块移动去重。
     * 玩家走到哪取消到哪：以玩家当前世界坐标为中心，自动侦察探明周围 9x9 区块 (~144米范围)。
     * 同时标记 (cx±1, cz±1) 所在的相邻 Region，确保跨 Region 边界的 4-邻接黑盒外框同步刷新。
     * 返回本次是否有新区块被探明。
     */
    public static boolean updatePlayerScouting(ResourceLocation dim, double playerX, double playerZ) {
        if (dim == null) return false;
        int pcx = ((int) Math.floor(playerX)) >> 4;
        int pcz = ((int) Math.floor(playerZ)) >> 4;

        if (dim.equals(lastScoutDim) && pcx == lastScoutChunkX && pcz == lastScoutChunkZ) {
            return false;
        }
        lastScoutDim = dim;
        lastScoutChunkX = pcx;
        lastScoutChunkZ = pcz;

        int r = AdventureConfig.SCOUT_RADIUS_CHUNKS.get();
        Set<Long> scouted = SCOUTED_CHUNKS_BY_DIM.computeIfAbsent(dim, k -> ConcurrentHashMap.newKeySet());
        var revMap = REGION_SCOUT_REVISION.computeIfAbsent(dim, k -> new ConcurrentHashMap<>());
        Set<Long> touchedRegions = new HashSet<>();
        boolean changed = false;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                long key = ChunkCoordPacker.pack(cx, cz);
                if (scouted.add(key)) {
                    changed = true;
                    // [修复 REG-03] 标记 (cx±1, cz±1) 所在 Region，保证跨边界 4-邻接红框外框同步刷新
                    for (int nrx = (cx - 1) >> 5; nrx <= (cx + 1) >> 5; nrx++) {
                        for (int nrz = (cz - 1) >> 5; nrz <= (cz + 1) >> 5; nrz++) {
                            touchedRegions.add((((long) nrx) << 32) | (nrz & 0xFFFFFFFFL));
                        }
                    }
                }
            }
        }

        if (changed) {
            for (long rKey : touchedRegions) {
                revMap.merge(rKey, 1, Integer::sum);
            }
            DIRTY_DIMENSIONS.add(dim);
        }
        return changed;
    }

    public static boolean isChunkScouted(ResourceLocation dim, long key) {
        if (dim == null) return false;
        Set<Long> scouted = SCOUTED_CHUNKS_BY_DIM.get(dim);
        return scouted != null && scouted.contains(key);
    }

    /**
     * [修复 REG-03] 获取指定 Region 的侦察与同步复合版本哈希，供 Xaero 大地图即时检测并重新烘培纹理。
     */
    public static int getRegionHighlightHash(ResourceLocation dim, int rx, int rz) {
        if (dim == null) return 0;
        int scoutRev = 0;
        var revMap = REGION_SCOUT_REVISION.get(dim);
        if (revMap != null) {
            long rKey = (((long) rx) << 32) | (rz & 0xFFFFFFFFL);
            scoutRev = revMap.getOrDefault(rKey, 0);
        }
        // 纯荒野空 Region 短路：无侦察且无任何高亮时恒定返回 0，消除空刷新开销
        if (scoutRev == 0 && !regionHasAnyHighlights(dim, rx, rz)) {
            return 0;
        }
        int syncRev = DIMENSION_SYNC_REVISION.getOrDefault(dim, 0);
        return scoutRev * 31 + syncRev;
    }

    /**
     * 文明区绝对安全判定：文礼原生 HIGH 区 (band == 3) 或显性文明派系统治区绝对不可被视为危险黑盒！
     */
    public static boolean isCivilizationChunk(ResourceLocation dim, int cx, int cz) {
        if (dim == null) return false;
        try {
            byte band = ChunkBandClientCache.getBand(dim.toString(), cx, cz);
            if (band >= 0 && band <= 4) return true; // 0~4 均为文明领地
        } catch (Throwable ignored) {}

        AdventureClusterSyncPayload.FactionInfo dom = getDominantFaction(dim, cx, cz);
        return dom != null && dom.isCivilization();
    }

    public static boolean isUnidentifiedZone(ResourceLocation dim, int cx, int cz) {
        if (dim == null) return false;
        if (isCivilizationChunk(dim, cx, cz)) return false;
        try {
            byte band = ChunkBandClientCache.getBand(dim.toString(), cx, cz);
            return band == 6; // 6 = ZONE (文礼原生冒险区)
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * [修复 C-02] 判定指定区块是否为“未勘明的未知危险冒险区”（需渲染不透明黑盒遮罩）：
     * 1. 绝对排除文明区
     * 2. 玩家走到哪取消到哪：排除按维度已侦察的区块
     * 3. 只要是非文明危险区（自定义派系如真菌领地，或文礼原生冒险区），未侦察前一律视为未知危险！
     */
    public static boolean isUnscoutedDanger(ResourceLocation dim, int cx, int cz) {
        if (dim == null) return false;
        if (isCivilizationChunk(dim, cx, cz)) return false;

        long key = ChunkCoordPacker.pack(cx, cz);
        if (isChunkScouted(dim, key)) return false;

        AdventureClusterSyncPayload.FactionInfo dom = getDominantFaction(dim, cx, cz);
        if (dom != null && !dom.isCivilization()) return true;

        return isUnidentifiedZone(dim, cx, cz);
    }

    public static boolean isChunkHighlit(ResourceLocation dim, int cx, int cz) {
        if (dim == null) return false;
        if (isCivilizationChunk(dim, cx, cz)) return false;
        if (isUnscoutedDanger(dim, cx, cz)) return true;
        AdventureClusterSyncPayload.FactionInfo dom = getDominantFaction(dim, cx, cz);
        return dom != null && !dom.isCivilization();
    }

    /**
     * [修复 M-02] 预先获取维度 Map 引用，消除 32x32 循环中 4,096 次重复哈希查找与 dim.toString()
     */
    public static boolean regionHasAnyHighlights(ResourceLocation dim, int rx, int rz) {
        if (dim == null) return false;
        int startCx = rx << 5;
        int startCz = rz << 5;

        var domMap = DIMENSION_DOMINANCE.get(dim);
        var memMap = KNOWN_FACTION_MEMORY.get(dim);
        Long2ByteMap rawBandMap = null;
        try {
            rawBandMap = ChunkBandClientCache.getRawDimensionMap(dim.toString());
        } catch (Throwable ignored) {}

        for (int dz = 0; dz < 32; dz++) {
            for (int dx = 0; dx < 32; dx++) {
                int cx = startCx + dx;
                int cz = startCz + dz;
                long key = ChunkCoordPacker.pack(cx, cz);

                byte band = (rawBandMap != null) ? rawBandMap.getOrDefault(key, (byte) -1) : -1;
                if (band >= 0 && band <= 4) continue; // 文明区

                Byte fIdx = (domMap != null) ? domMap.get(key) : null;
                AdventureClusterSyncPayload.FactionInfo dom = (fIdx != null) ? FACTIONS.get(fIdx) : (memMap != null ? memMap.get(key) : null);
                if (dom != null && dom.isCivilization()) continue;

                if (dom != null || band == 6) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * [修复 C-03, C-04 & REG-02] 带脏标记缓存与哈希校验的 24 邻域桥接聚类：
     * 1. 采用 rawMap.hashCode() 替代单纯的 size()，捕获同尺寸下的区块 band 变化；
     * 2. 遍历 rawMap (band == 6)、DIMENSION_DOMINANCE 以及 KNOWN_FACTION_MEMORY，杜绝服务端区块卸载后有黑盒无标牌；
     * 3. 统计每个 Cluster 的主导派系以激活 Profile 预设。
     */
    public static List<ZoneCluster> getUnscoutedDangerClusters(ResourceLocation dim) {
        if (dim == null) return Collections.emptyList();

        Long2ByteMap rawMap = null;
        try {
            rawMap = ChunkBandClientCache.getRawDimensionMap(dim.toString());
        } catch (Throwable ignored) {}

        int currentRawHash = (rawMap != null) ? rawMap.hashCode() : 0;
        Integer lastHash = LAST_RAW_BAND_HASH.get(dim);

        if (!DIRTY_DIMENSIONS.contains(dim)
                && lastHash != null && lastHash == currentRawHash
                && CACHED_UNSCOUTED_CLUSTERS.containsKey(dim)) {
            return CACHED_UNSCOUTED_CLUSTERS.get(dim);
        }

        LAST_RAW_BAND_HASH.put(dim, currentRawHash);
        DIRTY_DIMENSIONS.remove(dim);

        Set<Long> candidateKeys = new HashSet<>();
        if (rawMap != null) {
            for (Long2ByteMap.Entry entry : rawMap.long2ByteEntrySet()) {
                if (entry.getByteValue() == 6) {
                    long key = entry.getLongKey();
                    int cx = ChunkCoordPacker.unpackCx(key);
                    int cz = ChunkCoordPacker.unpackCz(key);
                    if (isUnscoutedDanger(dim, cx, cz)) {
                        candidateKeys.add(key);
                    }
                }
            }
        }

        // 1. 服务端当前加载同步的自定义派系（如真菌 fungal）未侦察区块纳入聚类
        var domMap = DIMENSION_DOMINANCE.get(dim);
        if (domMap != null) {
            for (Map.Entry<Long, Byte> entry : domMap.entrySet()) {
                AdventureClusterSyncPayload.FactionInfo fi = FACTIONS.get(entry.getValue());
                if (fi != null && !fi.isCivilization()) {
                    long key = entry.getKey();
                    int cx = ChunkCoordPacker.unpackCx(key);
                    int cz = ChunkCoordPacker.unpackCz(key);
                    if (isUnscoutedDanger(dim, cx, cz)) {
                        candidateKeys.add(key);
                    }
                }
            }
        }

        // 2. [修复 REG-02] 客户端持久记忆中曾同步过、后在服务端卸载但尚未被探明的危险区块也纳入聚类，防止有黑盒无标牌
        var memMap = KNOWN_FACTION_MEMORY.get(dim);
        if (memMap != null) {
            for (Map.Entry<Long, AdventureClusterSyncPayload.FactionInfo> entry : memMap.entrySet()) {
                AdventureClusterSyncPayload.FactionInfo fi = entry.getValue();
                if (fi != null && !fi.isCivilization()) {
                    long key = entry.getKey();
                    int cx = ChunkCoordPacker.unpackCx(key);
                    int cz = ChunkCoordPacker.unpackCz(key);
                    if (isUnscoutedDanger(dim, cx, cz)) {
                        candidateKeys.add(key);
                    }
                }
            }
        }

        if (candidateKeys.isEmpty()) {
            List<ZoneCluster> empty = Collections.emptyList();
            CACHED_UNSCOUTED_CLUSTERS.put(dim, empty);
            return empty;
        }

        List<ZoneCluster> clusters = new ArrayList<>();
        Set<Long> visited = new HashSet<>();

        for (long startKey : candidateKeys) {
            if (visited.contains(startKey)) continue;

            Deque<Long> queue = new ArrayDeque<>();
            Set<Long> clusterKeys = new HashSet<>();
            queue.add(startKey);
            visited.add(startKey);
            clusterKeys.add(startKey);

            int startCx = ChunkCoordPacker.unpackCx(startKey);
            int startCz = ChunkCoordPacker.unpackCz(startKey);
            int minX = startCx, maxX = startCx, minZ = startCz, maxZ = startCz;

            Map<Byte, Integer> factionCounts = new HashMap<>();

            while (!queue.isEmpty()) {
                long cur = queue.poll();
                int curX = ChunkCoordPacker.unpackCx(cur);
                int curZ = ChunkCoordPacker.unpackCz(cur);
                minX = Math.min(minX, curX);
                maxX = Math.max(maxX, curX);
                minZ = Math.min(minZ, curZ);
                maxZ = Math.max(maxZ, curZ);

                AdventureClusterSyncPayload.FactionInfo curFi = getDominantFaction(dim, curX, curZ);
                if (curFi != null && !curFi.isCivilization()) {
                    factionCounts.merge(curFi.index(), 1, Integer::sum);
                }

                for (int[] d : BRIDGING_OFFSETS) {
                    int nx = curX + d[0];
                    int nz = curZ + d[1];
                    long nKey = ChunkCoordPacker.pack(nx, nz);
                    if (!visited.contains(nKey) && candidateKeys.contains(nKey)) {
                        visited.add(nKey);
                        clusterKeys.add(nKey);
                        queue.add(nKey);
                    }
                }
            }

            AdventureClusterSyncPayload.FactionInfo bestFaction = null;
            int maxCount = -1;
            for (Map.Entry<Byte, Integer> e : factionCounts.entrySet()) {
                if (e.getValue() > maxCount) {
                    maxCount = e.getValue();
                    bestFaction = FACTIONS.get(e.getKey());
                }
            }

            clusters.add(new ZoneCluster(minX, maxX, minZ, maxZ, clusterKeys, bestFaction));
        }

        List<ZoneCluster> immutable = Collections.unmodifiableList(clusters);
        CACHED_UNSCOUTED_CLUSTERS.put(dim, immutable);
        return immutable;
    }

    public static int[] getMinimapColors(ResourceLocation dim, int cx, int cz) {
        if (dim == null) return null;
        if (isCivilizationChunk(dim, cx, cz)) return null;

        // 1. 未勘明的未知危险冒险区：区域内的地图用纯黑底色表示，边框读取对应派系预设
        if (isUnscoutedDanger(dim, cx, cz)) {
            AdventureClusterSyncPayload.FactionInfo dom = getDominantFaction(dim, cx, cz);
            String faction = (dom != null) ? dom.nameKey() : null;
            OverlayProfile profile = OverlayProfileRegistry.findProfile(dim, faction, 100.0);
            if (profile != null && !profile.showOnMinimap) return null;

            int bgRgb = profile != null ? (profile.backgroundColor & 0x00FFFFFF) : 0x0A0608;
            int borderRgb = profile != null ? (profile.borderColor & 0x00FFFFFF) : 0xE61919;

            // 100% 实心不透明黑底消去可视性
            int cBg = CivilXaeroChunkBandHighlightSupport.argbToXaeroHighlight((0xFF << 24) | bgRgb);
            int cBorder = CivilXaeroChunkBandHighlightSupport.argbToXaeroHighlight((0xFF << 24) | borderRgb);

            // 内部区块无缝纯黑，只有外边缘才画边框
            boolean northOuter = !isUnscoutedDanger(dim, cx, cz - 1);
            boolean eastOuter = !isUnscoutedDanger(dim, cx + 1, cz);
            boolean southOuter = !isUnscoutedDanger(dim, cx, cz + 1);
            boolean westOuter = !isUnscoutedDanger(dim, cx - 1, cz);

            int[] colors = new int[5];
            colors[0] = cBg;
            colors[1] = northOuter ? cBorder : cBg;
            colors[2] = eastOuter ? cBorder : cBg;
            colors[3] = southOuter ? cBorder : cBg;
            colors[4] = westOuter ? cBorder : cBg;
            return colors;
        }

        // 2. 已勘明具体派系：渲染该派系的半透明专属代表色
        AdventureClusterSyncPayload.FactionInfo dom = getDominantFaction(dim, cx, cz);
        if (dom != null && !dom.isCivilization()) {
            int rgb = dom.colorRgb() & 0x00FFFFFF;
            double opacity = AdventureConfig.MAP_OVERLAY_OPACITY.get();
            int fillAlpha = Math.max(0, Math.min(255, (int) (opacity * 0.40 * 255)));
            int borderAlpha = Math.max(0, Math.min(255, (int) (opacity * 0.85 * 255)));

            int cBg = CivilXaeroChunkBandHighlightSupport.argbToXaeroHighlight((fillAlpha << 24) | rgb);
            int cBorder = CivilXaeroChunkBandHighlightSupport.argbToXaeroHighlight((borderAlpha << 24) | rgb);

            boolean northOuter = !sameFaction(dim, cx, cz - 1, dom);
            boolean eastOuter = !sameFaction(dim, cx + 1, cz, dom);
            boolean southOuter = !sameFaction(dim, cx, cz + 1, dom);
            boolean westOuter = !sameFaction(dim, cx - 1, cz, dom);

            int[] colors = new int[5];
            colors[0] = cBg;
            colors[1] = northOuter ? cBorder : cBg;
            colors[2] = eastOuter ? cBorder : cBg;
            colors[3] = southOuter ? cBorder : cBg;
            colors[4] = westOuter ? cBorder : cBg;
            return colors;
        }

        return null;
    }

    private static boolean sameFaction(ResourceLocation dim, int cx, int cz,
                                       AdventureClusterSyncPayload.FactionInfo target) {
        if (isUnscoutedDanger(dim, cx, cz)) return false;
        AdventureClusterSyncPayload.FactionInfo neighbor = getDominantFaction(dim, cx, cz);
        return neighbor != null && neighbor.index() == target.index();
    }

    public static int[] getWorldMapColors(ResourceLocation dim, int cx, int cz) {
        return getMinimapColors(dim, cx, cz);
    }

    public static Component getTooltip(ResourceLocation dim, int cx, int cz) {
        if (dim == null) return null;

        // 鼠标位于此区域内的白色提示与文字提示去掉
        if (isUnscoutedDanger(dim, cx, cz)) {
            return null;
        }

        // 已勘明派系的冒险区：高亮显示粗体派系名
        AdventureClusterSyncPayload.FactionInfo dom = getDominantFaction(dim, cx, cz);
        if (dom != null && !dom.isCivilization()) {
            return Component.translatable(dom.nameKey())
                    .withStyle(Style.EMPTY.withColor(dom.colorRgb()).withBold(true));
        }

        return null;
    }

    public static void clear() {
        FACTIONS.clear();
        DIMENSION_DOMINANCE.clear();
        KNOWN_FACTION_MEMORY.clear();
        SCOUTED_CHUNKS_BY_DIM.clear();
        REGION_SCOUT_REVISION.clear();
        DIMENSION_SYNC_REVISION.clear();
        CACHED_UNSCOUTED_CLUSTERS.clear();
        LAST_RAW_BAND_HASH.clear();
        DIRTY_DIMENSIONS.clear();
        lastScoutDim = null;
        lastScoutChunkX = Integer.MIN_VALUE;
        lastScoutChunkZ = Integer.MIN_VALUE;
    }
}
