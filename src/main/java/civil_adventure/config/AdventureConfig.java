package civil_adventure.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class AdventureConfig {
    public static final ForgeConfigSpec.IntValue DETECTION_RADIUS;
    public static final ForgeConfigSpec.DoubleValue NORMALIZATION_DIVISOR;
    public static final ForgeConfigSpec.DoubleValue ZONE_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue CIV_OVERRIDE_THRESHOLD;
    public static final ForgeConfigSpec.IntValue SCAN_INTERVAL;
    public static final ForgeConfigSpec.IntValue BUFF_TIMEOUT;
    public static final ForgeConfigSpec.ConfigValue<String> ADVENTURE_FILL_COLOR;
    public static final ForgeConfigSpec.BooleanValue ADVENTURE_HUD_ENABLED;
    public static final ForgeConfigSpec.IntValue XAEROS_ALPHA;
    public static final ForgeConfigSpec.DoubleValue MAP_OVERLAY_OPACITY;
    public static final ForgeConfigSpec.BooleanValue WORLDMAP_SHADER_ENABLED;
    public static final ForgeConfigSpec.IntValue SCOUT_RADIUS_CHUNKS;
    public static final ForgeConfigSpec.ConfigValue<String> DEFAULT_PROFILE_ID;
    public static final ForgeConfigSpec.IntValue RECALC_COOLDOWN;
    public static final ForgeConfigSpec.DoubleValue SUPPRESS_PERCENT;
    public static final ForgeConfigSpec.DoubleValue SUPPRESS_FIXED;
    public static final ForgeConfigSpec.IntValue SUPPRESS_INTERVAL;
    public static final ForgeConfigSpec.IntValue SUPPRESS_RADIUS;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> IGNORED_FACTIONS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("冒险区检测范围 — 仿文礼 detectionRadius");
        builder.push("detection");

        DETECTION_RADIUS = builder
            .comment("冒险区判定半径（区块），3 = 7×7 区块区域")
            .defineInRange("radius_chunks", 3, 1, 16);

        builder.pop();

        builder.comment("分数计算");
        builder.push("scoring");

        NORMALIZATION_DIVISOR = builder
            .comment("归一化除数（仿文礼 normalizationFactor），原始分 ÷ 此值 = 归一化分")
            .defineInRange("normalization_divisor", 5.0, 0.1, 1000.0);

        ZONE_THRESHOLD = builder
            .comment("冒险区触发阈值（归一化分 ≥ 此值即视为冒险区）")
            .defineInRange("zone_threshold", 0.7, 0.01, 1.0);

        CIV_OVERRIDE_THRESHOLD = builder
            .comment("覆盖文礼文明区的阈值（归一化分），0=任何方块存在即覆盖")
            .defineInRange("civ_override_threshold", 0.7, 0.0, 1.0);

        builder.pop();

        builder.comment("Xaeros 小地图冒险区颜色");
        builder.push("xaeros_color");

        ADVENTURE_FILL_COLOR = builder
            .comment("冒险区色 (hex RGB)")
            .define("color", "F08252");

        builder.pop();

        builder.comment("Xaeros 透明度与大地图遮罩");
        builder.push("xaeros_alpha");

        XAEROS_ALPHA = builder
            .comment("冒险区小地图不透明度 (0-255, 255=完全不透明，消去地表可视性)")
            .defineInRange("alpha", 255, 0, 255);

        MAP_OVERLAY_OPACITY = builder
            .comment("战术大地图 CoreShader 遮罩不透明度 (0.0-1.0, 1.0=完全实心不透明消去地形可视性)")
            .defineInRange("map_overlay_opacity", 1.0, 0.0, 1.0);

        builder.pop();

        builder.comment("大地图 CoreShader 战术覆层");
        builder.push("map_overlay");

        WORLDMAP_SHADER_ENABLED = builder
            .comment("是否启用 Xaero 大地图 GPU 着色器覆层")
            .define("enabled", true);

        SCOUT_RADIUS_CHUNKS = builder
            .comment("玩家走过时探明周围战争迷雾的半径（区块），4 = 9x9 区块探明范围")
            .defineInRange("scout_radius_chunks", 4, 1, 16);

        DEFAULT_PROFILE_ID = builder
            .comment("默认覆层主题预设 ID (如 civil_adventure:cyber_warning)")
            .define("default_profile", "civil_adventure:cyber_warning");

        builder.pop();

        builder.comment("界面提示");
        builder.push("hud");

        ADVENTURE_HUD_ENABLED = builder
            .comment("进入/离开冒险区时显示提示")
            .define("enabled", true);

        builder.pop();

        builder.comment("实体强化扫描");
        builder.push("entity_scan");

        SCAN_INTERVAL = builder
            .comment("实体检测间隔（tick），20 tick = 1 秒")
            .defineInRange("interval_ticks", 100, 20, 12000);

        BUFF_TIMEOUT = builder
            .comment("离开冒险区后属性保留时间（tick），到期回收")
            .defineInRange("buff_timeout_ticks", 200, 20, 12000);

        RECALC_COOLDOWN = builder
            .comment("方块变化批量重算冷却（tick），冷却期内收集变化、到期一次性算完")
            .defineInRange("recalc_cooldown_ticks", 5, 1, 100);

        builder.pop();

        builder.comment("冒险区压制方块");
        builder.push("suppressor");

        SUPPRESS_PERCENT = builder
            .comment("每次压制削减的百分比（0.01 = 1%）")
            .defineInRange("percent", 0.01, 0.0, 1.0);

        SUPPRESS_FIXED = builder
            .comment("每次压制削减的固定分数")
            .defineInRange("fixed", 3.0, 0.0, 1000.0);

        SUPPRESS_INTERVAL = builder
            .comment("压制动作间隔（tick），20 tick = 1 秒")
            .defineInRange("interval_ticks", 100, 10, 12000);

        SUPPRESS_RADIUS = builder
            .comment("压制影响半径（区块）")
            .defineInRange("radius_chunks", 4, 1, 32);

        IGNORED_FACTIONS = builder
            .comment("压制方块忽略的派系白名单（不压制这些派系，如特定友好生态）")
            .defineListAllowEmpty("ignored_factions", java.util.List.of(), o -> o instanceof String);

        builder.pop();

        SPEC = builder.build();
    }

    public static final ForgeConfigSpec SPEC;

    /** 将 hex 色字符串 (#RRGGBB 或 RRGGBB) 转为 int ARGB */
    public static int parseHexColor(String hex) {
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() == 6) hex = "FF" + hex;
        return (int) (Long.parseLong(hex, 16) & 0xFFFFFFFFL);
    }
}
