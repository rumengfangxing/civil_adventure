package civil_adventure.mixin;

import civil_adventure.client.ClientClusterCache;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截 Xaero 大地图 (World Map) 高亮：
 * 1. 替换统一的 "冒险区" 提示为所属【具体派系名称】，并赋予对应派系代表色。
 * 2. 替换统一的红色高亮块为所属【派系自定义专属代表色】。
 */
@Pseudo
@Mixin(targets = "civil.compat.xaeros.highlight.CivilWorldMapChunkBandHighlighter", remap = false)
public class CivilWorldMapMixin {

    @Inject(method = "calculateRegionHash", at = @At("RETURN"), cancellable = true, require = 0)
    private void adventure$onCalculateRegionHash(ResourceKey<Level> dimKey, int rx, int rz, CallbackInfoReturnable<Integer> cir) {
        if (dimKey == null) return;
        int extraHash = ClientClusterCache.getRegionHighlightHash(dimKey.location(), rx, rz);
        if (extraHash != 0) {
            cir.setReturnValue(cir.getReturnValue() * 31 + extraHash);
        }
    }

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
    private void adventure$onGetWorldMapColors(ResourceKey<Level> dimKey, int cx, int cz, CallbackInfoReturnable<int[]> cir) {
        if (dimKey == null) return;
        int[] colors = ClientClusterCache.getWorldMapColors(dimKey.location(), cx, cz);
        if (colors != null) {
            cir.setReturnValue(colors);
        }
    }

    @Inject(method = "getChunkHighlightBluntTooltip", at = @At("HEAD"), cancellable = true, require = 0)
    private void adventure$onBluntTooltip(ResourceKey<Level> dimKey, int cx, int cz, CallbackInfoReturnable<Component> cir) {
        if (dimKey == null) return;
        if (ClientClusterCache.isUnscoutedDanger(dimKey.location(), cx, cz)) {
            cir.setReturnValue(null); // 完全去掉未勘明黑盒区域的白色提示框与文字提示
            return;
        }
        Component c = ClientClusterCache.getTooltip(dimKey.location(), cx, cz);
        if (c != null) cir.setReturnValue(c);
    }

    @Inject(method = "getChunkHighlightSubtleTooltip", at = @At("HEAD"), cancellable = true, require = 0)
    private void adventure$onSubtleTooltip(ResourceKey<Level> dimKey, int cx, int cz, CallbackInfoReturnable<Component> cir) {
        if (dimKey == null) return;
        if (ClientClusterCache.isUnscoutedDanger(dimKey.location(), cx, cz)) {
            cir.setReturnValue(null); // 完全去掉未勘明黑盒区域的白色提示框与文字提示
            return;
        }
        Component c = ClientClusterCache.getTooltip(dimKey.location(), cx, cz);
        if (c != null) cir.setReturnValue(c);
    }
}
