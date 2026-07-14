package civil_adventure.mixin;

import civil_adventure.CivilAdventureMod;
import civil_adventure.config.AdventureConfig;
import civil_adventure.score.AdventureScoreService;
import civil.map.CivilMapTintPalette;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 冒险区归一化分 ≥ 0.7 时，覆写区块地图色调为冒险区色（band 6）。
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
        CivilMapTintPalette.ChunkTintEval original = cir.getReturnValue();
        if (original == null) return;

        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        BlockPos center = new BlockPos((cx << 4) + 8, (sy << 4) + 8, (cz << 4) + 8);
        double normalized = svc.getNormalizedScoreAt(level, center);

        if (normalized >= AdventureConfig.ZONE_THRESHOLD.get()) {
            cir.setReturnValue(new CivilMapTintPalette.ChunkTintEval((byte) 6, null));
        }
    }
}
