package civil_adventure.block;

import civil_adventure.CivilAdventureMod;
import civil_adventure.config.AdventureConfig;
import civil_adventure.score.AdventureScoreService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;

import java.util.HashMap;
import java.util.Map;

/**
 * 冒险区压制方块实体（GeckoLib 动画）。
 * 状态机：
 * - 停用 → idle（待机）
 * - 启用未完全压制 → overload（超负荷，快速旋转）
 * - 启用已完全压制 → running（运转，慢速旋转）
 * - 停用→启用 → start + running 过渡后保持 overload
 * - 启用→停用 → running + close 过渡后保持 idle
 */
public class AdventureSuppressorBlockEntity extends BlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache cache =
        new software.bernie.geckolib.core.animatable.instance.InstancedAnimatableInstanceCache(this);

    /** 剩余生效 tick（0 = 无时间） */
    private int timerTicks = 0;
    /** 距离下次压制动作的 tick */
    private int suppressCooldown = 0;
    /** 本方块贡献的压制量（区块键 → 压制量），用于释放回弹 */
    private final Map<String, Double> localSuppression = new HashMap<>();

    // 动画状态（上次设置的动画目标，null = 未初始化）
    private AnimTarget animTarget = null;

    /** 动画目标状态 */
    private enum AnimTarget { IDLE, OVERLOAD, RUNNING }

    /** 上次红石信号状态（用于检测信号变化） */
    private boolean lastPowered = false;
    private boolean poweredInitialized = false;

    public AdventureSuppressorBlockEntity(BlockPos pos, BlockState state) {
        super(CivilAdventureMod.SUPPRESSOR_BLOCK_ENTITY.get(), pos, state);
    }

    /** 服务端 tick */
    public void serverTick() {
        if (level == null || level.isClientSide) return;

        boolean enabled = getBlockState().hasProperty(AdventureSuppressorBlock.ENABLED)
            && getBlockState().getValue(AdventureSuppressorBlock.ENABLED);

        // 停用 → 释放压制（回弹）、计时冻结
        if (!enabled) {
            releaseSuppression();
            return;
        }
        if (timerTicks <= 0) return;

        timerTicks--;

        if (--suppressCooldown <= 0) {
            applySuppression();
            suppressCooldown = AdventureConfig.SUPPRESS_INTERVAL.get();
        }

        if (timerTicks <= 0) {
            // 计时归零 → 自动停用 + 释放压制
            BlockState state = getBlockState();
            if (state.hasProperty(AdventureSuppressorBlock.ENABLED)) {
                level.setBlockAndUpdate(worldPosition,
                    state.setValue(AdventureSuppressorBlock.ENABLED, false));
            }
            releaseSuppression();
            setChanged();
        }
    }

    /** 累积压制：基于原始分额外压制 */
    private void applySuppression() {
        if (!(level instanceof ServerLevel serverLevel)) return;

        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        double percent = AdventureConfig.SUPPRESS_PERCENT.get();
        double fixed = AdventureConfig.SUPPRESS_FIXED.get();
        int radius = AdventureConfig.SUPPRESS_RADIUS.get();

        Map<String, Double> added = svc.suppressRegionScores(serverLevel, worldPosition, radius, percent, fixed);
        added.forEach((key, amount) -> localSuppression.merge(key, amount, Double::sum));
    }

    /** 释放本方块贡献的所有压制（立即回弹） */
    public void releaseSuppression() {
        if (level == null || level.isClientSide) return;
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        localSuppression.forEach((key, amount) -> svc.removeSuppression(key, amount));
        localSuppression.clear();
    }

    /** 附加时间（供外部机制调用） */
    public void addTime(int ticks) {
        this.timerTicks += ticks;
        setChanged();
    }

    public boolean isActive() {
        return timerTicks > 0;
    }

    public int getRemainingTicks() {
        return Math.max(0, timerTicks);
    }

    /** 区域内冒险分是否已被完全压制到 0 */
    public boolean isZoneFullySuppressed() {
        return localSuppression.isEmpty();
    }

    // ── GeckoLib 动画 ──────────────────────────────────

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 2, this::animPredicate));
    }

    private PlayState animPredicate(AnimationState<AdventureSuppressorBlockEntity> state) {
        boolean enabled = getBlockState().hasProperty(AdventureSuppressorBlock.ENABLED)
            && getBlockState().getValue(AdventureSuppressorBlock.ENABLED);
        boolean running = isZoneFullySuppressed();

        AnimationController<AdventureSuppressorBlockEntity> controller = state.getController();

        // 计算目标状态
        AnimTarget target = !enabled ? AnimTarget.IDLE
            : (running ? AnimTarget.RUNNING : AnimTarget.OVERLOAD);

        // 状态未变化 → 不干预（防止每 tick 重置导致抽搐）
        if (target == animTarget) return PlayState.CONTINUE;

        AnimTarget from = animTarget;
        animTarget = target;
        CivilAdventureMod.LOGGER.info("[anim] {} → {} (enabled={} running={} localSupp={})",
            from, target, enabled, running, localSuppression.size());

        if (from == null) {
            // 首次：直接进入目标状态
            controller.setAnimation(RawAnimation.begin()
                .thenLoop(target == AnimTarget.IDLE ? "idle" : (target == AnimTarget.RUNNING ? "running" : "overload")));
        } else if (from == AnimTarget.IDLE) {
            // (1)停用→(2)启用：连续播放 启动+运转，再彻底变为(2)
            controller.setAnimation(RawAnimation.begin()
                .thenPlay("start")
                .thenPlay("running")
                .thenLoop(target == AnimTarget.RUNNING ? "running" : "overload"));
        } else {
            // (2)启用→(1)停用：连续播放 运转+启动，再彻底变为(1)
            controller.setAnimation(RawAnimation.begin()
                .thenPlay("running")
                .thenPlay("start")
                .thenLoop("idle"));
        }
        return PlayState.CONTINUE;
    }

    /** 红石信号更新：仅在信号变化时覆盖状态（右键切换优先，避免反复翻转） */
    public void updateRedstone(BlockState state, boolean powered) {
        if (!poweredInitialized) {
            poweredInitialized = true;
            lastPowered = powered;
            // 放置时已有信号 → 立即启用
            if (powered && level != null) {
                level.setBlockAndUpdate(worldPosition, state.setValue(AdventureSuppressorBlock.ENABLED, true));
            }
            return;
        }
        if (powered == lastPowered) return;
        lastPowered = powered;
        if (level != null) {
            level.setBlockAndUpdate(worldPosition, state.setValue(AdventureSuppressorBlock.ENABLED, powered));
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    private long lastAnimTick = 0;

    @Override
    public double getTick(Object o) {
        // level 为 null 时返回缓存值，避免动画时间跳回 0 导致反复重播
        if (level != null) {
            lastAnimTick = level.getGameTime();
        }
        return lastAnimTick;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("TimerTicks", timerTicks);
        tag.putInt("SuppressCooldown", suppressCooldown);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        timerTicks = tag.getInt("TimerTicks");
        suppressCooldown = tag.getInt("SuppressCooldown");
    }
}
