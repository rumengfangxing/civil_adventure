package civil_adventure.faction;

import civil_adventure.CivilAdventureMod;
import civil_adventure.data.EntityConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 派系注册表。
 * 支持出厂两大基线：
 * - 0: civil:civilization (人类文明秩序)
 * - 1: civil_adventure:adventure (默认灾厄冒险)
 * 外部派系通过 data/<ns>/adventure_factions/*.json 动态注册。
 */
public final class FactionRegistry {

    private static final String DATA_PATH = "adventure_factions";

    public static final FactionDefinition DEFAULT_CIVILIZATION = new FactionDefinition(
            FactionDefinition.CIVILIZATION_ID,
            (byte) 0,
            "civil_adventure.faction.civilization.name",
            0x1976D2,
            1.0,
            "civil_adventure:is_civilized",
            "civil_adventure:not_civilized",
            Set.of(),
            Set.of(),
            Map.of(),
            List.of(),
            Set.of(),
            Set.of(),
            0.0,
            List.of(),
            List.of()
    );

    public static final FactionDefinition DEFAULT_ADVENTURE = new FactionDefinition(
            FactionDefinition.ADVENTURE_ID,
            (byte) 1,
            "civil_adventure.faction.adventure.name",
            0xF08252, // 文礼默认冒险区色 (#F08252)
            3.5,
            "civil_adventure:is_adventure",
            "civil_adventure:not_adventure",
            Set.of(),
            Set.of(),
            Map.of(), // 灾厄空列表占位
            List.of(),
            Set.of(
                new ResourceLocation("minecraft", "pillager_outpost"),
                new ResourceLocation("minecraft", "mansion")
            ),
            Set.of(),
            500.0,
            List.of(),
            List.of()
    );

    private static final List<FactionDefinition> INDEX_TO_FACTION = new ArrayList<>();
    private static final Map<ResourceLocation, FactionDefinition> ID_TO_FACTION = new ConcurrentHashMap<>();
    private static volatile boolean anyCustomBlocksRegistered = false;

    static {
        registerDefaultFactions();
    }

    private FactionRegistry() {}

    private static synchronized void registerDefaultFactions() {
        INDEX_TO_FACTION.clear();
        ID_TO_FACTION.clear();

        INDEX_TO_FACTION.add(DEFAULT_CIVILIZATION);
        ID_TO_FACTION.put(DEFAULT_CIVILIZATION.id(), DEFAULT_CIVILIZATION);

        INDEX_TO_FACTION.add(DEFAULT_ADVENTURE);
        ID_TO_FACTION.put(DEFAULT_ADVENTURE.id(), DEFAULT_ADVENTURE);

        anyCustomBlocksRegistered = false;
    }

    public static synchronized void reload(ResourceManager manager) {
        registerDefaultFactions();
        CivilAdventureMod.LOGGER.info("[civil_adventure][Step 1/FactionLoad] ========== 开始加载生态对抗派系配置 ==========");
        CivilAdventureMod.LOGGER.info("[civil_adventure][Step 1/FactionLoad] 预设派系 [Index 0] civil:civilization (人类文明秩序)");
        CivilAdventureMod.LOGGER.info("[civil_adventure][Step 1/FactionLoad] 预设派系 [Index 1] civil_adventure:adventure (灾厄冒险区) 关联结构: {}, 基础分: 500.0",
                DEFAULT_ADVENTURE.structureIds());

        if (manager == null) {
            CivilAdventureMod.LOGGER.warn("[civil_adventure][Step 1/FactionLoad] ResourceManager 为空，跳过数据包派系读取");
            return;
        }

        Map<ResourceLocation, Resource> resources = manager.listResources(DATA_PATH,
                id -> id.getPath().endsWith(".json"));

        List<Map.Entry<ResourceLocation, Resource>> sortedResources = new ArrayList<>(resources.entrySet());
        sortedResources.sort(Map.Entry.comparingByKey());

        byte nextIndex = 2;

        for (Map.Entry<ResourceLocation, Resource> entry : sortedResources) {
            ResourceLocation fileId = entry.getKey();
            try (InputStream is = entry.getValue().open();
                 InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {

                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

                String path = fileId.getPath();
                String factionName = path.substring(path.lastIndexOf('/') + 1).replace(".json", "");
                ResourceLocation factionId = new ResourceLocation(fileId.getNamespace(), factionName);

                if (factionId.equals(FactionDefinition.CIVILIZATION_ID) || factionId.equals(FactionDefinition.ADVENTURE_ID)) {
                    continue;
                }

                if (nextIndex >= 127) {
                    CivilAdventureMod.LOGGER.error("[civil_adventure][Step 1/FactionLoad] 已达最大派系数量上限 (127)，跳过加载 {}", factionId);
                    break;
                }

                String translationKey = root.has("name") ? root.get("name").getAsString() : "faction." + factionId.getNamespace() + "." + factionId.getPath();
                int color = 0xAA2222;
                if (root.has("color")) {
                    String colorHex = root.get("color").getAsString();
                    if (colorHex.startsWith("#")) colorHex = colorHex.substring(1);
                    color = (int) Long.parseLong(colorHex, 16);
                }

                double minScore = root.has("min_dominance_score") ? root.get("min_dominance_score").getAsDouble() : 5.0;

                // 是 / 非 Tags（兼容根节点与 tags 子对象两种写法）
                String isTag = root.has("is_tag") ? root.get("is_tag").getAsString() : "civil_adventure:is_" + factionId.getPath();
                String notTag = root.has("not_tag") ? root.get("not_tag").getAsString() : "civil_adventure:not_" + factionId.getPath();
                if (root.has("tags") && root.get("tags").isJsonObject()) {
                    JsonObject tagsObj = root.getAsJsonObject("tags");
                    if (tagsObj.has("is_tag")) isTag = tagsObj.get("is_tag").getAsString();
                    if (tagsObj.has("not_tag")) notTag = tagsObj.get("not_tag").getAsString();
                }

                // 实体白名单
                Set<TagKey<EntityType<?>>> entityTags = new HashSet<>();
                Set<EntityType<?>> entityTypes = new HashSet<>();
                if (root.has("entity_whitelist") && root.get("entity_whitelist").isJsonObject()) {
                    JsonObject ew = root.getAsJsonObject("entity_whitelist");
                    if (ew.has("tags") && ew.get("tags").isJsonArray()) {
                        for (JsonElement te : ew.getAsJsonArray("tags")) {
                            String tagStr = te.getAsString();
                            if (tagStr.startsWith("#")) tagStr = tagStr.substring(1);
                            entityTags.add(TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation(tagStr)));
                        }
                    }
                    if (ew.has("entities") && ew.get("entities").isJsonArray()) {
                        for (JsonElement ee : ew.getAsJsonArray("entities")) {
                            ResourceLocation eid = new ResourceLocation(ee.getAsString());
                            if (BuiltInRegistries.ENTITY_TYPE.containsKey(eid)) {
                                entityTypes.add(BuiltInRegistries.ENTITY_TYPE.get(eid));
                            }
                        }
                    }
                }

                // 独立危险方块列表与权重（兼容数组对象与键值对象两种写法）
                Map<Block, Double> blockWeights = new IdentityHashMap<>();
                List<TagKey<Block>> blockTags = new ArrayList<>();
                if (root.has("blocks")) {
                    if (root.get("blocks").isJsonArray()) {
                        for (JsonElement be : root.getAsJsonArray("blocks")) {
                            JsonObject bo = be.getAsJsonObject();
                            String bSpec = bo.get("block").getAsString();
                            double w = bo.has("weight") ? bo.get("weight").getAsDouble() : 1.0;
                            parseBlockSpec(bSpec, w, blockWeights, blockTags);
                        }
                    } else if (root.get("blocks").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> be : root.getAsJsonObject("blocks").entrySet()) {
                            String bSpec = be.getKey();
                            double w = be.getValue().getAsDouble();
                            parseBlockSpec(bSpec, w, blockWeights, blockTags);
                        }
                    }
                }

                // 优势增益属性与药水效果
                List<EntityConfig.AttrMod> attrs = new ArrayList<>();
                List<EntityConfig.EffectDef> effects = new ArrayList<>();

                if (root.has("dominant_buffs") && root.get("dominant_buffs").isJsonObject()) {
                    JsonObject buffs = root.getAsJsonObject("dominant_buffs");
                    if (buffs.has("attributes") && buffs.get("attributes").isJsonArray()) {
                        for (JsonElement ae : buffs.getAsJsonArray("attributes")) {
                            JsonObject ao = ae.getAsJsonObject();
                            String attrId = ao.get("attribute").getAsString();
                            double modifier = ao.has("amount")
                                    ? ao.get("amount").getAsDouble()
                                    : (ao.has("modifier") ? ao.get("modifier").getAsDouble() : 0.0);
                            int op = ao.has("operation") ? ao.get("operation").getAsInt() : 0;
                            AttributeModifier.Operation oper = switch (op) {
                                case 1 -> AttributeModifier.Operation.MULTIPLY_BASE;
                                case 2 -> AttributeModifier.Operation.MULTIPLY_TOTAL;
                                default -> AttributeModifier.Operation.ADDITION;
                            };
                            attrs.add(new EntityConfig.AttrMod(attrId, modifier, oper));
                        }
                    }

                    if (buffs.has("effects") && buffs.get("effects").isJsonArray()) {
                        for (JsonElement ee : buffs.getAsJsonArray("effects")) {
                            JsonObject eo = ee.getAsJsonObject();
                            String effId = eo.get("effect").getAsString();
                            int duration = eo.has("duration") ? eo.get("duration").getAsInt() : 100;
                            int amplifier = eo.has("amplifier") ? eo.get("amplifier").getAsInt() : 0;
                            effects.add(new EntityConfig.EffectDef(effId, duration, amplifier));
                        }
                    }
                }

                // 关联冒险区结构 (Structures) 与初始基础分
                Set<ResourceLocation> structureIds = new HashSet<>();
                Set<TagKey<Structure>> structureTags = new HashSet<>();
                if (root.has("structures") && root.get("structures").isJsonArray()) {
                    for (JsonElement se : root.getAsJsonArray("structures")) {
                        String s = se.getAsString().trim();
                        if (s.startsWith("#")) {
                            structureTags.add(TagKey.create(Registries.STRUCTURE, new ResourceLocation(s.substring(1))));
                        } else {
                            structureIds.add(new ResourceLocation(s));
                        }
                    }
                }
                double structureBaseScore = root.has("structure_base_score")
                        ? root.get("structure_base_score").getAsDouble()
                        : 500.0;

                FactionDefinition def = new FactionDefinition(
                        factionId,
                        nextIndex++,
                        translationKey,
                        color,
                        minScore,
                        isTag,
                        notTag,
                        entityTags,
                        entityTypes,
                        blockWeights,
                        blockTags,
                        structureIds,
                        structureTags,
                        structureBaseScore,
                        attrs,
                        effects
                );

                INDEX_TO_FACTION.add(def);
                ID_TO_FACTION.put(factionId, def);
                if (def.hasAnyBlocks()) {
                    anyCustomBlocksRegistered = true;
                }

                CivilAdventureMod.LOGGER.info("[civil_adventure][Step 1/FactionLoad] 注册派系 [Index {}] ID: {} | 名称: {} | 颜色: #{} | 门槛分: {} | 基础分: {}",
                        def.index(), def.id(), def.nameTranslationKey(), Integer.toHexString(def.colorRgb()), def.minDominanceScore(), def.structureBaseScore());
                CivilAdventureMod.LOGGER.info("  -> 危险方块: {} 个 (含 {} 个标签), 关联结构: {} 个 (含 {} 个标签), 白名单生物: {} 个 (含 {} 个标签), 优势属性: {} 条, 药水: {} 条",
                        blockWeights.size(), blockTags.size(), structureIds.size(), structureTags.size(), entityTypes.size(), entityTags.size(), attrs.size(), effects.size());

            } catch (Exception e) {
                CivilAdventureMod.LOGGER.error("[civil_adventure][Step 1/FactionLoad] 加载数据包派系失败 {}: {}", fileId, e.getMessage(), e);
            }
        }

        CivilAdventureMod.LOGGER.info("[civil_adventure][Step 1/FactionLoad] 派系加载完成，共注册 {} 个生态对抗派系 (自定义方块快筛活跃: {})",
                INDEX_TO_FACTION.size(), anyCustomBlocksRegistered);
    }

    private static void parseBlockSpec(String bSpec, double w, Map<Block, Double> blockWeights, List<TagKey<Block>> blockTags) {
        if (bSpec == null || bSpec.isEmpty()) return;
        if (bSpec.startsWith("#")) {
            String tagStr = bSpec.substring(1);
            blockTags.add(TagKey.create(Registries.BLOCK, new ResourceLocation(tagStr)));
        } else {
            ResourceLocation bid = new ResourceLocation(bSpec);
            if (BuiltInRegistries.BLOCK.containsKey(bid)) {
                blockWeights.put(BuiltInRegistries.BLOCK.get(bid), w);
            }
        }
    }

    public static FactionDefinition getByIndex(byte index) {
        if (index >= 0 && index < INDEX_TO_FACTION.size()) {
            return INDEX_TO_FACTION.get(index);
        }
        return null;
    }

    public static FactionDefinition getById(ResourceLocation id) {
        return ID_TO_FACTION.get(id);
    }

    public static int getFactionCount() {
        return INDEX_TO_FACTION.size();
    }

    public static List<FactionDefinition> getAllFactions() {
        return Collections.unmodifiableList(INDEX_TO_FACTION);
    }

    public static boolean hasCustomBlocks() {
        return anyCustomBlocksRegistered;
    }

    /** 是否有任意派系对此方块有分值权重 */
    public static boolean isAnyFactionBlock(Block block) {
        for (FactionDefinition faction : INDEX_TO_FACTION) {
            if (faction.hasBlockWeight(block)) {
                return true;
            }
        }
        return false;
    }

    /** 是否有任意派系对此方块状态有分值权重（用于调色板 maybeHas 快速过滤） */
    public static boolean isAnyFactionBlock(BlockState state) {
        return state != null && isAnyFactionBlock(state.getBlock());
    }
}
