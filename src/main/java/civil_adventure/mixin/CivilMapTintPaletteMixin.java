package civil_adventure.mixin;

import civil_adventure.CivilAdventureMod;
import civil_adventure.override.CivillisStructureTracker;
import civil_adventure.score.AdventureScoreService;
import civil.map.CivilMapTintPalette;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截文礼纸质地图（Civil Map）与烘焙色阶（evaluateTintForChunk）：
 * 1. 结构被彻底肃清后，禁止继续烘焙为冒险区（ZONE/band 6），恢复正常地形色。
 * 2. 宏观连通域成王败寇裁决：
 *    - 显性派系为【非文明派系】时，烘焙为冒险区警戒色（band 6）。
 *    - 显性派系为【文明派系】时，烘焙为文明领域白色（band 3）。
 */
@Mixin(value = CivilMapTintPalette.class, remap = false)
public class CivilMapTintPaletteMixin {

    @Inject(
        method = "evaluateTintForChunk",
        at = @At("RETURN"),
        cancellable = true,
        require = 0
    )
    private static void adventure$onEvaluateTint(
        ServerLevel level, int cx, int cz, int sy,
        CallbackInfoReturnable<CivilMapTintPalette.ChunkTintEval> cir
    ) {
        BlockPos center = new BlockPos((cx << 4) + 8, (sy << 4) + 8, (cz << 4) + 8);

        // 1. 已肃清结构：切断文礼原生原版结构冒险区着色
        if (CivillisStructureTracker.isStructureCleansed(level, center)) {
            cir.setReturnValue(new CivilMapTintPalette.ChunkTintEval((byte) 5, null));
            return;
        }

        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        civil_adventure.cluster.MacroCluster cluster = svc.getClusterAt(level, center);

        if (cluster != null && cluster.hasDominance()) {
            civil_adventure.faction.FactionDefinition dom = cluster.getDominantFaction();
            if (dom != null) {
                if (!dom.isCivilization()) {
                    // 敌对派系统治：烘焙为冒险区橙红警戒色 (band 6)
                    cir.setReturnValue(new CivilMapTintPalette.ChunkTintEval((byte) 6, null));
                } else {
                    // 文明派系统治：烘焙为文明领域浅白底色 (band 3)
                    cir.setReturnValue(new CivilMapTintPalette.ChunkTintEval((byte) 3, null));
                }
            }
        }
    }
}
