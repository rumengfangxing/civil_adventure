package civil_adventure.client.profile;

import civil_adventure.CivilAdventureMod;
import civil_adventure.config.AdventureConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战术地图覆层预设注册中心与匹配引擎 (Overlay Profile Registry & Matcher)
 * 支持默认内置预设、本地 config/civil_adventure/profiles/ 目录动态热读与规则匹配。
 * [修复 C-03 & M-01] 零堆分配查找，支持派系翻译键与短名双向匹配，预排序缓存与确定性文件加载。
 */
public final class OverlayProfileRegistry {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, OverlayProfile> PROFILES = new ConcurrentHashMap<>();
    private static volatile List<OverlayProfile> SORTED_PROFILES = Collections.emptyList();
    private static OverlayProfile DEFAULT_PROFILE;

    private OverlayProfileRegistry() {}

    public static void init() {
        initDefaults();
        loadFromDisk();
        rebuildSortedCache();
    }

    public static void register(OverlayProfile profile) {
        if (profile == null || profile.id == null) return;
        PROFILES.put(profile.id, profile);
        rebuildSortedCache();
    }

    private static void rebuildSortedCache() {
        List<OverlayProfile> list = new ArrayList<>(PROFILES.values());
        list.sort(Comparator.comparingInt((OverlayProfile p) -> p.priority).reversed());
        SORTED_PROFILES = Collections.unmodifiableList(list);
    }

    private static void initDefaults() {
        // 1. 默认赛博警告/404 故障预设 (核定核心方案 A)
        List<OverlayProfile.PhaseConfig> cyberPhases = List.of(
            new OverlayProfile.PhaseConfig("warning", 3.0f,
                    new ResourceLocation(CivilAdventureMod.MOD_ID, "textures/map/adventure_phase1.png"),
                    "NONE", 0.0f),
            new OverlayProfile.PhaseConfig("glitch", 1.5f,
                    new ResourceLocation(CivilAdventureMod.MOD_ID, "textures/map/adventure_phase2.png"),
                    "FULL_GLITCH", 1.0f),
            new OverlayProfile.PhaseConfig("corrupt_404", 2.5f,
                    new ResourceLocation(CivilAdventureMod.MOD_ID, "textures/map/adventure_phase3.png"),
                    "NONE", 0.0f)
        );

        DEFAULT_PROFILE = new OverlayProfile(
                "civil_adventure:cyber_warning", 0, List.of(), List.of(),
                0.0, 9999.0, 1.0f, 0xFF0F0A0C, "SOLID", 1.5f,
                true, 0xFFE61919, 0.02f, "SOLID", 2.0f,
                true, 0.75f, true, "NORMAL",
                cyberPhases, 40.0f, 12.0f, 0.08f, 0.015f, 8.0f,
                0xFFFF00FF, 0xFF000000, true, true, null, false
        );
        PROFILES.put(DEFAULT_PROFILE.id, DEFAULT_PROFILE);

        // 2. 生化真菌异变预设 (Fungal / Spore)
        List<OverlayProfile.PhaseConfig> fungalPhases = List.of(
            new OverlayProfile.PhaseConfig("bio_hazard", 3.5f,
                    new ResourceLocation(CivilAdventureMod.MOD_ID, "textures/map/adventure_phase1.png"),
                    "BIO_WAVE", 0.8f),
            new OverlayProfile.PhaseConfig("cell_decay", 2.0f,
                    new ResourceLocation(CivilAdventureMod.MOD_ID, "textures/map/adventure_phase2.png"),
                    "FULL_GLITCH", 1.2f)
        );
        OverlayProfile fungal = new OverlayProfile(
                "civil_adventure:fungal_spore", 10, List.of(), List.of("fungal", "spore"),
                0.0, 9999.0, 1.0f, 0xFF0A1A0D, "SOLID", 1.5f,
                true, 0xFF33CC55, 0.025f, "PULSE", 1.5f,
                true, 0.75f, true, "NORMAL",
                fungalPhases, 30.0f, 8.0f, 0.06f, 0.02f, 6.0f,
                0xFF22FF66, 0xFF051508, true, true, null, false
        );
        PROFILES.put(fungal.id, fungal);

        // 3. 虚空/末地异象预设 (Void / End)
        List<OverlayProfile.PhaseConfig> voidPhases = List.of(
            new OverlayProfile.PhaseConfig("void_whisper", 3.0f,
                    new ResourceLocation(CivilAdventureMod.MOD_ID, "textures/map/adventure_phase3.png"),
                    "STATIC_NOISE", 0.85f),
            new OverlayProfile.PhaseConfig("reality_tear", 1.5f,
                    new ResourceLocation(CivilAdventureMod.MOD_ID, "textures/map/adventure_phase2.png"),
                    "CHROMATIC", 1.5f)
        );
        OverlayProfile voidProfile = new OverlayProfile(
                "civil_adventure:void_corruption", 10, List.of("minecraft:the_end"), List.of(),
                0.0, 9999.0, 1.0f, 0xFF0C0414, "SOLID", 1.5f,
                true, 0xFFB026FF, 0.02f, "DASHED", 2.0f,
                true, 0.75f, true, "NORMAL",
                voidPhases, 50.0f, 15.0f, 0.05f, 0.03f, 10.0f,
                0xFFC040FF, 0xFF000000, true, true, null, false
        );
        PROFILES.put(voidProfile.id, voidProfile);
    }

    public static void loadFromDisk() {
        try {
            Path profileDir = FMLPaths.CONFIGDIR.get().resolve("civil_adventure").resolve("profiles");
            if (!Files.exists(profileDir)) {
                Files.createDirectories(profileDir);
                exportExampleProfile(profileDir);
            }

            File[] files = profileDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) return;

            // [修复 W-02] 确定性排序，防止不同操作系统下 JSON 加载覆盖顺序随机
            Arrays.sort(files, Comparator.comparing(File::getName));

            for (File file : files) {
                try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    String fallbackId = "custom:" + file.getName().replace(".json", "");
                    OverlayProfile profile = OverlayProfile.fromJson(json, fallbackId);
                    PROFILES.put(profile.id, profile);
                    CivilAdventureMod.LOGGER.info("[CivilAdventure] Loaded custom overlay profile: {}", profile.id);
                } catch (Exception e) {
                    CivilAdventureMod.LOGGER.error("[CivilAdventure] Failed to parse profile file: {}", file.getName(), e);
                }
            }
        } catch (Exception e) {
            CivilAdventureMod.LOGGER.error("[CivilAdventure] Failed to access profiles directory", e);
        }
    }

    private static void exportExampleProfile(Path dir) {
        try {
            Path readme = dir.resolve("README_PROFILES.txt");
            String doc = """
                    === Civil Adventure 战术地图覆层预设系统 (Overlay Profiles) ===
                    您可以在此目录创建任意 *.json 文件来定义不同区域/派系/维度的自定义覆层样式。
                    支持全套可选参数：
                    - id: 预设唯一标识
                    - priority: 优先级权重 (越大越优先)
                    - dimensions: 维度白名单 ["minecraft:overworld", "minecraft:the_nether"] (留空为全维度通用)
                    - factions: 派系白名单 ["fungal", "spore"] (留空为不限派系)
                    - overlay_opacity: 遮罩不透明度 (0.0 ~ 1.0, 1.0=完全遮蔽消去下层地形)
                    - background_color: 遮罩底色 hex (如 "#FF0F0A0C")
                    - border_enabled: true/false
                    - border_color: 边框色 hex (如 "#FFE61919")
                    - border_style: "SOLID", "DASHED", "PULSE", "CORNER"
                    - phases: 阶段列表，每个阶段包含 duration_seconds, texture, glitch_mode, glitch_intensity
                    """;
            Files.writeString(readme, doc, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    /**
     * [修复 C-03 & M-01] 零堆分配规则匹配器：
     * 1. 使用预排序的 SORTED_PROFILES，杜绝高频 new ArrayList 与 sort
     * 2. 派系名称双向归一化匹配，完美支持 "civil_adventure.faction.fungal.name" 与短名 "fungal"
     * 3. 尊重客户端配置的 defaultProfileId
     */
    public static OverlayProfile findProfile(ResourceLocation dim, String faction, double score) {
        String dimStr = dim != null ? dim.toString() : "";
        String normalizedFaction = normalizeFactionKey(faction);

        for (OverlayProfile p : SORTED_PROFILES) {
            // 1. 分数范围过滤
            if (score < p.minScore || score > p.maxScore) {
                continue;
            }
            // 2. 维度过滤
            if (!p.dimensions.isEmpty() && !p.dimensions.contains(dimStr)) {
                continue;
            }
            // 3. 派系过滤
            if (!p.factions.isEmpty()) {
                if (normalizedFaction == null) continue;
                boolean matched = false;
                for (String configuredFaction : p.factions) {
                    String normConfig = normalizeFactionKey(configuredFaction);
                    if (normalizedFaction.equalsIgnoreCase(normConfig)
                            || (faction != null && faction.toLowerCase(Locale.ROOT).contains(configuredFaction.toLowerCase(Locale.ROOT)))) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) continue;
            }
            return p;
        }

        try {
            String configuredDefaultId = AdventureConfig.DEFAULT_PROFILE_ID.get();
            if (configuredDefaultId != null && PROFILES.containsKey(configuredDefaultId)) {
                return PROFILES.get(configuredDefaultId);
            }
        } catch (Throwable ignored) {}

        return DEFAULT_PROFILE;
    }

    public static String normalizeFactionKey(String key) {
        if (key == null || key.isEmpty()) return null;
        String lower = key.toLowerCase(Locale.ROOT);
        // 兼容 "civil_adventure.faction.fungal.name" -> "fungal"
        if (lower.startsWith("civil_adventure.faction.") && lower.endsWith(".name")) {
            return lower.substring("civil_adventure.faction.".length(), lower.length() - ".name".length());
        }
        int colon = lower.indexOf(':');
        if (colon >= 0 && colon + 1 < lower.length()) {
            return lower.substring(colon + 1);
        }
        return lower;
    }

    public static OverlayProfile getDefaultProfile() {
        return DEFAULT_PROFILE;
    }

    public static Map<String, OverlayProfile> getAllProfiles() {
        return Collections.unmodifiableMap(PROFILES);
    }
}
