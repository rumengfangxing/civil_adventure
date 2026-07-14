package civil_adventure.mixin;

import civil_adventure.CivilAdventureMod;
import civil_adventure.config.AdventureConfig;
import civil_adventure.score.AdventureScoreService;
import civil.civilization.CivilRegionClassifier;
import civil.civilization.CivilRegionClassifier.ClassifyResult;
import civil.civilization.CivilRegionKind;
import civil.civilization.VoxelChunkKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 当冒险区活跃时，顶掉文礼的文明区分类（返回 NONE）。
 * 冒险区与文明区互斥——区块是冒险区就不再是文明区。
 */
@Mixin(value = CivilRegionClassifier.class, remap = false)
public class CivilRegionClassifierMixin {

    @Inject(
        method = "classify(Lnet/minecraft/server/level/ServerLevel;Lcivil/civilization/VoxelChunkKey;)Lcivil/civilization/CivilRegionClassifier$ClassifyResult;",
        at = @At("RETURN"),
        cancellable = true,
        require = 0
    )
    private static void adventure$overrideCivilization(
        ServerLevel level, VoxelChunkKey vc,
        CallbackInfoReturnable<ClassifyResult> cir
    ) {
        ClassifyResult original = cir.getReturnValue();
        if (original == null) return;

        // 只有文礼判定为文明区（HIGH）时才需要顶掉
        if (original.kind() != CivilRegionKind.HIGH) return;

        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        // 取区块中心位置检测冒险区分数
        BlockPos center = new BlockPos(
            vc.getCx() * 16 + 8,
            vc.getSy() * 16 + 8,
            vc.getCz() * 16 + 8
        );

        if (svc.getNormalizedScoreAt(level, center) >= AdventureConfig.ZONE_THRESHOLD.get()) {
            // 冒险区覆盖 → 返回 NONE 使文礼视为无文明区域
            cir.setReturnValue(new ClassifyResult(CivilRegionKind.NONE, null));
        }
    }
}
