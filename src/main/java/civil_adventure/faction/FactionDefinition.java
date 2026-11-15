package civil_adventure.faction;

import civil_adventure.data.EntityConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.*;

/**
 * 派系完整规格定义。
 * 遵循 2.0-FINAL 架构规范：
 * - 紧凑 byte 索引
 * - 独立危险方块列表与权重
 * - 关联冒险区结构 (Structures) 与初始基础分 (默认 500.0)
 * - 是 / 非 (is / not) Tags
 * - 实体白名单 (tags + entity types)
 * - 优势增益属性与药水效果
 */
public final class FactionDefinition {

    public static final ResourceLocation CIVILIZATION_ID = new ResourceLocation("civil", "civilization");
    public static final ResourceLocation ADVENTURE_ID = new ResourceLocation("civil_adventure", "adventure");

    private final ResourceLocation id;
    private final byte index;
    private final String nameTranslationKey;
    private final int colorRgb;
    private final double minDominanceScore;
    private final String isTag;
    private final String notTag;
    private final Set<TagKey<EntityType<?>>> entityWhitelistTags;
    private final Set<EntityType<?>> entityWhitelistTypes;
    private final Map<Block, Double> blockWeights;
    private final List<TagKey<Block>> blockTags;
    private final Set<ResourceLocation> structureIds;
    private final Set<TagKey<Structure>> structureTags;
    private final double structureBaseScore;
    private final List<EntityConfig.AttrMod> attributeModifiers;
    private final List<EntityConfig.EffectDef> potionEffects;

    public FactionDefinition(
            ResourceLocation id,
            byte index,
            String nameTranslationKey,
            int colorRgb,
            double minDominanceScore,
            String isTag,
            String notTag,
            Set<TagKey<EntityType<?>>> entityWhitelistTags,
            Set<EntityType<?>> entityWhitelistTypes,
            Map<Block, Double> blockWeights,
            List<TagKey<Block>> blockTags,
            Set<ResourceLocation> structureIds,
            Set<TagKey<Structure>> structureTags,
            double structureBaseScore,
            List<EntityConfig.AttrMod> attributeModifiers,
            List<EntityConfig.EffectDef> potionEffects
    ) {
        this.id = id;
        this.index = index;
        this.nameTranslationKey = nameTranslationKey;
        this.colorRgb = colorRgb;
        this.minDominanceScore = minDominanceScore;
        this.isTag = isTag;
        this.notTag = notTag;
        this.entityWhitelistTags = entityWhitelistTags != null ? Set.copyOf(entityWhitelistTags) : Set.of();
        this.entityWhitelistTypes = entityWhitelistTypes != null ? Set.copyOf(entityWhitelistTypes) : Set.of();
        this.blockWeights = blockWeights != null ? new IdentityHashMap<>(blockWeights) : Map.of();
        this.blockTags = blockTags != null ? List.copyOf(blockTags) : List.of();
        this.structureIds = structureIds != null ? Set.copyOf(structureIds) : Set.of();
        this.structureTags = structureTags != null ? Set.copyOf(structureTags) : Set.of();
        this.structureBaseScore = structureBaseScore > 0.0 ? structureBaseScore : 500.0;
        this.attributeModifiers = attributeModifiers != null ? List.copyOf(attributeModifiers) : List.of();
        this.potionEffects = potionEffects != null ? List.copyOf(potionEffects) : List.of();
    }

    public ResourceLocation id() { return id; }
    public byte index() { return index; }
    public String nameTranslationKey() { return nameTranslationKey; }
    public int colorRgb() { return colorRgb; }
    public double minDominanceScore() { return minDominanceScore; }
    public String isTag() { return isTag; }
    public String notTag() { return notTag; }
    public List<EntityConfig.AttrMod> attributeModifiers() { return attributeModifiers; }
    public List<EntityConfig.EffectDef> potionEffects() { return potionEffects; }
    public Set<ResourceLocation> structureIds() { return structureIds; }
    public Set<TagKey<Structure>> structureTags() { return structureTags; }
    public double structureBaseScore() { return structureBaseScore; }

    public boolean hasStructures() {
        return !structureIds.isEmpty() || !structureTags.isEmpty();
    }

    public boolean matchesStructure(ResourceLocation id, Holder<Structure> holder) {
        if (id != null && structureIds.contains(id)) return true;
        if (holder != null && !structureTags.isEmpty()) {
            for (TagKey<Structure> tag : structureTags) {
                if (holder.is(tag)) return true;
            }
        }
        return false;
    }

    public boolean isCivilization() {
        return id.equals(CIVILIZATION_ID);
    }

    public boolean isAdventure() {
        return id.equals(ADVENTURE_ID);
    }

    /** 检查实体是否属于本派系的白名单生物 */
    public boolean isWhitelistedEntity(LivingEntity entity) {
        if (entity == null) return false;
        EntityType<?> type = entity.getType();

        if (entityWhitelistTypes.contains(type)) {
            return true;
        }

        for (TagKey<EntityType<?>> tag : entityWhitelistTags) {
            if (type.is(tag)) {
                return true;
            }
        }

        // 文明派系默认 fallback: 玩家、村民、铁傀儡、以及非怪物
        if (isCivilization()) {
            if (entity instanceof net.minecraft.world.entity.player.Player ||
                entity instanceof net.minecraft.world.entity.npc.Villager ||
                entity instanceof net.minecraft.world.entity.animal.IronGolem) {
                return true;
            }
            return !(entity instanceof net.minecraft.world.entity.monster.Monster);
        }

        // 默认灾厄派系 fallback: 仅限灾厄村民(掠夺者/卫道士/唤魔者/幻术师)、劫掠兽、恼鬼、女巫
        if (isAdventure()) {
            return entity instanceof net.minecraft.world.entity.monster.AbstractIllager ||
                   entity instanceof net.minecraft.world.entity.monster.Ravager ||
                   entity instanceof net.minecraft.world.entity.monster.Vex ||
                   entity instanceof net.minecraft.world.entity.monster.Witch;
        }

        return false;
    }

    /** 查询指定方块在本派系中的危险分权重，0.0 表示无分 */
    public double getBlockWeight(Block block) {
        if (block == null) return 0.0;
        Double w = blockWeights.get(block);
        if (w != null) return w;

        for (TagKey<Block> tag : blockTags) {
            if (BuiltInRegistries.BLOCK.wrapAsHolder(block).is(tag)) {
                return 1.0; // 标签默认权重 1.0
            }
        }
        return 0.0;
    }

    public boolean hasBlockWeight(Block block) {
        return getBlockWeight(block) > 0.0;
    }

    public boolean hasAnyBlocks() {
        return !blockWeights.isEmpty() || !blockTags.isEmpty();
    }
}
