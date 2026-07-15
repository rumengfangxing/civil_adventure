package civil_adventure.mixin;

import civil_adventure.CivilAdventureMod;
import civil_adventure.score.AdventureBlockLoader;
import civil_adventure.score.AdventureScoreService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayDeque;
import java.util.Deque;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 仿文礼 CivilLevelBlockChangeMixin，拦截 Level.setBlock 捕获方块变化。
 * 当冒险区方块被放置/破坏时，全量扫描区块重新计算分数。
 *
 * 线程安全：HEAD/RETURN 之间任一退出路径均保证 pop 平衡。
 */
@Mixin(Level.class)
public class AdventureBlockChangeMixin {

    private static final ThreadLocal<ArrayDeque<BlockState>> oldStateStack =
        ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"))
    private void captureOldState(BlockPos pos, BlockState state, int flags,
                                  CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerLevel level) {
            oldStateStack.get().push(level.getBlockState(pos));
        }
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("RETURN"))
    private void onBlockSet(BlockPos pos, BlockState newState, int flags,
                             CallbackInfoReturnable<Boolean> cir) {
        // 先取旧状态（无论 success 与否都必须 pop 保持栈平衡）
        Deque<BlockState> stack = oldStateStack.get();
        BlockState oldState = stack.isEmpty() ? null : stack.pop();
        if (stack.isEmpty()) oldStateStack.remove();

        // 非服务端或变更失败 → 已 cleanup，直接返回
        if (!((Object) this instanceof ServerLevel level)) return;
        if (!cir.getReturnValue()) return;

        boolean oldIsAdventure = oldState != null && AdventureBlockLoader.hasWeight(oldState.getBlock());
        boolean newIsAdventure = AdventureBlockLoader.hasWeight(newState.getBlock());
        if (!oldIsAdventure && !newIsAdventure) return;

        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        String dim = level.dimension().location().toString();
        svc.markDirty(dim, pos.getX() >> 4, pos.getZ() >> 4);
    }
}
