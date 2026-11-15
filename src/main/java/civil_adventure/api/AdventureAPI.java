package civil_adventure.api;

import civil_adventure.CivilAdventureMod;
import civil_adventure.cluster.MacroCluster;
import civil_adventure.faction.FactionDefinition;
import civil_adventure.score.AdventureScoreService;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 公开外部开发者与 KubeJS 脚本交互 API。
 * 1. 严格保障旧版方法向后兼容。
 * 2. 暴露宏观连通域聚类、显性派系统治、以及是/非 Tag 查询接口。
 */
public final class AdventureAPI {

    private AdventureAPI() {}

    // ===================================================================
    //  2.0 宏观领地与多派系接口
    // ===================================================================

    /** 查询某位置归属的宏观连通域聚类（若无统治或荒野则可能为 null） */
    public static MacroCluster getClusterAt(ServerLevel level, BlockPos pos) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        return (svc != null) ? svc.getClusterAt(level, pos) : null;
    }

    /** 获取当前维度所有活跃的宏观领地聚类 */
    public static Collection<MacroCluster> getAllClusters(ServerLevel level) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        return (svc != null) ? svc.getClusterManager(level.dimension().location()).getAllClusters() : List.of();
    }

    /** 查询某位置的显性统治派系（若为荒野或无统治返回 null） */
    public static FactionDefinition getDominantFaction(ServerLevel level, BlockPos pos) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        return (svc != null) ? svc.getDominantFactionAt(level, pos) : null;
    }

    /** 查询某位置的显性统治派系 ID 字符串 */
    public static String getDominantFactionId(ServerLevel level, BlockPos pos) {
        FactionDefinition f = getDominantFaction(level, pos);
        return (f != null) ? f.id().toString() : "";
    }

    /** 检查某位置是否由指定派系统治 */
    public static boolean isFactionDominant(ServerLevel level, BlockPos pos, String factionIdStr) {
        FactionDefinition f = getDominantFaction(level, pos);
        return f != null && f.id().toString().equals(factionIdStr);
    }

    /** 检查实体身上是否带有指定的领地 Tag */
    public static boolean hasFactionTag(LivingEntity entity, String tag) {
        return entity != null && entity.getTags().contains(tag);
    }

    // ===================================================================
    //  向后兼容接口 (Backward Compatibility)
    // ===================================================================

    /**
     * 查询某位置的非文明归一化分数 [0, 1]。
     * 等效于文礼 CivilizationService.getScoreAt()。
     */
    public static double getScoreAt(ServerLevel level, BlockPos pos) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return 0.0;
        return svc.getNormalizedScoreAt(level, pos);
    }

    /** 获取当前所有被冒险区 buff 的实体 UUID */
    public static Set<UUID> getBuffedEntityUUIDs() {
        var tagManager = CivilAdventureMod.getEntityTagManager();
        if (tagManager == null) return Set.of();
        return tagManager.getBuffedEntityUUIDs();
    }

    /** 查询当前宏观领地覆盖的区块数 */
    public static int getZoneSize(ServerLevel level, BlockPos pos) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return 0;
        int radius = civil_adventure.config.AdventureConfig.DETECTION_RADIUS.get();
        return svc.getZoneSize(level, pos, radius);
    }

    /** 查询当前宏观领地显性统治总分 */
    public static double getRawScoreAt(ServerLevel level, BlockPos pos) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return 0.0;
        return svc.getRawScoreAt(level, pos);
    }

    /** 查询当前宏观领地的质心/中心坐标 */
    public static BlockPos getZoneCenter(ServerLevel level, BlockPos pos) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return pos;
        return svc.getZoneCenter(level, pos);
    }
}
