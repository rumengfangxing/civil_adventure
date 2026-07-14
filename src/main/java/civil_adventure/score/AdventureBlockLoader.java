package civil_adventure.score;

import civil_adventure.CivilAdventureMod;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.block.Block;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Map;

public final class AdventureBlockLoader {
    private static final String DATA_PATH = "adventure_blocks";
    private static volatile Map<Block, Double> globalWeights = new IdentityHashMap<>();

    private AdventureBlockLoader() {
    }

    public static void reload(ResourceManager manager) {
        if (manager == null) return;

        IdentityHashMap<Block, Double> accumulated = new IdentityHashMap<>();
        Map<ResourceLocation, Resource> resources = manager.listResources(DATA_PATH,
            id -> id.getPath().endsWith(".json"));

        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            try (InputStream is = entry.getValue().open();
                 InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                boolean replace = root.has("replace") && root.get("replace").getAsBoolean();
                if (replace) accumulated.clear();

                if (root.has("entries")) {
                    for (JsonElement elem : root.getAsJsonArray("entries")) {
                        JsonObject obj = elem.getAsJsonObject();
                        String blockSpec = obj.get("block").getAsString();
                        double weight = obj.get("weight").getAsDouble();
                        if (blockSpec.startsWith("#")) {
                            String tagPath = blockSpec.substring(1);
                            ResourceLocation tagId = new ResourceLocation(tagPath);
                            BuiltInRegistries.BLOCK.getTagOrEmpty(
                                net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, tagId)
                            ).forEach(holder -> accumulated.put(holder.value(), weight));
                        } else {
                            ResourceLocation blockId = new ResourceLocation(blockSpec);
                            if (BuiltInRegistries.BLOCK.containsKey(blockId)) {
                                Block block = BuiltInRegistries.BLOCK.get(blockId);
                                accumulated.put(block, weight);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                CivilAdventureMod.LOGGER.error("加载冒险区方块权重失败 {}: {}", fileId, e.getMessage());
            }
        }

        globalWeights = accumulated;
        CivilAdventureMod.LOGGER.info("加载了 {} 个冒险区方块权重", accumulated.size());
    }

    public static double getWeight(Block block) {
        return globalWeights.getOrDefault(block, 0.0);
    }

    public static boolean hasWeight(Block block) {
        return globalWeights.containsKey(block);
    }
}
