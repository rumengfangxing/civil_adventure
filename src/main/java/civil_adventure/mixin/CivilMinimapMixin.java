package civil_adventure.mixin;

import civil_adventure.client.ClientClusterCache;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截 Xaero 小地图雷达 (Minimap) 高亮：
 * 将原本统一的红色高亮块替换为所属【派系自定义专属代表色】。
 */
@Pseudo
@Mixin(targets = "civil.compat.xaeros.highlight.CivilMinimapChunkBandHighlighter", remap = false)
public class CivilMinimapMixin {

    @Inject(method = "regionHasHighlights", at = @At("HEAD"), cancellable = true, require = 0)
    private void adventure$onRegionHasHighlights(ResourceKey<Level> dimKey, int rx, int rz, CallbackInfoReturnable<Boolean> cir) {
        if (dimKey == null) return;
        if (ClientClusterCache.regionHasAnyHighlights(dimKey.location(), rx, rz)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "chunkIsHighlit", at = @At("HEAD"), cancellable = true, require = 0)
    private void adventure$onChunkIsHighlit(ResourceKey<Level> dimKey, int cx, int cz, CallbackInfoReturnable<Boolean> cir) {
        if (dimKey == null) return;
        if (ClientClusterCache.isChunkHighlit(dimKey.location(), cx, cz)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getColors", at = @At("HEAD"), cancellable = true, require = 0)
    private void adventure$onGetMinimapColors(ResourceKey<Level> dimKey, int cx, int cz, CallbackInfoReturnable<int[]> cir) {
        if (dimKey == null) return;
        int[] colors = ClientClusterCache.getMinimapColors(dimKey.location(), cx, cz);
        if (colors != null) {
            cir.setReturnValue(colors);
        }
    }
}
