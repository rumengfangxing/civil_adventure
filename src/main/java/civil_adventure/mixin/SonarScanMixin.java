package civil_adventure.mixin;

import civil_adventure.CivilAdventureMod;
import civil_adventure.config.AdventureConfig;
import civil_adventure.score.AdventureScoreService;
import civil.aura.SonarScan;
import civil.civilization.VoxelChunkKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让文礼探测器识别冒险区，显示声呐边界墙。
 * 同时影响 playerInHigh 判定（构造时）和 BFS 扩展（运行时）。
 */
@Mixin(value = SonarScan.class, remap = false)
public class SonarScanMixin {

    @Inject(
        method = "computeIsCivHighForBfs",
        at = @At("RETURN"),
        cancellable = true,
        require = 0
    )
    private static void adventure$overrideBfsHigh(
        ServerLevel world, VoxelChunkKey key, BlockPos pos,
        CallbackInfoReturnable<Boolean> cir) {

        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        boolean isAdventure = svc.getNormalizedScoreAt(world, pos) >= AdventureConfig.ZONE_THRESHOLD.get();
        if (!isAdventure) return;

        // 冒险区优先级高于文礼文明区
        // Civil 说 HIGH → 覆写为 LOW（在文明区中挖出冒险区边界）
        // Civil 说 LOW  → 覆写为 HIGH（在荒野中亮出冒险区边界）
        boolean civHigh = cir.getReturnValue();
        if (civHigh) {
            CivilAdventureMod.LOGGER.debug("[sonar] adventure overrides civ HIGH→LOW at {}", pos);
            cir.setReturnValue(false);
        } else {
            CivilAdventureMod.LOGGER.debug("[sonar] adventure overrides civ LOW→HIGH at {}", pos);
            cir.setReturnValue(true);
        }
    }
}
