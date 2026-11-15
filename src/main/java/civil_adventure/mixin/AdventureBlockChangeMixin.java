package civil_adventure.mixin;

import civil_adventure.CivilAdventureMod;
import civil_adventure.score.AdventureBlockLoader;
import civil_adventure.score.AdventureScoreService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;

/**
 * 拦截 Level.setBlock 捕获方块变化。
 * 当冒险区方块被放置/破坏时，标记脏区块触发防抖分数重算。
 * [修复 REG-01] 拦截 4 参数底层 setBlock 方法 (覆盖 destroyBlock 等路径)；
 * 先做 ServerLevel 判空杜绝客户端线程分配；复用 ThreadLocal ArrayDeque 实例杜绝每次销毁。
 */
@Mixin(Level.class)
public class AdventureBlockChangeMixin {

    @Unique
    private record PosStateSnapshot(BlockPos pos, BlockState state) {}

    @Unique
    private static final ThreadLocal<ArrayDeque<PosStateSnapshot>> civilAdventure$oldStateStack =
        ThreadLocal.withInitial(() -> new ArrayDeque<>(8));

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD")
    )
    private void civilAdventure$captureOldState(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                                CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel level) {
            ArrayDeque<PosStateSnapshot> stack = civilAdventure$oldStateStack.get();
            if (stack.size() > 64) {
                stack.clear(); // [修复 M-04] 异常逃逸自愈保护
            }
            stack.push(new PosStateSnapshot(pos.immutable(), level.getBlockState(pos)));
        }
    }

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("RETURN")
    )
    private void civilAdventure$onBlockSet(BlockPos pos, BlockState newState, int flags, int recursionLeft,
                                           CallbackInfoReturnable<Boolean> cir) {
        // 1. 先拦截非 ServerLevel，彻底杜绝 ClientLevel 线程触发 ThreadLocal 初始化
        if (!((Object) this instanceof ServerLevel level)) return;

        // 2. 弹出匹配的坐标快照，保留空 ArrayDeque 实例在 ThreadLocal 中复用，严禁每次 remove()
        ArrayDeque<PosStateSnapshot> stack = civilAdventure$oldStateStack.get();
        PosStateSnapshot matched = null;
        while (!stack.isEmpty()) {
            PosStateSnapshot top = stack.pop();
            if (top.pos().equals(pos)) {
                matched = top;
                break;
            }
        }

        if (!cir.getReturnValue() || matched == null) return;

        BlockState oldState = matched.state();
        if (oldState.is(newState.getBlock())) return;

        boolean oldIsAdventure = (oldState != null && (
            civil_adventure.faction.FactionRegistry.isAnyFactionBlock(oldState) ||
            AdventureBlockLoader.hasWeight(oldState.getBlock())
        ));
        boolean newIsAdventure = (newState != null && (
            civil_adventure.faction.FactionRegistry.isAnyFactionBlock(newState) ||
            AdventureBlockLoader.hasWeight(newState.getBlock())
        ));
        if (!oldIsAdventure && !newIsAdventure) return;

        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        svc.markDirty(level.dimension().location(), pos.getX() >> 4, pos.getZ() >> 4);
    }
}
