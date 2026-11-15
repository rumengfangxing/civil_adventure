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
 * - 停用→启用 → start 过渡后保持 overload/running
 * - 启用→停用 → close 过渡后保持 idle
 */
public class AdventureSuppressorBlockEntity extends BlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache cache =
        new software.bernie.geckolib.core.animatable.instance.InstancedAnimatableInstanceCache(this);

    /** 剩余生效 tick（0 = 无时间） */
    private int timerTicks = 0;
    /** 距离下次压制动作的 tick */
    private int suppressCooldown = 0;
    /** 累积脉冲计数：驱动由内向外的同心圆波前扩散（阵地持久战 1~2 分钟） */
    private int pulseCount = 0;
    /** 本方块贡献的压制量（压缩区块坐标 long → 派系压制数组），用于释放回弹 */
    private final Map<Long, float[]> localSuppression = new HashMap<>();
    /** 客户端同步：本方块当前是否在压制冒险区分数（localSuppression 非空） */
    private boolean suppressing = false;
    /** 区域威胁是否已被完全压制（无剩余未压制危险分，驱动 RUNNING 动画与比较器 15 信号） */
    private boolean fullySuppressed = false;
    /** 状态防抖计数器：防止边界威胁轻微波动导致 OVERLOAD 与 RUNNING 频繁瞬切抽搐 */
    private int statusStabilityCounter = 0;

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

        // [修复 V3-C02] 停用 → 仅在尚有未释放状态时执行一次释放，杜绝待机态每秒 20 次空转调用
        if (!enabled) {
            if (!localSuppression.isEmpty() || pulseCount > 0 || suppressing || fullySuppressed) {
                releaseSuppression();
            }
            return;
        }
        if (timerTicks <= 0) {
            BlockState state = getBlockState();
            if (state.hasProperty(AdventureSuppressorBlock.ENABLED) && state.getValue(AdventureSuppressorBlock.ENABLED)) {
                level.setBlockAndUpdate(worldPosition, state.setValue(AdventureSuppressorBlock.ENABLED, false));
            }
            releaseSuppression();
            setChanged();
            return;
        }

        // [修复 V3-C02] 存档重载后首帧同步恢复安全泡注册，若服务端重启导致压制账目已空则同步归零 pulseCount
        if (level instanceof ServerLevel serverLevel) {
            AdventureScoreService svc = CivilAdventureMod.getScoreService();
            if (svc != null && pulseCount > 0 && localSuppression.isEmpty()
                    && !svc.hasActiveSuppressionNear(serverLevel.dimension().location(), worldPosition)) {
                pulseCount = 0;
            }
            if (svc != null && pulseCount > 0) {
                svc.registerActiveSuppressor(serverLevel.dimension().location(), worldPosition, getWaveRadius());
            }
        }

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

    public float getWaveRadius() {
        int maxRadius = AdventureConfig.SUPPRESS_RADIUS.get();
        // 阵地持久战波前扩散节奏：每 4 次脉冲（约 20 秒）推开 1 个区块半径，约 70~90 秒达满级半径
        return Math.min((float) maxRadius, 0.5f + (pulseCount / 4.0f));
    }

    /** 累积压制：全额并发削减一切非文明、非白名单派系（突击前哨站波前模型） */
    private void applySuppression() {
        if (!(level instanceof ServerLevel serverLevel)) return;

        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        // [修复 V3-C01] 若该位置有正在倒灌的 DecayWave，中断倒灌并将其剩余压制账单与波前进度无缝接管回本实体！
        AdventureScoreService.DecayWave cancelledWave = svc.cancelGracefulDecay(serverLevel, worldPosition);
        if (cancelledWave != null) {
            cancelledWave.remainingSuppression.forEach((key, arr) -> {
                localSuppression.merge(key, arr, (oldArr, newArr) -> {
                    float[] merged = new float[Math.max(oldArr.length, newArr.length)];
                    for (int i = 0; i < merged.length; i++) {
                        merged[i] = (i < oldArr.length ? oldArr[i] : 0.0f) + (i < newArr.length ? newArr[i] : 0.0f);
                    }
                    return merged;
                });
            });
            int recoveredPulses = Math.max(0, (int) Math.round((cancelledWave.currentRadius - 0.5f) * 4.0f));
            this.pulseCount = Math.max(this.pulseCount, recoveredPulses);
        }

        pulseCount++;
        float waveRadius = getWaveRadius();
        int maxRadius = AdventureConfig.SUPPRESS_RADIUS.get();
        double percent = AdventureConfig.SUPPRESS_PERCENT.get();
        double fixed = AdventureConfig.SUPPRESS_FIXED.get();

        svc.registerActiveSuppressor(serverLevel.dimension().location(), worldPosition, waveRadius);

        Map<Long, float[]> added = svc.suppressRegionConcurrently(serverLevel, worldPosition, maxRadius, waveRadius, percent, fixed);
        added.forEach((key, arr) -> {
            localSuppression.merge(key, arr, (oldArr, newArr) -> {
                float[] merged = new float[Math.max(oldArr.length, newArr.length)];
                for (int i = 0; i < merged.length; i++) {
                    merged[i] = (i < oldArr.length ? oldArr[i] : 0.0f) + (i < newArr.length ? newArr[i] : 0.0f);
                }
                return merged;
            });
        });

        // 压制脉冲后，动态判定半径内是否已肃清全部威胁（含纯结构与方块分）
        // 只有波前完全推至最大半径且全域已无残留威胁，才判定为全域肃清 (RUNNING + 比较器 15 信号)
        boolean hasRemainingThreat = svc.hasRemainingThreatInRadius(serverLevel, worldPosition, maxRadius);
        boolean desiredFullySuppressed = (waveRadius >= maxRadius) && !hasRemainingThreat;
        if (desiredFullySuppressed != fullySuppressed) {
            // 需要连续 2 次脉冲确认才切换状态，防止临界线轻微波动导致动画剧烈重置
            if (++statusStabilityCounter >= 2) {
                statusStabilityCounter = 0;
                updateSuppressionStatus(desiredFullySuppressed);
            }
        } else {
            statusStabilityCounter = 0;
        }

        if (!added.isEmpty()) {
            CivilAdventureMod.LOGGER.debug("[civil_adventure][Step 7/Suppression] 压制核心 ({}, {}, {}) 执行脉冲 #{} (波前半径 {}/{})，累计压制影响区块: {}",
                    worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                    pulseCount, String.format("%.2f", waveRadius), maxRadius, localSuppression.size());
        }
    }

    /** 释放本方块贡献的所有压制（优雅倒灌回弹） */
    public void releaseSuppression() {
        if (level == null || level.isClientSide) return;
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc != null && level instanceof ServerLevel serverLevel) {
            svc.unregisterActiveSuppressor(serverLevel.dimension().location(), worldPosition);
            if (!localSuppression.isEmpty()) {
                CivilAdventureMod.LOGGER.info("[civil_adventure][Step 7/Suppression] 压制核心 ({}, {}, {}) 释放压制波，启动平滑倒灌回弹 ({} 个区块)",
                        worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), localSuppression.size());
                svc.startGracefulDecay(serverLevel, worldPosition, localSuppression, getWaveRadius());
            }
        }
        // 无论如何清除本地待发状态
        localSuppression.clear();
        pulseCount = 0;
        updateSuppressionStatus(false);
    }

    /** 压制状态变化 → 保存 + 推送客户端（动画 running/overload 判定依据）与红石更新 */
    private void updateSuppressionStatus(boolean newFullySuppressed) {
        boolean was = this.fullySuppressed;
        this.fullySuppressed = newFullySuppressed;
        this.suppressing = newFullySuppressed || !localSuppression.isEmpty();
        if (was == newFullySuppressed) return;
        setChanged();
        if (level != null) {
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
            if (level instanceof ServerLevel serverLevel) {
                net.minecraftforge.network.PacketDistributor.TRACKING_CHUNK
                    .with(() -> serverLevel.getChunkAt(worldPosition))
                    .send(net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this));
            }
        }
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

    /**
     * 区域内冒险分是否已被完全压制。
     * 服务端/客户端统一以 fullySuppressed 为准。
     */
    public boolean isZoneFullySuppressed() {
        return fullySuppressed;
    }

    // ── GeckoLib 动画 ──────────────────────────────────

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // transition 6 ticks (0.3s)：柔性插值过渡，消除状态切换时的瞬切与抽搐
        controllers.add(new AnimationController<>(this, "controller", 6, this::animPredicate));
    }

    private PlayState animPredicate(AnimationState<AdventureSuppressorBlockEntity> state) {
        boolean enabled = getBlockState().hasProperty(AdventureSuppressorBlock.ENABLED)
            && getBlockState().getValue(AdventureSuppressorBlock.ENABLED);
        boolean running = isZoneFullySuppressed();

        AnimationController<AdventureSuppressorBlockEntity> controller = state.getController();

        // 计算目标状态
        AnimTarget target = !enabled ? AnimTarget.IDLE
            : (running ? AnimTarget.RUNNING : AnimTarget.OVERLOAD);

        // 目标未变化时不要重新设置动画，否则每次谓词调用都会把时间轴跳回起点导致抽搐；
        // 仅当动画意外停止时直接回到目标循环（不做过渡）
        if (target == animTarget) {
            if (controller.getAnimationState() != AnimationController.State.STOPPED) return PlayState.CONTINUE;
            controller.setAnimation(loopAnimation(target));
            return PlayState.CONTINUE;
        }

        AnimTarget from = animTarget;
        animTarget = target;
        CivilAdventureMod.LOGGER.debug("[anim] {} → {} (enabled={} running={} localSupp={})",
            from, target, enabled, running, localSuppression.size());

        if (from == null) {
            // 首次：直接进入目标循环
            controller.setAnimation(loopAnimation(target));
        } else if (from == AnimTarget.IDLE) {
            // (1)停用→(2)启用：start 播放一次，随后进入目标循环
            controller.setAnimation(RawAnimation.begin()
                .thenPlay("start")
                .thenLoop(animationName(target)));
        } else if (target == AnimTarget.IDLE) {
            // (2)启用→(1)停用：close 播放一次，随后回到 idle 循环
            controller.setAnimation(RawAnimation.begin()
                .thenPlay("close")
                .thenLoop("idle"));
        } else {
            // overload ↔ running 都是启用状态：直接切换循环，不播放开关过渡
            controller.setAnimation(loopAnimation(target));
        }
        return PlayState.CONTINUE;
    }

    private static RawAnimation loopAnimation(AnimTarget target) {
        return RawAnimation.begin().thenLoop(animationName(target));
    }

    private static String animationName(AnimTarget target) {
        return target == AnimTarget.IDLE ? "idle"
            : (target == AnimTarget.RUNNING ? "running" : "overload");
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

    // 注意：不覆写 getTick —— 默认 RenderUtils.getCurrentTick() = Blaze3D.getTime() * 20d，
    // 渲染帧连续递增（带小数），动画平滑播放。
    // 之前覆写为 level.getGameTime()（整数 tick）导致同 tick 内渲染帧被判为 isReRender
    // 而跳过动画更新，动画以 20Hz 跳变 → 闪烁抽搐。

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("TimerTicks", timerTicks);
        tag.putInt("SuppressCooldown", suppressCooldown);
        tag.putInt("PulseCount", pulseCount);
        tag.putBoolean("Suppressing", suppressing);
        tag.putBoolean("FullySuppressed", fullySuppressed);
        tag.putBoolean("LastPowered", lastPowered);
        tag.putBoolean("PoweredInitialized", poweredInitialized);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        timerTicks = Math.max(0, tag.getInt("TimerTicks"));
        suppressCooldown = Math.max(0, tag.getInt("SuppressCooldown"));
        pulseCount = Math.max(0, tag.getInt("PulseCount"));
        suppressing = tag.getBoolean("Suppressing");
        fullySuppressed = tag.contains("FullySuppressed") ? tag.getBoolean("FullySuppressed") : suppressing;
        lastPowered = tag.getBoolean("LastPowered");
        poweredInitialized = tag.getBoolean("PoweredInitialized");

        // 压制量属于运行时服务数据，不能从方块实体存档恢复，避免重复扣分
        localSuppression.clear();
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level instanceof ServerLevel serverLevel) {
            AdventureScoreService svc = CivilAdventureMod.getScoreService();
            if (svc != null) {
                svc.unregisterActiveSuppressor(serverLevel.dimension().location(), worldPosition);
            }
        }
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket pkt) {
        if (pkt.getTag() != null) {
            load(pkt.getTag());
        }
    }
}
