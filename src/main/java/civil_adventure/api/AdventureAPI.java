package civil_adventure.api;

import civil_adventure.CivilAdventureMod;
import civil_adventure.score.AdventureScoreService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Set;
import java.util.UUID;

/**
 * 公开 API，仿文礼 CivilizationService 模式。
 *
 * 用法：
 *   AdventureAPI.getScoreAt(level, pos);         // 归一化冒险分 [0,1]
 *   AdventureAPI.getBuffedEntityUUIDs();          // 被强化的实体
 */
public final class AdventureAPI {
    private AdventureAPI() {
    }

    /**
     * 查询某位置的冒险区归一化分数 [0, 1]。
     * 自动聚合检测半径内所有区块的原始分并归一化（/5.0）。
     * 等效于文礼 CivilizationService.getScoreAt()。
     */
    public static double getScoreAt(ServerLevel level, BlockPos pos) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return 0;
        return svc.getNormalizedScoreAt(level, pos);
    }

    /** 获取当前所有被冒险区 buff 的实体 UUID */
    public static Set<UUID> getBuffedEntityUUIDs() {
        var scanner = CivilAdventureMod.getZoneScanner();
        if (scanner == null) return Set.of();
        return scanner.getBuffedEntityUUIDs();
    }

    /**
     * 检测半径内冒险区区块数（归一化 ≥ 0.7）。
     * 反映冒险区的实际覆盖范围，可用于难度缩放。
     */
    public static int getZoneSize(ServerLevel level, BlockPos pos) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return 0;
        int radius = civil_adventure.config.AdventureConfig.DETECTION_RADIUS.get();
        return svc.getZoneSize(level, pos, radius);
    }

    /** 检测半径内原始分之和（无归一化上限） */
    public static double getRawScoreAt(ServerLevel level, BlockPos pos) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return 0;
        return svc.getRawScoreAt(level, pos);
    }

    /** 冒险区中心坐标（检测半径内所有冒险区块的平均位置） */
    public static BlockPos getZoneCenter(ServerLevel level, BlockPos pos) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return pos;
        return svc.getZoneCenter(level, pos);
    }
}
