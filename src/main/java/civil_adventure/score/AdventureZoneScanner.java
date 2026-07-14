package civil_adventure.score;

import civil_adventure.CivilAdventureMod;
import civil_adventure.config.AdventureConfig;
import civil_adventure.data.AdventureEntityLoader;
import civil_adventure.data.EntityConfig;
import civil.civilization.CivilRegionClassifier;
import civil.civilization.CivilRegionKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时扫描冒险区内的实体，应用/回收属性加成和药水效果。
 *
 * 内存管理仿文礼 TtlVoxelCache 模式：
 * - TimestampedEntry 包裹每个条目，自带 tick 时间戳
 * - 每次扫描先 cleanupExpired() 清除过期条目
 * - 活跃实体主动 touch() 续期
 */
public class AdventureZoneScanner {
    // 条目 → 最后续期 tick
    private static final class Entry {
        long lastTouchTick;
        Entry(long tick) { this.lastTouchTick = tick; }
        void touch(long tick) { this.lastTouchTick = tick; }
        boolean isExpired(long now, long ttl) { return now - lastTouchTick > ttl; }
    }

    // 仿文礼 TtlVoxelCache: ConcurrentHashMap + 时间戳条目
    private final ConcurrentHashMap<UUID, Entry> buffedEntities = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> appliedAttrs = new ConcurrentHashMap<>();

    private long nextScanTick = 0;

    public void tick(ServerLevel level) {
        long currentTick = level.getGameTime();
        if (currentTick < nextScanTick) return;
        nextScanTick = currentTick + AdventureConfig.SCAN_INTERVAL.get();

        int ttl = AdventureConfig.BUFF_TIMEOUT.get();
        AdventureScoreService svc = CivilAdventureMod.getScoreService();
        if (svc == null) return;

        // 1. 仿文礼 cleanupExpired(): 移除过期条目并回收属性
        cleanupExpired(level, currentTick, ttl);

        // 2. 扫描冒险区内的实体，活跃实体 touch() 续期
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!living.isAlive()) continue;
            if (living instanceof ServerPlayer) continue;

            BlockPos pos = living.blockPosition();
            double threshold = AdventureConfig.ZONE_THRESHOLD.get();
            boolean inOurZone = svc.getNormalizedScoreAt(level, pos) >= threshold;
            boolean inCivilZone = isCivilZone(level, pos);
            if (!inOurZone && !inCivilZone) continue;

            ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType());
            if (entityId == null) continue;
            EntityConfig config = AdventureEntityLoader.getConfig(entityId.toString());
            if (config == null) continue;

            applyBuff(living, config);
            // touch() 续期 —— 仿文礼 touchL1()
            buffedEntities.computeIfAbsent(living.getUUID(), k -> new Entry(currentTick))
                .touch(currentTick);
        }
    }

    // ── 仿文礼 cleanupExpired() ──────────────────────────

    private void cleanupExpired(ServerLevel level, long now, int ttl) {
        if (buffedEntities.isEmpty()) return;

        Iterable<ServerLevel> levels = level.getServer().getAllLevels();

        for (Map.Entry<UUID, Entry> e : new HashSet<>(buffedEntities.entrySet())) {
            if (!e.getValue().isExpired(now, ttl)) continue;

            // 过期 → 回收属性 + 清除追踪
            removeBuffCrossDimension(levels, e.getKey());
            buffedEntities.remove(e.getKey());
            // appliedAttrs 已在 removeBuffCrossDimension 中移除
        }
    }

    // ── 属性/效果应用 ──────────────────────────────────

    private void applyBuff(LivingEntity entity, EntityConfig config) {
        String ns = "civil_adventure:" + entity.getStringUUID();

        for (EntityConfig.AttrMod attrDef : config.attributes()) {
            try {
                ResourceLocation attrId = new ResourceLocation(attrDef.attributeId());
                Attribute attribute = BuiltInRegistries.ATTRIBUTE.get(attrId);
                if (attribute == null) continue;

                AttributeInstance inst = entity.getAttribute(attribute);
                if (inst == null) continue;

                UUID modId = UUID.nameUUIDFromBytes((ns + ":" + attrDef.attributeId()).getBytes());
                inst.removeModifier(modId);
                if (attrDef.modifier() != 0.0) {
                    inst.addTransientModifier(new AttributeModifier(
                        modId, "civil_adventure_buff", attrDef.modifier(), attrDef.operation()));
                }

                appliedAttrs.computeIfAbsent(entity.getUUID(), k -> ConcurrentHashMap.newKeySet())
                    .add(attrDef.attributeId());
            } catch (Exception e) {
                CivilAdventureMod.LOGGER.warn("应用属性失败 {}: {}", attrDef.attributeId(), e.getMessage());
            }
        }

        for (EntityConfig.EffectDef effDef : config.effects()) {
            try {
                ResourceLocation effId = new ResourceLocation(effDef.effectId());
                MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(effId);
                if (effect == null) continue;
                entity.addEffect(new MobEffectInstance(effect, effDef.duration(), effDef.amplifier(), false, false));
            } catch (Exception e) {
                CivilAdventureMod.LOGGER.warn("应用效果失败 {}: {}", effDef.effectId(), e.getMessage());
            }
        }
    }

    // ── 属性回收 ──────────────────────────────────────

    private void removeBuffCrossDimension(Iterable<ServerLevel> levels, UUID entityUUID) {
        Set<String> attrIds = appliedAttrs.remove(entityUUID);
        if (attrIds == null || attrIds.isEmpty()) return;

        for (ServerLevel lvl : levels) {
            Entity entity = lvl.getEntity(entityUUID);
            if (entity instanceof LivingEntity living) {
                removeModifiers(living, attrIds);
                return;
            }
        }
    }

    private static void removeModifiers(LivingEntity living, Set<String> attrIds) {
        String ns = "civil_adventure:" + living.getStringUUID();
        for (String attrId : attrIds) {
            try {
                Attribute attribute = BuiltInRegistries.ATTRIBUTE.get(new ResourceLocation(attrId));
                if (attribute == null) continue;
                AttributeInstance inst = living.getAttribute(attribute);
                if (inst == null) continue;
                UUID modId = UUID.nameUUIDFromBytes((ns + ":" + attrId).getBytes());
                inst.removeModifier(modId);
            } catch (Exception e) {
                CivilAdventureMod.LOGGER.warn("回收属性失败 {}: {}", attrId, e.getMessage());
            }
        }
    }

    // ── 文礼区域兼容 ──────────────────────────────────

    private static boolean isCivilZone(ServerLevel level, BlockPos pos) {
        try {
            var vc = civil.civilization.VoxelChunkKey.from(pos);
            var result = CivilRegionClassifier.classify(level, vc);
            var kind = result.kind();
            return kind == CivilRegionKind.ZONE || kind == CivilRegionKind.SHRINE;
        } catch (Exception e) {
            return false;
        }
    }

    // ── API ─────────────────────────────────────────────

    public Set<UUID> getBuffedEntityUUIDs() {
        return Collections.unmodifiableSet(buffedEntities.keySet());
    }

    /** 实体移除/死亡时清理追踪记录，防内存泄漏 */
    public void onEntityRemoved(UUID entityUUID) {
        buffedEntities.remove(entityUUID);
        appliedAttrs.remove(entityUUID);
    }
}
