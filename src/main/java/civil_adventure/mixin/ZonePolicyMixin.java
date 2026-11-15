package civil_adventure.mixin;

import civil_adventure.CivilAdventureMod;
import civil_adventure.cluster.MacroCluster;
import civil_adventure.faction.FactionDefinition;
import civil_adventure.override.CivillisStructureTracker;
import civil_adventure.score.AdventureScoreService;
import civil.civilization.VoxelChunkKey;
import civil.civilization.ZonePolicyService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截文礼 ZonePolicyService。
 * 遵循 2.0-FINAL 架构规范：
 * 1. 结构被肃清后，原版冒险区判定永久失效（除非新派系重新滋生夺取统治）。
 * 2. 依据宏观连通域成王败寇裁决：
 *    - 显性派系为【非文明派系】时，强制作为文礼 ZONE 处理（紫色边界墙、雷达警报）。
 *    - 显性派系为【文明派系】时，视为安全秩序区。
 * 3. 允许显性非文明区内自然刷怪。
 */
@Mixin(value = ZonePolicyService.class, remap = false)
public class ZonePolicyMixin {

    @Inject(
        method = "treatAsNonCivilized(Lnet/minecraft/server/level/ServerLevel;Lcivil/civilization/VoxelChunkKey;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void adventure$treatAsNonCivilized(
        ServerLevel world, VoxelChunkKey vc,
        CallbackInfoReturnable<Boolean> cir
    ) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        BlockPos center = new BlockPos((vc.getCx() << 4) + 8, (vc.getSy() << 4) + 8, (vc.getCz() << 4) + 8);
        MacroCluster cluster = svc.getClusterAt(world, center);

        if (cluster != null && cluster.hasDominance()) {
            FactionDefinition dom = cluster.getDominantFaction();
            if (dom != null) {
                if (dom.isCivilization()) {
                    // 文明派系统治：非冒险区
                    cir.setReturnValue(false);
                    return;
                } else {
                    // 非文明派系（灾厄、真菌等）统治：作为 ZONE 处理
                    cir.setReturnValue(true);
                    return;
                }
            }
        }

        // 无显性非文明派系统治的区域（荒野或已肃清结构），绝不作为文礼 ZONE 处理
        cir.setReturnValue(false);
    }

    @Inject(
        method = "allowsHostileSpawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/EntityType;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void adventure$allowsHostileSpawn(
        ServerLevel world, BlockPos pos, EntityType<?> entityType,
        CallbackInfoReturnable<Boolean> cir
    ) {
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        MacroCluster cluster = svc.getClusterAt(world, pos);
        if (cluster != null && cluster.hasDominance()) {
            FactionDefinition dom = cluster.getDominantFaction();
            if (dom != null) {
                if (!dom.isCivilization()) {
                    cir.setReturnValue(true);
                    return;
                } else {
                    cir.setReturnValue(false);
                    return;
                }
            }
        }

        if (CivillisStructureTracker.isStructureCleansed(world, pos)) {
            cir.setReturnValue(false);
        }
    }
}
