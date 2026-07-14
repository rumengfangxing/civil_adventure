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
            .define("color", "CC1111");

        builder.pop();

        builder.comment("Xaeros 透明度");
        builder.push("xaeros_alpha");

        XAEROS_ALPHA = builder
            .comment("冒险区不透明度 (0-255, 255=完全不透明)")
            .defineInRange("alpha", 180, 0, 255);

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
