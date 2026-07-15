package civil_adventure.mixin;

import civil_adventure.CivilAdventureMod;
import civil_adventure.config.AdventureConfig;
import civil_adventure.score.AdventureScoreService;
import civil.aura.SonarScanManager;
import civil.civilization.VoxelChunkKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;

/**
 * 仅影响文明探测器显示：冒险区区块以文礼 ZONE 同等方式在探测器上渲染。
 * 不修改任何现有逻辑（不影响地图颜色、实体强化、文明区覆写）。
 */
@Mixin(value = SonarScanManager.class, remap = false)
public class ZonePolicyMixin {

    @Inject(
        method = "computeZonePolicyData",
        at = @At("RETURN"),
        cancellable = true,
        require = 0
    )
    private static void adventure$injectZoneCells(
        ServerLevel world, VoxelChunkKey center, int filterRange,
        Set<Long> shrineBypass2D,
        CallbackInfoReturnable<Object> cir
    ) {
        Object original = cir.getReturnValue();
        if (original == null) return;

        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        // 找出我们的冒险区块
        double threshold = AdventureConfig.ZONE_THRESHOLD.get();
        int sy = center.getSy();
        Set<Long> ourCells = new HashSet<>();

        for (int dx = -filterRange; dx <= filterRange; dx++) {
            for (int dz = -filterRange; dz <= filterRange; dz++) {
                int cx = center.getCx() + dx;
                int cz = center.getCz() + dz;
                long packed = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                if (shrineBypass2D.contains(packed)) continue;

                if (svc.getNormalizedScoreAt(world, new BlockPos(cx * 16 + 8, sy * 16 + 8, cz * 16 + 8)) >= threshold) {
                    ourCells.add(packed);
                }
            }
        }

        if (ourCells.isEmpty()) return;

        // 获取原结果的 faces 和 envelope2DToY
        // 通过反射兼容私有 record
        try {
            var facesField = original.getClass().getDeclaredField("faces");
            facesField.setAccessible(true);
            var yRangeField = original.getClass().getDeclaredField("envelope2DToY");
            yRangeField.setAccessible(true);

            List<Object> originalFaces = (List<Object>) facesField.get(original);
            Map<Long, float[]> originalY = (Map<Long, float[]>) yRangeField.get(original);

            // 合并我们的区块
            Map<Long, float[]> mergedY = new HashMap<>(originalY);
            for (long cell : ourCells) {
                mergedY.putIfAbsent(cell, new float[]{layerMinY(sy), layerMaxY(sy)});
            }

            // 构造新的 EnvelopeResult
            // 使用反射调用私有 record 构造器
            var constructor = original.getClass().getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object result = constructor.newInstance(originalFaces, mergedY);
            cir.setReturnValue(result);

        } catch (Exception e) {
            CivilAdventureMod.LOGGER.warn("注入冒险区到探测器失败: {}", e.getMessage());
        }
    }

    private static float layerMinY(int sy) { return sy * 16.0f; }
    private static float layerMaxY(int sy) { return (sy + 1) * 16.0f; }
}
