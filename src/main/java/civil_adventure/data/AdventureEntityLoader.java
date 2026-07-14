package civil_adventure.data;

import civil_adventure.CivilAdventureMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AdventureEntityLoader {
    private static final String DATA_PATH = "adventure_entities";
    // 实体ID字面量 → EntityConfig（键如 "minecraft:zombie"）
    private static volatile Map<String, EntityConfig> configs = new ConcurrentHashMap<>();

    private AdventureEntityLoader() {
    }

    public static void reload(ResourceManager manager) {
        if (manager == null) return;

        ConcurrentHashMap<String, EntityConfig> accumulated = new ConcurrentHashMap<>();
        Map<ResourceLocation, Resource> resources = manager.listResources(DATA_PATH,
            id -> id.getPath().endsWith(".json"));

        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            // data/<ns>/adventure_entities/minecraft.zombie.json → minecraft:zombie
            String path = fileId.getPath();
            String entityId = path.substring(path.lastIndexOf('/') + 1)
                .replace(".json", "")
                .replace('.', ':');

            try (InputStream is = entry.getValue().open();
                 InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                List<EntityConfig.AttrMod> attrs = new ArrayList<>();
                List<EntityConfig.EffectDef> effects = new ArrayList<>();

                if (root.has("attributes") && root.get("attributes").isJsonArray()) {
                    for (JsonElement ae : root.getAsJsonArray("attributes")) {
                        JsonObject ao = ae.getAsJsonObject();
                        String attrId = ao.get("attribute").getAsString();
                        double modifier = ao.get("modifier").getAsDouble();
                        int op = ao.has("operation") ? ao.get("operation").getAsInt() : 0;
                        AttributeModifier.Operation oper = switch (op) {
                            case 1 -> AttributeModifier.Operation.MULTIPLY_BASE;
                            case 2 -> AttributeModifier.Operation.MULTIPLY_TOTAL;
                            default -> AttributeModifier.Operation.ADDITION;
                        };
                        attrs.add(new EntityConfig.AttrMod(attrId, modifier, oper));
                    }
                }

                if (root.has("effects") && root.get("effects").isJsonArray()) {
                    for (JsonElement ee : root.getAsJsonArray("effects")) {
                        JsonObject eo = ee.getAsJsonObject();
                        String effId = eo.get("effect").getAsString();
                        int duration = eo.has("duration") ? eo.get("duration").getAsInt() : 100;
                        int amplifier = eo.has("amplifier") ? eo.get("amplifier").getAsInt() : 0;
                        effects.add(new EntityConfig.EffectDef(effId, duration, amplifier));
                    }
                }

                if (!attrs.isEmpty() || !effects.isEmpty()) {
                    accumulated.put(entityId, new EntityConfig(attrs, effects));
                }
            } catch (Exception e) {
                CivilAdventureMod.LOGGER.error("加载冒险区实体配置失败 {}: {}", fileId, e.getMessage());
            }
        }

        configs = accumulated;
        CivilAdventureMod.LOGGER.info("加载了 {} 个冒险区实体配置", accumulated.size());
    }

    public static EntityConfig getConfig(String entityId) {
        return configs.get(entityId);
    }

    public static boolean hasConfig(String entityId) {
        return configs.containsKey(entityId);
    }
}
