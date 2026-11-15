package civil_adventure.data;

import civil_adventure.CivilAdventureMod;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 压制方块燃料加载器。
 * data/<namespace>/adventure_suppressor_fuel/*.json
 * 格式: { "entries": [ { "item": "minecraft:emerald", "ticks": 1200 } ] }
 */
public final class AdventureFuelLoader {
    private static final String DATA_PATH = "adventure_suppressor_fuel";
    private static volatile Map<String, Integer> fuelTicks = new ConcurrentHashMap<>();

    private AdventureFuelLoader() {
    }

    public static void reload(ResourceManager manager) {
        if (manager == null) return;

        ConcurrentHashMap<String, Integer> accumulated = new ConcurrentHashMap<>();
        Map<ResourceLocation, Resource> resources = manager.listResources(DATA_PATH,
            id -> id.getPath().endsWith(".json"));

        List<Map.Entry<ResourceLocation, Resource>> sortedEntries = new ArrayList<>(resources.entrySet());
        sortedEntries.sort(Map.Entry.comparingByKey());

        for (Map.Entry<ResourceLocation, Resource> entry : sortedEntries) {
            try (InputStream is = entry.getValue().open();
                 InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                boolean replace = root.has("replace") && root.get("replace").getAsBoolean();
                if (replace) accumulated.clear();

                if (root.has("entries") && root.get("entries").isJsonArray()) {
                    for (JsonElement elem : root.getAsJsonArray("entries")) {
                        JsonObject obj = elem.getAsJsonObject();
                        String itemId = obj.get("item").getAsString();
                        int ticks = obj.has("ticks") ? obj.get("ticks").getAsInt() : 0;
                        if (ticks > 0) {
                            accumulated.put(itemId, ticks);
                        }
                    }
                }
            } catch (Exception e) {
                CivilAdventureMod.LOGGER.warn("加载压制燃料失败 {}: {}", entry.getKey(), e.getMessage());
            }
        }

        fuelTicks = accumulated;
        CivilAdventureMod.LOGGER.info("加载了 {} 种压制燃料", accumulated.size());
    }

    /** 返回该物品作为燃料提供的 tick，0 = 不是燃料 */
    public static int getFuelTicks(Item item) {
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        if (id == null) return 0;
        return fuelTicks.getOrDefault(id.toString(), 0);
    }
}
