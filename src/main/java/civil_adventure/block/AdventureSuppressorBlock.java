package civil_adventure.block;

import civil_adventure.CivilAdventureMod;
import civil_adventure.data.AdventureFuelLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import javax.annotation.Nullable;

/**
 * 冒险区压制方块。
 * 右键切换启用/停用；启用时内置计时器倒数，期间持续压制周围冒险区原始分。
 * 挖掘掉落本体。
 */
public class AdventureSuppressorBlock extends Block implements EntityBlock {

    public static final BooleanProperty ENABLED = BooleanProperty.create("enabled");

    /** 高为 3/4 格的长方体碰撞箱（对应模型形状） */
    private static final VoxelShape SHAPE = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.75, 1.0);

    public AdventureSuppressorBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(ENABLED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ENABLED);
    }

    // ── 3/4 高碰撞箱 ───────────────────────────────────

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        // 不完整方块：不遮挡相邻方块的面（底下方块上表面保留渲染）
        return Shapes.empty();
    }

    // ── 渲染：使用方块实体渲染器（GeckoLib） ────────────

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    // ── 红石控制 ─────────────────────────────────────────

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos,
                                net.minecraft.world.level.block.Block neighborBlock,
                                net.minecraft.core.BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide) return;
        updateFromRedstone(state, level, pos);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide) return;
        updateFromRedstone(state, level, pos);
    }

    /** 红石信号：仅在信号变化时启用/停用（右键切换优先，避免反复翻转） */
    private void updateFromRedstone(BlockState state, Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof AdventureSuppressorBlockEntity suppressor) {
            suppressor.updateRedstone(state, level.hasNeighborSignal(pos));
        }
    }

    // ── 右键：手持燃料→加时；其他→切换启用/停用 ──────────

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof AdventureSuppressorBlockEntity suppressor)) {
            return InteractionResult.PASS;
        }

        // 手持燃料 → 消耗 + 加时
        ItemStack held = player.getItemInHand(hand);
        int fuelTicks = AdventureFuelLoader.getFuelTicks(held.getItem());
        if (fuelTicks > 0) {
            suppressor.addTime(fuelTicks);
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            player.displayClientMessage(
                Component.literal("§a已充能 +" + fuelTicks + " tick（当前 " + suppressor.getRemainingTicks() + "）"), true);
            return InteractionResult.SUCCESS;
        }

        // 其他 → 切换启用/停用
        boolean enabled = state.getValue(ENABLED);
        if (enabled) {
            // 停用：计时冻结
            level.setBlockAndUpdate(pos, state.setValue(ENABLED, false));
            player.displayClientMessage(
                Component.literal("§c冒险区压制已停用（计时保留）"), true);
        } else {
            // 启用：开始扣计时
            if (suppressor.getRemainingTicks() <= 0) {
                player.displayClientMessage(
                    Component.literal("§c计时为 0，需要先附加时间"), true);
                return InteractionResult.SUCCESS;
            }
            level.setBlockAndUpdate(pos, state.setValue(ENABLED, true));
            player.displayClientMessage(
                Component.literal("§a冒险区压制已启用（剩余 " + suppressor.getRemainingTicks() + " tick）"), true);
        }
        return InteractionResult.SUCCESS;
    }

    // ── 方块移除时释放压制 ──────────────────────────────

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof AdventureSuppressorBlockEntity suppressor) {
                suppressor.releaseSuppression();
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // ── 方块实体 ────────────────────────────────────────

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AdventureSuppressorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof AdventureSuppressorBlockEntity suppressor) {
                suppressor.serverTick();
            }
        };
    }
}
