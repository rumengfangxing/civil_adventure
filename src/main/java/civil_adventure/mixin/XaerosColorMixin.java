package civil_adventure.mixin;

import civil_adventure.config.AdventureConfig;
import civil.compat.xaeros.highlight.CivilXaeroChunkBandHighlightSupport;
import civil.map.CivilMapTintPalette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Xaeros 小地图上冒险区颜色取配置值。
 */
@Mixin(value = CivilXaeroChunkBandHighlightSupport.class, remap = false)
public class XaerosColorMixin {

    @Inject(
        method = "argbForBand",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void adventure$xaerosColor(byte band, CallbackInfoReturnable<Integer> cir) {
        if (band != CivilMapTintPalette.ZONE) return;
        int alpha = AdventureConfig.XAEROS_ALPHA.get();
        int color = AdventureConfig.parseHexColor(AdventureConfig.ADVENTURE_FILL_COLOR.get()) & 0xFFFFFF;
        cir.setReturnValue((alpha << 24) | color);
    }
}
