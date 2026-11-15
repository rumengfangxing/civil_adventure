package civil_adventure.entity;

import civil_adventure.CivilAdventureMod;
import civil_adventure.cluster.MacroCluster;
import civil_adventure.data.AdventureEntityLoader;
import civil_adventure.data.EntityConfig;
import civil_adventure.faction.FactionDefinition;
import civil_adventure.faction.event.EntityFactionTagEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraftforge.common.MinecraftForge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生物【是 / 非】Tag 注入与延时防抖租约管理器。
 * 遵循 2.0-FINAL 架构规范：
 * 1. 领地内：派系白名单生物打上 [是] Tag，非白名单生物打上 [非] Tag（含文明派系）。
 * 2. 动态移除施加延时防抖（Debounce Hysteresis），防止生物在领地边缘走动时造成高频增删 Tag 闪烁。
 * 3. 租约到期后安全剥离 Tag 与暂态属性加成，杜绝脏数据。
 * 4. 10 tick 步进节流，常驻生物药水效果自动续期，血量上限修饰符等比例抬升当前生命值。
 */
public class EntityFactionTagManager {

    /** 离开领地后的防抖缓冲延迟（tick），80 tick = 4 秒 */
    public static final int DEBOUNCE_GRACE_TICKS = 80;

    /** 租约记录 */
    private static final class Lease {
        final LivingEntity entity;
        FactionDefinition faction;
        String attachedTag;
        boolean isWhitelisted;
        long lastActiveTick;
        final Set<String> appliedAttrIds = new HashSet<>();

        Lease(LivingEntity entity, FactionDefinition faction, String tag, boolean isWhitelisted, long tick) {
            this.entity = entity;
            this.faction = faction;
            this.attachedTag = tag;
            this.isWhitelisted = isWhitelisted;
            this.lastActiveTick = tick;
        }

        void touch(long tick) {
            this.lastActiveTick = tick;
        }

        boolean isExpired(long currentTick) {
            return currentTick - lastActiveTick >= DEBOUNCE_GRACE_TICKS;
        }
    }

    private final Map<UUID, Lease> activeLeases = new ConcurrentHashMap<>();

    public void tick(ServerLevel level) {
        if (level.getGameTime() % 10 != 0) return;
        long currentTick = level.getGameTime();

        // 1. 扫描当前维度所有存活生物，更新或新建领地 Tag
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!living.isAlive()) continue;

            BlockPos pos = living.blockPosition();
            MacroCluster cluster = CivilAdventureMod.getScoreService().getClusterAt(level, pos);

            if (cluster != null && cluster.hasDominance()) {
                FactionDefinition dominant = cluster.getDominantFaction();
                if (dominant != null) {
                    processEntityInDominantCluster(living, dominant, currentTick);
                }
            }
        }

        // 2. 检查离开领地的租约，执行防抖延时过期剥离
        cleanupExpiredLeases(level, currentTick);
    }

    private void processEntityInDominantCluster(LivingEntity entity, FactionDefinition dominant, long currentTick) {
        UUID uuid = entity.getUUID();
        boolean whitelisted = dominant.isWhitelistedEntity(entity);
        String targetTag = whitelisted ? dominant.isTag() : dominant.notTag();

        Lease lease = activeLeases.get(uuid);

        if (lease == null) {
            // 新进入领地：直接附加 Tag 并记录租约
            entity.addTag(targetTag);
            Lease newLease = new Lease(entity, dominant, targetTag, whitelisted, currentTick);

            if (whitelisted) {
                applyDominantBuffs(entity, dominant, newLease);
            }

            activeLeases.put(uuid, newLease);
            CivilAdventureMod.LOGGER.debug("[civil_adventure][Step 6/EntityTag] 实体 {} 进入 [{}] 领地，施加地缘标签: [{}] (白名单身份: {})",
                    entity.getName().getString(), dominant.id(), targetTag, whitelisted);
            MinecraftForge.EVENT_BUS.post(new EntityFactionTagEvent.Attached(entity, dominant, List.of(targetTag)));

        } else {
            // 已有租约：检查是否换了统治派系或身份
            if (!lease.attachedTag.equals(targetTag)) {
                // 派系变更或身份变更：先剥离旧 Tag
                entity.removeTag(lease.attachedTag);
                removeDominantBuffs(entity, lease);
                MinecraftForge.EVENT_BUS.post(new EntityFactionTagEvent.Removed(entity, lease.faction, List.of(lease.attachedTag)));

                // 赋予新 Tag
                entity.addTag(targetTag);
                lease.faction = dominant;
                lease.attachedTag = targetTag;
                lease.isWhitelisted = whitelisted;

                if (whitelisted) {
                    applyDominantBuffs(entity, dominant, lease);
                }
                CivilAdventureMod.LOGGER.debug("[civil_adventure][Step 6/EntityTag] 实体 {} 身份变更，切换地缘标签为: [{}] (派系: {}, 白名单: {})",
                        entity.getName().getString(), targetTag, dominant.id(), whitelisted);
                MinecraftForge.EVENT_BUS.post(new EntityFactionTagEvent.Attached(entity, dominant, List.of(targetTag)));
            } else if (whitelisted && (currentTick % 60 == 0)) {
                // 定期续期药水效果，防止长驻生物药水效果超时断档
                applyDominantEffectsOnly(entity, dominant);
            }

            // 续期租约（防抖重置）
            lease.touch(currentTick);
        }
    }

    private void cleanupExpiredLeases(ServerLevel level, long currentTick) {
        if (activeLeases.isEmpty()) return;

        Iterator<Map.Entry<UUID, Lease>> it = activeLeases.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Lease> entry = it.next();
            Lease lease = entry.getValue();

            // 若本 tick 刚刚被领地扫描 touch 过，无需重复查询聚类
            if (lease.lastActiveTick == currentTick) continue;

            // 1. 若实体已死亡或已从世界中彻底移除，立即清理
            if (!lease.entity.isAlive() || lease.entity.isRemoved()) {
                lease.entity.removeTag(lease.attachedTag);
                removeDominantBuffs(lease.entity, lease);
                it.remove();
                continue;
            }

            // 2. 关键防线：若实体当前不在本维度，跳过！（交由其所在维度的 tick 处理，严禁跨维度误杀）
            if (lease.entity.level() != level) {
                continue;
            }

            // 检查当前位置是否仍处于原领地
            BlockPos pos = lease.entity.blockPosition();
            MacroCluster cluster = CivilAdventureMod.getScoreService().getClusterAt(level, pos);
            boolean stillInZone = cluster != null && cluster.hasDominance() &&
                    cluster.getDominantFaction() != null &&
                    cluster.getDominantFaction().id().equals(lease.faction.id());

            if (stillInZone) {
                lease.touch(currentTick);
                continue;
            }

            // 已离开领地：检查防抖缓冲期是否到期
            if (lease.isExpired(currentTick)) {
                lease.entity.removeTag(lease.attachedTag);
                removeDominantBuffs(lease.entity, lease);
                it.remove();
                CivilAdventureMod.LOGGER.debug("[civil_adventure][Step 6/EntityTag] 实体 {} 离开领地防抖超时，剥离地缘标签: [{}]",
                        lease.entity.getName().getString(), lease.attachedTag);
                MinecraftForge.EVENT_BUS.post(new EntityFactionTagEvent.Removed(lease.entity, lease.faction, List.of(lease.attachedTag)));
            }
        }
    }

    private void applyDominantBuffs(LivingEntity entity, FactionDefinition faction, Lease lease) {
        String ns = "civil_adventure:buff:" + entity.getStringUUID();
        ResourceLocation entityKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String entityId = entityKey != null ? entityKey.toString() : "";

        // 优先检查是否有针对该实体的专属配置 (兼容精细化实体配置，所有白名单生物均可按需配置)
        EntityConfig customConfig = AdventureEntityLoader.getConfig(entityId);
        List<EntityConfig.AttrMod> attrs = (customConfig != null && !customConfig.attributes().isEmpty())
                ? customConfig.attributes()
                : faction.attributeModifiers();

        List<EntityConfig.EffectDef> effects = (customConfig != null && !customConfig.effects().isEmpty())
                ? customConfig.effects()
                : faction.potionEffects();

        float prevMax = entity.getMaxHealth();
        float prevHealth = entity.getHealth();
        boolean wasFullHealth = prevHealth >= prevMax - 0.01f;

        for (EntityConfig.AttrMod attrDef : attrs) {
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
                            modId, "civil_adventure_dominant_buff", attrDef.modifier(), attrDef.operation()));
                }
                lease.appliedAttrIds.add(attrDef.attributeId());
            } catch (Exception ignored) {}
        }

        // 同步抬升最大生命值带来的当前生命值，避免满血怪物变残血
        float newMax = entity.getMaxHealth();
        if (newMax > prevMax) {
            entity.setHealth(wasFullHealth ? newMax : Math.min(newMax, prevHealth * (newMax / prevMax)));
        }

        for (EntityConfig.EffectDef effDef : effects) {
            try {
                ResourceLocation effId = new ResourceLocation(effDef.effectId());
                MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(effId);
                if (effect == null) continue;
                entity.addEffect(new MobEffectInstance(effect, effDef.duration(), effDef.amplifier(), false, false));
            } catch (Exception ignored) {}
        }
    }

    private void applyDominantEffectsOnly(LivingEntity entity, FactionDefinition faction) {
        ResourceLocation entityKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String entityId = entityKey != null ? entityKey.toString() : "";
        EntityConfig customConfig = AdventureEntityLoader.getConfig(entityId);
        List<EntityConfig.EffectDef> effects = (customConfig != null && !customConfig.effects().isEmpty())
                ? customConfig.effects()
                : faction.potionEffects();

        for (EntityConfig.EffectDef effDef : effects) {
            try {
                ResourceLocation effId = new ResourceLocation(effDef.effectId());
                MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(effId);
                if (effect == null) continue;
                entity.addEffect(new MobEffectInstance(effect, effDef.duration(), effDef.amplifier(), false, false));
            } catch (Exception ignored) {}
        }
    }

    private void removeDominantBuffs(LivingEntity entity, Lease lease) {
        String ns = "civil_adventure:buff:" + entity.getStringUUID();
        for (String attrId : lease.appliedAttrIds) {
            try {
                Attribute attribute = BuiltInRegistries.ATTRIBUTE.get(new ResourceLocation(attrId));
                if (attribute == null) continue;
                AttributeInstance inst = entity.getAttribute(attribute);
                if (inst == null) continue;
                UUID modId = UUID.nameUUIDFromBytes((ns + ":" + attrId).getBytes());
                inst.removeModifier(modId);
            } catch (Exception ignored) {}
        }
        lease.appliedAttrIds.clear();

        // 钳制超出最大生命值的残余血量
        if (entity.getHealth() > entity.getMaxHealth()) {
            entity.setHealth(entity.getMaxHealth());
        }
    }

    public Set<UUID> getBuffedEntityUUIDs() {
        Set<UUID> set = new HashSet<>();
        for (Map.Entry<UUID, Lease> e : activeLeases.entrySet()) {
            if (e.getValue().isWhitelisted) {
                set.add(e.getKey());
            }
        }
        return set;
    }

    public void onEntityRemoved(UUID uuid) {
        Lease removed = activeLeases.remove(uuid);
        if (removed != null && removed.entity.isAlive()) {
            removed.entity.removeTag(removed.attachedTag);
            removeDominantBuffs(removed.entity, removed);
        }
    }

    public void clear() {
        for (Lease lease : activeLeases.values()) {
            if (lease.entity.isAlive()) {
                lease.entity.removeTag(lease.attachedTag);
                removeDominantBuffs(lease.entity, lease);
            }
        }
        activeLeases.clear();
    }
}
