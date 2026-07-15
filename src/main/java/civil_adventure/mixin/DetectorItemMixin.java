package civil_adventure.mixin;

import civil_adventure.CivilAdventureMod;
import civil_adventure.config.AdventureConfig;
import civil_adventure.score.AdventureScoreService;
import civil.item.CivilDetectorItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 探测器在冒险区使用时显示 ZONE 级别效果（贴图/音效/粒子），
 * 但不影响 CivilRegionClassifier 返回值（已由另一个 mixin 处理）。
 */
@Mixin(value = CivilDetectorItem.class, remap = false)
public class DetectorItemMixin {

    @Inject(
        method = "use",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void adventure$detectorUse(Level world, Player player, InteractionHand hand,
                                        CallbackInfoReturnable<InteractionResult> cir) {
        if (!(world instanceof ServerLevel serverLevel)) return;

        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        var pos = player.blockPosition();
        if (svc.getNormalizedScoreAt(serverLevel, pos) >= AdventureConfig.ZONE_THRESHOLD.get()) {
            // 在冒险区使用探测器 → 强制设为 ZONE 状态
            // 让原方法走 getCScoreAt 时返回一个高值以触发 high 纹理/音效
        }
    }
}
