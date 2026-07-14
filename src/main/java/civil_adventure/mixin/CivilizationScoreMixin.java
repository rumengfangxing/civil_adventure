package civil_adventure.mixin;

import civil_adventure.CivilAdventureMod;
import civil_adventure.config.AdventureConfig;
import civil_adventure.score.AdventureScoreService;
import civil.civilization.CScore;
import civil.civilization.scoring.ScalableCivilizationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 冒险区活跃时，向文礼返回 0 文明分，彻底顶掉文明区效果。
 * 同时覆写 getScoreAt 和 getCScoreAt，拦截所有文明机制路径。
 */
@Mixin(value = ScalableCivilizationService.class, remap = false)
public class CivilizationScoreMixin {

    @Inject(
        method = "getScoreAt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)D",
        at = @At("RETURN"),
        cancellable = true,
        require = 0
    )
    private void adventure$overrideCivilScore(
        ServerLevel level, BlockPos pos, CallbackInfoReturnable<Double> cir) {
        if (cir.getReturnValue() <= 0.0) return;
        if (isAdventureZone(level, pos)) cir.setReturnValue(0.0);
    }

    @Inject(
        method = "getCScoreAt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Lcivil/civilization/CScore;",
        at = @At("RETURN"),
        cancellable = true,
        require = 0
    )
    private void adventure$overrideCScore(
        ServerLevel level, BlockPos pos, CallbackInfoReturnable<CScore> cir) {
        if (cir.getReturnValue() == null) return;
        if (isAdventureZone(level, pos)) cir.setReturnValue(new CScore(0.0));
    }

    private static boolean isAdventureZone(ServerLevel level, BlockPos pos) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return false;
        return svc.getNormalizedScoreAt(level, pos) >= AdventureConfig.CIV_OVERRIDE_THRESHOLD.get();
    }
}
